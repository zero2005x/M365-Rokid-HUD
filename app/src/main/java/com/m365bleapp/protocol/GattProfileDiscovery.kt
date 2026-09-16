package com.m365bleapp.protocol

import java.util.UUID

/**
 * The GATT layouts a scooter may expose.
 *
 * ## Why this cannot be a single hard-coded set of UUIDs
 *
 * The app originally assumed the Nordic UART layout and nothing else. Three
 * profiles exist in the field, chosen by hardware generation:
 *
 *  - **[NUS]** — Nordic UART. Xiaomi M365, Ninebot ESx/Max/E/F/T15 **and** some
 *    current Segway models all expose it.
 *  - **[NINEBOT_CUSTOM]** — Segway's own service. Modern primary transport.
 *  - **[HMSOFT]** — a very old serial-over-BLE bridge on the earliest boards.
 *
 * Two traps make this more than a lookup:
 *
 *  1. **A device may expose more than one profile and answer on only one.** A
 *     Max G3 advertises Segway's service *and* Nordic UART, and replies on
 *     Nordic UART. Picking the "better" profile because it looks
 *     manufacturer-specific therefore connects to a service that never
 *     answers.
 *  2. **The Ninebot custom profile is not shaped like NUS.** Its notify
 *     characteristic is `…0004`, not `…0003`, and `…0003` is a *second write*
 *     channel. Code that assumes "0002 writes, 0003 notifies" reads nothing.
 *
 * So discovery returns **every** profile found, in a defined order, and the
 * connection layer probes them. It never picks one.
 */
enum class GattProfileKind(val displayName: String) {
    /** Nordic UART: `6e400001…`, write `…0002`, notify `…0003`. */
    NUS("Nordic UART"),

    /** Segway's own service: `6e400001-0000-0000-006e-696e65626f74`. */
    NINEBOT_CUSTOM("Ninebot Custom"),

    /** HMSoft serial bridge: service `ffe0`, character `ffe1`. */
    HMSOFT("HMSoft"),
}

/** The characteristics one profile uses, once resolved. */
data class GattChannels(
    val kind: GattProfileKind,
    val service: UUID,
    /** App-to-device channel. */
    val write: UUID,
    /** Device-to-app channel. */
    val notify: UUID,
)

/** A service as seen during discovery. */
data class GattServiceView(
    val uuid: UUID,
    val characteristics: List<UUID>,
)

/** A characteristic as seen during discovery. */
data class GattCharacteristicView(
    val uuid: UUID,
    val serviceUuid: UUID,
    /** `PROPERTY_WRITE` or `PROPERTY_WRITE_NO_RESPONSE`. */
    val canWrite: Boolean,
    /** `PROPERTY_NOTIFY` or `PROPERTY_INDICATE`. */
    val canNotify: Boolean,
)

/**
 * Resolves known scooters' GATT layouts from whatever a device advertises.
 *
 * Pure: it reads value objects, not a `BluetoothGatt`, so the whole decision is
 * unit-testable on a host with no Bluetooth stack. The Android caller adapts
 * real services into [GattServiceView] / [GattCharacteristicView].
 */
object GattProfileDiscovery {

    // --- Nordic UART ---
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    // --- Ninebot / Segway custom ---
    //
    // The suffix `006e-696e65626f74` is ASCII "\u0000ninebot", which is how the
    // vendor marks its own service.
    const val NINEBOT_SERVICE = "6e400001-0000-0000-006e-696e65626f74"
    const val NINEBOT_WRITE = "6e400002-0000-0000-006e-696e65626f74"
    /**
     * Secondary write channel (RCTP). Present on the service but **not** the
     * notify channel — this is the `0003`/`0004` trap.
     */
    const val NINEBOT_WRITE_RCTP = "6e400003-0000-0000-006e-696e65626f74"
    const val NINEBOT_NOTIFY = "6e400004-0000-0000-006e-696e65626f74"

    // --- HMSoft (very old boards) ---
    const val HMSOFT_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val HMSOFT_CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private fun uuid(s: String): UUID = UUID.fromString(s)

    /**
     * Every profile present on the device, in the order they should be tried.
     *
     * Ordering rationale:
     *
     *  1. **[NUS]** first. It is the layout this app has always used, so
     *     existing scooters keep working unchanged, and it is the layout current
     *     Segway models answer on even when they also advertise their own.
     *  2. **[NINEBOT_CUSTOM]** second — the modern primary transport, tried
     *     after the compatibility path.
     *  3. **[HMSOFT]** last: oldest hardware, least likely, and its single
     *     combined characteristic means it needs the most inference.
     *
     * An empty result means none of the known layouts were recognised. That is
     * a legitimate outcome and the caller should report the services it did see,
     * because on a new model that list is the most useful thing a bug report can
     * contain.
     */
    fun discover(
        services: List<GattServiceView>,
        characteristics: List<GattCharacteristicView>,
    ): List<GattChannels> {
        val byService: Map<UUID, List<GattCharacteristicView>> =
            characteristics.groupBy { it.serviceUuid }
        val found = mutableListOf<GattChannels>()

        fun servicePresent(uuid: UUID) = services.any { it.uuid == uuid }
        fun charsOf(uuid: UUID) = byService[uuid].orEmpty()
        fun has(uuid: UUID, serviceUuid: UUID, predicate: (GattCharacteristicView) -> Boolean) =
            charsOf(serviceUuid).any { it.uuid == uuid && predicate(it) }

        // --- NUS ---
        val nusService = uuid(NUS_SERVICE)
        if (servicePresent(nusService)) {
            val write = uuid(NUS_WRITE)
            val notify = uuid(NUS_NOTIFY)
            // Both channels must actually carry the direction we need; a service
            // that merely exists is not enough.
            if (has(write, nusService) { it.canWrite } && has(notify, nusService) { it.canNotify }) {
                found += GattChannels(GattProfileKind.NUS, nusService, write, notify)
            }
        }

        // --- Ninebot custom ---
        val nbService = uuid(NINEBOT_SERVICE)
        if (servicePresent(nbService)) {
            val write = uuid(NINEBOT_WRITE)
            val notify = uuid(NINEBOT_NOTIFY)
            if (has(write, nbService) { it.canWrite } && has(notify, nbService) { it.canNotify }) {
                found += GattChannels(GattProfileKind.NINEBOT_CUSTOM, nbService, write, notify)
            }
        }

        // --- HMSoft ---
        //
        // One characteristic does both directions, so a single entry must be
        // both writable and notifiable. Requiring that is what stops a device
        // which merely happens to expose `ffe0` from being probed as a scooter.
        val hmService = uuid(HMSOFT_SERVICE)
        if (servicePresent(hmService)) {
            val combined = uuid(HMSOFT_CHAR)
            if (has(combined, hmService) { it.canWrite && it.canNotify }) {
                found += GattChannels(GattProfileKind.HMSOFT, hmService, combined, combined)
            }
        }

        return found
    }

    /** Only the profiles present, for logging or a capability display. */
    fun kindsPresent(
        services: List<GattServiceView>,
        characteristics: List<GattCharacteristicView>,
    ): List<GattProfileKind> = discover(services, characteristics).map { it.kind }

    /**
     * The subset of [GattProfileDiscovery.NUS_SERVICE] &c. that a device
     * exposes, for diagnostics.
     *
     * Returned sorted so a bug report is stable between runs.
     */
    fun describeUnknown(services: List<GattServiceView>): List<String> =
        services.map { it.uuid.toString().lowercase() }.sorted()

    /** True when [serviceUuid] is one this app knows how to talk to. */
    fun isKnownService(serviceUuid: UUID): Boolean =
        serviceUuid == uuid(NUS_SERVICE) ||
            serviceUuid == uuid(NINEBOT_SERVICE) ||
            serviceUuid == uuid(HMSOFT_SERVICE)
}
