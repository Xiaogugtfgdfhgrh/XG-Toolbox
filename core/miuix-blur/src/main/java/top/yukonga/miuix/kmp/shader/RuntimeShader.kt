// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.shader

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

@SuppressLint("ObsoleteSdkInt")
@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@SuppressLint("ObsoleteSdkInt")
@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RuntimeShader internal constructor(val shader: android.graphics.RuntimeShader) {
    fun setFloatUniform(name: String, value: Float) = shader.setFloatUniform(name, value)
    fun setFloatUniform(name: String, value1: Float, value2: Float) = shader.setFloatUniform(name, value1, value2)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) = shader.setFloatUniform(name, value1, value2, value3)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) = shader.setFloatUniform(name, value1, value2, value3, value4)
    fun setFloatUniform(name: String, values: FloatArray) = shader.setFloatUniform(name, values)
    fun setIntUniform(name: String, value: Int) = shader.setIntUniform(name, value)
    fun setColorUniform(name: String, color: Color) = shader.setColorUniform(name, color.toArgb())
    fun setInputShader(name: String, shader: android.graphics.Shader) = this.shader.setInputShader(name, shader)
    fun asBrush(): ShaderBrush = ShaderBrush(shader)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader(shaderString: String): RuntimeShader = RuntimeShader(android.graphics.RuntimeShader(shaderString))
