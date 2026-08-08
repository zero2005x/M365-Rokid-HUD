package com.m365bleapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.ui.theme.*

// ============================================================
// M365 Rokid HUD - Original UI Components
// Custom designs - Zero external dependencies
// All designs are original work by the project author
// ============================================================

/**
 * Original Speed Display Component
 * Uses a clean arc design, not copied from any existing design
 */
@Composable
fun M365SpeedDisplay(
    currentSpeed: Float,
    maximumSpeed: Float = 25f,
    modifier: Modifier = Modifier,
    displaySize: Dp = 200.dp
) {
    val speedRatio by animateFloatAsState(
        // Guard the division: 0/0 is NaN, and NaN.coerceIn(0f, 1f) returns NaN
        // (every comparison with NaN is false), which propagated into the sweep
        // angle and made the progress arc vanish.
        targetValue = if (maximumSpeed > 0f && currentSpeed.isFinite() && maximumSpeed.isFinite()) {
            (currentSpeed / maximumSpeed).coerceIn(0f, 1f)
        } else {
            0f
        },
        animationSpec = tween(250),
        label = "speed_ratio"
    )
    
    val arcColor = when {
        currentSpeed > maximumSpeed * 0.9f -> DangerRed
        currentSpeed > maximumSpeed * 0.7f -> CautionAmber
        else -> ScooterCyan
    }
    
    Box(
        modifier = modifier.size(displaySize),
        contentAlignment = Alignment.Center
    ) {
        // Draw arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeThickness = 10.dp.toPx()
            val arcRadius = (size.minDimension - strokeThickness) / 2
            val topLeftOffset = Offset(
                (size.width - arcRadius * 2) / 2,
                (size.height - arcRadius * 2) / 2
            )
            
            // Background arc (220 degree range, starting at 160 degrees)
            drawArc(
                color = Color.Gray.copy(alpha = 0.2f),
                startAngle = 160f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = topLeftOffset,
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = strokeThickness, cap = StrokeCap.Round)
            )
            
            // Progress arc
            if (speedRatio > 0.01f) {
                drawArc(
                    color = arcColor,
                    startAngle = 160f,
                    sweepAngle = 220f * speedRatio,
                    useCenter = false,
                    topLeft = topLeftOffset,
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = strokeThickness, cap = StrokeCap.Round)
                )
            }
        }
        
        // Speed digits
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.1f".format(currentSpeed),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleMedium,
                color = arcColor
            )
        }
    }
}

/**
 * Original Battery Bar Component
 * Horizontal gradient bar design
 */
@Composable
fun M365BatteryBar(
    batteryLevel: Int,
    label: String = "",
    modifier: Modifier = Modifier
) {
    val levelRatio by animateFloatAsState(
        targetValue = batteryLevel.coerceIn(0, 100) / 100f,
        animationSpec = tween(400),
        label = "battery_level"
    )
    
    val barColor = when {
        batteryLevel <= 10 -> DangerRed
        batteryLevel <= 25 -> CautionAmber
        else -> EnergyGreen
    }
    
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$batteryLevel%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = barColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Gray.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(levelRatio)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                barColor.copy(alpha = 0.7f),
                                barColor
                            )
                        )
                    )
            )
        }
    }
}

/**
 * Original Info Tile Component
 */
@Composable
fun M365InfoTile(
    title: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Original Connection Status Badge
 */
@Composable
fun M365ConnectionBadge(
    connected: Boolean,
    deviceLabel: String? = null,
    rssiValue: Int? = null,
    modifier: Modifier = Modifier
) {
    val badgeColor = if (connected) EnergyGreen else DangerRed
    val statusText = if (connected) {
        deviceLabel ?: "Connected"
    } else {
        "Disconnected"
    }
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = badgeColor.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
            )
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor
            )
            
            // RSSI signal strength (optional)
            rssiValue?.let { rssi ->
                val signalBars = when {
                    rssi >= -60 -> 4
                    rssi >= -70 -> 3
                    rssi >= -80 -> 2
                    else -> 1
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    repeat(4) { idx ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(((idx + 1) * 4).dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (idx < signalBars) badgeColor 
                                    else Color.Gray.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Original Action Button Component
 */
@Composable
fun M365ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    icon: @Composable (() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            disabledContainerColor = buttonColor.copy(alpha = 0.4f)
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                icon?.invoke()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Original Alert Banner Component
 */
@Composable
fun M365AlertBanner(
    message: String,
    alertType: AlertType = AlertType.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (bgColor, contentColor) = when (alertType) {
        AlertType.INFO -> Pair(ScooterCyan.copy(alpha = 0.1f), ScooterCyanDark)
        AlertType.WARNING -> Pair(CautionAmber.copy(alpha = 0.1f), CautionAmberDark)
        AlertType.ERROR -> Pair(DangerRed.copy(alpha = 0.1f), DangerRedDark)
        AlertType.SUCCESS -> Pair(EnergyGreen.copy(alpha = 0.1f), EnergyGreenDark)
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor
                    )
                }
            }
        }
    }
}

enum class AlertType {
    INFO, WARNING, ERROR, SUCCESS
}
