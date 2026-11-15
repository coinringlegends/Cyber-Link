package com.example.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic engine for CYBER LINK.
 * Uses EC P-256 (ECDH key agreement + ECDSA SHA256withECDSA signatures),
 * HKDF-SHA256 key derivation, AES-256-GCM authenticated encryption,
 * and monotonic replay-window protection.
 *
 * SECURITY: Private keys and plaintext session keys are NEVER logged or serialized into QR codes.
 */
class EncryptionManager(
    private val identityKeyPair: KeyPair = generateEcKeyPair(),
) {
    val nodeId: String by lazy { deriveNodeId(identityKeyPair.public.encoded) }
    val publicKeyBase64: String by lazy { encodeBase64(identityKeyPair.public.encoded) }

    fun exportPrivateKeyPkcs8(): ByteArray = identityKeyPair.private.encoded
    fun exportPublicKeyX509(): ByteArray = identityKeyPair.public.encoded

    fun generateEphemeralKeyPair(): KeyPair = generateEcKeyPair()

    fun generateNonce(sizeBytes: Int = 16): ByteArray = ByteArray(sizeBytes).also {
        secureRandom.nextBytes(it)
    }

    fun signPayload(payload: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(identityKeyPair.private, secureRandom)
        signature.update(payload)
        return signature.sign()
    }

    fun verifySignature(
        peerIdentityPublicKeyBytes: ByteArray,
        payload: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean = runCatching {
        val publicKey = decodeEcPublicKey(peerIdentityPublicKeyBytes)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        verifier.verify(signatureBytes)
    }.getOrDefault(false)

    fun deriveSession(
        sessionId: String,
        localNodeId: String,
        peerNodeId: String,
        localEphemeralPrivate: PrivateKey,
        peerEphemeralPublicBytes: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
    ): SessionCipher {
        val peerEphemeralPublic = decodeEcPublicKey(peerEphemeralPublicBytes)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localEphemeralPrivate)
        agreement.doPhase(peerEphemeralPublic, true)
        val sharedSecret = agreement.generateSecret()

        val sortedIds = listOf(localNodeId, peerNodeId).sorted()
        val salt = sha256(nonceA + nonceB + sessionId.toByteArray(Charsets.UTF_8))
        val info = ("CYBERLINK_V5_SESSION:" + sortedIds.joinToString(":")).toByteArray(Charsets.UTF_8)
        val keyMaterial = hkdfSha256(ikm = sharedSecret, salt = salt, info = info, length = 32)
        val aesKey = SecretKeySpec(keyMaterial, "AES")
        sharedSecret.fill(0)
        keyMaterial.fill(0)

        return SessionCipher(
            sessionId = sessionId,
            localNodeId = localNodeId,
            peerNodeId = peerNodeId,
            aesKey = aesKey,
        )
    }

    class SessionCipher(
        val sessionId: String,
        val localNodeId: String,
        val peerNodeId: String,
        private val aesKey: SecretKey,
    ) {
        private var outboundCounter = 0L
        private val replayGuard = ReplayGuard()
        private val lock = Any()

        fun encrypt(plaintext: ByteArray, aad: ByteArray = sessionId.toByteArray(Charsets.UTF_8)): ByteArray {
            val counter: Long
            synchronized(lock) {
                counter = ++outboundCounter
            }
            val nonce = ByteArray(12)
            secureRandom.nextBytes(nonce)
            // Mix monotonic counter into last 8 bytes for strict nonce uniqueness
            val buf = ByteBuffer.allocate(8).putLong(counter).array()
            for (i in 0 until 8) {
                nonce[4 + i] = (nonce[4 + i].toInt() xor buf[i].toInt()).toByte()
            }

            val payloadWithSeq = ByteBuffer.allocate(8 + plaintext.size)
                .putLong(counter)
                .put(plaintext)
                .array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(payloadWithSeq)

            return ByteBuffer.allocate(12 + ciphertext.size)
                .put(nonce)
                .put(ciphertext)
                .array()
        }

        fun decrypt(packet: ByteArray, aad: ByteArray = sessionId.toByteArray(Charsets.UTF_8)): ByteArray {
            require(packet.size > 12 + 16 + 8) { "Encrypted packet too short" }
            val nonce = packet.copyOfRange(0, 12)
            val ciphertext = packet.copyOfRange(12, packet.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plainWithSeq = cipher.doFinal(ciphertext)

            val bb = ByteBuffer.wrap(plainWithSeq)
            val seq = bb.long
            if (!replayGuard.accept(seq)) {
                throw SecurityException("Replay or out-of-window sequence detected: $seq")
            }
            val payload = ByteArray(plainWithSeq.size - 8)
            bb.get(payload)
            return payload
        }
    }

    class ReplayGuard(private val windowSize: Long = 64L) {
        private var highestSeen = 0L
        private val seenSet = Collections.synchronizedSet(HashSet<Long>())

        @Synchronized
        fun accept(seq: Long): Boolean {
            if (seq <= 0L) return false
            if (seq in seenSet) return false
            if (highestSeen > windowSize && seq <= highestSeen - windowSize) {
                return false
            }
            if (seq > highestSeen) {
                val cutoff = seq - windowSize
                seenSet.removeIf { it <= cutoff }
                highestSeen = seq
            }
            seenSet.add(seq)
            return true
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateEcKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
            return kpg.generateKeyPair()
        }

        fun fromEncodedKeys(privatePkcs8: ByteArray, publicX509: ByteArray): EncryptionManager {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
            val pub = kf.generatePublic(X509EncodedKeySpec(publicX509))
            return EncryptionManager(KeyPair(pub, priv))
        }

        fun decodeEcPublicKey(x509Bytes: ByteArray): PublicKey {
            val kf = KeyFactory.getInstance("EC")
            return kf.generatePublic(X509EncodedKeySpec(x509Bytes))
        }

        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        fun sha256Hex(data: ByteArray): String =
            sha256(data).joinToString("") { "%02x".format(it) }

        fun deriveNodeId(publicKeyBytes: ByteArray): String {
            val hash = sha256(publicKeyBytes)
            val hex = hash.take(6).joinToString("") { "%02X".format(it) }
            return "CL-$hex"
        }

        fun encodeBase64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun decodeBase64(str: String): ByteArray =
            Base64.decode(str, Base64.NO_WRAP)

        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
            mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            val okm = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val copyLen = minOf(t.size, length - offset)
                System.arraycopy(t, 0, okm, offset, copyLen)
                offset += copyLen
                counter++
            }
            prk.fill(0)
            return okm
        }
    }
}
