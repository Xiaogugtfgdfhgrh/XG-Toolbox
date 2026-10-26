package com.xg.toolbox.feature.system.domain.clean

internal object CleanWhitelist {

    const val EMULATED_ROOT = "/storage/emulated/0"
    const val STAGING_DIR = "/data/local/tmp/xg/staging"
    const val LOGS_DIR = "/data/local/tmp/xg/logs"
    const val DROPBOX_DIR = "/data/system/dropbox"
    const val DCIM_THUMBNAILS = "$EMULATED_ROOT/DCIM/.thumbnails"
    const val SDCARD_THUMBNAILS = "$EMULATED_ROOT/.thumbnails"

    const val DROPBOX_RETAIN_DAYS = 7
    const val SAMPLE_LIMIT = 20

    
    private val APP_CACHE_DIR =
        Regex("^$EMULATED_ROOT/Android/data/[A-Za-z0-9._]+/(cache|code_cache)$")

    
    private val DROPBOX_FILE =
        Regex("^$DROPBOX_DIR/[A-Za-z0-9._@-]+\\.txt\\.gz$")

    
    private val SAFE_PATH = Regex("^[A-Za-z0-9._/@:+-]+$")

    
    private val FORBIDDEN_PREFIXES = listOf(
        "/data/data",
        "/data/system",
        "/data/media",
        "$EMULATED_ROOT/Android/obb",
        "$EMULATED_ROOT/Android/media",
    )

    
    fun validateDir(spec: CleanCategorySpec, canonicalPath: String): String? {
        val shapeError = validateShape(canonicalPath)
        if (shapeError != null) return shapeError
        if (FORBIDDEN_PREFIXES.any { canonicalPath == it || canonicalPath.startsWith("$it/") }) {
            return "命中保护目录（禁止清理）：$canonicalPath"
        }
        return when (spec.id) {
            CleanIds.APP_CACHE -> if (APP_CACHE_DIR.matches(canonicalPath)) {
                null
            } else {
                "不是允许清理的应用缓存目录：$canonicalPath"
            }

            CleanIds.THUMBNAILS -> if (canonicalPath == DCIM_THUMBNAILS || canonicalPath == SDCARD_THUMBNAILS) {
                null
            } else {
                "不是允许清理的缩略图目录：$canonicalPath"
            }

            CleanIds.LOGS -> if (canonicalPath == LOGS_DIR) {
                null
            } else {
                "不是允许清理的日志目录：$canonicalPath"
            }

            CleanIds.TMP -> if (canonicalPath == STAGING_DIR) {
                null
            } else {
                "不是允许清理的临时目录：$canonicalPath"
            }

            else -> "未知清理分类：${spec.id}"
        }
    }

    
    fun validateFile(spec: CleanCategorySpec, canonicalPath: String): String? {
        val shapeError = validateShape(canonicalPath)
        if (shapeError != null) return shapeError
        if (!spec.includeDropboxOldLogs) return "该分类不允许删除单个文件：${spec.id}"
        if (DROPBOX_FILE.matches(canonicalPath)) return null
        if (canonicalPath.startsWith("$DROPBOX_DIR/")) {
            return "dropbox 仅允许删除 *.txt.gz：$canonicalPath"
        }
        return "不是允许删除的 dropbox 日志：$canonicalPath"
    }

    
    fun validateShape(canonicalPath: String): String? = when {
        canonicalPath.isBlank() -> "路径为空"
        !canonicalPath.startsWith("/") -> "不是绝对路径：$canonicalPath"
        !SAFE_PATH.matches(canonicalPath) -> "路径含非法字符（已拒绝）：$canonicalPath"
        else -> null
    }

    
    fun isSafeRawPath(path: String): Boolean =
        path.startsWith("/") && path.length <= 1024 && !path.contains('\n') && SAFE_PATH.matches(path)

    
    fun whitelistForDisplay(): List<String> = listOf(
        STAGING_DIR,
        LOGS_DIR,
        "/sdcard/Android/data/*/cache",
        "/sdcard/Android/data/*/code_cache",
        DCIM_THUMBNAILS,
        SDCARD_THUMBNAILS,
        "$DROPBOX_DIR/*.txt.gz（仅 7 天前）",
    )
}
