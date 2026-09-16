# 02 — Telemetry parsing: notification bytes → values

**Scope**: how `com.basse.scootbatt` 1.9.2 turns incoming BLE notification bytes into telemetry
values. Static analysis only (no scooter, no hardware).

**Citation legend** (paths abbreviated):

| Prefix | Absolute path |
|---|---|
| `jadx:` | `/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/` |
| `smali:` | `/home/kali/ScooterHacking/re/scootbatt/apktool-full/smali/` (apktool 2.11.1 disassembly of `xapk/com.basse.scootbatt.apk`, produced for this report) |
| `res:` | `/home/kali/ScooterHacking/re/scootbatt/jadx-out/resources/` |

Field-name convention: jadx renames obfuscated fields as `f<id><original>`, e.g.
`uo3.f30556p` is dex field **`p`** of class `uo3`. Both forms are given where it matters
(`smali:uo3.smali:127  .field public final p:Luo2;`). All parse offsets below are relative
to the **payload**, i.e. frame offset 7 (see §3).

**⚠ Correction to CONTEXT.md**: `BleForegroundService.m3245p(byte[], UUID)` is *not* the
notification entry point — it is a *write* helper that pushes bytes into a GATT
characteristic (`jadx:com/basse/scootbatt/services/BleForegroundService.java:567-633`,
log tag `"writeElliptic"`). The real inbound chain is in §1.

---

## 1. Notification entry chain

| # | Step | Evidence |
|---|---|---|
| 1 | `C0993mx.onCharacteristicChanged(gatt, characteristic)` — app's `BluetoothGattCallback` | `jadx:p000/C0993mx.java:264` |
| 2 | If **Xiaomi "elliptic" mode** (`f19697n == true`, chosen when service `0000fe95-…` + chars `00000010`/`00000019` exist) → `service.mo3234e(uuid, value)`; **else** if characteristic == `f19693j` (NUS TX `6e400003…`) → `service.mo3235f(value)` | `jadx:p000/C0993mx.java:272-283`, mode set at `:466-475`, service discovery at `:469-493` |
| 3 | `BleForegroundService.mo3235f` posts `RunnableC1454x9(2, this, bArr)` to the service handler | `jadx:com/basse/scootbatt/services/BleForegroundService.java:228-243` |
| 4 | That Runnable re-dispatches to the registered `InterfaceC1030nx` (`f3918l`) → **`ScooterFragment.mo3235f(byte[])`** (the only parser sink in the app) | `jadx:p000/RunnableC1454x9.java:121-137`; implementors: `smali:com/basse/scootbatt/ui/fragments/scooter/ScooterFragment.smali` (`# interfaces … Lnx;`), only `BleForegroundService` + `ScooterFragment` |
| 5 | **Reassembly**: `hl2.m6915a(notificationBytes)` returns a whole frame or `null` (buffering) | `jadx:p000/hl2.java:57-90` |
| 6 | **Protocol branch** (see §2) → `m3299r0(frame)` | `jadx:com/basse/scootbatt/p005ui/fragments/scooter/ScooterFragment.java:1088-1155` |
| 7 | `m3299r0` splits the frame, picks a parser object, calls `vp0.h(payloadLen, payload)` | `jadx:…/ScooterFragment.java:1579-1894` (dispatch), call sites `:1663`, `:1698`, `:1731`, `:10998`(smali) |
| 8 | Parser writes `MutableLiveData` fields of a view model (`uo3`/`rm3`/`tn3`/`aj3`) | §5 |
| 9 | X-ray/logging: every dispatch is logged as `"Received -> <hex> / <parser>"`, and `C1286ss.m14666c(parser)` marks the poll request complete and releases the next queued request | `jadx:…/ScooterFragment.java:1655-1667`; `jadx:p000/C1286ss.java:72-89` |

MTU / chunking: `C0993mx.f19701r = 20` by default and is set to `mtu - 3` in `onMtuChanged`
(`jadx:p000/C0993mx.java:97`, `:436-441`). **No `requestMtu()` call exists in the app**
(grep over all sources), so chunk size is 20 unless the peripheral initiates a larger MTU.
Outbound writes are split into 20-byte chunks (`…/BleForegroundService.java:482-531, 567-633`).

---

## 2. Frame formats and protocol selection

`hl2` is constructed with `(xq0 protocol, chunkSize)` and stores the 2-byte header plus an
"extra bytes" count (`jadx:p000/hl2.java:33-58`):

| `xq0` (ordinal) | header bytes | `extra` (`f10683b`) | complete frame length |
|---|---|---|---|
| `Ninebot` (0) `f34610a` | `5A A5` | 9 | `LEN + 9` |
| `NinebotCrypto` (1) `f34611c` | `5A A5` | 13 | `LEN + 13` |
| `Xiaomi` (2) `f34612d` | `55 AA` | 6 | `LEN + 6` |
| `XiaomiCrypto` (3) `f34613e` | `55 AB` | 16 | `LEN + 16` |

`LEN` is byte `[2]` of the frame. Protocol selection:
`scooter.useCrypto ? NinebotCrypto : (scooter.isXiaomi ? Xiaomi : Ninebot)`
(`jadx:…/ScooterFragment.java:900`, enum at `jadx:p000/xq0.java:21-33`).

Branch inside `mo3235f` (switch map `go3.f9447b`, `jadx:p000/go3.java:33-44`):

| switch value | protocol | action |
|---|---|---|
| 1 | NinebotCrypto | strip 3-byte header, decrypt body, rebuild `[5A A5 LEN] + plaintext`, verify two handshake frames, then `m3299r0` |
| 2 | **Ninebot (plain)** | `m3299r0(frame)` **unchanged** (frame already has the parser layout) |
| 3 | **Xiaomi** | `oh3.m11880i(frame)` (layout conversion, §2.2) then `m3299r0` |
| 0 / none | XiaomiCrypto | **returns without parsing** — this protocol is handled by the *other* notification path (`mo3234e` + `fm2` elliptic decrypt, `jadx:…/ScooterFragment.java:1000-1042`) |

Evidence: `jadx:…/ScooterFragment.java:1090-1105`; ninebot-crypto rebuild at `:1108-1154`.

### 2.1 Ninebot / NinebotCrypto wire layout (what `m3299r0` consumes)

Derived from the *encoder* `pe4.m12550k` (`jadx:p000/pe4.java:1041-1057` for plain,
`:1058-1142` for crypto) — it wraps the app's `ScooterRequest` = `[0x3E, direction, action, position, payload…]`
(`jadx:com/basse/scootbatt/models/scooter/helpers/ScooterRequest.java:60-104`) as:

```
5A A5 LEN 3E DIR ACTION POSITION payload[0..LEN-1] CKSUM_LO CKSUM_HI      (plain Ninebot)
```

| off | len | meaning | app's Crashlytics name | notes |
|---|---|---|---|---|
| 0-1 | 2 | `5A A5` | — | magic |
| 2 | 1 | `LEN` = payload length | — | used verbatim as the payload size |
| 3 | 1 | address byte A (`0x3E` in the app's own requests) | `SOURCE_ADDRESS` | device that produced the frame for responses: `0x20` ESC / `0x21` BLE / `0x22` BMS / `0x23` external BMS |
| 4 | 1 | address byte B | `DESTINATION_ADDRESS` | only used for logging, never for dispatch |
| 5 | 1 | **action echoed** | `COMMAND` | 1=READ, 4, 0x31=SHFW_READ, 0x34, 0x39, 0x5B, 0x5C, 0x5D |
| 6 | 1 | **register/position echoed** | `ARGUMENT` | parser lookup key |
| 7… | LEN | payload | `PAYLOAD` | parsers see **only** this slice |
| last 2 | 2 | 16-bit checksum, little-endian, `~sum(frame[2..LEN+2])` | — | **never verified on receive** (§7) |

Evidence for the layout: encoder `jadx:p000/pe4.java:1043-1056`; the *responses* the app
checks itself: `[3]==0x21 && [4]==0x3E && [5]==0x5B` (serial/key reply) at
`jadx:…/ScooterFragment.java:1129`, `[5]==0x5C && [6]==1` (auth reply) at `:1143`;
Crashlytics labels at `:1851-1881`; smali register proof at
`smali:com/basse/scootbatt/ui/fragments/scooter/ScooterFragment.smali:10136-10158`.

For **NinebotCrypto** the body (address+action+position+payload, `LEN+4` bytes) is encrypted and
followed by 4 bytes of auth tag and a 2-byte frame counter; total `LEN+13`. The app rebuilds
`[5A A5 LEN] + plaintext(LEN+4)` and passes exactly `LEN+7` bytes to `m3299r0`, i.e. the
trailing 6 bytes are dropped (`jadx:…/ScooterFragment.java:1108-1154`).

### 2.2 Xiaomi wire layout → conversion (`oh3.m11880i`)

Xiaomi encoder (`jadx:p000/pe4.java:1143-1180`) produces
`55 AA (payloadLen+2) ADDR ACTION POSITION payload… CKSUM_LO CKSUM_HI`
(total `payloadLen+8`, consistent with `extra = 6`).

`oh3.m11880i` (`jadx:p000/oh3.java:393-437`) converts a received Xiaomi frame
`55 AA LEN ADDR CMD ARG data… CKSUM(2)` into the Ninebot layout above:

| Xiaomi `ADDR` (`in[3]`) | output `[3]` | output `[4]` | meaning (app's model) |
|---|---|---|---|
| 0x20 | 0x3E | 0x20 | ESC/scooter |
| 0x21 | 0x3E | 0x21 | BLE |
| 0x22 | 0x3E | 0x22 | battery |
| 0x23 | 0x20 | 0x3E | ESC/scooter |
| 0x24 | 0x21 | 0x3E | BLE |
| 0x25 | 0x22 | 0x3E | battery |

- `out[5] = in[4]` (action), `out[6] = in[5]` (register), `out[7…] = in[6…len-3]`,
  `out[2] = in.length - 8`, and a fresh checksum is appended.
- The converter **computes** the output checksum but never compares the input checksum.
- Only `ADDR` values 0x20-0x25 are handled; any other value leaves `out[3]`/`out[4]` = 0.
- ⚠ *Inference*: the app's own tablet of address meanings is inconsistent between the two
  halves (`0x20`/`0x23` both map to ESC). Which values a real Xiaomi device puts in `ADDR`
  cannot be settled statically — see §9.

### 2.3 XiaomiCrypto ("elliptic"/Majsi) path

`mo3234e` receives on NUS TX / Xiaomi chars, and for `55 AB` frames decrypts with the
`fm2` elliptic helper, rebuilds `55 AB LEN + plaintext`, drops the **last 2 bytes** and calls
`oh3.m11880i` → `m3299r0` (`jadx:…/ScooterFragment.java:964-1042`, esp. `:1030-1042`).
⚠ *Observation*: `m11880i` itself strips another 2 bytes as "checksum", so this path drops 2
payload bytes before conversion. Either the elliptic frame carries 4 trailing bytes or this is
an off-by-two bug; needs hardware to decide (§9).

---

## 3. Response-type discrimination inside `m3299r0`

`m3299r0(frame)` (`jadx:…/ScooterFragment.java:1579`; authoritative smali
`smali:…/ScooterFragment.smali:10127-11392`):

```
LEN  = f[2];  SRC = f[3];  DST = f[4];  CMD = f[5];  ARG = f[6];
payload = new byte[LEN]; System.arraycopy(f, 7, payload, 0, LEN);   // f.length must be >= 7+LEN
switch (CMD) { case 0x5B: … case 0x5C: … case 0x5D: … }             // packed-switch
```

Then **three sequential blocks**, each an independent `if` (smali line refs):

**Block 1 — vehicle/ESC telemetry, map `X2` (`f4047X2`), key = ARG**
`smali:…:10474-10641`
```
if (CMD == 4) OR (CMD == 1 AND (SRC == 0x20 OR (SRC == 0x23 AND scooter.isXiaomi())))
    parser = X2.get(ARG);  if (parser != null) { log; parser.h(LEN, payload); scheduler.done(parser); }
```

**Block 2 — SHFW/custom-firmware, map `Y2` (`f4048Y2`), key = ARG**
`smali:…:10643-10843`
```
if (CMD == 0x34) OR (CMD == 0x31) OR (CMD == 0x39 AND (SRC == 0x20 OR (SRC == 0x23 AND isXiaomi)))
    parser = (CMD == 0x39) ? new C1138os(uo3, 10) : Y2.get(ARG);
```

**Block 3 — BMS / external BMS, maps `Z2` (`f4049Z2`) and `a3` (`f4050a3`), key = ARG**
`smali:…:10845-10990`
```
if (SRC == 0x22)                     parser = Z2.get(ARG);      // main BMS
else if (SRC == 0x23) { if (uo3.installedFlag) parser = a3.get(ARG); else return; }   // eBMS
else if (SRC == 0x25)                parser = Z2.get(ARG);      // BMS (alt address)
else                                 parser = null;
```
`uo3.installedFlag` = `uo3.f30567u0` (dex `u0`), "external battery installed".

**Answers to the task's questions**

* **Is the action byte echoed?** Yes. `CMD` (`f[5]`) is the request's `action` byte; it is the
  coarse discriminator (1 = stock read, 0x31 = SHFW read, 0x39/0x34 = special reads,
  0x5B/0x5C/0x5D = identity/pairing). Proof: the serial request is literally
  `{0x3E, 0x21, 0x5B, 0x00}` (`jadx:…/ScooterFragment.java:1472`) and its reply is handled by
  `case 0x5B`; the pairing request is `{0x3E, 0x21, 0x5C, 0x00, <16-byte key>}` (`:1485`).
* **The parser key is the *register* byte** `ARG` (`f[6]`). The four maps are keyed by exactly
  the `position` byte of each poll request (§4).
* **CRC/length check before parsing?** *No checksum check anywhere* (§7). Length handling:
  `LEN` is trusted; the payload `arraycopy` throws `ArrayIndexOutOfBoundsException` on a short
  frame, which is caught and reported to Crashlytics — no parse happens.
* **Map registration sites**: `X2`/`Y2` in `jadx:p000/ao3.java:186-221` (rebuilt whenever the
  scooter object changes); `Z2`/`a3` in `jadx:…/ScooterFragment.java:476-498`.

---

## 4. Dispatch tables — register → parser → destination model

Direction in the *request* is `xp0` (`jadx:p000/xp0.java:36-48`):
`MASTER_TO_SCOOTER=0x20`, `MASTER_TO_BLE=0x21`, `MASTER_TO_BATTERY=0x22`,
`MASTER_TO_EXTERNAL_BATTERY=0x23`, `READ=1`, `SHFW_READ=0x31`.
Timeout = `vp0.mo2828g()`, ms; it is used as a delay before the next queued request
(`jadx:p000/C0923l0.java:1420-1424`).

### 4.1 Source `0x20` (ESC) — map `X2`, block 1 (`action = 1`)

| ARG | request (action, payload) | parser | target | parses |
|---|---|---|---|---|
| 16 (0x10) | READ `{14,0}` | `un0(...,1)` | `uo3` | ESC serial (14 ASCII) + model inference |
| 26 (0x1A) | READ `{2,0}` | `un0(...,0)` | `uo3` | region/version word |
| 27 (0x1B) | READ `{2,0}` | `C1138os(4)` | `uo3` | error code (string + int) |
| 37 (0x25) | READ `{2,0}` | `C1138os(9)` | `uo3` | remaining range |
| 41 (0x29) | READ `{4,0}` | `C1138os(8)` | `uo3` | odometer (u32) |
| 47 (0x2F) | READ `{2,0}` | `C1138os(2)` | `uo3` | speed (u16) |
| 50 (0x32) | READ `{4,0}` | `C1138os(14)` | `uo3` | total time on |
| 52 (0x34) | READ `{4,0}` | `C1138os(15)` | `uo3` | total time rolling |
| 62 (0x3E) | READ `{2,0}` | `C1138os(3)` | `uo3` | ESC temperature |
| 70 (0x46) | READ `{1,0}` | `c70(...,0)` | `uo3` | MCU/chip type |
| 71 (0x47) | READ `{2,0}` | `C1138os(13)` | `uo3` | system (ADC) voltage |
| 83 (0x53) | READ `{2,0}` | `C1138os(7)` | `uo3` | motor phase current |
| 102 (0x66) | READ `{6,0}` | `C1138os(0)` | `uo3` | BMS/BLE firmware versions + flag |
| 117 (0x75) | READ `{1,0}` | `C1138os(11)` | `uo3` | operation mode (ECO/Normal/Sport) |
| 123 (0x7B) | READ `{2,0}` | `C1138os(5)` | `uo3` | KERS/regen level (Weak/Medium/Strong) |
| 124 (0x7C) | READ `{1,0}` | `C1138os(1)` | `uo3` | cruise control flag |
| 125 (0x7D) | READ `{2,0}` | `C1138os(18)` | `uo3` | status bits (taillight / mph) |
| 181 (0xB5) | READ `{2,0}` | `c70(...,1)` | `uo3` | speed (SHFW), max-speed tracking |
| 185 (0xB9) | READ `{2,0}` | `C1138os(16)` | `uo3` | distance / trip (u16) |
| 186 (0xBA) | READ `{2,0}` | `C1138os(12)` | `uo3` | duration (u16) |
| 218 (0xDA) | READ `{12,0}` | `C1138os(17)` | `uo3` | 6-byte identifier (hex) |
| 178 (0xB2) | READ `{2,0}` | `un0(...,2)` | `uo3` | version/region word |

Registration: `jadx:p000/ao3.java:190-211`. Note `C1138os(6)` (register 0xFF, `SHFW_READ`) is
registered in `Y2` (`:211`), and `Y2` additionally holds `C1138os(10)` (register 0x39, `:210`),
`C1524z4` (`:212-221`) and `d83` (`:214-220`).

### 4.2 Source `0x20` (ESC) — map `Y2`, block 2

| trigger | parser | request | target | parses |
|---|---|---|---|---|
| CMD 0x39 | `C1138os(10)` (always constructed fresh) | raw `{3E,20,39,00}` | `uo3` | firmware version string + variant |
| CMD 0x31 / 0x34, ARG 0xFF | `Y2[0xFF] = C1138os(6)` | SHFW_READ `{2,0}` | `uo3` | SHFW version word |
| ARG 0xB2 | `Y2[0xB2] = C1524z4(aj3,0)` | SHFW_READ `{2,0}` | `aj3` | SHFW flags word |
| ARG 0x11 | `Y2[0x11] = C1524z4(aj3,1)` | SHFW_READ `{32,0}` | `aj3` | SHFW speed-limit profile A |
| ARG 0x4C | `Y2[0x4C] = C1524z4(aj3,2)` | SHFW_READ `{32,0}` | `aj3` | SHFW speed-limit profile B |
| ARG 0x87 | `Y2[0x87] = C1524z4(aj3,3)` | SHFW_READ `{32,0}` | `aj3` | SHFW speed-limit profile C |
| ARG 1 / 60 / 119 | `d83(0/1/2)` | SHFW_READ `{32,0}` | — | **empty parser** (`jadx:p000/d83.java:38-52`) |

### 4.3 Source `0x22` / `0x25` (main BMS) — map `Z2`, block 3

| ARG | request (action, payload) | parser idx | timeout ms |
|---|---|---|---|
| 48 (0x30) | READ `{2,0}` | `C1252rv(0)` | 300 |
| 64 (0x40) | READ `{32,0}` | `C1252rv(1)` | 1000 |
| 27 (0x1B) | READ `{4,0}` | `C1252rv(2)` | 1000 |
| 59 (0x3B) | READ `{2,0}` | `C1252rv(3)` | 1000 |
| 32 (0x20) | READ `{2,0}` | `C1252rv(4)` | 1000 |
| 16 (0x10) | READ `{14,0}` | `C1252rv(5)` | 1000 |
| 53 (0x35) | READ `{2,0}` | `C1252rv(6)` | 1000 |
| 24 (0x18) | READ `{2,0}` | `C1252rv(7)` | 1000 |
| 49 (0x31) | READ `{12,0}` | `C1252rv(8)` | 350 |

Requests: `jadx:p000/C1252rv.java:58-79`; registration `jadx:…/ScooterFragment.java:476-487`.

### 4.4 Source `0x23` (external BMS / eBMS) — map `a3`, block 3

| ARG | request (action, payload) | parser idx | timeout ms |
|---|---|---|---|
| 24 (0x18) | READ `{2,0}` | `qv0(0)` | 1000 |
| 48 (0x30) | READ `{2,0}` | `qv0(1)` | 300 |
| 64 (0x40) | READ `{32,0}` | `qv0(2)` | 1000 |
| 27 (0x1B) | READ `{4,0}` | `qv0(3)` | 1000 |
| 59 (0x3B) | READ `{2,0}` | `qv0(4)` | 1000 |
| 32 (0x20) | READ `{2,0}` | `qv0(5)` | 1000 |
| 16 (0x10) | READ `{14,0}` | `qv0(6)` | 1000 |
| 53 (0x35) | READ `{2,0}` | `qv0(7)` | 1000 |
| 49 (0x31) | READ `{12,0}` | `qv0(8)` | 350 |

Requests: `jadx:p000/qv0.java:45-68`; registration `jadx:…/ScooterFragment.java:488-498`.

---

## 5. Byte-offset → field tables

Common helpers used by all parsers:
`AbstractC1491y9.m17773w(off, b)` = **uint16 little-endian** `ByteBuffer.wrap(b,off,2).order(LITTLE_ENDIAN).getShort() & 0xFFFF`
(`jadx:p000/AbstractC1491y9.java:498-501`). Signed reads use `ByteBuffer…getShort()` directly.
`m17772v` = uint32 (LE) reader. `m17774x(sec, rolling)` = seconds→`"3d 4h 5m"` style text
(`jadx:p000/AbstractC1491y9.java:504+`). Miles preference = `Preferences.m3207P()`
(`jadx:com/basse/scootbatt/global/Preferences.java:548-550`, field `useEnglishMiles`) and
multiplies speed/distance by `0.621371`.

### 5.1 ESC telemetry — `C1138os` (`jadx:p000/C1138os.java:156-628`)

| register (ARG) | off | len | type / endian | signed? | scale | unit | target field (dex name) | range / guards |
|---|---|---|---|---|---|---|---|---|
| 0x66 (idx 0) | 0-1 | 2 | u16 LE | no | — | version word | `uo3.f30538g` (`g`) → eBMS fw string | payload len must be ≥6 (no guard, AIOOBE otherwise) |
| 0x66 | 2-3 | 2 | u16 LE | no | — | version word | `uo3.f30536f` (`f`) → BMS fw | |
| 0x66 | 4-5 | 2 | u16 LE | no | — | version word | `uo3.f30534e` (`e`) → BLE fw | |
| 0x66 | 4,5 | 2 | packed nibbles: `100*b[5] + 10*(b[4]>>4) + (b[4]&0xF)` | no | — | — | `uo3.f30514N` (`N`, bool) = `v<72 \|\| v>200` | app-side sanity flag, value 0-1155 (`:180-187`) |
| 0x7C (idx 1) | 0 | 1 | u8 | no | — | — | `uo3.f30566u` (`u`) = **cruise control** = `b[0]==1` | payload len must be ==1 (`:190`) |
| 0x2F (idx 2) | 0-1 | 2 | u16 LE | no | ÷100, ×0.621371 if miles | km/h | `uo3.f30556p` (`p`) + formatted string `uo3.f30558q` (`q`) | 0…655.35 (`:196-209`) |
| 0x3E (idx 3) | 0-1 | 2 | **int16 LE** | **yes** | ÷10 (rounded) | °C | `uo3.f30548l` (`l`) = **ESC temperature** | −3276.8…3276.7 (`:212`) |
| 0x1B (idx 4) | 0-1 | 2 | u16 LE | no | — | error code | `uo3.f30544j` (`j`) int + `uo3.f30542i` (`i`) text | payload len must be ==2; 0 = "None - all OK"; codes 10-54 mapped, others "Unknown error code" (`:214-340`) |
| 0x7B (idx 5) | 0 | 1 | u8 (payload must be 2 bytes; only `b[0]` is used) | no | enum 0/1/2 | — | KERS/regen level | `uo3.f30512L` (`L`) enum + `uo3.f30513M` (`M`) text | len==2; 0=Weak, 1=Medium, 2=Strong (`:341-368`) |
| 0xFF (idx 6) | 0-1 | 2 | u16 LE | no | — | SHFW version | `uo3.f30532d` (`d`), `uo3.f30574y` (`y`)=true, `uo3.f30496A` (`A`)=false | len==2, value>0 and !=255 (`:369-378`) |
| 0x53 (idx 7) | 0-1 | 2 | **int16 LE** | **yes** | ×0.01 | A | `uo3.f30509I` (`I`) = motor phase current | −327.68…327.67 (`:380`) |
| 0x29 (idx 8) | 0-3 | 4 | **int32 LE** (`m17772v` = `getInt()`, signed) | **yes** | ÷1000, ×0.621371 if miles | km | `uo3.f30546k` (`k`) = **odometer** | ±2147483.647 km (`:383-387`; reader `jadx:p000/AbstractC1491y9.java:492-494`) |
| 0x25 (idx 9) | 0-1 | 2 | u16 LE | no | ÷100, ×0.621371 if miles | km | `uo3.f30552n` (`n`) + `uo3.f30554o` (`o`) text = **remaining range** | 0…655.35 (`:390-404`) |
| 0x39 (idx 10) | 0-2 | 3 | 3 × u8 ASCII digits | — | — | version `A.B.C` | `uo3.f30576z` (`z`) | len≥5; parses 3 numbers + variant byte at off 3 ("Production"/"RC"/"Beta"/"Dev"/"Unknown") + suffix text from off 4 (`:405-567`) |
| 0x75 (idx 11) | 0 | 1 | u8 | no | — | mode | `uo3.f30550m` (`m`) = "Normal"/"ECO"/"Sport" | len==1; 0=Normal,1=ECO,2=Sport, else "N/D" (`:568-576`) |
| 0xBA (idx 12) | 0-1 | 2 | u16 LE | no | seconds | duration | `uo3.f30562s` (`s`) | formatted by `m17774x` (`:577-581`) |
| 0x47 (idx 13) | 0-1 | 2 | u16 LE | no | ÷100 | V | `uo3.f30508H` (`H`) = **system/ADC voltage** | label `chip_system_voltage` via alias `uo3.f30499B0` (`:583`) |
| 0x32 (idx 14) | 0-3 | 4 | **int32 LE** (signed) | yes | seconds | duration | `uo3.f30560r` (`r`) = total time on | `:585-589` |
| 0x34 (idx 15) | 0-3 | 4 | **int32 LE** (signed) | yes | seconds | duration | `uo3.f30564t` (`t`) = total time rolling | `:590-594` |
| 0xB9 (idx 16) | 0-1 | 2 | u16 LE | no | ÷100, ×0.621371 if miles | km | `uo3.f30506F` (`F`) + text `uo3.f30504E` (`E`) | `:595-610` |
| 0xDA (idx 17) | 0-11 | 12 | 6 × u16 LE rendered `%04X` | no | — | 6-byte id | `uo3.f30530c` (`c`) | len==12; formatter `jadx:p000/a14.java` case 2 (`:611-618`) |
| 0x7D (idx 18) | 0-1 | 2 | u16 LE | no | — | status bits | `uo3.f30510J` (`J`) raw; `uo3.f30568v` (`v`)=bit1 (**taillight always on**); `uo3.f30572x` (`x`)=bit4 (**use mph**) | len==2 (`:619-627`) |

### 5.2 ESC identity/model — `c70` (`jadx:p000/c70.java:109-127`) and `un0` (`jadx:p000/un0.java:86+`)

| register | parser | off | type | scale | target | notes |
|---|---|---|---|---|---|---|
| 0x46 | `c70(…,0)` | 0 | u8 | — | `uo3.f30507G` (`G`) | Xiaomi: 0=STM32F, 1=GD32E, 2=GD32F; other: 0=STM32F, 1=AT32F, else "Unknown" (`:110-124`) |
| 0xB5 | `c70(…,1)` | 0-1 | **int16 LE** | ×0.001 if `isXiaomi` else ×0.1; ×0.621371 if miles | `uo3.f30500C` (`C`) = **speed used by dashboard/trip recorder**; max-speed `uo3.f30502D` (`D`) + history list `uo3.f30505E0` | >0 values only added to history (`:126-127`) |
| 0x10 | `un0(…,1)` | 0… | ASCII string (whole payload) | — | `uo3.f30528b` (`b`) → serial (`uo3.f30516P`/`P`); inferred model into `ry4.f26566c`, region into `uo3.f30540h` (`h`) | model-name switch over `"e","f","1s","e2","f2","g2","d18","d28","d38","esx","max","mi3","pro","t15","m365","p100","pro2"`, prefixes `N2G`/`N5G`, `"256"/"257"/"263"/"272"/"292"/"303"` (`:119-...`) |
| 0x1A | `un0(…,0)` | 0-1 | u16 LE | — | `uo3.f30532d` (`d`); `uo3.f30515O` (`O`) = `1280 < v < 1792 && model != "f"`, then `v -= 1024`; lock flag `uo3.f30570w` (`w`), `uo3.f30498B` (`B`), `uo3.f30511K` (`K`) | `:102-118`, `:2364-2395` |
| 0xB2 | `un0(…,2)` | 0-1 | u16 LE | — | version/region word | `:51-53`, `mo2829h` case 2 |

⚠ `un0.mo2829h` is only **partially decompiled** (`jadx:p000/un0.java:2399`
`throw new UnsupportedOperationException("Method not decompiled: p000.un0.mo2829h(int, byte[]):void")`);
the model/region detection above is from the readable parts plus jadx's raw pseudo-bytecode
inside the method (`:850-900`, `:2355-2395`).

### 5.3 Main BMS telemetry — `C1252rv` (`jadx:p000/C1252rv.java:110-259`)

| register (ARG) | off | len | type | signed? | scale | unit | target field (dex) | UI label (`res:res/values/strings.xml`, bound in `jadx:p000/an3.java:183-363`) |
|---|---|---|---|---|---|---|---|---|
| 0x31 (idx 8) | 0-1 | 2 | u16 LE | no | — | mAh | `rm3.f26124g` (`g`) → alias `I`,`J` | remaining mAh (`chip_remaining_mah`) |
| 0x31 | 2-3 | 2 | u16 LE | no | — | % | `rm3.f26126h` (`h`) → alias `K`,`L` | battery percentage (`chip_battery_percentage`) |
| 0x31 | 4-5 | 2 | **int16 LE** | **yes** | ÷100 | A | `rm3.f26128i` (`i`) → alias `M`,`P` | battery current (`chip_battery_current`) |
| 0x31 | 6-7 | 2 | u16 LE | no | ÷100 | V | `rm3.f26130j` (`j`) → alias `N`,`Q` | battery voltage (`chip_battery_voltage`) |
| 0x31 | — | — | computed `I×V` | — | — | W | `rm3.f26132k` (`k`) → alias `O`,`R` | wattage (`chip_wattage`) |
| 0x31 | — | — | computed: first `off 2` value kept in `rm3.f26163z0`, then `previous - current` | — | — | % | `rm3.f26089C` (`C`) → alias `f26161y0` | **consumed percentage** (drives `consumed_percentage_notification_dialog_layout`, `jadx:…/ScooterFragment.java:692`, `jadx:p000/ao3.java:83-107`) (`:248-253`) |
| 0x30 (idx 0) | 0-1 | 2 | u16 LE bit 6 | no | — | bool | `rm3.f26087A` (`A`) → alias `f26157w0` | **charging state** ("charging"/"discharging", `jadx:p000/C1295t0.java:281`) |
| 0x40 (idx 1) | 0,2,4,…,18 | 20 | 10 × u16 LE | no | ÷1000 | V | `rm3.f26144q,f26146r,f26148s,f26150t,f26152u,f26154v,f26156w,f26158x,f26160y,f26162z` (cells 1-10) | cell 1-10 voltage, `bl3(21..23)` = `"%.3f V"` |
| 0x1B (idx 2) | 0-1 | 2 | u16 LE | no | — | count | `rm3.f26138n` (`n`) → alias `Y` | number of full charges |
| 0x1B | 2-3 | 2 | u16 LE | no | — | count | `rm3.f26140o` (`o`) → alias `Z` | number of partial charges |
| 0x3B (idx 3) | 0-1 | 2 | u16 LE | no | — | % | `rm3.f26142p` (`p`) → alias `a0` | health, text `"N% - Healthy/Normal/Poor"` (>90 Healthy, >50 Normal) |
| 0x20 (idx 4) | 0-1 | 2 | u16 LE bitfield | no | — | date | `rm3.f26122f` (`f`) → alias `H` | production date: `year=(v>>9)+2000`, `month=(v>>5)&15`, `day=v&31` (`:147-200`) |
| 0x10 (idx 5) | 0…len | ≤14 | ASCII | — | — | text | `rm3.f26116c` (`c`) → alias `D`; cell type `rm3.f26118d` (`d`) → `E` | serial; cell vendor from prefix (`4…`=LG, `3JCG`=Purple, `3JGG`=EVE ICR18650, `3J`=Blue, `0J`=Grey) (`:202-230`) |
| 0x35 (idx 6) | 0 | 1 | u8 | no | −20 | °C | `rm3.f26134l` (`l`) → alias `T` | temperature sensor 1 |
| 0x35 | 1 | 1 | u8 | no | −20 | °C | `rm3.f26136m` (`m`) → alias `U` | temperature sensor 2 |
| 0x18 (idx 7) | 0-1 | 2 | u16 LE | no | — | mAh | `rm3.f26120e` (`e`) → alias `F`,`G` | designed capacity |

Derived (computed by `rm3.m13941e()`, `jadx:p000/rm3.java:360-420`): min/max cell index
(lowest cell `f26127h0`/`f26129i0`=`i0`, highest `f26131j0`/`f26133k0`=`k0`), max−min
(`f26115b0`→`c0`), average (`f26119d0`→`e0`), standard deviation (`f26123f0`→`g0`).
`i80.m7267l0` = min, `i80.m7266k0` = max, `i80.m7254Y` = average
(`jadx:p000/i80.java:259-284`).

### 5.4 External BMS telemetry — `qv0` (`jadx:p000/qv0.java:109-256`)

| register (ARG) | off | len | type | signed? | scale | unit | target field (dex) | label (`jadx:p000/yn3.java:164-290`) |
|---|---|---|---|---|---|---|---|---|
| 0x31 (idx 8) | 0-1 | 2 | u16 LE | no | — | mAh | `tn3.f29078f` (`f`) → alias `J`,`K` | remaining mAh |
| 0x31 | 2-3 | 2 | u16 LE | no | — | % | `tn3.f29080g` (`g`) → alias `M`,`N` | battery percentage |
| 0x31 | 4-5 | 2 | **int16 LE** | **yes** | ÷100 | A | `tn3.f29082h` (`h`) → alias `O`,`R` | current |
| 0x31 | 6-7 | 2 | u16 LE | no | ÷100 | V | `tn3.f29084i` (`i`) → alias `P`,`S` | voltage |
| 0x31 | — | — | computed `I×V` | — | — | W | `tn3.f29086j` (`j`) → alias `Q`,`T` | wattage |
| 0x30 (idx 1) | 0-1 | 2 | u16 LE bit 6 | no | — | bool | `tn3.f29045C` (`C`) → alias `f29093m0` | charging state |
| 0x40 (idx 2) | 0,2,…,18 | 20 | 10 × u16 LE | no | ÷1000 | V | `tn3.f29104s,f29106t,f29107u,f29108v,f29109w,f29110x,f29111y,f29112z,f29043A,f29044B` → display aliases `f29073c0…f29091l0` | cell 1…10 voltage |
| 0x1B (idx 3) | 0-1 / 2-3 | 2+2 | u16 LE ×2 | no | — | count | `tn3.f29092m` (`m`) → `W`, `tn3.f29094n` (`n`) → `X` | full / partial charges (note: **same order as main BMS**) |
| 0x3B (idx 4) | 0-1 | 2 | u16 LE | no | — | % | `tn3.f29096o` (`o`) → `Y` | health text |
| 0x20 (idx 5) | 0-1 | 2 | u16 LE bitfield | no | — | date | `tn3.f29076e` (`e`) → `I` | production date (same decode) |
| 0x10 (idx 6) | 0… | ≤14 | ASCII | — | — | text | `tn3.f29070b` (`b`) → `E`; cell type `tn3.f29072c` (`c`) → `F` | serial + cell vendor |
| 0x35 (idx 7) | 0 / 1 | 1+1 | u8 ×2 | no | −20 | °C | `tn3.f29088k` (`k`) → `U`, `tn3.f29090l` (`l`) → `V` | temperature sensors 1/2 |
| 0x18 (idx 0) | 0-1 | 2 | u16 LE | no | — | mAh | `tn3.f29074d` (`d`) → `G`,`H` | designed capacity |

Derived like the main BMS in `tn3.m15129e()` (`jadx:p000/tn3.java:322-378`), plus
`tn3.f29054L` (`L`) = "derived actual capacity" (formula combines capacity and percentage
LiveData: `pi2.m12615f(new nc1(…))`, `jadx:p000/tn3.java:285`).

### 5.5 SHFW (custom firmware) profiles — `C1524z4` (`jadx:p000/C1524z4.java:67-160`) → `aj3`

| register | off | len | type | scale | target (jadx) | meaning |
|---|---|---|---|---|---|---|
| 0xB2 (idx 0) | 0-1 | 2 | int16 LE | — | `aj3.f642b` | SHFW flags word (`aj3.m742f`) |
| 0x11 (idx 1) | 10-11 | 2 | u16 LE | — | `aj3.f647g` (`g`) raw; `aj3.f645e` (`e`)=bit3; `aj3.f646f` (`f`)=bit2 | profile-1 flags |
| 0x11 | 12,13,14 | 3 | u8 ×3 | — | `aj3.f649i` (`i`), `aj3.f648h` (`h`), `aj3.f650j` (`j`) via map `ui3.f30220e` | profile-1 mode / speed-limit / KERS-ish indices |
| 0x11 | 15 | 1 | u8 | — | `aj3.m743g(v)` | profile-1 extra |
| 0x4C (idx 2) | same 10-15 | 6 | — | — | same fields | profile-2 |
| 0x87 (idx 3) | same 10-15 | 6 | — | — | same fields | profile-3 |

Payload length must be exactly 32 for idx 1-3 and 2 for idx 0 (`:70,85,118,149`).
`d83` deliberately parses nothing (`jadx:p000/d83.java:38-52`).

### 5.6 Identity / pairing frames handled directly in `m3299r0`

| CMD (`f[5]`) | condition | action | evidence |
|---|---|---|---|
| 0x5B | `LEN == 30` else **return** | `f4059j3 = payload[16..29]` (14-byte ASCII serial), `f4043T2 = true` (serial received) | `jadx:…/ScooterFragment.java:1602-1608`; smali `10437-10468` |
| 0x5C | `ARG == 1` (or `isXiaomi`) | `f4044U2 = true` ("paired"); shows legacy-pairing dialog if `f4045V2` | `:1758-1773` |
| 0x5D | `ARG == 1` else return | `f4046W2 = true; f4044U2 = true; state = ee0.f6546j` | `:1814-1821` |
| any 0x5B reply in crypto path | `[3]=0x21 && [4]=0x3E && [5]=0x5B && LEN=30` | copies `payload[0..15]` into the ninebot-crypto key `C1375v6.f31215d` | `:1129-1133` |
| 0x5C reply in crypto path | `[5]=0x5C && [6]=1` | activates auth key `C1375v6.f31216e` (16 bytes) | `:1143-1146` |

The serial write uses CMD 0x5D: `m3284b0()` builds `{3E,21,5D,00} + 14-byte serial`
(`jadx:…/ScooterFragment.java:752-758`).

### 5.7 Quick reference: where each requested field actually comes from

| Field (task list) | Register / frame | Decode | Status |
|---|---|---|---|
| speed | ESC `0xB5` (dashboard + trips) and ESC `0x2F` (display string) | int16 LE ×0.001 (Xiaomi) / ×0.1, or u16 LE ÷100 | ✅ verified |
| battery percentage | BMS `0x31` off 2 (same for eBMS) | u16 LE, already % | ✅ verified |
| battery current | BMS/eBMS `0x31` off 4 | int16 LE ÷100 A (negative = charge) | ✅ verified |
| battery voltage | BMS/eBMS `0x31` off 6 | u16 LE ÷100 V | ✅ verified |
| battery temperature | BMS/eBMS `0x35` off 0/1 | u8 − 20 °C (2 sensors) | ✅ verified |
| ESC/controller temperature | ESC `0x3E` off 0 | int16 LE ×0.1 °C | ✅ verified |
| remaining range | ESC `0x25` off 0 | u16 LE ÷100 km | ✅ verified |
| total mileage / odometer | ESC `0x29` off 0 | int32 LE ÷1000 km | ✅ verified |
| trip distance | *no register* — computed by the trip recorder from speed integration (`jadx:p000/jh4.java`, `TripSample`) | — | ✅ verified absence |
| trip time | *no register* — client-side; ESC has cumulative counters `0x32`/`0x34` (int32 s) and `0xBA` (u16 s) | — | ✅ |
| average speed | *no register* — computed client-side (trip stats) | — | ✅ |
| serial number | ESC `0x10`, BMS `0x10`, eBMS `0x10` (14 ASCII), plus frame CMD `0x5B` payload[16..29] | ASCII | ✅ verified |
| firmware version | ESC `0x66` (BLE/BMS/eBMS words), ESC `0x39` (version string), ESC `0xFF` (SHFW) | u16 LE / digits | ✅ verified |
| BMS cell voltages (highest/lowest) | BMS/eBMS `0x40` (10 × u16 LE ÷1000 V); min/max derived in `rm3.m13941e`/`tn3.m15129e` | — | ✅ verified |
| error / warning codes | ESC `0x1B` off 0 | u16 LE, 0 = OK, 10…54 named | ✅ verified |
| charging state | BMS/eBMS `0x30` off 0, bit 6 | bool → "charging"/"discharging" | ✅ verified |
| lock state | `uo3.f30537f0` (dex `f0`), written from the ESC `0x1A` path in `un0` | bit test | ⚠ partially verified (see §9.5) |
| gear / mode | ESC `0x75` off 0 | u8: 0 = Normal, 1 = ECO, 2 = Sport | ✅ verified |
| light state | **no live field.** Only the "taillight always on" bit (ESC `0x7D` bit 1) and SHFW profile flags (`chip_always_active_headlight` / `…brakelight`, in `aj3`, registers `0x11`/`0x4C`/`0x87`) | — | ✅ verified absence |
| KERS / regen level | ESC `0x7B` | u8: 0 = Weak, 1 = Medium, 2 = Strong (`kers_weak/medium/strong`) | ✅ verified |
| cruise state | ESC `0x7C` | u8 == 1 | ✅ verified |
| speed limit / region variant | SHFW profile registers `0x11`/`0x4C`/`0x87` (model `aj3`) and the SN-derived region in `uo3.f30524X` | — | ✅ verified |

---

## 6. Result model — the vocabulary to use

Parsers write `MutableLiveData`/mapped-LiveData fields of four view models. Class-level
meaning is established by the UI bindings (`chip_*` strings) and by the trip recorder.

| VM | role | created by | evidence |
|---|---|---|---|
| `uo3` | ESC / "vehicle" | `ScooterFragment.m3293l0()` → `f4028E2` | `jadx:…/ScooterFragment.java:1305-1307` |
| `rm3` | main BMS | `m3292k0()` → `f4029F2` | `:1288-1290` |
| `tn3` | external BMS (eBMS) | `m3289h0()` → (battery fragment `b0()`) | `:1270-1272`; `jadx:p000/an3.java`/`yn3.java` |
| `aj3` | SHFW profiles/speed limits | `m3294m0()` → `f4031H2` | `:1311-1313` |

**Stable, non-obfuscated domain vocabulary** — `com.basse.scootbatt.domain.TripSample`
(`jadx:com/basse/scootbatt/domain/TripSample.java:11-27`):
`batteryPercent`, `batteryTempC`, `currentA`, `escTempC`, `gpsSpeedKmh`, `powerW`,
`remainingMAh`, `speedKmh`, `tOffsetSeconds`, `voltageV`, `lat`/`lng`/`altitude`.

The trip recorder shows exactly which telemetry feeds those fields
(`jadx:p000/jh4.java:309-353`):

| TripSample field | source when eBMS installed | source otherwise |
|---|---|---|
| `speedKmh` | `uo3.f30551m0` (= `f30500C`, ESC reg 0xB5) ×1.60934 if the user prefers miles | same |
| `currentA` | `tn3.f29057O` (= `f29082h`, eBMS reg 0x31 off 4 ÷100) | `rm3.f26099M` (= `f26128i`, BMS reg 0x31 off 4 ÷100) |
| `voltageV` | `tn3.f29058P` | `rm3.f26100N` (= `f26130j`, off 6 ÷100) |
| `powerW` | `tn3.f29059Q` | `rm3.f26101O` (= `f26132k`, V×I) |
| `batteryPercent` | `tn3.f29055M` (= `f29080g`, off 2) | `rm3.f26097K` (= `f26126h`, off 2) |
| `remainingMAh` | `tn3.f29052J` (= `f29078f`, off 0) | `rm3.f26095I` (= `f26124g`, off 0) |
| `batteryTempC` | *not set (null)* | `min(rm3.f26106T, rm3.f26107U)` = min of the two 0x35 sensors |
| `escTempC` | `uo3.f30527a0` (= `f30548l`, ESC reg 0x3E) | same |

The app polls exactly these three ESC registers while trip recording:
`c70(uo3,isXiaomi,1)` = 0xB5 speed, `C1138os(uo3,3)` = 0x3E ESC temp,
`C1138os(uo3,7)` = 0x53 phase current (`jadx:p000/jh4.java:412-425`).

Field-semantics cross-reference for the ESC VM (jadx name → dex name → label):

| jadx | dex | label / meaning | evidence |
|---|---|---|---|
| `f30516P` | `P` | serial number | `jadx:p000/dp3.java:184-186` |
| `f30517Q` | `Q` | MCU identifier | `:190-192` |
| `f30524X` | `X` | region (model variant from SN) | `:196-198` |
| `f30559q0` | `q0` | chip type | `:200-202` |
| `f30520T` | `T` | ESC firmware version | `:204-206` |
| `f30521U` | `U` | BLE firmware version | `:208-210` |
| `f30522V` | `V` | BMS firmware version | `:212-214` |
| `f30523W` | `W` | external BMS firmware version | `:216-220` |
| `f30531c0` | `c0` | operation mode | `:261-263` |
| `f30525Y` | `Y` | error code | `:264-266` |
| `f30543i0` | `i0` | remaining mileage | `:267-269` |
| `f30545j0` | `j0` | total time on | `:270-272` |
| `f30549l0` | `l0` | total time rolling | `:273-275` |
| `f30526Z` | `Z` | odometer | `:276-280` |
| `f30529b0` | `b0` | ESC temperature (hidden for model `g30`) | `:281-286` |
| `f30533d0` | `d0` | cruise control | `jadx:p000/vo3.java:233-234` |
| `f30535e0` | `e0` | taillight always on | `:235-238` |
| `f30537f0` | `f0` | locked | `:243-246` |
| `f30539g0` | `g0` | use mph unit | `:239-242` |
| `f30573x0` | `x0` | KERS mode | `:250-253` |
| `f30569v0` | `v0` | SHFW-installed/status dependency flag | `:241` (`xb2` dependency) |
| `f30499B0` | `B0` | system/ADC voltage (used in notification when the `adcVoltageInsteadOfBatteryInNotification` pref is on) | `jadx:…/BleForegroundService.java:413-422`, pref `jadx:…/Preferences.java:560` |
| `f26103Q` | `Q` (rm3) | battery voltage (default notification value) | same |

Unit formatting helpers (what the app renders): `bl3` case 14 `"%.2f V"`, 15 `"%.2f W"`,
16 `"%.1f W"`, 17/18 temperature °C/°F, 24 `"mAh"`, 25 `"%"`, 26 `"%.2f A"`, 21-23 `"%.3f V"`
(`jadx:p000/bl3.java:270-355`); the eBMS/ESC string mappers are `sn3` (`jadx:p000/sn3.java`).

---

## 7. Error handling / robustness (important for a re-implementation)

1. **No CRC is ever verified.** `hl2.m6915a` checks only header bytes and the length byte
   (`jadx:p000/hl2.java:66-90`); `m3299r0` ignores the two trailing checksum bytes for the
   plain-Ninebot layout; `oh3.m11880i` *computes* an output checksum without comparing the
   input's (`jadx:p000/oh3.java:426-436`); the Ninebot-crypto path uses the last 2 bytes as a
   frame counter, not a checksum (`jadx:…/ScooterFragment.java:1119-1127`).
   ⇒ a corrupted-but-well-framed packet is parsed and displayed.
2. **Short/partial frames are silently buffered**, never reported: `hl2` returns `null` until
   `LEN + extra` bytes have accumulated; the poll request then simply times out
   (`mo2828g()` ms) and the next queued request is sent (`jadx:p000/C0923l0.java:1420-1424`).
3. **Notifications that do not start with the configured header are dropped** (`hl2` returns
   `null`), which is the only resynchronisation mechanism.
4. **Truncated-but-"complete" frames** (LEN larger than the real data) make
   `System.arraycopy(frame,7,payload,0,LEN)` throw `ArrayIndexOutOfBoundsException`. The whole
   dispatch is inside `try { … } catch (Exception)` → Crashlytics with custom keys `DATA`,
   `SOURCE_ADDRESS` (f[3]), `DESTINATION_ADDRESS` (f[4]), `PAYLOAD`, `COMMAND` (f[5]),
   `ARGUMENT` (f[6]); **no telemetry is updated** for that frame
   (`jadx:…/ScooterFragment.java:1844-1889`, smali `10158`, `11100-11182`).
5. **Parser-level length guards are advisory**: `C1138os` cases 1/4/5/6/10/11/17/18 check
   `payloadLen`, `C1524z4` cases check 2/32, `m3299r0` requires `LEN == 30` for 0x5B. The
   other parsers (`C1252rv`, `qv0`, `c70`, `un0`) index blindly → AIOOBE → rule 4.
6. Parsers that bail out silently on a wrong length leave the *old* value in the LiveData
   (e.g. a 0x39 firmware reply with `LEN < 5` does nothing, `jadx:p000/C1138os.java:405-406`).
7. Auth/pairing frames are not validated beyond magic bytes + fixed offsets
   (`jadx:…/ScooterFragment.java:1129`, `:1143`); no signature check of the crypto key blob.
8. Parser exceptions never disconnect BLE; only the BleForegroundService `IOException` handlers
   do (`jadx:p000/C0993mx.java:288-320`).

---

## 8. Model- and generation-specific branches

| Branch | Where | Effect on parsing |
|---|---|---|
| `useCrypto` / `isXiaomi` from the **BLE advertisement** | `jadx:p000/C1142ow.java:63-116` | byte[0] of the 6-byte advert block → model: 0x20=`m365`(Xiaomi), 0x21=`esx`, 0x22=`pro`(X), 0x23=`t15`, 0x24=`max`(G30), 0x78=`g65`, 0x83=`g2`, 0x27=`e`, 0x7D=`e2`, 0x25/0x2B=`1s`(X), 0x28=`pro2`(X), 0x29=`lite`(X), 0x2E=`mi3`(X), 0x7F/0x80/0x81=`f2`, 0x7B/0x2C=`f`, 0x2D=`f65`, 0x70=`gt1`, 0x71=`gt2`, 0x72=`d28`, 0x73=`d38`, 0x74=`d18`, 0x76=`p65`, 0x77=`p100`, 0x4A=`x160`; **byte[1]==2 ⇒ `useCrypto=true`** (`:117`) |
| protocol choice | `jadx:…/ScooterFragment.java:900` | `NinebotCrypto` / `Xiaomi` / `Ninebot` (frames arrive encrypted vs converted vs raw, §2) |
| `isNewNinebotGeneration()` | `jadx:com/basse/scootbatt/global/Scooter.java:170-172` | true only for models `g2`, `g65`, `f2`; hides the "locked" toggle (`jadx:p000/vo3.java:243,268`) and changes the BLE auth path (`jadx:p000/ho3.java:122,131`) |
| Xiaomi vs Ninebot in `c70` | `jadx:p000/c70.java:110-124` | MCU-id table differs (`GD32E/GD32F` vs `AT32F`), and 0xB5 speed scale is **×0.001 (Xiaomi) vs ×0.1 (other)** (`:117`) |
| `z10` model | `jadx:p000/pe4.java:1166-1168` | Xiaomi encoder overrides the address byte to 0x11 |
| `g30` | `jadx:p000/dp3.java:281-286` | ESC-temperature row hidden (parsing unchanged) |
| external battery eligibility | `jadx:com/basse/scootbatt/global/Scooter.java:155-157` | only models `esx`, `e`; the eBMS map (`a3`) is additionally gated at runtime by `uo3.f30567u0` |
| `max`/`f` model quirks in `un0` | `jadx:p000/un0.java:109`, `:2364+` | the `1280 < v < 1792` region flag is suppressed for model `"f"` |
| stock vs SHFW | maps `X2` vs `Y2` (§3) | SHFW/speed-limit values use `SHFW_READ` (0x31) frames and the `aj3` model; the app also has empty `d83` parsers for 0x01/0x3C/0x77 |
| main vs external battery | block 3 (§3) | same register numbers, **different parsers and different view models** (`rm3` vs `tn3`) |
| miles / °F preferences | `Preferences.m3207P()` / `m3208Q()` | parsing itself stores km/h and °C; the ×0.621371 factors in `C1138os` case 2/8/9/16 and `c70` are applied *at parse time* only when the user picked miles (⚠ the trip recorder multiplies back by 1.60934, `jadx:p000/jh4.java:311-314`) |

---

## 9. Unverified / needs hardware

1. **Address semantics on the wire.** Statically proven: `f[3]` of a *response* carries the
   replying device for BLE (`0x21` — verified twice in the pairing checks,
   `jadx:…/ScooterFragment.java:1129,1143`) and is used as `0x22`/`0x23`/`0x25` for the BMS
   maps. Whether the ESC really answers with `0x20` (assumed) or with `0x3E` (which would make
   the app's own block-1 condition `SRC == 0x20` fire) can only be confirmed with a capture.
2. **Xiaomi address table** (`oh3.m11880i`): the six-value table is proven, but which value a
   real M365/Mi 3 device uses in a reply (and whether battery replies really become
   `SRC = 0x22`) is unverified. The 2-byte drop before conversion in the elliptic path (§2.3)
   may be an app bug.
3. **Exact unit of register 0xB5 speed** — the app applies `×0.001` for Xiaomi and `×0.1`
   otherwise. Inference: `×0.001` suggests m/h, `×0.1` suggests 0.1 km/h; not proven.
4. **`un0` (ESC identity, registers 0x10/0x1A/0xB2) is not fully decompiled**
   (`jadx:p000/un0.java:2399`). The model/region/version logic and the lock/flag bits derived
   from it are only partially recovered.
5. **Semantics of a few ESC fields** are inferred from UI labels only: `f30510J` (`J`, reg 0x7D
   raw word), `f30514N` (`N`, reg 0x66 packed flag), `f30515O` (`O`, reg 0x1A region flag),
   `f30496A`/`f30574y` (`A`/`y`, "SHFW present" pair), and the two "duration" values
   (0xBA/0x32/0x34) — the app renders them as text without an explicit unit string.
6. **No BMS/BLE error-code tables for values not listed** in `C1138os` case 4 (codes 16/17/25…
   collapse to "Unknown error code").
7. **Frame-level CRC algorithm** is known for TX (`~sum` 16-bit LE,
   `jadx:p000/pe4.java:1050-1056`) but never checked on RX, so the *device's* RX checksum
   convention (and whether it matches the app's TX checksum) is unverified.
8. **Startup/poll ordering** (which registers are requested, in what order, per model) lives in
   the fragments' coroutines (`jadx:p000/ln3.java:69-81`, `vo3.java`, `ho3.java`) and was not
   exhaustively traced here — out of scope for the byte-offset deliverable, but needed for a
   drop-in replacement's polling loop.
