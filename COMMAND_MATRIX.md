# Command matrix

Mode: N = normal (requires VEHICLE_VERIFIED), X = experimental opt-in (per device, per session). Confirm: readback / ack / none.
All commands default to `RetryPolicy.NEVER`.

| Family | Model | Command | Register / bytes (logical PDU) | Params | Evidence | Mode | Confirm | Riding-affecting | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Xiaomi | M365 | KERS level | `04 20 02 7B <lvl> 00` | 0..2 | V | X | readback `0x7B` byte0 | yes | |
| Xiaomi | M365 | Cruise | `04 20 02 7C <0/1> 00` | on | V | X | readback `0x7C` byte0 | yes | HIGH risk |
| Xiaomi | M365 | Tail light always on | `04 20 02 7D <w1> <w2>` | on | V | X | readback `0x7D` LE u16 == intended word | no | RMW from fresh word; **write byte order unresolved** (Scootbatt BE vs M365 Tools LE), caller must pass `StatusWordWriteOrder` |
| Xiaomi | M365 | Units mph | same as above, bit 4 | on | V | X | as above | no | same caveat |
| Xiaomi | all | Lock/unlock, SHFW select, BMS writes | — | — | — | **excluded** | — | — | out of scope by design |
