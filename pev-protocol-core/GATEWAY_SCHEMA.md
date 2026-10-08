# Gateway contract — unreleased 0.1.0 snapshot

V1 is the existing M365 20-byte packet. Phone BLE and Wi-Fi producers use the same
`GatewayV1Frame` encoder, and glasses parse it through the same module. Double inputs multiply
before truncation; narrow integers wrap exactly as before. The parser preserves the original
Float display precision and accepts the first 20 bytes of a larger buffer. Parsed DTOs are for
display, **not** lossless byte forwarding. CRC covers bytes 0–17.

The primitive compatibility type has no unknown/provenance fields. New vehicle integrations
must use `GatewayCodec.encodeV1`, which refuses non-M365, missing, invalid, stale or wider values
and returns an explicit incompatibility result. It requires eight fresh typed readings, including
trip/time, average speed and range. Existing primitive producer APIs are retained for M365 only;
they do not satisfy the new multi-vehicle freshness contract.

V2 is an independently designed **core-only, unreleased** packet. No deployed client is expected
to understand the earlier draft. Do not send V2 until per-connection negotiation, clock alignment
and bounded transport reassembly exist. Packet integer fields use little endian.

| Header offset | Representation |
|---|---|
| 0 | literal ASCII `PEVG`, 4 bytes |
| 4 | version 2, u8 |
| 5 | explicit vehicle family code, u8; unknown remains 0 |
| 6 | connection state, u8 |
| 7 | reserved zero |
| 8 | complete packet length including CRC, u16 |
| 10 | per-connection sequence, u32 |
| 14 | sender generation time, nonnegative i64 epoch milliseconds |
| 22, 23 | field count and alert count, each u8 |

Field records then alert records follow in ascending stable wire-ID order. Each record contains
ID/state/evidence/reserved-zero (four u8), observation time (i64, -1 means absent), value (f64),
source UTF-8 byte length (u8), and source bytes (0–96). A trailing CRC16/MODBUS covers the entire
packet except its two little-endian checksum bytes. Complete packet size is bounded to 4096 bytes;
length, entry counts, record bounds, duplicate/unknown IDs and trailing data are rejected.

State codes are 0 NOT_PROVIDED, 1 VALID, 2 STALE, 3 INVALID, 4 UNSUPPORTED. Evidence codes are
0 absent, 1 SYNTHETIC, 2 VENDOR_STATIC, 3 WIRE_CAPTURED, 4 VEHICLE_VERIFIED. An unavailable record
stores canonical zero bits but decodes to a null value. VALID/STALE values require a finite,
representable number, original nonfuture observation time, evidence and a nonblank source.
Sources reject control characters, invalid Unicode and excessive UTF-8 length. No synthetic
record gains hardware evidence by serialization.

Stable field IDs/units live in `GatewayField`; battery and phase current, plus frame/IMU/MOS/
motor/battery temperatures, have distinct IDs. Trip/time counters are exact integers from zero
through 2^53−1. Representation bounds are wire safety limits, not verified vehicle capabilities.
Average/range inputs also require typed readings; they cannot override a core measurement.

Encoding ages readings using caller time and policy. Decoding requires receiver time on the
phone's epoch basis, rejects future generation times and ages original observations. Never
substitute receipt time. Alert values are 0/1 with the same metadata rules. `GatewayAlertTracker`
suppresses duplicate/out-of-order sequences, expires observations and emits newly active edges.
Its owner must reset it on reconnect; complete snapshots clear omitted alerts.

Inbound glasses messages allow only heartbeat feedback (0/8 bytes) and a single battery byte
from 0–100. Vehicle commands and client display-preference messages are refused. Display
preferences flow from phone to glasses. Production Wi-Fi and BLE battery ingress apply this gate.

Pending: capability/version negotiation, V2 BLE fragmentation and Wi-Fi envelope, upgrade UI,
clock alignment, semantic V2 display and actual per-connection alert tracker wiring. No V2
glasses or multi-vehicle gateway acceptance is claimed by the core tests.
