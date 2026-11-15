package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetooth.TransportState
import com.example.ui.components.EmptyCyberState
import com.example.ui.components.TransportStatusBadge
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary
import com.example.viewmodel.CyberLinkViewModel
import java.io.File

@Composable
fun ChatsContactsScreen(
    viewModel: CyberLinkViewModel,
    modifier: Modifier = Modifier,
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val messages by viewModel.recentMessages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val selectedPeerId by viewModel.selectedPeerId.collectAsStateWithLifecycle()
    val fileTransfers by viewModel.fileTransfers.collectAsStateWithLifecycle()

    var messageInput by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("NORMAL") }
    var groupIdInput by remember { mutableStateOf("") }

    val activePeerId = selectedPeerId ?: contacts.firstOrNull()?.nodeId
    val existingGroups = remember(messages) {
        messages.mapNotNull { it.groupId }.distinct()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && activePeerId != null) {
            viewModel.sendMediaFile(
                peerNodeId = activePeerId,
                uri = uri,
                isImage = true,
                priority = priority,
                groupId = groupIdInput.ifBlank { null },
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && activePeerId != null) {
            viewModel.sendMediaFile(
                peerNodeId = activePeerId,
                uri = uri,
                isImage = false,
                priority = priority,
                groupId = groupIdInput.ifBlank { null },
            )
        }
    }

    val peerMessages = remember(messages, activePeerId, groupIdInput) {
        if (activePeerId == null) {
            emptyList()
        } else if (groupIdInput.isNotBlank()) {
            messages.filter { it.groupId == groupIdInput.trim() }
        } else {
            messages.filter { (it.peerNodeId == activePeerId || it.senderNodeId == activePeerId) && it.groupId == null }
        }
    }

    LaunchedEffect(activePeerId, peerMessages.size) {
        if (activePeerId != null) {
            viewModel.markChatMessagesRead(activePeerId)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Bestätigte Kontakte (${contacts.size})",
                style = MaterialTheme.typography.titleLarge,
                color = CyberCyan,
            )
        }

        if (contacts.isEmpty()) {
            item {
                EmptyCyberState(
                    icon = Icons.Default.Person,
                    title = "Keine Kontakte gespeichert",
                    subtitle = "Nutze QR-Code oder CL2-Code im Netz-Tab, um echte Kontakte zu koppeln.",
                )
            }
        }

        items(contacts, key = { it.nodeId }) { contact ->
            val session = sessions[contact.nodeId]
            val state = session?.transportState ?: TransportState.SAVED
            val transport = viewModel.container.bluetoothManager.getEffectiveTransportForPeer(contact.nodeId)
            val isSelected = activePeerId == contact.nodeId

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) CyberCyan.copy(alpha = 0.12f) else CyberSurfaceElevated,
                ),
                onClick = { viewModel.selectPeer(contact.nodeId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contact_card_${contact.nodeId}"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${contact.nodeId} • CL2: ${contact.cl2Code}" +
                                    if (contact.blocked) " • BLOCKIERT" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (contact.blocked) CyberCrimson else CyberTextSecondary,
                            )
                        }
                        Row {
                            IconButton(onClick = { viewModel.toggleFavoriteContact(contact) }) {
                                Icon(
                                    imageVector = if (contact.favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Favorit",
                                    tint = if (contact.favorite) CyberAmber else CyberTextSecondary,
                                )
                            }
                            IconButton(onClick = { viewModel.toggleBlockContact(contact) }) {
                                Icon(
                                    imageVector = Icons.Default.Block,
                                    contentDescription = "Blockieren",
                                    tint = if (contact.blocked) CyberCrimson else CyberTextSecondary,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteContact(contact.nodeId) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Kontakt löschen",
                                    tint = CyberTextSecondary,
                                )
                            }
                        }
                    }

                    TransportStatusBadge(state = state, transport = transport)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.connectPeer(contact.nodeId) }) {
                            Text("Verbinden")
                        }
                        OutlinedButton(onClick = { viewModel.flushQueuedMessages(contact.nodeId) }) {
                            Text("Warteschlange senden")
                        }
                    }
                }
            }
        }

        if (activePeerId != null) {
            val activeSessionState = sessions[activePeerId]?.transportState
            if (activeSessionState == TransportState.DISCONNECTED || activeSessionState == TransportState.ERROR) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CyberAmber.copy(alpha = 0.14f))) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Verbindung unterbrochen — Nachrichten werden lokal verschlüsselt eingereiht.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberAmber,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { viewModel.attemptAutoReconnect(activePeerId) }) {
                                Text("Neu verbinden")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (groupIdInput.isNotBlank()) {
                        "Gruppenchat '$groupIdInput' (AES-256-GCM)"
                    } else {
                        "Verschlüsselter Chat mit $activePeerId"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                )
            }

            if (existingGroups.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = groupIdInput.isBlank(),
                                onClick = { groupIdInput = "" },
                                label = { Text("1:1 Einzelchat") },
                            )
                        }
                        items(existingGroups) { gid ->
                            FilterChip(
                                selected = groupIdInput == gid,
                                onClick = { groupIdInput = gid },
                                label = { Text("Gruppe: $gid") },
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HIGH", "NORMAL", "LOW").forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p) },
                        )
                    }
                }
            }

            if (fileTransfers.isNotEmpty()) {
                items(fileTransfers.values.toList(), key = { it.transferId }) { transfer ->
                    Card(colors = CardDefaults.cardColors(containerColor = CyberSurfaceElevated)) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Bluetooth-Transfer: ${transfer.fileName} — ${transfer.stateLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan,
                            )
                            LinearProgressIndicator(
                                progress = { transfer.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            items(peerMessages.reversed(), key = { it.messageId }) { msg ->
                val isMine = msg.senderNodeId == viewModel.localNodeId
                val statusLabel = when (msg.status) {
                    "QUEUED" -> "Warteschlange (Offline)"
                    "SENT" -> "Gesendet"
                    "DELIVERED" -> "Zugestellt ✓"
                    "READ" -> "Empfangsbestätigt / Gelesen ✓✓"
                    "FAILED" -> "Fehlgeschlagen"
                    else -> msg.status
                }
                val statusColor = when (msg.status) {
                    "READ", "DELIVERED" -> CyberEmerald
                    "FAILED" -> CyberCrimson
                    else -> CyberAmber
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMine) CyberCyan.copy(alpha = 0.14f) else CyberSurfaceElevated,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${if (isMine) "ICH" else msg.senderNodeId} • ${msg.messageType} • ${msg.priority}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberCyan,
                        )
                        if (msg.messageType == "IMAGE" && msg.attachmentPath != null) {
                            val bmp = remember(msg.attachmentPath) {
                                val f = File(msg.attachmentPath)
                                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = msg.content,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                        Text(msg.content, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Status: $statusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = groupIdInput,
                    onValueChange = { groupIdInput = it },
                    label = { Text("Optionale Gruppen-ID (leer = Einzelchat)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        label = { Text("Nachricht eingeben…") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                    )
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Bild über Photo Picker senden")
                    }
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Datei über SAF senden")
                    }
                    Button(
                        onClick = {
                            viewModel.sendTextMessage(
                                peerNodeId = activePeerId,
                                text = messageInput,
                                priority = priority,
                                groupId = groupIdInput.ifBlank { null },
                            )
                            messageInput = ""
                        },
                        modifier = Modifier.testTag("send_message_btn"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                    }
                }
            }
        }
    }
}
