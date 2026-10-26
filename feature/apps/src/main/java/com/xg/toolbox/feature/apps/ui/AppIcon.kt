package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.apps.AppsViewModel

@Composable
fun AppIcon(
    packageName: String,
    versionCode: Long,
    label: String,
    viewModel: AppsViewModel,
    modifier: Modifier = Modifier,
    size: Dp = XgSize.avatar,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    val icon by produceState<ImageBitmap?>(
        initialValue = viewModel.peekIcon(packageName, versionCode),
        key1 = packageName,
        key2 = versionCode,
    ) {
        value = viewModel.loadIcon(packageName, versionCode)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.inset)
            .border(1.dp, colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = label.firstOrNull()?.toString() ?: "?",
                style = XgType.rowTitle,
                color = colors.muted,
                maxLines = 1,
            )
        }
    }
}
