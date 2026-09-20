package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    private val hour = UpdateCheckPolicy.MIN_INTERVAL_MS

    @Test fun `첫 시도(기록 없음)는 확인한다`() {
        assertTrue(UpdateCheckPolicy.shouldCheck(nowMs = 1_000L, lastAttemptMs = null))
    }

    @Test fun `1시간 안에는 건너뛴다`() {
        assertFalse(UpdateCheckPolicy.shouldCheck(nowMs = hour - 1, lastAttemptMs = 0L))
    }

    @Test fun `1시간이 지나면 다시 확인한다`() {
        assertTrue(UpdateCheckPolicy.shouldCheck(nowMs = hour, lastAttemptMs = 0L))
    }

    @Test fun `403에 한도 0이면 한도 초과로 분류`() {
        assertEquals("GitHub 확인 한도 초과 (잠시 후 자동 재시도)", UpdateCheckPolicy.describeFailure(403, "0"))
    }

    @Test fun `한도가 남은 403은 일반 서버 응답으로`() {
        assertEquals("서버 응답 403", UpdateCheckPolicy.describeFailure(403, "12"))
    }

    @Test fun `404는 릴리즈 없음`() {
        assertEquals("릴리즈 정보를 찾을 수 없음", UpdateCheckPolicy.describeFailure(404, null))
    }
}
