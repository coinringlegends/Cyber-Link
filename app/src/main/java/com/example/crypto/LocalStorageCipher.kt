package com.example.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM cipher for encrypting locally persisted Room messages at rest.
 * Prefix "ENC1:" distinguishes encrypted records safely from legacy plaintext rows.
 */
class LocalStorageCipher(keyBytes: ByteArray) {

    init {
        require(keyBytes.size == 32) { "AES-256 storage key must be 32 bytes" }
    }

    private val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
    private val secureRandom = SecureRandom()

    fun encryptString(plaintext: String): String {
        if (plaintext.startsWith(PREFIX)) return plaintext
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptString(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(combined.size > 12 + 16)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse { "[Lokaler Entschlüsselungsfehler]" }
    }

    companion object {
        const val PREFIX = "ENC1:"

        fun generateRandomKeyBytes(): ByteArray = ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }
    }
}
