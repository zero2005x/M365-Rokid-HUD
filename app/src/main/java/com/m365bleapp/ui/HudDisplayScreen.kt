package com.m365bleapp.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m365bleapp.R
import com.m365bleapp.gateway.DisplayField
import com.m365bleapp.gateway.DisplayPrefsStore
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.LineSubtle
import com.m365bleapp.ui.theme.SurfaceRaised
import com.m365bleapp.ui.theme.TextPrimary
import com.m365bleapp.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Lets the rider choose which fields the glasses HUD renders.
 *
 * Design notes:
 *
 *  - Every row is a switch, not a checkbox or a chip. There is exactly one
 *    control type on the screen, so nothing has to be learned.
 *  - The list is grouped by where the data comes from (scooter / time & power /
 *    trip) rather than alphabetically, so the rider can find "battery" by
 *    thinking about the scooter.
 *  - Speed has no switch. [DisplayPrefsStore.setMask] refuses to store an empty
 *    mask, so a speed toggle would be a control that silently does nothing on
 *    the last click — worse than no control at all. It is shown as a locked row
 *    so the behaviour is explained rather than hidden.
 *  - Changes apply immediately. There is no Save button: the phone is usually
 *    in a pocket by the time the rider looks at the glasses, so an unsaved edit
 *    would be invisible and confusing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudDisplayScreen(
    store: DisplayPrefsStore,
    onBack: () -> Unit
) {
    val mask by store.mask.collectAsState()
    val textScale by store.textScalePercent.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hud_display_title)) },
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
            Text(
                text = stringResource(R.string.hud_display_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = Dimens.space16)
            )

            // --- Scooter ---
            SectionHeader(stringResource(R.string.hud_display_section_scooter))
            FieldSwitch(
                label = stringResource(R.string.hud_field_speed),
                icon = "⚡",
                checked = true,
                enabled = false,
                supporting = stringResource(R.string.hud_display_always_on),
                onCheckedChange = {}
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_scooter_battery),
                icon = "🛴",
                checked = mask and DisplayField.SCOOTER_BATTERY != 0,
                onCheckedChange = { store.setField(DisplayField.SCOOTER_BATTERY, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_remaining_range),
                icon = "📍",
                checked = mask and DisplayField.REMAINING_RANGE != 0,
                onCheckedChange = { store.setField(DisplayField.REMAINING_RANGE, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_avg_speed),
                icon = "📊",
                checked = mask and DisplayField.AVG_SPEED != 0,
                onCheckedChange = { store.setField(DisplayField.AVG_SPEED, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_temperature),
                icon = "🌡",
                checked = mask and DisplayField.TEMPERATURE != 0,
                onCheckedChange = { store.setField(DisplayField.TEMPERATURE, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_total_mileage),
                icon = "🧭",
                checked = mask and DisplayField.TOTAL_MILEAGE != 0,
                onCheckedChange = { store.setField(DisplayField.TOTAL_MILEAGE, it) }
            )

            // --- Time & power ---
            SectionHeader(stringResource(R.string.hud_display_section_glasses))
            FieldSwitch(
                label = stringResource(R.string.hud_field_time),
                icon = "🕐",
                checked = mask and DisplayField.TIME != 0,
                onCheckedChange = { store.setField(DisplayField.TIME, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_phone_battery),
                icon = "📱",
                checked = mask and DisplayField.PHONE_BATTERY != 0,
                onCheckedChange = { store.setField(DisplayField.PHONE_BATTERY, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_glasses_battery),
                icon = "👓",
                checked = mask and DisplayField.GLASSES_BATTERY != 0,
                onCheckedChange = { store.setField(DisplayField.GLASSES_BATTERY, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_signal_quality),
                icon = "📶",
                checked = mask and DisplayField.SIGNAL_QUALITY != 0,
                onCheckedChange = { store.setField(DisplayField.SIGNAL_QUALITY, it) }
            )

            // --- Trip ---
            SectionHeader(stringResource(R.string.hud_display_section_trip))
            FieldSwitch(
                label = stringResource(R.string.hud_field_trip_distance),
                icon = "🛣",
                checked = mask and DisplayField.TRIP_DISTANCE != 0,
                onCheckedChange = { store.setField(DisplayField.TRIP_DISTANCE, it) }
            )
            FieldSwitch(
                label = stringResource(R.string.hud_field_trip_time),
                icon = "⏱",
                checked = mask and DisplayField.TRIP_TIME != 0,
                onCheckedChange = { store.setField(DisplayField.TRIP_TIME, it) }
            )

            // --- Text size ---
            //
            // Glasses are worn at a fixed distance and the rider cannot lean in
            // to read them, so text size is a real accessibility control rather
            // than a cosmetic one.
            SectionHeader(stringResource(R.string.hud_display_text_size))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.space8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$textScale%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(64.dp)
                )
                Slider(
                    value = textScale.toFloat(),
                    onValueChange = { store.setTextScalePercent(it.roundToInt()) },
                    valueRange = M365HudGattProfileScaleRange,
                    steps = 5,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Dimens.space16))
            TextButton(onClick = { store.resetToDefault() }) {
                Text(stringResource(R.string.hud_display_reset))
            }
            Spacer(modifier = Modifier.height(Dimens.space32))
        }
    }
}

/** Slider range, taken from the wire contract so UI and protocol cannot drift. */
private val M365HudGattProfileScaleRange =
    com.m365bleapp.gateway.M365HudGattProfile.DISPLAY_PREFS_MIN_SCALE.toFloat()..
        com.m365bleapp.gateway.M365HudGattProfile.DISPLAY_PREFS_MAX_SCALE.toFloat()

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            top = Dimens.space24,
            bottom = Dimens.space8
        )
    )
}

@Composable
private fun FieldSwitch(
    label: String,
    icon: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space4)
            .background(SurfaceRaised, RoundedCornerShape(Dimens.cardCorner))
            .border(Dimens.cardBorder, LineSubtle, RoundedCornerShape(Dimens.cardCorner))
            // The whole row toggles, not just the switch: a 48dp switch is a
            // small target for a rider wearing gloves or looking at this
            // one-handed.
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = Dimens.cardPadding, vertical = Dimens.space12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.width(Dimens.space12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextSecondary
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
