package com.m365bleapp.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m365bleapp.R
import com.m365bleapp.gateway.GatewayService
import com.m365bleapp.utils.BluetoothHelper

/**
 * The one safe way to turn the BLE HUD gateway on.
 *
 * ## Why this exists
 *
 * The gateway needs Bluetooth enabled *and* the runtime `BLUETOOTH_ADVERTISE` /
 * `BLUETOOTH_CONNECT` permissions before `GatewayService.start()` can actually
 * advertise. That prerequisite check used to live in exactly one place
 * (DashboardScreen), while the Home and Settings toggles called
 * `GatewayService.start()` directly — so from those two entry points the service
 * would start and then silently fail to advertise on a device that had never
 * granted the permission.
 *
 * This composable centralises the flow: every gateway toggle in the app goes
 * through [rememberGatewayEnabler], which checks Bluetooth, requests the
 * permission if needed, and only then starts the service. It also owns the
 * "enable Bluetooth" dialog it may need to show.
 *
 * @param setEnabled called with the new desired state once it is actually
 *   applied, so the caller's own UI switch can follow the real service state
 *   rather than an optimistic guess.
 * @return a lambda the caller invokes with `true`/`false` from a switch.
 */
// NOSONAR kotlin:S3776 — a single permission-gating flow: it owns two
// ActivityResult launchers (enable-Bluetooth, request-permissions) whose
// callbacks and the returned decision all share `pendingEnable`. Splitting the
// branches apart would scatter that shared state across functions.
@Composable
fun rememberGatewayEnabler(setEnabled: (Boolean) -> Unit): (Boolean) -> Unit { // NOSONAR
    val context = LocalContext.current
    // Kept across the permission / enable-Bluetooth round trips so the launcher
    // callbacks know the user was trying to *enable* the gateway.
    var pendingEnable by remember { mutableStateOf(false) }
    var showBluetoothDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it } && pendingEnable) {
            GatewayService.start(context)
            setEnabled(true)
        }
        pendingEnable = false
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (BluetoothHelper.isBluetoothEnabled(context) && pendingEnable) {
            if (BluetoothHelper.hasAdvertisePermissions(context)) {
                GatewayService.start(context)
                setEnabled(true)
                pendingEnable = false
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    )
                )
            }
        } else {
            pendingEnable = false
        }
    }

    if (showBluetoothDialog) {
        AlertDialog(
            onDismissRequest = {
                showBluetoothDialog = false
                pendingEnable = false
            },
            title = { Text(stringResource(R.string.bluetooth_disabled_title)) },
            text = { Text(stringResource(R.string.bluetooth_gateway_disabled_message)) },
            confirmButton = {
                Button(onClick = {
                    showBluetoothDialog = false
                    enableBluetoothLauncher.launch(BluetoothHelper.createEnableBluetoothIntent())
                }) { Text(stringResource(R.string.bluetooth_enable)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBluetoothDialog = false
                    pendingEnable = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    return { want ->
        if (want) {
            when {
                !BluetoothHelper.isBluetoothEnabled(context) -> {
                    pendingEnable = true
                    showBluetoothDialog = true
                }
                !BluetoothHelper.hasAdvertisePermissions(context) -> {
                    pendingEnable = true
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                        )
                    )
                }
                else -> {
                    GatewayService.start(context)
                    setEnabled(true)
                }
            }
        } else {
            GatewayService.stop(context)
            setEnabled(false)
        }
    }
}
