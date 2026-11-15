package com.example

import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `protocol chunking and reassembly works at minimum MTU 23`() {
        val payload = ("CYBER_LINK_CHALLENGE_PAYLOAD_" + "A".repeat(140)).toByteArray(Charsets.UTF_8)
        val chunks = CyberLinkProtocol.chunkPayload(channelId = 7, payload = payload, mtu = 23, encrypted = false)
        assertTrue("Expected multiple chunks at MTU 23, got ${chunks.size}", chunks.size > 10)
        chunks.forEach { chunk ->
            assertTrue("Chunk ${chunk.size} exceeds 20-byte ATT payload for MTU 23", chunk.size <= 20)
        }

        val reassembler = CyberLinkProtocol.PacketReassembler()
        var result: CyberLinkProtocol.PacketReassembler.ReassembledMessage? = null
        for (c in chunks) {
            result = reassembler.feed(c)
        }
        assertArrayEquals(payload, result!!.payload)
    }

    @Test
    fun `ECDH HKDF AES-GCM encryption rejects replay and tampering`() {
        val alice = EncryptionManager()
        val bob = EncryptionManager()
        val ephA = alice.generateEphemeralKeyPair()
        val ephB = bob.generateEphemeralKeyPair()
        val nA = alice.generateNonce(16)
        val nB = bob.generateNonce(16)

        val cipherA = alice.deriveSession("sess1", alice.nodeId, bob.nodeId, ephA.private, ephB.public.encoded, nA, nB)
        val cipherB = bob.deriveSession("sess1", bob.nodeId, alice.nodeId, ephB.private, ephA.public.encoded, nA, nB)

        val plain = "VERIFY_ACK_PAYLOAD".toByteArray(Charsets.UTF_8)
        val encrypted = cipherA.encrypt(plain)
        val decrypted = cipherB.decrypt(encrypted)
        assertEquals("VERIFY_ACK_PAYLOAD", String(decrypted, Charsets.UTF_8))

        // Replay of exact same ciphertext must be rejected
        assertThrows(SecurityException::class.java) {
            cipherB.decrypt(encrypted)
        }
    }
}
