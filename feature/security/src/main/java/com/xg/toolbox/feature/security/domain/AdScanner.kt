package com.xg.toolbox.feature.security.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_EVIDENCE_LENGTH = 180

@Singleton
class AdScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scan(
        packages: List<String>? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): XgResult<List<AdHit>> = withContext(Dispatchers.IO) {
        val signatures = try {
            loadSignatures()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "广告特征库读取失败", t)
            return@withContext XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "广告特征库读取失败",
                t.message,
            )
        }
        if (signatures.isEmpty()) {
            return@withContext XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "广告特征库为空",
                "assets/$ASSET_NAME 缺少 packages/libs",
            )
        }

        val targets = try {
            resolveTargets(packages)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "应用列表读取失败", t)
            return@withContext XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "应用列表读取失败",
                t.message,
            )
        }

        val total = targets.size
        safeProgress(onProgress, 0, total)
        val matcher = DexMatcher(signatures.packages)
        val hits = linkedMapOf<String, MutableHit>()
        var done = 0

        for (target in targets) {
            currentCoroutineContext().ensureActive()
            try {
                scanTarget(target, signatures, matcher, hits)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(
                    TAG,
                    "扫描失败: ${target.packageName}: ${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                )
            }
            done += 1
            safeProgress(onProgress, done, total)
        }

        val result = hits.values
            .map { hit ->
                AdHit(
                    packageName = hit.packageName,
                    appLabel = hit.appLabel,
                    sdk = hit.sdk,
                    evidence = hit.evidence.joinToString(" | ").take(MAX_EVIDENCE_LENGTH),
                )
            }
            .sortedWith(compareBy({ it.packageName.lowercase(Locale.ROOT) }, { it.sdk.lowercase(Locale.ROOT) }))
        XgResult.Ok(result)
    }

    private fun resolveTargets(packages: List<String>?): List<AppTarget> {
        val packageManager = context.packageManager
        if (packages == null) {
            return packageManager.getInstalledApplications(0)
                .sortedWith(
                    compareBy(
                        { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 },
                        { it.packageName },
                    ),
                )
                .map { app -> toTarget(packageManager, app) }
        }
        return packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { packageName ->
                if (!isValidPackageName(packageName)) return@mapNotNull null
                val app = runCatching {
                    packageManager.getApplicationInfo(packageName, 0)
                }.getOrNull() ?: return@mapNotNull null
                toTarget(packageManager, app)
            }
    }

    private fun toTarget(packageManager: PackageManager, app: ApplicationInfo): AppTarget =
        AppTarget(
            packageName = app.packageName,
            label = runCatching {
                packageManager.getApplicationLabel(app).toString()
            }.getOrDefault(app.packageName),
            baseApk = app.sourceDir.orEmpty(),
            splitApks = app.splitSourceDirs?.toList().orEmpty(),
        )

    private suspend fun scanTarget(
        target: AppTarget,
        signatures: Signatures,
        matcher: DexMatcher,
        hits: MutableMap<String, MutableHit>,
    ) {
        val apkPaths = buildList {
            add(target.baseApk)
            addAll(target.splitApks)
        }.filter { it.isNotBlank() }.distinct()
        val evidenceBySdk = linkedMapOf<String, LinkedHashSet<String>>()

        for (apkPath in apkPaths) {
            currentCoroutineContext().ensureActive()
            val apk = File(apkPath)
            if (!apk.isFile || !apk.canRead()) {
                XgLog.w(TAG, "APK 不可读，已跳过: ${target.packageName}:$apkPath")
                continue
            }
            val scanDex = apk.length() <= MAX_DEX_SCAN_APK_BYTES
            try {
                ZipFile(apk).use { zip ->
                    scanLibraries(zip, apk.name, signatures.libs, evidenceBySdk)
                    if (scanDex) {
                        scanDexEntries(zip, apk.name, matcher, evidenceBySdk)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(
                    TAG,
                    "APK 读取失败: ${target.packageName}:${apk.name}: " +
                        "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                )
            }
        }

        for ((sdk, evidence) in evidenceBySdk) {
            val key = target.packageName + "\u0000" + sdk
            val hit = hits.getOrPut(key) {
                MutableHit(target.packageName, target.label, sdk)
            }
            for (value in evidence) {
                if (hit.evidence.size >= MAX_EVIDENCE_PER_SDK) break
                hit.evidence += value
            }
        }
    }

    private fun scanLibraries(
        zip: ZipFile,
        apkName: String,
        libraries: List<String>,
        evidenceBySdk: MutableMap<String, LinkedHashSet<String>>,
    ) {
        if (libraries.isEmpty()) return
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (!name.startsWith("lib/")) continue
            for (library in libraries) {
                if (name.contains(library, ignoreCase = true)) {
                    addEvidence(evidenceBySdk, library, "$apkName:$name")
                }
            }
        }
    }

    private suspend fun scanDexEntries(
        zip: ZipFile,
        apkName: String,
        matcher: DexMatcher,
        evidenceBySdk: MutableMap<String, LinkedHashSet<String>>,
    ) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            val entry = entries.nextElement()
            if (entry.isDirectory || !DEX_NAME.matches(entry.name)) continue
            if (entry.size > MAX_DEX_ENTRY_BYTES) {
                XgLog.w(TAG, "dex 过大，已跳过: $apkName:${entry.name}: ${entry.size}")
                continue
            }
            try {
                zip.getInputStream(entry).use { input ->
                    matcher.scan(input, "$apkName:${entry.name}") { sdk, evidence ->
                        addEvidence(evidenceBySdk, sdk, evidence)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(
                    TAG,
                    "dex 读取失败: $apkName:${entry.name}: ${t.javaClass.simpleName}",
                )
            }
        }
    }

    private fun addEvidence(
        evidenceBySdk: MutableMap<String, LinkedHashSet<String>>,
        sdk: String,
        evidence: String,
    ) {
        val values = evidenceBySdk.getOrPut(sdk) { linkedSetOf() }
        if (values.size >= MAX_EVIDENCE_PER_SDK) return
        values += evidence.take(MAX_EVIDENCE_LENGTH)
    }

    private fun loadSignatures(): Signatures {
        val raw = context.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val json = JSONObject(raw)
        return Signatures(
            packages = json.optJSONArray("packages").toStringList(),
            libs = json.optJSONArray("libs").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }.distinct()
    }

    private fun safeProgress(onProgress: (Int, Int) -> Unit, done: Int, total: Int) {
        try {
            onProgress(done, total)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
        }
    }

    private class DexMatcher(packageSignatures: List<String>) {

        private data class Needle(
            val sdk: String,
            val dotted: String,
            val slashed: String,
        )

        private val needles: List<Needle> = packageSignatures.mapNotNull { raw ->
            val sdk = raw.trim()
            if (sdk.isEmpty()) return@mapNotNull null
            val lower = sdk.lowercase(Locale.ROOT)
            Needle(sdk = sdk, dotted = lower, slashed = lower.replace('.', '/'))
        }.distinctBy { it.sdk }

        private val maxNeedleLength: Int =
            needles.maxOfOrNull { maxOf(it.dotted.length, it.slashed.length) } ?: 1

        suspend fun scan(
            input: InputStream,
            label: String,
            onHit: (String, String) -> Unit,
        ) {
            if (needles.isEmpty()) return
            val chunk = ByteArray(CHUNK_BYTES + maxNeedleLength + 1)
            var carry = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(chunk, carry, CHUNK_BYTES)
                if (read <= 0) break
                val total = carry + read
                val text = String(chunk, 0, total, Charsets.ISO_8859_1)
                for (needle in needles) {
                    find(text, needle.dotted, needle.sdk, label, onHit)
                    if (needle.slashed != needle.dotted) {
                        find(text, needle.slashed, needle.sdk, label, onHit)
                    }
                }
                val keep = minOf(maxNeedleLength - 1, total)
                if (keep > 0) {
                    System.arraycopy(chunk, total - keep, chunk, 0, keep)
                }
                carry = keep
            }
        }

        private fun find(
            text: String,
            needle: String,
            sdk: String,
            label: String,
            onHit: (String, String) -> Unit,
        ) {
            if (needle.isEmpty() || text.length < needle.length) return
            var from = 0
            var matches = 0
            while (from <= text.length - needle.length && matches < MATCHES_PER_CHUNK) {
                val index = text.indexOf(needle, from)
                if (index < 0) break
                val after = index + needle.length
                val afterOk = after >= text.length || !isPackageChar(text[after])
                val beforeOk = index == 0 ||
                    !isPackageChar(text[index - 1]) ||
                    text[index - 1] == 'L'
                if (afterOk && beforeOk) {
                    val evidence = "$label: ${context(text, index, needle.length)}"
                        .take(MAX_EVIDENCE_LENGTH)
                    onHit(sdk, evidence)
                    matches += 1
                }
                from = index + 1
            }
        }

        private fun context(text: String, index: Int, length: Int): String {
            var start = index
            while (start > 0 && isPrintable(text[start - 1]) && index - start < CONTEXT_RADIUS) {
                start -= 1
            }
            var end = index + length
            while (end < text.length && isPrintable(text[end]) && end - (index + length) < CONTEXT_RADIUS) {
                end += 1
            }
            return text.substring(start, end).trim().ifEmpty {
                text.substring(index, index + length)
            }
        }

        private fun isPrintable(char: Char): Boolean = char in ' '..'~'

        private fun isPackageChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

        private companion object {
            const val CHUNK_BYTES = 256 * 1024
            const val MATCHES_PER_CHUNK = 2
            const val CONTEXT_RADIUS = 80
        }
    }

    private data class Signatures(
        val packages: List<String>,
        val libs: List<String>,
    ) {
        fun isEmpty(): Boolean = packages.isEmpty() && libs.isEmpty()
    }

    private data class AppTarget(
        val packageName: String,
        val label: String,
        val baseApk: String,
        val splitApks: List<String>,
    )

    private class MutableHit(
        val packageName: String,
        val appLabel: String,
        val sdk: String,
    ) {
        val evidence: LinkedHashSet<String> = linkedSetOf()
    }

    private companion object {
        const val TAG = "AdScanner"
        const val ASSET_NAME = "ad_signatures.json"
        const val MAX_DEX_SCAN_APK_BYTES = 64L * 1024 * 1024
        const val MAX_DEX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_EVIDENCE_PER_SDK = 3
        val DEX_NAME = Regex("^classes\\d*\\.dex$")
    }
}
