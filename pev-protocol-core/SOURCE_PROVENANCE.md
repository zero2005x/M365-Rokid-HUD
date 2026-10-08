# Source provenance

Rule: unknown license => do not copy. Protocol *facts* (offsets, units, commands) are re-specified
neutrally and implemented independently; no GPL class, test, or expressive spec text is
copied, translated or refactored into this MIT module. Having read a source is not a clean-room claim.

| Source | URL / ref | License | Used for | Redistributable |
|---|---|---|---|---|
| M365-Rokid-HUD (this repo) | github.com/zero2005x/M365-Rokid-HUD @ 3d01e6f | MIT | code, fixtures | yes |
| PEVAppRE findings (local, read-only) | `/home/kali/PEVAppRE` (euc-programme/findings, scooter-apps/) | owner's research notes; vendor-app derived facts | protocol facts only; no vendor code or expressive text copied | vendor code/vendor-owned captures: no; owner M365 derivatives: existing fixture authorization below; A2 redistribution unresolved |
| RideFlux (local) | `Android/RideFlux` @ 9bd9eaa82e55efaa936afa5d332e7ef2c752b7b8, moving workspace | GPL | NOT a code/test/spec source; future consumer only | n/a |
| Vendor apps (Begode/Gotway, KingSong, Inmotion, Veteran, Zydtech, Ninebot) | via PEVAppRE | proprietary | facts about wire format only | no |

## Per-fixture log (append rows)
| Fixture | Origin | Evidence label | Transform / hash | Redistributable |
|---|---|---|---|---|
| inline in XiaomiMotorInfoDecoderTest (stationary 0xB0 reply data, 2026-09-20 14:30:39) | owner's own M365 MIScooter8964, PEVAppRE/scooter-apps/hardware-evidence/logcat-spin-raw.txt sha256 E92CF09A2D3A6392367DC1DCBCF243A2174307B96E558BDD11B3F506A3B35B58 | wire-captured | hex copied from decrypted Rx Decrypted line, header 2301B0 and 4-byte pad stripped | yes (owner's data, MIT repo) |
| inline in same test (0xB0 block with speed bytes 76 EA, 2026-09-20 14:34:02) | same file | wire-captured | MotorInfo raw data (32 bytes) line copied verbatim | yes |
| inline in XiaomiPduTest (3 20 01 B0 20) | same file, Query 0xb0 line | wire-captured | none | yes |

Open gaps: confirm redistribution rights for any wire capture before committing it as a fixture;
derive fixtures into `src/test/resources/fixtures/` with source sha256 + transform tool hash recorded above.

## Continuation sources (2026-10-07)
All implementation is independently written from neutral facts, with no legal clean-room claim.
MIT App DTOs remain compatibility adapters; vendor decompiler classes and GPL code/tests were not copied.

| Source / pinned artifact | License / rights | Use / limits |
|---|---|---|
| HUD `app/.../BmsTelemetryParser.kt` at 0d614823; original SHA256 `1818c4b3e6642fb8dce75ef3fc8b2e189d133062b2dd9c986987f4a72556a966` | repo MIT, independently authored analysis-derived parser | offset/unit facts for BMS; VENDOR_STATIC only, no BMS hardware validation |
| HUD `doc/reverse-engineering/scootbatt-reports/02-telemetry-parsing.md`, SHA256 `08813b849152d3f1edf755fdca0ec29478c6c5dbf7e590d6de8242fb2f323a98` | owner's MIT research text, proprietary source not copied | neutral register facts, corrected 0x39 minimum five bytes; 0x25 conflicts preserved raw |
| HUD `ninebot-ble/doc/protocol.md`, SHA256 `687fdc22c44ba7b4eda505bb2dfc0b6e48d4cbc8fcbb97eb870a33adcd1c143c` | repo MIT reference | literal 0x3A sample and trip counters; 0x25 annotation/arithmetic conflict unresolved |
| Owner M365 `logcat-spin-raw.txt`, SHA256 `e92cf09a2d3a6392367dc1dcbcf243a2174307b96e558bdd11b3f506a3b35b58` | existing owner-fixture authorization recorded above; not vendor-owned code/capture | 152 RX B0 + 3 RX3A + 1 RX25, sanitized; no identifiers/keys/auth/serial data |
| `scripts/extract-m365-fixtures.py`, SHA256 `c55621ea1108aae994291adbfff70d127a20cdef2140ad74ba2e4b4cfd1a50d0` | new MIT extraction tool | source/phone/output hashes and transformation in `src/test/resources/fixtures/xiaomi/m365/manifest.json`; originals unchanged |
| Synthetic BMS/ESC/command/Begode tests | newly authored MIT | boundary contracts only; never vehicle-verified |

CAP-A correction chain read as neutral facts only (paths relative to PEVAppRE `euc-programme/findings/`):

| Document | SHA256 |
|---|---|
| BEGODE_A2_TRUTH_TABLE.md | `7218be424557526adc135d0e7efa068221a01a7fca48c85fd9b02872480828d8` |
| CONFIRMED_CORRECT.md (rev3) | `d3e16f9688edfe8c53db309497f2b5a49191395620188aa295363ad619bfcb13` |
| CAP-B_PREFLIGHT_2026-09-30.md | `68086c0a559970026e7e74d90c6eb772484dee91ea2e5646acda7884a2e35ed8` |
| CAP-B_TOOLING_FIXES.md | `d03c676c9c1f882752678e17b2607e9f9651968f2941f40f6c5d071a7c27b96c` |
| POST_CAPB_REMEDIATION_BACKLOG.md | `edf3dc4e2f987197a140cfc10570c9355cbf1a1495416afbf4a02f17ee4e1366` |
| CAP-B_A2_VENDOR_COMPARISON.md | `b271e5065b5fd1a4183f648d662347b6396d77ae526d56d04e48974e4c24ae46` |
| BEGODE_A2_RC1_READINESS.md | `cd28ee41a1eee4d7a100384f1a1562b214ff9b8e16754ebee9ed61a0166392ff` |

Raw-only A2 code uses envelope/channel/branch facts, no vendor code or copied research prose.
CAP-A wire artifact and corrected extractor are used read-only for local replay, never packaged
or committed as MIT fixtures; concrete capture/tool redistribution rights must be resolved before
a distributable A2 captured test corpus. This does not block synthetic tests or raw diagnostics.

## Gateway continuation sources (2026-10-08)
The V1 contract is derived from this MIT repository's committed production producer/parser bytes.
No external vendor or RideFlux source is used to author the MIT gateway schema.

| Source at HUD commit `51536b5` | SHA256 (Git blob bytes) | Use / redistribution |
|---|---|---|
| `app/.../gateway/M365GattServer.kt` | `fc6fcbc88e49faefbacca0e4ffaae22f8472e2ff913e9c2c781b31dc2c1f9ba6` | MIT legacy Double quantization, narrow wrap, layout and CRC facts |
| `app/.../gateway/wifi/WifiGatewayServer.kt` | `580eea86ebe3bd5c5ef30f3089fb2c7e9599aaf247c41d99bedc074af44e4ad5` | MIT second producer contract and inbound message directions |
| `glass-hud/.../DataModels.kt` | `54257147b069d17d9a84e04e614cd0dfa5ebf0439fd0de0cd05c3d7775245d5b` | MIT signed/unsigned decode and Float display precision contract |
| New gateway V1/V2 and consumer tests | independently authored synthetic MIT vectors | Software contracts only; no hardware or vendor validation |

V2 is a new unreleased schema, not a recovered vehicle protocol. MIT core is used in RideFlux as
a dependency; edits to its GPL consumer remain there. Neither consumer code nor tests flow back
into this module. No new third-party dependency was introduced.

## Dependencies / module license
`LICENSE` is the unchanged repository MIT text, SHA256 `e256d9bcab8c7c56971e927f5a78f10c4c6b8c7bf944d369ad2f1938dddb162a`.
No new external dependency was added. Existing Kotlin standard library is Apache-2.0
([upstream license](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt));
JUnit4 4.13.2 is test-only EPL-1.0
([upstream license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt));
Kover 0.9.9 is build/test instrumentation, Apache-2.0
([upstream license](https://github.com/Kotlin/kotlinx-kover/blob/main/LICENSE.TXT)).
Their licenses remain their own; no dependency is relabeled MIT. No Android/JNI/GPL dependency
is in the core. Rokid SDK and existing App dependency licensing were not re-audited this round;
no new SDK inclusion or release approval is claimed.
