package com.m365bleapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m365bleapp.R
import com.m365bleapp.ui.theme.Dimens
import com.m365bleapp.ui.theme.LineSubtle
import com.m365bleapp.ui.theme.SurfaceRaised
import com.m365bleapp.ui.theme.TextPrimary
import com.m365bleapp.ui.theme.TextSecondary

/**
 * States plainly that a connected scooter cannot be read, and why.
 *
 * ## Why this is a first-class component and not an empty state
 *
 * A scooter can be **connected and recognised** while still producing no
 * telemetry: the app understands the protocol (Encryption2, or the Ninebot
 * legacy cipher) but the per-model register layout is not published for it.
 *
 * Showing zeros, or a blank dashboard, for that scooter would be a lie by
 * omission — the rider would reasonably read `0.0 km/h` and `0%` as real values.
 * A community integration that guessed at those registers produced "1924.9 km
 * range" and "1387.5 km/h", which were the ASCII bytes of a vehicle identifier.
 * **A wrong number is harder to notice than a missing one.**
 *
 * So the app says so, in the same visual language as the rest of the HUD, using
 * the warning accent rather than the error accent: nothing has gone wrong, and
 * there is nothing for the rider to retry.
 *
 * @param title headline; defaults to the generic "readings are unavailable"
 * @param body explanation. Pass the reason from the protocol layer so that the
 *   text and the technical cause cannot drift apart.
 */
@Composable
fun TelemetryUnsupportedCard(
    body: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.telemetry_unsupported_title),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(Dimens.cardCorner))
            .border(Dimens.cardBorder, LineSubtle, RoundedCornerShape(Dimens.cardCorner))
            .padding(Dimens.cardPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⚠",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(Dimens.space8))

        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

/**
 * A model name together with how much that identification can be trusted.
 *
 * ## Why the confidence is not optional
 *
 * Before connecting, nothing observable identifies a scooter's protocol. The
 * advertised name covers several wire generations of the same family, and the
 * GATT service list is not a discriminator: Xiaomi, Ninebot and current Segway
 * models all expose the same Nordic UART service, and some answer on only one of
 * the services they advertise.
 *
 * So a badge that shows a bare model name is claiming more than the scan can
 * know. Showing the confidence beside it keeps the claim honest, and gives the
 * rider a reason to use the manual override when the guess is wrong.
 *
 * Colour follows the same rule: the accent is reserved for verified readings,
 * so an unverified identification is deliberately quiet rather than attention
 * grabbing.
 */
@Composable
fun ModelBadge(
    identification: com.m365bleapp.protocol.Identification,
    modifier: Modifier = Modifier,
) {
    val truthful = identification.confidence.isTrustworthy
    val container = if (truthful) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (truthful) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        TextSecondary
    }

    androidx.compose.material3.Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(Dimens.cardCorner / 2),
    ) {
        Text(
            text = identification.badgeText,
            modifier = Modifier.padding(horizontal = Dimens.space8, vertical = Dimens.space2),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}
