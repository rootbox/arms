package com.arms.androidauto

// 재생 오류 자동 복구 간격. 예전엔 30초 안에 2번만 재시도하고 영구히 포기했다.
// 2026-10-04 06:00 벽걸이 태블릿에서 SBS 스트림 오류 뒤 59시간 무음으로 남은 사고(화면은 "ON AIR").
// 사람이 만지지 않는 기기도 스스로 돌아오도록: 처음 두 번은 빠르게(3s), 이후 30s·60s·2m·5m, 상한 10분으로
// 재생 의도(playWhenReady)가 살아 있는 동안 계속 시도한다. 네트워크가 다시 잡히면 대기를 건너뛴다.
internal object PlaybackRecoveryPolicy {
    fun nextDelayMs(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 2 -> 3_000L
        consecutiveFailures == 3 -> 30_000L
        consecutiveFailures == 4 -> 60_000L
        consecutiveFailures == 5 -> 120_000L
        consecutiveFailures == 6 -> 300_000L
        else -> 600_000L
    }

    // 성공적으로 이만큼 재생되면 실패 횟수를 초기화한다(잠깐 붙었다 바로 끊기는 소스가 빠른 재시도를 독점하지 않게).
    const val HEALTHY_PLAYBACK_RESET_MS = 60_000L
}
