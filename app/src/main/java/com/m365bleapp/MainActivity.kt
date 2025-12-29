package com.m365bleapp

import android.os.Build
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

class MainActivity : ComponentActivity() {
    private lateinit var repository: ScooterRepository

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        repository = ScooterRepository(applicationContext)
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
        repository.disconnect()
    }
}
