package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.battery.BatteryMode
import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredBluetoothPeer(
    val address: String,
    val name: String,
    val nodeId: String?,
    val rssi: Int,
    val lastSeenMs: Long,
)

data class QrPairingProgress(
    val active: Boolean = false,
    val stepNumber: Int = 0, // 1..12
    val stepLabel: String = "",
    val peerName: String? = null,
    val peerNodeId: String? = null,
    val tempDeviceId: String? = null,
    val btAddress: String? = null,
    val cl2Code: String? = null,
    val pairingToken: String? = null,
    val awaitingLocalConfirm: Boolean = false,
    val awaitingRemoteConfirm: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isConnected: Boolean = false,
)

data class IncomingPairingPrompt(
    val sessionId: String,
    val peerNodeId: String,
    val peerName: String,
    val tempDeviceId: String,
    val cl2Code: String,
    val pairingToken: String,
)

/**
 * Single centralized Bluetooth Transport Coordinator for the entire CYBER LINK app.
 * Chats, Contacts, Audio/Video Calls, Mesh Relay, and CYBER MAP all observe this single source of truth.
 */
@SuppressLint("MissingPermission")
class BluetoothManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val encryptionManager: EncryptionManager,
    private val lookupContactPublicKey: (String) -> ByteArray?,
    private val onPersistConsumedToken: (token: String, expiresAtMs: Long) -> Unit = { _, _ -> },
    private val onPeerAuthenticatedCallback: (String) -> Unit = {},
) {
    private val systemBtManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
    private val adapter: BluetoothAdapter? get() = systemBtManager.adapter

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredBluetoothPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredBluetoothPeer>> = _discoveredPeers.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, PeerTransportSession>>(emptyMap())
    val sessions: StateFlow<Map<String, PeerTransportSession>> = _sessions.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _bleDiagnostics = MutableStateFlow(
        BleDiagnostics(
            bluetoothEnabled = isBluetoothEnabled(),
            bleAdvertisingSupported = isBleAdvertisingSupported(),
            ownNodeId = encryptionManager.nodeId,
        )
    )
    val bleDiagnostics: StateFlow<BleDiagnostics> = _bleDiagnostics.asStateFlow()

    private val _qrPairingProgress = MutableStateFlow(QrPairingProgress())
    val qrPairingProgress: StateFlow<QrPairingProgress> = _qrPairingProgress.asStateFlow()

    private val _incomingPairingPrompt = MutableStateFlow<IncomingPairingPrompt?>(null)
    val incomingPairingPrompt: StateFlow<IncomingPairingPrompt?> = _incomingPairingPrompt.asStateFlow()

    private val _incomingEnvelopes = MutableSharedFlow<Pair<PeerTransportSession, CyberLinkProtocol.ProtocolEnvelope>>(extraBufferCapacity = 64)
    val incomingEnvelopes: SharedFlow<Pair<PeerTransportSession, CyberLinkProtocol.ProtocolEnvelope>> = _incomingEnvelopes.asSharedFlow()

    private val _incomingMediaFrames = MutableSharedFlow<Triple<String, Byte, ByteArray>>(extraBufferCapacity = 64)
    val incomingMediaFrames: SharedFlow<Triple<String, Byte, ByteArray>> = _incomingMediaFrames.asSharedFlow()

    private val sessionMap = ConcurrentHashMap<String, PeerTransportSession>()
    private val lastPublishedStates = ConcurrentHashMap<String, TransportState>()
    private val discoveredMap = ConcurrentHashMap<String, DiscoveredBluetoothPeer>()
    private val deviceByAddress = ConcurrentHashMap<String, BluetoothDevice>()

    // Tracks locally generated QR pairing tokens -> expiresAtMs, plus consumed tokens
    private val localIssuedTokens = ConcurrentHashMap<String, Long>()
    private val consumedTokens = ConcurrentHashMap<String, Long>()

    private var scanJob: Job? = null
    private var qrPairingJob: Job? = null
    private var activeScanCallback: ScanCallback? = null
    private var activeScanUsesUuidFilter: Boolean? = null
    private var receiverRegistered = false

    @Volatile
    private var localConfirmDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var remoteConfirmAckDeferred: CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck>? = null

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    updateDiagnostics {
                        copy(
                            bluetoothEnabled = true,
                            bleAdvertisingSupported = isBleAdvertisingSupported(),
                        )
                    }
                    startPeripheralServices()
                }
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    _isAdvertising.value = false
                    _isScanning.value = false
                    updateDiagnostics {
                        copy(
                            bluetoothEnabled = false,
                            advertiseState = DiagnosticComponentState.FAILED,
                            advertiseErrorCode = -1,
                            advertiseErrorDetail = describeAdvertiseError(-1),
                            scannerState = DiagnosticComponentState.FAILED,
                            scanErrorCode = -1,
                            scanErrorDetail = describeScanError(-1),
                            lastAndroidErrorCode = -1,
                        )
                    }
                }
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    val gattClient = GattClient(
        context = context,
        scope = scope,
        localEncryption = encryptionManager,
        onSessionUpdated = { session -> publishSession(session) },
        onApplicationEnvelope = { session, env ->
            routeIncomingEnvelope(session, env)
        },
    )

    val gattServer = GattServer(
        context = context,
        scope = scope,
        localEncryption = encryptionManager,
        getOrCreateSessionForDevice = { device, nodeIdHint ->
            val tempPeerId = "PEER-${device.address.replace(":", "")}"
            val resolvedId = nodeIdHint
                ?: discoveredMap[device.address]?.nodeId
                ?: tempPeerId
            val targetSession = getOrCreateSession(resolvedId, device.address, device.name)
            if (resolvedId != tempPeerId) {
                val staleTemp = sessionMap.remove(tempPeerId)
                if (staleTemp != null && staleTemp !== targetSession) {
                    targetSession.adoptPendingServerStateFrom(staleTemp)
                }
            }
            targetSession
        },
        lookupKnownPublicKey = lookupContactPublicKey,
        onSessionUpdated = { session -> publishSession(session) },
        onApplicationEnvelope = { session, env ->
            routeIncomingEnvelope(session, env)
        },
        onAdvertiseStateChanged = { active, supported, errCode, errDetail ->
            _isAdvertising.value = active
            updateDiagnostics {
                copy(
                    bluetoothEnabled = isBluetoothEnabled(),
                    bleAdvertisingSupported = supported,
                    advertiseState = when {
                        active -> DiagnosticComponentState.STARTED
                        errCode != null -> DiagnosticComponentState.FAILED
                        else -> DiagnosticComponentState.NOT_STARTED
                    },
                    advertiseErrorCode = errCode,
                    advertiseErrorDetail = errDetail,
                    lastAndroidErrorCode = errCode ?: lastAndroidErrorCode,
                )
            }
        },
    )

    val rfcommTransport: RfcommTransport = RfcommTransport(
        context = context,
        scope = scope,
        localNodeId = encryptionManager.nodeId,
        lookupSessionCipher = { peerId -> sessionMap[peerId]?.cipherSession },
        stopDiscoveryBeforeConnect = { stopDiscovery() },
        onFrameReceived = { addr, boundPeerId, frameType, encryptedPayload ->
            val resolvedPeerId = boundPeerId ?: rfcommTransport.getBoundPeerNodeId(addr)
            val session = resolvedPeerId?.let { sessionMap[it] }
                ?: sessionMap.values.firstOrNull { it.classicAddress == addr || it.deviceAddress == addr }
                ?: return@RfcommTransport
            val cipher = session.cipherSession ?: return@RfcommTransport
            runCatching { cipher.decrypt(encryptedPayload) }.onSuccess { plain ->
                if (frameType == RfcommTransport.FRAME_PROTOCOL_ENVELOPE) {
                    runCatching { CyberLinkProtocol.decodeEnvelope(plain) }.onSuccess { env ->
                        routeIncomingEnvelope(session, env)
                    }
                } else {
                    _incomingMediaFrames.tryEmit(Triple(session.peerNodeId, frameType, plain))
                }
            }
        },
        onRfcommStateChanged = { addr, boundPeerId, connected, bidirectionalReady, err ->
            val resolvedPeerId = boundPeerId ?: rfcommTransport.getBoundPeerNodeId(addr)
            val session = resolvedPeerId?.let { sessionMap[it] }
                ?: sessionMap.values.firstOrNull { it.classicAddress == addr || it.deviceAddress == addr }
            if (session != null) {
                if (connected) {
                    session.classicAddress = addr
                }
                session.rfcommBidirectionalReady = bidirectionalReady
                session.rfcommLastError = err
                val diag = rfcommTransport.diagnostics.value
                session.rfcommBytesSent = diag.bytesSent
                session.rfcommBytesReceived = diag.bytesReceived
                publishSession(session)
            }
        },
    )

    val rfcommDiagnostics: StateFlow<RfcommDiagnostics> = rfcommTransport.diagnostics

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isBleAdvertisingSupported(): Boolean {
        val ad = adapter ?: return false
        return ad.isMultipleAdvertisementSupported && ad.bluetoothLeAdvertiser != null
    }

    fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
    }

    fun hasConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateDiagnostics(transform: BleDiagnostics.() -> BleDiagnostics) {
        _bleDiagnostics.value = _bleDiagnostics.value.transform()
    }

    fun getValidLocalBluetoothAddress(): String? =
        PeerManager.getValidBluetoothAddressOrNull(adapter)

    fun registerIssuedPairingToken(token: String, expiresAtMs: Long) {
        localIssuedTokens[token] = expiresAtMs
    }

    fun seedConsumedTokens(tokens: Map<String, Long>) {
        consumedTokens.putAll(tokens)
    }

    fun isTokenConsumed(token: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val exp = consumedTokens[token] ?: return false
        return exp > nowMs
    }

    fun markTokenConsumed(token: String, expiresAtMs: Long) {
        consumedTokens[token] = maxOf(expiresAtMs, System.currentTimeMillis() + 60_000L)
        localIssuedTokens.remove(token)
        onPersistConsumedToken(token, expiresAtMs)
    }

    private fun routeIncomingEnvelope(
        session: PeerTransportSession,
        env: CyberLinkProtocol.ProtocolEnvelope,
    ) {
        when (env) {
            is CyberLinkProtocol.ProtocolEnvelope.PairingConfirmRequest -> {
                val now = System.currentTimeMillis()
                val issuedExpiry = localIssuedTokens[env.pairingToken]
                val valid = issuedExpiry != null && now <= issuedExpiry && !isTokenConsumed(env.pairingToken, now)
                if (!valid) {
                    scope.launch(Dispatchers.IO) {
                        sendEnvelope(
                            session.peerNodeId,
                            CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck(
                                sessionId = env.sessionId,
                                responderNodeId = encryptionManager.nodeId,
                                accepted = false,
                                pairingToken = env.pairingToken,
                                reason = "Pairing-Token unbekannt, abgelaufen oder bereits verwendet",
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return
                }
                _incomingPairingPrompt.value = IncomingPairingPrompt(
                    sessionId = env.sessionId,
                    peerNodeId = env.initiatorNodeId,
                    peerName = env.initiatorName,
                    tempDeviceId = env.tempDeviceId,
                    cl2Code = env.cl2Code,
                    pairingToken = env.pairingToken,
                )
            }

            is CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck -> {
                remoteConfirmAckDeferred?.complete(env)
            }

            else -> {
                _incomingEnvelopes.tryEmit(session to env)
            }
        }
    }

    fun confirmLocalInitiatorPairing(accepted: Boolean) {
        localConfirmDeferred?.complete(accepted)
    }

    fun respondToIncomingPairing(accepted: Boolean, onAcceptedSaveContact: () -> Unit = {}) {
        val prompt = _incomingPairingPrompt.value ?: return
        _incomingPairingPrompt.value = null
        scope.launch(Dispatchers.IO) {
            if (accepted) {
                val exp = localIssuedTokens[prompt.pairingToken] ?: (System.currentTimeMillis() + PeerManager.DEFAULT_QR_TTL_MS)
                markTokenConsumed(prompt.pairingToken, exp)
                onAcceptedSaveContact()
            }
            sendEnvelope(
                prompt.peerNodeId,
                CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck(
                    sessionId = prompt.sessionId,
                    responderNodeId = encryptionManager.nodeId,
                    accepted = accepted,
                    pairingToken = prompt.pairingToken,
                    reason = if (accepted) null else "Verbindung auf Partnergerät abgelehnt",
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            if (!accepted) {
                disconnectPeer(prompt.peerNodeId)
            } else {
                onPeerAuthenticatedCallback(prompt.peerNodeId)
            }
        }
    }

    fun dismissQrPairingProgress() {
        qrPairingJob?.cancel()
        localConfirmDeferred?.complete(false)
        remoteConfirmAckDeferred?.cancel()
        _qrPairingProgress.value = QrPairingProgress()
    }

    /**
     * Executes the strict 12-step QR pairing and connection workflow.
     * Never displays CONNECTED until all 12 steps (including mutual confirmation and bidirectional VERIFY_ACK) pass.
     */
    fun startTwelveStepQrPairing(
        payload: PeerManager.PairingQrPayload,
        onVerifiedAndConnected: suspend (PeerManager.PairingQrPayload) -> Unit,
    ) {
        qrPairingJob?.cancel()
        qrPairingJob = scope.launch(Dispatchers.IO) {
            fun updateProgress(
                step: Int,
                label: String,
                awaitingLocal: Boolean = false,
                awaitingRemote: Boolean = false,
                error: String? = null,
                connected: Boolean = false,
            ) {
                _qrPairingProgress.value = QrPairingProgress(
                    active = true,
                    stepNumber = step,
                    stepLabel = label,
                    peerName = payload.name,
                    peerNodeId = payload.nodeId,
                    tempDeviceId = payload.tempDeviceId,
                    btAddress = payload.btAddress,
                    cl2Code = payload.cl2Code,
                    pairingToken = payload.pairingToken,
                    awaitingLocalConfirm = awaitingLocal,
                    awaitingRemoteConfirm = awaitingRemote,
                    isError = error != null,
                    errorMessage = error,
                    isConnected = connected,
                )
            }

            // Step 1 & Step 2
            updateDiagnostics {
                copy(
                    bluetoothEnabled = isBluetoothEnabled(),
                    bleAdvertisingSupported = isBleAdvertisingSupported(),
                    targetNodeId = payload.nodeId,
                )
            }
            updateProgress(1, "1/12 QR-Code gescannt & Signatur geprüft")
            delay(150)
            updateProgress(
                2,
                "2/12 Peer: ${payload.name} (${payload.nodeId}, ${payload.tempDeviceId})" +
                    (payload.btAddress?.let { " • $it" } ?: ""),
            )

            // Step 3: Check Bluetooth adapter availability & Android 12+ permissions
            updateProgress(3, "3/12 Bluetooth-Verfügbarkeit & Berechtigungen prüfen…")
            if (!isBluetoothEnabled()) {
                updateDiagnostics {
                    copy(
                        bluetoothEnabled = false,
                        advertiseState = DiagnosticComponentState.FAILED,
                        scannerState = DiagnosticComponentState.FAILED,
                        scanErrorCode = -1,
                        scanErrorDetail = describeScanError(-1),
                        lastAndroidErrorCode = -1,
                    )
                }
                updateProgress(3, "3/12 Bluetooth nicht verfügbar", error = "Bluetooth ist deaktiviert oder nicht verfügbar (Code -1).")
                return@launch
            }
            if (!hasScanPermission() || !hasConnectPermission()) {
                updateDiagnostics {
                    copy(
                        scannerState = DiagnosticComponentState.FAILED,
                        scanErrorCode = -2,
                        scanErrorDetail = describeScanError(-2),
                        lastAndroidErrorCode = -2,
                    )
                }
                updateProgress(3, "3/12 Bluetooth-Berechtigung fehlt", error = "BLUETOOTH_SCAN / BLUETOOTH_CONNECT Berechtigung fehlt (Code -2).")
                return@launch
            }

            // Ensure local advertising & GATT server are also running for mutual handshake
            ensurePeripheralServicesActive()

            // Step 4: Discover peer via BLE (active after QR scan; hardware UUID filter first, then software fallback)
            updateProgress(4, "4/12 Gerät ${payload.nodeId} über BLE suchen…")
            var matchedPeer = findMatchingDiscoveredPeer(payload)
            if (matchedPeer == null) {
                startDiscovery(BatteryMode.PERFORMANCE, continuous = true, useServiceUuidFilter = true)
                val filterDeadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < filterDeadline && matchedPeer == null) {
                    if (_bleDiagnostics.value.scannerState == DiagnosticComponentState.FAILED) break
                    delay(250)
                    matchedPeer = findMatchingDiscoveredPeer(payload)
                }
            }
            if (matchedPeer == null && _bleDiagnostics.value.scannerState != DiagnosticComponentState.FAILED) {
                updateProgress(4, "4/12 Erweiterte BLE-Suche für ${payload.nodeId}…")
                startDiscovery(BatteryMode.PERFORMANCE, continuous = true, useServiceUuidFilter = false)
                val fallbackDeadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < fallbackDeadline && matchedPeer == null) {
                    if (_bleDiagnostics.value.scannerState == DiagnosticComponentState.FAILED) break
                    delay(250)
                    matchedPeer = findMatchingDiscoveredPeer(payload)
                }
            }

            val targetAddress = matchedPeer?.address
            if (targetAddress == null) {
                stopDiscovery()
                val diag = _bleDiagnostics.value
                val detail = when {
                    diag.scannerState == DiagnosticComponentState.FAILED ->
                        "BLE-Scanner fehlgeschlagen: ${diag.scanErrorDetail ?: "Unbekannt"} (Code ${diag.scanErrorCode})"
                    diag.detectedNodeIds.isNotEmpty() ->
                        "Kein BLE-Advertisement für ${payload.nodeId} gefunden. Erkannte Nodes: ${diag.detectedNodeIds.joinToString(", ")}"
                    diag.detectedServiceUuid != null ->
                        "CYBER LINK Service-UUID erkannt, aber Node-ID ${payload.nodeId} fehlte im Advertisement/ScanResponse."
                    else ->
                        "Kein BLE-Advertisement für ${payload.nodeId} gefunden (bitte auf Zielgerät BLE-Advertising prüfen: Status=${diag.advertiseState.labelDe})."
                }
                updateProgress(4, "4/12 Gerät nicht über BLE gefunden", error = detail)
                return@launch
            }

            // Step 5: Match device ID & pairing token
            updateProgress(5, "5/12 Geräte-ID & Einmal-Pairing-Token abgleichen…")
            val now = System.currentTimeMillis()
            if (now > payload.expiresAtMs || isTokenConsumed(payload.pairingToken, now)) {
                stopDiscovery()
                updateProgress(5, "5/12 Token ungültig", error = "Pairing-Token abgelaufen oder bereits verwendet.")
                return@launch
            }

            // Step 6 (Initiator part): Local user confirmation
            val confirmDefer = CompletableDeferred<Boolean>()
            localConfirmDeferred = confirmDefer
            updateProgress(6, "6/12 Gegenseitige Bestätigung erforderlich", awaitingLocal = true)
            val localAccepted = confirmDefer.await()
            localConfirmDeferred = null
            if (!localAccepted) {
                stopDiscovery()
                updateProgress(6, "6/12 Abgebrochen", error = "Kopplung lokal abgebrochen.")
                return@launch
            }

            // Step 7-11: Stop active scan cleanly right before connectGatt to avoid radio contention
            stopDiscovery()
            updateProgress(7, "7/12 Kryptografischen Sitzungsschlüssel vorbereiten…")
            val session = getOrCreateSession(payload.nodeId, targetAddress, payload.name)
            val expectedKey = EncryptionManager.decodeBase64(payload.pubB64)

            updateProgress(8, "8/12 GATT-Verbindung zu $targetAddress aufbauen…")
            val device = deviceByAddress[targetAddress]
                ?: runCatching { adapter?.getRemoteDevice(targetAddress) }.getOrNull()
            if (device == null) {
                updateProgress(8, "8/12 Bluetooth-Gerät nicht erreichbar", error = "BluetoothDevice $targetAddress ungültig.")
                return@launch
            }

            gattClient.connectAndHandshake(device, session, expectedKey)

            // Observe GATT handshake progress toward CONNECTED (steps 8..11)
            val gattDeadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < gattDeadline) {
                val latestChallengeStep = session.logs.lastOrNull {
                    it.step == GattStep.CHALLENGE_CREATED ||
                        it.step == GattStep.CHALLENGE_WRITE_STARTED ||
                        it.step == GattStep.CHALLENGE_WRITE_SUCCESS ||
                        it.step == GattStep.RESPONSE_WAITING ||
                        it.step == GattStep.RESPONSE_RECEIVED ||
                        it.step == GattStep.RESPONSE_TIMEOUT
                }
                when (session.transportState) {
                    TransportState.SERVICE_DISCOVERY ->
                        updateProgress(9, "9/12 Notifications & RX/TX aktivieren (CCCD 0x2902)…")
                    TransportState.AUTHENTICATING -> {
                        val subLabel = when (latestChallengeStep?.step) {
                            GattStep.CHALLENGE_CREATED -> "7/12 Challenge erzeugt (ch=${session.activeChallengeId?.take(8) ?: "—"})…"
                            GattStep.CHALLENGE_WRITE_STARTED -> "8/12 Challenge an RX senden…"
                            GattStep.CHALLENGE_WRITE_SUCCESS -> "8/12 Challenge gesendet • aktiviere Antwortkanal…"
                            GattStep.RESPONSE_WAITING -> "8/12 Warte auf ChallengeResponse von ${payload.nodeId}…"
                            GattStep.RESPONSE_RECEIVED -> "8/12 ChallengeResponse empfangen & Signatur prüfen…"
                            GattStep.RESPONSE_TIMEOUT -> "8/12 Response-Timeout • erneuter Versuch / Diagnose…"
                            else -> "7/12 ECDH-Schlüsselaustausch & Challenge-Signatur…"
                        }
                        updateProgress(8, subLabel)
                    }
                    TransportState.DATA_CHANNEL_READY ->
                        updateProgress(10, "10/12 Verschlüsselte Testnachricht (VERIFY_ACK) senden…")
                    TransportState.CONNECTED -> break
                    TransportState.ERROR -> {
                        updateProgress(
                            8,
                            "GATT/Auth Fehler",
                            error = session.lastError ?: "Bluetooth-GATT-Handshake fehlgeschlagen",
                        )
                        return@launch
                    }
                    else -> Unit
                }
                delay(150)
            }

            if (session.transportState != TransportState.CONNECTED || !session.verifyAckReceived) {
                updateProgress(
                    11,
                    "11/12 Handshake fehlgeschlagen",
                    error = session.lastError ?: "Kein verschlüsseltes VERIFY_ACK erhalten.",
                )
                disconnectPeer(payload.nodeId)
                return@launch
            }

            updateProgress(11, "11/12 Verschlüsseltes ACK empfangen • Warte auf Partner-Bestätigung…", awaitingRemote = true)

            // Step 6 (Responder remote confirmation over verified encrypted link)
            val ackDeferred = CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck>()
            remoteConfirmAckDeferred = ackDeferred
            val reqSend = sendEnvelope(
                payload.nodeId,
                CyberLinkProtocol.ProtocolEnvelope.PairingConfirmRequest(
                    sessionId = session.sessionId,
                    initiatorNodeId = encryptionManager.nodeId,
                    initiatorName = payload.name,
                    tempDeviceId = payload.tempDeviceId,
                    pairingToken = payload.pairingToken,
                    cl2Code = payload.cl2Code,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            if (reqSend.isFailure) {
                remoteConfirmAckDeferred = null
                updateProgress(6, "6/12 Bestätigungsanfrage fehlgeschlagen", error = reqSend.exceptionOrNull()?.message)
                disconnectPeer(payload.nodeId)
                return@launch
            }

            val remoteAck = withTimeoutOrNull(35_000L) { ackDeferred.await() }
            remoteConfirmAckDeferred = null
            if (remoteAck == null || !remoteAck.accepted) {
                val reason = remoteAck?.reason ?: "Gegenseitige Bestätigung auf dem Zielgerät abgelaufen oder abgelehnt"
                updateProgress(6, "6/12 Gegenseitige Bestätigung fehlgeschlagen", error = reason)
                disconnectPeer(payload.nodeId)
                return@launch
            }

            // Step 12: Consume single-use token, persist verified contact, mark VERBUNDEN
            markTokenConsumed(payload.pairingToken, payload.expiresAtMs)
            onVerifiedAndConnected(payload)
            onPeerAuthenticatedCallback(payload.nodeId)
            updateProgress(12, "12/12 VERBUNDEN (E2EE & gegenseitig bestätigt)", connected = true)
        }
    }

    private fun findMatchingDiscoveredPeer(payload: PeerManager.PairingQrPayload): DiscoveredBluetoothPeer? {
        return discoveredMap.values.firstOrNull { peer ->
            peer.nodeId == payload.nodeId ||
                (payload.btAddress != null && peer.address.equals(payload.btAddress, ignoreCase = true))
        }
    }

    fun startPeripheralServices(localName: String = "CYBER-${encryptionManager.nodeId.takeLast(6)}") {
        updateDiagnostics {
            copy(
                bluetoothEnabled = isBluetoothEnabled(),
                bleAdvertisingSupported = isBleAdvertisingSupported(),
                advertiseState = DiagnosticComponentState.STARTING,
            )
        }
        gattServer.startServerAndAdvertising(adapter, localName)
        rfcommTransport.startServer(adapter)
        _isAdvertising.value = gattServer.isAdvertising
    }

    fun ensurePeripheralServicesActive(localName: String = "CYBER-${encryptionManager.nodeId.takeLast(6)}") {
        if (!gattServer.isAdvertising) {
            startPeripheralServices(localName)
        } else if (!rfcommDiagnostics.value.rfcommServerReady) {
            rfcommTransport.startServer(adapter)
        }
    }

    fun startDiscovery(
        batteryMode: BatteryMode,
        continuous: Boolean = false,
        useServiceUuidFilter: Boolean = true,
    ) {
        if (!isBluetoothEnabled()) {
            _isScanning.value = false
            updateDiagnostics {
                copy(
                    bluetoothEnabled = false,
                    scannerState = DiagnosticComponentState.FAILED,
                    scanErrorCode = -1,
                    scanErrorDetail = describeScanError(-1),
                    lastAndroidErrorCode = -1,
                )
            }
            return
        }
        if (!hasScanPermission()) {
            _isScanning.value = false
            updateDiagnostics {
                copy(
                    scannerState = DiagnosticComponentState.FAILED,
                    scanErrorCode = -2,
                    scanErrorDetail = describeScanError(-2),
                    lastAndroidErrorCode = -2,
                )
            }
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _isScanning.value = false
            updateDiagnostics {
                copy(
                    scannerState = DiagnosticComponentState.FAILED,
                    scanErrorCode = 4,
                    scanErrorDetail = "BluetoothLeScanner nicht verfügbar (Feature unsupported)",
                    lastAndroidErrorCode = 4,
                )
            }
            return
        }

        // Reuse already running scan of same filter type to avoid Android 5-scans/30s throttling (Code 6)
        if (_isScanning.value && activeScanCallback != null && activeScanUsesUuidFilter == useServiceUuidFilter) {
            return
        }
        stopDiscovery()

        val filters = if (useServiceUuidFilter) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(CyberLinkUuids.SERVICE_UUID))
                    .build()
            )
        } else {
            emptyList()
        }
        val scanMode = when (batteryMode) {
            BatteryMode.ULTRA -> ScanSettings.SCAN_MODE_LOW_POWER
            BatteryMode.NORMAL -> ScanSettings.SCAN_MODE_BALANCED
            BatteryMode.PERFORMANCE -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                activeScanCallback = null
                activeScanUsesUuidFilter = null
                updateDiagnostics {
                    copy(
                        scannerState = DiagnosticComponentState.FAILED,
                        scanErrorCode = errorCode,
                        scanErrorDetail = describeScanError(errorCode),
                        lastAndroidErrorCode = errorCode,
                    )
                }
            }
        }

        activeScanCallback = cb
        activeScanUsesUuidFilter = useServiceUuidFilter
        _isScanning.value = true
        updateDiagnostics {
            copy(
                bluetoothEnabled = true,
                scannerState = DiagnosticComponentState.RUNNING,
                scanErrorCode = null,
                scanErrorDetail = null,
                bleScanOk = true,
            )
        }

        runCatching {
            scanner.startScan(filters, settings, cb)
        }.onFailure { err ->
            _isScanning.value = false
            activeScanCallback = null
            activeScanUsesUuidFilter = null
            val code = if (err is SecurityException) -2 else 3
            updateDiagnostics {
                copy(
                    scannerState = DiagnosticComponentState.FAILED,
                    scanErrorCode = code,
                    scanErrorDetail = "${describeScanError(code)}: ${err.message}",
                    lastAndroidErrorCode = code,
                )
            }
            return
        }

        if (!continuous) {
            val windowMs = when (batteryMode) {
                BatteryMode.ULTRA -> 6_000L
                BatteryMode.NORMAL -> 12_000L
                BatteryMode.PERFORMANCE -> 20_000L
            }
            scanJob = scope.launch {
                delay(windowMs)
                stopDiscovery()
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val record = result.scanRecord
        val mfgData = record?.getManufacturerSpecificData(BleAdvertisementCodec.MANUFACTURER_ID)
        val serviceData = record?.getServiceData(ParcelUuid(CyberLinkUuids.SERVICE_UUID))
        val hasCyberServiceUuid = record?.serviceUuids?.any { it.uuid == CyberLinkUuids.SERVICE_UUID } == true

        // In unfiltered fallback scan mode, ignore unrelated third-party BLE beacons
        if (!hasCyberServiceUuid && mfgData == null && serviceData == null) {
            return
        }

        val parsedNodeId = BleAdvertisementCodec.decodeManufacturerNodePayload(mfgData)
            ?: BleAdvertisementCodec.decodeServiceDataNodeId(serviceData)
        val existingPeer = discoveredMap[device.address]
        val resolvedNodeId = parsedNodeId ?: existingPeer?.nodeId

        val rawName = record?.deviceName ?: runCatching { device.name }.getOrNull()
        val name = rawName ?: resolvedNodeId ?: existingPeer?.name ?: device.address
        deviceByAddress[device.address] = device

        val peer = DiscoveredBluetoothPeer(
            address = device.address,
            name = name,
            nodeId = resolvedNodeId,
            rssi = result.rssi,
            lastSeenMs = System.currentTimeMillis(),
        )
        discoveredMap[device.address] = peer
        _discoveredPeers.value = discoveredMap.values.sortedByDescending { it.lastSeenMs }

        val knownIds = discoveredMap.values.mapNotNull { it.nodeId }.distinct()
        updateDiagnostics {
            copy(
                detectedServiceUuid = if (hasCyberServiceUuid || serviceData != null) {
                    CyberLinkUuids.SERVICE_UUID.toString()
                } else {
                    detectedServiceUuid
                },
                detectedNodeIds = knownIds,
            )
        }

        if (resolvedNodeId != null) {
            val session = getOrCreateSession(resolvedNodeId, device.address, name)
            if (session.transportState == TransportState.SAVED || session.transportState == TransportState.DISCONNECTED) {
                session.updateState(TransportState.BLUETOOTH_FOUND)
                publishSession(session)
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        activeScanCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        activeScanCallback = null
        activeScanUsesUuidFilter = null
        _isScanning.value = false
        updateDiagnostics {
            if (scannerState == DiagnosticComponentState.RUNNING) {
                copy(scannerState = DiagnosticComponentState.NOT_STARTED)
            } else {
                this
            }
        }
    }

    fun connectToPeer(peerNodeId: String, address: String? = null) {
        val resolvedAddr = address
            ?: discoveredMap.values.firstOrNull { it.nodeId == peerNodeId }?.address
            ?: sessionMap[peerNodeId]?.deviceAddress
        if (resolvedAddr == null) {
            val session = getOrCreateSession(peerNodeId, null, null)
            session.fail("Kein BLE-Gerät für $peerNodeId gefunden. Bitte Scan starten.")
            publishSession(session)
            return
        }
        val device = deviceByAddress[resolvedAddr]
            ?: runCatching { adapter?.getRemoteDevice(resolvedAddr) }.getOrNull()
        if (device == null) {
            val session = getOrCreateSession(peerNodeId, resolvedAddr, null)
            session.fail("Ungültige Bluetooth-Adresse: $resolvedAddr")
            publishSession(session)
            return
        }

        val session = getOrCreateSession(peerNodeId, resolvedAddr, device.name)
        val expectedKey = lookupContactPublicKey(peerNodeId)
        gattClient.connectAndHandshake(device, session, expectedKey)
    }

    fun attemptAutoReconnect(peerNodeId: String) {
        if (!isBluetoothEnabled()) return
        val session = sessionMap[peerNodeId] ?: return
        if (session.transportState == TransportState.CONNECTED ||
            session.transportState == TransportState.CONNECTING ||
            session.transportState == TransportState.AUTHENTICATING
        ) return
        val addr = session.deviceAddress ?: discoveredMap.values.firstOrNull { it.nodeId == peerNodeId }?.address
        if (addr != null) {
            connectToPeer(peerNodeId, addr)
        }
    }

    suspend fun upgradeToRfcomm(peerNodeId: String): Result<Unit> {
        val session = sessionMap[peerNodeId]
            ?: return Result.failure(IllegalStateException("Keine Session für $peerNodeId"))
        if (session.transportState != TransportState.CONNECTED || session.cipherSession == null) {
            return Result.failure(IllegalStateException("GATT-Authentifizierung & Session-Schlüssel noch nicht abgeschlossen"))
        }
        val res = rfcommTransport.connectAndVerifyPeer(
            adapter = adapter,
            peerNodeId = peerNodeId,
            peerNameHint = session.deviceName,
            classicAddressHint = session.classicAddress,
            bleAddressHint = session.deviceAddress,
            cipher = session.cipherSession,
        )
        return res.map { }
    }

    suspend fun sendEnvelope(
        peerNodeId: String,
        envelope: CyberLinkProtocol.ProtocolEnvelope,
        preferRfcomm: Boolean = false,
    ): Result<Unit> {
        val session = sessionMap[peerNodeId]
            ?: return Result.failure(IllegalStateException("Keine aktive Session für $peerNodeId"))
        if (session.transportState != TransportState.CONNECTED || session.activeTransport == ActiveTransport.NONE) {
            return Result.failure(IllegalStateException("Kein authentifizierter Transport aktiv (${session.transportState.labelDe})"))
        }
        val rfcommAddr = rfcommTransport.getBoundAddress(peerNodeId) ?: session.effectiveRfcommAddress()
        if (preferRfcomm && rfcommAddr != null && rfcommTransport.isBidirectionallyReady(session.classicAddress, session.deviceAddress, peerNodeId)) {
            val bytes = CyberLinkProtocol.encodeEnvelope(envelope)
            return rfcommTransport.sendFrame(rfcommAddr, RfcommTransport.FRAME_PROTOCOL_ENVELOPE, bytes, session.cipherSession)
        }
        val bleAddr = session.deviceAddress
        return when (session.connectionRole) {
            ConnectionRole.INITIATOR_CLIENT -> gattClient.sendEncryptedEnvelope(session, envelope)
            ConnectionRole.RESPONDER_SERVER -> {
                if (bleAddr != null) {
                    gattServer.sendEncryptedNotification(bleAddr, session, envelope)
                } else {
                    Result.failure(IllegalStateException("Adresse für GATT-Server-Notify fehlt"))
                }
            }
            ConnectionRole.NONE -> {
                if (session.isLocalInitiator(encryptionManager.nodeId)) {
                    gattClient.sendEncryptedEnvelope(session, envelope)
                } else if (bleAddr != null) {
                    gattServer.sendEncryptedNotification(bleAddr, session, envelope)
                } else {
                    Result.failure(IllegalStateException("Keine aktive GATT-Verbindungsrolle festgelegt"))
                }
            }
        }
    }

    fun disconnectPeer(peerNodeId: String) {
        val session = sessionMap[peerNodeId] ?: return
        rfcommTransport.closePeer(peerNodeId, "Vom Benutzer getrennt")
        session.classicAddress?.let { rfcommTransport.closePeer(it, "Vom Benutzer getrennt") }
        session.deviceAddress?.let { rfcommTransport.closePeer(it, "Vom Benutzer getrennt") }
        gattClient.closeCurrent("Vom Benutzer getrennt")
        session.closeDisconnected("Vom Benutzer getrennt")
        publishSession(session)
    }

    fun getOrCreateSession(
        peerNodeId: String,
        address: String?,
        name: String?,
    ): PeerTransportSession {
        val existing = sessionMap[peerNodeId]
        if (existing != null) {
            if (address != null) existing.deviceAddress = address
            if (name != null) existing.deviceName = name
            return existing
        }
        val created = PeerTransportSession(peerNodeId, address, name)
        sessionMap[peerNodeId] = created
        publishSession(created)
        return created
    }

    fun getSession(peerNodeId: String): PeerTransportSession? = sessionMap[peerNodeId]

    fun getEffectiveTransportForPeer(peerNodeId: String): ActiveTransport {
        val session = sessionMap[peerNodeId] ?: return ActiveTransport.NONE
        if (session.transportState != TransportState.CONNECTED) return ActiveTransport.NONE
        val rfcommReady = rfcommTransport.isBidirectionallyReady(
            classicAddress = session.classicAddress,
            bleAddress = session.deviceAddress,
            peerNodeId = peerNodeId,
        )
        return when {
            rfcommReady && session.activeTransport == ActiveTransport.BLE_GATT -> ActiveTransport.DUAL_BLE_RFCOMM
            rfcommReady -> ActiveTransport.CLASSIC_RFCOMM
            else -> session.activeTransport
        }
    }

    fun disconnectIdleSessions(maxIdleMs: Long) {
        val now = System.currentTimeMillis()
        sessionMap.values.filter {
            it.transportState == TransportState.CONNECTED && (now - it.lastActivityMs) > maxIdleMs
        }.forEach {
            disconnectPeer(it.peerNodeId)
        }
    }

    private fun publishSession(session: PeerTransportSession) {
        val prevState = lastPublishedStates[session.peerNodeId]
        lastPublishedStates[session.peerNodeId] = session.transportState
        sessionMap[session.peerNodeId] = session
        _sessions.value = sessionMap.toMap()
        val authTrace = session.logs
            .filter {
                it.step == GattStep.CCCD_WRITE_COMPLETED ||
                    it.step == GattStep.CHALLENGE_CREATED ||
                    it.step == GattStep.CHALLENGE_WRITE_STARTED ||
                    it.step == GattStep.CHALLENGE_WRITE_SUCCESS ||
                    it.step == GattStep.RESPONSE_WAITING ||
                    it.step == GattStep.RESPONSE_RECEIVED ||
                    it.step == GattStep.RESPONSE_TIMEOUT ||
                    it.step == GattStep.CHALLENGE_RECEIVED ||
                    it.step == GattStep.CHALLENGE_VALIDATED ||
                    it.step == GattStep.RESPONSE_CREATED ||
                    it.step == GattStep.RESPONSE_WRITE_STARTED ||
                    it.step == GattStep.RESPONSE_WRITE_SUCCESS ||
                    it.step == GattStep.VERIFY_ACK_COMPLETED ||
                    it.step == GattStep.STEP_ERROR
            }
            .takeLast(12)
            .map { it.formatLine() }
        val gattConnected = session.rxSpec != null ||
            session.transportState == TransportState.SERVICE_DISCOVERY ||
            session.transportState == TransportState.AUTHENTICATING ||
            session.transportState == TransportState.DATA_CHANNEL_READY ||
            session.transportState == TransportState.CONNECTED
        val gattReady = session.transportState == TransportState.CONNECTED &&
            (session.activeTransport == ActiveTransport.BLE_GATT || session.activeTransport == ActiveTransport.DUAL_BLE_RFCOMM)
        updateDiagnostics {
            copy(
                gattStatusText = "${session.peerNodeId}: ${session.transportState.labelDe}",
                lastAndroidErrorCode = session.lastErrorStatusCode ?: lastAndroidErrorCode,
                authRole = session.connectionRole.labelDe,
                activeSessionId = session.sessionId,
                activeChallengeId = session.activeChallengeId,
                challengeTrace = authTrace,
                bleGattConnectOk = gattConnected || bleGattConnectOk,
                bleGattReady = gattReady,
            )
        }
        if (prevState != TransportState.CONNECTED && session.transportState == TransportState.CONNECTED) {
            onPeerAuthenticatedCallback(session.peerNodeId)
        }
    }

    fun shutdown() {
        stopDiscovery()
        qrPairingJob?.cancel()
        gattClient.closeCurrent("Shutdown")
        gattServer.stop()
        rfcommTransport.stop()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(btStateReceiver) }
            receiverRegistered = false
        }
    }
}
