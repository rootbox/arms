package com.arms.androidauto.remote.host

import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.local.CommandOutcome

// 호스트(벽걸이 태블릿)의 세션·전원 관련 "결정"만 모은 순수 Kotlin. 타이머·Android 호출은 서비스 몫.
// (2026-10-12 §1: 세션이 끊긴 채 200을 주던 결함, IDLE에서 만료된 서명 URL로 재생하던 결함, 절전 중 인바운드 지연.)

// 미디어 세션(MediaController) 재연결 간격: 2s, 5s, 10s, 그 뒤로 30s. 서비스가 사는 동안 끝없이 재시도한다.
object ControllerReconnectPolicy {
    val DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L)
    // 연결 시도 하나의 상한. 넘기면 future를 버리고 다시 시도한다(바인드가 영원히 안 끝나는 경우 대비).
    const val CONNECT_TIMEOUT_MS = 15_000L

    // attempt: 연속 실패 횟수(1 = 방금 첫 실패/끊김).
    fun delayMs(attempt: Int): Long = DELAYS_MS[(attempt.coerceAtLeast(1) - 1).coerceAtMost(DELAYS_MS.size - 1)]
}

// 명령을 실행하기 전 세션 연결 확인. 컨트롤러가 없거나(null) 끊겼으면 503 unavailable — 호출자는 즉시 재연결을 건다.
object SessionGate {
    const val CONNECTING_MESSAGE = "미디어 세션 연결 중"

    // connected: null = 컨트롤러 없음, false = 있지만 끊김(Media3가 자동 release), true = 정상.
    fun outcome(connected: Boolean?): CommandOutcome? =
        if (connected == true) null else CommandOutcome.unavailable(CONNECTING_MESSAGE)

    fun isAvailable(connected: Boolean?): Boolean = connected == true
}

// "켜기/재생"에서 플레이어에 무엇을 시킬지.
// - 큐가 비었거나 IDLE인데 현재 항목을 모르면: 마지막 채널을 새로 넣고 재생(ReplaceWithLast).
// - IDLE(정지·오류 뒤)이고 라디오 항목이면: 같은 mediaId를 다시 setMediaItem → 서비스가 서명 스트림 URL을 새로 받는다(Restart).
// - IDLE이고 NAS 항목이면: prepare+play로 이어서(큐·위치를 지킨다).
// - 그 밖(READY/BUFFERING/ENDED): play만.
object ResumePolicy {
    // Media3 Player.STATE_IDLE (의존을 피하려고 숫자 그대로; LocalStateMapper와 같은 방식)
    const val STATE_IDLE = 1

    sealed class Action {
        data object ReplaceWithLast : Action()
        data class Restart(val mediaId: String) : Action()
        data object PrepareAndPlay : Action()
        data object Play : Action()
    }

    fun decide(hasItems: Boolean, playbackState: Int, currentMediaId: String?): Action {
        if (!hasItems) return Action.ReplaceWithLast
        if (playbackState != STATE_IDLE) return Action.Play
        if (currentMediaId.isNullOrEmpty()) return Action.ReplaceWithLast
        return if (MediaIdScheme.isNas(currentMediaId)) Action.PrepareAndPlay else Action.Restart(currentMediaId)
    }
}

// 벽걸이 모드 락: 로컬 제어(스마트싱스)가 켜져 있고 충전 중일 때만 PARTIAL_WAKE_LOCK + WifiLock을 잡는다.
// 배터리로 도는 동안(차량·휴대)에는 잡지 않는다.
object HostPowerPolicy {
    fun shouldHoldLocks(localEnabled: Boolean, charging: Boolean): Boolean = localEnabled && charging

    // ACTION_BATTERY_CHANGED의 EXTRA_PLUGGED(0 = 전원 없음, 그 외 AC/USB/무선/독).
    fun isCharging(plugged: Int): Boolean = plugged != 0
}
