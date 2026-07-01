package com.arms.androidauto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// 라디오 튜너 느낌을 위해 항상 어두운 네온 팔레트를 사용 (다이나믹 컬러는 사용하지 않음)
private val RadioColorScheme = darkColorScheme(
    primary = RadioNeonCyan,
    onPrimary = RadioBgDeep,
    primaryContainer = RadioBgSurface,
    onPrimaryContainer = RadioNeonCyan,
    secondary = RadioNeonMagenta,
    onSecondary = RadioBgDeep,
    tertiary = RadioNeonOrange,
    background = RadioBgDeep,
    onBackground = RadioOnDark,
    surface = RadioBgSurface,
    onSurface = RadioOnDark,
    surfaceVariant = RadioSurfaceVariant,
    onSurfaceVariant = RadioOnDarkMuted,
    error = RadioOnAirRed,
    onError = RadioOnDark
)

@Composable
fun ARMSAndroidAutoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RadioColorScheme,
        typography = Typography,
        content = content
    )
}
