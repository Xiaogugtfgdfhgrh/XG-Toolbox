package com.xg.toolbox.feature.system.domain.flash

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.feature.system.domain.perf.ShellIo
import com.xg.toolbox.core.shell.Shell

internal object FlashPolicy {

    
    val SAFE_BASES: Set<String> = setOf("boot", "init_boot", "vbmeta", "dtbo", "recovery")

    
    private val DANGEROUS_BASES: Set<String> = setOf(
        "super", "userdata", "metadata", "persist", "misc", "frp", "devinfo", "keystore",
        "ssd", "secdata", "storsec", "param", "apdp", "apdp_full", "apdpb",
        "modem", "modemst1", "modemst2", "fsg", "fsc", "mdtp", "mdtpsecapp",
        "nonmodemst1", "nonmodemst2", "dsp", "qmcs",
        "xbl", "xbl_config", "xbl_ramdump", "xbl_sc_logs", "xbl_sc_test_mode",
        "abl", "aop", "aop_config", "tz", "tzsc", "hyp", "keymaster", "devcfg",
        "uefi", "uefisecapp", "uefivarstore", "qupfw", "pvmfw", "vendor_boot",
        "vbmeta_system", "vbmeta_vendor", "vm-bootsys", "vm-data", "vm-persist",
        "preisp_dt", "preisp_otp", "preisp_dt_bk", "shrm", "rtice", "rticmpdata",
        "spuservice", "limits", "limits-cdsp", "cdt", "ocdt", "engineering_cdt",
        "ddr", "dip", "dpm", "driver", "toolsfv", "imagefv", "connsec", "oplus_sec",
        "qweslicstore", "spunvm",
    )

    
    private val EXTENDED_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

    
    @Volatile
    private var enumeratedNames: Set<String> = emptySet()

    
    fun remember(names: Collection<String>) {
        enumeratedNames = names.toSet()
    }

    
    fun baseOf(name: String): String = when {
        name.endsWith("_a") || name.endsWith("_b") -> name.dropLast(2)
        else -> name
    }

    
    fun slotOf(name: String): String? = when {
        name.endsWith("_a") -> "A"
        name.endsWith("_b") -> "B"
        else -> null
    }

    
    fun riskOf(name: String): PartitionRisk {
        val base = baseOf(name).lowercase()
        return when {
            base in SAFE_BASES -> PartitionRisk.SAFE
            base in DANGEROUS_BASES -> PartitionRisk.DANGEROUS
            else -> PartitionRisk.CAUTION
        }
    }

    
    fun isWriteAllowed(name: String): Boolean = isSafeName(name) && name in enumeratedNames

    
    fun riskGate(name: String, force: Boolean): XgResult.Err? {
        if (riskOf(name) != PartitionRisk.DANGEROUS || force) return null
        return XgResult.Err(
            code = ErrorCode.ERR_POLICY_BLOCKED,
            message = "分区 $name 风险等级 DANGEROUS：${riskDetail(name)}。" +
                "如确认继续，请以 force=true 重新发起（默认仍会尽量备份）",
        )
    }

    
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        if (name == "." || name == "..") return false
        if (ShellIo.isSafePartitionName(name)) return true
        return EXTENDED_NAME.matches(name)
    }

    
    fun riskDetail(name: String): String {
        val base = baseOf(name).lowercase()
        return when (base) {
            "super" -> "super 是动态分区容器（system/vendor/product 等），写错会丢失整个系统分区"
            "userdata" -> "userdata 含全部应用数据与内部存储，写入会清空用户数据"
            "metadata" -> "metadata 是加密元数据，写坏可能导致设备无法解密启动"
            "persist" -> "persist 保存传感器校准/DRM 等持久数据，写坏通常不可恢复"
            "misc" -> "misc 保存启动槽与 bootloader 消息，写坏可能导致无法启动或变砖"
            "frp", "devinfo", "ssd", "secdata", "storsec", "keystore" ->
                "该分区保存设备身份/安全状态，写坏可能导致无法开机或安全校验失败"
            "modem", "modemst1", "modemst2", "fsg", "fsc", "mdtp", "mdtpsecapp",
            "nonmodemst1", "nonmodemst2", "dsp", "qmcs", "apdp", "apdp_full", "apdpb" ->
                "基带/调制解调器分区，写坏会导致无信号、IMEI 丢失或无法开机"
            "xbl", "xbl_config", "xbl_ramdump", "abl", "aop", "aop_config", "tz", "tzsc",
            "hyp", "keymaster", "devcfg", "uefi", "uefisecapp", "uefivarstore", "qupfw",
            "pvmfw", "vendor_boot", "vbmeta_system", "vbmeta_vendor", "vm-bootsys",
            "vm-data", "vm-persist", "preisp_dt", "preisp_otp", "preisp_dt_bk", "shrm",
            "rtice", "rticmpdata", "spuservice", "limits", "limits-cdsp", "cdt", "ocdt",
            "engineering_cdt", "ddr", "dip", "dpm", "driver", "toolsfv", "imagefv",
            "connsec", "oplus_sec", "qweslicstore", "spunvm" ->
                "引导链/安全固件，写错可导致设备无法开机（硬砖风险）"
            else -> "该分区含关键数据，写入可能导致数据丢失或无法启动"
        }
    }
}

internal enum class FlashPartitionSource {
    
    BY_NAME,

    
    PROCFS,

    
    NONE,
}

internal data class FlashScan(
    val slotSuffix: String,
    val bootSlotProp: String,
    val abUpdate: Boolean,
    val bootctlPath: String?,
    val bootctlCurrentSlot: Int?,
    val kernelVersion: String,
    val partitions: List<PartitionInfo>,
    val byNameTotal: Int,
    val skipped: List<PartitionSkip> = emptyList(),
    val source: FlashPartitionSource = FlashPartitionSource.BY_NAME,
    val partitionDir: String? = null,
    val notes: List<String> = emptyList(),
) {
    val hasBootctl: Boolean get() = !bootctlPath.isNullOrBlank()

    
    val hasSlotSuffix: Boolean get() = slotSuffix == "_a" || slotSuffix == "_b"

    
    val sourceLabel: String
        get() = when {
            partitionDir != null -> "by-name $partitionDir"
            source == FlashPartitionSource.PROCFS -> "/proc/partitions + sysfs uevent（无 by-name 降级）"
            else -> "未发现可用分区源"
        }

    
    val currentSlot: String?
        get() {
            if (!hasSlotSuffix) return null
            return when {
                bootctlCurrentSlot == 0 -> "A"
                bootctlCurrentSlot == 1 -> "B"
                slotSuffix == "_a" -> "A"
                slotSuffix == "_b" -> "B"
                else -> null
            }
        }

    
    val nextSlotIndex: Int?
        get() {
            if (!hasSlotSuffix) return null
            val current = when {
                bootctlCurrentSlot == 0 || bootctlCurrentSlot == 1 -> bootctlCurrentSlot
                slotSuffix == "_a" -> 0
                slotSuffix == "_b" -> 1
                else -> null
            } ?: return null
            return 1 - current
        }

    fun findPartition(name: String): PartitionInfo? = partitions.firstOrNull { it.name == name }

    fun riskCounts(): Map<PartitionRisk, Int> = partitions.groupingBy { it.risk }.eachCount()
}

internal data class PartitionSkip(
    val name: String,
    val reason: String,
)

internal class FlashProbe(private val shell: Shell) {

    suspend fun probe(): FlashScan? {
        val result = shell.exec(DISCOVERY_SCRIPT, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (!result.ok) {
            FlashPolicy.remember(emptyList())
            return null
        }
        val output = result.stdout.substringBefore(SECTION_END)
        val sections = splitSections(output)
        val slotLines = sections[SECTION_SLOT].orEmpty()
        fun slotValue(key: String): String =
            slotLines.firstOrNull { it.startsWith("$key ") }?.removePrefix("$key ")?.trim().orEmpty()
        val slotSuffix = slotValue("SLOT").takeIf { it == "_a" || it == "_b" }.orEmpty()
        val hasSlotSuffix = slotSuffix == "_a" || slotSuffix == "_b"
        val bootSlotProp = slotValue("BOOTSLOT")
        val abUpdate = slotValue("AB").equals("true", ignoreCase = true)
        val bootctlPath = slotValue("BOOTCTL").takeIf { it.startsWith("/") }
        val bootctlCurrentText = slotValue("CUR")
        val bootctlCurrentSlot = (
            bootctlCurrentText.toIntOrNull()
                ?: Regex("(?i)current\\s*slot\\s*[:=]?\\s*(\\d+)")
                    .find(bootctlCurrentText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            )?.takeIf { it == 0 || it == 1 }
        val kernelVersion = slotValue("KERNEL")

        val discoveryLines = sections[SECTION_DISCOVERY].orEmpty()
        val discoveredDir = discoveryLines
            .firstOrNull { it.startsWith("DIR ") }
            ?.removePrefix("DIR ")
            ?.trim()
            .orEmpty()
        val byNameDir = discoveredDir
            .trimEnd('/')
            .takeIf { it.isNotBlank() && isSafeByNameDir(it) }
        val notes = mutableListOf<String>()
        if (discoveredDir.isNotBlank() && byNameDir == null) {
            notes += "探测到的 by-name 路径未通过白名单校验，已忽略：$discoveredDir"
        }

        val entries = parseLinks(sections[SECTION_PARTS].orEmpty())

        val skipped = LinkedHashMap<String, String>()
        fun skip(name: String, reason: String) {
            if (!skipped.containsKey(name)) skipped[name] = reason
        }

        val candidates = mutableListOf<PartitionCandidate>()
        var byNameTotal = 0
        val source: FlashPartitionSource

        if (byNameDir != null) {
            source = FlashPartitionSource.BY_NAME
            byNameTotal = entries.size
            notes += "分区源 → $byNameDir（by-name，按顺序取第一个存在的目录）"
            if (entries.isEmpty()) {
                notes += "by-name 目录 $byNameDir 存在，但未解析到任何符号链接条目（未降级到 /proc/partitions）"
            }
            entries.forEach { (name, target) ->
                when {
                    !FlashPolicy.isSafeName(name) ->
                        skip(name, "名称含非法字符")
                    !isUnderBlockDev(target) ->
                        skip(name, "符号链接目标不在 /dev/block/ 或含路径逃逸")
                    isWholeDisk(name, target) ->
                        skip(name, "整盘块设备（非分区），禁止写入")
                    else -> {
                        val blockName = target.substringAfterLast('/')
                        if (!BLOCK_NAME.matches(blockName)) {
                            skip(name, "符号链接目标缺少合法块设备名")
                        } else {
                            candidates += PartitionCandidate(
                                name = name,
                                path = "$byNameDir/$name",
                                blockName = blockName,
                            )
                        }
                    }
                }
            }
        } else {
            source = FlashPartitionSource.PROCFS
            val procNames = parseProcPartitions(sections[SECTION_PROC].orEmpty())
            byNameTotal = procNames.size
            notes += "未找到可用 by-name 目录；降级读取 /proc/partitions + sysfs uevent（${procNames.size} 个块设备）"
            if (procNames.isEmpty()) {
                notes += "/proc/partitions 为空或不可读，无法恢复任何分区名"
            }
            val uevents = readProcUevents(procNames)
            val seenPartNames = HashSet<String>()
            var unresolved = 0
            procNames.forEach { procName ->
                val uevent = uevents[procName]
                if (uevent == null) {
                    unresolved++
                    skip(procName, "sysfs uevent 不可读，无法确定 DEVNAME/PARTNAME")
                    return@forEach
                }
                if (uevent["DEVTYPE"] == "disk") {
                    unresolved++
                    skip(procName, "整盘块设备（非分区），禁止写入")
                    return@forEach
                }
                val devName = uevent["DEVNAME"].orEmpty()
                if (!PROC_NAME.matches(devName)) {
                    unresolved++
                    skip(procName, "uevent 缺少合法 DEVNAME")
                    return@forEach
                }
                val partName = uevent["PARTNAME"].orEmpty()
                if (partName.isBlank()) {
                    unresolved++
                    skip(procName, "uevent 无 PARTNAME，无法恢复 GPT 分区名（不伪造）")
                    return@forEach
                }
                if (devName == partName && WHOLE_DISK.matches(devName)) {
                    unresolved++
                    skip(procName, "整盘块设备（非分区），禁止写入")
                    return@forEach
                }
                if (!FlashPolicy.isSafeName(partName)) {
                    unresolved++
                    skip(procName, "PARTNAME 含非法字符：$partName")
                    return@forEach
                }
                val path = "/dev/block/$devName"
                if (!isSafeDirectBlockPath(path)) {
                    unresolved++
                    skip(procName, "DEVNAME 生成的路径不在 /dev/block/ 白名单内")
                    return@forEach
                }
                if (!seenPartNames.add(partName)) {
                    unresolved++
                    skip(procName, "PARTNAME $partName 重复，无法唯一映射")
                    return@forEach
                }
                candidates += PartitionCandidate(
                    name = partName,
                    path = path,
                    blockName = devName,
                )
            }
            notes += "procfs 可取 PARTNAME 的候选 ${candidates.size} 个；未解析 $unresolved 个（未伪造名称）"
            if (candidates.isEmpty()) {
                notes += "/proc/partitions 与 sysfs uevent 未能提供可用 PARTNAME，返回空分区列表"
            }
        }

        val facts = readFacts(candidates)
        val current = when {
            bootctlCurrentSlot == 0 -> "A"
            bootctlCurrentSlot == 1 -> "B"
            slotSuffix == "_a" -> "A"
            slotSuffix == "_b" -> "B"
            else -> null
        }
        val partitions = candidates.mapNotNull { candidate ->
            val name = candidate.name
            val path = candidate.path
            val fact = facts[name]
            if (fact == null) {
                skip(name, "容量/可写性读取失败")
                return@mapNotNull null
            }
            if (fact.sizeBytes <= 0L) {
                skip(name, "分区容量为 0 或无法读取")
                return@mapNotNull null
            }
            val slot = if (hasSlotSuffix) FlashPolicy.slotOf(name) else null
            PartitionInfo(
                base = if (hasSlotSuffix) FlashPolicy.baseOf(name) else name,
                name = name,
                path = path,
                sizeBytes = fact.sizeBytes,
                writable = fact.writable,
                slotted = slot != null,
                slot = slot,
                risk = FlashPolicy.riskOf(name),
                currentSlot = slot != null && slot == current,
            )
        }
            .sortedWith(compareBy<PartitionInfo>({ it.risk.ordinal }, { !it.currentSlot }, { it.name }))

        if (partitions.isEmpty()) {
            val examples = skipped.values.take(3).joinToString("；")
            notes += if (examples.isBlank()) {
                "未生成任何可刷分区（没有可解析的 by-name/uevent 条目）"
            } else {
                "未生成任何可刷分区；跳过原因示例：$examples"
            }
        }

        FlashPolicy.remember(partitions.map { it.name })
        return FlashScan(
            slotSuffix = slotSuffix,
            bootSlotProp = bootSlotProp,
            abUpdate = abUpdate,
            bootctlPath = bootctlPath,
            bootctlCurrentSlot = bootctlCurrentSlot,
            kernelVersion = kernelVersion,
            partitions = partitions,
            byNameTotal = byNameTotal,
            skipped = skipped.map { (name, reason) -> PartitionSkip(name, reason) },
            source = source,
            partitionDir = byNameDir,
            notes = notes,
        )
    }

    
    private fun parseLinks(lines: List<String>): List<Pair<String, String>> {
        val seen = LinkedHashSet<String>()
        return lines.mapNotNull { line ->
            val match = PART_LINK.find(line) ?: return@mapNotNull null
            val name = match.groupValues[1].substringAfterLast('/')
            val target = match.groupValues[2]
            if (!seen.add(name)) null else name to target
        }
    }

    
    private fun isUnderBlockDev(target: String): Boolean {
        if (!target.startsWith("/dev/block/")) return false
        if (target.contains("..") || target.contains('\n') || target.contains('\r')) return false
        if (target.contains("//") || target.endsWith('/')) return false
        val tail = target.removePrefix("/dev/block/")
        return tail.isNotEmpty() && BLOCK_PATH.matches(tail)
    }

    
    private fun isWholeDisk(name: String, target: String): Boolean {
        val base = target.substringAfterLast('/')
        return base == name && WHOLE_DISK.matches(base)
    }

    
    private suspend fun readFacts(candidates: List<PartitionCandidate>): Map<String, PartitionFacts> {
        if (candidates.isEmpty()) return emptyMap()
        val script = buildString {
            candidates.forEach { candidate ->
                val block = candidate.blockName
                if (!BLOCK_NAME.matches(block)) return@forEach
                appendLine("echo '@@P ${candidate.name}'")
                appendLine("sc=\$(cat /sys/class/block/$block/size 2>/dev/null)")
                appendLine(
                    "if [ -n \"\$sc\" ] && [ \"\$sc\" != \"0\" ]; then echo \"SC \$sc\"; " +
                        "else echo \"S \$(blockdev --getsize64 ${ShellIo.quote(candidate.path)} 2>/dev/null)\"; fi",
                )
                appendLine("echo \"RO \$(cat /sys/class/block/$block/ro 2>/dev/null)\"")
                appendLine("test -r ${ShellIo.quote(candidate.path)} && echo 'RD 1' || echo 'RD 0'")
                appendLine("test -w ${ShellIo.quote(candidate.path)} && echo 'W 1' || echo 'W 0'")
            }
            appendLine("echo '$SECTION_END'")
        }
        if (script.isBlank()) return emptyMap()
        val result = shell.exec(script, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (!result.ok) return emptyMap()

        val facts = mutableMapOf<String, PartitionFacts>()
        var current: String? = null
        var sectors = 0L
        var bytes = 0L
        var ro = ""
        var readable = false
        var writableNode = false
        fun flush() {
            val name = current ?: return
            val size = if (sectors > 0L && sectors <= Long.MAX_VALUE / 512L) {
                sectors * 512L
            } else {
                bytes
            }
            if (size > 0L) {
                facts[name] = PartitionFacts(
                    sizeBytes = size,
                    writable = readable && writableNode && ro != "1",
                )
            }
            sectors = 0L
            bytes = 0L
            ro = ""
            readable = false
            writableNode = false
        }
        result.stdout.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line == SECTION_END -> {
                    flush()
                    current = null
                }
                line.startsWith("@@P ") -> {
                    flush()
                    current = line.removePrefix("@@P ").trim()
                }
                current == null -> Unit
                line.startsWith("SC ") -> sectors = line.removePrefix("SC ").trim().toLongOrNull() ?: 0L
                line.startsWith("S ") -> bytes = line.removePrefix("S ").trim().toLongOrNull() ?: 0L
                line.startsWith("RO ") -> ro = line.removePrefix("RO ").trim()
                line.startsWith("RD ") -> readable = line.removePrefix("RD ").trim() == "1"
                line.startsWith("W ") -> writableNode = line.removePrefix("W ").trim() == "1"
            }
        }
        flush()
        return facts
    }

    
    private suspend fun readProcUevents(names: List<String>): Map<String, Map<String, String>> {
        val safeNames = names.filter { PROC_NAME.matches(it) }
        if (safeNames.isEmpty()) return emptyMap()
        val script = buildString {
            safeNames.forEach { name ->
                appendLine("echo '@@U $name'")
                appendLine("cat ${ShellIo.quote("/sys/class/block/$name/uevent")} 2>/dev/null")
            }
            appendLine("echo '$SECTION_END'")
        }
        val result = shell.exec(script, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (!result.ok) return emptyMap()

        val uevents = LinkedHashMap<String, MutableMap<String, String>>()
        var current: String? = null
        result.stdout.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line == SECTION_END -> current = null
                line.startsWith("@@U ") -> {
                    val name = line.removePrefix("@@U ").trim()
                    if (PROC_NAME.matches(name)) {
                        current = name
                        uevents.getOrPut(name) { mutableMapOf() }
                    } else {
                        current = null
                    }
                }
                current != null && line.contains('=') -> {
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=', "").trim()
                    if (UEVENT_KEY.matches(key)) {
                        uevents[current]?.put(key, value)
                    }
                }
            }
        }
        return uevents
    }

    
    private fun parseProcPartitions(lines: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        lines.forEach { raw ->
            val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size < 4) return@forEach
            if (tokens[0].toIntOrNull() == null || tokens[1].toIntOrNull() == null) return@forEach
            if (tokens[2].toLongOrNull() == null) return@forEach
            val name = tokens[3]
            if (PROC_NAME.matches(name)) seen += name
        }
        return seen.toList()
    }

    
    private fun isSafeByNameDir(path: String): Boolean {
        if (!BY_NAME_DIR_PATH.matches(path)) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    
    private fun isSafeDirectBlockPath(path: String): Boolean {
        if (!DIRECT_BLOCK_PATH.matches(path)) return false
        val name = path.substringAfterLast('/')
        return name != "." && name != ".." && BLOCK_NAME.matches(name)
    }

    private fun splitSections(output: String): Map<String, List<String>> {
        val sections = mutableMapOf<String, List<String>>()
        var current = ""
        var buffer = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) sections[current] = buffer
            buffer = mutableListOf()
        }
        output.lineSequence().forEach { line ->
            val marker = line.trim()
            if (marker.startsWith("@@") && marker.length > 2) {
                flush()
                current = marker.removePrefix("@@").lowercase()
            } else {
                buffer += line
            }
        }
        flush()
        return sections
    }

    private data class PartitionCandidate(
        val name: String,
        val path: String,
        val blockName: String,
    )

    private data class PartitionFacts(val sizeBytes: Long, val writable: Boolean)

    private companion object {
        const val PROBE_TIMEOUT_MS = 60_000L
        const val SECTION_SLOT = "slot"
        const val SECTION_DISCOVERY = "discovery"
        const val SECTION_PARTS = "parts"
        const val SECTION_PROC = "proc"
        const val SECTION_END = "@@END"

        
        val PART_LINK = Regex("(\\S+)\\s+->\\s+(\\S+)\\s*$")
        val BLOCK_PATH = Regex("[A-Za-z0-9._/-]+")
        val BLOCK_NAME = Regex("[A-Za-z0-9._-]+")
        val WHOLE_DISK = Regex("sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+|ublk\\d+|dm-\\d+")
        val PROC_NAME = Regex("[A-Za-z0-9._-]{1,64}")
        val UEVENT_KEY = Regex("[A-Z0-9_]{1,64}")
        val WHITESPACE = Regex("\\s+")
        val DIRECT_BLOCK_PATH = Regex("/dev/block/[A-Za-z0-9._-]+")

        
        val BY_NAME_DIR_PATH = Regex(
            "/dev/block/(?:by-name|bootdevice/by-name|" +
                "platform/[A-Za-z0-9._-]+/by-name|" +
                "platform/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/by-name|" +
                "platform/soc/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/by-name)",
        )

        val DISCOVERY_SCRIPT: String = """
            echo '@@SLOT'
            echo "SLOT ${'$'}(getprop ro.boot.slot_suffix)"
            echo "BOOTSLOT ${'$'}(getprop ro.boot.slot)"
            echo "AB ${'$'}(getprop ro.build.ab_update)"
            echo "BOOTCTL ${'$'}(command -v bootctl)"
            echo "CUR ${'$'}(bootctl get-current-slot 2>/dev/null)"
            echo "KERNEL ${'$'}(uname -r)"
            echo '@@DISCOVERY'
            by=''
            for d in /dev/block/by-name \
                     /dev/block/bootdevice/by-name \
                     /dev/block/platform/*/by-name \
                     /dev/block/platform/*/*/by-name \
                     /dev/block/platform/soc/*/by-name \
                     /dev/block/platform/soc/*/*/by-name; do
                if [ -d "${'$'}d" ]; then by="${'$'}d"; break; fi
            done
            echo "DIR ${'$'}by"
            echo '@@PARTS'
            if [ -n "${'$'}by" ]; then ls -l "${'$'}by" 2>/dev/null; fi
            echo '@@PROC'
            cat /proc/partitions 2>/dev/null
            echo '@@END'
        """.trimIndent()
    }
}
