// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.drawHighlight
import top.yukonga.miuix.kmp.blur.internal.ShapeProvider
import top.yukonga.miuix.kmp.blur.internal.recordLayer
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import kotlin.math.ceil

private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (BackdropEffectScope.() -> Highlight?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    contentBlendMode: BlendMode = BlendMode.SrcOver,
    enabled: Boolean = true,
): Modifier {
    val effectiveEnabled = enabled && isRenderEffectSupported()
    return this
        .then(if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shape = shape,
                effects = effects,
                highlight = highlight,
                layerBlock = layerBlock,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                contentBlendMode = contentBlendMode,
                enabled = effectiveEnabled,
            ),
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shape: () -> Shape,
    val effects: BackdropEffectScope.() -> Unit,
    val highlight: (BackdropEffectScope.() -> Highlight?)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val contentBlendMode: BlendMode,
    val enabled: Boolean,
) : ModifierNodeElement<DrawBackdropNode>() {
    override fun create(): DrawBackdropNode = DrawBackdropNode(
        backdrop, shape, effects, highlight, layerBlock, onDrawBehind,
        onDrawBackdrop, onDrawSurface, onDrawFront, contentBlendMode, enabled,
    )

    override fun update(node: DrawBackdropNode) {
        val enabledChanged = node.enabled != enabled
        node.backdrop = backdrop
        node.updateShape(shape)
        node.effects = effects
        node.highlight = highlight
        node.layerBlock = layerBlock
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.contentBlendMode = contentBlendMode
        node.enabled = enabled
        if (enabledChanged) {
            if (!enabled) node.releaseLayer()
            node.invalidateMeasurement()
        }
        node.invalidateEffects()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false
        return backdrop == other.backdrop &&
            shape == other.shape &&
            effects == other.effects &&
            highlight == other.highlight &&
            layerBlock == other.layerBlock &&
            onDrawBehind == other.onDrawBehind &&
            onDrawBackdrop == other.onDrawBackdrop &&
            onDrawSurface == other.onDrawSurface &&
            onDrawFront == other.onDrawFront &&
            contentBlendMode == other.contentBlendMode &&
            enabled == other.enabled
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (highlight?.hashCode() ?: 0)
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + contentBlendMode.hashCode()
        result = 31 * result + enabled.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    shape: () -> Shape,
    var effects: BackdropEffectScope.() -> Unit,
    var highlight: (BackdropEffectScope.() -> Highlight?)?,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var contentBlendMode: BlendMode,
    var enabled: Boolean,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode,
    GlobalPositionAwareModifierNode, ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    override val shouldAutoInvalidate: Boolean = false

    private val shapeProvider = ShapeProvider(shape)
    private lateinit var graphicsContext: androidx.compose.ui.graphics.GraphicsContext
    private var mainLayer: GraphicsLayer? = null
    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null)
    private val contentPaint = Paint()
    private val highlightPaint = Paint()
    private var renderEffect: android.graphics.RenderEffect? = null
    private var effectsValid = false

    private val effectScope = object : BackdropEffectScopeImpl() {
        override val shape: Shape get() = shapeProvider.innerShape
    }

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        this.shape = shapeProvider.shape
        compositingStrategy = CompositingStrategy.Offscreen
    }

    fun updateShape(shape: () -> Shape) {
        shapeProvider.shapeBlock = shape
    }

    private fun ensureLayer(): GraphicsLayer = mainLayer ?: graphicsContext.createGraphicsLayer().also { mainLayer = it }

    fun releaseLayer() {
        if (!::graphicsContext.isInitialized) {
            mainLayer = null
            renderEffect = null
            return
        }
        mainLayer?.let { graphicsContext.releaseGraphicsLayer(it) }
        mainLayer = null
        renderEffect = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable: Placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (enabled) {
                placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
            } else {
                placeable.place(IntOffset.Zero)
            }
        }
    }

    override fun onAttach() {
        graphicsContext = currentValueOf(LocalGraphicsContext)
        effectScope.runtimeShaderCache = currentValueOf(LocalRuntimeShaderCache)
        observeReads { updateEffects() }
    }

    override fun onObservedReadsChanged() {
        updateEffects()
        invalidateDraw()
    }

    fun invalidateEffects() {
        if (isAttached) observeReads { updateEffects() }
        invalidateDraw()
    }

    private fun updateEffects() {
        if (!enabled) return
        if (!effectsValid) return
        effectScope.apply(effects)
        renderEffect = effectScope.renderEffect
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) layoutCoordinates = coordinates
    }

    override fun onDetach() {
        releaseLayer()
        layoutCoordinates = null
        effectScope.reset()
        effectsValid = false
    }

    private fun DrawScope.recordBackdrop(layer: GraphicsLayer) {
        val currentPadding = effectScope.padding
        val sf = effectScope.downscaleFactor.coerceAtLeast(1)
        val fullW = (size.width + currentPadding * 2f).toInt().coerceAtLeast(1)
        val fullH = (size.height + currentPadding * 2f).toInt().coerceAtLeast(1)
        val w = (fullW / sf).coerceAtLeast(1)
        val h = (fullH / sf).coerceAtLeast(1)
        val scaledPadding = currentPadding / sf
        recordLayer(layer, size = IntSize(w, h)) {
            translate(scaledPadding, scaledPadding) {
                onDrawBackdrop {
                    with(backdrop) {
                        drawBackdrop(
                            density = effectScope,
                            coordinates = layoutCoordinates,
                            layerBlock = layerBlock,
                            downscaleFactor = sf,
                        )
                    }
                }
            }
        }
        layer.topLeft = IntOffset(-scaledPadding.toInt(), -scaledPadding.toInt())
        layer.renderEffect = renderEffect?.asComposeRenderEffect()
        layer.colorFilter = effectScope.colorFilter
    }

    private fun DrawScope.drawGlass() {
        val layer = ensureLayer()
        recordBackdrop(layer)
        val sf = effectScope.downscaleFactor.toFloat().coerceAtLeast(1f)
        translate(-backdrop.offsetResidualX, -backdrop.offsetResidualY) {
            scale(sf, sf, Offset.Zero) {
                drawLayer(layer)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (!enabled) {
            drawContent()
            return
        }
        if (effectScope.update(this)) {
            effectsValid = true
            observeReads { updateEffects() }
        }
        onDrawBehind?.invoke(this)
        drawGlass()
        onDrawSurface?.invoke(this)
        drawContent()
        highlight?.invoke(effectScope)?.let {
            drawHighlight(it, effectScope.shape, effectScope.runtimeShaderCache, highlightPaint)
        }
        onDrawFront?.invoke(this)
    }
}






