package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val publicKeyB64: String,
    val cl2Code: String,
    val verified: Boolean = true,
    val blocked: Boolean = false,
    val favorite: Boolean = false,
    val lastSeenAt: Long = 0L,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val peerNodeId: String,
    val groupId: String? = null,
    val senderNodeId: String,
    val content: String,
    val messageType: String = "TEXT", // TEXT, VOICE, IMAGE, FILE
    val attachmentPath: String? = null,
    val priority: String = "NORMAL",  // HIGH, NORMAL, LOW
    val status: String = "QUEUED",    // QUEUED, SENT, DELIVERED, FAILED
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "map_regions")
data class MapRegionEntity(
    @PrimaryKey val regionId: String,
    val regionName: String,
    val version: String,
    val dataTimestamp: Long,
    val filePath: String,
    val fileSize: Long,
    val sha256: String,
    val installationState: String, // NOT_INSTALLED, INSTALLING, INSTALLED, CORRUPT, OVERVIEW_ONLY
    val licenseAttribution: String,
    val detailLevels: String = "REGIONAL_DETAIL", // WORLD_OVERVIEW, REGIONAL_DETAIL
    val minLat: Double = -90.0,
    val maxLat: Double = 90.0,
    val minLon: Double = -180.0,
    val maxLon: Double = 180.0,
)

@Entity(tableName = "map_packages")
data class MapPackageEntity(
    @PrimaryKey val packageId: String,
    val regionId: String,
    val packageType: String, // MAP_VECTOR, POI_DB, ROUTING_GRAPH, CYBER_MAP_UPDATE
    val version: String,
    val checksum: String,
    val transferState: String, // IDLE, TRANSFERRING, PAUSED, VERIFYING, COMPLETED, FAILED
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String = "",
)

@Entity(tableName = "location_shares")
data class LocationShareEntity(
    @PrimaryKey val contactNodeId: String,
    val enabled: Boolean,
    val startedAt: Long,
    val lastUpdate: Long,
    val revokedAt: Long? = null,
)

@Entity(tableName = "contact_locations")
data class ContactLocationEntity(
    @PrimaryKey val contactNodeId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val timestamp: Long,
    val locationState: String, // LIVE, STALE, UNAVAILABLE
)

@Entity(tableName = "map_waypoints")
data class MapWaypointEntity(
    @PrimaryKey val waypointId: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "map_routes")
data class MapRouteEntity(
    @PrimaryKey val routeId: String,
    val startPoint: String,
    val destination: String,
    val profile: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val calculatedAt: Long,
    val routingDataVersion: String,
    val polylineJson: String,
)
