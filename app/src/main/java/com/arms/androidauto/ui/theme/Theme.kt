package com.arms.androidauto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Spotify 느낌의 항상 어두운 팔레트 (다이나믹 컬러는 사용하지 않음): near-black 배경에
// 단일 그린 강조색.
private val SpotifyColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = SpotifyBlack,
    primaryContainer = SpotifySurfaceElevated,
    onPrimaryContainer = SpotifyGreen,
    secondary = SpotifyGreen,
    onSecondary = SpotifyBlack,
    tertiary = SpotifyGreen,
    background = SpotifyBlack,
    onBackground = SpotifyTextPrimary,
    surface = SpotifyBlackElevated,
    onSurface = SpotifyTextPrimary,
    surfaceVariant = SpotifySurfaceElevated,
    onSurfaceVariant = SpotifyTextMuted,
    error = SpotifyLiveRed,
    onError = SpotifyTextPrimary
)

// 카드/다이얼로그/텍스트필드가 같은 곡률을 쓰도록 모양도 토큰으로 통일한다.
private val SpotifyShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),
    small = RoundedCornerShape(Radius.md),
    medium = RoundedCornerShape(Radius.lg),
    large = RoundedCornerShape(Radius.xl),
    extraLarge = RoundedCornerShape(Radius.xxl)
)

@Composable
fun ARMSAndroidAutoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SpotifyColorScheme,
        typography = Typography,
        shapes = SpotifyShapes,
        content = content
    )
}
