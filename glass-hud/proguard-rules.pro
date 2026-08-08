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
# Rokid CXR SDK  --  REQUIRED, do not comment out
# ============================================
# The SDK is a real dependency (com.rokid.cxr:client-m), but CxrMClient only
# ever touches it reflectively:
#
#     Class.forName("com.rokid.cxr.CxrClient")
#     cxrClientClass.getMethod("sendData", ByteArray::class.java)
#     Proxy.newProxyInstance(..., arrayOf(Class.forName("com.rokid.cxr.DataCallback")))
#
# There is no compile-time reference anywhere, so without these rules R8 sees
# the whole package as unreachable and strips or renames it. The result is a
# release-only failure: Class.forName throws ClassNotFoundException,
# isSdkAvailable() reports false and the CXR-M transport silently disappears,
# while debug builds (no minification) keep working.
#
# `{ *; }` is deliberate: the method names above are resolved by string, so
# members must keep their original names too.
-keep class com.rokid.cxr.** { *; }
-keepclassmembers class com.rokid.cxr.** { *; }
-dontwarn com.rokid.cxr.**

# The callback is implemented with a dynamic Proxy over the SDK interface.
-keep interface com.rokid.cxr.** { *; }

# ============================================
# OkHttp optional TLS providers  --  REQUIRED
# ============================================
# The Rokid CXR SDK pulls in OkHttp transitively. OkHttp compiles against
# three optional TLS providers (BouncyCastle JSSE, Conscrypt, OpenJSSE) and
# picks whichever is present at runtime, guarding each probe with try/catch.
# None of them is on this project's classpath, which is fine at runtime but
# makes R8 fail the build:
#
#   ERROR: Missing class org.conscrypt.Conscrypt (referenced from:
#          boolean okhttp3.internal.platform.ConscryptPlatform$Companion...)
#
# -dontwarn is the correct response, not -keep: the classes genuinely do not
# exist and must not be kept. Wildcards are used instead of the exact nine
# classes R8 listed so an OkHttp upgrade cannot reintroduce the failure.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

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
