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
- No AGENTS.md exists in the HUD repo. Files have mixed LF/CRLF endings. Preserve each file's existing endings; do not blanket-convert or rewrite with `Set-Content`. For retained CRLF files, use `git -c core.whitespace=cr-at-eol diff --check`.

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
Sonar: core sources/tests/Kover are configured in `sonar-project.properties` and CI. External Sonar has not run locally (no scanner/token).
Use JDK21 explicitly: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH="$JAVA_HOME/bin:$PATH"`.
`scripts/verify-hud-wsl.sh` stages the checkout and runs core/App/glasses Kover, both lintDebug and debug APK builds; it explicitly uses existing native artifacts with `-PskipRustBuild`. Do not run staging scripts concurrently against the same stage.

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
- Queue item 4 (Gateway G) PARTIAL: original `51536b5` draft passed unit tests but independent review found decoder length/CRC overlap, incorrect magic, V1 precision/wrap differences, zero-filled unknown values, and conflated current/temperature sensors. This continuation fixes the core wire contract and delegates actual BLE/Wi-Fi V1 producers and glasses V1 parser to it. V2 remains an unreleased core schema; negotiated delivery, MTU framing, upgrade UI and alert freshness/deduplication are still required. No multi-vehicle glasses support is claimed.
- Command safety hardening DONE: `CommandCoordinator` and `CommandGate` hardened against ungated execution, uncorrelated ACK, cancellation after final delay, reconnect write races, profile changes restoring consent, pack/source identity omissions, mutable buffer exposure, cross-session RMW reuse/expiry, late confirmations, and wrong-source/type readback.
- Coverage enforcement DONE: `scripts/check-core-coverage.py` enforces core LINE and BRANCH coverage >= 90%.
- Docs: `pev-protocol-core/{README,ARCHITECTURE,SOURCE_PROVENANCE}.md`, `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `OWNER_VALIDATION.md`, `TEST_REPORT.md`.
- Unit tests for all of the above (see §7 for the last verified result).

## 7. Verification log (append; never write "passed" without a run)
| Date | Command | Result |
|---|---|---|
| 2026-10-07 | WSL `:pev-protocol-core:test :koverXmlReport` | BUILD SUCCESSFUL; 42 tests, 0 failures; Kover LINE 181/182, BRANCH 125/131 |
| 2026-10-07 | scripts/core-test-wsl.sh (core + Xiaomi adapter) | BUILD SUCCESSFUL; 65 tests, 0 failures; Kover LINE 250/251, BRANCH 190/205 |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (pre-change core + app unit tests) | BUILD SUCCESSFUL; Core 66 tests, 0 failures; App 22 testsuites, 361 tests, 0 failures (309 in the earlier handoff was incorrect) |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (integrated core + app unit tests + Kover XML) | BUILD SUCCESSFUL; Core 142 tests, 0 failures; App 22 testsuites, 367 tests, 0 failures; Core Kover LINE 569/569 (100.00%), BRANCH 522/549 (95.08%) |
| 2026-10-07 | scripts/app-and-core-test-wsl.sh (with Gateway protocol V1/V2 & Guard) | BUILD SUCCESSFUL; Core 155 tests, 0 failures; App 22 testsuites, 367 tests, 0 failures; Core Kover LINE 819/822 (99.64%), BRANCH 621/651 (95.39%) |
| 2026-10-07 | Windows native toolchain cargo test (ninebot-ffi) | Prior agent recorded 5 passed; not rerun/independently witnessed in the 2026-10-08 continuation |
| 2026-10-07 | Windows native toolchain cargo test (ninebot-ble) | Prior agent recorded 148 passed; not rerun/independently witnessed in the 2026-10-08 continuation |
| 2026-10-07 | Windows native toolchain test-jni.ps1 | Prior agent recorded 56 passed; not rerun/independently witnessed in the 2026-10-08 continuation |
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
   - 2b(v): SOFTWARE CONTRACTS DONE (fresh session-bound RMW, explicit write order, signed-speed isolation). Physical 0x7D endianness remains unresolved; owner procedure NOT EXECUTED.
   - 2b(iii) [Remaining]: app-side `PevTransport` adapter over the Rust/JNI encrypt path — the core emits logical PDUs `[len,to,cmd,reg,payload]`, the adapter encrypts/frames.
3. **Begode/Gotway (C)**:
   - A2 raw-only codec + unit tests + 11,424 CAP-A frame replay [DONE].
   - Remaining: command builders/plans for verified or experimental Begode settings when safe.
4. **Gateway (G) [PARTIAL]**: share legacy V1 serialization/parser and strict inbound command refusal. Finish per-connection capability/version negotiation, V2 BLE fragmentation + Wi-Fi framing, generation/field freshness, upgrade UI, semantic display and alert deduplication; exercise old/new clients. Existing primitive M365 update APIs still lack field provenance and are not safe multi-vehicle entry points. No new vehicle may enter that legacy path.
5. **Phone UI + app wiring**: adopt `CommandCoordinator` as the only GATT writer; experimental-mode screen (per device/session opt-in, shows profile/firmware/command/unit/why-unverified); no raw hex console.
6. **Zydtech (D), Ninebot ESx/G30 (B), Inmotion I1 + KingSong (E), Veteran/NOSFET + Inmotion I2 (F)**: each in its own `codec/<family>/` namespace with fixtures, tests, capability proposals, provenance rows. Unidentified layout ⇒ raw diagnostics only. Never infer family from UUID (FFE0/NUS collide).
7. **RideFlux integration [PARTIAL]**: isolated `RideFlux-pev-core-consumer`, branch `codex/pev-core-consumer`, local commit `eb94187` on explicit base `9bd9eaa`; configurable thin Gradle included build consumes external MIT core without source copying. M365 read PDUs/SOC/odometer/frame temperature delegated; legacy speed/B9 compatibility still unresolved. Strict domain/protocol/core tests passed 684, M365Codec 100% line / 93.55% branch. Keep newer main privacy/token-log fixes and owner version commit `ee23b875` when integrating; original checkout untouched. Android lint and both debug APK builds also passed; full command/gateway migration remains pending. Follow-up evidence is committed as `c984f52`; see consumer docs for exact results.
8. Final: independent review (provenance, A2 corrections, UUID collision, setting gates, gateway unknown handling, M365 regression), owner validation checklist for M365/A2 (list unexecuted items explicitly).

Out of scope this round: Tuya/ThingClips, LEBI, KuKirin, NIU (no empty adapters), calibration/OTA/unlock/shutdown/battery-protection commands.

## 9. Latest continuation checkpoint — 2026-10-08
- HUD core fix commit `8d6587c`; production V1/ingress/lifecycle/permission wiring commit `1b1b2d9`.
- `scripts/verify-hud-wsl.sh` BUILD SUCCESSFUL: core 166 + phone 367 + glasses 12 tests,
  zero failures/errors/skips; core LINE 836/838 (99.76%), BRANCH 885/937 (94.45%); both lintDebug
  zero errors (phone 98 warnings / 2 hints; glasses 19 warnings), both debugAPKs assembled.
  Use `PEV_GRADLE_ONLINE=1` only if declared dependencies are missing from offline cache.
- Native libraries used with explicit `-PskipRustBuild`; host fingerprint separator warning remains.
  Rust/native freshness/hardware/Sonar were not newly certified. Prior Rust counts above are historical.
- Gateway production now shares V1 encoder/parser and rejects glasses vehicle commands. V2 schema
  is core-only and documented in `pev-protocol-core/GATEWAY_SCHEMA.md`; retain PARTIAL status.
- BLE invalid CRC no longer extends old telemetry freshness; session starts stale and ignores late
  callbacks from an old GATT object. Atomic check/mutation synchronization is still a follow-up.
- Lint fixes: collect lifecycle once from onCreate and switch bound StateFlow clients; permission
  rejection in preference notification is handled; disconnect always releases local/native resources.
- Independent read-only review completed gateway schema/production paths, lifecycle/permission
  changes and isolated RideFlux consumer. MotorInfo's old signed-i16/median-ratio comment corrected
  to the implemented 0xC000 compatibility threshold and limited replay evidence; no algorithm change.
- RideFlux owner HEAD is `ee23b875`; remote main advanced from `b7a51ad` to `83ef5a8835345d624bbaec3ad899c89b81b084fc` on recheck. Inspect that new diff before further consumer work; only owner GEMINI
  remediation prompt untracked. No reset/stash/owner-file edit/push/merge/release/vehicle write.
- Consumer exact hashes, strict dependency integrity evidence and 684 test results live in its committed
  `docs/PEV_CORE_CONSUMER.md`, `docs/pev-core-validation.json`, `docs/pev-core-artifact-verification.json`.
- Consumer Android follow-up `c984f52`: strict offline lint and APK tasks BUILD SUCCESSFUL; phone/HUD
  zero lint errors, 37/13 warnings. Both APKs contain the MIT decoder and no coverage-agent namespace.
  New build/test dependency POMs audited as Apache-2.0, with separate shaded ASM BSD-3-Clause attribution.
  Exact evidence: `docs/pev-core-android-validation.json` and `docs/pev-core-license-audit.json`.

### Next-agent entry point
Read this file, `TEST_REPORT.md`, source ledger and the consumer docs, then re-query both repositories
and owner working-tree status. The pasted historical baseline is obsolete. Never cherry-pick GPL
consumer changes into HUD. Do not treat the isolated older RideFlux base as current released main.

Next implementation priority: finish the Rust/GATT epoch-bound adapter and isolate polling/settings
through one coordinator; phone per-session experimental-mode UI; negotiated V2 delivery/display;
then factual, provenance-audited additional family codecs/settings. Use the source/research chains
and field/command matrices. Physical M365 0x7D order and A2 CAP-B scaling remain owner tests NOT RUN.
Keep source ownership per namespace, preserve screenshots/owner edits, and append actual commands
and results before local commits. If interrupted, leave exact active process/staging and file-owner
state here; do not convert unrun work into DONE.
