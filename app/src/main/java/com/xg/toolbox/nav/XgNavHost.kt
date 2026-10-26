package com.xg.toolbox.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xg.toolbox.core.common.XgTab
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseInCubic
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.xgMotionDuration
import com.xg.toolbox.feature.apps.AppsRoute
import com.xg.toolbox.feature.device.DeviceRoute
import com.xg.toolbox.feature.home.HomeRoute
import com.xg.toolbox.feature.security.settings.SettingsRoute
import com.xg.toolbox.feature.system.SystemRoute
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object XgRoutes {
    const val HOME = "home"
    const val DEVICE = "device"
    const val APPS = "apps"
    const val SYSTEM = "system"
    const val SECURITY = "security"
}

internal object XgNavMemory {
    var lastTab: XgTab = XgTab.HOME
}

internal val XgTab.route: String
    get() = when (this) {
        XgTab.HOME -> XgRoutes.HOME
        XgTab.DEVICE -> XgRoutes.DEVICE
        XgTab.APPS -> XgRoutes.APPS
        XgTab.SYSTEM -> XgRoutes.SYSTEM
        XgTab.SECURITY -> XgRoutes.SECURITY
    }

internal fun routeToTab(route: String?): XgTab? = when (route) {
    XgRoutes.HOME -> XgTab.HOME
    XgRoutes.DEVICE -> XgTab.DEVICE
    XgRoutes.APPS -> XgTab.APPS
    XgRoutes.SYSTEM -> XgTab.SYSTEM
    XgRoutes.SECURITY -> XgTab.SECURITY
    else -> null
}

@Composable
fun XgNavHost(
    currentTab: XgTab,
    onSelectTab: (XgTab) -> Unit,
    modifier: Modifier = Modifier,
    startTab: XgTab = XgNavMemory.lastTab,
) {
    val onSelectState = rememberUpdatedState(onSelectTab)
    val initialTab = remember { startTab }
    var visitedOrder by remember { mutableStateOf(listOf(initialTab)) }
    val directionTracker = remember { TabDirectionTracker(initialTab) }
    val direction = directionTracker.directionTo(currentTab)
    val outgoingTab = directionTracker.outgoingTab

    LaunchedEffect(currentTab) {
        if (currentTab !in visitedOrder) {
            visitedOrder = (visitedOrder + currentTab).distinct()
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(TAB_PRELOAD_START_DELAY_MS)
        XgTab.entries.forEach { tab ->
            if (tab != initialTab && tab !in visitedOrder) {
                visitedOrder = (visitedOrder + tab).distinct()
            }
            delay(TAB_PRELOAD_STAGGER_MS)
        }
    }

    val renderedOrder = (visitedOrder + currentTab).distinct()
    Box(modifier = modifier.clipToBounds()) {
        renderedOrder.forEach { tab ->
            key(tab) {
                XgTabPage(
                    tab = tab,
                    selected = tab == currentTab,
                    isInitialTab = tab == initialTab,
                    direction = direction,
                    isOutgoing = tab == outgoingTab,
                    onSelectState = onSelectState,
                )
            }
        }
    }
}

@Composable
private fun XgTabContent(
    tab: XgTab,
    onSelectState: State<(XgTab) -> Unit>,
) {
    when (tab) {
        XgTab.HOME -> HomeRoute(onOpenTab = { target -> onSelectState.value(target) })
        XgTab.DEVICE -> DeviceRoute()
        XgTab.APPS -> AppsRoute()
        XgTab.SYSTEM -> SystemRoute()
        XgTab.SECURITY -> SettingsRoute()
    }
}

@Composable
private fun XgTabPage(
    tab: XgTab,
    selected: Boolean,
    isInitialTab: Boolean,
    direction: Int,
    isOutgoing: Boolean,
    onSelectState: State<(XgTab) -> Unit>,
) {
    val parentLifecycleOwner = LocalLifecycleOwner.current
    val lifecycleOwner = remember { TabLifecycleOwner().apply { setActive(isInitialTab) } }
    val motion = LocalXgMotionScale.current
    val density = LocalDensity.current
    var hasAppeared by remember { mutableStateOf(isInitialTab) }
    val pageAlpha = remember { Animatable(if (isInitialTab) 1f else 0f) }
    val pageShift = remember { Animatable(0f) }
    val enterPx = with(density) { TAB_ENTER_SHIFT.toPx() }
    val exitPx = with(density) { TAB_EXIT_SHIFT.toPx() }
    val travelDirection = if (direction == 0) 1 else direction

    LaunchedEffect(selected, direction, isOutgoing) {
        val duration = xgMotionDuration(TAB_TRANSITION_DURATION_MS, motion)
        if (selected) {
            if (!hasAppeared) {
                hasAppeared = true
                pageAlpha.snapTo(0f)
                pageShift.snapTo(travelDirection * enterPx)
            } else if (pageAlpha.value < 0.01f) {
                pageShift.snapTo(travelDirection * enterPx)
            }
            if (duration <= 0) {
                pageAlpha.snapTo(1f)
                pageShift.snapTo(0f)
            } else {
                coroutineScope {
                    launch { pageAlpha.animateTo(1f, tween(duration, easing = XgEaseOutCubic)) }
                    launch { pageShift.animateTo(0f, tween(duration, easing = XgEaseOutCubic)) }
                }
            }
        } else if (hasAppeared) {
            if (!isOutgoing) {
                pageAlpha.snapTo(0f)
                pageShift.snapTo(0f)
                return@LaunchedEffect
            }
            if (duration <= 0) {
                pageAlpha.snapTo(0f)
                pageShift.snapTo(-travelDirection * exitPx)
            } else {
                coroutineScope {
                    launch { pageAlpha.animateTo(0f, tween(duration, easing = XgEaseInCubic)) }
                    launch { pageShift.animateTo(-travelDirection * exitPx, tween(duration, easing = XgEaseInCubic)) }
                }
            }
        }
    }

    DisposableEffect(parentLifecycleOwner, selected) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                lifecycleOwner.setActive(
                    selected && parentLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                )
            }
        }
        parentLifecycleOwner.lifecycle.addObserver(observer)
        lifecycleOwner.setActive(
            selected && parentLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        onDispose { parentLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selected) 1f else 0f)
                .then(if (selected) Modifier else Modifier.hiddenPageGuard())
                .graphicsLayer {
                    alpha = pageAlpha.value
                    translationX = pageShift.value
                }
                .drawWithContent {
                    if (pageAlpha.value > 0.001f) drawContent()
                },
        ) {
            XgTabContent(tab = tab, onSelectState = onSelectState)
        }
    }
}

private fun Modifier.hiddenPageGuard(): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { change -> change.consume() }
            }
        }
    }
    .clearAndSetSemantics { }

private class TabDirectionTracker(initialTab: XgTab) {
    private var lastTab = initialTab
    private var direction = 0
    var outgoingTab: XgTab? = null
        private set

    fun directionTo(nextTab: XgTab): Int {
        if (nextTab != lastTab) {
            outgoingTab = lastTab
            direction = if (nextTab.ordinal > lastTab.ordinal) 1 else -1
            lastTab = nextTab
        }
        return direction
    }
}

private const val TAB_TRANSITION_DURATION_MS = 400
private const val TAB_PRELOAD_START_DELAY_MS = 140L
private const val TAB_PRELOAD_STAGGER_MS = 160L
private val TAB_ENTER_SHIFT = 18.dp
private val TAB_EXIT_SHIFT = 14.dp

private class TabLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun setActive(active: Boolean) {
        registry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }
}

@Composable
internal fun XgTabBackHandler(currentTab: XgTab?, onBackToHome: () -> Unit) {
    BackHandler(
        enabled = currentTab != null && currentTab != XgTab.HOME,
        onBack = onBackToHome,
    )
}
