package com.example.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.CameraXManager
import com.example.model.CameraLens
import com.example.model.CameraNodeInfo
import com.example.model.RecordingSettings
import com.example.model.VideoFps
import com.example.model.VideoResolution
import com.example.ui.theme.DirectorCyan
import com.example.ui.theme.ReadyGreen
import com.example.ui.theme.RecordRed
import com.example.ui.theme.StandbyYellow
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioElevatedBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun CameraPreviewView(
    cameraManager: CameraXManager,
    settings: RecordingSettings,
    cameraAngle: String = "CAM_A",
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraManager.bindCamera(lifecycleOwner, this, settings, cameraAngle)
            }
        },
        modifier = modifier
    )
}

@Composable
fun StudioTallyBar(
    isRecording: Boolean,
    durationSec: Long,
    cameraAngle: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "TallyPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TallyPulseAlpha"
    )

    Surface(
        color = if (isRecording) RecordRed.copy(alpha = 0.95f) else StudioElevatedBg.copy(alpha = 0.85f),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isRecording) RecordRed.copy(alpha = pulseAlpha) else StudioBorder
        ),
        modifier = modifier
            .shadow(if (isRecording) 12.dp else 2.dp, RoundedCornerShape(24.dp))
            .testTag("tally_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.White else ReadyGreen)
                    .alpha(if (isRecording) pulseAlpha else 1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (isRecording) "REC  $cameraAngle" else "STANDBY  $cameraAngle",
                color = if (isRecording) Color.White else ReadyGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )

            if (isRecording) {
                Spacer(modifier = Modifier.width(12.dp))
                val hours = durationSec / 3600
                val minutes = (durationSec % 3600) / 60
                val seconds = durationSec % 60
                val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                Text(
                    text = timeString,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun CountdownOverlay(
    countdownValue: Int?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = countdownValue != null,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        countdownValue?.let { value ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .testTag("countdown_overlay")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (value == 0) "ACTION!" else value.toString(),
                        color = if (value == 0) ReadyGreen else RecordRed,
                        fontSize = if (value == 0) 64.sp else 96.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "SYNCHRONIZING ALL CAMERAS",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun ClapperFlashOverlay(
    isFlashing: Boolean,
    modifier: Modifier = Modifier
) {
    if (isFlashing) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White)
        )
    }
}

@Composable
fun CameraNodeCard(
    node: CameraNodeInfo,
    onPingClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isRec = node.isRecording
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRec) StudioElevatedBg else StudioCardBg
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isRec) RecordRed else StudioBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("node_card_${node.nodeId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRec) RecordRed else ReadyGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = node.cameraAngleLabel,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = StudioElevatedBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = node.deviceName,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    color = if (isRec) RecordRed.copy(alpha = 0.2f) else ReadyGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isRec) "RECORDING" else "READY",
                        color = if (isRec) RecordRed else ReadyGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Battery
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (node.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                        contentDescription = "Battery",
                        tint = if (node.batteryPercent < 20) RecordRed else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${node.batteryPercent}%",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                // Storage Free
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = "Storage",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${node.freeStorageGb} GB free",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                // Ping / IP
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Network",
                        tint = DirectorCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (node.pingMs > 0) "${node.pingMs}ms" else node.ipAddress,
                        fontSize = 12.sp,
                        color = DirectorCyan
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: RecordingSettings,
    onSettingsChanged: (RecordingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioDarkBg,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Director Sync Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Resolution
            Text(
                text = "VIDEO RESOLUTION",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DirectorCyan,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VideoResolution.values().forEach { res ->
                    FilterChip(
                        selected = settings.resolution == res.name,
                        onClick = { onSettingsChanged(settings.copy(resolution = res.name)) },
                        label = { Text(res.label.substringBefore(" ")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RecordRed,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Frame Rate FPS
            Text(
                text = "FRAME RATE (FPS)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DirectorCyan,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VideoFps.values().forEach { fps ->
                    FilterChip(
                        selected = settings.fps == fps.name,
                        onClick = { onSettingsChanged(settings.copy(fps = fps.name)) },
                        label = { Text(fps.label.substringBefore(" ")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RecordRed,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Countdown Seconds
            Text(
                text = "SYNC COUNTDOWN: ${settings.countdownSeconds} SECONDS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DirectorCyan,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = settings.countdownSeconds.toFloat(),
                onValueChange = { onSettingsChanged(settings.copy(countdownSeconds = it.toInt())) },
                valueRange = 0f..10f,
                steps = 9
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Toggles
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Audio toggle
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.audioEnabled) StudioElevatedBg else StudioCardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSettingsChanged(settings.copy(audioEnabled = !settings.audioEnabled)) }
                        .padding(end = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = if (settings.audioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Audio",
                            tint = if (settings.audioEnabled) ReadyGreen else TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (settings.audioEnabled) "Audio ON" else "Muted",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Clapper Beep toggle
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.clapperSyncBeep) StudioElevatedBg else StudioCardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSettingsChanged(settings.copy(clapperSyncBeep = !settings.clapperSyncBeep)) }
                        .padding(start = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Clapper Beep",
                            tint = if (settings.clapperSyncBeep) DirectorCyan else TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (settings.clapperSyncBeep) "Sync Tone ON" else "Tone OFF",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
