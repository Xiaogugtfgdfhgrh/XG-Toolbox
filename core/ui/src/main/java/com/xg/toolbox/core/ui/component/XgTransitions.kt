package com.xg.toolbox.core.ui.component

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.LocalXgReduceMotion
import com.xg.toolbox.core.ui.theme.XgEaseInCubic
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgMotionDuration
import com.xg.toolbox.core.ui.theme.xgTween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class XgPageDirection {
    Forward,
    Backward,
}

@Composable
fun <T> XgPageTransition(
    target: T,
    modifier: Modifier = Modifier,
    direction: XgPageDirection = XgPageDirection.Forward,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalXgMotionScale.current
    if (motion <= 0f) {
        Box(modifier = modifier) { content(target) }
        return
    }
    val density = LocalDensity.current
    val enterPx = with(density) { 24.dp.roundToPx() }
    val exitPx = with(density) { 16.dp.roundToPx() }
    val forward = direction == XgPageDirection.Forward
    val duration = xgMotionDuration(if (forward) 180 else 160, motion)
    val easing = if (forward) XgEaseOutCubic else XgEaseInCubic
    val slideSpec = tween<IntOffset>(durationMillis = duration, easing = easing)
    val fadeSpec = tween<Float>(durationMillis = duration, easing = easing)

    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (forward) {
                (slideInHorizontally(animationSpec = slideSpec) { enterPx } + fadeIn(animationSpec = fadeSpec)) togetherWith
                    (slideOutHorizontally(animationSpec = slideSpec) { -exitPx } + fadeOut(animationSpec = fadeSpec))
            } else {
                (slideInHorizontally(animationSpec = slideSpec) { -enterPx } + fadeIn(animationSpec = fadeSpec)) togetherWith
                    (slideOutHorizontally(animationSpec = slideSpec) { exitPx } + fadeOut(animationSpec = fadeSpec))
            }
        },
        label = "xgPageTransition",
    ) { state ->
        content(state)
    }
}

@Composable
fun XgContentTransition(
    stateKey: Any,
    loading: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    skeletonLines: Int = 4,
    showInlineError: Boolean = true,
    initiallyLoaded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val motion = LocalXgMotionScale.current
    val reduceMotion = LocalXgReduceMotion.current || motion <= 0f
    val initialHasData = initiallyLoaded || (!loading && error == null)
    var hasData by remember { mutableStateOf(initialHasData) }
    val contentAlpha = remember { Animatable(if (initialHasData) 1f else 0f) }
    val skeletonAlpha = remember {
        Animatable(if (!initialHasData && loading) 1f else 0f)
    }
    val contentBlur = remember { Animatable(if (initialHasData) 0f else 5f) }
    val contentScale = remember { Animatable(if (initialHasData) 1f else 0.985f) }
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(loading, error, hasData) {
        when {
            !hasData && !loading && error == null -> hasData = true
            !hasData && loading -> skeletonAlpha.snapTo(1f)
            !hasData && error != null -> skeletonAlpha.snapTo(0f)
        }
    }
    LaunchedEffect(hasData, reduceMotion) {
        if (!hasData) return@LaunchedEffect
        if (reduceMotion) {
            skeletonAlpha.snapTo(0f)
            contentAlpha.snapTo(1f)
            contentBlur.snapTo(0f)
            contentScale.snapTo(1f)
        } else {
            coroutineScope {
                launch {
                    skeletonAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = xgMotionDuration(170, motion),
                            easing = XgEaseOutCubic,
                        ),
                    )
                }
                launch {
                    contentAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = xgMotionDuration(260, motion),
                            easing = XgEaseOutCubic,
                        ),
                    )
                }
                launch {
                    if (supportsBlur) {
                        contentBlur.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = xgMotionDuration(260, motion),
                                easing = XgEaseOutCubic,
                            ),
                        )
                    } else {
                        contentScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            }
        }
    }

    Box(modifier = modifier) {
        if (hasData) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        if (!supportsBlur) {
                            scaleX = contentScale.value
                            scaleY = contentScale.value
                        }
                    }
                    .then(
                        if (supportsBlur) {
                            Modifier.blur(radius = contentBlur.value.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                content()
            }
            if (showInlineError && error != null) {
                XgContentErrorBar(
                    message = error,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = XgSpacing.screen, vertical = XgSpacing.sm),
                )
            }
            if (skeletonAlpha.value > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = XgSpacing.screen)
                        .graphicsLayer { alpha = skeletonAlpha.value },
                ) {
                    XgLoadingSkeleton(lines = skeletonLines)
                }
            }
        } else if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = XgSpacing.screen),
            ) {
                XgErrorState(code = null, message = error, onRetry = onRetry)
            }
        } else if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = XgSpacing.screen),
            ) {
                XgLoadingSkeleton(lines = skeletonLines)
            }
        }
    }
}

@Composable
private fun XgContentErrorBar(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shapes = rememberXgShapes()
    Row(
        modifier = modifier
            .clip(shapes.card)
            .background(colors.dangerSoft)
            .border(1.dp, colors.danger.copy(alpha = 0.28f), shapes.card)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(XgSpacing.sm))
        Text(
            text = message,
            style = XgType.note,
            color = colors.danger,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(Modifier.width(6.dp))
            XgTextAction(
                text = stringResource(R.string.xg_retry),
                onClick = onRetry,
                tone = XgTone.Danger,
            )
        }
    }
}

fun Modifier.xgAnimateItem(enabled: Boolean = true): Modifier = if (!enabled) {
    this
} else {
    composed {
        val motion = LocalXgMotionScale.current
        val reduceMotion = LocalXgReduceMotion.current
        if (motion <= 0f || reduceMotion) {
            this
        } else {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = xgMotionDuration(200, motion),
                        easing = XgEaseOutCubic,
                    ),
                )
            }
            this.graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 8.dp.toPx()
            }
        }
    }
}

@Composable
fun XgPullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = LocalXgMotionScale.current
    val reduceMotion = LocalXgReduceMotion.current || motion <= 0f
    val density = LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    val maxPullPx = with(density) { 112.dp.toPx() }
    val controller = remember { XgPullRefreshController() }
    val refreshingState = rememberUpdatedState(refreshing)
    val refreshAction = rememberUpdatedState(onRefresh)
    var wasRefreshing by remember { mutableStateOf(refreshing) }

    val connection = remember(thresholdPx, maxPullPx, reduceMotion, controller) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    !refreshingState.value &&
                    !controller.completing &&
                    available.y < 0f &&
                    controller.pull > 0f
                ) {
                    val next = (controller.pull + available.y).coerceAtLeast(0f)
                    val consumed = next - controller.pull
                    controller.updatePull(next)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    available.y > 0f &&
                    !refreshingState.value &&
                    !controller.refreshRequested &&
                    !controller.completing
                ) {
                    val next = (controller.pull + available.y * 0.55f).coerceAtMost(maxPullPx)
                    controller.updatePull(next)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshingState.value || controller.completing || controller.pull <= 0f) {
                    return Velocity.Zero
                }
                if (controller.pull >= thresholdPx) {
                    controller.snap(thresholdPx)
                    controller.refreshRequested = true
                    val started = runCatching { refreshAction.value() }.isSuccess
                    if (!started) {
                        controller.refreshRequested = false
                        controller.settleNow(0f, reduceMotion)
                    }
                } else {
                    controller.settleNow(0f, reduceMotion)
                }
                return available
            }
        }
    }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            wasRefreshing = true
            controller.refreshRequested = true
            controller.completing = false
            if (controller.pull < thresholdPx) {
                controller.settleNow(thresholdPx, reduceMotion)
            } else {
                controller.snap(thresholdPx)
            }
        } else if (wasRefreshing) {
            wasRefreshing = false
            controller.refreshRequested = false
            controller.completing = true
            delay(if (reduceMotion) 160 else 240)
            controller.completing = false
            controller.settleNow(0f, reduceMotion)
        }
    }

    LaunchedEffect(controller.refreshRequested, refreshing) {
        if (controller.refreshRequested && !refreshing) {
            delay(700)
            if (controller.refreshRequested && !refreshing) {
                controller.refreshRequested = false
                controller.settleNow(0f, reduceMotion)
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        XgPullRefreshIndicator(
            pullFraction = (controller.pull / thresholdPx).coerceIn(0f, 1f),
            pullPx = controller.pull,
            refreshing = refreshing,
            completing = controller.completing,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun XgPullRefreshIndicator(
    pullFraction: Float,
    pullPx: Float,
    refreshing: Boolean,
    completing: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val density = LocalDensity.current
    val fraction = pullFraction.coerceIn(0f, 1f)
    val indicatorSizePx = with(density) { 40.dp.toPx() }
    val visible = refreshing || completing || fraction > 0.001f
    val rotation = remember { Animatable(0f) }
    val label = when {
        completing -> stringResource(R.string.xg_refresh_complete)
        refreshing -> stringResource(R.string.xg_refresh_refreshing)
        fraction >= 0.999f -> stringResource(R.string.xg_refresh_release)
        else -> stringResource(R.string.xg_refresh_pull)
    }

    LaunchedEffect(refreshing, reduceMotion) {
        if (refreshing && !reduceMotion) {
            rotation.snapTo(0f)
            while (true) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 900, easing = LinearEasing),
                )
            }
        } else {
            rotation.snapTo(0f)
        }
    }

    if (!visible) return

    val alpha = if (refreshing || completing) 1f else (fraction * 2.4f).coerceIn(0f, 1f)
    val targetIcon = when {
        completing -> 2
        refreshing -> 1
        else -> 0
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                translationY = pullPx - indicatorSizePx
            }
            .size(40.dp)
            .semantics {
                contentDescription = label
                stateDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.line, CircleShape),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
        ) {
            val ringStroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = colors.line,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = ringStroke,
            )
            val sweep = when {
                completing -> 360f
                refreshing -> 280f
                else -> 360f * fraction
            }
            if (sweep > 0f) {
                val start = if (refreshing && !reduceMotion) rotation.value - 90f else -90f
                drawArc(
                    color = if (completing) colors.ok else colors.accent,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = ringStroke,
                )
            }
        }
        AnimatedContent(
            targetState = targetIcon,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    (fadeIn(tween(100)) + scaleIn(initialScale = 0.78f, animationSpec = tween(100))) togetherWith
                        (fadeOut(tween(80)) + scaleOut(targetScale = 0.78f, animationSpec = tween(80)))
                }
            },
            contentAlignment = Alignment.Center,
            label = "xgPullRefreshIcon",
        ) { target ->
            when (target) {
                2 -> Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = colors.ok,
                    modifier = Modifier.size(15.dp),
                )
                1 -> Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer {
                            rotationZ = if (reduceMotion) 90f else rotation.value
                        },
                )
                else -> Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = fraction * 180f },
                )
            }
        }
    }
}

private class XgPullRefreshController {
    var pull by mutableFloatStateOf(0f)
        private set
    var refreshRequested by mutableStateOf(false)
    var completing by mutableStateOf(false)
    private var animationVersion = 0

    fun updatePull(value: Float) {
        animationVersion += 1
        pull = value.coerceAtLeast(0f)
    }

    fun snap(value: Float) {
        animationVersion += 1
        pull = value.coerceAtLeast(0f)
    }

    suspend fun settleNow(target: Float, reduceMotion: Boolean) {
        val version = animationVersion + 1
        animationVersion = version
        if (reduceMotion) {
            pull = target
            return
        }
        animate(
            initialValue = pull,
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) { value, _ ->
            if (version == animationVersion) pull = value
        }
        if (version == animationVersion) pull = target
    }
}
