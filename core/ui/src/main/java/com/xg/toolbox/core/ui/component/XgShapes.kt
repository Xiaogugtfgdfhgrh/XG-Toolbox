package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.XgRadius

@Immutable
data class XgShapes(
    val card: Shape,
    val button: Shape,
    val chip: Shape,
    val badge: Shape,
    val small: Shape,
    val search: Shape,
    val bottomBar: Shape,
    val bottomBarTab: Shape,
)

@Composable
fun rememberXgShapes(): XgShapes = remember {
    XgShapes(
        card = RoundedCornerShape(XgRadius.card),
        button = RoundedCornerShape(XgRadius.button),
        chip = RoundedCornerShape(XgRadius.chip),
        badge = RoundedCornerShape(XgRadius.badge),
        small = RoundedCornerShape(XgRadius.small),
        search = RoundedCornerShape(12.dp),
        bottomBar = RoundedCornerShape(23.dp),
        bottomBarTab = RoundedCornerShape(17.dp),
    )
}
