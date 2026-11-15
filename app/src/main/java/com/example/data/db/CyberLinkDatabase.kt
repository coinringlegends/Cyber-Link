package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ContactEntity::class,
        MessageEntity::class,
        MapRegionEntity::class,
        MapPackageEntity::class,
        LocationShareEntity::class,
        ContactLocationEntity::class,
        MapWaypointEntity::class,
        MapRouteEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CyberLinkDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun mapRegionDao(): MapRegionDao
    abstract fun mapPackageDao(): MapPackageDao
    abstract fun locationShareDao(): LocationShareDao
    abstract fun contactLocationDao(): ContactLocationDao
    abstract fun mapWaypointDao(): MapWaypointDao
    abstract fun mapRouteDao(): MapRouteDao

    companion object {
        @Volatile
        private var INSTANCE: CyberLinkDatabase? = null

        fun getInstance(context: Context): CyberLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CyberLinkDatabase::class.java,
                    "cyber_link_v5.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
