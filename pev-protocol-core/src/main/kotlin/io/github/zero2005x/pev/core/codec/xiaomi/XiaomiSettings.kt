package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.command.CommandPlan
import io.github.zero2005x.pev.core.command.CommandBinding
import io.github.zero2005x.pev.core.command.CommandScope
import io.github.zero2005x.pev.core.command.CommandSpec
import io.github.zero2005x.pev.core.command.ConfirmKind
import io.github.zero2005x.pev.core.command.ParamSpec
import io.github.zero2005x.pev.core.command.ReadbackSpec
import io.github.zero2005x.pev.core.command.RetryPolicy
import io.github.zero2005x.pev.core.command.WriteStep
import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldState

/**
 * Byte order used when *writing* the `0x7D` status word. Reads are little-endian in every
 * known app, but two reference apps disagree on the write (Scootbatt: big-endian; M365 Tools:
 * little-endian) and no hardware result exists. There is deliberately **no default**: the
 * caller must choose, and the owner test in OWNER_VALIDATION.md is what settles it.
 */
enum class StatusWordWriteOrder { BIG_ENDIAN, LITTLE_ENDIAN }

sealed interface PlanResult {
    data class Ok(val plan: CommandPlan) : PlanResult
    data class Refused(val reason: String) : PlanResult
}

/** App attaches the device/session/profile of the actual read; fresh words cannot cross sessions. */
data class XiaomiStatusWordObservation(
    val reading: XiaomiRegisterValue<XiaomiEscDecoder.StatusWord>,
    val binding: CommandBinding,
)

/**
 * Reversible M365 settings: KERS level, cruise, tail-light-always-on, display units.
 * Every command reads back. Status-word writes are read-modify-write from a *fresh* word so
 * the neighbouring bit is preserved. Not verified on a real vehicle: evidence is VENDOR_STATIC,
 * hence experimental-only until the owner validates each command.
 *
 * Excluded on purpose: lock/unlock, SHFW profile select, anything on the BMS.
 */
object XiaomiSettings {
    const val TAIL_LIGHT_BIT = 1 shl 1
    const val MPH_BIT = 1 shl 4
    const val STATUS_WORD_MAX_AGE_MS = 2_000L
    private const val FAMILY_MODELS = "M365"

    private val scope = CommandScope(Family.XIAOMI_SCOOTER, setOf(FAMILY_MODELS))
    private val refs = listOf(
        "doc/reverse-engineering/scootbatt-reports/04-write-commands.md (W3-W11)",
        "doc/reverse-engineering/m365tools-reports/05-write-commands.md",
    )

    private fun spec(id: String, label: String, param: ParamSpec, riding: Boolean, risk: String) = CommandSpec(
        id, label, scope, listOf(param), Evidence.VENDOR_STATIC, ConfirmKind.READBACK,
        RetryPolicy.NEVER, riding, risk, refs,
    )

    val KERS = spec("xiaomi.kers", "KERS level", ParamSpec("level", null, 0.0, 2.0), true,
        "Changes regenerative braking feel; 0=weak 1=medium 2=strong.")
    val CRUISE = spec("xiaomi.cruise", "Cruise control", ParamSpec("on", null, 0.0, 1.0), true,
        "HIGH: vehicle can hold speed without throttle input.")
    val TAIL_LIGHT = spec("xiaomi.tail_light_always_on", "Tail light always on", ParamSpec("on", null, 0.0, 1.0), false,
        "Read-modify-write of shared 0x7D word; write byte order unresolved (see StatusWordWriteOrder).")
    val UNITS = spec("xiaomi.units_mph", "Display units mph", ParamSpec("on", null, 0.0, 1.0), false,
        "Changes dash speed unit; same shared 0x7D word and unresolved write order.")

    val ALL = listOf(KERS, CRUISE, TAIL_LIGHT, UNITS)

    fun kersPlan(level: Int): PlanResult =
        if (level !in 0..2) PlanResult.Refused("KERS level out of range") else byteWrite(KERS, XiaomiPdu.REG_KERS, level)

    fun cruisePlan(on: Boolean): PlanResult = byteWrite(CRUISE, XiaomiPdu.REG_CRUISE, if (on) 1 else 0)

    /** A fresh timestamped read is required; the resulting plan expires with that read. */
    fun tailLightPlan(currentWord: XiaomiStatusWordObservation?, on: Boolean,
        order: StatusWordWriteOrder, nowMs: Long): PlanResult =
        wordWrite(TAIL_LIGHT, currentWord, TAIL_LIGHT_BIT, on, order, nowMs)

    fun unitsPlan(currentWord: XiaomiStatusWordObservation?, mph: Boolean,
        order: StatusWordWriteOrder, nowMs: Long): PlanResult =
        wordWrite(UNITS, currentWord, MPH_BIT, mph, order, nowMs)

    /** Pure compatibility encoding; this byte payload alone conveys no write authorization. */
    fun statusWordPayload(currentWord: Int, bit: Int, set: Boolean, order: StatusWordWriteOrder): ByteArray {
        require(currentWord in 0..0xFFFF) { "status word out of u16 range" }
        require(bit == TAIL_LIGHT_BIT || bit == MPH_BIT) { "unknown setting bit" }
        val word = if (set) currentWord or bit else currentWord and bit.inv()
        val hi = (word ushr 8).toByte()
        val lo = word.toByte()
        return if (order == StatusWordWriteOrder.BIG_ENDIAN) byteArrayOf(hi, lo) else byteArrayOf(lo, hi)
    }

    private fun byteWrite(spec: CommandSpec, register: Int, value: Int): PlanResult {
        val payload = byteArrayOf(value.toByte(), 0x00)
        val readback = ReadbackSpec(XiaomiPdu.read(register, payload.size)) { raw ->
            XiaomiReply.parse(raw)?.let {
                it.direction == 0x23 && it.type == XiaomiPdu.CMD_READ && it.register == register &&
                    it.data.size == 2 && Le.u16(it.data, 0) == value
            } == true
        }
        return PlanResult.Ok(CommandPlan(spec, listOf(WriteStep(XiaomiPdu.write(register, payload))), readback,
            parameterValues = mapOf(spec.params.single().name to value.toDouble())))
    }

    private fun wordWrite(spec: CommandSpec, observation: XiaomiStatusWordObservation?,
        bit: Int, set: Boolean, order: StatusWordWriteOrder, nowMs: Long): PlanResult {
        if (!freshWord(observation?.reading, nowMs)) return PlanResult.Refused("fresh 0x7D word required for read-modify-write")
        val read = requireNotNull(observation)
        val current = requireNotNull(read.reading.value).raw
        if (current !in 0..0xFFFF) return PlanResult.Refused("status word out of u16 range")
        val word = if (set) current or bit else current and bit.inv()
        val payload = statusWordPayload(current, bit, set, order)
        val register = XiaomiPdu.REG_STATUS_WORD
        // Read-back is always little-endian: the stored word must equal the intended word.
        val readback = ReadbackSpec(XiaomiPdu.read(register, 2)) { raw ->
            XiaomiReply.parse(raw)?.let {
                it.direction == 0x23 && it.type == XiaomiPdu.CMD_READ && it.register == register &&
                    it.data.size == 2 && Le.u16(it.data, 0) == word
            } == true
        }
        return PlanResult.Ok(CommandPlan(spec, listOf(WriteStep(XiaomiPdu.write(register, payload))), readback,
            parameterValues = mapOf(spec.params.single().name to if (set) 1.0 else 0.0),
            validUntilMs = read.reading.observedAtMs + STATUS_WORD_MAX_AGE_MS, binding = read.binding,
            validFromMs = read.reading.observedAtMs))
    }

    private fun freshWord(observation: XiaomiRegisterValue<XiaomiEscDecoder.StatusWord>?, nowMs: Long): Boolean =
        observation != null && observation.value != null && observation.state == FieldState.VALID &&
            observation.evidence != Evidence.SYNTHETIC && observation.source == "xiaomi.M365.ESC.7d" &&
            observation.observedAtMs >= 0 && observation.observedAtMs <= nowMs &&
            nowMs - observation.observedAtMs <= STATUS_WORD_MAX_AGE_MS &&
            observation.observedAtMs <= Long.MAX_VALUE - STATUS_WORD_MAX_AGE_MS
}
