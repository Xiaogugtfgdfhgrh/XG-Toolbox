package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.liquid.LocalXgBackdropVisible
import com.xg.toolbox.core.ui.liquid.xgBackdropSurfaceColor
import com.xg.toolbox.core.ui.liquid.xgPageSurfaceColor
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgTheme
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgPage(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable ColumnScope.() -> Unit,
) {
    XgTheme(darkTheme = darkTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(xgPageSurfaceColor())
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = XgSpacing.screen)
                .padding(top = XgSpacing.sm, bottom = XgSpacing.contentBottom),
            content = content,
        )
    }
}

@Composable
fun XgPageScaffold(
    topBar: (@Composable () -> Unit)?,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(xgPageSurfaceColor())) {
        if (topBar != null) {
            XgStickyHeader { topBar() }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (topBar == null) Modifier.statusBarsPadding() else Modifier),
            contentPadding = PaddingValues(
                start = XgSpacing.screen,
                end = XgSpacing.screen,
                bottom = XgSpacing.contentBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
            content = content,
        )
    }
}

@Composable
fun XgStickyHeader(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalXgColors.current
    val backdropVisible = LocalXgBackdropVisible.current
    val headerSurface = if (backdropVisible) colors.bg.copy(alpha = 0.62f) else colors.bg
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .background(headerSurface)
            .statusBarsPadding()
            .drawWithCache {
                val hairline = 1.dp.toPx()
                val fadeHeight = 10.dp.toPx()
                val fadeBrush = Brush.verticalGradient(
                    colors = listOf(
                        headerSurface.copy(alpha = if (backdropVisible) 0.52f else 0.92f),
                        Color.Transparent,
                    ),
                    startY = size.height,
                    endY = size.height + fadeHeight,
                )
                onDrawBehind {
                    drawRect(
                        color = colors.line,
                        topLeft = Offset(0f, size.height - hairline),
                        size = Size(size.width, hairline),
                    )
                    drawRect(
                        brush = fadeBrush,
                        topLeft = Offset(0f, size.height),
                        size = Size(size.width, fadeHeight),
                    )
                }
            }
            .padding(horizontal = XgSpacing.screen, vertical = XgSpacing.sm),
        content = content,
    )
}

@Composable
fun XgPageHeader(
    title: String,
    meta: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = XgType.pageTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.width(9.dp))
                Text(
                    text = meta,
                    style = XgType.pageMeta,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.width(XgSpacing.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

data class XgAnchor(
    val id: String,
    val label: String,
)

@Composable
fun XgAnchorRail(
    anchors: List<XgAnchor>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalXgColors.current
    val shapes = rememberXgShapes()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = XgSpacing.sm, bottom = XgSpacing.xs),
    ) {
        items(anchors.size, key = { index -> anchors[index].id }) { index ->
            val anchor = anchors[index]
            val selected = anchor.id == selectedId
            Box(
                modifier = Modifier
                    .heightIn(min = XgSize.touch)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(anchor.id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = XgSize.anchorH)
                        .clip(shapes.chip)
                        .background(if (selected) colors.ink else colors.surface.copy(alpha = 0.72f))
                        .border(1.dp, if (selected) colors.ink else colors.lineStrong, shapes.chip)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = anchor.label,
                        style = XgType.chip,
                        color = if (selected) colors.bg else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun XgSectionHeader(
    title: String,
    meta: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = XgType.sectionTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = meta,
                style = XgType.pageMeta,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            action()
        }
    }
}

@Composable
fun XgPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalXgColors.current
    val shape = rememberXgShapes().card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(xgBackdropSurfaceColor(colors.surface, alphaWhenVisible = 0.66f))
            .border(1.dp, colors.line, shape)
            .padding(horizontal = XgSpacing.panelHorizontal, vertical = XgSpacing.panelVertical),
        content = content,
    )
}

@Composable
fun XgPanelHeader(
    title: String,
    meta: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = XgType.cardTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!meta.isNullOrBlank()) {
                Text(
                    text = meta,
                    style = XgType.note,
                    color = colors.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(XgSpacing.sm))
            trailing()
        }
    }
}

@Composable
fun XgHairline() {
    val colors = LocalXgColors.current
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.line),
    )
}
