package com.xg.toolbox.core.ui.component

import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xg.toolbox.core.common.XgTab
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgMotionDuration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val barShape = RoundedCornerShape(32.dp)
private val pillShape = RoundedCornerShape(28.dp)
private val xgTabLabelStyle = XgType.badge.copy(fontSize = 9.sp)

@Stable
private class HtmlNavSpring(
    private val scope: CoroutineScope,
    private val density: androidx.compose.ui.unit.Density,
    private val reduceMotion: Boolean,
    initialIndex: Int,
    private val tabCount: Int,
    private val onPreview: (Int) -> Unit,
) {
    private val padPx = with(density) { 4.dp.toPx() }
    private var widthPx by mutableFloatStateOf(0f)
    private var cellPx by mutableFloatStateOf(0f)
    private var selectedIndex = initialIndex
    private var targetLeftPx = 0f
    private var targetRightPx = 0f
    private var leftVelocity = 0f
    private var rightVelocity = 0f
    private var leadDirection by mutableIntStateOf(1)

    var leftPx by mutableFloatStateOf(0f)
        private set
    var rightPx by mutableFloatStateOf(0f)
        private set
    var press by mutableFloatStateOf(0f)
        private set
    private var pressTarget = 0f
    var dragging by mutableStateOf(false)
        private set
    var previewIndex by mutableIntStateOf(-1)
        private set

    private var frameJob: Job? = null

    val pillCellPx: Float get() = cellPx
    val pillLeftPx: Float
        get() {
            val width = rightPx - leftPx
            if (width <= pillCellPx * MAX_PILL_STRETCH) return leftPx
            return if (leadDirection > 0) rightPx - pillCellPx * MAX_PILL_STRETCH else leftPx
        }
    val pillWidthPx: Float
        get() {
            if (pillCellPx <= 0f) return 0f
            return (rightPx - leftPx).coerceIn(pillCellPx * 0.92f, pillCellPx * MAX_PILL_STRETCH)
        }

    fun onMetricsChanged(width: Int) {
        val nextWidth = width.toFloat().coerceAtLeast(1f)
        val nextCell = ((nextWidth - padPx * 2f) / tabCount).coerceAtLeast(1f)
        if (nextWidth == widthPx && abs(nextCell - cellPx) < 0.5f) return
        widthPx = nextWidth
        cellPx = nextCell
        dragging = false
        pressTarget = 0f
        press = 0f
        previewIndex = -1
        onPreview(-1)
        updateTargetForIndex(selectedIndex, immediate = true)
    }

    fun nearestIndex(x: Float): Int {
        if (cellPx <= 0f) return selectedIndex
        return (((x - padPx - cellPx / 2f) / cellPx).roundToInt()).coerceIn(0, tabCount - 1)
    }

    fun beginDrag(x: Float) {
        if (widthPx <= 0f || cellPx <= 0f) return
        dragging = true
        pressTarget = 1f
        previewIndex = nearestIndex(x)
        onPreview(previewIndex)
        updateTargetForDrag(x)
        if (reduceMotion) {
            snapToTargets()
            press = pressTarget
        } else {
            kick()
        }
    }

    fun moveDrag(x: Float) {
        if (!dragging) return
        updateTargetForDrag(x)
        val preview = nearestIndex(x)
        if (preview != previewIndex) {
            previewIndex = preview
            onPreview(preview)
        }
        if (reduceMotion) snapToTargets() else kick()
    }

    fun endDrag(x: Float): Int {
        val index = nearestIndex(x)
        dragging = false
        pressTarget = 0f
        previewIndex = -1
        onPreview(-1)
        updateTargetForIndex(index, immediate = reduceMotion)
        if (reduceMotion) {
            press = 0f
        } else {
            restartKick()
        }
        return index
    }

    fun cancelDrag() {
        dragging = false
        pressTarget = 0f
        previewIndex = -1
        onPreview(-1)
        updateTargetForIndex(selectedIndex, immediate = reduceMotion)
        if (reduceMotion) {
            press = 0f
        } else {
            restartKick()
        }
    }

    fun springTo(index: Int) {
        selectedIndex = index.coerceIn(0, tabCount - 1)
        if (dragging) return
        updateTargetForIndex(selectedIndex, immediate = reduceMotion)
        if (reduceMotion) {
            pressTarget = 0f
            press = 0f
        } else {
            restartKick()
        }
    }

    private fun updateTargetForDrag(x: Float) {
        val halfCell = cellPx / 2f
        val center = x.coerceIn(padPx + halfCell, widthPx - padPx - halfCell)
        setTarget(center - halfCell, center + halfCell, immediate = reduceMotion)
    }

    private fun updateTargetForIndex(index: Int, immediate: Boolean) {
        val left = padPx + cellPx * index.coerceIn(0, tabCount - 1)
        setTarget(left, left + cellPx, immediate)
    }

    private fun setTarget(left: Float, right: Float, immediate: Boolean) {
        val targetCenter = (left + right) / 2f
        val currentCenter = (leftPx + rightPx) / 2f
        if (abs(targetCenter - currentCenter) > 0.5f) {
            leadDirection = if (targetCenter > currentCenter) 1 else -1
        }
        targetLeftPx = left
        targetRightPx = right
        if (immediate) snapToTargets()
    }

    private fun snapToTargets() {
        leftPx = targetLeftPx
        rightPx = targetRightPx
        leftVelocity = 0f
        rightVelocity = 0f
    }

    private fun restartKick() {
        if (reduceMotion) {
            snapToTargets()
            return
        }
        frameJob?.cancel()
        frameJob = null
        kick()
    }

    private fun kick() {
        if (reduceMotion) {
            snapToTargets()
            return
        }
        if (frameJob?.isActive == true) return
        frameJob = scope.launch {
            var last = 0L
            var startedAt = 0L
            while (isActive) {
                val now = withFrameNanos { it }
                if (last == 0L) {
                    last = now
                    startedAt = now
                    continue
                }
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 1f / 30f)
                last = now
                val pressStep = 1f - exp(-dt / 0.055f)
                press += (pressTarget - press) * pressStep
                val leadK = if (dragging) 960f else 420f
                val leadC = if (dragging) 52f else 34f
                val trailK = if (dragging) 620f else 320f
                val trailC = if (dragging) 42f else 31f
                val leftK = if (leadDirection < 0) leadK else trailK
                val leftC = if (leadDirection < 0) leadC else trailC
                val rightK = if (leadDirection > 0) leadK else trailK
                val rightC = if (leadDirection > 0) leadC else trailC
                leftVelocity += ((targetLeftPx - leftPx) * leftK - leftVelocity * leftC) * dt
                rightVelocity += ((targetRightPx - rightPx) * rightK - rightVelocity * rightC) * dt
                leftVelocity = leftVelocity.coerceIn(-6000f, 6000f)
                rightVelocity = rightVelocity.coerceIn(-6000f, 6000f)
                leftPx += leftVelocity * dt
                rightPx += rightVelocity * dt
                val timedOut = !dragging && now - startedAt >= BOTTOM_BAR_SETTLE_MS * 1_000_000L
                val calm = abs(targetLeftPx - leftPx) < 0.12f &&
                    abs(targetRightPx - rightPx) < 0.12f &&
                    abs(leftVelocity) < 6f &&
                    abs(rightVelocity) < 6f &&
                    abs(pressTarget - press) < 0.002f
                if (calm || timedOut) {
                    leftPx = targetLeftPx
                    rightPx = targetRightPx
                    leftVelocity = 0f
                    rightVelocity = 0f
                    press = pressTarget
                    break
                }
            }
            frameJob = null
        }
    }
}

private const val MAX_PILL_STRETCH = 1.22f
private const val TAB_LABEL_FADE_MS = 260
private const val TAB_ICON_BOUNCE_MS = 260
private const val BOTTOM_BAR_SETTLE_MS = 400

@Composable
fun XgBottomBar(
    selected: XgTab,
    onSelect: (XgTab) -> Unit,
    modifier: Modifier = Modifier,
    backgroundImage: ImageBitmap? = null,
) {
    val colors = LocalXgColors.current
    val motionScale = LocalXgMotionScale.current
    val tabs = XgTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentSelected = rememberUpdatedState(selected)

    var previewIndex by remember { mutableIntStateOf(-1) }
    val spring = remember(scope, density, motionScale, tabs.size) {
        HtmlNavSpring(
            scope = scope,
            density = density,
            reduceMotion = motionScale <= 0f,
            initialIndex = selectedIndex,
            tabCount = tabs.size,
            onPreview = { previewIndex = it },
        )
    }

    LaunchedEffect(selectedIndex) {
        spring.springTo(selectedIndex)
    }

    fun activateTab(index: Int) {
        val target = index.coerceIn(0, tabs.lastIndex)
        spring.springTo(target)
        if (tabs[target] != currentSelected.value) currentOnSelect.value(tabs[target])
    }

    val effectiveIndex = if (spring.dragging) previewIndex.coerceAtLeast(0) else selectedIndex
    val focusRequesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val inactiveLabel = if (colors.isDark) Color.White.copy(alpha = 0.86f) else Color(0xFF25303D)
    val activeLabel = colors.accent
    val labelShadow = if (colors.isDark) {
        Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 1f), 2f)
    } else {
        Shadow(Color.White.copy(alpha = 0.60f), Offset(0f, 1f), 0f)
    }
    val barTint = if (colors.isDark) Color(0xFF1C222C) else Color.White
    val barGradient = listOf(
        barTint.copy(alpha = if (colors.isDark) 0.72f else 0.62f),
        barTint.copy(alpha = if (colors.isDark) 0.54f else 0.38f),
        barTint.copy(alpha = if (colors.isDark) 0.66f else 0.52f),
    )
    val pillGradient = if (colors.isDark) {
        listOf(
            Color.White.copy(alpha = 0.26f),
            Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.18f),
        )
    } else {
        listOf(
            Color(0xFFDCE7F5).copy(alpha = 0.88f),
            Color(0xFFF0F5FB).copy(alpha = 0.58f),
            Color(0xFFD8E4F3).copy(alpha = 0.76f),
        )
    }
    val pillEdge = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.95f),
            Color.White.copy(alpha = 0.24f),
            Color.White.copy(alpha = 0.70f),
        ),
    )
    val barEdge = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.62f),
            Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.36f),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .height(64.dp)
            .onSizeChanged { spring.onMetricsChanged(it.width) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(12.dp, barShape, clip = false)
                .clip(barShape)
                .border(1.dp, barEdge, barShape),
        ) {
            if (backgroundImage != null) {
                Image(
                    bitmap = backgroundImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                    22f,
                                    22f,
                                    Shader.TileMode.CLAMP,
                                ).asComposeRenderEffect()
                            }
                        },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.surface, colors.bg, colors.surface2),
                            ),
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(barGradient)),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.82f),
                                    Color.Transparent,
                                ),
                            ),
                            start = Offset(size.width * 0.12f, 1.dp.toPx()),
                            end = Offset(size.width * 0.88f, 1.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(with(density) { spring.pillCellPx.toDp() })
                .height(56.dp)
                .graphicsLayer {
                    val cellWidth = spring.pillCellPx
                    val pillWidth = spring.pillWidthPx
                    val stretch = if (cellWidth > 0f) {
                        (pillWidth / cellWidth - 1f).coerceIn(0f, 0.30f)
                    } else {
                        0f
                    }
                    translationX = spring.pillLeftPx
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = if (cellWidth > 0f) pillWidth / cellWidth else 1f
                    scaleY = 1f - (stretch * 0.50f + spring.press * 0.018f).coerceIn(0f, 0.10f)
                }
                .clip(pillShape)
                .background(Brush.verticalGradient(pillGradient), pillShape)
                .border(1.dp, pillEdge, pillShape)
                .drawWithContent {
                    drawContent()
                    val press = spring.press
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.58f * press), Color.Transparent),
                            center = Offset(size.width * 0.5f, 0f),
                            radius = size.width * 0.9f,
                        ),
                    )
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.80f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.10f),
                        ),
                    )
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.92f), Color.Transparent),
                        ),
                        start = Offset(size.width * 0.14f, 1.dp.toPx()),
                        end = Offset(size.width * 0.86f, 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .selectableGroup()
                .pointerInput(tabs.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var holdBegan = false
                        var dragStarted = false
                        val dragSlop = 4.dp.toPx()
                        val holdJob = scope.launch {
                            delay(110)
                            if (!dragStarted) {
                                holdBegan = true
                                spring.beginDrag(startX)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    holdJob.cancel()
                                    if (dragStarted || holdBegan) {
                                        change.consume()
                                        val index = spring.endDrag(change.position.x)
                                        activateTab(index)
                                    } else {
                                        change.consume()
                                        activateTab(spring.nearestIndex(change.position.x))
                                    }
                                    break
                                }
                                if (!dragStarted && abs(change.position.x - startX) > dragSlop) {
                                    holdJob.cancel()
                                    dragStarted = true
                                    spring.beginDrag(change.position.x)
                                }
                                if (dragStarted || holdBegan) {
                                    change.consume()
                                    spring.moveDrag(change.position.x)
                                }
                            }
                        } finally {
                            holdJob.cancel()
                            if (spring.dragging) spring.cancelDrag()
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == effectiveIndex
                TabItem(
                    tab = tab,
                    active = active,
                    selected = index == selectedIndex,
                    onClick = { activateTab(index) },
                    onMoveFocus = { direction ->
                        val next = (index + if (isLtr) direction else -direction + tabs.size) % tabs.size
                        activateTab(next)
                        focusRequesters[next].requestFocus()
                    },
                    focusRequester = focusRequesters[index],
                    inactiveColor = inactiveLabel,
                    activeColor = activeLabel,
                    labelShadow = labelShadow,
                    modifier = if (active) Modifier else Modifier,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    tab: XgTab,
    active: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onMoveFocus: (Int) -> Unit,
    focusRequester: FocusRequester,
    inactiveColor: Color,
    activeColor: Color,
    labelShadow: Shadow,
    modifier: Modifier = Modifier,
) {
    val motion = LocalXgMotionScale.current
    val contentColor by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        animationSpec = if (motion <= 0f) {
            snap()
        } else {
            tween(xgMotionDuration(TAB_LABEL_FADE_MS, motion), easing = XgEaseOutCubic)
        },
        label = "xgTabContentColor",
    )
    val iconScale = remember { Animatable(1f) }
    var selectionSeen by remember { mutableStateOf(false) }

    LaunchedEffect(selected, motion) {
        if (motion <= 0f) {
            iconScale.snapTo(1f)
            selectionSeen = !selected
            return@LaunchedEffect
        }
        if (!selected) {
            selectionSeen = true
            return@LaunchedEffect
        }
        if (selectionSeen) {
            selectionSeen = false
            val duration = xgMotionDuration(TAB_ICON_BOUNCE_MS, motion)
            val peak = (duration * 0.58f).roundToInt().coerceIn(1, duration)
            iconScale.snapTo(0.82f)
            iconScale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = duration
                    0.82f at 0
                    1.06f at peak
                    1f at duration
                },
            )
        }
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                onClick {
                    onClick()
                    true
                }
            }
            .onKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                        onClick()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        onMoveFocus(1)
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                        onMoveFocus(-1)
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon(),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    translationY = if (active) -0.5.dp.toPx() else 0f
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(tab.labelRes()),
            style = xgTabLabelStyle.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                shadow = labelShadow,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun XgTab.icon(): ImageVector = when (this) {
    XgTab.HOME -> Icons.Outlined.Speed
    XgTab.DEVICE -> Icons.Outlined.Memory
    XgTab.APPS -> Icons.Outlined.Apps
    XgTab.SYSTEM -> Icons.Outlined.Tune
    XgTab.SECURITY -> Icons.Outlined.Settings
}

private fun XgTab.labelRes(): Int = when (this) {
    XgTab.HOME -> R.string.xg_tab_home
    XgTab.DEVICE -> R.string.xg_tab_device
    XgTab.APPS -> R.string.xg_tab_apps
    XgTab.SYSTEM -> R.string.xg_tab_system
    XgTab.SECURITY -> R.string.xg_tab_security
}
