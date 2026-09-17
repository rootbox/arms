package com.arms.androidauto.desktop.storage

import com.arms.androidauto.core.network.SynologyCredentials
import java.io.File
import java.util.Properties

// Phase 2용 파일 기반 구현. OS별 앱 데이터 디렉터리에 저장한다.
// ⚠️ Phase 4 하드닝: macOS Keychain / Windows Credential Manager로 교체 예정.
// (현재는 평문에 가깝다. 안드로이드의 암호화 저장소 수준은 아니다 — 인터페이스로 분리해 둔 이유.)
class FileCredentialStore(dir: File = defaultDir()) : CredentialStore {
    private val file = File(dir.apply { mkdirs() }, "credentials.properties")

    private fun load() = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    private fun store(p: Properties) = file.outputStream().use { p.store(it, "SimpleRadio") }

    override fun get(): SynologyCredentials? {
        val p = load()
        val base = p.getProperty("baseUrl") ?: return null
        val acc = p.getProperty("account") ?: return null
        val pw = p.getProperty("password") ?: return null
        return SynologyCredentials(base, acc, pw)
    }
    override fun save(credentials: SynologyCredentials) {
        val p = load()
        p.setProperty("baseUrl", credentials.baseUrl)
        p.setProperty("account", credentials.account)
        p.setProperty("password", credentials.password)
        store(p)
    }
    override fun clear() { if (file.exists()) file.delete() }
    override fun getSid(): String? = load().getProperty("sid")
    override fun saveSid(sid: String) { store(load().apply { setProperty("sid", sid) }) }
    override fun clearSid() { store(load().apply { remove("sid") }) }

    companion object {
        fun defaultDir(): File {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                os.contains("mac") -> File("$home/Library/Application Support/SimpleRadio")
                os.contains("win") -> File(System.getenv("APPDATA") ?: "$home", "SimpleRadio")
                else -> File("$home/.config/SimpleRadio")
            }
        }
    }
}
