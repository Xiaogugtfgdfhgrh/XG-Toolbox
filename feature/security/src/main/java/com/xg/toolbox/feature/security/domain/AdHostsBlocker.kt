package com.xg.toolbox.feature.security.domain

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightValueCache
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.data.BehaviorPreferences
import com.xg.toolbox.core.shell.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class AdHostsMethod { ROOT_BIND, VPN, IPTABLES, MODULE, BIND }

data class AdHostsStatus(
    val rootGranted: Boolean = false,
    val supported: Boolean = false,
    val method: AdHostsMethod? = null,
    val modulePath: String? = null,
    val targetPath: String? = null,
    val systemPath: String = "/system/etc/hosts",
    val enabled: Boolean = false,
    val domainCount: Int = 0,
    val appliedCount: Int = 0,
    val backupPath: String? = null,
    val message: String? = null,
)

@Singleton
class AdHostsBlocker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val behaviorPreferences: BehaviorPreferences,
) {

    private val cache = SingleFlightValueCache<AdHostsStatus>()

    suspend fun status(forceRefresh: Boolean = false): AdHostsStatus = withContext(Dispatchers.IO) {
        requireNotNull(
            cache.get(forceRefresh) {
                val allDomains = loadAllDomains()
                val blocked = selectedBlockedDomains(allDomains)
                val content = readGlobalHosts()
                val active = content?.contains(MARKER_START) == true
                val granted = rootGranted()
                AdHostsStatus(
                    rootGranted = granted,
                    supported = true,
                    method = AdHostsMethod.ROOT_BIND,
                    targetPath = TARGET_HOSTS,
                    enabled = active,
                    domainCount = allDomains.size,
                    appliedCount = countAppliedDomains(content),
                    message = when {
                        !granted -> "未授予 Root，无法写入 hosts"
                        active -> "已启用（root bind，重启后由后台协调器恢复）"
                        else -> "未启用；使用 root bind 写入 /system/etc/hosts"
                    },
                )
            },
        )
    }

    suspend fun apply(): XgResult<AdHostsStatus> = withContext(Dispatchers.IO) {
        try {
            if (!rootGranted()) {
                return@withContext XgResult.Err(ErrorCode.ERR_SU_DENIED, "需要 Root 才能写入 hosts")
            }
            val allDomains = loadAllDomains()
            val blocked = selectedBlockedDomains(allDomains)
            if (blocked.isEmpty()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "当前没有可拦截的域名")
            }
            unmountQuietly()
            val original = readGlobalHosts()?.let(::stripBlock)?.ifBlank { BASE_HOSTS } ?: BASE_HOSTS
            val content = buildString {
                append(original.trimEnd())
                append('\n')
                append(MARKER_START)
                append('\n')
                for (domain in blocked) {
                    append("0.0.0.0 ")
                    append(domain)
                    append('\n')
                }
                append(MARKER_END)
                append('\n')
            }
            val write = writeRootFile(WORK_HOSTS, content)
            if (!write.ok) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "写入临时 hosts 失败")
            }
            val mount = mountGlobal(WORK_HOSTS)
            if (!mount.ok) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "hosts 挂载失败：${firstLine(mount.stderr.ifBlank { mount.stdout })}",
                )
            }
            val active = readGlobalHosts()?.contains(MARKER_START) == true
            if (!active) {
                unmountQuietly()
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "hosts 已写入但仍未全局生效；请确认设备支持 su -M/全局挂载",
                )
            }
            val persisted = runCatching { behaviorPreferences.setAdHostsEnabled(true) }
            if (persisted.isFailure) {
                unmountQuietly()
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "hosts 已生效但用户启用状态保存失败，已撤销挂载",
                    persisted.exceptionOrNull()?.message,
                )
            }
            XgResult.Ok(status(forceRefresh = true))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "启用 hosts 失败", t)
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, t.message?.take(220) ?: "启用 hosts 失败")
        }
    }

    suspend fun restorePersisted(): XgResult<AdHostsStatus> = withContext(Dispatchers.IO) {
        val wanted = behaviorPreferences.adHostsEnabled.first()
        if (!wanted) {
            return@withContext XgResult.Ok(
                status(forceRefresh = true).copy(message = "用户未启用 hosts 过滤，跳过恢复"),
            )
        }
        val current = status(forceRefresh = true)
        if (current.enabled) {
            return@withContext XgResult.Ok(current.copy(message = "hosts 过滤已在 boot 后保持生效"))
        }
        apply()
    }

    suspend fun disable(): XgResult<AdHostsStatus> = withContext(Dispatchers.IO) {
        try {
            if (!rootGranted()) {
                return@withContext XgResult.Err(ErrorCode.ERR_SU_DENIED, "需要 Root 才能恢复 hosts")
            }
            unmountQuietly()
            runRoot("rm -f '$WORK_HOSTS'")
            val active = readGlobalHosts()?.contains(MARKER_START) == true
            if (active) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "hosts 仍处于挂载状态")
            }
            val persisted = runCatching { behaviorPreferences.setAdHostsEnabled(false) }
            if (persisted.isFailure) {
                val restored = apply()
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    if (restored is XgResult.Ok) {
                        "关闭状态保存失败，已恢复 hosts 挂载以免重启后状态错乱"
                    } else {
                        "关闭状态保存失败，且 hosts 恢复失败"
                    },
                    persisted.exceptionOrNull()?.message,
                )
            }
            XgResult.Ok(status(forceRefresh = true))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "关闭 hosts 失败", t)
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, t.message?.take(220) ?: "关闭 hosts 失败")
        }
    }

    private fun selectedBlockedDomains(all: List<String>): List<String> {
        val mode = AdDnsFilterStore.mode(context)
        val selected = AdDnsFilterStore.selected(context)
        return when (mode) {
            AdDnsFilterMode.ALL -> all
            AdDnsFilterMode.BLACKLIST -> all.filter { it in selected }
            AdDnsFilterMode.WHITELIST -> all.filter { it !in selected }
        }
    }

    private suspend fun mountGlobal(path: String): ShellResult {
        val result = runRoot("su -M -c 'mount -o bind $path $TARGET_HOSTS'")
        if (result.ok) return result
        return runRoot("mount -o bind $path $TARGET_HOSTS")
    }

    private suspend fun unmountQuietly() {
        runRoot("su -M -c 'umount $TARGET_HOSTS' 2>/dev/null")
        runRoot("umount $TARGET_HOSTS 2>/dev/null")
    }

    private suspend fun readGlobalHosts(): String? {
        val result = runRoot("su -M -c 'cat $TARGET_HOSTS'")
        if (result.ok) return result.stdout
        val fallback = runRoot("cat $TARGET_HOSTS")
        return if (fallback.ok) fallback.stdout else null
    }

    private suspend fun writeRootFile(path: String, content: String): ShellResult {
        val encoded = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        runRoot("printf '%s' '$encoded' | base64 -d > '$path'")
        return runRoot("chcon u:object_r:system_file:s0 '$path' 2>/dev/null")
    }

    private suspend fun runRoot(command: String): ShellResult =
        shell.exec(command, root = true, timeoutMs = 30_000L)

    private suspend fun rootGranted(): Boolean {
        if (rootStatus.state.value is RootState.Granted) return true
        if (rootStatus.state.value is RootState.Unknown) {
            val result = runRoot("id -u")
            return result.ok && result.stdout.trim() == "0"
        }
        return false
    }

    private fun loadAllDomains(): List<String> = try {
        context.assets.open(DOMAINS_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[0] in setOf("0.0.0.0", "127.0.0.1", "::1")) parts[1] else parts[0]
                }
                .filter { it.isNotBlank() && it != "localhost" }
                .distinct()
                .toList()
        }
    } catch (t: Throwable) {
        XgLog.e(TAG, "读取域名列表失败", t)
        emptyList()
    }

    private fun stripBlock(text: String): String {
        val start = text.indexOf(MARKER_START)
        if (start < 0) return text
        val end = text.indexOf(MARKER_END, start)
        if (end < 0) return text.substring(0, start)
        return text.removeRange(start, end + MARKER_END.length)
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()

    private fun countAppliedDomains(content: String?): Int {
        if (content == null) return 0
        val start = content.indexOf(MARKER_START)
        val end = content.indexOf(MARKER_END)
        if (start < 0 || end <= start) return 0
        return content
            .substring(start + MARKER_START.length, end)
            .lineSequence()
            .count { line -> line.isNotBlank() }
    }
    private companion object {
        const val TAG = "AdHostsBlocker"
        const val DOMAINS_ASSET = "ad_domains.txt"
        const val WORK_HOSTS = "/data/local/tmp/xg/hosts"
        const val TARGET_HOSTS = "/system/etc/hosts"
        const val MARKER_START = "# >>> xg-toolbox hosts >>>"
        const val MARKER_END = "# <<< xg-toolbox hosts <<<"
        const val BASE_HOSTS = "127.0.0.1 localhost\n::1 ip6-localhost\n"
    }
}
