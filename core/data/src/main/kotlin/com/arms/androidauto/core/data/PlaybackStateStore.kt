package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.NasAlbum
import org.json.JSONArray
import org.json.JSONObject

// 마지막으로 재생한 것이 라디오였는지 NAS 앨범이었는지를 구분해서 저장한다.
// (기존에는 stationId 문자열 하나만 저장해서 NAS를 표현할 방법이 없었다)
sealed class LastPlayed {
    data class Radio(val stationId: String) : LastPlayed()
    data class Nas(val album: NasAlbum) : LastPlayed()

    // 이름을 함께 저장해두면 DB를 읽기 전에도 표시를 결정할 수 있다.
    // (재생 시점에 이름이 바뀌어 있으면 DB 값이 우선한다)
    data class NasPlaylist(val playlistId: Long, val name: String) : LastPlayed()
}

// 차량 자동 재개와 "최근 재생한 앨범" 목록을 위한 저장소.
//
// 자격증명이 아니라 재생 이력만 다루므로 암호화 저장소(NasCredentialsStore)를 쓰지 않는다.
// 암호화 저장소는 첫 접근에 Keystore 초기화가 붙어서, 차량이 브라우징 루트를 물어보는
// 시점처럼 빨라야 하는 경로에서 쓰기에 부담스럽다. 기존 라디오 설정과 같은 평문 prefs 파일을
// 공유한다.
class PlaybackStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastPlayed(lastPlayed: LastPlayed) {
        val editor = prefs.edit()
        when (lastPlayed) {
            is LastPlayed.Radio -> {
                editor.putString(KEY_LAST_KIND, KIND_RADIO)
                editor.putString(KEY_LAST_STATION_ID, lastPlayed.stationId)
            }
            is LastPlayed.Nas -> {
                editor.putString(KEY_LAST_KIND, KIND_NAS)
                editor.putString(KEY_LAST_NAS_ALBUM, lastPlayed.album.toJson().toString())
            }
            is LastPlayed.NasPlaylist -> {
                editor.putString(KEY_LAST_KIND, KIND_NAS_PLAYLIST)
                editor.putLong(KEY_LAST_PLAYLIST_ID, lastPlayed.playlistId)
                editor.putString(KEY_LAST_PLAYLIST_NAME, lastPlayed.name)
            }
        }
        editor.apply()
    }

    // 알 수 없는 종류가 저장돼 있으면 null을 돌려준다. 구버전 앱으로 되돌아가도
    // 크래시하지 않고 "재개할 것 없음"으로 조용히 처리되도록 하기 위함.
    fun getLastPlayed(): LastPlayed? {
        return when (prefs.getString(KEY_LAST_KIND, null)) {
            KIND_RADIO -> prefs.getString(KEY_LAST_STATION_ID, null)?.let { LastPlayed.Radio(it) }
            KIND_NAS -> prefs.getString(KEY_LAST_NAS_ALBUM, null)
                ?.let { runCatching { JSONObject(it).toNasAlbum() }.getOrNull() }
                ?.let { LastPlayed.Nas(it) }
            KIND_NAS_PLAYLIST -> {
                val id = prefs.getLong(KEY_LAST_PLAYLIST_ID, -1L)
                if (id <= 0) null
                else LastPlayed.NasPlaylist(id, prefs.getString(KEY_LAST_PLAYLIST_NAME, "") ?: "")
            }
            else -> null
        }
    }

    // 차량 루트의 "최근 재생한 앨범" 노드용. 곡 수까지 저장해두는 이유는, 네트워크 없이도
    // 목록을 즉시 그리기 위해서다 (터널이나 NAS 연결 실패 상황에서도 목록은 뜨고, 실제로
    // 누른 순간에 처음 네트워크를 쓴다).
    fun pushRecentAlbum(album: NasAlbum) {
        val existing = getRecentAlbums().filterNot {
            it.name == album.name && it.albumArtist == album.albumArtist
        }
        val updated = (listOf(album) + existing).take(MAX_RECENT_ALBUMS)
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_RECENT_ALBUMS, array.toString()).apply()
    }

    fun getRecentAlbums(): List<NasAlbum> {
        val raw = prefs.getString(KEY_RECENT_ALBUMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { array.getJSONObject(i).toNasAlbum() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun clearRecentAlbums() {
        prefs.edit().remove(KEY_RECENT_ALBUMS).apply()
    }

    // NAS가 설정되어 있는지를 평문으로 따로 들고 있는다. 실제 자격증명 확인(hasCredentials)은
    // 암호화 저장소를 열어야 해서 느리기 때문에, 차량 브라우징 루트처럼 빨라야 하는 곳에서는
    // 이 플래그만 본다. 자격증명 자체가 아니라 "설정됨 여부"만 저장하므로 노출 위험이 없다.
    fun setNasConfigured(configured: Boolean) {
        prefs.edit().putBoolean(KEY_NAS_CONFIGURED, configured).apply()
    }

    fun isNasConfigured(): Boolean = prefs.getBoolean(KEY_NAS_CONFIGURED, false)

    private fun NasAlbum.toJson(): JSONObject = JSONObject().apply {
        put(FIELD_ARTIST, albumArtist)
        put(FIELD_NAME, name)
        put(FIELD_COUNT, songCount)
    }

    private fun JSONObject.toNasAlbum(): NasAlbum = NasAlbum(
        name = getString(FIELD_NAME),
        albumArtist = optString(FIELD_ARTIST, ""),
        songCount = optInt(FIELD_COUNT, 0)
    )

    companion object {
        // StationRepository와 같은 파일을 공유한다 (마지막 재생 채널이 이미 여기 저장됨)
        private const val PREFS_NAME = "arms_radio_prefs"

        private const val KEY_LAST_KIND = "last_played_kind"
        private const val KEY_LAST_STATION_ID = "last_played_station_id"
        private const val KEY_LAST_NAS_ALBUM = "last_played_nas_album"
        private const val KEY_RECENT_ALBUMS = "recent_nas_albums"
        private const val KEY_NAS_CONFIGURED = "nas_configured"

        private const val KEY_LAST_PLAYLIST_ID = "last_played_playlist_id"
        private const val KEY_LAST_PLAYLIST_NAME = "last_played_playlist_name"

        private const val KIND_RADIO = "radio"
        private const val KIND_NAS = "nas"
        private const val KIND_NAS_PLAYLIST = "nas_playlist"

        private const val FIELD_ARTIST = "artist"
        private const val FIELD_NAME = "name"
        private const val FIELD_COUNT = "count"

        private const val MAX_RECENT_ALBUMS = 8
    }
}
