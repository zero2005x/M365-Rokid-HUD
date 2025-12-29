package com.m365bleapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import kotlinx.coroutines.flow.collect

import com.m365bleapp.repository.MotorInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: ScooterRepository,
    onLogs: () -> Unit,
    onScooterInfo: () -> Unit,
    onDisconnect: () -> Unit
) {
    val motorInfo by repository.motorInfo.collectAsState()
    val connState by repository.connectionState.collectAsState()

    // Auto-disconnect handling removed to prevent double-pop with Button.
    // Error state is displayed in UI.

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Status
            if (connState is ConnectionState.Error) {
                Text(
                    text = (connState as ConnectionState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(stringResource(R.string.connected), color = MaterialTheme.colorScheme.primary)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Speedometer (Simple Text for now)
            Text(
                text = "${"%.1f".format(motorInfo?.speed ?: 0.0)}",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.unit_kmh), fontSize = 24.sp)
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (motorInfo?.battery ?: 0) / 100f },
                    modifier = Modifier.weight(1f).height(16.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("${motorInfo?.battery ?: 0}${stringResource(R.string.unit_percent)}", fontSize = 24.sp)
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = onScooterInfo,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(stringResource(R.string.dashboard_view_details))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onLogs,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(stringResource(R.string.dashboard_view_logs))
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}
