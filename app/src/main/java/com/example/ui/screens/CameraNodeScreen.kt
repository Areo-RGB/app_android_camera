package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraLens
import com.example.network.CameraNodeClient
import com.example.ui.components.CameraPreviewView
import com.example.ui.components.ClapperFlashOverlay
import com.example.ui.components.CountdownOverlay
import com.example.ui.components.StudioTallyBar
import com.example.ui.theme.DirectorCyan
import com.example.ui.theme.ReadyGreen
import com.example.ui.theme.RecordRed
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioElevatedBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.MultiCamViewModel

@Composable
fun CameraNodeScreen(
    viewModel: MultiCamViewModel,
    onBackToHub: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val nodeConnectionState by viewModel.nodeConnectionState.collectAsState()
    val discoveredDirectors by viewModel.discoveredDirectors.collectAsState()
    val discoveredNearbyDirectors by viewModel.discoveredNearbyDirectors.collectAsState()
    val assignedAngle by viewModel.assignedCameraAngle.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val durationSec by viewModel.recordingDurationSec.collectAsState()
    val countdownValue by viewModel.countdownValue.collectAsState()
    val isFlashing by viewModel.isClapperFlashing.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()

    var manualIpInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var isPreviewOpen by remember { mutableStateOf(false) }

    val isConnected = nodeConnectionState is CameraNodeClient.ConnectionState.Connected
    val connectionTitle = (nodeConnectionState as? CameraNodeClient.ConnectionState.Connected)?.let {
        if (it.host == "Google Nearby") "Google Nearby" else "Wi-Fi LAN"
    } ?: "Offline"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioDarkBg)
    ) {
        // Battery Saving Camera Preview (Small and hidden by default)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CameraPreviewView(
                cameraManager = viewModel.cameraManager,
                settings = settings,
                cameraAngle = assignedAngle,
                modifier = if (isPreviewOpen) {
                    Modifier
                        .fillMaxWidth(0.5f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, StudioBorder, RoundedCornerShape(16.dp))
                } else {
                    Modifier.size(1.dp).alpha(0f)
                }
            )

            if (!isPreviewOpen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = "Preview Closed",
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Vorschau pausiert (Batterie sparen)",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Overlay Studio HUD
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top HUD Bar: Back button, Tally Indicator, Director connection pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onBackToHub,
                    modifier = Modifier
                        .size(40.dp)
                        .background(StudioDarkBg.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }

                StudioTallyBar(
                    isRecording = isRecording,
                    durationSec = durationSec,
                    cameraAngle = assignedAngle
                )

                Surface(
                    color = if (isConnected) ReadyGreen.copy(alpha = 0.2f) else StudioDarkBg.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isConnected) ReadyGreen else StudioBorder
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) ReadyGreen else RecordRed)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "SYNCED • $connectionTitle" else "OFFLINE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) ReadyGreen else TextSecondary
                        )
                    }
                }
            }

            // Middle Connection Card (Shown only if NOT connected yet)
            AnimatedVisibility(
                visible = !isConnected,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioDarkBg.copy(alpha = 0.94f)),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .padding(vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = DirectorCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Director verbinden",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                            }

                            IconButton(
                                onClick = { viewModel.refreshDirectorDiscovery() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 1. Discovered Google Nearby Directors (Bluetooth & Wi-Fi Direct P2P)
                        if (discoveredNearbyDirectors.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = DirectorCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Google Nearby Direct (Ohne Router):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DirectorCyan
                                )
                            }

                            discoveredNearbyDirectors.forEach { nearbyDirector ->
                                Surface(
                                    color = StudioElevatedBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, DirectorCyan),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable {
                                            viewModel.connectNodeToNearbyDirector(nearbyDirector)
                                        }
                                        .testTag("connect_nearby_director_${nearbyDirector.endpointId}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = nearbyDirector.endpointName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "Google Nearby • P2P Direct Connect",
                                                fontSize = 11.sp,
                                                color = DirectorCyan
                                            )
                                        }
                                        Surface(
                                            color = DirectorCyan,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "1-Tap Sync",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = StudioDarkBg,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // 2. Discovered Wi-Fi Directors (NSD)
                        if (discoveredDirectors.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = ReadyGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Wi-Fi Netzwerk Directors:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ReadyGreen
                                )
                            }

                            discoveredDirectors.forEach { director ->
                                Surface(
                                    color = StudioElevatedBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable {
                                            viewModel.connectNodeToDirector(director.host, director.port)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = director.serviceName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "${director.host}:${director.port}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextSecondary
                                            )
                                        }
                                        Text(
                                            text = "Verbinden",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = ReadyGreen
                                        )
                                    }
                                }
                            }
                        }

                        if (discoveredNearbyDirectors.isEmpty() && discoveredDirectors.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = DirectorCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Suche Director via Google Nearby & Wi-Fi...",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Manual IP Entry fallback
                        if (showManualInput) {
                            OutlinedTextField(
                                value = manualIpInput,
                                onValueChange = { manualIpInput = it },
                                label = { Text("Director IP (z.B. 192.168.1.50)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DirectorCyan,
                                    unfocusedBorderColor = StudioBorder,
                                    focusedLabelColor = DirectorCyan
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    if (manualIpInput.isNotBlank()) {
                                        viewModel.connectNodeToDirector(manualIpInput.trim())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DirectorCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Direkt verbinden", fontWeight = FontWeight.Bold, color = StudioDarkBg)
                            }
                        } else {
                            Text(
                                text = "Oder Director IP manuell eingeben",
                                fontSize = 12.sp,
                                color = DirectorCyan,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { showManualInput = true }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            // Bottom HUD: Settings Badge & Camera local controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Synced format badge
                Surface(
                    color = StudioDarkBg.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${settings.resolution.substringAfter("_")} • ${settings.fps.substringAfter("_")} FPS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = if (settings.audioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Audio",
                            tint = if (settings.audioEnabled) ReadyGreen else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Quick Local Lens, Torch, and Preview controls
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { isPreviewOpen = !isPreviewOpen },
                        modifier = Modifier
                            .size(44.dp)
                            .background(StudioDarkBg.copy(alpha = 0.75f), CircleShape)
                            .border(1.dp, if (isPreviewOpen) ReadyGreen else StudioBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPreviewOpen) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Preview",
                            tint = if (isPreviewOpen) ReadyGreen else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleTorch() },
                        modifier = Modifier
                            .size(44.dp)
                            .background(StudioDarkBg.copy(alpha = 0.75f), CircleShape)
                            .border(1.dp, if (isTorchOn) ReadyGreen else StudioBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Torch",
                            tint = if (isTorchOn) ReadyGreen else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val next = if (settings.lens == CameraLens.BACK.name) CameraLens.FRONT else CameraLens.BACK
                            viewModel.setLens(next)
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(StudioDarkBg.copy(alpha = 0.75f), CircleShape)
                            .border(1.dp, StudioBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip Lens",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Overlay Sync Animations
        CountdownOverlay(countdownValue = countdownValue)
        ClapperFlashOverlay(isFlashing = isFlashing)
    }
}
