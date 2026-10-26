package com.m365bleapp.protocol

import com.m365bleapp.repository.MotorInfo
import io.github.zero2005x.pev.core.codec.begode.BegodeA2Codec
import io.github.zero2005x.pev.core.codec.inmotioni1.InmotionI1Codec
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Passive notification stream session for Begode A2 (`0xffe0` / `0xffe1`).
 *
 * Contract from `protocols/begode/CONNECTION.md` and `READ_FIELDS.json`:
 * - Subscribing to `0xffe1` CCCD `0x2902` (`01 00`) is not yet `Ready`; at least
 *   one 24-byte `55 AA ... 5A 5A 5A 5A` frame must be reassembled and validated
 *   before the session transitions to `Ready` or exposes telemetry.
 * - Zero poll/configuration writes are ever emitted (`WRITE` is `insufficient`).
 * - The 9-octet ASCII `GW...` device-info reply is routed to [deviceInfoAscii]
 *   and never fed into the binary frame stream.
 * - Only real-vehicle-verified fields (`PACK_VOLTAGE` and `TEMP_IMU`) are exposed;
 *   unverified fields (speed, SOC, current, distance, mode word) stay absent
 *   (`Double.NaN` / `-1` / `NOT_PROVIDED`) so the UI and HUD never render fake zeros.
 * - If the stream stalls for more than [BegodeA2Codec.STALE_AFTER_MS] (1,000 ms),
 *   readings are aged to [FieldState.STALE] rather than retained as current.
 */
class BegodeA2Session(
    inputEvidence: Evidence = Evidence.WIRE_CAPTURED,
) {
    private val codec = BegodeA2Codec(BegodeA2Codec.Profile.A2, inputEvidence)

    var isReady: Boolean = false
        private set

    var deviceInfoAscii: String? = null
        private set

    var latestSnapshot: TelemetrySnapshot = TelemetrySnapshot()
        private set

    var lastValidFrameAtMs: Long? = null
        private set

    var validFrameCount: Long = 0L
        private set

    var malformedCount: Long = 0L
        private set

    sealed interface Update {
        data class DeviceInfo(val ascii: String) : Update
        data class Telemetry(
            val motorInfo: MotorInfo,
            val newlyReady: Boolean,
            val framesInChunk: Int,
        ) : Update
        data object AwaitingCompleteFrame : Update
    }

    fun onNotification(chunk: ByteArray, nowMs: Long = System.currentTimeMillis()): Update {
        if (chunk.isEmpty()) return Update.AwaitingCompleteFrame

        val ascii = BegodeA2Codec.parseDeviceInfoAsciiOrNull(chunk)
        if (ascii != null) {
            deviceInfoAscii = ascii
            return Update.DeviceInfo(ascii)
        }

        val events = codec.feed(chunk, observedAtMs = nowMs)
        var framesInChunk = 0
        val wasReady = isReady

        for (event in events) {
            when (event) {
                is BegodeA2Codec.Event.Frame -> {
                    framesInChunk++
                    validFrameCount++
                    isReady = true
                    lastValidFrameAtMs = nowMs
                    if (event.diagnostic.telemetry.fields.isNotEmpty()) {
                        latestSnapshot = latestSnapshot.merge(event.diagnostic.telemetry)
                    }
                }
                is BegodeA2Codec.Event.Malformed -> {
                    malformedCount++
                }
                is BegodeA2Codec.Event.Overflow -> {
                    malformedCount += event.dropped
                }
            }
        }

        if (framesInChunk == 0) {
            return Update.AwaitingCompleteFrame
        }

        return Update.Telemetry(
            motorInfo = toMotorInfo(nowMs),
            newlyReady = !wasReady && isReady,
            framesInChunk = framesInChunk,
        )
    }

    /**
     * Ages the snapshot when no frame has arrived within [BegodeA2Codec.STALE_AFTER_MS].
     * Returns an updated [MotorInfo] when a valid reading transitions to [FieldState.STALE],
     * or `null` when no state transition occurred.
     */
    fun ageIfStale(nowMs: Long = System.currentTimeMillis()): MotorInfo? {
        if (!isReady) return null
        val lastAt = lastValidFrameAtMs ?: return null
        if (nowMs - lastAt <= BegodeA2Codec.STALE_AFTER_MS) return null
        val hadValid = latestSnapshot.fields.values.any { it.state == FieldState.VALID }
        if (!hadValid) return null
        latestSnapshot = latestSnapshot.aged(nowMs, BegodeA2Codec.STALE_AFTER_MS)
        return toMotorInfo(nowMs)
    }

    fun toMotorInfo(nowMs: Long = System.currentTimeMillis()): MotorInfo {
        val fresh = latestSnapshot.aged(nowMs, BegodeA2Codec.STALE_AFTER_MS)
        val validVoltage = fresh[FieldId.PACK_VOLTAGE].takeIf { it.state == FieldState.VALID }?.value
        val validTemp = fresh[FieldId.TEMP_IMU].takeIf { it.state == FieldState.VALID }?.value
        return MotorInfo(
            speed = Double.NaN,
            battery = -1,
            temp = validTemp ?: Double.NaN,
            mileage = Double.NaN,
            avgSpeed = Double.NaN,
            tripSeconds = -1,
            tripMeters = -1,
            remainingKm = Double.NaN,
            packVoltageV = validVoltage,
            receivedAtMs = lastValidFrameAtMs ?: nowMs,
            typedFields = fresh.fields,
        )
    }

    fun reset() {
        codec.reset()
        isReady = false
        deviceInfoAscii = null
        latestSnapshot = TelemetrySnapshot()
        lastValidFrameAtMs = null
        validFrameCount = 0L
        malformedCount = 0L
    }
}

/**
 * Passive diagnostic envelope listener for InMotion I1 (`AA AA ... 55 55`).
 *
 * Performs bounded CAN-envelope framing and checksum validation via [InmotionI1Codec]
 * without issuing any writes or fabricating unverified physical readings.
 */
class InmotionI1Session(
    inputEvidence: Evidence = Evidence.WIRE_CAPTURED,
) {
    private val codec = InmotionI1Codec(inputEvidence = inputEvidence)

    var validFrameCount: Long = 0L
        private set

    var malformedCount: Long = 0L
        private set

    fun onNotification(chunk: ByteArray): List<InmotionI1Codec.Packet> {
        val events = codec.feed(chunk)
        val frames = mutableListOf<InmotionI1Codec.Packet>()
        for (event in events) {
            when (event) {
                is InmotionI1Codec.Event.Frame -> {
                    validFrameCount++
                    frames += event.packet
                }
                is InmotionI1Codec.Event.Malformed -> malformedCount++
                is InmotionI1Codec.Event.Overflow -> malformedCount += event.dropped
            }
        }
        return frames
    }

    fun reset() {
        codec.reset()
        validFrameCount = 0L
        malformedCount = 0L
    }
}
