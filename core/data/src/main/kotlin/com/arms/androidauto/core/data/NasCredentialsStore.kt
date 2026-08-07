package com.arms.androidauto.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arms.androidauto.core.network.SynologyCredentials

// Synology NAS 계정 정보는 이 앱에서 처음으로 저장하는 사용자 인증정보라, 평문 SharedPreferences가
// 아니라 Android Keystore 기반 EncryptedSharedPreferences로 암호화해서 저장한다.
class NasCredentialsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nas_credentials_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(credentials: SynologyCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, credentials.baseUrl)
            .putString(KEY_ACCOUNT, credentials.account)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
    }

    fun get(): SynologyCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return SynologyCredentials(baseUrl, account, password)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // 로그인 세션(sid)도 함께 보관한다. 앱을 껐다 켤 때마다 새로 로그인하면 NAS가 매번
    // "새 로그인"으로 감지해 알림을 보내기 때문에, 살아있는 세션은 재사용한다.
    // sid는 그 자체로 계정 권한을 갖는 토큰이라 비밀번호와 같은 암호화 저장소에 둔다.
    fun saveSid(sid: String) {
        prefs.edit().putString(KEY_SID, sid).apply()
    }

    fun getSid(): String? = prefs.getString(KEY_SID, null)

    fun clearSid() {
        prefs.edit().remove(KEY_SID).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SID = "sid"
    }
}
