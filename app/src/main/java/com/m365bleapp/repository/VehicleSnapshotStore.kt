package com.m365bleapp.repository

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last known state of a scooter, so the detail page is readable when the
 * scooter is off, out of range, or simply not connected yet.
 *
 * ## Why this exists
 *
 * `ScooterInfoScreen` reads entirely from `repository.motorInfo`, which is live
 * telemetry. With no connection every field rendered as `0` — the screen was
 * not merely empty, it was *wrong*, showing a 0 km/h and 0% battery that looked
 * like real readings. That is why the screen used to be reachable only after a
 * successful connection.
 *
 * With a snapshot the page has something true to show offline, and the screen
 * can state how old it is instead of implying it is current.
 *
 * ## What is persisted and what is not
 *
 * Two classes of data, stored differently:
 *
 *  - **Identity** (serial, firmware, model) changes only on a firmware flash.
 *    Written to disk, and read back on a later launch.
 *  - **Telemetry** (speed, battery, odometer) changes constantly. Persisting it
 *    on every poll would mean a disk write per second for data that is stale
 *    within a minute of the phone being put away.
 *
 * Both live in [VehicleSnapshot]; only the identity half is written to disk
 * eagerly. The telemetry half is kept in memory for the session and refreshed
 * from the live flow, which covers the real case — the rider disconnects and
 * looks at the page seconds or minutes later without restarting the app.
 */
data class VehicleSnapshot(
    /** Scooter serial number, from the ESC. Stable. */
    val serial: String? = null,
    /** Firmware version string, from the ESC. Stable between flashes. */
    val firmware: String? = null,
    /** BLE MAC this snapshot belongs to. */
    val mac: String? = null,
    /**
     * The scooter's advertised BLE name, when it was known at connect time.
     *
     * Kept because model resolution needs it and telemetry does not carry it.
     */
    val advertisedName: String? = null,

    val speedKmh: Double = 0.0,
    val batteryPercent: Int = 0,
    val temperatureC: Double = 0.0,
    val totalMileageKm: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val remainingKm: Double = 0.0,
    val tripMeters: Int = 0,
    val tripSeconds: Int = 0,

    /**
     * When this snapshot was last refreshed from live telemetry, in
     * `System.currentTimeMillis()` terms. `0` means it has never been populated.
     */
    val capturedAtMs: Long = 0L
) {
    /** True when this holds no usable data at all. */
    val isEmpty: Boolean get() = capturedAtMs == 0L

    /** Age in milliseconds, or null when never captured. */
    fun ageMs(now: Long = System.currentTimeMillis()): Long? =
        if (isEmpty) null else (now - capturedAtMs).coerceAtLeast(0L)

    /**
     * The advertised name this snapshot belongs to, for model resolution.
     *
     * Telemetry carries no name, so the field is optional and callers must cope
     * with `null` — which resolves to an unidentified model and therefore to no
     * capabilities, i.e. no rows. That is the intended degradation.
     */
    fun modelNameOrNull(): String? = advertisedName
}

/**
 * Persists the stable half of a [VehicleSnapshot] and holds the volatile half
 * for the life of the process.
 *
 * Follows the same pattern as `DisplayPrefsStore`: a process-wide singleton so
 * the activity and the foreground gateway service observe one instance, and a
 * [kotlinx.coroutines.flow.StateFlow] so the UI recomposes rather than polls.
 */
class VehicleSnapshotStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(loadPersisted())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    /**
     * Merges live telemetry into the snapshot.
     *
     * Called from the telemetry observer. The identity fields are only written
     * to disk when they actually change, so a steady ride performs no writes
     * while `serial`/`firmware` stay put.
     */
    /** The mutable telemetry fields merged by [updateTelemetry], as one value. */
    data class TelemetryUpdate(
        val speedKmh: Double,
        val batteryPercent: Int,
        val temperatureC: Double,
        val totalMileageKm: Double,
        val averageSpeedKmh: Double,
        val remainingKm: Double,
        val tripMeters: Int,
        val tripSeconds: Int,
    )

    fun updateTelemetry(mac: String?, update: TelemetryUpdate) {
        val previous = _snapshot.value
        _snapshot.value = previous.copy(
            mac = mac ?: previous.mac,
            speedKmh = update.speedKmh,
            batteryPercent = update.batteryPercent,
            temperatureC = update.temperatureC,
            totalMileageKm = update.totalMileageKm,
            averageSpeedKmh = update.averageSpeedKmh,
            remainingKm = update.remainingKm,
            tripMeters = update.tripMeters,
            tripSeconds = update.tripSeconds,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Records the scooter's advertised name.
     *
     * Telemetry does not carry a name, but model resolution needs one, and the
     * capabilities that decide which rows the detail screen renders come from the
     * resolved model. Without this the screen would resolve to an unidentified
     * model and hide every row.
     */
    fun updateAdvertisedName(name: String?) {
        if (name.isNullOrBlank()) return
        val previous = _snapshot.value
        if (previous.advertisedName == name) return
        _snapshot.value = previous.copy(advertisedName = name)
    }

    /**
     * Records the scooter's identity once it has been read.
     *
     * Separate from [updateTelemetry] because identity arrives once per
     * connection (or never, if the scooter does not answer those registers)
     * whereas telemetry arrives continuously.
     */
    fun updateIdentity(mac: String?, serial: String?, firmware: String?) {
        val previous = _snapshot.value
        val merged = previous.copy(
            mac = mac ?: previous.mac,
            serial = serial ?: previous.serial,
            firmware = firmware ?: previous.firmware
        )
        if (merged.serial == previous.serial &&
            merged.firmware == previous.firmware &&
            merged.mac == previous.mac
        ) {
            return // nothing new; skip the disk write
        }
        _snapshot.value = merged
        persist(merged)
    }

    /** Clears everything, for a "forget this scooter" action. */
    fun clear() {
        _snapshot.value = VehicleSnapshot()
        prefs.edit { clear() }
    }

    private fun persist(snapshot: VehicleSnapshot) {
        prefs.edit {
            putString(KEY_MAC, snapshot.mac)
            putString(KEY_SERIAL, snapshot.serial)
            putString(KEY_FIRMWARE, snapshot.firmware)
        }
    }

    private fun loadPersisted(): VehicleSnapshot {
        val mac = prefs.getString(KEY_MAC, null) ?: return VehicleSnapshot()
        val serial = prefs.getString(KEY_SERIAL, null)
        val firmware = prefs.getString(KEY_FIRMWARE, null)
        if (serial == null && firmware == null) return VehicleSnapshot()
        // Telemetry is deliberately not restored from disk, so a snapshot loaded
        // on a cold start has identity only. The screen renders identity
        // unconditionally and shows live fields as "—" until telemetry arrives,
        // rather than presenting last week's battery level as current.
        return VehicleSnapshot(mac = mac, serial = serial, firmware = firmware)
    }

    companion object {
        private const val PREFS_NAME = "vehicle_snapshot"
        private const val KEY_MAC = "mac"
        private const val KEY_SERIAL = "serial"
        private const val KEY_FIRMWARE = "firmware"

        @Volatile
        private var instance: VehicleSnapshotStore? = null

        fun getInstance(context: Context): VehicleSnapshotStore =
            instance ?: synchronized(this) {
                instance ?: VehicleSnapshotStore(context).also { instance = it }
            }
    }
}
