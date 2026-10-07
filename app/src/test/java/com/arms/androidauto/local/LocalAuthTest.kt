package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class LocalAuthTest {

    private class MemStore : PairedClientStore {
        var saved: String? = null
        override fun load(): List<PairedClient> = PairedClientCodec.decode(saved)
        override fun save(clients: List<PairedClient>) { saved = PairedClientCodec.encode(clients) }
    }

    @Test
    fun tokenIs32BytesBase64Url() {
        val t = TokenCodec.newToken(SecureRandom())
        assertEquals(43, t.length) // 32바이트 → 패딩 없는 base64 43자
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(t, TokenCodec.newToken(SecureRandom()))
    }

    @Test
    fun hashIsSha256Hex() {
        // echo -n abc | shasum -a 256
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TokenCodec.hash("abc"))
        assertTrue(TokenCodec.matches("abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertFalse(TokenCodec.matches("abd", TokenCodec.hash("abc")))
    }

    @Test
    fun bearerParsing() {
        assertEquals("tok", TokenCodec.bearer("Bearer tok"))
        assertEquals("tok", TokenCodec.bearer("bearer   tok "))
        assertNull(TokenCodec.bearer("Basic dXNlcjpwYXNz"))
        assertNull(TokenCodec.bearer("Bearer "))
        assertNull(TokenCodec.bearer(null))
    }

    @Test
    fun issueStoresOnlyHashAndVerifies() {
        val store = MemStore()
        val auth = LocalAuth(store, clock = { 42L })
        val (entry, token) = auth.issue("smartthings-edge", "거실 허브")
        assertFalse("원문 토큰이 저장소에 남으면 안 된다", store.saved!!.contains(token))
        assertTrue(store.saved!!.contains(TokenCodec.hash(token)))
        assertEquals("거실 허브", entry.label)
        assertEquals(42L, entry.createdAtMs)
        assertEquals(entry, auth.verify(token))
        assertNull(auth.verify(token + "x"))
        assertNull(auth.verify(""))
        assertNull(auth.verify(null))
    }

    @Test
    fun persistsAcrossInstancesAndRevokes() {
        val store = MemStore()
        val (entry, token) = LocalAuth(store).issue("smartthings-edge", "hub")
        val reloaded = LocalAuth(store)
        assertNotNull(reloaded.verify(token))
        assertEquals(listOf(entry), reloaded.clients.value)
        assertTrue(reloaded.revoke(entry.id))
        assertNull(reloaded.verify(token))
        assertFalse(reloaded.revoke(entry.id))
        assertTrue(LocalAuth(store).clients.value.isEmpty())
    }

    @Test
    fun sanitizesLabelAndDefaults() {
        val auth = LocalAuth(MemStore())
        assertEquals("SmartThings", auth.issue("", "  ").first.label)
        assertEquals("ab", auth.issue("x", "a\nb").first.label)
        assertEquals(64, auth.issue("x", "y".repeat(200)).first.label.length)
    }

    @Test
    fun dropsOldestBeyondLimit() {
        val auth = LocalAuth(MemStore(), maxClients = 2)
        val (_, t1) = auth.issue("c", "1")
        auth.issue("c", "2")
        auth.issue("c", "3")
        assertEquals(listOf("2", "3"), auth.clients.value.map { it.label })
        assertNull(auth.verify(t1))
    }

    @Test
    fun codecIgnoresGarbage() {
        assertTrue(PairedClientCodec.decode("not json").isEmpty())
        assertTrue(PairedClientCodec.decode(null).isEmpty())
        assertTrue(PairedClientCodec.decode("""[{"id":"a","hash":"short"}]""").isEmpty())
    }
}
