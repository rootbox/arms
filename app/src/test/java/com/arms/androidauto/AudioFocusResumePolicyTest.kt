package com.arms.androidauto

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 내비 음성인식이 끝난 뒤 라디오가 다시 시작되지 않던 버그의 재발 방지.
// "되살려야 하는 정지"와 "그대로 둬야 하는 정지"를 구분하는 규칙을 고정한다.
class AudioFocusResumePolicyTest {

    @Test
    fun `오디오 포커스를 뺏겨 멈추면 다시 살린다`() {
        assertTrue(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            )
        )
    }

    @Test
    fun `포커스 억제가 너무 오래돼 멈춘 경우도 다시 살린다`() {
        assertTrue(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG
            )
        )
    }

    @Test
    fun `사용자가 직접 멈춘 것은 되살리지 않는다`() {
        assertFalse(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
        )
        assertFalse(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
            )
        )
    }

    // 이어폰/블루투스가 빠졌을 때 다시 재생하면 스피커로 소리가 튀어나온다.
    @Test
    fun `출력 장치가 빠져서 멈춘 것은 되살리지 않는다`() {
        assertFalse(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            )
        )
    }

    @Test
    fun `재생이 끝나서 멈춘 것은 되살리지 않는다`() {
        assertFalse(
            AudioFocusResumePolicy.shouldAutoResume(
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            )
        )
    }
}
