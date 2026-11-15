package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GroupOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetooth.ActiveTransport
import com.example.media.AudioCallManager
import com.example.media.VideoCallManager
import com.example.ui.components.TransportStatusBadge
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel
import java.util.concurrent.Executors

@Composable
fun CallsScreen(
    viewModel: CyberLinkViewModel,
    hasMicPermission: Boolean,
    hasCameraPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selectedPeerId by viewModel.selectedPeerId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val audioState by viewModel.audioCallState.collectAsStateWithLifecycle()
    val videoState by viewModel.videoCallState.collectAsStateWithLifecycle()

    val targetPeerId = selectedPeerId ?: contacts.firstOrNull()?.nodeId
    val session = targetPeerId?.let { sessions[it] }
    val transport = targetPeerId?.let { viewModel.container.bluetoothManager.getEffectiveTransportForPeer(it) }
        ?: ActiveTransport.NONE
    val rfcommReady = transport == ActiveTransport.CLASSIC_RFCOMM || transport == ActiveTransport.DUAL_BLE_RFCOMM

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Audio- & Videoanrufe (Bluetooth RFCOMM)",
                style = MaterialTheme.typography.titleLarge,
                color = CyberCyan,
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Zielkontakt: ${targetPeerId ?: "Kein Kontakt ausgewählt"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (session != null) {
                        TransportStatusBadge(state = session.transportState, transport = transport)
                    } else {
                        Text(
                            text = "Kein aktiver Transport. Verbinde zuerst einen Kontakt und starte RFCOMM.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary,
                        )
                    }
                }
            }
        }

        // BLUETOOTH TRANSPORT CAPABILITY CHECK CARD
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberCyan.copy(alpha = 0.09f))) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Bluetooth-Transportprüfung (Hardware-Realität)",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberCyan,
                    )
                    Text(
                        text = AudioCallManager.TRANSPORT_CAPABILITY_SUMMARY_DE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                    )
                }
            }
        }

        // AUDIO CALL CARD
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Audioanruf (AudioRecord / AudioTrack)", style = MaterialTheme.typography.titleMedium, color = CyberEmerald)
                    Text("Status: ${audioState.status.labelDe} — ${audioState.statusDetail}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Qualität: ${audioState.qualityLabel} • Bitrate: ${"%.1f".format(audioState.bitrateKbps)} kbps • Verlust: ${"%.1f".format(audioState.lossRatePct)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                    )
                    Text(
                        text = "Pakete Gesendet: ${audioState.packetsSent} • Empfangen: ${audioState.packetsReceived}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary,
                    )
                    if (!hasMicPermission) {
                        OutlinedButton(onClick = onRequestMicPermission) {
                            Text("Mikrofonberechtigung erteilen")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (audioState.status == AudioCallManager.CallStatus.RINGING_INCOMING) {
                            Button(
                                onClick = { viewModel.acceptAudioCall(hasMicPermission) },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Annehmen")
                            }
                            Button(
                                onClick = { viewModel.hangupAudioCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Ablehnen")
                            }
                        } else if (audioState.status == AudioCallManager.CallStatus.ACTIVE ||
                            audioState.status == AudioCallManager.CallStatus.RINGING_OUTGOING ||
                            audioState.status == AudioCallManager.CallStatus.RECONNECTING
                        ) {
                            OutlinedButton(onClick = { viewModel.container.audioCallManager.toggleMute() }) {
                                Icon(if (audioState.isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Stumm")
                            }
                            OutlinedButton(onClick = { viewModel.container.audioCallManager.toggleSpeaker() }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Lautsprecher")
                            }
                            Button(
                                onClick = { viewModel.hangupAudioCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Beenden")
                                Spacer(Modifier.width(4.dp))
                                Text("Beenden")
                            }
                        } else {
                            Button(
                                enabled = targetPeerId != null && rfcommReady,
                                onClick = {
                                    if (!hasMicPermission) onRequestMicPermission()
                                    else if (targetPeerId != null) viewModel.startAudioCall(targetPeerId, true)
                                },
                                modifier = Modifier.testTag("start_audio_call_btn"),
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (rfcommReady) "Audioanruf starten" else "NICHT UNTERSTÜTZT (RFCOMM fehlt)")
                            }
                        }
                    }
                }
            }
        }

        // VIDEO CALL CARD
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Videoanruf (CameraX -> RFCOMM)", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                    Text(
                        text = "Status: ${videoState.status.labelDe} | FPS: ${"%.1f".format(videoState.measuredFps)} | Durchsatz: ${"%.1f".format(videoState.measuredKbps)} kbps | Qualität: ${videoState.adaptiveQuality}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (videoState.status == VideoCallManager.VideoCallStatus.TOO_SLOW) CyberAmber else CyberTextSecondary,
                    )
                    Text(videoState.statusDetail, style = MaterialTheme.typography.bodyMedium)

                    if (!hasCameraPermission) {
                        OutlinedButton(onClick = onRequestCameraPermission) {
                            Text("Kameraberechtigung erteilen")
                        }
                    }

                    if (videoState.status == VideoCallManager.VideoCallStatus.RINGING_INCOMING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (!hasCameraPermission) onRequestCameraPermission()
                                    else viewModel.acceptVideoCall(true)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald),
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Video annehmen")
                            }
                            Button(
                                onClick = { viewModel.endVideoCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Ablehnen")
                            }
                        }
                    } else if (videoState.status == VideoCallManager.VideoCallStatus.RINGING_OUTGOING) {
                        Button(
                            onClick = { viewModel.endVideoCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Videoanfrage abbrechen")
                        }
                    } else if (videoState.status == VideoCallManager.VideoCallStatus.ACTIVE && hasCameraPermission) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Local CameraX preview + frame encoder
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            ) {
                                VideoCameraXPreview(
                                    useFront = videoState.useFrontCamera,
                                    onFrame = { viewModel.container.videoCallManager.processCameraFrame(it) },
                                )
                            }
                            // Remote decoded frame
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                val remote = videoState.remoteFrame
                                if (remote != null) {
                                    Image(
                                        bitmap = remote.asImageBitmap(),
                                        contentDescription = "Remote Video Frame",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text("Warte auf Remote-Video…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.container.videoCallManager.switchCamera() }) {
                                Icon(Icons.Default.Cameraswitch, contentDescription = "Kamera wechseln")
                            }
                            OutlinedButton(onClick = { viewModel.container.videoCallManager.toggleCamera() }) {
                                Icon(
                                    if (videoState.cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = "Kamera an/aus",
                                )
                            }
                            OutlinedButton(onClick = { viewModel.container.audioCallManager.toggleSpeaker() }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Lautsprecher")
                            }
                            Button(
                                onClick = { viewModel.endVideoCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCrimson),
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Beenden")
                                Spacer(Modifier.width(4.dp))
                                Text("Beenden")
                            }
                        }
                    } else {
                        Button(
                            enabled = targetPeerId != null && rfcommReady,
                            onClick = {
                                if (!hasCameraPermission) onRequestCameraPermission()
                                else if (targetPeerId != null) viewModel.startVideoCall(targetPeerId, true)
                            },
                            modifier = Modifier.testTag("start_video_call_btn"),
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (rfcommReady) "Videoanruf starten" else "NICHT UNTERSTÜTZT (RFCOMM fehlt)")
                        }
                    }
                }
            }
        }

        // GROUP CALLS HONEST CAPABILITY NOTICE (V4.0 Section 7)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CyberCrimson.copy(alpha = 0.12f))) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.GroupOff, contentDescription = null, tint = CyberCrimson)
                    Column {
                        Text(
                            text = "Gruppenanrufe (3+ Smartphones): NICHT UNTERSTÜTZT",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberCrimson,
                        )
                        Text(
                            text = "Echtzeit-Audio/Video-Multicast über gleichzeitige Bluetooth P2P-Scatternets wird auf Standard-Android-Hardware nicht zuverlässig unterstützt und wird daher nicht simuliert. Text-Gruppenchats über Mesh Store-and-Forward bleiben verfügbar.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCameraXPreview(
    useFront: Boolean,
    onFrame: (androidx.camera.core.ImageProxy) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { proxy ->
                            try {
                                onFrame(proxy)
                            } finally {
                                proxy.close()
                            }
                        }
                    }
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}
