package com.example.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.bluetooth.TransportState
import com.example.map.ContactMapStatusResolver
import com.example.map.OfflineRoutingEngine
import com.example.map.OfflineSearchEngine
import com.example.ui.components.EmptyCyberState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel

@Composable
fun OfflineNavigationView(viewModel: CyberLinkViewModel) {
    val gps by viewModel.gpsSnapshot.collectAsStateWithLifecycle()
    val searchResult by viewModel.searchResult.collectAsStateWithLifecycle()
    val routingResult by viewModel.routingResult.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var destLatInput by remember { mutableStateOf("") }
    var destLonInput by remember { mutableStateOf("") }
    var selectedProfile by remember { mutableStateOf(OfflineRoutingEngine.RouteProfile.AUTO) }
    var selectedOpt by remember { mutableStateOf(OfflineRoutingEngine.RouteOptimization.FASTEST) }
    var avoidHighways by remember { mutableStateOf(false) }
    var avoidUnpaved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Offline Suche & Navigation", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Ort, Straße, POI oder Wegpunkt suchen") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.searchOffline(searchQuery) }) {
                    Icon(Icons.Default.Search, contentDescription = "Suchen")
                }
            }
        }

        when (val sr = searchResult) {
            is OfflineSearchEngine.SearchResult.NotInstalled -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CyberAmber.copy(alpha = 0.14f))) {
                        Text(sr.reason, modifier = Modifier.padding(12.dp), color = CyberAmber)
                    }
                }
            }
            is OfflineSearchEngine.SearchResult.Success -> {
                items(sr.hits) { hit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated),
                        onClick = {
                            destLatInput = hit.latitude.toString()
                            destLonInput = hit.longitude.toString()
                        },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.titleMedium)
                            Text("${hit.subtitle} • ${hit.latitude}, ${hit.longitude}", style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                        }
                    }
                }
            }
            null -> Unit
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Offline-Routenberechnung (A*)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OfflineRoutingEngine.RouteProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = selectedProfile == profile,
                                onClick = { selectedProfile = profile },
                                label = { Text(profile.labelDe, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OfflineRoutingEngine.RouteOptimization.entries.forEach { opt ->
                            FilterChip(
                                selected = selectedOpt == opt,
                                onClick = { selectedOpt = opt },
                                label = { Text(opt.labelDe) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = avoidHighways, onCheckedChange = { avoidHighways = it })
                        Text("Autobahnen vermeiden", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                        Checkbox(checked = avoidUnpaved, onCheckedChange = { avoidUnpaved = it })
                        Text("Unbefestigte Wege vermeiden", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = destLatInput,
                            onValueChange = { destLatInput = it },
                            label = { Text("Ziel Lat") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = destLonInput,
                            onValueChange = { destLonInput = it },
                            label = { Text("Ziel Lon") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val sLat = gps.latitude ?: 52.5200
                            val sLon = gps.longitude ?: 13.4050
                            val eLat = destLatInput.toDoubleOrNull() ?: return@Button
                            val eLon = destLonInput.toDoubleOrNull() ?: return@Button
                            viewModel.calculateOfflineRoute(
                                startLat = sLat,
                                startLon = sLon,
                                endLat = eLat,
                                endLon = eLon,
                                options = OfflineRoutingEngine.RouteOptions(
                                    profile = selectedProfile,
                                    optimization = selectedOpt,
                                    avoidHighways = avoidHighways,
                                    avoidUnpaved = avoidUnpaved,
                                ),
                            )
                        },
                        modifier = Modifier.testTag("calculate_route_btn"),
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Offline-Route berechnen")
                    }
                }
            }
        }

        when (val rr = routingResult) {
            is OfflineRoutingEngine.RoutingResult.MissingData -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CyberCrimson.copy(alpha = 0.14f))) {
                        Text(rr.message, modifier = Modifier.padding(14.dp), color = CyberCrimson)
                    }
                }
            }
            is OfflineRoutingEngine.RoutingResult.NoPathFound -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CyberAmber.copy(alpha = 0.14f))) {
                        Text(rr.message, modifier = Modifier.padding(14.dp), color = CyberAmber)
                    }
                }
            }
            is OfflineRoutingEngine.RoutingResult.Success -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CyberEmerald.copy(alpha = 0.14f))) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Route berechnet (v${rr.route.routingDataVersion})", style = MaterialTheme.typography.titleMedium, color = CyberEmerald)
                            Text("Distanz: ${ContactMapStatusResolver.formatDistance(rr.route.distanceMeters)} • Dauer: ${rr.route.durationSeconds / 60} min • Punkte: ${rr.route.points.size}")
                        }
                    }
                }
            }
            null -> Unit
        }
    }
}

@Composable
fun MapStorageManagementView(viewModel: CyberLinkViewModel) {
    val context = LocalContext.current
    val regions by viewModel.mapRegions.collectAsStateWithLifecycle()

    var regionNameInput by remember { mutableStateOf("") }
    var versionInput by remember { mutableStateOf("1.0") }
    var expectedShaInput by remember { mutableStateOf("") }
    var isWorldOverview by remember { mutableStateOf(false) }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
            viewModel.importMapUri(
                uri = uri,
                fileNameHint = fileName,
                regionName = regionNameInput.ifBlank { fileName?.substringBeforeLast('.') ?: "Region" },
                version = versionInput.ifBlank { "1.0" },
                expectedSha256 = expectedShaInput.ifBlank { null },
                isWorldOverview = isWorldOverview,
            )
        }
    }

    val overviewInstalled = regions.any { it.detailLevels == "WORLD_OVERVIEW" && it.installationState == "INSTALLED" }
    val detailCount = regions.count { it.detailLevels == "REGIONAL_DETAIL" && it.installationState == "INSTALLED" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("KARTENVERWALTUNG (Weltübersicht & Regionen)", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
            Text(
                text = "Weltübersicht: ${if (overviewInstalled) "INSTALLIERT" else "NICHT INSTALLIERT"} | Regionale Detailpakete: $detailCount installiert",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Manueller Kartenimport via SAF (.map, .poi, .clroute, .clmap)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = regionNameInput,
                        onValueChange = { regionNameInput = it },
                        label = { Text("Regionsname (z.B. Deutschland-Berlin)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = versionInput,
                            onValueChange = { versionInput = it },
                            label = { Text("Version") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = expectedShaInput,
                            onValueChange = { expectedShaInput = it },
                            label = { Text("Optionaler SHA-256") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isWorldOverview, onCheckedChange = { isWorldOverview = it })
                        Text("Als Weltübersicht (Ebene 1) kennzeichnen", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { safLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("saf_import_map_btn"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Datei auswählen & atomar installieren")
                    }
                }
            }
        }

        items(regions, key = { it.regionId }) { region ->
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(region.regionName, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { viewModel.deleteMapRegion(region.regionId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Region löschen", tint = CyberCrimson)
                        }
                    }
                    Text("Version: ${region.version} • Status: ${region.installationState} • Ebene: ${region.detailLevels}", style = MaterialTheme.typography.labelSmall, color = CyberEmerald)
                    Text("Größe: ${region.fileSize / 1024} KB • SHA-256: ${region.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                    Text(region.licenseAttribution, style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                }
            }
        }
    }
}

@Composable
fun BluetoothMapUpdatesView(viewModel: CyberLinkViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val regions by viewModel.mapRegions.collectAsStateWithLifecycle()
    val transfers by viewModel.mapTransfers.collectAsStateWithLifecycle()

    val connectedPeers = sessions.values.filter { it.transportState == TransportState.CONNECTED }
    var selectedPeer by remember(connectedPeers) { mutableStateOf(connectedPeers.firstOrNull()?.peerNodeId) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("BLUETOOTH MAP UPDATES (Offline P2P)", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
            Text(
                text = "Überträgt Kartenpakete in Chunks mit ACK, Fortsetzen und SHA-256-Prüfung über authentifizierte CYBER-LINK-Verbindungen.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary,
            )
        }

        if (connectedPeers.isEmpty()) {
            item {
                EmptyCyberState(
                    icon = Icons.Default.Download,
                    title = "Kein authentifizierter Bluetooth-Peer verbunden",
                    subtitle = "Verbinde zwei Geräte über den Netz-Tab, um Kartenpakete auszutauschen.",
                )
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connectedPeers.forEach { peer ->
                        FilterChip(
                            selected = selectedPeer == peer.peerNodeId,
                            onClick = { selectedPeer = peer.peerNodeId },
                            label = { Text(peer.deviceName ?: peer.peerNodeId) },
                        )
                    }
                }
            }
        }

        items(regions.filter { it.installationState == "INSTALLED" }, key = { it.regionId }) { region ->
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(region.regionName, style = MaterialTheme.typography.titleMedium)
                        Text("v${region.version} • ${region.fileSize / 1024} KB", style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                    }
                    Button(
                        enabled = selectedPeer != null,
                        onClick = { selectedPeer?.let { viewModel.offerMapToPeer(it, region) } },
                    ) {
                        Text("Anbieten")
                    }
                }
            }
        }

        items(transfers.values.toList(), key = { it.packageId }) { tr ->
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${tr.regionName} (${tr.direction}) — ${tr.state}", style = MaterialTheme.typography.titleMedium)
                    Text("Chunks: ${tr.currentChunk}/${tr.totalChunks} • ${tr.bytesTransferred}/${tr.totalBytes} B", style = MaterialTheme.typography.labelSmall)
                    if (tr.errorMessage != null) {
                        Text(tr.errorMessage, style = MaterialTheme.typography.bodyMedium, color = CyberCrimson)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tr.state == "AWAITING_CONFIRMATION" || tr.state == "PAUSED") {
                            Button(onClick = { viewModel.acceptMapOffer(tr.packageId) }) {
                                Text(if (tr.state == "PAUSED") "Fortsetzen" else "Übertragung bestätigen")
                            }
                        }
                        if (tr.state == "TRANSFERRING") {
                            OutlinedButton(onClick = { viewModel.container.mapUpdateEngine.pauseTransfer(tr.packageId) }) {
                                Text("Pausieren")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationSharingView(
    viewModel: CyberLinkViewModel,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val shares by viewModel.locationShares.collectAsStateWithLifecycle()
    val bgState by viewModel.backgroundSharingState.collectAsStateWithLifecycle()
    var useForegroundService by remember { mutableStateOf(true) }

    val shareByContact = remember(shares) { shares.associateBy { it.contactNodeId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("STANDORTFREIGABE (Standard: AUS)", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
            Text(
                text = "Hintergrunddienst: ${bgState.labelDe} • Ausschließlich für explizit bestätigte Kontakte, niemals über Mesh-Relays.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary,
            )
            if (!hasLocationPermission) {
                Button(onClick = onRequestLocationPermission, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Standortberechtigung für GPS-Freigabe erteilen")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useForegroundService, onCheckedChange = { useForegroundService = it })
                Text("Dauerhafte Freigabe per Android Foreground-Service (mit Stoppen-Benachrichtigung)")
            }
        }

        items(contacts.filter { it.verified && !it.blocked }, key = { it.nodeId }) { contact ->
            val active = shareByContact[contact.nodeId]?.let { it.enabled && it.revokedAt == null } == true
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (active) "FREIGABE AKTIV bis zum Widerruf" else "Freigabe AUS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) CyberEmerald else CyberTextSecondary,
                        )
                    }
                    Switch(
                        checked = active,
                        onCheckedChange = { next ->
                            if (next && !hasLocationPermission) {
                                onRequestLocationPermission()
                            } else {
                                viewModel.toggleLocationSharingForContact(contact.nodeId, next, useForegroundService)
                            }
                        },
                        modifier = Modifier.testTag("share_switch_${contact.nodeId}"),
                    )
                }
            }
        }
    }
}

@Composable
fun MapPrivacyView(viewModel: CyberLinkViewModel) {
    val killSwitch by viewModel.globalLocationKillSwitch.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("MAP PRIVACY & NOT-AUS", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
            Text(
                text = "Globaler Not-Aus Status: ${if (killSwitch) "AKTIVIERT (Alle Freigaben gestoppt)" else "Normal"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (killSwitch) CyberCrimson else CyberEmerald,
            )
        }
        item {
            Button(
                onClick = { viewModel.triggerEmergencyLocationKillSwitch() },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("location_emergency_stop_btn"),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("NOT-AUS: Alle Standortfreigaben sofort widerrufen")
            }
        }
        item {
            OutlinedButton(
                onClick = { viewModel.clearAllContactLocations() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Empfangene Kontaktstandorte löschen")
            }
        }
        item {
            OutlinedButton(
                onClick = { viewModel.exportOfflineDiagnosticsAndData() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Lokale Daten & Diagnose exportieren")
            }
        }
        item {
            OutlinedButton(
                onClick = { viewModel.wipeAllLocalData() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCrimson),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Shield, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Lokale Daten vollständig zurücksetzen")
            }
        }
    }
}

@Composable
fun MapDiagnosticsView(
    viewModel: CyberLinkViewModel,
    hasLocationPermission: Boolean,
) {
    val gps by viewModel.gpsSnapshot.collectAsStateWithLifecycle()
    val regions by viewModel.mapRegions.collectAsStateWithLifecycle()
    val bgState by viewModel.backgroundSharingState.collectAsStateWithLifecycle()
    val freeMb = remember(regions) { viewModel.container.mapStorageManager.getAvailableBytes() / (1024 * 1024) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("CYBER MAP V1.0 DIAGNOSE", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                    Text("Netzwerk / INTERNET-Berechtigung: ENTFERNT (100% Offline)", style = MaterialTheme.typography.labelSmall, color = CyberEmerald)
                    Text("GPS-Berechtigung: ${if (hasLocationPermission) "ERTEILT" else "FEHLT"}", style = MaterialTheme.typography.labelSmall)
                    Text("GPS-Status: ${gps.status.labelDe}", style = MaterialTheme.typography.labelSmall)
                    Text("Hintergrund-Freigabe: ${bgState.labelDe}", style = MaterialTheme.typography.labelSmall)
                    Text("Freier interner Kartenspeicher: $freeMb MB", style = MaterialTheme.typography.labelSmall)
                    Text("Installierte Regionen: ${regions.count { it.installationState == "INSTALLED" }}", style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.osm_attribution), style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                    Text(stringResource(R.string.mapsforge_attribution), style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                }
            }
        }
    }
}
