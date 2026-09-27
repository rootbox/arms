package com.arms.androidauto.core.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {
    @Test
    fun `seq must increase monotonically`() {
        val guard = ReplayGuard(windowMs = 1_000)
        assertTrue(guard.accept(seq = 10, sentAtMs = 100, nowMs = 100))
        assertFalse("same seq", guard.accept(seq = 10, sentAtMs = 100, nowMs = 100))
        assertFalse("older seq", guard.accept(seq = 9, sentAtMs = 100, nowMs = 100))
        assertTrue("gaps are fine", guard.accept(seq = 50, sentAtMs = 100, nowMs = 100))
        assertFalse(guard.accept(seq = 11, sentAtMs = 100, nowMs = 100))
    }

    @Test
    fun `time window is enforced in both directions`() {
        val guard = ReplayGuard(windowMs = 1_000)
        assertFalse("too old", guard.accept(seq = 1, sentAtMs = 0, nowMs = 1_001))
        assertFalse("from the future", guard.accept(seq = 1, sentAtMs = 2_002, nowMs = 1_001))
        assertTrue("at the edge", guard.accept(seq = 1, sentAtMs = 1, nowMs = 1_001))
        assertTrue("at the other edge", guard.accept(seq = 2, sentAtMs = 2_001, nowMs = 1_001))
    }

    @Test
    fun `rejected message does not advance the seq`() {
        val guard = ReplayGuard(windowMs = 1_000)
        assertFalse(guard.accept(seq = 100, sentAtMs = 0, nowMs = 10_000))
        assertTrue("seq 5 still accepted because 100 was rejected", guard.accept(seq = 5, sentAtMs = 10_000, nowMs = 10_000))
    }

    @Test
    fun `reset allows earlier seq again`() {
        val guard = ReplayGuard(windowMs = 1_000)
        assertTrue(guard.accept(seq = 10, sentAtMs = 0, nowMs = 0))
        assertFalse(guard.accept(seq = 5, sentAtMs = 0, nowMs = 0))
        guard.reset()
        assertTrue(guard.accept(seq = 5, sentAtMs = 0, nowMs = 0))
    }

    @Test
    fun `unbounded window ignores time`() {
        val guard = ReplayGuard(windowMs = Long.MAX_VALUE)
        assertTrue(guard.accept(seq = 1, sentAtMs = 0, nowMs = Long.MAX_VALUE))
        assertTrue(guard.accept(seq = 2, sentAtMs = Long.MAX_VALUE, nowMs = 0))
    }

    @Test
    fun `default window is two minutes`() {
        val guard = ReplayGuard()
        assertTrue(guard.accept(seq = 1, sentAtMs = 0, nowMs = 120_000))
        assertFalse(guard.accept(seq = 2, sentAtMs = 0, nowMs = 120_001))
    }
}
