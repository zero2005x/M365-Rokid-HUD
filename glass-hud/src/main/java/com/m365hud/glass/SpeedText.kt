package com.m365hud.glass

import kotlin.math.abs

/**
 * HUD speed text that never shows "-0.0".
 *
 * The phone forwards signed speed so reverse movement is visible, but a reading
 * such as -0.012 km/h would otherwise round to "-0.0" on the glasses.
 */
internal fun formatSpeedKmh(kmh: Float): String =
    "%.1f".format(if (abs(kmh) < 0.05f) 0f else kmh)
