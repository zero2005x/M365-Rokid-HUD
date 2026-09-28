# Reverse-engineering reference

Imported from the WSL2 Kali workspace `/home/kali/ScooterHacking` on 2026-09-17.
This is **reference material**, not part of the app build.

## `scootbatt-reports/`
Static-analysis reports on `com.basse.scootbatt` 1.9.2 (jadx 1.5.1 + apktool
smali cross-check). Documents the Ninebot/M365 ESC BLE protocol that the
`app/.../protocol/` parsers implement. Start at `README.md`, then
`00-protocol-core.md`.

> **All conclusions are static analysis — none verified against a real
> scooter.** See each report's "unverified" section and `doc/IMPROVEMENT_PLAN.md`.

## `m365tools-reports/`
Static-analysis reports on `app.peretti.m365tools` **m365 Tools 1.8.0**, a second
reference implementation covering 29 scooter models. Its string encryption was
broken first, then the connection logic: pre-connect identification from
advertisement data, service-agnostic characteristic lookup, per-model board
addressing, four protocol families, the frame envelope and its checksum, the
write inventory, and the connection lifecycle. Start at `README.md`.

This is the source of the per-model addressing in `ninebot-ble/src/model/mod.rs`
(`BoardAddress`, `XIAOMI_BOARDS`) and of the open `0x7D` byte-order question
recorded in `ScooterSettingsWriter`.

> **Static analysis only.** No scooter and no phone were attached, and only a
> Xiaomi M365 is available to this project — so every Ninebot row is
> documentation-grade.

## `scooterhacking-wiki/`
Raw ScooterHacking wiki pages (error codes, BMS guides, model notes) used as
source citations for the reports and for `ScooterErrorCodes.kt`.

## Related in-repo docs
- `doc/PROTOCOL_FAMILIES.md`, `doc/MODEL_SUPPORT.md`, `doc/NINEBOT_LEGACY_PROTOCOL.md`
- `doc/IMPROVEMENT_PLAN.md` — the plan these parsers were built against
- `research/references/` — Ninebot docs wiki + NinebotCrypto reference (nbcrypt.c)
