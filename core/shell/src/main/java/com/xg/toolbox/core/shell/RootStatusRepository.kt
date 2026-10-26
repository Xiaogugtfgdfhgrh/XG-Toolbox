package com.xg.toolbox.core.shell

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RootStatusRepository @Inject constructor(
    private val rootProbe: RootProbe,
    private val capabilityProbe: CapabilityProbe,
    private val rootShell: RootShell,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val capabilityMutex = Mutex()

    private val _state = MutableStateFlow<RootState>(RootState.Unknown)

    
    val state: StateFlow<RootState> = _state.asStateFlow()

    private val _rootInfo = MutableStateFlow<RootInfo?>(null)

    
    val rootInfo: StateFlow<RootInfo?> = _rootInfo.asStateFlow()

    private val _capabilities = MutableStateFlow<Capabilities?>(null)

    
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private val _evidence = MutableStateFlow<List<String>>(emptyList())

    
    val evidence: StateFlow<List<String>> = _evidence.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        scope.launch {
            rootShell.rootLossEvents.collect { reason -> onRootLost(reason) }
        }
    }

    
    suspend fun refresh(): RootState = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            _refreshing.value = true
            try {
                val probeResult = rootProbe.probe()
                _rootInfo.value = probeResult.info
                _state.value = probeResult.state
                val evidence = probeResult.evidence.toMutableList()
                if (probeResult.state is RootState.Granted) {
                    val report = capabilityProbe.probe()
                    _capabilities.value = report.capabilities
                    evidence += report.evidence
                } else {
                    _capabilities.value = null
                }
                _evidence.value = evidence
                _state.value
            } finally {
                _refreshing.value = false
            }
        }
    }

    
    suspend fun ensureCapabilities(): Capabilities? = withContext(Dispatchers.IO) {
        if (_state.value !is RootState.Granted) return@withContext null
        capabilityMutex.withLock {
            val cached = _capabilities.value
            if (cached != null) return@withLock cached
            val report = capabilityProbe.probe()
            _capabilities.value = report.capabilities
            _evidence.value = _evidence.value + report.evidence
            report.capabilities
        }
    }

    private fun onRootLost(reason: DenyReason) {
        if (_state.value !is RootState.Granted) return
        _capabilities.value = null
        _evidence.value = _evidence.value + "运行时 Root 权限失效：${reason.displayText()}"
        _state.value = RootState.Denied(reason)
    }
}

fun RootState.rootError(): XgResult.Err? = when (this) {
    is RootState.Granted -> null
    is RootState.Unknown -> XgResult.Err(
        code = ErrorCode.ERR_SU_DENIED,
        message = "Root 状态尚未确认，请重新检测",
    )
    is RootState.Unsupported -> XgResult.Err(
        code = ErrorCode.ERR_UNSUPPORTED_DEVICE,
        message = if (reason.isBlank()) ErrorCatalog.message(ErrorCode.ERR_UNSUPPORTED_DEVICE) else reason,
    )
    is RootState.Denied -> when (reason) {
        DenyReason.NO_BINARY -> XgResult.Err(ErrorCode.ERR_SU_MISSING, ErrorCatalog.message(ErrorCode.ERR_SU_MISSING))
        DenyReason.USER_REJECTED -> XgResult.Err(ErrorCode.ERR_SU_DENIED, ErrorCatalog.message(ErrorCode.ERR_SU_DENIED))
        DenyReason.TIMEOUT -> XgResult.Err(ErrorCode.ERR_SU_TIMEOUT, ErrorCatalog.message(ErrorCode.ERR_SU_TIMEOUT))
        DenyReason.POLICY_BLOCKED -> XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, ErrorCatalog.message(ErrorCode.ERR_POLICY_BLOCKED))
        DenyReason.SELINUX_BLOCKED -> XgResult.Err(ErrorCode.ERR_SELINUX, ErrorCatalog.message(ErrorCode.ERR_SELINUX))
    }
}
