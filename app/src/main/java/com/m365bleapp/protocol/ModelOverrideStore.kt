package com.m365bleapp.protocol

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Legacy global preference, retained only until explicit migration or discard.
 * New choices are written to VehicleProfileStore and never to this store.
 */
class ModelOverrideStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _override = MutableStateFlow(load())
    val override: StateFlow<ScooterModel?> = _override.asStateFlow()

    /** True when the rider has pinned a model. */
    val hasOverride: Boolean get() = _override.value != null

    /** Pins a model. Passing null or [ScooterModel.UNKNOWN] clears the choice. */
    fun set(model: ScooterModel?) {
        val normalized = model?.takeIf { it != ScooterModel.UNKNOWN }
        _override.value = normalized
        prefs.edit {
            if (normalized == null) remove(KEY_MODEL) else putString(KEY_MODEL, normalized.rustId)
        }
    }

    /** Clears the choice, returning to automatic identification. */
    fun clear() = set(null)

    private fun load(): ScooterModel? {
        val id = prefs.getString(KEY_MODEL, null) ?: return null
        // An id written by a newer build must read as "no override" rather than
        // silently resolving to a different model.
        return ScooterModel.entries.firstOrNull { it.rustId == id && it != ScooterModel.UNKNOWN }
    }

    companion object {
        private const val PREFS_NAME = "scooter_model_override"
        private const val KEY_MODEL = "model_rust_id"

        @Volatile
        private var instance: ModelOverrideStore? = null

        /**
         * Process-wide singleton so the scan screen, the repository and the
         * logger all see one value; two instances would each hold their own
         * [StateFlow] and an override would not reach the connection path.
         */
        fun getInstance(context: Context): ModelOverrideStore =
            instance ?: synchronized(this) {
                instance ?: ModelOverrideStore(context).also { instance = it }
            }
    }
}
