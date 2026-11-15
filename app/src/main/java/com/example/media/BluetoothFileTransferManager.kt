package com.example.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.crypto.EncryptionManager
import com.example.crypto.LocalStorageCipher
import com.example.data.db.MessageDao
import com.example.data.db.MessageEntity
import com.example.protocol.CyberLinkProtocol
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real encrypted Bluetooth Image & File transfer engine (zero placeholders).
 * Transfers file bytes in verified chunks over RFCOMM (preferred) or BLE GATT,
 * checks SHA-256 integrity on receipt, and confirms delivery via FileTransferAck.
 */
class BluetoothFileTransferManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val messageDao: MessageDao,
    private val localStorageCipher: LocalStorageCipher,
) {
    companion object {
        const val MAX_BLE_FILE_BYTES = 512 * 1024L      // 512 KB cap over BLE GATT
        const val MAX_RFCOMM_FILE_BYTES = 10 * 1024 * 1024L // 10 MB cap over RFCOMM
        const val CHUNK_SIZE_BLE = 384
        const val CHUNK_SIZE_RFCOMM = 16 * 1024
    }

    data class TransferStatus(
        val transferId: String,
        val messageId: String,
        val fileName: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val stateLabel: String,
    ) {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    private data class IncomingTransfer(
        val offer: CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer,
        val fromPeerNodeId: String,
        val tempFile: File,
        val chunks: Array<ByteArray?> = arrayOfNulls(offer.totalChunks),
        var receivedChunks: Int = 0,
        var bytesReceived: Long = 0L,
    )

    private val mediaRoot = File(context.filesDir, "cyber_media").apply { mkdirs() }
    private val sentDir = File(mediaRoot, "sent").apply { mkdirs() }
    private val recvDir = File(mediaRoot, "received").apply { mkdirs() }
    private val tmpDir = File(mediaRoot, "recv_tmp").apply { mkdirs() }

    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()

    private val _transfers = MutableStateFlow<Map<String, TransferStatus>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferStatus>> = _transfers.asStateFlow()

    suspend fun sendUriToPeer(
        peerNodeId: String,
        uri: Uri,
        isImage: Boolean,
        priority: String = "NORMAL",
        groupId: String? = null,
    ): Result<String> = runCatching {
        val (displayName, mimeType) = resolveUriMetadata(uri, isImage)
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank {
            if (isImage) "image.jpg" else "file.bin"
        }
        val msgId = UUID.randomUUID().toString()
        val targetFile = File(sentDir, "${msgId}_$safeName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Ausgewählte Datei konnte nicht gelesen werden")

        val fileSize = targetFile.length()
        if (fileSize <= 0L) {
            targetFile.delete()
            throw IllegalStateException("Datei ist leer (0 Bytes)")
        }

        val transport = bluetoothManager.getEffectiveTransportForPeer(peerNodeId)
        val useRfcomm = transport == ActiveTransport.CLASSIC_RFCOMM || transport == ActiveTransport.DUAL_BLE_RFCOMM
        val maxAllowed = if (useRfcomm) MAX_RFCOMM_FILE_BYTES else MAX_BLE_FILE_BYTES
        if (transport != ActiveTransport.NONE && fileSize > maxAllowed) {
            val limitText = if (useRfcomm) "10 MB (RFCOMM)" else "512 KB (BLE-only; RFCOMM für größere Dateien aktivieren)"
            targetFile.delete()
            throw IllegalStateException("Datei zu groß (${fileSize / 1024} KB). Limit: $limitText")
        }

        val sha256 = computeSha256Hex(targetFile)
        val msgType = if (isImage) "IMAGE" else "FILE"
        val displayContent = if (isImage) "Bild: $safeName (${fileSize / 1024} KB)" else "Datei: $safeName (${fileSize / 1024} KB)"

        messageDao.upsert(
            MessageEntity(
                messageId = msgId,
                peerNodeId = peerNodeId,
                groupId = groupId,
                senderNodeId = bluetoothManager.encryptionManager.nodeId,
                content = localStorageCipher.encryptString(displayContent),
                messageType = msgType,
                attachmentPath = targetFile.absolutePath,
                priority = priority,
                status = "QUEUED",
                timestamp = System.currentTimeMillis(),
            )
        )

        if (transport == ActiveTransport.NONE) {
            // Saved in offline queue for transmission when peer connects
            return@runCatching msgId
        }

        transmitFileInternal(
            peerNodeId = peerNodeId,
            messageId = msgId,
            file = targetFile,
            fileName = safeName,
            mimeType = mimeType,
            messageType = msgType,
            sha256 = sha256,
            priority = priority,
            groupId = groupId,
            useRfcomm = useRfcomm,
        ).getOrThrow()
        msgId
    }

    suspend fun transmitQueuedAttachment(msg: MessageEntity): Result<Unit> {
        val path = msg.attachmentPath ?: return Result.failure(IllegalStateException("Kein Dateipfad vorhanden"))
        val file = File(path)
        if (!file.exists()) return Result.failure(IllegalStateException("Lokale Datei nicht mehr vorhanden"))
        val transport = bluetoothManager.getEffectiveTransportForPeer(msg.peerNodeId)
        if (transport == ActiveTransport.NONE) {
            return Result.failure(IllegalStateException("Peer nicht verbunden"))
        }
        val useRfcomm = transport == ActiveTransport.CLASSIC_RFCOMM || transport == ActiveTransport.DUAL_BLE_RFCOMM
        val sha256 = computeSha256Hex(file)
        val mime = if (msg.messageType == "IMAGE") "image/*" else "application/octet-stream"
        return transmitFileInternal(
            peerNodeId = msg.peerNodeId,
            messageId = msg.messageId,
            file = file,
            fileName = file.name.substringAfter('_', file.name),
            mimeType = mime,
            messageType = msg.messageType,
            sha256 = sha256,
            priority = msg.priority,
            groupId = msg.groupId,
            useRfcomm = useRfcomm,
        )
    }

    private suspend fun transmitFileInternal(
        peerNodeId: String,
        messageId: String,
        file: File,
        fileName: String,
        mimeType: String,
        messageType: String,
        sha256: String,
        priority: String,
        groupId: String?,
        useRfcomm: Boolean,
    ): Result<Unit> = runCatching {
        val bytes = file.readBytes()
        val chunkSize = if (useRfcomm) CHUNK_SIZE_RFCOMM else CHUNK_SIZE_BLE
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val transferId = UUID.randomUUID().toString()

        updateTransfer(transferId, messageId, fileName, 0L, bytes.size.toLong(), "Sende Angebot…")

        val offer = CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer(
            transferId = transferId,
            messageId = messageId,
            senderNodeId = bluetoothManager.encryptionManager.nodeId,
            recipientNodeId = peerNodeId,
            groupId = groupId,
            fileName = fileName,
            mimeType = mimeType,
            messageType = messageType,
            fileSize = bytes.size.toLong(),
            sha256 = sha256,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            priority = priority,
            timestampMs = System.currentTimeMillis(),
        )
        bluetoothManager.sendEnvelope(peerNodeId, offer, preferRfcomm = useRfcomm).getOrThrow()

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, bytes.size)
            val slice = bytes.copyOfRange(start, end)
            val chunkEnv = CyberLinkProtocol.ProtocolEnvelope.FileTransferChunk(
                transferId = transferId,
                chunkIndex = i,
                totalChunks = totalChunks,
                dataB64 = Base64.encodeToString(slice, Base64.NO_WRAP),
            )
            bluetoothManager.sendEnvelope(peerNodeId, chunkEnv, preferRfcomm = useRfcomm).getOrThrow()
            updateTransfer(
                transferId = transferId,
                messageId = messageId,
                fileName = fileName,
                bytesTransferred = end.toLong(),
                totalBytes = bytes.size.toLong(),
                stateLabel = "Übertrage ${i + 1}/$totalChunks",
            )
        }

        messageDao.updateStatus(messageId, "SENT")
        updateTransfer(transferId, messageId, fileName, bytes.size.toLong(), bytes.size.toLong(), "Warte auf SHA-256 ACK…")
    }

    suspend fun onOfferReceived(
        fromPeerNodeId: String,
        offer: CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer,
    ) {
        if (offer.fileSize <= 0L || offer.fileSize > MAX_RFCOMM_FILE_BYTES || offer.totalChunks <= 0) {
            bluetoothManager.sendEnvelope(
                fromPeerNodeId,
                CyberLinkProtocol.ProtocolEnvelope.FileTransferAck(
                    transferId = offer.transferId,
                    messageId = offer.messageId,
                    ackedChunkIndex = -1,
                    completed = false,
                    sha256Verified = false,
                    error = "Ungültige Dateigröße oder Chunk-Anzahl",
                ),
            )
            return
        }
        val safeName = offer.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tempFile = File(tmpDir, "${offer.transferId}_$safeName.part")
        incomingTransfers[offer.transferId] = IncomingTransfer(
            offer = offer,
            fromPeerNodeId = fromPeerNodeId,
            tempFile = tempFile,
        )
        updateTransfer(
            transferId = offer.transferId,
            messageId = offer.messageId,
            fileName = offer.fileName,
            bytesTransferred = 0L,
            totalBytes = offer.fileSize,
            stateLabel = "Empfange…",
        )
    }

    suspend fun onChunkReceived(
        fromPeerNodeId: String,
        chunk: CyberLinkProtocol.ProtocolEnvelope.FileTransferChunk,
    ) {
        val state = incomingTransfers[chunk.transferId] ?: return
        if (state.fromPeerNodeId != fromPeerNodeId) return
        if (chunk.chunkIndex < 0 || chunk.chunkIndex >= state.offer.totalChunks) return
        if (state.chunks[chunk.chunkIndex] == null) {
            val decoded = runCatching { Base64.decode(chunk.dataB64, Base64.NO_WRAP) }.getOrNull() ?: return
            state.chunks[chunk.chunkIndex] = decoded
            state.receivedChunks++
            state.bytesReceived += decoded.size
        }

        updateTransfer(
            transferId = chunk.transferId,
            messageId = state.offer.messageId,
            fileName = state.offer.fileName,
            bytesTransferred = state.bytesReceived,
            totalBytes = state.offer.fileSize,
            stateLabel = "Empfangen ${state.receivedChunks}/${state.offer.totalChunks}",
        )

        if (state.receivedChunks == state.offer.totalChunks) {
            incomingTransfers.remove(chunk.transferId)
            finalizeIncomingTransfer(state)
        }
    }

    private suspend fun finalizeIncomingTransfer(state: IncomingTransfer) {
        val offer = state.offer
        val safeName = offer.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        FileOutputStream(state.tempFile).use { out ->
            for (part in state.chunks) {
                if (part == null) {
                    state.tempFile.delete()
                    sendFailAck(state.fromPeerNodeId, offer, "Unvollständige Chunks")
                    return
                }
                out.write(part)
            }
        }

        val actualSha = computeSha256Hex(state.tempFile)
        if (!actualSha.equals(offer.sha256, ignoreCase = true)) {
            state.tempFile.delete()
            updateTransfer(offer.transferId, offer.messageId, offer.fileName, 0L, offer.fileSize, "SHA-256 Fehler")
            sendFailAck(state.fromPeerNodeId, offer, "SHA-256 Prüfsumme stimmt nicht überein")
            return
        }

        val finalFile = File(recvDir, "${offer.messageId}_$safeName")
        if (finalFile.exists()) finalFile.delete()
        if (!state.tempFile.renameTo(finalFile)) {
            state.tempFile.copyTo(finalFile, overwrite = true)
            state.tempFile.delete()
        }

        val label = if (offer.messageType == "IMAGE") {
            "Bild: $safeName (${offer.fileSize / 1024} KB)"
        } else {
            "Datei: $safeName (${offer.fileSize / 1024} KB)"
        }
        messageDao.upsert(
            MessageEntity(
                messageId = offer.messageId,
                peerNodeId = if (offer.groupId == null) state.fromPeerNodeId else offer.recipientNodeId,
                groupId = offer.groupId,
                senderNodeId = offer.senderNodeId,
                content = localStorageCipher.encryptString(label),
                messageType = offer.messageType,
                attachmentPath = finalFile.absolutePath,
                priority = offer.priority,
                status = "DELIVERED",
                timestamp = offer.timestampMs,
            )
        )

        updateTransfer(offer.transferId, offer.messageId, offer.fileName, offer.fileSize, offer.fileSize, "Abgeschlossen ✓")
        bluetoothManager.sendEnvelope(
            state.fromPeerNodeId,
            CyberLinkProtocol.ProtocolEnvelope.FileTransferAck(
                transferId = offer.transferId,
                messageId = offer.messageId,
                ackedChunkIndex = offer.totalChunks - 1,
                completed = true,
                sha256Verified = true,
            ),
        )
    }

    private suspend fun sendFailAck(
        peerNodeId: String,
        offer: CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer,
        reason: String,
    ) {
        bluetoothManager.sendEnvelope(
            peerNodeId,
            CyberLinkProtocol.ProtocolEnvelope.FileTransferAck(
                transferId = offer.transferId,
                messageId = offer.messageId,
                ackedChunkIndex = -1,
                completed = false,
                sha256Verified = false,
                error = reason,
            ),
        )
    }

    suspend fun onAckReceived(ack: CyberLinkProtocol.ProtocolEnvelope.FileTransferAck) {
        if (ack.completed && ack.sha256Verified) {
            messageDao.updateStatus(ack.messageId, "DELIVERED")
            _transfers.value[ack.transferId]?.let { existing ->
                updateTransfer(
                    ack.transferId,
                    ack.messageId,
                    existing.fileName,
                    existing.totalBytes,
                    existing.totalBytes,
                    "Zugestellt ✓ (SHA-256 OK)",
                )
            }
        } else if (ack.error != null) {
            messageDao.updateStatus(ack.messageId, "FAILED")
            _transfers.value[ack.transferId]?.let { existing ->
                updateTransfer(
                    ack.transferId,
                    ack.messageId,
                    existing.fileName,
                    existing.bytesTransferred,
                    existing.totalBytes,
                    "Fehler: ${ack.error}",
                )
            }
        }
    }

    private fun resolveUriMetadata(uri: Uri, isImage: Boolean): Pair<String, String> {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: if (isImage) "image/*" else "application/octet-stream"
        return (name ?: uri.lastPathSegment ?: if (isImage) "bild.jpg" else "datei.bin") to mime
    }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateTransfer(
        transferId: String,
        messageId: String,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        stateLabel: String,
    ) {
        val next = _transfers.value.toMutableMap()
        next[transferId] = TransferStatus(
            transferId = transferId,
            messageId = messageId,
            fileName = fileName,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            stateLabel = stateLabel,
        )
        _transfers.value = next
    }
}
