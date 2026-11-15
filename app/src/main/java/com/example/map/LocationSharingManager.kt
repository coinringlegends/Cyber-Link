package com.example.map

import com.example.battery.BatteryMode
import com.example.bluetooth.BluetoothManager
import com.example.data.SettingsStore
import com.example.data.db.ContactDao
import com.example.data.db.ContactLocationDao
import com.example.data.db.ContactLocationEntity
import com.example.data.db.LocationShareDao
import com.example.data.db.LocationShareEntity
import com.example.protocol.CyberLinkProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages live location sharing exclusively with verified, non-blocked CYBER LINK contacts.
 * Rules enforced:
 * - Default OFF.
 * - Explicit per-contact consent persisted in Room LocationShareEntity.
 * - Instant revocation & Global Kill Switch (Not-Aus).
 * - Replay protection via monotonic sequenceNonce + timestamp freshness validation.
 * - Never transmitted through MeshRouter.
 */
class LocationSharingManager(
    private val contactDao: ContactDao,
    private val locationShareDao: LocationShareDao,
    private val contactLocationDao: ContactLocationDao,
    private val settingsStore: SettingsStore,
    private val bluetoothManager: BluetoothManager,
) {
    companion object {
        const val STALE_AGE_MS = 10 * 60 * 1000L // 10 minutes
        const val MAX_FUTURE_SKEW_MS = 120 * 1000L
    }

    enum class BackgroundSharingState(val labelDe: String) {
        INACTIVE("AUS"),
        ACTIVE("AKTIV"),
        PAUSED_BY_SYSTEM("PAUSIERT"),
        OFFLINE("OFFLINE"),
    }

    private val _backgroundState = MutableStateFlow(BackgroundSharingState.INACTIVE)
    val backgroundState: StateFlow<BackgroundSharingState> = _backgroundState.asStateFlow()

    private var outboundSequence = System.currentTimeMillis()
    private val lastSentAtByContact = ConcurrentHashMap<String, Pair<Long, Pair<Double, Double>>>()
    private val highestSeqReceived = ConcurrentHashMap<String, Long>()

    fun setBackgroundState(state: BackgroundSharingState) {
        _backgroundState.value = state
    }

    suspend fun enableSharingForContact(contactNodeId: String): Result<LocationShareEntity> {
        val contact = contactDao.getById(contactNodeId)
            ?: return Result.failure(IllegalStateException("Unbekannter Kontakt: $contactNodeId"))
        if (!contact.verified || contact.blocked) {
            return Result.failure(SecurityException("Standortfreigabe nur für bestätigte, nicht blockierte Kontakte erlaubt"))
        }
        settingsStore.setGlobalLocationKillSwitch(false)
        val now = System.currentTimeMillis()
        val entity = LocationShareEntity(
            contactNodeId = contactNodeId,
            enabled = true,
            startedAt = now,
            lastUpdate = now,
            revokedAt = null,
        )
        locationShareDao.upsert(entity)
        return Result.success(entity)
    }

    suspend fun revokeSharingForContact(contactNodeId: String) {
        val now = System.currentTimeMillis()
        locationShareDao.revoke(contactNodeId, now)
        lastSentAtByContact.remove(contactNodeId)
        bluetoothManager.sendEnvelope(
            peerNodeId = contactNodeId,
            envelope = CyberLinkProtocol.ProtocolEnvelope.LocationShareRevoke(
                contactNodeId = bluetoothManager.encryptionManager.nodeId,
                timestampMs = now,
            ),
        )
    }

    suspend fun triggerEmergencyStop() {
        val now = System.currentTimeMillis()
        settingsStore.setGlobalLocationKillSwitch(true)
        val active = locationShareDao.getActiveSharesOnce()
        locationShareDao.revokeAll(now)
        lastSentAtByContact.clear()
        _backgroundState.value = BackgroundSharingState.INACTIVE
        active.forEach { share ->
            bluetoothManager.sendEnvelope(
                peerNodeId = share.contactNodeId,
                envelope = CyberLinkProtocol.ProtocolEnvelope.LocationShareRevoke(
                    contactNodeId = bluetoothManager.encryptionManager.nodeId,
                    timestampMs = now,
                ),
            )
        }
    }

    suspend fun broadcastGpsFixIfAuthorized(
        gps: OfflineGpsEngine.GpsSnapshot,
        batteryMode: BatteryMode,
        globalKillSwitch: Boolean,
    ): Int {
        if (globalKillSwitch) return 0
        if (gps.status != OfflineGpsEngine.GpsSignalStatus.ACTIVE_FIX) return 0
        val lat = gps.latitude ?: return 0
        val lon = gps.longitude ?: return 0
        val acc = gps.accuracyMeters ?: return 0
        val ts = gps.timestampMs ?: System.currentTimeMillis()

        val activeShares = locationShareDao.getActiveSharesOnce()
        if (activeShares.isEmpty()) return 0

        var sentCount = 0
        for (share in activeShares) {
            val contact = contactDao.getById(share.contactNodeId) ?: continue
            if (!contact.verified || contact.blocked) continue

            val prev = lastSentAtByContact[share.contactNodeId]
            val shouldSend = if (prev == null) {
                true
            } else {
                val dt = System.currentTimeMillis() - prev.first
                val dist = ContactMapStatusResolver.distanceMeters(prev.second.first, prev.second.second, lat, lon)
                dt >= batteryMode.gpsMinTimeMs && dist >= batteryMode.gpsMinDistanceMeters ||
                    dt >= batteryMode.gpsMinTimeMs * 4
            }
            if (!shouldSend) continue

            val seq = ++outboundSequence
            val packet = CyberLinkProtocol.ProtocolEnvelope.LocationShareUpdate(
                contactNodeId = bluetoothManager.encryptionManager.nodeId,
                latitude = lat,
                longitude = lon,
                accuracyMeters = acc,
                altitudeMeters = gps.altitudeMeters,
                speedMps = gps.speedMps,
                bearingDeg = gps.bearingDeg,
                timestampMs = ts,
                sequenceNonce = seq,
            )
            val res = bluetoothManager.sendEnvelope(share.contactNodeId, packet, preferRfcomm = false)
            if (res.isSuccess) {
                lastSentAtByContact[share.contactNodeId] = System.currentTimeMillis() to (lat to lon)
                locationShareDao.upsert(share.copy(lastUpdate = System.currentTimeMillis()))
                sentCount++
            }
        }
        return sentCount
    }

    suspend fun handleIncomingLocationUpdate(
        fromPeerNodeId: String,
        update: CyberLinkProtocol.ProtocolEnvelope.LocationShareUpdate,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<ContactLocationEntity> {
        val contact = contactDao.getById(fromPeerNodeId)
            ?: return Result.failure(SecurityException("Standort von unbekanntem Peer $fromPeerNodeId verworfen"))
        if (!contact.verified || contact.blocked) {
            return Result.failure(SecurityException("Standort von unbestätigtem/blockiertem Kontakt $fromPeerNodeId verworfen"))
        }

        // Replay protection
        val prevSeq = highestSeqReceived[fromPeerNodeId] ?: 0L
        if (update.sequenceNonce <= prevSeq) {
            return Result.failure(SecurityException("Replay-Standortmeldung verworfen (seq=${update.sequenceNonce} <= $prevSeq)"))
        }
        if (update.timestampMs > nowMs + MAX_FUTURE_SKEW_MS) {
            return Result.failure(IllegalArgumentException("Standortzeitstempel liegt zu weit in der Zukunft"))
        }

        highestSeqReceived[fromPeerNodeId] = update.sequenceNonce
        val ageMs = nowMs - update.timestampMs
        val state = if (ageMs > STALE_AGE_MS) "STALE" else "LIVE"

        val entity = ContactLocationEntity(
            contactNodeId = fromPeerNodeId,
            latitude = update.latitude,
            longitude = update.longitude,
            accuracy = update.accuracyMeters,
            altitude = update.altitudeMeters,
            speed = update.speedMps,
            bearing = update.bearingDeg,
            timestamp = update.timestampMs,
            locationState = state,
        )
        contactLocationDao.upsert(entity)
        return Result.success(entity)
    }

    suspend fun handleIncomingRevoke(fromPeerNodeId: String) {
        contactLocationDao.deleteForContact(fromPeerNodeId)
    }
}
