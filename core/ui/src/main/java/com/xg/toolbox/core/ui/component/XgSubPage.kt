package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import com.xg.toolbox.core.ui.liquid.xgPageSurfaceColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgSubPageScaffold(
    title: String,
    meta: String? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(xgPageSurfaceColor())) {
        XgStickyHeader {
            XgSubPageHeader(
                title = title,
                meta = meta,
                onBack = onBack,
                actions = actions,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
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
fun XgSubPage(
    title: String,
    meta: String? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(xgPageSurfaceColor())) {
        XgStickyHeader {
            XgSubPageHeader(
                title = title,
                meta = meta,
                onBack = onBack,
                actions = actions,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            content = content,
        )
    }
}

@Composable
private fun XgSubPageHeader(
    title: String,
    meta: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XgIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.xg_action_back),
            onClick = onBack,
        )
        Spacer(Modifier.width(XgSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = XgType.pageTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!meta.isNullOrBlank()) {
                Text(
                    text = meta,
                    style = XgType.pageMeta,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
