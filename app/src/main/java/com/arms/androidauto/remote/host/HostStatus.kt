package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.RemoteFailure
import com.arms.androidauto.core.remote.RemoteGuest

// 호스트(태블릿)의 리모컨 상태를 화면(상단바 칩·페어링 화면)에 보여 주기 위한 한 값.
// 2026-10-06 사고: 브로커에 한 번도 붙지 못한 채 4일간 "리모컨 대기 중"만 보였다. 이제
// "설정 오류 / 연결 중 / 오프라인 / 준비됨(게스트 유무)"을 구분해 보여 준다.
sealed class HostStatus {
    // 호스트 서비스가 꺼져 있다(역할이 HOST가 아니거나 권한 없음, 또는 페어링도 스마트싱스 연결도 없음).
    data object NotRunning : HostStatus()

    // 서비스는 떠 있지만(스마트싱스 로컬 제어용) 폰 리모컨 페어링이 없다. 브로커에 붙지 않는다 — 오류가 아니다.
    data object NoPairing : HostStatus()

    // 저장된 페어링의 브로커 주소가 정책상 쓸 수 없다(예: 127.0.0.1). 연결을 시도하지 않는다.
    data class InvalidConfig(val problem: BrokerUrlPolicy.Problem) : HostStatus()

    // 브로커에 붙는 중(서비스 시작 직후, 또는 끊긴 직후 첫 몇 번의 시도). attempt는 1부터.
    data class Connecting(val attempt: Int, val lastFailure: FailureKind?) : HostStatus()

    // 여러 번 실패해 백오프하며 재시도 중. sinceMs = 처음 실패(또는 끊김)한 시각.
    data class Offline(val lastFailure: FailureKind?, val sinceMs: Long) : HostStatus()

    // 브로커에 붙어 명령 토픽을 구독 중. guests가 비어 있으면 "대기 중".
    data class Ready(val guests: List<RemoteGuest>) : HostStatus()
}

object HostStatusPolicy {
    // 실패가 이어져도 이 횟수까지는 "연결 중"으로 본다(부팅 직후 네트워크가 늦게 붙는 정도는 오프라인이 아니다).
    const val CONNECTING_ATTEMPTS = 2

    // brokerState는 "명령 토픽 구독까지 끝난" 실효 상태를 넘긴다(전송이 CONNECTED여도 구독 전이면 CONNECTING).
    // failingSinceMs: 마지막 정상 연결 이후(또는 시작 이후) 첫 실패 시각. 실패한 적 없으면 null.
    // attempt: 마지막 정상 연결 이후 연속 시작 시도 횟수(1부터, 시도 전이면 0).
    fun compute(
        running: Boolean,
        configProblem: BrokerUrlPolicy.Problem?,
        brokerState: ConnectionState,
        lastFailure: FailureKind?,
        failingSinceMs: Long?,
        guests: List<RemoteGuest>,
        attempt: Int,
        // 폰 리모컨 페어링(MQTT)이 있는지. 없으면 브로커 상태와 무관하게 NoPairing.
        hasPairing: Boolean = true,
    ): HostStatus = when {
        !running -> HostStatus.NotRunning
        !hasPairing -> HostStatus.NoPairing
        configProblem != null -> HostStatus.InvalidConfig(configProblem)
        brokerState == ConnectionState.CONNECTED -> HostStatus.Ready(guests)
        failingSinceMs == null || attempt <= CONNECTING_ATTEMPTS ->
            HostStatus.Connecting(attempt.coerceAtLeast(1), lastFailure)
        else -> HostStatus.Offline(lastFailure, failingSinceMs)
    }

    // 칩 메뉴 첫 줄·페어링 화면에 쓰는 한 줄 설명.
    fun detail(status: HostStatus, nowMs: Long = System.currentTimeMillis()): String = when (status) {
        HostStatus.NotRunning -> "리모컨 호스트가 꺼져 있습니다."
        HostStatus.NoPairing -> "폰 리모컨이 페어링되지 않았습니다."
        is HostStatus.InvalidConfig -> BrokerUrlPolicy.message(status.problem)
        is HostStatus.Connecting ->
            status.lastFailure?.let { "다시 연결하는 중 · ${RemoteFailure.message(it)}" } ?: "브로커에 연결하는 중…"
        is HostStatus.Offline -> {
            val reason = RemoteFailure.message(status.lastFailure ?: FailureKind.UNKNOWN)
            val minutes = ((nowMs - status.sinceMs) / 60_000L).coerceAtLeast(0L)
            if (minutes >= 1) "$reason (${minutes}분째)" else reason
        }
        is HostStatus.Ready -> "브로커 연결됨"
    }
}

// 시작 재시도 간격: 5s, 10s, 20s, 40s, 그 뒤로 60s. 어떤 경우에도 5s보다 촘촘히 시도하지 않는다.
object StartRetryPolicy {
    const val MIN_GAP_MS = 5_000L
    const val MAX_DELAY_MS = 60_000L

    // failures: 연속 실패 횟수(1 = 방금 첫 실패). 1→5s, 2→10s, 3→20s, 4→40s, 5+→60s.
    fun nextDelayMs(failures: Int): Long {
        val n = failures.coerceAtLeast(1)
        if (n >= 5) return MAX_DELAY_MS
        return (MIN_GAP_MS shl (n - 1)).coerceAtMost(MAX_DELAY_MS)
    }

    // "지금 다시 연결"·네트워크 복구로 대기를 건너뛸 때도 직전 시도 시작으로부터 MIN_GAP_MS는 지킨다.
    fun minGapRemainingMs(lastAttemptStartMs: Long, nowMs: Long): Long =
        (MIN_GAP_MS - (nowMs - lastAttemptStartMs)).coerceIn(0L, MIN_GAP_MS)
}
