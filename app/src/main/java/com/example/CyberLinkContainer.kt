package com.example

import android.content.Context
import com.example.battery.BatteryMode
import com.example.bluetooth.BluetoothManager
import com.example.crypto.EncryptionManager
import com.example.crypto.LocalStorageCipher
import com.example.data.SettingsStore
import com.example.data.db.ContactEntity
import com.example.data.db.CyberLinkDatabase
import com.example.data.db.MessageEntity
import com.example.map.BluetoothMapUpdateEngine
import com.example.map.LocationSharingManager
import com.example.map.MapStorageManager
import com.example.map.OfflineGpsEngine
import com.example.map.OfflineRoutingEngine
import com.example.map.OfflineSearchEngine
import com.example.media.AudioCallManager
import com.example.media.BluetoothFileTransferManager
import com.example.media.VideoCallManager
import com.example.mesh.MeshRouter
import com.example.protocol.CyberLinkProtocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CyberLinkContainer(val appContext: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = CyberLinkDatabase.getInstance(appContext)
    val settingsStore = SettingsStore(appContext)

    val encryptionManager: EncryptionManager = runBlocking {
        settingsStore.loadOrCreateEncryptionManager()
    }

    val localStorageCipher: LocalStorageCipher = runBlocking {
        settingsStore.loadOrCreateLocalStorageCipher()
    }

    private val contactKeyCache = ConcurrentHashMap<String, ByteArray>()

    val meshRouter = MeshRouter()

    val bluetoothManager: BluetoothManager by lazy {
        BluetoothManager(
            context = appContext,
            scope = appScope,
            encryptionManager = encryptionManager,
            lookupContactPublicKey = { nodeId -> contactKeyCache[nodeId] },
            onPersistConsumedToken = { token, expiresAtMs ->
                appScope.launch {
                    settingsStore.consumePairingToken(token, expiresAtMs)
                }
            },
            onPeerAuthenticatedCallback = { peerNodeId ->
                appScope.launch {
                    flushQueuedForPeer(peerNodeId)
                }
            },
        ).also { bt ->
            runBlocking {
                bt.seedConsumedTokens(settingsStore.getConsumedPairingTokens())
            }
        }
    }

    val fileTransferManager: BluetoothFileTransferManager by lazy {
        BluetoothFileTransferManager(
            context = appContext,
            bluetoothManager = bluetoothManager,
            messageDao = database.messageDao(),
            localStorageCipher = localStorageCipher,
        )
    }

    val mapStorageManager = MapStorageManager(
        context = appContext,
        regionDao = database.mapRegionDao(),
        packageDao = database.mapPackageDao(),
    )

    val offlineGpsEngine = OfflineGpsEngine(appContext)

    val locationSharingManager: LocationSharingManager by lazy {
        LocationSharingManager(
            contactDao = database.contactDao(),
            locationShareDao = database.locationShareDao(),
            contactLocationDao = database.contactLocationDao(),
            settingsStore = settingsStore,
            bluetoothManager = bluetoothManager,
        )
    }

    val mapUpdateEngine: BluetoothMapUpdateEngine by lazy {
        BluetoothMapUpdateEngine(
            storageManager = mapStorageManager,
            packageDao = database.mapPackageDao(),
            bluetoothManager = bluetoothManager,
        )
    }

    val offlineSearchEngine = OfflineSearchEngine(
        storageManager = mapStorageManager,
        regionDao = database.mapRegionDao(),
        waypointDao = database.mapWaypointDao(),
    )

    val offlineRoutingEngine = OfflineRoutingEngine(mapStorageManager)

    val audioCallManager: AudioCallManager by lazy {
        AudioCallManager(appContext, appScope, bluetoothManager)
    }

    val videoCallManager: VideoCallManager by lazy {
        VideoCallManager(appScope, bluetoothManager)
    }

    init {
        appScope.launch {
            database.contactDao().observeAll().collect { contacts ->
                contactKeyCache.clear()
                contacts.filter { !it.blocked }.forEach { contact ->
                    runCatching { EncryptionManager.decodeBase64(contact.publicKeyB64) }
                        .getOrNull()
                        ?.let { contactKeyCache[contact.nodeId] = it }
                }
            }
        }

        appScope.launch {
            bluetoothManager.incomingEnvelopes.collect { (session, envelope) ->
                handleIncomingEnvelope(session.peerNodeId, envelope)
            }
        }
    }

    suspend fun flushQueuedForPeer(peerNodeId: String) {
        val queued = database.messageDao().getQueuedMessages().filter { it.peerNodeId == peerNodeId }
        for (msg in queued) {
            if (msg.attachmentPath != null && (msg.messageType == "IMAGE" || msg.messageType == "FILE")) {
                fileTransferManager.transmitQueuedAttachment(msg)
            } else {
                val plainText = localStorageCipher.decryptString(msg.content)
                val res = bluetoothManager.sendEnvelope(
                    peerNodeId,
                    CyberLinkProtocol.ProtocolEnvelope.ChatMessage(
                        messageId = msg.messageId,
                        senderNodeId = encryptionManager.nodeId,
                        recipientNodeId = peerNodeId,
                        groupId = msg.groupId,
                        text = plainText,
                        messageType = msg.messageType,
                        priority = msg.priority,
                        timestampMs = msg.timestamp,
                    ),
                )
                if (res.isSuccess) {
                    database.messageDao().updateStatus(msg.messageId, "SENT")
                }
            }
        }
    }

    private suspend fun handleIncomingEnvelope(
        fromPeerNodeId: String,
        envelope: CyberLinkProtocol.ProtocolEnvelope,
    ) {
        val contact = database.contactDao().getById(fromPeerNodeId)
        if (contact?.blocked == true) return

        when (envelope) {
            is CyberLinkProtocol.ProtocolEnvelope.ChatMessage -> {
                database.messageDao().upsert(
                    MessageEntity(
                        messageId = envelope.messageId,
                        peerNodeId = if (envelope.groupId == null) fromPeerNodeId else envelope.recipientNodeId,
                        groupId = envelope.groupId,
                        senderNodeId = envelope.senderNodeId,
                        content = localStorageCipher.encryptString(envelope.text),
                        messageType = envelope.messageType,
                        priority = envelope.priority,
                        status = "DELIVERED",
                        timestamp = envelope.timestampMs,
                    )
                )
                bluetoothManager.sendEnvelope(
                    fromPeerNodeId,
                    CyberLinkProtocol.ProtocolEnvelope.MessageAck(
                        messageId = envelope.messageId,
                        senderNodeId = encryptionManager.nodeId,
                        timestampMs = System.currentTimeMillis(),
                    ),
                )
            }

            is CyberLinkProtocol.ProtocolEnvelope.MessageAck -> {
                database.messageDao().updateStatus(envelope.messageId, "DELIVERED")
            }

            is CyberLinkProtocol.ProtocolEnvelope.MessageReadAck -> {
                envelope.messageIds.forEach { msgId ->
                    database.messageDao().updateStatus(msgId, "READ")
                }
            }

            is CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer -> {
                fileTransferManager.onOfferReceived(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.FileTransferChunk -> {
                fileTransferManager.onChunkReceived(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.FileTransferAck -> {
                fileTransferManager.onAckReceived(envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.LocationShareUpdate -> {
                locationSharingManager.handleIncomingLocationUpdate(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.LocationShareRevoke -> {
                locationSharingManager.handleIncomingRevoke(fromPeerNodeId)
            }

            is CyberLinkProtocol.ProtocolEnvelope.MapPackageOffer -> {
                mapUpdateEngine.onOfferReceived(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.MapPackageRequest -> {
                mapUpdateEngine.onPackageRequestReceived(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.MapChunkPacket -> {
                mapUpdateEngine.onChunkReceived(fromPeerNodeId, envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.MapChunkAck -> {
                mapUpdateEngine.onChunkAckReceived(envelope)
            }

            is CyberLinkProtocol.ProtocolEnvelope.CallSignal -> {
                if (envelope.callType == "AUDIO") {
                    audioCallManager.onIncomingCallSignal(fromPeerNodeId, envelope)
                } else if (envelope.callType == "VIDEO") {
                    videoCallManager.onIncomingCallSignal(fromPeerNodeId, envelope)
                }
            }

            is CyberLinkProtocol.ProtocolEnvelope.MeshRelayPacket -> {
                val optIn = settingsStore.meshRelayOptInFlow.first()
                val mode = settingsStore.batteryModeFlow.first()
                meshRouter.handleIncomingMeshPacket(
                    packet = envelope,
                    localNodeId = encryptionManager.nodeId,
                    userOptInEnabled = optIn,
                    batteryMode = mode,
                )
            }

            else -> Unit
        }
    }

    suspend fun saveVerifiedContact(
        nodeId: String,
        displayName: String,
        publicKeyB64: String,
        cl2Code: String,
    ) {
        database.contactDao().upsert(
            ContactEntity(
                nodeId = nodeId,
                displayName = displayName,
                publicKeyB64 = publicKeyB64,
                cl2Code = cl2Code,
                verified = true,
                blocked = false,
                lastSeenAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun wipeAllLocalData() {
        locationSharingManager.triggerEmergencyStop()
        database.contactDao().deleteAll()
        database.messageDao().deleteAll()
        database.mapRegionDao().deleteAll()
        database.mapPackageDao().deleteAll()
        database.locationShareDao().deleteAll()
        database.contactLocationDao().deleteAll()
        database.mapWaypointDao().deleteAll()
        database.mapRouteDao().deleteAll()
        mapStorageManager.rootDir.deleteRecursively()
        File(appContext.filesDir, "cyber_media").deleteRecursively()
        meshRouter.clear()
    }
}
