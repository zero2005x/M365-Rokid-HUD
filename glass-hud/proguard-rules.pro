# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# ============================================
# BLE related classes
# ============================================
-keep class android.bluetooth.** { *; }

# ============================================
# Compose
# ============================================
-keep class androidx.compose.** { *; }

# ============================================
# Rokid CXR SDK (for future integration)
# If using official Rokid CXR-M SDK, uncomment below
# ============================================
# -keep class com.rokid.cxr.** { *; }
# -dontwarn com.rokid.cxr.**

# ============================================
# Retrofit (if using with Rokid SDK)
# ============================================
# -keepattributes Signature, InnerClasses, EnclosingMethod
# -keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
# -keepclassmembers,allowshrinking,allowobfuscation interface * {
#     @retrofit2.http.* <methods>;
# }
# -dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
# -dontwarn javax.annotation.**
# -dontwarn kotlin.Unit
# -dontwarn retrofit2.KotlinExtensions
# -dontwarn retrofit2.KotlinExtensions$*

# ============================================
# OkHttp (if using with Rokid SDK)
# ============================================
# -dontwarn okhttp3.**
# -dontwarn okio.**
# -keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================
# Gson (if using with Rokid SDK)
# ============================================
# -keepattributes Signature
# -keepattributes *Annotation*
# -dontwarn sun.misc.**
# -keep class com.google.gson.stream.** { *; }
# -keep class * extends com.google.gson.TypeAdapter
# -keep class * implements com.google.gson.TypeAdapterFactory
# -keep class * implements com.google.gson.JsonSerializer
# -keep class * implements com.google.gson.JsonDeserializer
