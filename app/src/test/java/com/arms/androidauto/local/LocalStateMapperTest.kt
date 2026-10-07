package com.arms.androidauto.local

import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.local.LocalState.Playback
import com.arms.androidauto.local.LocalState.Power
import com.arms.androidauto.remote.host.SessionSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStateMapperTest {

    private val IDLE = 1
    private val BUFFERING = 2
    private val READY = 3
    private val ENDED = 4

    private val stations = listOf(
        Station("1", "KBS Cool FM", "89.1", StationType.RADIO),
        Station("2", "SBS 파워FM", "107.7", StationType.RADIO),
        Station("9", "아트 없는 채널", "https://x/y.m3u8", StationType.STREAMING),
    )
    private val artIds = setOf("1", "2", "3", "4", "5")
    private val hasArt: (String) -> Boolean = { it in artIds }
    private val base = "http://192.168.0.12:8765"

    private fun pb(isPlaying: Boolean, state: Int, pwr: Boolean, err: Boolean = false) =
        LocalStateMapper.playback(isPlaying, state, pwr, err)

    @Test
    fun playbackMapping() {
        assertEquals(Power.ON to Playback.PLAYING, pb(true, READY, true))
        assertEquals(Power.ON to Playback.BUFFERING, pb(false, BUFFERING, true))
        // 재생 복구 대기(IDLE + 오류 + 재생 의도)
        assertEquals(Power.ON to Playback.BUFFERING, pb(false, IDLE, true, err = true))
        // 포커스를 잠시 잃음: 의도는 살아 있다
        assertEquals(Power.ON to Playback.PAUSED, pb(false, READY, true))
        assertEquals(Power.OFF to Playback.PAUSED, pb(false, READY, false))
        assertEquals(Power.OFF to Playback.PAUSED, pb(false, BUFFERING, false))
        // stop()은 playWhenReady를 바꾸지 않는다 → IDLE이면 오류가 없는 한 정지
        assertEquals(Power.OFF to Playback.STOPPED, pb(false, IDLE, true))
        assertEquals(Power.OFF to Playback.STOPPED, pb(false, IDLE, false))
        assertEquals(Power.OFF to Playback.STOPPED, pb(false, ENDED, true))
        assertEquals(Power.OFF to Playback.STOPPED, pb(false, IDLE, false, err = true))
    }

    @Test
    fun albumArtRules() {
        assertEquals(ArtRef.Station("1"), LocalStateMapper.albumArt("1", null, hasArt))
        assertEquals(ArtRef.Station("1"), LocalStateMapper.albumArt("1", "content://p/a.jpg", hasArt))
        assertEquals(ArtRef.Public("https://cdn.example/c.jpg"), LocalStateMapper.albumArt("1", "https://cdn.example/c.jpg", hasArt))
        assertEquals(ArtRef.None, LocalStateMapper.albumArt("9", null, hasArt))
        assertEquals(ArtRef.None, LocalStateMapper.albumArt(null, null, hasArt))
        val nas = MediaIdScheme.encodeAlbum(NasAlbum(name = "Lilac", albumArtist = "IU", songCount = 10))
        assertEquals(ArtRef.None, LocalStateMapper.albumArt(nas, "https://nas.example/cover?_sid=abc", hasArt))
        assertEquals(ArtRef.None, LocalStateMapper.albumArt(nas, "https://public.example/c.jpg", hasArt))
    }

    private fun snapshot(mediaId: String? = "1", isPlaying: Boolean = true, state: Int = READY, artwork: String? = null) =
        SessionSnapshot(
            mediaId = mediaId, title = "KBS Cool FM - 볼륨을 높여요", artist = "볼륨을 높여요", albumTitle = null,
            isPlaying = isPlaying, playbackState = state, artworkUri = artwork, playWhenReady = isPlaying, hasError = false,
        )

    @Test
    fun stateJsonMatchesContract() {
        val s = LocalStateMapper.map(snapshot(), stations, 40, muted = false, hasStationArt = hasArt, nowMs = 1_791_358_000_000L)
        val j = JSONObject(LocalJson.state(s, base).toString())
        assertEquals("on", j.getString("power"))
        assertEquals("playing", j.getString("playback"))
        assertEquals("1", j.getString("mediaId"))
        assertEquals("KBS Cool FM - 볼륨을 높여요", j.getString("title"))
        assertEquals("볼륨을 높여요", j.getString("artist"))
        assertEquals("$base/art/station/1.png", j.getString("albumArtUrl"))
        assertEquals(40, j.getInt("volume"))
        assertFalse(j.getBoolean("muted"))
        assertEquals(1_791_358_000_000L, j.getLong("updatedAtMs"))
        val presets = j.getJSONArray("presets")
        assertEquals(3, presets.length())
        assertEquals("1", presets.getJSONObject(0).getString("id"))
        assertEquals("KBS Cool FM", presets.getJSONObject(0).getString("name"))
        assertEquals("$base/art/station/1.png", presets.getJSONObject(0).getString("imageUrl"))
        assertTrue(presets.getJSONObject(2).isNull("imageUrl"))
    }

    @Test
    fun stoppedNasStateHasNullArtAndKeepsKeys() {
        val nas = MediaIdScheme.encodeAlbum(NasAlbum(name = "Lilac", albumArtist = "IU", songCount = 10))
        val s = LocalStateMapper.map(snapshot(mediaId = nas, isPlaying = false, state = IDLE), emptyList(), null, true, hasArt, 5L)
        val j = JSONObject(LocalJson.state(s, base).toString())
        assertEquals("off", j.getString("power"))
        assertEquals("stopped", j.getString("playback"))
        assertTrue(j.has("albumArtUrl"))
        assertTrue(j.isNull("albumArtUrl"))
        assertEquals(0, j.getInt("volume"))
        assertTrue(j.getBoolean("muted"))
    }

    @Test
    fun emptySnapshotIsOffAndNullFields() {
        val s = LocalStateMapper.map(SessionSnapshot.EMPTY, stations, 55, false, hasArt, 1L)
        val j = JSONObject(LocalJson.state(s, null).toString())
        assertEquals("off", j.getString("power"))
        assertEquals("stopped", j.getString("playback"))
        assertTrue(j.isNull("mediaId"))
        assertTrue(j.isNull("title"))
        assertTrue(j.isNull("albumArtUrl"))
        // 주소를 모르면 프리셋 이미지도 null
        assertTrue(j.getJSONArray("presets").getJSONObject(0).isNull("imageUrl"))
    }

    @Test
    fun publicCoverWinsOverStationArt() {
        val s = LocalStateMapper.map(snapshot(artwork = "https://cdn.example/c.jpg"), stations, 10, false, hasArt, 1L)
        assertEquals("https://cdn.example/c.jpg", LocalJson.state(s, base).getString("albumArtUrl"))
    }

    @Test
    fun sameContentIgnoresTimestampOnly() {
        val a = LocalStateMapper.map(snapshot(), stations, 40, false, hasArt, 1L)
        assertTrue(a.sameContentAs(a.copy(updatedAtMs = 2L)))
        assertFalse(a.sameContentAs(a.copy(volume = 41)))
        assertFalse(a.sameContentAs(null))
    }

    @Test
    fun infoJson() {
        val j = LocalJson.info(DeviceInfo("uuid-1", "Simple Radio SM-X200", "SM-X200", "0.8.2"), pairingOpen = true)
        assertEquals("uuid-1", j.getString("id"))
        assertEquals("Simple Radio SM-X200", j.getString("name"))
        assertEquals("SM-X200", j.getString("model"))
        assertEquals("0.8.2", j.getString("appVersion"))
        assertEquals(1, j.getInt("apiVersion"))
        assertTrue(j.getBoolean("pairingOpen"))
    }
}
