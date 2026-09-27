package com.arms.androidauto.core.remote

// 재전송 방지: seq는 마지막으로 받아들인 값보다 커야 하고, sentAtMs는 nowMs ± windowMs 안이어야 한다.
// 창 밖이거나 seq가 뒤처진 메시지는 거부하되 상태를 바꾸지 않는다. 전송 스레드에서 불리므로 synchronized.
class ReplayGuard(private val windowMs: Long = 120_000L) {
    private val lock = Any()
    private var lastSeq: Long = Long.MIN_VALUE

    fun accept(seq: Long, sentAtMs: Long, nowMs: Long): Boolean = synchronized(lock) {
        if (seq <= lastSeq) return false
        if (!withinWindow(sentAtMs, nowMs)) return false
        lastSeq = seq
        true
    }

    fun reset() = synchronized(lock) {
        lastSeq = Long.MIN_VALUE
    }

    private fun withinWindow(sentAtMs: Long, nowMs: Long): Boolean {
        if (windowMs == Long.MAX_VALUE) return true
        val delta = nowMs - sentAtMs
        return delta in -windowMs..windowMs
    }
}
