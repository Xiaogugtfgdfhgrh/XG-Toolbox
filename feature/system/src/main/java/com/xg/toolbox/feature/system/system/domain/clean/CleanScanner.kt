package com.xg.toolbox.feature.system.domain.clean

import com.xg.toolbox.core.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CleanScanner(private val shell: Shell) {

    
    suspend fun resolve(spec: CleanCategorySpec): CleanResolveResult = withContext(Dispatchers.IO) {
        val rejected = mutableListOf<RejectedPath>()

        val patternDirs = spec.dirGlobs + spec.fixedDirs
        val rawDirs = if (patternDirs.isEmpty()) {
            emptyList()
        } else {
            val result = shell.exec(
                "ls -d ${patternDirs.joinToString(" ")} 2>/dev/null",
                root = true,
                timeoutMs = SCAN_TIMEOUT_MS,
            )
            if (!result.ok && result.stdout.isBlank() && result.code >= SYNTHETIC_ERROR_FLOOR) {
                return@withContext errorResult("无法展开清理目录：${firstLine(result.stderr)}")
            }
            result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        val safeDirs = rawDirs.filter { path ->
            val safe = CleanWhitelist.isSafeRawPath(path)
            if (!safe) rejected += RejectedPath(path, "路径含非法字符，已拒绝")
            safe
        }.distinct()

        val canonicalDirs = mutableListOf<String>()
        if (safeDirs.isNotEmpty()) {
            val canonical = canonicalize(safeDirs)
                ?: return@withContext errorResult("readlink -f 规范化目录失败")
            canonical.forEachIndexed { index, path ->
                val reason = CleanWhitelist.validateDir(spec, path)
                if (reason == null) {
                    canonicalDirs += path
                } else {
                    rejected += RejectedPath(safeDirs.getOrElse(index) { path }, reason)
                }
            }
        }

        val canonicalFiles = mutableListOf<String>()
        if (spec.includeDropboxOldLogs) {
            val candidates = dropboxOldLogCandidates()
            if (candidates.isNotEmpty()) {
                val canonical = canonicalize(candidates)
                if (canonical == null) {
                    rejected += RejectedPath(CleanWhitelist.DROPBOX_DIR, "readlink -f 规范化 dropbox 文件失败")
                } else {
                    canonical.forEachIndexed { index, path ->
                        val reason = CleanWhitelist.validateFile(spec, path)
                        if (reason == null) {
                            canonicalFiles += path
                        } else {
                            rejected += RejectedPath(candidates.getOrElse(index) { path }, reason)
                        }
                    }
                }
            }
        }

        val dirs = canonicalDirs.distinct()
        val files = canonicalFiles.distinct()
        val bytes = measureBytes(dirs, files)
            ?: return@withContext errorResult("du 统计失败，无法计算可释放量")
        val fileCount = countFiles(dirs, files) ?: 0
        val samples = sampleFiles(dirs, files)
        CleanResolveResult(
            targets = CleanTargets(
                categoryId = spec.id,
                dirs = dirs,
                files = files,
                bytes = bytes,
                fileCount = fileCount,
                samplePaths = samples,
            ),
            rejected = rejected,
        )
    }

    
    suspend fun measureBytes(targets: CleanTargets): Long? =
        measureBytes(targets.dirs, targets.files)

    
    fun buildDeletePlan(targets: CleanTargets): Pair<CleanDeletePlan?, String?> {
        val spec = CleanCategories.byId(targets.categoryId)
            ?: return null to "未知清理分类：${targets.categoryId}"
        targets.dirs.forEach { path ->
            CleanWhitelist.validateDir(spec, path)?.let { return null to "目录白名单校验失败：$it" }
        }
        targets.files.forEach { path ->
            CleanWhitelist.validateFile(spec, path)?.let { return null to "文件白名单校验失败：$it" }
        }
        val commands = mutableListOf<String>()
        targets.dirs.chunked(COMMAND_BATCH_SIZE).forEach { group ->
            commands += "find ${group.joinToString(" ") { quote(it) }} -mindepth 1 -delete"
        }
        targets.files.chunked(COMMAND_BATCH_SIZE).forEach { group ->
            commands += "rm -f ${group.joinToString(" ") { quote(it) }}"
        }
        return CleanDeletePlan(
            commands = commands,
            dirCount = targets.dirs.size,
            fileCount = targets.files.size,
        ) to null
    }

    
    private suspend fun dropboxOldLogCandidates(): List<String> {
        val result = shell.exec(
            "stat -c %s:%Y:%n ${CleanWhitelist.DROPBOX_DIR}/* 2>/dev/null",
            root = true,
            timeoutMs = SCAN_TIMEOUT_MS,
        )
        if (!result.ok && result.stdout.isBlank() && result.code >= SYNTHETIC_ERROR_FLOOR) {
            return emptyList()
        }
        val cutoffMs = System.currentTimeMillis() - CleanWhitelist.DROPBOX_RETAIN_DAYS * DAY_MS
        val candidates = mutableListOf<String>()
        result.stdout.lineSequence().forEach { line ->
            val parts = line.trim().split(":", limit = 3)
            if (parts.size != 3) return@forEach
            val modifiedAtMs = parts[1].toLongOrNull()?.times(1000L) ?: return@forEach
            val path = parts[2]
            if (!path.startsWith("${CleanWhitelist.DROPBOX_DIR}/")) return@forEach
            if (!path.endsWith(DROPBOX_SUFFIX)) return@forEach
            if (modifiedAtMs >= cutoffMs) return@forEach
            candidates += path
        }
        return candidates.distinct()
    }

    
    private suspend fun canonicalize(paths: List<String>): List<String>? {
        val canonical = mutableListOf<String>()
        paths.chunked(COMMAND_BATCH_SIZE).forEach { group ->
            val result = shell.exec(
                "readlink -f ${group.joinToString(" ") { quote(it) }}",
                root = true,
                timeoutMs = SCAN_TIMEOUT_MS,
            )
            if (!result.ok && result.stdout.isBlank()) return null
            val resolved = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (resolved.size != group.size) return null
            canonical += resolved
        }
        return canonical
    }

    private suspend fun measureBytes(dirs: List<String>, files: List<String>): Long? {
        val all = dirs + files
        if (all.isEmpty()) return 0L
        var totalKb = 0L
        all.chunked(COMMAND_BATCH_SIZE).forEach { group ->
            val result = shell.exec(
                "du -sk ${group.joinToString(" ") { quote(it) }} 2>/dev/null",
                root = true,
                timeoutMs = MEASURE_TIMEOUT_MS,
            )
            if (result.stdout.isBlank()) {
                if (result.code >= SYNTHETIC_ERROR_FLOOR) return null
                return@forEach
            }
            result.stdout.lineSequence().forEach { line ->
                val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                val kb = tokens.firstOrNull()?.toLongOrNull() ?: return@forEach
                totalKb += kb
            }
        }
        return totalKb * 1024L
    }

    private suspend fun countFiles(dirs: List<String>, files: List<String>): Int? {
        val all = dirs + files
        if (all.isEmpty()) return 0
        var total = 0
        all.chunked(COMMAND_BATCH_SIZE).forEach { group ->
            val result = shell.exec(
                "find ${group.joinToString(" ") { quote(it) }} -type f 2>/dev/null | wc -l",
                root = true,
                timeoutMs = MEASURE_TIMEOUT_MS,
            )
            val count = result.stdout.trim().toIntOrNull() ?: return null
            total += count
        }
        return total
    }

    private suspend fun sampleFiles(dirs: List<String>, files: List<String>): List<String> {
        val all = dirs + files
        if (all.isEmpty()) return emptyList()
        val result = shell.exec(
            "find ${all.joinToString(" ") { quote(it) }} -type f 2>/dev/null | head -${CleanWhitelist.SAMPLE_LIMIT}",
            root = true,
            timeoutMs = MEASURE_TIMEOUT_MS,
        )
        if (!result.ok && result.stdout.isBlank()) return emptyList()
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(CleanWhitelist.SAMPLE_LIMIT)
            .toList()
    }

    private fun errorResult(message: String): CleanResolveResult = CleanResolveResult(
        targets = CleanTargets(
            categoryId = "",
            dirs = emptyList(),
            files = emptyList(),
            bytes = 0L,
            fileCount = 0,
            samplePaths = emptyList(),
        ),
        error = message,
    )

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "未知错误"

    
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    companion object {
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val MEASURE_TIMEOUT_MS = 60_000L
        private const val COMMAND_BATCH_SIZE = 100
        private const val SYNTHETIC_ERROR_FLOOR = 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DROPBOX_SUFFIX = ".txt.gz"
        private val WHITESPACE = Regex("\\s+")
    }
}
