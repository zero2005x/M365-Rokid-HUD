package com.m365bleapp.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared by the repository's flow and logger; binding an active address is synchronous. */
internal class VehicleIdentificationState(private val profiles: VehicleProfileStore,
    private val onChanged: (Identification) -> Unit) {
    private var address: String? = null
    private var advertisedName: String? = null
    private val mutable = MutableStateFlow(ScooterModelRegistry.resolve(null))
    val state = mutable.asStateFlow()
    fun activate(address: String?, advertisedName: String?) {
        this.address = address
        this.advertisedName = advertisedName
        publish()
    }
    fun bind(address: String, model: ScooterModel?) {
        profiles.bind(address, model)
        if (address.equals(this.address, true)) publish()
    }
    private fun publish() {
        val identity = ScooterModelRegistry.resolve(advertisedName, profiles.manualModel(address))
        mutable.value = identity
        onChanged(identity)
    }
}
