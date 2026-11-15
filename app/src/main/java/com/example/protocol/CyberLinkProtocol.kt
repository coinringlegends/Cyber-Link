package com.example.protocol

import java.nio.ByteBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * CYBER LINK V5.0 binary framing & protocol definitions.
 * Supports chunking & reassembly down to minimum BLE ATT MTU = 23 (20-byte ATT payload).
 *
 * Binary Chunk Header (8 bytes):
 * - [0]     magic = 0xC1
 * - [1]     flags (bit 0 = START, bit 1 = END, bit 2 = ENCRYPTED)
 * - [2..3]  channelId (unsigned 16-bit)
 * - [4..5]  chunkIndex (unsigned 16-bit, 0-based)
 * - [6..7]  totalChunks (unsigned 16-bit, >= 1)
 *
 * At MTU 23: ATT max payload = 20 bytes -> 8-byte header + 12 bytes payload per chunk.
 * At MTU 517: ATT max payload = 512 bytes -> 8-byte header + 504 bytes payload per chunk.
 */
object CyberLinkProtocol {

    const val PROTOCOL_VERSION = "CL5.0"
    const val MAGIC_BYTE: Byte = 0xC1.toByte()
    const val HEADER_SIZE = 8
    const val MIN_MTU = 23
    const val ATT_OVERHEAD = 3
    const val MAX_REASSEMBLY_BYTES = 2 * 1024 * 1024 // 2 MB cap per logical frame

    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val FLAG_ENCRYPTED = 0x04

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun maxAttPayloadForMtu(mtu: Int): Int {
        val safeMtu = mtu.coerceAtLeast(MIN_MTU)
        // Android caps single GATT write/notify payload to 512 bytes even if MTU=517
        return (safeMtu - ATT_OVERHEAD).coerceAtMost(512)
    }

    fun maxChunkDataSizeForMtu(mtu: Int): Int {
        return (maxAttPayloadForMtu(mtu) - HEADER_SIZE).coerceAtLeast(1)
    }

    fun chunkPayload(
        channelId: Int,
        payload: ByteArray,
        mtu: Int,
        encrypted: Boolean = false,
    ): List<ByteArray> {
        val maxData = maxChunkDataSizeForMtu(mtu)
        val totalChunks = if (payload.isEmpty()) 1 else ((payload.size + maxData - 1) / maxData)
        require(totalChunks <= 65535) { "Payload too large for 16-bit chunk count: $totalChunks" }

        val chunks = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * maxData
            val end = minOf(start + maxData, payload.size)
            val sliceLen = end - start
            var flags = 0
            if (i == 0) flags = flags or FLAG_START
            if (i == totalChunks - 1) flags = flags or FLAG_END
            if (encrypted) flags = flags or FLAG_ENCRYPTED

            val packet = ByteBuffer.allocate(HEADER_SIZE + sliceLen)
                .put(MAGIC_BYTE)
                .put(flags.toByte())
                .putShort((channelId and 0xFFFF).toShort())
                .putShort((i and 0xFFFF).toShort())
                .putShort((totalChunks and 0xFFFF).toShort())
                .put(payload, start, sliceLen)
                .array()
            chunks.add(packet)
        }
        return chunks
    }

    data class ParsedChunk(
        val flags: Int,
        val channelId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray,
    ) {
        val isStart: Boolean get() = (flags and FLAG_START) != 0
        val isEnd: Boolean get() = (flags and FLAG_END) != 0
        val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
    }

    fun parseChunk(raw: ByteArray): ParsedChunk? {
        if (raw.size < HEADER_SIZE) return null
        if (raw[0] != MAGIC_BYTE) return null
        val bb = ByteBuffer.wrap(raw)
        bb.get() // magic
        val flags = bb.get().toInt() and 0xFF
        val channelId = bb.short.toInt() and 0xFFFF
        val chunkIndex = bb.short.toInt() and 0xFFFF
        val totalChunks = bb.short.toInt() and 0xFFFF
        if (totalChunks == 0 || chunkIndex >= totalChunks) return null
        val data = ByteArray(raw.size - HEADER_SIZE)
        bb.get(data)
        return ParsedChunk(flags, channelId, chunkIndex, totalChunks, data)
    }

    class PacketReassembler {
        private data class AssemblyBuffer(
            val totalChunks: Int,
            val encrypted: Boolean,
            val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks),
            var receivedCount: Int = 0,
            var totalBytes: Int = 0,
            val createdAtMs: Long = System.currentTimeMillis(),
        )

        private val buffers = HashMap<Int, AssemblyBuffer>()

        data class ReassembledMessage(
            val channelId: Int,
            val encrypted: Boolean,
            val payload: ByteArray,
        )

        @Synchronized
        fun feed(raw: ByteArray): ReassembledMessage? {
            val chunk = parseChunk(raw) ?: return null
            if (chunk.chunkIndex == 0) {
                buffers[chunk.channelId] = AssemblyBuffer(
                    totalChunks = chunk.totalChunks,
                    encrypted = chunk.isEncrypted,
                )
            }
            val buf = buffers[chunk.channelId] ?: return null
            if (buf.totalChunks != chunk.totalChunks) {
                buffers.remove(chunk.channelId)
                return null
            }
            if (buf.chunks[chunk.chunkIndex] == null) {
                buf.totalBytes += chunk.data.size
                if (buf.totalBytes > MAX_REASSEMBLY_BYTES) {
                    buffers.remove(chunk.channelId)
                    return null
                }
                buf.chunks[chunk.chunkIndex] = chunk.data
                buf.receivedCount++
            }
            if (buf.receivedCount == buf.totalChunks) {
                buffers.remove(chunk.channelId)
                val out = ByteArray(buf.totalBytes)
                var pos = 0
                for (part in buf.chunks) {
                    if (part == null) return null
                    System.arraycopy(part, 0, out, pos, part.size)
                    pos += part.size
                }
                return ReassembledMessage(chunk.channelId, buf.encrypted, out)
            }
            return null
        }

        @Synchronized
        fun clearChannel(channelId: Int) {
            buffers.remove(channelId)
        }

        @Synchronized
        fun reset() {
            buffers.clear()
        }
    }

    @Serializable
    sealed class ProtocolEnvelope {
        @Serializable
        data class IdentityInfo(
            val nodeId: String,
            val deviceName: String,
            val publicKeyB64: String,
            val protocolVersion: String = PROTOCOL_VERSION,
            val supportsRfcomm: Boolean = true,
        ) : ProtocolEnvelope()

        @Serializable
        data class ChallengeStart(
            val sessionId: String,
            val initiatorNodeId: String,
            val responderNodeId: String,
            val nonceAB64: String,
            val ephemeralPubAB64: String,
            val identityPubAB64: String,
            val timestampMs: Long,
            val signatureAB64: String,
        ) : ProtocolEnvelope()

        @Serializable
        data class ChallengeResponse(
            val sessionId: String,
            val responderNodeId: String,
            val initiatorNodeId: String,
            val nonceAB64: String,
            val nonceBB64: String,
            val ephemeralPubBB64: String,
            val identityPubBB64: String,
            val timestampMs: Long,
            val signatureBB64: String,
        ) : ProtocolEnvelope()

        @Serializable
        data class VerifyAck(
            val sessionId: String,
            val senderNodeId: String,
            val targetNodeId: String,
            val direction: String, // "A_TO_B" or "B_TO_A"
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class ConfirmOk(
            val sessionId: String,
            val senderNodeId: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class ChatMessage(
            val messageId: String,
            val senderNodeId: String,
            val recipientNodeId: String,
            val groupId: String? = null,
            val text: String,
            val messageType: String = "TEXT",
            val priority: String = "NORMAL",
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class MessageAck(
            val messageId: String,
            val senderNodeId: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class MessageReadAck(
            val messageIds: List<String>,
            val senderNodeId: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class PairingConfirmRequest(
            val sessionId: String,
            val initiatorNodeId: String,
            val initiatorName: String,
            val tempDeviceId: String,
            val pairingToken: String,
            val cl2Code: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class PairingConfirmAck(
            val sessionId: String,
            val responderNodeId: String,
            val accepted: Boolean,
            val pairingToken: String,
            val reason: String? = null,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class FileTransferOffer(
            val transferId: String,
            val messageId: String,
            val senderNodeId: String,
            val recipientNodeId: String,
            val groupId: String? = null,
            val fileName: String,
            val mimeType: String,
            val messageType: String, // "IMAGE" or "FILE"
            val fileSize: Long,
            val sha256: String,
            val totalChunks: Int,
            val chunkSize: Int,
            val priority: String = "NORMAL",
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class FileTransferChunk(
            val transferId: String,
            val chunkIndex: Int,
            val totalChunks: Int,
            val dataB64: String,
        ) : ProtocolEnvelope()

        @Serializable
        data class FileTransferAck(
            val transferId: String,
            val messageId: String,
            val ackedChunkIndex: Int,
            val completed: Boolean = false,
            val sha256Verified: Boolean = false,
            val error: String? = null,
        ) : ProtocolEnvelope()

        @Serializable
        data class LocationShareUpdate(
            val contactNodeId: String,
            val latitude: Double,
            val longitude: Double,
            val accuracyMeters: Float,
            val altitudeMeters: Double?,
            val speedMps: Float?,
            val bearingDeg: Float?,
            val timestampMs: Long,
            val sequenceNonce: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class LocationShareRevoke(
            val contactNodeId: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class MeshRelayPacket(
            val packetId: String,
            val originNodeId: String,
            val finalTargetNodeId: String,
            val ttl: Int,
            val hopCount: Int,
            val priority: String,
            val innerPayloadB64: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()

        @Serializable
        data class MapPackageOffer(
            val packageId: String,
            val regionId: String,
            val regionName: String,
            val packageType: String,
            val version: String,
            val fileSize: Long,
            val sha256: String,
            val totalChunks: Int,
            val chunkSize: Int,
        ) : ProtocolEnvelope()

        @Serializable
        data class MapPackageRequest(
            val packageId: String,
            val accepted: Boolean,
            val resumeFromChunk: Int = 0,
        ) : ProtocolEnvelope()

        @Serializable
        data class MapChunkPacket(
            val packageId: String,
            val chunkIndex: Int,
            val totalChunks: Int,
            val dataB64: String,
        ) : ProtocolEnvelope()

        @Serializable
        data class MapChunkAck(
            val packageId: String,
            val ackedChunkIndex: Int,
            val completed: Boolean = false,
            val sha256Verified: Boolean = false,
        ) : ProtocolEnvelope()

        @Serializable
        data class CallSignal(
            val callId: String,
            val callType: String, // "AUDIO" or "VIDEO"
            val action: String,   // "INVITE", "ACCEPT", "REJECT", "HANGUP", "MUTE"
            val senderNodeId: String,
            val transportHint: String,
            val timestampMs: Long,
        ) : ProtocolEnvelope()
    }

    fun encodeEnvelope(envelope: ProtocolEnvelope): ByteArray =
        json.encodeToString(envelope).toByteArray(Charsets.UTF_8)

    fun decodeEnvelope(bytes: ByteArray): ProtocolEnvelope =
        json.decodeFromString(bytes.toString(Charsets.UTF_8))

    fun signatureTranscriptForStart(
        sessionId: String,
        initiatorNodeId: String,
        responderNodeId: String,
        nonceA: ByteArray,
        ephemeralPubA: ByteArray,
        timestampMs: Long,
    ): ByteArray = (
        "START|$PROTOCOL_VERSION|$sessionId|$initiatorNodeId|$responderNodeId|$timestampMs|"
        ).toByteArray(Charsets.UTF_8) + nonceA + ephemeralPubA

    fun signatureTranscriptForResponse(
        sessionId: String,
        responderNodeId: String,
        initiatorNodeId: String,
        nonceA: ByteArray,
        nonceB: ByteArray,
        ephemeralPubB: ByteArray,
        timestampMs: Long,
    ): ByteArray = (
        "RESP|$PROTOCOL_VERSION|$sessionId|$responderNodeId|$initiatorNodeId|$timestampMs|"
        ).toByteArray(Charsets.UTF_8) + nonceA + nonceB + ephemeralPubB
}
