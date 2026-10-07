package com.arms.androidauto.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// 토큰: 무작위 32바이트 base64url(패딩 없음). 저장소에는 SHA-256(hex)만 둔다 — 원문은 /pair 응답 한 번뿐.
object TokenCodec {
    const val TOKEN_BYTES = 32

    fun newToken(random: SecureRandom): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // 상수 시간 비교(해시끼리).
    fun matches(token: String, storedHash: String): Boolean =
        MessageDigest.isEqual(hash(token).toByteArray(Charsets.US_ASCII), storedHash.lowercase().toByteArray(Charsets.US_ASCII))

    // "Authorization: Bearer <token>" → token. 스킴은 대소문자 무시.
    fun bearer(authorization: String?): String? {
        val v = authorization?.trim() ?: return null
        if (v.length <= 7 || !v.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return v.substring(7).trim().takeIf { it.isNotEmpty() }
    }
}

// 연결된 클라이언트(예: SmartThings Edge 드라이버). tokenHash만 저장한다.
data class PairedClient(
    val id: String,
    val label: String,
    val client: String,
    val createdAtMs: Long,
    val tokenHash: String,
)

interface PairedClientStore {
    fun load(): List<PairedClient>
    fun save(clients: List<PairedClient>)
}

object PairedClientCodec {
    fun encode(clients: List<PairedClient>): String {
        val arr = JSONArray()
        clients.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("client", c.client)
                    .put("createdAtMs", c.createdAtMs)
                    .put("hash", c.tokenHash),
            )
        }
        return arr.toString()
    }

    fun decode(text: String?): List<PairedClient> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val hash = o.optString("hash").takeIf { it.length == 64 } ?: return@mapNotNull null
                PairedClient(id, o.optString("label"), o.optString("client"), o.optLong("createdAtMs"), hash)
            }
        }.getOrDefault(emptyList())
    }
}

// 토큰 발급·검증·해제. 스레드 안전(서버 IO 스레드와 UI가 같이 부른다).
class LocalAuth(
    private val store: PairedClientStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxClients: Int = MAX_CLIENTS,
    // 화면이 서버보다 먼저 구독할 수 있게 바깥(LocalControl)의 흐름을 넘겨받을 수 있다.
    clientsFlow: MutableStateFlow<List<PairedClient>> = MutableStateFlow(emptyList()),
) {
    private val _clients = clientsFlow.also { it.value = store.load() }
    val clients: StateFlow<List<PairedClient>> = _clients.asStateFlow()

    // 새 토큰을 만들고 해시만 저장한 뒤 원문을 돌려준다. 한도를 넘으면 가장 오래된 것부터 지운다.
    @Synchronized
    fun issue(client: String, label: String): Pair<PairedClient, String> {
        val token = TokenCodec.newToken(random)
        val entry = PairedClient(
            id = newId(),
            label = sanitize(label).ifEmpty { "SmartThings" },
            client = sanitize(client).ifEmpty { "unknown" },
            createdAtMs = clock(),
            tokenHash = TokenCodec.hash(token),
        )
        val next = (_clients.value + entry).takeLast(maxClients)
        store.save(next)
        _clients.value = next
        return entry to token
    }

    fun verify(token: String?): PairedClient? {
        if (token.isNullOrEmpty() || token.length > 256) return null
        val hash = TokenCodec.hash(token).toByteArray(Charsets.US_ASCII)
        var found: PairedClient? = null
        // 일치해도 끝까지 돈다(대상 수에 따른 시간 차이만 남김).
        for (c in _clients.value) {
            if (MessageDigest.isEqual(hash, c.tokenHash.toByteArray(Charsets.US_ASCII)) && found == null) found = c
        }
        return found
    }

    @Synchronized
    fun revoke(clientId: String): Boolean {
        val cur = _clients.value
        val next = cur.filterNot { it.id == clientId }
        if (next.size == cur.size) return false
        store.save(next)
        _clients.value = next
        return true
    }

    private fun newId(): String {
        val b = ByteArray(6)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sanitize(s: String): String = s.filter { !it.isISOControl() }.trim().take(MAX_LABEL)

    companion object {
        const val MAX_CLIENTS = 8
        const val MAX_LABEL = 64
    }
}

// "연결 허용" 창. 열린 동안(기본 10분)만 /pair가 토큰을 발급하고, 첫 발급 뒤 바로 닫힌다.
// clock은 단조 시계(Android: SystemClock.elapsedRealtime) — 벽시계 변경에 흔들리지 않게.
// wallClock은 게스트(폰)에게 보낼 마감 시각(epoch ms)용. 열 때 한 번만 계산해 두므로 상태 지문이 흔들리지 않는다.
class PairingWindow(
    private val clock: () -> Long,
    val durationMs: Long = DEFAULT_DURATION_MS,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val _deadline = MutableStateFlow<Long?>(null)
    // 열려 있으면 닫히는 시각(clock 기준), 닫혀 있으면 null. 만료는 isOpen/remainingMs를 부를 때 반영된다.
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()
    private var wallDeadline: Long? = null

    @Synchronized
    fun open(): Long {
        val until = clock() + durationMs
        wallDeadline = wallClock() + durationMs
        _deadline.value = until
        return until
    }

    @Synchronized
    fun close() {
        wallDeadline = null
        _deadline.value = null
    }

    // 열려 있으면 닫히는 시각(epoch ms), 닫혀 있거나 만료됐으면 null. MQTT HostState.stPairingOpenUntilMs에 그대로 싣는다.
    @Synchronized
    fun openUntilWallMs(): Long? = if (isOpen()) wallDeadline else null

    @Synchronized
    fun isOpen(): Boolean = remainingMs() > 0L

    @Synchronized
    fun remainingMs(): Long {
        val until = _deadline.value ?: return 0L
        val left = until - clock()
        if (left <= 0L) {
            wallDeadline = null
            _deadline.value = null
            return 0L
        }
        return left
    }

    // 열려 있으면 block을 실행하고 창을 닫는다(첫 페어링 한 번만). 닫혀 있으면 null.
    @Synchronized
    fun <T> consume(block: () -> T): T? {
        if (!isOpen()) return null
        val result = block()
        wallDeadline = null
        _deadline.value = null
        return result
    }

    companion object {
        const val DEFAULT_DURATION_MS = 10 * 60_000L
    }
}
