package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun backoffNeverGivesUp() {
        val delays = (1..10).map { PlaybackRecoveryPolicy.nextDelayMs(it) }
        assertEquals(listOf(3_000L, 3_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 600_000L, 600_000L, 600_000L), delays)
    }
}
