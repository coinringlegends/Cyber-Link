package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.crypto.EncryptionManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Abstraction over a stream-oriented RFCOMM socket for testability without mocking crypto or framing.
 */
interface RfcommSocketHandle {
    val remoteAddress: String
    val remoteName: String?
    val isConnected: Boolean
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

private class AndroidBluetoothSocketHandle(
    private val socket: BluetoothSocket,
    override val remoteName: String?,
) : RfcommSocketHandle {
    override val remoteAddress: String = socket.remoteDevice?.address ?: "00:00:00:00:00:00"
    override val isConnected: Boolean get() = socket.isConnected
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream
    override fun close() {
        runCatching { socket.close() }
    }
}

class StreamRfcommSocketHandle(
    override val remoteAddress: String,
    override val remoteName: String?,
    override val inputStream: InputStream,
    override val outputStream: OutputStream,
    private val onClose: () -> Unit = {},
) : RfcommSocketHandle {
    @Volatile
    override var isConnected: Boolean = true

    override fun close() {
        isConnected = false
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        onClose()
    }
}

/**
 * Bluetooth Classic RFCOMM transport with strictly separated Server/Client lifecycles,
 * runtime permission checks, controlled discovery cancellation, bonding support,
 * per-peer concurrency guard, and mandatory bidirectional plaintext + AES-256-GCM probe verification.
 *
 * Strictly segregated from BLE GATT: never reports RFCOMM connected unless socket,
 * streams, bidirectional probe+ACK, and encrypted AES-GCM exchange succeed.
 */
class RfcommTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localNodeId: String,
    private val lookupSessionCipher: (peerNodeId: String) -> EncryptionManager.SessionCipher?,
    private val stopDiscoveryBeforeConnect: () -> Unit = {},
    private val onFrameReceived: (peerAddress: String, peerNodeId: String?, frameType: Byte, payload: ByteArray) -> Unit,
    private val onRfcommStateChanged: (peerAddress: String, peerNodeId: String?, connected: Boolean, bidirectionalReady: Boolean, error: String?) -> Unit,
) {
    companion object {
        const val FRAME_PROTOCOL_ENVELOPE: Byte = 0x01
        const val FRAME_AUDIO_PCM: Byte = 0x02
        const val FRAME_VIDEO_JPEG: Byte = 0x03
        const val FRAME_MAP_CHUNK: Byte = 0x04

        // Bidirectional RFCOMM verification frames
        const val FRAME_PROBE_PING: Byte = 0x10
        const val FRAME_PROBE_PONG: Byte = 0x11
        const val FRAME_PROBE_ACK: Byte = 0x12
        const val FRAME_ENC_PING: Byte = 0x13
        const val FRAME_ENC_PONG: Byte = 0x14

        const val MAX_FRAME_BYTES = 1024 * 1024
        private const val BOND_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
    }

    private data class PendingProbe(
        val probeId: String,
        val peerNodeId: String,
        val pongDeferred: CompletableDeferred<String> = CompletableDeferred(),
        val encPongDeferred: CompletableDeferred<ByteArray> = CompletableDeferred(),
    )

    private val _diagnostics = MutableStateFlow(RfcommDiagnostics(ownNodeId = localNodeId))
    val diagnostics: StateFlow<RfcommDiagnostics> = _diagnostics.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private val activeSockets = ConcurrentHashMap<String, RfcommSocketHandle>()
    private val readJobs = ConcurrentHashMap<String, Job>()
    private val addressToPeerNodeId = ConcurrentHashMap<String, String>()
    private val peerNodeIdToAddress = ConcurrentHashMap<String, String>()
    private val bidirectionalReadyPeers = ConcurrentHashMap<String, Boolean>()
    private val peerConnectLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingProbes = ConcurrentHashMap<String, PendingProbe>()

    private val sentByteCounter = AtomicLong(0L)
    private val recvByteCounter = AtomicLong(0L)

    fun hasConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun describeBondState(bondState: Int): String = when (bondState) {
        BluetoothDevice.BOND_BONDED -> "GEKOPPELT (BOND_BONDED)"
        BluetoothDevice.BOND_BONDING -> "KOPPLUNG LÄUFT (BOND_BONDING)"
        BluetoothDevice.BOND_NONE -> "NICHT GEKOPPELT (BOND_NONE)"
        else -> "UNBEKANNT ($bondState)"
    }

    fun isSocketConnected(classicOrBleAddress: String?, peerNodeId: String? = null): Boolean {
        val resolvedAddr = peerNodeId?.let { peerNodeIdToAddress[it] } ?: classicOrBleAddress
        return resolvedAddr != null && activeSockets[resolvedAddr]?.isConnected == true
    }

    fun isBidirectionallyReady(
        classicAddress: String?,
        bleAddress: String? = null,
        peerNodeId: String? = null,
    ): Boolean {
        val id = peerNodeId
            ?: classicAddress?.let { addressToPeerNodeId[it] }
            ?: bleAddress?.let { addressToPeerNodeId[it] }
            ?: return false
        if (bidirectionalReadyPeers[id] != true) return false
        val boundAddr = peerNodeIdToAddress[id] ?: classicAddress ?: bleAddress ?: return false
        return activeSockets[boundAddr]?.isConnected == true
    }

    fun getBoundPeerNodeId(address: String): String? = addressToPeerNodeId[address]
    fun getBoundAddress(peerNodeId: String): String? = peerNodeIdToAddress[peerNodeId]

    fun bindPeerAddress(address: String, peerNodeId: String) {
        addressToPeerNodeId[address] = peerNodeId
        peerNodeIdToAddress[peerNodeId] = address
    }

    private fun updateDiag(transform: RfcommDiagnostics.() -> RfcommDiagnostics) {
        _diagnostics.value = _diagnostics.value.transform()
    }

    private fun appendTrace(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        updateDiag {
            copy(trace = (trace + line).takeLast(20))
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer(adapter: BluetoothAdapter?) {
        if (!hasConnectPermission()) {
            val err = "BLUETOOTH_CONNECT Berechtigung für RFCOMM-Server fehlt"
            updateDiag {
                copy(
                    serverSocketStatus = RfcommSocketState.FAILED,
                    serverErrorDetail = err,
                    rfcommServerReady = false,
                )
            }
            appendTrace("SERVER_FAILED: $err")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            val err = if (adapter == null) "Kein BluetoothAdapter vorhanden" else "Bluetooth-Adapter ist deaktiviert"
            updateDiag {
                copy(
                    serverSocketStatus = RfcommSocketState.FAILED,
                    serverErrorDetail = err,
                    rfcommServerReady = false,
                )
            }
            appendTrace("SERVER_FAILED: $err")
            return
        }
        if (acceptJob?.isActive == true && _diagnostics.value.rfcommServerReady) return

        acceptJob?.cancel()
        updateDiag {
            copy(
                ownNodeId = localNodeId,
                serverSocketStatus = RfcommSocketState.STARTING,
                serverErrorDetail = null,
                rfcommServerReady = false,
            )
        }
        appendTrace("SERVER_START: Öffne listenUsingRfcommWithServiceRecord(${CyberLinkUuids.RFCOMM_SERVICE_NAME}, ${CyberLinkUuids.RFCOMM_SDP_UUID})")

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord(
                    CyberLinkUuids.RFCOMM_SERVICE_NAME,
                    CyberLinkUuids.RFCOMM_SDP_UUID,
                )
                serverSocket = srv
                updateDiag {
                    copy(
                        serverSocketStatus = RfcommSocketState.LISTENING,
                        serverErrorDetail = null,
                        rfcommServerReady = true,
                    )
                }
                appendTrace("RFCOMM_SERVER_READY: Warte in accept() auf eingehende Verbindung")

                while (isActive) {
                    val socket = srv.accept()
                    val remote = socket.remoteDevice
                    val addr = remote?.address ?: continue
                    val devName = runCatching { remote.name }.getOrNull()
                    val bondText = runCatching { describeBondState(remote.bondState) }.getOrDefault("UNBEKANNT")
                    val handle = AndroidBluetoothSocketHandle(socket, devName)
                    appendTrace("SERVER_ACCEPTED: Eingehender RFCOMM-Socket von $addr (${devName ?: "?"})")
                    updateDiag {
                        copy(
                            bluetoothDeviceName = devName ?: bluetoothDeviceName,
                            pairingStatus = bondText,
                            activePeerAddress = addr,
                            rfcommSocketConnected = true,
                            actualConnectionStatus = "SOCKET OFFEN (warte auf bidirektionale Probe…)",
                        )
                    }
                    attachSocketHandle(handle, existingPeerNodeId = addressToPeerNodeId[addr])
                }
            } catch (sec: SecurityException) {
                val msg = "SecurityException im RFCOMM-Server: ${sec.message}"
                updateDiag {
                    copy(
                        serverSocketStatus = RfcommSocketState.FAILED,
                        serverErrorDetail = msg,
                        rfcommServerReady = false,
                    )
                }
                appendTrace("SERVER_SECURITY_ERROR: $msg")
            } catch (io: IOException) {
                val msg = "IOException im RFCOMM-Server: ${io.message}"
                val closedIntentionally = serverSocket == null
                updateDiag {
                    copy(
                        serverSocketStatus = if (closedIntentionally) RfcommSocketState.IDLE else RfcommSocketState.FAILED,
                        serverErrorDetail = if (closedIntentionally) null else msg,
                        ioExceptionDetail = if (closedIntentionally) ioExceptionDetail else msg,
                        rfcommServerReady = false,
                    )
                }
                if (!closedIntentionally) {
                    appendTrace("SERVER_IO_ERROR: $msg")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectAndVerifyPeer(
        adapter: BluetoothAdapter?,
        peerNodeId: String,
        peerNameHint: String?,
        classicAddressHint: String?,
        bleAddressHint: String?,
        cipher: EncryptionManager.SessionCipher?,
    ): Result<RfcommDiagnostics> {
        val mutex = peerConnectLocks.getOrPut(peerNodeId) { Mutex() }
        if (!mutex.tryLock()) {
            val busyMsg = "Paralleler RFCOMM-Verbindungsversuch für $peerNodeId bereits aktiv"
            updateDiag { copy(peerNodeId = peerNodeId, connectError = busyMsg) }
            appendTrace("CLIENT_BLOCKED: $busyMsg")
            return Result.failure(IllegalStateException(busyMsg))
        }

        try {
            // 1. Check Bluetooth permissions & adapter
            if (!hasConnectPermission() || !hasScanPermission()) {
                val err = "Bluetooth-Berechtigungen (BLUETOOTH_CONNECT / BLUETOOTH_SCAN) fehlen"
                failClient(peerNodeId, peerNameHint, "BERECHTIGUNG FEHLT", err, null)
                return Result.failure(SecurityException(err))
            }
            if (adapter == null || !adapter.isEnabled) {
                val err = if (adapter == null) "BluetoothAdapter nicht verfügbar" else "Bluetooth ist deaktiviert"
                failClient(peerNodeId, peerNameHint, "ADAPTER DEAKTIVIERT", err, null)
                return Result.failure(IllegalStateException(err))
            }
            if (cipher == null) {
                val err = "AES-256-GCM Session-Schlüssel fehlt — erst BLE GATT Handshake abschließen"
                failClient(peerNodeId, peerNameHint, "UNBEKANNT", err, null)
                return Result.failure(SecurityException(err))
            }

            // If a server socket from this peer is already connected, reuse & verify it directly
            val alreadyBoundAddr = peerNodeIdToAddress[peerNodeId]
            if (alreadyBoundAddr != null && activeSockets[alreadyBoundAddr]?.isConnected == true) {
                appendTrace("REUSE_ACCEPTED_SOCKET: Verwende bereits akzeptierten Socket für $peerNodeId ($alreadyBoundAddr)")
                return runBidirectionalTestOnOpenSocket(alreadyBoundAddr, peerNodeId, cipher)
            }

            // 2. Identify target Classic BluetoothDevice (prefer Classic MAC or bonded device over rotating BLE RPA)
            val targetDevice = resolveTargetBluetoothDevice(adapter, peerNameHint, classicAddressHint, bleAddressHint)
            if (targetDevice == null) {
                val err = "Kein gültiges Bluetooth Classic Zielgerät für $peerNodeId gefunden"
                failClient(peerNodeId, peerNameHint, "NICHT GEKOPPELT", err, null)
                return Result.failure(IllegalStateException(err))
            }

            val resolvedName = runCatching { targetDevice.name }.getOrNull() ?: peerNameHint ?: targetDevice.address
            val targetAddr = targetDevice.address
            var bondStatusText = describeBondState(targetDevice.bondState)

            updateDiag {
                copy(
                    ownNodeId = localNodeId,
                    peerNodeId = peerNodeId,
                    bluetoothDeviceName = resolvedName,
                    pairingStatus = bondStatusText,
                    clientSocketStatus = RfcommSocketState.PAIRING,
                    connectError = null,
                    ioExceptionDetail = null,
                    actualConnectionStatus = "PAIRING PRÜFEN…",
                    bidirectionalTestStatus = "AUSSTEHEND",
                    activePeerAddress = targetAddr,
                    rfcommClientConnectOk = false,
                    rfcommSocketConnected = false,
                    rfcommBidirectionalReady = false,
                )
            }
            appendTrace("CLIENT_TARGET: $peerNodeId • Name=$resolvedName • Addr=$targetAddr • $bondStatusText")

            // 3. Ensure Bluetooth Classic pairing / bonding before SDP connect
            if (targetDevice.bondState != BluetoothDevice.BOND_BONDED) {
                val bondRes = ensureDeviceBonded(targetDevice)
                bondStatusText = describeBondState(targetDevice.bondState)
                if (bondRes.isFailure) {
                    val err = bondRes.exceptionOrNull()?.message ?: "Bluetooth-Pairing fehlgeschlagen"
                    failClient(peerNodeId, resolvedName, bondStatusText, err, null)
                    return Result.failure(bondRes.exceptionOrNull() ?: IllegalStateException(err))
                }
                appendTrace("PAIRING_OK: $targetAddr ist jetzt $bondStatusText")
            }

            // 4. Controlled stop of BLE scan & Classic discovery before socket.connect()
            stopDiscoveryBeforeConnect()
            if (runCatching { adapter.isDiscovering }.getOrDefault(false)) {
                runCatching { adapter.cancelDiscovery() }
                appendTrace("DISCOVERY_CANCELLED: Bluetooth Classic Discovery vor connect() gestoppt")
            }

            // 5. Connect RFCOMM socket via shared SDP UUID
            updateDiag {
                copy(
                    pairingStatus = describeBondState(targetDevice.bondState),
                    clientSocketStatus = RfcommSocketState.CONNECTING,
                    actualConnectionStatus = "SOCKET VERBINDET…",
                )
            }
            appendTrace("RFCOMM_CLIENT_CONNECT: createRfcommSocketToServiceRecord(${CyberLinkUuids.RFCOMM_SDP_UUID}) -> connect()")

            val socket: BluetoothSocket = try {
                val s = targetDevice.createRfcommSocketToServiceRecord(CyberLinkUuids.RFCOMM_SDP_UUID)
                s.connect()
                s
            } catch (sec: SecurityException) {
                val err = "SecurityException bei socket.connect(): ${sec.message}"
                failClient(peerNodeId, resolvedName, bondStatusText, err, err)
                return Result.failure(sec)
            } catch (io: IOException) {
                val ioMsg = "${io::class.java.simpleName}: ${io.message ?: "Unbekannter IO-Fehler"}"
                val err = "RFCOMM Socket-Verbindung fehlgeschlagen ($targetAddr)"
                failClient(peerNodeId, resolvedName, bondStatusText, err, ioMsg)
                return Result.failure(io)
            }

            if (!socket.isConnected) {
                val err = "Socket gemeldet isConnected == false nach connect()"
                runCatching { socket.close() }
                failClient(peerNodeId, resolvedName, bondStatusText, err, "isConnected == false")
                return Result.failure(IOException(err))
            }

            val handle = AndroidBluetoothSocketHandle(socket, resolvedName)
            bindPeerAddress(targetAddr, peerNodeId)
            bidirectionalReadyPeers.remove(peerNodeId)
            updateDiag {
                copy(
                    rfcommClientConnectOk = true,
                    rfcommSocketConnected = true,
                    clientSocketStatus = RfcommSocketState.SOCKET_CONNECTED,
                    actualConnectionStatus = "SOCKET OFFEN (starte bidirektionalen Test…)",
                )
            }
            appendTrace("RFCOMM_SOCKET_CONNECTED: Socket & Streams offen für $targetAddr")
            attachSocketHandle(handle, existingPeerNodeId = peerNodeId)

            // 6. Execute mandatory bidirectional plaintext + AES-256-GCM test before marking CONNECTED
            return runBidirectionalTestOnOpenSocket(targetAddr, peerNodeId, cipher)
        } finally {
            mutex.unlock()
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveTargetBluetoothDevice(
        adapter: BluetoothAdapter,
        peerNameHint: String?,
        classicAddressHint: String?,
        bleAddressHint: String?,
    ): BluetoothDevice? {
        val cleanClassic = PeerManager.sanitizeBluetoothAddress(classicAddressHint)
        if (cleanClassic != null) {
            return runCatching { adapter.getRemoteDevice(cleanClassic) }.getOrNull()
        }

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isNotEmpty()) {
            // Exact address match first
            bleAddressHint?.let { addr ->
                bonded.firstOrNull { it.address.equals(addr, ignoreCase = true) }?.let { return it }
            }
            // Name match with Classic or Dual-mode bonded device
            if (!peerNameHint.isNullOrBlank()) {
                bonded.firstOrNull { dev ->
                    val nameMatch = runCatching { dev.name }.getOrNull()?.equals(peerNameHint, ignoreCase = true) == true
                    val isClassicCapable = runCatching { dev.type != BluetoothDevice.DEVICE_TYPE_LE }.getOrDefault(true)
                    nameMatch && isClassicCapable
                }?.let { return it }
            }
        }

        val fallbackAddr = PeerManager.sanitizeBluetoothAddress(bleAddressHint) ?: bleAddressHint
        return fallbackAddr?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureDeviceBonded(device: BluetoothDevice): Result<Unit> {
        val current = device.bondState
        if (current == BluetoothDevice.BOND_BONDED) return Result.success(Unit)

        val bondResult = CompletableDeferred<Result<Unit>>()
        val targetAddress = device.address
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changedDev?.address != targetAddress) return
                val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                updateDiag { copy(pairingStatus = describeBondState(newState)) }
                when (newState) {
                    BluetoothDevice.BOND_BONDED -> bondResult.complete(Result.success(Unit))
                    BluetoothDevice.BOND_NONE -> if (prevState == BluetoothDevice.BOND_BONDING) {
                        bondResult.complete(
                            Result.failure(IllegalStateException("Bluetooth-Pairing auf $targetAddress abgelehnt oder abgebrochen"))
                        )
                    }
                }
            }
        }

        var registered = false
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
            appendTrace("PAIRING_START: Starte createBond() für $targetAddress (aktuell ${describeBondState(current)})")

            if (current == BluetoothDevice.BOND_NONE) {
                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started && device.bondState != BluetoothDevice.BOND_BONDED) {
                    return Result.failure(
                        IllegalStateException("createBond() von Android abgelehnt (Classic MAC oder Pairing blockiert)")
                    )
                }
            }
            if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.success(Unit)

            return withTimeoutOrNull(BOND_TIMEOUT_MS) { bondResult.await() }
                ?: Result.failure(IllegalStateException("Bluetooth-Pairing Timeout (${BOND_TIMEOUT_MS}ms) für $targetAddress"))
        } finally {
            if (registered) {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    /**
     * Attaches an open [RfcommSocketHandle] and launches the frame reader loop.
     * Supports piped streams cleanly for deterministic JVM / Robolectric testing.
     */
    fun attachSocketHandle(
        handle: RfcommSocketHandle,
        existingPeerNodeId: String? = null,
    ) {
        val addr = handle.remoteAddress
        existingPeerNodeId?.let { bindPeerAddress(addr, it) }
        activeSockets[addr]?.let { old -> if (old !== handle) runCatching { old.close() } }
        activeSockets[addr] = handle

        onRfcommStateChanged(addr, addressToPeerNodeId[addr], true, false, null)

        readJobs[addr]?.cancel()
        readJobs[addr] = scope.launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(handle.inputStream)
                while (isActive && handle.isConnected) {
                    val frameType = input.readByte()
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME_BYTES) {
                        throw IOException("Ungültige RFCOMM Frame-Länge: $len")
                    }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    val totalWireBytes = 5L + len
                    val recvTotal = recvByteCounter.addAndGet(totalWireBytes)
                    val boundPeer = addressToPeerNodeId[addr]
                    updateDiag { copy(bytesReceived = recvTotal) }
                    handleIncomingWireFrame(addr, boundPeer, frameType, buf, totalWireBytes)
                }
            } catch (e: IOException) {
                val reason = "${e::class.java.simpleName}: ${e.message ?: "Stream geschlossen"}"
                updateDiag { copy(ioExceptionDetail = reason) }
                appendTrace("READ_IO_ERROR ($addr): $reason")
                closePeer(addr, "RFCOMM getrennt: $reason")
            }
        }
    }

    private fun handleIncomingWireFrame(
        address: String,
        boundPeerId: String?,
        frameType: Byte,
        payload: ByteArray,
        wireBytes: Long,
    ) {
        when (frameType) {
            FRAME_PROBE_PING -> {
                val parts = String(payload, Charsets.UTF_8).split("|")
                val senderNodeId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                val probeId = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return
                bindPeerAddress(address, senderNodeId)
                updateDiag {
                    copy(
                        peerNodeId = senderNodeId,
                        activePeerAddress = address,
                        rfcommSocketConnected = true,
                        clientSocketStatus = if (clientSocketStatus == RfcommSocketState.IDLE) {
                            RfcommSocketState.TESTING_BIDIRECTIONAL
                        } else clientSocketStatus,
                        bidirectionalTestStatus = "PING empfangen von $senderNodeId ($wireBytes B) — sende PONG…",
                    )
                }
                appendTrace("PROBE_PING_RX: $senderNodeId ($wireBytes B) -> sende PONG")
                val pong = "PONG|$localNodeId|$senderNodeId|$probeId|$wireBytes".toByteArray(Charsets.UTF_8)
                writeRawFrame(address, FRAME_PROBE_PONG, pong)
            }

            FRAME_PROBE_PONG -> {
                val text = String(payload, Charsets.UTF_8)
                val parts = text.split("|")
                val responderNodeId = parts.getOrNull(1)
                val probeId = parts.getOrNull(3) ?: return
                if (!responderNodeId.isNullOrBlank()) {
                    bindPeerAddress(address, responderNodeId)
                }
                appendTrace("PROBE_PONG_RX: von $responderNodeId ($wireBytes B)")
                pendingProbes[probeId]?.pongDeferred?.complete(text)
            }

            FRAME_PROBE_ACK -> {
                val parts = String(payload, Charsets.UTF_8).split("|")
                val senderNodeId = parts.getOrNull(1) ?: boundPeerId
                appendTrace("PROBE_ACK_RX: Klartext-ACK von $senderNodeId bestätigt")
                updateDiag {
                    copy(
                        bidirectionalTestStatus = "Klartext A→B→A + ACK OK; warte auf AES-GCM Probe…",
                    )
                }
            }

            FRAME_ENC_PING -> {
                val peerId = boundPeerId ?: addressToPeerNodeId[address] ?: return
                val cipher = lookupSessionCipher(peerId)
                if (cipher == null) {
                    val err = "Verschlüsselte RFCOMM-Probe von $peerId abgelehnt: SessionCipher fehlt"
                    updateDiag { copy(connectError = err, bidirectionalTestStatus = err) }
                    appendTrace("ENC_PING_REJECTED: $err")
                    return
                }
                runCatching {
                    val plain = String(cipher.decrypt(payload), Charsets.UTF_8)
                    val parts = plain.split("|")
                    require(parts.size >= 3 && parts[0] == "ENC_PING") { "Ungültiger ENC_PING" }
                    val probeId = parts[2]
                    val encPong = cipher.encrypt("ENC_PONG|$localNodeId|$probeId".toByteArray(Charsets.UTF_8))
                    writeRawFrame(address, FRAME_ENC_PONG, encPong).getOrThrow()
                }.onSuccess {
                    bidirectionalReadyPeers[peerId] = true
                    updateDiag {
                        copy(
                            peerNodeId = peerId,
                            activePeerAddress = address,
                            rfcommSocketConnected = true,
                            rfcommBidirectionalReady = true,
                            actualConnectionStatus = "VERBUNDEN (Bidirektional + AES-256-GCM verifiziert)",
                            bidirectionalTestStatus = "BESTANDEN: A→B→A + ACK + AES-256-GCM",
                            connectError = null,
                        )
                    }
                    appendTrace("ENC_PING_OK -> ENC_PONG gesendet: RFCOMM_BIDIRECTIONAL_READY für $peerId")
                    onRfcommStateChanged(address, peerId, true, true, null)
                }.onFailure { err ->
                    val msg = "AES-GCM RFCOMM Probe fehlgeschlagen: ${err.message}"
                    updateDiag { copy(connectError = msg, bidirectionalTestStatus = msg) }
                    appendTrace("ENC_PING_FAILED: $msg")
                }
            }

            FRAME_ENC_PONG -> {
                val peerId = boundPeerId ?: addressToPeerNodeId[address]
                val cipher = peerId?.let(lookupSessionCipher)
                if (cipher == null) {
                    appendTrace("ENC_PONG_REJECTED: Kein SessionCipher für $address")
                    return
                }
                runCatching {
                    val plain = cipher.decrypt(payload)
                    val parts = String(plain, Charsets.UTF_8).split("|")
                    val probeId = parts.getOrNull(2) ?: error("probeId fehlt")
                    pendingProbes[probeId]?.encPongDeferred?.complete(plain)
                }.onFailure { err ->
                    appendTrace("ENC_PONG_DECRYPT_ERR: ${err.message}")
                }
            }

            else -> {
                onFrameReceived(address, boundPeerId, frameType, payload)
            }
        }
    }

    /**
     * Runs the full mandatory bidirectional verification on an already connected socket:
     * 1) Client->Server PING + Server->Client PONG + Client->Server ACK
     * 2) Client->Server AES-256-GCM encrypted PING + Server->Client AES-256-GCM encrypted PONG
     */
    suspend fun runBidirectionalTestOnOpenSocket(
        address: String,
        peerNodeId: String,
        cipher: EncryptionManager.SessionCipher?,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): Result<RfcommDiagnostics> {
        val handle = activeSockets[address]
            ?: return Result.failure(IllegalStateException("Kein offener RFCOMM-Socket für $address"))
        if (!handle.isConnected) {
            return Result.failure(IllegalStateException("RFCOMM-Socket für $address ist nicht verbunden"))
        }
        if (cipher == null) {
            val err = "AES-256-GCM SessionCipher fehlt — Test abgebrochen"
            failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, err, null)
            return Result.failure(SecurityException(err))
        }

        bindPeerAddress(address, peerNodeId)
        bidirectionalReadyPeers.remove(peerNodeId)
        updateDiag {
            copy(
                peerNodeId = peerNodeId,
                bluetoothDeviceName = handle.remoteName ?: bluetoothDeviceName,
                activePeerAddress = address,
                rfcommSocketConnected = true,
                rfcommBidirectionalReady = false,
                clientSocketStatus = RfcommSocketState.TESTING_BIDIRECTIONAL,
                actualConnectionStatus = "TESTE BIDIREKTIONALEN DATENAUSTAUSCH…",
                bidirectionalTestStatus = "LÄUFT (PING/PONG/ACK + AES-GCM)…",
                connectError = null,
            )
        }

        val probeId = UUID.randomUUID().toString().take(12)
        val pending = PendingProbe(probeId = probeId, peerNodeId = peerNodeId)
        pendingProbes[probeId] = pending

        try {
            // Step 1: Plaintext bidirectional probe A -> B
            val pingPayload = "PING|$localNodeId|$peerNodeId|$probeId|${System.currentTimeMillis()}"
                .toByteArray(Charsets.UTF_8)
            val pingWrite = writeRawFrame(address, FRAME_PROBE_PING, pingPayload)
            if (pingWrite.isFailure) {
                val ex = pingWrite.exceptionOrNull()
                val msg = "Bidirektionaler Test (PING schreiben) fehlgeschlagen: ${ex?.message}"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, ex?.message)
                return Result.failure(ex ?: IOException(msg))
            }

            // Step 2: Await B -> A PONG
            val pongText = withTimeoutOrNull(timeoutMs) { pending.pongDeferred.await() }
            if (pongText == null) {
                val msg = "Bidirektionaler Test Timeout (${timeoutMs}ms): keine PONG-Antwort von $peerNodeId"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, null)
                return Result.failure(IOException(msg))
            }
            val pongParts = pongText.split("|")
            if (pongParts.getOrNull(0) != "PONG" || pongParts.getOrNull(3) != probeId) {
                val msg = "Ungültige PONG-Antwort im bidirektionalen Test: $pongText"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, null)
                return Result.failure(IllegalStateException(msg))
            }

            // Step 3: Send A -> B ACK
            val ackWrite = writeRawFrame(address, FRAME_PROBE_ACK, "ACK|$localNodeId|$probeId".toByteArray(Charsets.UTF_8))
            if (ackWrite.isFailure) {
                val ex = ackWrite.exceptionOrNull()
                val msg = "Bidirektionaler Test (ACK schreiben) fehlgeschlagen: ${ex?.message}"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, ex?.message)
                return Result.failure(ex ?: IOException(msg))
            }

            // Step 4: Encrypted AES-256-GCM bidirectional probe A -> B -> A
            val encPingBytes = cipher.encrypt("ENC_PING|$localNodeId|$probeId".toByteArray(Charsets.UTF_8))
            val encWrite = writeRawFrame(address, FRAME_ENC_PING, encPingBytes)
            if (encWrite.isFailure) {
                val ex = encWrite.exceptionOrNull()
                val msg = "Verschlüsselter RFCOMM-Test fehlgeschlagen: ${ex?.message}"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, ex?.message)
                return Result.failure(ex ?: IOException(msg))
            }

            val encPongPlain = withTimeoutOrNull(timeoutMs) { pending.encPongDeferred.await() }
            if (encPongPlain == null) {
                val msg = "Verschlüsselter AES-256-GCM Test Timeout (${timeoutMs}ms): keine Antwort von $peerNodeId"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, null)
                return Result.failure(IOException(msg))
            }
            val encText = String(encPongPlain, Charsets.UTF_8)
            val encParts = encText.split("|")
            if (encParts.getOrNull(0) != "ENC_PONG" || encParts.getOrNull(2) != probeId) {
                val msg = "AES-256-GCM Antwort ungültig: $encText"
                failClient(peerNodeId, handle.remoteName, _diagnostics.value.pairingStatus, msg, null)
                return Result.failure(SecurityException(msg))
            }

            bidirectionalReadyPeers[peerNodeId] = true
            val sent = sentByteCounter.get()
            val recv = recvByteCounter.get()
            updateDiag {
                copy(
                    peerNodeId = peerNodeId,
                    bluetoothDeviceName = handle.remoteName ?: bluetoothDeviceName,
                    activePeerAddress = address,
                    clientSocketStatus = RfcommSocketState.CONNECTED,
                    rfcommClientConnectOk = true,
                    rfcommSocketConnected = true,
                    rfcommBidirectionalReady = true,
                    connectError = null,
                    ioExceptionDetail = null,
                    actualConnectionStatus = "VERBUNDEN (Bidirektional + AES-256-GCM verifiziert)",
                    bidirectionalTestStatus = "BESTANDEN (A→B→A + ACK + AES-256-GCM • ${sent}B↑ / ${recv}B↓)",
                    bytesSent = sent,
                    bytesReceived = recv,
                )
            }
            appendTrace("RFCOMM_BIDIRECTIONAL_READY: $peerNodeId verifiziert (${sent}B gesendet, ${recv}B empfangen)")
            onRfcommStateChanged(address, peerNodeId, true, true, null)
            return Result.success(_diagnostics.value)
        } finally {
            pendingProbes.remove(probeId)
        }
    }

    private fun writeRawFrame(address: String, frameType: Byte, wirePayload: ByteArray): Result<Long> {
        val handle = activeSockets[address]
            ?: return Result.failure(IllegalStateException("Kein aktiver RFCOMM-Socket für $address"))
        if (!handle.isConnected) {
            return Result.failure(IllegalStateException("RFCOMM-Socket getrennt"))
        }
        return try {
            synchronized(handle) {
                val out = DataOutputStream(handle.outputStream)
                out.writeByte(frameType.toInt())
                out.writeInt(wirePayload.size)
                out.write(wirePayload)
                out.flush()
            }
            val wireBytes = 5L + wirePayload.size
            val totalSent = sentByteCounter.addAndGet(wireBytes)
            updateDiag { copy(bytesSent = totalSent) }
            Result.success(wireBytes)
        } catch (e: IOException) {
            val reason = "${e::class.java.simpleName}: ${e.message ?: "Schreibfehler"}"
            updateDiag { copy(ioExceptionDetail = reason) }
            closePeer(address, "RFCOMM Schreibfehler: $reason")
            Result.failure(e)
        }
    }

    fun sendFrame(
        address: String,
        frameType: Byte,
        payload: ByteArray,
        cipher: EncryptionManager.SessionCipher?,
    ): Result<Unit> {
        val peerId = addressToPeerNodeId[address]
        if (peerId != null && bidirectionalReadyPeers[peerId] != true) {
            return Result.failure(IllegalStateException("RFCOMM noch nicht bidirektional verifiziert"))
        }
        val wirePayload = cipher?.encrypt(payload) ?: return Result.failure(
            SecurityException("RFCOMM-Übertragung ohne aktive AES-256-GCM Session-Verschlüsselung blockiert")
        )
        return writeRawFrame(address, frameType, wirePayload).map { }
    }

    private fun failClient(
        peerNodeId: String,
        deviceName: String?,
        pairingStatus: String,
        error: String,
        ioException: String?,
    ) {
        bidirectionalReadyPeers.remove(peerNodeId)
        updateDiag {
            copy(
                ownNodeId = localNodeId,
                peerNodeId = peerNodeId,
                bluetoothDeviceName = deviceName ?: bluetoothDeviceName,
                pairingStatus = pairingStatus,
                clientSocketStatus = RfcommSocketState.FAILED,
                connectError = error,
                ioExceptionDetail = ioException ?: ioExceptionDetail,
                actualConnectionStatus = "FEHLGESCHLAGEN",
                bidirectionalTestStatus = "FEHLGESCHLAGEN: $error",
                rfcommClientConnectOk = false,
                rfcommSocketConnected = peerNodeIdToAddress[peerNodeId]?.let { activeSockets[it]?.isConnected } == true,
                rfcommBidirectionalReady = false,
            )
        }
        appendTrace("CLIENT_FAILED ($peerNodeId): $error${ioException?.let { " | IO: $it" } ?: ""}")
    }

    fun closePeer(addressOrPeerId: String, reason: String? = null) {
        val resolvedAddr = peerNodeIdToAddress[addressOrPeerId] ?: addressOrPeerId
        val peerId = addressToPeerNodeId.remove(resolvedAddr) ?: addressOrPeerId.takeIf { peerNodeIdToAddress.containsKey(it) }
        peerId?.let {
            peerNodeIdToAddress.remove(it)
            bidirectionalReadyPeers.remove(it)
        }
        readJobs.remove(resolvedAddr)?.cancel()
        activeSockets.remove(resolvedAddr)?.let { runCatching { it.close() } }
        updateDiag {
            copy(
                rfcommSocketConnected = false,
                rfcommBidirectionalReady = false,
                clientSocketStatus = if (clientSocketStatus == RfcommSocketState.CONNECTED) RfcommSocketState.IDLE else clientSocketStatus,
                actualConnectionStatus = reason ?: "GETRENNT",
            )
        }
        onRfcommStateChanged(resolvedAddr, peerId, false, false, reason)
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        val srv = serverSocket
        serverSocket = null
        runCatching { srv?.close() }
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
        addressToPeerNodeId.clear()
        peerNodeIdToAddress.clear()
        bidirectionalReadyPeers.clear()
        updateDiag {
            copy(
                serverSocketStatus = RfcommSocketState.IDLE,
                clientSocketStatus = RfcommSocketState.IDLE,
                rfcommServerReady = false,
                rfcommClientConnectOk = false,
                rfcommSocketConnected = false,
                rfcommBidirectionalReady = false,
                actualConnectionStatus = "GESTOPPT",
            )
        }
    }
}
