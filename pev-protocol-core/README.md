# pev-protocol-core

Independent, MIT-licensed, pure Kotlin/JVM protocol core for personal electric vehicles
(scooters and EUCs). The M365-Rokid-HUD app consumes it. RideFlux composite-build integration
is planned and has not been implemented; no second codec copy is authorized.

Hard rules: no Android / Compose / JNI / GPL dependency; no code copied from GPL projects;
see `SOURCE_PROVENANCE.md` before adding any source, fixture or spec text.

See `ARCHITECTURE.md` for the design and `doc/MULTIVEHICLE_HANDOFF.md` (repo root `doc/`) for
status and the ordered work queue.

The source license is in `LICENSE`. Kotlin/JUnit/Kover retain their own licenses; see
`SOURCE_PROVENANCE.md`. Core API is an unreleased 0.1.0 snapshot, with no published artifact.
`scripts/app-and-core-test-wsl.sh` runs tests and enforces at least 90% core line/branch
coverage. Passing this local gate does not claim that external Sonar analysis passed.
