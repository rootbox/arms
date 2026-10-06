package com.arms.androidauto.core.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RemoteFailureTest {
    private class ConnectionFailedException(cause: Throwable) : RuntimeException(cause)
    private class Mqtt3ConnAckException(msg: String) : RuntimeException(msg)

    @Test
    fun classifiesWrappedCauses() {
        assertEquals(FailureKind.DNS, RemoteFailure.classify(ConnectionFailedException(UnknownHostException("mqtt.x"))))
        assertEquals(FailureKind.REFUSED, RemoteFailure.classify(ConnectionFailedException(ConnectException("refused"))))
        assertEquals(FailureKind.TIMEOUT, RemoteFailure.classify(ConnectionFailedException(SocketTimeoutException())))
        assertEquals(FailureKind.TLS, RemoteFailure.classify(ConnectionFailedException(SSLHandshakeException("cert"))))
        assertEquals(FailureKind.AUTH, RemoteFailure.classify(Mqtt3ConnAckException("CONNACK BAD_USER_NAME_OR_PASSWORD")))
        assertEquals(FailureKind.UNKNOWN, RemoteFailure.classify(IllegalStateException("x")))
        assertEquals(FailureKind.UNKNOWN, RemoteFailure.classify(null))
    }

    @Test
    fun everyKindHasAMessage() {
        FailureKind.values().forEach { assertTrue(RemoteFailure.message(it).isNotBlank()) }
    }

    @Test
    fun probeReportsRefusedForClosedPort() = runBlocking {
        // 비어 있는 포트를 잡았다 놓아 "아무도 안 듣는" 주소를 만든다.
        val port = ServerSocket(0).use { it.localPort }
        val r = BrokerProbe.probe("tcp://127.0.0.1:$port", "u", "p", "probe-test", timeoutMs = 5_000)
        assertTrue("got $r", r == BrokerProbe.Result.Failed(FailureKind.REFUSED))
    }

    @Test
    fun probeReportsDnsForUnknownHost() = runBlocking {
        val r = BrokerProbe.probe("wss://no-such-host.invalid/", "u", "p", "probe-test", timeoutMs = 5_000)
        assertTrue("got $r", r == BrokerProbe.Result.Failed(FailureKind.DNS))
    }
}
