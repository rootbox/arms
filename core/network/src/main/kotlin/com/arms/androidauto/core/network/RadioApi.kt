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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// SBS 온에어 API가 주는 현재 프로그램. channelImageUrl은 프로그램 이미지가 없을 때의 폴백.
data class SbsOnairProgram(val title: String, val imageUrl: String?, val channelImageUrl: String?)

// LISTEN.moe 게이트웨이가 푸시하는 현재 곡. 곡·아티스트·커버가 한 번에 온다.
data class KpopTrack(val artist: String, val title: String, val coverUrl: String?)

// 게이트웨이 메시지 한 건을 해석한다. TRACK_UPDATE(op=1)가 아니면 null.
// 순수 함수로 떼어둔 이유는 실제 소켓 없이 페이로드 형태를 테스트로 고정하기 위해서다.
internal fun parseKpopGatewayMessage(raw: String): KpopTrack? {
    return try {
        val message = JSONObject(raw)
        if (message.optInt("op", -1) != 1) return null
        if (message.optString("t") != "TRACK_UPDATE") return null
        val song = message.optJSONObject("d")?.optJSONObject("song") ?: return null
        val title = song.optString("title").ifBlank { return null }
        val artists = song.optJSONArray("artists")
        val artistNames = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists!!.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        val albums = song.optJSONArray("albums")
        val coverFile = (0 until (albums?.length() ?: 0))
            .mapNotNull { albums!!.optJSONObject(it)?.optString("image")?.ifBlank { null } }
            .firstOrNull()
        KpopTrack(
            artist = artistNames.joinToString(", ").ifBlank { "K-POP" },
            title = title,
            coverUrl = coverFile?.let { "https://cdn.listen.moe/covers/$it" }
        )
    } catch (e: Exception) {
        null
    }
}

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
    // 파워FM "지금 방송 중" 프로그램. 제목·시간·프로그램 이미지를 한 번에 준다.
    // 예전에 쓰던 bora/today는 "보이는 라디오" 편성표라 영상이 있는 방송만 담겨 있어서,
    // 하루 중 대부분의 시간대(예: 09~22시)에 파워FM 프로그램을 찾지 못하고 커버가 비었다.
    private val sbsOnairApiUrl = "https://static.apis.sbs.co.kr/play-api/1.0/onair/channel/S07"

    // 프로그램 이미지를 끝내 못 구했을 때 쓰는 파워FM 채널 이미지(SBS가 온에어 API의
    // background 필드로 제공하는 것과 같은 자원). 회색 빈 칸 대신 최소한 방송사 이미지를 보여준다.
    private val sbsChannelImageUrl = "https://image.cloud.sbs.co.kr/play/radio/power.jpg"

    private val sbsBoraApiUrl = "https://static.apis.sbs.co.kr/radio-api/gorealra/1.0/onair/bora/today?limit=100&todayOnly=false"

    // LISTEN.moe의 K-POP 24시간 논스톱 스트림. 만료 토큰이 없는 고정 주소.
    private val kpopStreamUrl = "https://listen.moe/kpop/stream"

    // K-POP 발라드 24/7. 1차는 국내 개인 서버(sCast.kr, Shoutcast DNAS 엔드포인트 — Steamcast
    // 마운트는 ICY 요청에 비표준 상태줄을 돌려주므로 쓰지 않는다), 2차는 laut.fm(GEMA 정산 플랫폼).
    // 개인 서버는 예고 없이 사라질 수 있어 재생 직전에 살아있는 쪽을 고른다.
    private val balladStreamUrls = listOf(
        "https://scast.kr:90/ballad.mp3",
        "https://stream.laut.fm/kpop-love",
    )
    // K-POP 2세대 히트(2000s~2010s). "80/90년대 가요" 전용 공개 스트림은 2026-09 실측에서 없었고,
    // 이 채널의 실제 편성이 2세대(2009~2017)라 이름을 그에 맞췄다. Zeno.FM은 브라우저 UA를 401로
    // 막지만 OkHttp/ExoPlayer/libVLC 기본 UA는 통과한다(User-Agent를 Mozilla로 바꾸지 말 것).
    private val rewindStreamUrls = listOf("https://stream.zeno.fm/hkrivfrongdvv")

    private val balladStationName = "K-POP 발라드 24/7"
    private val rewindStationName = "K-POP 2세대 히트 24/7"

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
            ),
            StationApiResponse(id = "4", name = balladStationName, streamUrl = balladStreamUrls.first(), type = "STREAMING"),
            StationApiResponse(id = "5", name = rewindStationName, streamUrl = rewindStreamUrls.first(), type = "STREAMING"),
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
            "4" -> firstAliveStreamUrl(balladStreamUrls)
            "5" -> firstAliveStreamUrl(rewindStreamUrls)
            else -> null
        }
    }

    override fun getStationMetadata(stationId: String): StationApiResponse? {
        return when (stationId) {
            "1" -> kbsMetadata()
            "2" -> sbsMetadata()
            "3" -> kpopMetadata()
            "4" -> icyStationMetadata("4", balladStationName, balladStreamUrls)
            "5" -> icyStationMetadata("5", rewindStationName, rewindStreamUrls)
            else -> null
        }
    }

    // ---- ICY(Shoutcast/Icecast) 스트림 ----
    // 발라드/2세대 채널은 편성표 API가 없다. 대신 스트림 자체에 실린 ICY 메타데이터("StreamTitle")로
    // 지금 곡을 읽는다. 세 스트림 모두 icy-metaint(16000~16384)를 주므로, 그만큼의 오디오 바이트 뒤에
    // 붙는 메타데이터 블록 하나만 읽고 끊는다(호출당 약 16KB).

    private val streamUserAgent = "SimpleRadio (ExoPlayerLib/1.3.1)"

    // 스트림 요청 전용 클라이언트. 공용 클라이언트의 HttpLoggingInterceptor(BODY)는 응답 본문을
    // 끝까지 읽어 로그로 남기려 하는데, 오디오 스트림은 끝이 없어 영원히 블록된다(데스크톱 스모크가
    // 발라드 채널에서 멈춘 원인). 로깅 인터셉터를 떼고 타임아웃을 짧게 둔다.
    private val streamClient: OkHttpClient by lazy {
        client.newBuilder().apply {
            interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(8, TimeUnit.SECONDS)
            callTimeout(15, TimeUnit.SECONDS)
        }.build()
    }

    // 죽은 스트림 감지: 200 + audio/* 인 첫 후보를 쓴다. 전부 죽었으면 1차 URL을 돌려
    // 플레이어의 오류 경로가 사용자에게 알리게 한다. 후보가 하나면 확인할 것이 없고,
    // 메타데이터는 8초마다 폴링되므로 선택 결과를 5분간 기억해 매번 두드리지 않는다.
    private val aliveChoice = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private fun firstAliveStreamUrl(candidates: List<String>): String {
        if (candidates.size == 1) return candidates.first()
        val key = candidates.first()
        aliveChoice[key]?.let { (url, at) -> if (System.currentTimeMillis() - at < 5 * 60_000L) return url }
        val chosen = candidates.firstOrNull { isStreamAlive(it) } ?: candidates.first()
        aliveChoice[key] = chosen to System.currentTimeMillis()
        return chosen
    }

    private fun isStreamAlive(url: String): Boolean = try {
        val request = Request.Builder().url(url).header("User-Agent", streamUserAgent).build()
        streamClient.newCall(request).execute().use { r ->
            r.isSuccessful && (r.header("Content-Type")?.startsWith("audio/") == true)
        }
    } catch (e: Exception) { false }

    private fun icyStationMetadata(id: String, name: String, candidates: List<String>): StationApiResponse {
        val url = firstAliveStreamUrl(candidates)
        val icy = fetchIcyNowPlaying(url)
        return StationApiResponse(
            id = id, name = name, streamUrl = url, type = "STREAMING",
            programTitle = icy?.streamTitle ?: "실시간 스트리밍 수신 중",
            currentSong = icy?.stationName ?: name,
            imageUrl = icy?.streamTitle?.let { fetchStreamTrackArtwork(it) },
        )
    }

    private fun fetchIcyNowPlaying(url: String): IcyNowPlaying? = try {
        val request = Request.Builder().url(url)
            .header("User-Agent", streamUserAgent)
            .header("Icy-MetaData", "1")
            .build()
        streamClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val stationName = cleanIcyName(r.header("icy-name"))
            val metaint = r.header("icy-metaint")?.trim()?.toIntOrNull()
                ?: return IcyNowPlaying(stationName, null)
            val buf = ByteArray(metaint + 1 + 16 * 255)
            val stream = r.body?.byteStream() ?: return IcyNowPlaying(stationName, null)
            var read = 0
            // 메타데이터 블록 길이 바이트까지는 반드시 읽고, 그 뒤는 길이만큼만 더 읽는다.
            var need = metaint + 1
            while (read < need) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
                if (read >= metaint + 1) need = metaint + 1 + buf[metaint].toInt().and(0xFF) * 16
            }
            IcyNowPlaying(stationName, extractIcyStreamTitle(buf.copyOf(read), metaint))
        }
    } catch (e: Exception) { null }

    // 곡별 커버: 스트림엔 이미지가 없으므로 Deezer 공개 검색(키 불필요)으로 앨범 커버를 찾는다.
    // 못 찾으면 null(회색 플레이스홀더). 실패는 조용히 넘긴다.
    private val artworkCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun fetchStreamTrackArtwork(streamTitle: String): String? {
        artworkCache[streamTitle]?.let { return it.ifEmpty { null } }
        val found = try {
            val q = java.net.URLEncoder.encode(cleanTrackQuery(streamTitle), "UTF-8")
            val request = Request.Builder().url("https://api.deezer.com/search?q=$q&limit=1")
                .header("User-Agent", streamUserAgent).build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null
                else parseDeezerCover(r.body?.string().orEmpty(), streamTitle)
            }
        } catch (e: Exception) { null }
        artworkCache[streamTitle] = found.orEmpty()
        return found
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
    // 온에어 API 응답 해석. 순수 함수로 떼어 실측 페이로드 형태를 테스트로 고정한다.
    // 이미지는 640x360 썸네일을 우선하고(폰/차량 모두 충분한 해상도), 없으면 작은 program_image,
    // 그것도 없으면 채널 배경 이미지를 쓴다.
    internal fun parseSbsOnair(body: String): SbsOnairProgram? {
        return try {
            val info = JSONObject(body).optJSONObject("onair")?.optJSONObject("info") ?: return null
            val title = info.optString("title").ifBlank { return null }
            val image = info.optJSONObject("thumbs")?.optString("640")?.ifBlank { null }
                ?: info.optString("program_image").ifBlank { null }
                ?: info.optString("thumbimg").ifBlank { null }
            val background = info.optString("background").ifBlank { null }
            SbsOnairProgram(title = title, imageUrl = image, channelImageUrl = background)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchSbsOnairProgram(): SbsOnairProgram? {
        return try {
            val request = Request.Builder()
                .url(sbsOnairApiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful || body.isEmpty()) return null
                parseSbsOnair(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    // 보이는 라디오 편성표(bora/today)에서 현재 파워FM 프로그램을 찾는다. 온에어 API가 안 될 때의
    // 2차 경로로만 쓴다 - 영상이 있는 방송만 실려 있어 시간대에 따라 비어 있는 경우가 많다.
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
        // 1차: 온에어 API(현재 프로그램 직접 제공). 2차: 보이는 라디오 편성표.
        // 어느 쪽이든 프로그램 이미지가 없으면 파워FM 채널 이미지로 대신한다 - 커버가
        // 아예 비어 회색으로 남는 것보다 낫고, 방송사 이미지는 항상 있다.
        val onair = fetchSbsOnairProgram()
        val bora = if (onair == null) fetchSbsCurrentProgram() else null
        val title = onair?.title ?: bora?.first
        val image = onair?.imageUrl ?: bora?.second ?: onair?.channelImageUrl ?: sbsChannelImageUrl
        return StationApiResponse(
            id = "2",
            name = "SBS 파워FM (107.7 MHz)",
            streamUrl = fetchSbsLiveStreamUrl() ?: "",
            type = "RADIO",
            currentSong = "실시간 라디오 음원 수신 중",
            programTitle = title ?: "SBS 파워FM 실시간 방송",
            imageUrl = image
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

    // 게이트웨이(공식 3rd-party API)에 붙어 첫 TRACK_UPDATE 한 건만 받고 끊는다.
    // 게이트웨이는 접속 직후 현재 곡을 바로 밀어주므로 연결을 유지할 필요가 없고, 이 함수는
    // 갱신 주기마다 불리는 폴링 구조에 그대로 맞는다.
    private fun fetchKpopTrackFromGateway(): KpopTrack? {
        val latch = CountDownLatch(1)
        var result: KpopTrack? = null
        val request = Request.Builder()
            .url("wss://listen.moe/kpop/gateway_v2")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val socket = try {
            client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    parseKpopGatewayMessage(text)?.let {
                        result = it
                        latch.countDown()
                    }
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    latch.countDown()
                }
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return null
        }
        try {
            latch.await(10, TimeUnit.SECONDS)
        } finally {
            socket.close(1000, null)
        }
        return result
    }

    private fun kpopMetadata(): StationApiResponse {
        // 커버는 게이트웨이가 곡과 함께 정확히 알려준다. 예전 방식(SSE로 제목만 받고 GraphQL
        // 검색으로 커버를 찾기)은 실측에서 유명곡 6곡 중 5곡의 커버를 못 찾았다 - 검색이
        // 제목을 잘 매칭하지 못한다. 게이트웨이가 안 될 때만 예전 경로로 폴백한다.
        val gatewayTrack = fetchKpopTrackFromGateway()
        val nowPlaying: Pair<String, String>? =
            gatewayTrack?.let { it.artist to it.title } ?: fetchKpopNowPlaying()
        val imageUrl = gatewayTrack?.coverUrl
            ?: nowPlaying?.second?.let { if (gatewayTrack == null) fetchKpopCoverImage(it) else null }
        // KBS/SBS와 동일하게, 실제로 화면에 노출되는 필드(programTitle -> subtitle)에
        // 실시간 곡 정보를 담는다. 이전에는 이 값이 항상 고정 문구였고, 실시간 곡 정보는
        // 화면에 노출되지 않는 currentSong(artist) 필드에만 담겨 있어 갱신이 반영되지 않았다.
        val nowPlayingText = nowPlaying?.let { "${it.first} - ${it.second}" }
        return StationApiResponse(
            id = "3",
            name = "K-POP 24/7",
            streamUrl = kpopStreamUrl,
            type = "STREAMING",
            currentSong = "24시간 K-POP 스트리밍",
            programTitle = nowPlayingText ?: "24시간 K-POP 스트리밍",
            imageUrl = imageUrl
        )
    }
}

// ICY 메타데이터 결과. stationName은 icy-name 헤더, streamTitle은 "아티스트 - 곡" 형식이 보통.
internal data class IcyNowPlaying(val stationName: String?, val streamTitle: String?)

// icy-name에는 운영자가 홍보 문구를 붙이곤 한다("Naya Ballad - Soul Kpop # https://sCast.kr").
internal fun cleanIcyName(raw: String?): String? =
    raw?.substringBefore('#')?.trim()?.ifBlank { null }

// 응답 본문에서 첫 메타데이터 블록의 StreamTitle을 꺼낸다.
// 본문 = [오디오 metaint 바이트][길이 1바이트 (x16)][메타데이터 블록 "StreamTitle='...';..."]
internal fun extractIcyStreamTitle(body: ByteArray, metaint: Int): String? {
    if (body.size <= metaint) return null
    val len = body[metaint].toInt().and(0xFF) * 16
    if (len == 0) return null
    val end = minOf(body.size, metaint + 1 + len)
    val block = String(body, metaint + 1, end - (metaint + 1), Charsets.UTF_8)
    return Regex("StreamTitle='([^']*)'").find(block)?.groupValues?.get(1)?.trim()?.ifBlank { null }
}

// "임재현 - Heaven (2023)" → "임재현 Heaven" 처럼 검색에 방해되는 괄호/연도/구분자를 뗀다.
internal fun cleanTrackQuery(streamTitle: String): String =
    streamTitle.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(" - ", " ").replace(Regex("\\s+"), " ").trim()

// 비교용 정규화: 소문자, 괄호 내용 제거, 영숫자·한글만 남김. "K. will" == "K.Will", "Heaven (2023)" == "Heaven".
internal fun normalizeForMatch(s: String): String =
    s.lowercase().replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").replace(Regex("[^0-9a-z가-힣]"), "")

// Deezer 검색은 엉뚱한 곡을 1순위로 주기도 한다("임재현 Heaven" → 다른 가수의 "Your presence is heaven").
// 틀린 커버는 회색보다 나쁘므로 제목이 정규화 후 같고 아티스트가 서로를 포함할 때만 채택한다.
internal fun deezerMatches(streamTitle: String, deezerArtist: String, deezerTitle: String): Boolean {
    val parts = streamTitle.split(" - ", limit = 2)
    if (parts.size != 2) return false
    val (artist, title) = parts.map { normalizeForMatch(it) }
    val dArtist = normalizeForMatch(deezerArtist)
    val dTitle = normalizeForMatch(deezerTitle)
    if (title.isEmpty() || artist.isEmpty() || dArtist.isEmpty() || dTitle != title) return false
    return dArtist.contains(artist) || artist.contains(dArtist)
}

// Deezer /search 응답에서 첫 곡이 요청 곡과 같을 때만 앨범 커버(큰 사이즈)를 돌려준다.
internal fun parseDeezerCover(body: String, streamTitle: String): String? = try {
    val data = JSONObject(body).optJSONArray("data") ?: return null
    if (data.length() == 0) null
    else {
        val first = data.getJSONObject(0)
        val artist = first.optJSONObject("artist")?.optString("name").orEmpty()
        if (!deezerMatches(streamTitle, artist, first.optString("title"))) null
        else first.optJSONObject("album")?.let { a ->
            a.optString("cover_xl").ifBlank { null } ?: a.optString("cover_big").ifBlank { null }
        }
    }
} catch (e: Exception) { null }
