package com.m365bleapp.ui

import androidx.compose.animation.Crossfade
import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.m365bleapp.gateway.GatewayService
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.ui.components.M365BatteryBar
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.FontSizes
import com.m365bleapp.ui.theme.TextSecondary

/**
 * The single control page.
 *
 * ## Why this replaced two screens
 *
 * The app used to have a scan screen that *navigated away* the moment a scooter
 * connected, which produced two problems:
 *
 *  - "Where do I connect?" — the connection controls were on one screen and
 *    every other control was on a screen you could only reach by connecting, so
 *    the app's entry point changed meaning depending on state.
 *  - Connecting to a *different* scooter meant disconnecting first, because the
 *    device list only existed on the screen you had already left.
 *
 * This page keeps one identity and changes what it shows:
 *
 *  - **No scooter connected** → the scan and device list, i.e. the old
 *    ScanScreen, unchanged.
 *  - **Connected** → live telemetry, with the scooter's name and a way back to
 *    the device list.
 *
 * The scan screen is reused rather than reimplemented. It owns the permission
 * flow, the BLE scan lifecycle and the connect dialog; duplicating that here
 * would mean two copies of the trickiest code in the app. Only the *outer*
 * frame is new.
 *
 * ## Why a Crossfade and not a hard swap
 *
 * Connecting takes a second or more (handshake, login, first telemetry poll).
 * Without a transition the page appears to jump between two unrelated layouts.
 * A short crossfade makes it read as one page changing state, which is the
 * whole point of merging them.
 */
@Composable
fun HomeScreen(
    repository: ScooterRepository,
    onOpenSettings: () -> Unit,
    onOpenHudDisplay: () -> Unit,
    onOpenScooterInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenLogViewer: () -> Unit
) {
    val connState by repository.connectionState.collectAsState()

    // Only treat a fully ready link as connected. Showing telemetry during
    // Handshaking would display zeros as if they were real readings.
    val isConnected = connState is ConnectionState.Ready

    Crossfade(
        targetState = isConnected,
        animationSpec = tween(durationMillis = 220),
        label = "home-state"
    ) { connected ->
        if (connected) {
            ConnectedHome(
                repository = repository,
                onOpenScooterInfo = onOpenScooterInfo,
                onOpenDashboard = onOpenDashboard
            )
        } else {
            ScanScreen(
                repository = repository,
                onNavigateToDashboard = onOpenDashboard,
                onNavigateToSettings = onOpenSettings,
                // Retained for callers that still navigate directly; Settings is
                // the entry point now, so these are only fallbacks.
                onNavigateToLanguage = onOpenSettings,
                onNavigateToLogViewer = onOpenLogViewer,
                onNavigateToHudDisplay = onOpenHudDisplay
            )
        }
    }
}

/**
 * The connected face of the home page.
 *
 * Deliberately sparse. The rider's stated priority order is speed, then scooter
 * battery, then glasses battery, with three actions: connect scooter, connect
 * glasses, lights. Everything else lives one tap away, because a dashboard that
 * shows everything at once shows nothing clearly.
 */
@Composable
private fun ConnectedHome(
    repository: ScooterRepository,
    onOpenScooterInfo: () -> Unit,
    onOpenDashboard: () -> Unit
) {
    val motorInfo by repository.motorInfo.collectAsState()
    val info = motorInfo
    val context = LocalContext.current

    // Gateway state is polled rather than observed: GatewayService is a
    // foreground service in another process component and exposes only static
    // queries. A slow poll is enough for a status line.
    var gatewayEnabled by remember {
        mutableStateOf(
            GatewayService.isRunning()
        )
    }
    var glassesConnected by remember {
        mutableStateOf(
            GatewayService.isGlassesConnected()
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            gatewayEnabled = GatewayService.isRunning()
            glassesConnected = GatewayService.isGlassesConnected()
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.gutter)
    ) {
        Spacer(modifier = Modifier.height(Dimens.space24))

        // --- Hero: speed. The one thing readable at a glance while riding.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%.1f".format(info?.speed ?: 0.0),
                    fontSize = FontSizes.heroSpeed,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.unit_kmh),
                    fontSize = FontSizes.heroUnit,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.space24))

        // --- The two batteries the rider asked for, side by side.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12)
        ) {
            M365BatteryBar(
                batteryLevel = info?.battery ?: 0,
                label = "🛴",
                modifier = Modifier.weight(1f)
            )
            PhoneBatteryTile(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(Dimens.space16))

        // --- Connection status, stated once and quietly.
        HomeStatusLine(
            gatewayEnabled = gatewayEnabled,
            glassesConnected = glassesConnected
        )

        Spacer(modifier = Modifier.weight(1f))

        // --- Actions.
        HomeActionRow(
            onOpenScooterInfo = onOpenScooterInfo,
            onOpenDashboard = onOpenDashboard,
            onToggleGateway = {
                if (GatewayService.isRunning()) {
                    GatewayService.stop(context)
                    gatewayEnabled = false
                } else {
                    GatewayService.start(context)
                    gatewayEnabled = true
                }
            },
            gatewayEnabled = gatewayEnabled
        )

        Spacer(modifier = Modifier.height(Dimens.space24))
    }
}

/**
 * Phone battery, shown next to the scooter's.
 *
 * Reads the system battery directly. It is not part of `MotorInfo` because it
 * describes the phone, not the scooter.
 */
@Composable
private fun PhoneBatteryTile(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val percentage = remember {
        val bm = context.getSystemService(Context.BATTERY_SERVICE)
            as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }
    M365BatteryBar(
        batteryLevel = percentage,
        label = "📱",
        modifier = modifier
    )
}

@Composable
private fun HomeStatusLine(
    gatewayEnabled: Boolean,
    glassesConnected: Boolean
) {
    val text = when {
        glassesConnected -> stringResource(R.string.glasses_connected)
        gatewayEnabled -> stringResource(R.string.gateway_broadcasting)
        else -> stringResource(R.string.gateway_off)
    }
    val color = when {
        glassesConnected -> MaterialTheme.colorScheme.secondary
        gatewayEnabled -> MaterialTheme.colorScheme.primary
        else -> TextSecondary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "👓", fontSize = 16.sp)
        Spacer(modifier = Modifier.width(Dimens.space8))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
private fun HomeActionRow(
    onOpenScooterInfo: () -> Unit,
    onOpenDashboard: () -> Unit,
    onToggleGateway: () -> Unit,
    gatewayEnabled: Boolean
) {
    Column {
        HomeActionButton(
            label = stringResource(R.string.dashboard_view_details),
            onClick = onOpenScooterInfo
        )
        Spacer(modifier = Modifier.height(Dimens.space8))
        HomeActionButton(
            label = if (gatewayEnabled) {
                stringResource(R.string.gateway_broadcasting)
            } else {
                stringResource(R.string.gateway_hud)
            },
            onClick = onToggleGateway
        )
        Spacer(modifier = Modifier.height(Dimens.space8))
        HomeActionButton(
            label = stringResource(R.string.dashboard_title),
            onClick = onOpenDashboard
        )
    }
}

@Composable
private fun HomeActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.primaryButtonHeight),
        shape = RoundedCornerShape(Dimens.cardCorner)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
