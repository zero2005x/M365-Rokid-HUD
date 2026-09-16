package com.m365bleapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================
// M365 Rokid HUD — Theme
// ============================================================
//
// The app is dark-only, on purpose.
//
// It used to build both a dark and a light scheme and pick between them with
// `isSystemInDarkTheme()`. That is the wrong behaviour for this product: the
// screen is glanced at while riding, and the glasses HUD is always dark, so a
// light phone UI produced two different-looking halves of the same app and
// forced every colour decision to work at two very different luminances.
// Locking to the dark scheme means one palette, one set of contrast
// guarantees, and a phone screen that matches the glasses.
//
// Dynamic colour was already off and stays off — `dynamicColor` was declared
// but nothing ever passed `true`, and the branch is now removed rather than
// left as a dead parameter.

private val M365DarkColorScheme = darkColorScheme(
    // Accent: interactive elements only.
    primary = AccentCyan,
    onPrimary = SurfaceBase,
    primaryContainer = AccentCyanSoft,
    onPrimaryContainer = AccentCyan,

    // Secondary is deliberately NOT a second bright colour. It is used for
    // "connected / good" affordances, so it reuses the semantic green.
    secondary = LevelGreen,
    onSecondary = SurfaceBase,
    secondaryContainer = SurfaceSunken,
    onSecondaryContainer = LevelGreen,

    tertiary = LevelAmber,
    onTertiary = SurfaceBase,
    tertiaryContainer = SurfaceSunken,
    onTertiaryContainer = LevelAmber,

    error = LevelRed,
    onError = SurfaceBase,
    errorContainer = SurfaceSunken,
    onErrorContainer = LevelRed,

    // Surfaces.
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceSunken,
    surfaceContainerLow = SurfaceBase,
    surfaceContainerLowest = SurfaceBase,

    // Lines.
    outline = LineStrong,
    outlineVariant = LineSubtle,

    // Explicit, because `surfaceTint` defaults to `primary` and Material 3 then
    // tints every elevated surface cyan. That is what made cards look washed
    // out and slightly different from each other depending on elevation.
    surfaceTint = SurfaceRaised,
)

@Composable
fun M365BleAppTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // window.statusBarColor was REMOVED, not forgotten: the setter is
            // deprecated and is a complete no-op for apps targeting Android 15
            // (API 35) and above, which this module does (targetSdk 36). Under
            // enforced edge-to-edge the status bar is transparent and the app's
            // own content shows through it, so the colour is supplied by the
            // Compose surface underneath rather than by the window.
            //
            // The icon appearance still matters: this is what keeps the clock
            // and battery icons legible. Dark-only means these are always light.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = M365DarkColorScheme,
        typography = M365Typography,
        content = content
    )
}
