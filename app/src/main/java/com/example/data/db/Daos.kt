package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY favorite DESC, displayName ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getById(nodeId: String): ContactEntity?

    @Query("SELECT * FROM contacts")
    suspend fun getAllOnce(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE nodeId = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("UPDATE contacts SET blocked = :blocked WHERE nodeId = :nodeId")
    suspend fun setBlocked(nodeId: String, blocked: Boolean)

    @Query("UPDATE contacts SET favorite = :favorite WHERE nodeId = :nodeId")
    suspend fun setFavorite(nodeId: String, favorite: Boolean)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerNodeId = :peerNodeId AND groupId IS NULL ORDER BY timestamp ASC")
    fun observeDirectChat(peerNodeId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun observeGroupChat(groupId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' ORDER BY timestamp ASC")
    suspend fun getQueuedMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("SELECT * FROM messages WHERE peerNodeId = :peerNodeId AND senderNodeId != :localNodeId AND status != 'READ'")
    suspend fun getUnreadIncomingForPeer(peerNodeId: String, localNodeId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE peerNodeId = :peerNodeId")
    suspend fun deleteForPeer(peerNodeId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface MapRegionDao {
    @Query("SELECT * FROM map_regions ORDER BY detailLevels ASC, regionName ASC")
    fun observeAll(): Flow<List<MapRegionEntity>>

    @Query("SELECT * FROM map_regions WHERE installationState = 'INSTALLED'")
    suspend fun getInstalledOnce(): List<MapRegionEntity>

    @Query("SELECT * FROM map_regions WHERE regionId = :regionId LIMIT 1")
    suspend fun getById(regionId: String): MapRegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(region: MapRegionEntity)

    @Query("DELETE FROM map_regions WHERE regionId = :regionId")
    suspend fun deleteById(regionId: String)

    @Query("DELETE FROM map_regions")
    suspend fun deleteAll()
}

@Dao
interface MapPackageDao {
    @Query("SELECT * FROM map_packages ORDER BY packageId ASC")
    fun observeAll(): Flow<List<MapPackageEntity>>

    @Query("SELECT * FROM map_packages WHERE packageId = :packageId LIMIT 1")
    suspend fun getById(packageId: String): MapPackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pkg: MapPackageEntity)

    @Query("DELETE FROM map_packages WHERE packageId = :packageId")
    suspend fun deleteById(packageId: String)

    @Query("DELETE FROM map_packages")
    suspend fun deleteAll()
}

@Dao
interface LocationShareDao {
    @Query("SELECT * FROM location_shares ORDER BY contactNodeId ASC")
    fun observeAll(): Flow<List<LocationShareEntity>>

    @Query("SELECT * FROM location_shares WHERE enabled = 1 AND revokedAt IS NULL")
    suspend fun getActiveSharesOnce(): List<LocationShareEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(share: LocationShareEntity)

    @Query("UPDATE location_shares SET enabled = 0, revokedAt = :revokedAt WHERE contactNodeId = :contactNodeId")
    suspend fun revoke(contactNodeId: String, revokedAt: Long)

    @Query("UPDATE location_shares SET enabled = 0, revokedAt = :revokedAt WHERE enabled = 1")
    suspend fun revokeAll(revokedAt: Long)

    @Query("DELETE FROM location_shares")
    suspend fun deleteAll()
}

@Dao
interface ContactLocationDao {
    @Query("SELECT * FROM contact_locations ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ContactLocationEntity>>

    @Query("SELECT * FROM contact_locations WHERE contactNodeId = :contactNodeId LIMIT 1")
    suspend fun getByContact(contactNodeId: String): ContactLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(loc: ContactLocationEntity)

    @Query("DELETE FROM contact_locations WHERE contactNodeId = :contactNodeId")
    suspend fun deleteForContact(contactNodeId: String)

    @Query("DELETE FROM contact_locations")
    suspend fun deleteAll()
}

@Dao
interface MapWaypointDao {
    @Query("SELECT * FROM map_waypoints ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MapWaypointEntity>>

    @Query("SELECT * FROM map_waypoints")
    suspend fun getAllOnce(): List<MapWaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(waypoint: MapWaypointEntity)

    @Query("DELETE FROM map_waypoints WHERE waypointId = :waypointId")
    suspend fun deleteById(waypointId: String)

    @Query("DELETE FROM map_waypoints")
    suspend fun deleteAll()
}

@Dao
interface MapRouteDao {
    @Query("SELECT * FROM map_routes ORDER BY calculatedAt DESC")
    fun observeAll(): Flow<List<MapRouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: MapRouteEntity)

    @Query("DELETE FROM map_routes")
    suspend fun deleteAll()
}
