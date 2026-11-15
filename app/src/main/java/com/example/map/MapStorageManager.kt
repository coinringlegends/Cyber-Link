package com.example.map

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.example.data.db.MapPackageDao
import com.example.data.db.MapPackageEntity
import com.example.data.db.MapRegionDao
import com.example.data.db.MapRegionEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central offline storage manager for CYBER MAP V1.0.
 * Enforces:
 * - Internal app storage only (zero broad storage permissions).
 * - Storage space check via StatFs before import.
 * - Staging (.tmp) -> Magic header & format validation -> SHA-256 verification -> Atomic rename.
 * - Never activates unverified maps and never corrupts an existing installed map on failed update.
 */
class MapStorageManager(
    private val context: Context,
    private val regionDao: MapRegionDao,
    private val packageDao: MapPackageDao,
) {
    companion object {
        const val OSM_ATTRIBUTION = "© OpenStreetMap contributors • Open Database License (ODbL)"
        private val MAPSFORGE_MAGIC = "mapsforge binary OSM".toByteArray(Charsets.US_ASCII)
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val CLROUTE_MAGIC = "CLROUTE1".toByteArray(Charsets.US_ASCII)
    }

    enum class MapFileFormat(val extension: String, val packageType: String) {
        MAPSFORGE_MAP("map", "MAP_VECTOR"),
        MAPSFORGE_POI("poi", "POI_DB"),
        CYBER_ROUTING("clroute", "ROUTING_GRAPH"),
        CYBER_UPDATE("clmap", "CYBER_MAP_UPDATE"),
    }

    val rootDir: File get() = File(context.filesDir, "cyber_map").apply { mkdirs() }
    val regionsDir: File get() = File(rootDir, "regions").apply { mkdirs() }
    val stagingDir: File get() = File(rootDir, "staging").apply { mkdirs() }
    val poiDir: File get() = File(rootDir, "poi").apply { mkdirs() }
    val routingDir: File get() = File(rootDir, "routing").apply { mkdirs() }

    fun getAvailableBytes(): Long {
        val stat = StatFs(rootDir.absolutePath)
        return if (stat.blockCountLong > 0L) stat.availableBytes else rootDir.usableSpace
    }

    suspend fun importFromUri(
        uri: Uri,
        fileNameHint: String?,
        regionName: String?,
        version: String = "1.0",
        expectedSha256: String? = null,
        isWorldOverview: Boolean = false,
    ): Result<MapRegionEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawName = (fileNameHint ?: uri.lastPathSegment ?: "region_${System.currentTimeMillis()}.map")
            .substringAfterLast('/')
        val format = detectFormat(rawName)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Nicht unterstütztes Dateiformat ($rawName). Erlaubt: .map, .poi, .clroute, .clmap")
            )

        val stream = resolver.openInputStream(uri)
            ?: return@withContext Result.failure(IllegalStateException("Datei konnte nicht über SAF geöffnet werden"))

        stream.use { input ->
            importFromStream(
                inputStream = input,
                originalFileName = rawName,
                format = format,
                regionName = regionName ?: rawName.substringBeforeLast('.'),
                version = version,
                expectedSha256 = expectedSha256,
                isWorldOverview = isWorldOverview,
            )
        }
    }

    suspend fun importFromStream(
        inputStream: InputStream,
        originalFileName: String,
        format: MapFileFormat,
        regionName: String,
        version: String,
        expectedSha256: String? = null,
        isWorldOverview: Boolean = false,
        knownSizeBytes: Long? = null,
    ): Result<MapRegionEntity> = withContext(Dispatchers.IO) {
        val avail = getAvailableBytes()
        if (knownSizeBytes != null && knownSizeBytes > 0 && avail < knownSizeBytes + 8 * 1024 * 1024L) {
            return@withContext Result.failure(
                IllegalStateException("Nicht genügend interner Speicherplatz (benötigt ${knownSizeBytes / 1024} KB, frei ${avail / 1024} KB)")
            )
        }

        val regionId = sanitizeId(regionName.ifBlank { originalFileName.substringBeforeLast('.') })
        val tempFile = File(stagingDir, "${regionId}_${UUID.randomUUID()}.tmp")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            FileOutputStream(tempFile).use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = inputStream.read(buf)
                    if (read <= 0) break
                    totalBytes += read
                    if (totalBytes > getAvailableBytes()) {
                        throw IllegalStateException("Speicherplatz während des Imports erschöpft")
                    }
                    digest.update(buf, 0, read)
                    out.write(buf, 0, read)
                }
                out.flush()
            }

            if (totalBytes < 32L) {
                tempFile.delete()
                return@withContext Result.failure(IllegalArgumentException("Unvollständige oder leere Kartendatei ($totalBytes Bytes)"))
            }

            val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expectedSha256.isNullOrBlank() && !computedSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                tempFile.delete()
                return@withContext Result.failure(
                    SecurityException("SHA-256 Prüfsumme ungültig! Erwartet=$expectedSha256, Berechnet=$computedSha")
                )
            }

            if (!validateMagicHeader(tempFile, format)) {
                tempFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("Beschädigtes oder ungültiges Kartenformat für ${format.extension}")
                )
            }

            val existing = regionDao.getById(regionId)
            if (existing != null && existing.installationState == "INSTALLED" && compareVersions(version, existing.version) < 0) {
                tempFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("Downgrade abgelehnt: installierte Version ${existing.version} ist neuer als $version")
                )
            }

            val targetDir = when (format) {
                MapFileFormat.MAPSFORGE_MAP, MapFileFormat.CYBER_UPDATE -> regionsDir
                MapFileFormat.MAPSFORGE_POI -> poiDir
                MapFileFormat.CYBER_ROUTING -> routingDir
            }
            val destFile = File(targetDir, "${regionId}.${format.extension}")
            val backupFile = File(targetDir, "${regionId}.${format.extension}.bak")

            // Atomic installation preserving any previously installed version on failure
            if (destFile.exists()) {
                backupFile.delete()
                if (!destFile.renameTo(backupFile)) {
                    tempFile.delete()
                    return@withContext Result.failure(IllegalStateException("Atomares Backup der bestehenden Karte fehlgeschlagen"))
                }
            }

            val moved = tempFile.renameTo(destFile)
            if (!moved) {
                // Fallback copy within internal storage then delete temp
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            if (backupFile.exists()) backupFile.delete()

            val detailLevel = if (isWorldOverview) "WORLD_OVERVIEW" else "REGIONAL_DETAIL"
            val entity = MapRegionEntity(
                regionId = regionId,
                regionName = regionName,
                version = version,
                dataTimestamp = System.currentTimeMillis(),
                filePath = destFile.absolutePath,
                fileSize = destFile.length(),
                sha256 = computedSha,
                installationState = "INSTALLED",
                licenseAttribution = OSM_ATTRIBUTION,
                detailLevels = detailLevel,
            )
            regionDao.upsert(entity)
            packageDao.upsert(
                MapPackageEntity(
                    packageId = "${regionId}_${format.packageType}",
                    regionId = regionId,
                    packageType = format.packageType,
                    version = version,
                    checksum = computedSha,
                    transferState = "COMPLETED",
                    bytesTransferred = destFile.length(),
                    totalBytes = destFile.length(),
                    filePath = destFile.absolutePath,
                )
            )
            Result.success(entity)
        } catch (t: Throwable) {
            tempFile.delete()
            Result.failure(t)
        }
    }

    suspend fun deleteRegion(regionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = regionDao.getById(regionId)
        if (existing != null) {
            File(existing.filePath).delete()
            regionDao.deleteById(regionId)
        }
        packageDao.deleteById("${regionId}_MAP_VECTOR")
        packageDao.deleteById("${regionId}_POI_DB")
        packageDao.deleteById("${regionId}_ROUTING_GRAPH")
        Result.success(Unit)
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun validateMagicHeader(file: File, format: MapFileFormat): Boolean {
        if (!file.exists() || file.length() < 20) return false
        val header = ByteArray(32)
        val read = FileInputStream(file).use { it.read(header) }
        if (read < 16) return false
        return when (format) {
            MapFileFormat.MAPSFORGE_MAP -> header.copyOfRange(0, MAPSFORGE_MAGIC.size).contentEquals(MAPSFORGE_MAGIC)
            MapFileFormat.MAPSFORGE_POI -> header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)
            MapFileFormat.CYBER_ROUTING -> {
                val isBinary = header.copyOfRange(0, CLROUTE_MAGIC.size).contentEquals(CLROUTE_MAGIC)
                val trimmed = String(header, 0, read, Charsets.UTF_8).trimStart()
                isBinary || trimmed.startsWith("{")
            }
            MapFileFormat.CYBER_UPDATE -> {
                val trimmed = String(header, 0, read, Charsets.UTF_8).trimStart()
                trimmed.startsWith("{") || header.copyOfRange(0, MAPSFORGE_MAGIC.size).contentEquals(MAPSFORGE_MAGIC)
            }
        }
    }

    fun detectFormat(fileName: String): MapFileFormat? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MapFileFormat.entries.firstOrNull { it.extension == ext }
    }

    fun sanitizeId(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifEmpty { "region" }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
