package com.xg.toolbox.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a user-picked picture into app-private storage. Keeping the bytes (not the SAF URI)
 * means the background continues to work after reboot and after self-hide copies app data.
 */
@Singleton
class BackgroundImageStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "backgrounds").apply { mkdirs() }

    fun fileFor(fileName: String?): File? {
        if (fileName.isNullOrBlank() || fileName == "." || fileName == "..") return null
        if (fileName != File(fileName).name) return null
        val root = dir
        val file = File(root, fileName)
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val canonicalFile = file.canonicalFile
            file.takeIf {
                it.isFile &&
                    it.length() > 0L &&
                    canonicalFile.parentFile == canonicalRoot
            }
        }.getOrNull()
    }

    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
                true
            } ?: false
            if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("无法读取所选图片")
            }
            if (bounds.outWidth < 64 || bounds.outHeight < 64) {
                throw IOException("图片尺寸过小，至少需要 64×64")
            }
            val sourceSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            if (sourceSize != null && sourceSize > MAX_IMPORT_BYTES) {
                throw IOException("图片文件过大，最大支持 ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
            }
            val ext = extensionFor(uri)
            val name = "custom_${UUID.randomUUID().toString().replace("-", "")}.$ext"
            val target = File(dir, name)
            try {
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > MAX_IMPORT_BYTES) {
                                throw IOException("图片文件过大，最大支持 ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IOException("无法复制所选图片")
                if (target.length() <= 0L) throw IOException("图片复制失败")
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
            name
        }
    }

    fun delete(fileName: String?) {
        fileFor(fileName)?.delete()
    }

    private fun extensionFor(uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("heic") || mime.contains("heif") -> "heic"
            else -> "jpg"
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 24L * 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
