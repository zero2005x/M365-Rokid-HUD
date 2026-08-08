package com.m365bleapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================
// M365 Rokid HUD - Original Theme System
// Custom brand colors for Material 3
// ============================================================

private val M365DarkColorScheme = darkColorScheme(
    primary = ScooterCyan,
    onPrimary = NeutralWhite,
    primaryContainer = ScooterCyanDark,
    onPrimaryContainer = ScooterCyanLight,
    
    secondary = EnergyGreen,
    onSecondary = NeutralWhite,
    secondaryContainer = EnergyGreenDark,
    onSecondaryContainer = EnergyGreenLight,
    
    tertiary = CautionAmber,
    onTertiary = NeutralDark,
    tertiaryContainer = CautionAmberDark,
    onTertiaryContainer = CautionAmberLight,
    
    error = DangerRed,
    onError = NeutralWhite,
    errorContainer = DangerRedDark,
    onErrorContainer = DangerRedLight,
    
    background = SurfaceDark,
    onBackground = NeutralLight,
    surface = SurfaceDark,
    onSurface = NeutralLight,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = NeutralMedium,
    
    outline = NeutralMedium,
    outlineVariant = CardDark
)

private val M365LightColorScheme = lightColorScheme(
    primary = ScooterCyanDark,
    onPrimary = NeutralWhite,
    primaryContainer = ScooterCyanLight,
    onPrimaryContainer = ScooterCyanDark,
    
    secondary = EnergyGreenDark,
    onSecondary = NeutralWhite,
    secondaryContainer = EnergyGreenLight,
    onSecondaryContainer = EnergyGreenDark,
    
    tertiary = CautionAmberDark,
    onTertiary = NeutralWhite,
    tertiaryContainer = CautionAmberLight,
    onTertiaryContainer = CautionAmberDark,
    
    error = DangerRed,
    onError = NeutralWhite,
    errorContainer = DangerRedLight,
    onErrorContainer = DangerRedDark,
    
    background = SurfaceLight,
    onBackground = NeutralDark,
    surface = SurfaceLight,
    onSurface = NeutralDark,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = NeutralMedium,
    
    outline = NeutralMedium,
    outlineVariant = SurfaceVariantLight
)

@Composable
fun M365BleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic color for brand consistency
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> M365DarkColorScheme
        else -> M365LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = M365Typography,
        content = content
    )
}

