package com.m365hud.glass

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * HUD Screen for Rokid Glasses
 * 
 * Layout optimized for glasses display:
 * - Left: Time + Phone Battery
 * - Center: Speed (large)
 * - Right: Scooter Battery + Connection Status
 * 
 * Note: Rokid glasses use a prism/mirror system to project the display.
 * The content may need to be horizontally mirrored (scaleX = -1) depending on
 * the specific Rokid model. Set MIRROR_FOR_ROKID = true if text appears reversed.
 */

// Set this to true if the display appears mirrored/reversed on your Rokid glasses
private const val MIRROR_FOR_ROKID = false
@Composable
fun HudScreen(
    telemetry: TelemetryData,
    timeData: TimeData,
    connectionState: BleClient.ConnectionState,
    onRetryClick: () -> Unit = {}
) {
    // Colors optimized for glasses display (bright on dark)
    val backgroundColor = Color(0xFF000000)
    val primaryColor = Color(0xFF00FF88)  // Bright green for speed
    val secondaryColor = Color(0xFFFFFFFF)
    val warningColor = Color(0xFFFFAA00)
    val errorColor = Color(0xFFFF4444)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Mirror horizontally for Rokid glasses prism display if needed
                scaleX = if (MIRROR_FOR_ROKID) -1f else 1f
            }
            .background(backgroundColor)
            .padding(start = 16.dp, end = 16.dp, top = 285.dp, bottom = 16.dp)  // Extra top padding to push content down for Rokid glasses
    ) {
        when (connectionState) {
            is BleClient.ConnectionState.Disconnected,
            is BleClient.ConnectionState.Error -> {
                DisconnectedView(
                    connectionState = connectionState,
                    onRetryClick = onRetryClick,
                    textColor = secondaryColor,
                    errorColor = errorColor
                )
            }
            is BleClient.ConnectionState.Scanning,
            is BleClient.ConnectionState.Connecting -> {
                ConnectingView(
                    isScanning = connectionState is BleClient.ConnectionState.Scanning,
                    textColor = secondaryColor
                )
            }
            is BleClient.ConnectionState.Connected -> {
                ConnectedHudView(
                    telemetry = telemetry,
                    timeData = timeData,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    warningColor = warningColor
                )
            }
        }
    }
}

@Composable
private fun DisconnectedView(
    connectionState: BleClient.ConnectionState,
    onRetryClick: () -> Unit,
    textColor: Color,
    errorColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚡ M365 HUD",
            color = textColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (connectionState is BleClient.ConnectionState.Error) {
            Text(
                text = connectionState.message,
                color = errorColor,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Button(
            onClick = onRetryClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AA66))
        ) {
            Text("Scan for Gateway", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ConnectingView(
    isScanning: Boolean,
    textColor: Color
) {
    // Pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isScanning) "🔍 Scanning..." else "🔗 Connecting...",
            color = textColor.copy(alpha = alpha),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        CircularProgressIndicator(
            color = Color(0xFF00FF88),
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun ConnectedHudView(
    telemetry: TelemetryData,
    timeData: TimeData,
    primaryColor: Color,
    secondaryColor: Color,
    warningColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Time & Phone Battery
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.weight(1f)
        ) {
            // Current Time
            Text(
                text = if (timeData.hour > 0 || timeData.minute > 0) {
                    timeData.formatTime()
                } else {
                    // Fallback to system time
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                },
                color = secondaryColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Phone Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📱",
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                BatteryIndicator(
                    percentage = timeData.phoneBattery,
                    warningColor = warningColor,
                    normalColor = secondaryColor,
                    size = 18
                )
            }
        }
        
        // Center: Speed (Large)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(2f)
        ) {
            // Speed value
            Text(
                text = "%.1f".format(telemetry.speedKmh),
                color = primaryColor,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            // Unit
            Text(
                text = "km/h",
                color = secondaryColor.copy(alpha = 0.7f),
                fontSize = 20.sp
            )
            
            // Scooter connection indicator
            if (telemetry.connectionState != GattProfile.STATE_READY) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (telemetry.connectionState) {
                        GattProfile.STATE_CONNECTING -> "⏳ Scooter Connecting"
                        else -> "❌ Scooter Offline"
                    },
                    color = warningColor,
                    fontSize = 12.sp
                )
            }
        }
        
        // Right: Scooter Battery
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.weight(1f)
        ) {
            // Scooter Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🛴",
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                BatteryIndicator(
                    percentage = telemetry.scooterBattery,
                    warningColor = warningColor,
                    normalColor = primaryColor,
                    size = 32
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Trip info (if riding)
            if (telemetry.tripMeters > 0 || telemetry.tripSeconds > 0) {
                Text(
                    text = telemetry.formatTripDistance(),
                    color = secondaryColor.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
                Text(
                    text = telemetry.formatTripTime(),
                    color = secondaryColor.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun BatteryIndicator(
    percentage: Int,
    warningColor: Color,
    normalColor: Color,
    size: Int
) {
    val color = when {
        percentage <= 15 -> Color.Red
        percentage <= 30 -> warningColor
        else -> normalColor
    }
    
    Text(
        text = "${percentage}%",
        color = color,
        fontSize = size.sp,
        fontWeight = if (percentage <= 15) FontWeight.Bold else FontWeight.Normal
    )
}
