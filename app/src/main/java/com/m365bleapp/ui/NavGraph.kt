package com.m365bleapp.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.m365bleapp.vehicle.modelName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.m365bleapp.MainActivity
import com.m365bleapp.repository.ScooterRepository

@Composable
fun NavHostContainer(repository: ScooterRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val unverified by repository.verificationPrompt.collectAsState()
    unverified?.let { profile ->
        AlertDialog(onDismissRequest = { repository.confirmUnverified(false) },
            title = { Text("未驗證車款") },
            text = { Text(modelName(profile.modelId) + " 已完成文件與單元測試，尚未經實車驗證。部分控制可能未提供，請確認後再連線。") },
            confirmButton = { TextButton(onClick = { repository.confirmUnverified(true) }) { Text("使用實驗性車款") } },
            dismissButton = { TextButton(onClick = { repository.confirmUnverified(false) }) { Text("取消") } })
    }
    val pairingDevice by repository.pairingDevice.collectAsState()
    pairingDevice?.let { name ->
        PairingSerialDialog(name, repository::submitPairingSerial, onCancel = {
            @SuppressLint("MissingPermission")
            repository.disconnect()
        })
    }

    NavHost(navController = navController, startDestination = "scan") {
        composable("scan") {
            ScanScreen(
                repository = repository,
                onNavigateToDashboard = { navController.navigate("dashboard") },
                onNavigateToLanguage = { navController.navigate("language") },
                onNavigateToLogViewer = { navController.navigate("logViewer") }
            )
        }
        composable("dashboard") {
            DashboardScreen(
                repository = repository,
                onLogs = { navController.navigate("logViewer") },
                onScooterInfo = { navController.navigate("scooterInfo") },
                onDisconnect = { 
                    // Reachable only after a successful connection, so
                    // BLUETOOTH_CONNECT has been granted by this point.
                    @SuppressLint("MissingPermission")
                    repository.disconnect()
                    navController.popBackStack()
                }
            )
        }
        composable("scooterInfo") {
            ScooterInfoScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }
        composable("logs") {
            LoggingScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }
        composable("logViewer") {
            LogViewerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("language") {
            LanguageScreen(
                onBack = { navController.popBackStack() },
                onLanguageChanged = {
                    // Restart the activity to apply language change
                    // Do NOT disconnect BLE here - the GatewayService needs
                    // the scooter connection to remain active for glasses HUD
                    // 
                    // Note: Using finish() + startActivity() instead of recreate()
                    // because some devices (like Rokid glasses) throw ClassCastException
                    // in ActivityImpl.checkAccessControl when using recreate()
                    val activity = context as? Activity
                    activity?.let {
                        val intent = it.intent
                        it.finish()
                        it.startActivity(intent)
                        // No animation for seamless transition
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            it.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                        } else {
                            it.overridePendingTransition(0, 0)
                        }
                    }
                }
            )
        }
    }
}
