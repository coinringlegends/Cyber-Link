package com.example.ui.screens

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BleDiagnostics
import com.example.bluetooth.DiagnosticComponentState
import com.example.bluetooth.PeerManager
import com.example.bluetooth.RfcommDiagnostics
import com.example.bluetooth.RfcommSocketState
import com.example.bluetooth.TransportChannelStage
import com.example.bluetooth.TransportState
import com.example.media.QrCodeHelper
import com.example.ui.components.EmptyCyberState
import com.example.ui.components.TacticalHeroHeader
import com.example.ui.components.TransportStatusBadge
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

@Composable
fun BluetoothHubScreen(
    viewModel: CyberLinkViewModel,
    hasBluetoothPermissions: Boolean,
    hasCameraPermission: Boolean,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val bleDiagnostics by viewModel.bleDiagnostics.collectAsStateWithLifecycle()
    val rfcommDiagnostics by viewModel.rfcommDiagnostics.collectAsStateWithLifecycle()
    val qrPairingProgress by viewModel.qrPairingProgress.collectAsStateWithLifecycle()
    val incomingPrompt by viewModel.incomingPairingPrompt.collectAsStateWithLifecycle()
    val currentQrPayload by viewModel.currentQrPayload.collectAsStateWithLifecycle()

    var showMyQrDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var showManualCl2Dialog by remember { mutableStateOf(false) }
    var manualNodeId by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualCl2 by remember { mutableStateOf("") }

    val connectedCount = sessions.values.count {
        it.transportState == TransportState.CONNECTED && it.activeTransport != ActiveTransport.NONE
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TacticalHeroHeader(
                nodeId = viewModel.localNodeId,
                cl2Code = viewModel.localCl2Code,
                displayName = displayName,
                activePeerCount = connectedCount,
            )
        }

        if (!hasBluetoothPermissions) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberAmber.copy(alpha = 0.14f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Bluetooth-Berechtigungen erforderlich",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberAmber,
                        )
                        Text(
                            text = "Für BLE-Scan, GATT-Server und RFCOMM werden BLUETOOTH_SCAN, BLUETOOTH_CONNECT und BLUETOOTH_ADVERTISE benötigt (ohne Standortzwang).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onRequestBluetoothPermissions,
                            modifier = Modifier.testTag("grant_bluetooth_permissions_btn"),
                        ) {
                            Text("Bluetooth-Berechtigungen erteilen")
                        }
                    }
                }
            }
        }

        if (qrPairingProgress.active) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            qrPairingProgress.isError -> CyberCrimson.copy(alpha = 0.14f)
                            qrPairingProgress.isConnected -> CyberEmerald.copy(alpha = 0.14f)
                            else -> CyberCyan.copy(alpha = 0.12f)
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("qr_pairing_progress_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "QR-Verbindungsaufbau (Schritt ${qrPairingProgress.stepNumber} / 12)",
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                qrPairingProgress.isError -> CyberCrimson
                                qrPairingProgress.isConnected -> CyberEmerald
                                else -> CyberCyan
                            },
                        )
                        LinearProgressIndicator(
                            progress = { (qrPairingProgress.stepNumber / 12f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Nutzer: ${qrPairingProgress.peerName ?: "—"} • Node: ${qrPairingProgress.peerNodeId ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Temp-ID: ${qrPairingProgress.tempDeviceId ?: "—"} • BT-Addr: ${qrPairingProgress.btAddress ?: "über BLE-Scan"} • CL2: ${qrPairingProgress.cl2Code ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTextSecondary,
                        )
                        Text(
                            text = qrPairingProgress.stepLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (qrPairingProgress.errorMessage != null) {
                            Text(
                                text = "Fehler: ${qrPairingProgress.errorMessage}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberCrimson,
                            )
                        }
                        if (qrPairingProgress.awaitingLocalConfirm) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.confirmInitiatorQrPairing(true) },
                                    modifier = Modifier.testTag("qr_local_confirm_btn"),
                                ) {
                                    Text("Kopplung bestätigen")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.confirmInitiatorQrPairing(false) },
                                ) {
                                    Text("Abbrechen")
                                }
                            }
                        } else if (qrPairingProgress.isError || qrPairingProgress.isConnected) {
                            OutlinedButton(onClick = { viewModel.dismissQrPairingProgress() }) {
                                Text("Schließen")
                            }
                        }
                    }
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
                        if (!hasBluetoothPermissions) {
                            onRequestBluetoothPermissions()
                        } else if (isScanning) {
                            viewModel.stopScan()
                        } else {
                            viewModel.startBluetoothServicesAndScan(continuous = false)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ble_scan_toggle_btn"),
                ) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isScanning) "Scan stoppen" else "BLE Scan & GATT")
                }

                OutlinedButton(
                    onClick = {
                        if (hasBluetoothPermissions) {
                            viewModel.ensureBluetoothAdvertisingAndStandby()
                        }
                        showMyQrDialog = true
                    },
                    modifier = Modifier.testTag("show_my_qr_btn"),
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Mein QR-Code")
                    Spacer(Modifier.width(4.dp))
                    Text("Mein QR")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (hasCameraPermission) showQrScannerDialog = true else onRequestCameraPermission()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_qr_btn"),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("QR scannen")
                }

                OutlinedButton(
                    onClick = { showManualCl2Dialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_cl2_btn"),
                ) {
                    Icon(Icons.Default.SettingsInputAntenna, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("CL2-Code Pairing")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated.copy(alpha = 0.88f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ble_diagnostics_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("BLE-Diagnose (Echtzeit)", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                        if (bleDiagnostics.advertiseState != DiagnosticComponentState.STARTED) {
                            OutlinedButton(
                                onClick = {
                                    if (hasBluetoothPermissions) viewModel.ensureBluetoothAdvertisingAndStandby()
                                    else onRequestBluetoothPermissions()
                                },
                                modifier = Modifier.testTag("restart_advertising_btn"),
                            ) {
                                Text("Advertising starten", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val advColor = when (bleDiagnostics.advertiseState) {
                        DiagnosticComponentState.STARTED -> CyberEmerald
                        DiagnosticComponentState.FAILED -> CyberCrimson
                        else -> CyberAmber
                    }
                    val scanColor = when (bleDiagnostics.scannerState) {
                        DiagnosticComponentState.RUNNING -> CyberEmerald
                        DiagnosticComponentState.FAILED -> CyberCrimson
                        else -> CyberTextSecondary
                    }
                    Text(
                        text = "Advertising: ${bleDiagnostics.advertiseState.labelDe}" +
                            (bleDiagnostics.advertiseErrorDetail?.let { " ($it)" } ?: "") +
                            " • HW-Support: ${if (bleDiagnostics.bleAdvertisingSupported) "JA" else "NEIN"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = advColor,
                    )
                    Text(
                        text = "Scanner: ${bleDiagnostics.scannerState.labelDe}" +
                            (bleDiagnostics.scanErrorDetail?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = scanColor,
                    )
                    Text(
                        text = "Eigene Node-ID: ${bleDiagnostics.ownNodeId} • Gesucht: ${bleDiagnostics.targetNodeId ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "Erkannte Service-UUID: ${bleDiagnostics.detectedServiceUuid ?: "Keine"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                    Text(
                        text = "Erkannte Geräte-IDs: ${bleDiagnostics.detectedNodeIds.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Keine"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bleDiagnostics.detectedNodeIds.isNotEmpty()) CyberEmerald else CyberTextSecondary,
                    )
                    Text(
                        text = "GATT-Status: ${bleDiagnostics.gattStatusText} • Android-Code: ${bleDiagnostics.lastAndroidErrorCode ?: "Keiner"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bleDiagnostics.lastAndroidErrorCode != null) CyberCrimson else CyberTextSecondary,
                    )
                    Text(
                        text = "Auth-Rolle: ${bleDiagnostics.authRole} • Session: ${bleDiagnostics.activeSessionId?.take(8) ?: "—"} • Challenge: ${bleDiagnostics.activeChallengeId?.take(8) ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                    if (bleDiagnostics.challengeTrace.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Challenge-Response Ablauf:",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan,
                        )
                        bleDiagnostics.challengeTrace.forEach { traceLine ->
                            val isErr = traceLine.contains("TIMEOUT") || traceLine.contains("STEP_ERROR")
                            Text(
                                text = traceLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isErr) CyberCrimson else CyberEmerald,
                            )
                        }
                    }
                }
            }
        }

        item {
            RfcommAndStageDiagnosticsCard(
                bleDiagnostics = bleDiagnostics,
                rfcommDiagnostics = rfcommDiagnostics,
                hasBluetoothPermissions = hasBluetoothPermissions,
                onRequestBluetoothPermissions = onRequestBluetoothPermissions,
                onStartRfcommServer = { viewModel.startRfcommServer() },
            )
        }

        item {
            Text(
                text = "GATT-Sessions & Transportstatus (V4.1)",
                style = MaterialTheme.typography.titleMedium,
                color = CyberCyan,
            )
        }

        if (sessions.isEmpty() && discovered.isEmpty()) {
            item {
                EmptyCyberState(
                    icon = Icons.Default.BluetoothSearching,
                    title = "Keine aktiven Bluetooth-Geräte",
                    subtitle = "Starte BLE Scan & GATT oder kopple ein Gerät per QR-Code / 6-stelligem CL2-Code.",
                )
            }
        }

        items(sessions.values.toList(), key = { it.peerNodeId }) { session ->
            val effectiveTransport = viewModel.container.bluetoothManager.getEffectiveTransportForPeer(session.peerNodeId)
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("session_card_${session.peerNodeId}"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.deviceName ?: session.peerNodeId,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Node: ${session.peerNodeId} • Addr: ${session.deviceAddress ?: "—"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextSecondary,
                            )
                        }
                        Text(
                            text = "MTU: ${session.negotiatedMtu?.toString() ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan,
                        )
                    }

                    TransportStatusBadge(
                        state = session.transportState,
                        transport = effectiveTransport,
                    )

                    if (session.lastError != null) {
                        Surface(
                            color = CyberCrimson.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "BT_ERROR: ${session.lastError}" +
                                    (session.lastErrorStatusCode?.let { " (GATT Status $it)" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberCrimson,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectPeer(session.peerNodeId, session.deviceAddress) },
                            modifier = Modifier.testTag("connect_peer_${session.peerNodeId}"),
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (session.transportState == TransportState.ERROR) "Neu verbinden" else "Verbinden")
                        }

                        if (session.transportState == TransportState.CONNECTED) {
                            OutlinedButton(
                                onClick = { viewModel.upgradeToRfcomm(session.peerNodeId) },
                                modifier = Modifier.testTag("rfcomm_upgrade_${session.peerNodeId}"),
                            ) {
                                Text("RFCOMM")
                            }
                            OutlinedButton(
                                onClick = { viewModel.runRfcommBidirectionalTest(session.peerNodeId) },
                                modifier = Modifier.testTag("rfcomm_test_${session.peerNodeId}"),
                            ) {
                                Text("RFCOMM Test")
                            }
                            OutlinedButton(
                                onClick = { viewModel.disconnectPeer(session.peerNodeId) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCrimson),
                                modifier = Modifier.testTag("disconnect_peer_${session.peerNodeId}"),
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = "Trennen")
                            }
                        }
                    }
                }
            }
        }

        if (discovered.isNotEmpty()) {
            item {
                Text(
                    text = "Entdeckte BLE-Geräte (${discovered.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                )
            }
            items(discovered, key = { it.address }) { peer ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Node: ${peer.nodeId ?: "Unbekannt"} • RSSI: ${peer.rssi} dBm • ${peer.address}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextSecondary,
                            )
                        }
                        Button(
                            onClick = {
                                val targetId = peer.nodeId ?: "PEER-${peer.address.replace(":", "")}"
                                viewModel.connectPeer(targetId, peer.address)
                            },
                            modifier = Modifier.testTag("connect_discovered_${peer.address}"),
                        ) {
                            Text("GATT")
                        }
                    }
                }
            }
        }

        val allLogs = viewModel.getAllGattLogs().take(25)
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = CyberCyan)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "GATT-Protokoll (${allLogs.size} Schritte)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                )
            }
        }

        if (allLogs.isEmpty()) {
            item {
                Text(
                    text = "Noch keine GATT-Schritte aufgezeichnet. (Advertising aktiv=$isAdvertising)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary,
                )
            }
        } else {
            items(allLogs) { entry ->
                Text(
                    text = entry.formatLine(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.gattStatusCode != null && entry.gattStatusCode != 0) CyberCrimson else CyberEmerald,
                )
            }
        }
    }

    if (showMyQrDialog) {
        var nowTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(showMyQrDialog) {
            while (showMyQrDialog) {
                nowTickMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
        val remainingSec = ((currentQrPayload.expiresAtMs - nowTickMs) / 1000L).coerceAtLeast(0L)
        val qrJson = remember(currentQrPayload) { PeerManager.encodeQrPayload(currentQrPayload) }
        val qrBitmap: Bitmap? = remember(qrJson) { QrCodeHelper.generateQrBitmap(qrJson, 512) }

        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("Mein CYBER LINK QR-Code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "CYBER LINK Pairing QR Code",
                            modifier = Modifier.size(200.dp),
                        )
                    }
                    Text(
                        text = "${currentQrPayload.protocol} v${currentQrPayload.v} • ${currentQrPayload.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                    )
                    Text("Node-ID: ${currentQrPayload.nodeId}", style = MaterialTheme.typography.labelSmall)
                    Text("Temp-ID: ${currentQrPayload.tempDeviceId}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "BT-Adresse: ${currentQrPayload.btAddress ?: "Von Android verborgen (Datenschutz)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                    Text(
                        text = "CL2-Code: ${currentQrPayload.cl2Code} • Gültig: ${remainingSec}s",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (remainingSec > 0) CyberAmber else CyberCrimson,
                    )
                    Text(
                        text = "Signiertes Einmal-Token (wird nach Nutzung ungültig). Niemals private Schlüssel oder SIM-Daten.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.renewLocalQrPayload() },
                    modifier = Modifier.testTag("renew_qr_btn"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("QR erneuern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMyQrDialog = false }) { Text("Schließen") }
            },
        )
    }

    if (incomingPrompt != null) {
        val prompt = incomingPrompt!!
        AlertDialog(
            onDismissRequest = { viewModel.respondToIncomingPairing(false) },
            title = { Text("Kopplungsanfrage bestätigen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Partnergerät: ${prompt.peerName}", style = MaterialTheme.typography.titleMedium)
                    Text("Node-ID: ${prompt.peerNodeId}", style = MaterialTheme.typography.labelSmall)
                    Text("Temp-ID: ${prompt.tempDeviceId}", style = MaterialTheme.typography.labelSmall)
                    Text("CL2-Prüfcode: ${prompt.cl2Code}", style = MaterialTheme.typography.titleMedium, color = CyberAmber)
                    Text(
                        text = "Nur bestätigen, wenn das andere Smartphone vor dir liegt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.respondToIncomingPairing(true) },
                    modifier = Modifier.testTag("incoming_pairing_accept_btn"),
                ) {
                    Text("Bestätigen")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondToIncomingPairing(false) }) {
                    Text("Ablehnen", color = CyberCrimson)
                }
            },
        )
    }

    if (showQrScannerDialog) {
        QrCameraScannerDialog(
            onQrDetected = { raw ->
                showQrScannerDialog = false
                viewModel.importQrPayload(raw)
            },
            onDismiss = { showQrScannerDialog = false },
        )
    }

    if (showManualCl2Dialog) {
        AlertDialog(
            onDismissRequest = { showManualCl2Dialog = false },
            title = { Text("CL2-Code Pairing (6-stellig)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualNodeId,
                        onValueChange = { manualNodeId = it },
                        label = { Text("Peer Node-ID (z.B. CL-2CDDB959FBEF)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("Anzeigename") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manualCl2,
                        onValueChange = { if (it.length <= 6) manualCl2 = it.filter(Char::isDigit) },
                        label = { Text("6-stelliger CL2-Code") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addManualContactWithCl2(manualNodeId, manualName, manualCl2)
                        showManualCl2Dialog = false
                    }
                ) { Text("Bestätigen") }
            },
            dismissButton = {
                TextButton(onClick = { showManualCl2Dialog = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun QrCameraScannerDialog(
    onQrDetected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var handled by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CYBER LINK QR-Code scannen") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { imgAnalysis ->
                                    imgAnalysis.setAnalyzer(executor) { proxy ->
                                        try {
                                            if (!handled) {
                                                val decoded = QrCodeHelper.decodeQrFromImageProxy(proxy)
                                                if (decoded != null) {
                                                    handled = true
                                                    ContextCompat.getMainExecutor(ctx).execute {
                                                        onQrDetected(decoded)
                                                    }
                                                }
                                            }
                                        } finally {
                                            proxy.close()
                                        }
                                    }
                                }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun RfcommAndStageDiagnosticsCard(
    bleDiagnostics: BleDiagnostics,
    rfcommDiagnostics: RfcommDiagnostics,
    hasBluetoothPermissions: Boolean,
    onRequestBluetoothPermissions: () -> Unit,
    onStartRfcommServer: () -> Unit,
) {
    val stageList = listOf(
        TransportChannelStage.BLE_SCAN to bleDiagnostics.bleScanOk,
        TransportChannelStage.BLE_GATT_CONNECT to bleDiagnostics.bleGattConnectOk,
        TransportChannelStage.BLE_GATT_READY to bleDiagnostics.bleGattReady,
        TransportChannelStage.RFCOMM_SERVER_READY to rfcommDiagnostics.rfcommServerReady,
        TransportChannelStage.RFCOMM_CLIENT_CONNECT to rfcommDiagnostics.rfcommClientConnectOk,
        TransportChannelStage.RFCOMM_SOCKET_CONNECTED to rfcommDiagnostics.rfcommSocketConnected,
        TransportChannelStage.RFCOMM_BIDIRECTIONAL_READY to rfcommDiagnostics.rfcommBidirectionalReady,
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated.copy(alpha = 0.88f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rfcomm_diagnostics_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RFCOMM-Diagnose (Bluetooth Classic)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                )
                if (!rfcommDiagnostics.rfcommServerReady) {
                    OutlinedButton(
                        onClick = {
                            if (hasBluetoothPermissions) onStartRfcommServer() else onRequestBluetoothPermissions()
                        },
                        modifier = Modifier.testTag("rfcomm_server_start_btn"),
                    ) {
                        Text("Server starten", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text(
                text = "Transportstufen (BLE vs. RFCOMM strikt getrennt):",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
            )
            stageList.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pair.forEach { (stage, ok) ->
                        Surface(
                            color = if (ok) CyberEmerald.copy(alpha = 0.16f) else CyberSurfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "${if (ok) "OK" else "—"} ${stage.labelDe}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ok) CyberEmerald else CyberTextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Eigene Node-ID: ${rfcommDiagnostics.ownNodeId} • Peer-Node-ID: ${rfcommDiagnostics.peerNodeId ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "Bluetooth-Gerätename: ${rfcommDiagnostics.bluetoothDeviceName ?: "—"} • Pairing: ${rfcommDiagnostics.pairingStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
            )
            Text(
                text = "RFCOMM Service-UUID: ${rfcommDiagnostics.serviceUuid}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
            )
            val srvColor = when (rfcommDiagnostics.serverSocketStatus) {
                RfcommSocketState.LISTENING, RfcommSocketState.CONNECTED -> CyberEmerald
                RfcommSocketState.FAILED -> CyberCrimson
                else -> CyberAmber
            }
            Text(
                text = "Server-Socket-Status: ${rfcommDiagnostics.serverSocketStatus.labelDe}" +
                    (rfcommDiagnostics.serverErrorDetail?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = srvColor,
            )
            val cliColor = when (rfcommDiagnostics.clientSocketStatus) {
                RfcommSocketState.CONNECTED -> CyberEmerald
                RfcommSocketState.FAILED -> CyberCrimson
                RfcommSocketState.IDLE -> CyberTextSecondary
                else -> CyberAmber
            }
            Text(
                text = "Client-Socket-Status: ${rfcommDiagnostics.clientSocketStatus.labelDe}",
                style = MaterialTheme.typography.labelSmall,
                color = cliColor,
            )
            Text(
                text = "Connect-Fehler: ${rfcommDiagnostics.connectError ?: "Keiner"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (rfcommDiagnostics.connectError != null) CyberCrimson else CyberTextSecondary,
            )
            Text(
                text = "IOException: ${rfcommDiagnostics.ioExceptionDetail ?: "Keine"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (rfcommDiagnostics.ioExceptionDetail != null) CyberCrimson else CyberTextSecondary,
            )
            Text(
                text = "Tatsächlicher Status: ${rfcommDiagnostics.actualConnectionStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = if (rfcommDiagnostics.rfcommBidirectionalReady) CyberEmerald else CyberAmber,
            )
            Text(
                text = "Bidirektionaler Test: ${rfcommDiagnostics.bidirectionalTestStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = if (rfcommDiagnostics.rfcommBidirectionalReady) CyberEmerald else CyberTextSecondary,
            )
            Text(
                text = "RFCOMM Bytes: ${rfcommDiagnostics.bytesSent} B gesendet • ${rfcommDiagnostics.bytesReceived} B empfangen",
                style = MaterialTheme.typography.labelSmall,
                color = CyberCyan,
            )
            if (rfcommDiagnostics.trace.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("RFCOMM-Protokoll:", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                rfcommDiagnostics.trace.takeLast(8).forEach { line ->
                    val isErr = line.contains("FAILED") || line.contains("ERROR") || line.contains("REJECTED")
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isErr) CyberCrimson else CyberEmerald,
                    )
                }
            }
        }
    }
}

