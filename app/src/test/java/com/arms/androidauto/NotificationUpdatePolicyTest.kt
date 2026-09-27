package com.arms.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUpdatePolicyTest {
    private val playing = NotificationUpdatePolicy.Fingerprint(
        playWhenReady = true, playbackState = 3, isPlaying = true, mediaId = "1",
        title = "KBS Cool FM - 폴킴의 가요광장", artist = "폴킴의 가요광장", artworkKey = "content://a/1.jpg",
        startInForegroundRequired = true,
    )

    @Test
    fun firstPostAlwaysGoesThrough() {
        assertTrue(NotificationUpdatePolicy.shouldPost(null, playing))
    }

    @Test
    fun identicalFingerprintIsSuppressed() {
        // HLS 타임라인 갱신처럼 보이는 값이 그대로면 게시하지 않는다.
        assertFalse(NotificationUpdatePolicy.shouldPost(playing, playing.copy()))
    }

    @Test
    fun visibleChangesPost() {
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(isPlaying = false, playWhenReady = false)))
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(playbackState = 2)))
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(mediaId = "2", title = "SBS 파워FM - 두시탈출")))
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(title = "KBS Cool FM - 볼륨을 높여요")))
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(artworkKey = "content://a/2.jpg")))
    }

    @Test
    fun foregroundRequirementChangeAlwaysPosts() {
        assertTrue(NotificationUpdatePolicy.shouldPost(playing, playing.copy(startInForegroundRequired = false)))
    }
}
