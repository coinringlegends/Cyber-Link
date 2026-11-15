package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android BLE GATT Server + Connectable Advertiser.
 * Hosts CYBER LINK Service (IDENTITY read, RX write/write_no_response, TX notify+CCCD 0x2902).
 * Properly handles API 33+ notifyCharacteristicChanged vs legacy setValue+notifyCharacteristicChanged,
 * validates incoming ChallengeStart, and transmits encrypted VERIFY_ACK responses sequentially.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localEncryption: EncryptionManager,
    private val getOrCreateSessionForDevice: (BluetoothDevice, String?) -> PeerTransportSession,
    private val lookupKnownPublicKey: (String) -> ByteArray?,
    private val onSessionUpdated: (PeerTransportSession) -> Unit,
    private val onApplicationEnvelope: (PeerTransportSession, CyberLinkProtocol.ProtocolEnvelope) -> Unit,
    private val onAdvertiseStateChanged: (isAdvertising: Boolean, supported: Boolean, errorCode: Int?, errorDetail: String?) -> Unit = { _, _, _, _ -> },
) {
    private val androidBtManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val cccdEnabledByAddress = ConcurrentHashMap<String, Boolean>()
    private val serverChannelId = AtomicInteger(1000)
    private var lastLocalName: String = "CYBER-LINK"

    @Volatile
    var isAdvertising: Boolean = false
        private set

    @Volatile
    var lastAdvertiseError: String? = null
        private set

    @Volatile
    var lastAdvertiseErrorCode: Int? = null
        private set

    fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startServerAndAdvertising(adapter: BluetoothAdapter?, localDeviceName: String) {
        lastLocalName = localDeviceName
        if (adapter == null || !adapter.isEnabled) {
            isAdvertising = false
            lastAdvertiseErrorCode = -1
            lastAdvertiseError = describeAdvertiseError(-1)
            onAdvertiseStateChanged(false, false, -1, lastAdvertiseError)
            return
        }
        if (!hasConnectPermission()) {
            isAdvertising = false
            lastAdvertiseErrorCode = -2
            lastAdvertiseError = "BLUETOOTH_CONNECT Berechtigung fehlt"
            onAdvertiseStateChanged(false, adapter.isMultipleAdvertisementSupported, -2, lastAdvertiseError)
            return
        }
        startGattServer()
        startAdvertising(adapter)
    }

    fun restartAdvertisingIfNeeded(adapter: BluetoothAdapter?) {
        if (!isAdvertising && adapter != null && adapter.isEnabled) {
            startServerAndAdvertising(adapter, lastLocalName)
        }
    }

    private fun startGattServer() {
        if (gattServer != null) return

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices[device.address] = device
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device.address)
                    cccdEnabledByAddress.remove(device.address)
                    // Restart advertising in case the Android BT stack halted peripheral advertising on disconnect
                    val adapter = androidBtManager.adapter
                    if (adapter != null && adapter.isEnabled) {
                        isAdvertising = false
                        startAdvertising(adapter)
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                val session = getOrCreateSessionForDevice(device, null)
                session.updateNegotiatedMtu(mtu)
                onSessionUpdated(session)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == CyberLinkUuids.IDENTITY_CHAR_UUID) {
                    val info = CyberLinkProtocol.ProtocolEnvelope.IdentityInfo(
                        nodeId = localEncryption.nodeId,
                        deviceName = Build.MODEL ?: "Android",
                        publicKeyB64 = localEncryption.publicKeyBase64,
                    )
                    val bytes = CyberLinkProtocol.encodeEnvelope(info)
                    val slice = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (descriptor.uuid == CyberLinkUuids.CCCD_UUID) {
                    val enabled = value != null && (
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        )
                    cccdEnabledByAddress[device.address] = enabled
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                } else if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (characteristic.uuid != CyberLinkUuids.RX_CHAR_UUID || value == null) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                    }
                    return
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }

                val session = getOrCreateSessionForDevice(device, null)
                val reassembled = session.reassembler.feed(value) ?: return
                scope.launch(Dispatchers.IO) {
                    handleServerReassembled(device, session, reassembled)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                val session = getOrCreateSessionForDevice(device, null)
                session.gattQueue.completeActive(
                    expectedOpPrefix = "notifyTx",
                    status = status,
                    callbackName = "onNotificationSent",
                )
            }
        }

        val server = androidBtManager.openGattServer(context, callback) ?: return
        val service = BluetoothGattService(
            CyberLinkUuids.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val identityChar = BluetoothGattCharacteristic(
            CyberLinkUuids.IDENTITY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val rxChar = BluetoothGattCharacteristic(
            CyberLinkUuids.RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val txChar = BluetoothGattCharacteristic(
            CyberLinkUuids.TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            CyberLinkUuids.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        txChar.addDescriptor(cccd)
        txCharacteristic = txChar

        service.addCharacteristic(identityChar)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        server.addService(service)
        gattServer = server
    }

    private suspend fun handleServerReassembled(
        device: BluetoothDevice,
        session: PeerTransportSession,
        reassembled: CyberLinkProtocol.PacketReassembler.ReassembledMessage,
    ) {
        if (!reassembled.encrypted) {
            val env = runCatching { CyberLinkProtocol.decodeEnvelope(reassembled.payload) }.getOrNull()
            if (env is CyberLinkProtocol.ProtocolEnvelope.ChallengeStart) {
                val realSession = getOrCreateSessionForDevice(device, env.initiatorNodeId)
                realSession.updateNegotiatedMtu(session.effectiveMtu)
                val knownKey = lookupKnownPublicKey(env.initiatorNodeId)
                val respResult = realSession.processIncomingChallengeStart(
                    localEncryption = localEncryption,
                    start = env,
                    peerExpectedPublicKeyBytes = knownKey,
                )
                respResult.onSuccess { respBytes ->
                    sendChunkedNotification(device, realSession, respBytes, encrypted = false)
                }
                onSessionUpdated(realSession)
                return
            }
        }

        session.onIncomingReassembledFrame(
            reassembled = reassembled,
            localEncryption = localEncryption,
            onResponderVerifyAckReady = { encryptedReply ->
                scope.launch(Dispatchers.IO) {
                    sendChunkedNotification(device, session, encryptedReply, encrypted = true)
                    onSessionUpdated(session)
                }
            },
            onApplicationEnvelope = { env ->
                onApplicationEnvelope(session, env)
            },
        )
        onSessionUpdated(session)
    }

    suspend fun sendEncryptedNotification(
        deviceAddress: String,
        session: PeerTransportSession,
        envelope: CyberLinkProtocol.ProtocolEnvelope,
    ): Result<Unit> {
        val device = connectedDevices[deviceAddress]
            ?: return Result.failure(IllegalStateException("Gerät $deviceAddress nicht am GATT-Server verbunden"))
        val cipher = session.cipherSession
            ?: return Result.failure(IllegalStateException("Keine aktive Session-Verschlüsselung"))
        val encrypted = cipher.encrypt(CyberLinkProtocol.encodeEnvelope(envelope))
        return sendChunkedNotification(device, session, encrypted, encrypted = true)
    }

    private suspend fun sendChunkedNotification(
        device: BluetoothDevice,
        session: PeerTransportSession,
        payload: ByteArray,
        encrypted: Boolean,
    ): Result<Unit> {
        val server = gattServer ?: return Result.failure(IllegalStateException("GATT-Server nicht gestartet"))
        val tx = txCharacteristic ?: return Result.failure(IllegalStateException("TX Characteristic fehlt"))
        val chunks = CyberLinkProtocol.chunkPayload(
            channelId = serverChannelId.getAndIncrement() and 0xFFFF,
            payload = payload,
            mtu = session.effectiveMtu,
            encrypted = encrypted,
        )
        for ((idx, chunk) in chunks.withIndex()) {
            val res = session.gattQueue.enqueue("notifyTx_$idx", timeoutMs = 5_000L) {
                notifyCompat(server, device, tx, chunk)
            }
            if (res is GattOperationQueue.GattOpResult.Failed) {
                return Result.failure(IllegalStateException(res.reason))
            }
        }
        return Result.success(Unit)
    }

    private fun notifyCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        tx: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = server.notifyCharacteristicChanged(device, tx, false, data)
            status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = data
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, tx, false)
        }
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (!hasAdvertisePermission()) {
            isAdvertising = false
            lastAdvertiseErrorCode = -2
            lastAdvertiseError = describeAdvertiseError(-2)
            onAdvertiseStateChanged(false, adapter.isMultipleAdvertisementSupported, -2, lastAdvertiseError)
            return
        }

        val adv = adapter.bluetoothLeAdvertiser
        val hwSupported = adapter.isMultipleAdvertisementSupported && adv != null
        if (adv == null || !adapter.isMultipleAdvertisementSupported) {
            isAdvertising = false
            lastAdvertiseErrorCode = AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
            lastAdvertiseError = describeAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            onAdvertiseStateChanged(false, hwSupported, lastAdvertiseErrorCode, lastAdvertiseError)
            return
        }

        // Stop previous active advertising before restarting so ADVERTISE_FAILED_ALREADY_STARTED (3) cannot happen
        advertiseCallback?.let { prev ->
            runCatching { adv.stopAdvertising(prev) }
        }
        advertiseCallback = null
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Primary packet: Flags (3B) + 128-bit SERVICE_UUID (18B) = 21 bytes (safely <= 31B)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(CyberLinkUuids.SERVICE_UUID))
            .build()

        // ScanResponse: ManufacturerData(0xC1B3, [0x05] + 6B binary node hash) = 11 bytes (safely <= 31B!)
        val mfgPayload = BleAdvertisementCodec.encodeManufacturerNodePayload(localEncryption.nodeId)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BleAdvertisementCodec.MANUFACTURER_ID, mfgPayload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                lastAdvertiseErrorCode = null
                lastAdvertiseError = null
                onAdvertiseStateChanged(true, true, null, null)
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                lastAdvertiseErrorCode = errorCode
                lastAdvertiseError = describeAdvertiseError(errorCode)
                onAdvertiseStateChanged(false, hwSupported, errorCode, lastAdvertiseError)
            }
        }
        advertiseCallback = cb
        runCatching {
            adv.startAdvertising(settings, data, scanResponse, cb)
        }.onFailure { err ->
            isAdvertising = false
            val code = if (err is SecurityException) -2 else AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
            lastAdvertiseErrorCode = code
            lastAdvertiseError = "${describeAdvertiseError(code)}: ${err.message}"
            onAdvertiseStateChanged(false, hwSupported, code, lastAdvertiseError)
        }
    }

    fun stopAdvertisingOnly() {
        advertiseCallback?.let { cb ->
            runCatching { advertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
        isAdvertising = false
        onAdvertiseStateChanged(false, advertiser != null, lastAdvertiseErrorCode, lastAdvertiseError)
    }

    fun stop() {
        stopAdvertisingOnly()
        runCatching { gattServer?.close() }
        gattServer = null
        connectedDevices.clear()
        cccdEnabledByAddress.clear()
    }
}
