package com.m365bleapp.ui

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.m365bleapp.R
import com.m365bleapp.protocol.*
import com.m365bleapp.repository.*
import com.m365bleapp.ui.components.ModelBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import io.github.zero2005x.pev.core.vehicle.*

@SuppressLint("MissingPermission")
private data class ScannedDevice(val scanResult: ScanResult, val isRegistered: Boolean,
    val discoveryIndex: Int = 0) {
    val address = scanResult.device.address
    val rssi = scanResult.rssi
    val name = scanResult.scanRecord?.deviceName ?: scanResult.device.name
    val identification get() = ScooterModelRegistry.resolve(name,
        manufacturerTypeCode = scanResult.scanRecord?.getManufacturerSpecificData(ScooterModelRegistry.MANUFACTURER_COMPANY_ID)
            ?.firstOrNull()?.toInt()?.and(255))
    val looksLikeScooter get() = !identification.isUnknown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(repository: ScooterRepository, onNavigateToDashboard: () -> Unit,
    onNavigateToSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val connState by repository.connectionState.collectAsStateWithLifecycle()
    val reconnect by repository.autoReconnectStatus.collectAsStateWithLifecycle()
    val identity by repository.identification.collectAsStateWithLifecycle()
    val profiles = remember { VehicleProfileStore.getInstance(context) }
    val records by profiles.records.collectAsStateWithLifecycle()
    val pending by ModelOverrideStore.getInstance(context).override.collectAsStateWithLifecycle()
    val devices = remember { mutableStateMapOf<String, ScannedDevice>() }
    val counter = remember { java.util.concurrent.atomic.AtomicInteger() }
    var searchRequested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ScannedDevice?>(null) }
    var modelAddress by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) searchRequested = true
        else error = context.getString(R.string.scan_bluetooth_permission_required)
    }
    val canScan = (connState is ConnectionState.Disconnected || connState is ConnectionState.Error) && reconnect == null
    val saved = repository.savedVehicleAddresses()
    val sorted = NearbyListOrder.order(devices.values.map {
        DiscoveryEntry(it.address, it.discoveryIndex, it.isRegistered, it.looksLikeScooter)
    }, true).mapNotNull { devices[it.address] }
    LaunchedEffect(searchRequested, canScan) {
        if (!canScan) searchRequested = false
        if (searchRequested && canScan) {
            try { collectScanResults(repository, devices, counter) { error = it } }
            finally { searchRequested = false }
        }
    }
    var previousConnection by remember { mutableStateOf(connState) }
    LaunchedEffect(connState) {
        if (connState is ConnectionState.Ready && previousConnection !is ConnectionState.Ready) onNavigateToDashboard()
        previousConnection = connState
    }
    modelAddress?.let { address ->
        ModelOverrideDialog(address, repository, onDismiss = { modelAddress = null })
    }
    selected?.let { device ->
        ConnectDialog(repository, device.scanResult, onDismiss = { selected = null }, onConnect = {
            searchRequested = false
            repository.connect(device.address, it)
            selected = null
        })
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_vehicles)) }, actions = {
        TextButton(onClick = onNavigateToSettings) { Text(stringResource(R.string.settings_title)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                repository.currentAddress?.let { address ->
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.current_vehicle))
                            Text(identity.model.displayName + " · " + address)
                            Text(when (connState) {
                                is ConnectionState.Ready -> stringResource(R.string.connected)
                                is ConnectionState.Disconnected -> stringResource(R.string.vehicle_disconnected)
                                is ConnectionState.Error -> stringResource(R.string.error)
                                else -> stringResource(R.string.connecting)
                            })
                            TextButton(onClick = { modelAddress = address }) { Text(stringResource(R.string.model_override_title)) }
                            TextButton(onClick = repository::disconnect) { Text(stringResource(R.string.disconnect)) }
                        }
                    }
                }
                Text(stringResource(R.string.saved_vehicles), Modifier.padding(12.dp))
                if (pending != null) {
                    Card(Modifier.padding(12.dp)) { Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.legacy_model_prompt, pending!!.displayName))
                        saved.forEach { address -> TextButton(onClick = { repository.resolveLegacyModel(address) }) { Text(address) } }
                        TextButton(onClick = { repository.resolveLegacyModel(null) }) { Text(stringResource(R.string.discard)) }
                    } }
                }
            }
            items(saved, key = { "saved:" + it }) { address ->
                ListItem(headlineContent = { Text(records.firstOrNull { it.addressHint.equals(address, true) }?.displayName?.ifBlank { address } ?: address) },
                    supportingContent = { Text(address) }, trailingContent = { Row {
                        TextButton(onClick = { modelAddress = address }) { Text(stringResource(R.string.model_override_title)) }
                        TextButton(onClick = { searchRequested = false; repository.connect(address) }) { Text(stringResource(R.string.connect)) }
                    } })
            }
            item {
                Button(enabled = canScan, onClick = {
                    error = null
                    val required = com.m365bleapp.utils.BluetoothHelper.getRequiredPermissions()
                    if (required.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) searchRequested = true
                    else permissionLauncher.launch(required.toTypedArray())
                }, modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.search_nearby))
                }
                if (searchRequested) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(sorted, key = { it.address }) { device ->
                ScannedDeviceItem(device, onModel = { modelAddress = it.address }, onSelect = {
                    if (canScan) selected = it
                })
            }
        }
    }
}

@Composable
private fun ScannedDeviceItem(scannedDevice: ScannedDevice, onModel: (ScannedDevice) -> Unit, onSelect: (ScannedDevice) -> Unit) {
    // Use cached properties from ScannedDevice for better performance
    val displayName = scannedDevice.name ?: stringResource(R.string.unknown)
    val isReg = scannedDevice.isRegistered
    val identification = scannedDevice.identification
    val isScooter = scannedDevice.looksLikeScooter
    val address = scannedDevice.address
    val rssi = scannedDevice.rssi

    // Animate background and scale for registered devices
    val backgroundColor by animateColorAsState(
        targetValue = if (isReg) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                      else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = 500),
        label = "registeredBgColor"
    )
    val scaleValue by animateFloatAsState(
        targetValue = if (isReg) 1.02f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "registeredScale"
    )

    ListItem(
        modifier = Modifier
            .scale(scaleValue)
            .background(backgroundColor)
            .clickable { onSelect(scannedDevice) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScooter) {
                    Text("🛴 ", style = MaterialTheme.typography.bodyLarge)
                }
                Text(displayName)
                // Model + confidence badge.
                //
                // The confidence is part of the badge on purpose:
                // a name prefix identifies a family, not a
                // protocol, so presenting the model alone would
                // claim more than the scan can know.
                if (isScooter) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ModelBadge(identification)
                }
                if (isReg) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            stringResource(R.string.registered),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        supportingContent = { Text("$address (${stringResource(R.string.scan_rssi, rssi)})") },
        trailingContent = {
            TextButton(onClick = { onModel(scannedDevice) }) { Text(stringResource(R.string.model_override_title)) }
        }
    )
    HorizontalDivider()
}

@SuppressLint("MissingPermission")
private suspend fun collectScanResults(
    repository: ScooterRepository,
    devicesMap: MutableMap<String, ScannedDevice>,
    discoveryCounter: java.util.concurrent.atomic.AtomicInteger,
    onError: (String?) -> Unit
) {
    repository.scan()
        .retry(3) { cause ->
            // Retry on scan failures (common on MIUI first launch)
            Log.w("ScanScreen", "Scan failed, retrying: ${cause.message}")
            delay(1000)
            true
        }
        .catch { e ->
            Log.e("ScanScreen", "Scan error after retries: ${e.message}")
            onError(e.message)
        }
        .collect { res ->
            updateScannedDevice(repository, devicesMap, discoveryCounter, res)
        }
}

@SuppressLint("MissingPermission")
private fun updateScannedDevice(
    repository: ScooterRepository,
    devicesMap: MutableMap<String, ScannedDevice>,
    discoveryCounter: java.util.concurrent.atomic.AtomicInteger,
    res: ScanResult
) {
    val mac = res.device.address
    val isReg = repository.isRegistered(mac)
    // Get advertised name from scan record (more reliable)
    val advertisedName = res.scanRecord?.deviceName ?: res.device.name

    // Only update if device is new or RSSI changed significantly (>5 dBm)
    // This reduces unnecessary recompositions
    val existing = devicesMap[mac]
    if (existing == null ||
        kotlin.math.abs(existing.rssi - res.rssi) > 5 ||
        existing.name != advertisedName) {
        val scannedDevice = ScannedDevice(
            res,
            isReg,
            discoveryIndex = existing?.discoveryIndex ?: discoveryCounter.getAndIncrement(),
        )
        // Log scooter discovery
        if (scannedDevice.looksLikeScooter) {
            Log.i("ScanScreen", "Found scooter: $advertisedName ($mac)")
        }
        devicesMap[mac] = scannedDevice
    }
}

@Composable
fun ConnectDialog(
    repository: ScooterRepository,
    device: ScanResult,
    onDismiss: () -> Unit,
    onConnect: (Boolean) -> Unit
) {
    // Registration is decided here, not asked.
    //
    // The dialog used to present a "Register" checkbox, which asked the rider to
    // choose between two protocol paths they have no way to evaluate. The answer
    // is not a preference — it is a fact the app already knows: a scooter with a
    // stored token must log in, one without must register first. `register` is
    // therefore derived from storage and passed straight through to connect().
    val isAlreadyRegistered = repository.isRegistered(device.device.address)
    // Kept as a recallable value so the confirm button reads from state rather
    // than recomputing storage on every recomposition.
    val register = !isAlreadyRegistered

    // The dialog is only shown for a device discovered by a permission-gated
    // scan, so BLUETOOTH_CONNECT is already held here.
    @SuppressLint("MissingPermission")
    val deviceName = device.device.name ?: stringResource(R.string.unknown)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(deviceName) },
        text = {
            Column {
                Text(
                    text = if (isAlreadyRegistered) {
                        stringResource(R.string.dialog_connect_known)
                    } else {
                        stringResource(R.string.dialog_connect_new)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!isAlreadyRegistered) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // The one instruction that genuinely matters on a first
                    // pairing, and the reason it is here rather than in a log.
                    Text(
                        text = stringResource(R.string.dialog_register_warning),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(register) }) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}



/**
 * Lets the rider pin the scooter model when identification fails or is wrong.
 *
 * The list is deliberately flat and labelled rather than grouped: a rider
 * reaching for this is looking at a scooter that the app could not name, and a
 * two-level picker would be a worse experience at exactly the moment they are
 * already stuck.
 *
 * Models whose register layout this app cannot read are still listed. Selecting
 * one is honest — it tells the app what the scooter *is*, even though the app
 * will then report that it cannot read it. Hiding them would leave the rider
 * wondering whether their scooter was in the list at all.
 */
@Composable
fun ModelOverrideDialog(
    address: String,
    repository: ScooterRepository,
    onDismiss: () -> Unit,
) {
    val store = VehicleProfileStore.getInstance(LocalContext.current)
    val profiles by store.records.collectAsStateWithLifecycle()
    val current = profiles.let { store.manualModel(address) }
    val onSelect: (ScooterModel?) -> Unit = { choice ->
        repository.setVehicleModel(address, choice)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_override_title) + "\n" + address) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.model_override_explanation),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // "Automatic" first: clearing the override is the common case
                // once a scooter has been identified correctly.
                ModelOverrideRow(
                    label = stringResource(R.string.model_override_automatic),
                    detail = null,
                    selected = current == null,
                    onClick = { onSelect(null) }
                )

                for (model in com.m365bleapp.protocol.ScooterModelRegistry.selectableModels.filter { it.producesTelemetry }) {
                    ModelOverrideRow(
                        label = model.displayName,
                        detail = if (model.producesTelemetry) {
                            null
                        } else {
                            stringResource(R.string.model_override_no_readings)
                        },
                        selected = current == model,
                        onClick = { onSelect(model) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ModelOverrideRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
