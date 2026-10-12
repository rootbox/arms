package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 덕킹 고착(볼륨이 작게 들림) 복구 규칙 고정. docs/ISSUES_2026-10-12.md §2.
class DuckingRecoveryPolicyTest {

    @Test
    fun `재생 중이고 포커스 상실이 아닌데 볼륨이 1 미만이면 복원한다`() {
        assertEquals(
            DuckingRecoveryPolicy.Action.RESTORE_VOLUME,
            DuckingRecoveryPolicy.decide(isPlaying = true, inFocusLoss = false, volume = 0.2f)
        )
    }

    @Test
    fun `볼륨이 이미 1이면 아무것도 하지 않는다`() {
        assertEquals(
            DuckingRecoveryPolicy.Action.NONE,
            DuckingRecoveryPolicy.decide(isPlaying = true, inFocusLoss = false, volume = 1f)
        )
    }

    // 내비 안내 중에 볼륨을 올리면 안내 위로 라디오가 덮인다.
    @Test
    fun `포커스를 뺏긴 상태에서는 볼륨을 건드리지 않는다`() {
        assertEquals(
            DuckingRecoveryPolicy.Action.NONE,
            DuckingRecoveryPolicy.decide(isPlaying = true, inFocusLoss = true, volume = 0.2f)
        )
    }

    @Test
    fun `재생 중이 아니면 볼륨을 건드리지 않는다`() {
        assertEquals(
            DuckingRecoveryPolicy.Action.NONE,
            DuckingRecoveryPolicy.decide(isPlaying = false, inFocusLoss = false, volume = 0.2f)
        )
    }

    @Test
    fun `포커스 상실은 영구 상실 대기 또는 일시 억제 중 하나면 참`() {
        assertTrue(DuckingRecoveryPolicy.isInFocusLoss(pausedByFocusLoss = true, suppressedByFocus = false))
        assertTrue(DuckingRecoveryPolicy.isInFocusLoss(pausedByFocusLoss = false, suppressedByFocus = true))
        assertFalse(DuckingRecoveryPolicy.isInFocusLoss(pausedByFocusLoss = false, suppressedByFocus = false))
    }

    @Test
    fun `라디오 재시작은 IDLE이 아니면 같은 채널이라도 stop을 먼저 한다`() {
        assertTrue(
            DuckingRecoveryPolicy.shouldStopBeforeRestart(
                isIdle = false, targetIsNas = false, targetMediaId = "2", currentMediaId = "2"
            )
        )
        assertTrue(
            DuckingRecoveryPolicy.shouldStopBeforeRestart(
                isIdle = false, targetIsNas = false, targetMediaId = "3", currentMediaId = "2"
            )
        )
    }

    // 정지·오류 뒤(IDLE)는 ExoPlayer가 이미 포커스를 놓았다.
    @Test
    fun `이미 IDLE이면 stop을 하지 않는다`() {
        assertFalse(
            DuckingRecoveryPolicy.shouldStopBeforeRestart(
                isIdle = true, targetIsNas = false, targetMediaId = "3", currentMediaId = "2"
            )
        )
    }

    @Test
    fun `NAS는 다른 항목으로 바뀔 때만 stop을 하고 같은 큐 재요청은 그대로 둔다`() {
        assertTrue(
            DuckingRecoveryPolicy.shouldStopBeforeRestart(
                isIdle = false, targetIsNas = true, targetMediaId = "nas:album:b", currentMediaId = "nas:album:a"
            )
        )
        assertFalse(
            DuckingRecoveryPolicy.shouldStopBeforeRestart(
                isIdle = false, targetIsNas = true, targetMediaId = "nas:album:a", currentMediaId = "nas:album:a"
            )
        )
    }
}
