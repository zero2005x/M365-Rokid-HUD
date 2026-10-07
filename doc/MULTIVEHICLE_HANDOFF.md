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
- Docs: `pev-protocol-core/{README,ARCHITECTURE,SOURCE_PROVENANCE}.md`.
- Unit tests for all of the above (see §7 for the last verified result).

## 7. Verification log (append; never write "passed" without a run)
| Date | Command | Result |
|---|---|---|
| 2026-10-07 | WSL `:pev-protocol-core:test :koverXmlReport` | BUILD SUCCESSFUL; 42 tests, 0 failures; Kover LINE 181/182, BRANCH 125/131 |
| 2026-10-07 | HUD app/glass-hud tests, Rust checks, lint | **NOT RUN** (only core module touched so far) |
| — | Sonar analysis | **NOT RUN** (sonar-project.properties updated to include the core module; CI must add `:pev-protocol-core:koverXmlReport`) |

## 8. Work queue (ordered). Owner = who may touch shared files
1. **Integration owner** (sonar-project.properties already updated): add CI step running `:pev-protocol-core:test :pev-protocol-core:koverXmlReport` in in `.github/workflows/ci.yml`; add `SUPPORT_MATRIX.md`, `COMMAND_MATRIX.md`, `TEST_REPORT.md`, `OWNER_VALIDATION.md` skeletons.
2. **Xiaomi adapter (B)**: wrap existing MIT logic (`app/.../protocol/FrameCodec.kt, ScooterSettingsWriter.kt, PlaintextTelemetryMapper.kt, ScooterModelRegistry.kt`) behind core types; keep Rust/JNI auth in the app. Verify the 0x7D status-word endianness, RMW preserving unrelated bits, readback. M365-only signed-speed fix must not leak to EUC codecs. M365 real-capture replay test.
3. **Begode/Gotway (C)**: A2 codec from corrected RX-only CAP-A fixtures (regenerate with the corrected tool into a new dir; record hashes). 24-byte frame reassembly (20+20+20+20+16 notifies), type@18, byte19 branches 0x00/01/04/07. Do **not**: fuse the two 16-bit distances, treat mode word@14 as PWM, assume raw/100 = packV (CAP-B B9 pending), invent SOC curve. Unknown scale ⇒ raw diagnostic field, not VALID physical value.
4. **Gateway (G)**: versioned serialization shared by BLE & Wi-Fi; keep legacy 20-byte M365 path; per-field validity/freshness; wider trip distance/time; old-glasses "upgrade needed" instead of zero-filling; reject any vehicle-setting message from glasses.
5. **Phone UI + app wiring**: adopt `CommandCoordinator` as the only GATT writer; experimental-mode screen (per device/session opt-in, shows profile/firmware/command/unit/why-unverified); no raw hex console.
6. **Zydtech (D), Ninebot ESx/G30 (B), Inmotion I1 + KingSong (E), Veteran/NOSFET + Inmotion I2 (F)**: each in its own `codec/<family>/` namespace with fixtures, tests, capability proposals, provenance rows. Unidentified layout ⇒ raw diagnostics only. Never infer family from UUID (FFE0/NUS collide).
7. **RideFlux integration**: Gradle composite build/explicit local artifact; RideFlux consumes core (GPL app may use MIT core; never reverse). Do it on an isolated checkout of a committed baseline; list impacts of its uncommitted Dashboard/Inmotion I2/Veteran edits.
8. Final: independent review (provenance, A2 corrections, UUID collision, setting gates, gateway unknown handling, M365 regression), owner validation checklist for M365/A2 (list unexecuted items explicitly).

Out of scope this round: Tuya/ThingClips, LEBI, KuKirin, NIU (no empty adapters), calibration/OTA/unlock/shutdown/battery-protection commands.
