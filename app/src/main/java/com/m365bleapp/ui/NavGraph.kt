package com.m365bleapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
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

    // The home page is the start destination and stays the start destination.
    //
    // There used to be two top-level screens — "scan" and "dashboard" — with a
    // one-way navigation between them fired as soon as a scooter connected.
    // That is what made the entry point ambiguous (the app began somewhere
    // different depending on whether a scooter was already paired) and what
    // forced a disconnect before you could connect a different scooter.
    //
    // HomeScreen owns both faces and swaps between them in place. The connected
    // face is the dashboard; ScooterInfoScreen is the one full detail view.
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repository = repository,
                onOpenSettings = { navController.navigate("settings") },
                onOpenScooterInfo = { navController.navigate("scooterInfo") }
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
