package com.xg.toolbox.feature.system.domain.flash

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FlashImageInspector(private val context: Context) {

    
    suspend fun inspect(uri: String): XgResult<FlashImageInfo> = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "镜像地址不合法")
        val rawName = queryDisplayName(parsed) ?: parsed.lastPathSegment ?: "image.img"
        val displayName = sanitizeDisplayName(rawName)
            ?: return@withContext err(
                ErrorCode.ERR_CMD_FAILED,
                "镜像文件名包含非法字符（仅允许 A-Za-z0-9._-），已拒绝",
            )
        if (!displayName.lowercase().endsWith(".img")) {
            return@withContext err(
                ErrorCode.ERR_CMD_FAILED,
                "仅接受 .img 镜像文件（当前：$displayName）",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val stream = context.contentResolver.openInputStream(parsed)
                ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法打开所选文件（$displayName）")
            stream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } catch (t: Exception) {
            XgLog.w(TAG, "读取镜像失败：${t.javaClass.simpleName}")
            return@withContext err(
                ErrorCode.ERR_CMD_FAILED,
                "读取镜像失败（${t.javaClass.simpleName}），请重新选择文件",
            )
        }
        if (total <= 0L) {
            return@withContext err(ErrorCode.ERR_CMD_FAILED, "所选镜像为空文件")
        }
        XgResult.Ok(
            FlashImageInfo(
                displayName = displayName,
                sizeBytes = total,
                sha256 = digest.digest().toHex(),
                uri = uri,
            ),
        )
    }

    
    suspend fun copyToFile(
        uri: String,
        target: File,
        expectedSha256: String,
        onProgress: suspend (Float) -> Unit,
        expectedSize: Long = 0L,
    ): XgResult<Unit> = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "镜像地址不合法")
        val stagingRoot = File(context.cacheDir, LOCAL_STAGING_DIR)
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法创建本地暂存目录")
        }
        val targetCanonical = target.canonicalFile
        if (targetCanonical.parentFile?.canonicalFile != stagingRoot.canonicalFile ||
            !STAGING_NAME_REGEX.matches(targetCanonical.name)
        ) {
            return@withContext err(ErrorCode.ERR_POLICY_BLOCKED, "暂存目标路径越界，已拒绝写入")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val stream = context.contentResolver.openInputStream(parsed)
                ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法打开所选文件")
            stream.use { input ->
                targetCanonical.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                        if (expectedSize > 0L) {
                            val percent = (total * 100 / expectedSize).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent.toFloat())
                            }
                        }
                    }
                    output.flush()
                }
            }
        } catch (t: Exception) {
            XgLog.w(TAG, "复制镜像失败：${t.javaClass.simpleName}")
            return@withContext err(
                ErrorCode.ERR_CMD_FAILED,
                "复制镜像到暂存失败（${t.javaClass.simpleName}）",
            )
        }
        if (total <= 0L) {
            return@withContext err(ErrorCode.ERR_CMD_FAILED, "读取到的镜像为空")
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return@withContext err(
                ErrorCode.ERR_HASH_MISMATCH,
                "暂存校验失败：镜像 SHA-256 与选择时不一致，已中止（未写入分区）",
            )
        }
        XgResult.Ok(Unit)
    }

    
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        var name: String? = null
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index)
            }
        }
        name
    }.getOrNull()

    
    private fun sanitizeDisplayName(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        val base = File(trimmed).name
        if (base.isEmpty() || base == "." || base == "..") return null
        if (!base.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }) {
            return null
        }
        return base
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(Integer.toHexString(value))
        }
        return builder.toString()
    }

    private companion object {
        const val TAG = "FlashImageInspector"
        const val BUFFER_SIZE = 256 * 1024

        
        const val LOCAL_STAGING_DIR = "flash_staging"

        
        val STAGING_NAME_REGEX = Regex("[A-Za-z0-9_-]{1,64}\\.img")

        fun err(code: Int, message: String): XgResult.Err = XgResult.Err(code, message)
    }
}
