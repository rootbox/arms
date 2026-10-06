package com.arms.androidauto.remote.host

import org.junit.Assert.assertEquals
import org.junit.Test

class StartRetryPolicyTest {

    @Test
    fun backoffDoublesFromFiveSecondsAndCapsAtSixty() {
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            (1..7).map { StartRetryPolicy.nextDelayMs(it) },
        )
    }

    @Test
    fun largeAndNonPositiveFailureCountsAreSafe() {
        assertEquals(60_000L, StartRetryPolicy.nextDelayMs(1_000))
        assertEquals(60_000L, StartRetryPolicy.nextDelayMs(Int.MAX_VALUE))
        assertEquals(5_000L, StartRetryPolicy.nextDelayMs(0))
        assertEquals(5_000L, StartRetryPolicy.nextDelayMs(-3))
    }

    @Test
    fun earlyRetryStillKeepsFiveSecondGap() {
        assertEquals(5_000L, StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs = 10_000L, nowMs = 10_000L))
        assertEquals(3_000L, StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs = 10_000L, nowMs = 12_000L))
        assertEquals(0L, StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs = 10_000L, nowMs = 15_000L))
        assertEquals(0L, StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs = 10_000L, nowMs = 99_000L))
        // 시계가 뒤로 가도 5s를 넘겨 기다리지 않는다.
        assertEquals(5_000L, StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs = 10_000L, nowMs = 1_000L))
    }
}
