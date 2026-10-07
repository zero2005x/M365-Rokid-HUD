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
| Integrated core tests | 142 tests, zero failures/errors/skips |
| Integrated App tests | 367 tests, zero failures/errors/skips |
| Core Kover | LINE 569/569 (100%); BRANCH 522/549 (95.08%); methods 188/188 |
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
