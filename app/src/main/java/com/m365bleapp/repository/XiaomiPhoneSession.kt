// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.*
import io.github.zero2005x.pev.core.command.*
import io.github.zero2005x.pev.core.identity.*
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/** Only audited typed settings are available; this is not a raw command entry point. */
internal sealed interface XiaomiSetting {
    data class Kers(val level: Int) : XiaomiSetting
    data class Cruise(val on: Boolean) : XiaomiSetting
    data class TailLight(val on: Boolean, val order: StatusWordWriteOrder) : XiaomiSetting
    data class Units(val mph: Boolean, val order: StatusWordWriteOrder) : XiaomiSetting
}

/** Allocated only after an actual Xiaomi encrypted login, never from demo/plaintext Ready. */
internal class XiaomiPhoneSession(val transport: XiaomiEncryptedTransport, private val nowMs: () -> Long) {
    val authority = XiaomiTransactionAuthority(transport)
    private val consent = WriteSession(transport.deviceId, transport.connectionId, authenticated = true)
    @Volatile private var identity = DeviceIdentity(family = Family.XIAOMI_SCOOTER)
    @Volatile private var telemetry = TelemetrySnapshot()

    fun enableM365Experimental() {
        identity = DeviceIdentity(Family.XIAOMI_SCOOTER, "M365", source = IdentitySource.USER_SELECTED)
        consent.enableExperimental(identity)
    }

    fun observe(raw: ByteArray, atMs: Long) {
        val reply = XiaomiReply.parse(raw) ?: return
        if (reply.direction == 0x23 && reply.type == XiaomiPdu.CMD_READ && reply.register == XiaomiPdu.REG_MOTOR_INFO && reply.data.size == 32) {
            telemetry = XiaomiMotorInfoDecoder.decode(reply.data, atMs)
        }
    }

    fun execute(setting: XiaomiSetting): CommandResult = authority.execute({ read ->
        when (setting) {
            is XiaomiSetting.Kers -> plan(XiaomiSettings.kersPlan(setting.level))
            is XiaomiSetting.Cruise -> plan(XiaomiSettings.cruisePlan(setting.on))
            is XiaomiSetting.TailLight -> plan(XiaomiSettings.tailLightPlan(status(read), setting.on, setting.order, nowMs()))
            is XiaomiSetting.Units -> plan(XiaomiSettings.unitsPlan(status(read), setting.mph, setting.order, nowMs()))
        }
    }) { command ->
        val initial = state(command)
        CommandExecutionContext(initial) { state(command) }
    }

    private fun plan(result: PlanResult): CommandPlan? = (result as? PlanResult.Ok)?.plan

    private fun status(read: (Int, Int) -> ByteArray?): XiaomiStatusWordObservation? {
        val raw = read(XiaomiPdu.REG_STATUS_WORD, 2) ?: return null
        val reply = XiaomiReply.parse(raw) ?: return null
        val at = transport.lastReplyAtMs ?: return null
        return XiaomiStatusWordObservation(XiaomiEscDecoder.statusWord(reply.data, at),
            CommandBinding(transport.deviceId, transport.connectionId, identity.profileKey))
    }

    private fun state(plan: CommandPlan) = CurrentWriteState(transport.deviceId, identity, consent,
        plan.parameterValues, telemetry, nowMs())

    fun close() { consent.revokeExperimental(); authority.close() }
}
