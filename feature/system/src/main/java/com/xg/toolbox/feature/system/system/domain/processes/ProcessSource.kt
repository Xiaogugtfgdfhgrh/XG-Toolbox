package com.xg.toolbox.feature.system.domain.processes

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ProcessSource(private val shell: Shell) {

    
    suspend fun load(myUid: Int): XgResult<ProcessLoad> = withContext(Dispatchers.IO) {
        val primary = shell.exec(PRIMARY_COMMAND, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (primary.code >= SYNTHETIC_ERROR_FLOOR && primary.stdout.isBlank()) {
            return@withContext XgResult.Err(
                code = primary.code,
                message = "无法执行进程列表命令（退出码 ${primary.code}）",
                detail = firstLine(primary.stderr),
            )
        }
        val sections = splitSections(primary.stdout)
        val bootTimeSec = sections.getOrElse(0) { "" }
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("btime ") }
            ?.removePrefix("btime ")
            ?.trim()
            ?.toLongOrNull()
        val enrichment = parseEnrichment(sections.getOrElse(1) { "" }, bootTimeSec)
        val psRows = parsePs(sections.getOrElse(2) { "" })
        val psTotalCount = sections.getOrElse(3) { "" }.trim().toIntOrNull()?.let { (it - 1).coerceAtLeast(0) }
        if (psRows.size >= MIN_PS_ROWS) {
            return@withContext buildLoad(
                rows = psRows,
                enrichment = enrichment.entries,
                bootTimeSec = enrichment.bootTimeSec,
                psTotalCount = psTotalCount,
                usedProcFallback = false,
                myUid = myUid,
            )
        }
        val fallback = shell.exec(PROC_FALLBACK_COMMAND, root = true, timeoutMs = FALLBACK_TIMEOUT_MS)
        if (fallback.code >= SYNTHETIC_ERROR_FLOOR || fallback.stdout.isBlank()) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "无法读取进程列表（ps 与 /proc 回退均失败）",
                detail = firstLine(fallback.stderr.ifBlank { primary.stderr }),
            )
        }
        val fallbackSections = splitSections(fallback.stdout)
        val fallbackParsed = parseProcFallback(
            btimeSection = fallbackSections.getOrElse(0) { "" },
            pageSizeSection = fallbackSections.getOrElse(1) { "" },
            procSection = fallbackSections.getOrElse(2) { "" },
        )
        if (fallbackParsed.rows.isEmpty()) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "无法解析 /proc 进程数据",
            )
        }
        buildLoad(
            rows = fallbackParsed.rows,
            enrichment = fallbackParsed.enrichment,
            bootTimeSec = fallbackParsed.bootTimeSec,
            psTotalCount = null,
            usedProcFallback = true,
            myUid = myUid,
        )
    }

    private fun buildLoad(
        rows: List<PsRow>,
        enrichment: Map<Int, ProcEnrichment>,
        bootTimeSec: Long?,
        psTotalCount: Int?,
        usedProcFallback: Boolean,
        myUid: Int,
    ): XgResult<ProcessLoad> {
        var noise = 0
        val items = mutableListOf<ProcessItem>()
        rows.forEach { row ->
            if (isSelfNoise(row)) {
                noise++
                return@forEach
            }
            val enriched = enrichment[row.pid]
            val uid = enriched?.uid ?: UNKNOWN_UID
            val oomAdj = enriched?.oomAdj ?: ProcessItem.NO_OOM_ADJ
            val group = classify(row = row, uid = uid, oomAdj = oomAdj)
            val (protected, reason) = protection(
                pid = row.pid,
                name = row.name,
                uid = uid,
                group = group,
                myUid = myUid,
            )
            val startTimeMs = if (bootTimeSec != null && enriched != null) {
                bootTimeSec * 1000L + enriched.startTicks * TICK_MS
            } else {
                null
            }
            items += ProcessItem(
                pid = row.pid,
                ppid = row.ppid,
                uid = uid,
                user = row.user,
                name = row.name,
                cmdline = row.cmdline,
                rssKb = row.rssKb,
                vszKb = row.vszKb,
                stat = row.stat,
                threads = enriched?.threads ?: 0,
                oomAdj = oomAdj,
                group = group,
                protected = protected,
                protectedReason = reason,
                selinux = enriched?.selinux,
                startTimeMs = startTimeMs,
                cgroup = enriched?.cgroup,
            )
        }
        val sorted = items.sortedWith(
            compareBy<ProcessItem> { it.group.ordinal }.thenBy { it.pid },
        )
        return XgResult.Ok(
            ProcessLoad(
                processes = sorted,
                bootTimeSec = bootTimeSec,
                psTotalCount = psTotalCount,
                filteredNoise = noise,
                usedProcFallback = usedProcFallback,
            ),
        )
    }

    
    private fun parsePs(section: String): List<PsRow> {
        if (section.isBlank()) return emptyList()
        val lines = section.lineSequence().map { it.trimEnd('\r') }.toList()
        val headerIndex = lines.indexOfFirst { line ->
            val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            tokens.isNotEmpty() && tokens[0] == "PID" && tokens.contains("NAME")
        }
        if (headerIndex < 0) return emptyList()
        val rows = mutableListOf<PsRow>()
        lines.drop(headerIndex + 1).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val tokens = trimmed.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size < PS_FIXED_COLUMNS) return@forEach
            val pid = tokens[0].toIntOrNull() ?: return@forEach
            val ppid = tokens[1].toIntOrNull() ?: return@forEach
            rows += PsRow(
                pid = pid,
                ppid = ppid,
                user = tokens[2],
                vszKb = tokens[3].toLongOrNull() ?: 0L,
                rssKb = tokens[4].toLongOrNull() ?: 0L,
                stat = tokens[5],
                name = tokens[10],
                cmdline = if (tokens.size > PS_FIXED_COLUMNS) {
                    tokens.subList(PS_FIXED_COLUMNS, tokens.size).joinToString(" ")
                } else {
                    ""
                },
            )
        }
        return rows
    }

    private data class EnrichmentParse(
        val bootTimeSec: Long?,
        val entries: Map<Int, ProcEnrichment>,
    )

    
    private fun parseEnrichment(section: String, bootTimeSec: Long?): EnrichmentParse {
        val entries = mutableMapOf<Int, ProcEnrichment>()
        section.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size < ENRICH_FIELDS) return@forEach
            val pid = tokens[0].toIntOrNull() ?: return@forEach
            entries[pid] = ProcEnrichment(
                pid = pid,
                oomAdj = tokens[1].toIntOrNull() ?: ProcessItem.NO_OOM_ADJ,
                uid = tokens[2].toIntOrNull() ?: UNKNOWN_UID,
                state = tokens[3],
                ppid = tokens[4].toIntOrNull() ?: 0,
                utimeTicks = tokens[5].toLongOrNull() ?: 0L,
                stimeTicks = tokens[6].toLongOrNull() ?: 0L,
                threads = tokens[7].toIntOrNull() ?: 0,
                startTicks = tokens[8].toLongOrNull() ?: 0L,
                selinux = tokens[9].takeIf { it != EMPTY_FIELD },
                cgroup = tokens.drop(10).joinToString(" ").takeIf { it.isNotEmpty() && it != EMPTY_FIELD },
            )
        }
        return EnrichmentParse(bootTimeSec = bootTimeSec, entries = entries)
    }

    private data class ProcFallbackParse(
        val bootTimeSec: Long?,
        val rows: List<PsRow>,
        val enrichment: Map<Int, ProcEnrichment>,
    )

    
    private fun parseProcFallback(
        btimeSection: String,
        pageSizeSection: String,
        procSection: String,
    ): ProcFallbackParse {
        val bootTimeSec = btimeSection.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("btime ") }
            ?.removePrefix("btime ")
            ?.trim()
            ?.toLongOrNull()
        val pageSize = pageSizeSection.trim().toIntOrNull()?.takeIf { it in 1024..65536 } ?: DEFAULT_PAGE_SIZE
        val pageToKb = pageSize / 1024L
        val trailingFields = 9
        val rows = mutableListOf<PsRow>()
        val enrichment = mutableMapOf<Int, ProcEnrichment>()
        procSection.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size < 4 + 1 + trailingFields) return@forEach
            val pid = tokens[0].toIntOrNull() ?: return@forEach
            val ppid = tokens[1].toIntOrNull() ?: return@forEach
            val uid = tokens[2].toIntOrNull() ?: UNKNOWN_UID
            val state = tokens[3]
            val tail = tokens.size - trailingFields
            val name = tokens[4]
            val cmdline = if (tail > 5) tokens.subList(5, tail).joinToString(" ") else ""
            val vszPages = tokens[tail].toLongOrNull() ?: 0L
            val rssPages = tokens[tail + 1].toLongOrNull() ?: 0L
            val oomAdj = tokens[tail + 2].toIntOrNull() ?: ProcessItem.NO_OOM_ADJ
            val utime = tokens[tail + 3].toLongOrNull() ?: 0L
            val stime = tokens[tail + 4].toLongOrNull() ?: 0L
            val threads = tokens[tail + 5].toIntOrNull() ?: 0
            val startTicks = tokens[tail + 6].toLongOrNull() ?: 0L
            val selinux = tokens[tail + 7].takeIf { it != EMPTY_FIELD }
            val cgroup = tokens[tail + 8].takeIf { it != EMPTY_FIELD }
            rows += PsRow(
                pid = pid,
                ppid = ppid,
                user = when (uid) {
                    0 -> "root"
                    else -> "uid:$uid"
                },
                vszKb = vszPages * pageToKb,
                rssKb = rssPages * pageToKb,
                stat = state,
                name = name,
                cmdline = cmdline,
            )
            enrichment[pid] = ProcEnrichment(
                pid = pid,
                oomAdj = oomAdj,
                uid = uid,
                state = state,
                ppid = ppid,
                utimeTicks = utime,
                stimeTicks = stime,
                threads = threads,
                startTicks = startTicks,
                selinux = selinux,
                cgroup = cgroup,
            )
        }
        return ProcFallbackParse(bootTimeSec = bootTimeSec, rows = rows, enrichment = enrichment)
    }

    private fun classify(row: PsRow, uid: Int, oomAdj: Int): ProcessGroup {
        val looksLikeKernelName = row.name.startsWith("[") && row.name.endsWith("]")
        val kernel = row.pid <= 2 || row.ppid == 2 || (row.cmdline.isEmpty() && looksLikeKernelName)
        if (kernel) return ProcessGroup.KERNEL
        if (uid in 0 until SYSTEM_UID_LIMIT) return ProcessGroup.SYSTEM
        if (oomAdj == ProcessItem.NO_OOM_ADJ) return ProcessGroup.CACHED
        return when {
            oomAdj <= FOREGROUND_ADJ_LIMIT -> ProcessGroup.FOREGROUND
            oomAdj <= VISIBLE_ADJ_LIMIT -> ProcessGroup.VISIBLE
            oomAdj <= SERVICE_ADJ_LIMIT -> ProcessGroup.SERVICE
            else -> ProcessGroup.CACHED
        }
    }

    
    private fun protection(
        pid: Int,
        name: String,
        uid: Int,
        group: ProcessGroup,
        myUid: Int,
    ): Pair<Boolean, String?> = when {
        pid <= 2 -> true to "内核线程/init，禁止结束"
        name == "kthreadd" -> true to "kthreadd 内核线程，禁止结束"
        pid == 1 || name == "init" -> true to "init 进程，禁止结束"
        name == "system_server" -> true to "system_server 关键进程，禁止结束"
        myUid > 0 && uid == myUid -> true to "本应用进程，禁止结束"
        group == ProcessGroup.KERNEL -> true to "内核线程不可结束"
        else -> false to null
    }

    
    private fun isSelfNoise(row: PsRow): Boolean {
        val cmd = row.cmdline
        if (cmd.isEmpty()) return false
        if (cmd.contains("ps -A -o PID")) return true
        if (cmd.contains("ps -A | wc -l")) return true
        if (cmd.contains("/proc/[0-9]") && cmd.contains("oom_score_adj")) return true
        if (row.name == "ps" && cmd.startsWith("ps -A")) return true
        if (row.name == "wc" && cmd.trim() == "wc -l") return true
        return false
    }

    private fun splitSections(output: String): List<String> {
        val sections = mutableListOf<String>()
        val builder = StringBuilder()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.trim() == SECTION_MARKER) {
                sections += builder.toString()
                builder.setLength(0)
            } else {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(line)
            }
        }
        sections += builder.toString()
        return sections
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

    companion object {
        private const val SECTION_MARKER = "@@"
        private const val SYNTHETIC_ERROR_FLOOR = 1000
        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val FALLBACK_TIMEOUT_MS = 90_000L
        private const val MIN_PS_ROWS = 5
        private const val PS_FIXED_COLUMNS = 11
        private const val ENRICH_FIELDS = 10
        private const val UNKNOWN_UID = -1
        private const val TICK_MS = 10L
        private const val DEFAULT_PAGE_SIZE = 4096
        private const val EMPTY_FIELD = "-"
        private const val SYSTEM_UID_LIMIT = 10_000
        private const val FOREGROUND_ADJ_LIMIT = 0
        private const val VISIBLE_ADJ_LIMIT = 200
        private const val SERVICE_ADJ_LIMIT = 700

        
        private val ENRICH_PART: String =
            "grep btime /proc/stat; echo " + SECTION_MARKER + "; " +
                "for d in /proc/[0-9]*; do p=\${d#/proc/}; s=; read -r s < \$d/stat 2>/dev/null; " +
                "[ -n \"\$s\" ] || continue; a=-32768; read -r a < \$d/oom_score_adj 2>/dev/null; " +
                "u=-1; while read -r k v rest; do case \$k in Uid:*) u=\$v; break;; esac; done < \$d/status 2>/dev/null; " +
                "r=\${s##*) }; set -- \$r; st=\$1; ppid=\$2; ut=\${12}; stt=\${13}; th=\${18}; start=\${20}; " +
                "sl=-; t=; read -r t < \$d/attr/current 2>/dev/null; [ -n \"\$t\" ] && sl=\$t; " +
                "cg=-; t=; read -r t < \$d/cgroup 2>/dev/null; [ -n \"\$t\" ] && cg=\$t; " +
                "echo \$p \$a \$u \$st \$ppid \$ut \$stt \$th \$start \$sl \$cg; done"

        
        private val PRIMARY_COMMAND: String = buildString {
            append(ENRICH_PART)
            append("; echo ").append(SECTION_MARKER).append("; ")
            append("ps -A -o PID,PPID,USER,VSZ,RSS,STAT,PRI,NI,TIME,ELAPSED,NAME,ARGS")
            append("; echo ").append(SECTION_MARKER).append("; ps -A | wc -l")
        }

        
        private val PROC_FALLBACK_COMMAND: String =
            "grep btime /proc/stat; echo " + SECTION_MARKER + "; " +
                "getconf PAGE_SIZE 2>/dev/null || echo " + DEFAULT_PAGE_SIZE + "; echo " + SECTION_MARKER + "; " +
                "for d in /proc/[0-9]*; do p=\${d#/proc/}; s=; read -r s < \$d/stat 2>/dev/null; " +
                "[ -n \"\$s\" ] || continue; r=\${s##*) }; set -- \$r; st=\$1; ppid=\$2; " +
                "ut=\${12}; stt=\${13}; th=\${18}; start=\${20}; " +
                "a=-32768; read -r a < \$d/oom_score_adj 2>/dev/null; " +
                "u=-1; while read -r k v rest; do case \$k in Uid:*) u=\$v; break;; esac; done < \$d/status 2>/dev/null; " +
                "name=; read -r name < \$d/comm 2>/dev/null; cmd=; read -r cmd < \$d/cmdline 2>/dev/null; " +
                "vszp=0; rssp=0; read -r vszp rssp rest2 < \$d/statm 2>/dev/null; " +
                "sl=-; t=; read -r t < \$d/attr/current 2>/dev/null; [ -n \"\$t\" ] && sl=\$t; " +
                "cg=-; t=; read -r t < \$d/cgroup 2>/dev/null; [ -n \"\$t\" ] && cg=\$t; " +
                "echo \$p \$ppid \$u \$st \$name \$cmd \$vszp \$rssp \$a \$ut \$stt \$th \$start \$sl \$cg; done"

        private val WHITESPACE = Regex("\\s+")
    }
}
