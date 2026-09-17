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

## `scooterhacking-wiki/`
Raw ScooterHacking wiki pages (error codes, BMS guides, model notes) used as
source citations for the reports and for `ScooterErrorCodes.kt`.

## Related in-repo docs
- `doc/PROTOCOL_FAMILIES.md`, `doc/MODEL_SUPPORT.md`, `doc/NINEBOT_LEGACY_PROTOCOL.md`
- `doc/IMPROVEMENT_PLAN.md` — the plan these parsers were built against
- `research/references/` — Ninebot docs wiki + NinebotCrypto reference (nbcrypt.c)
