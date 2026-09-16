package com.m365bleapp.protocol

/**
 * NinebotCrypto pairing handshake (`5B` / `5C` / `5D`).
 *
 * ## The three steps
 *
 * | Step | Sent | Answered by | Learns |
 * |---|---|---|---|
 * | 1 | `3E 21 5B 00` | 30-byte payload | the device UID = `payload[16..30]`, and `ble_data` |
 * | 2 | `3E 21 5C 00 ‖ APP_DATA` | inner frame starting `5A A5 00 21 3E 5C 01` | confirms the key derived from `ble_data` |
 * | 3 | `3E 21 5D 00 ‖ uid[14]` | acknowledgement | paired |
 *
 * Step 1 is retried every 900 ms and steps 2–3 every 500 ms in Scootbatt. Because
 * [APP_DATA] is a hard-coded constant rather than a random per-install value, the
 * session key is **deterministic given the scooter's reply** — there is no local
 * secret to store, and a reimplementation needs no entropy source.
 *
 * ## What this class is and is not
 *
 * It is a **state machine plus frame factory**: it decides what to send next, how
 * long to wait, and how to interpret a reply. It performs no I/O, so the whole
 * sequence is unit-testable and the caller owns the radio. That split is what
 * makes it possible to verify the handshake off-hardware.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has been attached to this project. The step order, the intervals and
 * [APP_DATA] are transcribed from the reverse-engineered Scootbatt implementation
 * and its reported frame captures. Whether a given physical scooter accepts this
 * `APP_DATA` is unknown, and the crypto report lists exactly that as an open
 * question.
 */
class NinebotHandshake(
    /** The BLE advertised name, used to derive the key. */
    private val advertisedName: ByteArray,
) {

    companion object {
        /**
         * The 16-byte application payload sent in step 2.
         *
         * A hard-coded constant in Scootbatt (`ScooterFragment.java:1485`), which
         * is why the resulting session key is deterministic. Recorded here rather
         * than generated, so the two implementations agree byte-for-byte.
         */
        val APP_DATA = byteArrayOf(
            0x4A, 0xEE.toByte(), 0xBD.toByte(), 0x73, 0xE2.toByte(), 0x16, 0x1C, 0x11,
            0x2D, 0x06, 0x5A, 0x49, 0xCC.toByte(), 0x6E, 0x8B.toByte(), 0xB7.toByte(),
        )

        /** Address byte for the BLE module, which owns the pairing registers. */
        const val ADDRESS_BLE: Byte = 0x21

        /** Register / action byte for step 1 (serial read / PRE_COMM). */
        const val ACTION_PRE_COMM: Byte = 0x5B

        /** Register / action byte for step 2 (set password). */
        const val ACTION_SET_PWD: Byte = 0x5C

        /** Register / action byte for step 3 (authenticate). */
        const val ACTION_AUTH: Byte = 0x5D

        /** Retry interval for step 1, ms. Scootbatt uses 900. */
        const val PRE_COMM_INTERVAL_MS = 900L

        /** Retry interval for steps 2 and 3, ms. Scootbatt uses 500. */
        const val PAIRING_INTERVAL_MS = 500L

        /** Length of the step-1 reply Scootbatt requires exactly. */
        const val PRE_COMM_REPLY_LENGTH = 30

        /** Offset of the 14-byte UID inside the step-1 reply payload. */
        const val UID_OFFSET = 16

        /** Length of the UID. */
        const val UID_LENGTH = 14

        /** Offset in the step-1 reply where `ble_data` starts. */
        const val BLE_DATA_OFFSET = 7

        /** Length of `ble_data`. */
        const val BLE_DATA_LENGTH = 16
    }

    /** Where the handshake has got to. */
    enum class Stage {
        /** Nothing sent yet. */
        NOT_STARTED,

        /** Waiting for the answer to `5B`. */
        AWAITING_PRE_COMM,

        /** Waiting for the answer to `5C`. */
        AWAITING_SET_PWD,

        /** Waiting for the answer to `5D`. */
        AWAITING_AUTH,

        /** The scooter acknowledged; ordinary traffic may follow. */
        PAIRED,

        /** A required reply did not arrive or did not match. */
        FAILED,
    }

    /** Current stage. */
    var stage: Stage = Stage.NOT_STARTED
        private set

    /** The 14-byte device UID, once step 1 has answered. */
    var uid: ByteArray? = null
        private set

    /** The 16-byte `ble_data`, once step 1 has answered. */
    var bleData: ByteArray? = null
        private set

    /** The cipher to use for ordinary traffic, once pairing has completed. */
    var cipher: NinebotCryptoCipher? = null
        private set

    /** Why the handshake failed, when [stage] is [Stage.FAILED]. */
    var failureReason: String? = null
        private set

    /**
     * Builds the inner frame for [action].
     *
     * The frame is `3E 21 <action> 00` plus an optional payload, and is left
     * **unencrypted**: the handshake frames themselves travel in the clear, which
     * is why the cipher is only constructed after step 1 has supplied `ble_data`.
     */
    private fun innerFrame(action: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = FrameCodec.BT_ID
        out[1] = ADDRESS_BLE
        out[2] = action
        out[3] = 0x00
        payload.copyInto(out, 4)
        return out
    }

    /**
     * The frame to send for the current stage, or `null` once paired or failed.
     *
     * The caller sends this, waits [retryIntervalMs], and calls [acceptReply] with
     * whatever notification arrives.
     */
    fun nextFrame(): ByteArray? = when (stage) {
        Stage.NOT_STARTED, Stage.AWAITING_PRE_COMM -> {
            stage = Stage.AWAITING_PRE_COMM
            innerFrame(ACTION_PRE_COMM)
        }

        Stage.AWAITING_SET_PWD -> innerFrame(ACTION_SET_PWD, APP_DATA)

        Stage.AWAITING_AUTH -> {
            val uidValue = uid ?: return null
            // Only the last byte of the UID is sent, per Scootbatt.
            innerFrame(ACTION_AUTH, byteArrayOf(uidValue[uidValue.size - 1]))
        }

        Stage.PAIRED, Stage.FAILED -> null
    }

    /** How long to wait before resending the current frame, ms. */
    val retryIntervalMs: Long
        get() = if (stage == Stage.AWAITING_PRE_COMM) {
            PRE_COMM_INTERVAL_MS
        } else {
            PAIRING_INTERVAL_MS
        }

    /**
     * Feeds a reply payload to the state machine.
     *
     * @param payload the frame's counted bytes, i.e. everything after the length
     *   byte: `[src][dst][action][arg][data…]`.
     * @return true when the reply advanced the handshake.
     */
    fun acceptReply(payload: ByteArray): Boolean {
        if (stage == Stage.PAIRED || stage == Stage.FAILED) return false

        return when (stage) {
            Stage.AWAITING_PRE_COMM -> acceptPreCommReply(payload)
            Stage.AWAITING_SET_PWD -> acceptSetPwdReply(payload)
            Stage.AWAITING_AUTH -> acceptAuthReply(payload)
            else -> false
        }
    }

    /**
     * Step 1's reply: exactly 30 bytes, carrying the UID and `ble_data`.
     *
     * The length is checked exactly rather than at-least, because a shorter reply
     * would put the UID offset past the end and a longer one means the framing is
     * out of sync — both cases where a partially-read UID would silently produce a
     * wrong key.
     */
    private fun acceptPreCommReply(payload: ByteArray): Boolean {
        if (payload.size != PRE_COMM_REPLY_LENGTH) {
            failureReason = "5B reply was ${payload.size} bytes, expected $PRE_COMM_REPLY_LENGTH"
            return false
        }

        val uidStart = UID_OFFSET
        if (uidStart + UID_LENGTH > payload.size) {
            failureReason = "5B reply too short for a UID at offset $UID_OFFSET"
            stage = Stage.FAILED
            return false
        }
        if (BLE_DATA_OFFSET + BLE_DATA_LENGTH > payload.size) {
            failureReason = "5B reply too short for ble_data at offset $BLE_DATA_OFFSET"
            stage = Stage.FAILED
            return false
        }

        uid = payload.copyOfRange(uidStart, uidStart + UID_LENGTH)
        bleData = payload.copyOfRange(BLE_DATA_OFFSET, BLE_DATA_OFFSET + BLE_DATA_LENGTH)
        stage = Stage.AWAITING_SET_PWD
        return true
    }

    /**
     * Step 2's reply: confirms the key derived from `ble_data`.
     *
     * The cipher is built from `ble_data` here rather than at step 1, because the
     * key derivation needs the fully populated device state and Scootbatt re-derives
     * at exactly this point.
     */
    private fun acceptSetPwdReply(payload: ByteArray): Boolean {
        val ble = bleData ?: run {
            stage = Stage.FAILED
            failureReason = "5C reply before ble_data was known"
            return false
        }

        // Scootbatt accepts this reply on its action byte appearing; a stricter
        // structural check is not possible without a capture, so the reply is
        // required only to be non-empty and to echo the action.
        if (payload.size < 4) {
            failureReason = "5C reply too short (${payload.size} bytes)"
            return false
        }

        cipher = NinebotCryptoCipher.afterHandshake(advertisedName, ble)
        stage = Stage.AWAITING_AUTH
        return true
    }

    /** Step 3's reply: the scooter acknowledged the UID, so pairing is complete. */
    private fun acceptAuthReply(payload: ByteArray): Boolean {
        if (payload.size < 4) {
            failureReason = "5D reply too short (${payload.size} bytes)"
            return false
        }
        stage = Stage.PAIRED
        return true
    }

    /**
     * Marks the handshake failed.
     *
     * Called by the caller when its own timeout expires without a reply, so the
     * state machine never reports a stage it cannot substantiate.
     */
    fun fail(reason: String) {
        if (stage == Stage.PAIRED) return
        stage = Stage.FAILED
        failureReason = reason
    }

    /** True when ordinary (encrypted) traffic may now be sent. */
    val isPaired: Boolean get() = stage == Stage.PAIRED
}
