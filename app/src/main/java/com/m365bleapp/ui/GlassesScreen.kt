package com.m365bleapp.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m365bleapp.R
import com.m365bleapp.gateway.GatewayUiState
import com.m365bleapp.ui.theme.Dimens

@Composable
fun GlassesScreen(onOpenSettings: () -> Unit) {
    val gateway by GatewayUiState.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.gutter)
    ) {
        Text(stringResource(R.string.nav_glasses))
        Text(
            when (gateway.compatibility) {
                "incompatible" -> stringResource(R.string.gateway_incompatible)
                "negotiated_v2" -> stringResource(R.string.gateway_negotiated)
                else -> stringResource(R.string.gateway_legacy)
            }
        )
        GatewayRow()
        WifiGatewayRow(androidx.compose.ui.platform.LocalContext.current)
        androidx.compose.material3.TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.settings_title))
        }
    }
}
