// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.internal

import android.annotation.SuppressLint
import top.yukonga.miuix.kmp.shader.RuntimeShader
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

@SuppressLint("NewApi")
internal fun android.graphics.RenderEffect?.chain(other: android.graphics.RenderEffect): android.graphics.RenderEffect =
    if (this != null) android.graphics.RenderEffect.createChainEffect(other, this) else other

@SuppressLint("NewApi")
internal fun runtimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): android.graphics.RenderEffect {
    check(isRenderEffectSupported())
    return android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader.shader, uniformShaderName)
}
