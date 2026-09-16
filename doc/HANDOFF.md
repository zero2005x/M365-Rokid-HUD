> 2026-09-16 Windows/Kali integration: see [current build and integration notes](WINDOWS_WSL_BUILD.md). Earlier handoff and protocol-only status claims below are historical where superseded.

## WiFi HUD / JNI continuation — 2026-09-16

This update supersedes the earlier open D5/D6 entries.

- BleConnectionService owns UnifiedConnectionManager; MainActivity collects its
  complete HUD snapshot (connection, telemetry, clock, freshness, signal quality
  and display preferences). Explicit INTERNET/ACCESS_NETWORK_STATE permissions
  are now declared in the glasses manifest.
- BLE stays usable during WiFi discovery. Connected WiFi takes priority; socket
  failure falls back to available BLE data. Cached preferences and time are
  replayed on every transport switch. CXR-M is not started on the glasses.
- WiFi now uses the same 20-byte CRC-checked telemetry decoder as BLE, including
  unsigned fields and isValid=true. It expires telemetry after 3 seconds, checks
  TCP length bounds, reconnects after failures, and invalidates old discovery/
  socket callbacks on disconnect. A partial-frame read timeout closes the socket.
- Ten glasses host tests cover CRC/unsigned decoding, fragmented/coalesced TCP
  frames, malformed/truncated input, preference replay and BLE/WiFi fallback.
- JNI coverage: five Rust registry lifecycle/concurrency tests plus 56 real-JVM
  boundary checks against all seven exported functions, including independent
  login/decryption vectors and invalid/stale handles. CI runs both suites.
  See ninebot-ffi/tests/README.md.
- Validation: both Debug APKs built; glasses 10/10 and phone 107/107 unit tests
  passed; Rust JNI 5/5 tests and Java JNI 56 checks passed.
- No real phone/glasses/network or scooter was attached. Live NSD discovery,
  OEM background behavior and physical transport switching remain unverified.
  No protocol-support confidence was promoted. No commit or push was made.

### Physical WiFi HUD acceptance still needed
1. Put phone and glasses on the same LAN; enable the phone WiFi HUD gateway.
   Confirm the glasses foreground notification says Connected via WiFi.
2. Change field selection and text scale on the phone; check the glasses update.
   Check telemetry, clock, phone battery and signal indicator.
3. With both gateways enabled, disable WiFi or stop its gateway and verify BLE
   data/preferences resume; restore WiFi and verify it becomes active again.
4. Pause telemetry while retaining heartbeats and check the stale indicator after
   roughly 3 seconds. Stop/restart the glasses service and confirm one connection,
   no old callbacks reconnecting, and preferences arriving again.

## Continuation update — 2026-09-16 (WSL2 Kali)

This update supersedes D1/D3 and the old CI configuration description below.
The earlier sections remain a historical snapshot.

- Work continues in /home/kali/ScooterHacking/repo. Existing uncommitted changes were retained.
- On arrival, CI already built Rust (D1's skipRustBuild contradiction was already removed).
  This continuation adds host protocol tests, a JNI crate test command, explicit Debug
  unit-test targets, a 45-minute timeout, SDK path validation/fallback, and checks that
  all four native ABIs exist. JDK 21 matches the locally verified toolchain.
- D3 fixed: ScooterRepository now calls MotorInfoParser, and MotorInfoParserTest calls
  that same production decoder. Seven regression tests cover offsets, signedness,
  short packets, optional temperature, existing trip/range preservation and battery
  fallback. Existing behavior, including the fallback for zero battery and signed
  odometer handling, is deliberately preserved pending hardware evidence.
- Sonar configuration explicitly limits its scope to Kotlin/Java. Rust host tests
  do not constitute Rust static analysis or verification of unexposed JNI protocols.
- Verification: both Debug APK tasks succeeded; 107 Kotlin tests passed; 146 Rust
  protocol tests passed. JNI cargo test compiled and passed with ZERO tests (D6
  remains open). The CI ELF script passed for all four ABIs, each with 0x4000
  PT_LOAD alignment. Workflow YAML parsing and shell syntax checks passed.
- GitHub-hosted CI and Sonar quality gate have not been run. WiFi HUD integration,
  JNI-specific tests, hardware captures and the four-byte offset question remain open.
- No commit or push was made. Existing whitespace/CRLF warnings remain in the wider
  working tree; this continuation did not reformat unrelated files.

# Handoff | 交接文件

> **Audience**: an AI agent or engineer taking over this repository.
> **Purpose**: state exactly what is done, what is not, what is *claimed without
> evidence*, and what must happen before this can be pushed to GitHub with CI and
> SonarQube passing.
>
> **Everything below was verified by running commands in this workspace.** Where
> something is a claim rather than a measurement, it says so.

---

## 0. Read this first

### 0.1 The single most important fact

**No change in this repository has ever been tested against real hardware.**

Every verification is a compile, a host unit test, or a cross-compile. There has
been no M365, no Ninebot, no G2, and no Rokid glasses attached at any point. Any
statement of the form "this works on X" is **unverified**, including statements
about the M365 path that existed before this work.

This is not a caveat to skim. It is the central risk in the tree and it shapes
everything in §3.

### 0.2 The second most important fact

**A large amount of new Rust code is not in the APK, and cannot be.**

`ninebot-ffi` exports exactly seven JNI functions, all of them
`mi_crypto::*`. The linker dead-code-eliminates everything else. The new modules
(`mtu`, `transport`, `model`, `identity`, `ninebot_legacy`, `encryption2`) are
exercised only by host tests; their symbols are **absent** from
`libninebot_ffi.so`.

Verified by:
```bash
nm -D --defined-only app/src/main/jniLibs/arm64-v8a/libninebot_ffi.so | grep Java_com
# -> only 7 symbols, all M365Native_*
strings app/src/main/jniLibs/arm64-v8a/libninebot_ffi.so | grep -c Encryption2Session
# -> 0
```

The build chain is correct; the *reachability* is missing. See §5.2.

### 0.3 State of the tree

| | |
| --- | --- |
| Branch | `main` |
| HEAD | `7c764cb fix(build): make missing Rokid credentials non-fatal for release packaging` |
| Remote | `https://github.com/zero2005x/M365-Rokid-HUD.git` |
| Modified | 41 files |
| Untracked | 20 files |
| Commits made this session | **none** — all work is uncommitted |

---

## 1. Every goal that was stated

These were the explicit requests, in order. Nothing is omitted.

### Phase A — UI / HUD refactor (the main objective)

The app's interface was described as "混亂" (chaotic) and the following were
requested, after a six-question clarification round whose answers are binding:

1. **Visual**: pure-black instrument style, large type, a single accent colour,
   remove purple remnants, remove the duplicated title bar.
2. **Single control page**: merge scan + dashboard into one page that shows
   device selection while disconnected and becomes the dashboard in place once
   connected.
3. **Connection options automatic**: hide register/encryption/plaintext/Gateway
   from the rider; decide automatically, reveal only on failure.
4. **Settings hub**: move protocol, experimental models, debug logs, battery
   optimisation, language and sponsorship into Settings/Advanced.
5. **Phone controls the glasses display**: choose which telemetry fields the HUD
   renders, synced over the existing gateway protocol.
6. **Offline-readable detail page**: cache model / serial / firmware / odometer.
7. Constraints chosen by the user: **read-only telemetry only** (no writes);
   **phone + glasses in one pass**; **both manuals replaced by the merged page**.
8. Acceptance: every phase must pass `assembleDebug`.

### Phase B — Protocol foundation (requested afterwards)

- **Phase 0**: toolchain + protocol documentation.
- **Phase 1**: `Transport` trait so protocol logic is testable without hardware;
  MTU−3 fragmentation on both directions with dropped-write detection and retry;
  three GATT profile auto-discovery; `5AA5`/`55AA` framing with CRC16 and both
  vendor reply codes; probe-and-cache detection.
- **Phase 2**: Xiaomi family `ModelProfile` registry (M365 / Pro / Pro2 / 1S /
  Lite, Mi 3 marked Unverified), register tables from `etransport/ninebot-docs`,
  existing fe95 path preserved byte-for-byte, existing tokens unaffected.
- **Phase 3**: NinebotCrypto family (ESx / Max G30 / E / F / T15) —
  `0x5B/0x5C/0x5D` pairing, TEA/XTEA, chained AES, `msgIt`; write functions
  gated or removed; pairing warnings in the UI; `doc/NINEBOT_LEGACY_PROTOCOL.md`
  marked Unverified.
- **Phase 4**: new generation (G2 / F2 / D / T15) — implement Encryption2
  identification and handshake; return "unsupported" for telemetry and say so in
  the UI; **do not guess register addresses**.
- **Phase 5**: `ScanScreen` loses the hardcoded prefix and gains a
  model + confidence badge; manual model override as an escape hatch;
  capability-driven `ScooterInfoScreen`; model column in `TelemetryLogger`.
- **Phase 6**: converge the duplicated M365 constants to one source; README and
  `BLE_PROTOCOL_GUIDE.md` updated; `MODEL_SUPPORT.md` support matrix.
- **Build chain**: a Gradle task calling `cargo ndk` and copying artifacts, so
  "edited Rust but forgot to rebuild" is impossible.

### Phase C — Diagnostic requests

- Resolve the M365 `0xB0` offset dispute (`MODEL_SUPPORT.md` §8).
- Wire `GattProfileDiscovery` + `ProtocolProbe` into the connection layer so
  detection actually runs.

---

## 2. What is done, and the evidence for it

### 2.1 Verification summary (measured, current)

```bash
./gradlew --no-daemon clean :app:assembleDebug :glass-hud:assembleDebug \
          :app:testDebugUnitTest
# BUILD SUCCESSFUL
# app-debug.apk        72,688,760 bytes
# glass-hud-debug.apk  30,086,218 bytes
# verifyRustJniLibs: libninebot_ffi.so present and current for all 4 ABIs

cd ninebot-ble && cargo test --no-default-features     # 146 passed, 0 failed
cd ninebot-ble && cargo check --target aarch64-linux-android \
   {,--features ble,--features write-ops,--all-features}  # all ok
```

Kotlin unit tests: **107 passing**, in 8 classes.

### 2.2 Phase A — all seven items

| # | Item | Where | State |
| --- | --- | --- | --- |
| 1 | Black instrument visual | `ui/theme/{Color,Type,Dimens,Theme}.kt`, `res/values/themes.xml` | Done. Zero purple references (only a comment naming them). Title bar fixed by `android:Theme.Material.NoActionBar`. |
| 2 | Merged control page | `ui/HomeScreen.kt`, `NavGraph.kt` | Done. `startDestination = "home"`; `Crossfade` between scan and telemetry. |
| 3 | Connection options automatic | `ui/ScanScreen.kt` (`ConnectDialog`) | Done. Register checkbox removed; `register` derived from `repository.isRegistered()`. |
| 4 | Settings hub | `ui/SettingsScreen.kt` | Done. Display / Glasses / Advanced / About. Advanced holds model override, protocol diagnostics, language, logging, log viewer, diagnostics share, issue tracker. |
| 5 | Phone → glasses display control | `M365HudGattProfile.kt`, `HudDisplayScreen.kt`, `DisplayPrefsStore.kt`, `glass-hud/…/DataModels.kt`, `HudScreen.kt` | Done over **BLE** and **WiFi**. 12 selectable fields. |
| 6 | Offline detail page | `repository/VehicleSnapshotStore.kt`, `ui/ScooterInfoScreen.kt` | Done. Identity persisted to disk; telemetry in memory; age banner; `—` for absent values. |
| 7 | Capability-driven fields | `ui/ScooterInfoScreen.kt`, `ui/DashboardScreen.kt` | Done **including controls** — lock and tail-light buttons are gated on `ModelCapabilities`, added late in the work. |

### 2.3 Phase B — protocol work

| Module | Tests | Notes |
| --- | --- | --- |
| `ninebot-ble/src/mtu.rs` | 10 | Single home for the `ATT_MTU − 3` rule. |
| `ninebot-ble/src/transport.rs` | 12 | Async `Transport` trait + `MockTransport`. |
| `ninebot-ble/src/model/mod.rs` | 23 | `ModelProfile` registry, `Confidence`. |
| `ninebot-ble/src/identity.rs` | 9 | Scan identity rule, moved out of the BLE-gated scanner so it runs on a host. |
| `ninebot-ble/src/ninebot_legacy.rs` | 28 | NinebotCrypto cipher. **Unverified.** |
| `ninebot-ble/src/encryption2.rs` | 41 | Encryption2 cipher + family identification. |
| `ninebot-ble/tests/motor_info_offsets_test.rs` | 11 | Pins the Rust decoder's byte offsets. |
| Kotlin `protocol/` | 94 | MTU, framing, GATT discovery, retry policy, model registry, override, doc consistency. |

`write-ops` Cargo feature is **off by default**; the write modules
(`session/{lock,light,settings}.rs`) are behind it. `write-ops` implies `ble`.

### 2.4 Build chain (Phase: ".so build chain")

Three Gradle tasks, wired into `preBuild` so every `assemble*` pulls them in:

- `buildRustJni` — `cargo ndk` → `build/rustJni/<abi>/`. `isIgnoreExitValue = false`.
- `copyRustJniLibs` — copies into `src/main/jniLibs`, writes a SHA-256 stamp.
  `outputs.upToDateWhen { false }`, so it can never be skipped.
- `verifyRustJniLibs` — recomputes the hash at execution time and fails on
  mismatch.

Verified empirically: editing `mi_crypto::crc16` changed the `.so`
(`afef500f3c40` → `eb1e5d65b00a`) and the stamp; reverting restored both, and the
stamp was stable across three consecutive identical builds.

`app/src/main/jniLibs/` is now **gitignored** and the four committed `.so` files
are untracked (`git rm --cached`).

### 2.5 Phase C

**Offset dispute — partially resolved, and my earlier conclusion was WRONG.**

I previously reported that three implementations disagreed. Re-deriving the byte
accounting from source showed that report compared offsets counted from different
base pointers. `decrypt_uart` yields `P = [size][D][T][attr][data…]`, and the
Android parser is handed `packet[3 until len-4]`, so `data[i] == P[i+3]`. The two
shipped parsers read **the same bytes**:

| Field | Rust (in `P`) | Android offset | Android in `P` |
| --- | --- | --- | --- |
| battery | 11 | 8 | 11 |
| speed | 13 | 10 | 13 |
| average speed | 15 | 12 | 15 |
| odometer | 17 | 14 | 17 |
| temperature | 25 | 22 | 25 |

`rust_P_offset == android_offset + 3`.

**One question remains**: both reach battery 8 bytes past the 3-byte header, while
`M365ESC.md` places it at offset 4 — a 4-byte gap. Either the response carries a
4-byte prefix (parsers right) or both parsers are wrong. **Offline evidence cannot
distinguish these.** See §5.1.

Pinned by `tests/motor_info_offsets_test.rs`, which builds a synthetic payload
with pairwise-distinct values (a 4-byte odometer of 100000 that no 2-byte field
could hold).

**Connection-layer wiring — done.**

- `BleManager.onServicesDiscovered` adapts real GATT objects into
  `GattServiceView` / `GattCharacteristicView` and publishes through
  `GattProfileDiscovery`. Decision logic stays in the unit-tested pure module.
- `ScooterRepository` replaces its UART UUIDs with the discovered profile
  (NUS tried first, deliberately).
- Notification routing changed to role-based (`uuid != AUTH_UPNP && uuid !=
  AUTH_AVDTP` is the data plane) so a late profile switch cannot drop frames.
- `ProtocolProbe` records `XIAOMI_MI` **only after a completed login**, and
  forgets it when the handshake fails.
- `connectionDiagnostics()` produces a shareable block, surfaced in Settings.

---

## 3. What is NOT done — read before claiming anything

### 3.1 Hard blockers (cannot proceed without the user)

| # | Blocker | Blocks |
| --- | --- | --- |
| B1 | **No hardware captures at all.** No HCI snoop log has been provided. | Verifying any protocol work; resolving the 4-byte offset question; every "Unverified" marker. |
| B2 | **No real-hardware test of the UI.** | The entire Phase A refactor is compile-verified only. |

Capture instructions are in `doc/MODEL_SUPPORT.md` §0.

### 3.2 Known defects and gaps in the tree

| # | Item | Severity | Detail |
| --- | --- | --- | --- |
| D1 | ~~CI would fail~~ | **Fixed** | `ci.yml` ran `./gradlew test -PskipRustBuild` and then required `jniLibs/*.so` to exist — impossible together, and the files are now gitignored. **Fixed by building Rust in CI** rather than skipping it; the project is not shippable without the native library. See §4.1. |
| D2 | New Rust modules unreachable from JNI | High | §0.2. Needs a JNI surface before the protocol work can affect the app. |
| D3 | `MotorInfoParserTest` tests itself | Medium | Its private `parseMotorInfo` re-implements the parser with **different offsets** (speed@4, battery@26, odometer@8) from the production parser (speed@10, battery@8, odometer@14). It cannot detect a production bug. 7 tests, all vacuous with respect to the real code. |
| D4 | `responses_test.rs` is truncated | Low | The vector declares 37 payload bytes and carries 6. Valid but only constrains register `0x25`. Its own name is `it_guess_what_distance_is_left`. |
| D5 | Glass HUD over WiFi not wired end-to-end | Medium | `UnifiedConnectionManager` relays `displayPrefs` from both transports, but `glass-hud/MainActivity.kt` still uses `BleClient` directly, so the WiFi path never reaches the HUD. |
| D6 | `ninebot-ffi` has no tests | Medium | Zero test files. The crypto is covered only indirectly via `ninebot-ble`'s tests. |
| D7 | ~~Stale claim in `README.md`~~ | **Fixed** | `README.md` still described the incorrect three-way offset disagreement. Rewritten to state the corrected finding (parsers agree; one 4-byte question remains). `ModelSupportDocConsistencyTest` re-run and passing. |
| D8 | Translated READMEs are partially updated | Low | `doc/README_zh-TW.md` and `doc/README_zh-CN.md` had only the identity-rule section synchronised. They do not describe the new UI, protocol families, or support matrix. I deliberately did not machine-translate technical prose. |
| D9 | `ninebot-ffi/src/mi_crypto.rs` is a re-export shim | Info | Intentional (de-duplication). `ninebot-ble` is now the only implementation. |
| D10 | ~~Version catalogue check~~ | **Resolved** | There is **no** `gradle/libs.versions.toml`. All pins are inline in `build.gradle.kts` (`com.android.application` version `9.0.0`) and `gradle/wrapper/gradle-wrapper.properties` (`gradle-9.6.1-bin.zip`). Nothing hidden. |

### 3.3 Things I could not verify at all

- Whether the workflow actually runs green on GitHub's runner. All the
  environment facts check out (§4.2) and the commands were reproduced locally,
  but nothing has been pushed.
- Whether `secrets.SONAR_TOKEN` exists in the repository (referenced once).
- Whether SonarCloud's *automatic analysis* is what actually runs, and how many
  new-code findings it will report.
- Anything about runtime behaviour on a device.

---

## 4. What must happen before pushing to GitHub

### 4.1 CI — the contradiction, and its fix (DONE)

`ci.yml` used to be self-contradictory:

- it passed `-PskipRustBuild` to Gradle, and
- it then required `app/src/main/jniLibs/*/libninebot_ffi.so` to exist, failing the
  job if the glob returned nothing.

Those cannot both hold, and since `app/src/main/jniLibs/` is now gitignored (the
`.so` files are generated, not committed), a fresh checkout has none. The job
could only fail.

**Fixed by making CI build Rust**, which is the only consistent choice: the app
calls `System.loadLibrary("ninebot_ffi")`, so an APK without the library throws
`UnsatisfiedLinkError` and no handshake, login or telemetry decryption works. The
"skip the native build" option was never viable — it only appeared to work
because the binaries used to be committed.

`ci.yml` now mirrors `release.yml`: `rustup` + four Android targets, a cargo
cache keyed on **both** crates (ninebot-ffi depends on ninebot-ble by path, so
hashing only the ffi crate would restore a stale `target/`), `cargo install
cargo-ndk --locked`, and `ANDROID_NDK_HOME` exported from the runner image.

`./gradlew test` now pulls the whole chain via `preBuild` →
`verifyRustJniLibs` → `copyRustJniLibs` → `buildRustJni`, and
`verifyRustJniLibs` fails the build if the `.so` is missing or does not match a
content hash of the Rust sources. That is what makes "edited Rust but did not
rebuild" impossible — in CI as well as locally.

Verified locally with CI's exact commands:

```
./gradlew --no-daemon test
  buildRustJni UP-TO-DATE / copyRustJniLibs / verifyRustJniLibs
  verifyRustJniLibs: libninebot_ffi.so present and current for all 4 ABIs
  BUILD SUCCESSFUL
./gradlew --no-daemon :app:assembleDebug :glass-hud:assembleDebug
  BUILD SUCCESSFUL
```

**Not verified**: that GitHub's runner behaves identically. That requires an
actual push.

### 4.2 CI — environment facts (researched, no longer assumptions)

An earlier draft of this document listed the JDK version as an open risk. It is
not. Researched against the official release notes and compatibility matrices:

| Question | Answer | Source |
| --- | --- | --- |
| AGP 9.0.0 minimum JDK | **17** (and 17 is also its default) | [AGP 9.0 release notes](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes) |
| Does AGP 9 require JDK 21? | **No.** JDK 17 is sufficient; there is no "requires Java 21" error for AGP 9.0.0 | [Java versions in Android builds](https://developer.android.com/build/jdks) |
| AGP 9.0.0 minimum Gradle | **9.1.0** — 9.6.1 is above it and inside the tested range | [AGP 9.0 release notes](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes), [Gradle 9.6.1 compatibility](https://docs.gradle.org/9.6.1/userguide/compatibility.html) |
| Gradle 9.6.1 minimum JDK | **17** (supported range 17–26) | [Gradle 9.6.1 compatibility](https://docs.gradle.org/9.6.1/userguide/compatibility.html) |
| AGP 9.0 SDK Build Tools | **36.0.0** (minimum and default) | AGP 9.0 release notes |
| AGP 9.0 maximum API level | 36.1 — so `compileSdk = 36` is fine | AGP 9.0 release notes |
| `ubuntu-latest` | Ubuntu 24.04 x64, with JDK 8/11/**17 (default)**/21/25 preinstalled | [runner-images](https://github.com/actions/runner-images/blob/main/README.md) |
| Android SDK on the runner | `platforms;android-36` and `build-tools;36.0.0` **already installed**, licences accepted | [Ubuntu 24.04 image readme](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md) |

Consequences:

- The existing `java-version: '17'` is correct and needs no change. JDK 21 (what
  the local build used) would also work; 17 matches AGP's documented default.
- No `sdkmanager` step is needed — the required SDK components ship with the
  image.
- The `local.properties` step writing `sdk.dir=$ANDROID_SDK_ROOT` is fine;
  `ANDROID_SDK_ROOT` is set on the runner.

**Still unverified:** the Compose compiler plugin (2.2.10) is reported to match
the Kotlin Gradle Plugin that AGP 9.0 pulls in, so no version-skew failure is
expected — but that was not confirmed against an authoritative source and has not
been run on CI.

### 4.3 SonarQube

Current configuration:

```properties
sonar.sources=app/src/main/java,glass-hud/src/main/java
sonar.tests=app/src/test/java
sonar.exclusions=**/build/**,**/dist/**,**/*.so,**/jniLibs/**,**/assets/**,**/*.lc
```

Notes and likely findings:

- The CI step has `continue-on-error: true`, so **Sonar cannot currently fail the
  build**. SonarCloud "automatic analysis" may be doing the real work. Establish
  which is authoritative before interpreting any result.
- **`ninebot-ble` is not in `sonar.sources`.** ~1,500 lines of new Rust are
  analysed by nothing. Add it, or state explicitly that Rust is out of scope.
- `sonar.tests` covers only `app/src/test/java`. That is correct today
  (`glass-hud` has no tests; the Rust tests are integration tests).
- **Duplication is the most likely new-code finding.** Several new test files
  repeat literal blocks, and `HudDisplayScreen.kt` has twelve near-identical
  `FieldSwitch(...)` calls. If the quality gate enforces duplication on new code,
  expect it to fail; a shared helper or a data-driven list fixes it.
- **Cognitive complexity** is the next likely finding. `ScooterRepository.kt` is
  ~1,500 lines and grew this session; `ScanScreen.kt` is ~900.
- **Hardcoded strings** in Compose: several new `Text(...)` calls use literals
  (diagnostics labels, `"—"`, emoji icon arguments). Some are intentional
  (emoji, glyphs), some should move to `strings.xml`.
- **Unused code**: `M365Components.kt` still exports helpers that the new screens
  no longer use; `M365SpeedDisplay` in particular. Sonar flags unused private
  members, not unused public API, so check manually.

### 4.4 Git hygiene

- 41 modified + 20 untracked files, **no commits**. Decide the commit structure
  before pushing; a single 60-file commit will be hard to review and will make
  Sonar's new-code window enormous.
- The four `.so` deletions are staged (`D ` in `git status`). Confirm they land
  in the same commit as the `.gitignore` change or the branch will build nowhere.
- `.gitignore` now ignores `/app/src/main/jniLibs/`. Confirm no other module
  needs a native library that this would hide.
- `local.properties` is present locally and must **not** be committed.

---

## 5. Precise open questions

### 5.1 The M365 4-byte question (needs one capture)

Both shipped parsers reach battery 8 bytes past the 3-byte header; the documented
map says offset 4. **Five values from one real `0xB0` response**, plus the battery
percentage the scooter itself displayed, decides it. That single capture also
identifies which byte is speed, odometer and temperature, because they are
adjacent and fixed-width.

Do **not** "fix" the offsets without it. `model::M365` records the documented
layout, the Android behaviour is deliberately untouched, and tests assert the
current disagreement so a silent change is caught.

### 5.2 JNI surface (needs a decision, not a capture)

To make the protocol work affect the app, `ninebot-ffi` needs new exports, e.g.:

```rust
Java_..._detectProtocol(...)        // -> ProtocolFamily
Java_..._legacyDecrypt(...)         // -> plaintext
Java_..._encryption2Handshake(...)  // -> session
```

**Recommendation: do not do this yet.** Wiring unverified cryptography into the
connection path risks the user's scooter, and the existing M365 path does not
need it. The correct order is capture → verify → wire.

### 5.3 What "done" would mean for the Unverified markers

`Confidence::VERIFIED` is claimed by nothing. Promoting a model requires traffic
from that model. `ModelSupportDocConsistencyTest` enforces that the documentation
never claims more than the registry.

---

## 6. How to verify the project yourself

```bash
# Toolchain lives in the workspace (home is read-only in this sandbox)
source /home/kali/ScooterHacking/env.sh

cd /home/kali/ScooterHacking/repo

# Full clean build + all Kotlin tests
./gradlew --no-daemon clean :app:assembleDebug :glass-hud:assembleDebug \
          :app:testDebugUnitTest

# Rust protocol core (runs on a host: no Bluetooth stack needed)
cd ninebot-ble && cargo test --no-default-features

# Rust BLE layer and every feature combination, for Android
for c in "--no-default-features" "--no-default-features --features ble" \
         "--no-default-features --features write-ops" "--all-features"; do
  cargo check --target aarch64-linux-android $c
done

# Prove the native library matches the Rust sources
cd .. && ./gradlew --no-daemon :app:verifyRustJniLibs

# Prove a Rust edit is detected
echo "// probe" >> ninebot-ble/src/mtu.rs
./gradlew --no-daemon :app:assembleDebug    # rebuilds; stamp changes
sed -i '/\/\/ probe/d' ninebot-ble/src/mtu.rs
```

Expected: build succeeds; 107 Kotlin tests and 146 Rust tests pass;
`verifyRustJniLibs` reports all four ABIs current.

**Note on the host Rust tests**: `cargo test` with the default features needs
`pkg-config` and `libdbus-1-dev`, which are **absent** in this sandbox. That is
why the `ble` feature exists and why the protocol core is feature-free. Do not
"fix" this by installing dbus; the split is deliberate and is what makes the
protocol testable at all.

---

## 7. Suggested order of work for the next agent

1. ~~Fix `ci.yml`~~ — **done** (§4.1). Rust is now built in CI.
2. ~~Confirm the JDK version~~ — **done** (§4.2). JDK 17 is correct.
3. **Decide `sonar.sources` for Rust** and pre-empt the duplication findings
   (§4.3).
4. **Commit in reviewable slices** (§4.4) — infrastructure, Rust protocol,
   Android UI, docs.
5. **Then** the substantive work, in this order:
   - D3: make `MotorInfoParserTest` test the real parser, or mark it clearly as a
     documentation fixture rather than a test.
   - D5: wire the glass WiFi path.
   - D6: add tests to `ninebot-ffi`.
   - D2 / §5.2: only after captures arrive.
6. **Ask the user for captures** (§3.1). Most of the protocol tree stays
   `Unverified` until then, and that is the honest state, not a defect to paper
   over.

---

## 8. House style this codebase now follows

Worth preserving, because the value is in the reasoning being recoverable:

- **Comments explain why, not what.** Several non-obvious decisions (MTU rule,
  tree-shaking, the offset base-pointer trap, why nothing is `VERIFIED`) are
  documented at the point of confusion.
- **Claims are marked.** `✅ Verified` / `📗 Documented` / `⚠️ Unverified` /
  `⛔ Unknown` appear in the docs and map onto `Confidence` in code.
- **Nothing guesses a register address.** Where a layout is unknown the code
  returns "unsupported" and the UI says so.
- **Tests that cannot fail are worse than no tests.** D3 is the counter-example
  still in the tree; do not add more.
- **A silent failure is the enemy.** The MTU bug, the discarded-write boolean,
  and the stale `.so` were all cases where the code reported success while doing
  nothing. Each fix made the failure loud.
