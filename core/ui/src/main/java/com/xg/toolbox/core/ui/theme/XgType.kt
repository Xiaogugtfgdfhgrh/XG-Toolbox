package com.xg.toolbox.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object XgType {
    private const val TNUM = "tnum"

    private fun sans(
        size: Float,
        weight: FontWeight,
        lineHeight: Float,
        letterSpacing: Float = 0f,
    ): TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
    )

    private fun mono(
        size: Float,
        weight: FontWeight,
        lineHeight: Float,
        letterSpacing: Float = 0f,
    ): TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
        fontFeatureSettings = TNUM,
    )

    
    val pageTitle: TextStyle = sans(19f, FontWeight.Bold, 26f)

    
    val sectionTitle: TextStyle = sans(14f, FontWeight.SemiBold, 20f)

    
    val cardTitle: TextStyle = sans(13f, FontWeight.SemiBold, 19f)

    
    val body: TextStyle = sans(13f, FontWeight.Normal, 21f)

    
    val note: TextStyle = sans(9.5f, FontWeight.Normal, 15f)

    
    val label: TextStyle = mono(8.5f, FontWeight.SemiBold, 12f, letterSpacing = 0.68f)

    
    val fieldLabel: TextStyle = sans(10f, FontWeight.SemiBold, 14f, letterSpacing = 0.2f)

    
    val monoKey: TextStyle = mono(10f, FontWeight.Normal, 14f, letterSpacing = 0.2f)

    
    val monoValue: TextStyle = mono(12f, FontWeight.Normal, 16f)

    
    val monoValueMedium: TextStyle = mono(16f, FontWeight.Medium, 20f)

    
    val monoValueLarge: TextStyle = mono(24f, FontWeight.SemiBold, 28f)

    
    val chip: TextStyle = sans(11f, FontWeight.SemiBold, 14f)

    
    val badge: TextStyle = sans(9f, FontWeight.SemiBold, 12f)

    
    val terminal: TextStyle = mono(10f, FontWeight.Normal, 15f)

    
    val pageMeta: TextStyle = mono(9f, FontWeight.Normal, 12f, letterSpacing = 0.18f)

    
    val metricName: TextStyle = sans(9.5f, FontWeight.SemiBold, 13f, letterSpacing = 0.2f)

    
    val metricNow: TextStyle = mono(8f, FontWeight.Normal, 11f)

    
    val metricUnit: TextStyle = mono(8f, FontWeight.Normal, 11f, letterSpacing = 0.2f)

    
    val rowTitle: TextStyle = sans(11.5f, FontWeight.SemiBold, 16f)

    
    val rowNote: TextStyle = sans(9.5f, FontWeight.Normal, 15f)

    
    val kvValue: TextStyle = mono(11f, FontWeight.Normal, 17f)

    
    val statusChip: TextStyle = mono(9f, FontWeight.SemiBold, 12f, letterSpacing = 0.36f)

    
    val monoSmall: TextStyle = mono(8.5f, FontWeight.Normal, 12f, letterSpacing = 0.17f)

    
    val choice: TextStyle = sans(10.5f, FontWeight.SemiBold, 14f)

    
    val toast: TextStyle = sans(10.5f, FontWeight.Medium, 15f)

    
    val dialogTitle: TextStyle = sans(15f, FontWeight.SemiBold, 20f)
}
