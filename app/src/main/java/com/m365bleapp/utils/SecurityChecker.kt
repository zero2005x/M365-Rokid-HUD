package com.m365bleapp.utils

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Security utility to detect potential security risks on the device.
 * 
 * This is a P3 priority feature - provides warnings but does NOT block functionality.
 * Users with rooted devices can still use the app, but are informed of the risks.
 */
object SecurityChecker {
    
    private const val TAG = "SecurityChecker"
    
    /**
     * Result of security check
     */
    data class SecurityStatus(
        val isRooted: Boolean,
        val isEmulator: Boolean,
        val isDebugBuild: Boolean,
        val warnings: List<String>
    ) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
        
        fun getWarningMessage(): String {
            return if (warnings.isEmpty()) {
                "Device security check passed"
            } else {
                "Security warnings: ${warnings.joinToString(", ")}"
            }
        }
    }
    
    /**
     * Perform security check on the device.
     * 
     * This checks for:
     * - Root access (su binary, common root paths)
     * - Emulator detection
     * - Debug build
     * 
     * Note: This is NOT a comprehensive security solution. A determined attacker
     * can bypass these checks. This is meant to inform users of potential risks.
     */
    fun checkSecurity(isDebugBuild: Boolean = false): SecurityStatus {
        val warnings = mutableListOf<String>()
        
        val isRooted = checkRootAccess()
        val isEmulator = checkEmulator()
        
        if (isRooted) {
            warnings.add("Device may be rooted - token security reduced")
            Log.w(TAG, "Root access detected on device")
        }
        
        if (isEmulator) {
            warnings.add("Running on emulator")
            Log.w(TAG, "Emulator detected")
        }
        
        if (isDebugBuild) {
            warnings.add("Debug build - not for production")
            Log.w(TAG, "Debug build detected")
        }
        
        return SecurityStatus(
            isRooted = isRooted,
            isEmulator = isEmulator,
            isDebugBuild = isDebugBuild,
            warnings = warnings
        )
    }
    
    /**
     * Check for common root indicators.
     * 
     * This is NOT foolproof - root hiding tools like Magisk Hide can bypass this.
     * It's meant as a basic check to inform users.
     */
    private fun checkRootAccess(): Boolean {
        // Check for su binary in common locations
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        for (path in suPaths) {
            if (File(path).exists()) {
                return true
            }
        }
        
        // Check for common root management apps
        val rootApps = listOf(
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/system/app/Magisk.apk"
        )
        
        for (path in rootApps) {
            if (File(path).exists()) {
                return true
            }
        }
        
        // Check build tags
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }
        
        return false
    }
    
    /**
     * Check if running on an emulator.
     */
    private fun checkEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || Build.PRODUCT == "google_sdk"
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("emulator")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu"))
    }
    
    /**
     * Get a user-friendly security warning message.
     * Returns null if no warnings.
     */
    fun getSecurityWarningForUser(status: SecurityStatus): String? {
        if (!status.hasWarnings) return null
        
        val messages = mutableListOf<String>()
        
        if (status.isRooted) {
            messages.add("⚠️ Your device appears to be rooted. " +
                    "Scooter authentication tokens stored on this device may be accessible " +
                    "to other apps or users with root access.")
        }
        
        if (status.isEmulator) {
            messages.add("ℹ️ Running on emulator. Some features may not work correctly.")
        }
        
        return messages.joinToString("\n\n")
    }
}
