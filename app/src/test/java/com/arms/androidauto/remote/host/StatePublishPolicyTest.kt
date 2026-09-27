package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.BluetoothStatus
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatePublishPolicyTest {

    private val policy = StatePublishPolicy(debounceMs = 500L, heartbeatMs = 300_000L)

    private val playing = StateFingerprint(
        mediaId = "1", title = "볼륨을 높여요", artist = "KBS", isPlaying = true, playbackState = 3,
        artworkRef = "station:1", btConnected = true, btDeviceName = "거실 스피커", batteryBucket = 85, itemsKey = 7,
    )

    private fun state(battery: Int? = 87, updatedAt: Long = 0L, items: List<RemoteItem> = emptyList()) = HostState(
        mediaId = "1", title = "볼륨을 높여요", artist = "KBS", isPlaying = true, playbackState = 3,
        artworkRef = "station:1", bluetooth = BluetoothStatus(true, "거실 스피커", 5L),
        batteryPercent = battery, updatedAtMs = updatedAt, items = items,
    )

    // ---- decide ----

    @Test
    fun firstStateIsPublishedImmediately() {
        assertEquals(PublishDecision.Now, policy.decide(null, playing, null, nowMs = 1_000L))
    }

    @Test
    fun unchangedStateIsSkipped() {
        assertEquals(PublishDecision.Skip, policy.decide(playing, playing.copy(), lastPublishedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun changedStateIsDebounced() {
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(isPlaying = false), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(mediaId = "2"), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(btConnected = false), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(batteryBucket = 80), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(itemsKey = 8), 1_000L, 2_000L))
    }

    @Test
    fun forcedRefreshPublishesNowEvenIfUnchanged() {
        assertEquals(PublishDecision.Now, policy.decide(playing, playing.copy(), 1_000L, 2_000L, forced = true))
    }

    @Test
    fun heartbeatPublishesUnchangedStateAfterInterval() {
        assertEquals(PublishDecision.Skip, policy.decide(playing, playing.copy(), 1_000L, 1_000L + 299_999L))
        assertEquals(PublishDecision.Now, policy.decide(playing, playing.copy(), 1_000L, 1_000L + 300_000L))
    }

    @Test
    fun heartbeatDelayCountsDownFromLastPublish() {
        assertEquals(0L, policy.heartbeatDelayMs(null, 5_000L))
        assertEquals(300_000L, policy.heartbeatDelayMs(1_000L, 1_000L))
        assertEquals(100_000L, policy.heartbeatDelayMs(1_000L, 201_000L))
        assertEquals(0L, policy.heartbeatDelayMs(1_000L, 900_000L))
        assertTrue(policy.isHeartbeatDue(null, 0L))
        assertFalse(policy.isHeartbeatDue(1_000L, 2_000L))
    }

    @Test
    fun defaultsMatchPlan() {
        val p = StatePublishPolicy()
        assertEquals(500L, p.debounceMs)
        assertEquals(5 * 60_000L, p.heartbeatMs)
    }

    // ---- battery bucket ----

    @Test
    fun batteryIsBucketedToFivePercent() {
        assertEquals(0, StatePublishPolicy.batteryBucket(0))
        assertEquals(0, StatePublishPolicy.batteryBucket(4))
        assertEquals(5, StatePublishPolicy.batteryBucket(5))
        assertEquals(85, StatePublishPolicy.batteryBucket(87))
        assertEquals(95, StatePublishPolicy.batteryBucket(99))
        assertEquals(100, StatePublishPolicy.batteryBucket(100))
        assertEquals(100, StatePublishPolicy.batteryBucket(150))
        assertNull(StatePublishPolicy.batteryBucket(null))
    }

    // ---- fingerprintOf ----

    @Test
    fun fingerprintIgnoresTimestampAndSmallBatteryDrift() {
        val a = StatePublishPolicy.fingerprintOf(state(battery = 87, updatedAt = 1L))
        val b = StatePublishPolicy.fingerprintOf(state(battery = 86, updatedAt = 99_999L))
        assertEquals(a, b)
    }

    @Test
    fun fingerprintChangesWhenBatteryCrossesBucketOrItemsChange() {
        val a = StatePublishPolicy.fingerprintOf(state(battery = 85))
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(battery = 84)))
        val items = listOf(RemoteItem("1", "KBS Cool FM", "89.1"))
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(battery = 85, items = items)))
    }

    @Test
    fun fingerprintCarriesBluetoothAndArtwork() {
        val fp = StatePublishPolicy.fingerprintOf(state())
        assertTrue(fp.btConnected)
        assertEquals("거실 스피커", fp.btDeviceName)
        assertEquals("station:1", fp.artworkRef)
        val noBt = StatePublishPolicy.fingerprintOf(state().copy(bluetooth = null))
        assertFalse(noBt.btConnected)
        assertNull(noBt.btDeviceName)
    }
}
