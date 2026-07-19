package com.arms.androidauto.ui.theme

import androidx.compose.ui.graphics.Color

// Spotify 스타일의 near-black 배경 + 단일 그린 강조색 팔레트.
val SpotifyGreen = Color(0xFF1ED760)
val SpotifyGreenDim = Color(0xFF1AA34A)

// 배경/서피스: 거의 검정에서 살짝 밝은 순으로 층을 둔다 (Spotify의 #121212 계열).
val SpotifyBlack = Color(0xFF121212)
val SpotifyBlackElevated = Color(0xFF181818)
val SpotifySurface = Color(0xFF1E1E1E)
val SpotifySurfaceElevated = Color(0xFF282828)

val SpotifyTextPrimary = Color(0xFFFFFFFF)
val SpotifyTextMuted = Color(0xFFB3B3B3)
// "ON AIR" 라이브 표시/정지 버튼용의 절제된 레드.
val SpotifyLiveRed = Color(0xFFF0353F)

// 기존 코드가 참조하던 이름들을 새 팔레트로 매핑 (호출부 대량 수정 없이 톤을 통일).
// 네온 3색은 모두 단일 그린 강조색으로 합쳐 Spotify처럼 깔끔하게 통일한다.
val RadioNeonCyan = SpotifyGreen
val RadioNeonMagenta = SpotifyGreen
val RadioNeonOrange = SpotifyGreen

val RadioBgDeep = SpotifyBlack
val RadioBgMid = SpotifyBlackElevated
val RadioBgSurface = SpotifySurface
val RadioSurfaceVariant = SpotifySurfaceElevated

val RadioOnDark = SpotifyTextPrimary
val RadioOnDarkMuted = SpotifyTextMuted
val RadioOnAirRed = SpotifyLiveRed
