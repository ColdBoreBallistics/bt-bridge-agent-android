// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorBackground = Color(0xFF0F1112)
private val ColorSurface    = Color(0xFF181C1E)
private val ColorAmber      = Color(0xFFF0A500)
private val ColorGreen      = Color(0xFF5A9E6F)

private val DarkColors = darkColorScheme(
    primary         = ColorAmber,
    onPrimary       = Color.Black,
    background      = ColorBackground,
    onBackground    = Color(0xFFDDE3E8),
    surface         = ColorSurface,
    onSurface       = Color(0xFFDDE3E8),
    secondary       = ColorGreen,
    onSecondary     = Color.Black,
)

@Composable
fun BtBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content,
    )
}
