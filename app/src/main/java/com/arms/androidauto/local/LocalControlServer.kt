package com.arms.androidauto.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

// 서버가 앱(호스트 서비스)에 묻는 것. Android 의존은 전부 이 뒤에 둔다(서버는 JVM 테스트에서 그대로 돈다).
interface LocalBackend {
    // 디바운스된 현재 상태. 바뀔 때마다 SSE로 나간다(updatedAtMs만 다르면 보내지 않는다).
    val states: StateFlow<LocalState>

    // 지금 시점의 상태(/state, SSE 첫 이벤트).
    suspend fun currentState(): LocalState

    fun deviceInfo(): DeviceInfo

    // 실행까지 마치고 돌아온다(서버가 5초로 자른다).
    suspend fun execute(command: LocalCommand): CommandOutcome

    // "http://<wlan IPv4>:8765". 모르면 null(그럼 요청의 Host 헤더로 만든다).
    fun lanBaseUrl(): String?

    // 번들 채널 아트 PNG. 없으면 null.
    fun stationArtPng(stationId: String): ByteArray?
}

// 로컬 제어 API v1 HTTP 서버(smartthings/LAN_API.md). java.net.ServerSocket + 코루틴.
// - 요청마다 응답 하나 쓰고 닫는다(keep-alive 없음). SSE만 연결을 유지한다.
// - 요청 16KB·읽기 10초. 동시 연결은 요청 16개·SSE 8개를 따로 센다(ConnectionBudget) — SSE가 요청을 막지 않는다.
// - SSE는 페어링된 클라이언트(토큰)당 하나만: 같은 클라이언트가 /events를 다시 열면 이전 스트림을 닫는다
//   (허브가 재접속할 때 반쯤 죽은 옛 소켓이 남지 않게). 쓰기(상태·ping)가 실패하면 그 자리에서 끊는다.
// - 토큰은 로그에 남기지 않는다.
class LocalControlServer(
    private val backend: LocalBackend,
    private val auth: LocalAuth,
    private val pairing: PairingWindow,
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val port: Int = DEFAULT_PORT,
        // null = 모든 인터페이스
        val bindAddress: InetAddress? = null,
        // 명령·상태·아트 요청의 동시 연결 상한(SSE 제외)
        val maxConnections: Int = 16,
        // SSE 스트림 상한(클라이언트당 1개이므로 사실상 페어링 수 상한)
        val maxSseConnections: Int = 8,
        val maxRequestBytes: Int = HttpRequestParser.DEFAULT_MAX_BYTES,
        val readTimeoutMs: Int = 10_000,
        val pingIntervalMs: Long = 25_000L,
        val commandTimeoutMs: Long = 5_000L,
    )

    // 접속 하나. SSE로 판명되면 clientId가 채워지고 예산이 SSE 슬롯으로 옮겨진다.
    private class Conn(val socket: Socket) {
        @Volatile var clientId: String = ""
        @Volatile var sse: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val budget = ConnectionBudget(config.maxConnections, config.maxSseConnections)
    private val conns = ConcurrentHashMap<Socket, Conn>()
    // 클라이언트 id → 지금 살아 있는 SSE 소켓(하나).
    private val streams = ConcurrentHashMap<String, Socket>()

    val sseClientCount: Int get() = budget.sseCount
    val requestConnectionCount: Int get() = budget.requestCount

    // 포트를 열고 접속을 받기 시작한다. 실제로 묶인 포트를 돌려준다(port=0이면 임의 포트). 실패하면 IOException.
    @Synchronized
    fun start(): Int {
        check(serverSocket == null) { "already started" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(config.bindAddress, config.port), 32)
        } catch (e: IOException) {
            runCatching { ss.close() }
            throw e
        }
        serverSocket = ss
        scope.launch { acceptLoop(ss) }
        // 해제된 클라이언트의 SSE 연결은 바로 끊는다.
        scope.launch {
            auth.clients.collect { list ->
                val ids = list.mapTo(HashSet()) { it.id }
                conns.values.forEach { c -> if (c.clientId.isNotEmpty() && c.clientId !in ids) runCatching { c.socket.close() } }
            }
        }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        runCatching { ss.close() }
        conns.keys.forEach { runCatching { it.close() } }
        conns.clear()
        streams.clear()
        scope.cancel()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (ss.isClosed) return
                log("accept 실패 ${e.javaClass.simpleName}")
                delay(200)
                continue
            }
            if (!budget.tryAcquireRequest()) {
                runCatching {
                    socket.soTimeout = 2_000
                    HttpResponses.json(socket.getOutputStream(), 503, LocalJson.error("busy").toString())
                }
                runCatching { socket.close() }
                continue
            }
            val conn = Conn(socket)
            conns[socket] = conn
            scope.launch {
                try {
                    handle(conn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // 클라이언트가 먼저 끊음·타임아웃: 조용히 닫는다.
                } catch (t: Throwable) {
                    log("요청 처리 오류 ${t.javaClass.simpleName}")
                } finally {
                    conns.remove(socket)
                    runCatching { socket.close() }
                    if (conn.sse) budget.releaseSse() else budget.releaseRequest()
                }
            }
        }
    }

    private suspend fun handle(conn: Conn) {
        val socket = conn.socket
        socket.soTimeout = config.readTimeoutMs
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()
        val req = try {
            HttpRequestParser.parse(input, config.maxRequestBytes) ?: return
        } catch (e: HttpParseException) {
            HttpResponses.json(out, e.status, LocalJson.error(if (e.status == 413) "too_large" else "bad_request").toString())
            return
        } catch (e: SocketTimeoutException) {
            runCatching { HttpResponses.json(out, 408, LocalJson.error("timeout").toString()) }
            return
        }
        route(req, conn, out)
    }

    private suspend fun route(req: HttpRequest, conn: Conn, out: OutputStream) {
        val path = req.path
        when {
            path == "/api/v1/info" -> {
                if (!requireMethod(req, out, "GET")) return
                HttpResponses.json(out, 200, LocalJson.info(backend.deviceInfo(), pairing.isOpen()).toString())
            }
            path == "/api/v1/pair" -> {
                if (!requireMethod(req, out, "POST")) return
                pair(req, out)
            }
            path == "/api/v1/state" -> {
                if (!requireMethod(req, out, "GET")) return
                authorized(req, out) ?: return
                HttpResponses.json(out, 200, LocalJson.state(backend.currentState(), baseUrl(req)).toString())
            }
            path == "/api/v1/command" -> {
                if (!requireMethod(req, out, "POST")) return
                authorized(req, out) ?: return
                command(req, out)
            }
            path == "/api/v1/events" -> {
                if (!requireMethod(req, out, "GET")) return
                val client = authorized(req, out) ?: return
                events(req, conn, out, client)
            }
            path.startsWith(ART_PREFIX) && path.endsWith(".png") -> {
                if (!requireMethod(req, out, "GET", allowHead = true)) return
                val id = path.removePrefix(ART_PREFIX).removeSuffix(".png")
                val png = if (SAFE_ID.matches(id)) backend.stationArtPng(id) else null
                if (png == null) {
                    HttpResponses.json(out, 404, LocalJson.error("not_found").toString())
                } else {
                    HttpResponses.write(
                        out, 200, "image/png", png,
                        listOf("Cache-Control" to "public, max-age=86400"),
                        omitBody = req.method == "HEAD",
                    )
                }
            }
            else -> HttpResponses.json(out, 404, LocalJson.error("not_found").toString())
        }
    }

    private fun requireMethod(req: HttpRequest, out: OutputStream, method: String, allowHead: Boolean = false): Boolean {
        if (req.method == method || (allowHead && req.method == "HEAD")) return true
        HttpResponses.json(out, 405, LocalJson.error("method_not_allowed").toString(), listOf("Allow" to method))
        return false
    }

    private fun authorized(req: HttpRequest, out: OutputStream): PairedClient? {
        val client = auth.verify(TokenCodec.bearer(req.header("authorization")))
        if (client == null) {
            HttpResponses.json(out, 401, LocalJson.error("unauthorized").toString(), listOf("WWW-Authenticate" to "Bearer"))
        }
        return client
    }

    private fun pair(req: HttpRequest, out: OutputStream) {
        if (!pairing.isOpen()) {
            HttpResponses.json(out, 403, LocalJson.error("pairing_closed").toString())
            return
        }
        val text = req.bodyText().trim()
        val obj: JSONObject = if (text.isEmpty()) {
            JSONObject()
        } else {
            try {
                JSONTokener(text).nextValue() as? JSONObject
            } catch (e: JSONException) {
                null
            } ?: run {
                HttpResponses.json(out, 400, LocalJson.error("bad_request").toString())
                return
            }
        }
        val clientName = obj.optString("client", "unknown")
        val label = obj.optString("label", "SmartThings")
        val issued = pairing.consume { auth.issue(clientName, label) }
        if (issued == null) {
            HttpResponses.json(out, 403, LocalJson.error("pairing_closed").toString())
            return
        }
        val (entry, token) = issued
        log("페어링 완료 (${entry.client}, ${entry.label}, id ${entry.id})")
        HttpResponses.json(out, 200, JSONObject().put("token", token).toString())
    }

    private suspend fun command(req: HttpRequest, out: OutputStream) {
        when (val parsed = LocalCommandParser.parse(req.bodyText())) {
            is LocalCommandParser.Result.Invalid ->
                HttpResponses.json(out, 400, LocalJson.commandResult(false, parsed.message).toString())
            is LocalCommandParser.Result.Ok -> {
                val outcome = try {
                    withTimeoutOrNull(config.commandTimeoutMs) { backend.execute(parsed.command) }
                        ?: CommandOutcome(false, "timeout", 504)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    CommandOutcome.failed(t.message ?: t.javaClass.simpleName)
                }
                log("명령 ${parsed.command} → ${outcome.httpStatus}")
                HttpResponses.json(out, outcome.httpStatus, LocalJson.commandResult(outcome.ok, outcome.message).toString())
            }
        }
    }

    // SSE: 헤더 → 현재 상태 1회 → 바뀔 때마다(또는 명령 뒤 강제 push마다) state 이벤트, 25초마다 ": ping".
    // 클라이언트가 끊거나(읽기 EOF) 쓰기가 실패하거나(죽은 소켓) 토큰이 해제되거나 같은 클라이언트가 새 스트림을
    // 열면(소켓 닫힘) 끝난다.
    private suspend fun events(req: HttpRequest, conn: Conn, out: OutputStream, client: PairedClient) {
        val socket = conn.socket
        // 요청 슬롯 → SSE 슬롯. SSE가 꽉 찼으면 503(요청 슬롯은 그대로이므로 finally가 평소처럼 반납한다).
        if (!budget.convertToSse()) {
            log("SSE 상한(${config.maxSseConnections}) → 503 busy")
            HttpResponses.json(out, 503, LocalJson.error("busy").toString())
            return
        }
        conn.sse = true
        conn.clientId = client.id
        socket.soTimeout = 0
        runCatching { socket.keepAlive = true }
        val base = baseUrl(req)
        // 같은 클라이언트의 이전 스트림은 닫는다(허브 재접속 시 옛 소켓이 남지 않게).
        val previous = streams.put(client.id, socket)
        if (previous != null && previous !== socket) {
            log("SSE 교체 (${client.id}) — 이전 스트림 닫음")
            runCatching { previous.close() }
        }
        try {
            writeText(out, SseFormat.HEADERS)
            val streamJob = scope.launch {
                try {
                    stream(out, base)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // 쓰기 실패 = 죽은 소켓. 바로 끊는다(finally에서 닫힘).
                    log("SSE 쓰기 실패 (${client.id}) → 연결 끊음")
                } catch (t: Throwable) {
                    log("SSE 오류 ${t.javaClass.simpleName}")
                }
            }
            // 클라이언트가 연결을 닫으면 read가 -1 → 스트림 종료. 닫히지 않는 한 여기서 기다린다.
            val readerJob = scope.launch {
                try {
                    val inp = socket.getInputStream()
                    val buf = ByteArray(256)
                    while (inp.read(buf) != -1) { /* 무시 */ }
                } catch (e: IOException) {
                    // 소켓 닫힘
                }
                streamJob.cancel()
            }
            try {
                streamJob.join()
            } finally {
                runCatching { socket.close() }
                readerJob.cancel()
            }
        } finally {
            streams.remove(client.id, socket)
        }
    }

    private suspend fun stream(out: OutputStream, base: String?): Unit = coroutineScope {
        val lock = Mutex()
        var last = backend.currentState()
        lock.withLock { writeText(out, SseFormat.event("state", LocalJson.state(last, base).toString())) }
        launch {
            while (true) {
                delay(config.pingIntervalMs)
                // ping 쓰기가 실패하면 IOException이 coroutineScope를 깨뜨려 스트림 전체가 끝난다(죽은 소켓 감지).
                lock.withLock { writeText(out, SseFormat.PING) }
            }
        }
        backend.states.collect { st ->
            if (st.shouldEmitAfter(last)) {
                last = st
                lock.withLock { writeText(out, SseFormat.event("state", LocalJson.state(st, base).toString())) }
            }
        }
    }

    private fun writeText(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // 상태 JSON 속 아트 URL의 앞부분. wlan IPv4를 우선하고, 모르면 클라이언트가 쓴 Host 헤더(검증 후).
    private fun baseUrl(req: HttpRequest): String? {
        backend.lanBaseUrl()?.let { return it }
        val host = req.header("host")?.trim() ?: return null
        if (!SAFE_HOST.matches(host)) return null
        return "http://$host"
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val ART_PREFIX = "/art/station/"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val SAFE_HOST = Regex("^([A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}])(:\\d{1,5})?$")
    }
}
