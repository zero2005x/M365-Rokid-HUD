package com.m365bleapp.utils

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Helper class for Bluetooth permission and state management.
 * 
 * Provides utilities for:
 * - Checking if Bluetooth is enabled
 * - Checking if required permissions are granted
 * - Requesting Bluetooth to be enabled
 * - Opening system Bluetooth settings
 */
object BluetoothHelper {
    
    private const val TAG = "BluetoothHelper"
    
    /**
     * Status of Bluetooth requirements check
     */
    sealed class BluetoothStatus {
        /** Bluetooth is enabled and all permissions are granted */
        object Ready : BluetoothStatus()
        
        /** Bluetooth is not supported on this device */
        object NotSupported : BluetoothStatus()
        
        /** Bluetooth adapter is disabled */
        object Disabled : BluetoothStatus()
        
        /** Missing required permissions */
        data class MissingPermissions(val permissions: List<String>) : BluetoothStatus()
    }
    
    /**
     * Get the required Bluetooth permissions based on Android version
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            // Android 11 and below
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
    
    /**
     * Get the required permissions for BLE advertising (Gateway feature)
     */
    fun getAdvertisePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            // Pre-Android 12 doesn't need special advertise permission
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }
    
    /**
     * Check if all required Bluetooth permissions are granted
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if BLE advertise permissions are granted (for Gateway)
     */
    fun hasAdvertisePermissions(context: Context): Boolean {
        return getAdvertisePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get list of missing advertise permissions
     */
    fun getMissingAdvertisePermissions(context: Context): List<String> {
        return getAdvertisePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if Bluetooth is supported on this device
     */
    fun isBluetoothSupported(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter != null
    }
    
    /**
     * Check if Bluetooth is currently enabled
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }
    
    /**
     * Comprehensive Bluetooth status check
     * Returns the current status including permissions and enabled state
     */
    fun checkBluetoothStatus(context: Context): BluetoothStatus {
        // Check if Bluetooth is supported
        if (!isBluetoothSupported(context)) {
            Log.w(TAG, "Bluetooth not supported on this device")
            return BluetoothStatus.NotSupported
        }
        
        // Check permissions first
        val missingPermissions = getMissingPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: $missingPermissions")
            return BluetoothStatus.MissingPermissions(missingPermissions)
        }
        
        // Check if Bluetooth is enabled
        if (!isBluetoothEnabled(context)) {
            Log.w(TAG, "Bluetooth is disabled")
            return BluetoothStatus.Disabled
        }
        
        Log.d(TAG, "Bluetooth is ready")
        return BluetoothStatus.Ready
    }
    
    /**
     * Check if Bluetooth is ready for Gateway (advertising) feature
     */
    fun checkGatewayBluetoothStatus(context: Context): BluetoothStatus {
        // Check if Bluetooth is supported
        if (!isBluetoothSupported(context)) {
            return BluetoothStatus.NotSupported
        }
        
        // Check advertise permissions
        val missingPermissions = getMissingAdvertisePermissions(context)
        if (missingPermissions.isNotEmpty()) {
            return BluetoothStatus.MissingPermissions(missingPermissions)
        }
        
        // Check if Bluetooth is enabled
        if (!isBluetoothEnabled(context)) {
            return BluetoothStatus.Disabled
        }
        
        return BluetoothStatus.Ready
    }
    
    /**
     * Create an Intent to request Bluetooth enable
     * Note: On Android 12+, this requires BLUETOOTH_CONNECT permission
     */
    @Suppress("DEPRECATION")
    fun createEnableBluetoothIntent(): Intent {
        return Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    }
    
    /**
     * Create an Intent to open system Bluetooth settings
     */
    fun createBluetoothSettingsIntent(): Intent {
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }
    
    /**
     * Create an Intent to open app settings (for granting permissions manually)
     */
    fun createAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
    }
    
    /**
     * Get user-friendly error message for BluetoothStatus
     */
    fun getStatusMessage(context: Context, status: BluetoothStatus): String {
        return when (status) {
            is BluetoothStatus.Ready -> 
                context.getString(com.m365bleapp.R.string.bluetooth_ready)
            is BluetoothStatus.NotSupported -> 
                context.getString(com.m365bleapp.R.string.bluetooth_not_supported)
            is BluetoothStatus.Disabled -> 
                context.getString(com.m365bleapp.R.string.bluetooth_disabled)
            is BluetoothStatus.MissingPermissions -> 
                context.getString(com.m365bleapp.R.string.bluetooth_permission_required)
        }
    }
}
