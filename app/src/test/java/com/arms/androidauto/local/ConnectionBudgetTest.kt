package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBudgetTest {

    @Test
    fun requestsAreCappedAndReleased() {
        val b = ConnectionBudget(maxRequests = 2, maxSse = 1)
        assertTrue(b.tryAcquireRequest())
        assertTrue(b.tryAcquireRequest())
        assertFalse(b.tryAcquireRequest())
        assertEquals(2, b.requestCount)
        b.releaseRequest()
        assertTrue(b.tryAcquireRequest())
    }

    @Test
    fun sseDoesNotConsumeRequestSlots() {
        val b = ConnectionBudget(maxRequests = 1, maxSse = 8)
        assertTrue(b.tryAcquireRequest())
        assertTrue(b.convertToSse())
        assertEquals(0, b.requestCount)
        assertEquals(1, b.sseCount)
        // SSE가 열려 있어도 요청 슬롯은 비어 있다
        assertTrue(b.tryAcquireRequest())
        b.releaseRequest()
        b.releaseSse()
        assertEquals(0, b.sseCount)
    }

    @Test
    fun sseHasItsOwnCapAndKeepsRequestSlotWhenRefused() {
        val b = ConnectionBudget(maxRequests = 4, maxSse = 1)
        assertTrue(b.tryAcquireRequest())
        assertTrue(b.convertToSse())
        assertTrue(b.tryAcquireRequest())
        // 두 번째 SSE는 거부: 요청 슬롯은 그대로(호출자가 503 뒤 releaseRequest)
        assertFalse(b.convertToSse())
        assertEquals(1, b.requestCount)
        assertEquals(1, b.sseCount)
        b.releaseRequest()
        assertEquals(0, b.requestCount)
    }
}
