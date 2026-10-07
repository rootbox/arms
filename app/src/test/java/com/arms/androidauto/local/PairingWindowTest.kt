package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingWindowTest {

    private var now = 1_000_000L
    // 벽시계는 단조 시계와 다른 값으로 두어, 게스트에게 보내는 마감이 벽시계 기준인지 확인한다.
    private var wall = 1_700_000_000_000L
    private val window = PairingWindow(clock = { now }, wallClock = { wall })

    @Test
    fun closedByDefault() {
        assertFalse(window.isOpen())
        assertEquals(0L, window.remainingMs())
        assertNull(window.deadline.value)
        assertNull(window.consume { "token" })
    }

    @Test
    fun openForTenMinutes() {
        assertEquals(10 * 60_000L, PairingWindow.DEFAULT_DURATION_MS)
        window.open()
        assertTrue(window.isOpen())
        assertEquals(600_000L, window.remainingMs())
        now += 599_999L
        assertTrue(window.isOpen())
        assertEquals(1L, window.remainingMs())
        now += 1L
        assertFalse(window.isOpen())
        assertNull(window.deadline.value)
    }

    @Test
    fun consumeRunsOnceAndCloses() {
        window.open()
        var calls = 0
        assertEquals("t1", window.consume { calls++; "t1" })
        assertFalse(window.isOpen())
        assertNull(window.consume { calls++; "t2" })
        assertEquals(1, calls)
    }

    @Test
    fun consumeAfterExpiryFails() {
        window.open()
        now += 600_000L
        assertNull(window.consume { "late" })
    }

    @Test
    fun reopenExtendsAndCloseCloses() {
        window.open()
        now += 100_000L
        window.open()
        assertEquals(600_000L, window.remainingMs())
        window.close()
        assertFalse(window.isOpen())
    }

    @Test
    fun wallDeadlineFollowsWindowLifecycle() {
        assertNull(window.openUntilWallMs())
        window.open()
        assertEquals(wall + 600_000L, window.openUntilWallMs())
        // 시간이 흘러도(단조 시계만 진행) 마감은 열 때 정한 값 그대로다 — 상태 지문이 흔들리지 않게.
        now += 300_000L
        wall += 300_000L
        assertEquals(1_700_000_000_000L + 600_000L, window.openUntilWallMs())
        // 만료되면 null.
        now += 300_000L
        assertNull(window.openUntilWallMs())
        assertNull(window.deadline.value)
        // 다시 열면 새 마감, consume·close로 닫히면 null.
        window.open()
        assertEquals(wall + 600_000L, window.openUntilWallMs())
        window.consume { "token" }
        assertNull(window.openUntilWallMs())
        window.open()
        window.close()
        assertNull(window.openUntilWallMs())
    }
}
