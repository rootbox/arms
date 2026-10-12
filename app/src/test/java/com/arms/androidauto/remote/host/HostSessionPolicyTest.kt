package com.arms.androidauto.remote.host

import com.arms.androidauto.auto.MediaIdScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionPolicyTest {

    // ---- ControllerReconnectPolicy ----

    @Test
    fun reconnectBackoffIsTwoFiveTenThenThirtyForever() {
        assertEquals(
            listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L),
            (1..6).map { ControllerReconnectPolicy.delayMs(it) },
        )
        assertEquals(2_000L, ControllerReconnectPolicy.delayMs(0))
        assertEquals(2_000L, ControllerReconnectPolicy.delayMs(-5))
        assertEquals(30_000L, ControllerReconnectPolicy.delayMs(Int.MAX_VALUE))
    }

    // ---- SessionGate (503 경로) ----

    @Test
    fun missingOrDisconnectedControllerIs503Unavailable() {
        val none = SessionGate.outcome(connected = null)
        assertNotNull(none)
        assertEquals(503, none!!.httpStatus)
        assertFalse(none.ok)
        assertEquals("미디어 세션 연결 중", none.message)

        val dropped = SessionGate.outcome(connected = false)
        assertEquals(503, dropped!!.httpStatus)
        assertEquals(SessionGate.CONNECTING_MESSAGE, dropped.message)

        assertFalse(SessionGate.isAvailable(null))
        assertFalse(SessionGate.isAvailable(false))
    }

    @Test
    fun connectedControllerPassesGate() {
        assertNull(SessionGate.outcome(connected = true))
        assertTrue(SessionGate.isAvailable(true))
    }

    // ---- ResumePolicy ----

    @Test
    fun emptyQueueReplacesWithLastStation() {
        assertEquals(ResumePolicy.Action.ReplaceWithLast, ResumePolicy.decide(hasItems = false, playbackState = 1, currentMediaId = null))
        assertEquals(ResumePolicy.Action.ReplaceWithLast, ResumePolicy.decide(hasItems = false, playbackState = 3, currentMediaId = "1"))
    }

    @Test
    fun idleRadioRestartsSameMediaIdSoStreamUrlIsRefreshed() {
        assertEquals(ResumePolicy.Action.Restart("7"), ResumePolicy.decide(hasItems = true, playbackState = ResumePolicy.STATE_IDLE, currentMediaId = "7"))
    }

    @Test
    fun idleWithoutCurrentItemFallsBackToLastStation() {
        assertEquals(ResumePolicy.Action.ReplaceWithLast, ResumePolicy.decide(hasItems = true, playbackState = 1, currentMediaId = null))
        assertEquals(ResumePolicy.Action.ReplaceWithLast, ResumePolicy.decide(hasItems = true, playbackState = 1, currentMediaId = ""))
    }

    @Test
    fun idleNasItemOnlyPreparesAndPlays() {
        val nas = MediaIdScheme.encodeSong("music_1")
        assertTrue(MediaIdScheme.isNas(nas))
        assertEquals(ResumePolicy.Action.PrepareAndPlay, ResumePolicy.decide(hasItems = true, playbackState = 1, currentMediaId = nas))
    }

    @Test
    fun nonIdleStatesJustPlay() {
        for (state in listOf(2, 3, 4)) {
            assertEquals(ResumePolicy.Action.Play, ResumePolicy.decide(hasItems = true, playbackState = state, currentMediaId = "1"))
        }
    }

    // ---- HostPowerPolicy ----

    @Test
    fun locksOnlyWhenLocalControlEnabledAndCharging() {
        assertTrue(HostPowerPolicy.shouldHoldLocks(localEnabled = true, charging = true))
        assertFalse(HostPowerPolicy.shouldHoldLocks(localEnabled = true, charging = false))
        assertFalse(HostPowerPolicy.shouldHoldLocks(localEnabled = false, charging = true))
        assertFalse(HostPowerPolicy.shouldHoldLocks(localEnabled = false, charging = false))
    }

    @Test
    fun chargingMeansAnyPluggedSource() {
        assertFalse(HostPowerPolicy.isCharging(plugged = 0))
        assertTrue(HostPowerPolicy.isCharging(plugged = 1)) // AC
        assertTrue(HostPowerPolicy.isCharging(plugged = 2)) // USB
        assertTrue(HostPowerPolicy.isCharging(plugged = 4)) // 무선
    }
}
