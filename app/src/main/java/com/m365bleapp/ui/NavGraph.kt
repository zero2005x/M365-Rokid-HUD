package com.m365bleapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.m365bleapp.repository.ScooterRepository

@Composable
fun NavHostContainer(repository: ScooterRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "scan") {
        composable("scan") {
            ScanScreen(
                repository = repository,
                onNavigateToDashboard = { navController.navigate("dashboard") }
            )
        }
        composable("dashboard") {
            DashboardScreen(
                repository = repository,
                onLogs = { navController.navigate("logs") },
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
    }
}
