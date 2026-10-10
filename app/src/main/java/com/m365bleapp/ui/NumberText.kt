package com.m365bleapp.ui

import kotlin.math.abs

/**
 * One-decimal text that never shows "-0.0".
 *
 * Speed keeps its sign so reverse movement is visible, but a reading such as
 * -0.012 km/h would otherwise round to "-0.0", which looks like a fault.
 */
internal fun formatOneDecimal(value: Double): String =
    "%.1f".format(if (abs(value) < 0.05) 0.0 else value)
