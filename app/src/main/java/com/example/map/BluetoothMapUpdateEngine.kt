package com.example.map

import android.util.Base64
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.TransportState
import com.example.data.db.MapPackageDao
import com.example.data.db.MapPackageEntity
import com.example.data.db.MapRegionEntity
import com.example.protocol.CyberLinkProtocol
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bluetooth Map Update Engine (V5.0 Section G).
 * Transfers real map/POI/routing packages between two authenticated CYBER LINK devices
 * with chunk ACKs, pause/resume, SHA-256 verification, and atomic installation.
 * Uses RFCOMM when available for large packages; pauses honestly if Bluetooth capacity drops.
 */
class BluetoothMapUpdateEngine(
    private val storageManager: MapStorageManager,
    private val packageDao: MapPackageDao,
    private val bluetoothManager: BluetoothManager,
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB per chunk over RFCOMM / chunked GATT
    }

    data class TransferStatus(
        val packageId: String,
        val regionName: String,
        val peerNodeId: String,
        val direction: String, // "SENDING" or "RECEIVING"
        val currentChunk: Int,
        val totalChunks: Int,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val state: String,
        val errorMessage: String? = null,
    )

    private data class IncomingTransfer(
        val offer: CyberLinkProtocol.ProtocolEnvelope.MapPackageOffer,
        val peerNodeId: String,
        val partFile: File,
        var lastAckedChunk: Int,
    )

    private val _transfers = MutableStateFlow<Map<String, TransferStatus>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferStatus>> = _transfers.asStateFlow()

    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()
    private val outgoingFiles = ConcurrentHashMap<String, Pair<MapRegionEntity, File>>()
    private val pausedTransfers = ConcurrentHashMap<String, Boolean>()

    suspend fun offerRegionToPeer(peerNodeId: String, region: MapRegionEntity): Result<Unit> {
        val session = bluetoothManager.getSession(peerNodeId)
        if (session == null || session.transportState != TransportState.CONNECTED) {
            return Result.failure(IllegalStateException("Peer $peerNodeId ist nicht authentifiziert verbunden"))
        }
        val file = File(region.filePath)
        if (!file.exists()) {
            return Result.failure(IllegalStateException("Kartendatei nicht gefunden: ${region.filePath}"))
        }
        val totalBytes = file.length()
        val totalChunks = ((totalBytes + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt().coerceAtLeast(1)
        val packageId = "${region.regionId}_v${region.version}"
        outgoingFiles[packageId] = region to file

        val offer = CyberLinkProtocol.ProtocolEnvelope.MapPackageOffer(
            packageId = packageId,
            regionId = region.regionId,
            regionName = region.regionName,
            packageType = MapStorageManager.MapFileFormat.MAPSFORGE_MAP.packageType,
            version = region.version,
            fileSize = totalBytes,
            sha256 = region.sha256,
            totalChunks = totalChunks,
            chunkSize = DEFAULT_CHUNK_SIZE,
        )
        return bluetoothManager.sendEnvelope(peerNodeId, offer, preferRfcomm = true)
    }

    suspend fun onOfferReceived(
        peerNodeId: String,
        offer: CyberLinkProtocol.ProtocolEnvelope.MapPackageOffer,
    ) {
        val partFile = File(storageManager.stagingDir, "${offer.packageId}.part")
        val resumeChunk = if (partFile.exists()) {
            (partFile.length() / offer.chunkSize).toInt().coerceIn(0, offer.totalChunks)
        } else {
            0
        }
        incomingTransfers[offer.packageId] = IncomingTransfer(
            offer = offer,
            peerNodeId = peerNodeId,
            partFile = partFile,
            lastAckedChunk = resumeChunk - 1,
        )
        updateTransferUi(
            TransferStatus(
                packageId = offer.packageId,
                regionName = offer.regionName,
                peerNodeId = peerNodeId,
                direction = "RECEIVING",
                currentChunk = resumeChunk,
                totalChunks = offer.totalChunks,
                bytesTransferred = partFile.length(),
                totalBytes = offer.fileSize,
                state = "AWAITING_CONFIRMATION",
            )
        )
    }

    suspend fun acceptIncomingOffer(packageId: String): Result<Unit> {
        val transfer = incomingTransfers[packageId]
            ?: return Result.failure(IllegalStateException("Angebot $packageId nicht gefunden"))
        val avail = storageManager.getAvailableBytes()
        if (avail < transfer.offer.fileSize + 8 * 1024 * 1024L) {
            return Result.failure(IllegalStateException("Speicherplatz unzureichend für ${transfer.offer.regionName}"))
        }
        pausedTransfers[packageId] = false
        val resumeFrom = (transfer.lastAckedChunk + 1).coerceAtLeast(0)
        val req = CyberLinkProtocol.ProtocolEnvelope.MapPackageRequest(
            packageId = packageId,
            accepted = true,
            resumeFromChunk = resumeFrom,
        )
        updateTransferUi(
            TransferStatus(
                packageId = packageId,
                regionName = transfer.offer.regionName,
                peerNodeId = transfer.peerNodeId,
                direction = "RECEIVING",
                currentChunk = resumeFrom,
                totalChunks = transfer.offer.totalChunks,
                bytesTransferred = transfer.partFile.length(),
                totalBytes = transfer.offer.fileSize,
                state = "TRANSFERRING",
            )
        )
        return bluetoothManager.sendEnvelope(transfer.peerNodeId, req, preferRfcomm = true)
    }

    suspend fun onPackageRequestReceived(
        peerNodeId: String,
        request: CyberLinkProtocol.ProtocolEnvelope.MapPackageRequest,
    ): Result<Unit> {
        if (!request.accepted) {
            updateTransferUi(
                TransferStatus(
                    packageId = request.packageId,
                    regionName = request.packageId,
                    peerNodeId = peerNodeId,
                    direction = "SENDING",
                    currentChunk = 0,
                    totalChunks = 1,
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    state = "REJECTED",
                )
            )
            return Result.success(Unit)
        }
        val (region, file) = outgoingFiles[request.packageId]
            ?: return Result.failure(IllegalStateException("Paket ${request.packageId} nicht bereitgestellt"))

        val transport = bluetoothManager.getEffectiveTransportForPeer(peerNodeId)
        if (transport == ActiveTransport.NONE) {
            return Result.failure(IllegalStateException("Übertragung pausiert: Bluetooth-Transport getrennt"))
        }

        val totalBytes = file.length()
        val totalChunks = ((totalBytes + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt().coerceAtLeast(1)
        pausedTransfers[request.packageId] = false

        RandomAccessFile(file, "r").use { raf ->
            for (chunkIdx in request.resumeFromChunk until totalChunks) {
                if (pausedTransfers[request.packageId] == true ||
                    bluetoothManager.getEffectiveTransportForPeer(peerNodeId) == ActiveTransport.NONE
                ) {
                    updateTransferUi(
                        TransferStatus(
                            packageId = request.packageId,
                            regionName = region.regionName,
                            peerNodeId = peerNodeId,
                            direction = "SENDING",
                            currentChunk = chunkIdx,
                            totalChunks = totalChunks,
                            bytesTransferred = chunkIdx.toLong() * DEFAULT_CHUNK_SIZE,
                            totalBytes = totalBytes,
                            state = "PAUSED",
                            errorMessage = "Übertragung bei Chunk $chunkIdx pausiert (Bluetooth-Kapazität/Verbindung unterbrochen)",
                        )
                    )
                    return Result.failure(IllegalStateException("Übertragung pausiert bei Chunk $chunkIdx"))
                }

                val offset = chunkIdx.toLong() * DEFAULT_CHUNK_SIZE
                val len = minOf(DEFAULT_CHUNK_SIZE.toLong(), totalBytes - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)

                val chunkEnv = CyberLinkProtocol.ProtocolEnvelope.MapChunkPacket(
                    packageId = request.packageId,
                    chunkIndex = chunkIdx,
                    totalChunks = totalChunks,
                    dataB64 = Base64.encodeToString(buf, Base64.NO_WRAP),
                )
                val sendRes = bluetoothManager.sendEnvelope(peerNodeId, chunkEnv, preferRfcomm = true)
                if (sendRes.isFailure) {
                    updateTransferUi(
                        TransferStatus(
                            packageId = request.packageId,
                            regionName = region.regionName,
                            peerNodeId = peerNodeId,
                            direction = "SENDING",
                            currentChunk = chunkIdx,
                            totalChunks = totalChunks,
                            bytesTransferred = offset,
                            totalBytes = totalBytes,
                            state = "PAUSED",
                            errorMessage = sendRes.exceptionOrNull()?.message,
                        )
                    )
                    return sendRes
                }

                updateTransferUi(
                    TransferStatus(
                        packageId = request.packageId,
                        regionName = region.regionName,
                        peerNodeId = peerNodeId,
                        direction = "SENDING",
                        currentChunk = chunkIdx + 1,
                        totalChunks = totalChunks,
                        bytesTransferred = offset + len,
                        totalBytes = totalBytes,
                        state = if (chunkIdx + 1 == totalChunks) "COMPLETED" else "TRANSFERRING",
                    )
                )
            }
        }
        return Result.success(Unit)
    }

    suspend fun onChunkReceived(
        peerNodeId: String,
        chunk: CyberLinkProtocol.ProtocolEnvelope.MapChunkPacket,
    ) {
        val transfer = incomingTransfers[chunk.packageId] ?: return
        val bytes = Base64.decode(chunk.dataB64, Base64.NO_WRAP)

        RandomAccessFile(transfer.partFile, "rw").use { raf ->
            raf.seek(chunk.chunkIndex.toLong() * transfer.offer.chunkSize)
            raf.write(bytes)
        }
        transfer.lastAckedChunk = chunk.chunkIndex

        val isLast = chunk.chunkIndex + 1 >= chunk.totalChunks
        if (!isLast) {
            bluetoothManager.sendEnvelope(
                peerNodeId,
                CyberLinkProtocol.ProtocolEnvelope.MapChunkAck(
                    packageId = chunk.packageId,
                    ackedChunkIndex = chunk.chunkIndex,
                    completed = false,
                ),
                preferRfcomm = true,
            )
            updateTransferUi(
                TransferStatus(
                    packageId = chunk.packageId,
                    regionName = transfer.offer.regionName,
                    peerNodeId = peerNodeId,
                    direction = "RECEIVING",
                    currentChunk = chunk.chunkIndex + 1,
                    totalChunks = chunk.totalChunks,
                    bytesTransferred = transfer.partFile.length(),
                    totalBytes = transfer.offer.fileSize,
                    state = "TRANSFERRING",
                )
            )
            return
        }

        // Last chunk received: verify SHA-256 and atomically install
        val installRes = FileInputStream(transfer.partFile).use { input ->
            storageManager.importFromStream(
                inputStream = input,
                originalFileName = "${transfer.offer.regionId}.map",
                format = MapStorageManager.MapFileFormat.MAPSFORGE_MAP,
                regionName = transfer.offer.regionName,
                version = transfer.offer.version,
                expectedSha256 = transfer.offer.sha256,
                knownSizeBytes = transfer.offer.fileSize,
            )
        }
        if (installRes.isSuccess) {
            transfer.partFile.delete()
            bluetoothManager.sendEnvelope(
                peerNodeId,
                CyberLinkProtocol.ProtocolEnvelope.MapChunkAck(
                    packageId = chunk.packageId,
                    ackedChunkIndex = chunk.chunkIndex,
                    completed = true,
                    sha256Verified = true,
                ),
                preferRfcomm = true,
            )
            updateTransferUi(
                TransferStatus(
                    packageId = chunk.packageId,
                    regionName = transfer.offer.regionName,
                    peerNodeId = peerNodeId,
                    direction = "RECEIVING",
                    currentChunk = chunk.totalChunks,
                    totalChunks = chunk.totalChunks,
                    bytesTransferred = transfer.offer.fileSize,
                    totalBytes = transfer.offer.fileSize,
                    state = "INSTALLED",
                )
            )
        } else {
            val err = installRes.exceptionOrNull()?.message ?: "SHA-256 Prüfung fehlgeschlagen"
            packageDao.upsert(
                MapPackageEntity(
                    packageId = chunk.packageId,
                    regionId = transfer.offer.regionId,
                    packageType = transfer.offer.packageType,
                    version = transfer.offer.version,
                    checksum = transfer.offer.sha256,
                    transferState = "FAILED",
                    bytesTransferred = transfer.partFile.length(),
                    totalBytes = transfer.offer.fileSize,
                    filePath = transfer.partFile.absolutePath,
                )
            )
            updateTransferUi(
                TransferStatus(
                    packageId = chunk.packageId,
                    regionName = transfer.offer.regionName,
                    peerNodeId = peerNodeId,
                    direction = "RECEIVING",
                    currentChunk = chunk.chunkIndex + 1,
                    totalChunks = chunk.totalChunks,
                    bytesTransferred = transfer.partFile.length(),
                    totalBytes = transfer.offer.fileSize,
                    state = "FAILED",
                    errorMessage = err,
                )
            )
        }
    }

    fun onChunkAckReceived(ack: CyberLinkProtocol.ProtocolEnvelope.MapChunkAck) {
        val current = _transfers.value[ack.packageId] ?: return
        if (ack.completed && ack.sha256Verified) {
            updateTransferUi(current.copy(state = "REMOTE_INSTALLED"))
        }
    }

    fun pauseTransfer(packageId: String) {
        pausedTransfers[packageId] = true
    }

    private fun updateTransferUi(status: TransferStatus) {
        val next = _transfers.value.toMutableMap()
        next[status.packageId] = status
        _transfers.value = next
    }
}
