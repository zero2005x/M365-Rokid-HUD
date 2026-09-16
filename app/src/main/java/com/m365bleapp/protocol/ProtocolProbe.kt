package com.m365bleapp.protocol

import android.content.Context
import androidx.core.content.edit

/**
 * Which protocol dialect a given scooter speaks.
 *
 * Determined at runtime by probing, never by the advertised name or the GATT
 * service list — see [ProtocolProbe] for why those signals are not usable.
 */
enum class ScooterProtocol(val id: String, val label: String) {
    /**
     * Xiaomi Mi authentication: ECDH P-256 + HKDF-SHA256 + AES-128-CCM on the
     * `fe95` service, telemetry on Nordic UART. This is what the app implements
     * today (M365 and close relatives).
     */
    XIAOMI_MI("xiaomi_mi", "Xiaomi Mi auth"),

    /**
     * Ninebot legacy crypto: `5B/5C/5D` pairing with chained AES and a `msgIt`
     * counter. Needs a power-button press to pair.
     */
    NINEBOT_CRYPTO("ninebot_crypto", "NinebotCrypto"),

    /**
     * Plain `5AA5` framing with no encryption (`encrypt = 0`).
     *
     * The generated family table lists Ninebot ESx and Max G30 here, while
     * NinebotCrypto lists the same families as crypto-capable — both are true
     * of different firmware generations, which is precisely why the dialect has
     * to be probed rather than looked up.
     */
    NINEBOT_PLAIN("ninebot_plain", "Ninebot plain 5AA5"),

    /**
     * Current-generation `5AA5` with AES-128 CTR + CBC-MAC and board-scoped
     * addressing (G2, F2, D-series, GT/P-series, Max G3).
     */
    ENCRYPTION2("enc2", "Encryption2"),

    /** Not yet identified; a probe is still owed. */
    UNKNOWN("unknown", "Unknown")
}

/**
 * Remembers the protocol dialect we determined for each scooter.
 *
 * ## Why a cache is not an optimisation here
 *
 * Detection means attempting a handshake and watching for an answer. It is the
 * only reliable method, but it is also slow and, on some devices, involves
 * pairing traffic. Paying that cost on every connect would be user-visible, so
 * the result is stored and reused.
 *
 * The stored value is a **hint, not a commitment**. A scooter can be reflashed
 * (stock → SHFW, or a downgrade) and change dialect while keeping its MAC.
 * Callers must therefore treat a cache hit as "try this first", and clear the
 * entry via [forget] when that attempt fails, rather than trusting it
 * unconditionally.
 *
 * ## Scope of persistence
 *
 * Keyed by MAC address and stored in plain SharedPreferences. Like
 * `DisplayPrefsStore` this holds no secret — it is a routing hint, and putting
 * it in the encrypted store would force the gateway service to hold the master
 * key for no benefit.
 */
class ProtocolProbe(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The last known dialect for [mac], or [ScooterProtocol.UNKNOWN] if it has
     * never been probed.
     */
    fun cached(mac: String): ScooterProtocol {
        val id = prefs.getString(key(mac), null) ?: return ScooterProtocol.UNKNOWN
        // An id written by a newer build that this one does not know about must
        // read as UNKNOWN rather than crash or silently mismatch.
        return ScooterProtocol.entries.firstOrNull { it.id == id } ?: ScooterProtocol.UNKNOWN
    }

    /** True when a previous probe produced a usable answer. */
    fun hasCached(mac: String): Boolean = cached(mac) != ScooterProtocol.UNKNOWN

    /** Records the outcome of a probe. */
    fun remember(mac: String, protocol: ScooterProtocol) {
        if (protocol == ScooterProtocol.UNKNOWN) {
            // Storing "unknown" would make hasCached() lie on the next call.
            forget(mac)
            return
        }
        prefs.edit { putString(key(mac), protocol.id) }
    }

    /**
     * Drops the cached dialect for [mac].
     *
     * Call this when a connection using the cached dialect fails. Without it a
     * scooter that was reflashed would fail forever on the stale entry and could
     * never be re-probed.
     */
    fun forget(mac: String) {
        prefs.edit { remove(key(mac)) }
    }

    /** Clears every cached dialect. Exposed for a "forget all devices" action. */
    fun forgetAll() {
        prefs.edit { clear() }
    }

    private fun key(mac: String) = "protocol_" + mac.uppercase()

    private companion object {
        /** Separate from the auth-token store: this holds no secret. */
        const val PREFS_NAME = "scooter_protocol_cache"
    }
}

/**
 * How a probe attempt turned out.
 *
 * Separate from [ScooterProtocol] so a failure can carry its reason: the UI
 * needs to distinguish "no answer at all" (device off or out of range) from
 * "answered but refused" (wrong dialect, or the rider did not press the power
 * button), because only the second one is worth retrying differently.
 */
sealed class ProbeResult {
    /** A dialect was identified. */
    data class Detected(val protocol: ScooterProtocol, val viaCache: Boolean) : ProbeResult()

    /** The device never answered. */
    data class NoAnswer(val detail: String) : ProbeResult()

    /** The device answered, but not in any dialect we know. */
    data class Unrecognised(val detail: String) : ProbeResult()
}

/**
 * Decides which dialect to try first for a device.
 *
 * Kept free of Android BLE types on purpose: the decision is pure logic over
 * (cached hint, advertised name), so it can be unit-tested without a radio.
 * Performing the actual handshake belongs to the connection layer, which
 * reports back through [ProtocolProbe.remember] / [ProtocolProbe.forget].
 */
object ProtocolProbeStrategy {

    /**
     * Ordered dialects to attempt for a device.
     *
     * If a probe has already succeeded for this MAC, that dialect is tried
     * first and the rest are kept as fallbacks — a reflashed scooter should cost
     * one wasted attempt, not a dead end.
     *
     * With no cached hint the order is cheapest-and-most-likely first:
     * 1. [ScooterProtocol.XIAOMI_MI] — the implemented path; also the only one
     *    that can be confirmed without a button press.
     * 2. [ScooterProtocol.NINEBOT_PLAIN] — plain framing, no crypto, so a wrong
     *    guess is harmless and fails fast.
     * 3. [ScooterProtocol.NINEBOT_CRYPTO] — needs a power-button press, so it
     *    is only worth asking the rider for once the cheap options are out.
     * 4. [ScooterProtocol.ENCRYPTION2] — needs a stored password on vehicles
     *    already paired to the official app, so it is last.
     */
    fun probeOrder(cached: ScooterProtocol): List<ScooterProtocol> {
        val base = listOf(
            ScooterProtocol.XIAOMI_MI,
            ScooterProtocol.NINEBOT_PLAIN,
            ScooterProtocol.NINEBOT_CRYPTO,
            ScooterProtocol.ENCRYPTION2
        )
        if (cached == ScooterProtocol.UNKNOWN) return base
        return listOf(cached) + base.filter { it != cached }
    }

    /**
     * Dialects worth trying given what the device advertises.
     *
     * **The advertised name does not narrow anything, and this function exists
     * to say so in one place.**
     *
     * The name was deliberately not used to filter the candidate list. All it
     * could do is select between prefixes that all map to the same set of
     * dialects — `MIScooter`, `NBScooter`, `Ninebot-*`, `NB<serial>`, `S1D*` and
     * `Segway` between them cover both Xiaomi and Ninebot, and several of those
     * names are used across *different wire generations* of the same model
     * family. Filtering on it would therefore drop the correct dialect for any
     * device whose name does not match its protocol, which is exactly the
     * failure mode probing is meant to eliminate.
     *
     * `advertisedName` is accepted anyway so that callers have somewhere to
     * pass it, and so that a future refinement — for instance preferring the
     * plain-framing dialect for a prefix known to have dropped crypto — has a
     * natural home that is already threaded through.
     */
    @Suppress("UNUSED_PARAMETER")
    fun candidatesFor(advertisedName: String?, cached: ScooterProtocol): List<ScooterProtocol> =
        probeOrder(cached)
}
