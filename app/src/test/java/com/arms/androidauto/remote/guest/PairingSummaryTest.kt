package com.arms.androidauto.remote.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingSummaryTest {
    @Test fun `브로커 URL에서 호스트만 보여준다`() {
        assertEquals("mqtt.example.com", PairingSummary.brokerHost("wss://mqtt.example.com/mqtt"))
        assertEquals("mqtt.example.com", PairingSummary.brokerHost("wss://mqtt.example.com:8884/mqtt?x=1"))
        assertEquals("192.168.0.10", PairingSummary.brokerHost("tcp://192.168.0.10:1883"))
        assertEquals("알 수 없음", PairingSummary.brokerHost(""))
    }

    @Test fun `브로커 URL에 든 계정 정보는 절대 보여주지 않는다`() {
        val shown = PairingSummary.brokerHost("wss://user:secret@mqtt.example.com/mqtt")
        assertEquals("mqtt.example.com", shown)
        assertFalse(shown.contains("secret"))
    }

    @Test fun `pairId는 앞 4자만 남기고 가린다`() {
        assertEquals("ab12••••", PairingSummary.maskedPairId("ab12cd34ef56"))
        assertEquals("ab", PairingSummary.maskedPairId("ab"))
        assertEquals("abcd", PairingSummary.maskedPairId("abcd"))
        assertEquals("", PairingSummary.maskedPairId(""))
    }
}
