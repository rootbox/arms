package com.arms.androidauto.local

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.remote.host.RemoteHostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 서버의 지금 상태(화면 표시용).
sealed class LocalServerStatus {
    data object Stopped : LocalServerStatus()
    data object Starting : LocalServerStatus()
    // address: "192.168.0.12:8765" (와이파이 주소를 모르면 null)
    data class Running(val address: String?) : LocalServerStatus()
    data class Failed(val reason: String) : LocalServerStatus()
}

// 로컬 제어(스마트싱스 연결)의 프로세스 단위 상태: 켬/끔, 연결 허용 창, 연결된 클라이언트, 서버 상태.
// 서버 자체는 호스트 서비스(RemoteHostService)가 LocalControlHost로 띄운다. 화면은 이 객체만 본다.
object LocalControl {
    const val PORT = LocalControlServer.DEFAULT_PORT

    val pairingWindow = PairingWindow(clock = { SystemClock.elapsedRealtime() })

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _pairedClients = MutableStateFlow<List<PairedClient>>(emptyList())
    val pairedClients: StateFlow<List<PairedClient>> = _pairedClients.asStateFlow()

    private val _serverStatus = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Stopped)
    val serverStatus: StateFlow<LocalServerStatus> = _serverStatus.asStateFlow()

    @Volatile private var auth: LocalAuth? = null
    @Volatile private var store: RemoteSettingsStore? = null

    // 암호화 저장소를 열고 설정·클라이언트 목록을 읽는다(한 번만). 서비스·화면 어디서 먼저 불러도 된다.
    @Synchronized
    fun init(context: Context): LocalAuth {
        auth?.let { return it }
        val s = RemoteSettingsStore(context.applicationContext)
        store = s
        _enabled.value = s.smartThingsLocalEnabled
        val a = LocalAuth(
            store = object : PairedClientStore {
                override fun load(): List<PairedClient> = PairedClientCodec.decode(s.getLocalClientsJson())
                override fun save(clients: List<PairedClient>) = s.saveLocalClientsJson(PairedClientCodec.encode(clients))
            },
            clientsFlow = _pairedClients,
        )
        auth = a
        return a
    }

    // "연결 허용 (3분)": 이 동안만 /pair가 토큰을 발급한다. 첫 발급 뒤 바로 닫힌다. 닫히는 시각(elapsedRealtime)을 돌려준다.
    fun openPairingWindow(): Long = pairingWindow.open()

    fun closePairingWindow() = pairingWindow.close()

    fun pairingRemainingMs(): Long = pairingWindow.remainingMs()

    // 연결 해제: 토큰을 지운다. 그 클라이언트의 SSE 연결은 서버가 바로 끊는다.
    fun revoke(clientId: String): Boolean = auth?.revoke(clientId) ?: false

    // 켜기/끄기. 페어링 없는 호스트는 이 설정이 꺼지면 서비스가 내려간다(syncWithRole).
    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        init(app)
        store?.smartThingsLocalEnabled = on
        _enabled.value = on
        if (!on) pairingWindow.close()
        CoroutineScope(Dispatchers.IO).launch { runCatching { RemoteHostService.syncWithRole(app) } }
    }

    internal fun setServerStatus(status: LocalServerStatus) {
        _serverStatus.value = status
    }

    fun displayName(): String = "Simple Radio ${Build.MODEL}".take(63)
}
