# Multi-vehicle core — agent handoff (read this first)

Living status file. **Any agent continuing the work: read this, then update §3/§4 as you finish items,
commit, and leave the next agent a clean state.** Do not push, merge or release without the owner.

## 1. Source of truth for the task
- Full requirements prompt: `/home/kali/PEVAppRE/handoffs/20261007-m365-rokid-multivehicle/AI_AGENT_PROMPT.md`
  (Windows: `\\wsl.localhost\kali-linux\home\kali\PEVAppRE\handoffs\20261007-m365-rokid-multivehicle\`), plus `RESEARCH.md` beside it.
- PEVAppRE is **read-only** reference. RideFlux (`../RideFlux`, GPL, has uncommitted edits) must not be reset/stashed/overwritten and is never a code source.

## 2. Baseline (recorded 2026-10-07)
- HUD `origin/main` = `3d01e6f492141fd2c29aced4b19f98d51cb8364c`; old local branch `fix/negative-speed-and-gateway-retry` has an identical tree (squash-merged).
- Work branch: `feat/pev-protocol-core` (from origin/main). Untracked `glasses-screen.png`, `phone-screen.png` are the user's; leave them.
- No AGENTS.md exists in the HUD repo. Files in the repo use CRLF — use editor tools that preserve endings; do not rewrite whole files with `Set-Content`.

## 3. Decisions already made (do not re-ask)
Scooters + EUCs, telemetry + settings; experimental (unverified) writes only behind an explicit per-device/per-session
opt-in on the phone; only vehicles owner can test: **Xiaomi M365, Begode A2**; independent MIT core consumed by HUD and RideFlux;
settings on phone only, glasses show telemetry + alerts only (never vehicle settings).

## 4. Build / test (this machine)
Native Windows Gradle fails here ("Unable to establish loopback connection", even unsandboxed). Use WSL (Kali):
```bash
src=/mnt/c/Users/liangtinglin/Documents/codebase/Android/M365-Rokid-HUD; st=/home/kali/build/pev-core-stage
rsync -a --delete --exclude build --exclude .gradle --exclude .git --exclude target --exclude '*.jks' --exclude '*.bak' --exclude research --exclude docs --exclude .idea $src/ $st/
cd $st && sed -i 's/\r$//' gradlew && printf 'sdk.dir=/home/kali/android-sdk\n' > local.properties
bash gradlew --no-daemon --offline :pev-protocol-core:test :pev-protocol-core:koverXmlReport
```
(WSL has JDK 21/25 only, hence the core pins `jvmTarget 17` instead of a toolchain. Passing multi-line scripts via `wsl bash -c` from PowerShell mangles quotes — write a `.sh` file and run it.)
Full repo build/tests: `scripts/build-wsl.ps1` expects `/home/kali/ScooterHacking/env.sh`, which does **not** exist now — recreate or adapt.
Sonar: `sonar-project.properties` lists sources/tests/coverage paths; **the new module is not yet in it** (see queue item 1).

## 5. Quality gates for every change (SonarCloud)
Small functions, no unused code, no `!!` where avoidable, no duplicated literals, tests for every branch, no new
dependencies without license check. Pure logic must be JVM-unit-tested (Kover). Keep coverage on new code high (aim ≥ 90%).

## 6. Done so far (branch `feat/pev-protocol-core`)
- `:pev-protocol-core` module (Kotlin/JVM, MIT, registered in settings + root kover).
- `telemetry/` Reading/FieldState/Evidence/FieldId/TelemetrySnapshot (unknown ≠ 0, aging).
- `identity/` DeviceIdentity + IdentitySource + profileKey.
- `codec/StreamReassembler` + `FixedFrameSplitter` (bounded, resync, diagnostics).
- `command/` CommandSpec/Plan, CommandGate (default-deny, experimental opt-in bound to profileKey), WriteSession, CommandCoordinator (single writer, no auto-retry, ACK vs readback vs unconfirmed).
- Queue item 2a DONE: `codec/xiaomi/` — `XiaomiPdu` (logical read/write messages + reply parse), `XiaomiMotorInfoDecoder` (0xB0: SOC/speed signed/total distance/`TEMP_FRAME`, evidence WIRE_CAPTURED, 2 real-capture fixtures), `XiaomiSettings` (KERS, cruise, tail light, units; all readback-confirmed, VENDOR_STATIC ⇒ experimental-only, M365-scoped; 0x7D is RMW from a fresh word and **requires an explicit `StatusWordWriteOrder`**, no default).
- Root docs started: `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `OWNER_VALIDATION.md` (0x7D byte-order test procedure). `scripts/core-test-wsl.sh` runs core tests in WSL.
- Queue item 2b(i) DONE: `codec/xiaomi/` — `XiaomiBmsDecoder` (0x31/0x35/0x40/0x38/0x39/0x3B, cell voltages, dual temperatures, cycle/charge counts), `XiaomiEscDecoder` (0x25, 0x3A, 0x1B error code, 0x1A/0x39 firmware/identity, 0x7B/0x7C/0x7D settings readback), and `XiaomiRegisterBytes`.
- Queue item 2b(iv) DONE: `XiaomiCaptureReplayTest` replaying 152 real B0 payloads + 3 RX3A + 1 RX25 from `logcat-spin-raw.txt` with manifest verification, regression oracles, and 92 matching phone CSV records.
- Queue item 2b(v) / App delegation DONE: `BmsTelemetryParser` and `EscTelemetryParser` delegate to `XiaomiBmsDecoder` and `XiaomiEscDecoder` while preserving legacy app behaviors (display clamping, percent fallback, nullable status).
- Queue item 3 (Begode A2 raw codec) DONE: `BegodeA2Codec` in `io.github.zero2005x.pev.core.codec.begode`, implementing bounded 24-byte envelope reassembly, 0x00/01/04/07 branch decoding with byte19 discriminator, and strictly raw diagnostic representations (no fake PWM, no fused distances, no unproven voltage/SOC scaling). Compiled-core replay across 11,424 CAP-A frames verified.
- Queue item 4 (Gateway G) DONE: `io.github.zero2005x.pev.core.gateway` — `GatewayProtocol`, `GatewayV1Frame` (20-byte M365 legacy format with CRC-16/MODBUS), `GatewayV2Frame` (versioned multi-vehicle structured payload with per-field validity bitmask, alerts, wide trip distance/duration), `GatewayCodec` (refuses V1 encoding for non-M365 vehicles without zero-filling; encodes V2 with explicit validity flags), and `GatewayCommandGuard` (strictly rejects any vehicle setting/write attempt from glasses).
- Command safety hardening DONE: `CommandCoordinator` and `CommandGate` hardened against ungated execution, uncorrelated ACK, cancellation after final delay, reconnect write races, profile changes restoring consent, pack/source identity omissions, mutable buffer exposure, cross-session RMW reuse/expiry, late confirmations, and wrong-source/type readback.
- Coverage enforcement DONE: `scripts/check-core-coverage.py` enforces core LINE and BRANCH coverage >= 90%.
- Docs: `pev-protocol-core/{README,ARCHITECTURE,SOURCE_PROVENANCE}.md`, `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `OWNER_VALIDATION.md`, `TEST_REPORT.md`.
- Unit tests for all of the above (see §7 for the last verified result).

## 7. Verification log (append; never write "passed" without a run)
| Date | Command | Result |
|---|---|---|
| 2026-10-07 | WSL `:pev-protocol-core:test :koverXmlReport` | BUILD SUCCESSFUL; 42 tests, 0 failures; Kover LINE 181/182, BRANCH 125/131 |
| 2026-10-07 | scripts/core-test-wsl.sh (core + Xiaomi adapter) | BUILD SUCCESSFUL; 65 tests, 0 failures; Kover LINE 250/251, BRANCH 190/205 |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (pre-change core + app unit tests) | BUILD SUCCESSFUL; Core 66 tests, 0 failures; App 22 testsuites, 309 tests, 0 failures |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (integrated core + app unit tests + Kover XML) | BUILD SUCCESSFUL; Core 142 tests, 0 failures; App 22 testsuites, 367 tests, 0 failures; Core Kover LINE 569/569 (100.00%), BRANCH 522/549 (95.08%) |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (with Gateway protocol V1/V2 & Guard) | BUILD SUCCESSFUL; Core 155 tests, 0 failures; App 22 testsuites, 367 tests, 0 failures; Core Kover LINE 819/822 (99.64%), BRANCH 621/651 (95.39%) |
| 2026-10-07 | Windows native toolchain cargo test (ninebot-ffi) | 5 tests passed; 0 failed |
| 2026-10-07 | Windows native toolchain cargo test (ninebot-ble) | 148 tests passed; 0 failed |
| 2026-10-07 | Windows native toolchain test-jni.ps1 | 56 checks passed |
| 2026-10-07 | WSL compiled-core replay of Begode A2 CAP-A | 11,424 frames decoded, 0 overflows, 0 physical guesses |
| — | SonarCloud | NOT RUN (CI configured; local Sonar scanner/token not present; see TEST_REPORT.md) |

Note on JNI build stamp:
`app/build.gradle.kts` computes `rustInputFingerprint` over file relative paths using host file separators (`\` on Windows, `/` on Linux). The Rust sources in `ninebot-ffi` and `ninebot-ble` are pristine and unedited. The Windows-built `rust-build.stamp` matches the Windows fingerprint (`e80791fd...`). When building in WSL/Linux, use `-PskipRustBuild` unless rebuilding native libraries with a Linux NDK toolchain.

## 8. Work queue (ordered). Owner = who may touch shared files
1. **Integration owner**: CI step running `:pev-protocol-core:test :pev-protocol-core:koverXmlReport` added to `.github/workflows/ci.yml`; `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `TEST_REPORT.md`, `OWNER_VALIDATION.md` completed. [DONE]
2. **Xiaomi adapter (B)**:
   - 2a: DONE (`codec/xiaomi/XiaomiPdu`, `XiaomiMotorInfoDecoder`, `XiaomiSettings`)
   - 2b(i): DONE (`XiaomiBmsDecoder`, `XiaomiEscDecoder`, `XiaomiRegisterBytes`)
   - 2b(ii): DONE (`MotorInfoParser`, `ScooterSettingsWriter` delegate to core)
   - 2b(iv): DONE (M365 152-payload capture replay + phone CSV correspondence test)
   - 2b(v): DONE (0x7D endianness, RMW, signed-speed isolation, unit tests)
   - 2b(iii) [Remaining]: app-side `PevTransport` adapter over the Rust/JNI encrypt path — the core emits logical PDUs `[len,to,cmd,reg,payload]`, the adapter encrypts/frames.
3. **Begode/Gotway (C)**:
   - A2 raw-only codec + unit tests + 11,424 CAP-A frame replay [DONE].
   - Remaining: command builders/plans for verified or experimental Begode settings when safe.
4. **Gateway (G)**: versioned serialization shared by BLE & Wi-Fi; keep legacy 20-byte M365 path; per-field validity/freshness; wider trip distance/time; old-glasses "upgrade needed" instead of zero-filling; reject any vehicle-setting message from glasses. [DONE]
5. **Phone UI + app wiring**: adopt `CommandCoordinator` as the only GATT writer; experimental-mode screen (per device/session opt-in, shows profile/firmware/command/unit/why-unverified); no raw hex console.
6. **Zydtech (D), Ninebot ESx/G30 (B), Inmotion I1 + KingSong (E), Veteran/NOSFET + Inmotion I2 (F)**: each in its own `codec/<family>/` namespace with fixtures, tests, capability proposals, provenance rows. Unidentified layout ⇒ raw diagnostics only. Never infer family from UUID (FFE0/NUS collide).
7. **RideFlux integration**: Gradle composite build/explicit local artifact; RideFlux consumes core (GPL app may use MIT core; never reverse). Do it on an isolated checkout of a committed baseline; list impacts of its uncommitted Dashboard/Inmotion I2/Veteran edits.
8. Final: independent review (provenance, A2 corrections, UUID collision, setting gates, gateway unknown handling, M365 regression), owner validation checklist for M365/A2 (list unexecuted items explicitly).

Out of scope this round: Tuya/ThingClips, LEBI, KuKirin, NIU (no empty adapters), calibration/OTA/unlock/shutdown/battery-protection commands.
