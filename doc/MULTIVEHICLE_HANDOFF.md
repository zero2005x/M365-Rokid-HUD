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
- CI update DONE: `.github/workflows/ci.yml` runs `:pev-protocol-core:koverXmlReport` alongside app/glasses reports and verifies `pev-protocol-core/build/reports/kover/report.xml` for SonarCloud.
- Queue item 2b(ii) DONE: `:app` added `implementation(project(':pev-protocol-core'))`. `MotorInfoParser` delegates 0xB0 parsing to `XiaomiMotorInfoDecoder` while preserving HUD display clamping (raw ≥ 0xC000 / negative speed clamped to 0.0 km/h on HUD, without leaking to core/EUC). `ScooterSettingsWriter` delegates constants, builders, and plans to `XiaomiSettings` and `XiaomiPdu` and provides `toCommandPlan`. `XiaomiMotorInfoDecoder` updated for MIN_LENGTH=22 (optional frame temperature) and signed speed decoding with 0xC000 threshold (>32 km/h forward speed preserved). `scripts/app-and-core-test-wsl.sh` added.
- Docs: `pev-protocol-core/{README,ARCHITECTURE,SOURCE_PROVENANCE}.md`.
- Unit tests for all of the above (see §7 for the last verified result).

## 7. Verification log (append; never write "passed" without a run)
| Date | Command | Result |
|---|---|---|
| 2026-10-07 | WSL `:pev-protocol-core:test :koverXmlReport` | BUILD SUCCESSFUL; 42 tests, 0 failures; Kover LINE 181/182, BRANCH 125/131 |
| 2026-10-07 | scripts/core-test-wsl.sh (core + Xiaomi adapter) | BUILD SUCCESSFUL; 65 tests, 0 failures; Kover LINE 250/251, BRANCH 190/205 |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (core + app unit tests + Kover XML) | BUILD SUCCESSFUL; Core 66 tests, 0 failures (Kover LINE 256/257 covered); App 22 testsuites, 309 tests, 0 failures; Kover reports generated for both modules |
| — | Rust checks, lint, SonarCloud | **NOT RUN** (preBuild verifyRustJni checked with -PskipRustBuild; native JNI / lint / SonarCloud in CI) |

## 8. Work queue (ordered). Owner = who may touch shared files
1. **Integration owner**: CI step running `:pev-protocol-core:test :pev-protocol-core:koverXmlReport` added to `.github/workflows/ci.yml`; flesh out `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `TEST_REPORT.md`, `OWNER_VALIDATION.md` skeletons.
2. **Xiaomi adapter (B)** — 2a done (see §6), **2b(ii) done** (app delegates to core and app tests pass). **Remaining 2b**:
   - (i) migrate the other registers (BMS 0x31/0x35/0x40, ESC 0x25/0x3A/0x7B-0x7D reads, error 0x1B, firmware/identity) into `codec/xiaomi` with fixtures;
   - (iii) app-side `PevTransport` adapter over the Rust/JNI encrypt path — the core emits logical PDUs `[len,to,cmd,reg,payload]`, the adapter encrypts/frames;
   - (iv) M365 replay test over more of `logcat-spin-raw.txt` (152 payloads, sha in SOURCE_PROVENANCE) and the phone-log CSVs;
   - (v) do NOT reuse app `FrameCodec` (it is the Ninebot-style src/dst framing) for Xiaomi writes. Wrap existing MIT logic (`app/.../protocol/FrameCodec.kt, PlaintextTelemetryMapper.kt, ScooterModelRegistry.kt`) behind core types; keep Rust/JNI auth in the app. Verify 0x7D endianness, RMW preserving unrelated bits, readback. M365-only signed-speed fix must not leak to EUC codecs. M365 real-capture replay test.
3. **Begode/Gotway (C)**: A2 codec from corrected RX-only CAP-A fixtures (regenerate with the corrected tool into a new dir; record hashes). 24-byte frame reassembly (20+20+20+20+16 notifies), type@18, byte19 branches 0x00/01/04/07. Do **not**: fuse the two 16-bit distances, treat mode word@14 as PWM, assume raw/100 = packV (CAP-B B9 pending), invent SOC curve. Unknown scale ⇒ raw diagnostic field, not VALID physical value.
4. **Gateway (G)**: versioned serialization shared by BLE & Wi-Fi; keep legacy 20-byte M365 path; per-field validity/freshness; wider trip distance/time; old-glasses "upgrade needed" instead of zero-filling; reject any vehicle-setting message from glasses.
5. **Phone UI + app wiring**: adopt `CommandCoordinator` as the only GATT writer; experimental-mode screen (per device/session opt-in, shows profile/firmware/command/unit/why-unverified); no raw hex console.
6. **Zydtech (D), Ninebot ESx/G30 (B), Inmotion I1 + KingSong (E), Veteran/NOSFET + Inmotion I2 (F)**: each in its own `codec/<family>/` namespace with fixtures, tests, capability proposals, provenance rows. Unidentified layout ⇒ raw diagnostics only. Never infer family from UUID (FFE0/NUS collide).
7. **RideFlux integration**: Gradle composite build/explicit local artifact; RideFlux consumes core (GPL app may use MIT core; never reverse). Do it on an isolated checkout of a committed baseline; list impacts of its uncommitted Dashboard/Inmotion I2/Veteran edits.
8. Final: independent review (provenance, A2 corrections, UUID collision, setting gates, gateway unknown handling, M365 regression), owner validation checklist for M365/A2 (list unexecuted items explicitly).

Out of scope this round: Tuya/ThingClips, LEBI, KuKirin, NIU (no empty adapters), calibration/OTA/unlock/shutdown/battery-protection commands.
