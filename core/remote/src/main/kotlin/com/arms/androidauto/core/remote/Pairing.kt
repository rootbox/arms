package com.arms.androidauto.core.remote

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

// 페어링 정보 ↔ QR 문자열. 형식: "SR1." + base64url(패딩 없음, JSON {v,p,k,u,n,w}).
// - v: 형식 버전(1), p: pairId(12 hex), k: 32바이트 키(base64url), u: 브로커 URL, n/w: 브로커 계정.
// 조금이라도 형식이 어긋나면 decode는 null(예외 없음). 키가 들어 있으므로 결과를 로그에 남기지 않는다.
object PairingCodec {
    private const val PREFIX = "SR1."
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val PAIR_ID_BYTES = 6 // → 12 hex chars
    private val allowedKeys = setOf("v", "p", "k", "u", "n", "w")
    private val pairIdPattern = Regex("^[0-9a-f]{12}$")

    private val random = SecureRandom()
    private val b64Enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec: Base64.Decoder = Base64.getUrlDecoder()

    fun generate(brokerUrl: String, username: String, password: String): Pairing {
        require(brokerUrl.isNotBlank()) { "brokerUrl is blank" }
        require(username.isNotBlank()) { "username is blank" }
        require(password.isNotBlank()) { "password is blank" }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val idBytes = ByteArray(PAIR_ID_BYTES).also(random::nextBytes)
        val pairId = idBytes.joinToString("") { "%02x".format(it) }
        return Pairing(pairId = pairId, key = key, brokerUrl = brokerUrl, username = username, password = password)
    }

    fun encode(pairing: Pairing): String {
        require(pairing.key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        val json = JSONObject()
            .put("v", VERSION)
            .put("p", pairing.pairId)
            .put("k", b64Enc.encodeToString(pairing.key))
            .put("u", pairing.brokerUrl)
            .put("n", pairing.username)
            .put("w", pairing.password)
        return PREFIX + b64Enc.encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    fun decode(text: String): Pairing? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.substring(PREFIX.length)
        if (body.isEmpty() || body.contains('=')) return null
        return try {
            val jsonText = String(b64Dec.decode(body), Charsets.UTF_8)
            val json = JSONObject(jsonText)
            if (json.keySet().any { it !in allowedKeys }) return null
            if (json.get("v") != VERSION) return null
            val pairId = json.get("p") as? String ?: return null
            if (!pairIdPattern.matches(pairId)) return null
            val keyText = json.get("k") as? String ?: return null
            val key = b64Dec.decode(keyText)
            if (key.size != KEY_BYTES) return null
            val brokerUrl = json.get("u") as? String ?: return null
            val username = json.get("n") as? String ?: return null
            val password = json.get("w") as? String ?: return null
            if (brokerUrl.isBlank() || username.isBlank() || password.isBlank()) return null
            Pairing(pairId = pairId, key = key, brokerUrl = brokerUrl, username = username, password = password)
        } catch (_: Exception) {
            null
        }
    }
}
