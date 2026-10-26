package com.xg.toolbox.feature.security.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.clone.ClonePhase
import com.xg.toolbox.core.clone.CloneProgress
import com.xg.toolbox.core.clone.SelfCloneManager
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppearancePreferences
import com.xg.toolbox.core.data.BackgroundImageStore
import com.xg.toolbox.core.data.BehaviorPreferences
import com.xg.toolbox.core.rootops.RootOpsManager
import com.xg.toolbox.core.rootops.RootOpsStatus
import com.xg.toolbox.core.shell.SuPathStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val customBackgroundEnabled: Boolean = false,
    val backgroundFileName: String? = null,
    val backgroundFile: File? = null,
    val backgroundMissing: Boolean = false,
    val backgroundImporting: Boolean = false,
    val backgroundMessage: String? = null,
    val backgroundMessageIsError: Boolean = false,
    val autostartEnabled: Boolean = false,
    val autostartBusy: Boolean = false,
    val autostartMessage: String? = null,
    val rootKeepAliveEnabled: Boolean = false,
    val keepAliveBusy: Boolean = false,
    val keepAliveMessage: String? = null,
    val rootStatus: RootOpsStatus = RootOpsStatus(),
    val rootStatusRefreshing: Boolean = true,
    val privilegeBusy: Boolean = false,
    val privilegeOutput: String? = null,
    val privilegeOutputIsError: Boolean = false,
    val suCustomPath: String? = null,
    val suEffectivePath: String = "",
    val suInput: String = "",
    val suBusy: Boolean = false,
    val suMessage: String? = null,
    val suMessageIsError: Boolean = false,
    val hideConfirmVisible: Boolean = false,
    val hideRunning: Boolean = false,
    val hideResultPackage: String? = null,
    val hideError: String? = null,
    val cloneProgress: CloneProgress? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appearancePreferences: AppearancePreferences,
    private val backgroundImageStore: BackgroundImageStore,
    private val behaviorPreferences: BehaviorPreferences,
    private val suPathStore: SuPathStore,
    private val rootOpsManager: RootOpsManager,
    private val selfCloneManager: SelfCloneManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var suInputEdited = false
    private var rootRefreshJob: Job? = null

    init {
        observeAppearance()
        observeBehavior()
        observeSuPath()
        observeRootStatus()
        observeCloneProgress()
        refreshRootStatus()
    }

    private fun observeAppearance() {
        viewModelScope.launch {
            combine(
                appearancePreferences.customBackgroundEnabled,
                appearancePreferences.backgroundFileName,
            ) { enabled, fileName ->
                enabled to fileName
            }.collect { (enabled, fileName) ->
                val nameChanged = _uiState.value.backgroundFileName != fileName
                _uiState.update { current ->
                    current.copy(
                        customBackgroundEnabled = enabled,
                        backgroundFileName = fileName,
                        backgroundFile = if (nameChanged) null else current.backgroundFile,
                        backgroundMissing = false,
                    )
                }
                if (nameChanged || fileName != null) {
                    refreshBackgroundFile(fileName)
                }
            }
        }
    }

    private fun observeBehavior() {
        viewModelScope.launch {
            combine(
                behaviorPreferences.autostartEnabled,
                behaviorPreferences.rootKeepAliveEnabled,
            ) { autostart, keepAlive -> autostart to keepAlive }
                .collect { (autostart, keepAlive) ->
                    _uiState.update {
                        it.copy(
                            autostartEnabled = if (it.autostartBusy) it.autostartEnabled else autostart,
                            rootKeepAliveEnabled = keepAlive,
                        )
                    }
                }
        }
    }

    private fun observeSuPath() {
        viewModelScope.launch {
            suPathStore.customPath.collect { customPath ->
                val effective = effectiveSuPath()
                _uiState.update { current ->
                    current.copy(
                        suCustomPath = customPath,
                        suEffectivePath = effective,
                        suInput = if (suInputEdited) current.suInput else customPath.orEmpty(),
                    )
                }
            }
        }
    }

    private fun observeRootStatus() {
        viewModelScope.launch {
            rootOpsManager.status.collect { status ->
                _uiState.update { it.copy(rootStatus = status) }
            }
        }
    }

    private fun observeCloneProgress() {
        viewModelScope.launch {
            selfCloneManager.progress.collect { progress ->
                _uiState.update { it.copy(cloneProgress = progress) }
            }
        }
    }

    fun refreshRootStatus() {
        if (rootRefreshJob?.isActive == true) return
        _uiState.update { it.copy(rootStatusRefreshing = true) }
        rootRefreshJob = viewModelScope.launch {
            try {
                rootOpsManager.refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        rootStatus = current.rootStatus.copy(
                            message = t.message?.takeIf { it.isNotBlank() }
                                ?: "Root 状态检测失败",
                        ),
                    )
                }
            } finally {
                _uiState.update { it.copy(rootStatusRefreshing = false) }
                rootRefreshJob = null
            }
        }
    }

    fun onCustomBackgroundEnabledChange(enabled: Boolean) {
        val previous = _uiState.value.customBackgroundEnabled
        _uiState.update {
            it.copy(
                customBackgroundEnabled = enabled,
                backgroundMessage = if (enabled && it.backgroundFile == null) {
                    "请选择一张背景图；未选择时不会显示自定义背景。"
                } else {
                    null
                },
                backgroundMessageIsError = false,
            )
        }
        viewModelScope.launch {
            try {
                appearancePreferences.setCustomBackgroundEnabled(enabled)
                if (enabled && _uiState.value.backgroundFile == null) {
                    _uiState.update {
                        it.copy(
                            backgroundMessage = "请选择一张背景图；未选择时不会显示自定义背景。",
                        )
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(customBackgroundEnabled = previous, backgroundMessage = null)
                }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        customBackgroundEnabled = previous,
                        backgroundMessage = "自定义背景设置保存失败：${t.settingsUserMessage()}",
                        backgroundMessageIsError = true,
                    )
                }
            }
        }
    }

    fun importBackground(uri: Uri) {
        if (_uiState.value.backgroundImporting) return
        val previousFileName = _uiState.value.backgroundFileName
        val previousFile = _uiState.value.backgroundFile
        val previousEnabled = _uiState.value.customBackgroundEnabled
        var importedFileName: String? = null
        _uiState.update {
            it.copy(
                backgroundImporting = true,
                backgroundMessage = null,
                backgroundMessageIsError = false,
            )
        }
        viewModelScope.launch {
            try {
                val imported = backgroundImageStore.import(uri)
                val fileName = imported.getOrNull()
                if (fileName != null) {
                    importedFileName = fileName
                    appearancePreferences.setBackgroundFileName(fileName)
                    appearancePreferences.setCustomBackgroundEnabled(true)
                    val file = withContext(Dispatchers.IO) {
                        backgroundImageStore.fileFor(fileName)
                    } ?: throw IOException("导入的图片文件不可读")
                    if (previousFileName != null && previousFileName != fileName) {
                        withContext(NonCancellable + Dispatchers.IO) { backgroundImageStore.delete(previousFileName) }
                    }
                    _uiState.update {
                        it.copy(
                            backgroundFileName = fileName,
                            backgroundFile = file,
                            backgroundMissing = false,
                            customBackgroundEnabled = true,
                            backgroundImporting = false,
                            backgroundMessage = "背景图已导入并启用。",
                            backgroundMessageIsError = false,
                        )
                    }
                } else {
                    val failure = imported.exceptionOrNull()
                    _uiState.update {
                        it.copy(
                            backgroundImporting = false,
                            backgroundMessage = "背景图处理失败：${failure?.settingsUserMessage() ?: "未知错误"}",
                            backgroundMessageIsError = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    rollbackBackgroundImport(importedFileName, previousFileName, previousEnabled)
                }
                _uiState.update {
                    it.copy(
                        backgroundImporting = false,
                        backgroundFileName = previousFileName,
                        backgroundFile = previousFile,
                        customBackgroundEnabled = previousEnabled,
                    )
                }
                throw e
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    rollbackBackgroundImport(importedFileName, previousFileName, previousEnabled)
                }
                _uiState.update {
                    it.copy(
                        backgroundImporting = false,
                        backgroundFileName = previousFileName,
                        backgroundFile = previousFile,
                        customBackgroundEnabled = previousEnabled,
                        backgroundMessage = "背景图处理失败：${t.settingsUserMessage()}",
                        backgroundMessageIsError = true,
                    )
                }
            }
        }
    }

    private suspend fun rollbackBackgroundImport(
        importedFileName: String?,
        previousFileName: String?,
        previousEnabled: Boolean,
    ) {
        if (importedFileName == null) return
        runCatching { appearancePreferences.setBackgroundFileName(previousFileName) }
        runCatching { appearancePreferences.setCustomBackgroundEnabled(previousEnabled) }
        withContext(Dispatchers.IO) { backgroundImageStore.delete(importedFileName) }
    }

    fun onAutostartEnabledChange(enabled: Boolean) {
        val state = _uiState.value
        if (state.autostartBusy) return
        val previous = state.autostartEnabled
        _uiState.update {
            it.copy(
                autostartEnabled = enabled,
                autostartBusy = true,
                autostartMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = rootOpsManager.setAutostartEnabled(enabled)) {
                    is XgResult.Ok -> {
                        _uiState.update {
                            it.copy(
                                autostartEnabled = enabled,
                                autostartBusy = false,
                                autostartMessage = result.data,
                            )
                        }
                        refreshRootStatus()
                    }

                    is XgResult.Err -> {
                        val detail = result.detail
                            ?.lineSequence()
                            ?.firstOrNull { it.isNotBlank() }
                            ?.take(160)
                        _uiState.update {
                            it.copy(
                                autostartEnabled = previous,
                                autostartBusy = false,
                                autostartMessage = buildString {
                                    append("自启动设置失败，开关已还原：${result.message}")
                                    if (!detail.isNullOrBlank()) append("（$detail）")
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        autostartEnabled = previous,
                        autostartBusy = false,
                    )
                }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        autostartEnabled = previous,
                        autostartBusy = false,
                        autostartMessage = "自启动设置失败，开关已还原：${t.settingsUserMessage()}",
                    )
                }
            }
        }
    }

    fun onRootKeepAliveEnabledChange(enabled: Boolean) {
        val state = _uiState.value
        if (state.keepAliveBusy) return
        val previous = state.rootKeepAliveEnabled
        _uiState.update {
            it.copy(
                rootKeepAliveEnabled = enabled,
                keepAliveBusy = true,
                keepAliveMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = rootOpsManager.setRootKeepAliveEnabled(enabled)) {
                    is XgResult.Ok -> {
                        try {
                            behaviorPreferences.setRootKeepAliveEnabled(enabled)
                            _uiState.update {
                                it.copy(
                                    keepAliveBusy = false,
                                    keepAliveMessage = if (enabled) {
                                        "已请求启动 root 保活守护进程；实际状态以检测结果为准。"
                                    } else {
                                        "已请求停止 root 保活守护进程。"
                                    },
                                )
                            }
                        } catch (t: Throwable) {
                            _uiState.update {
                                it.copy(
                                    rootKeepAliveEnabled = previous,
                                    keepAliveBusy = false,
                                    keepAliveMessage = "系统已接受操作，但本地偏好保存失败：${t.settingsUserMessage()}",
                                )
                            }
                        }
                        refreshRootStatus()
                    }

                    is XgResult.Err -> {
                        _uiState.update {
                            it.copy(
                                rootKeepAliveEnabled = previous,
                                keepAliveBusy = false,
                                keepAliveMessage = "后台保活设置失败，开关已还原：${result.settingsDescription()}",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        rootKeepAliveEnabled = previous,
                        keepAliveBusy = false,
                    )
                }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        rootKeepAliveEnabled = previous,
                        keepAliveBusy = false,
                        keepAliveMessage = "后台保活设置失败，开关已还原：${t.settingsUserMessage()}",
                    )
                }
            }
        }
    }

    fun provisionPrivileges() {
        if (_uiState.value.privilegeBusy) return
        _uiState.update {
            it.copy(
                privilegeBusy = true,
                privilegeOutput = null,
                privilegeOutputIsError = false,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = rootOpsManager.provisionPrivileges()) {
                    is XgResult.Ok -> {
                        _uiState.update {
                            it.copy(
                                privilegeBusy = false,
                                privilegeOutput = result.data,
                                privilegeOutputIsError = false,
                            )
                        }
                    }

                    is XgResult.Err -> {
                        _uiState.update {
                            it.copy(
                                privilegeBusy = false,
                                privilegeOutput = result.settingsDescription(),
                                privilegeOutputIsError = true,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(privilegeBusy = false) }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        privilegeBusy = false,
                        privilegeOutput = t.settingsUserMessage(),
                        privilegeOutputIsError = true,
                    )
                }
            }
            refreshRootStatus()
        }
    }

    fun onSuInputChanged(value: String) {
        suInputEdited = true
        _uiState.update {
            it.copy(
                suInput = value,
                suMessage = null,
                suMessageIsError = false,
            )
        }
    }

    fun useSuPreset(path: String) {
        suInputEdited = true
        _uiState.update {
            it.copy(
                suInput = path,
                suMessage = "已填入预设路径，请先测试再保存。",
                suMessageIsError = false,
            )
        }
    }

    fun testAndSaveSuPath() {
        val state = _uiState.value
        if (state.suBusy) return
        val path = state.suInput.trim()
        if (path.isEmpty()) {
            _uiState.update {
                it.copy(
                    suMessage = "请输入 SU 可执行文件路径。",
                    suMessageIsError = true,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                suBusy = true,
                suMessage = null,
                suMessageIsError = false,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = rootOpsManager.testCustomSuPath(path)) {
                    is XgResult.Ok -> {
                        val saveFailure = try {
                            withContext(Dispatchers.IO) { suPathStore.setCustomPath(path) }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            t
                        }
                        if (saveFailure != null) {
                            _uiState.update {
                                it.copy(
                                    suBusy = false,
                                    suInput = path,
                                    suMessage = "测试成功，但保存路径失败：${saveFailure.settingsUserMessage()}",
                                    suMessageIsError = true,
                                )
                            }
                        } else {
                            suInputEdited = false
                            val effective = effectiveSuPath()
                            val detail = result.data.trim()
                            _uiState.update {
                                it.copy(
                                    suBusy = false,
                                    suInput = path,
                                    suCustomPath = path,
                                    suEffectivePath = effective,
                                    suMessage = buildString {
                                        append("测试成功，路径已保存。")
                                        if (detail.isNotEmpty()) {
                                            append('\n')
                                            append(detail)
                                        }
                                    },
                                    suMessageIsError = false,
                                )
                            }
                        }
                    }

                    is XgResult.Err -> {
                        _uiState.update {
                            it.copy(
                                suBusy = false,
                                suMessage = "测试失败，路径未保存：${result.settingsDescription()}",
                                suMessageIsError = true,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(suBusy = false) }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        suBusy = false,
                        suMessage = "测试失败，路径未保存：${t.settingsUserMessage()}",
                        suMessageIsError = true,
                    )
                }
            }
        }
    }

    fun restoreAutoSuPath() {
        if (_uiState.value.suBusy) return
        _uiState.update {
            it.copy(
                suBusy = true,
                suMessage = null,
                suMessageIsError = false,
            )
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { suPathStore.clear() }
                suInputEdited = false
                val effective = effectiveSuPath()
                _uiState.update {
                    it.copy(
                        suBusy = false,
                        suInput = "",
                        suCustomPath = null,
                        suEffectivePath = effective,
                        suMessage = "已清除自定义路径，恢复自动探测。",
                        suMessageIsError = false,
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(suBusy = false) }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        suBusy = false,
                        suMessage = "恢复自动探测失败：${t.settingsUserMessage()}",
                        suMessageIsError = true,
                    )
                }
            }
        }
    }

    fun requestHideSelf() {
        if (_uiState.value.hideRunning || isCloneActive(_uiState.value.cloneProgress)) return
        _uiState.update {
            it.copy(
                hideConfirmVisible = true,
                hideResultPackage = null,
                hideError = null,
            )
        }
    }

    fun dismissHideConfirm() {
        _uiState.update { it.copy(hideConfirmVisible = false) }
    }

    fun confirmHideSelf() {
        if (_uiState.value.hideRunning || isCloneActive(_uiState.value.cloneProgress)) return
        _uiState.update {
            it.copy(
                hideConfirmVisible = false,
                hideRunning = true,
                hideResultPackage = null,
                hideError = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = selfCloneManager.hideSelf()) {
                    is XgResult.Ok -> {
                        _uiState.update {
                            it.copy(
                                hideRunning = false,
                                hideResultPackage = result.data,
                                hideError = null,
                            )
                        }
                    }

                    is XgResult.Err -> {
                        _uiState.update {
                            it.copy(
                                hideRunning = false,
                                hideResultPackage = null,
                                hideError = result.settingsDescription(),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(hideRunning = false) }
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        hideRunning = false,
                        hideResultPackage = null,
                        hideError = t.settingsUserMessage(),
                    )
                }
            }
        }
    }

    private fun refreshBackgroundFile(fileName: String?) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { backgroundImageStore.fileFor(fileName) }.getOrNull()
            }
            _uiState.update { current ->
                if (current.backgroundFileName != fileName) {
                    current
                } else {
                    current.copy(
                        backgroundFile = file,
                        backgroundMissing = fileName != null && file == null,
                    )
                }
            }
        }
    }

    private suspend fun effectiveSuPath(): String = withContext(Dispatchers.IO) {
        runCatching { suPathStore.effectivePath }.getOrDefault("")
    }

    private fun isCloneActive(progress: CloneProgress?): Boolean = when (progress?.phase) {
        null,
        ClonePhase.IDLE,
        ClonePhase.DONE,
        ClonePhase.FAILED
        -> false

        ClonePhase.PREPARING,
        ClonePhase.PATCHING,
        ClonePhase.SIGNING,
        ClonePhase.INSTALLING,
        ClonePhase.MIGRATING
        -> true
    }

}

internal fun Throwable.settingsUserMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

internal fun XgResult.Err.settingsDescription(): String = buildString {
    append(message.ifBlank { "未知错误" })
    detail?.takeIf { it.isNotBlank() }?.let {
        append('\n')
        append(it)
    }
}
