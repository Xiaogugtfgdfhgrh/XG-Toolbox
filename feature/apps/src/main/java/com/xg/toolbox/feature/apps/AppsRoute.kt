package com.xg.toolbox.feature.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgPageDirection
import com.xg.toolbox.core.ui.component.XgPageTransition
import com.xg.toolbox.core.ui.component.XgToastHost
import com.xg.toolbox.core.ui.component.XgToastState
import com.xg.toolbox.feature.apps.ui.AndroidIdPage
import com.xg.toolbox.feature.apps.ui.AppDetailPage
import com.xg.toolbox.feature.apps.ui.AppsDialogs
import com.xg.toolbox.feature.apps.ui.AppsOverviewPage
import com.xg.toolbox.feature.apps.ui.BatchPage
import com.xg.toolbox.feature.apps.ui.ModulesPage

@Composable
fun AppsRoute(viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val globalToast = LocalXgToast.current
    val localToast = remember { XgToastState() }
    val toast = globalToast ?: localToast
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { message -> toast.show(message) }
    }
    var page by remember { mutableStateOf<AppsPage>(AppsPage.Overview) }
    var direction by remember { mutableStateOf(XgPageDirection.Forward) }
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is AppsEvent.BackToOverview) {
                direction = XgPageDirection.Backward
                page = AppsPage.Overview
            }
        }
    }
    BackHandler(enabled = page != AppsPage.Overview) {
        when (page) {
            is AppsPage.Detail,
            AppsPage.Modules,
            AppsPage.AndroidId -> {
                direction = XgPageDirection.Backward
                page = AppsPage.Overview
            }

            AppsPage.Batch -> {
                if (!state.batch.executing) {
                    if (state.batch.step != BatchStep.SELECT) {
                        viewModel.backToBatchSelect()
                    } else {
                        viewModel.resetBatch()
                        direction = XgPageDirection.Backward
                        page = AppsPage.Overview
                    }
                }
            }

            AppsPage.Overview -> Unit
        }
    }

    XgPageTransition(target = page, direction = direction) { target ->
        when (target) {
            AppsPage.Overview -> AppsOverviewPage(
                state = state,
                listState = listState,
                viewModel = viewModel,
                onOpenDetail = { packageName ->
                    direction = XgPageDirection.Forward
                    page = AppsPage.Detail(packageName)
                },
                onOpenModules = {
                    direction = XgPageDirection.Forward
                    page = AppsPage.Modules
                },
                onOpenBatch = {
                    viewModel.openBatch()
                    direction = XgPageDirection.Forward
                    page = AppsPage.Batch
                },
                onOpenAndroidId = {
                    direction = XgPageDirection.Forward
                    page = AppsPage.AndroidId
                },
            )

            is AppsPage.Detail -> {
                val packageName = target.packageName
                LaunchedEffect(packageName) { viewModel.loadDetail(packageName) }
                AppDetailPage(
                    state = state,
                    packageName = packageName,
                    onBack = {
                        direction = XgPageDirection.Backward
                        page = AppsPage.Overview
                    },
                    viewModel = viewModel,
                )
            }

            AppsPage.Modules -> {
                LaunchedEffect(Unit) { viewModel.loadModules() }
                ModulesPage(
                    state = state,
                    onBack = {
                        direction = XgPageDirection.Backward
                        page = AppsPage.Overview
                    },
                    viewModel = viewModel,
                )
            }

            AppsPage.Batch -> BatchPage(
                state = state,
                onBack = {
                    if (!state.batch.executing) {
                        if (state.batch.step != BatchStep.SELECT) {
                            viewModel.backToBatchSelect()
                        } else {
                            viewModel.resetBatch()
                            direction = XgPageDirection.Backward
                            page = AppsPage.Overview
                        }
                    }
                },
                onClose = {
                    viewModel.resetBatch()
                    direction = XgPageDirection.Backward
                    page = AppsPage.Overview
                },
                viewModel = viewModel,
            )

            AppsPage.AndroidId -> {
                LaunchedEffect(Unit) { viewModel.openAndroidIdPage() }
                AndroidIdPage(
                    state = state,
                    onBack = {
                        direction = XgPageDirection.Backward
                        page = AppsPage.Overview
                    },
                    viewModel = viewModel,
                )
            }
        }
    }

    AppsDialogs(state = state, viewModel = viewModel)
    if (globalToast == null) {
        XgToastHost(state = localToast)
    }
}

