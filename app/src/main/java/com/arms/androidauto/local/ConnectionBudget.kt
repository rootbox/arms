package com.arms.androidauto.local

import java.util.concurrent.atomic.AtomicInteger

// 로컬 제어 서버의 동시 연결 예산. 요청(명령·상태·아트)과 SSE 스트림을 따로 센다 —
// 반쯤 죽은 SSE 소켓이 요청 상한(16)을 채워 명령이 503 busy를 받던 경로(2026-10-12 §1 보조 가설)를 막는다.
// 접속은 모두 요청 슬롯으로 들어오고, /events로 판명되면 SSE 슬롯으로 옮긴다(convertToSse).
class ConnectionBudget(val maxRequests: Int, val maxSse: Int) {
    private val requests = AtomicInteger(0)
    private val sse = AtomicInteger(0)

    val requestCount: Int get() = requests.get()
    val sseCount: Int get() = sse.get()

    // 새 접속. 상한이면 false(아무것도 점유하지 않음).
    fun tryAcquireRequest(): Boolean {
        if (requests.incrementAndGet() > maxRequests) {
            requests.decrementAndGet()
            return false
        }
        return true
    }

    fun releaseRequest() {
        requests.decrementAndGet()
    }

    // 요청 슬롯을 SSE 슬롯으로 바꾼다. SSE 상한이면 false — 요청 슬롯은 그대로 둔다(호출자는 503을 쓰고 평소처럼 반납).
    fun convertToSse(): Boolean {
        if (sse.incrementAndGet() > maxSse) {
            sse.decrementAndGet()
            return false
        }
        requests.decrementAndGet()
        return true
    }

    fun releaseSse() {
        sse.decrementAndGet()
    }
}
