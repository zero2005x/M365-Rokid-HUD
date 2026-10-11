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

V2 is negotiated per Wi-Fi socket. A missing hello remains V1 only when the vehicle
supports legacy M365 telemetry. A `PEVH` hello (TCP type `0x07`, 8-byte payload) selects
NEGOTIATED_V2 for typed-capable new/new peers, otherwise LEGACY_V1 or INCOMPATIBLE.
An INCOMPATIBLE socket receives no V1. Compatibility state belongs to each socket and
is removed on disconnect. Old phone/new glasses fall back to V1 without a phone hello;
new phone/old glasses continue to receive the unchanged V1 packet. BLE remains V1 only;
the core BLE fragmentation utility is not connected to production notifications.

Production Wi-Fi V2 uses TCP type `0x10`, payload `[phone sessionGeneration i64 BE][PEVG packet]`.
The TCP length prefix (i32 BE) includes the type byte and these 8 generation bytes, so the
maximum V2 declared length is 1 + 8 + 4096. The PEVG packet below remains unchanged and
uses little endian. The generation comes from the phone vehicle-session epoch, not the
glasses reconnect counter. Receiver resets its gate on socket reconnect; within a socket,
older generations and duplicate/older sequences are dropped. Sequence continues across
vehicle sessions while the Wi-Fi service runs.

Motor observations are stamped when the decoder receives data, never at gateway send time.
Each complete packet is aged using `generatedAtMs - observedAtMs` from the phone clock;
glasses use `fromBytesForRemoteDisplay` and never subtract their wall clock from a phone
observation. Local elapsed time detects silence on the socket independently. Non-VALID
fields clear their prior display value to —. M365 B0 supplies speed, SOC, TEMP_FRAME and
TOTAL_DISTANCE_M; received 0x3A supplies trip distance/time with its own observation times.
Primitive defaults, disputed legacy range and unobserved fields remain NOT_PROVIDED.

| Header offset | Representation |
|---|---|
| 0 | literal ASCII `PEVG`, 4 bytes |
| 4 | version 2, u8 |
| 5 | explicit vehicle family code, u8; unknown remains 0 |
| 6 | connection state, u8 |
| 7 | reserved zero |
| 8 | complete packet length including CRC, u16 |
| 10 | gateway stream sequence, u32 |
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

Inbound glasses messages allow only heartbeat feedback (0/8 bytes), a single battery byte
from 0–100, and a validated 8-byte version hello. Vehicle commands and client display-preference messages are refused. Display
preferences flow from phone to glasses. Production Wi-Fi and BLE battery ingress apply this gate.

Pending: production V2 BLE transport, capability UI beyond M365, actual alert-tracker
wiring, and hardware acceptance of the new Wi-Fi/UI path. Core/unit tests do not establish
V2 glasses or multi-vehicle hardware acceptance. Glasses never issue vehicle commands.
