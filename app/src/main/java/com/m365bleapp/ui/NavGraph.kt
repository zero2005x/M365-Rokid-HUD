package com.m365bleapp.ui

import android.app.Activity
import android.content.Intent
import android.os.Process
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
                    // Restart the app completely to apply language change
                    val activity = context as? Activity
                    activity?.let {
                        val intent = Intent(it, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        it.startActivity(intent)
                        it.finish()
                        // Kill process to ensure complete resource reload
                        Process.killProcess(Process.myPid())
                    }
                }
            )
        }
    }
}
