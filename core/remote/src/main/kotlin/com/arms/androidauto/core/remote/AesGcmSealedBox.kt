package com.arms.androidauto.core.remote

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// AES-256-GCM 봉인. 출력 = nonce(12) || ciphertext || tag(16). nonce는 메시지마다 SecureRandom.
// aad(토픽 이름)가 다르면 개봉이 실패하므로 cmd 암호문을 state 토픽에 재생하는 식의 바꿔치기가 막힌다.
class AesGcmSealedBox(key: ByteArray) : SealedBox {
    init {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
    }

    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val random = SecureRandom()

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plain)
        return nonce + ct
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
    }
}
