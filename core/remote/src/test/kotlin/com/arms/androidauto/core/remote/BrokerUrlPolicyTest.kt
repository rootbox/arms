package com.arms.androidauto.core.remote

import com.arms.androidauto.core.remote.BrokerUrlPolicy.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerUrlPolicyTest {
    private fun problem(url: String, allowLoopback: Boolean = false) =
        (BrokerUrlPolicy.check(url, allowLoopback) as? BrokerUrlPolicy.Result.Invalid)?.problem

    @Test
    fun loopbackIsRejected() {
        // 2026-10-06 태블릿 사고의 그 주소.
        assertEquals(Problem.LOOPBACK, problem("ws://127.0.0.1:9001/"))
        assertEquals(Problem.LOOPBACK, problem("wss://localhost/"))
        assertEquals(Problem.LOOPBACK, problem("ws://[::1]:9001/"))
        assertEquals(Problem.LOOPBACK, problem("tcp://0.0.0.0:1883"))
        assertEquals(Problem.LOOPBACK, problem("ws://127.1.2.3/"))
    }

    @Test
    fun loopbackAllowedOnlyForDebug() {
        assertTrue(BrokerUrlPolicy.check("ws://127.0.0.1:9001/", allowLoopback = true) is BrokerUrlPolicy.Result.Ok)
    }

    @Test
    fun publicWssIsOk() {
        val r = BrokerUrlPolicy.check(" wss://mqtt.1319.space/ ") as BrokerUrlPolicy.Result.Ok
        assertEquals("wss://mqtt.1319.space/", r.normalizedUrl)
        assertEquals("mqtt.1319.space", r.host)
        assertEquals(false, r.isLan)
    }

    @Test
    fun plaintextOnlyOnPrivateLan() {
        assertEquals(Problem.PLAINTEXT_PUBLIC, problem("ws://mqtt.1319.space/"))
        assertEquals(Problem.PLAINTEXT_PUBLIC, problem("tcp://211.208.166.136:1883"))
        assertTrue(BrokerUrlPolicy.check("ws://192.168.10.33:9001/") is BrokerUrlPolicy.Result.Ok)
        assertTrue(BrokerUrlPolicy.check("ws://10.0.0.5:9001/") is BrokerUrlPolicy.Result.Ok)
        assertTrue(BrokerUrlPolicy.check("ws://172.20.1.2:9001/") is BrokerUrlPolicy.Result.Ok)
        assertEquals(Problem.PLAINTEXT_PUBLIC, problem("ws://172.32.1.2:9001/"))
    }

    @Test
    fun malformedAndEmpty() {
        assertEquals(Problem.EMPTY, problem(""))
        assertEquals(Problem.EMPTY, problem("wss://"))
        assertEquals(Problem.BAD_FORMAT, problem("mqtt.1319.space"))
        assertEquals(Problem.UNSUPPORTED_SCHEME, problem("https://mqtt.1319.space/"))
        assertEquals(Problem.BAD_FORMAT, problem("wss://bad host/"))
    }

    @Test
    fun everyProblemHasAMessage() {
        Problem.values().forEach { assertTrue(BrokerUrlPolicy.message(it).isNotBlank()) }
    }
}
