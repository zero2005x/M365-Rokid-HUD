package com.m365bleapp.ui

import androidx.compose.runtime.Composable
import com.m365bleapp.repository.ScooterRepository

@Composable
fun PairingKeysScreen(repository: ScooterRepository, onBack: () -> Unit) {
    com.m365bleapp.bond.PairingKeysScreen(repository.bondStore, onBack)
}
