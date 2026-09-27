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

    // 화면 좌우 기본 여백 (YouTube Music과 동일하게 16dp)
    val screenHorizontal = lg
}

object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
}

// 목록 썸네일/재생 버튼처럼 반복해서 쓰는 크기 (YouTube Music 기준)
object Sizes {
    // 목록 행 썸네일 56dp, 모서리 Radius.md(8dp)
    val listThumbnail = 56.dp
    // 미니플레이어: 48dp 커버, 상단 2dp 진행/라이브 선
    val miniPlayerThumbnail = 48.dp
    val miniPlayerButton = 40.dp
    val miniPlayerIcon = 24.dp
    val miniPlayerProgress = 2.dp
    // 전체화면 플레이어: 64dp 흰 원형 재생 버튼, 이전/다음은 40dp 흰 아이콘
    val playerPrimaryButton = 64.dp
    val playerSecondaryButton = 48.dp
    val playerIcon = 32.dp
    val playerSkipIcon = 40.dp
    // 재생 중 표시용 작은 이퀄라이저
    val equalizerHeight = 16.dp
}
