package com.m365bleapp.gateway

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The rider's choice of which fields the glasses HUD shows.
 *
 * This is the single source of truth shared by two very different consumers:
 *
 *  - the settings UI, which writes it, and
 *  - [GatewayService], which pushes it to the glasses over BLE.
 *
 * It exists because those two live in different Android components (an Activity
 * and a foreground Service) and must agree. Reading the mask straight out of
 * `SharedPreferences` in both places would work until one of them cached a
 * stale value; a [StateFlow] gives the service a change notification instead of
 * a poll.
 *
 * Persistence matters for a second reason: the rider usually sets this up
 * BEFORE putting the phone away and connecting the glasses. The value has to
 * survive that gap — including the app being killed in between — so the choice
 * is written to disk on every change rather than held in memory.
 *
 * Deliberately NOT stored in the repository's EncryptedSharedPreferences: this
 * holds no secret. It is a UI preference, and putting it in the encrypted store
 * would mean the gateway service needs the master key, which is a larger
 * liability than the data is worth. See [ScooterRepository] for the encrypted
 * store used for auth tokens.
 */
class DisplayPrefsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mask = MutableStateFlow(
        // A missing or zero value means "never configured" — fall back to the
        // historical layout rather than to nothing.
        prefs.getInt(KEY_MASK, 0).takeIf { it != 0 } ?: DisplayField.DEFAULT_MASK
    )
    val mask: StateFlow<Int> = _mask.asStateFlow()

    private val _textScalePercent = MutableStateFlow(
        prefs.getInt(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE)
    )
    val textScalePercent: StateFlow<Int> = _textScalePercent.asStateFlow()

    /** Replaces the whole selection. */
    fun setMask(value: Int) {
        // Guard against the "show nothing" state. It is reachable by toggling
        // every switch off, and a blank HUD is never a useful outcome for
        // someone who is riding — the rider then has to stop and take the phone
        // out to undo it.
        val safe = if (value == 0) DisplayField.SPEED else value
        _mask.value = safe
        prefs.edit { putInt(KEY_MASK, safe) }
    }

    /** Turns one field on or off. */
    fun setField(field: Int, enabled: Boolean) {
        setMask(if (enabled) _mask.value or field else _mask.value and field.inv())
    }

    fun isEnabled(field: Int): Boolean = (_mask.value and field) != 0

    fun setTextScalePercent(percent: Int) {
        val clamped = percent.coerceIn(
            M365HudGattProfile.DISPLAY_PREFS_MIN_SCALE,
            M365HudGattProfile.DISPLAY_PREFS_MAX_SCALE
        )
        _textScalePercent.value = clamped
        prefs.edit { putInt(KEY_TEXT_SCALE, clamped) }
    }

    /** Restores the historical layout. */
    fun resetToDefault() {
        setMask(DisplayField.DEFAULT_MASK)
        setTextScalePercent(DEFAULT_TEXT_SCALE)
    }

    companion object {
        private const val PREFS_NAME = "hud_display_prefs"
        private const val KEY_MASK = "field_mask"
        private const val KEY_TEXT_SCALE = "text_scale_percent"

        /** 100% — the size the HUD used before the scale was configurable. */
        const val DEFAULT_TEXT_SCALE = 100

        @Volatile
        private var instance: DisplayPrefsStore? = null

        /**
         * Process-wide singleton.
         *
         * The service and the activity must see the same in-memory [StateFlow];
         * two instances would each hold their own and a change made in the UI
         * would not reach the service until the next process restart.
         */
        fun getInstance(context: Context): DisplayPrefsStore =
            instance ?: synchronized(this) {
                instance ?: DisplayPrefsStore(context).also { instance = it }
            }
    }
}
