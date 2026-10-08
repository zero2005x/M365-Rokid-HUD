// SPDX-License-Identifier: MIT
package com.m365bleapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m365bleapp.R
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.repository.XiaomiSetting
import io.github.zero2005x.pev.core.codec.xiaomi.StatusWordWriteOrder
import io.github.zero2005x.pev.core.command.CommandOutcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Explicit model selection and consent are discarded on disconnect; no remembered radio writes. */
@Composable
internal fun ExperimentalVehicleSettings(repository: ScooterRepository) {
    val state by repository.experimentalSettings.collectAsState()
    val scope = rememberCoroutineScope()
    var optIn by remember(state.connectionId) { mutableStateOf(false) }
    var order by remember(state.connectionId) { mutableStateOf<StatusWordWriteOrder?>(null) }
    var pending by remember(state.connectionId) { mutableStateOf<XiaomiSetting?>(null) }
    var busy by remember(state.connectionId) { mutableStateOf(false) }
    var result by remember(state.connectionId) { mutableStateOf<String?>(null) }
    val confirmedText = stringResource(R.string.experimental_readback_confirmed)
    val unconfirmedText = stringResource(R.string.experimental_unconfirmed)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.experimental_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.experimental_scope), style = MaterialTheme.typography.bodySmall)
        if (!state.available) Text(stringResource(R.string.experimental_connect_required))
        else if (!state.enabled) {
            Button(onClick = { optIn = true }) { Text(stringResource(R.string.experimental_enable)) }
        } else {
            Text(stringResource(R.string.experimental_device, state.deviceId.orEmpty()))
            Text(stringResource(R.string.experimental_order_note), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = order == StatusWordWriteOrder.BIG_ENDIAN, onClick = { order = StatusWordWriteOrder.BIG_ENDIAN }, label = { Text("BE") })
                FilterChip(selected = order == StatusWordWriteOrder.LITTLE_ENDIAN, onClick = { order = StatusWordWriteOrder.LITTLE_ENDIAN }, label = { Text("LE") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && order != null, onClick = { order?.let { pending = XiaomiSetting.TailLight(true, it) } }) { Text(stringResource(R.string.experimental_light_on)) }
                OutlinedButton(enabled = !busy && order != null, onClick = { order?.let { pending = XiaomiSetting.TailLight(false, it) } }) { Text(stringResource(R.string.experimental_light_off)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && order != null, onClick = { order?.let { pending = XiaomiSetting.Units(false, it) } }) { Text("km/h") }
                OutlinedButton(enabled = !busy && order != null, onClick = { order?.let { pending = XiaomiSetting.Units(true, it) } }) { Text("mph") }
            }
            Text(stringResource(R.string.experimental_motion_note), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (level in 0..2) OutlinedButton(enabled = !busy, onClick = { pending = XiaomiSetting.Kers(level) }) { Text(stringResource(R.string.experimental_kers, level)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pending = XiaomiSetting.Cruise(true) }) { Text(stringResource(R.string.experimental_cruise_on)) }
                OutlinedButton(enabled = !busy, onClick = { pending = XiaomiSetting.Cruise(false) }) { Text(stringResource(R.string.experimental_cruise_off)) }
            }
            TextButton(onClick = { repository.disconnect() }) { Text(stringResource(R.string.experimental_disable)) }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (optIn) AlertDialog(
        onDismissRequest = { optIn = false }, title = { Text(stringResource(R.string.experimental_confirm_model)) },
        text = { Text(stringResource(R.string.experimental_consent)) },
        confirmButton = { TextButton(onClick = { repository.enableM365Experimental(state.connectionId); optIn = false }) { Text(stringResource(R.string.experimental_enable)) } },
        dismissButton = { TextButton(onClick = { optIn = false }) { Text(stringResource(android.R.string.cancel)) } })
    pending?.let { command ->
        val details = when (command) {
            is XiaomiSetting.Kers -> stringResource(R.string.experimental_kers, command.level)
            is XiaomiSetting.Cruise -> stringResource(if (command.on) R.string.experimental_cruise_on else R.string.experimental_cruise_off)
            is XiaomiSetting.TailLight -> stringResource(if (command.on) R.string.experimental_light_on else R.string.experimental_light_off) + " (${command.order})"
            is XiaomiSetting.Units -> (if (command.mph) "mph" else "km/h") + " (${command.order})"
        }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(details) },
            text = { Text(stringResource(R.string.experimental_command_confirmation)) },
            confirmButton = { TextButton(onClick = {
                pending = null; busy = true
                scope.launch {
                    try {
                        val outcome = repository.executeXiaomiSetting(command, state.connectionId)
                        result = if (outcome.outcome == CommandOutcome.READBACK_CONFIRMED) confirmedText
                            else "$unconfirmedText: ${outcome.outcome} ${outcome.detail.orEmpty()}"
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        result = "$unconfirmedText: ${failure.message.orEmpty()}"
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.experimental_send)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
