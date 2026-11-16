package com.example.bluetooth

import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single authoritative session object for a CYBER LINK peer.
 * Enforces:
 * - ActiveTransport stays NONE until Challenge-Response AND encrypted bidirectional VERIFY_ACK complete.
 * - Explicit ConnectionRole (INITIATOR_CLIENT vs RESPONDER_SERVER) decoupled from Node-ID tie-breaking.
 * - Replay-safe challengeId + sessionId + nonceA binding on every authentication exchange.
 * - On disconnect or error, old MTU/PHY/RSSI/Transport values are immediately invalidated.
 */
class PeerTransportSession(
    val peerNodeId: String,
    var deviceAddress: String?,
    var deviceName: String?,
) {
    @Volatile
    var classicAddress: String? = null

    @Volatile
    var rfcommBidirectionalReady: Boolean = false

    @Volatile
    var rfcommBytesSent: Long = 0L

    @Volatile
    var rfcommBytesReceived: Long = 0L

    @Volatile
    var rfcommLastError: String? = null

    fun effectiveRfcommAddress(): String? = classicAddress ?: deviceAddress

    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    @Volatile
    var activeChallengeId: String? = null
        private set

    @Volatile
    var connectionRole: ConnectionRole = ConnectionRole.NONE

    @Volatile
    var transportState: TransportState = TransportState.SAVED
        private set

    @Volatile
    var activeTransport: ActiveTransport = ActiveTransport.NONE
        private set

    /** Actual negotiated ATT MTU from Android onMtuChanged; null if not yet negotiated. */
    @Volatile
    var negotiatedMtu: Int? = null
        private set

    /** Effective chunking MTU (falls back safely to 23 if negotiatedMtu is null). */
    val effectiveMtu: Int
        get() = (negotiatedMtu ?: CyberLinkProtocol.MIN_MTU).coerceAtLeast(CyberLinkProtocol.MIN_MTU)

    @Volatile
    var phyLabel: String? = null
        private set

    @Volatile
    var rssiDbm: Int? = null
        private set

    @Volatile
    var rxSpec: GattCapabilityValidator.CharacteristicSpec? = null

    @Volatile
    var txSpec: GattCapabilityValidator.CharacteristicSpec? = null

    @Volatile
    var notificationLocallyEnabled: Boolean = false

    @Volatile
    var cccdWriteSucceeded: Boolean = false

    @Volatile
    var cccdStatusCode: Int? = null

    @Volatile
    var cipherSession: EncryptionManager.SessionCipher? = null
        private set

    @Volatile
    var verifyAckSent: Boolean = false
        private set

    @Volatile
    var verifyAckReceived: Boolean = false
        private set

    @Volatile
    var confirmOkReceived: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastErrorStatusCode: Int? = null
        private set

    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    val gattQueue = GattOperationQueue()
    val reassembler = CyberLinkProtocol.PacketReassembler()
    val logs = CopyOnWriteArrayList<GattLogEntry>()

    private val authMutex = Mutex()
    private val seenChallengeIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    // Handshake synchronization primitives
    @Volatile
    private var pendingResponseDeferred: CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.ChallengeResponse>? = null

    @Volatile
    private var pendingVerifyAckDeferred: CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.VerifyAck>? = null

    @Volatile
    private var pendingConfirmOkDeferred: CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.ConfirmOk>? = null

    // Ephemeral state for active handshake
    private var localEphemeralKeyPair: java.security.KeyPair? = null
    private var pendingNonceA: ByteArray? = null
    private var pendingPeerIdentityPub: ByteArray? = null

    @Volatile
    var peerIdentityPublicKeyB64: String? = null
        private set

    fun logStep(
        step: GattStep,
        detail: String,
        gattStatusCode: Int? = null,
        callbackName: String? = null,
        characteristicUuid: String? = null,
        writeType: String? = null,
    ) {
        lastActivityMs = System.currentTimeMillis()
        logs.add(
            GattLogEntry(
                timestampMs = lastActivityMs,
                step = step,
                peerNodeId = peerNodeId,
                sessionId = sessionId,
                challengeId = activeChallengeId,
                role = connectionRole,
                connectionState = transportState,
                gattStatusCode = gattStatusCode,
                callbackName = callbackName,
                characteristicUuid = characteristicUuid,
                writeType = writeType,
                currentMtu = negotiatedMtu,
                detail = detail,
            )
        )
        while (logs.size > 200) {
            logs.removeAt(0)
        }
    }

    fun updateState(newState: TransportState) {
        transportState = newState
        lastActivityMs = System.currentTimeMillis()
    }

    fun updateNegotiatedMtu(mtu: Int) {
        negotiatedMtu = mtu.coerceAtLeast(CyberLinkProtocol.MIN_MTU)
        lastActivityMs = System.currentTimeMillis()
    }

    fun updateTelemetry(rssi: Int? = null, phy: String? = null) {
        if (rssi != null) rssiDbm = rssi
        if (phy != null) phyLabel = phy
        lastActivityMs = System.currentTimeMillis()
    }

    fun adoptPendingServerStateFrom(tempSession: PeerTransportSession) {
        if (tempSession === this) return
        tempSession.negotiatedMtu?.let { mtu ->
            if ((negotiatedMtu ?: 0) < mtu) updateNegotiatedMtu(mtu)
        }
        if (tempSession.cccdWriteSucceeded) {
            cccdWriteSucceeded = true
            cccdStatusCode = tempSession.cccdStatusCode ?: 0
        }
        val existingTimestamps = logs.map { it.timestampMs to it.step }.toHashSet()
        val adopted = tempSession.logs.filter { (it.timestampMs to it.step) !in existingTimestamps }
        if (adopted.isNotEmpty()) {
            logs.addAll(adopted)
            logs.sortBy { it.timestampMs }
        }
    }

    fun resetForNewAttempt() {
        sessionId = UUID.randomUUID().toString()
        activeChallengeId = null
        connectionRole = ConnectionRole.NONE
        activeTransport = ActiveTransport.NONE
        negotiatedMtu = null
        phyLabel = null
        rssiDbm = null
        rxSpec = null
        txSpec = null
        notificationLocallyEnabled = false
        cccdWriteSucceeded = false
        cccdStatusCode = null
        cipherSession = null
        verifyAckSent = false
        verifyAckReceived = false
        confirmOkReceived = false
        lastError = null
        lastErrorStatusCode = null
        rfcommBidirectionalReady = false
        rfcommBytesSent = 0L
        rfcommBytesReceived = 0L
        rfcommLastError = null
        localEphemeralKeyPair = null
        pendingNonceA = null
        pendingResponseDeferred?.cancel()
        pendingVerifyAckDeferred?.cancel()
        pendingConfirmOkDeferred?.cancel()
        pendingResponseDeferred = null
        pendingVerifyAckDeferred = null
        pendingConfirmOkDeferred = null
        reassembler.reset()
        gattQueue.cancelAll("Session reset for new attempt")
    }

    fun fail(
        message: String,
        statusCode: Int? = null,
        callbackName: String? = null,
        characteristicUuid: String? = null,
        writeType: String? = null,
    ) {
        lastError = message
        lastErrorStatusCode = statusCode
        activeTransport = ActiveTransport.NONE
        cipherSession = null
        verifyAckSent = false
        verifyAckReceived = false
        confirmOkReceived = false
        negotiatedMtu = null
        phyLabel = null
        rssiDbm = null
        rfcommBidirectionalReady = false
        transportState = TransportState.ERROR
        pendingResponseDeferred?.completeExceptionally(IllegalStateException(message))
        pendingVerifyAckDeferred?.completeExceptionally(IllegalStateException(message))
        pendingConfirmOkDeferred?.completeExceptionally(IllegalStateException(message))
        gattQueue.cancelAll(message)
        logStep(
            step = GattStep.STEP_ERROR,
            detail = message,
            gattStatusCode = statusCode,
            callbackName = callbackName,
            characteristicUuid = characteristicUuid,
            writeType = writeType,
        )
    }

    fun closeDisconnected(reason: String = "Verbindung getrennt") {
        activeTransport = ActiveTransport.NONE
        connectionRole = ConnectionRole.NONE
        activeChallengeId = null
        cipherSession = null
        verifyAckSent = false
        verifyAckReceived = false
        confirmOkReceived = false
        negotiatedMtu = null
        phyLabel = null
        rssiDbm = null
        rxSpec = null
        txSpec = null
        notificationLocallyEnabled = false
        cccdWriteSucceeded = false
        cccdStatusCode = null
        rfcommBidirectionalReady = false
        reassembler.reset()
        pendingResponseDeferred?.cancel()
        pendingVerifyAckDeferred?.cancel()
        pendingConfirmOkDeferred?.cancel()
        pendingResponseDeferred = null
        pendingVerifyAckDeferred = null
        pendingConfirmOkDeferred = null
        gattQueue.cancelAll(reason)
        if (transportState != TransportState.ERROR) {
            transportState = TransportState.DISCONNECTED
        }
    }

    /**
     * Deterministic tie-breaker for simultaneous dual connections:
     * Lexicographically smaller Node ID wins initiator role when both peers dial concurrently.
     * NOTE: Do NOT use this for routing live GATT packets; use [connectionRole] instead.
     */
    fun isLocalInitiator(localNodeId: String): Boolean = localNodeId < peerNodeId

    suspend fun runClientChallengeHandshake(
        localEncryption: EncryptionManager,
        peerExpectedPublicKeyBytes: ByteArray?,
        sendBytesChunked: suspend (payload: ByteArray, writeType: Int, encrypted: Boolean) -> GattOperationQueue.GattOpResult,
        responseTimeoutMs: Long = 8_000L,
        transportToActivate: ActiveTransport = ActiveTransport.BLE_GATT,
        maxChallengeRetries: Int = 1,
        onStepLogged: () -> Unit = {},
    ): Result<EncryptionManager.SessionCipher> {
        if (!authMutex.tryLock()) {
            val busyMsg = "Paralleler Authentifizierungsvorgang für $peerNodeId bereits aktiv"
            logStep(step = GattStep.STEP_ERROR, detail = busyMsg, callbackName = "runClientChallengeHandshake")
            onStepLogged()
            return Result.failure(IllegalStateException(busyMsg))
        }
        try {
            connectionRole = ConnectionRole.INITIATOR_CLIENT
            val validation = GattCapabilityValidator.validateBeforeChallenge(
                rxSpec = rxSpec,
                txSpec = txSpec,
                notificationLocallyEnabled = notificationLocallyEnabled,
                cccdWriteSucceeded = cccdWriteSucceeded,
                cccdGattStatus = cccdStatusCode,
            )
            if (validation is GattCapabilityValidator.ValidationResult.Invalid) {
                fail(
                    message = validation.reason,
                    statusCode = cccdStatusCode,
                    callbackName = "validateBeforeChallenge",
                    characteristicUuid = validation.characteristicUuid,
                )
                onStepLogged()
                return Result.failure(IllegalStateException(validation.reason))
            }
            val ready = validation as GattCapabilityValidator.ValidationResult.Ready

            updateState(TransportState.AUTHENTICATING)
            onStepLogged()

            var verifiedResponse: CyberLinkProtocol.ProtocolEnvelope.ChallengeResponse? = null
            var activeEphPair: java.security.KeyPair? = null
            var activeNonceA: ByteArray? = null

            for (attempt in 0..maxChallengeRetries) {
                val challengeId = UUID.randomUUID().toString()
                val ephPair = localEncryption.generateEphemeralKeyPair()
                val nonceA = localEncryption.generateNonce(16)
                val timestampMs = System.currentTimeMillis()

                activeChallengeId = challengeId
                localEphemeralKeyPair = ephPair
                pendingNonceA = nonceA
                pendingPeerIdentityPub = peerExpectedPublicKeyBytes
                activeEphPair = ephPair
                activeNonceA = nonceA

                val transcript = CyberLinkProtocol.signatureTranscriptForStart(
                    sessionId = sessionId,
                    challengeId = challengeId,
                    initiatorNodeId = localEncryption.nodeId,
                    responderNodeId = peerNodeId,
                    nonceA = nonceA,
                    ephemeralPubA = ephPair.public.encoded,
                    timestampMs = timestampMs,
                )
                val sigA = localEncryption.signPayload(transcript)
                val startEnvelope = CyberLinkProtocol.ProtocolEnvelope.ChallengeStart(
                    sessionId = sessionId,
                    challengeId = challengeId,
                    initiatorNodeId = localEncryption.nodeId,
                    responderNodeId = peerNodeId,
                    nonceAB64 = EncryptionManager.encodeBase64(nonceA),
                    ephemeralPubAB64 = EncryptionManager.encodeBase64(ephPair.public.encoded),
                    identityPubAB64 = localEncryption.publicKeyBase64,
                    timestampMs = timestampMs,
                    signatureAB64 = EncryptionManager.encodeBase64(sigA),
                )

                logStep(
                    step = GattStep.CHALLENGE_CREATED,
                    detail = "Challenge erstellt (Versuch ${attempt + 1}/${maxChallengeRetries + 1}, MTU=$effectiveMtu, ChunkData=${CyberLinkProtocol.maxChunkDataSizeForMtu(effectiveMtu)} B)",
                    writeType = ready.writeTypeLabel,
                )
                onStepLogged()

                val responseDeferred = CompletableDeferred<CyberLinkProtocol.ProtocolEnvelope.ChallengeResponse>()
                pendingResponseDeferred = responseDeferred
                pendingVerifyAckDeferred = CompletableDeferred()
                pendingConfirmOkDeferred = CompletableDeferred()

                val encodedStart = CyberLinkProtocol.encodeEnvelope(startEnvelope)
                val chunkCount = CyberLinkProtocol.chunkPayload(1, encodedStart, effectiveMtu).size
                logStep(
                    step = GattStep.CHALLENGE_WRITE_STARTED,
                    detail = "Sende Challenge an RX (${encodedStart.size} B, $chunkCount Chunks)",
                    characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
                    writeType = ready.writeTypeLabel,
                )
                onStepLogged()

                val writeRes = sendBytesChunked(encodedStart, ready.writeType, false)
                if (writeRes is GattOperationQueue.GattOpResult.Failed) {
                    val detail = if (writeRes.isTimeout) {
                        "Challenge-Start Write-Timeout: ${writeRes.reason}"
                    } else {
                        "Challenge-Start Write fehlgeschlagen: ${writeRes.reason}"
                    }
                    fail(
                        message = detail,
                        statusCode = writeRes.statusCode,
                        callbackName = writeRes.callbackName ?: "onCharacteristicWrite",
                        characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
                        writeType = ready.writeTypeLabel,
                    )
                    onStepLogged()
                    return Result.failure(IllegalStateException(detail))
                }

                logStep(
                    step = GattStep.CHALLENGE_WRITE_SUCCESS,
                    detail = "ChallengeStart vollständig an RX übertragen ($chunkCount Chunks)",
                    gattStatusCode = 0,
                    callbackName = "onCharacteristicWrite",
                    characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
                    writeType = ready.writeTypeLabel,
                )
                logStep(
                    step = GattStep.RESPONSE_WAITING,
                    detail = "Warte auf ChallengeResponse über TX Notify (max ${responseTimeoutMs}ms)",
                    characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                )
                onStepLogged()

                val response = withTimeoutOrNull(responseTimeoutMs) {
                    responseDeferred.await()
                }
                if (response != null) {
                    verifiedResponse = response
                    break
                }

                // Diagnose WHY no ChallengeResponse arrived before deciding retry vs abort
                val partial = reassembler.pendingChunkProgress()
                val diagnosis = if (partial == null) {
                    "0 Chunks empfangen (Zielgerät hat Challenge nicht verarbeitet oder TX-Notification/CCCD blockiert)"
                } else {
                    "Unvollständige TX-Antwort empfangen ($partial)"
                }
                val timeoutMsg = "Response-Timeout (${responseTimeoutMs}ms): keine ChallengeResponse von $peerNodeId empfangen — $diagnosis."
                logStep(
                    step = GattStep.RESPONSE_TIMEOUT,
                    detail = timeoutMsg,
                    callbackName = "onCharacteristicChanged",
                    characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                )
                onStepLogged()

                val linkStillHealthy = transportState == TransportState.AUTHENTICATING
                if (attempt < maxChallengeRetries && partial == null && linkStillHealthy) {
                    reassembler.reset()
                    continue
                } else {
                    fail(
                        message = timeoutMsg,
                        callbackName = "onCharacteristicChanged",
                        characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                    )
                    onStepLogged()
                    return Result.failure(IllegalStateException(timeoutMsg))
                }
            }

            val response = verifiedResponse ?: run {
                val msg = "Response-Timeout (${responseTimeoutMs}ms): keine gültige ChallengeResponse empfangen."
                fail(message = msg, callbackName = "onCharacteristicChanged")
                onStepLogged()
                return Result.failure(IllegalStateException(msg))
            }

            val ephPair = activeEphPair!!
            val nonceA = activeNonceA!!

            if (response.sessionId != sessionId ||
                response.challengeId != activeChallengeId ||
                response.responderNodeId != peerNodeId ||
                response.initiatorNodeId != localEncryption.nodeId
            ) {
                val msg = "Session-/Challenge-/Peer-Konflikt in ChallengeResponse (erwartet $peerNodeId / $sessionId / $activeChallengeId, erhalten ${response.responderNodeId} / ${response.sessionId} / ${response.challengeId})."
                fail(message = msg, callbackName = "ChallengeResponse")
                onStepLogged()
                return Result.failure(SecurityException(msg))
            }

            val respNonceA = EncryptionManager.decodeBase64(response.nonceAB64)
            if (!respNonceA.contentEquals(nonceA)) {
                val msg = "Nonce-Mismatch / Replay-Verdacht in ChallengeResponse."
                fail(message = msg, callbackName = "ChallengeResponse")
                onStepLogged()
                return Result.failure(SecurityException(msg))
            }

            val peerIdentityPub = peerExpectedPublicKeyBytes ?: EncryptionManager.decodeBase64(response.identityPubBB64)
            val respEphB = EncryptionManager.decodeBase64(response.ephemeralPubBB64)
            val respNonceB = EncryptionManager.decodeBase64(response.nonceBB64)
            val respSig = EncryptionManager.decodeBase64(response.signatureBB64)

            val expectedRespTranscript = CyberLinkProtocol.signatureTranscriptForResponse(
                sessionId = sessionId,
                challengeId = response.challengeId,
                responderNodeId = response.responderNodeId,
                initiatorNodeId = localEncryption.nodeId,
                nonceA = nonceA,
                nonceB = respNonceB,
                ephemeralPubB = respEphB,
                timestampMs = response.timestampMs,
            )
            if (!localEncryption.verifySignature(peerIdentityPub, expectedRespTranscript, respSig)) {
                val msg = "ECDSA-Signaturprüfung der ChallengeResponse fehlgeschlagen."
                fail(message = msg, callbackName = "ChallengeResponse")
                onStepLogged()
                return Result.failure(SecurityException(msg))
            }
            peerIdentityPublicKeyB64 = EncryptionManager.encodeBase64(peerIdentityPub)

            val derivedCipher = localEncryption.deriveSession(
                sessionId = sessionId,
                localNodeId = localEncryption.nodeId,
                peerNodeId = peerNodeId,
                localEphemeralPrivate = ephPair.private,
                peerEphemeralPublicBytes = respEphB,
                nonceA = nonceA,
                nonceB = respNonceB,
            )
            cipherSession = derivedCipher
            updateState(TransportState.DATA_CHANNEL_READY)
            onStepLogged()

            // Send AES-GCM encrypted VERIFY_ACK (A -> B)
            val verifyAckA = CyberLinkProtocol.ProtocolEnvelope.VerifyAck(
                sessionId = sessionId,
                senderNodeId = localEncryption.nodeId,
                targetNodeId = peerNodeId,
                direction = "A_TO_B",
                timestampMs = System.currentTimeMillis(),
            )
            val encryptedVerify = derivedCipher.encrypt(CyberLinkProtocol.encodeEnvelope(verifyAckA))
            val verifyWriteRes = sendBytesChunked(encryptedVerify, ready.writeType, true)
            if (verifyWriteRes is GattOperationQueue.GattOpResult.Failed) {
                val msg = "VERIFY_ACK-Übertragung fehlgeschlagen: ${verifyWriteRes.reason}"
                fail(
                    message = msg,
                    statusCode = verifyWriteRes.statusCode,
                    callbackName = verifyWriteRes.callbackName,
                    characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
                    writeType = ready.writeTypeLabel,
                )
                onStepLogged()
                return Result.failure(IllegalStateException(msg))
            }
            verifyAckSent = true
            onStepLogged()

            // Wait for encrypted VerifyAck B -> A from peer before marking CONNECTED
            val peerAck = withTimeoutOrNull(responseTimeoutMs) {
                pendingVerifyAckDeferred?.await()
            }
            if (peerAck == null) {
                val msg = "Bidirektionale VERIFY_ACK-Bestätigung von $peerNodeId ausgeblieben (Timeout ${responseTimeoutMs}ms)."
                fail(message = msg, callbackName = "VerifyAck")
                onStepLogged()
                return Result.failure(IllegalStateException(msg))
            }

            verifyAckReceived = true
            confirmOkReceived = true
            activeTransport = transportToActivate
            updateState(TransportState.CONNECTED)
            logStep(
                step = GattStep.VERIFY_ACK_COMPLETED,
                detail = "Bidirektionaler verschlüsselter VERIFY_ACK erfolgreich. Transport=${activeTransport.labelDe}",
                gattStatusCode = 0,
            )
            onStepLogged()
            return Result.success(derivedCipher)
        } finally {
            authMutex.unlock()
        }
    }

    /**
     * Server/Responder side processing of incoming ChallengeStart.
     * Enforces replay protection via challengeId & timestamp freshness, logs CHALLENGE_RECEIVED,
     * CHALLENGE_VALIDATED, and RESPONSE_CREATED, and returns serialized ChallengeResponse bytes.
     */
    fun processIncomingChallengeStart(
        localEncryption: EncryptionManager,
        start: CyberLinkProtocol.ProtocolEnvelope.ChallengeStart,
        peerExpectedPublicKeyBytes: ByteArray?,
    ): Result<ByteArray> {
        connectionRole = ConnectionRole.RESPONDER_SERVER
        sessionId = start.sessionId
        activeChallengeId = start.challengeId
        activeTransport = ActiveTransport.NONE
        verifyAckSent = false
        verifyAckReceived = false
        confirmOkReceived = false
        updateState(TransportState.AUTHENTICATING)

        logStep(
            step = GattStep.CHALLENGE_RECEIVED,
            detail = "ChallengeStart von ${start.initiatorNodeId} empfangen",
            gattStatusCode = 0,
            callbackName = "onCharacteristicWriteRequest",
            characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
        )

        if (start.responderNodeId != localEncryption.nodeId) {
            val msg = "ChallengeStart Ziel-Node-ID (${start.responderNodeId}) passt nicht zu diesem Gerät (${localEncryption.nodeId})."
            fail(msg, callbackName = "onCharacteristicWriteRequest", characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString())
            return Result.failure(SecurityException(msg))
        }
        if (start.initiatorNodeId != peerNodeId) {
            val msg = "ChallengeStart Peer-Node-ID mismatch (erwartet $peerNodeId, erhalten ${start.initiatorNodeId})."
            fail(msg, callbackName = "onCharacteristicWriteRequest")
            return Result.failure(SecurityException(msg))
        }
        if (start.challengeId.isBlank() || !seenChallengeIds.add(start.challengeId)) {
            val msg = "Doppelte oder ungültige Challenge-ID (Replay-Schutz): ${start.challengeId}"
            fail(msg, callbackName = "onCharacteristicWriteRequest")
            return Result.failure(SecurityException(msg))
        }
        if (abs(System.currentTimeMillis() - start.timestampMs) > MAX_CLOCK_SKEW_MS) {
            val msg = "ChallengeStart Zeitstempel außerhalb des gültigen Fensters (mögliche Replay-Nachricht)."
            fail(msg, callbackName = "onCharacteristicWriteRequest")
            return Result.failure(SecurityException(msg))
        }

        val peerIdentityPub = peerExpectedPublicKeyBytes ?: EncryptionManager.decodeBase64(start.identityPubAB64)
        val nonceA = EncryptionManager.decodeBase64(start.nonceAB64)
        val ephPubA = EncryptionManager.decodeBase64(start.ephemeralPubAB64)
        val sigA = EncryptionManager.decodeBase64(start.signatureAB64)

        val startTranscript = CyberLinkProtocol.signatureTranscriptForStart(
            sessionId = start.sessionId,
            challengeId = start.challengeId,
            initiatorNodeId = start.initiatorNodeId,
            responderNodeId = start.responderNodeId,
            nonceA = nonceA,
            ephemeralPubA = ephPubA,
            timestampMs = start.timestampMs,
        )
        if (!localEncryption.verifySignature(peerIdentityPub, startTranscript, sigA)) {
            val msg = "Signaturprüfung für eingehenden ChallengeStart fehlgeschlagen."
            fail(msg, callbackName = "onCharacteristicWriteRequest")
            return Result.failure(SecurityException(msg))
        }
        peerIdentityPublicKeyB64 = EncryptionManager.encodeBase64(peerIdentityPub)

        logStep(
            step = GattStep.CHALLENGE_VALIDATED,
            detail = "ChallengeStart ECDSA-Signatur, Session-ID & Challenge-ID verifiziert",
            gattStatusCode = 0,
            callbackName = "onCharacteristicWriteRequest",
        )

        val ephB = localEncryption.generateEphemeralKeyPair()
        val nonceB = localEncryption.generateNonce(16)
        val ts = System.currentTimeMillis()
        val respTranscript = CyberLinkProtocol.signatureTranscriptForResponse(
            sessionId = start.sessionId,
            challengeId = start.challengeId,
            responderNodeId = localEncryption.nodeId,
            initiatorNodeId = start.initiatorNodeId,
            nonceA = nonceA,
            nonceB = nonceB,
            ephemeralPubB = ephB.public.encoded,
            timestampMs = ts,
        )
        val sigB = localEncryption.signPayload(respTranscript)

        val derived = localEncryption.deriveSession(
            sessionId = start.sessionId,
            localNodeId = localEncryption.nodeId,
            peerNodeId = peerNodeId,
            localEphemeralPrivate = ephB.private,
            peerEphemeralPublicBytes = ephPubA,
            nonceA = nonceA,
            nonceB = nonceB,
        )
        cipherSession = derived
        updateState(TransportState.DATA_CHANNEL_READY)

        val respEnvelope = CyberLinkProtocol.ProtocolEnvelope.ChallengeResponse(
            sessionId = start.sessionId,
            challengeId = start.challengeId,
            responderNodeId = localEncryption.nodeId,
            initiatorNodeId = start.initiatorNodeId,
            nonceAB64 = start.nonceAB64,
            nonceBB64 = EncryptionManager.encodeBase64(nonceB),
            ephemeralPubBB64 = EncryptionManager.encodeBase64(ephB.public.encoded),
            identityPubBB64 = localEncryption.publicKeyBase64,
            timestampMs = ts,
            signatureBB64 = EncryptionManager.encodeBase64(sigB),
        )
        val encoded = CyberLinkProtocol.encodeEnvelope(respEnvelope)
        logStep(
            step = GattStep.RESPONSE_CREATED,
            detail = "ChallengeResponse erstellt & signiert (${encoded.size} B, MTU=$effectiveMtu)",
            gattStatusCode = 0,
        )
        return Result.success(encoded)
    }

    /**
     * Handles incoming reassembled packet (either plaintext handshake frame or AES-GCM encrypted frame).
     */
    fun onIncomingReassembledFrame(
        reassembled: CyberLinkProtocol.PacketReassembler.ReassembledMessage,
        localEncryption: EncryptionManager,
        onResponderVerifyAckReady: (ByteArray) -> Unit = {},
        onApplicationEnvelope: (CyberLinkProtocol.ProtocolEnvelope) -> Unit = {},
    ) {
        val rawBytes = if (reassembled.encrypted) {
            val cipher = cipherSession ?: run {
                fail("Verschlüsseltes Paket vor Session-Schlüsselableitung empfangen")
                return
            }
            try {
                cipher.decrypt(reassembled.payload)
            } catch (t: Throwable) {
                fail("AES-GCM Entschlüsselung / Replay-Prüfung fehlgeschlagen: ${t.message}")
                return
            }
        } else {
            reassembled.payload
        }

        val envelope = try {
            CyberLinkProtocol.decodeEnvelope(rawBytes)
        } catch (t: Throwable) {
            fail("Ungültiges Protokollpaket: ${t.message}")
            return
        }

        when (envelope) {
            is CyberLinkProtocol.ProtocolEnvelope.ChallengeResponse -> {
                if (envelope.sessionId != sessionId || envelope.challengeId != activeChallengeId) {
                    logStep(
                        step = GattStep.STEP_ERROR,
                        detail = "Veraltete ChallengeResponse ignoriert (erwartet sess=${sessionId.take(8)} ch=${activeChallengeId?.take(8)}, erhalten sess=${envelope.sessionId.take(8)} ch=${envelope.challengeId.take(8)})",
                        callbackName = "onCharacteristicChanged",
                        characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                    )
                    return
                }
                logStep(
                    step = GattStep.RESPONSE_RECEIVED,
                    detail = "ChallengeResponse von ${envelope.responderNodeId} empfangen (${reassembled.payload.size} B)",
                    gattStatusCode = 0,
                    callbackName = "onCharacteristicChanged",
                    characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                )
                pendingResponseDeferred?.complete(envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.VerifyAck -> {
                if (!reassembled.encrypted) {
                    fail("Unverschlüsseltes VERIFY_ACK abgelehnt")
                    return
                }
                if (envelope.sessionId != sessionId) {
                    fail("VERIFY_ACK Session-ID mismatch")
                    return
                }
                if (envelope.direction == "A_TO_B") {
                    val cipher = cipherSession ?: return
                    verifyAckReceived = true
                    val replyAck = CyberLinkProtocol.ProtocolEnvelope.VerifyAck(
                        sessionId = sessionId,
                        senderNodeId = localEncryption.nodeId,
                        targetNodeId = peerNodeId,
                        direction = "B_TO_A",
                        timestampMs = System.currentTimeMillis(),
                    )
                    val encryptedReply = cipher.encrypt(CyberLinkProtocol.encodeEnvelope(replyAck))
                    verifyAckSent = true
                    activeTransport = ActiveTransport.BLE_GATT
                    updateState(TransportState.CONNECTED)
                    logStep(
                        step = GattStep.VERIFY_ACK_COMPLETED,
                        detail = "Eingehender VERIFY_ACK geprüft & bidirektionale Bestätigung gesendet",
                        gattStatusCode = 0,
                    )
                    onResponderVerifyAckReady(encryptedReply)
                } else {
                    logStep(
                        step = GattStep.CONFIRM_OK_RECEIVED,
                        detail = "Verschlüsselte B_TO_A VERIFY_ACK-Bestätigung empfangen",
                        gattStatusCode = 0,
                    )
                    pendingVerifyAckDeferred?.complete(envelope)
                }
            }

            is CyberLinkProtocol.ProtocolEnvelope.ConfirmOk -> {
                if (!reassembled.encrypted) {
                    fail("Unverschlüsseltes CONFIRM_OK abgelehnt")
                    return
                }
                logStep(
                    step = GattStep.CONFIRM_OK_RECEIVED,
                    detail = "CONFIRM_OK von ${envelope.senderNodeId} empfangen",
                    gattStatusCode = 0,
                )
                confirmOkReceived = true
                pendingConfirmOkDeferred?.complete(envelope)
            }

            else -> {
                if (!reassembled.encrypted) {
                    fail("Unverschlüsseltes Nutzdatenpaket (${envelope::class.simpleName}) abgelehnt")
                    return
                }
                onApplicationEnvelope(envelope)
            }
        }
    }

    companion object {
        private const val MAX_CLOCK_SKEW_MS = 15 * 60 * 1000L
    }
}
