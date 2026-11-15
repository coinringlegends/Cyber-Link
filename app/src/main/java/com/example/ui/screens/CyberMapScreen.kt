package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.bluetooth.TransportState
import com.example.map.ContactMapStatusResolver
import com.example.map.OfflineGpsEngine
import com.example.ui.components.EmptyCyberState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberOutline
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

enum class CyberMapSection(val title: String) {
    CYBER_MAP("CYBER MAP"),
    MEINE_POSITION("MEINE POSITION"),
    CYBER_FRIENDS("CYBER FRIENDS"),
    NAEHE_SUCHEN("NÄHE SUCHEN"),
    OFFLINE_NAVIGATION("OFFLINE NAVIGATION"),
    KARTENVERWALTUNG("KARTENVERWALTUNG"),
    MAP_UPDATES("MAP UPDATES"),
    STANDORTFREIGABE("STANDORTFREIGABE"),
    MAP_PRIVACY("MAP PRIVACY"),
    MAP_DIAGNOSTICS("MAP DIAGNOSTICS"),
}

@Composable
fun CyberMapScreen(
    viewModel: CyberLinkViewModel,
    hasLocationPermission: Boolean,
    hasBluetoothPermissions: Boolean,
    onRequestLocationPermission: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeSection by remember { mutableStateOf(CyberMapSection.CYBER_MAP) }

    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(CyberMapSection.entries) { section ->
                FilterChip(
                    selected = activeSection == section,
                    onClick = { activeSection = section },
                    label = { Text(section.title, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("map_tab_${section.name}"),
                )
            }
        }

        when (activeSection) {
            CyberMapSection.CYBER_MAP -> CyberMapCanvasView(
                viewModel = viewModel,
                hasLocationPermission = hasLocationPermission,
                onRequestLocationPermission = onRequestLocationPermission,
            )
            CyberMapSection.MEINE_POSITION -> MyPositionView(
                viewModel = viewModel,
                hasLocationPermission = hasLocationPermission,
                onRequestLocationPermission = onRequestLocationPermission,
            )
            CyberMapSection.CYBER_FRIENDS -> CyberFriendsView(viewModel = viewModel)
            CyberMapSection.NAEHE_SUCHEN -> NearbySearchView(
                viewModel = viewModel,
                hasBluetoothPermissions = hasBluetoothPermissions,
                onRequestBluetoothPermissions = onRequestBluetoothPermissions,
            )
            CyberMapSection.OFFLINE_NAVIGATION -> OfflineNavigationView(viewModel = viewModel)
            CyberMapSection.KARTENVERWALTUNG -> MapStorageManagementView(viewModel = viewModel)
            CyberMapSection.MAP_UPDATES -> BluetoothMapUpdatesView(viewModel = viewModel)
            CyberMapSection.STANDORTFREIGABE -> LocationSharingView(
                viewModel = viewModel,
                hasLocationPermission = hasLocationPermission,
                onRequestLocationPermission = onRequestLocationPermission,
            )
            CyberMapSection.MAP_PRIVACY -> MapPrivacyView(viewModel = viewModel)
            CyberMapSection.MAP_DIAGNOSTICS -> MapDiagnosticsView(
                viewModel = viewModel,
                hasLocationPermission = hasLocationPermission,
            )
        }
    }
}

@Composable
private fun CyberMapCanvasView(
    viewModel: CyberLinkViewModel,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    val regions by viewModel.mapRegions.collectAsStateWithLifecycle()
    val gps by viewModel.gpsSnapshot.collectAsStateWithLifecycle()
    val markers by viewModel.friendMarkers.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()

    val installedMapFile = remember(regions) {
        regions.firstOrNull {
            it.installationState == "INSTALLED" && it.filePath.endsWith(".map", ignoreCase = true) && File(it.filePath).exists()
        }?.let { File(it.filePath) }
    }

    var waypointTitle by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberObsidian),
            ) {
                if (installedMapFile != null) {
                    MapsforgeMapContainer(
                        mapFile = installedMapFile,
                        centerLat = gps.latitude ?: 52.5200,
                        centerLon = gps.longitude ?: 13.4050,
                    )
                } else {
                    OfflineCoordinateGridFallback(
                        gps = gps,
                        markers = markers,
                        waypointsCount = waypoints.size,
                    )
                }

                // Mandatory OpenStreetMap ODbL Attribution Bar
                Surface(
                    color = CyberObsidian.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.osm_attribution),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                Surface(
                    color = CyberObsidian.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = if (installedMapFile != null) "Mapsforge: ${installedMapFile.name}" else "Keine .map installiert",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (installedMapFile != null) CyberEmerald else CyberAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (!hasLocationPermission) onRequestLocationPermission()
                        else viewModel.startGps(true)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("map_gps_start_btn"),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasLocationPermission) "GPS Fix starten" else "GPS Berechtigung")
                }
                OutlinedButton(
                    onClick = { viewModel.refreshGps(hasLocationPermission) },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "GPS aktualisieren")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = waypointTitle,
                    onValueChange = { waypointTitle = it },
                    label = { Text("Wegpunkt an aktueller Position") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = gps.latitude != null && gps.longitude != null,
                    onClick = {
                        val lat = gps.latitude ?: return@Button
                        val lon = gps.longitude ?: return@Button
                        viewModel.addWaypoint(waypointTitle, "USER_PIN", lat, lon)
                        waypointTitle = ""
                    },
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = "Wegpunkt speichern")
                }
            }
        }

        item {
            Text(
                text = "Installierte Kartenebenen: ${regions.count { it.installationState == "INSTALLED" }} • Freunde auf Karte: ${markers.size}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
            )
        }
    }
}

@Composable
private fun MapsforgeMapContainer(
    mapFile: File,
    centerLat: Double,
    centerLon: Double,
) {
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                isClickable = true
                mapScaleBar.isVisible = true
                setBuiltInZoomControls(true)
                val cache = AndroidUtil.createTileCache(
                    ctx,
                    "cyber_map_tiles",
                    model.displayModel.tileSize,
                    1f,
                    model.frameBufferModel.overdrawFactor,
                )
                val mapDataStore = MapFile(mapFile)
                val tileLayer = TileRendererLayer(
                    cache,
                    mapDataStore,
                    model.mapViewPosition,
                    org.mapsforge.map.android.graphics.AndroidGraphicFactory.INSTANCE,
                )
                tileLayer.setXmlRenderTheme(MapsforgeThemes.OSMARENDER)
                layerManager.layers.add(tileLayer)
                setCenter(LatLong(centerLat, centerLon))
                setZoomLevel(13.toByte())
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun OfflineCoordinateGridFallback(
    gps: OfflineGpsEngine.GpsSnapshot,
    markers: List<ContactMapStatusResolver.CyberFriendMarker>,
    waypointsCount: Int,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = CyberOutline.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = CyberOutline.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = CyberCyan.copy(alpha = 0.25f),
                radius = 70.dp.toPx(),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                ),
            )
            if (gps.latitude != null) {
                drawCircle(color = CyberEmerald, radius = 8.dp.toPx(), center = center)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Layers, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Offline-Koordinatenraster (Keine .map installiert)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Importiere eine Mapsforge .map Datei unter KARTENVERWALTUNG für vollständige Vektorkarten.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary,
            )
            Text(
                text = "GPS: ${gps.status.labelDe} • Wegpunkte: $waypointsCount • Freunde: ${markers.size}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberCyan,
            )
        }
    }
}

@Composable
private fun MyPositionView(
    viewModel: CyberLinkViewModel,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    val gps by viewModel.gpsSnapshot.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = CyberCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = gps.status.labelDe,
                            style = MaterialTheme.typography.titleLarge,
                            color = when (gps.status) {
                                OfflineGpsEngine.GpsSignalStatus.ACTIVE_FIX -> CyberEmerald
                                OfflineGpsEngine.GpsSignalStatus.STALE_LAST_KNOWN -> CyberAmber
                                else -> CyberCrimson
                            },
                        )
                    }
                    Text("Breitengrad: ${gps.latitude?.let { "%.6f".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text("Längengrad: ${gps.longitude?.let { "%.6f".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text("Genauigkeit: ${gps.accuracyMeters?.let { "%.1f m".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text("Höhe: ${gps.altitudeMeters?.let { "%.1f m".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text("Geschwindigkeit: ${gps.speedMps?.let { "%.2f m/s".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text("Kompass / Kurs: ${gps.bearingDeg?.let { "%.0f°".format(it) } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "Zeitstempel: ${gps.timestampMs?.let { timeFmt.format(Date(it)) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!hasLocationPermission) onRequestLocationPermission()
                                else viewModel.startGps(true)
                            },
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("GNSS-Updates starten")
                        }
                        OutlinedButton(onClick = { viewModel.refreshGps(hasLocationPermission) }) {
                            Text("Manuell aktualisieren")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CyberFriendsView(viewModel: CyberLinkViewModel) {
    val markers by viewModel.friendMarkers.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "CYBER FRIENDS (Nur bestätigte Kontakte)",
                style = MaterialTheme.typography.titleLarge,
                color = CyberCyan,
            )
            Text(
                text = "Hinweis: GPS-Entfernung nutzt ausschließlich echte Koordinaten (niemals RSSI) und bedeutet keine Bluetooth-Anrufreichweite.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary,
            )
        }

        if (markers.isEmpty()) {
            item {
                EmptyCyberState(
                    icon = Icons.Default.MyLocation,
                    title = "Keine bestätigten Kontakte",
                    subtitle = "Kopple Kontakte per QR oder CL2-Code, um autorisierte Standorte anzuzeigen.",
                )
            }
        }

        items(markers, key = { it.contact.nodeId }) { m ->
            val dotColor = when (m.color) {
                ContactMapStatusResolver.FriendMapColor.GREEN -> CyberEmerald
                ContactMapStatusResolver.FriendMapColor.CYAN -> CyberCyan
                ContactMapStatusResolver.FriendMapColor.YELLOW -> CyberAmber
                ContactMapStatusResolver.FriendMapColor.GRAY -> CyberTextSecondary
                ContactMapStatusResolver.FriendMapColor.RED -> CyberCrimson
            }
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(m.contact.displayName, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(m.color.labelDe, style = MaterialTheme.typography.labelSmall, color = dotColor)
                    Text(
                        text = "GPS-Entfernung: ${ContactMapStatusResolver.formatDistance(m.gpsDistanceMeters)}" +
                            (m.location?.let { " • ±${it.accuracy.toInt()} m" + if (m.isLocationStale) " [ALT]" else "" } ?: " • Kein Standort"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Transport: ${m.activeTransport.labelDe} | Chat: ${if (m.chatAvailable) "JA" else "NEIN"} | Audio: ${if (m.audioCallAvailable) "JA" else "NEIN"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbySearchView(
    viewModel: CyberLinkViewModel,
    hasBluetoothPermissions: Boolean,
    onRequestBluetoothPermissions: () -> Unit,
) {
    val discovered by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val contactMap = remember(contacts) { contacts.associateBy { it.nodeId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = {
                    if (!hasBluetoothPermissions) onRequestBluetoothPermissions()
                    else viewModel.startBluetoothServicesAndScan(continuous = false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nearby_search_start_btn"),
            ) {
                Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isScanning) "BLE Nearby-Scan läuft…" else "NÄHE SUCHEN (CYBER-LINK BLE Filter)")
            }
        }

        val verifiedNearby = discovered.filter { it.nodeId != null && contactMap[it.nodeId]?.let { c -> c.verified && !c.blocked } == true }
        if (verifiedNearby.isEmpty()) {
            item {
                EmptyCyberState(
                    icon = Icons.Default.BluetoothSearching,
                    title = "Keine bestätigten Kontakte in Bluetooth-Reichweite",
                    subtitle = "Es werden ausschließlich bestätigte, nicht blockierte CYBER-LINK-Kontakte angezeigt.",
                )
            }
        }

        items(verifiedNearby, key = { it.address }) { peer ->
            val nodeId = peer.nodeId!!
            val contact = contactMap[nodeId]!!
            val session = sessions[nodeId]
            val state = session?.transportState ?: TransportState.BLUETOOTH_FOUND
            val transport = viewModel.container.bluetoothManager.getEffectiveTransportForPeer(nodeId)
            val dataReady = state == TransportState.CONNECTED
            val rfcommReady = transport.name.contains("RFCOMM")

            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${contact.displayName} ($nodeId)", style = MaterialTheme.typography.titleMedium)
                    Text("Bluetooth gefunden (RSSI: ${peer.rssi} dBm) • Verbindung möglich: JA", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                    Text(
                        text = "Auth: ${if (dataReady) "ERFOLGREICH" else state.labelDe} | Datenkanal: ${if (dataReady) "BEREIT" else "NEIN"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "Chat: ${if (dataReady) "MÖGLICH" else "NEIN"} | Audio: ${if (rfcommReady) "VERFÜGBAR" else "NICHT UNTERSTÜTZT"} | Video: ${if (rfcommReady) "VERFÜGBAR" else "NICHT UNTERSTÜTZT"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                    Button(onClick = { viewModel.connectPeer(nodeId, peer.address) }) {
                        Text("Verbindung aufbauen")
                    }
                }
            }
        }
    }
}
