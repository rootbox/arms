package com.arms.androidauto

// 미디어 알림을 다시 게시할지 결정하는 순수 정책.
// Media3 1.3.1은 타임라인 변경(HLS 라이브 플레이리스트 갱신, 약 6초)마다 알림을 다시 그리고
// startForeground()를 재호출한다. 실사용 로그(S22, 2026-09-27)에서 내용이 같은 알림이 시간당
// 약 600회 게시돼 SystemUI·런처·Android Auto 파서를 매번 깨웠다. 사용자에게 보이는 값이
// 바뀌었을 때만 게시한다.
internal object NotificationUpdatePolicy {
    data class Fingerprint(
        val playWhenReady: Boolean,
        val playbackState: Int,
        val isPlaying: Boolean,
        val mediaId: String?,
        val title: String?,
        val artist: String?,
        val artworkKey: String?,
        val startInForegroundRequired: Boolean,
    )

    // 첫 게시, 또는 지문이 달라진 경우에만 true. 포그라운드 전환 요구가 바뀐 경우는 지문에 포함돼
    // 있어 항상 통과한다(FGS 시작을 건너뛰면 재생이 죽는다).
    fun shouldPost(last: Fingerprint?, next: Fingerprint): Boolean = last == null || last != next
}
