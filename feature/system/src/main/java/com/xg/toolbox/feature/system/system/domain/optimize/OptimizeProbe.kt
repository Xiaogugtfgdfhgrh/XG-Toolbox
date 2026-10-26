package com.xg.toolbox.feature.system.domain.optimize

import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class OptimizeProbe(private val shell: Shell) {

    
    suspend fun scanRaw(): OptimizeScanRaw = withContext(Dispatchers.IO) {
        val results = shell.execMany(SCAN_COMMANDS, root = true, timeoutMs = SCAN_TIMEOUT_MS)
        val anim = parseAnimScales(results[0].stdout)
        val memStorage = sectionsOf(results[1])
        val mem = parseMemInfo(memStorage.getOrNull(0).orEmpty())
        val storageUsed = parseStorageUsedKb(memStorage.getOrNull(1).orEmpty())
        val cacheBytes = parseDuSum(results[2])
        val nodeSections = sectionsOf(results[3])
        val tools = parseFstrimTools(nodeSections.getOrNull(0).orEmpty())
        val zram = parseZram(
            disksizeSection = nodeSections.getOrNull(1).orEmpty(),
            hybridSection = nodeSections.getOrNull(2).orEmpty(),
            swapSection = nodeSections.getOrNull(3).orEmpty(),
        )
        val packageSections = sectionsOf(results[4])
        val installed = parsePackageNames(packageSections.getOrNull(0).orEmpty())
        val disabled = parsePackageNames(packageSections.getOrNull(1).orEmpty())
        OptimizeScanRaw(
            anim = anim,
            mem = mem,
            cacheBytes = cacheBytes,
            storageUsedBytes = storageUsed,
            zram = zram,
            fstrim = tools,
            installed = installed,
            disabled = disabled,
        )
    }

    
    suspend fun readAnimScales(): AnimScales? {
        val result = shell.exec(ANIM_COMMAND, root = true, timeoutMs = 10_000)
        if (!result.ok) return null
        return parseAnimScales(result.stdout)
    }

    
    suspend fun readMemInfo(root: Boolean = false): MemInfo? {
        val result = shell.exec(
            "grep -E 'MemTotal|MemFree|MemAvailable' /proc/meminfo",
            root = root,
            timeoutMs = 10_000,
        )
        if (!result.ok) return null
        return parseMemInfo(result.stdout)
    }

    
    suspend fun readStorageUsedBytes(root: Boolean = false): Long? {
        val result = shell.exec("df -k /data | tail -1", root = root, timeoutMs = 10_000)
        if (!result.ok) return null
        return parseStorageUsedKb(result.stdout)
    }

    
    suspend fun readCacheBytes(): Long? {
        val result = shell.exec(CACHE_COMMAND, root = true, timeoutMs = CACHE_TIMEOUT_MS)
        if (!result.ok && result.stdout.isBlank()) return null
        return parseDuSum(result)
    }

    
    suspend fun readZramState(): ZramState? {
        val result = shell.exec(NODE_COMMAND, root = true, timeoutMs = 10_000)
        if (!result.ok && result.stdout.isBlank()) return null
        val sections = sectionsOf(result)
        return parseZram(
            disksizeSection = sections.getOrNull(1).orEmpty(),
            hybridSection = sections.getOrNull(2).orEmpty(),
            swapSection = sections.getOrNull(3).orEmpty(),
        )
    }

    
    suspend fun readFstrimTools(): FstrimTools {
        val result = shell.exec(TOOLS_COMMAND, root = true, timeoutMs = 8_000)
        return parseFstrimTools(sectionsOf(result).getOrNull(0).orEmpty())
    }

    
    suspend fun listInstalledPackages(): Set<String>? {
        val result = shell.exec("pm list packages", root = true, timeoutMs = 20_000)
        if (!result.ok) return null
        return parsePackageNames(result.stdout).takeIf { it.isNotEmpty() }
    }

    
    suspend fun listDisabledPackages(): Set<String>? {
        val result = shell.exec("pm list packages -d", root = true, timeoutMs = 20_000)
        if (!result.ok) return null
        return parsePackageNames(result.stdout)
    }

    
    suspend fun isPackageInstalled(packageName: String): Boolean {
        if (!isSafePackageName(packageName)) return false
        val result = shell.exec("pm list packages ${shellQuote(packageName)}", root = true, timeoutMs = 10_000)
        if (!result.ok) return false
        return parsePackageNames(result.stdout).contains(packageName)
    }

    
    suspend fun disablePackage(packageName: String): ShellResult {
        if (!isSafePackageName(packageName)) {
            return ShellResult(
                code = com.xg.toolbox.core.common.ErrorCode.ERR_POLICY_BLOCKED,
                stdout = "",
                stderr = "包名不合法，已拒绝：$packageName",
                durationMs = 0L,
            )
        }
        return shell.exec(
            "pm disable-user --user ${OptimizeIds.ANDROID_USER} ${shellQuote(packageName)}",
            root = true,
            timeoutMs = 20_000,
        )
    }

    
    private fun sectionsOf(result: ShellResult): List<String> = splitSections(result.stdout)

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

    
    private fun parseAnimScales(text: String): AnimScales? {
        val values = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != SECTION_MARKER }
            .map { it.takeIf { value -> value != "null" } }
            .toList()
        if (values.size < 3) return null
        return AnimScales(window = values[0], transition = values[1], animator = values[2])
    }

    private fun parseMemInfo(text: String): MemInfo? {
        var total = 0L
        var free = 0L
        var available = 0L
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> total = meminfoKb(line)
                line.startsWith("MemFree:") -> free = meminfoKb(line)
                line.startsWith("MemAvailable:") -> available = meminfoKb(line)
            }
        }
        if (total <= 0L) return null
        return MemInfo(totalBytes = total, freeBytes = free, availableBytes = available)
    }

    private fun meminfoKb(line: String): Long =
        line.substringAfter(':').trim().split(WHITESPACE).firstOrNull()?.toLongOrNull()?.times(1024L) ?: 0L

    private fun parseStorageUsedKb(dfOutput: String): Long? {
        val line = dfOutput.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < 4) return null
        val usedKb = tokens[2].toLongOrNull() ?: return null
        return usedKb * 1024L
    }

    private fun parseDuSum(result: ShellResult): Long? {
        if (result.stdout.isBlank()) return if (result.ok) 0L else null
        var totalKb = 0L
        var counted = 0
        result.stdout.lineSequence().forEach { line ->
            val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size >= 2) {
                val kb = tokens[0].toLongOrNull() ?: return@forEach
                totalKb += kb
                counted++
            }
        }
        if (counted == 0) return null
        return totalKb * 1024L
    }

    private fun parseFstrimTools(section: String): FstrimTools {
        val hasSm = section.lineSequence().any { it.contains("/sm") }
        val hasFstrim = section.lineSequence().any { it.contains("fstrim") && !it.contains("/sm") }
        return FstrimTools(hasSm = hasSm, hasFstrim = hasFstrim)
    }

    private fun parseZram(
        disksizeSection: String,
        hybridSection: String,
        swapSection: String,
    ): ZramState? {
        val disksize = disksizeSection.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(DIGITS) }
            ?.toLongOrNull()
        val hybridPresent = hybridSection.lineSequence().any { it.contains("hybridswap") }
        var swapTotalKb = 0L
        swapSection.lineSequence().forEach { line ->
            if (!line.startsWith("/dev/block/zram")) return@forEach
            val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            swapTotalKb = tokens.getOrNull(2)?.toLongOrNull() ?: 0L
        }
        if (disksize == null && !hybridPresent && swapTotalKb == 0L) return null
        return ZramState(
            disksizeBytes = disksize,
            hybridSwapPresent = hybridPresent,
            swapTotalKb = swapTotalKb,
        )
    }

    private fun parsePackageNames(output: String): Set<String> {
        val names = mutableSetOf<String>()
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(PACKAGE_PREFIX)) {
                val name = trimmed.removePrefix(PACKAGE_PREFIX).trim()
                if (name.isNotEmpty()) names += name
            }
        }
        return names
    }

    companion object {
        private const val SECTION_MARKER = "@@"
        private const val PACKAGE_PREFIX = "package:"
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val CACHE_TIMEOUT_MS = 30_000L
        private val WHITESPACE = Regex("\\s+")
        private val DIGITS = Regex("\\d+")

        private const val ANIM_COMMAND =
            "settings get global window_animation_scale; echo @@; " +
                "settings get global transition_animation_scale; echo @@; " +
                "settings get global animator_duration_scale"

        private const val CACHE_COMMAND =
            "du -sk /data/data/*/cache /data/data/*/code_cache 2>/dev/null"

        private const val TOOLS_COMMAND = "ls /system/bin/sm /system/bin/fstrim 2>/dev/null"

        private const val NODE_COMMAND =
            "$TOOLS_COMMAND; echo @@; cat /sys/block/zram0/disksize 2>/dev/null; echo @@; " +
                "ls -d /sys/block/zram0/hybridswap_* 2>/dev/null; echo @@; grep zram /proc/swaps 2>/dev/null"

        private val SCAN_COMMANDS = listOf(
            ANIM_COMMAND,
            "grep -E 'MemTotal|MemFree|MemAvailable' /proc/meminfo; echo @@; df -k /data | tail -1",
            CACHE_COMMAND,
            NODE_COMMAND,
            "pm list packages; echo @@; pm list packages -d",
        )
    }
}

internal data class OptimizeScanRaw(
    val anim: AnimScales?,
    val mem: MemInfo?,
    val cacheBytes: Long?,
    val storageUsedBytes: Long?,
    val zram: ZramState?,
    val fstrim: FstrimTools,
    val installed: Set<String>?,
    val disabled: Set<String>?,
)

private val SAFE_PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

private fun isSafePackageName(name: String): Boolean =
    name.length in 3..200 && SAFE_PACKAGE_NAME.matches(name)

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal fun parseBloatEntries(json: String): List<BloatEntry> = runCatching {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return emptyList()
    val root: JSONArray = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        JSONObject(trimmed).optJSONArray("packages") ?: JSONArray()
    }
    buildList {
        for (index in 0 until root.length()) {
            val item = root.optJSONObject(index) ?: continue
            val pkg = item.optString("pkg").trim()
            if (!isSafePackageName(pkg)) continue
            add(
                BloatEntry(
                    pkg = pkg,
                    name = item.optString("name").trim().ifEmpty { pkg },
                    risk = item.optString("risk").trim().ifEmpty { "中" },
                    note = item.optString("note").trim(),
                ),
            )
        }
    }
}.getOrDefault(emptyList())
