package com.m365bleapp.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.m365bleapp.R
import com.m365bleapp.repository.ScooterRepository

private data class ShellDestination(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    ShellDestination("ride", R.string.nav_ride, Icons.Default.Speed),
    ShellDestination("vehicles", R.string.nav_vehicles, Icons.Default.DirectionsCar),
    ShellDestination("glasses", R.string.nav_glasses, Icons.Default.RemoveRedEye),
)

@Composable
fun AppShell(
    repository: ScooterRepository,
    onOpenSettings: () -> Unit,
    onOpenScooterInfo: () -> Unit,
) {
    val navController = rememberNavController()
    val current = navController.currentBackStackEntryAsState().value?.destination?.route
    val connection by repository.connectionState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(connection) {
        if (connection is com.m365bleapp.repository.ConnectionState.Ready) navigate(navController, "ride", repository)
    }
    val useRail = LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(
        bottomBar = {
            if (!useRail) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = current == destination.route,
                            onClick = { navigate(navController, destination.route, repository) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (useRail) {
                NavigationRail {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = current == destination.route,
                            onClick = { navigate(navController, destination.route, repository) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
            NavHost(navController, startDestination = "ride", modifier = Modifier.weight(1f)) {
                composable("ride") {
                    HomeScreen(repository, onOpenSettings, onOpenScooterInfo)
                }
                composable("vehicles") {
                    ScanScreen(repository, onNavigateToDashboard = { navigate(navController, "ride", repository) }, onNavigateToSettings = onOpenSettings)
                }
                composable("glasses") {
                    GlassesScreen(onOpenSettings)
                }
            }
        }
    }
}

private fun navigate(navController: androidx.navigation.NavHostController, route: String, repository: ScooterRepository) {
    repository.stopScan()
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
