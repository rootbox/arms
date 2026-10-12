package com.arms.androidauto.remote.host

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

// 벽걸이 모드 락(HostPowerPolicy가 "잡아라"일 때만). 화면이 꺼진 태블릿은 CPU suspend·와이파이 절전으로
// 허브→태블릿 새 TCP 연결(명령)이 5초 안에 못 들어올 수 있다(2026-10-12 §1 트리거 추정).
// - PARTIAL_WAKE_LOCK "SimpleRadio:host": CPU를 깨워 둔다(화면은 건드리지 않는다).
// - WifiLock: API 29+ FULL_LOW_LATENCY, 그 이하 FULL_HIGH_PERF.
// 잡기/풀기 각각 한 줄씩만 로그. 참조 카운트는 쓰지 않는다(한 번 잡고 한 번 푼다).
class HostPowerLocks(context: Context) {
    private val app = context.applicationContext
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    val isHeld: Boolean get() = wake != null || wifi != null

    @Suppress("DEPRECATION")
    fun acquire() {
        if (isHeld) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wake = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w("ARMS", "리모컨 호스트: WakeLock 실패 ${it.javaClass.simpleName}") }.getOrNull()
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi = runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm?.createWifiLock(mode, TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w("ARMS", "리모컨 호스트: WifiLock 실패 ${it.javaClass.simpleName}") }.getOrNull()
        if (isHeld) Log.i("ARMS", "리모컨 호스트: 벽걸이 모드 락 잡음 (wake=${wake != null}, wifi=${wifi != null})")
    }

    fun release() {
        if (!isHeld) return
        wake?.let { l -> runCatching { if (l.isHeld) l.release() } }
        wifi?.let { l -> runCatching { if (l.isHeld) l.release() } }
        wake = null
        wifi = null
        Log.i("ARMS", "리모컨 호스트: 벽걸이 모드 락 풀음")
    }

    private companion object {
        const val TAG = "SimpleRadio:host"
    }
}
