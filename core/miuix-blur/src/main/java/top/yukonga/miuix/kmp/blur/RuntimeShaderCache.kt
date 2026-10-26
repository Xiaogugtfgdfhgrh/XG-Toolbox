// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.shader.RuntimeShader
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

interface RuntimeShaderCache {
    fun obtainRuntimeShader(key: String, string: String): RuntimeShader
}

val LocalRuntimeShaderCache = staticCompositionLocalOf<RuntimeShaderCache> { RuntimeShaderCacheImpl() }

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {
    private val lock = Any()
    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader = synchronized(lock) {
        runtimeShaders.getOrPut(key) {
            check(isRuntimeShaderSupported()) { "RuntimeShader requires API 33" }
            RuntimeShader(string)
        }
    }
}
