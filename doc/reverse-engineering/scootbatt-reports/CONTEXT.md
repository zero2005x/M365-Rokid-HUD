# Scootbatt RE — shared context for all agents

## Goal
Reverse engineer `com.basse.scootbatt` (Scootbatt 1.9.2, versionCode 136) to document its
scooter BLE protocol so it can be reimplemented reliably in another app
(M365-Rokid-HUD). Priorities: (1) reliable telemetry read-out, (2) inventory of write
commands (unlock / speed limit / settings) with risk notes.

## Hard constraint
**Static analysis only.** There is NO scooter, NO phone attached, and NO network access
to the vendor. Everything must come from the decompiled code. Anything you infer rather
than read must be labelled as inferred, with the evidence you based it on. Never present
a guess as a verified fact.

## Materials (all paths absolute, read-only for you)

| What | Path |
|---|---|
| Decompiled Java (jadx, 11357 files) | `/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/` |
| Decompiled resources | `/home/kali/ScooterHacking/re/scootbatt/jadx-out/resources/` |
| Base APK | `/home/kali/ScooterHacking/re/scootbatt/xapk/com.basse.scootbatt.apk` |
| armeabi-v7a split | `/home/kali/ScooterHacking/re/scootbatt/xapk/config.armeabi_v7a.apk` |
| Raw dex (for `strings`/grep) | unzip `classes*.dex` from the base APK |
| jadx log (62 decompile errors) | `/home/kali/ScooterHacking/re/scootbatt/jadx.log` |

The app is **R8-obfuscated**: app classes are `p000.C0123ab` style, Kotlin classes keep
their package (`com.basse.scootbatt.*`). Enum/constant *values* and string literals
survive obfuscation — those are what you are hunting. Decompiled code is often ugly
(`f19694k`, `mo3234e`); read it carefully rather than trusting names.

Useful tools: `grep`, `strings`, `javap` (JDK 25 is installed), `python3`,
and `/home/kali/ScooterHacking/toolchain/jadx/bin/jadx` if you need to re-decompile a
single class with different options. `radare2` is installed for native code.

## Already-established facts (do not re-derive, build on these)

**BLE profile** — `/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/p000/C0993mx.java`
is the main `BluetoothGattCallback`:

```java
f19677s = 00002902-0000-1000-8000-00805f9b34fb   // CCCD (subscribe)
f19678t = 6e400001-b5a3-f393-e0a9-e50e24dcca9e   // Nordic UART Service (NUS) — used for scanning/filtering
f19679u = 6e400002-b5a3-f393-e0a9-e50e24dcca9e   // NUS RX — app WRITES commands here
f19680v = 6e400003-b5a3-f393-e0a9-e50e24dcca9e   // NUS TX — notifications (telemetry) arrive here
f19681w = 0000fe95-0000-1000-8000-00805f9b34fb   // Xiaomi service
f19682x = 00000010-0000-1000-8000-00805f9b34fb   // Battery Level
f19683y = 00000019-0000-1000-8000-00805f9b34fb   // Firmware Revision String
```

**Command envelope** —
`/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/com/basse/scootbatt/models/scooter/helpers/ScooterRequest.java`
`construct()` builds: `[0x3E, direction, action, position, payload...]` where
`0x3E` is `'>'`, `direction`/`action` come from two enums (`p000.xp0`), and `position`
is a sequence/offset byte. Length is `payload.length + 4`. Check whether a checksum or
CRC is appended by the caller.

**Write path with MTU chunking** —
`/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/com/basse/scootbatt/services/BleForegroundService.java`
`m3244o(byte[])` splits a frame into `f19701r`-sized chunks, queues them in
`C0993mx.f19684a`, and writes to `f19694k` via `writeCharacteristic`. `m3245p(byte[], UUID)`
is the notification entry point.

**Brands/models present**: Ninebot (ESx, E, E2, F, F2, F65, D18, D28, D38, G2, G30, G65,
GT1, GT2, P65, P100, X160, Air T15), Xiaomi (M365, M365 Pro, Mi Pro, Mi Pro 2 — CN/EU/US
variants). Strings `NinebotCrypto`, `XiaomiCrypto`, `ninebotCrypto`,
`isNewNinebotGeneration` exist in the dex.

**Xiaomi cloud/elliptic path**: `com/basse/scootbatt/crypto/elliptic/ConfigurationElliptic.java`
(beaconKey / deviceToken / deviceInfo / ssid), used by `services/MajsiHomeReceiver.java`
and `services/RequestEllipticKeysReceiver.java`.

## Deliverable format
A single markdown report. Use tables for any byte-offset / constant mapping. For every
claim give `file:line` evidence. End with an explicit **"Unverified / needs hardware"**
section listing what could not be established statically.
