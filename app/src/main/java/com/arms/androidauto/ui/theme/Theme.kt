package com.arms.androidauto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// YouTube Music 느낌의 항상 어두운 팔레트 (다이나믹 컬러는 사용하지 않음).
// primary는 흰색이라 Button/RadioButton/TextButton/Slider가 기본값만으로 "흰 컨트롤 + 검정 글자"가 된다.
// surfaceContainer* 계열까지 지정해야 AlertDialog/DropdownMenu/NavigationBar가 M3 기본의
// 보라빛 회색 대신 우리 회색(#212121/#282828)을 쓴다.
private val YtColorScheme = darkColorScheme(
    primary = YtAccent,
    onPrimary = YtBackground,
    primaryContainer = YtSurfaceElevated,
    onPrimaryContainer = YtTextPrimary,
    secondary = YtTextSecondary,
    onSecondary = YtBackground,
    secondaryContainer = YtSurfaceElevated,
    onSecondaryContainer = YtTextPrimary,
    tertiary = YtRed,
    onTertiary = YtTextPrimary,
    background = YtBackground,
    onBackground = YtTextPrimary,
    surface = YtBackground,
    onSurface = YtTextPrimary,
    surfaceVariant = YtSurfaceElevated,
    onSurfaceVariant = YtTextSecondary,
    surfaceDim = YtBackdrop,
    surfaceBright = YtSurfaceHighest,
    surfaceContainerLowest = YtBackdrop,
    surfaceContainerLow = YtBackground,
    surfaceContainer = YtSurface,
    surfaceContainerHigh = YtSurfaceElevated,
    surfaceContainerHighest = YtSurfaceHighest,
    outline = YtDivider,
    outlineVariant = YtDivider,
    error = YtRed,
    onError = YtTextPrimary
)

// 카드/다이얼로그/텍스트필드가 같은 곡률을 쓰도록 모양도 토큰으로 통일한다.
private val YtShapes = Shapes(
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
        colorScheme = YtColorScheme,
        typography = Typography,
        shapes = YtShapes,
        content = content
    )
}
