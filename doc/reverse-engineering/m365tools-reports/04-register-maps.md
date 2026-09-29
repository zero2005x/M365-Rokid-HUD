# AREA 4 — Per-model register / field maps (M365 Tools 1.8.0, `app.peretti.m365tools`)

Static analysis only. No scooter, no phone. All citations are relative to
`/home/kali/ScooterHacking/re/m365tools/`; `apktool-out/smali/…` is the source of
record. `jadx-out-nodeobf/` is cited only where the Java is materially easier to
read and the smali was checked to agree. `jadx-out/` is never cited.

---

## 0. Executive summary

1. **`zm` is not the M365 model list.** It is the *Ninebot/Segway* model enum
   (UNKNOWN, ONE, MINI, MINI_MAX, MARK2, ESCOOTER, VIO, KART, …, T15,
   F_Series30, F_Series60 — 30 constants). Each constant carries a **marketing
   name** as its third constructor argument, and that is where `M365`,
   `M365PRO`, `M365_1S`, `M365_Lite`, `M365_3`, `M365_PRO2` live.
   `zm.smali:1643-1719` (constructor), `zm.smali:152-1514` (`<clinit>`),
   `zm$*.smali` (one file per constant that needs a composed payload).
2. There are **four** register buffers, not two: `mDataBytes` (ESC/CTL),
   `mBLEDataBytes` (BLE), `mDataBytesBattery` (BMS) and a fourth, unnamed
   buffer selected for board type `xm.BMS2` (second/external pack).
   All four are `byte[0x200]` (`iq.smali:305-341`).
3. **The unit is the decisive fact.** A reply to "read register R" is stored at
   `buffer[R * 2]`, so **buffer byte offset = register address × 2**; each
   register owns a 2-byte little-endian slot. Register space is therefore
   `0x00..0xFF` and the buffers are 256 × u16 slots. This is *read from the
   code*, three independent ways (see §2).
4. Only two commands fill a buffer: `READ` (id `1`) and `RESPONSE_READING`
   (id `4`). Anything else is logged to Crashlytics and dropped.
5. The classic **Xiaomi** protocol is codec `mn`, an RC4-encrypted `55 AB`
   frame; `M365 Tools` selects it for the models whose display name is `M365`,
   `M365PRO`, `M365_1S`, `M365_1S_DE`, `M365_Lite`, `M365_PRO2`, `M365_3` — but
   **only when the scanned protocol version is 1** (`po.smali:611-712`).
6. There is **no explicit "second battery present" flag** in the parser. The
   external pack is a second `ym` holder whose values are *added* to the main
   BMS values (`iq.smali:1729-1733`, `iq.java` line 623). Presence is implied
   only by the model's board list containing an `xm.BMS2` entry.

---

## 1. The model enum `zm` — what each constant actually carries

Constructor (real, 9 args) `zm.smali:1643`:
`(String name, int ordinal, String s, int i2, int i3, ArrayList<String> displayNames, ArrayList<wm> boards, ArrayList<qn> groups, ArrayList<pn> extras)`.
The synthetic 10-arg form at `zm.smali:1516` wraps a bare `String` into the
`displayNames` list; the flags int defaults the `pn` list to `null`.

Only **`i3`** (the 5th argument) is stored — `zm.smali:1697` `iput p5 → OO00OOOOOO000O0OO00O`.
`i2` (4th argument) is compiled in but never read anywhere in `zm`. The
`displayNames` list is only ever *filled* (`zm.smali:1705-1707`); nothing reads
it back in this class.

| # | constant name | marketing name (`M365 Tools` UI) | i2 | **i3 (model id)** | citation |
|---|---|---|---|---|---|
| 0 | `UNKNOWN` | `Unknown` | 0 | 0 | `zm.smali:158-170,443` |
| 1 | `ONE` | `One` | 2 | 2 | `zm.smali:450-518` |
| 2 | `MINI` | `Mini` | 3 | 3 | `zm$OO00OOOOO00OO00OOOO0.smali:26-31,175` |
| 3 | `MINI_MAX` | `MiniMax` | 35 | 24 | `zm$O000000O000000000OO0.smali:175` |
| 4 | `MARK2` | `Mark2` | 4 | 19 | `zm$OO0OOOOOOOO00000O0O0.smali:175` |
| 5 | **`ESCOOTER`** | **`M365`** | 5 | **32** | `zm$OOOOOOO0OOOOO0O00OO0.smali:26-31,162-175` |
| 6 | `VIO` | — | 6 | 20 | `zm$OOOOO0000OO0O00000O0.smali` |
| 7 | `KART` | `Kart` | 7 | 48 | `zm$OO00O0O0O0000000000O.smali:70` |
| 8 | `KART_PRO` | `KartPro` | 72 | 49 | `zm$OO0O0OOO00O00OOO0000.smali:57` |
| 9 | `KART_PRO_LAMBO` | `KartProLambo` | 9 | ? | `zm.smali:585` |
| 10 | `NANO` | `MiniLite` | 32 | 22 | `zm$OO0OO000O000OOO0O00O.smali:134` |
| 11 | `MINI_KIDS_MI` | `MiniKidsMi` | 11 | ? | `zm.smali` |
| 12 | `MINI_KIDS` | `MiniKids` | 12 | ? | `zm.smali` |
| 13 | `MARK3` | `Z` | 42 | 18 | `zm$O0O000OOOO0000OOOO0O.smali:162` |
| 14 | `SCOOTER2` | — | 52 | 33 | `zm$OO00OOOOOO000O0OO00O.smali:273` |
| 15 | `SCOOTER2_P` | — | 55 | 39 | `zm$OOOOO00O0O0OO00OOO0O.smali:288` |
| 16 | `MI_SCOOTER_PRO` | **`M365PRO`** | 53 | 34 | `zm$O00OO0OOO0O0OOO0OO0O.smali:175` |
| 17 | `MI_SCOOTER_1S_DE` | **`M365_1S_DE`** | 37 | 37 | `zm$OO00OO0O0OO0O00000OO.smali:210` |
| 18 | `MI_SCOOTER_LITE` | **`M365_Lite`** | 41 | 41 | `zm$OOOOOOO00OOO0O00OOOO.smali:210` |
| 19 | `MI_SCOOTER_1S` | **`M365_1S`** | 43 | 43 | `zm$O000OO0OO0OOO00OO000.smali:210` |
| 20 | `MI_SCOOTER_PRO2` | **`M365_PRO2`** | 40 | 40 | `zm$OOO00O0OO00OOO0OOOO0.smali:210` |
| 21 | `MI_SCOOTER_3` | **`M365_3`** | 46 | 46 | `zm$O00OO00OOOOOO0OOOOO00O.smali:210` |
| 22 | `WILD_STEEL_DUST` | — | ? | ? | `zm$OOOO00O0OOO0OOOOO000.smali` |
| 23 | `SCOOTER_G30` | — | ? | ? | `zm$OO0O00O0OO00OOO00O00.smali` |
| 24 | `STEELDUST` | `Steeldust` | ? | ? | `zm.smali:1099-1117` |
| 25 | `MEBIKE` | `Mebike` | ? | ? | `zm.smali:1140-1148` |
| 26 | `SCOOTER_AIR` | — | ? | ? | `zm.smali:1179` |
| 27 | `T15` | — | 521 | 44 | `zm$O0OOOOOO00OOOOO000O0.smali:222` |
| 28 | `F_Series30` | — | 521 | 45 | `zm$O00OOO0O00O00OOO0000.smali:222` |
| 29 | `F_Series60` | — | ? | ? | `zm.smali:1377` |

*Ordinal = order of `sput-object` in `<clinit>` (`zm.smali:445,520,535,550,…`),
cross-checked against each subclass' `sput-object` target field.*
Confidence: **read** for the constant names, the marketing names of the seven
M365 ids, i2/i3 of the rows cited to a `zm$*.smali` file, and ordinal order.
Confidence: **inferred** for `?` rows (my extractor could not resolve the
register holding the literal; those rows are not load-bearing for this report).

The predicate `zm.OO0OOOOOOOO00000O0O0()` (`zm.smali:1944-2036`) is
`String.valueOf(i3) ∈ {"32","34","37","40","41","43","46"}` — i.e. **"is this a
Xiaomi M365-series model"**. It is used **only in the UI**
(`ScooterInformationActivity.smali:1210,4292`), never in parsing.

`zm.O00OOO0R0O00OOO0000(int)` (`zm.smali:1752-1817`) resolves a **board id** to a
`wm`: it matches `wm.mID` **or** `wm.mReceiveID`; `62 (0x3E)` synthesises
`wm(xm.HOST,62,62)`; anything else yields `wm(xm.UNKNOWN,0,0)`.
`wm` = `(xm mType, byte mID, byte mReceiveID)` (`wm.smali:7-13,27-31`).

`xm` (`xm.smali:65-303`, decompiled at `jadx-out-nodeobf/.../xm.java`), ordinals:
`NONE=0, CTL=1, SUB_CTL=2, BLE=3, BMS=4, BMS2=5, BMS3=6, DRIVER=7, AHRS=8, UPC=9,
ANCHOR=10, DBOARD=11, MOTOR1=12, MOTOR2=13, TAG=14, PTZ=15, KART=16, MCU=17,
BFG=18, ECU=19, HOST=20, UNKNOWN=21`.

---

## 2. The four buffers, the command that fills them, and **the units**

### 2.1 The buffers

Allocated in `iq.<init>` (`iq.smali:305-341`), all `byte[0x200]`:

| field | real name (from assert messages) | board (`xm`) that targets it | citation |
|---|---|---|---|
| `OOO000O00000OO000O0O` | **`mDataBytes`** | `CTL` (ord. 1) *and* the default branch | assert `iq.smali:1511`; write `iq.smali:1085,1163` |
| `O00O00O00O000OO0O0OO` | **`mBLEDataBytes`** | `BLE` (ord. 3) | assert `iq.smali:1396`; write `iq.smali:1133` |
| `O0O000O0O0OOO0OO00O0` | **`mDataBytesBattery`** | `BMS` (ord. 4) | assert `iq.smali:2783`; write `iq.smali:1117` |
| `O0000OOO0R0OOOOO0O0O` | *(not named in any assert)* — `mDataBytesBattery2` **inferred** | `BMS2` (ord. 5) | write `iq.smali:1101` |
| `OO00OOOOO00OO00OOOO0`, `O000000O000000000OO0`, `O000OO0OO0OOO00OO000` | three 4-byte fragments of `mDataBytes` | — | `iq.smali:2049-2100` |

The assert messages are the app's own text and use `…` (U+2026) as a literal
abbreviation, e.g. `copyOfRange(mScooter.mDa…ytes, 0x10 * 2, 0x17 * 2)` is the
full plaintext; it is not a tool truncation.

### 2.2 Which command fills a buffer

`iq.OOOOOOO00OOO0O00OOOO(um)` (`iq.smali:867`) accepts a reply only when
`um.command == READ (0x01)` or `um.command == RESPONSE_READING (0x04)`
(`iq.smali:930-947`; ids from `BaseCommand$O0OOOOOO00OOOOO000O0.smali:78,120,335`).
Every other command produces a Crashlytics log and is discarded
(`iq.smali:961-1015`). `um` is `(model, toModel, command, register, payload)`
(`um.smali:27-56`); its `toString` is `"%s%s%s: %02X @%02X %s"` — command then
register (`um.smali:141`).

### 2.3 Units — this is the load-bearing part

The write is
```java
wrap.get(dest, umVar.register * 2, umVar.payload.length);   // iq.smali:1085/1101/1117/1133/1163
```
i.e. **payload byte *k* of the reply to register *R* lands at `buffer[2R + k]`.**

Three independent confirmations that the unit is *2 bytes per register address*:

1. The developer's own assert text is compiled to the doubled value:
   `copyOfRange(mDataBytes, 0x10 * 2, 0x17 * 2)` ↔ `Arrays.copyOfRange(…, 0x20, 0x2E)`
   (`iq.smali:1509-1511` vs `iq.smali:1501-1505`), and
   `copyOfRange(mDataBytes, 0xDA * 2, 0xDA * 2 + 0x02 * 2)` ↔
   `Arrays.copyOfRange(…, 0x1B4, 0x1B8)` (`iq.smali:2053-2055` vs `iq.smali:2045-2049`).
2. The buffers are exactly `0x200` = 256 × u16 — the register address is a single
   byte on the wire (`mn.smali:322-336` reads it as `v1[2]`).
3. M365 Tools' ESC temperature lives at `byte 124 = 2 × 0x3E`, and two
   *independent* codebases put a `/10` temperature at register `0x3E`:
   this app at `iq.smali:1754-1763`, and the fork at
   `/home/kali/ScooterHacking/repo/app/src/main/java/com/m365bleapp/protocol/EscTelemetryParser.kt:40-41,112-113`.
   The same agreement holds for `0x25` (remaining mileage), `0x7C` (cruise == 1)
   and `0x7D` bit 1 (tail light) — see §4.

So every table below has **two** offset columns: the buffer byte offset (what the
code uses) and the register address (offset ÷ 2).

### 2.4 Re-parse semantics (a real behavioural trap)

`iq.OOOOOOO00OOO0O00OOOO` parses **all four buffers on every notification**
(`iq.smali:1386-3230`; the method has no early return once past the command
gate). It is a singleton (`iq.smali:20,134`). Consequences:
fields extracted from the BMS section **overwrite** the same field extracted
from the ESC section, and stale data from a buffer that is not being refreshed
persists silently. There is **no length validation per field** — the only guards
are the `assert` statements (which are disabled in a release build) and one
model-conditional re-read (§4.3).

---

## 3. The protocol codecs and the model → codec dispatch

Six codec classes extend `vm`; each is a `(sender, receiver)` pair:
`bn`, `fn`, `gn`, `kn`, `mn`, `nn`
(`apktool-out/smali/dalvik/O/O/a/b/O/c/b/O/o/{bn,fn,gn,kn,mn,nn}.smali`).

`po.O00OOO0R0O00OOO0000(Object)` (`po.smali:36`) is the factory. It switches on
`App.O00OO00OOOOOO0OOO00O().ordinal()` (the selected `zm` constant) at
`po.smali:111-140`, then on `App.O000OO0OO0OOO00OO000()` (a protocol-version int
read from the advertisement, `App.smali:139-151`) and on a Firebase RemoteConfig
flag, and stores the codec in `op.OO0O0O0OO0OOO0OO0000`.

| model ordinal(s) | version == 2 & RC flag | version == 2 | version == 1 | otherwise |
|---|---|---|---|---|
| 2 (`MINI`) | `fn` (`po.smali:749`) | `gn` (`:767`) | `mn` (`:797`) | `nn` (`:815`) |
| 5 (**`ESCOOTER` / "M365"**) | `kn` (`:638`) | `gn` (`:654`) | **`mn`** (`:684`) | `nn` (`:702`) |
| 16 (`MI_SCOOTER_PRO` / "M365PRO") | `kn` (`:282`) | `gn` (`:298`) | `mn` (`:328`) | `nn` (`:346`) |
| 17–21 (`M365_1S_DE`, `M365_Lite`, `M365_1S`, `M365_PRO2`, `M365_3`) | `kn` (`:194`) | `kn` | `mn` (`:217`) | `nn` (`:235`) |
| 14 (`SCOOTER2`), 23 (`SCOOTER_G30`) | `fn` (`:462`/`:550`) | `gn` (`:480`/`:568`) | — | `bn` (`:503`/`:591`) |
| everything else | `fn` (`:169`) | `gn` (`:367`) | `mn` (`:397`) | `bn` (`:415`) |

RemoteConfig keys: `nbsecft` (`po.smali:154`) on the ordinal-2/5/16/23 paths,
`nbsecf` (`po.smali:447,535,734`) on the ordinal-14/23/default paths, and
`ecdh_old` (`po.smali:267,623`) on the M365/M365PRO paths.

Confidence: **read** for the class per branch and the ordinal set; the branch
table was extracted from the `if-eq`/`packed-switch` chain at `po.smali:126-140`
plus the `:pswitch_data_0` block at `po.smali:1459-1466`
(`packed-switch 0x10` → 16:`pswitch_1`, 17..21:`pswitch_0`).

### 3.1 Frame layout per codec (what the buffers are filled from)

`mn` — the classic Xiaomi protocol, and the one that matters for the only
available hardware:

| direction | bytes | meaning | citation |
|---|---|---|---|
| TX | `0x55 0xAB` | magic | `mn.smali:768,773` |
| TX | `len` | `data.length + 2` | `mn.java:190,204` |
| TX | RC4(body) | body is `toModel, cmd, register, data…, FF FF FF FF` | `mn.java:192-203`, `mn.smali:230-249` |
| TX | u16 LE | checksum | `mn.java:217-222` |
| RX | `peek[2 : len-2]` | CRC16 range | `mn.java:59` |
| RX | u16 LE `peek[len-2 : len]` | checksum | `mn.java:60-63` |
| RX | RC4 of `peek[3 : len-2]` | decipher key = **SHA-256(connected MAC string) truncated to 16 bytes** | `mn.java:69`, `po.smali:52-93`, `App.smali:139-151` |
| RX → `um` | `decoded[0]`→buffer chooser, `decoded[1]`→command, `decoded[2]`→**register**, `decoded[3 : len-4]`→payload | | `mn.smali:320-336` |

Buffer chooser in `mn` (`mn.smali:251-308`, table at `:367-375`):

| `decoded[0]` | `um(model,toModel,…)` | `zm.O00OOO0R0O00OOO0000` resolves to | buffer |
|---|---|---|---|
| `0x01` | `(1, 62)` | no match → `xm.UNKNOWN` | **`mDataBytes`** (default branch) |
| `0x20`,`0x21`,`0x22` | `(62, 32/33/34)` | `wm(xm.HOST,62,62)` | **`mDataBytes`** (default branch) |
| `0x23` | `(32, 62)` | `wm(CTL,32,35)` → `xm.CTL` | **`mDataBytes`** |
| `0x24` | `(33, 62)` | `wm(BLE,33,36)` → `xm.BLE` | **`mBLEDataBytes`** |
| `0x25` | `(34, 62)` | `wm(BMS,34,37)` → `xm.BMS` | **`mDataBytesBattery`** |

(The `0x20/0x21/0x22` family carries the board in `toModel`, but `iq` looks the
board up from `model`, which is `62 (HOST)` for those — so they all land in
`mDataBytes`. This is read from the code; whether it is *intended* is not
knowable statically.)

The RC4 cipher is standard: KSA + PRGA (identity permutation mixed with the
key repeated to fill 256 bytes; output byte = `S[(S[i]+S[j]) & 0xFF] ^ in[i]`),
key length 1..256 (`vp$OO00000OOOOOOOO0000O.smali:26-148`, PRGA at `:223-260`).

Other codecs, `um(...)` arguments read directly:

| codec | TX magic | RX → `um(model, toModel, command, register, payload)` | citation |
|---|---|---|---|
| `bn` | `0x5A 0xA5` (`bn.smali:373,378`) | `um(p[3], p[4], p[5], p[6], p[7 : len-2])` | `bn.java:56` |
| `fn` | `0x5A 0xA5` (`fn.smali:1241,1246`) | `um(p[1], p[2], p[3], p[4], p[5 : len])` | `fn.smali:702-722` |
| `gn` | `0x5A 0xA5` (`gn.smali:1523,1528`) | `um(p[1], p[2], p[3], p[4], p[5 : len])` | `gn.smali:776-797` |
| `mn` | `0x55 0xAB` (`mn.smali:768,773`) | see above | `mn.smali:320-336` |
| `nn` | `0x55 0xAA` (`nn.smali:450,455`) | `um(p[3], p[4], p[5], **0x3E**, p[6 : len-2])` | `nn.smali:237-264` |
| `kn` | not established | `um(v17, v18, p[v12], p[v11], p[v13 : len-v14])` with `v17 ∈ {0x21,1,0}`, `v18 ∈ {0x20..0x23}` | `kn.smali:1614-1690` |

`nn` additionally requires the hex form of the frame to start with `"55aa"`
(`nn.smali:104,166`) and rejects `cmd==0x65 && register==0x1A`
(`nn.smali:271-279`). `kn` shares that rejection (`kn.smali:1700-1707`).

`kn` is the only codec with a byte-field constructor and crypto helpers
(`kn.smali:19-56, 810, 2415, 2490, 2569`); its frame body is **UNRESOLVED** here.

### 3.2 Checksums and validation — what the app checks

* **Checksum** = one's complement of the 16-bit sum of the bytes:
  `sum(bytes) & 0xFFFF` then `XOR 0xFFFF`, written **little-endian**
  (`vm.smali:56-89`; `vm.java` `OO00000OOOOOOOO0000O([B)S`). This is the classic
  M365 checksum.
* `bn`, `mn`, `nn` verify it on receive: they compare the computed value against
  the trailing LE u16 and **loop until it matches or the timeout expires**
  (`bn.java:49-55`, `mn.java:59-65`, `nn.smali:199-225`), throwing
  `TimeoutException` / `"Wrong Header"` (`nn.smali:293,307`) on failure.
* `mn` has the string `"Checksum missmatch"` (`mn.smali:223`) on the mismatch
  path.
* `fn` and `gn` **do not** appear to verify a checksum on receive: their payload
  runs to `len`, not `len-2` (`fn.smali:702-722`, `gn.smali:776-797`).
* **No length validation** is applied to the register payload before parsing.
  The only structural guard is the `cmd==0x65 && reg==0x1A` drop in `kn`/`nn`.
* The `assert` messages quoted throughout this report are compiled `assert`
  statements, i.e. **inert in a release build** — they document intent, they do
  not enforce it.

---

## 4. Field map per buffer

Colour key: **R** = read directly from the code (offset and width), **I** = the
*identity* of the field is inferred (from `ScooterDAO`'s named constructor, from
cross-agreement with the fork's Scootbatt-derived table, or from the app's own
getters).

### 4.1 `mDataBytes` — ESC / `xm.CTL` (and the default branch)

Filled by: a reply whose model byte resolves to `xm.CTL` (ordinal 1) or to
anything that is not `BLE`/`BMS`/`BMS2`, at `register × 2`.

| reg | buffer off | len | encoding | value | conf | citation |
|---|---|---|---|---|---|---|
| `0x10`–`0x16` | 32–46 | 14 | ASCII (`UTF-8`) | **serial number** (`O0O000OOOO0000OOOO0O`) | R/I | `iq.smali:1499-1539`; assert `:1509-1511` |
| `0x17`–`0x19` | 46–52 | 6 | ASCII | ESC firmware version string — built in `iq` but **discarded** there; used by `ScooterInformationActivity` | R/I | `iq.smali:1544-1548`; assert `:1552-1554`; `ScooterInformationActivity.smali:2589-2615`; assert `ScooterInformationActivity.smali:2599` |
| `0x1A` | 52 | 2 | u16 LE → `String.format("%04x")` | **ESC firmware version** (`O00OO00OOOOOO0OOO00O`) | R | `iq.smali:1583-1622`; format `iq.smali:1588` |
| `0x1B` | 54 | 2 | u16 LE | `O000O0000O00OOOO00O0` | R (offset) / UNRESOLVED (name) | `iq.smali:1625-1629` |
| `0x1C` | 56 | 2 | u16 LE | `OO0O0OO00000O0O00O00` | R / UNRESOLVED | `iq.smali:1632-1636` |
| `0x32`–`0x33` | 100 | 4 | u32 LE | `OO00O0O0O0000000000O` | R / UNRESOLVED | `iq.smali:1641-1651` |
| `0x34`–`0x35` | 104 | 4 | u32 LE | `OO0O0OOO00O00OOO0000` | R / UNRESOLVED | `iq.smali:1656-1661` |
| `0x25` | 74 | 2 | u16 LE via `getChar()` | **remaining range** (`OO0OO000O000OOO0O00O`, `ScooterDAO.RemainingMileage`) | R/I | `iq.smali:1666-1675`; `ScooterDAO.smali:124` |
| `0x26` | 76 | 2 | u16 LE / 10.0 → float | `OOOOOO00O00000OOO0OO`; the speed signal used by the cruise/limit rounding on "forza-like" models | R/I | `iq.smali:1678-1701`, `:2209-2235` |
| `0x2F` | 94 | 2 | u16 LE | `OO0O0O0OO0OOO0OO0000` | R / UNRESOLVED | `iq.smali:1706-1715` |
| `0x3B` | 118 | 2 | u16 LE | `OOOO0OO00OO0O0O00000`; re-read from the same offset if zero | R / UNRESOLVED | `iq.smali:1720-1748` |
| `0x3E` | 124 | 2 | u16 LE / 10.0 | **ESC temperature °C** on "forza-like" models | R/I | `iq.smali:1754-1763`, branch `:2159-2160`; fork `EscTelemetryParser.kt:40-41,112-113` |
| `0x47` | 142 | 8 | 4 × u16 LE (all discarded) | — | R | `iq.smali:1768-1780` |
| `0x68` | 208 | 2 | u16 LE → `"%04x"` | ESC "code" (`OOO00O0OO00OOO0OOOO0`) and int `OOOOOOO00OOO0O00OOOO` | R | `iq.smali:1787-1800`, `:1840` |
| `0x7B` | 246 | 2 | u16 LE | KERS setting (`OOOOO00O0O0OO00OOO0O`) | R/I | `iq.smali:1840-1863`; fork `EscTelemetryParser.kt:173-177` |
| `0x7C` | 248 | 2 | u16 LE, `== 1` | **cruise control engaged** (`OO00OOOOOO000O0OO00O`) | R/I | `iq.smali:1875-1890`; fork `EscTelemetryParser.kt:195-196` |
| `0x7D` | 250 | 2 | u16 LE bitfield | status (`O00OOO0O00O00OOO0000`); **bit 1** = tail light always on (`iq.O00OO00OOOOOO0OOO00O()`) | R/I | `iq.smali:1875-1890`; accessor `iq.smali:547-566`; fork `EscTelemetryParser.kt:225-228` |
| `0x72` | 228 | 2 | u16 LE | `OOOOO0000OO0O00000O0` | R / UNRESOLVED | `iq.smali:1890-1905` |
| `0x74` | 232 | 2 | u16 LE → `(short)(x/10)*10` | `OOOO00O0OOO0OOOOO000` | R / UNRESOLVED | `iq.smali:1905-1949` |
| `0x75` | 234 | 2 | u16 LE | **ride mode** (`OO0O00O0OO00OOO00O00`) | R/I | `iq.smali:1954-1964`; fork `EscTelemetryParser.kt:150-154` |
| `0x80` | 256 | 2 | u16 LE | `OOOOOOO0OOOOO0O00OO0` | R / UNRESOLVED | `iq.smali:1966-1978` |
| `0xB5`–`0xB6` | 362 | 4 | u32 LE | **total mileage** on "forza-like" models | R/I | `iq.smali:1980-1990` |
| `0xB0` | 352 | 2 | u16 LE | `O0OOOO0O000000OO0OO0` (overwritten at `0xB9`) | R | `iq.smali:1994-2004`, `:2324-2328` |
| `0xB1` | 354 | 2 | u16 LE | `OOO0O0OO0000O0O0000O` | R / UNRESOLVED | `iq.smali:2009-2013` |
| `0xB2` | 356 | 2 | u16 LE | `OO0OOOO00OO0O0OOO00O` (overwritten at `0xBA`) | R | `iq.smali:2018-2022`, `:2333-2337` |
| `0xB3` | 358 | 2 | u16 LE | discarded | R | `iq.smali:2027` |
| `0xB4` | 360 | 2 | u16 LE | **battery percentage** (`O00000OO0OOOOO00OOOO`) — ESC estimate | R/I | `iq.smali:2034-2038` |
| `0xDA`,`0xDC`,`0xDE` | 436,440,444 | 4 each | raw | three fragments concatenated by `iq.O0OOOOOO00OOOOO000O0()` into a 12-byte blob; `iq.OO00OO0O0OO0O00000OO()` returns true iff any byte ≠ 0 | R | `iq.smali:2043-2100`; asserts `:2055,2076,2097`; accessors `iq.smali:610-651`, `:742-813` |
| `0xB5` | 362 | 2 | u16 LE | **speed**, raw. Sign-fixed: `if (v < -4000) v &= 0xFFFF`; `if model supports rn.O0O000OO0OOO0OO000O0: v *= 100`. `O0OO000O00O0O00O0O0O = v / 1000.0f` km/h | R/I | `iq.smali:2104-2160`, `:2200-2235` |
| `0xB7`–`0xB8` | 366 | 4 | u32 LE | **total mileage / odometer** (`O0OO000O0000M0O00O00`, `ScooterDAO.TotalMileage`) for non-"forza-like" models | R/I | `iq.smali:2312-2319`; `ScooterDAO.smali:127` |
| `0xB9` | 370 | 2 | u16 LE | `O0OOOO0O000000OO0OO0` | R / UNRESOLVED | `iq.smali:2324-2328` |
| `0xBA` | 372 | 2 | u16 LE | `OO0OOOOOOOO00000O0O0` | R / UNRESOLVED | `iq.smali:2333-2337` |
| `0xBB` | 374 | 2 | u16 LE / 10.0 | **ESC temperature °C** on non-"forza-like" models | R/I | `iq.smali:2342-2346`, `:2126-2160` |

The "forza-like" predicate is `App.OO00O0R0O0000OO00OO0 ∈ {127,128,129,131,124,125,141}`
(`iq.java:153-157`) — a *different* id space from `zm`'s `i3`, taken from the
advertisement. Which real model each of those seven ids is was **not**
established.

### 4.2 `mBLEDataBytes` — `xm.BLE`

| reg | buffer off | len | encoding | value | conf | citation |
|---|---|---|---|---|---|---|
| `0x08`–`0x0B` | 16–24 | 8 | ASCII | BLE firmware version (`O0OO0O00O0OOOO0OO0OO`) | R/I | `iq.smali:1386-1424`; assert `:1396` |
| `0x14` | 40 | 2 | u16 LE | `OO0O0O0OOO0OOO00OO00` | R / UNRESOLVED | `iq.smali:1443` |
| `0x15` | 42 | 2 | u16 LE | `O0000O00OO00OOOO0000` | R / UNRESOLVED | `iq.smali:1455` |
| `0x16` | 44 | 2 | u16 LE | `O00000OOO0R0O00O0O0O` | R / UNRESOLVED | `iq.java:338` |
| `0x18` | 48 | 2 | u16 LE | `OO00O0O0O0000OO00OO0` | R / UNRESOLVED | `iq.smali:1474` |

### 4.3 `mDataBytesBattery` — `xm.BMS` (main pack)

| reg | buffer off | len | encoding | value | conf | citation |
|---|---|---|---|---|---|---|
| `0x10`–`0x16` | 32–46 | 14 | ASCII | **BMS serial** (`OO00OO0O0OO0O00000OO`) | R/I | `iq.smali:2763-2793`; assert `:2783` |
| `0x17` | 46 | 2 | u16 LE → `"%04x"` | **BMS firmware version** (`O00OO0OOO0O0OOO0OO0O`) | R | `iq.smali:2800-2845` |
| `0x67` | 206 | 2 | u16 LE → `"%04x"` | BMS firmware version, **re-read from `mDataBytes`** when the `0x17` value equals the literal `"0000"` | R | `iq.smali:2860` (compare), `:2954-2993` (re-read) |
| `0x1B` | 54 | 2 | u16 LE | `OOO0O0OO0O00O00O00O0` | R / UNRESOLVED | `iq.smali:2997-3009` |
| `0x1C` | 56 | 2 | u16 LE | `OOOOO00OOO0O00OO0O0O` | R / UNRESOLVED | `iq.smali:3012-3021` |
| `0x1D` | 58 | 2 | u16 LE | `O00O0O000O0000OO0OOO` | R / UNRESOLVED | `iq.smali:3024-3030` |
| `0x20` | 64 | 2 | packed date | day = `v & 0x1F`, month = `(v >> 5) & 0xF`, year = `(v >> 9) & 0x7F` → `SimpleDateFormat("dd/MM/yyyy")` with `/20` prefix → **production date** | R | `iq.smali:3033-3113`; formats `:2525,2547` |
| `0x31` | 98 | 2 | u16 LE | **BMS remaining capacity** (`O00O0O000OOO0000O0O0`), added to the external pack's capacity by `ScooterDAO` arg 5 | R/I | `iq.smali:3116-3125`; `iq.java:623`; `ScooterDAO.smali:118-121` |
| `0x32` | 100 | 2 | u16 LE | **battery percentage** (`O00000OO0OOOOO00OOOO`) — this value wins over the ESC one (§2.4) | R/I | `iq.smali:3128-3134` |
| `0x33` | 102 | 2 | u16 LE | **current** (`OOOOOOOOOO00O0OOO0OO`), summed with the external pack | R/I | `iq.smali:3137-3144`; sum `iq.smali:591-608` |
| `0x34` | 104 | 2 | u16 LE | **voltage** (`O0O0OO00OOOOOOO0O0OO`, `ScooterDAO.BatteryVoltage`) | R/I | `iq.smali:3145-3152`; `ScooterDAO.smali:113-116` |
| `0x35` | 106 | 1 | i8 − 20 | **BMS temperature °C** (`OO0O00OO000000000OO0`, `ScooterDAO.BatteryTemperature`) | R/I | `iq.smali:3157-3165` |
| `0x36` | 107 | 1 | i8 − 20 | second sensor (`O0OO00000OO000000O00`) | R/I | `iq.smali:3170-3178` |
| `0x3B` | 118 | 2 | u16 LE | `OO0OOO00O0O00O00OO00` | R / UNRESOLVED | `iq.smali:3183-3192` |
| `0x40`… | 128 | 30 | 15 × u16 LE | **cell voltages** (`OOOO00OOO0OO000OOO0O[15]`) | R/I | `iq.smali:3197-3223`; array `iq.smali:305-310` |

### 4.4 the fourth buffer (`xm.BMS2`) — external / second pack

Parsed into the `ym` holder (`iq.smali:85`, `ym.smali`), same offsets as the BMS
block. **The values are added to the main pack's, there is no presence flag.**

| reg | buffer off | len | encoding | value | conf | citation |
|---|---|---|---|---|---|---|
| `0x10`–`0x16` | 32–46 | 14 | ASCII | external-pack serial (`ym.OO00OOOOO00OO00OOOO0`) | R/I | `iq.smali:2395-2400` |
| `0x17` | 46 | 2 | u16 LE → `"%04x"` | external-pack firmware version (`ym.O000000O000000000OO0`) | R | `iq.smali:2413-2470` |
| `0x18` | 48 | 2 | u16 LE | `ym.OOOOOOO00OOO0O00OOOO` + `iq.OOO0O0OO0O00O00O00O0` | R / UNRESOLVED | `iq.smali:2503` |
| `0x1B`,`0x1C` | 54,56 | 2 | u16 LE | `ym.OO00O0O0O0000000000O`, `ym.O00OOO0O00O00OOO0000` | R / UNRESOLVED | `iq.smali:2470-2500` |
| `0x20` | 64 | 2 | packed date | → `ym.OO00OO0O0OO0O00000OO` | R | `iq.smali:2503-2540` |
| `0x31` | 98 | 2 | u16 LE | capacity, added to the main pack's (`ym.OO0O0OOO00O00OOO0000`) | R/I | `iq.smali:2597-2610`; `iq.java:623` |
| `0x32` | 100 | 2 | u16 LE | percentage (`ym.OO00000OOOOOOOO0000O`) — **not** used for the reported percentage | R | `iq.smali:2597-2610` |
| `0x33` | 102 | 2 | u16 LE | current, added to the main pack's (`ym.O000OO0OO0OOO00OO000`) | R/I | `iq.smali:2597-2610`; sum `iq.smali:591-608` |
| `0x34` | 104 | 2 | u16 LE | voltage (`ym.OO0OOOOOOOO00000O0O0`) | R | `iq.smali:2597-2610` |
| `0x35`,`0x36` | 106,107 | 1 | i8 − 20 | `ym.O0OOOOOO00OOOOO000O0`, `ym.O0O000OOOO0000OOOO0O` | R | `iq.smali:2597-2610` |
| `0x3B` | 118 | 2 | u16 LE | `ym.OOOOOO0OOOOO0O00OO00` | R / UNRESOLVED | `iq.smali:2692` |
| `0x40`… | 128 | 30 | 15 × u16 LE | cell voltages (`ym.O00OO00OOOOOO0OOO00O[15]`) | R/I | `iq.smali:2711-2740` |

### 4.5 Where these values end up (the naming evidence)

`iq` writes a `ScooterDAO` row at most every 300 ms when battery % > 0
(`iq.smali:3230-3299`). `ScooterDAO` is an ObjectBox entity whose field names are
**plain, unencrypted strings**, and its constructor `(S I S S S C I S S)` maps
arguments to names in order:

| arg | type | field | fed from |
|---|---|---|---|
| 1 | short | `Ampere` | `BMS 0x33 + BMS2 0x33` (`iq.smali:3276`) |
| 2 | int | `Speed` | `iq.OO0O0OOO00O00OOO0000()` (`iq.smali:3281`) |
| 3 | short | `BatteryPercentage` | BMS `0x32` (or ESC `0xB4`) (`iq.smali:3286`) |
| 4 | short | `BatteryVoltage` | BMS `0x34` (`iq.smali:3289`) |
| 5 | short | `BatteryCapacity` | `BMS 0x31 + BMS2 0x31` (`iq.smali:3292-3298`) |
| 6 | char | `RemainingMileage` | ESC `0x25` (`iq.smali:3292`) |
| 7 | int | `TotalMileage` | ESC `0xB7` (or `0xB5` on forza-like) (`iq.smali:3281` path) |
| 8 | short | `BatteryTemperature` | BMS `0x35` (`iq.smali`) |
| 9 | short | `ESCTemperature` | `0x3E` or `0xBB` / 10 (`iq.smali`) |

Names read from `ScooterDAO.smali:12-32`; order read from `ScooterDAO.smali:92-144`
(`iput` sequence) and `iq.smali:3273-3330`.

**Trip distance and trip time are not in any of the four buffers.** They live in
a separate `0x3A` "trip info" reply handled outside `iq` (`ScooterRepository`
in the fork reads `0x3A` for trip data; in M365 Tools the equivalent path is the
`Trip` entity, not `iq`). This report found **no** offset for trip distance or
trip time in `iq.smali` — treat them as **not established here**.

---

## 5. Serial number, second battery, and model dependence — direct answers

* **Serial number**: ESC serial = `mDataBytes[32..46)` = registers `0x10`–`0x16`,
  14 ASCII bytes (`iq.smali:1499-1539`). Battery serial =
  `mDataBytesBattery[32..46)` (`iq.smali:2763-2793`). External-pack serial =
  buffer 4 `[32..46)` (`iq.smali:2395-2400`). BLE has no serial in the parsed
  offsets.
* **Second battery**: yes — buffer 4, board type `xm.BMS2`, parsed into `ym`.
  **Detection is structural, not value-based**: `zm`'s board list for the model
  must contain a `wm` whose type is `xm.BMS2`, and then the payload's
  `decoded[0]` must resolve to it (`iq.smali:1093-1103`). Models whose board list
  has an `xm.BMS2` entry: `MARK2` (ord. 4), `MARK3` (13), `SCOOTER2` (14),
  `SCOOTER2_P` (15), `MI_SCOOTER_1S` (19) — `zm.java` static block lines 117,
  235, 250, 265, 373. There is **no** `hasSecondBattery` boolean anywhere in the
  parse path, and no check that the pack is non-zero before the values are added.
* **Model-dependent parsing branches actually in `iq`:**
  1. `App.OO00O0R0O0000OO00OO0 ∈ {124,125,127,128,129,131,141}` — "forza-like":
     speed source (`0x26/10 ×1000` vs `0xB5/1000`), odometer source
     (`0xB5` u32 vs `0xB7` u32), ESC temperature source (`0x3E` vs `0xBB`).
     `iq.java:153-157, 183-233, 452-483`.
  2. `zm.OO0O0OOO00O00OOO0000(rn.O0O000OO0OOO0OO000O0)` — multiplies raw speed by 100
     when the model's `qn` list declares that register group (`iq.java:443-445`).
  3. `App.O0000O00OO00OOOO0000 == 1 || escCode ∈ {"0081","0100"}`
     (`iq.java:159-162`, strings at `iq.smali:511,525`) — used for
     `iq.O000OO0OO0OOO00OO000()`.
  4. The BMS-version `"0000"` fallback (§4.3).
  5. Buffer selection by board type (§2.1, §3.1) — the only branch keyed on the
     `zm`/`xm` enums that changes *which bytes are read*.

---

## 6. Comparison with the fork (M365-Rokid-HUD)

Fork sources read: `app/src/main/java/com/m365bleapp/protocol/EscTelemetryParser.kt`,
`.../BmsTelemetryParser.kt`, `.../PlaintextTelemetryMapper.kt`,
`.../repository/MotorInfoParser.kt`, `.../protocol/ScooterModelRegistry.kt`,
`ninebot-ble/src/model/mod.rs`.

**Where the two agree** (this is the strongest evidence in this report, because
the fork's values come from a *different* app, Scootbatt 1.9.2):

| quantity | M365 Tools | fork | note |
|---|---|---|---|
| register addressing | `buffer[reg*2]`, payload byte *k* = register *R+k* | `payload[k]` of the reply to register `R` | **same convention**, different container |
| remaining range | `0x25`, u16 | `0x25`, u16/100 (Java) / u16/10 (Rust) | fork `EscTelemetryParser.kt:34-35,101`; `ninebot-ble/src/model/mod.rs:406` |
| ESC temperature | `0x3E`, u16/10 (forza-like); `0xBB`, u16/10 (others) | `0x3E`, i16/10; byte 22 = `0xBB` | fork `:40-41,112-113`; `MotorInfoParser.kt:57` |
| cruise engaged | `0x7C`, `== 1` | `0x7C`, `== 1` | fork `:195-196` |
| tail light always on | `0x7D` bit 1 | `0x7D` bit 1 | fork `:225-228` |
| ride mode | `0x75`, u16 | `0x75`, u16 | fork `:150-154` |
| KERS | `0x7B`, u16 | `0x7B`, u16 | fork `:173-177` |
| speed | `0xB5`, u16, `/1000` km/h for the plain M365 | `0xB5` at payload byte 10, `/1000` | fork `:28-32,87-89` |
| battery % | `0xB4`, u16 (ESC) / `0x32` (BMS) | payload byte 8 of the `0xB0` read | `MotorInfoParser.kt:53` |
| odometer | `0xB7`, u32, `/1000` | payload byte 14, u32 `/1000` | `MotorInfoParser.kt:61` |
| checksum | `~sum(bytes) & 0xFFFF`, LE | `FrameCodec` | — |
| M365 board addresses | `CTL mID 0x20 / mReceiveID 0x23`, `BLE 0x21/0x24`, `BMS 0x22/0x25` | `XIAOMI_BOARDS` = ESC `0x20/0x23`, BMS `0x22/0x25` | `zm$OOOOOOO0OOOOO0O00OO0.smali:46-82`; `ninebot-ble/src/model/mod.rs:294-297` |

**Where they disagree — flag for the parent:**

1. **M365 Tools resolves the fork's own flagged `0xB0` offset discrepancy — in
   favour of the fork's *Java* parser.** The fork records in
   `ninebot-ble/src/model/mod.rs:353-385` that the shipped parser "disagrees by
   exactly 2 bytes" and that only a capture can settle it. M365 Tools settles it
   statically, because it reads the same registers through the `×2` map:

   | field | M365 Tools register (byte off = 2×reg) | payload byte in a `0xB0` read | fork Java `MotorInfoParser` | fork Rust `M365` `FieldSpec.offset` |
   |---|---|---|---|---|
   | battery % | `0xB4` (`iq.smali:2034-2038`, off 360) | **8** | `getShort(8)` (`:53`) | `4` (`mod.rs:400`) |
   | speed | `0xB5` (`iq.smali:2104-2160`, off 362) | **10** | `getShort(10)` (`:88`) | `6` (`mod.rs:401`) |
   | avg speed | `0xB6` (`iq.smali:2308`, off 364 — read then discarded) | **12** | `getShort(12)` (`:60`) | `8` (`mod.rs:402`) |
   | odometer | `0xB7`–`0xB8` u32 (`iq.smali:2312-2319`, off 366) | **14** | `getInt(14)` (`:61`) | `10` (`mod.rs:403`) |
   | temperature | `0xBB` (`iq.smali:2342-2346`, off 374) | **22** | `getShort(22)` (`:57`) | `18` (`mod.rs:404`) |

   Five for five: M365 Tools puts battery, speed, average speed, odometer and
   temperature at payload bytes **8, 10, 12, 14, 22** of a 32-byte `0xB0` read —
   exactly the fork's Java indices, and exactly 4 payload bytes (2 registers)
   above the Rust profile's `offset` values. The Rust `FieldSpec.offset` is used
   as a **byte index into the payload**
   (`ninebot-ble/src/model/mod.rs:208-213, 258-259`), so on this evidence the
   `M365` profile's offsets should be `8, 10, 12, 14, 22`, and the "2-byte
   shift" note understates the gap by half. Confidence: **the five register
   identities are read from M365 Tools and agree with the fork's Java parser and
   with the fork's Rust doc-comment `0xB0`-block listing**; what remains
   unproven is which of the two parsers a real M365 actually feeds — but the
   two now agree with each other, which removes the fork's stated ambiguity.
2. **Remaining range scaling.** M365 Tools reads `0x25` as a bare `char`/u16
   (`iq.smali:1666-1675`) with no division in the parser; the fork divides by
   100 in Java (`EscTelemetryParser.kt:34-35,101`) and by **10** in Rust
   (`ninebot-ble/src/model/mod.rs:406`, `U16Scaled(10.0)`). The fork's two
   scalings disagree with each other; M365 Tools does not disambiguate (the
   scaling is applied later, in the UI, which this report did not chase).
3. **Firmware version registers.** M365 Tools reads `%04x` of a **u16 at
   `0x1A`** (ESC, `iq.smali:1583-1622`) and a **u16 at `0xBMS 0x17`** (BMS,
   `iq.smali:2800-2845`), plus a separate 6-byte ASCII string at `0x17`–`0x19`
   used by `ScooterInformationActivity.smali:2589-2615`. The fork's
   `firmwareVersions` reads three bytes at `0x66`
   (`EscTelemetryParser.kt:232-253`). Different registers for the same idea —
   worth reconciling against a capture.

**Models M365 Tools can talk to that the fork currently withholds.**
The fork's `ScooterModel` enum has 15 members
(`ScooterModelRegistry.kt:112-250`); six have real capabilities (M365, M365_PRO,
M365_PRO2, MI_1S, MI_LITE and — per the Rust table — the same Xiaomi layout),
`MI3` and the eight Ninebot/Segway families are `ModelCapabilities.NONE`
(`ScooterModelRegistry.kt:90-101,214-247`).

| fork entry | fork state | what M365 Tools has | what the fork would need |
|---|---|---|---|
| `MI3` (`Mi3`) | `NONE` (`:214-219`) | `zm` ordinal 21, marketing name `M365_3`, model id `46`; codec `kn`/`mn`/`nn`; buffers identical to the other M365 ids | one capture on a Mi 3 to confirm which codec the advertisement's `protocol_version` selects; the register offsets are already shared with the M365 profile |
| `NINEBOT_T15` (`NinebotT15`) | `NONE` (default, `:231`) | `zm` ordinal 27, id `44`, codec `fn`/`gn`/`mn`/`bn` | a capture **and** the `fn`/`gn`/`bn` frame layouts, which are only partly recovered here |
| `NINEBOT_MAX_G30` | `NONE` (`:228`) | generic paths only; `zm` id space has no G30-specific constant, it falls to the default branch | the advertisement's `protocol_version` + `scooter_type` values for a G30, then a capture |
| `NINEBOT_ESX` | `NONE` (`:227`) | generic paths only | same |
| `NINEBOT_E_SERIES`, `NINEBOT_F_SERIES`, `NINEBOT_MAX_G2`, `NINEBOT_F2`, `NINEBOT_D_SERIES` | `NONE` (`:229-247`) | `F_Series30`/`F_Series60` (ord. 28/29) and `SCOOTER_G30` (23) are named in `zm`, no field map recovered | the four-buffer model means the fork must first adopt the "which board answers" concept before any per-model map is useful |
| **`M365_1S_DE`** (ord. 17) | *not present at all* as a separate fork entry — `MI_1S` covers it | distinct `zm` constant, id `37`, one of the seven "Xiaomi M365-series" ids | a one-line addition to the registry; shares the M365 register block |

The single largest structural gap is not a register: it is that **M365 Tools
keys its parse on the answering board** (`ESC` / `BLE` / `BMS` / `BMS2`) and
keeps four separate 512-byte buffers, whereas the fork keys on the polled
register and treats each reply payload as self-contained. Any Ninebot-family
support has to reproduce the board→buffer step first
(`iq.smali:1060-1170`), because a Ninebot reply's *register byte alone* does not
say which subsystem it belongs to.

---

## 7. Unverified / needs hardware

Everything below is **not** confirmed and must not be implemented as fact.

1. **Any Ninebot / Segway conclusion.** `kn`, `fn`, `gn`, `bn` frame layouts are
   only partly recovered; `kn`'s frame body and `fn`/`gn`'s checksum behaviour
   are marked UNRESOLVED above. Only a Xiaomi M365 can ever be tested here.
2. **The `zm` ordinals ≥ 22.** `WILD_STEEL_DUST`, `SCOOTER_G30`, `STEELDUST`,
   `MEBIKE`, `SCOOTER_AIR`, `T15`, `F_Series30`, `F_Series60` — their ids,
   board lists and codec choice are read, but the field semantics are not.
3. **The seven "forza-like" `scooter_type` ids** `{124,125,127,128,129,131,141}`.
   Which models they are was not established, so *which* branch a given real
   scooter takes is unknown. This changes speed, odometer and temperature
   sources — a wrong branch silently produces plausible numbers. **Do not
   implement either branch on the strength of this report alone.**
4. **Every "R / UNRESOLVED" row** in §4 — the offset and width are read from the
   code, the *meaning* is not. There are 25 such rows; they are named by their
   field symbol only, deliberately.
5. **The unit claim.** `buffer[R*2]` is read from the code and cross-validated at
   `0x3E`, `0x25`, `0x7C`, `0x7D`, `0xB5`, `0xB7`; it is not confirmed by a
   capture. If it were wrong, every offset in §4 would be wrong by a factor of
   two — which is exactly the failure mode that is worth one hardware session.
6. **The `0xB0`-block base.** M365 Tools' five-register agreement (§6.1) is
   strong but is *still* an agreement between two static sources, not a capture.
   One 32-byte `0xB0` reply from the M365 at `C7:B8:DC:3B:A1:B2` decides it, and
   that single capture also validates the `×2` mapping for the whole ESC block.
7. **Trip distance / trip time** — no offset found in `iq`; the values are
   presumably carried by a different reply that this report did not locate.
8. **RC4 key derivation.** The key is `SHA-256(<connected MAC string>)[0..16)`
   (`po.smali:52-93`), but the exact string form (case, separators) was not
   confirmed, and it is recomputed per connection.
9. **The `0x20/0x21/0x22` `mn` family** lands in `mDataBytes` because `iq` looks
   the board up from `model` (=62) rather than `toModel`. Read from the code;
   whether that is intended, or a bug the app gets away with, is unknown.
10. **`mDataBytesBattery2`** is my own name for `O0000OOO0R0OOOOO0O0O`; the app
    never names it in an assert message.

Clean-room note: this report contains offsets, register addresses, enum names and
algorithm structure only. Nothing here is a copy of the app's code or tables.
