package com.arms.androidauto.remote.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException

// 블루투스 출력 재연결 "탐침"(플랜 §4). 앱이 A2DP 연결을 직접 명령하는 공개 API는 없으므로
// 가능한 우회를 순서대로 시도하고, 무엇이 됐고 무엇이 막혔는지 정확히 보고한다.
// 실기(Galaxy Tab S7 FE, Android 14)에서 결과를 보고 기능 범위를 정하기 위한 것이라
// 어떤 경우에도 예외를 밖으로 내지 않는다. 로그에는 기기 주소를 남기지 않는다.
//
// 순서:
//  (a) API 30+: MediaRouter2 — FEATURE_LIVE_AUDIO 탐색 → 블루투스 라우트(TYPE_BLUETOOTH_A2DP 또는
//      마지막 기기 이름 일치)를 찾아 transferTo. 페어링만 되고 미연결인 기기가 목록에 뜨는지가 관건.
//  (b) 리플렉션: BluetoothA2dp.connect(BluetoothDevice) (숨김 API). SecurityException → 권한 없음,
//      NoSuchMethodException(비-SDK 차단) → 미지원.
//  각 시도 후 감시기(BluetoothOutputMonitor)가 "연결됨"을 보고할 때까지 최대 8초 기다린다.
class BluetoothReconnector(
    context: Context,
    private val monitor: BluetoothOutputMonitor,
    private val confirmTimeoutMs: Long = 8_000L,
    private val routeDiscoveryMs: Long = 3_000L,
) {
    private val appContext = context.applicationContext

    sealed class ReconnectResult(val code: String) {
        object AlreadyConnected : ReconnectResult("ALREADY_CONNECTED")
        // method: "MediaRouter2" | "BluetoothA2dp.connect". confirmed: 제한 시간 안에 감시기가 연결을 확인했는지.
        data class ConnectRequested(val method: String, val confirmed: Boolean, val detail: String? = null) :
            ReconnectResult("CONNECT_REQUESTED")
        object NoKnownDevice : ReconnectResult("NO_KNOWN_DEVICE")
        object Unsupported : ReconnectResult("UNSUPPORTED")
        object PermissionDenied : ReconnectResult("PERMISSION_DENIED")
        data class Failed(val reason: String) : ReconnectResult("FAILED")

        // 게스트 ack / 로그에 그대로 실어 보낼 한 줄 설명(주소·비밀 없음).
        fun describe(): String = when (this) {
            AlreadyConnected -> "이미 연결되어 있음"
            is ConnectRequested -> buildString {
                append(if (confirmed) "재연결됨" else "재연결 요청함(8초 내 미확인)")
                append(" · $method")
                detail?.let { append(" · $it") }
            }
            NoKnownDevice -> "기억된 블루투스 기기가 없음"
            Unsupported -> "이 기기에서는 앱이 블루투스 재연결을 명령할 수 없음"
            PermissionDenied -> "블루투스 연결 권한이 없음"
            is Failed -> "재연결 실패: $reason"
        }

        val isSuccess: Boolean get() = this is AlreadyConnected || (this is ConnectRequested && confirmed)
    }

    suspend fun reconnect(): ReconnectResult = try {
        withContext(Dispatchers.Main.immediate) { reconnectInternal() }
    } catch (t: Throwable) {
        Log.w("ARMS", "BT 재연결: 예기치 못한 오류 ${t.javaClass.simpleName}")
        ReconnectResult.Failed(t.javaClass.simpleName)
    }.also { Log.i("ARMS", "BT 재연결 결과: ${it.code} — ${it.describe()}") }

    private suspend fun reconnectInternal(): ReconnectResult {
        if (monitor.status.value.connected) return ReconnectResult.AlreadyConnected
        if (!monitor.isBluetoothAvailable()) return ReconnectResult.Unsupported
        val target = monitor.lastKnownDevice() ?: return ReconnectResult.NoKnownDevice
        Log.i("ARMS", "BT 재연결 시작 (대상 이름 ${if (target.name != null) "있음" else "없음"})")

        // (a) MediaRouter2
        var routerAttempt: ReconnectResult.ConnectRequested? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val transferred = try {
                tryMediaRouter2(target)
            } catch (t: Throwable) {
                Log.w("ARMS", "BT 재연결 (a) MediaRouter2 예외 ${t.javaClass.simpleName}")
                false
            }
            if (transferred) {
                val confirmed = awaitConnected()
                Log.i("ARMS", "BT 재연결 (a) MediaRouter2 transferTo 요청 → ${if (confirmed) "연결 확인" else "미확인"}")
                if (confirmed) return ReconnectResult.ConnectRequested("MediaRouter2", confirmed = true)
                routerAttempt = ReconnectResult.ConnectRequested("MediaRouter2", confirmed = false)
            }
        } else {
            Log.i("ARMS", "BT 재연결 (a) MediaRouter2 건너뜀 (API ${Build.VERSION.SDK_INT})")
        }

        // (b) 리플렉션
        val reflective = tryReflectiveConnect(target)
        if (reflective != null) {
            Log.i("ARMS", "BT 재연결 (b) BluetoothA2dp.connect 실패: ${reflective.code}")
            return routerAttempt?.copy(detail = "리플렉션 ${reflective.code}") ?: reflective
        }
        val confirmed = awaitConnected()
        Log.i("ARMS", "BT 재연결 (b) BluetoothA2dp.connect 요청 → ${if (confirmed) "연결 확인" else "미확인"}")
        return ReconnectResult.ConnectRequested(
            "BluetoothA2dp.connect", confirmed,
            detail = routerAttempt?.let { "MediaRouter2도 요청했으나 미확인" },
        )
    }

    private suspend fun awaitConnected(): Boolean {
        monitor.refresh()
        return withTimeoutOrNull(confirmTimeoutMs) { monitor.status.first { it.connected } } != null
    }

    // 라우트를 찾아 transferTo까지 했으면 true. 라우트가 없으면 false.
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun tryMediaRouter2(target: BluetoothOutputMonitor.KnownDevice): Boolean {
        val router = MediaRouter2.getInstance(appContext)
        val preference = RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), true).build()
        val callback = object : MediaRouter2.RouteCallback() {}
        router.registerRouteCallback(ContextCompat.getMainExecutor(appContext), callback, preference)
        try {
            val deadline = System.currentTimeMillis() + routeDiscoveryMs
            var found: MediaRoute2Info? = null
            while (found == null) {
                found = pickBluetoothRoute(router.routes, target)
                if (found != null || System.currentTimeMillis() >= deadline) break
                delay(300)
            }
            val routes = router.routes
            Log.i(
                "ARMS",
                "BT 재연결 (a) MediaRouter2 라우트 ${routes.size}개" +
                    routes.joinToString(prefix = " [", postfix = "]") { describeRoute(it) } +
                    ", 대상 ${if (found != null) "찾음" else "없음"}",
            )
            if (found == null) return false
            router.transferTo(found)
            // 전환이 비동기라 잠깐 콜백을 유지한 채 감시기 확인을 기다린다(호출부 awaitConnected).
            delay(500)
            return true
        } finally {
            runCatching { router.unregisterRouteCallback(callback) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun pickBluetoothRoute(routes: List<MediaRoute2Info>, target: BluetoothOutputMonitor.KnownDevice): MediaRoute2Info? {
        val byName = target.name?.let { name -> routes.firstOrNull { it.name.toString().equals(name, ignoreCase = true) } }
        if (byName != null) return byName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return routes.firstOrNull { it.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeRoute(route: MediaRoute2Info): String {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "type=${route.type}" else "type=?"
        return "${route.name}($type, sys=${route.isSystemRoute})"
    }

    // null이면 "요청은 나갔다". 아니면 그 결과가 최종.
    @SuppressLint("MissingPermission")
    private fun tryReflectiveConnect(target: BluetoothOutputMonitor.KnownDevice): ReconnectResult? {
        if (!monitor.hasConnectPermission()) return ReconnectResult.PermissionDenied
        val proxy = monitor.a2dpProxy() ?: return ReconnectResult.Failed("A2DP 프록시 없음")
        val adapter = monitor.adapter() ?: return ReconnectResult.Unsupported
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(target.address)
        } catch (e: IllegalArgumentException) {
            return ReconnectResult.Failed("저장된 기기 주소 형식 오류")
        }
        val method = try {
            BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
        } catch (e: NoSuchMethodException) {
            return ReconnectResult.Unsupported
        } catch (e: SecurityException) {
            return ReconnectResult.PermissionDenied
        } catch (t: Throwable) {
            return ReconnectResult.Failed("connect 메서드 조회 ${t.javaClass.simpleName}")
        }
        val result = try {
            method.invoke(proxy, device)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            return if (cause is SecurityException) ReconnectResult.PermissionDenied
            else ReconnectResult.Failed("connect 호출 ${cause?.javaClass?.simpleName ?: "InvocationTargetException"}")
        } catch (e: SecurityException) {
            return ReconnectResult.PermissionDenied
        } catch (e: IllegalAccessException) {
            return ReconnectResult.Unsupported
        } catch (t: Throwable) {
            return ReconnectResult.Failed("connect 호출 ${t.javaClass.simpleName}")
        }
        if (result == false) return ReconnectResult.Failed("connect()가 false 반환")
        return null
    }
}
