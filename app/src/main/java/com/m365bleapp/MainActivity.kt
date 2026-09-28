package com.m365bleapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
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
        drawEdgeToEdge()
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
    
    /**
     * Edge-to-edge on every supported API level, without androidx.activity's
     * `enableEdgeToEdge()`.
     *
     * That helper sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` on API 28-29
     * (its `EdgeToEdgeApi28`), and because minSdk is 28 that code path is in
     * the release DEX. Play Console flags the constant as an API deprecated in
     * Android 15, whatever device actually runs it.
     *
     * The rest of what the helper did is covered here or elsewhere:
     *  - bar colours: transparent via the window theme (themes.xml);
     *  - icon appearance: M365BleAppTheme sets light icons (dark-only app);
     *  - cutout: ALWAYS on API 30+, which is also what the helper used there.
     *    On API 28-29 the platform DEFAULT already lets a portrait window that
     *    extends under the status bar draw into the cutout.
     */
    private fun drawEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Otherwise the system paints a translucent scrim behind the
            // gesture/button navigation bar on API 29-34.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
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
