package com.arms.androidauto.ui.theme

import androidx.compose.ui.unit.dp

// 4dp 그리드 기반 여백 토큰.
// 화면 코드에서 10.dp, 14.dp 같은 값을 직접 쓰지 말고 여기 것을 쓴다.
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    // 화면 좌우 기본 여백 (Spotify와 동일하게 16dp)
    val screenHorizontal = lg
}

object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
}

// 목록 썸네일/재생 버튼처럼 반복해서 쓰는 크기
object Sizes {
    val listThumbnail = 48.dp
    val miniPlayerThumbnail = 40.dp
    val miniPlayerButton = 40.dp
    val miniPlayerIcon = 20.dp
    val playerPrimaryButton = 72.dp
    val playerSecondaryButton = 48.dp
    val playerIcon = 32.dp
}
