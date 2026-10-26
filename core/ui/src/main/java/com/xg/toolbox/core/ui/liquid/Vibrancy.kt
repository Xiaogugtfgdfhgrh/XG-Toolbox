// Adapted from Kyant0/AndroidLiquidGlass and compose-miuix-ui (Apache-2.0).

package com.xg.toolbox.core.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

fun BackdropEffectScope.vibrancy(
    saturation: Float = 1.8f,
    brightness: Float = 0.05f,
) {
    colorControls(brightness = brightness, contrast = 1f, saturation = saturation)
}
