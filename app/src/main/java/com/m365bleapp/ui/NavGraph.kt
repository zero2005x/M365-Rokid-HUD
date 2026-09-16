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

    // The home page is the start destination and stays the start destination.
    //
    // There used to be two top-level screens — "scan" and "dashboard" — with a
    // one-way navigation between them fired as soon as a scooter connected.
    // That is what made the entry point ambiguous (the app began somewhere
    // different depending on whether a scooter was already paired) and what
    // forced a disconnect before you could connect a different scooter.
    //
    // HomeScreen now owns both faces and swaps between them in place. The
    // "dashboard" route is kept only as the full detail view reachable from it.
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repository = repository,
                onOpenSettings = { navController.navigate("settings") },
                onOpenHudDisplay = { navController.navigate("hudDisplay") },
                onOpenScooterInfo = { navController.navigate("scooterInfo") },
                onOpenDashboard = { navController.navigate("dashboard") },
                onOpenLogViewer = { navController.navigate("logViewer") }
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
                    // Back to home, which will now show its disconnected face.
                    navController.popBackStack()
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                repository = repository,
                onBack = { navController.popBackStack() },
                onOpenHudDisplay = { navController.navigate("hudDisplay") },
                onOpenLanguage = { navController.navigate("language") },
                onOpenLogViewer = { navController.navigate("logViewer") },
                onOpenLogging = { navController.navigate("logs") }
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
        composable("hudDisplay") {
            HudDisplayScreen(
                store = com.m365bleapp.gateway.DisplayPrefsStore.getInstance(context),
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
