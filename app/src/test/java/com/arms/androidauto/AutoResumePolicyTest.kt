package com.arms.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumePolicyTest {
    @Test
    fun playingOrBufferingIsIntended() {
        assertTrue(AutoResumePolicy.isPlaybackIntended(playWhenReady = true, hasItem = true, isIdle = false, hasError = false))
    }

    @Test
    fun errorWaitingForRecoveryIsStillIntended() {
        assertTrue(AutoResumePolicy.isPlaybackIntended(playWhenReady = true, hasItem = true, isIdle = true, hasError = true))
    }

    @Test
    fun userStopPauseOrEmptyQueueIsNotIntended() {
        assertFalse(AutoResumePolicy.isPlaybackIntended(playWhenReady = true, hasItem = true, isIdle = true, hasError = false))
        assertFalse(AutoResumePolicy.isPlaybackIntended(playWhenReady = false, hasItem = true, isIdle = false, hasError = false))
        assertFalse(AutoResumePolicy.isPlaybackIntended(playWhenReady = true, hasItem = false, isIdle = true, hasError = false))
    }

    @Test
    fun onlyHomePlayerAutoResumes() {
        assertTrue(AutoResumePolicy.shouldResumePlayback(isHomePlayer = true, wasPlaying = true))
        assertFalse(AutoResumePolicy.shouldResumePlayback(isHomePlayer = true, wasPlaying = false))
        assertFalse(AutoResumePolicy.shouldResumePlayback(isHomePlayer = false, wasPlaying = true))
    }
}
