package com.xg.toolbox.feature.system.domain.display

data class DisplayOption(val id: String, val label: String)

data class DisplayState(
    val size: String,
    val physicalSize: String,
    val density: Int,
    val refreshOptions: List<Int>,
    val currentPeakHz: Float?,
    val currentMinHz: Float?,
    val forceHighRefresh: Boolean,
    val touchSampleOptions: List<Int>,
    val touchSampleHz: Int?,
    val dcSupported: Boolean,
    val dcEnabled: Boolean,
    val colorTemperatureSupported: Boolean,
    val colorTemperature: Int?,
    val nightMode: String,
    val densityRange: IntRange,
)
