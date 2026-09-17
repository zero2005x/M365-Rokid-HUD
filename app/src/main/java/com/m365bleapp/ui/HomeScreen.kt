package com.m365bleapp.ui

import android.annotation.SuppressLint
import androidx.compose.animation.Crossfade
import android.content.Context
import android.os.BatteryManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
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
 *  - **Connected** → live telemetry plus the everyday controls (lights, gateway,
 *    lock) and a way into the full detail page.
 *
 * There is no separate dashboard route any more: the connected face *is* the
 * dashboard, and [ScooterInfoScreen] is the one detail page.
 */
@Composable
fun HomeScreen(
    repository: ScooterRepository,
    onOpenSettings: () -> Unit,
    onOpenHudDisplay: () -> Unit,
    onOpenScooterInfo: () -> Unit,
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
                onOpenSettings = onOpenSettings,
                onOpenScooterInfo = onOpenScooterInfo
            )
        } else {
            ScanScreen(
                repository = repository,
                // The connected transition is owned here by the Crossfade now, so
                // ScanScreen must not also push a route when a link goes Ready.
                onNavigateToDashboard = {},
                onNavigateToSettings = onOpenSettings,
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
 * The rider's stated priority order is speed, then scooter battery, then glasses
 * battery; the everyday actions are lights, the glasses gateway and the lock.
 * Everything diagnostic or rarely touched is one tap away in Settings, because a
 * dashboard that shows everything at once shows nothing clearly.
 */
@Composable
private fun ConnectedHome(
    repository: ScooterRepository,
    onOpenSettings: () -> Unit,
    onOpenScooterInfo: () -> Unit
) {
    val motorInfo by repository.motorInfo.collectAsState()
    val info = motorInfo

    // Gateway state is polled rather than observed: GatewayService is a
    // foreground service component and exposes only static queries.
    var gatewayEnabled by remember { mutableStateOf(GatewayService.isRunning()) }
    var glassesConnected by remember { mutableStateOf(GatewayService.isGlassesConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            gatewayEnabled = GatewayService.isRunning()
            glassesConnected = GatewayService.isGlassesConnected()
            delay(2000)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val capabilities = repository.currentCapabilities()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    text = stringResource(R.string.connected),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.space16))

            // --- Hero: speed. A dash, not 0.0, until the first reading arrives —
            // a zero here reads as a real measurement.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = info?.speed?.let { "%.1f".format(it) }
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
                    label = "🛴",
                    percent = info?.battery,
                    modifier = Modifier.weight(1f)
                )
                PhoneBatteryTile(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(Dimens.space16))

            HomeStatusLine(
                gatewayEnabled = gatewayEnabled,
                glassesConnected = glassesConnected
            )

            Spacer(modifier = Modifier.weight(1f))

            // --- Everyday controls. Each capability-gated: a control for a
            // register this model does not have would write to the wrong address.
            if (capabilities.tailLight) {
                LightControlRow(repository = repository, snackbarHostState = snackbarHostState)
                Spacer(modifier = Modifier.height(Dimens.space8))
            }

            GatewayControlRow(
                gatewayEnabled = gatewayEnabled,
                glassesConnected = glassesConnected,
                onSetEnabled = { gatewayEnabled = it }
            )
            Spacer(modifier = Modifier.height(Dimens.space8))

            if (capabilities.motorLock) {
                LockControlRow(repository = repository, snackbarHostState = snackbarHostState)
                Spacer(modifier = Modifier.height(Dimens.space8))
            }

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

/** Scooter/other battery tile that shows a dash rather than a fake 0 %. */
@Composable
private fun BatteryTile(label: String, percent: Int?, modifier: Modifier = Modifier) {
    if (percent != null) {
        M365BatteryBar(batteryLevel = percent, label = label, modifier = modifier)
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$label ", fontSize = 20.sp)
            Text(
                text = stringResource(R.string.value_unknown),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
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

/** Tail-light toggle. Reflects the repository's light state. */
@Composable
private fun LightControlRow(
    repository: ScooterRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val lightOn by repository.isLightOn.collectAsState()
    var loading by remember { mutableStateOf(false) }
    val msgOn = stringResource(R.string.msg_light_on)
    val msgOff = stringResource(R.string.msg_light_off)
    val failFmt = stringResource(R.string.msg_light_failed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.primaryButtonHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (lightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                contentDescription = null,
                tint = if (lightOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = stringResource(R.string.control_light),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Switch(
                checked = lightOn,
                onCheckedChange = { want ->
                    loading = true
                    scope.launch {
                        repository.setLight(want)
                            .onSuccess { snackbarHostState.showSnackbar(if (want) msgOn else msgOff) }
                            .onFailure { e -> snackbarHostState.showSnackbar(failFmt.format(e.message ?: "")) }
                        loading = false
                    }
                }
            )
        }
    }
}

/**
 * Motor lock control.
 *
 * The scooter cannot report its lock state, so the state is shown as *unknown*
 * rather than an optimistic "unlocked" that would be wrong after a reconnect.
 * Locking asks for confirmation because it is safety-relevant and the vehicle
 * may be moving; unlocking does not.
 */
@Composable
private fun LockControlRow(
    repository: ScooterRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var confirmLock by remember { mutableStateOf(false) }
    val lockedMsg = stringResource(R.string.msg_motor_locked)
    val unlockedMsg = stringResource(R.string.msg_motor_unlocked)
    val lockFailFmt = stringResource(R.string.msg_lock_failed)
    val unlockFailFmt = stringResource(R.string.msg_unlock_failed)

    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.lock_confirm_title)) },
            text = { Text(stringResource(R.string.lock_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLock = false
                        loading = true
                        scope.launch {
                            repository.lock()
                                .onSuccess { snackbarHostState.showSnackbar(lockedMsg) }
                                .onFailure { e -> snackbarHostState.showSnackbar(lockFailFmt.format(e.message ?: "")) }
                            loading = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.control_lock)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLock = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.primaryButtonHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Column {
                Text(
                    text = stringResource(R.string.control_motor),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.control_state_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                OutlinedButton(
                    onClick = { confirmLock = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.control_lock)) }
                Button(
                    onClick = {
                        loading = true
                        scope.launch {
                            repository.unlock()
                                .onSuccess { snackbarHostState.showSnackbar(unlockedMsg) }
                                .onFailure { e -> snackbarHostState.showSnackbar(unlockFailFmt.format(e.message ?: "")) }
                            loading = false
                        }
                    }
                ) { Text(stringResource(R.string.control_unlock)) }
            }
        }
    }
}

/** Glasses HUD gateway toggle, using the shared safe enabler. */
@Composable
private fun GatewayControlRow(
    gatewayEnabled: Boolean,
    glassesConnected: Boolean,
    onSetEnabled: (Boolean) -> Unit
) {
    val enableGateway = rememberGatewayEnabler(setEnabled = onSetEnabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.primaryButtonHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "👓 " + stringResource(R.string.gateway_hud),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = when {
                    glassesConnected -> stringResource(R.string.glasses_connected)
                    gatewayEnabled -> stringResource(R.string.gateway_broadcasting)
                    else -> stringResource(R.string.gateway_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = gatewayEnabled,
            onCheckedChange = { enableGateway(it) }
        )
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
