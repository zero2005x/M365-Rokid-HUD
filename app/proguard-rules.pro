# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# Keep rules here must be NARROW. This file used to keep androidx.compose.**,
# com.m365bleapp.**, Tink, security-crypto and coroutines-android wholesale,
# which left most of the DEX unshrunk, unobfuscated and unoptimized: Play
# Console reported 13% on all three and flags anything below 25%. Every one
# of those libraries ships its own consumer rules; a blanket `-keep` here only
# overrides them with "touch nothing".

# ============================================
# Missing Annotation Classes (Google Tink / Crypto)
# ============================================
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.checkerframework.**
-dontwarn com.google.crypto.tink.**

# Tink (via AndroidX Security Crypto) parses its keysets with shaded
# protobuf-lite, which reads message fields reflectively.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# ============================================
# JNA (Java Native Access)
# ============================================
# JNA binds Java methods to native symbols by name at runtime.
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# ============================================
# Rust FFI (ninebot-ffi)
# ============================================
# The native library exports Java_com_m365bleapp_ffi_M365Native_<method>, so
# the class name and the native method names must survive. The Rust side does
# not call back into Java (no FindClass / CallMethod), so nothing else in the
# app is reached by name from native code.
-keep class com.m365bleapp.ffi.M365Native {
    native <methods>;
}

# ============================================
# Kotlin Coroutines
# ============================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ============================================
# General Android
# ============================================
# Readable stack traces in Play Console (deobfuscated with mapping.txt).
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================
# Remove Verbose Logging in Release Build
# ============================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
