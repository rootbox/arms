package com.arms.androidauto.core.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 브로커 없이 확인할 수 있는 부분만: URL 해석 오류가 connect()에서 IllegalArgumentException으로 드러난다.
class MqttRemoteTransportTest {
    private fun connectError(url: String): Throwable? = runBlocking {
        runCatching { MqttRemoteTransport(url, "u", "p", "c").connect() }.exceptionOrNull()
    }

    @Test
    fun `unsupported scheme is rejected at connect`() {
        val e = connectError("http://broker.test/mqtt")
        assertTrue("$e", e is IllegalArgumentException)
    }

    @Test
    fun `url without host is rejected at connect`() {
        assertTrue(connectError("wss:///mqtt") is IllegalArgumentException)
        assertTrue(connectError("not a url") is IllegalArgumentException)
    }

    @Test
    fun `initial state is disconnected`() {
        val t = MqttRemoteTransport("wss://broker.test/mqtt", "u", "p", "c")
        assertEquals(ConnectionState.DISCONNECTED, t.connectionState.value)
    }
}
