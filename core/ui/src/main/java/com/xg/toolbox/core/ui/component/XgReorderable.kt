package com.xg.toolbox.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgSpacing
import kotlinx.coroutines.launch
import kotlin.math.min

private data class XgReorderSlot(val top: Float, val height: Float) {
    val bottom: Float get() = top + height
    val center: Float get() = top + height / 2f
}

@Composable
fun <T> XgReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    spacing: Dp = XgSpacing.md,
    content: @Composable (index: Int, item: T, dragging: Boolean, modifier: Modifier) -> Unit,
) {
    val motion = LocalXgMotionScale.current
    val haptic = LocalHapticFeedback.current
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val slots = remember { mutableStateMapOf<Any, XgReorderSlot>() }
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestReorder by rememberUpdatedState(onReorder)
    val dragShape = remember { RoundedCornerShape(16.dp) }

    var dragKey by remember { mutableStateOf<Any?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }

    val moveUpLabel = stringResource(R.string.xg_reorder_move_up)
    val moveDownLabel = stringResource(R.string.xg_reorder_move_down)
    val keys = items.map(key)
    val orderedSlots = keys.map { slots[it] }
    val targetTops = if (
        dragKey != null &&
        dragStartIndex >= 0 &&
        dragTargetIndex >= 0 &&
        orderedSlots.all { it != null }
    ) {
        reorderTargetTops(
            keys = keys,
            slots = orderedSlots.filterNotNull(),
            from = dragStartIndex,
            to = dragTargetIndex,
            spacingPx = spacingPx,
        )
    } else {
        emptyMap()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items.forEachIndexed { index, item ->
            val itemKey = key(item)
            val dragging = dragKey == itemKey
            val slot = slots[itemKey]
            val shiftTarget = if (!dragging && slot != null) {
                targetTops[itemKey]?.minus(slot.top) ?: 0f
            } else {
                0f
            }
            val shift by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = if (motion <= 0f) {
                    snap()
                } else {
                    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                },
                label = "xgReorderShift$index",
            )
            val scale by animateFloatAsState(
                targetValue = if (dragging) 1.04f else 1f,
                animationSpec = if (motion <= 0f) {
                    snap()
                } else {
                    spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                },
                label = "xgReorderScale$index",
            )
            val state = stringResource(R.string.xg_reorder_state, index + 1, items.size)
            val canMoveUp = enabled && index > 0
            val canMoveDown = enabled && index < items.lastIndex
            val move = { target: Int ->
                if (target in items.indices && target != index) {
                    latestReorder(items.toMutableList().apply { add(target, removeAt(index)) })
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        slots[itemKey] = XgReorderSlot(
                            top = coordinates.positionInParent().y,
                            height = coordinates.size.height.toFloat(),
                        )
                    }
                    .zIndex(if (dragging) 1f else 0f)
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(itemKey, items) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        if (dragKey == null && !settling && keys.all { slots[it] != null }) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragKey = itemKey
                                            dragStartIndex = index
                                            dragTargetIndex = index
                                            dragOffset = 0f
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        val activeKey = dragKey ?: return@detectDragGesturesAfterLongPress
                                        change.consume()
                                        val start = slots[activeKey] ?: return@detectDragGesturesAfterLongPress
                                        val currentSlots = keys.mapNotNull { slots[it] }
                                        if (currentSlots.size != items.size) return@detectDragGesturesAfterLongPress
                                        val minOffset = currentSlots.first().top - start.top
                                        val maxOffset = currentSlots.last().bottom - start.bottom
                                        dragOffset = (dragOffset + amount.y).coerceIn(minOffset, maxOffset)
                                        val center = start.center + dragOffset
                                        dragTargetIndex = currentSlots
                                            .indexOfLast { center >= it.center }
                                            .coerceAtLeast(0)
                                            .coerceAtMost(items.lastIndex)
                                    },
                                    onDragEnd = {
                                        val activeKey = dragKey ?: return@detectDragGesturesAfterLongPress
                                        val from = dragStartIndex
                                        val to = dragTargetIndex
                                        val currentSlots = keys.mapNotNull { slots[it] }
                                        val start = slots[activeKey]
                                        if (
                                            from !in items.indices ||
                                            to !in items.indices ||
                                            start == null ||
                                            currentSlots.size != items.size
                                        ) {
                                            dragKey = null
                                            dragStartIndex = -1
                                            dragTargetIndex = -1
                                            dragOffset = 0f
                                            return@detectDragGesturesAfterLongPress
                                        }
                                        val targetOffset = reorderTargetTops(
                                            keys = keys,
                                            slots = currentSlots,
                                            from = from,
                                            to = to,
                                            spacingPx = spacingPx,
                                        )[activeKey]?.minus(start.top) ?: 0f
                                        settling = true
                                        scope.launch {
                                            if (motion <= 0f) {
                                                dragOffset = targetOffset
                                            } else {
                                                Animatable(dragOffset).animateTo(
                                                    targetValue = targetOffset,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.86f,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                                ) {
                                                    dragOffset = value
                                                }
                                            }
                                            if (from != to) {
                                                latestReorder(
                                                    latestItems.toMutableList().apply {
                                                        add(to, removeAt(from))
                                                    },
                                                )
                                            }
                                            dragKey = null
                                            dragStartIndex = -1
                                            dragTargetIndex = -1
                                            dragOffset = 0f
                                            settling = false
                                        }
                                    },
                                    onDragCancel = {
                                        settling = true
                                        scope.launch {
                                            if (motion <= 0f) {
                                                dragOffset = 0f
                                            } else {
                                                Animatable(dragOffset).animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.9f,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                                ) {
                                                    dragOffset = value
                                                }
                                            }
                                            dragKey = null
                                            dragStartIndex = -1
                                            dragTargetIndex = -1
                                            dragOffset = 0f
                                            settling = false
                                        }
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        stateDescription = state
                        customActions = buildList {
                            if (canMoveUp) {
                                add(CustomAccessibilityAction(moveUpLabel) { move(index - 1); true })
                            }
                            if (canMoveDown) {
                                add(CustomAccessibilityAction(moveDownLabel) { move(index + 1); true })
                            }
                        }
                    },
            ) {
                content(
                    index,
                    item,
                    dragging,
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else shift
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = if (dragging) 12.dp.toPx() else 0f
                            shape = dragShape
                            clip = false
                        },
                )
            }
        }
    }
}

private fun <T> reorderTargetTops(
    keys: List<T>,
    slots: List<XgReorderSlot>,
    from: Int,
    to: Int,
    spacingPx: Float,
): Map<T, Float> {
    if (from == to || keys.isEmpty() || slots.size != keys.size) return emptyMap()
    val finalKeys = keys.toMutableList().apply { add(to, removeAt(from)) }
    val slotByKey = keys.zip(slots).toMap()
    val rangeStart = min(from, to)
    val rangeEnd = maxOf(from, to)
    var top = slots[rangeStart].top
    val result = LinkedHashMap<T, Float>(rangeEnd - rangeStart + 1)
    for (index in rangeStart..rangeEnd) {
        val itemKey = finalKeys[index]
        val slot = slotByKey[itemKey] ?: continue
        result[itemKey] = top
        top += slot.height + spacingPx
    }
    return result
}
