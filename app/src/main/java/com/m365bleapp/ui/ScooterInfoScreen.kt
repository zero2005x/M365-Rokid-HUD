package com.m365bleapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m365bleapp.R
import com.m365bleapp.protocol.ModelCapabilities
import com.m365bleapp.protocol.ModelOverrideStore
import com.m365bleapp.protocol.ScooterModelRegistry
import com.m365bleapp.repository.MotorInfo
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.repository.VehicleSnapshot
import com.m365bleapp.repository.VehicleSnapshotStore
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Full vehicle detail.
 *
 * ## Offline behaviour
 *
 * This screen used to be reachable only immediately after connecting, because
 * every field came from live telemetry and rendered as `0` when there was no
 * connection. Zeros are worse than blanks on a dashboard — they look like
 * readings.
 *
 * It now reads from two sources and says which one it is using:
 *
 *  - **Live** telemetry when connected. Preferred, and always wins.
 *  - **Last known** values from [VehicleSnapshotStore] otherwise, with the age
 *    of the reading stated in a banner.
 *
 * Identity fields (serial, firmware) come from the snapshot unconditionally,
 * because they do not change between connections and reading them live would
 * make the screen wrong for no benefit.
 *
 * The body is split into one composable per section so that neither this
 * function nor any section carries the whole screen's branching at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScooterInfoScreen(
    repository: ScooterRepository,
    onBack: () -> Unit
) {
    val motorInfo by repository.motorInfo.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snapshotStore = remember { VehicleSnapshotStore.getInstance(context) }
    val snapshot by snapshotStore.snapshot.collectAsState()

    // Re-render the "x minutes ago" banner without recomposing every frame.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val live = motorInfo
    val isLive = live != null

    // Which fields this scooter can actually report. Capability-driven rather
    // than "does it look like an M365": a section whose register this model does
    // not have is hidden entirely instead of being rendered as a row of zeros.
    val overrideStore = remember { ModelOverrideStore.getInstance(context) }
    val modelOverride by overrideStore.override.collectAsState()
    val identification = ScooterModelRegistry.resolve(snapshot.modelNameOrNull(), modelOverride)
    val caps = identification.model.capabilities

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scooter_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.space12)
        ) {
            DataAgeBanner(
                isLive = isLive,
                ageMs = snapshot.ageMs(nowMs),
                isEmpty = snapshot.isEmpty
            )
            IdentitySection(snapshot)
            SpeedSection(live, snapshot, isLive, caps)
            BatterySection(live, snapshot, isLive, caps)
            TripSection(live, snapshot, isLive, caps)
            OdometerSection(live, snapshot, isLive, caps)
            TemperatureSection(live, snapshot, isLive, caps)

            Spacer(modifier = Modifier.height(Dimens.space24))
        }
    }
}

/** Identity. Stable, so shown whenever known, connected or not. */
@Composable
private fun IdentitySection(snapshot: VehicleSnapshot) {
    if (snapshot.serial == null && snapshot.firmware == null && snapshot.mac == null) return
    InfoCard(title = stringResource(R.string.info_vehicle_section)) {
        snapshot.serial?.let {
            InfoRow(label = stringResource(R.string.info_serial), value = it, isLive = false)
        }
        snapshot.firmware?.let {
            InfoRow(label = stringResource(R.string.info_firmware), value = it, isLive = false)
        }
        snapshot.mac?.let {
            InfoRow(label = stringResource(R.string.info_address), value = it, isLive = false)
        }
    }
}

@Composable
private fun SpeedSection(
    live: MotorInfo?,
    snapshot: VehicleSnapshot,
    isLive: Boolean,
    caps: ModelCapabilities
) {
    if (!caps.speed) return
    InfoCard(title = stringResource(R.string.info_speed_section)) {
        InfoRow(
            label = stringResource(R.string.info_current_speed),
            value = formatOrDash(
                live?.speed?.toFloat()?.takeIf { isLive } ?: snapshot.takeIf { !isLive }?.speedKmh?.toFloat(),
                stringResource(R.string.unit_kmh)
            ),
            isLive = isLive
        )
        InfoRow(
            label = stringResource(R.string.info_avg_speed),
            value = formatOrDash(
                live?.avgSpeed?.toFloat()?.takeIf { isLive } ?: snapshot.takeIf { !isLive }?.averageSpeedKmh?.toFloat(),
                stringResource(R.string.unit_kmh)
            ),
            isLive = isLive
        )
    }
}

@Composable
private fun BatterySection(
    live: MotorInfo?,
    snapshot: VehicleSnapshot,
    isLive: Boolean,
    caps: ModelCapabilities
) {
    if (!caps.batteryPercent) return
    InfoCard(title = stringResource(R.string.info_battery_section)) {
        val battery: Int? = if (live != null) live.battery else snapshot.batteryPercent.takeIf { !snapshot.isEmpty }
        InfoRow(
            label = stringResource(R.string.info_battery_level),
            value = battery?.let { "$it${stringResource(R.string.unit_percent)}" }
                ?: stringResource(R.string.value_unknown),
            isLive = isLive
        )
        if (battery != null) {
            LinearProgressIndicator(
                progress = { battery / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(top = 8.dp),
                color = when {
                    battery > 50 -> MaterialTheme.colorScheme.primary
                    battery > 20 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (caps.remainingRange) InfoRow(
            label = stringResource(R.string.info_remaining_range),
            value = formatOrDash(
                live?.remainingKm?.toFloat()?.takeIf { isLive } ?: snapshot.takeIf { !isLive }?.remainingKm?.toFloat(),
                stringResource(R.string.unit_km)
            ),
            isLive = isLive
        )
    }
}

@Composable
private fun TripSection(
    live: MotorInfo?,
    snapshot: VehicleSnapshot,
    isLive: Boolean,
    caps: ModelCapabilities
) {
    if (!caps.tripDistance && !caps.tripTime) return
    InfoCard(title = stringResource(R.string.info_trip_section)) {
        val tripSeconds: Int? = if (live != null) live.tripSeconds else snapshot.tripSeconds.takeIf { !snapshot.isEmpty }
        if (caps.tripTime) InfoRow(
            label = stringResource(R.string.info_trip_time),
            value = tripSeconds?.let {
                stringResource(R.string.info_time_format, it / 60, it % 60)
            } ?: stringResource(R.string.value_unknown),
            isLive = isLive
        )

        val tripMeters: Int? = if (live != null) live.tripMeters else snapshot.tripMeters.takeIf { !snapshot.isEmpty }
        if (caps.tripDistance) InfoRow(
            label = stringResource(R.string.info_trip_distance),
            value = when {
                tripMeters == null -> stringResource(R.string.value_unknown)
                tripMeters >= 1000 -> "${"%.2f".format(tripMeters / 1000.0)} ${stringResource(R.string.unit_km)}"
                else -> "$tripMeters ${stringResource(R.string.unit_m)}"
            },
            isLive = isLive
        )
    }
}

/**
 * Odometer. This is the field a rider most wants offline: it is the one that
 * answers "how far has this thing been".
 */
@Composable
private fun OdometerSection(
    live: MotorInfo?,
    snapshot: VehicleSnapshot,
    isLive: Boolean,
    caps: ModelCapabilities
) {
    if (!caps.totalMileage) return
    InfoCard(title = stringResource(R.string.info_stats_section)) {
        InfoRow(
            label = stringResource(R.string.info_total_mileage),
            value = formatOrDash(
                live?.mileage?.toFloat()?.takeIf { isLive } ?: snapshot.takeIf { !isLive }?.totalMileageKm?.toFloat(),
                stringResource(R.string.unit_km)
            ),
            isLive = isLive
        )
    }
}

@Composable
private fun TemperatureSection(
    live: MotorInfo?,
    snapshot: VehicleSnapshot,
    isLive: Boolean,
    caps: ModelCapabilities
) {
    if (!caps.temperature) return
    InfoCard(title = stringResource(R.string.info_system_section)) {
        val temp: Double? = if (live != null) live.temp else snapshot.temperatureC.takeIf { !snapshot.isEmpty }
        InfoRow(
            label = stringResource(R.string.info_controller_temp),
            value = temp?.let { "${"%.1f".format(it)}${stringResource(R.string.unit_celsius)}" }
                ?: stringResource(R.string.value_unknown),
            valueColor = when {
                temp == null -> TextSecondary
                temp > 60 -> MaterialTheme.colorScheme.error
                temp > 45 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
            isLive = isLive
        )
    }
}

/**
 * States whether the numbers below are live, cached, or unavailable.
 *
 * Always present, because a detail page that silently shows hour-old data is
 * worse than one that admits it has none.
 */
@Composable
private fun DataAgeBanner(isLive: Boolean, ageMs: Long?, isEmpty: Boolean) {
    val text = when {
        isLive -> stringResource(R.string.info_data_live)
        isEmpty -> stringResource(R.string.info_data_none)
        ageMs == null -> stringResource(R.string.info_data_none)
        else -> stringResource(R.string.info_data_cached, formatAge(ageMs))
    }
    val color = when {
        isLive -> MaterialTheme.colorScheme.secondary
        isEmpty -> TextSecondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardCorner)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Dimens.cardPadding, vertical = Dimens.space12),
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

/** Coarse, human age. Precision here would be false comfort. */
@Composable
private fun formatAge(ageMs: Long): String {
    val minutes = ageMs / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(R.string.age_just_now)
        minutes < 60 -> stringResource(R.string.age_minutes, minutes)
        hours < 24 -> stringResource(R.string.age_hours, hours)
        else -> stringResource(R.string.age_days, days)
    }
}

/** Formats a value with its unit, or an em dash when there is nothing to show. */
@Composable
private fun formatOrDash(value: Float?, unit: String): String =
    value?.let { "${"%.1f".format(it)} $unit" } ?: stringResource(R.string.value_unknown)

@Composable
private fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardCorner)
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(Dimens.space12))
            content()
        }
    }
}

/**
 * A label/value row.
 *
 * @param isLive when false the value is dimmed, so a cached reading is visually
 *   distinct from a live one even if the banner is missed.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    isLive: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isLive) valueColor else TextSecondary
        )
    }
}
