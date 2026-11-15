package com.example.ui.screens

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.battery.BatteryMode
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel
import java.io.File

private data class FeatureAuditRow(
    val feature: String,
    val file: String,
    val implStatus: String,
    val hwStatus: String,
)

@Composable
fun SystemStatusScreen(
    viewModel: CyberLinkViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val batteryMode by viewModel.batteryMode.collectAsStateWithLifecycle()
    val meshOptIn by viewModel.meshRelayOptIn.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val meshQueued by viewModel.container.meshRouter.queuedCount.collectAsStateWithLifecycle()
    val meshRelayed by viewModel.container.meshRouter.relayedCount.collectAsStateWithLifecycle()

    var nameEdit by remember(displayName) { mutableStateOf(displayName) }

    val realBatteryPct = remember {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100) / scale else null
    }

    val localExportFiles = remember {
        File(context.filesDir, "exports").listFiles()?.toList().orEmpty()
    }

    val auditRows = remember {
        listOf(
            FeatureAuditRow("GATT Sequential Queue & Status 147", "GattOperationQueue.kt / GattClient.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("ECDH + ECDSA + AES-GCM VERIFY_ACK", "EncryptionManager.kt / PeerTransportSession.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Signed/Expiring Single-Use QR + 12-Step Mutual Auth", "PeerManager.kt / BluetoothManager.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Encrypted Room Chat, Read Receipts & File/Image Transfer", "LocalStorageCipher.kt / BluetoothFileTransferManager.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Audio Calls (AudioRecord/AudioTrack RFCOMM + Telemetry)", "AudioCallManager.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Video Calls (CameraX -> RFCOMM + Two-Way Signaling)", "VideoCallManager.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Group Calls (3+ Smartphones Media)", "CallsScreen.kt", "NOT SUPPORTED", "NOT TESTED"),
            FeatureAuditRow("Voluntary Mesh Relay (No Location Relay)", "MeshRouter.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("CYBER MAP (Mapsforge + SAF + SHA-256)", "MapStorageManager.kt / CyberMapScreen.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Bluetooth Map Update Engine", "BluetoothMapUpdateEngine.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Offline GPS & Opt-in Location Sharing", "OfflineGpsEngine.kt / LocationSharingManager.kt", "IMPLEMENTED", "NOT TESTED"),
            FeatureAuditRow("Offline Search & A* Routing", "OfflineSearchEngine.kt / OfflineRoutingEngine.kt", "IMPLEMENTED", "NOT TESTED"),
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("System, Akku, Mesh & Audit-Matrix", style = MaterialTheme.typography.titleLarge, color = CyberCyan)
        }

        // Identity Name
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it },
                        label = { Text("Lokaler Gerätename") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { viewModel.setDisplayName(nameEdit) }) {
                        Text("Speichern")
                    }
                }
            }
        }

        // Battery Dashboard (real BatteryManager reading, never faked)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = CyberEmerald)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Akku-Dashboard (Systemwert: ${realBatteryPct?.let { "$it%" } ?: "Unbekannt"})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BatteryMode.entries.forEach { mode ->
                            FilterChip(
                                selected = batteryMode == mode,
                                onClick = { viewModel.setBatteryMode(mode) },
                                label = { Text(mode.labelDe) },
                                modifier = Modifier.testTag("battery_mode_${mode.name}"),
                            )
                        }
                    }
                    Text(batteryMode.descriptionDe, style = MaterialTheme.typography.bodyMedium, color = CyberTextSecondary)
                    Text(
                        text = "GPS-Intervall: ${batteryMode.gpsMinTimeMs / 1000}s / ${batteryMode.gpsMinDistanceMeters.toInt()}m • BLE-Scan: ${batteryMode.bleScanWindowMs / 1000}s • Leerlauf-Trennung: ${batteryMode.idleDisconnectMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                    )
                }
            }
        }

        // Voluntary Mesh Relay
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = CyberCyan)
                            Spacer(Modifier.width(6.dp))
                            Text("Freiwilliges Mesh-Relay (Store & Forward)", style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(
                            checked = meshOptIn && batteryMode.allowMeshRelay,
                            enabled = batteryMode.allowMeshRelay,
                            onCheckedChange = { viewModel.setMeshRelayOptIn(it) },
                            modifier = Modifier.testTag("mesh_relay_switch"),
                        )
                    }
                    Text(
                        text = if (!batteryMode.allowMeshRelay) {
                            "Im ULTRA-Modus deaktiviert."
                        } else {
                            "Warteschlange: $meshQueued • Weitergeleitet: $meshRelayed (Standortmeldungen werden NIEMALS über Mesh weitergeleitet)."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                    )
                }
            }
        }

        // Offline File Manager & Export
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = CyberAmber)
                        Spacer(Modifier.width(6.dp))
                        Text("Offline-Dateimanager & Datenschutz-Dashboard", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Exporte: ${localExportFiles.size} • Kartenverzeichnis: ${viewModel.container.mapStorageManager.rootDir.name}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.exportOfflineDiagnosticsAndData() }) {
                            Text("Diagnose exportieren")
                        }
                        OutlinedButton(onClick = { viewModel.wipeAllLocalData() }) {
                            Text("Alle Daten löschen", color = CyberCrimson)
                        }
                    }
                }
            }
        }

        // Feature & Hardware Test Matrix
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = CyberCyan)
                Spacer(Modifier.width(6.dp))
                Text("Funktions- & Hardwaretest-Matrix", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
            }
        }

        items(auditRows) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated.copy(alpha = 0.75f))) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(row.feature, style = MaterialTheme.typography.titleMedium)
                    Text(row.file, style = MaterialTheme.typography.labelSmall, color = CyberTextSecondary)
                    Text(
                        text = "Implementierung: ${row.implStatus} • Hardware-Test: ${row.hwStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.implStatus == "NOT SUPPORTED") CyberCrimson else CyberAmber,
                    )
                }
            }
        }
    }
}
