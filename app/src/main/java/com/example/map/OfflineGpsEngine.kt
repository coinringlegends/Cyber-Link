package com.example.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.example.battery.BatteryMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Offline GNSS/GPS engine using Android LocationManager.GPS_PROVIDER exclusively.
 * Zero internet/network provider usage. Never fabricates coordinates.
 */
class OfflineGpsEngine(private val context: Context) {

    enum class GpsSignalStatus(val labelDe: String) {
        NO_SIGNAL("KEIN GPS-SIGNAL"),
        STALE_LAST_KNOWN("ALT (LETZTE BEKANNTE POSITION)"),
        ACTIVE_FIX("AKTIVER GPS-FIX"),
        PERMISSION_MISSING("STANDORTBERECHTIGUNG FEHLT"),
        GPS_DISABLED("GPS DEAKTIVIERT"),
    }

    data class GpsSnapshot(
        val status: GpsSignalStatus = GpsSignalStatus.NO_SIGNAL,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Float? = null,
        val altitudeMeters: Double? = null,
        val speedMps: Float? = null,
        val bearingDeg: Float? = null,
        val timestampMs: Long? = null,
        val isStale: Boolean = false,
    )

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _snapshot = MutableStateFlow(GpsSnapshot())
    val snapshot: StateFlow<GpsSnapshot> = _snapshot.asStateFlow()

    private var isListening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateFromLocation(location, isLastKnown = false)
        }

        @Deprecated("Deprecated in Android API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER && _snapshot.value.status == GpsSignalStatus.GPS_DISABLED) {
                _snapshot.value = _snapshot.value.copy(status = GpsSignalStatus.NO_SIGNAL)
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                _snapshot.value = _snapshot.value.copy(status = GpsSignalStatus.GPS_DISABLED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(hasLocationPermission: Boolean, batteryMode: BatteryMode) {
        if (!hasLocationPermission) {
            _snapshot.value = GpsSnapshot(status = GpsSignalStatus.PERMISSION_MISSING)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _snapshot.value = _snapshot.value.copy(status = GpsSignalStatus.GPS_DISABLED)
            return
        }

        // Load last known GPS fix strictly labeled as ALT / stale
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.let { last -> updateFromLocation(last, isLastKnown = true) }

        stopUpdates()
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                batteryMode.gpsMinTimeMs,
                batteryMode.gpsMinDistanceMeters,
                listener,
                Looper.getMainLooper(),
            )
            isListening = true
        }
    }

    @SuppressLint("MissingPermission")
    fun requestSingleRefresh(hasLocationPermission: Boolean) {
        if (!hasLocationPermission) {
            _snapshot.value = GpsSnapshot(status = GpsSignalStatus.PERMISSION_MISSING)
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _snapshot.value = _snapshot.value.copy(status = GpsSignalStatus.GPS_DISABLED)
            return
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor,
                ) { loc ->
                    if (loc != null) updateFromLocation(loc, isLastKnown = false)
                }
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            }
        }
    }

    fun updateFromLocation(location: Location, isLastKnown: Boolean) {
        val ageMs = System.currentTimeMillis() - location.time
        val stale = isLastKnown || ageMs > 120_000L
        _snapshot.value = GpsSnapshot(
            status = if (stale) GpsSignalStatus.STALE_LAST_KNOWN else GpsSignalStatus.ACTIVE_FIX,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            timestampMs = location.time,
            isStale = stale,
        )
    }

    fun stopUpdates() {
        if (isListening) {
            runCatching { locationManager.removeUpdates(listener) }
            isListening = false
        }
    }
}
