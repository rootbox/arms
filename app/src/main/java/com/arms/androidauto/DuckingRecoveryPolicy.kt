package com.arms.androidauto

// 오디오 포커스 덕킹 고착 복구 규칙 (docs/ISSUES_2026-10-12.md §2, 1단계).
//
// 증상: 다른 앱(알림음·TTS)이 GAIN_TRANSIENT_MAY_DUCK로 포커스를 잠깐 가져갔다 abandon 콜백이 오지 않으면
// Media3 AudioFocusManager의 내부 배율(×0.2)과 프레임워크의 uid 단위 VolumeShaper가 돌아오지 않아
// 채널을 바꿔도 조용하다. ExoPlayer는 포커스를 쥔 채로 prepare/play를 반복해서 포커스를 재요청하지 않는다.
//
// 1단계 대응은 두 가지다.
//  (1) 라디오를 (재)시작하기 직전에 stop()으로 IDLE을 거친다 → ExoPlayer가 포커스를 abandon하고 prepare()에서
//      다시 요청한다 → 시스템 unduck + 내부 배율 리셋. NAS 큐 안의 곡→곡 전환에는 적용하지 않는다(공백이 생긴다).
//  (2) 주기 틱마다 눈에 보이는 배율(player.volume)이 1 미만이면 되돌린다.
//      주의: Media3의 내부 덕킹 배율은 player.volume에 보이지 않는다. 이 워치독은 "보이는 부분"만 덮고,
//      보이지 않는 부분은 (1)과 2단계(자체 포커스 관리)가 맡는다.
internal object DuckingRecoveryPolicy {
    enum class Action { NONE, RESTORE_VOLUME }

    // 워치독 한 틱의 판단. 포커스를 뺏긴 상태(영구 상실로 멈춤, 또는 일시 상실로 억제 중)에서는 볼륨을 건드리지
    // 않는다 - 그때 1로 올리면 내비 안내 위에 라디오가 덮인다. 재생 중이 아닐 때도 건드릴 이유가 없다.
    fun decide(isPlaying: Boolean, inFocusLoss: Boolean, volume: Float): Action = when {
        !isPlaying -> Action.NONE
        inFocusLoss -> Action.NONE
        volume < 1f -> Action.RESTORE_VOLUME
        else -> Action.NONE
    }

    // 플레이어가 "포커스 상실" 상태인가. pausedByFocusLoss는 영구 상실/장기 억제로 서비스가 재개를 기다리는 중,
    // suppressedByFocus는 일시 상실로 ExoPlayer가 playWhenReady를 유지한 채 소리를 멈춘 상태.
    fun isInFocusLoss(pausedByFocusLoss: Boolean, suppressedByFocus: Boolean): Boolean =
        pausedByFocusLoss || suppressedByFocus

    // (재)시작 직전에 stop()을 먼저 부를지. 이미 IDLE이면(정지·오류 뒤) 포커스는 이미 놓은 상태라 불필요하다.
    // 대상이 라디오면 같은 채널을 다시 눌러도 stop을 거친다(재시작은 어차피 재버퍼링이고, 덕킹 리셋이 목적).
    // 대상이 NAS면 다른 항목으로 바뀔 때만 - 같은 큐를 다시 요청하는 경우는 그대로 둔다.
    fun shouldStopBeforeRestart(
        isIdle: Boolean,
        targetIsNas: Boolean,
        targetMediaId: String?,
        currentMediaId: String?
    ): Boolean {
        if (isIdle) return false
        if (!targetIsNas) return true
        return targetMediaId != currentMediaId
    }
}
