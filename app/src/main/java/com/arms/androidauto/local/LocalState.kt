package com.arms.androidauto.local

import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.remote.host.HostStateBuilder
import com.arms.androidauto.remote.host.SessionSnapshot
import org.json.JSONArray
import org.json.JSONObject

// 로컬 제어 API v1(smartthings/LAN_API.md)의 상태. 태블릿 IP가 들어가는 URL은 여기 넣지 않고
// JSON으로 바꿀 때(LocalJson.state) 붙인다 — 같은 상태를 여러 연결에 그대로 보낼 수 있게.
data class LocalState(
    val power: Power,
    val playback: Playback,
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val albumArt: ArtRef,
    val volume: Int,
    val muted: Boolean,
    val presets: List<LocalPreset>,
    val updatedAtMs: Long,
) {
    enum class Power(val wire: String) { ON("on"), OFF("off") }
    enum class Playback(val wire: String) { PLAYING("playing"), PAUSED("paused"), STOPPED("stopped"), BUFFERING("buffering") }

    // updatedAtMs만 다른 상태는 "바뀌지 않음"(SSE로 다시 보내지 않는다).
    fun sameContentAs(other: LocalState?): Boolean = other != null && copy(updatedAtMs = 0L) == other.copy(updatedAtMs = 0L)

    companion object {
        val EMPTY = LocalState(
            power = Power.OFF, playback = Playback.STOPPED, mediaId = null, title = null, artist = null,
            albumArt = ArtRef.None, volume = 0, muted = false, presets = emptyList(), updatedAtMs = 0L,
        )
    }
}

// 커버: 공개 http(s) URL, 번들 채널 아트(LAN URL로 바뀜), 또는 없음(NAS·알 수 없음).
sealed class ArtRef {
    data class Public(val url: String) : ArtRef()
    data class Station(val stationId: String) : ArtRef()
    data object None : ArtRef()
}

data class LocalPreset(val id: String, val name: String, val hasArt: Boolean)

// 세션 스냅샷(MQTT HostState와 같은 소스) → LocalState. Android 의존 없음.
object LocalStateMapper {

    // Media3 Player.STATE_* (의존을 피하려고 숫자 그대로)
    private const val STATE_IDLE = 1
    private const val STATE_BUFFERING = 2
    private const val STATE_READY = 3

    // power: on = 재생 의도 있음(재생·버퍼링·오류 복구 대기), off = 그 외(정지·일시정지).
    // playback: 재생 중 → playing, 의도 있는 버퍼링/오류 복구 대기 → buffering, IDLE·ENDED → stopped, 나머지 → paused.
    fun playback(isPlaying: Boolean, playbackState: Int, playWhenReady: Boolean, hasError: Boolean): Pair<LocalState.Power, LocalState.Playback> {
        return when {
            isPlaying -> LocalState.Power.ON to LocalState.Playback.PLAYING
            playWhenReady && playbackState == STATE_BUFFERING -> LocalState.Power.ON to LocalState.Playback.BUFFERING
            // 서비스의 재생 복구가 다시 붙기를 기다리는 동안(IDLE + 오류 + 재생 의도)
            playWhenReady && hasError && playbackState == STATE_IDLE -> LocalState.Power.ON to LocalState.Playback.BUFFERING
            // 오디오 포커스를 잠시 잃어 소리만 멈춘 상태: 의도는 살아 있다.
            playWhenReady && playbackState == STATE_READY -> LocalState.Power.ON to LocalState.Playback.PAUSED
            playbackState == STATE_READY || playbackState == STATE_BUFFERING -> LocalState.Power.OFF to LocalState.Playback.PAUSED
            else -> LocalState.Power.OFF to LocalState.Playback.STOPPED
        }
    }

    // HostStateBuilder.artworkRef와 같은 규칙(NAS → 없음, 공개 URL 우선, 라디오 → 채널 아트).
    fun albumArt(mediaId: String?, artworkUri: String?, hasStationArt: (String) -> Boolean): ArtRef {
        val ref = HostStateBuilder.artworkRef(mediaId, artworkUri) ?: return ArtRef.None
        if (ref.startsWith(HostStateBuilder.STATION_ART_PREFIX)) {
            val id = ref.removePrefix(HostStateBuilder.STATION_ART_PREFIX)
            return if (hasStationArt(id)) ArtRef.Station(id) else ArtRef.None
        }
        return ArtRef.Public(ref)
    }

    fun map(
        snapshot: SessionSnapshot,
        stations: List<Station>,
        volumePercent: Int?,
        muted: Boolean,
        hasStationArt: (String) -> Boolean,
        nowMs: Long,
    ): LocalState {
        val (power, playback) = playback(snapshot.isPlaying, snapshot.playbackState, snapshot.playWhenReady, snapshot.hasError)
        return LocalState(
            power = power,
            playback = playback,
            mediaId = snapshot.mediaId,
            title = snapshot.title,
            artist = snapshot.artist,
            albumArt = albumArt(snapshot.mediaId, snapshot.artworkUri, hasStationArt),
            volume = (volumePercent ?: 0).coerceIn(0, 100),
            muted = muted,
            presets = stations.filter { !MediaIdScheme.isNas(it.id) }.map { LocalPreset(it.id, it.name, hasStationArt(it.id)) },
            updatedAtMs = nowMs,
        )
    }
}

data class DeviceInfo(val id: String, val name: String, val model: String, val appVersion: String)

object LocalJson {
    const val API_VERSION = 1
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,32}$")

    fun stationArtUrl(baseUrl: String?, stationId: String): String? {
        if (baseUrl == null || !SAFE_ID.matches(stationId)) return null
        return "$baseUrl/art/station/$stationId.png"
    }

    private fun artUrl(art: ArtRef, baseUrl: String?): String? = when (art) {
        is ArtRef.Public -> art.url
        is ArtRef.Station -> stationArtUrl(baseUrl, art.stationId)
        ArtRef.None -> null
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    fun state(s: LocalState, baseUrl: String?): JSONObject {
        val presets = JSONArray()
        s.presets.forEach { p ->
            presets.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .putNullable("imageUrl", if (p.hasArt) stationArtUrl(baseUrl, p.id) else null),
            )
        }
        return JSONObject()
            .put("power", s.power.wire)
            .put("playback", s.playback.wire)
            .putNullable("mediaId", s.mediaId)
            .putNullable("title", s.title)
            .putNullable("artist", s.artist)
            .putNullable("albumArtUrl", artUrl(s.albumArt, baseUrl))
            .put("volume", s.volume)
            .put("muted", s.muted)
            .put("presets", presets)
            .put("updatedAtMs", s.updatedAtMs)
    }

    fun info(info: DeviceInfo, pairingOpen: Boolean): JSONObject = JSONObject()
        .put("id", info.id)
        .put("name", info.name)
        .put("model", info.model)
        .put("appVersion", info.appVersion)
        .put("apiVersion", API_VERSION)
        .put("pairingOpen", pairingOpen)

    fun error(code: String): JSONObject = JSONObject().put("error", code)

    fun commandResult(ok: Boolean, message: String?): JSONObject {
        val o = JSONObject().put("ok", ok)
        if (message != null) o.put("message", message)
        return o
    }
}
