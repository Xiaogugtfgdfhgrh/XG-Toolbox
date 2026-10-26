package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgBadge(
    text: String,
    tone: XgTone = XgTone.Muted,
) {
    val shape = rememberXgShapes().badge
    Box(
        modifier = Modifier
            .clip(shape)
            .background(xgToneSoftColor(tone))
            .border(1.dp, xgToneBorderColor(tone), shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = XgType.badge,
            color = xgToneColor(tone),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XgStatusChip(
    text: String,
    tone: XgTone = XgTone.Accent,
) {
    val shape = rememberXgShapes().badge
    Box(
        modifier = Modifier
            .clip(shape)
            .background(xgToneSoftColor(tone))
            .border(1.dp, xgToneBorderColor(tone), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = XgType.statusChip,
            color = xgToneColor(tone),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
