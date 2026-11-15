package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.TransportState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberCrimson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberOutline
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberTextSecondary

@Composable
fun TacticalHeroHeader(
    nodeId: String,
    cl2Code: String,
    displayName: String,
    activePeerCount: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CyberOutline, RoundedCornerShape(16.dp)),
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_cyber_mesh_banner_1794753115752),
            contentDescription = "CYBER LINK Tactical Offline Mesh Banner",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            CyberObsidian.copy(alpha = 0.92f),
                            CyberObsidian.copy(alpha = 0.65f),
                            CyberObsidian.copy(alpha = 0.85f),
                        )
                    )
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "CYBER LINK V5.0",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    color = if (activePeerCount > 0) CyberEmerald.copy(alpha = 0.2f) else CyberSurfaceElevated,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (activePeerCount > 0) CyberEmerald else CyberOutline,
                    ),
                ) {
                    Text(
                        text = if (activePeerCount > 0) "$activePeerCount VERBUNDEN" else "OFFLINE-NETZ",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activePeerCount > 0) CyberEmerald else CyberTextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Column {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Node: $nodeId",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                    )
                    Text(
                        text = "CL2: $cl2Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberAmber,
                    )
                }
            }
        }
    }
}

@Composable
fun TransportStatusBadge(
    state: TransportState,
    transport: ActiveTransport,
    modifier: Modifier = Modifier,
) {
    val (color, icon) = when (state) {
        TransportState.CONNECTED -> CyberEmerald to Icons.Default.BluetoothConnected
        TransportState.ERROR -> CyberCrimson to Icons.Default.ErrorOutline
        TransportState.AUTHENTICATING,
        TransportState.SERVICE_DISCOVERY,
        TransportState.CONNECTING,
        TransportState.DATA_CHANNEL_READY -> CyberAmber to Icons.Default.Security
        TransportState.BLUETOOTH_FOUND -> CyberCyan to Icons.Default.BluetoothConnected
        TransportState.SAVED,
        TransportState.DISCONNECTED -> CyberTextSecondary to Icons.Default.BluetoothDisabled
    }

    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "${state.labelDe} | Transport: ${transport.labelDe}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyCyberState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = CyberSurfaceElevated.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberOutline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary,
            )
        }
    }
}
