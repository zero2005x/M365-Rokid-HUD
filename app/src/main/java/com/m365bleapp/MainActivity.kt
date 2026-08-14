package com.m365bleapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.ui.NavHostContainer
import com.m365bleapp.ui.theme.M365BleAppTheme
import com.m365bleapp.utils.LocaleHelper

class MainActivity : ComponentActivity() {
    private lateinit var repository: ScooterRepository

    // NOTE: the permission list that used to live here was dead code — nothing
    // read it, because the actual request flow moved into ScanScreen (see the
    // comment in onCreate). It was also a third stale copy of the same list,
    // still asking for ACCESS_FINE_LOCATION on API 31+ where the manifest no
    // longer declares it. BluetoothHelper.getRequiredPermissions() is now the
    // single source of truth.

    override fun attachBaseContext(newBase: Context) {
        // Apply saved locale before activity is created
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        repository = ScooterRepository.getInstance(applicationContext)
        repository.init()

        // Prompt for permissions at startup is now handled in ScanScreen to prevent race conditions 
        // with the scanning logic. MainActivity just initializes the repository.

        setContent {
            M365BleAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHostContainer(repository = repository)
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Only disconnect if the activity is actually finishing (user pressed back, etc.)
        // Do NOT disconnect on configuration changes or activity recreation
        // because the GatewayService needs the scooter connection to remain active
        if (isFinishing) {
            // disconnect() only touches BLE when a connection exists, which
            // implies BLUETOOTH_CONNECT was already granted at connect time.
            @SuppressLint("MissingPermission")
            repository.disconnect()
        }
    }
}
