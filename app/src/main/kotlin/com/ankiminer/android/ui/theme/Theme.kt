package com.ankiminer.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF006A61),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF74F8E8),
        onPrimaryContainer = Color(0xFF00201C),
        secondary = Color(0xFF4A635F),
        secondaryContainer = Color(0xFFCDE8E2),
        background = Color(0xFFF5FBF8),
        surface = Color(0xFFF5FBF8),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF53DBCC),
        onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005048),
        onPrimaryContainer = Color(0xFF74F8E8),
        secondary = Color(0xFFB1CCC6),
        secondaryContainer = Color(0xFF334B47),
    )

@Composable
fun AnkiMinerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
