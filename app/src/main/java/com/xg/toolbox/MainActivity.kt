package com.xg.toolbox

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xg.toolbox.core.ui.theme.XgTheme
import com.xg.toolbox.root.XgRootScaffold
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyHighestRefreshRate()
        setContent {
            XgTheme {
                XgRootScaffold()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyHighestRefreshRate()
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun applyHighestRefreshRate() {
        val display = display ?: return
        val modes = display.supportedModes
        if (modes.isEmpty()) return
        val current = display.mode
        val sameResolution = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val candidates = sameResolution.ifEmpty { modes.toList() }
        val target = candidates
            .filter { it.refreshRate <= MAX_REFRESH_HZ + 0.5f }
            .maxByOrNull { it.refreshRate }
            ?: candidates.maxByOrNull { it.refreshRate }
            ?: return
        val requested = target.refreshRate.coerceAtMost(MAX_REFRESH_HZ)
        val attributes = window.attributes
        attributes.preferredDisplayModeId = target.modeId
        attributes.preferredRefreshRate = requested
        window.attributes = attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                window.decorView.setRequestedFrameRate(requested)
                window.setFrameRateBoostOnTouchEnabled(true)
                window.setFrameRatePowerSavingsBalanced(false)
            }
        }
    }

    private companion object {
        const val MAX_REFRESH_HZ = 185f
    }
}
