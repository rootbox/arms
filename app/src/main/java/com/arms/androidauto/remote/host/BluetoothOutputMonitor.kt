package com.arms.androidauto.remote.host

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.arms.androidauto.core.remote.BluetoothStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// 태블릿의 블루투스 오디오 출력(A2DP) 연결 상태를 StateFlow로 낸다.
//
// - 초기값/재조회: BluetoothAdapter.getProfileProxy(A2DP)의 connectedDevices
// - 변화 감지: BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED (+ ACL 연결/해제는 보조 신호로 재조회)
// - 권한: BLUETOOTH_CONNECT는 API 31+에서만 필요. 없으면 조용히 "연결 안 됨(기기명 없음)"으로 보고한다.
// - 마지막으로 연결됐던 기기(주소·이름)는 평문 SharedPreferences에 남겨 재연결 대상으로 쓴다.
//   (주소는 비밀이 아니지만 로그에는 남기지 않는다.)
class BluetoothOutputMonitor(context: Context) {

    data class KnownDevice(val address: String, val name: String?)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _status = MutableStateFlow(BluetoothStatus(connected = false, deviceName = null, changedAtMs = null))
    val status: StateFlow<BluetoothStatus> = _status

    private var a2dp: BluetoothA2dp? = null
    private var receiver: BroadcastReceiver? = null
    private var started = false

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothAvailable(): Boolean = adapter != null

    // 재연결기(BluetoothReconnector)가 리플렉션 호출에 쓴다. 프록시가 아직 안 붙었으면 null.
    fun a2dpProxy(): BluetoothA2dp? = a2dp

    fun adapter(): BluetoothAdapter? = adapter

    fun lastKnownDevice(): KnownDevice? {
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return null
        return KnownDevice(address, prefs.getString(KEY_LAST_NAME, null))
    }

    fun start() {
        if (started) return
        started = true
        val adapter = adapter
        if (adapter == null) {
            Log.i("ARMS", "BT 감시: 블루투스 어댑터 없음")
            return
        }
        registerReceiver()
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            Log.w("ARMS", "BT 감시: A2DP 프록시 요청 실패 ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        a2dp?.let { proxy -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) } }
        a2dp = null
    }

    // 프록시의 연결 목록을 다시 읽어 상태를 맞춘다. 권한이 없으면 SecurityException → "연결 안 됨".
    @SuppressLint("MissingPermission")
    fun refresh() {
        val proxy = a2dp ?: return
        if (!hasConnectPermission()) {
            update(connected = false, device = null)
            return
        }
        val device = try {
            proxy.connectedDevices.firstOrNull()
        } catch (e: SecurityException) {
            Log.w("ARMS", "BT 감시: 연결 목록 조회 권한 없음")
            null
        } catch (e: Exception) {
            Log.w("ARMS", "BT 감시: 연결 목록 조회 실패 ${e.javaClass.simpleName}")
            null
        }
        update(connected = device != null, device = device)
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = proxy as? BluetoothA2dp
            Log.i("ARMS", "BT 감시: A2DP 프록시 연결")
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = null
            Log.i("ARMS", "BT 감시: A2DP 프록시 끊김")
        }
    }

    private fun registerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> update(connected = true, device = device)
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                // 다른 기기가 아직 붙어 있을 수 있으므로 프록시 기준으로 다시 확인한다.
                                if (a2dp != null) refresh() else update(connected = false, device = null)
                            }
                            else -> Unit // CONNECTING/DISCONNECTING은 무시
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // 시스템만 보낼 수 있는 보호 브로드캐스트라 EXPORTED여도 다른 앱이 흉내 낼 수 없다.
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun update(connected: Boolean, device: BluetoothDevice?) {
        val name = if (connected) safeName(device) ?: lastKnownDevice()?.takeIf { it.address == device?.address }?.name else null
        if (connected && device != null) rememberDevice(device, name)
        val prev = _status.value
        val changed = prev.connected != connected
        val next = BluetoothStatus(
            connected = connected,
            deviceName = name ?: if (connected) prev.deviceName else null,
            changedAtMs = if (changed) System.currentTimeMillis() else prev.changedAtMs,
        )
        if (next != prev) {
            _status.value = next
            Log.i("ARMS", "BT 출력: ${if (connected) "연결됨" else "끊김"} (기기명 ${if (name != null) "있음" else "없음"})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String? {
        if (device == null || !hasConnectPermission()) return null
        return try { device.name?.takeIf { it.isNotBlank() } } catch (e: SecurityException) { null }
    }

    private fun rememberDevice(device: BluetoothDevice, name: String?) {
        val address = try { device.address } catch (e: Exception) { null } ?: return
        prefs.edit().putString(KEY_LAST_ADDRESS, address).apply {
            if (name != null) putString(KEY_LAST_NAME, name)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "remote_host_bt"
        const val KEY_LAST_ADDRESS = "last_address"
        const val KEY_LAST_NAME = "last_name"
    }
}
