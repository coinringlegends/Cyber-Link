package com.example.map

import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.TransportState
import com.example.data.db.ContactEntity
import com.example.data.db.ContactLocationEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure deterministic resolver for CYBER FRIENDS map markers, colors, and GPS Haversine distances.
 * NEVER converts RSSI into an exact distance.
 */
object ContactMapStatusResolver {

    enum class FriendMapColor(val labelDe: String) {
        GREEN("GREEN — Authentifizierter Bluetooth-Datenkanal aktiv"),
        CYAN("CYAN — Kontakt per Bluetooth entdeckt, nicht verbunden"),
        YELLOW("YELLOW — Letzte Standortmeldung vorhanden, Verbindung nicht verfügbar"),
        GRAY("GRAY — Kein aktueller Standort verfügbar"),
        RED("RED — Verbindung oder Authentifizierung fehlgeschlagen"),
    }

    data class CyberFriendMarker(
        val contact: ContactEntity,
        val color: FriendMapColor,
        val transportState: TransportState,
        val activeTransport: ActiveTransport,
        val location: ContactLocationEntity?,
        val gpsDistanceMeters: Double?,
        val isLocationStale: Boolean,
        val chatAvailable: Boolean,
        val audioCallAvailable: Boolean,
        val videoCallAvailable: Boolean,
    )

    fun resolveColor(
        transportState: TransportState,
        activeTransport: ActiveTransport,
        location: ContactLocationEntity?,
    ): FriendMapColor {
        return when {
            transportState == TransportState.ERROR -> FriendMapColor.RED
            transportState == TransportState.CONNECTED && activeTransport != ActiveTransport.NONE -> FriendMapColor.GREEN
            transportState == TransportState.BLUETOOTH_FOUND ||
                transportState == TransportState.CONNECTING ||
                transportState == TransportState.SERVICE_DISCOVERY ||
                transportState == TransportState.AUTHENTICATING -> FriendMapColor.CYAN
            location != null -> FriendMapColor.YELLOW
            else -> FriendMapColor.GRAY
        }
    }

    fun buildMarker(
        contact: ContactEntity,
        transportState: TransportState,
        activeTransport: ActiveTransport,
        location: ContactLocationEntity?,
        ownGps: OfflineGpsEngine.GpsSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): CyberFriendMarker {
        val color = resolveColor(transportState, activeTransport, location)
        val distance = if (
            ownGps.latitude != null &&
            ownGps.longitude != null &&
            location != null
        ) {
            distanceMeters(ownGps.latitude, ownGps.longitude, location.latitude, location.longitude)
        } else {
            null
        }
        val stale = location != null && (
            location.locationState == "STALE" ||
                (nowMs - location.timestamp) > LocationSharingManager.STALE_AGE_MS
            )
        val connected = transportState == TransportState.CONNECTED && activeTransport != ActiveTransport.NONE
        val rfcomm = activeTransport == ActiveTransport.CLASSIC_RFCOMM || activeTransport == ActiveTransport.DUAL_BLE_RFCOMM

        return CyberFriendMarker(
            contact = contact,
            color = color,
            transportState = transportState,
            activeTransport = activeTransport,
            location = location,
            gpsDistanceMeters = distance,
            isLocationStale = stale,
            chatAvailable = connected,
            audioCallAvailable = connected && rfcomm,
            videoCallAvailable = connected && rfcomm,
        )
    }

    /**
     * Exact WGS-84 Haversine distance in meters between two valid GPS coordinates.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun formatDistance(meters: Double?): String {
        if (meters == null) return "—"
        return if (meters < 1000.0) "${meters.toInt()} m" else "%.2f km".format(meters / 1000.0)
    }
}
