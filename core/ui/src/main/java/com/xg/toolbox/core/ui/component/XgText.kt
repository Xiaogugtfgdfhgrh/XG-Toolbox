package com.xg.toolbox.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgLabel(text: String) {
    val colors = LocalXgColors.current
    Text(
        text = text.uppercase(),
        style = XgType.label,
        color = colors.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun XgFieldLabel(text: String) {
    val colors = LocalXgColors.current
    Text(
        text = text,
        style = XgType.fieldLabel,
        color = colors.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun XgNote(
    text: String,
    tone: XgTone = XgTone.Muted,
) {
    val colors = LocalXgColors.current
    val color = when (tone) {
        XgTone.Muted -> colors.faint
        XgTone.Neutral -> colors.text
        else -> xgToneColor(tone)
    }
    Text(text = text, style = XgType.note, color = color)
}

@Composable
fun XgMono(
    text: String,
    style: TextStyle = XgType.monoValue,
    color: Color = Color.Unspecified,
) {
    val colors = LocalXgColors.current
    Text(
        text = text,
        style = style,
        color = if (color == Color.Unspecified) colors.ink else color,
    )
}

@Composable
fun XgNum(
    text: String,
    style: TextStyle = XgType.monoValue,
    color: Color = Color.Unspecified,
) {
    val colors = LocalXgColors.current
    Text(
        text = text,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = if (color == Color.Unspecified) colors.ink else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
