package com.m365bleapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m365bleapp.R
import com.m365bleapp.repository.MotorInfo

/** Displays an actual ESC reading; a beep report alone never fabricates a fault code. */
@Composable
internal fun ScooterFaultBanner(info: MotorInfo?) {
    val code = info?.errorCode?.takeIf { it != 0 } ?: return
    val description = if (code == 14) stringResource(R.string.scooter_fault_throttle)
        else info.errorDescription.orEmpty()
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.scooter_fault, code, description), color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.experimental_fault_blocked), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
