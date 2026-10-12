package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessEnhancerPolicyTest {
    private val unset = -1

    @Test
    fun `보정이 필요 없는 채널은 기존 이펙트를 해제하고 없으면 건너뛴다`() {
        assertEquals(
            LoudnessEnhancerPolicy.Action.RELEASE,
            LoudnessEnhancerPolicy.decide(wantsBoost = false, hasEnhancer = true, boundSessionId = 7, currentSessionId = 7, unsetSessionId = unset)
        )
        assertEquals(
            LoudnessEnhancerPolicy.Action.SKIP,
            LoudnessEnhancerPolicy.decide(wantsBoost = false, hasEnhancer = false, boundSessionId = unset, currentSessionId = 7, unsetSessionId = unset)
        )
    }

    @Test
    fun `세션 ID가 아직 없으면 만들지 않는다`() {
        assertEquals(
            LoudnessEnhancerPolicy.Action.SKIP,
            LoudnessEnhancerPolicy.decide(wantsBoost = true, hasEnhancer = false, boundSessionId = unset, currentSessionId = unset, unsetSessionId = unset)
        )
    }

    @Test
    fun `이펙트가 없거나 세션이 바뀌었으면 새로 만든다`() {
        assertEquals(
            LoudnessEnhancerPolicy.Action.RECREATE,
            LoudnessEnhancerPolicy.decide(wantsBoost = true, hasEnhancer = false, boundSessionId = unset, currentSessionId = 7, unsetSessionId = unset)
        )
        assertEquals(
            LoudnessEnhancerPolicy.Action.RECREATE,
            LoudnessEnhancerPolicy.decide(wantsBoost = true, hasEnhancer = true, boundSessionId = 7, currentSessionId = 9, unsetSessionId = unset)
        )
    }

    @Test
    fun `같은 세션의 살아 있는 이펙트는 재사용한다`() {
        assertEquals(
            LoudnessEnhancerPolicy.Action.REUSE,
            LoudnessEnhancerPolicy.decide(wantsBoost = true, hasEnhancer = true, boundSessionId = 7, currentSessionId = 7, unsetSessionId = unset)
        )
    }

    @Test
    fun `예외 로그는 클래스별로 한 번만`() {
        val logged = mutableSetOf<String>()
        assertTrue(LoudnessEnhancerPolicy.shouldLogError(logged, "RuntimeException"))
        assertFalse(LoudnessEnhancerPolicy.shouldLogError(logged, "RuntimeException"))
        assertTrue(LoudnessEnhancerPolicy.shouldLogError(logged, "IllegalStateException"))
    }
}
