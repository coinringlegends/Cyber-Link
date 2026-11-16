package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android BLE GATT Client implementing sequential operation queue, API 33+ vs legacy read/write calls,
 * explicit GATT status handling, MTU 23 fallback safety, stale-Gatt callback isolation,
 * proper encrypted flag propagation for VERIFY_ACK, and full Initiator step logging.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localEncryption: EncryptionManager,
    private val onSessionUpdated: (PeerTransportSession) -> Unit,
    private val onApplicationEnvelope: (PeerTransportSession, CyberLinkProtocol.ProtocolEnvelope) -> Unit,
) {
    private val channelIdCounter = AtomicInteger(1)
    private val attemptCounter = AtomicInteger(0)

    @Volatile
    private var currentAttemptId: Int = 0

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var activeSession: PeerTransportSession? = null

    private var connectDeferred: CompletableDeferred<Int>? = null

    fun connectAndHandshake(
        device: BluetoothDevice,
        session: PeerTransportSession,
        peerExpectedPublicKeyBytes: ByteArray?,
    ) {
        val hadActiveGatt = activeGatt != null
        closeCurrent("Neue Verbindung initiiert")
        val myAttemptId = attemptCounter.incrementAndGet()
        currentAttemptId = myAttemptId

        session.resetForNewAttempt()
        session.connectionRole = ConnectionRole.INITIATOR_CLIENT
        session.deviceAddress = device.address
        session.updateState(TransportState.CONNECTING)
        activeSession = session
        onSessionUpdated(session)

        val connDeferred = CompletableDeferred<Int>()
        connectDeferred = connDeferred

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (myAttemptId != currentAttemptId) {
                    runCatching { gatt.close() }
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val statusDesc = describeGattStatus(status)
                    val msg = if (status == 147) {
                        "GATT_CONNECTION_TIMEOUT (147) beim Verbindungsaufbau zu ${session.peerNodeId}"
                    } else {
                        "GATT-Verbindungsfehler: $statusDesc"
                    }
                    session.fail(
                        message = msg,
                        statusCode = status,
                        callbackName = "onConnectionStateChange",
                    )
                    closeGattInternal(gatt)
                    connDeferred.complete(status)
                    onSessionUpdated(session)
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    session.logStep(
                        step = GattStep.GATT_CONNECTED,
                        detail = "BLE GATT verbunden mit ${device.address}",
                        gattStatusCode = status,
                        callbackName = "onConnectionStateChange",
                    )
                    connDeferred.complete(BluetoothGatt.GATT_SUCCESS)
                    onSessionUpdated(session)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    session.closeDisconnected("Peer hat GATT-Verbindung getrennt (STATE_DISCONNECTED)")
                    closeGattInternal(gatt)
                    onSessionUpdated(session)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (myAttemptId != currentAttemptId) return
                val effective = if (status == BluetoothGatt.GATT_SUCCESS) mtu else CyberLinkProtocol.MIN_MTU
                session.updateNegotiatedMtu(effective)
                session.gattQueue.completeActive(
                    expectedOpPrefix = "requestMtu",
                    status = BluetoothGatt.GATT_SUCCESS, // MTU failure falls back to 23 cleanly
                    callbackName = "onMtuChanged",
                )
                onSessionUpdated(session)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (myAttemptId != currentAttemptId) return
                session.gattQueue.completeActive(
                    expectedOpPrefix = "discoverServices",
                    status = status,
                    callbackName = "onServicesDiscovered",
                )
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (myAttemptId != currentAttemptId) return
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: ByteArray(0)
                session.gattQueue.completeActive(
                    expectedOpPrefix = "readChar",
                    status = status,
                    value = bytes,
                    callbackName = "onCharacteristicRead",
                )
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (myAttemptId != currentAttemptId) return
                session.gattQueue.completeActive(
                    expectedOpPrefix = "readChar",
                    status = status,
                    value = value,
                    callbackName = "onCharacteristicRead",
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (myAttemptId != currentAttemptId) return
                session.gattQueue.completeActive(
                    expectedOpPrefix = "writeChar",
                    status = status,
                    callbackName = "onCharacteristicWrite",
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (myAttemptId != currentAttemptId) return
                session.cccdStatusCode = status
                session.cccdWriteSucceeded = (status == BluetoothGatt.GATT_SUCCESS)
                session.gattQueue.completeActive(
                    expectedOpPrefix = "writeDesc",
                    status = status,
                    callbackName = "onDescriptorWrite",
                )
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (myAttemptId != currentAttemptId) return
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                handleIncomingTxNotification(session, bytes)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (myAttemptId != currentAttemptId) return
                handleIncomingTxNotification(session, value)
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (myAttemptId != currentAttemptId) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    session.updateTelemetry(rssi = rssi)
                    onSessionUpdated(session)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            if (hadActiveGatt) {
                delay(150L)
            }
            if (myAttemptId != currentAttemptId) return@launch

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            if (gatt == null) {
                session.fail("connectGatt gab null zurück", callbackName = "connectGatt")
                onSessionUpdated(session)
                return@launch
            }
            activeGatt = gatt

            val connResult = withTimeoutOrNull(12_000L) { connDeferred.await() }
            if (myAttemptId != currentAttemptId) {
                closeGattInternal(gatt)
                return@launch
            }
            if (connResult == null) {
                session.fail(
                    message = "GATT_CONNECTION_TIMEOUT (147): Timeout beim Warten auf onConnectionStateChange.",
                    statusCode = 147,
                    callbackName = "connectGatt",
                )
                closeGattInternal(gatt)
                onSessionUpdated(session)
                return@launch
            }
            if (connResult != BluetoothGatt.GATT_SUCCESS) {
                return@launch
            }

            // Step 1: Request MTU 517 (if it fails or returns 23, MTU 23 remains valid)
            session.gattQueue.enqueue("requestMtu", timeoutMs = 4_000L) {
                gatt.requestMtu(517)
            }

            // Step 2: Service discovery (strictly sequential after MTU)
            session.updateState(TransportState.SERVICE_DISCOVERY)
            session.logStep(
                step = GattStep.SERVICE_DISCOVERY_STARTED,
                detail = "GATT Service Discovery gestartet",
            )
            onSessionUpdated(session)

            val discRes = session.gattQueue.enqueue("discoverServices", timeoutMs = 10_000L) {
                gatt.discoverServices()
            }
            if (discRes is GattOperationQueue.GattOpResult.Failed) {
                session.fail(
                    message = "Service Discovery fehlgeschlagen: ${discRes.reason}",
                    statusCode = discRes.statusCode,
                    callbackName = "onServicesDiscovered",
                )
                closeGattInternal(gatt)
                onSessionUpdated(session)
                return@launch
            }

            val service = gatt.getService(CyberLinkUuids.SERVICE_UUID)
            if (service == null) {
                session.fail(
                    message = "CYBER LINK GATT Service (${CyberLinkUuids.SERVICE_UUID}) nicht gefunden",
                    callbackName = "onServicesDiscovered",
                )
                closeGattInternal(gatt)
                onSessionUpdated(session)
                return@launch
            }
            session.logStep(
                step = GattStep.SERVICE_DISCOVERY_COMPLETED,
                detail = "CYBER LINK Service mit ${service.characteristics.size} Characteristics gefunden",
                gattStatusCode = 0,
                callbackName = "onServicesDiscovered",
            )

            // Step 3: Read Identity Characteristic
            val idChar = service.getCharacteristic(CyberLinkUuids.IDENTITY_CHAR_UUID)
            if (idChar != null) {
                session.logStep(
                    step = GattStep.IDENTITY_READ_STARTED,
                    detail = "Lese Peer-Identität",
                    characteristicUuid = CyberLinkUuids.IDENTITY_CHAR_UUID.toString(),
                )
                val readRes = session.gattQueue.enqueue("readChar_identity", timeoutMs = 5_000L) {
                    gatt.readCharacteristic(idChar)
                }
                if (readRes is GattOperationQueue.GattOpResult.Success && readRes.value.isNotEmpty()) {
                    var identityMismatch: String? = null
                    runCatching {
                        val env = CyberLinkProtocol.decodeEnvelope(readRes.value)
                        if (env is CyberLinkProtocol.ProtocolEnvelope.IdentityInfo) {
                            session.deviceName = env.deviceName
                            if (env.nodeId.isNotBlank() && session.peerNodeId.startsWith("CL-") && env.nodeId != session.peerNodeId) {
                                identityMismatch = "GATT-Identitätskonflikt: erwartet ${session.peerNodeId}, Gerät meldet ${env.nodeId}"
                            }
                        }
                    }
                    if (identityMismatch != null) {
                        session.fail(
                            message = identityMismatch!!,
                            callbackName = "onCharacteristicRead",
                            characteristicUuid = CyberLinkUuids.IDENTITY_CHAR_UUID.toString(),
                        )
                        closeGattInternal(gatt)
                        onSessionUpdated(session)
                        return@launch
                    }
                    session.logStep(
                        step = GattStep.IDENTITY_READ_COMPLETED,
                        detail = "GATT-Identität gelesen: ${session.deviceName ?: session.peerNodeId}",
                        gattStatusCode = 0,
                        callbackName = "onCharacteristicRead",
                        characteristicUuid = CyberLinkUuids.IDENTITY_CHAR_UUID.toString(),
                    )
                }
            }

            // Step 4: Inspect RX & TX Characteristics & CCCD
            val rxChar = service.getCharacteristic(CyberLinkUuids.RX_CHAR_UUID)
            if (rxChar != null) {
                session.rxSpec = GattCapabilityValidator.CharacteristicSpec(
                    uuid = rxChar.uuid,
                    properties = rxChar.properties,
                    hasCccdDescriptor = rxChar.getDescriptor(CyberLinkUuids.CCCD_UUID) != null,
                )
                session.logStep(
                    step = GattStep.RX_CHARACTERISTIC_FOUND,
                    detail = "RX Characteristic gefunden (props=0x${rxChar.properties.toString(16)})",
                    characteristicUuid = rxChar.uuid.toString(),
                )
            }

            val txChar = service.getCharacteristic(CyberLinkUuids.TX_CHAR_UUID)
            if (txChar != null) {
                val cccd = txChar.getDescriptor(CyberLinkUuids.CCCD_UUID)
                session.txSpec = GattCapabilityValidator.CharacteristicSpec(
                    uuid = txChar.uuid,
                    properties = txChar.properties,
                    hasCccdDescriptor = cccd != null,
                )
                session.logStep(
                    step = GattStep.TX_CHARACTERISTIC_FOUND,
                    detail = "TX Characteristic gefunden (props=0x${txChar.properties.toString(16)}, cccd=${cccd != null})",
                    characteristicUuid = txChar.uuid.toString(),
                )

                // Step 5: Enable local notification + write CCCD 0x2902 sequentially
                val localNotifyOk = gatt.setCharacteristicNotification(txChar, true)
                session.notificationLocallyEnabled = localNotifyOk
                if (localNotifyOk && cccd != null) {
                    val isNotify = (txChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val enableValue = if (isNotify) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }
                    session.logStep(
                        step = GattStep.CCCD_WRITE_STARTED,
                        detail = "Schreibe CCCD 0x2902 (${if (isNotify) "NOTIFY" else "INDICATION"})",
                        characteristicUuid = txChar.uuid.toString(),
                    )
                    val cccdRes = session.gattQueue.enqueueStatus("writeDesc_cccd", timeoutMs = 5_000L) {
                        writeDescriptorCompatStatus(gatt, cccd, enableValue)
                    }
                    if (cccdRes is GattOperationQueue.GattOpResult.Success) {
                        session.cccdWriteSucceeded = true
                        session.cccdStatusCode = 0
                        session.logStep(
                            step = GattStep.CCCD_WRITE_COMPLETED,
                            detail = "CCCD 0x2902 erfolgreich aktiviert",
                            gattStatusCode = 0,
                            callbackName = "onDescriptorWrite",
                            characteristicUuid = txChar.uuid.toString(),
                        )
                    } else if (cccdRes is GattOperationQueue.GattOpResult.Failed) {
                        session.cccdWriteSucceeded = false
                        session.cccdStatusCode = cccdRes.statusCode
                    }
                }
            }

            onSessionUpdated(session)

            // Step 6: Validate ALL prerequisites and run sequential Challenge-Response
            val result = session.runClientChallengeHandshake(
                localEncryption = localEncryption,
                peerExpectedPublicKeyBytes = peerExpectedPublicKeyBytes,
                sendBytesChunked = { payload, writeType, encrypted ->
                    sendChunkedToRx(gatt, rxChar!!, session, payload, writeType, encrypted = encrypted)
                },
                onStepLogged = { onSessionUpdated(session) },
            )
            if (result.isFailure) {
                closeGattInternal(gatt)
            } else {
                gatt.readRemoteRssi()
            }
            onSessionUpdated(session)
        }
    }

    suspend fun sendEncryptedEnvelope(
        session: PeerTransportSession,
        envelope: CyberLinkProtocol.ProtocolEnvelope,
    ): Result<Unit> {
        val gatt = activeGatt ?: return Result.failure(IllegalStateException("Keine aktive GATT-Verbindung"))
        val service = gatt.getService(CyberLinkUuids.SERVICE_UUID)
            ?: return Result.failure(IllegalStateException("CYBER LINK Service fehlt"))
        val rxChar = service.getCharacteristic(CyberLinkUuids.RX_CHAR_UUID)
            ?: return Result.failure(IllegalStateException("RX Characteristic fehlt"))
        val cipher = session.cipherSession
            ?: return Result.failure(IllegalStateException("Keine verschlüsselte Session aktiv"))

        val encrypted = cipher.encrypt(CyberLinkProtocol.encodeEnvelope(envelope))
        val writeType = if ((rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val res = sendChunkedToRx(gatt, rxChar, session, encrypted, writeType, encrypted = true)
        return when (res) {
            is GattOperationQueue.GattOpResult.Success -> Result.success(Unit)
            is GattOperationQueue.GattOpResult.Failed -> Result.failure(IllegalStateException(res.reason))
        }
    }

    private suspend fun sendChunkedToRx(
        gatt: BluetoothGatt,
        rxChar: BluetoothGattCharacteristic,
        session: PeerTransportSession,
        payload: ByteArray,
        writeType: Int,
        encrypted: Boolean,
    ): GattOperationQueue.GattOpResult {
        val channelId = channelIdCounter.getAndIncrement() and 0xFFFF
        val chunks = CyberLinkProtocol.chunkPayload(
            channelId = channelId,
            payload = payload,
            mtu = session.effectiveMtu,
            encrypted = encrypted,
        )
        for ((index, chunk) in chunks.withIndex()) {
            var res = session.gattQueue.enqueueStatus("writeChar_rx_$index", timeoutMs = 5_000L) {
                writeCharacteristicCompatStatus(gatt, rxChar, chunk, writeType)
            }
            if (res is GattOperationQueue.GattOpResult.Failed && (res.statusCode == 201 || res.statusCode == -10)) {
                delay(60L)
                res = session.gattQueue.enqueueStatus("writeChar_rx_${index}_retry", timeoutMs = 5_000L) {
                    writeCharacteristicCompatStatus(gatt, rxChar, chunk, writeType)
                }
            }
            if (res is GattOperationQueue.GattOpResult.Failed) {
                return res
            }
        }
        return GattOperationQueue.GattOpResult.Success()
    }

    private fun handleIncomingTxNotification(
        session: PeerTransportSession,
        bytes: ByteArray,
    ) {
        val reassembled = session.reassembler.feed(bytes) ?: return
        session.onIncomingReassembledFrame(
            reassembled = reassembled,
            localEncryption = localEncryption,
            onApplicationEnvelope = { env ->
                onApplicationEnvelope(session, env)
            },
        )
        onSessionUpdated(session)
    }

    private fun writeCharacteristicCompatStatus(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int,
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else -10
        }
    }

    private fun writeDescriptorCompatStatus(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        data: ByteArray,
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, data)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = data
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) BluetoothGatt.GATT_SUCCESS else -10
        }
    }

    fun closeCurrent(reason: String) {
        currentAttemptId = attemptCounter.incrementAndGet()
        activeGatt?.let { closeGattInternal(it) }
        activeSession?.closeDisconnected(reason)
        activeSession = null
    }

    private fun closeGattInternal(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        if (activeGatt == gatt) {
            activeGatt = null
        }
    }
}
