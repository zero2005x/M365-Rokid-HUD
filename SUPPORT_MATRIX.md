# Support matrix (model + firmware + field granularity)

Evidence: S = synthetic, V = vendor-static, W = wire-captured on a real vehicle, H = owner hardware-verified.
Presence of a decoder is **not** vehicle verification. Add one row per model/firmware/field; never widen a scope without evidence.

| Family | Model | Firmware | Field | Decoder | Evidence | Notes |
|---|---|---|---|---|---|---|
| Xiaomi scooter | M365 | any (not recorded) | SOC | `XiaomiMotorInfoDecoder` @8 u16 | W | one capture session 2026-09-20 |
| Xiaomi scooter | M365 | any | speed | @10 i16 m/h, signed as decoded | W | cross-checked vs odometer rate (median 1.023); stationary negative values exist; display clamp is app policy |
| Xiaomi scooter | M365 | any | total distance | @14 u32 m | W | |
| Xiaomi scooter | M365 | any | frame temperature | @22 i16 /10 | W | `TEMP_FRAME`, not IMU/MOS |
| Xiaomi scooter | M365 / Pro / Pro2 / 1S / Lite | — | other registers (BMS 0x31/0x35/0x40, ESC 0x25/0x3A, error 0x1B…) | not yet migrated from `app/.../protocol/*Parser.kt` | V | queue item 2b |
| Xiaomi scooter | Pro, Pro2, 1S, Lite, Mi3 | — | all | **not scoped** | — | no per-model evidence; commands denied by gate |
| Others | see doc/MULTIVEHICLE_HANDOFF.md §8 | | | not started | | |
