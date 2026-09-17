package com.m365bleapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.m365bleapp.R
import androidx.compose.runtime.collectAsState
import com.m365bleapp.protocol.ModelOverrideStore
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.LineSubtle
import com.m365bleapp.ui.theme.SurfaceRaised
import com.m365bleapp.ui.theme.TextPrimary
import com.m365bleapp.ui.theme.TextSecondary

/**
 * The settings hub.
 *
 * ## Why this exists rather than more icons in the app bar
 *
 * The home screen's app bar used to carry four icon buttons — glasses display,
 * logs, language, and (on the dashboard) more. Four unlabelled icons is a
 * guessing game, and it put diagnostics next to everyday controls with equal
 * weight.
 *
 * Everything that is not part of riding now lives here, grouped by what the
 * rider is trying to do, with text labels so nothing has to be learned:
 *
 *  - **Display** — what the glasses show.
 *  - **Glasses Connection** — the gateway toggle and its battery caveat.
 *  - **Advanced** — logging, log export, language. Anything diagnostic or
 *    rarely touched.
 *  - **About** — version, support link.
 *
 * The advanced entries are not hidden behind a further confirmation: burying
 * diagnostics two levels deep makes bug reports worse, and they are not
 * dangerous. One tap is enough separation now that they are off the riding
 * surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: ScooterRepository,
    onBack: () -> Unit,
    onOpenHudDisplay: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenLogViewer: () -> Unit,
    onOpenLogging: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter)
        ) {
            // --- Display ---
            SettingsSection(stringResource(R.string.settings_section_display))
            SettingsRow(
                icon = "👓",
                title = stringResource(R.string.hud_display_title),
                subtitle = stringResource(R.string.hud_display_subtitle),
                onClick = onOpenHudDisplay
            )

            // --- Glasses connection ---
            SettingsSection(stringResource(R.string.settings_section_gateway))
            GatewayRow()
            WifiGatewayRow(context)
            BatteryOptimizationRow(context)

            // --- Advanced ---
            SettingsSection(stringResource(R.string.settings_section_advanced))
            SettingsRow(
                icon = "🌐",
                title = stringResource(R.string.language_title),
                onClick = onOpenLanguage
            )
            SettingsRow(
                icon = "⚙️",
                title = stringResource(R.string.logs_title),
                subtitle = stringResource(R.string.log_enable_logging),
                onClick = onOpenLogging
            )

            // --- Testing ---
            // Deliberately last and clearly labelled. Without a scooter there is
            // no other way to put a value on the glasses, so this is the only
            // end-to-end check available off-hardware.
            SettingsSection(stringResource(R.string.demo_section))
            DemoRideRow(repository)
            // "Experimental models" and "protocol" both belong here per the
            // objective, and they belong together: the override decides which
            // register layout the app tries, and the protocol line reports what
            // it turned out to be. Splitting them across screens meant the rider
            // had to guess which one to change.
            ModelOverrideSettingsRow(context)

            ProtocolSettingsRow(repository)

            SettingsRow(
                icon = "📄",
                title = stringResource(R.string.log_viewer_title),
                onClick = onOpenLogViewer
            )
            // Sharing the connection state is the fastest route to a useful bug
            // report: it answers "which layout, which dialect, which MTU, which
            // model" without the reporter having to know those are the questions.
            SettingsRow(
                icon = "📋",
                title = stringResource(R.string.diagnostics_title),
                subtitle = stringResource(R.string.diagnostics_subtitle),
                onClick = {
                    val text = repository.connectionDiagnostics()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "M365 HUD connection diagnostics")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }
            )

            // --- About ---
            SettingsSection(stringResource(R.string.settings_section_about))
            SettingsRow(
                icon = "💬",
                title = stringResource(R.string.settings_feedback),
                subtitle = stringResource(R.string.settings_feedback_subtitle),
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/zero2005x/M365-Rokid-HUD/issues")
                    )
                    runCatching { context.startActivity(intent) }
                }
            )

            Spacer(modifier = Modifier.height(Dimens.space32))
        }
    }
}

/**
 * The gateway toggle.
 *
 * States the battery-optimization requirement inline instead of as a dismissible
 * banner on the riding screen: Android killing the foreground service is the
 * single most common cause of "the glasses keep disconnecting", and it belongs
 * next to the switch that depends on it.
 */
@Composable
private fun GatewayRow() {
    var enabled by remember {
        mutableStateOf(com.m365bleapp.gateway.GatewayService.isRunning())
    }
    // Shared safe enabler: checks Bluetooth + BLUETOOTH_ADVERTISE/CONNECT before
    // starting the service, so this toggle can no longer start a gateway that
    // then silently fails to advertise.
    val enableGateway = rememberGatewayEnabler(setEnabled = { enabled = it })

    SettingsRow(
        icon = "📡",
        title = stringResource(R.string.gateway_hud),
        subtitle = if (enabled) {
            stringResource(R.string.gateway_broadcasting)
        } else {
            stringResource(R.string.glasses_connect_hint)
        },
        trailing = {
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { want -> enableGateway(want) }
            )
        },
        onClick = { enableGateway(!enabled) }
    )
}

/**
 * WiFi gateway toggle — the lower-latency alternative transport to the BLE
 * gateway. It lives here rather than on the riding screen because two transport
 * switches on the glance surface is clutter; the rider picks a transport once.
 */
@Composable
private fun WifiGatewayRow(context: Context) {
    var enabled by remember {
        mutableStateOf(com.m365bleapp.gateway.wifi.WifiGatewayService.isRunning())
    }

    SettingsRow(
        icon = "📶",
        title = stringResource(R.string.wifi_gateway_title),
        subtitle = if (enabled) {
            stringResource(R.string.wifi_gateway_starting)
        } else {
            stringResource(R.string.wifi_gateway_hint)
        },
        trailing = {
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    if (want) {
                        com.m365bleapp.gateway.wifi.WifiGatewayService.start(context)
                    } else {
                        com.m365bleapp.gateway.wifi.WifiGatewayService.stop(context)
                    }
                    enabled = want
                }
            )
        },
        onClick = {
            if (enabled) {
                com.m365bleapp.gateway.wifi.WifiGatewayService.stop(context)
            } else {
                com.m365bleapp.gateway.wifi.WifiGatewayService.start(context)
            }
            enabled = !enabled
        }
    )
}

/**
 * Demo-ride toggle: feeds synthetic telemetry with no scooter attached.
 *
 * ## Why this is offered in the UI rather than hidden
 *
 * A phone cannot impersonate a scooter over BLE, so off-hardware there is no
 * other way to get a value onto the glasses. This is the only end-to-end check of
 * the display path available without a scooter.
 *
 * ## What it does not do
 *
 * It bypasses the frame codec, the crypto session and every register parser, so
 * a good demo run proves the display chain and nothing about the protocol. The
 * subtitle says so on screen, and the generated samples are marked `DEMO` in the
 * logs, so a demo reading is never mistaken for live telemetry.
 *
 * State is read from the repository on each recomposition rather than remembered
 * locally, because the demo also stops through `disconnect()`; a remembered flag
 * would then disagree with reality.
 */
@Composable
private fun DemoRideRow(repository: ScooterRepository) {
    val running = repository.isDemoRunning

    SettingsRow(
        icon = "🧪",
        title = stringResource(R.string.demo_title),
        subtitle = if (running) {
            stringResource(R.string.demo_running)
        } else {
            stringResource(R.string.demo_hint)
        },
        trailing = {
            androidx.compose.material3.Switch(
                checked = running,
                onCheckedChange = { want ->
                    if (want) repository.startDemo() else repository.stopDemo()
                }
            )
        },
        onClick = {
            if (running) repository.stopDemo() else repository.startDemo()
        }
    )
}

/**
 * Battery-optimization exemption.
 *
 * Only shown when the app is actually being optimised, because that is the only
 * time it is actionable. A permanent "everything is fine" row is noise.
 */
@Composable
private fun BatteryOptimizationRow(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isOptimized = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
    if (!isOptimized) return

    SettingsRow(
        icon = "🔋",
        title = stringResource(R.string.battery_optimization_title),
        subtitle = stringResource(R.string.battery_optimization_warning),
        onClick = {
            // Two intents, because the direct request only works when the app
            // declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and Play policy
            // restricts that. Asking for the settings page always works.
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { context.startActivity(intent) }
        }
    )
}

@Composable
private fun SettingsSection(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = Dimens.space24, bottom = Dimens.space8)
    )
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space4)
            .background(SurfaceRaised, RoundedCornerShape(Dimens.cardCorner))
            .border(Dimens.cardBorder, LineSubtle, RoundedCornerShape(Dimens.cardCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.cardPadding, vertical = Dimens.space12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.width(Dimens.space12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}

/**
 * Lets the rider pin the scooter model from Settings.
 *
 * The scan screen also offers this, on the app bar, because that is where a
 * failing identification is first noticed. This entry exists so the setting is
 * discoverable without having to remember what an unlabelled icon does.
 */
@Composable
private fun ModelOverrideSettingsRow(context: Context) {
    val store = remember { ModelOverrideStore.getInstance(context) }
    val override by store.override.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }

    if (pickerOpen) {
        ModelOverrideDialog(
            current = override,
            onDismiss = { pickerOpen = false },
            onSelect = { choice ->
                store.set(choice)
                pickerOpen = false
            }
        )
    }

    SettingsRow(
        icon = "\uD83D\uDE97",
        title = stringResource(R.string.model_override_title),
        subtitle = override?.displayName ?: stringResource(R.string.model_override_automatic),
        onClick = { pickerOpen = true }
    )
}

/**
 * Reports what the app detected about the connected scooter's protocol.
 *
 * Read-only and live. It surfaces the two facts that a "connects but shows
 * nothing" report turns on — which GATT layout answered, and which protocol
 * dialect a completed handshake proved — so the rider can send them without
 * knowing to look.
 */
@Composable
private fun ProtocolSettingsRow(repository: ScooterRepository) {
    // Resolved here rather than passed in, so the row is self-contained.
    val context = LocalContext.current
    val profile by repository.activeProfile.collectAsState()
    val protocol by repository.detectedProtocol.collectAsState()

    val summary = buildString {
        append(profile?.displayName ?: stringResource(R.string.diagnostics_no_layout))
        append(" \u00B7 ")
        append(protocol.label)
    }

    SettingsRow(
        icon = "\uD83D\uDD0C",
        title = stringResource(R.string.settings_protocol_title),
        subtitle = summary,
        onClick = {
            // Sharing is the primary action: the value of these fields is
            // almost entirely in getting them into a bug report.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "M365 HUD connection diagnostics")
                putExtra(Intent.EXTRA_TEXT, repository.connectionDiagnostics())
            }
            runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        }
    )
}
