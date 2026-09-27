package com.arms.androidauto.core.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AesGcmSealedBoxTest {
    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val aad = "sr/abc/cmd".toByteArray()
    private val plain = """{"v":1,"t":"cmd","seq":1,"ts":2,"cmd":"play"}""".toByteArray()

    @Test
    fun `round trip`() {
        val box = AesGcmSealedBox(key)
        val sealed = box.seal(plain, aad)
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, box.open(sealed, aad))
    }

    @Test
    fun `same plaintext seals differently each time`() {
        val box = AesGcmSealedBox(key)
        val a = box.seal(plain, aad)
        val b = box.seal(plain, aad)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `empty plaintext round trips`() {
        val box = AesGcmSealedBox(key)
        assertArrayEquals(ByteArray(0), box.open(box.seal(ByteArray(0), aad), aad))
    }

    @Test
    fun `tampered ciphertext opens to null`() {
        val box = AesGcmSealedBox(key)
        val sealed = box.seal(plain, aad)
        val tampered = sealed.copyOf().also { it[15] = (it[15].toInt() xor 0x01).toByte() }
        assertNull(box.open(tampered, aad))
        val tamperedTag = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        assertNull(box.open(tamperedTag, aad))
        val tamperedNonce = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(box.open(tamperedNonce, aad))
    }

    @Test
    fun `wrong aad opens to null`() {
        val box = AesGcmSealedBox(key)
        val sealed = box.seal(plain, aad)
        assertNull(box.open(sealed, "sr/abc/state".toByteArray()))
    }

    @Test
    fun `wrong key opens to null`() {
        val sealed = AesGcmSealedBox(key).seal(plain, aad)
        assertNull(AesGcmSealedBox(otherKey).open(sealed, aad))
    }

    @Test
    fun `truncated or empty input opens to null`() {
        val box = AesGcmSealedBox(key)
        val sealed = box.seal(plain, aad)
        assertNull(box.open(sealed.copyOf(sealed.size - 1), aad))
        assertNull(box.open(sealed.copyOf(20), aad))
        assertNull(box.open(ByteArray(0), aad))
        assertNull(box.open(ByteArray(27), aad))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `key must be 32 bytes`() {
        AesGcmSealedBox(ByteArray(16))
    }
}
