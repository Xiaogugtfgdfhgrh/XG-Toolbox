package com.xg.toolbox.feature.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xg.toolbox.core.ui.component.XgPageDirection
import com.xg.toolbox.core.ui.component.XgPageTransition
import com.xg.toolbox.feature.security.SecurityCapabilityIds

@Composable
fun SystemRoute(initialAnchor: String? = null) {
    var selectedSectionId by rememberSaveable {
        mutableStateOf(
            initialAnchor?.takeIf {
                SystemSection.byId(it) != null || SecurityCapabilityIds.isCapability(it)
            },
        )
    }
    var direction by remember { mutableStateOf(XgPageDirection.Forward) }

    val goBack: () -> Unit = {
        direction = XgPageDirection.Backward
        selectedSectionId = null
    }

    BackHandler(enabled = selectedSectionId != null, onBack = goBack)

    XgPageTransition(
        target = selectedSectionId,
        modifier = Modifier.fillMaxSize(),
        direction = direction,
    ) { targetId ->
        if (targetId == null) {
            SystemOverview(
                onOpen = { id ->
                    direction = XgPageDirection.Forward
                    selectedSectionId = id
                },
            )
        } else {
            SystemScreen(
                sectionId = targetId,
                onBack = goBack,
            )
        }
    }
}
