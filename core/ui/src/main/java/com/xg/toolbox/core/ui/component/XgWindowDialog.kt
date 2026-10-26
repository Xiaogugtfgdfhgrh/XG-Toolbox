package com.xg.toolbox.core.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.liquid.LocalXgBackdrop
import com.xg.toolbox.core.ui.liquid.xgLiquidGlassSurface
import com.xg.toolbox.core.ui.theme.LocalXgColors
import top.yukonga.miuix.kmp.blur.Backdrop

@Stable
class XgOverlayRequest internal constructor(
    internal val content: @Composable (Backdrop) -> Unit,
)

@Stable
class XgWindowOverlayHostState {
    internal var activeRequest: XgOverlayRequest? by mutableStateOf(null)
    val hasOverlay: Boolean get() = activeRequest != null

    fun show(content: @Composable (Backdrop) -> Unit): XgOverlayRequest {
        val request = XgOverlayRequest(content)
        activeRequest = request
        return request
    }

    fun dismiss(request: XgOverlayRequest) {
        if (activeRequest === request) activeRequest = null
    }
}

val LocalXgWindowOverlayHost = staticCompositionLocalOf<XgWindowOverlayHostState?> { null }

@Composable
fun XgWindowOverlayHost(
    state: XgWindowOverlayHostState,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val request = state.activeRequest ?: return
    Box(modifier = modifier.fillMaxSize()) {
        request.content(backdrop)
    }
}

@Composable
fun XgGlassDialog(
    backdrop: Backdrop,
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    maxWidth: Dp = 342.dp,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalXgColors.current
    val focusRequester = remember { FocusRequester() }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismissRequest,
            )
            .semantics { isTraversalGroup = true },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalXgBackdrop provides backdrop) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 22.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .xgLiquidGlassSurface(
                        shape = shape,
                        surfaceColor = colors.surface.copy(alpha = 0.70f),
                        opaqueSurfaceColor = colors.surface,
                        accentColor = colors.accent,
                        borderColor = colors.line,
                        enabled = true,
                        elevation = 14.dp,
                    )
                    .focusRequester(focusRequester)
                    .focusGroup()
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismissRequest()
                            true
                        } else {
                            false
                        }
                    }
                    .semantics { paneTitle = title }
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(20.dp)
                    .then(contentModifier),
                content = content,
            )
        }
    }
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
}
