package com.m365hud.glass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.m365hud.glass.ui.theme.GlassHudTheme

class MainActivity : ComponentActivity() {
    
    private lateinit var bleClient: BleClient
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startBleScan()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on for HUD
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Initialize BLE client
        bleClient = BleClient(this)
        
        setContent {
            GlassHudTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val connectionState by bleClient.connectionState.collectAsState()
                    val telemetry by bleClient.telemetry.collectAsState()
                    val timeData by bleClient.timeData.collectAsState()
                    
                    HudScreen(
                        telemetry = telemetry,
                        timeData = timeData,
                        connectionState = connectionState,
                        onRetryClick = { checkPermissionsAndScan() }
                    )
                }
            }
        }
        
        // Start scanning on launch
        checkPermissionsAndScan()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
    }
    
    private fun checkPermissionsAndScan() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            startBleScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }
    
    private fun startBleScan() {
        bleClient.startScan()
    }
}
