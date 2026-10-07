package com.arms.androidauto.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Collections

// 서버를 임의 포트(루프백)로 띄우고 HttpURLConnection으로 계약(LAN_API.md)을 확인한다. Android 없음.
class LocalControlServerTest {

    private class FakeBackend : LocalBackend {
        val flow = MutableStateFlow(
            LocalState(
                power = LocalState.Power.ON, playback = LocalState.Playback.PLAYING, mediaId = "1",
                title = "KBS Cool FM - 볼륨을 높여요", artist = "볼륨을 높여요", albumArt = ArtRef.Station("1"),
                volume = 40, muted = false, presets = listOf(LocalPreset("1", "KBS Cool FM", true)), updatedAtMs = 1L,
            ),
        )
        val executed: MutableList<LocalCommand> = Collections.synchronizedList(mutableListOf())
        override val states: StateFlow<LocalState> = flow
        override suspend fun currentState(): LocalState = flow.value
        override fun deviceInfo() = DeviceInfo("uuid-1", "Simple Radio Test", "Test", "0.8.2")
        override suspend fun execute(command: LocalCommand): CommandOutcome {
            if (command is LocalCommand.PlayPreset && command.id != "1") return CommandOutcome.badRequest("unknown preset")
            executed += command
            return CommandOutcome.OK
        }
        override fun lanBaseUrl(): String = "http://10.0.0.5:8765"
        override fun stationArtPng(stationId: String): ByteArray? = if (stationId == "1") PNG else null
    }

    private class MemStore : PairedClientStore {
        var list: List<PairedClient> = emptyList()
        override fun load() = list
        override fun save(clients: List<PairedClient>) { list = clients }
    }

    private data class Resp(val code: Int, val body: String, val contentType: String?)

    private var now = 0L
    private val backend = FakeBackend()
    private val auth = LocalAuth(MemStore())
    private val window = PairingWindow(clock = { now })
    private lateinit var server: LocalControlServer
    private var port = 0

    @Before
    fun setUp() {
        server = LocalControlServer(
            backend, auth, window,
            LocalControlServer.Config(port = 0, bindAddress = InetAddress.getLoopbackAddress(), pingIntervalMs = 300L),
        )
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun url(path: String) = URL("http://127.0.0.1:$port$path")

    private fun http(method: String, path: String, body: String? = null, token: String? = null, chunked: Boolean = false): Resp {
        val c = url(path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3_000
        c.readTimeout = 5_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (chunked) c.setChunkedStreamingMode(4)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val type = c.contentType
        c.disconnect()
        return Resp(code, text, type)
    }

    private fun pair(label: String = "거실 허브"): String {
        window.open()
        val r = http("POST", "/api/v1/pair", """{"client":"smartthings-edge","label":"$label"}""")
        assertEquals(200, r.code)
        return JSONObject(r.body).getString("token")
    }

    @Test
    fun infoIsPublicAndReportsPairingWindow() {
        val r = http("GET", "/api/v1/info")
        assertEquals(200, r.code)
        assertTrue(r.contentType!!.startsWith("application/json"))
        val j = JSONObject(r.body)
        assertEquals("uuid-1", j.getString("id"))
        assertEquals(1, j.getInt("apiVersion"))
        assertFalse(j.getBoolean("pairingOpen"))
        window.open()
        assertTrue(JSONObject(http("GET", "/api/v1/info").body).getBoolean("pairingOpen"))
    }

    @Test
    fun pairIsForbiddenWhenClosedAndOneShotWhenOpen() {
        val closed = http("POST", "/api/v1/pair", """{"client":"smartthings-edge","label":"x"}""")
        assertEquals(403, closed.code)
        assertEquals("pairing_closed", JSONObject(closed.body).getString("error"))

        window.open()
        val ok = http("POST", "/api/v1/pair", """{"client":"smartthings-edge","label":"거실 허브"}""", chunked = true)
        assertEquals(200, ok.code)
        val token = JSONObject(ok.body).getString("token")
        assertEquals(43, token.length)
        assertEquals("거실 허브", auth.clients.value.single().label)

        // 첫 페어링 뒤 창은 닫힌다
        assertFalse(window.isOpen())
        assertEquals(403, http("POST", "/api/v1/pair", """{"client":"x"}""").code)
    }

    @Test
    fun pairWithBadJsonIs400AndKeepsWindowOpen() {
        window.open()
        assertEquals(400, http("POST", "/api/v1/pair", "{nope").code)
        assertTrue(window.isOpen())
    }

    @Test
    fun stateRequiresToken() {
        val noAuth = http("GET", "/api/v1/state")
        assertEquals(401, noAuth.code)
        assertEquals("unauthorized", JSONObject(noAuth.body).getString("error"))
        assertEquals(401, http("GET", "/api/v1/state", token = "wrong").code)

        val token = pair()
        val r = http("GET", "/api/v1/state", token = token)
        assertEquals(200, r.code)
        val j = JSONObject(r.body)
        assertEquals("on", j.getString("power"))
        assertEquals("playing", j.getString("playback"))
        assertEquals("http://10.0.0.5:8765/art/station/1.png", j.getString("albumArtUrl"))
        assertEquals(40, j.getInt("volume"))
    }

    @Test
    fun commandsExecuteAndValidate() {
        val token = pair()
        val ok = http("POST", "/api/v1/command", """{"command":"play"}""", token)
        assertEquals(200, ok.code)
        assertTrue(JSONObject(ok.body).getBoolean("ok"))
        assertEquals(200, http("POST", "/api/v1/command", """{"command":"setVolume","value":55}""", token).code)
        assertEquals(listOf(LocalCommand.Play, LocalCommand.SetVolume(55)), backend.executed.toList())

        val unknown = http("POST", "/api/v1/command", """{"command":"explode"}""", token)
        assertEquals(400, unknown.code)
        assertFalse(JSONObject(unknown.body).getBoolean("ok"))
        assertTrue(JSONObject(unknown.body).getString("message").isNotEmpty())
        assertEquals(400, http("POST", "/api/v1/command", """{"command":"setVolume","value":101}""", token).code)
        assertEquals(400, http("POST", "/api/v1/command", """{"command":"playPreset","value":"99"}""", token).code)
        assertEquals(401, http("POST", "/api/v1/command", """{"command":"play"}""").code)
        assertEquals(405, http("GET", "/api/v1/command", token = token).code)
        assertEquals(2, backend.executed.size)
    }

    @Test
    fun stationArtAndNotFound() {
        val c = url("/art/station/1.png").openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("image/png", c.contentType)
        assertArrayEquals(PNG, c.inputStream.use { it.readBytes() })
        assertEquals(404, http("GET", "/art/station/9.png").code)
        assertEquals(404, http("GET", "/art/station/..%2F1.png").code)
        assertEquals(404, http("GET", "/nope").code)
    }

    private fun openEvents(token: String): Pair<HttpURLConnection, BufferedReader> {
        val c = url("/api/v1/events").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "text/event-stream")
        c.readTimeout = 5_000
        assertEquals(200, c.responseCode)
        assertEquals("text/event-stream", c.contentType)
        assertEquals("no-cache", c.getHeaderField("Cache-Control"))
        return c to c.inputStream.bufferedReader(Charsets.UTF_8)
    }

    // 다음 "event: state" 프레임의 data JSON(ping은 건너뛰되 pings에 센다).
    private fun nextStateEvent(r: BufferedReader, pings: IntArray? = null): JSONObject {
        while (true) {
            val line = r.readLine() ?: error("stream closed")
            if (line == ": ping") { pings?.let { it[0]++ }; continue }
            if (line == "event: state") {
                val data = r.readLine()
                assertTrue(data.startsWith("data: "))
                assertEquals("", r.readLine())
                return JSONObject(data.removePrefix("data: "))
            }
        }
    }

    @Test
    fun eventsSendCurrentStateThenChangesAndPings() {
        val token = pair()
        assertEquals(401, http("GET", "/api/v1/events").code)
        val (conn, reader) = openEvents(token)
        try {
            val first = nextStateEvent(reader)
            assertEquals("playing", first.getString("playback"))

            // updatedAtMs만 바뀐 상태는 보내지 않고, 내용이 바뀐 상태는 보낸다.
            backend.flow.value = backend.flow.value.copy(updatedAtMs = 2L)
            backend.flow.value = backend.flow.value.copy(playback = LocalState.Playback.PAUSED, power = LocalState.Power.OFF, updatedAtMs = 3L)
            val pings = IntArray(1)
            val second = nextStateEvent(reader, pings)
            assertEquals("paused", second.getString("playback"))
            assertEquals(3L, second.getLong("updatedAtMs"))

            // ping(테스트는 300ms 간격)
            var line: String?
            do { line = reader.readLine() } while (line != null && line != ": ping")
            assertEquals(": ping", line)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun severalSseClientsAndCleanupOnDisconnect() {
        val token = pair()
        val a = openEvents(token)
        val b = openEvents(token)
        nextStateEvent(a.second)
        nextStateEvent(b.second)
        waitUntil { server.sseClientCount == 2 }
        backend.flow.value = backend.flow.value.copy(volume = 10, updatedAtMs = 9L)
        assertEquals(10, nextStateEvent(a.second).getInt("volume"))
        assertEquals(10, nextStateEvent(b.second).getInt("volume"))
        a.first.disconnect()
        waitUntil { server.sseClientCount == 1 }
        b.first.disconnect()
        waitUntil { server.sseClientCount == 0 }
    }

    @Test
    fun revokeClosesStreamAndToken() {
        val token = pair()
        val (conn, reader) = openEvents(token)
        nextStateEvent(reader)
        val id = auth.clients.value.single().id
        assertTrue(auth.revoke(id))
        // 서버가 소켓을 닫는다 → EOF
        var line: String? = ""
        while (line != null) line = runCatching { reader.readLine() }.getOrNull()
        assertNull(line)
        conn.disconnect()
        assertEquals(401, http("GET", "/api/v1/state", token = token).code)
    }

    @Test
    fun oversizeAndGarbageRequestsAreRejected() {
        val token = pair()
        val big = "{\"command\":\"play\",\"pad\":\"" + "a".repeat(20_000) + "\"}"
        val r = runCatching { http("POST", "/api/v1/command", big, token) }.getOrNull()
        // 서버가 413을 보내고 닫는다(클라이언트가 아직 쓰는 중이면 연결 리셋으로 보일 수도 있다)
        if (r != null) assertEquals(413, r.code)

        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write("HELLO\r\n\r\n".toByteArray())
            val resp = s.getInputStream().bufferedReader().readLine()
            assertNotNull(resp)
            assertTrue(resp, resp.startsWith("HTTP/1.1 400"))
        }
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("condition not met in ${timeoutMs}ms")
            Thread.sleep(20)
        }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    }
}
