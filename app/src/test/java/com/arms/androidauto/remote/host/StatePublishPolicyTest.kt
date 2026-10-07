package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.BluetoothStatus
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteGuest
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
        volumeBucket = 40, guestNames = listOf("Galaxy S22"), revoked = false,
    )

    private fun state(
        battery: Int? = 87,
        updatedAt: Long = 0L,
        items: List<RemoteItem> = emptyList(),
        volume: Int? = 42,
        guests: List<RemoteGuest> = listOf(RemoteGuest("Galaxy S22", 1_000L)),
    ) = HostState(
        mediaId = "1", title = "볼륨을 높여요", artist = "KBS", isPlaying = true, playbackState = 3,
        artworkRef = "station:1", bluetooth = BluetoothStatus(true, "거실 스피커", 5L),
        batteryPercent = battery, updatedAtMs = updatedAt, items = items, volumePercent = volume, guests = guests,
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
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(volumeBucket = 45), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(guestNames = emptyList()), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(revoked = true), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(stPairingOpenUntilMs = 9_000L), 1_000L, 2_000L))
        assertEquals(PublishDecision.Debounced, policy.decide(playing, playing.copy(stClientCount = 1), 1_000L, 2_000L))
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

    @Test
    fun volumeIsBucketedToFivePercent() {
        assertEquals(0, StatePublishPolicy.volumeBucket(3))
        assertEquals(40, StatePublishPolicy.volumeBucket(42))
        assertEquals(40, StatePublishPolicy.volumeBucket(44))
        assertEquals(45, StatePublishPolicy.volumeBucket(45))
        assertEquals(100, StatePublishPolicy.volumeBucket(100))
        assertEquals(100, StatePublishPolicy.volumeBucket(130))
        assertNull(StatePublishPolicy.volumeBucket(null))
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
    fun fingerprintIgnoresSmallVolumeDriftButNotBucketCrossing() {
        val a = StatePublishPolicy.fingerprintOf(state(volume = 40))
        assertEquals(a, StatePublishPolicy.fingerprintOf(state(volume = 44)))
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(volume = 45)))
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(volume = null)))
    }

    @Test
    fun fingerprintTracksGuestNamesButNotLastSeen() {
        val a = StatePublishPolicy.fingerprintOf(state(guests = listOf(RemoteGuest("Galaxy S22", 1_000L))))
        // 60초마다 오는 Hello로 lastSeen만 바뀌면 다시 publish 하지 않는다.
        assertEquals(a, StatePublishPolicy.fingerprintOf(state(guests = listOf(RemoteGuest("Galaxy S22", 61_000L)))))
        // 게스트가 붙거나 떨어지면 publish.
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(guests = emptyList())))
        assertNotEquals(a, StatePublishPolicy.fingerprintOf(state(guests = listOf(RemoteGuest("Galaxy S22", 1L), RemoteGuest("Galaxy S24", 2L)))))
        assertEquals(listOf("Galaxy S22"), a.guestNames)
        assertFalse(a.revoked)
    }

    @Test
    fun fingerprintChangesWhenRevoked() {
        val a = StatePublishPolicy.fingerprintOf(state())
        val revoked = StatePublishPolicy.fingerprintOf(state().copy(revoked = true))
        assertNotEquals(a, revoked)
        assertTrue(revoked.revoked)
    }

    @Test
    fun fingerprintTracksSmartThingsPairingWindowAndClientCount() {
        val closed = StatePublishPolicy.fingerprintOf(state())
        assertNull(closed.stPairingOpenUntilMs)
        assertEquals(0, closed.stClientCount)
        // 창이 열리면(마감 시각이 생기면) publish, 같은 마감이면 다시 publish 하지 않는다.
        val open = StatePublishPolicy.fingerprintOf(state().copy(stPairingOpenUntilMs = 600_000L))
        assertNotEquals(closed, open)
        assertEquals(open, StatePublishPolicy.fingerprintOf(state(updatedAt = 5_000L).copy(stPairingOpenUntilMs = 600_000L)))
        // 다시 열어 마감이 바뀌면 publish. 클라이언트가 붙거나 떨어져도 publish.
        assertNotEquals(open, StatePublishPolicy.fingerprintOf(state().copy(stPairingOpenUntilMs = 700_000L)))
        assertNotEquals(closed, StatePublishPolicy.fingerprintOf(state().copy(stClientCount = 1)))
        assertEquals(1, StatePublishPolicy.fingerprintOf(state().copy(stClientCount = 1)).stClientCount)
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
