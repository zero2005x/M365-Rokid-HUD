# Command matrix

Mode: N = normal (requires VEHICLE_VERIFIED), X = experimental opt-in (per device, per session). Confirm: readback / ack / none.
All commands default to `RetryPolicy.NEVER`.

| Family | Model | Command | Register / bytes (logical PDU) | Params | Evidence | Mode | Confirm | Riding-affecting | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Xiaomi | M365 | KERS level | `04 20 02 7B <lvl> 00` | 0..2 integer | V | X | exact 2-byte LE readback, ESC/read/register match | yes | fresh trusted stationary speed required |
| Xiaomi | M365 | Cruise | `04 20 02 7C <0/1> 00` | on/off | V | X | exact 2-byte LE readback, ESC/read/register match | yes | HIGH risk; fresh trusted stationary speed required |
| Xiaomi | M365 | Tail light always on | `04 20 02 7D <w1> <w2>` | on | V | X | readback `0x7D` LE u16 == intended word | no | RMW from fresh word; **write byte order unresolved** (Scootbatt BE vs M365 Tools LE), caller must pass `StatusWordWriteOrder` |
| Xiaomi | M365 | Units mph | same as above, bit 4 | on | V | X | as above | no | same caveat |
| Xiaomi | all | Lock/unlock, SHFW select, BMS writes | — | — | — | **excluded** | — | — | out of scope by design |

Core plans now carry their exact parameter values. Coordinator requires live authorized context,
device/connection-bound transport writes, and correlated notification cursors; ACK_ONLY requires an explicit
protocol predicate. A reception cursor does not prove full transaction correlation: the App must isolate
polling and command replies when wiring the sole writer. No timeout retry is performed.

0x7D plans require a timestamped, VALID observation bound to the exact device/connection/profile.
They expire after 2 seconds, retain unrelated bits, and require an explicit byte order. Legacy
`ScooterSettingsWriter.toCommandPlan` refuses missing reads, malformed bytes, no-ops and multi-bit changes.
Byte-only compatibility builders convey no authorization. Normal mode has no hardware-validated command
here; App experimental UI and Rust transport hookup remain pending. Existing repository light/lock APIs
predate this core and have not yet been routed through it; do not claim all production writes are gated.


Inmotion I1 receive framing is now implemented; all I1 setting/write capabilities remain closed.
No TX encoder, auth, model-specific command scope, unit/range or confirmation plan is claimed.
Reopen each setting only after a pinned source establishes that exact model/firmware, encoding,
parameters and confirmation behavior; do not infer write access from AA AA or a successful replay.
