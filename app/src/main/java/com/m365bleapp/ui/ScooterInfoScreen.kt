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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.repository.ScooterRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScooterInfoScreen(
    repository: ScooterRepository,
    onBack: () -> Unit
) {
    val motorInfo by repository.motorInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scooter_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Speed Section
            InfoCard(title = stringResource(R.string.info_speed_section)) {
                InfoRow(label = stringResource(R.string.info_current_speed), value = "${"%.1f".format(motorInfo?.speed ?: 0.0)} ${stringResource(R.string.unit_kmh)}")
                InfoRow(label = stringResource(R.string.info_avg_speed), value = "${"%.1f".format(motorInfo?.avgSpeed ?: 0.0)} ${stringResource(R.string.unit_kmh)}")
            }
            
            // Battery Section
            InfoCard(title = stringResource(R.string.info_battery_section)) {
                val battery = motorInfo?.battery ?: 0
                InfoRow(label = stringResource(R.string.info_battery_level), value = "$battery${stringResource(R.string.unit_percent)}")
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
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(label = stringResource(R.string.info_remaining_range), value = "${"%.1f".format(motorInfo?.remainingKm ?: 0.0)} ${stringResource(R.string.unit_km)}")
            }
            
            // Trip Section
            InfoCard(title = stringResource(R.string.info_trip_section)) {
                val tripSeconds = motorInfo?.tripSeconds ?: 0
                val tripMinutes = tripSeconds / 60
                val tripSecs = tripSeconds % 60
                InfoRow(label = stringResource(R.string.info_trip_time), value = stringResource(R.string.info_time_format, tripMinutes, tripSecs))
                
                val tripMeters = motorInfo?.tripMeters ?: 0
                val tripKm = tripMeters / 1000.0
                InfoRow(label = stringResource(R.string.info_trip_distance), value = if (tripMeters >= 1000) "${"%.2f".format(tripKm)} ${stringResource(R.string.unit_km)}" else "$tripMeters ${stringResource(R.string.unit_m)}")
            }
            
            // Total Stats Section
            InfoCard(title = stringResource(R.string.info_stats_section)) {
                InfoRow(label = stringResource(R.string.info_total_mileage), value = "${"%.2f".format(motorInfo?.mileage ?: 0.0)} ${stringResource(R.string.unit_km)}")
            }
            
            // Temperature Section
            InfoCard(title = stringResource(R.string.info_system_section)) {
                val temp = motorInfo?.temp ?: 0.0
                InfoRow(
                    label = stringResource(R.string.info_controller_temp),
                    value = "${"%.1f".format(temp)}${stringResource(R.string.unit_celsius)}",
                    valueColor = when {
                        temp > 60 -> MaterialTheme.colorScheme.error
                        temp > 45 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
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
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
