package com.m365bleapp.protocol

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The rider's manual model choice, when the app cannot identify the scooter.
 *
 * ## Why this exists
 *
 * Nothing observable before connecting identifies a scooter's protocol: the
 * advertised name covers several wire generations of the same family, and the
 * GATT service list is not a discriminator. So on a scooter this project has
 * never seen, automatic identification can simply fail.
 *
 * Without a manual override that failure would be permanent — the rider would
 * have no way to say "this is an M365 Pro" to an app that is otherwise perfectly
 * capable of talking to it. The override is therefore not a convenience; it is
 * the escape hatch that keeps the app usable on untested hardware.
 *
 * ## Why it is global rather than per-device
 *
 * A per-MAC override would be more precise, but the rider generally owns one
 * scooter and reaches for this when identification fails — possibly before a
 * stable MAC is even known. A single global choice is simpler to reason about
 * and to explain. It is persisted so it survives the restart that follows a
 * language change or a crash.
 *
 * ## What it does NOT do
 *
 * Choosing a model does **not** raise the confidence. [ScooterModelRegistry.resolve]
 * reports a manual choice as [Confidence.UNVERIFIED], because the choice says
 * which register layout to *try*; it does not make that layout correct. The
 * badge keeps saying so.
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
