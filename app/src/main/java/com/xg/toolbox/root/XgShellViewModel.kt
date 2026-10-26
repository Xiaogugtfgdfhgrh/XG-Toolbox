package com.xg.toolbox.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.XgTab
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.nav.route
import com.xg.toolbox.nav.routeToTab
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class XgShellViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val restoredTab: StateFlow<XgTab?> = settingsStore.lastScreen
        .map { stored -> routeToTab(stored) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    
    fun onTabSelected(tab: XgTab) {
        viewModelScope.launch {
            settingsStore.setLastScreen(tab.route)
        }
    }
}
