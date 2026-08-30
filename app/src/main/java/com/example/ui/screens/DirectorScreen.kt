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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CameraLens
import com.example.model.VideoFps
import com.example.model.VideoResolution
import com.example.ui.components.CameraNodeCard
import com.example.ui.components.CameraPreviewView
import com.example.ui.components.ClapperFlashOverlay
import com.example.ui.components.CountdownOverlay
import com.example.ui.components.SettingsBottomSheet
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
import java.util.Locale

@Composable
fun DirectorScreen(
    viewModel: MultiCamViewModel,
    onBackToHub: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val connectedNodes by viewModel.connectedNodes.collectAsState()
    val directorIp by viewModel.directorIp.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val durationSec by viewModel.recordingDurationSec.collectAsState()
    val countdownValue by viewModel.countdownValue.collectAsState()
    val isFlashing by viewModel.isClapperFlashing.collectAsState()
    val isDirectorCameraEnabled by viewModel.isDirectorLocalCameraEnabled.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()

    var showSettingsSheet by remember { mutableStateOf(false) }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            settings = settings,
            onSettingsChanged = { viewModel.updateSettings(it) },
            onDismiss = { showSettingsSheet = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioDarkBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Navigation & Scene Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackToHub,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "DIRECTOR",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = RecordRed,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = StudioCardBg,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder)
                            ) {
                                Text(
                                    text = "$directorIp:8989",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = DirectorCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = DirectorCyan.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DirectorCyan.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Nearby P2P",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DirectorCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Scene & Take Indicator
                    Surface(
                        color = StudioElevatedBg,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.clickable { viewModel.nextTake() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${settings.sceneName} • Take ${settings.takeNumber}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Next Take",
                                tint = DirectorCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Director Setting Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Resolution Chip
                FilterChip(
                    selected = true,
                    onClick = {
                        val nextRes = when (settings.resolution) {
                            VideoResolution.UHD_4K.name -> VideoResolution.FHD_1080P
                            VideoResolution.FHD_1080P.name -> VideoResolution.HD_720P
                            else -> VideoResolution.UHD_4K
                        }
                        viewModel.setResolution(nextRes)
                    },
                    label = {
                        Text(
                            text = when (settings.resolution) {
                                VideoResolution.UHD_4K.name -> "4K"
                                VideoResolution.HD_720P.name -> "720p"
                                else -> "1080p"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioElevatedBg,
                        selectedLabelColor = DirectorCyan
                    )
                )

                // FPS Chip
                FilterChip(
                    selected = true,
                    onClick = {
                        val nextFps = when (settings.fps) {
                            VideoFps.FPS_24.name -> VideoFps.FPS_30
                            VideoFps.FPS_30.name -> VideoFps.FPS_60
                            else -> VideoFps.FPS_24
                        }
                        viewModel.setFps(nextFps)
                    },
                    label = {
                        Text(
                            text = when (settings.fps) {
                                VideoFps.FPS_60.name -> "60 FPS"
                                VideoFps.FPS_24.name -> "24 FPS"
                                else -> "30 FPS"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioElevatedBg,
                        selectedLabelColor = DirectorCyan
                    )
                )

                // Audio toggle
                FilterChip(
                    selected = settings.audioEnabled,
                    onClick = { viewModel.toggleAudio() },
                    leadingIcon = {
                        Icon(
                            if (settings.audioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text(if (settings.audioEnabled) "Audio" else "Muted", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioElevatedBg,
                        selectedLabelColor = ReadyGreen
                    )
                )

                // Director local camera toggle
                FilterChip(
                    selected = isDirectorCameraEnabled,
                    onClick = { viewModel.toggleDirectorLocalCamera() },
                    leadingIcon = {
                        Icon(
                            if (isDirectorCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("Director Cam", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioElevatedBg,
                        selectedLabelColor = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Content Area: Director Camera + Connected Nodes
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Director's own local camera preview card (if enabled)
                if (isDirectorCameraEnabled) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioCardBg),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isRecording) RecordRed else StudioBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CameraPreviewView(
                                    cameraManager = viewModel.cameraManager,
                                    settings = settings,
                                    cameraAngle = "CAM 1 (Master)",
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Overlay Tally & Angle Tag
                                StudioTallyBar(
                                    isRecording = isRecording,
                                    durationSec = durationSec,
                                    cameraAngle = "CAM 1 (DIRECTOR)",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(10.dp)
                                )

                                // Camera Controls Overlay
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.toggleTorch() },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(StudioDarkBg.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                            contentDescription = "Torch",
                                            tint = if (isTorchOn) ReadyGreen else TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val nextLens = if (settings.lens == CameraLens.BACK.name) CameraLens.FRONT else CameraLens.BACK
                                            viewModel.setLens(nextLens)
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(StudioDarkBg.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Default.Cameraswitch,
                                            contentDescription = "Flip Lens",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section Title: Connected Remote Cameras
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = "CONNECTED CAMERA NODES (${connectedNodes.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )

                        if (connectedNodes.isNotEmpty()) {
                            Text(
                                text = "All Synced",
                                fontSize = 11.sp,
                                color = ReadyGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // If no remote cameras connected yet, show waiting banner
                if (connectedNodes.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = StudioCardBg),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(DirectorCyan.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sensors,
                                        contentDescription = null,
                                        tint = DirectorCyan,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Warte auf Kameras...",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Öffne die App auf anderen Smartphones im selben WLAN/Hotspot und wähle 'Kamera-Node'. Sie verbinden sich automatisch!",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    lineHeight = 16.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Surface(
                                    color = StudioElevatedBg,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Director IP: $directorIp (Port: 8989)",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = DirectorCyan,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(connectedNodes.values.toList(), key = { it.nodeId }) { node ->
                        CameraNodeCard(node = node)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Studio Record Deck Bar
            Surface(
                color = StudioCardBg,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    if (isRecording) RecordRed else StudioBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(24.dp))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Timecode / Status Info
                    Column {
                        val hours = durationSec / 3600
                        val minutes = (durationSec % 3600) / 60
                        val seconds = durationSec % 60
                        val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

                        Text(
                            text = if (isRecording) timeString else "00:00:00",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = if (isRecording) RecordRed else TextSecondary
                        )

                        Text(
                            text = if (isRecording) "RECORDING ON ALL CAMERAS" else "${connectedNodes.size + (if (isDirectorCameraEnabled) 1 else 0)} CAMERAS READY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) ReadyGreen else TextMuted,
                            letterSpacing = 1.sp
                        )
                    }

                    // Big Record / Stop Button
                    Button(
                        onClick = {
                            if (isRecording) {
                                viewModel.stopMasterRecording()
                            } else {
                                viewModel.startMasterRecording()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) RecordRed else RecordRed
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("director_record_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                                contentDescription = if (isRecording) "Stop" else "Record",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRecording) "STOP ALL" else "START REC",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        // Overlay Sync Animations
        CountdownOverlay(countdownValue = countdownValue)
        ClapperFlashOverlay(isFlashing = isFlashing)
    }
}
