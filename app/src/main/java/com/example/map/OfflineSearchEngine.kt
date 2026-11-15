package com.example.map

import com.example.data.db.MapRegionDao
import com.example.data.db.MapWaypointDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory

/**
 * Local Offline Search Engine.
 * Searches installed Mapsforge .poi SQLite databases and saved local MapWaypoints.
 * Never fakes search results; reports NOT_INSTALLED if no local map/POI data exists.
 */
class OfflineSearchEngine(
    private val storageManager: MapStorageManager,
    private val regionDao: MapRegionDao,
    private val waypointDao: MapWaypointDao,
) {
    data class SearchHit(
        val id: String,
        val title: String,
        val subtitle: String,
        val category: String,
        val latitude: Double,
        val longitude: Double,
    )

    sealed class SearchResult {
        data class Success(val hits: List<SearchHit>) : SearchResult()
        data class NotInstalled(val reason: String) : SearchResult()
    }

    suspend fun search(
        query: String,
        centerLat: Double = 52.5200,
        centerLon: Double = 13.4050,
    ): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val installedRegions = regionDao.getInstalledOnce()
        val poiFiles = storageManager.poiDir.listFiles { f -> f.extension.equals("poi", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        val waypoints = waypointDao.getAllOnce()

        if (installedRegions.isEmpty() && poiFiles.isEmpty() && waypoints.isEmpty()) {
            return@withContext SearchResult.NotInstalled("NICHT INSTALLIERT: Keine lokalen Kartendaten oder POI-Datenbanken vorhanden.")
        }

        val hits = mutableListOf<SearchHit>()

        // 1. Search local saved waypoints
        waypoints.filter {
            trimmed.isEmpty() ||
                it.title.contains(trimmed, ignoreCase = true) ||
                it.category.contains(trimmed, ignoreCase = true)
        }.forEach { wp ->
            hits.add(
                SearchHit(
                    id = wp.waypointId,
                    title = wp.title,
                    subtitle = "Gespeicherter Wegpunkt (${wp.category})",
                    category = wp.category,
                    latitude = wp.latitude,
                    longitude = wp.longitude,
                )
            )
        }

        // 2. Search installed Mapsforge .poi SQLite databases
        if (trimmed.isNotEmpty()) {
            for (poiFile in poiFiles) {
                runCatching {
                    val manager = AndroidPoiPersistenceManagerFactory.getPoiPersistenceManager(poiFile.absolutePath)
                    try {
                        val found = manager.findNearPosition(
                            LatLong(centerLat, centerLon),
                            50_000,
                            null,
                            null,
                            null,
                            100,
                            false,
                        )
                        found?.filter { poi ->
                            poi.name?.contains(trimmed, ignoreCase = true) == true ||
                                poi.category?.title?.contains(trimmed, ignoreCase = true) == true
                        }?.take(25)?.forEach { poi ->
                            hits.add(
                                SearchHit(
                                    id = "poi_${poi.id}",
                                    title = poi.name ?: "POI",
                                    subtitle = poi.category?.title ?: "OSM POI (${poiFile.name})",
                                    category = poi.category?.title ?: "POI",
                                    latitude = poi.latitude,
                                    longitude = poi.longitude,
                                )
                            )
                        }
                    } finally {
                        manager.close()
                    }
                }
            }
        }

        if (poiFiles.isEmpty() && hits.isEmpty()) {
            return@withContext SearchResult.NotInstalled(
                "NICHT INSTALLIERT: Regionale POI-Datenbank (.poi) für '${trimmed.ifEmpty { "Suche" }}' fehlt."
            )
        }

        SearchResult.Success(hits)
    }
}
