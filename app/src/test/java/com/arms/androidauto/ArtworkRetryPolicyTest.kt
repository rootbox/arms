package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 커버 로드가 한 번 실패하면 편성이 바뀔 때까지 회색으로 남던 버그의 재발 방지.
// 재시도 간격이 "너무 자주"도 "영영 안 함"도 아니게 유지되는지 고정한다.
class ArtworkRetryPolicyTest {

    @Test
    fun `첫 재시도는 갱신 주기 한 번 뒤`() {
        assertEquals(8_000L, ArtworkRetryPolicy.delayMs(0))
    }

    @Test
    fun `실패가 이어지면 간격이 두 배씩 늘어난다`() {
        assertEquals(16_000L, ArtworkRetryPolicy.delayMs(1))
        assertEquals(32_000L, ArtworkRetryPolicy.delayMs(2))
        assertEquals(64_000L, ArtworkRetryPolicy.delayMs(3))
    }

    @Test
    fun `간격은 2분을 넘지 않는다 - 포기하지 않되 두드리지도 않는다`() {
        assertEquals(120_000L, ArtworkRetryPolicy.delayMs(4))
        assertEquals(120_000L, ArtworkRetryPolicy.delayMs(10))
        assertEquals(120_000L, ArtworkRetryPolicy.delayMs(1_000))
    }

    @Test
    fun `음수 시도 횟수도 안전하게 처리한다`() {
        assertTrue(ArtworkRetryPolicy.delayMs(-5) >= 8_000L)
    }
}
