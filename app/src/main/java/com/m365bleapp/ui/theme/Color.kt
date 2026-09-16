package com.m365bleapp.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// M365 Rokid HUD — "Instrument" palette
// ============================================================
//
// Design rules (deliberate — keep them when editing):
//
// 1. SURFACES ARE NEAR-BLACK. A scooter dashboard is used at night and in
//    motion. An OLED-black background gives the highest possible contrast for
//    large numerals and draws the least power on the phone that is running BLE
//    plus the gateway.
//
// 2. EXACTLY ONE ACCENT. `AccentCyan` marks *interactive* things — the active
//    switch, the primary button, the connected indicator. It is NOT used for
//    body text, not for card fills, and not for decoration. The previous
//    palette used the accent as text colour, container colour and border
//    colour simultaneously, which is why nothing looked more important than
//    anything else.
//
// 3. SEMANTIC COLOURS ARE RESERVED. Green/amber/red mean battery level and
//    staleness, nothing else. They never decorate.
//
// 4. NO DYNAMIC COLOUR. The theme does not read the wallpaper. A HUD must look
//    identical on every device.
//
// The same accent is used by the glasses HUD (glass-hud/HudScreen.kt) so the
// phone and the glasses read as one product.
//
// Removed in this revision: Purple80/PurpleGrey80/Pink80 and their `40`
// variants. They were Material 3 template leftovers with no relationship to
// this product — nothing referenced them except the template itself.

// --- The single accent -------------------------------------------------------
val AccentCyan = Color(0xFF22D3EE)
val AccentCyanDim = Color(0xFF0E7490)
val AccentCyanSoft = Color(0x3322D3EE) // 20% — tonal fills only

// --- Semantic (reserved; never decorative) -----------------------------------
val LevelGreen = Color(0xFF34D399)
val LevelAmber = Color(0xFFFBBF24)
val LevelRed = Color(0xFFF87171)

// --- Surfaces ----------------------------------------------------------------
/** True black. The canvas. */
val SurfaceBase = Color(0xFF000000)
/** Raised card. */
val SurfaceRaised = Color(0xFF0B0B0D)
/** Inset well / pressed state. */
val SurfaceSunken = Color(0xFF141417)

// --- Text --------------------------------------------------------------------
/** Primary text: pure white, for numerals and headings. */
val TextPrimary = Color(0xFFFFFFFF)
/** Secondary text: labels, units, captions. */
val TextSecondary = Color(0xFF9BA1A6)
/**
 * Tertiary: disabled and de-emphasised.
 *
 * Not a low-alpha white: at this value it is still legible for the small label
 * text it is used on, where a 38%-alpha white was not.
 */
val TextTertiary = Color(0xFF6B7075)

// --- Lines -------------------------------------------------------------------
/** Card border. */
val LineSubtle = Color(0xFF232327)
/** Divider / inactive track. */
val LineStrong = Color(0xFF35353A)

// --- Legacy aliases ----------------------------------------------------------
// Kept so call sites that have not migrated yet keep compiling. New code should
// use the names above. Delete once nothing references these.
val ScooterCyan = AccentCyan
val ScooterCyanDark = AccentCyanDim
val ScooterCyanLight = AccentCyan
val EnergyGreen = LevelGreen
val EnergyGreenDark = LevelGreen
val EnergyGreenLight = LevelGreen
val CautionAmber = LevelAmber
val CautionAmberDark = LevelAmber
val CautionAmberLight = LevelAmber
val DangerRed = LevelRed
val DangerRedDark = LevelRed
val DangerRedLight = LevelRed
val NeutralDark = SurfaceBase
val NeutralMedium = TextSecondary
val NeutralLight = TextPrimary
val NeutralWhite = TextPrimary
val SurfaceDark = SurfaceBase
val SurfaceVariantDark = SurfaceSunken
val CardDark = SurfaceRaised
val SurfaceLight = SurfaceBase
val SurfaceVariantLight = SurfaceSunken
val CardLight = SurfaceRaised
