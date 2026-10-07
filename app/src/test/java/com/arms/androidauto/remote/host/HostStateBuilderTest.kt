package com.arms.androidauto.remote.host

import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.core.remote.BluetoothStatus
import com.arms.androidauto.core.remote.RemoteGuest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStateBuilderTest {

    private val stations = listOf(
        Station(id = "1", name = "KBS Cool FM", frequencyOrUrl = "89.1", type = StationType.RADIO),
        Station(id = "4", name = "K-POP 발라드", frequencyOrUrl = "https://cdn.example/ballad.m3u8", type = StationType.STREAMING),
        Station(id = "7", name = "웹 라디오", frequencyOrUrl = "http://stream.example/live", type = StationType.RADIO),
    )
    private val nasAlbumId = MediaIdScheme.encodeAlbum(NasAlbum(name = "Lilac", albumArtist = "IU", songCount = 10))

    private fun radio(id: String = "1", artworkUri: String? = null) = SessionSnapshot(
        mediaId = id, title = "볼륨을 높여요", artist = "KBS", albumTitle = null,
        isPlaying = true, playbackState = 3, artworkUri = artworkUri,
    )

    // ---- artworkRef ----

    @Test
    fun radioWithoutPublicCoverUsesStationRef() {
        assertEquals("station:1", HostStateBuilder.artworkRef("1", null))
        assertEquals("station:1", HostStateBuilder.artworkRef("1", "content://com.arms.androidauto.artworkprovider/artwork/a-1.jpg"))
        assertEquals("station:4", HostStateBuilder.artworkRef("4", "android.resource://com.arms.androidauto/123"))
    }

    @Test
    fun publicHttpsCoverIsPassedThrough() {
        assertEquals("https://cdn.deezer.com/cover.jpg", HostStateBuilder.artworkRef("1", "https://cdn.deezer.com/cover.jpg"))
        assertEquals("http://img.example/a.png", HostStateBuilder.artworkRef("1", "http://img.example/a.png"))
        // mediaId가 없어도 공개 URL이면 그대로.
        assertEquals("https://cdn.example/x.jpg", HostStateBuilder.artworkRef(null, "https://cdn.example/x.jpg"))
    }

    @Test
    fun nasItemsNeverExposeArtwork() {
        assertNull(HostStateBuilder.artworkRef(nasAlbumId, "https://nas.example:5001/webapi/AudioStation/cover.cgi?_sid=abc"))
        assertNull(HostStateBuilder.artworkRef(nasAlbumId, "https://cdn.example/public.jpg"))
        assertNull(HostStateBuilder.artworkRef(nasAlbumId, null))
    }

    @Test
    fun urlsCarryingSessionIdAreDropped() {
        assertNull(HostStateBuilder.artworkRef(null, "https://nas.example/cover.cgi?_sid=abc"))
        assertNull(HostStateBuilder.artworkRef(null, "https://nas.example/cover.cgi?sid=abc"))
    }

    @Test
    fun nothingKnownGivesNull() {
        assertNull(HostStateBuilder.artworkRef(null, null))
        assertNull(HostStateBuilder.artworkRef("", "content://x/y"))
        assertNull(HostStateBuilder.artworkRef(null, "file:///data/x.jpg"))
    }

    // ---- items ----

    @Test
    fun itemsListAllStationsWithSafeSubtitles() {
        val items = HostStateBuilder.items(stations, radio())
        assertEquals(listOf("1", "4", "7"), items.map { it.mediaId })
        assertEquals("89.1", items[0].subtitle)
        // 스트림 URL은 밖으로 나가지 않는다.
        assertEquals("스트리밍", items[1].subtitle)
        assertEquals("라디오", items[2].subtitle)
        assertTrue(items.none { it.subtitle.contains("http") })
    }

    @Test
    fun currentNasItemIsAppendedOnlyWhilePlayingNas() {
        val nas = SessionSnapshot(
            mediaId = nasAlbumId, title = "Coin", artist = "IU", albumTitle = "Lilac",
            isPlaying = true, playbackState = 3, artworkUri = "content://x",
        )
        val items = HostStateBuilder.items(stations, nas)
        assertEquals(4, items.size)
        assertEquals(nasAlbumId, items.last().mediaId)
        assertEquals("Lilac", items.last().name)
        assertEquals("IU", items.last().subtitle)

        assertEquals(3, HostStateBuilder.items(stations, radio()).size)
        assertEquals(3, HostStateBuilder.items(stations, null).size)
    }

    @Test
    fun nasItemFallsBackToTitleWhenNoAlbum() {
        val nas = SessionSnapshot(nasAlbumId, "Coin", null, null, false, 1, null)
        val last = HostStateBuilder.items(emptyList(), nas).single()
        assertEquals("Coin", last.name)
        assertEquals("NAS", last.subtitle)
    }

    @Test
    fun selectIsOnlyAllowedForPublishedIds() {
        val items = HostStateBuilder.items(stations, radio())
        assertTrue(HostStateBuilder.isKnownMediaId("4", items))
        assertFalse(HostStateBuilder.isKnownMediaId("99", items))
        assertFalse(HostStateBuilder.isKnownMediaId(nasAlbumId, items))
    }

    // ---- build ----

    @Test
    fun buildCopiesSnapshotAndEnvironment() {
        val bt = BluetoothStatus(connected = true, deviceName = "거실 스피커", changedAtMs = 10L)
        val state = HostStateBuilder.build(radio(artworkUri = "content://x"), stations, bt, 87, nowMs = 12345L)
        assertEquals("1", state.mediaId)
        assertEquals("볼륨을 높여요", state.title)
        assertEquals("KBS", state.artist)
        assertTrue(state.isPlaying)
        assertEquals(3, state.playbackState)
        assertEquals("station:1", state.artworkRef)
        assertEquals(bt, state.bluetooth)
        assertEquals(87, state.batteryPercent)
        assertEquals(12345L, state.updatedAtMs)
        assertEquals(3, state.items.size)
        // 기본값: 볼륨 모름, 게스트 없음, 연결 종료 아님.
        assertNull(state.volumePercent)
        assertTrue(state.guests.isEmpty())
        assertFalse(state.revoked)
        // 기본값: 스마트싱스 연결 허용 창 닫힘, 연결된 클라이언트 0대.
        assertNull(state.stPairingOpenUntilMs)
        assertEquals(0, state.stClientCount)
    }

    @Test
    fun buildCarriesSmartThingsPairingWindowAndClientCount() {
        val state = HostStateBuilder.build(radio(), stations, null, null, 1L, stPairingOpenUntilMs = 1_700_000_600_000L, stClientCount = 2)
        assertEquals(1_700_000_600_000L, state.stPairingOpenUntilMs)
        assertEquals(2, state.stClientCount)
        // 창이 닫혀 있어도 클라이언트 수는 그대로, 음수 카운트는 0으로.
        val closed = HostStateBuilder.build(radio(), stations, null, null, 1L, stPairingOpenUntilMs = null, stClientCount = -1)
        assertNull(closed.stPairingOpenUntilMs)
        assertEquals(0, closed.stClientCount)
    }

    @Test
    fun buildCarriesVolumeGuestsAndRevoked() {
        val guests = listOf(RemoteGuest("Galaxy S22", 100L), RemoteGuest("Galaxy S24", 200L))
        val state = HostStateBuilder.build(radio(), stations, null, 50, 1L, volumePercent = 42, guests = guests, revoked = true)
        assertEquals(42, state.volumePercent)
        assertEquals(guests, state.guests)
        assertTrue(state.revoked)
        // 볼륨도 배터리처럼 0..100으로 자른다.
        assertEquals(100, HostStateBuilder.build(radio(), stations, null, null, 1L, volumePercent = 250).volumePercent)
        assertEquals(0, HostStateBuilder.build(radio(), stations, null, null, 1L, volumePercent = -3).volumePercent)
    }

    @Test
    fun buildClampsBatteryAndAllowsUnknown() {
        assertEquals(100, HostStateBuilder.build(SessionSnapshot.EMPTY, emptyList(), null, 140, 0L).batteryPercent)
        assertNull(HostStateBuilder.build(SessionSnapshot.EMPTY, emptyList(), null, null, 0L).batteryPercent)
    }

    @Test
    fun emptySnapshotIsIdle() {
        val state = HostStateBuilder.build(SessionSnapshot.EMPTY, emptyList(), null, null, 0L)
        assertNull(state.mediaId)
        assertFalse(state.isPlaying)
        assertEquals(1, state.playbackState)
        assertNull(state.artworkRef)
        assertTrue(state.items.isEmpty())
    }
}
