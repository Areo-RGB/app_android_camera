package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DirectorCyan
import com.example.ui.theme.ReadyGreen
import com.example.ui.theme.RecordRed
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioElevatedBg
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HelpConnectionDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed)
            ) {
                Text("Verstanden", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = StudioDarkBg,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = DirectorCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Multi-Kamera Verbindung",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Unterstützt Google Nearby Connections (direkt ohne Router) und lokales WLAN:",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                SetupStepItem(
                    stepNumber = "1",
                    title = "Google Nearby Connections (Empfohlen)",
                    description = "Kein WLAN-Router nötig! Bluetooth & Wi-Fi am Handy aktivieren. Die Geräte verbinden sich via Google Nearby P2P direkt miteinander auf Knopfdruck.",
                    icon = Icons.Default.Sensors,
                    iconColor = DirectorCyan
                )

                Spacer(modifier = Modifier.height(10.dp))

                SetupStepItem(
                    stepNumber = "2",
                    title = "Director auf Hauptgerät starten",
                    description = "Wähle auf dem Master-Smartphone 'Director Mode'. Das Gerät sendet Nearby-Broadcasts und startet den Sync-Server.",
                    icon = Icons.Default.CastConnected,
                    iconColor = RecordRed
                )

                Spacer(modifier = Modifier.height(10.dp))

                SetupStepItem(
                    stepNumber = "3",
                    title = "Kamera-Nodes verbinden",
                    description = "Auf den anderen Handys 'Kamera-Node' wählen. Der Director erscheint automatisch unter 'Google Nearby' oder im lokalen WLAN.",
                    icon = Icons.Default.Videocam,
                    iconColor = ReadyGreen
                )

                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioElevatedBg),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = DirectorCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Der synchrone Countdown mit optischer Klappe und Sync-Signalton synchronisiert alle Kameras auf die Millisekunde genau für einfachen Videoschnitt.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun SetupStepItem(
    stepNumber: String,
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color
) {
    Surface(
        color = StudioCardBg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.2f))
            ) {
                Text(
                    text = stepNumber,
                    color = iconColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
