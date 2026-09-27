package com.arms.androidauto.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arms.androidauto.core.remote.Pairing

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
    }

    // 호스트는 자기가 만든 페어링을, 게스트는 스캔한 페어링을 저장한다. QR 문자열 그대로 보관.
    fun getPairing(): Pairing? = prefs.getString(KEY_PAIRING, null)?.let { Pairing.fromQrText(it) }

    fun savePairing(pairing: Pairing) {
        prefs.edit().putString(KEY_PAIRING, pairing.toQrText()).apply()
    }

    fun clearPairing() {
        prefs.edit().remove(KEY_PAIRING).apply()
    }

    // 게스트/호스트가 브로커에 접속할 때 쓰는 클라이언트 ID(기기마다 1회 생성, 비밀 아님).
    fun clientId(): String {
        val existing = prefs.getString(KEY_CLIENT_ID, null)
        if (existing != null) return existing
        val id = "sr-" + java.util.UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_CLIENT_ID, id).apply()
        return id
    }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_PAIRING = "pairing"
        const val KEY_CLIENT_ID = "client_id"
    }
}
