# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep BLE related classes
-keep class android.bluetooth.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
