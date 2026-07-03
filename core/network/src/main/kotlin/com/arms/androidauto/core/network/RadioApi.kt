package com.arms.androidauto.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

data class StationApiResponse(
    val id: String,
    val name: String,
    val streamUrl: String,
    val type: String,
    val currentSong: String? = null,
    val programTitle: String? = null,
    val imageUrl: String? = null
)

interface RadioApiService {
    fun getStations(): List<StationApiResponse>
    fun getStationMetadata(stationId: String): StationApiResponse?

    // 재생 직전에 호출하여 항상 유효한(만료되지 않은) 스트림 URL을 새로 받아옴
    fun getPlaybackUrl(stationId: String): String?
}

class RadioApiServiceImpl(private val client: OkHttpClient) : RadioApiService {

    // KBS 공식 라이브 채널 API. service_url은 만료 서명(Policy/Signature)이 포함된 임시 URL이라 매번 새로 받아야 함.
    private val kbsLiveChannelUrl = "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25"
    private val fallbackStreamUrl = "https://2fm-ad.gscdn.kbs.co.kr/2fm_ad_192_1.m3u8"

    // KBS 공식 편성표 API (radio.kbs.co.kr 페이지가 실제로 호출하는 엔드포인트에서 확인).
    // program_ch_code=25 는 2FM(Cool FM) 채널 코드.
    private val kbsProgramApiUrl = "https://pprogramapi.kbs.co.kr/api/v1/external/program" +
        "?rtype=jsonp&end_yn=N&program_ch_code=25&section_code=99&station_code=00&page=1&page_size=20&sort_option=on_air%20asc"

    // SBS 공식 라이브 스트림 API. 응답 본문이 곧 서명 URL 문자열(JSON 아님)이며 매번 새로 받아야 함.
    private val sbsLiveApiUrl = "https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y"

    // SBS 라디오 페이지(www.sbs.co.kr/radio)가 실제로 호출하는 편성표 API.
    private val sbsBoraApiUrl = "https://static.apis.sbs.co.kr/radio-api/gorealra/1.0/onair/bora/today?limit=100&todayOnly=false"

    // LISTEN.moe의 K-POP 24시간 논스톱 스트림. 만료 토큰이 없는 고정 주소.
    private val kpopStreamUrl = "https://listen.moe/kpop/stream"

    override fun getStations(): List<StationApiResponse> {
        val kbsStreamUrl = fetchKbsLiveStreamUrl() ?: fallbackStreamUrl
        val sbsStreamUrl = fetchSbsLiveStreamUrl()

        // SBS는 실시간 서명 URL 발급에 실패하면 목록에서 제외 (다음 새로고침에 재시도)
        return listOfNotNull(
            StationApiResponse(
                id = "1",
                name = "KBS Cool FM (89.1 MHz)",
                streamUrl = kbsStreamUrl,
                type = "RADIO"
            ),
            sbsStreamUrl?.let {
                StationApiResponse(
                    id = "2",
                    name = "SBS 파워FM (107.7 MHz)",
                    streamUrl = it,
                    type = "RADIO"
                )
            },
            StationApiResponse(
                id = "3",
                name = "K-POP 24/7",
                streamUrl = kpopStreamUrl,
                type = "STREAMING"
            )
        )
    }

    // KBS 공식 API에서 현재 시점에 유효한 서명된 스트림 URL을 가져옴
    private fun fetchKbsLiveStreamUrl(): String? {
        return try {
            val request = Request.Builder()
                .url(kbsLiveChannelUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.isNotEmpty()) {
                    """"service_url"\s*:\s*"([^"]+)"""".toRegex().find(body)?.groupValues?.get(1)
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    // SBS 공식 API에서 현재 시점에 유효한 서명된 스트림 URL을 가져옴 (응답 본문 자체가 URL)
    private fun fetchSbsLiveStreamUrl(): String? {
        return try {
            val request = Request.Builder()
                .url(sbsLiveApiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (response.isSuccessful && body.startsWith("http")) body else null
            }
        } catch (e: IOException) {
            null
        }
    }

    // 재생 직전에 호출하여 항상 새로 서명된(만료되지 않은) 스트림 URL을 가져옴
    override fun getPlaybackUrl(stationId: String): String? {
        return when (stationId) {
            "1" -> fetchKbsLiveStreamUrl() ?: fallbackStreamUrl
            "2" -> fetchSbsLiveStreamUrl()
            "3" -> kpopStreamUrl
            else -> null
        }
    }

    override fun getStationMetadata(stationId: String): StationApiResponse? {
        return when (stationId) {
            "1" -> kbsMetadata()
            "2" -> sbsMetadata()
            "3" -> kpopMetadata()
            else -> null
        }
    }

    // 현재 대한민국 시각(KST)을 HHMM 정수로 반환 (예: 14:05 -> 1405)
    private fun currentKstHHMM(): Int {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        return now.hour * 100 + now.minute
    }

    // start~end 구간에 now가 포함되는지 확인. end가 자정(0000)이면 다음날 0시로 취급해 자정을 넘기는 구간도 처리.
    private fun isNowInWindow(nowHHMM: Int, startHHMM: Int, endHHMMRaw: Int): Boolean {
        val endHHMM = if (endHHMMRaw == 0 && startHHMM != 0) 2400 else endHHMMRaw
        return if (endHHMM > startHHMM) {
            nowHHMM in startHHMM until endHHMM
        } else {
            nowHHMM >= startHHMM || nowHHMM < endHHMM
        }
    }

    // KBS 2FM 공식 편성표에서 현재 시간대에 방송 중인 프로그램명/이미지를 찾음
    private fun fetchKbsCurrentProgram(): Pair<String, String?>? {
        return try {
            val request = Request.Builder()
                .url(kbsProgramApiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful || body.isEmpty()) return null

                val items = JSONObject(body).optJSONArray("data") ?: return null
                val nowHHMM = currentKstHHMM()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val stime = item.optString("program_stime").toIntOrNull() ?: continue
                    val etime = item.optString("program_etime").toIntOrNull() ?: continue
                    if (isNowInWindow(nowHHMM, stime, etime)) {
                        val title = item.optString("title").ifBlank { null } ?: continue
                        val image = item.optString("image_o").ifBlank { null }
                        return title to image
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // KBS의 실시간 편성 정보를 공식 편성표 API에서 가져옴 (조회 실패 시 사실과 다른 정보를 지어내지 않고 안내 문구로 대체)
    private fun kbsMetadata(): StationApiResponse {
        val current = fetchKbsCurrentProgram()
        return StationApiResponse(
            id = "1",
            name = "KBS Cool FM (89.1 MHz)",
            streamUrl = fallbackStreamUrl,
            type = "RADIO",
            currentSong = "실시간 방송 중",
            programTitle = current?.first ?: "편성 정보를 제공하지 않습니다",
            imageUrl = current?.second
        )
    }

    // SBS 파워FM 공식 편성표에서 현재 시간대에 방송 중인 프로그램명/이미지를 찾음
    private fun fetchSbsCurrentProgram(): Pair<String, String?>? {
        return try {
            val request = Request.Builder()
                .url(sbsBoraApiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful || body.isEmpty()) return null

                val items = JSONArray(body)
                val nowHHMM = currentKstHHMM()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("section") != "PF") continue
                    val stime = item.optString("startTime").replace(":", "").toIntOrNull() ?: continue
                    val etime = item.optString("endTime").replace(":", "").toIntOrNull() ?: continue
                    if (isNowInWindow(nowHHMM, stime, etime)) {
                        val title = item.optString("title").ifBlank { null } ?: continue
                        val image = item.optString("program_image").ifBlank { null }
                        return title to image
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sbsMetadata(): StationApiResponse {
        val current = fetchSbsCurrentProgram()
        return StationApiResponse(
            id = "2",
            name = "SBS 파워FM (107.7 MHz)",
            streamUrl = fetchSbsLiveStreamUrl() ?: "",
            type = "RADIO",
            currentSong = "실시간 라디오 음원 수신 중",
            programTitle = current?.first ?: "SBS 파워FM 실시간 방송",
            imageUrl = current?.second
        )
    }

    // LISTEN.moe의 실시간 메타데이터 스트림(SSE)에서 K-POP 채널의 현재 곡 (아티스트, 제목)을 읽어옴.
    // 스트림은 계속 열려 있으므로 kpop 항목을 찾는 즉시 연결을 닫는다.
    private fun fetchKpopNowPlaying(): Pair<String, String>? {
        return try {
            val request = Request.Builder()
                .url("https://listen.moe/metadata?channel=kpop")
                .header("User-Agent", "Mozilla/5.0")
                // gzip 압축을 받으면 SSE 청크가 즉시 flush되지 않고 버퍼링되어 응답이 지연됨
                .header("Accept-Encoding", "identity")
                .build()

            // BODY 레벨 로깅 인터셉터가 응답 본문을 통째로 peek하려다 끝나지 않는 SSE 스트림에서
            // 무한 대기할 수 있으므로, 공용 client의 인터셉터를 물려받지 않는 별도 클라이언트를 사용
            val sseClient = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            sseClient.newCall(request).execute().use { response ->
                val body = response.body ?: return null
                val reader = body.byteStream().bufferedReader()
                var result: Pair<String, String>? = null
                var linesRead = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    linesRead++
                    if (linesRead > 30) break
                    if (line.startsWith("data:") && line.contains("\"/kpop/stream\"")) {
                        val title = """"title"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
                            .find(line)?.groupValues?.get(1)
                        if (title != null) {
                            val parts = title.split(" - ", limit = 2)
                            result = if (parts.size == 2) parts[0] to parts[1] else "K-POP" to title
                        }
                        break
                    }
                }
                result
            }
        } catch (e: Exception) {
            null
        }
    }

    // LISTEN.moe GraphQL API에서 곡 제목으로 검색해 앨범 커버 이미지를 가져옴
    private fun fetchKpopCoverImage(songTitle: String): String? {
        return try {
            val escaped = songTitle.replace("\\", "\\\\").replace("\"", "\\\"")
            val graphqlQuery = "{ search(query: \"$escaped\", kpop: true, limit: 1) " +
                "{ ... on Song { albums { image } } } }"
            val payload = JSONObject().put("query", graphqlQuery).toString()

            val request = Request.Builder()
                .url("https://listen.moe/graphql")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val searchResults = JSONObject(body).optJSONObject("data")?.optJSONArray("search")
                if (searchResults == null || searchResults.length() == 0) return null
                val albums = searchResults.getJSONObject(0).optJSONArray("albums")
                if (albums == null || albums.length() == 0) return null
                val image = albums.getJSONObject(0).optString("image").ifBlank { null }
                image?.let { "https://cdn.listen.moe/covers/$it" }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun kpopMetadata(): StationApiResponse {
        val nowPlaying = fetchKpopNowPlaying()
        val imageUrl = nowPlaying?.second?.let { fetchKpopCoverImage(it) }
        return StationApiResponse(
            id = "3",
            name = "K-POP 24/7",
            streamUrl = kpopStreamUrl,
            type = "STREAMING",
            currentSong = nowPlaying?.let { "${it.first} - ${it.second}" } ?: "쉬지 않고 이어지는 K-POP 논스톱",
            programTitle = "24시간 K-POP 스트리밍",
            imageUrl = imageUrl
        )
    }
}
