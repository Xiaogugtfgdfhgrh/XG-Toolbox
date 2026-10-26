package com.xg.toolbox.feature.security

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class SecurityStateStore {

    private val _uiState = MutableStateFlow(SecurityUiState())

    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<SecurityMessage>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: SharedFlow<SecurityMessage> = _messages.asSharedFlow()

    
    val current: SecurityUiState get() = _uiState.value

    fun update(block: (SecurityUiState) -> SecurityUiState) {
        _uiState.update(block)
    }

    
    fun show(message: SecurityMessage) {
        _messages.tryEmit(message)
    }
}
