package com.arms.androidauto

// 앱 업데이트·재부팅 뒤 자동으로 무엇을 되살릴지. 벽걸이 태블릿은 업데이트 후 앱을 여는 사람이 없어서
// v23 설치 직후 리모컨 호스트와 라디오가 앱을 열 때까지 멈춰 있었다(2026-10-06).
internal object AutoResumePolicy {
    // 재생 의도: 재생 중이거나 버퍼링 중, 또는 오류로 멈춰 복구를 기다리는 중. 사용자가 정지(stop → IDLE, 오류 없음)·
    // 일시정지(playWhenReady=false)했거나 큐가 비었으면 의도 없음.
    fun isPlaybackIntended(playWhenReady: Boolean, hasItem: Boolean, isIdle: Boolean, hasError: Boolean): Boolean =
        playWhenReady && hasItem && (!isIdle || hasError)

    // 자동 재생은 홈 플레이어(호스트)에서만. 폰은 업데이트·재부팅 직후 주머니에서 소리가 나면 안 된다.
    fun shouldResumePlayback(isHomePlayer: Boolean, wasPlaying: Boolean): Boolean = isHomePlayer && wasPlaying
}
