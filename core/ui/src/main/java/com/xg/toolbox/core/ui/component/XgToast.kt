package com.xg.toolbox.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween
import kotlinx.coroutines.delay

private val xgToastActionStyle = XgType.toast.copy(fontWeight = FontWeight.Bold)

@Stable
class XgToastState {
    internal data class Toast(
        val id: Long,
        val message: String,
        val action: String?,
        val onAction: (() -> Unit)?,
    )

    internal val current: MutableState<Toast?> = mutableStateOf(null)
    private var counter: Long = 0L

    fun show(
        message: String,
        action: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        counter += 1
        current.value = Toast(counter, message, action, onAction)
    }

    internal fun dismiss(id: Long) {
        if (current.value?.id == id) current.value = null
    }
}

val LocalXgToast: ProvidableCompositionLocal<XgToastState?> = staticCompositionLocalOf { null }

@Composable
fun XgToastHost(state: XgToastState) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val toastShape = remember { RoundedCornerShape(11.dp) }
    val actionShape = remember { RoundedCornerShape(7.dp) }
    val current = state.current.value
    var displayed by remember { mutableStateOf<XgToastState.Toast?>(null) }

    LaunchedEffect(current?.id) {
        val toast = current ?: return@LaunchedEffect
        displayed = toast
        delay(3000)
        if (state.current.value?.id == toast.id) state.dismiss(toast.id)
    }

    val visible = current != null
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = xgTween(if (visible) 180 else 140, motion, XgEaseOutCubic),
        label = "xgToastVisibility",
    )
    LaunchedEffect(visible, progress) {
        if (!visible && progress <= 0.01f) displayed = null
    }

    val toast = displayed
    if (toast != null && (visible || progress > 0f)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 104.dp)
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * 8.dp.toPx()
                    }
                    .clip(toastShape)
                    .background(colors.ink)
                    .border(1.dp, colors.lineStrong, toastShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = toast.message,
                    style = XgType.toast,
                    color = colors.bg,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!toast.action.isNullOrBlank() && toast.onAction != null) {
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = XgSize.touch)
                            .clip(actionShape)
                            .clickable(role = Role.Button) {
                                toast.onAction?.invoke()
                                state.dismiss(toast.id)
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = toast.action.orEmpty(),
                            style = xgToastActionStyle,
                            color = colors.bg,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
