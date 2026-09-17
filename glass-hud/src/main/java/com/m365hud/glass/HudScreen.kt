package com.m365hud.glass

import android.content.Context
import android.os.BatteryManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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

/**
 * Get the glasses battery level
 */
private fun getGlassesBatteryLevel(context: Context): Int {
    // getSystemService can return null on some builds, and
    // BATTERY_PROPERTY_CAPACITY returns -1 when the value is unknown — the hard
    // cast crashed and -1 rendered as "-1%".
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        ?: return 0
    val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (capacity in 0..100) capacity else 0
}

@Composable
fun HudScreen(
    telemetry: TelemetryData,
    timeData: TimeData,
    connectionState: BleClient.ConnectionState,
    signalStrength: BleClient.SignalStrength = BleClient.SignalStrength.Good,
    isTelemetryFresh: Boolean = true,
    displayPrefs: DisplayPrefs = DisplayPrefs(),
    onRetryClick: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // Glasses battery level - refresh every 30 seconds
    var glassesBattery by remember { mutableIntStateOf(getGlassesBatteryLevel(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            glassesBattery = getGlassesBatteryLevel(context)
            delay(30_000L) // Refresh every 30 seconds
        }
    }
    // Colors optimised for the glasses display (bright on dark).
    //
    // The accent is cyan, matching the phone app's `AccentCyan`, so the two
    // halves of the product read as one thing. It used to be a bright green
    // here and cyan there.
    val backgroundColor = Color(0xFF000000)
    val primaryColor = Color(0xFF22D3EE)  // accent, matches phone AccentCyan
    val secondaryColor = Color(0xFFFFFFFF)
    val warningColor = Color(0xFFFFAA00)
    val errorColor = Color(0xFFFF4444)

    // Rider-selected text scale (100 = normal). Applied to every size below so
    // the whole layout grows together instead of one element outgrowing its
    // column.
    val scale = displayPrefs.textScalePercent / 100f
    fun sz(base: Float) = (base * scale).sp
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Mirror horizontally for Rokid glasses prism display if needed
                scaleX = if (MIRROR_FOR_ROKID) -1f else 1f
            }
            .background(backgroundColor)
            .padding(start = 8.dp, end = 8.dp, top = 285.dp, bottom = 16.dp)  // Reduced horizontal padding for Rokid glasses
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
                    glassesBattery = glassesBattery,
                    signalStrength = signalStrength,
                    isTelemetryFresh = isTelemetryFresh,
                    displayPrefs = displayPrefs,
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
        verticalArrangement = Arrangement.SpaceEvenly  // Changed to SpaceEvenly for better distribution
    ) {
        Text(
            text = "⚡ M365 HUD",
            color = textColor,
            fontSize = 20.sp,  // Reduced from 28.sp for glasses display
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        
        if (connectionState is BleClient.ConnectionState.Error) {
            Text(
                text = connectionState.message,
                color = errorColor,
                fontSize = 12.sp,  // Reduced from 16.sp
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
        
        Button(
            onClick = onRetryClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00AA66)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)  // Smaller button
        ) {
            Text("Scan", fontSize = 14.sp)  // Shortened text and reduced font
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
        verticalArrangement = Arrangement.SpaceEvenly  // Changed for better distribution
    ) {
        Text(
            text = if (isScanning) "🔍 Scanning..." else "🔗 Connecting...",
            color = textColor.copy(alpha = alpha),
            fontSize = 18.sp,  // Reduced from 24.sp for glasses display
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        
        CircularProgressIndicator(
            color = Color(0xFF00FF88),
            modifier = Modifier.size(32.dp)  // Reduced from 48.dp
        )
    }
}

@Composable
private fun ConnectedHudView(
    telemetry: TelemetryData,
    timeData: TimeData,
    glassesBattery: Int,
    signalStrength: BleClient.SignalStrength,
    isTelemetryFresh: Boolean,
    displayPrefs: DisplayPrefs,
    primaryColor: Color,
    secondaryColor: Color,
    warningColor: Color
) {
    // Warning color for stale data or weak signal
    val staleWarningColor = Color(0xFFFF6600)

    // Rider-selected text scale. Each element scales with the rest so the
    // three columns stay visually balanced; see HudScreen for why.
    val scale = displayPrefs.textScalePercent / 100f

    /** Choose a battery colour by level. Reserved for level, never decoration. */
    fun levelColor(percent: Int, normal: Color) = when {
        percent <= 15 -> Color.Red
        percent <= 30 -> warningColor
        else -> normal
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- Left column: time, phone battery, glasses battery, link quality.
        // Each row is independently switchable, so a rider who only wants the
        // clock gets only the clock.
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.weight(1.2f)
        ) {
            if (displayPrefs.shows(DisplayField.TIME)) {
                Text(
                    text = if (timeData.hour > 0 || timeData.minute > 0) {
                        timeData.formatTime()
                    } else {
                        // Fallback to system time
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    },
                    color = secondaryColor,
                    fontSize = (22f * scale).sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (displayPrefs.shows(DisplayField.PHONE_BATTERY)) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(text = "📱", fontSize = (14f * scale).sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${timeData.phoneBattery}%",
                        color = levelColor(timeData.phoneBattery, secondaryColor),
                        fontSize = (16f * scale).sp,
                        fontWeight = if (timeData.phoneBattery <= 15) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (displayPrefs.shows(DisplayField.GLASSES_BATTERY)) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(text = "👓", fontSize = (14f * scale).sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${glassesBattery}%",
                        color = levelColor(glassesBattery, secondaryColor),
                        fontSize = (16f * scale).sp,
                        fontWeight = if (glassesBattery <= 15) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (displayPrefs.shows(DisplayField.TEMPERATURE)) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "%.0f°C".format(telemetry.temperatureC),
                    color = secondaryColor.copy(alpha = 0.8f),
                    fontSize = (14f * scale).sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (displayPrefs.shows(DisplayField.SIGNAL_QUALITY)) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    val (signalIcon, signalColor) = when (signalStrength) {
                        BleClient.SignalStrength.Good -> "📶" to Color(0xFF34D399)
                        BleClient.SignalStrength.Weak -> "📶" to warningColor
                        BleClient.SignalStrength.Poor -> "📵" to Color.Red
                    }
                    Text(
                        text = signalIcon,
                        fontSize = (12f * scale).sp,
                        color = signalColor
                    )

                    if (!isTelemetryFresh) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⚠",
                            fontSize = (12f * scale).sp,
                            color = staleWarningColor
                        )
                    }
                }
            }
        }

        // ---- Centre column: speed, the hero element.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(2f)
        ) {
            if (displayPrefs.shows(DisplayField.SPEED)) {
                Text(
                    text = "%.1f".format(telemetry.speedKmh),
                    color = primaryColor,
                    fontSize = (48f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "km/h",
                    color = secondaryColor.copy(alpha = 0.7f),
                    fontSize = (16f * scale).sp,
                    maxLines = 1
                )
            }

            if (displayPrefs.shows(DisplayField.AVG_SPEED)) {
                Text(
                    text = "avg %.1f".format(telemetry.avgSpeedKmh),
                    color = secondaryColor.copy(alpha = 0.7f),
                    fontSize = (14f * scale).sp,
                    maxLines = 1
                )
            }

            if (displayPrefs.shows(DisplayField.REMAINING_RANGE)) {
                Text(
                    text = "~%.1f km".format(telemetry.remainingRangeKm),
                    color = secondaryColor.copy(alpha = 0.7f),
                    fontSize = (14f * scale).sp,
                    maxLines = 1
                )
            }

            // Scooter link state is not a rider preference: if the scooter link
            // is down, the numbers above are stale and saying so is not optional.
            if (telemetry.connectionState != GattProfile.STATE_READY) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (telemetry.connectionState) {
                        GattProfile.STATE_CONNECTING -> "⏳ Scooter Connecting"
                        else -> "❌ Scooter Offline"
                    },
                    color = warningColor,
                    fontSize = (12f * scale).sp
                )
            }
        }

        // ---- Right column: scooter battery, odometer, trip.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.weight(1.2f)
        ) {
            if (displayPrefs.shows(DisplayField.SCOOTER_BATTERY)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(text = "🛴", fontSize = (14f * scale).sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${telemetry.scooterBattery}%",
                        color = levelColor(telemetry.scooterBattery, primaryColor),
                        fontSize = (18f * scale).sp,
                        fontWeight = if (telemetry.scooterBattery <= 15) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (displayPrefs.shows(DisplayField.TOTAL_MILEAGE)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = telemetry.formatTotalMileage(),
                    color = secondaryColor.copy(alpha = 0.8f),
                    fontSize = (14f * scale).sp,
                    maxLines = 1
                )
            }

            if (displayPrefs.shows(DisplayField.TRIP_DISTANCE) &&
                (telemetry.tripMeters > 0 || telemetry.tripSeconds > 0)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = telemetry.formatTripDistance(),
                    color = secondaryColor.copy(alpha = 0.8f),
                    fontSize = (14f * scale).sp,
                    maxLines = 1
                )
            }

            if (displayPrefs.shows(DisplayField.TRIP_TIME) &&
                (telemetry.tripMeters > 0 || telemetry.tripSeconds > 0)
            ) {
                Text(
                    text = telemetry.formatTripTime(),
                    color = secondaryColor.copy(alpha = 0.6f),
                    fontSize = (12f * scale).sp,
                    maxLines = 1
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
