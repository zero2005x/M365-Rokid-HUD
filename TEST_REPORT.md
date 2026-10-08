# Multi-vehicle continuation verification — 2026-10-07

Scope: M365 remaining register decoding/App delegation, historical replay, raw-only A2 codec,
and command authorization/confirmation hardening. This is not completion of the whole product.

## Baselines and protected work
- HUD branch `feat/pev-protocol-core`, starting HEAD `0d614823db9479078dd050b11a5a69c7fbe72149`.
- HUD GitHub main checked with `git ls-remote` before work and again before delivery:
  `3d01e6f492141fd2c29aced4b19f98d51cb8364c`; no newer remote baseline.
- Initial HUD status contained only owner `glasses-screen.png` and `phone-screen.png`; both preserved.
- No applicable AGENTS.md found in HUD or checked parent directories. RESEARCH.md read from
  `/home/kali/PEVAppRE/handoffs/20261007-m365-rokid-multivehicle/`, SHA256
  `c375afca1d85894670619b502f746e5ddc8a513c4732d23f3ec2ed39687e2526`.
- RideFlux changed concurrently: observed HEAD `4714740` at start and
  `9bd9eaa82e55efaa936afa5d332e7ef2c752b7b8` later, matching then-current GitHub main.
  Owner `GEMINI_NOSFET_F18_V11_REMEDIATION_PROMPT.md` stayed untracked; a concurrent modification
  of `ApprovedGlassesStoreTest.kt` appeared. No RideFlux file/reset/stash/checkout was performed.
  Re-query before its future isolated integration; the pasted historical baseline is obsolete.
- Work reused the existing dedicated unpushed feature branch; no push/merge/release/vehicle write.

## Executed tests
`wsl -d kali-linux -- bash /mnt/c/Users/liangtinglin/Documents/codebase/Android/M365-Rokid-HUD/scripts/app-and-core-test-wsl.sh`
stages into `/home/kali/build/pev-core-stage` and uses JDK21 + existing Android SDK, offline Gradle,
`:pev-protocol-core:test :pev-protocol-core:koverXmlReport :app:testDebugUnitTest :app:koverXmlReportDebug -PskipRustBuild`.

| Check | Actual result |
|---|---|
| Pre-change core/App tests | BUILD SUCCESSFUL; core 66, App 361, zero failures (the previous handoff's App 309 count was incorrect) |
| Integrated core tests | 155 tests, zero failures/errors/skips |
| Integrated App tests | 367 tests, zero failures/errors/skips |
| Core Kover | LINE 819/822 (99.64%); BRANCH 621/651 (95.39%); methods 207/207 |
| Explicit local/CI coverage floor | `scripts/check-core-coverage.py` enforces LINE and BRANCH >=90%; actual report passed |
| External Sonar | NOT RUN: local scanner and SONAR_TOKEN unavailable; CI scan remains configured. No Sonar quality-gate pass claimed |
| Rust/JNI checks | NOT RUN; Rust source untouched, Kotlin validation uses `-PskipRustBuild` and existing native artifacts |

App Kover XML includes runtime Android/UI code and is not an overall >=90% report. Existing Sonar
coverage exclusions still define its pure-logic scope. No new exclusions were added to hide code.
Baseline deprecation warnings in Android crypto/SDK APIs and Gradle remain.

## M365 historical replay
- 152 exact B0 payloads plus 3 RX3A and 1 RX25, sanitized fixture hashes checked by JVM test.
- Captured numeric word diagnostics and historical Parsed values are retained as regression
  oracles; 92 unique timestamp matches to separately recorded phone CSV, deltas <=9ms.
- The other 60 payloads have no corresponding phone row. Those CSVs/diagnostics come from the
  same historical App, not independent vendor/physical measurements.
- Actual corpus: SOC {43,44,51}, total 400107–400871m, frame temperature 31–45C,
  signed speed -5.514–0.150km/h. It does not reproduce the old claimed moving-window scale oracle.
- Tool can reproduce byte-identical CSVs/manifests under Windows and WSL. Source and transform
  SHA256 are in `pev-protocol-core/SOURCE_PROVENANCE.md` and the committed fixture manifest.
- New BMS/ESC synthetic vectors verify bounds, endian/signedness, unknown/invalid handling,
  cell-index preservation and freshness; none establishes BMS hardware validity.

## A2 corrected CAP-A compiled-core replay
Read original research tools with `python3 -B`; generate only new ignored implementation build
outputs. No old capture/corpus/tool/research output was edited and no captured A2 fixture committed.

- Source `euc-programme/work/captures/BT_HCI_2026_0929_161755.cfa` SHA256
  `8ad60dc2bc1e9c7bd05b772e6e7ed792f5c0b7d9ecfad9de32691f96abb7f280`.
- Corrected `btsnoop_extract.py` SHA256 `b8b52f4090ea254a39fa8ab2f4ffb597f68da3a88e3a355379698acb289e57c9`;
  run with selected peer, `--whole-file --handle 0x25 --max-frame-lines 0`, fresh ignored output directory.
  Exact local command/peer is retained in ignored report; vendor tool code not copied into module.
- RX-only 274185 bytes SHA256 `5be6db36b6057d391faf5f5128c0d50d1fd586254c02857867f2b8bae33596c1`.
- Compiled codec replay, four runs: A2 original notification boundaries with budgets256/24,
  UNKNOWN original boundaries256, A2 fully coalesced stream256. All produced 11424 exact frames,
  9 malformed/resync bytes, zero overflows, zero physical telemetry fields.
- Type/byte19 pairs (00,18)/(01,00)/(04,18)/(07,18) each 2856. No model/firmware inferred from
  `GW1511014`; mode@14 is not PWM; @6/@8 distance channels stay separate.
- Ignored `build/pev-begode-replay/compiled-core-replay.json` SHA256
  `fdddb1021604e5da7ee6a094134e301fbd9940b672a4cb743638e7fe27ccf83d`
  records capture/tool/lib/time-helper/runner/core-jar/codec hashes. The Java runner beside it is
  generated independently. Keep these local artifacts for audit; packaging captures remains blocked
  until concrete redistribution rights are settled.
- CAP-B remains NOT CAPTURED. Physical scaling/SOC/command applicability and new vehicle acceptance
  are unexecuted. Raw-only framing is not full A2 product support.

## Independent review and remaining integration risks
Separate agent reviewed command safety and Begode layouts; concrete issues were fixed with tests:
ungated execute, uncorrelated ACK, cancellation after final delay, reconnect write race, profile
return restoring consent, omitted pack/source identity, mutable bytes/scope/notification data,
RMW cross-session reuse/expiry/future evidence, late confirmation, wrong-source/type/body readback.

Production integration remains pending: Rust/GATT adapter must atomically bind device/epoch,
isolate polling/reply ownership and use the one coordinator. Generic plans trust audited builders;
the phone must not expose raw hex or arbitrary specs. Existing legacy light/lock methods are not
yet routed through the new gate. Versioned gateway unknown/freshness handling, RideFlux integration,
other family implementations and actual M365/A2 acceptance remain in the handoff queue.

## Continuation — 2026-10-08
Started from HUD `51536b5`, clean except the two owner screenshots. GitHub main still
`3d01e6f492141fd2c29aced4b19f98d51cb8364c`. The prior gateway draft's green tests did not prove
its contract: independent review found short-frame crashes/CRC overlap, reversed magic,
changed V1 quantization/wrap, invented zeros and conflated sensor meanings. Those were repaired
in local core commit `8d6587c`; `pev-protocol-core/GATEWAY_SCHEMA.md` describes the unreleased V2.

Actual BLE/Wi-Fi V1 producers and glasses V1 parser now consume the same module. Wi-Fi ingress
and BLE battery feedback apply the command guard. Invalid CRC no longer refreshes previous BLE
telemetry; reconnect starts unknown/stale until a valid current-session packet. Late old-GATT
callbacks are ignored. Full atomic callback/session synchronization remains future hardening.

The final semantic run used `scripts/verify-hud-wsl.sh`: JDK21, Gradle offline, core/App/glasses
Kover, both `lintDebug`, both `assembleDebug`, explicit `-PskipRustBuild`.

| Actual check | Result |
|---|---|
| HUD core / phone / glasses JVM tests | 166 / 367 / 12, zero failures/errors/skips |
| Core Kover and >=90% line/branch floor | LINE 836/838 (99.76%), BRANCH 885/937 (94.45%), methods 237/238; passed |
| Phone / glasses lintDebug | Passed, zero errors; 98 warnings + 2 hints / 19 warnings remain |
| Phone / glasses debug APK build | Both assembled successfully |
| External Sonar | NOT RUN; no scanner/token. Local coverage/lint are not an external Sonar gate pass |
| Rust/native hardware | NOT rerun; existing Windows artifacts used; no vehicle writes/acceptance |

The first full offline run lacked declared Android test dependencies (`androidx.test.ext:junit`
1.3.0 and Espresso 3.7.0). One online resolution run populated them. Lint then identified the
existing glasses `repeatOnLifecycle` registration via `onStart`, and three phone permission
contracts. Fixed these without suppressing/excluding checks: register one lifecycle observer from
`onCreate`, switch bound clients with `collectLatest`, catch permission loss when notifying display
preferences, and permit local/native disconnect cleanup after revocation. The final offline run
was BUILD SUCCESSFUL. Final phone packaging after whitespace-only cleanup also passed:
`wsl -d kali-linux --cd /home/kali/build/pev-core-stage -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash gradlew --no-daemon --offline -PskipRustBuild :app:assembleDebug`.

Local artifacts and copied reports are under ignored `build/hud-verification/`:
phone APK SHA256 `7d6a481cb4b64eacc7ea5510a1d400b0bdb90eb463495e541472acfbbc0439ac`,
glasses APK SHA256 `79de006ba5a74625b8baf676da41e02b6f70c20fdbb1d2ee3dee9a182d1d361e`.
These are local debug artifacts, with the native limitations below; no install or publication occurred.

Native freshness warning is retained: Windows stamp `e80791fd...` versus Linux `396ba136...` due
host separators in the fingerprint. Rust sources remain unchanged. The prior agent recorded
Windows Rust/JNI counts 5/148/56 in the handoff; this continuation did not independently witness
or rerun them. No new native freshness/runtime acceptance is inferred from Kotlin APK assembly.

Independent read-only review checked V1 producers/reader, V2 bounds/CRC/UTF-8/state/time/source,
command refusal, lifecycle behavior and permission cleanup. Lone-surrogate provenance encoding
was fixed. Parsed V1 DTOs preserve Float display behavior and are not lossless forwarding objects.
V2 negotiation, clock alignment, packet delivery/fragmentation, upgrade UI and semantic alert/display
wiring remain pending; existing primitive V1 APIs are M365-only and cannot represent provenance.

### RideFlux isolated consumer
Local commit `eb941870c583bed86072f304cd5e0e2cbddffc1a`, branch `codex/pev-core-consumer`, worktree
`C:/Users/liangtinglin/Documents/codebase/Android/RideFlux-pev-core-consumer`, based explicitly on
`9bd9eaa82e55efaa936afa5d332e7ef2c752b7b8`. The owner checkout advanced concurrently to `ee23b875`
(0.1.11/versionCode12); remote main advanced from `b7a51ad` to `83ef5a8835345d624bbaec3ad899c89b81b084fc` at final recheck. No owner change/reset/stash was performed.

Thin Gradle included build references the authoritative external module source/build script and
redirects outputs into the consumer. It avoids importing HUD Android/Rust builds. M365 read PDUs,
SOC, unsigned odometer and frame temperature actually delegate to core. ES2 stays independent;
legacy signed-magnitude speed and B9 trip policies remain documented compatibility gaps.

Strict offline validation: domain123 + protocol395 + core166 =684 tests, zero failures/errors/skips;
JVM17 composite substitution verified. M365Codec JaCoCo 24/24 lines, 58/62 branches. Whole protocol
coverage includes existing unrelated codecs (3122/3789 lines, 1754/2713 branches), not a new overall
>=90% gate pass. All 16 added public dependency hashes matched fresh primary Maven Central bytes;
previous trusted hashes retained. GPL consumer code/tests were never copied into MIT HUD.

Exact commands, source/artifact hashes and counts are committed in that worktree's
`docs/PEV_CORE_CONSUMER.md`, `docs/pev-core-validation.json`, and
`docs/pev-core-artifact-verification.json`. Separate strict offline Android tasks
`:app:lintDebug :hud-app:lintDebug :app:assembleDebug :hud-app:assembleDebug` were BUILD SUCCESSFUL
(314 tasks, 6m46s). Phone/HUD lint: zero errors, 37/13 warnings. APK SHA256:
phone `562b7b4d1b32167cbaf71b87d180e52b5e46636b33762ff0a75e3f8e68277517`,
HUD `1c146642561e300e849226a56c59a8c977930e64cea784b448b0c0006b43f0d3`.
Both DEX files contain the shared decoder without the coverage-agent namespace. Existing native
SDK alignment/stripping warnings and D8 Play Services warning remain; no hardware or remote CI
acceptance is claimed. Documentation follow-up commit `c984f52fb7415e8d376b3a4ba45b89dbd86358e3`
records `docs/pev-core-android-validation.json` and `docs/pev-core-license-audit.json`.
All nine new dependency coordinates declare Apache-2.0; the two agent JARs contain 64 shaded ASM
classes each with separate BSD-3-Clause attribution. Instrumentation is build/test-only.
The selected baseline predates newer privacy/token-log fixes and owner version bump. Integrate
with those commits before release; do not replace the live owner checkout with this older base.


### BLE session continuation (`04e141c`)
The full `scripts/verify-hud-wsl.sh` run passed 166 core / 367 phone / 16 glasses tests (549 total),
both lintDebug tasks and APK builds. Final scan callback/subscription improvements were then copied
into the stage and checked with JDK21/offline `:glass-hud:koverXmlReportDebug :glass-hud:lintDebug
:glass-hud:assembleDebug`: BUILD SUCCESSFUL in 2m 07s, 56 tasks. Glasses lint remains19 warnings/0errors.
Four new synthetic tests exercise reference identity, disconnect revisions, a concurrent admitted
callback vs reconnect, and lock release after failure. `BleSessionOwner` Kover LINE 12/12,
BRANCH 2/2, METHOD 8/8. No exclusion/suppression was added; the existing deprecated notification
API annotation moved with that API to the extracted helper. Source CRLF retained.

GATT callback admission now covers all state mutation; connect, teardown, old watchdog/battery/RSSI,
delayed discovery/subscriptions and reconnect use captured ownership. Each scan attempt has its own
callback object. Manual disconnect or disabling auto-reconnect invalidates delayed work. Freshness
uses elapsedRealtime. Null connect/subscription submission failure is reported, revoked Bluetooth
permission is caught, unknown BatteryManager values are not sent as 255%, and detached GATT handles
close before scope cancellation. Short submission calls run in the serialized section, with no
coroutine wait under its monitor. Android Binder timing and real reconnection remain NOT EXECUTED.

Final BLE APK before subsequent I1 work SHA256
`9bded798f802dd1bc9b3a364e672142ce090e8cdbd1e01b050c792b1294c24a2`.
RideFlux remote diff b7a51ad..83ef5a8 only changes application version lines (two files), while
the live owner checkout has advanced to 83ef5a8 and has new diagnostic logging edits/untracked files.
Those files were read for status only and preserved. Isolated consumer remains c984f52 and clean.


### Final I1/BLE and shared consumer validation (2026-10-08)

Code revisions: BLE session fencing `04e141c`; independent MIT I1 receive-only envelope `bd3913d`.
Earlier 545/549/684-test results and artifact hashes above belong to their recorded snapshots.

`wsl -d kali-linux -- bash /mnt/c/Users/liangtinglin/Documents/codebase/Android/M365-Rokid-HUD/scripts/verify-hud-wsl.sh`
completed BUILD SUCCESSFUL in 9m 36s (120 tasks: 35 executed / 85 up-to-date), JDK21/offline,
explicit no-native-rebuild policy. Core 182 + phone 367 + glasses 16 = **565 tests** with zero
failures/errors/skips. Core Kover LINE 930/932 (99.79%), BRANCH 959/1013 (94.67%), METHOD 266/267;
>=90% line and branch floor passed. New I1 namespace: LINE 94/94, BRANCH 74/76, METHOD 29/29.
Both lintDebug tasks passed with zero errors: phone 98 warnings + 2 hints; glasses 19 warnings.
Both debug APKs assembled. Final SHA256:
phone `2f5c483347bcf475d66cc2ba72e059eb808d6dee120df03ee2caffe03e0bec73`,
glasses `947cd5db0ab5b3f4d4d351df78f78bd5431fa3deb5e8ef4a2d0740b385bd7e04`.
Actual copied reports/APKs and summary are in ignored `build/hud-verification/`.

The initial I1 test run failed because a manually frozen synthetic golden packet had a mistyped
checksum `57`; independent byte addition gives `49`. The fixture was corrected and the strict
decoder validator retained. All final 16 I1 tests passed, including every split, coalescing,
reserved-byte/check escaping, raw-check compatibility, unsupported length, bounded extension,
reset/noise/corruption, immutable diagnostics and shared-magic I2 rejection. Synthetic assertions
alone do not confer reference or physical verification.

The final compiled MIT JAR was independently replayed using the committed tools:

```sh
wsl -d kali-linux -- bash /mnt/c/Users/liangtinglin/Documents/codebase/Android/M365-Rokid-HUD/scripts/replay-inmotion-i1-wsl.sh /home/kali/PEVAppRE/euc-programme/upstream/Wheellog.Android/app/src/test/resources
```

Read-only external historical traces yielded V5F 146 / V8S 315 / alerts 40 = **501 frames**.
Historical CSV segments, one-byte input and full coalescing produced identical frames/hashes:
zero overflows, eight escaped CHECKs total, zero physical fields. V8S retained 44 diagnostic bytes
(two unsupported length codes plus 42 bad-preamble bytes); none were treated as telemetry.
CSV segmentation is not certified original BLE notification boundaries. Source CSVs associated
with WheelLog GPL were not redistributed or relabeled MIT; only independently authored synthetic
tests/tools/spec were committed. Exact source/transform/tool/decoded-stream hashes are in
SOURCE_PROVENANCE.md and ignored `build/pev-inmotion-i1-replay/replay.json` (report SHA256
`7fede9168857f0d014326b6fe891a67e01a585e44f9b632ed8cf78b61501b76c`). No model, physical
units/scales, authentication, alert semantics or command support is inferred from these envelopes.

The isolated RideFlux consumer then refreshed only its external MIT core snapshot to `bd3913d`
and passed strict offline domain/protocol/core/Kover/JaCoCo + both lint/APK tasks in 4m 27s,
326 tasks. **123 + 395 + 182 =700 tests**, no failures/errors/skips; identical core line/branch
counters and M365Codec 24/24 lines / 58/62 branches. Phone/HUD lint zero errors, 37/13 warnings.
Both APKs define the shared Xiaomi and I1 classes without coverage-agent class definitions:
phone SHA256 `eb51b2505bda5300f191bd2850bed49c618ca678bc7989f8018a70d2015c131f`,
HUD `351e3bed6b28cfc6b111ca562cd93974de74f7fa4bfa37bcb2b7362c5c56d6a7`.
Exact latest evidence is `RideFlux-pev-core-consumer/docs/pev-core-latest-validation.json`.

External Sonar scanner/token, physical BLE timing/reconnect and M365/A2 owner acceptance remain
NOT RUN. No new independent agent review of the I1/BLE changes was completed: the available team
ended after a usage-limit report, leaving root only. Earlier independent review is described above.
Native artifacts were reused; Rust/JNI/hardware were not newly certified. Production sole-writer,
per-session experimental UI and negotiated V2 delivery/display remain unfinished. All work is local;
no push, merge, release, original RideFlux checkout modification or automatic vehicle write.
