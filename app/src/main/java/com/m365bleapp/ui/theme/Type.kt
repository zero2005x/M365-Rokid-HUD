package com.m365bleapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================
// M365 Rokid HUD — Typography
// ============================================================
//
// Every step from `bodyMedium` upward was increased. The previous scale ran
// 10–16sp for anything that was not a headline, which is below what is
// comfortably readable at arm's length on a handlebar mount — the reported
// `資訊太小`.
//
// Changes and why:
//
//   style            before → after   reason
//   -----------------------------------------------------------------------
//   bodySmall         12 → 14        smallest text that carries real content
//   bodyMedium        14 → 16        the workhorse body size; 14 is too small
//   labelMedium       12 → 14        form labels and units
//   labelSmall        10 → 12        10sp is below any sane legibility floor
//   titleSmall        14 → 16        card action labels
//   titleMedium       16 → 18        card titles
//   titleLarge        20 → 22        screen section headings
//   headlineSmall     22 → 24
//   headlineMedium    26 → 28
//   headlineLarge     32 → 34
//
// `displayLarge` also grew, because it is the hero speed numeral and 72sp was
// reported as too small for a glance while riding. Use `FontSizes.heroSpeed`
// for that element specifically; `displayLarge` remains the general large
// numeral style.
//
// The scale is intentionally NOT compressed at the top: the gap between the
// hero and the next size down is what creates the hierarchy. A screen should
// have exactly one hero.
//
// `FontFamily.SansSerif` is kept (the system font) rather than bundling a
// font: numerals stay familiar, the APK stays small, and this is a dashboard
// where legibility beats personality. `FontWeight.Bold` on numerals is
// deliberate — thin strokes disappear against a black background in daylight.

val M365Typography = Typography(
    // Display styles — hero numerals.
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 88.sp,
        lineHeight = 96.sp,
        letterSpacing = (-1).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.5).sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),

    // Headlines — screen and card-group headings.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Titles — card headers and list items.
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),

    // Body.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.3.sp
    ),

    // Labels — buttons, form labels, units.
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
