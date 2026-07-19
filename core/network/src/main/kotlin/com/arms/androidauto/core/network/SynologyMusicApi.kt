package com.arms.androidauto.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SynologyCredentials(
    val baseUrl: String, // 예: https://v.1319.space:5001
    val account: String,
    val password: String
)

data class SynologySongResponse(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?
)

// Synology DSM의 공식 Web API(webapi/)를 통해 Audio Station의 곡 목록을 조회하고,
// 실제 재생 가능한 스트리밍 URL을 구성한다.
//
// Audio Station에 별도로 만든 재생목록(Playlist)이 없는 경우가 많고, 앨범은 자체 ID가 없는
// 태그 기반 그룹이라 서버 쪽 필터 조회 파라미터가 불명확했다. 대신 전체 곡 목록을 한 번에
// 받아와서(라이브러리 규모가 실제로 감당 가능한 수준) 클라이언트에서 앨범별로 묶는 방식을
// 쓴다 — 실제 NAS(736곡)로 확인한 결과 전체 목록이 한 번의 요청으로 온전히 돌아온다.
interface SynologyMusicApi {
    // 로그인에 성공하면 세션 ID(sid)를 반환. 이후 모든 요청에 _sid 파라미터로 사용한다.
    fun login(credentials: SynologyCredentials): String?

    fun getAllSongs(credentials: SynologyCredentials, sid: String): List<SynologySongResponse>

    // 세션이 유효한 동안 바로 재생 가능한 스트리밍 URL (별도 요청 없이 URL 자체로 스트리밍됨)
    fun getStreamUrl(credentials: SynologyCredentials, sid: String, songId: String): String
}

class SynologyMusicApiImpl(private val client: OkHttpClient) : SynologyMusicApi {

    override fun login(credentials: SynologyCredentials): String? {
        return try {
            val url = credentials.baseUrl.trimEnd('/') + "/webapi/auth.cgi"
            val httpUrl = url.toHttpUrl().newBuilder()
                .addQueryParameter("api", "SYNO.API.Auth")
                .addQueryParameter("version", "6")
                .addQueryParameter("method", "login")
                .addQueryParameter("account", credentials.account)
                .addQueryParameter("passwd", credentials.password)
                .addQueryParameter("session", "AudioStation")
                .addQueryParameter("format", "sid")
                .build()
            val request = Request.Builder().url(httpUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: return null)
                if (!json.optBoolean("success")) return null
                json.optJSONObject("data")?.optString("sid")?.ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getAllSongs(credentials: SynologyCredentials, sid: String): List<SynologySongResponse> {
        return try {
            val url = credentials.baseUrl.trimEnd('/') + "/webapi/AudioStation/song.cgi"
            val httpUrl = url.toHttpUrl().newBuilder()
                .addQueryParameter("api", "SYNO.AudioStation.Song")
                .addQueryParameter("version", "3")
                .addQueryParameter("method", "list")
                .addQueryParameter("library", "shared")
                .addQueryParameter("additional", "song_tag")
                .addQueryParameter("_sid", sid)
                .build()
            val request = Request.Builder().url(httpUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string() ?: return emptyList())
                val songArray = json.optJSONObject("data")?.optJSONArray("songs") ?: return emptyList()
                (0 until songArray.length()).mapNotNull { i ->
                    val item = songArray.getJSONObject(i)
                    val id = item.optString("id").ifBlank { return@mapNotNull null }
                    val tag = item.optJSONObject("additional")?.optJSONObject("song_tag")
                    SynologySongResponse(
                        id = id,
                        title = item.optString("title").ifBlank { "제목 없음" },
                        artist = tag?.optString("artist")?.ifBlank { null },
                        album = tag?.optString("album")?.ifBlank { null },
                        albumArtist = tag?.optString("album_artist")?.ifBlank { null }
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getStreamUrl(credentials: SynologyCredentials, sid: String, songId: String): String {
        val url = credentials.baseUrl.trimEnd('/') + "/webapi/AudioStation/stream.cgi"
        return url.toHttpUrl().newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Stream")
            .addQueryParameter("version", "2")
            .addQueryParameter("method", "stream")
            .addQueryParameter("id", songId)
            .addQueryParameter("_sid", sid)
            .build()
            .toString()
    }
}
