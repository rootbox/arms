package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.HostState

// 리모컨(폰) 화면의 "스마트싱스" 카드가 무엇을 보여줄지 정하는 순수 규칙. 태블릿을 만지지 않고
// 스마트싱스 연결 허용 창(10분)을 여는 흐름: 버튼 → st_pair → 호스트 ack → HostState.stPairingOpenUntilMs로 카운트다운.
// 시계 기준: stPairingOpenUntilMs는 태블릿의 벽시계(epoch ms)다. 폰 시계와 몇 초 어긋날 수 있지만 카운트다운 용도로는 충분하다.
object SmartThingsPairingPolicy {
    // 창 길이(분). 호스트의 PairingWindow.DEFAULT_DURATION_MS(10분)와 같다. 버튼 문구에만 쓴다 — 실제 남은 시간은 호스트가 준다.
    const val WINDOW_MINUTES = 10L
    const val OPEN_BUTTON_LABEL = "연결 허용 (${WINDOW_MINUTES}분)"
    const val HINT_WHILE_OPEN = "SmartThings 앱에서 기기 추가 → 주변 기기 검색"
    const val DEFAULT_OPENED_MESSAGE = "${WINDOW_MINUTES}분 동안 스마트싱스 연결을 허용합니다"
    const val DEFAULT_REJECTED_MESSAGE = "연결 허용을 열지 못했습니다"
    // 카운트다운 갱신 주기. 초 단위 표시라 1초면 충분하다.
    const val TICK_MS = 1_000L

    sealed class View {
        // 창이 닫혀 있다(또는 상태를 아직 모른다): 버튼을 보여준다.
        object Closed : View()
        // 창이 열려 있다: 남은 시간과 안내를 보여준다.
        data class Open(val remainingMs: Long) : View()
    }

    // 창이 열려 있으면 남은 시간(ms, 0 초과), 아니면 0.
    fun remainingMs(openUntilMs: Long?, nowMs: Long): Long {
        if (openUntilMs == null) return 0L
        return (openUntilMs - nowMs).coerceAtLeast(0L)
    }

    fun isOpen(openUntilMs: Long?, nowMs: Long): Boolean = remainingMs(openUntilMs, nowMs) > 0L

    fun view(openUntilMs: Long?, nowMs: Long): View {
        val left = remainingMs(openUntilMs, nowMs)
        return if (left > 0L) View.Open(left) else View.Closed
    }

    fun viewOf(state: HostState?, nowMs: Long): View = view(state?.stPairingOpenUntilMs, nowMs)

    // "연결된 스마트싱스: n대"
    fun clientCountLabel(count: Int): String = "연결된 스마트싱스: ${count.coerceAtLeast(0)}대"

    fun clientCountLabelOf(state: HostState?): String = clientCountLabel(state?.stClientCount ?: 0)

    // 남은 시간 "m:ss". 초는 올림(남아 있는 동안 0:00이 보이지 않게).
    fun formatCountdown(ms: Long): String {
        val totalSec = ((ms.coerceAtLeast(0L) + 999L) / 1000L)
        return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
    }

    // "연결 허용 중 · m:ss 남음 — SmartThings 앱에서 기기 추가 → 주변 기기 검색"
    fun countdownLabel(remainingMs: Long): String = "연결 허용 중 · ${formatCountdown(remainingMs)} 남음 — $HINT_WHILE_OPEN"

    // st_pair ack를 받았을 때 카드에 잠깐 보여줄 문구. 호스트가 준 메시지가 있으면 그대로.
    fun ackMessage(ok: Boolean, message: String?): String =
        message?.takeIf { it.isNotBlank() } ?: if (ok) DEFAULT_OPENED_MESSAGE else DEFAULT_REJECTED_MESSAGE

    // 버튼을 누를 수 있는지: 명령을 보낼 수 있고(연결됨·대기 중인 ack 없음) 창이 닫혀 있을 때만.
    // 호스트 상태를 아직 못 받았어도(state == null) 보낼 수는 있다 — ack로 결과를 안다.
    fun canOpen(canSend: Boolean, openUntilMs: Long?, nowMs: Long): Boolean = canSend && !isOpen(openUntilMs, nowMs)
}
