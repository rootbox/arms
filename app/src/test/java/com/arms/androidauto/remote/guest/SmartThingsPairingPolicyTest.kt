package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartThingsPairingPolicyTest {
    private val now = 1_700_000_000_000L

    private fun state(openUntil: Long?, clients: Int = 0) = HostState(
        mediaId = "4", title = "발라드", artist = null, isPlaying = true, playbackState = 3,
        artworkRef = "station:4", bluetooth = null, batteryPercent = 80, updatedAtMs = now,
        stPairingOpenUntilMs = openUntil, stClientCount = clients,
    )

    @Test fun `마감이 없거나 지났으면 닫힘, 미래면 남은 시간과 함께 열림`() {
        assertEquals(SmartThingsPairingPolicy.View.Closed, SmartThingsPairingPolicy.view(null, now))
        assertEquals(SmartThingsPairingPolicy.View.Closed, SmartThingsPairingPolicy.view(now, now))
        assertEquals(SmartThingsPairingPolicy.View.Closed, SmartThingsPairingPolicy.view(now - 1L, now))
        assertEquals(SmartThingsPairingPolicy.View.Open(1L), SmartThingsPairingPolicy.view(now + 1L, now))
        assertEquals(SmartThingsPairingPolicy.View.Open(600_000L), SmartThingsPairingPolicy.view(now + 600_000L, now))
        // HostState 경유: 상태가 없으면 닫힘.
        assertEquals(SmartThingsPairingPolicy.View.Closed, SmartThingsPairingPolicy.viewOf(null, now))
        assertEquals(SmartThingsPairingPolicy.View.Open(5_000L), SmartThingsPairingPolicy.viewOf(state(now + 5_000L), now))
    }

    @Test fun `남은 시간은 0 아래로 내려가지 않는다`() {
        assertEquals(0L, SmartThingsPairingPolicy.remainingMs(null, now))
        assertEquals(0L, SmartThingsPairingPolicy.remainingMs(now - 10_000L, now))
        assertEquals(10_000L, SmartThingsPairingPolicy.remainingMs(now + 10_000L, now))
        assertFalse(SmartThingsPairingPolicy.isOpen(now - 10_000L, now))
        assertTrue(SmartThingsPairingPolicy.isOpen(now + 10_000L, now))
    }

    @Test fun `카운트다운은 m_ss이고 초는 올림`() {
        assertEquals("10:00", SmartThingsPairingPolicy.formatCountdown(600_000L))
        assertEquals("10:00", SmartThingsPairingPolicy.formatCountdown(599_001L))
        assertEquals("9:59", SmartThingsPairingPolicy.formatCountdown(599_000L))
        assertEquals("1:05", SmartThingsPairingPolicy.formatCountdown(65_000L))
        assertEquals("0:01", SmartThingsPairingPolicy.formatCountdown(1L))
        assertEquals("0:00", SmartThingsPairingPolicy.formatCountdown(0L))
        assertEquals("0:00", SmartThingsPairingPolicy.formatCountdown(-5L))
    }

    @Test fun `열린 동안 문구는 남은 시간과 SmartThings 앱 안내를 담는다`() {
        assertEquals(
            "연결 허용 중 · 9:59 남음 — SmartThings 앱에서 기기 추가 → 주변 기기 검색",
            SmartThingsPairingPolicy.countdownLabel(599_000L),
        )
        assertEquals("연결 허용 (10분)", SmartThingsPairingPolicy.OPEN_BUTTON_LABEL)
    }

    @Test fun `연결된 스마트싱스 수 문구`() {
        assertEquals("연결된 스마트싱스: 0대", SmartThingsPairingPolicy.clientCountLabel(0))
        assertEquals("연결된 스마트싱스: 2대", SmartThingsPairingPolicy.clientCountLabel(2))
        assertEquals("연결된 스마트싱스: 0대", SmartThingsPairingPolicy.clientCountLabel(-1))
        assertEquals("연결된 스마트싱스: 0대", SmartThingsPairingPolicy.clientCountLabelOf(null))
        assertEquals("연결된 스마트싱스: 3대", SmartThingsPairingPolicy.clientCountLabelOf(state(null, clients = 3)))
    }

    @Test fun `ack 문구는 호스트 메시지를 우선하고 없으면 기본 문구`() {
        assertEquals("10분 동안 스마트싱스 연결을 허용합니다", SmartThingsPairingPolicy.ackMessage(true, "10분 동안 스마트싱스 연결을 허용합니다"))
        assertEquals("스마트싱스 연결이 꺼져 있습니다", SmartThingsPairingPolicy.ackMessage(false, "스마트싱스 연결이 꺼져 있습니다"))
        assertEquals(SmartThingsPairingPolicy.DEFAULT_OPENED_MESSAGE, SmartThingsPairingPolicy.ackMessage(true, null))
        assertEquals(SmartThingsPairingPolicy.DEFAULT_OPENED_MESSAGE, SmartThingsPairingPolicy.ackMessage(true, "  "))
        assertEquals(SmartThingsPairingPolicy.DEFAULT_REJECTED_MESSAGE, SmartThingsPairingPolicy.ackMessage(false, null))
    }

    @Test fun `버튼은 보낼 수 있고 창이 닫혀 있을 때만 활성`() {
        assertTrue(SmartThingsPairingPolicy.canOpen(canSend = true, openUntilMs = null, nowMs = now))
        assertTrue(SmartThingsPairingPolicy.canOpen(canSend = true, openUntilMs = now - 1L, nowMs = now))
        assertFalse(SmartThingsPairingPolicy.canOpen(canSend = true, openUntilMs = now + 60_000L, nowMs = now))
        assertFalse(SmartThingsPairingPolicy.canOpen(canSend = false, openUntilMs = null, nowMs = now))
    }

    @Test fun `창 길이 상수는 호스트와 같은 10분`() {
        assertEquals(10L, SmartThingsPairingPolicy.WINDOW_MINUTES)
        assertEquals(1_000L, SmartThingsPairingPolicy.TICK_MS)
    }
}
