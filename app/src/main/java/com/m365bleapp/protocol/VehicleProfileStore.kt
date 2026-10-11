package com.m365bleapp.protocol

import android.content.Context
import androidx.core.content.edit
import io.github.zero2005x.pev.core.vehicle.VehicleProfileCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device manual model. The old global preference is pending only: it is
 * consumed only after the user chooses a saved vehicle or discards it.
 */
class VehicleProfileStore internal constructor(raw: String = "", private val persist: (String) -> Unit = {}) {
    private val _records = MutableStateFlow(VehicleProfileCodec.decode(raw))
    val records: StateFlow<List<io.github.zero2005x.pev.core.vehicle.VehicleProfileRecord>> = _records.asStateFlow()

    fun manualModel(address: String?): ScooterModel? {
        val mac = address?.takeIf { it.isNotBlank() } ?: return null
        val id = VehicleProfileCodec.manualModelId(_records.value, mac) ?: return null
        return ScooterModel.entries.firstOrNull { it.rustId == id && it.producesTelemetry }
    }

    /** Only an explicit user choice may consume the legacy global preference. */
    fun resolvePending(address: String?, pending: ModelOverrideStore) =
        resolvePending(address, pending.override.value, pending::clear)

    internal fun resolvePending(address: String?, model: ScooterModel?, clear: () -> Unit) {
        if (address != null) bind(address, model)
        clear()
    }

    fun bind(address: String, model: ScooterModel?) {
        val next = VehicleProfileCodec.bind(
            _records.value,
            address,
            model?.takeIf { it.producesTelemetry }?.rustId.orEmpty(),
            model?.displayName.orEmpty(),
        )
        _records.value = next
        persist(VehicleProfileCodec.encode(next))
    }

    companion object {
        private const val PREFS_NAME = "vehicle_profiles"
        private const val KEY_RECORDS = "records"

        @Volatile private var instance: VehicleProfileStore? = null

        fun getInstance(context: Context): VehicleProfileStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    VehicleProfileStore(prefs.getString(KEY_RECORDS, "").orEmpty()) { raw ->
                        prefs.edit { putString(KEY_RECORDS, raw) }
                    }.also { instance = it }
                }
            }
    }
}
