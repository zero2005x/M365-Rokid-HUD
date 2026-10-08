// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

data class ExperimentalSettingsState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val deviceId: String? = null,
    val connectionId: String? = null,
)
