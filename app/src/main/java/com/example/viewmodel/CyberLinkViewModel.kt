package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CyberLinkApp
import com.example.battery.BatteryMode
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BleDiagnostics
import com.example.bluetooth.DiscoveredBluetoothPeer
import com.example.bluetooth.GattLogEntry
import com.example.bluetooth.IncomingPairingPrompt
import com.example.bluetooth.PeerManager
import com.example.bluetooth.PeerTransportSession
import com.example.bluetooth.QrPairingProgress
import com.example.bluetooth.RfcommDiagnostics
import com.example.bluetooth.TransportState
import com.example.crypto.EncryptionManager
import com.example.data.db.ContactEntity
import com.example.data.db.ContactLocationEntity
import com.example.data.db.LocationShareEntity
import com.example.data.db.MapPackageEntity
import com.example.data.db.MapRegionEntity
import com.example.data.db.MapRouteEntity
import com.example.data.db.MapWaypointEntity
import com.example.data.db.MessageEntity
import com.example.map.BluetoothMapUpdateEngine
import com.example.map.ContactMapStatusResolver
import com.example.map.LocationSharingManager
import com.example.map.OfflineGpsEngine
import com.example.map.OfflineRoutingEngine
import com.example.map.OfflineSearchEngine
import com.example.media.AudioCallManager
import com.example.media.BluetoothFileTransferManager
import com.example.media.VideoCallManager
import com.example.protocol.CyberLinkProtocol
import com.example.service.BluetoothTransportService
import com.example.service.LocationSharingService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CyberLinkViewModel(application: Application) : AndroidViewModel(application) {

    val container = (application as CyberLinkApp).container
    private val db = container.database

    val localNodeId: String = container.encryptionManager.nodeId
    val localPublicKeyB64: String = container.encryptionManager.publicKeyBase64
    val localCl2Code: String = PeerManager.deriveCl2Code(
        localNodeId,
        container.encryptionManager.exportPublicKeyX509(),
    )

    val displayName: StateFlow<String> = container.settingsStore.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "CyberNode")

    val batteryMode: StateFlow<BatteryMode> = container.settingsStore.batteryModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryMode.NORMAL)

    val meshRelayOptIn: StateFlow<Boolean> = container.settingsStore.meshRelayOptInFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val globalLocationKillSwitch: StateFlow<Boolean> = container.settingsStore.globalLocationKillSwitchFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val discoveredPeers: StateFlow<List<DiscoveredBluetoothPeer>> = container.bluetoothManager.discoveredPeers
    val sessions: StateFlow<Map<String, PeerTransportSession>> = container.bluetoothManager.sessions
    val isScanning: StateFlow<Boolean> = container.bluetoothManager.isScanning
    val isAdvertising: StateFlow<Boolean> = container.bluetoothManager.isAdvertising
    val bleDiagnostics: StateFlow<BleDiagnostics> = container.bluetoothManager.bleDiagnostics
    val rfcommDiagnostics: StateFlow<RfcommDiagnostics> = container.bluetoothManager.rfcommDiagnostics
    val qrPairingProgress: StateFlow<QrPairingProgress> = container.bluetoothManager.qrPairingProgress
    val incomingPairingPrompt: StateFlow<IncomingPairingPrompt?> = container.bluetoothManager.incomingPairingPrompt

    val contacts: StateFlow<List<ContactEntity>> = db.contactDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentMessages: StateFlow<List<MessageEntity>> = db.messageDao().observeRecent()
        .map { list ->
            list.map { msg ->
                msg.copy(content = container.localStorageCipher.decryptString(msg.content))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fileTransfers: StateFlow<Map<String, BluetoothFileTransferManager.TransferStatus>> =
        container.fileTransferManager.transfers

    val mapRegions: StateFlow<List<MapRegionEntity>> = db.mapRegionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mapPackages: StateFlow<List<MapPackageEntity>> = db.mapPackageDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationShares: StateFlow<List<LocationShareEntity>> = db.locationShareDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contactLocations: StateFlow<List<ContactLocationEntity>> = db.contactLocationDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waypoints: StateFlow<List<MapWaypointEntity>> = db.mapWaypointDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routes: StateFlow<List<MapRouteEntity>> = db.mapRouteDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val gpsSnapshot: StateFlow<OfflineGpsEngine.GpsSnapshot> = container.offlineGpsEngine.snapshot
    val backgroundSharingState: StateFlow<LocationSharingManager.BackgroundSharingState> =
        container.locationSharingManager.backgroundState
    val mapTransfers: StateFlow<Map<String, BluetoothMapUpdateEngine.TransferStatus>> =
        container.mapUpdateEngine.transfers
    val audioCallState: StateFlow<AudioCallManager.AudioCallState> = container.audioCallManager.state
    val videoCallState: StateFlow<VideoCallManager.VideoCallState> = container.videoCallManager.state

    private val _currentQrPayload = MutableStateFlow(createAndRegisterQrPayload(displayName.value))
    val currentQrPayload: StateFlow<PeerManager.PairingQrPayload> = _currentQrPayload.asStateFlow()

    val friendMarkers: StateFlow<List<ContactMapStatusResolver.CyberFriendMarker>> = combine(
        contacts,
        sessions,
        contactLocations,
        gpsSnapshot,
    ) { contactList, sessionMap, locs, ownGps ->
        val locById = locs.associateBy { it.contactNodeId }
        contactList.filter { !it.blocked }.map { contact ->
            val session = sessionMap[contact.nodeId]
            val state = session?.transportState ?: TransportState.SAVED
            val activeTransport = container.bluetoothManager.getEffectiveTransportForPeer(contact.nodeId)
            ContactMapStatusResolver.buildMarker(
                contact = contact,
                transportState = state,
                activeTransport = activeTransport,
                location = locById[contact.nodeId],
                ownGps = ownGps,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _searchResult = MutableStateFlow<OfflineSearchEngine.SearchResult?>(null)
    val searchResult: StateFlow<OfflineSearchEngine.SearchResult?> = _searchResult.asStateFlow()

    private val _routingResult = MutableStateFlow<OfflineRoutingEngine.RoutingResult?>(null)
    val routingResult: StateFlow<OfflineRoutingEngine.RoutingResult?> = _routingResult.asStateFlow()

    init {
        // Periodically broadcast own GPS fix to explicitly authorized contacts only
        viewModelScope.launch {
            combine(
                gpsSnapshot,
                batteryMode,
                globalLocationKillSwitch,
            ) { gps, mode, kill -> Triple(gps, mode, kill) }.collect { (gps, mode, kill) ->
                container.locationSharingManager.broadcastGpsFixIfAuthorized(gps, mode, kill)
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun selectPeer(nodeId: String) {
        _selectedPeerId.value = nodeId
    }

    fun ensureBluetoothAdvertisingAndStandby() {
        val ctx = getApplication<Application>()
        runCatching { BluetoothTransportService.start(ctx) }
        container.bluetoothManager.ensurePeripheralServicesActive(displayName.value)
    }

    fun startBluetoothServicesAndScan(continuous: Boolean = false) {
        val ctx = getApplication<Application>()
        runCatching { BluetoothTransportService.start(ctx) }
        container.bluetoothManager.startPeripheralServices(displayName.value)
        container.bluetoothManager.startDiscovery(batteryMode.value, continuous = continuous)
        _statusBanner.value = "BLE Scan & GATT-Server gestartet (${batteryMode.value.labelDe})"
    }

    fun stopScan() {
        container.bluetoothManager.stopDiscovery()
    }

    fun connectPeer(peerNodeId: String, address: String? = null) {
        _selectedPeerId.value = peerNodeId
        container.bluetoothManager.connectToPeer(peerNodeId, address)
    }

    fun upgradeToRfcomm(peerNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.bluetoothManager.upgradeToRfcomm(peerNodeId)
            _statusBanner.value = res.fold(
                onSuccess = { "RFCOMM bidirektional & AES-GCM verifiziert für $peerNodeId" },
                onFailure = { it.message ?: "RFCOMM-Aufbau fehlgeschlagen" },
            )
        }
    }

    fun startRfcommServer() {
        container.bluetoothManager.startPeripheralServices(displayName.value)
    }

    fun runRfcommBidirectionalTest(peerNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = container.bluetoothManager.getSession(peerNodeId)
            val boundAddr = container.bluetoothManager.rfcommTransport.getBoundAddress(peerNodeId)
                ?: session?.effectiveRfcommAddress()
            val res = if (boundAddr != null && container.bluetoothManager.rfcommTransport.isSocketConnected(boundAddr, peerNodeId)) {
                container.bluetoothManager.rfcommTransport.runBidirectionalTestOnOpenSocket(
                    address = boundAddr,
                    peerNodeId = peerNodeId,
                    cipher = session?.cipherSession,
                ).map { }
            } else {
                container.bluetoothManager.upgradeToRfcomm(peerNodeId)
            }
            _statusBanner.value = res.fold(
                onSuccess = { "RFCOMM bidirektionaler Test für $peerNodeId bestanden" },
                onFailure = { it.message ?: "RFCOMM-Test fehlgeschlagen" },
            )
        }
    }

    fun disconnectPeer(peerNodeId: String) {
        container.bluetoothManager.disconnectPeer(peerNodeId)
        _statusBanner.value = "Verbindung zu $peerNodeId getrennt"
    }

    private fun createAndRegisterQrPayload(name: String): PeerManager.PairingQrPayload {
        val btAddr = container.bluetoothManager.getValidLocalBluetoothAddress()
        val payload = PeerManager.buildLocalQrPayload(
            encryptionManager = container.encryptionManager,
            displayName = name,
            btAddress = btAddr,
        )
        container.bluetoothManager.registerIssuedPairingToken(payload.pairingToken, payload.expiresAtMs)
        return payload
    }

    fun renewLocalQrPayload() {
        _currentQrPayload.value = createAndRegisterQrPayload(displayName.value)
        _statusBanner.value = "Neuer kryptografisch signierter QR-Code & Einmal-Token erzeugt."
    }

    fun buildLocalQrJson(): String =
        PeerManager.encodeQrPayload(_currentQrPayload.value)

    fun importQrPayload(rawQr: String) {
        viewModelScope.launch {
            val parsed = PeerManager.parseQrPayload(
                raw = rawQr,
                isTokenConsumed = { token -> container.bluetoothManager.isTokenConsumed(token) },
            )
            parsed.fold(
                onSuccess = { payload ->
                    _selectedPeerId.value = payload.nodeId
                    container.bluetoothManager.startTwelveStepQrPairing(payload) { verifiedPayload ->
                        container.saveVerifiedContact(
                            nodeId = verifiedPayload.nodeId,
                            displayName = verifiedPayload.name,
                            publicKeyB64 = verifiedPayload.pubB64,
                            cl2Code = verifiedPayload.cl2Code,
                        )
                    }
                },
                onFailure = { err ->
                    _statusBanner.value = "QR-Pairing abgelehnt: ${err.message}"
                },
            )
        }
    }

    fun confirmInitiatorQrPairing(accepted: Boolean) {
        container.bluetoothManager.confirmLocalInitiatorPairing(accepted)
    }

    fun respondToIncomingPairing(accepted: Boolean) {
        val prompt = incomingPairingPrompt.value
        container.bluetoothManager.respondToIncomingPairing(accepted) {
            if (prompt != null) {
                viewModelScope.launch {
                    val session = container.bluetoothManager.getSession(prompt.peerNodeId)
                    val pubB64 = session?.peerIdentityPublicKeyB64
                        ?: db.contactDao().getById(prompt.peerNodeId)?.publicKeyB64
                        ?: ""
                    db.contactDao().upsert(
                        ContactEntity(
                            nodeId = prompt.peerNodeId,
                            displayName = prompt.peerName,
                            publicKeyB64 = pubB64,
                            cl2Code = prompt.cl2Code,
                            verified = true,
                            blocked = false,
                            lastSeenAt = System.currentTimeMillis(),
                        )
                    )
                    _statusBanner.value = "Verbindung mit ${prompt.peerName} bestätigt und Token verbraucht."
                }
            }
        }
    }

    fun dismissQrPairingProgress() {
        container.bluetoothManager.dismissQrPairingProgress()
    }

    fun attemptAutoReconnect(peerNodeId: String) {
        container.bluetoothManager.attemptAutoReconnect(peerNodeId)
        _statusBanner.value = "Wiederverbindung zu $peerNodeId gestartet…"
    }

    fun addManualContactWithCl2(
        nodeId: String,
        name: String,
        cl2Code: String,
        discoveredPeer: DiscoveredBluetoothPeer? = null,
    ) {
        viewModelScope.launch {
            val cleanId = nodeId.trim().uppercase()
            if (!cleanId.startsWith("CL-")) {
                _statusBanner.value = "Ungültige Node-ID (muss mit CL- beginnen)"
                return@launch
            }
            if (!PeerManager.isValidCl2Code(cl2Code.trim())) {
                _statusBanner.value = "CL2-Code muss genau 6 Ziffern besitzen"
                return@launch
            }
            val session = container.bluetoothManager.getSession(cleanId)
            db.contactDao().upsert(
                ContactEntity(
                    nodeId = cleanId,
                    displayName = name.trim().ifEmpty { cleanId },
                    publicKeyB64 = "",
                    cl2Code = cl2Code.trim(),
                    verified = true,
                    blocked = false,
                    lastSeenAt = System.currentTimeMillis(),
                )
            )
            discoveredPeer?.let {
                container.bluetoothManager.getOrCreateSession(cleanId, it.address, it.name)
            }
            if (session != null && session.deviceAddress != null) {
                connectPeer(cleanId, session.deviceAddress)
            }
            _statusBanner.value = "Kontakt $cleanId mit CL2-Code gespeichert."
        }
    }

    fun toggleBlockContact(contact: ContactEntity) {
        viewModelScope.launch {
            val nextBlocked = !contact.blocked
            db.contactDao().setBlocked(contact.nodeId, nextBlocked)
            if (nextBlocked) {
                container.locationSharingManager.revokeSharingForContact(contact.nodeId)
                container.bluetoothManager.disconnectPeer(contact.nodeId)
            }
        }
    }

    fun toggleFavoriteContact(contact: ContactEntity) {
        viewModelScope.launch {
            db.contactDao().setFavorite(contact.nodeId, !contact.favorite)
        }
    }

    fun deleteContact(nodeId: String) {
        viewModelScope.launch {
            container.locationSharingManager.revokeSharingForContact(nodeId)
            container.bluetoothManager.disconnectPeer(nodeId)
            db.contactDao().deleteById(nodeId)
            db.messageDao().deleteForPeer(nodeId)
        }
    }

    fun sendTextMessage(
        peerNodeId: String,
        text: String,
        priority: String = "NORMAL",
        groupId: String? = null,
        messageType: String = "TEXT",
        attachmentPath: String? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachmentPath == null) return

        viewModelScope.launch(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = MessageEntity(
                messageId = msgId,
                peerNodeId = peerNodeId,
                groupId = groupId,
                senderNodeId = localNodeId,
                content = container.localStorageCipher.encryptString(trimmed),
                messageType = messageType,
                attachmentPath = attachmentPath,
                priority = priority,
                status = "QUEUED",
                timestamp = now,
            )
            db.messageDao().upsert(entity)

            val env = CyberLinkProtocol.ProtocolEnvelope.ChatMessage(
                messageId = msgId,
                senderNodeId = localNodeId,
                recipientNodeId = peerNodeId,
                groupId = groupId,
                text = trimmed,
                messageType = messageType,
                priority = priority,
                timestampMs = now,
            )
            val sendRes = container.bluetoothManager.sendEnvelope(peerNodeId, env)
            if (sendRes.isSuccess) {
                db.messageDao().updateStatus(msgId, "SENT")
            } else {
                _statusBanner.value = "Nachricht in Offline-Warteschlange: ${sendRes.exceptionOrNull()?.message}"
            }
        }
    }

    fun sendMediaFile(
        peerNodeId: String,
        uri: Uri,
        isImage: Boolean,
        priority: String = "NORMAL",
        groupId: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileTransferManager.sendUriToPeer(
                peerNodeId = peerNodeId,
                uri = uri,
                isImage = isImage,
                priority = priority,
                groupId = groupId,
            )
            res.onFailure {
                _statusBanner.value = "Übertragung fehlgeschlagen: ${it.message}"
            }
        }
    }

    fun markChatMessagesRead(peerNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val unread = db.messageDao().getUnreadIncomingForPeer(peerNodeId, localNodeId)
            if (unread.isEmpty()) return@launch
            val ids = unread.map { it.messageId }
            ids.forEach { db.messageDao().updateStatus(it, "READ") }
            container.bluetoothManager.sendEnvelope(
                peerNodeId,
                CyberLinkProtocol.ProtocolEnvelope.MessageReadAck(
                    messageIds = ids,
                    senderNodeId = localNodeId,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun flushQueuedMessages(peerNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.flushQueuedForPeer(peerNodeId)
        }
    }

    // Audio & Video Calls
    fun startAudioCall(peerNodeId: String, hasMicPermission: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.audioCallManager.startOutgoingCall(peerNodeId, hasMicPermission)
            res.onFailure { _statusBanner.value = it.message }
        }
    }

    fun acceptAudioCall(hasMicPermission: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.audioCallManager.acceptIncomingCall(hasMicPermission)
        }
    }

    fun hangupAudioCall() {
        viewModelScope.launch(Dispatchers.IO) {
            container.audioCallManager.rejectOrHangup()
        }
    }

    fun startVideoCall(peerNodeId: String, hasCameraPermission: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.videoCallManager.startVideoCall(peerNodeId, hasCameraPermission)
            res.onFailure { _statusBanner.value = it.message }
        }
    }

    fun acceptVideoCall(hasCameraPermission: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.videoCallManager.acceptIncomingVideoCall(hasCameraPermission)
            res.onFailure { _statusBanner.value = it.message }
        }
    }

    fun endVideoCall() {
        viewModelScope.launch(Dispatchers.IO) {
            container.videoCallManager.endCall()
        }
    }

    // Battery & Mesh settings
    fun setBatteryMode(mode: BatteryMode) {
        viewModelScope.launch {
            container.settingsStore.setBatteryMode(mode)
        }
    }

    fun setMeshRelayOptIn(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setMeshRelayOptIn(enabled)
        }
    }

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            container.settingsStore.setDisplayName(name)
        }
    }

    // CYBER MAP V1.0 actions
    fun startGps(hasLocationPermission: Boolean) {
        container.offlineGpsEngine.startUpdates(hasLocationPermission, batteryMode.value)
    }

    fun refreshGps(hasLocationPermission: Boolean) {
        container.offlineGpsEngine.requestSingleRefresh(hasLocationPermission)
    }

    fun importMapUri(
        uri: Uri,
        fileNameHint: String?,
        regionName: String,
        version: String,
        expectedSha256: String?,
        isWorldOverview: Boolean,
    ) {
        viewModelScope.launch {
            val res = container.mapStorageManager.importFromUri(
                uri = uri,
                fileNameHint = fileNameHint,
                regionName = regionName,
                version = version,
                expectedSha256 = expectedSha256,
                isWorldOverview = isWorldOverview,
            )
            _statusBanner.value = res.fold(
                onSuccess = { "Karte '${it.regionName}' (SHA-256 geprüft) atomar installiert." },
                onFailure = { "Kartenimport abgelehnt: ${it.message}" },
            )
        }
    }

    fun deleteMapRegion(regionId: String) {
        viewModelScope.launch {
            container.mapStorageManager.deleteRegion(regionId)
            _statusBanner.value = "Region $regionId entfernt"
        }
    }

    fun offerMapToPeer(peerNodeId: String, region: MapRegionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.mapUpdateEngine.offerRegionToPeer(peerNodeId, region)
            res.onFailure { _statusBanner.value = "Kartenangebot fehlgeschlagen: ${it.message}" }
            res.onSuccess { _statusBanner.value = "Kartenpaket '${region.regionName}' an $peerNodeId angeboten." }
        }
    }

    fun acceptMapOffer(packageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.mapUpdateEngine.acceptIncomingOffer(packageId)
            res.onFailure { _statusBanner.value = it.message }
        }
    }

    fun toggleLocationSharingForContact(contactNodeId: String, enable: Boolean, startBackgroundService: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (enable) {
                val res = container.locationSharingManager.enableSharingForContact(contactNodeId)
                res.onSuccess {
                    if (startBackgroundService) {
                        LocationSharingService.start(ctx)
                    }
                    _statusBanner.value = "Standortfreigabe für $contactNodeId aktiviert"
                }.onFailure {
                    _statusBanner.value = it.message
                }
            } else {
                container.locationSharingManager.revokeSharingForContact(contactNodeId)
                val remaining = db.locationShareDao().getActiveSharesOnce()
                if (remaining.isEmpty()) {
                    LocationSharingService.stop(ctx)
                    container.locationSharingManager.setBackgroundState(LocationSharingManager.BackgroundSharingState.INACTIVE)
                }
                _statusBanner.value = "Standortfreigabe für $contactNodeId widerrufen"
            }
        }
    }

    fun triggerEmergencyLocationKillSwitch() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            LocationSharingService.stop(ctx)
            container.locationSharingManager.triggerEmergencyStop()
            _statusBanner.value = "NOT-AUS: Alle Standortfreigaben sofort widerrufen"
        }
    }

    fun clearAllContactLocations() {
        viewModelScope.launch {
            db.contactLocationDao().deleteAll()
            _statusBanner.value = "Empfangene Kontaktstandorte gelöscht"
        }
    }

    fun addWaypoint(title: String, category: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            db.mapWaypointDao().upsert(
                MapWaypointEntity(
                    waypointId = UUID.randomUUID().toString(),
                    latitude = lat,
                    longitude = lon,
                    title = title.trim().ifEmpty { "Wegpunkt" },
                    category = category,
                )
            )
            _statusBanner.value = "Wegpunkt '$title' gespeichert"
        }
    }

    fun searchOffline(query: String) {
        viewModelScope.launch {
            val gps = gpsSnapshot.value
            val res = container.offlineSearchEngine.search(
                query = query,
                centerLat = gps.latitude ?: 52.5200,
                centerLon = gps.longitude ?: 13.4050,
            )
            _searchResult.value = res
        }
    }

    fun calculateOfflineRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        options: OfflineRoutingEngine.RouteOptions,
    ) {
        viewModelScope.launch {
            val res = container.offlineRoutingEngine.calculateRoute(
                startLat = startLat,
                startLon = startLon,
                endLat = endLat,
                endLon = endLon,
                options = options,
            )
            _routingResult.value = res
            if (res is OfflineRoutingEngine.RoutingResult.Success) {
                db.mapRouteDao().upsert(
                    MapRouteEntity(
                        routeId = UUID.randomUUID().toString(),
                        startPoint = "$startLat,$startLon",
                        destination = "$endLat,$endLon",
                        profile = options.profile.name,
                        distanceMeters = res.route.distanceMeters,
                        durationSeconds = res.route.durationSeconds,
                        calculatedAt = System.currentTimeMillis(),
                        routingDataVersion = res.route.routingDataVersion,
                        polylineJson = res.route.polylineJson,
                    )
                )
            }
        }
    }

    fun exportOfflineDiagnosticsAndData() {
        viewModelScope.launch {
            val outFile = withContext(Dispatchers.IO) {
                val exportDir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
                val file = File(exportDir, "cyber_link_export_${System.currentTimeMillis()}.txt")
                val sb = StringBuilder()
                sb.appendLine("CYBER LINK V5.0 / CYBER MAP V1.0 LOKALER EXPORT")
                sb.appendLine("Node-ID: $localNodeId")
                sb.appendLine("Kontakte: ${contacts.value.size}")
                sb.appendLine("Kartenregionen: ${mapRegions.value.size}")
                sb.appendLine("Standortfreigaben: ${locationShares.value.size}")
                sb.appendLine("--- GATT DIAGNOSTICS ---")
                sessions.value.values.forEach { s ->
                    sb.appendLine("Peer=${s.peerNodeId} State=${s.transportState.labelDe} Transport=${s.activeTransport.labelDe} MTU=${s.negotiatedMtu ?: "N/A"}")
                    s.logs.forEach { sb.appendLine(it.formatLine()) }
                }
                file.writeText(sb.toString())
                file
            }
            _statusBanner.value = "Lokaler Export gespeichert: ${outFile.name}"
        }
    }

    fun wipeAllLocalData() {
        viewModelScope.launch {
            LocationSharingService.stop(getApplication())
            container.wipeAllLocalData()
            _statusBanner.value = "Alle lokalen Daten und Karten sicher gelöscht"
        }
    }

    fun getAllGattLogs(): List<GattLogEntry> =
        sessions.value.values.flatMap { it.logs }.sortedByDescending { it.timestampMs }
}
