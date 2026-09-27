package com.arms.androidauto.ui.theme

import androidx.compose.ui.graphics.Color

// YouTube Music 스타일 팔레트 (항상 다크).
// near-black 배경 + 회색 서피스, 컨트롤 강조는 흰색, 빨강은 "ON AIR"/라이브와 선택된 칩에만 쓴다.
// 초록은 어디에도 쓰지 않는다.
val YtRed = Color(0xFFFF0033)
val YtBackground = Color(0xFF0F0F0F)
val YtBackdrop = Color(0xFF030303) // 재생 화면(블러 커버 뒤) 배경
val YtSurface = Color(0xFF212121)
val YtSurfaceElevated = Color(0xFF282828)
val YtSurfaceHighest = Color(0xFF303030)
val YtDivider = Color(0xFF3F3F3F)
val YtTextPrimary = Color(0xFFFFFFFF)
val YtTextSecondary = Color(0xFFAAAAAA)
val YtAccent = YtTextPrimary // 버튼/선택/재생 상태 등 컨트롤 강조 = 흰색

// ---- 기존 토큰 이름 → 새 팔레트 매핑 ----
// 다른 파일들이 이 이름들을 import하므로 이름은 그대로 두고 값만 바꾼다.
//   SpotifyGreen, SpotifyGreenDim, RadioNeonCyan/Magenta/Orange  -> YtAccent (흰색; 초록 강조색 폐지)
//   SpotifyBlack, RadioBgDeep, RadioBgMid                          -> YtBackground (배경 그라데이션 없이 단색)
//   SpotifyBlackElevated, SpotifySurface, RadioBgSurface           -> YtSurface (#212121)
//   SpotifySurfaceElevated, RadioSurfaceVariant                    -> YtSurfaceElevated (#282828)
//   SpotifyTextPrimary, RadioOnDark                                -> YtTextPrimary (#FFFFFF)
//   SpotifyTextMuted, RadioOnDarkMuted                             -> YtTextSecondary (#AAAAAA)
//   SpotifyLiveRed, RadioOnAirRed                                  -> YtRed (#FF0033)
val SpotifyGreen = YtAccent
val SpotifyGreenDim = YtTextSecondary

val SpotifyBlack = YtBackground
val SpotifyBlackElevated = YtSurface
val SpotifySurface = YtSurface
val SpotifySurfaceElevated = YtSurfaceElevated

val SpotifyTextPrimary = YtTextPrimary
val SpotifyTextMuted = YtTextSecondary
val SpotifyLiveRed = YtRed

val RadioNeonCyan = YtAccent
val RadioNeonMagenta = YtAccent
val RadioNeonOrange = YtAccent

val RadioBgDeep = YtBackground
val RadioBgMid = YtBackground
val RadioBgSurface = YtSurface
val RadioSurfaceVariant = YtSurfaceElevated

val RadioOnDark = YtTextPrimary
val RadioOnDarkMuted = YtTextSecondary
val RadioOnAirRed = YtRed
