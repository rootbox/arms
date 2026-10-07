package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingWindowTest {

    private var now = 1_000_000L
    private val window = PairingWindow(clock = { now })

    @Test
    fun closedByDefault() {
        assertFalse(window.isOpen())
        assertEquals(0L, window.remainingMs())
        assertNull(window.deadline.value)
        assertNull(window.consume { "token" })
    }

    @Test
    fun openForThreeMinutes() {
        window.open()
        assertTrue(window.isOpen())
        assertEquals(180_000L, window.remainingMs())
        now += 179_999L
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
        now += 180_000L
        assertNull(window.consume { "late" })
    }

    @Test
    fun reopenExtendsAndCloseCloses() {
        window.open()
        now += 100_000L
        window.open()
        assertEquals(180_000L, window.remainingMs())
        window.close()
        assertFalse(window.isOpen())
    }
}
