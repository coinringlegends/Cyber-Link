package com.example

import android.content.Context
import android.location.Location
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.battery.BatteryMode
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.TransportState
import com.example.crypto.EncryptionManager
import com.example.data.SettingsStore
import com.example.data.db.ContactEntity
import com.example.data.db.ContactLocationEntity
import com.example.data.db.CyberLinkDatabase
import com.example.map.BluetoothMapUpdateEngine
import com.example.map.ContactMapStatusResolver
import com.example.map.LocationSharingManager
import com.example.map.MapStorageManager
import com.example.map.OfflineGpsEngine
import com.example.map.OfflineRoutingEngine
import com.example.mesh.MeshRouter
import com.example.protocol.CyberLinkProtocol
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CyberMapRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: CyberLinkDatabase
    private lateinit var storage: MapStorageManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var btManager: BluetoothManager
    private lateinit var locationSharing: LocationSharingManager
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CyberLinkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = MapStorageManager(context, db.mapRegionDao(), db.mapPackageDao())
        storage.rootDir.deleteRecursively()
        storage.rootDir.mkdirs()
        settingsStore = SettingsStore(context)
        val enc = EncryptionManager()
        btManager = BluetoothManager(context, testScope, enc, lookupContactPublicKey = { null })
        locationSharing = LocationSharingManager(
            contactDao = db.contactDao(),
            locationShareDao = db.locationShareDao(),
            contactLocationDao = db.contactLocationDao(),
            settingsStore = settingsStore,
            bluetoothManager = btManager,
        )
    }

    @After
    fun teardown() {
        btManager.shutdown()
        db.close()
        storage.rootDir.deleteRecursively()
    }

    private fun validMapsforgeSampleBytes(extra: String = "v1"): ByteArray {
        val header = "mapsforge binary OSM".toByteArray(Charsets.US_ASCII)
        val padding = ("|CYBERMAP_TEST_PAYLOAD_$extra|" + "X".repeat(64)).toByteArray(Charsets.US_ASCII)
        return header + padding
    }

    @Test
    fun `1 and 3 import valid mapsforge map package with SHA-256 verification`() = runBlocking {
        val payload = validMapsforgeSampleBytes("berlin_v1")
        val sha = EncryptionManager.sha256Hex(payload)

        val res = storage.importFromStream(
            inputStream = ByteArrayInputStream(payload),
            originalFileName = "berlin.map",
            format = MapStorageManager.MapFileFormat.MAPSFORGE_MAP,
            regionName = "Berlin",
            version = "1.0",
            expectedSha256 = sha,
        )
        assertTrue(res.isSuccess)
        val region = res.getOrThrow()
        assertEquals("INSTALLED", region.installationState)
        assertEquals(sha, region.sha256)
        assertEquals("REGIONAL_DETAIL", region.detailLevels)
    }

    @Test
    fun `2 reject corrupted map package`() = runBlocking {
        val corrupt = "NOT_A_VALID_MAPSFORGE_HEADER_1234567890".toByteArray()
        val res = storage.importFromStream(
            inputStream = ByteArrayInputStream(corrupt),
            originalFileName = "corrupt.map",
            format = MapStorageManager.MapFileFormat.MAPSFORGE_MAP,
            regionName = "Corrupt",
            version = "1.0",
        )
        assertTrue(res.isFailure)
        assertTrue(db.mapRegionDao().getInstalledOnce().isEmpty())
    }

    @Test
    fun `4 atomic update preserves existing installed map when update fails SHA-256`() = runBlocking {
        val v1Bytes = validMapsforgeSampleBytes("v1")
        val v1Sha = EncryptionManager.sha256Hex(v1Bytes)
        storage.importFromStream(
            ByteArrayInputStream(v1Bytes),
            "brandenburg.map",
            MapStorageManager.MapFileFormat.MAPSFORGE_MAP,
            "Brandenburg",
            "1.0",
            v1Sha,
        ).getOrThrow()

        val v2Bytes = validMapsforgeSampleBytes("v2")
        val failedUpdate = storage.importFromStream(
            ByteArrayInputStream(v2Bytes),
            "brandenburg.map",
            MapStorageManager.MapFileFormat.MAPSFORGE_MAP,
            "Brandenburg",
            "2.0",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        assertTrue(failedUpdate.isFailure)
        val stillInstalled = db.mapRegionDao().getById("brandenburg")
        assertNotNull(stillInstalled)
        assertEquals("1.0", stillInstalled!!.version)
        assertEquals(v1Sha, stillInstalled.sha256)
    }

    @Test
    fun `5 and 6 bluetooth map package chunk transfer pause resume and SHA-256 install`() = runBlocking {
        val updateEngine = BluetoothMapUpdateEngine(storage, db.mapPackageDao(), btManager)
        val payload = validMapsforgeSampleBytes("p2p_transfer")
        val sha = EncryptionManager.sha256Hex(payload)
        val chunkSize = 32
        val totalChunks = (payload.size + chunkSize - 1) / chunkSize

        val offer = CyberLinkProtocol.ProtocolEnvelope.MapPackageOffer(
            packageId = "pkg_test_1",
            regionId = "p2p_region",
            regionName = "P2P Region",
            packageType = "MAP_VECTOR",
            version = "1.0",
            fileSize = payload.size.toLong(),
            sha256 = sha,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
        )
        updateEngine.onOfferReceived("CL-PEER1", offer)

        // Receive first chunk, pause/interrupt, verify resume index from staging part file
        val c0 = payload.copyOfRange(0, minOf(chunkSize, payload.size))
        updateEngine.onChunkReceived(
            "CL-PEER1",
            CyberLinkProtocol.ProtocolEnvelope.MapChunkPacket(
                packageId = offer.packageId,
                chunkIndex = 0,
                totalChunks = totalChunks,
                dataB64 = Base64.encodeToString(c0, Base64.NO_WRAP),
            ),
        )
        // Re-offer simulates reconnect resume check
        updateEngine.onOfferReceived("CL-PEER1", offer)
        val resumedState = updateEngine.transfers.value[offer.packageId]!!
        assertEquals(1, resumedState.currentChunk)

        // Complete remaining chunks
        for (i in 1 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val slice = payload.copyOfRange(start, end)
            updateEngine.onChunkReceived(
                "CL-PEER1",
                CyberLinkProtocol.ProtocolEnvelope.MapChunkPacket(
                    packageId = offer.packageId,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    dataB64 = Base64.encodeToString(slice, Base64.NO_WRAP),
                ),
            )
        }
        assertEquals("INSTALLED", updateEngine.transfers.value[offer.packageId]?.state)
        assertNotNull(db.mapRegionDao().getById("p2p_region"))
    }

    @Test
    fun `7 and 8 offline GPS shows NO_SIGNAL without fix, ALT for last-known, and ACTIVE_FIX for fresh GPS`() {
        val gps = OfflineGpsEngine(context)
        assertEquals(OfflineGpsEngine.GpsSignalStatus.NO_SIGNAL, gps.snapshot.value.status)

        val loc = Location("gps").apply {
            latitude = 52.5200
            longitude = 13.4050
            accuracy = 8.0f
            time = System.currentTimeMillis()
        }
        gps.updateFromLocation(loc, isLastKnown = true)
        assertEquals(OfflineGpsEngine.GpsSignalStatus.STALE_LAST_KNOWN, gps.snapshot.value.status)
        assertTrue(gps.snapshot.value.isStale)

        gps.updateFromLocation(loc, isLastKnown = false)
        assertEquals(OfflineGpsEngine.GpsSignalStatus.ACTIVE_FIX, gps.snapshot.value.status)
        assertFalse(gps.snapshot.value.isStale)
    }

    @Test
    fun `9 10 11 17 location sharing verified-only, revocation, replay-check, and restart persistence`() = runBlocking {
        val unverified = ContactEntity("CL-UNVER", "Unverified", "", "111111", verified = false, blocked = false)
        val verified = ContactEntity("CL-VERIF", "Alice", "", "222222", verified = true, blocked = false)
        db.contactDao().upsert(unverified)
        db.contactDao().upsert(verified)

        assertTrue(locationSharing.enableSharingForContact("CL-UNVER").isFailure)
        assertTrue(locationSharing.enableSharingForContact("CL-VERIF").isSuccess)

        // Persisted authorization check
        val activeAfterRestart = db.locationShareDao().getActiveSharesOnce()
        assertEquals(1, activeAfterRestart.size)
        assertEquals("CL-VERIF", activeAfterRestart.first().contactNodeId)

        // Incoming replay & stale timestamp checks
        val now = System.currentTimeMillis()
        val u1 = CyberLinkProtocol.ProtocolEnvelope.LocationShareUpdate(
            contactNodeId = "CL-VERIF",
            latitude = 52.52,
            longitude = 13.40,
            accuracyMeters = 5f,
            altitudeMeters = 40.0,
            speedMps = 1.2f,
            bearingDeg = 90f,
            timestampMs = now - (15 * 60 * 1000L), // 15 min old -> STALE
            sequenceNonce = 100L,
        )
        val stored = locationSharing.handleIncomingLocationUpdate("CL-VERIF", u1, now).getOrThrow()
        assertEquals("STALE", stored.locationState)

        // Replay with same/lower nonce must be rejected
        assertTrue(locationSharing.handleIncomingLocationUpdate("CL-VERIF", u1, now).isFailure)

        // Revocation disables share immediately
        locationSharing.revokeSharingForContact("CL-VERIF")
        assertTrue(db.locationShareDao().getActiveSharesOnce().isEmpty())
    }

    @Test
    fun `12 13 14 Haversine distance, stale marker, and disconnected contact states`() {
        val dist = ContactMapStatusResolver.distanceMeters(52.5200, 13.4050, 52.5300, 13.4050)
        assertTrue("Expected ~1111m, got $dist", dist in 1050.0..1180.0)

        val contact = ContactEntity("CL-BOB", "Bob", "", "333333", verified = true)
        val oldLoc = ContactLocationEntity(
            contactNodeId = "CL-BOB",
            latitude = 52.53,
            longitude = 13.405,
            accuracy = 12f,
            timestamp = System.currentTimeMillis() - 900_000L,
            locationState = "STALE",
        )
        val marker = ContactMapStatusResolver.buildMarker(
            contact = contact,
            transportState = TransportState.DISCONNECTED,
            activeTransport = ActiveTransport.NONE,
            location = oldLoc,
            ownGps = OfflineGpsEngine.GpsSnapshot(
                status = OfflineGpsEngine.GpsSignalStatus.ACTIVE_FIX,
                latitude = 52.52,
                longitude = 13.405,
            ),
        )
        assertEquals(ContactMapStatusResolver.FriendMapColor.YELLOW, marker.color)
        assertTrue(marker.isLocationStale)
        assertFalse(marker.chatAvailable)
        assertFalse(marker.audioCallAvailable)
        assertNotNull(marker.gpsDistanceMeters)
    }

    @Test
    fun `15 offline routing reports ROUTINGDATEN FEHLEN when empty and solves A-Star when graph installed`() = runBlocking {
        val router = OfflineRoutingEngine(storage)
        val missing = router.calculateRoute(52.52, 13.40, 52.53, 13.41)
        assertTrue(missing is OfflineRoutingEngine.RoutingResult.MissingData)

        val graph = OfflineRoutingEngine.RoutingGraph(
            version = "2026.11",
            regionId = "berlin_test",
            nodes = listOf(
                OfflineRoutingEngine.GraphNode("n1", 52.5200, 13.4050),
                OfflineRoutingEngine.GraphNode("n2", 52.5250, 13.4050),
                OfflineRoutingEngine.GraphNode("n3", 52.5300, 13.4050),
            ),
            edges = listOf(
                OfflineRoutingEngine.GraphEdge("n1", "n2", 556.0, 13.9),
                OfflineRoutingEngine.GraphEdge("n2", "n3", 556.0, 13.9),
            ),
        )
        val graphBytes = Json.encodeToString(graph).toByteArray(Charsets.UTF_8)
        storage.importFromStream(
            ByteArrayInputStream(graphBytes),
            "berlin_test.clroute",
            MapStorageManager.MapFileFormat.CYBER_ROUTING,
            "Berlin Routing",
            "1.0",
        ).getOrThrow()

        val found = router.calculateRoute(52.5200, 13.4050, 52.5300, 13.4050)
        assertTrue(found is OfflineRoutingEngine.RoutingResult.Success)
        val success = found as OfflineRoutingEngine.RoutingResult.Success
        assertEquals(3, success.route.points.size)
        assertEquals(1112.0, success.route.distanceMeters, 1.0)
    }

    @Test
    fun `16 ultra battery mode blocks mesh relay and mesh never relays location updates`() {
        val mesh = MeshRouter()
        val locPayload = Base64.encodeToString("LocationShareUpdate:52.52,13.40".toByteArray(), Base64.NO_WRAP)
        val locMesh = CyberLinkProtocol.ProtocolEnvelope.MeshRelayPacket(
            packetId = "p1",
            originNodeId = "CL-A",
            finalTargetNodeId = "CL-B",
            ttl = 4,
            hopCount = 0,
            priority = "HIGH",
            innerPayloadB64 = locPayload,
            timestampMs = System.currentTimeMillis(),
        )
        assertEquals(
            MeshRouter.RelayDecision.DROPPED_LOCATION_FORBIDDEN,
            mesh.handleIncomingMeshPacket(locMesh, "CL-ME", userOptInEnabled = true, batteryMode = BatteryMode.NORMAL),
        )

        val chatMesh = locMesh.copy(
            packetId = "p2",
            innerPayloadB64 = Base64.encodeToString("ChatMessage:Hallo".toByteArray(), Base64.NO_WRAP),
        )
        assertEquals(
            MeshRouter.RelayDecision.DROPPED_DISABLED,
            mesh.handleIncomingMeshPacket(chatMesh, "CL-ME", userOptInEnabled = true, batteryMode = BatteryMode.ULTRA),
        )
        assertNull(mesh.pollNextForward())
    }
}
