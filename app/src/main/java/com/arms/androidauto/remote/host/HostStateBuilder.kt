package com.arms.androidauto.remote.host

import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.core.remote.BluetoothStatus
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteGuest
import com.arms.androidauto.core.remote.RemoteItem

// 세션(MediaController)에서 읽은 값 중 게스트 상태를 만드는 데 필요한 것만 담는 스냅샷.
// 서비스가 컨트롤러에서 채우고, 빌더는 Android 의존 없이 이 값만 본다(단위 테스트용).
data class SessionSnapshot(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val albumTitle: String?,
    val isPlaying: Boolean,
    val playbackState: Int,
    val artworkUri: String?,
    // 로컬 제어 API의 power/playback 판단용(재생 의도·오류 복구 대기). MQTT HostState에는 쓰지 않는다.
    val playWhenReady: Boolean = false,
    val hasError: Boolean = false,
) {
    companion object {
        val EMPTY = SessionSnapshot(
            mediaId = null, title = null, artist = null, albumTitle = null,
            isPlaying = false, playbackState = 1 /* Player.STATE_IDLE */, artworkUri = null,
        )
    }
}

// 세션 스냅샷 + 채널 목록 + 블루투스/배터리 → HostState. 화이트리스트(플랜 §3) 밖의 값은
// 여기서 걸러진다: NAS 스트림/커버 URL(sid 포함)은 절대 밖으로 나가지 않는다.
object HostStateBuilder {

    const val STATION_ART_PREFIX = "station:"

    // artworkRef 규칙:
    // - NAS 항목: 항상 null (커버 URL에 sid가 붙고, content:// 도 게스트가 읽을 수 없다)
    // - 세션 커버가 공개 http(s) URL이면 그 URL (단, sid 파라미터가 보이면 null)
    // - 라디오 채널이면 "station:<id>" (게스트가 번들 채널 아트로 그린다)
    // - 그 외(content:// FileProvider, android.resource://, 없음) → null
    fun artworkRef(mediaId: String?, artworkUri: String?): String? {
        if (mediaId != null && MediaIdScheme.isNas(mediaId)) return null
        val publicUrl = artworkUri?.takeIf { isPublicHttpUrl(it) }
        if (publicUrl != null) return publicUrl
        if (!mediaId.isNullOrBlank()) return STATION_ART_PREFIX + mediaId
        return null
    }

    private fun isPublicHttpUrl(uri: String): Boolean {
        val lower = uri.lowercase()
        if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false
        // NAS(Synology) 스트림/커버 URL은 세션 id를 쿼리에 싣는다. 혹시라도 새어 나가지 않게 막는다.
        if (lower.contains("_sid=") || lower.contains("sid=")) return false
        return true
    }

    // 게스트가 고를 수 있는 목록: 라디오 채널 전체 + (NAS 재생 중이면) 지금 항목 하나.
    // NAS 브라우징은 v1 범위 밖이다(플랜 §5).
    fun items(stations: List<Station>, current: SessionSnapshot?): List<RemoteItem> {
        val result = stations.map { RemoteItem(mediaId = it.id, name = it.name, subtitle = stationSubtitle(it)) }
        val nasId = current?.mediaId?.takeIf { MediaIdScheme.isNas(it) } ?: return result
        val name = current.albumTitle?.takeIf { it.isNotBlank() } ?: current.title?.takeIf { it.isNotBlank() } ?: "NAS 음악"
        val subtitle = current.artist?.takeIf { it.isNotBlank() } ?: "NAS"
        return result + RemoteItem(mediaId = nasId, name = name, subtitle = subtitle)
    }

    // 주파수는 보여 주되 스트림 URL은 밖으로 내지 않는다.
    fun stationSubtitle(station: Station): String = when (station.type) {
        StationType.STREAMING -> "스트리밍"
        StationType.RADIO -> {
            val f = station.frequencyOrUrl.trim()
            if (f.isEmpty() || f.startsWith("http", ignoreCase = true)) "라디오" else f
        }
    }

    // 게스트가 보낸 select{mediaId}가 호스트가 공개한 항목인지. 그 외 id는 거부한다(권한 분리).
    fun isKnownMediaId(mediaId: String, items: List<RemoteItem>): Boolean = items.any { it.mediaId == mediaId }

    fun build(
        snapshot: SessionSnapshot,
        stations: List<Station>,
        bluetooth: BluetoothStatus?,
        batteryPercent: Int?,
        nowMs: Long,
        // 미디어 볼륨(0..100). 모르면 null.
        volumePercent: Int? = null,
        // 지금 붙어 있는 게스트(이름·마지막 인사 시각). 호스트 상단바·QR 화면 자동 닫힘용.
        guests: List<RemoteGuest> = emptyList(),
        // "연결 종료" 알림. 게스트는 이걸 받으면 페어링을 지운다.
        revoked: Boolean = false,
        // 스마트싱스 연결 허용 창이 열려 있으면 닫히는 시각(epoch ms). 게스트 카운트다운용.
        stPairingOpenUntilMs: Long? = null,
        // 연결된 스마트싱스 클라이언트 수(이름·주소는 내보내지 않는다).
        stClientCount: Int = 0,
    ): HostState = HostState(
        mediaId = snapshot.mediaId,
        title = snapshot.title,
        artist = snapshot.artist,
        isPlaying = snapshot.isPlaying,
        playbackState = snapshot.playbackState,
        artworkRef = artworkRef(snapshot.mediaId, snapshot.artworkUri),
        bluetooth = bluetooth,
        batteryPercent = batteryPercent?.coerceIn(0, 100),
        updatedAtMs = nowMs,
        items = items(stations, snapshot),
        volumePercent = volumePercent?.coerceIn(0, 100),
        guests = guests,
        revoked = revoked,
        stPairingOpenUntilMs = stPairingOpenUntilMs,
        stClientCount = stClientCount.coerceAtLeast(0),
    )
}
