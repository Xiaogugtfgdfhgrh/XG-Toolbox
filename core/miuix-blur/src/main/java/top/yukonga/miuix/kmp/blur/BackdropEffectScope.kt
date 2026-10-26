// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.shader.RuntimeShader

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {
    val size: Size
    val layoutDirection: LayoutDirection
    val shape: Shape
    var padding: Float
    var renderEffect: android.graphics.RenderEffect?
    var downscaleFactor: Int
    var noiseCoefficient: Float
    var colorFilter: ColorFilter?
}

@SuppressLint("NewApi")
internal abstract class BackdropEffectScopeImpl : BackdropEffectScope {
    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: android.graphics.RenderEffect? = null
    override var downscaleFactor: Int = 1
    override var noiseCoefficient: Float = 0f
    override var colorFilter: ColorFilter? = null

    var runtimeShaderCache: RuntimeShaderCache = RuntimeShaderCacheImpl()

    internal var cachedBlurRadiusX: Float = Float.NaN
    internal var cachedBlurRadiusY: Float = Float.NaN
    internal var cachedBlurSizeW: Float = Float.NaN
    internal var cachedBlurSizeH: Float = Float.NaN
    internal var cachedBlurDownscale: Int = -1
    internal var cachedBlurResult: android.graphics.RenderEffect? = null

    internal var cachedColorBrightness: Float = Float.NaN
    internal var cachedColorContrast: Float = Float.NaN
    internal var cachedColorSaturation: Float = Float.NaN
    internal var cachedColorResult: android.graphics.RenderEffect? = null

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader =
        runtimeShaderCache.obtainRuntimeShader(key, string)

    fun update(scope: DrawScope): Boolean {
        val changed = density != scope.density || fontScale != scope.fontScale ||
            size != scope.size || layoutDirection != scope.layoutDirection
        density = scope.density
        fontScale = scope.fontScale
        size = scope.size
        layoutDirection = scope.layoutDirection
        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        downscaleFactor = 1
        noiseCoefficient = 0f
        colorFilter = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        downscaleFactor = 1
        noiseCoefficient = 0f
        colorFilter = null
        cachedBlurRadiusX = Float.NaN
        cachedBlurRadiusY = Float.NaN
        cachedBlurSizeW = Float.NaN
        cachedBlurSizeH = Float.NaN
        cachedBlurDownscale = -1
        cachedBlurResult = null
        cachedColorBrightness = Float.NaN
        cachedColorContrast = Float.NaN
        cachedColorSaturation = Float.NaN
        cachedColorResult = null
    }
}

internal val BackdropEffectScope.impl: BackdropEffectScopeImpl
    get() = this as BackdropEffectScopeImpl



