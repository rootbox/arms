package com.arms.androidauto.desktop.nas

import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.core.model.NasTrack
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.network.SongListResult
import com.arms.androidauto.core.network.SynologyCredentials
import com.arms.androidauto.core.network.SynologyMusicApi
import com.arms.androidauto.desktop.storage.CredentialStore

private const val UNKNOWN_ALBUM = "알 수 없는 앨범"

// 요청한 ID 순서를 그대로 유지해 곡을 골라낸다. (안드로이드 core/data의 orderSongsByIds와 동일
// 알고리즘 — 사용자가 담은 순서와 재생 순서가 어긋나면 조용히 틀리는 버그가 되므로 테스트로 고정)
internal fun orderSongsByIds(songs: List<NasSong>, songIds: List<String>): List<NasSong> {
    val byId = songs.associateBy { it.id }
    return songIds.mapNotNull { byId[it] }
}

// 데스크톱 NAS 저장소. 재사용 가능한 SynologyMusicApi(순수 JVM) 위에 세션 재사용 + 인증 오류
// 시에만 재로그인 + 곡목록 캐시를 올린다. 안드로이드 NasMusicRepository와 같은 정책이되,
// 저장(자격증명/sid)은 데스크톱 CredentialStore에 위임한다.
//
// (안드로이드와 로직이 겹치는 부분은 장차 core로 추출해 공유할 여지가 있다 — Phase 2 노트.)
class DesktopNasRepository(
    private val credentialStore: CredentialStore,
    private val api: SynologyMusicApi = NetworkClient.synologyMusicApi,
) {
    private data class Session(val credentials: SynologyCredentials, val sid: String)
    @Volatile private var session: Session? = null
    @Volatile private var songsCache: List<NasSong>? = null

    fun hasCredentials(): Boolean = credentialStore.get() != null

    fun saveCredentials(baseUrl: String, account: String, password: String) {
        credentialStore.save(SynologyCredentials(baseUrl.trim(), account.trim(), password))
        session = null; songsCache = null; credentialStore.clearSid()
    }

    fun testConnection(baseUrl: String, account: String, password: String): Boolean =
        api.login(SynologyCredentials(baseUrl.trim(), account.trim(), password)) != null

    // 저장된 sid를 우선 재사용하고, 없을 때만 로그인한다. NAS가 매 실행 "새 로그인"으로 감지하지
    // 않도록 하는 정책(안드로이드와 동일).
    private fun session(forceFresh: Boolean = false): Session? {
        val creds = credentialStore.get() ?: return null
        if (!forceFresh) {
            session?.let { if (it.credentials == creds) return it }
            credentialStore.getSid()?.let { return Session(creds, it).also { s -> session = s } }
        }
        val sid = api.login(creds) ?: return null
        credentialStore.saveSid(sid)
        return Session(creds, sid).also { session = it }
    }

    private fun discardSession() { session = null; credentialStore.clearSid() }

    private fun songs(): List<NasSong> {
        songsCache?.let { return it }
        val fetched = fetchSongs() ; songsCache = fetched ; return fetched
    }

    // 저장된 세션으로 시도하고, 인증 오류일 때만 새 로그인으로 한 번 더 시도한다.
    private fun fetchSongs(): List<NasSong> {
        requestSongs(false)?.let { return it }
        discardSession()
        return requestSongs(true) ?: emptyList()
    }

    // 인증 실패면 null, 그 밖의 실패면 빈 목록.
    private fun requestSongs(forceFresh: Boolean): List<NasSong>? {
        val s = session(forceFresh) ?: return emptyList()
        return when (val r = api.getAllSongs(s.credentials, s.sid)) {
            is SongListResult.Success -> r.songs.map { NasSong(it.id, it.title, it.artist, it.album, it.albumArtist) }
            SongListResult.AuthFailure -> null
            SongListResult.Failure -> emptyList()
        }
    }

    fun getAlbums(): List<NasAlbum> = songs()
        .groupBy { (it.album ?: UNKNOWN_ALBUM) to (it.albumArtist ?: "") }
        .map { (k, v) -> NasAlbum(name = k.first, albumArtist = k.second, songCount = v.size) }
        .sortedBy { it.name }

    fun getAlbumTracks(album: NasAlbum): List<NasTrack> =
        attach(songs().filter { (it.album ?: UNKNOWN_ALBUM) == album.name && (it.albumArtist ?: "") == album.albumArtist })

    // 플레이리스트 재생용: 담은 순서 그대로.
    fun getTracksForSongIds(songIds: List<String>): List<NasTrack> =
        attach(orderSongsByIds(songs(), songIds))

    private fun attach(target: List<NasSong>): List<NasTrack> {
        if (target.isEmpty()) return emptyList()
        val s = session() ?: return emptyList()
        return target.map { NasTrack(it, api.getStreamUrl(s.credentials, s.sid, it.id), api.getCoverUrl(s.credentials, s.sid, it.id)) }
    }
}
