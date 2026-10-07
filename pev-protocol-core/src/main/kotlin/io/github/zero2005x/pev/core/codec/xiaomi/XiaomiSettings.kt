package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.command.CommandPlan
import io.github.zero2005x.pev.core.command.CommandScope
import io.github.zero2005x.pev.core.command.CommandSpec
import io.github.zero2005x.pev.core.command.ConfirmKind
import io.github.zero2005x.pev.core.command.ParamSpec
import io.github.zero2005x.pev.core.command.ReadbackSpec
import io.github.zero2005x.pev.core.command.RetryPolicy
import io.github.zero2005x.pev.core.command.WriteStep
import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.telemetry.Evidence

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

    /** [currentWord] must be the word just read from 0x7D; null refuses (never assume 0). */
    fun tailLightPlan(currentWord: Int?, on: Boolean, order: StatusWordWriteOrder): PlanResult =
        wordWrite(TAIL_LIGHT, currentWord, TAIL_LIGHT_BIT, on, order)

    fun unitsPlan(currentWord: Int?, mph: Boolean, order: StatusWordWriteOrder): PlanResult =
        wordWrite(UNITS, currentWord, MPH_BIT, mph, order)

    private fun byteWrite(spec: CommandSpec, register: Int, value: Int): PlanResult {
        val payload = byteArrayOf(value.toByte(), 0x00)
        val readback = ReadbackSpec(XiaomiPdu.read(register, payload.size)) { raw ->
            XiaomiReply.parse(raw)?.let { it.register == register && Le.u8(it.data, 0) == value } == true
        }
        return PlanResult.Ok(CommandPlan(spec, listOf(WriteStep(XiaomiPdu.write(register, payload))), readback))
    }

    private fun wordWrite(spec: CommandSpec, current: Int?, bit: Int, set: Boolean, order: StatusWordWriteOrder): PlanResult {
        if (current == null) return PlanResult.Refused("fresh 0x7D word required for read-modify-write")
        if (current !in 0..0xFFFF) return PlanResult.Refused("status word out of u16 range")
        val word = if (set) current or bit else current and bit.inv()
        val hi = (word ushr 8).toByte()
        val lo = word.toByte()
        val payload = if (order == StatusWordWriteOrder.BIG_ENDIAN) byteArrayOf(hi, lo) else byteArrayOf(lo, hi)
        val register = XiaomiPdu.REG_STATUS_WORD
        // Read-back is always little-endian: the stored word must equal the intended word.
        val readback = ReadbackSpec(XiaomiPdu.read(register, 2)) { raw ->
            XiaomiReply.parse(raw)?.let { it.register == register && Le.u16(it.data, 0) == word } == true
        }
        return PlanResult.Ok(CommandPlan(spec, listOf(WriteStep(XiaomiPdu.write(register, payload))), readback))
    }
}
