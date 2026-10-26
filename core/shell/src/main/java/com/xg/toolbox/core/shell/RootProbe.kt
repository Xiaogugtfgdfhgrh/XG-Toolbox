package com.xg.toolbox.core.shell

import android.os.SystemClock
import com.xg.toolbox.core.common.ErrorCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class RootProbe @Inject constructor(
    private val shell: Shell,
    private val suPathStore: SuPathStore,
) {

    suspend fun probe(): RootProbeResult = withContext(Dispatchers.IO) {
        val evidence = mutableListOf<String>()

        // The configured path is always tested first. Known absolute fallbacks and, finally,
        // PATH lookup are only probes; RootShell never relies on a cached previous success.
        val candidates = buildList {
            suPathStore.customPath.value?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            addAll(SuBinary.candidates())
            add("su")
        }.distinct()

        var firstVersion: ShellResult? = null
        var lastAttempt: Attempt? = null
        var selected: Attempt? = null
        var lastReason = DenyReason.NO_BINARY

        for (path in candidates) {
            val quoted = shellQuote(path)
            val versionResult = shell.exec("$quoted -v", root = false, timeoutMs = STEP_TIMEOUT_MS)
            evidence += evidenceOf("$quoted -v", versionResult)
            if (firstVersion == null) firstVersion = versionResult

            val versionCodeResult = shell.exec("$quoted -V", root = false, timeoutMs = STEP_TIMEOUT_MS)
            evidence += evidenceOf("$quoted -V", versionCodeResult)

            val idResult = shell.exec("$quoted -c id", root = false, timeoutMs = STEP_TIMEOUT_MS)
            evidence += evidenceOf("$quoted -c id", idResult)
            val uid = parseUid(idResult.stdout.ifBlank { idResult.stderr })
            val attempt = Attempt(path, versionResult, versionCodeResult, idResult, uid)
            lastAttempt = attempt

            if (uid == 0) {
                selected = attempt
                evidence += "有效 su 路径：$path"
                break
            }

            lastReason = denialReason(idResult, versionResult)
            // A real denial should not trigger repeated authorization prompts on other paths.
            if (lastReason != DenyReason.NO_BINARY && lastReason != DenyReason.TIMEOUT) break
        }

        if (selected == null) {
            val scheme = firstVersion?.let {
                parseScheme(it.stdout.ifBlank { it.stderr })
            } ?: RootScheme.NONE
            return@withContext RootProbeResult(
                state = RootState.Denied(lastReason),
                info = deniedInfo(
                    scheme = scheme,
                    versionResult = firstVersion,
                    versionCodeResult = null,
                    suPath = lastAttempt?.path ?: suPathStore.effectivePath,
                ),
                evidence = evidence,
            )
        }

        val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        val crossTimeout = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_CROSS_TIMEOUT_MS)
        val crossResult = shell.exec(CROSS_CHECK_SCRIPT, root = true, timeoutMs = crossTimeout)
        evidence += evidenceOf("交叉验证（方案与 SELinux）", crossResult)

        var magiskConfirmed = false
        var kernelsuConfirmed = false
        var apatchConfirmed = false
        var modulesConfirmed = false
        var selinux = ""
        if (crossResult.ok) {
            val facts = parseFacts(crossResult.stdout)
            magiskConfirmed = facts["magisk"] == "1"
            kernelsuConfirmed = facts["ksu"] == "1"
            apatchConfirmed = facts["ap"] == "1"
            modulesConfirmed = facts["modules"] == "1"
            selinux = facts["selinux"].orEmpty()
            evidence += "交叉验证 → magisk=${flag(magiskConfirmed)}, ksu=${flag(kernelsuConfirmed)}, " +
                "apatch=${flag(apatchConfirmed)}, modules=${flag(modulesConfirmed)}, selinux=${selinux.ifBlank { "未知" }}"
        } else {
            val reason = if (crossResult.code == ErrorCode.ERR_SU_TIMEOUT) {
                "超出 $TOTAL_BUDGET_MS ms 总预算"
            } else {
                "退出码 ${crossResult.code}"
            }
            evidence += "交叉验证未完成（$reason），方案以 su -v 为准，SELinux 状态未知"
        }

        val scheme = resolveScheme(
            parseScheme(selected.versionResult.stdout.ifBlank { selected.versionResult.stderr }),
            magiskConfirmed,
            kernelsuConfirmed,
            apatchConfirmed,
        )
        val info = RootInfo(
            scheme = scheme,
            version = resolveVersion(scheme, selected.versionResult, selected.versionCodeResult),
            suPath = selected.path,
            daemon = daemonOf(scheme),
            modulesDir = DEFAULT_MODULES_DIR,
            selinux = selinux,
            uid = selected.uid ?: 0,
        )
        RootProbeResult(RootState.Granted, info, evidence)
    }

    private fun evidenceOf(command: String, result: ShellResult): String {
        val raw = result.stdout.ifBlank { result.stderr }.trim().lineSequence().firstOrNull().orEmpty()
        val output = if (raw.length > MAX_EVIDENCE_OUTPUT) raw.take(MAX_EVIDENCE_OUTPUT) + "…" else raw
        return if (result.ok) {
            "$command → ${output.ifBlank { "（无输出）" }}"
        } else {
            "$command → 失败(code=${result.code})${if (output.isBlank()) "" else " $output"}"
        }
    }

    private fun parseScheme(text: String): RootScheme {
        if (text.isBlank()) return RootScheme.NONE
        val upper = text.uppercase()
        return when {
            upper.contains("KERNELSU") -> RootScheme.KERNELSU
            upper.contains("APATCH") -> RootScheme.APATCH
            upper.contains("MAGISK") -> RootScheme.MAGISK
            else -> RootScheme.OTHER
        }
    }

    private fun parseUid(text: String): Int? = UID_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun denialReason(idResult: ShellResult, versionResult: ShellResult): DenyReason {
        val combined = (idResult.stdout + "\n" + idResult.stderr + "\n" +
            versionResult.stdout + "\n" + versionResult.stderr).lowercase()
        return when {
            idResult.code == ErrorCode.ERR_SU_TIMEOUT ||
                versionResult.code == ErrorCode.ERR_SU_TIMEOUT -> DenyReason.TIMEOUT
            idResult.code == ErrorCode.ERR_SU_MISSING ||
                idResult.code == 126 || idResult.code == 127 ||
                combined.contains("not found") ||
                combined.contains("no such file") ||
                combined.contains("inaccessible") -> DenyReason.NO_BINARY
            idResult.code == ErrorCode.ERR_SELINUX ||
                combined.contains("avc: denied") -> DenyReason.SELINUX_BLOCKED
            idResult.code == ErrorCode.ERR_POLICY_BLOCKED ||
                combined.contains("device policy") -> DenyReason.POLICY_BLOCKED
            else -> DenyReason.USER_REJECTED
        }
    }

    private fun deniedInfo(
        scheme: RootScheme,
        versionResult: ShellResult?,
        versionCodeResult: ShellResult?,
        suPath: String,
    ): RootInfo? {
        if (scheme == RootScheme.NONE || versionResult == null) return null
        return RootInfo(
            scheme = scheme,
            version = resolveVersion(scheme, versionResult, versionCodeResult),
            suPath = suPath,
            daemon = daemonOf(scheme),
            modulesDir = DEFAULT_MODULES_DIR,
            selinux = "",
            uid = -1,
        )
    }

    private fun parseFacts(output: String): Map<String, String> {
        val facts = mutableMapOf<String, String>()
        output.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            facts[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return facts
    }

    private fun resolveScheme(
        primary: RootScheme,
        magisk: Boolean,
        kernelsu: Boolean,
        apatch: Boolean,
    ): RootScheme {
        if (primary == RootScheme.MAGISK || primary == RootScheme.KERNELSU || primary == RootScheme.APATCH) {
            return primary
        }
        return when {
            kernelsu -> RootScheme.KERNELSU
            magisk -> RootScheme.MAGISK
            apatch -> RootScheme.APATCH
            else -> RootScheme.OTHER
        }
    }

    private fun resolveVersion(
        scheme: RootScheme,
        versionResult: ShellResult,
        versionCodeResult: ShellResult?,
    ): String {
        val short = versionResult.stdout.trim().lineSequence().firstOrNull().orEmpty()
        val code = versionCodeResult?.stdout?.trim()?.lineSequence()?.firstOrNull().orEmpty()
        val fromShort = short.substringBefore(':').trim()
        return when (scheme) {
            RootScheme.MAGISK -> fromShort.ifEmpty { code }
            RootScheme.KERNELSU -> fromShort.ifEmpty { code }
            RootScheme.APATCH -> code.ifEmpty { short }
            else -> short.ifEmpty { code }
        }
    }

    private fun daemonOf(scheme: RootScheme): String = when (scheme) {
        RootScheme.MAGISK -> "magiskd"
        RootScheme.KERNELSU -> "ksud"
        RootScheme.APATCH -> "apd"
        RootScheme.OTHER, RootScheme.NONE -> ""
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private data class Attempt(
        val path: String,
        val versionResult: ShellResult,
        val versionCodeResult: ShellResult,
        val idResult: ShellResult,
        val uid: Int?,
    )

    private companion object {
        const val STEP_TIMEOUT_MS = 5_000L
        const val TOTAL_BUDGET_MS = 4_000L
        const val MIN_CROSS_TIMEOUT_MS = 500L
        const val MAX_EVIDENCE_OUTPUT = 160
        const val DEFAULT_MODULES_DIR = "/data/adb/modules"

        val UID_REGEX = Regex("uid=(\\d+)")

        val CROSS_CHECK_SCRIPT: String = """
            echo "magisk=$(test -d /data/adb/magisk && echo 1 || echo 0)"
            echo "ksu=$(test -x /data/adb/ksud && echo 1 || echo 0)"
            echo "ap=$(test -x /data/adb/apd && echo 1 || echo 0)"
            echo "modules=$(test -d /data/adb/modules && echo 1 || echo 0)"
            echo "selinux=$(getenforce 2>/dev/null)"
        """.trimIndent()
    }
}