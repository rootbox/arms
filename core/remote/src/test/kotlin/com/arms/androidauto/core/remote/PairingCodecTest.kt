package com.arms.androidauto.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingCodecTest {
    private val brokerUrl = "wss://mqtt.example-home.synology.me/mqtt"

    @Test
    fun `generate makes a 32 byte key and 12 hex pairId`() {
        val p = Pairing.generate(brokerUrl, "simpleradio", "correct horse battery staple")
        assertEquals(32, p.key.size)
        assertTrue(p.pairId, Regex("^[0-9a-f]{12}$").matches(p.pairId))
        assertEquals("sr/${p.pairId}/state", p.topics.state)
        val q = Pairing.generate(brokerUrl, "simpleradio", "correct horse battery staple")
        assertNotEquals(p.pairId, q.pairId)
        assertTrue(!p.key.contentEquals(q.key))
    }

    @Test
    fun `encode decode round trip`() {
        val p = Pairing.generate(brokerUrl, "simpleradio", "correct horse battery staple")
        val text = p.toQrText()
        assertTrue(text.startsWith("SR1."))
        val decoded = Pairing.fromQrText(text)
        assertEquals(p, decoded)
        assertEquals(p.brokerUrl, decoded!!.brokerUrl)
        assertEquals(p.username, decoded.username)
        assertEquals(p.password, decoded.password)
    }

    @Test
    fun `encoded text fits a QR`() {
        val p = Pairing.generate(brokerUrl, "simpleradio-guest", "a-reasonably-long-password-1234")
        val text = p.toQrText()
        assertTrue("length ${text.length}", text.length <= 300)
        assertTrue("no padding/whitespace", !text.contains('=') && !text.contains(' '))
    }

    @Test
    fun `unicode credentials round trip`() {
        val p = Pairing.generate(brokerUrl, "라디오", "비밀번호!@#")
        assertEquals(p, Pairing.fromQrText(p.toQrText()))
    }

    private fun tamperJson(text: String, mutate: (org.json.JSONObject) -> Unit): String {
        val body = text.removePrefix("SR1.")
        val json = org.json.JSONObject(String(Base64.getUrlDecoder().decode(body)))
        mutate(json)
        return "SR1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }

    @Test
    fun `malformed inputs decode to null`() {
        val good = Pairing.generate(brokerUrl, "u", "p").toQrText()
        assertNull(Pairing.fromQrText(""))
        assertNull(Pairing.fromQrText("SR1."))
        assertNull(Pairing.fromQrText("SR2." + good.removePrefix("SR1.")))
        assertNull(Pairing.fromQrText("hello world"))
        assertNull(Pairing.fromQrText("SR1.!!!not-base64!!!"))
        assertNull(Pairing.fromQrText("SR1." + Base64.getUrlEncoder().withoutPadding().encodeToString("not json".toByteArray())))
        assertNull(Pairing.fromQrText("SR1." + Base64.getUrlEncoder().withoutPadding().encodeToString("[]".toByteArray())))
        assertNull("wrong version", Pairing.fromQrText(tamperJson(good) { it.put("v", 2) }))
        assertNull("short key", Pairing.fromQrText(tamperJson(good) { it.put("k", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))) }))
        assertNull("blank broker", Pairing.fromQrText(tamperJson(good) { it.put("u", " ") }))
        assertNull("blank user", Pairing.fromQrText(tamperJson(good) { it.put("n", "") }))
        assertNull("blank password", Pairing.fromQrText(tamperJson(good) { it.put("w", "") }))
        assertNull("missing key", Pairing.fromQrText(tamperJson(good) { it.remove("k") }))
        assertNull("bad pairId", Pairing.fromQrText(tamperJson(good) { it.put("p", "not/hex") }))
        assertNull("unknown field", Pairing.fromQrText(tamperJson(good) { it.put("extra", "x") }))
        assertNull("wrong type", Pairing.fromQrText(tamperJson(good) { it.put("u", 42) }))
    }
}
