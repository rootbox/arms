package com.arms.androidauto.core.remote

// 브로커 연결 실패를 사람이 고칠 수 있는 단위로 분류한다. 예전엔 "연결할 수 없습니다" 한 줄뿐이라
// 주소가 틀렸는지(DNS), 브로커가 꺼졌는지(거부), 인증서인지(TLS), 계정인지(인증) 알 수 없었다.
enum class FailureKind { DNS, REFUSED, TIMEOUT, TLS, AUTH, WEBSOCKET, CLOSED, UNKNOWN }

object RemoteFailure {
    // HiveMQ는 원인을 여러 겹으로 감싼다. 의존성 없이 클래스 이름으로 원인 사슬을 훑는다.
    fun classify(t: Throwable?): FailureKind {
        var cur = t
        var depth = 0
        var sawClosed = false
        while (cur != null && depth < 8) {
            val n = cur.javaClass.name
            val msg = cur.message.orEmpty()
            when {
                n.endsWith("UnknownHostException") -> return FailureKind.DNS
                n.endsWith("ConnectException") && !n.contains("Mqtt") -> return FailureKind.REFUSED
                n.endsWith("NoRouteToHostException") -> return FailureKind.REFUSED
                n.endsWith("SocketTimeoutException") || n.endsWith("ConnectTimeoutException") -> return FailureKind.TIMEOUT
                n.contains("SSL") || n.contains("Certificate") || n.endsWith("CertPathValidatorException") -> return FailureKind.TLS
                n.endsWith("Mqtt3ConnAckException") || n.endsWith("Mqtt5ConnAckException") ->
                    return if (msg.contains("NOT_AUTHORIZED") || msg.contains("BAD_USER_NAME_OR_PASSWORD") ||
                        msg.contains("refused", ignoreCase = true)
                    ) FailureKind.AUTH else FailureKind.UNKNOWN
                n.contains("WebSocketHandshakeException") || n.contains("WebSocketClientHandshakeException") ->
                    return FailureKind.WEBSOCKET
                n.endsWith("ConnectionClosedException") -> sawClosed = true
            }
            if (msg.contains("NOT_AUTHORIZED") || msg.contains("BAD_USER_NAME_OR_PASSWORD")) return FailureKind.AUTH
            cur = cur.cause
            depth++
        }
        return if (sawClosed) FailureKind.CLOSED else FailureKind.UNKNOWN
    }

    fun message(kind: FailureKind): String = when (kind) {
        FailureKind.DNS -> "브로커 주소를 찾을 수 없습니다 (도메인/DNS 확인)."
        FailureKind.REFUSED -> "브로커가 응답하지 않습니다 (꺼져 있거나 포트가 막힘)."
        FailureKind.TIMEOUT -> "브로커 연결 시간이 초과됐습니다 (네트워크 확인)."
        FailureKind.TLS -> "보안 인증서 오류입니다 (NAS 리버스 프록시에 인증서 바인딩 확인)."
        FailureKind.AUTH -> "브로커 계정 또는 비밀번호가 틀렸습니다."
        FailureKind.WEBSOCKET -> "웹소켓 연결이 거부됐습니다 (리버스 프록시 WebSocket 헤더 확인)."
        FailureKind.CLOSED -> "브로커가 연결을 끊었습니다."
        FailureKind.UNKNOWN -> "브로커에 연결할 수 없습니다."
    }
}

// 저장/스캔 전에 한 번 실제로 붙어 본다. 자동 재접속 없는 일회성 연결.
object BrokerProbe {
    sealed class Result {
        data class Ok(val elapsedMs: Long) : Result()
        data class Failed(val kind: FailureKind) : Result()
    }

    suspend fun probe(url: String, username: String, password: String, clientId: String, timeoutMs: Long = 8_000L): Result {
        val started = System.currentTimeMillis()
        val tr = try {
            MqttRemoteTransport(url, username, password, clientId, autoReconnect = false)
        } catch (t: Throwable) {
            return Result.Failed(RemoteFailure.classify(t))
        }
        return try {
            val ok = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { tr.connect(); true }
            if (ok == true) Result.Ok(System.currentTimeMillis() - started) else Result.Failed(FailureKind.TIMEOUT)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            Result.Failed(RemoteFailure.classify(t))
        } finally {
            runCatching { tr.disconnect() }
        }
    }
}
