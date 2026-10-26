package com.m365bleapp.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.annotation.SuppressLint
import androidx.compose.animation.Crossfade
import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.m365bleapp.gateway.GatewayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.ui.components.M365BatteryBar
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.FontSizes
import com.m365bleapp.ui.theme.TextSecondary

/** Ride tab. Connection and gateway lifetimes belong to their services. */
@Composable
fun HomeScreen(
    repository: ScooterRepository,
    onOpenSettings: () -> Unit,
    onOpenScooterInfo: () -> Unit,
) {
    val connState by repository.connectionState.collectAsStateWithLifecycle()
    val reconnectStatus by repository.autoReconnectStatus.collectAsStateWithLifecycle()

    // Only treat a fully ready link as connected. Showing telemetry during
    // Handshaking would display zeros as if they were real readings. An
    // automatic reconnect keeps the dashboard (telemetry is cleared, so it
    // shows dashes): the scan list invited a tap that cancelled the retry.
    val isConnected = connState is ConnectionState.Ready || reconnectStatus != null

    Crossfade(
        targetState = isConnected,
        animationSpec = tween(durationMillis = 220),
        label = "home-state"
    ) { connected ->
        if (connected) {
            ConnectedHome(
                repository = repository,
                reconnectStatus = reconnectStatus,
                onOpenSettings = onOpenSettings,
                onOpenScooterInfo = onOpenScooterInfo
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ride_connect_hint), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Vehicle identity, telemetry, and read-only connection status. */
@Composable
private fun ConnectedHome(
    repository: ScooterRepository,
    /** Non-null while an automatic reconnect runs; the text is its progress. */
    reconnectStatus: String?,
    onOpenSettings: () -> Unit,
    onOpenScooterInfo: () -> Unit
) {
    val reconnecting = reconnectStatus != null
    val motorInfo by repository.motorInfo.collectAsStateWithLifecycle()
    val info = motorInfo
    val identity by repository.identification.collectAsStateWithLifecycle()
    val profiles by com.m365bleapp.protocol.VehicleProfileStore.getInstance(LocalContext.current).records.collectAsStateWithLifecycle()
    val title = profiles.firstOrNull { it.addressHint.equals(repository.currentAddress, true) }?.displayName?.ifBlank { null } ?: identity.model.displayName

    val gatewayState by com.m365bleapp.gateway.GatewayUiState.state.collectAsStateWithLifecycle()
    val gatewayEnabled = gatewayState.bleRunning
    val glassesConnected = gatewayState.glassesConnected

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = Dimens.gutter)
        ) {
            // --- Header: identity + settings, so Settings is reachable while
            // connected (it used to be reachable only from the scan face).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title + " · " + stringResource(if (reconnecting) R.string.connecting else R.string.connected) + "\n" +
                        stringResource(R.string.data_updated_at, info?.receivedAtMs?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "—"),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (reconnecting) TextSecondary else MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.space16))

            if (reconnectStatus != null) {
                ReconnectingBanner(reconnectStatus)
                Spacer(modifier = Modifier.height(Dimens.space16))
            }

            ScooterFaultBanner(info)

            // --- Hero: speed. A dash, not 0.0, until the first reading arrives —
            // a zero here reads as a real measurement.
            val caps = identity.model.capabilities
            val liveSpeed = info?.validSpeedOrNull(caps)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = liveSpeed?.let { formatOneDecimal(it) }
                            ?: stringResource(R.string.value_unknown),
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
                BatteryTile(
                    label = if (identity.model.producesTelemetry) "🛴" else "🚗",
                    percent = info?.validBatteryOrNull(caps),
                    voltageV = info?.validPackVoltageOrNull(caps),
                    modifier = Modifier.weight(1f)
                )
                PhoneBatteryTile(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(Dimens.space16))

            HomeStatusLine(
                gatewayEnabled = gatewayEnabled,
                glassesConnected = glassesConnected
            )

            Spacer(modifier = Modifier.height(Dimens.space16))

            Spacer(modifier = Modifier.height(Dimens.space8))

            HomeActionButton(
                label = stringResource(R.string.dashboard_view_details),
                onClick = onOpenScooterInfo
            )

            Spacer(modifier = Modifier.height(Dimens.space8))

            // --- Disconnect. Low emphasis: switching scooters needs it, but it
            // is not an everyday action. On disconnect the page crossfades back
            // to the scan list on its own.
            DisconnectButton(repository = repository)

            Spacer(modifier = Modifier.height(Dimens.space24))
        }
    }
}

@Composable
private fun ReconnectingBanner(status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(Dimens.space12))
            .padding(Dimens.space12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Spacer(modifier = Modifier.width(Dimens.space12))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/** Scooter/other battery tile that shows a dash (or verified pack voltage) rather than a fake 0 %. */
@Composable
private fun BatteryTile(
    label: String,
    percent: Int?,
    modifier: Modifier = Modifier,
    voltageV: Double? = null,
) {
    if (percent != null) {
        M365BatteryBar(batteryLevel = percent, label = label, modifier = modifier)
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$label ", fontSize = 20.sp)
            Text(
                text = voltageV?.let { "${"%.1f".format(it)} V" }
                    ?: stringResource(R.string.value_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = if (voltageV != null) MaterialTheme.colorScheme.primary else TextSecondary
            )
        }
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
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    BatteryTile(label = "📱", percent = percentage, modifier = modifier)
}

@Composable
private fun HomeStatusLine(gatewayEnabled: Boolean, glassesConnected: Boolean) {
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
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun DisconnectButton(repository: ScooterRepository) {
    OutlinedButton(
        onClick = {
            // disconnect() only touches BLE when a connection exists, which
            // implies BLUETOOTH_CONNECT was granted at connect time.
            @SuppressLint("MissingPermission")
            repository.disconnect()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.primaryButtonHeight),
        shape = RoundedCornerShape(Dimens.cardCorner),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text(text = stringResource(R.string.disconnect), style = MaterialTheme.typography.labelLarge)
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
