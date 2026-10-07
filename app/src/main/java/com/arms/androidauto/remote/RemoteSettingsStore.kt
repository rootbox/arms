package com.arms.androidauto.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arms.androidauto.core.remote.Pairing
import kotlinx.coroutines.flow.MutableStateFlow

// 이 기기의 원격 제어 역할. 태블릿=HOST(홈 플레이어), 폰=GUEST(리모컨).
enum class RemoteRole { NONE, HOST, GUEST }

// 역할과 페어링(키 포함)을 보관. 키는 NAS 자격증명과 같은 Keystore 기반 암호화 저장소에만 둔다.
class RemoteSettingsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "remote_control_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getRole(): RemoteRole = prefs.getString(KEY_ROLE, null)?.let { runCatching { RemoteRole.valueOf(it) }.getOrNull() } ?: RemoteRole.NONE

    fun setRole(role: RemoteRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
        roleEvents.value = roleEvents.value + 1
    }

    // 역할이 저장소에서 바뀌었음을 화면에 알리는 카운터. 게스트가 "연결 종료"를 받아 스스로 역할을 NONE으로
    // 바꿔도 MainActivity가 이를 알 길이 없어 리모컨 탭이 남았다(rc4 검증).
    val roleEvents = MutableStateFlow(0)

    // 호스트는 자기가 만든 페어링을, 게스트는 스캔한 페어링을 저장한다. QR 문자열 그대로 보관.
    fun getPairing(): Pairing? = prefs.getString(KEY_PAIRING, null)?.let { Pairing.fromQrText(it) }

    fun savePairing(pairing: Pairing) {
        prefs.edit().putString(KEY_PAIRING, pairing.toQrText()).apply()
        pairingEvents.value = pairingEvents.value + 1
    }

    fun clearPairing() {
        prefs.edit().remove(KEY_PAIRING).apply()
        pairingEvents.value = pairingEvents.value + 1
    }

    // 페어링이 바뀌었음을 이미 떠 있는 화면에 알린다. 리모컨 화면에서 "페어링 다시 하기"로 새 QR을 저장하고
    // 돌아와도 예전(127.0.0.1) 상태가 그대로 남던 문제(2026-10-06 S25 검증).
    val pairingEvents = MutableStateFlow(0)

    // 호스트의 브로커 설정(한 번만 입력). 페어링을 해제해도 남겨 두어 QR을 다시 만들 때 재입력하지 않는다.
    data class BrokerSettings(val url: String, val username: String, val password: String)

    fun getBrokerSettings(): BrokerSettings? {
        val url = prefs.getString(KEY_BROKER_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return BrokerSettings(
            url = url,
            username = prefs.getString(KEY_BROKER_USER, "") ?: "",
            password = prefs.getString(KEY_BROKER_PASS, "") ?: "",
        )
    }

    fun saveBrokerSettings(settings: BrokerSettings) {
        prefs.edit()
            .putString(KEY_BROKER_URL, settings.url.trim())
            .putString(KEY_BROKER_USER, settings.username.trim())
            .putString(KEY_BROKER_PASS, settings.password)
            .apply()
    }

    // 게스트/호스트가 브로커에 접속할 때 쓰는 클라이언트 ID(기기마다 1회 생성, 비밀 아님).
    fun clientId(): String {
        val existing = prefs.getString(KEY_CLIENT_ID, null)
        if (existing != null) return existing
        val id = "sr-" + java.util.UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_CLIENT_ID, id).apply()
        return id
    }

    // ---- 스마트싱스 로컬 제어(로컬 제어 API v1, smartthings/LAN_API.md) ----

    // 같은 와이파이의 SmartThings(Edge 드라이버)에서 이 기기를 조작하게 할지. 기본 켬(호스트일 때만 의미 있음).
    var smartThingsLocalEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_LOCAL_ENABLED, value).apply() }

    // mDNS TXT의 id: 설치 단위로 고정된 UUID(비밀 아님). 앱을 지우면 바뀐다.
    fun localDeviceId(): String {
        val existing = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
        if (existing != null) return existing
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_LOCAL_DEVICE_ID, id).apply()
        return id
    }

    // 연결된 로컬 클라이언트 목록(JSON, 토큰은 SHA-256 해시만). 형식은 local/PairedClientCodec.
    fun getLocalClientsJson(): String? = prefs.getString(KEY_LOCAL_CLIENTS, null)

    fun saveLocalClientsJson(json: String) {
        prefs.edit().putString(KEY_LOCAL_CLIENTS, json).apply()
    }

    private companion object {
        const val KEY_LOCAL_ENABLED = "smartthings_local_enabled"
        const val KEY_LOCAL_DEVICE_ID = "local_device_id"
        const val KEY_LOCAL_CLIENTS = "local_clients"
        const val KEY_ROLE = "role"
        const val KEY_PAIRING = "pairing"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_BROKER_URL = "broker_url"
        const val KEY_BROKER_USER = "broker_user"
        const val KEY_BROKER_PASS = "broker_pass"
    }
}
