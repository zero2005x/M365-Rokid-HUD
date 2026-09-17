package com.m365bleapp.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing and size scale for the "Instrument" layout.
 *
 * Why this file exists: the screens previously hard-coded a mix of 4/6/8/12/16/
 * 24.dp paddings and 10–20.sp font sizes at each call site. That is what made
 * the UI read as cluttered — unrelated elements ended up at nearly the same
 * visual weight, so nothing looked primary (`資訊太小`, `有點混亂`).
 *
 * Rules:
 *  - Spacing comes from this scale only. No ad-hoc `.dp` literals in screens.
 *  - A screen has ONE hero element. Everything else is at least two steps down
 *    the type scale from it.
 */
object Dimens {
    // --- Spacing scale -------------------------------------------------------
    val space2 = 2.dp
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp

    /** Horizontal page gutter, used by every screen. */
    val gutter = space16

    /** Inner padding of a card. */
    val cardPadding = space16

    /** Vertical gap between sibling cards. */
    val cardGap = space12

    // --- Touch targets -------------------------------------------------------
    /**
     * Minimum interactive size.
     *
     * The Material minimum is 48dp and this is used for anything that must be
     * hittable while wearing gloves or while the phone is mounted on a
     * handlebar. Buttons in the old layout went as small as 36dp.
     */
    val touchTarget = 48.dp

    /** Height of the primary action button on the home screen. */
    val primaryButtonHeight = 56.dp

    // --- Component sizes -----------------------------------------------------
    val cardCorner = 16.dp
    val cardBorder = 1.dp
    val dividerThickness = 1.dp

    /** Battery / level bar height. */
    val barHeight = 8.dp
    val barCorner = 4.dp
}

/**
 * Font sizes that are NOT part of the Material type scale.
 *
 * The hero speed numeral is deliberately larger than `displayLarge` (72sp): it
 * is the one thing a rider glances at, and the previous 72sp was reported as
 * too small to read at a glance. Everything else was moved UP the scale in
 * `Type.kt` rather than given a custom size here.
 */
object FontSizes {
    /** Hero speed numeral on the dashboard. */
    val heroSpeed = 104.sp

    /** Unit label next to the hero numeral ("km/h"). */
    val heroUnit = 24.sp

    /** A secondary metric value inside a tile. */
    val tileValue = 28.sp
}
