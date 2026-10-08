# Owner validation checklist (M365 and Begode A2 only)

Nothing below has been executed. Do not mark a row passed without recording date, firmware, app version and raw log.

## Xiaomi M365 — status-word write byte order (resolves BE vs LE)
Preconditions: M365 stationary on stand, wheel off ground, official Mi Home app available for comparison, phone build with experimental mode enabled for this device only.
1. Read `0x7D` (`03 20 01 7D 02`); record the word W0 and note tail-light and unit state in the official app.
2. Experimental: tail light ON via `StatusWordWriteOrder.BIG_ENDIAN`. Read back.
   - Readback == W0|0x0002 and official app shows tail light on, unit unchanged -> BIG_ENDIAN confirmed.
   - Readback differs or the units flipped -> STOP. Do not retry or guess an alternate write order. Use the official app to restore the previous settings and record raw frames.
3. Test the other order only in a separate explicitly authorized test session after the original app confirms restoration; obtain a new timestamped read first.
4. Stop conditions: any unintended unit/tail-light change, any error reply, link loss mid-write.
5. Record: firmware, both raw write frames, both readbacks, official-app screenshots.
Outcome feeds `COMMAND_MATRIX.md` for the exact device/firmware/command. A single result does not
justify a family-wide write byte order or global default.

## Xiaomi M365 — KERS / cruise (stand only, wheel off ground)
Write each value, confirm via readback and official app. Cruise: verify OFF after test. Record raw frames.

## Begode A2
Historical CAP-A structural replay is complete; no new vehicle acceptance or CAP-B result exists.
The core currently produces raw diagnostics only. Its unknown physical fields must display unknown.

1. Record exact selected A2 profile, firmware/board reported by the original app (do not infer from `GW1511014`), charger label and pack information.
2. Stationary capture: retain RX/TX direction, peer and notification boundaries. Compare exact 24-byte frames and each type/byte19 branch with the original app.
3. CAP-B B9 remains unexecuted: compare original-app voltage, measured/charger evidence and raw voltage word at the same time. Do not choose raw/100 vs 20/16 scaling or an SOC curve from plausibility alone.
4. Nonzero speed/current/PWM and named sensor temperature scales remain unexecuted. Mode word@14 is never PWM; distance words@6/@8 remain separate.
5. No Begode command is currently exposed. Stop on mismatched profile, wrong framing, stale values, unexpected setting changes, disconnection or error; preserve the capture for review.

## Phone command workflow acceptance (not yet wired)
Verify per-device/session explicit opt-in; reconnect/profile/firmware/pack changes revoke it.
Attempt missing/stale/future speed and ensure riding settings refuse rather than treating unknown as zero.
Attempt missing/expired/other-session 0x7D reads and ensure zero writes. Cancel during a multi-step
delay/readback, reconnect, or inject normal telemetry as an ACK and ensure no false confirmation.
Inspect logs for sent/ACK/readback/timeout/cancel distinction; no timeout auto-retry.

The Rust transport, unique production writer, phone experimental UI, and versioned glasses gateway
are pending. These are owner acceptance steps for a future integrated build, not capabilities
already available in the current APK. Glasses receive only telemetry/alerts; no vehicle write flow
may be added there. No checklist item here is passed by a synthetic test or historical replay.


## Glasses BLE session acceptance (NOT EXECUTED)
1. Connect to the phone gateway, receive valid telemetry, and retain the connection/build identifiers.
2. Reconnect while old telemetry/RSSI/service-discovery callbacks and watchdog/subscription work
   are queued. Only the new connection may update telemetry, preferences, RSSI or freshness.
3. Disconnect manually while auto-reconnect is pending; disable then re-enable auto-reconnect.
   Neither action should resurrect an older delayed connection attempt.
4. Stop/restart scans while results/failures from an older scan remain queued. They must not
   connect, mark Error or cancel a new scan. Close/destroy must leave no delayed scan or GATT leak.
5. Revoke Bluetooth permission, return unknown BatteryManager data, reject telemetry subscription,
   and feed malformed CRC packets. Expect an explicit error/stale state and no invented freshness.
6. Record actual Android/Rokid/phone versions, timestamps and old/new GATT session diagnostics.
   Synthetic JVM ownership tests do not establish Bluetooth stack timing or hardware acceptance.

Inmotion I1 has no owner-testable vehicle in this scope. The 501-frame historical reference replay
does not pass an owner checklist, validate physical scales or authorize a setting.
