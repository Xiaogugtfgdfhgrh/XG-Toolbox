package com.xg.toolbox.feature.system.domain.clean

data class CleanCategory(
    val id: String,
    val name: String,
    val path: String,
    val bytes: Long,
    val fileCount: Int,
    val samplePaths: List<String>,
    val group: String,
)

data class CleanScan(
    val categories: List<CleanCategory>,
    val totalBytes: Long,
)

data class CleanProgress(
    val categoryId: String,
    val percent: Float,
    val freedBytes: Long,
    val message: String? = null,
)

internal object CleanIds {
    const val APP_CACHE = "app-cache"
    const val THUMBNAILS = "thumbnails"
    const val LOGS = "logs"
    const val TMP = "tmp"

    val ORDER = listOf(APP_CACHE, THUMBNAILS, LOGS, TMP)
}

internal data class CleanCategorySpec(
    val id: String,
    val name: String,
    val displayPath: String,
    val group: String,
    val dirGlobs: List<String> = emptyList(),
    val fixedDirs: List<String> = emptyList(),
    val includeDropboxOldLogs: Boolean = false,
)

internal object CleanCategories {
    val specs: List<CleanCategorySpec> = listOf(
        CleanCategorySpec(
            id = CleanIds.APP_CACHE,
            name = "应用缓存",
            displayPath = "/sdcard/Android/data/*/cache · .../code_cache",
            group = "cache",
            dirGlobs = listOf(
                "/sdcard/Android/data/*/cache",
                "/sdcard/Android/data/*/code_cache",
            ),
        ),
        CleanCategorySpec(
            id = CleanIds.THUMBNAILS,
            name = "缩略图",
            displayPath = "/sdcard/DCIM/.thumbnails · /sdcard/.thumbnails",
            group = "image",
            fixedDirs = listOf(
                "/sdcard/DCIM/.thumbnails",
                "/sdcard/.thumbnails",
            ),
        ),
        CleanCategorySpec(
            id = CleanIds.LOGS,
            name = "日志与临时记录",
            displayPath = "/data/local/tmp/xg/logs · /data/system/dropbox（仅 *.txt.gz 且保留最近 7 天）",
            group = "log",
            fixedDirs = listOf("/data/local/tmp/xg/logs"),
            includeDropboxOldLogs = true,
        ),
        CleanCategorySpec(
            id = CleanIds.TMP,
            name = "临时文件",
            displayPath = "/data/local/tmp/xg/staging",
            group = "tmp",
            fixedDirs = listOf("/data/local/tmp/xg/staging"),
        ),
    )

    fun byId(id: String): CleanCategorySpec? = specs.firstOrNull { it.id == id }
}

internal data class RejectedPath(val path: String, val reason: String)

internal data class CleanTargets(
    val categoryId: String,
    val dirs: List<String>,
    val files: List<String>,
    val bytes: Long,
    val fileCount: Int,
    val samplePaths: List<String>,
) {
    val isEmpty: Boolean get() = dirs.isEmpty() && files.isEmpty()
}

internal data class CleanResolveResult(
    val targets: CleanTargets,
    val rejected: List<RejectedPath> = emptyList(),
    val error: String? = null,
)

internal data class CleanDeletePlan(
    val commands: List<String>,
    val dirCount: Int,
    val fileCount: Int,
)
