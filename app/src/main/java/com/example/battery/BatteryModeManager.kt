package com.example.battery

enum class BatteryMode(
    val labelDe: String,
    val descriptionDe: String,
    val gpsMinTimeMs: Long,
    val gpsMinDistanceMeters: Float,
    val bleScanWindowMs: Long,
    val idleDisconnectMs: Long,
    val allowMeshRelay: Boolean,
    val allowAutoMapUpdates: Boolean,
) {
    ULTRA(
        labelDe = "ULTRA",
        descriptionDe = "Keine Hintergrund-Scans, stark reduzierte GPS-Updates, kein Mesh-Relay",
        gpsMinTimeMs = 60_000L,
        gpsMinDistanceMeters = 100f,
        bleScanWindowMs = 4_000L,
        idleDisconnectMs = 60_000L,
        allowMeshRelay = false,
        allowAutoMapUpdates = false,
    ),
    NORMAL(
        labelDe = "NORMAL",
        descriptionDe = "Adaptive GPS-Updates, kurze BLE-Scanfenster, Standort nur bei Änderung",
        gpsMinTimeMs = 15_000L,
        gpsMinDistanceMeters = 25f,
        bleScanWindowMs = 8_000L,
        idleDisconnectMs = 300_000L,
        allowMeshRelay = true,
        allowAutoMapUpdates = false,
    ),
    PERFORMANCE(
        labelDe = "PERFORMANCE",
        descriptionDe = "Häufige GPS-Updates, aktive Navigation, schnelle Nearby-Suche",
        gpsMinTimeMs = 2_000L,
        gpsMinDistanceMeters = 5f,
        bleScanWindowMs = 15_000L,
        idleDisconnectMs = 900_000L,
        allowMeshRelay = true,
        allowAutoMapUpdates = false,
    ),
}
