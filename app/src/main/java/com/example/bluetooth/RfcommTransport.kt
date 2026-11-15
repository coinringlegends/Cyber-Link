package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.example.crypto.EncryptionManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bluetooth Classic RFCOMM transport for high-bandwidth payloads:
 * - Mapsforge .map / .poi / .clroute package transfers
 * - Real-time Audio Call PCM frames
 * - Real-time Video Call adaptive JPEG frames (when bandwidth allows)
 *
 * Strictly segregated from BLE GATT; never claims RFCOMM is active unless socket is connected.
 */
@SuppressLint("MissingPermission")
class RfcommTransport(
    private val scope: CoroutineScope,
    private val onFrameReceived: (peerAddress: String, frameType: Byte, payload: ByteArray) -> Unit,
    private val onRfcommStateChanged: (peerAddress: String, connected: Boolean, error: String?) -> Unit,
) {
    companion object {
        const val FRAME_PROTOCOL_ENVELOPE: Byte = 0x01
        const val FRAME_AUDIO_PCM: Byte = 0x02
        const val FRAME_VIDEO_JPEG: Byte = 0x03
        const val FRAME_MAP_CHUNK: Byte = 0x04
        const val MAX_FRAME_BYTES = 1024 * 1024
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val readJobs = ConcurrentHashMap<String, Job>()

    fun isConnected(address: String?): Boolean =
        address != null && activeSockets[address]?.isConnected == true

    fun startServer(adapter: BluetoothAdapter?) {
        if (adapter == null || !adapter.isEnabled || acceptJob?.isActive == true) return
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord(
                    CyberLinkUuids.RFCOMM_SERVICE_NAME,
                    CyberLinkUuids.RFCOMM_SDP_UUID,
                )
                serverSocket = srv
                while (isActive) {
                    val socket = srv.accept()
                    val addr = socket.remoteDevice.address
                    attachSocket(addr, socket)
                }
            } catch (_: IOException) {
                // Server socket closed cleanly
            }
        }
    }

    fun connectClient(device: BluetoothDevice): Result<Unit> {
        return try {
            val socket = device.createRfcommSocketToServiceRecord(CyberLinkUuids.RFCOMM_SDP_UUID)
            socket.connect()
            attachSocket(device.address, socket)
            Result.success(Unit)
        } catch (e: IOException) {
            onRfcommStateChanged(device.address, false, "RFCOMM Verbindung fehlgeschlagen: ${e.message}")
            Result.failure(e)
        }
    }

    private fun attachSocket(address: String, socket: BluetoothSocket) {
        activeSockets[address]?.let { old -> runCatching { old.close() } }
        activeSockets[address] = socket
        onRfcommStateChanged(address, true, null)

        readJobs[address]?.cancel()
        readJobs[address] = scope.launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive && socket.isConnected) {
                    val frameType = input.readByte()
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME_BYTES) {
                        throw IOException("Ungültige RFCOMM Frame-Länge: $len")
                    }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    onFrameReceived(address, frameType, buf)
                }
            } catch (e: IOException) {
                closePeer(address, "RFCOMM getrennt: ${e.message}")
            }
        }
    }

    fun sendFrame(
        address: String,
        frameType: Byte,
        payload: ByteArray,
        cipher: EncryptionManager.SessionCipher?,
    ): Result<Unit> {
        val socket = activeSockets[address]
            ?: return Result.failure(IllegalStateException("Kein aktiver RFCOMM-Socket für $address"))
        if (!socket.isConnected) {
            return Result.failure(IllegalStateException("RFCOMM-Socket getrennt"))
        }
        return try {
            val wirePayload = cipher?.encrypt(payload) ?: return Result.failure(
                SecurityException("RFCOMM-Übertragung ohne aktive Session-Verschlüsselung blockiert")
            )
            synchronized(socket) {
                val out = DataOutputStream(socket.outputStream)
                out.writeByte(frameType.toInt())
                out.writeInt(wirePayload.size)
                out.write(wirePayload)
                out.flush()
            }
            Result.success(Unit)
        } catch (e: IOException) {
            closePeer(address, "RFCOMM Schreibfehler: ${e.message}")
            Result.failure(e)
        }
    }

    fun closePeer(address: String, reason: String? = null) {
        readJobs.remove(address)?.cancel()
        activeSockets.remove(address)?.let { runCatching { it.close() } }
        onRfcommStateChanged(address, false, reason)
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
    }
}
