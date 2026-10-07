package com.arms.androidauto.local

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "ARMS"

// 와이파이(또는 유선) IPv4 주소. 상태 JSON의 아트 URL과 화면 표시에 쓴다.
object LanAddress {
    @Suppress("DEPRECATION")
    fun ipv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            for (n in networks) {
                val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
                ipv4Of(lp)?.let { return it }
            }
        }
        // 대체: 인터페이스 이름으로(wlan0·eth0)
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun ipv4Of(lp: LinkProperties): String? =
        lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
}

// mDNS 광고: _simpleradio._tcp, TXT id·v·port·name. 등록 리스너는 한 번만 쓸 수 있어 등록마다 새로 만든다.
class LocalNsdAdvertiser(context: Context, private val serviceName: String, private val txt: Map<String, String>, private val port: Int) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register() {
        if (nsd == null || listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = this@LocalNsdAdvertiser.serviceName
            serviceType = SERVICE_TYPE
            port = this@LocalNsdAdvertiser.port
            txt.forEach { (k, v) -> runCatching { setAttribute(k, v) } }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "로컬 제어: mDNS 등록 (${info.serviceName})")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "로컬 제어: mDNS 등록 실패 ($errorCode)")
                if (listener === this) listener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "로컬 제어: mDNS 등록 해제")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "로컬 제어: mDNS 해제 실패 ($errorCode)")
            }
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
            Log.w(TAG, "로컬 제어: mDNS 등록 예외 ${e.javaClass.simpleName}")
        }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(l) }
    }

    companion object {
        const val SERVICE_TYPE = "_simpleradio._tcp"
    }
}

// 호스트 서비스 안에서 로컬 제어 서버·mDNS·멀티캐스트 락·네트워크 감시를 묶어 켜고 끈다(main 스레드에서 호출).
// - 포트가 아직 안 풀렸으면(서비스 재시작 직후 등) 2→5→10→30s 간격으로 다시 연다.
// - 네트워크(와이파이)가 바뀌면 주소를 다시 읽고 mDNS를 다시 등록한다.
// - 멀티캐스트 락: 화면이 꺼진 태블릿은 와이파이 칩이 멀티캐스트를 걸러 mDNS 질의를 못 받을 수 있다.
//   벽걸이(상시 충전) 기기라 켜져 있는 동안 계속 잡는다.
class LocalControlHost(
    private val context: Context,
    private val backend: LocalBackend,
    private val scope: CoroutineScope,
    private val deviceId: String,
) {
    private var job: Job? = null
    private var server: LocalControlServer? = null
    private var nsd: LocalNsdAdvertiser? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkJob: Job? = null
    @Volatile private var address: String? = null

    val isStarted: Boolean get() = job != null

    // "http://192.168.0.12:8765" (모르면 null)
    fun baseUrl(): String? = address?.let { "http://$it:${LocalControl.PORT}" }

    fun start() {
        if (job != null) return
        val auth = LocalControl.init(context)
        LocalControl.setServerStatus(LocalServerStatus.Starting)
        address = LanAddress.ipv4(context)
        acquireMulticastLock()
        registerNetworkCallback()
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val srv = LocalControlServer(backend, auth, LocalControl.pairingWindow, log = { Log.i(TAG, "로컬 제어: $it") })
                val result = withContext(Dispatchers.IO) { runCatching { srv.start() } }
                if (result.isSuccess) {
                    server = srv
                    break
                }
                attempt++
                val wait = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.size - 1)]
                Log.w(TAG, "로컬 제어: 포트 ${LocalControl.PORT} 열기 실패 ${result.exceptionOrNull()?.javaClass?.simpleName} — ${wait / 1000}s 후 재시도")
                LocalControl.setServerStatus(LocalServerStatus.Failed("포트 ${LocalControl.PORT}를 열 수 없습니다. 다시 시도하는 중…"))
                delay(wait)
            }
            Log.i(TAG, "로컬 제어: 서버 시작 (포트 ${LocalControl.PORT})")
            val name = LocalControl.displayName()
            nsd = LocalNsdAdvertiser(
                context, name,
                mapOf("id" to deviceId, "v" to "1", "port" to LocalControl.PORT.toString(), "name" to name),
                LocalControl.PORT,
            ).also { it.register() }
            publishStatus()
        }
    }

    fun stop() {
        val j = job ?: return
        job = null
        j.cancel()
        networkJob?.cancel()
        networkJob = null
        networkCallback?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        nsd?.unregister()
        nsd = null
        server?.stop()
        server = null
        multicastLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
        multicastLock = null
        LocalControl.setServerStatus(LocalServerStatus.Stopped)
        Log.i(TAG, "로컬 제어: 서버 종료")
    }

    private fun publishStatus() {
        if (server == null) return
        LocalControl.setServerStatus(LocalServerStatus.Running(address?.let { "$it:${LocalControl.PORT}" }))
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wm.createMulticastLock("simpleradio-mdns").apply { setReferenceCounted(false) }
            lock.acquire()
            multicastLock = lock
        }.onFailure { Log.w(TAG, "로컬 제어: 멀티캐스트 락 실패 ${it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "로컬 제어: 네트워크 콜백 등록 실패 ${e.javaClass.simpleName}")
        }
    }

    // 콜백은 연달아 온다(등록 직후 1회 포함) → 1.5초 모아서 한 번. 주소가 바뀌었거나 새 네트워크면 mDNS 재등록.
    private fun onNetworkChanged() {
        scope.launch {
            networkJob?.cancel()
            networkJob = launch {
                delay(NETWORK_SETTLE_MS)
                val next = LanAddress.ipv4(context)
                val changed = next != address
                address = next
                publishStatus()
                if (changed && server != null) {
                    Log.i(TAG, "로컬 제어: 네트워크 변경 → mDNS 다시 등록")
                    nsd?.unregister()
                    delay(NSD_REREGISTER_GAP_MS)
                    if (server != null && next != null) nsd?.register()
                }
            }
        }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L)
        const val NETWORK_SETTLE_MS = 1_500L
        const val NSD_REREGISTER_GAP_MS = 500L
    }
}
