package com.m365bleapp.protocol

import io.github.zero2005x.pev.core.codec.xiaomi.PlanResult
import io.github.zero2005x.pev.core.codec.xiaomi.StatusWordWriteOrder
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiSettings
import io.github.zero2005x.pev.core.command.CommandPlan

/**
 * The reversible settings writes, and nothing else.
 *
 * Builders delegate logic and constants to [XiaomiSettings] and [XiaomiPdu]
 * from `:pev-protocol-core`.
 *
 * ## Scope, and what is deliberately excluded
 *
 * Only four operations are modelled, all of them ≤2-byte setting registers that a
 * reader can undo by writing the previous value back:
 *
 * | Operation | Register | Payload |
 * |---|---|---|
 * | KERS weak / medium / strong | `0x7B` | `{00\|01\|02, 00}` |
 * | Cruise off / on | `0x7C` | `{00\|01, 00}` |
 * | Tail light on / off | `0x7D` | 16-bit word, **big-endian**, bit 1 |
 * | Units km/h / mph | `0x7D` | 16-bit word, **big-endian**, bit 4 |
 *
 * Excluded on purpose, with reasons:
 *
 * - **Lock / unlock (`0x70` / `0x71`).** Reversible in principle, but locking
 *   mid-ride is a genuine safety event and the app already has the feature; adding
 *   a second path to it buys nothing.
 * - **SHFW profile select (`0xB2`).** Changes live speed and current limits, and
 *   the profile *names* come from an exported, permission-less broadcast receiver
 *   in the reference implementation, so a stale list can make the rider pick an
 *   index that is not the name they saw.
 * - **Anything on the BMS.** The reference app performs no BMS writes at all.
 *
 * ## The `0x7D` hazard, and why every write here is read-modify-write
 *
 * `0x7D` is **read little-endian but written big-endian**, and two settings share
 * the same 16-bit word. Writing a value assembled from a guess therefore risks
 * corrupting the *other* bit, or spilling into register `0x7E`. Every write in this
 * file is consequently expressed as "given the word that was just read, produce the
 * word to write", and [apply] refuses to build a write without a reading.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * The payloads are transcribed from the reverse-engineered reference app. No
 * scooter has been attached to this project, so **no write in this file has ever
 * been sent to a real vehicle**. Writing an out-of-range value to an unknown
 * firmware is exactly the kind of thing that cannot be undone remotely.
 */
object ScooterSettingsWriter {

    /** Register for the KERS level. */
    const val REG_KERS = XiaomiPdu.REG_KERS

    /** Register for cruise control. */
    const val REG_CRUISE = XiaomiPdu.REG_CRUISE

    /** Register for the shared status word (tail light + units). */
    const val REG_STATUS = XiaomiPdu.REG_STATUS_WORD

    /** KERS levels the reference app can set. There is no "off". */
    enum class Kers(val code: Int, val label: String) {
        WEAK(0, "Weak"),
        MEDIUM(1, "Medium"),
        STRONG(2, "Strong"),
    }

    /** Display unit. */
    enum class Units(val label: String) {
        KMH("km/h"),
        MPH("mph"),
    }

    /**
     * A write to perform.
     *
     * Carries the register and the exact payload bytes, so the caller never
     * assembles a payload itself — the endianness trap lives only in [apply].
     */
    data class Write(val register: Int, val payload: ByteArray) {
        // ByteArray gives identity equals, which would make two identical writes
        // compare unequal and break tests and de-duplication.
        override fun equals(other: Any?): Boolean =
            other is Write && register == other.register && payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            31 * register + payload.contentHashCode()

        /** Hex form for logging. */
        fun describe(): String =
            "reg 0x%02X <- %s".format(register, payload.joinToString(" ") { "%02X".format(it) })
    }

    // ------------------------------------------------------------- builders

    /** Builds the KERS write. */
    fun setKers(level: Kers): Write {
        val plan = when (val res = XiaomiSettings.kersPlan(level.code)) {
            is PlanResult.Ok -> res.plan
            is PlanResult.Refused -> error(res.reason)
        }
        val pdu = plan.steps[0].bytes
        return Write(REG_KERS, pdu.copyOfRange(4, pdu.size))
    }

    /** Builds the cruise-control write. */
    fun setCruise(engaged: Boolean): Write {
        val plan = when (val res = XiaomiSettings.cruisePlan(engaged)) {
            is PlanResult.Ok -> res.plan
            is PlanResult.Refused -> error(res.reason)
        }
        val pdu = plan.steps[0].bytes
        return Write(REG_CRUISE, pdu.copyOfRange(4, pdu.size))
    }

    /**
     * Builds a tail-light write from the **word that was just read**.
     *
     * @param currentlyReadWord the 16-bit value returned by [EscTelemetryParser.statusBits].
     * @param on whether the tail light should stay on.
     */
    fun setTailLight(currentlyReadWord: Int, on: Boolean): Write {
        val masked = currentlyReadWord and 0xFFFF
        val plan = when (val res = XiaomiSettings.tailLightPlan(masked, on, StatusWordWriteOrder.BIG_ENDIAN)) {
            is PlanResult.Ok -> res.plan
            is PlanResult.Refused -> error(res.reason)
        }
        val pdu = plan.steps[0].bytes
        return Write(REG_STATUS, pdu.copyOfRange(4, pdu.size))
    }

    /**
     * Builds a units write from the **word that was just read**.
     *
     * @param currentlyReadWord the 16-bit value returned by [EscTelemetryParser.statusBits].
     */
    fun setUnits(currentlyReadWord: Int, units: Units): Write {
        val masked = currentlyReadWord and 0xFFFF
        val plan = when (val res = XiaomiSettings.unitsPlan(masked, units == Units.MPH, StatusWordWriteOrder.BIG_ENDIAN)) {
            is PlanResult.Ok -> res.plan
            is PlanResult.Refused -> error(res.reason)
        }
        val pdu = plan.steps[0].bytes
        return Write(REG_STATUS, pdu.copyOfRange(4, pdu.size))
    }

    /**
     * Encodes a status word **big-endian**.
     *
     * This is the trap: [EscTelemetryParser.statusBits] reads the same register
     * little-endian, and this builder deliberately answers big-endian. Sending
     * little-endian here would set the wrong bit — with two settings sharing the
     * word, that means silently toggling the other one.
     *
     * ⚠️ **Two reference implementations disagree here, and it is unresolved.**
     *
     * | Source | `0x7D` **write** byte order |
     * |---|---|
     * | Scootbatt 1.9.2 — what this file follows (`doc/reverse-engineering/scootbatt-reports/README.md` §5) | **big-endian** |
     * | m365 Tools 1.8.0 — `BaseCommand.smali`, `<init>(IIIISZ)V`: `ByteBuffer.allocate(2).order(LITTLE_ENDIAN).putShort(...)` | **little-endian** |
     *
     * This file matches Scootbatt, so the asymmetry is deliberate rather than a
     * mistake — but it is not *settled*. Both apps read `0x7D` little-endian and
     * differ only on the write, and picking wrong flips the neighbouring bit
     * silently instead of failing. Resolve against hardware — write a known word,
     * read `0x7D` back, and check which order reproduces it — then replace this
     * table with the answer. See
     * `doc/reverse-engineering/m365tools-reports/05-write-commands.md`.
     */
    fun statusWordWrite(word: Int): Write {
        val masked = word and 0xFFFF
        val pdu = XiaomiPdu.write(
            REG_STATUS,
            byteArrayOf(
                ((masked ushr 8) and 0xFF).toByte(),
                (masked and 0xFF).toByte(),
            ),
        )
        return Write(REG_STATUS, pdu.copyOfRange(4, pdu.size))
    }

    // ------------------------------------------------------------- read-back

    /**
     * Checks a read-back against what was written.
     *
     * Every write in this file is followed by a re-read in the reference app, so a
     * silent no-op is detected rather than reported as success. KERS and cruise are
     * `{code, 0}` while `0x7D` is a whole word, so the two comparisons differ and
     * are kept separate rather than forced into one shape.
     */
    fun verifyKers(write: Write, readBack: ByteArray): Boolean =
        readBack.isNotEmpty() && (readBack[0].toInt() and 0xFF) == (write.payload[0].toInt() and 0xFF)

    /** Checks a cruise write's read-back. */
    fun verifyCruise(write: Write, readBack: ByteArray): Boolean =
        verifyKers(write, readBack)

    /**
     * Checks a status-word read-back.
     *
     * The value is read back **little-endian**, mirroring [EscTelemetryParser].
     */
    fun verifyStatusWord(write: Write, readBack: ByteArray): Boolean {
        val read = EscTelemetryParser.u16(readBack, 0) ?: return false
        val expected =
            ((write.payload[0].toInt() and 0xFF) shl 8) or (write.payload[1].toInt() and 0xFF)
        return read == expected
    }

    /** Checks a read-back for whichever register [write] targets. */
    fun verify(write: Write, readBack: ByteArray): Boolean = when (write.register) {
        REG_STATUS -> verifyStatusWord(write, readBack)
        REG_KERS, REG_CRUISE -> verifyKers(write, readBack)
        else -> false
    }

    /**
     * A write paired with the value it replaced, so it can be undone.
     *
     * Returned by the builders that need a prior reading; the caller keeps it and
     * can apply [undo] to restore the previous state without another read.
     */
    data class ReversibleWrite(val write: Write, val undo: Write)

    /** Builds a tail-light write together with its inverse. */
    fun setTailLightReversible(currentlyReadWord: Int, on: Boolean): ReversibleWrite {
        val was = (currentlyReadWord and EscTelemetryParser.TAIL_LIGHT_BIT) != 0
        return ReversibleWrite(
            write = setTailLight(currentlyReadWord, on),
            undo = setTailLight(currentlyReadWord, was),
        )
    }

    /** Builds a units write together with its inverse. */
    fun setUnitsReversible(currentlyReadWord: Int, units: Units): ReversibleWrite {
        val wasMph = (currentlyReadWord and EscTelemetryParser.MPH_BIT) != 0
        return ReversibleWrite(
            write = setUnits(currentlyReadWord, units),
            undo = setUnits(currentlyReadWord, if (wasMph) Units.MPH else Units.KMH),
        )
    }

    /** Builds a KERS write together with its inverse. */
    fun setKersReversible(previous: Kers, level: Kers): ReversibleWrite =
        ReversibleWrite(write = setKers(level), undo = setKers(previous))

    /** Builds a cruise write together with its inverse. */
    fun setCruiseReversible(previousEngaged: Boolean, engaged: Boolean): ReversibleWrite =
        ReversibleWrite(write = setCruise(engaged), undo = setCruise(previousEngaged))

    /**
     * Converts a [Write] to a core [CommandPlan], if a matching setting plan exists.
     */
    fun toCommandPlan(
        write: Write,
        currentStatusWord: Int? = null,
        order: StatusWordWriteOrder = StatusWordWriteOrder.BIG_ENDIAN,
    ): CommandPlan? = when (write.register) {
        REG_KERS -> {
            val level = write.payload.firstOrNull()?.toInt() ?: 0
            (XiaomiSettings.kersPlan(level) as? PlanResult.Ok)?.plan
        }
        REG_CRUISE -> {
            val engaged = (write.payload.firstOrNull()?.toInt() ?: 0) != 0
            (XiaomiSettings.cruisePlan(engaged) as? PlanResult.Ok)?.plan
        }
        REG_STATUS -> {
            val word = if (write.payload.size >= 2) {
                ((write.payload[0].toInt() and 0xFF) shl 8) or (write.payload[1].toInt() and 0xFF)
            } else 0
            val isTailLight = (word and XiaomiSettings.TAIL_LIGHT_BIT) != 0
            (XiaomiSettings.tailLightPlan(currentStatusWord ?: word, isTailLight, order) as? PlanResult.Ok)?.plan
        }
        else -> null
    }
}
