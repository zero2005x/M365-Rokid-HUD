# Support matrix (model + firmware + field granularity)

Evidence: S = synthetic, V = vendor-static, W = wire-captured on a real vehicle, R = historical community reference (not owner acceptance), H = owner hardware-verified.
Presence of a decoder is **not** vehicle verification. Add one row per model/firmware/field; never widen a scope without evidence.

| Family | Model | Firmware | Field | Decoder | Evidence | Notes |
|---|---|---|---|---|---|---|
| Xiaomi scooter | M365 | unknown, no all-firmware claim | SOC | `XiaomiMotorInfoDecoder` @8 u16 | W | 152 historical owner payloads; {43,44,51}; no new hardware acceptance |
| Xiaomi scooter | M365 | unknown | speed | @10 u16 with 0xC000 negative/sentinel threshold | W | signed core observation retained; HUD clamps negatives; this raw subset does not prove the historical moving-window scale claim |
| Xiaomi scooter | M365 | unknown | total distance | @14 u32 m | W | historical 400107–400871 m |
| Xiaomi scooter | M365 | unknown | frame temperature | @22 i16 /10 | W | 31–45 C; `TEMP_FRAME`, not IMU/MOS |
| Xiaomi scooter | M365 | unknown | BMS status 0x31 | `XiaomiBmsDecoder`: capacity mAh, SOC, battery current A, pack voltage V | V | no BMS capture; invalid SOC is not clamped in core |
| Xiaomi scooter | M365 | unknown | BMS 0x35 / 0x40 | two temperature sensors / ten indexed cell slots | V | sensor locations unknown; zero slots are NOT_PROVIDED policy, not proof of absent cells |
| Xiaomi scooter | M365 | unknown | BMS 0x30 / 0x18 / 0x1B / 0x3B | charging / design capacity / cycle counters / health | V | exact units retained; ESC 0x1B is a different namespace |
| Xiaomi scooter | M365 | unknown | ESC 0x3A | trip seconds and metres, separate unsigned u16 | V + W bytes | three RX blocks retained; physical unit acceptance still unexecuted |
| Xiaomi scooter | M365 | unknown | ESC 0x25 | raw u16 diagnostic only | V + W bytes | /10 vs /100 unresolved; existing HUD range/UI remains legacy and disputed |
| Xiaomi scooter | M365 | unknown | ESC 0x1B / 0x7B / 0x7C / 0x7D | error / KERS / cruise / status word | V | unknown enums invalid; read status LE; setting write order unresolved |
| Xiaomi scooter | M365 | unknown | ESC 0x66 / 0x39 | three raw words / version components | V | no inferred board roles, serial, MAC, or model identity |
| Begode | A2 (explicitly selected) | unknown | 24-byte framing, type @18, branch @19, eight raw channels | `BegodeA2Codec` | W layout, S test vectors | four branches; type1 battery layout unresolved; UNKNOWN profile gets uninterpreted diagnostics |
| Begode | A2 | unknown | voltage / SOC / current / temperatures / physical distances / PWM | absent | — | raw diagnostics only; CAP-B NOT CAPTURED; mode@14 never PWM and two distance words never fused |
| Xiaomi scooter | Pro, Pro2, 1S, Lite, Mi3 | — | all | **not scoped** | — | no per-model evidence; commands denied by gate |
| Inmotion I1 | V5F/V8S historical reference filenames; exact in-band identity not implemented | unknown | CAN envelope, raw ID/data/channel/format/type/extension | `InmotionI1Codec` | R structure, S contracts | 501 reference frames; escaped CHECK supported; unsupported length codes rejected; no model inferred from filename or AA AA |
| Inmotion I1 | all models | unknown | speed / SOC / physical voltage/current/temperature/distances / interpreted alerts / auth / settings | absent | — | empty physical snapshot; model layouts/scales and setting plans remain unimplemented |
| Others | see doc/MULTIVEHICLE_HANDOFF.md §8 | | | not started | | |

Remaining capability gaps: no public family registry, no Ninebot/Zydtech/KingSong/Veteran/I2 core codec,
no Begode settings, and no phone session/UI wiring yet. No aggregate support badge is justified.

## Consumer integration (2026-10-08)
HUD phone delegates M365 register reads to core; phone BLE/Wi-Fi and glasses share core V1 bytes.
RideFlux's isolated `codex/pev-core-consumer` branch consumes the same authoritative external source
for M365 read PDUs, SOC, odometer and frame temperature. It retains disputed legacy speed/B9 display
policies and independent Ninebot diagnostics. This is not full core migration or vehicle acceptance.
V2 delivery/negotiation and the unique phone writer/experimental UI remain pending in both products.
See `TEST_REPORT.md` and the isolated consumer's `docs/PEV_CORE_CONSUMER.md` for actual validation.
