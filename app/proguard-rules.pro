# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# ============================================
# Missing Annotation Classes (Google Tink / Crypto)
# ============================================
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.checkerframework.**
-dontwarn com.google.crypto.tink.**

# Keep Tink classes used by AndroidX Security Crypto
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }

# ============================================
# JNA (Java Native Access) - Required for Rust FFI
# ============================================
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# ============================================
# Kotlin Coroutines
# ============================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.** { *; }

# ============================================
# Jetpack Compose
# ============================================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ============================================
# AndroidX Security Crypto
# ============================================
-keep class androidx.security.crypto.** { *; }

# ============================================
# Application Classes - Keep native interface
# ============================================
-keep class com.m365bleapp.** { *; }

# ============================================
# General Android
# ============================================
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
