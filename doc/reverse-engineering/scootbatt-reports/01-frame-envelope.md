# 01 — Frame / Envelope Layer, Action & Direction Constants

**Target:** `com.basse.scootbatt` 1.9.2 (versionCode 136), R8-obfuscated, decompiled with jadx into
`/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/`.
**Scope of this report:** the shared constant enum `p000.xp0`, the frame built by
`ScooterRequest.construct()`, the wire-envelope wrapper that turns it into bytes on the air, the
direction semantics, the reassembly of multi-chunk notifications, and the MTU/chunk-size plumbing.
Peripheral telemetry decoding is out of scope (only used as cross-check evidence).
**Method:** static only. Every claim is annotated with `file:line`. Where jadx output is mangled or
where I had to interpret, the claim is marked **[inferred]**; anything that cannot be settled
statically is listed in §9.

All paths below are relative to `jadx-out/sources/` unless stated otherwise.

---

## 1. `p000.xp0` — the shared direction/action constant enum

### 1.1 Evidence: the static initializer *is* the answer

`p000/xp0.java:43-67` is the class initializer that R8 left intact. The constructor is
`xp0(String name, int ordinal, byte value)` and stores the **third** argument in the single instance
field `f34564a` (`p000/xp0.java:70-72`):

```java
44:  xp0 xp0Var  = new xp0("MASTER_TO_SCOOTER", 0, (byte) 32);
46:  xp0 xp0Var2 = new xp0("MASTER_TO_BLE", 1, (byte) 33);
47:  xp0 xp0Var3 = new xp0("MASTER_TO_BLE_READ", 2, (byte) 36);
48:  xp0 xp0Var4 = new xp0("MASTER_TO_BATTERY", 3, (byte) 34);
50:  xp0 xp0Var5 = new xp0("MASTER_TO_EXTERNAL_BATTERY", 4, (byte) 35);
52:  xp0 xp0Var6 = new xp0("READ", 5, (byte) 1);
54:  xp0 xp0Var7 = new xp0("WRITE", 6, (byte) 2);
56:  xp0 xp0Var8 = new xp0("WRITE_NO_REPLY", 7, (byte) 3);
57:  xp0 xp0Var9 = new xp0("SHFW_READ", 8, (byte) 49);
59:  xp0 xp0Var10 = new xp0("SHFW_WRITE_NO_REPLY", 9, (byte) 51);
60:  xp0 xp0Var11 = new xp0("SHFW_WRITE", 10, (byte) 50);
```

The enum constant *names* also survive verbatim in the dex string table (verified by scanning
`classes*.dex` of `xapk/com.basse.scootbatt.apk`: `MASTER_TO_`, `MASTER_TO_SCOOTER`,
`MASTER_TO_EXTERNAL_BATTERY`, `MASTER_TO_BLE_READ`, `SHFW_WRITE_NO_REPLY` each occur).

### 1.2 Complete constant table

`static field` = the field R8 kept for that constant. R8 dropped the fields of the constants the app
never references, so a `—` means the constant exists in `values()` but **no app code can reach it by
field access**.

| # | Name | Byte (dec / hex) | static field | Role in the app | Usage evidence |
|---|---|---|---|---|---|
| 0 | `MASTER_TO_SCOOTER` | 32 / `0x20` | `f34556e` | **direction** — ESC / controller | `p000/xp0.java:44-45`; used as `direction` at `p000/C1138os.java:95` … — 47 of the 65 call sites (45 literal + 2 variable, App. A) |
| 1 | `MASTER_TO_BLE` | 33 / `0x21` | — (dropped) | **direction** — BLE MCU | `p000/xp0.java:46`; unreferenced, but the literal `33` is used in the hand-built auth frames `ScooterFragment.java:1472,1491` |
| 2 | `MASTER_TO_BLE_READ` | 36 / `0x24` | — (dropped) | **direction** (name only) | `p000/xp0.java:47`; no reference anywhere in the app |
| 3 | `MASTER_TO_BATTERY` | 34 / `0x22` | `f34557g` | **direction** — internal BMS | `p000/xp0.java:48-49`; `p000/C1252rv.java:61-77` (9 of 65 sites) |
| 4 | `MASTER_TO_EXTERNAL_BATTERY` | 35 / `0x23` | `f34558h` | **direction** — external / eBMS | `p000/xp0.java:50-51`; `p000/qv0.java:60-76` (9 of 65 sites) |
| 5 | `READ` | 1 / `0x01` | `f34559j` | **action** | `p000/xp0.java:52-53`; `p000/C1138os.java:95` etc. (40 of 65 sites) |
| 6 | `WRITE` | 2 / `0x02` | `f34560l` | **action** | `p000/xp0.java:54-55`; `p000/al0.java:40` etc. (13 of 65 sites) |
| 7 | `WRITE_NO_REPLY` | 3 / `0x03` | — (dropped) | **action** | `p000/xp0.java:56`; no reference anywhere in the app |
| 8 | `SHFW_READ` | 49 / `0x31` | `f34561m` | **action** — SHFW memory read | `p000/xp0.java:57-58`; `p000/C1138os.java:107`, `p000/C1524z4.java:45-51`, `p000/d83.java:18-22` (8 of 65 sites) |
| 9 | `SHFW_WRITE_NO_REPLY` | 51 / `0x33` | — (dropped) | **action** | `p000/xp0.java:59`; no reference anywhere in the app |
| 10 | `SHFW_WRITE` | 50 / `0x32` | `f34562n` | **action** — SHFW memory write | `p000/xp0.java:60-61`; `p000/wv3.java:25`, `p000/kd4.java:52,58`, `p000/ed2.java:97` (4 of 65 sites) |

Byte values are cross-checked against real usage, not merely read from the initializer:

* `32` (= `MASTER_TO_SCOOTER`) is passed as the `direction` argument in 47 of the 65 call sites
  (counting the two sites where a local variable holds the constant) and is never passed as `action`
  (App. A) — consistent with its name.
* `34`/`35` are used as directions only for battery register sets, and the two register sets are
  identical (`p000/C1252rv.java:61-77` vs `p000/qv0.java:60-76`) — consistent with "same BMS, two
  addresses".
* `1`/`2` are used as `action` only, never as `direction`/`position` (App. A).
* The receive-side dispatcher keys its handler maps on exactly the direction bytes `32/34/35/37`
  (`ScooterFragment.java:1632-1637,1673,1711`), which are the same values the request builders use.

### 1.3 The companion array `f34555d`, the "map" `f34554c`, and name↔byte conversion

| Field | Declaration / init | What it really is |
|---|---|---|
| `f34563p` | `p000/xp0.java:37,62-63` | the synthetic `$VALUES` array (ordinal order) |
| `f34555d` | `p000/xp0.java:13,66` — `xp0[] … = (xp0[]) nk1.m11351F(new f51(f34563p), new xp0[0])` | **a second, independent copy of the constants in ordinal order.** `nk1.m11351F` is `Collection.toArray(T[])` (`p000/nk1.java:497-530`) and `f51` is a `List` view over the enum array (`p000/f51.java:16-30`). It is used **only** by `construct()` for byte→constant lookup. |
| `f34554c` | `p000/xp0.java:10,65` — `new cr8(21)` | **not a map and not used.** `cr8` is an R8-merged synthetic-lambda holder (`p000/cr8.java:38-44`: `f4680a` = lambda id); no `cr8` method contains a `case 21` (`p000/cr8.java:105,119,220` – cases 12/13/15/17 only). All five read sites are dead local assignments (`p000/an3.java:370`, `p000/dp3.java:334`, `p000/yn3.java:333`, `p000/vo3.java:130`, plus the synthetic null-check the compiler emits inside `construct()`, `ScooterRequest.java:73-75`). |

Consequently the conversions are:

* **name → byte**: plain Java enum lookup `xp0.valueOf(String)` (`p000/xp0.java:75-77`, i.e. `Enum`'s
  internal name map) followed by reading `f34564a`. No app code does this at runtime.
* **byte → constant**: linear scan of `f34555d`, comparing `f34564a`
  (`ScooterRequest.java:76-108`). If the byte is not found, the compiler-inserted
  `xp0Var2.getClass()` on a `null` throws `NullPointerException`
  (`ScooterRequest.java:91` and `:107`) — there is no graceful fallback.
* **constant → name**: `Enum.name()` / `toString()` of the real enum (the class is a genuine Java
  enum even though jadx lost the `enum` modifier; `valueOf`/`values` are present at
  `p000/xp0.java:75-82`).

Because `construct()` re-validates both the direction and the action against `f34555d` and looks the
byte up *from the enum instance*, the byte actually emitted is always the constant's own byte — the
table can never "drift". **[inferred]** this double lookup is a Kotlin `when`/`require` remnant, but
its observable effect is only the NPE-on-unknown-value guard.

---

## 2. The frame: `construct()` and everything that happens after it

### 2.1 Inner frame produced by `ScooterRequest.construct()` — no checksum here

`com/basse/scootbatt/models/scooter/helpers/ScooterRequest.java:67-112`:

```java
70:  byte[] bArr = new byte[this.payload.length + 4];
72:  bArr[0] = 62;                      // 0x3E  '>'  — constant
92:  bArr[1] = xp0Var2.f34564a;         // direction byte  (0x20/0x21/0x22/0x23)
108: bArr[2] = xp0Var.f34564a;          // action byte     (0x01/0x02/0x31/0x32)
109: bArr[3] = this.position;           // register / parameter address
110:  AbstractC1539zj.m18538l0(this.payload, 4, bArr);   // payload copied at offset 4
```

**Inner frame = `3E | direction | action | position | payload…`, length = `payload.length + 4`.**
There is **no** length byte, **no** terminator and **no** checksum/CRC inside `construct()`.
Constructor argument order is `(direction, action, position, payload)`
(`ScooterRequest.java:19-27`), and `position` is a single signed byte.

### 2.2 The wire envelope is added by `pe4.m12550k(byte[])`

`construct()` output is **never** handed to the BLE writer directly. It is wrapped by
`pe4.m12550k(byte[])` (`p000/pe4.java:1034-1300`), whose branch is selected by the protocol variant
enum `xq0` (`p000/xq0.java:25-34`: `Ninebot`=ordinal 0, `NinebotCrypto`=1, `Xiaomi`=2,
`XiaomiCrypto`=3). `pe4` is constructed with `(model, xq0)` (`p000/pe4.java:1719-1725`), and the
variant is chosen in `ScooterFragment.java:900`:

```java
m3291j0.f19861b = scooter.getUseCrypto() ? xq0.f34611c
                : scooter.isXiaomi()     ? xq0.f34612d
                :                          xq0.f34610a;
```

| Variant (`xq0`) | On-air layout produced by `pe4.m12550k` | Total length | Evidence |
|---|---|---|---|
| 0 `Ninebot` | `5A A5 │ len=payload │ 3E dir action pos │ payload │ sum_lo sum_hi` | `payload+9` | `p000/pe4.java:1041-1056` |
| 1 `NinebotCrypto` | `5A A5 │ len=payload │ AES( 3E dir action pos payload ) │ 00 00 │ sum_lo sum_hi │ ctr_hi ctr_lo` | `payload+13` | `p000/pe4.java:1058-1142` — buffer assembled at `:1071-1091` (ciphertext at 3, zeros `:1089-1090`, crc `:1085-1088`, counter `:1132-1134`) |
| 2 `Xiaomi` | `55 AA │ len=payload+2 │ dir │ action │ pos │ payload │ sum_lo sum_hi` | `payload+8` | `p000/pe4.java:1143-1182` |
| 3 `XiaomiCrypto` | `55 AB │ len │ ctr_lo ctr_hi │ AES-CCM(dir‖action‖pos‖payload‖rand) │ tag(4) │ sum_lo sum_hi` | `len+16` | `p000/pe4.java:1184-1297` (header `:1245-1246`, AES call `:1278`, checksum `:1283-1295`); receive side `ScooterFragment.java:988-1003` (`AES/CCM/NoPadding`, nonce built from `frame[3]`,`frame[4]`) |

Notes on the table:

* For variant 2 the `0x3E` marker is **dropped** and the direction byte moves to offset 3
  (`p000/pe4.java:1149-1170`). The Xiaomi "reply" forms `0x23/0x24/0x25` are produced only in the
  fallback branch that reads the *inner-frame* byte 0 — see §3.3 [inferred].
* For model `"z10"` the Xiaomi direction byte is forced to `17` (`0x11`);
  `p000/pe4.java:1166-1168`.
* Variant 1's `len` byte stays in clear and equals the payload length (`p000/pe4.java:1064`),
  because only the 4-byte inner header + payload are encrypted (`p000/pe4.java:1073-1084`).

### 2.3 Checksum findings (the question "is a checksum appended, and where?")

**Yes — a 2-byte checksum is appended, but not by `construct()` and not by the BLE writer. It is
appended by `pe4.m12550k()`.** Concretely:

* **Algorithm:** 16-bit **one's complement of an 8-bit-wide sum** — *not* CRC-16/CCITT, *not*
  CRC-16/IBM. `sum = Σ byte[i] & 0xFF`; `chk = sum ^ 0xFFFF`; emitted **little-endian**
  (low byte first).
  * variant 0: `p000/pe4.java:1049-1055`
    (`for (i5=2; i5<length+3; i5++) i3 += bArr3[i5]&255; i6 = i3 ^ 65535;`)
  * variant 2: `p000/pe4.java:1174-1181`
  * variant 3: `p000/pe4.java:1283-1295` (byte-wise sum then `~`); the same algorithm in the
    Xiaomi→Ninebot converter `p000/oh3.java:429-435`
  * variant 1: sum over the **plaintext** inner frame, `j2 = ~j` (`p000/pe4.java:1077-1082`)
  * (caveat: the sum is *not* truncated to 16 bits before the `^ 0xFFFF`, so it would be wrong for
    frames whose byte sum exceeds `0xFFFF`. The app's largest payload is 32 bytes (BMS cell voltages,
    `p000/C1252rv.java:63`), i.e. 37 summed bytes ⇒ `sum ≤ 9435`, so the caveat never triggers;
    SHFW writes carry 2-byte payloads, `p000/xo3.java:68-116`, `p000/vo3.java:186`.)
* **Covered range:** from the **length byte** through the **last payload byte**, i.e. the `5A A5` /
  `55 AA` / `55 AB` prefix is excluded, and the checksum bytes themselves are excluded.
  `p000/pe4.java:1050` (start index 2), `p000/pe4.java:1176` (start index 2).
* **Position:** immediately after the payload for variants 0 and 2 (`p000/pe4.java:1054-1055`,
  `:1180-1181`). For variant 1 it sits *two bytes after* the payload with two zero bytes before it
  (`p000/pe4.java:1085-1090`) and the 2-byte crypto counter **after** it (`p000/pe4.java:1132-1134`)
  — i.e. the checksum is not the last two bytes of a variant-1 frame. For variant 3 it is the last
  two bytes (`p000/pe4.java:1283-1295`).
* **There is no CRC-16 implementation in the app package.** The only CRC-polynomial-looking tables
  in the decompiled tree belong to a bundled PDF/report library (`p000/gb7.java:72`,
  `f9006g`/`f9007h` = CRC-32/CCITT tables, used by `yf5`/`ck5`), and the only `"CRC check failed"`
  string is in DTS audio code (`p000/ej5.java:281`). A grep for `0x1021/0xA001/0x8408/0xEDB88320`
  finds no scooter-protocol user. The only one's-complement checksum sites in the whole app are
  `p000/pe4.java:1053`, `p000/pe4.java:1179` and `p000/oh3.java:433`.
* **The app never verifies an inbound checksum.** The receive path only validates the 2-byte prefix
  and the declared length (`p000/hl2.java:59-93`), and for variant 1 the last two bytes are treated
  as a 16-bit counter, not a checksum (`ScooterFragment.java:1105-1110`).

### 2.4 The receive-side envelope (Ninebot reply form)

`ScooterFragment.m3299r0(byte[])` — the single dispatcher for all decoded replies — reads:

```java
1590:  int i  = bArr[2];   // payload length
1591:  int i2 = bArr[3];   // direction / address
1592:  int i3 = bArr[4];   // = 0x3E (constant marker, never used)
1593:  int i4 = bArr[5];   // action (switch selector)
1594:  int i5 = bArr[6];   // position = register (map key)
1599:  System.arraycopy(bArr, 7, bArr2, 0, i);   // payload handed to the handler
```

so the **reply frame is `5A A5 │ len │ addr │ 3E │ action │ position │ payload(len) │ sum_lo sum_hi`**
(total `len+9`, matching `p000/hl2.java:68`). This is confirmed independently of the (partly mangled)
dispatcher by two auth-handshake expectations in the same file:

* request `3E 21 5B 00` (`ScooterFragment.java:1472`) ⇒ expected reply
  `5A A5 30 21 3E 5B …` (`ScooterFragment.java:1129`);
* request `3E 21 5C 00 ‖ 16-byte token` (`ScooterFragment.java:1491`) ⇒ expected reply
  `5A A5 00 21 3E 5C 01` (`ScooterFragment.java:1143`).

**Request vs. reply: the `0x3E` marker's position encodes the direction of travel**
(`3E addr` = master→device, `addr 3E` = device→master). This is proven by the Xiaomi→Ninebot
converter `oh3.m11880i` (`p000/oh3.java:393-437`):

```java
404:  byte b = bArr[3];
405:  if (b == 32) { bArr2[3] = 62; bArr2[4] = 32; }   // 0x3E then addr  = request form
408:  else if (b == 33) { bArr2[3] = 62; bArr2[4] = 33; }
411:  else if (b == 34) { bArr2[3] = 62; bArr2[4] = 34; }
414:  else if (b == 35) { bArr2[3] = 32; bArr2[4] = 62; }  // addr then 0x3E = reply form
417:  else if (b == 36) { bArr2[3] = 33; bArr2[4] = 62; }
420:  else if (b == 37) { bArr2[3] = 34; bArr2[4] = 62; }
```

The converter is applied on the receive paths (`ScooterFragment.java:1035-1040` for XiaomiCrypto,
`:1100-1104` for Xiaomi) so that all
replies reach `m3299r0` in the same "addr, `0x3E`" shape.

### 2.5 Action bytes that are **not** in the enum

The auth handshake on NinebotCrypto scooters uses two action bytes with no enum constant:

| Action byte | Frame (inner form) | Meaning |
|---|---|---|
| `0x5B` (91) | `3E 21 5B 00` | auth step 1 — read the 14-byte token (`ScooterFragment.java:1472`, literal bytes in the jadx raw dump; reply matched at `ScooterFragment.java:1129`, 14-byte token extracted at `:1607`) |
| `0x5C` (92) | `3E 21 5C 00 ‖ 16-byte token` | auth step 2 — write the derived token (`ScooterFragment.java:1491`); its presence is also checked on the outgoing wrapped frame at `p000/pe4.java:1136` |

These are hard-coded array literals, hence invisible to §1; the enum's action set is complete for
*register* traffic only. The reply dispatcher switches on the reply action byte with explicit
`case 91:` / `case 92:` (`ScooterFragment.java:1601-1602`, `:1758`) and compares against `4`, `1`,
`52`, `49`, `57` in the same (jadx-flattened) switch region (`ScooterFragment.java:1604-1620`,
`:1770-1780`) — the exact role of those five values could not be recovered reliably (see §9).

---

## 3. Direction semantics

### 3.1 Which byte means which direction

The byte in the `direction` slot is an **address**, not a direction flag: it names the device the
frame is addressed to / coming from. `0x3E` placement (request vs reply, §2.4) carries the actual
direction of travel.

| Byte | Name in `xp0` | Device | Evidence |
|---|---|---|---|
| `0x20` | `MASTER_TO_SCOOTER` | ESC / controller ("master" in Ninebot terms) | `p000/xp0.java:44`; `p000/C1138os.java:95-131`, `p000/un0.java:48-52`, `p000/c70.java:43-45`, `p000/vc2.java:41-43`, `p000/d83.java:18-22`, `p000/ed2.java:97`, `p000/wv3.java:25` |
| `0x21` | `MASTER_TO_BLE` | BLE MCU / dashboard | `p000/xp0.java:46`; hand-built auth frames `ScooterFragment.java:1472,1491` (literal `33`) |
| `0x22` | `MASTER_TO_BATTERY` | internal BMS | `p000/xp0.java:48`; `p000/C1252rv.java:61-77` |
| `0x23` | `MASTER_TO_EXTERNAL_BATTERY` | external / eBMS (2nd pack) | `p000/xp0.java:50`; `p000/qv0.java:60-76`; chosen over `0x22` by the "external battery present" flag `uo3.f30567u0` (`p000/jh4.java:414-419`); `Scooter.isEligibleForExternalBattery()` ⇔ model `esx`/`e` (`com/basse/scootbatt/global/Scooter.java:162-164`) |
| `0x24` | `MASTER_TO_BLE_READ` | not used; in the Xiaomi numbering this is the *reply* form of `0x21` | `p000/xp0.java:47`; `p000/oh3.java:417-419` maps Xiaomi `36 → (0x21, 0x3E)` |
| `0x25` | *(no name)* | Xiaomi reply form of `0x22` (BMS reply); in the Ninebot dispatcher it falls through to the ESC handler map (see §3.3) | `ScooterFragment.java:1639`, `p000/oh3.java:420-422` [inferred for the Ninebot meaning] |
| `0x11` | — | forced direction for Xiaomi model `"z10"` | `p000/pe4.java:1166-1168` |

Additional findings:

* The four maps that hold reply handlers are keyed by `Pair(action=register, direction)`
  (`p000/m13.java:10-17` is Kotlin `Pair`) and are populated per UI page, e.g.
  `new m13((byte) 16, (byte) 34) → new C1252rv(vm, 5)` (`p000/an3.java:370-378` for BMS),
  `new m13((byte) 16, (byte) 35) → new qv0(vm, 6)` (`p000/yn3.java:333-341` for eBMS),
  `new m13((byte) -38, (byte) 32) → new C1138os(vm, 17)` (`p000/dp3.java:334-342` for ESC).
  The dispatcher's own maps are keyed by the bare register byte and are filled per direction
  (`p000/ao3.java:186-224`): `f4047X2` ← 22 ESC registers (`0xDA,0xBA,0xB5,0xB9,0xB2,70,16,26,
  102,27,37,41,62,124,125,123,47,117,50,52,71,83`), `f4048Y2` ← the SHFW registers
  (`0x39,0xFF,0xB2,1,17,60,76,119,0x87`), `f4049Z2` ← the 9 internal-BMS registers
  (`ScooterFragment.java:475-484`), `f4050a3` ← the 9 external-BMS registers
  (`ScooterFragment.java:486-495`, all `qv0`).
  So **the same register number is read from three different devices purely by the direction byte**;
  this is what plays the role of a "BMS index" in this app (there is no `bmsIndex` field anywhere —
  grep for `bmsIndex` returns nothing).
* `0x21` and `0x24` are the two values that never appear as a *request* direction. Requests are
  always addressed to `0x20` (ESC), `0x22` (BMS) or `0x23` (eBMS) (App. A).

### 3.2 Request direction (app→scooter)

The app builds requests with `direction ∈ {0x20, 0x22, 0x23}` and `action ∈ {READ, WRITE,
SHFW_READ, SHFW_WRITE}`, and `pe4.m12550k` emits them as `… 3E addr action pos …` (variants 0/1) or
`… addr action pos …` (variants 2/3). So **app→scooter = the request form** (`0x3E` before the
address byte in the Ninebot envelope; the plain address byte in the Xiaomi envelope).

### 3.3 Reply direction (scooter→app)

Replies are parsed only in the `addr 3E …` form (Ninebot envelope, §2.4) or after the Xiaomi
address byte has been translated by `oh3.m11880i` (§2.4). The dispatcher's direction branch is:

| reply direction | handler map | confidence |
|---|---|---|
| `32` (`0x20`, ESC) | `f4047X2` (normal ESC registers) / `f4048Y2` (SHFW registers) | direct (`ScooterFragment.java:1678,1711`, `p000/ao3.java:186-224`) |
| `34` (`0x22`, internal BMS) | `f4049Z2` | direct (`ScooterFragment.java:1673`, `ScooterFragment.java:475-484`) |
| `35` (`0x23`, external BMS) | `f4050a3`, **gated** by the "external battery present" flag `uo3.f30567u0` (`p000/jh4.java:414-419`) | direct (`ScooterFragment.java:1631-1638`, `ScooterFragment.java:486-495`) |
| `37` (`0x25`) | falls through to `f4047X2` (ESC) | **[inferred]** — jadx-mangled control flow (`ScooterFragment.java:1639` only nulls `obj` when `i2 != 37`; the `f4047X2` lookup is at `:1711`) |

**[inferred]** `35`/`37` are also the Xiaomi *reply* encodings of `0x20`/`0x22` (see
`p000/oh3.java:414-422`), while for Ninebot frames `0x23` is the external BMS address used in
requests (`p000/qv0.java:60-76`). The app's reuse of the same numeric values for two meanings is
only consistent if Xiaomi models never trigger the external-BMS branch
(`Scooter.isEligibleForExternalBattery()` ⇔ model `esx`/`e`,
`com/basse/scootbatt/global/Scooter.java:162-164`); the two meanings cannot be separated further
from static code.

---

## 4. Multi-part handling: chunk reassembly and the `position` byte

### 4.1 BLE-level reassembly (`hl2`) — this is the real "multi-part" mechanism

`hl2` is the per-connection frame assembler; it is created as
`new hl2(xq0Variant, chunkSize)` with `chunkSize = C0993mx.f19701r`
(`ScooterFragment.java:896-909` for the normal path, `:1200-1214` for the auth path).

* Per-variant total-length overhead, keyed by `xq0` ordinal
  (`p000/hl2.java:31-52`): `Ninebot → 9`, `NinebotCrypto → 13`, `Xiaomi → 6`, `XiaomiCrypto → 16`,
  together with the prefixes `5A A5`, `5A A5`, `55 AA`, `55 AB`. These four numbers match the frame
  sizes derived from `pe4.m12550k` in §2.2 exactly — an independent confirmation of the layout table.
* Assembly algorithm (`p000/hl2.java:59-93`): on a chunk that starts with the variant prefix, the
  declared total length is `frame[2] + overhead`; if it is ≤ chunk size the chunk is returned as a
  complete frame, otherwise a buffer of the declared total length is allocated, the first chunk
  copied, and the remaining byte count stored. Subsequent chunks are appended at
  `buffer.length - remaining` and the buffer is returned when `remaining == 0`.
* Limits: only **one** frame may be in flight (a new prefix while `remaining > 0` is not handled),
  and the declared length is trusted — no checksum verification, no upper bound check other than
  the buffer allocation.

### 4.2 The `position` byte is a register address, not a sequence number

App. A shows every `construct()` call: `position` is always the register/parameter to read or write
(e.g. `0x10` = BMS serial, 14 ASCII bytes, and `0x40` = 10 cell voltages, both confirmed from the
handler bodies `p000/C1252rv.java:119-139` and `:202-230`; `0x66`, `0x7C`, `0x7D`, `0x30` … are the
other ESC/BMS registers of `p000/C1138os.java:95-131`). It is echoed by the scooter in the reply
(`ScooterFragment.java:1594`) and is the map key used to find the handler. **No request is ever split
into numbered parts**, and no handler accumulates data across replies: each register read is answered
by exactly one frame whose `len` byte carries the whole payload (up to 255 bytes).

For SHFW (`action = SHFW_READ/SHFW_WRITE`) the same byte is a **SHFW memory address**:

| Address | Direction/action | Payload | Evidence |
|---|---|---|---|
| `0xFF` | ESC / `SHFW_READ` | `{2,0}` | `p000/C1138os.java:107` |
| `0xB2` | ESC / `SHFW_READ` and / `SHFW_WRITE` | `{2,0}` / LE u16 | `p000/un0.java:52`, `p000/ed2.java:97` |
| `0x00`, `0x11`, `0x4C`, `0x87` | ESC / `SHFW_READ` | `{32,0}` | `p000/C1524z4.java:45-51` |
| `0x01`, `0x3C`, `0x77` | ESC / `SHFW_READ` | `{32,0}` | `p000/d83.java:18-22` |
| arbitrary | ESC / `SHFW_WRITE` | arbitrary | `p000/wv3.java:17-26` (`yq0` request object) |

SHFW writes are **sent three times** and then a read-back request is queued:
`C1312th.java:435-444` (`for (i6=0; i6<3; i6++) { frame = pe4.m12550k(req.mo777f()); m3244o(frame); }`
followed by `req.mo778r()` → `ScooterFragment.m3300s0`). This is retry, not multi-part reassembly.

---

## 5. MTU and write chunking

| Fact | Value | Evidence |
|---|---|---|
| Chunk size field | `C0993mx.f19701r` | `p000/C0993mx.java:97` |
| Default value | **20 bytes** | `p000/C0993mx.java:97` (`public int f19701r = 20;`); the same literal `20` is the fallback in `ScooterFragment.java:889` |
| Updated on MTU change | `f19701r = mtu − 3` | `p000/C0993mx.java:435-440` (`onMtuChanged`, only when `status == 0`) |
| Negotiated MTU default | **none is requested by the app** → Android's default ATT MTU 23 ⇒ 20-byte payload | no `requestMtu` call exists anywhere in the app sources (grep over `p000/` + `com/basse/`); `connectGatt(context, false, cb, 2)` only selects `TRANSPORT_LE` (`BleForegroundService.java:385`), autoConnect = false |
| Extra confirmation | the elliptic/auth write path hard-codes 20-byte chunks with a 20 ms gap before handing them to the writer | `p000/C1527z7.java:1287` (`length <= 20`), `:1308` (`Math.min(length, 20)`), `:1317` (20 ms delay) |
| Chunking implementation | first slice written immediately when idle, remaining slices appended to queue `C0993mx.f19684a`; count = `ceil(len / f19701r)` | `BleForegroundService.java:494-529` (`m3244o`), `:593-624` (`m3245p`) |
| Queue drain | each successful `onCharacteristicWrite` pops one slice and writes it | `p000/C0993mx.java:237-260` (`m10820e`), `:287-320` |
| Write characteristic | `f19694k` = NUS RX `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (or the `0000fe95…/00000019…` auth chars in auth mode) | `p000/C0993mx.java:31,469-496` |
| Notify characteristic | `f19693j` = NUS TX `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | `p000/C0993mx.java:34,469,276-283` |
| Same value feeds reassembly | `new hl2(variant, f19701r)` | `ScooterFragment.java:896-909`, `:1201-1214` |

**Practical consequence:** with the default MTU the app writes at most 20 bytes per
`writeCharacteristic` call. A 32-byte cell-voltage reply (frame length `32+9 = 41`) arrives as three
notifications (20+20+1) and is stitched by `hl2`. Any reimplementation must therefore (a) chunk
outgoing frames at `MTU−3` and (b) not assume one notification = one frame.

---

## 6. Caller trace (who wraps, who writes)

### 6.1 `ScooterRequest.construct()`

65 call sites in 19 files, **all** of them inside a `vp0.mo2827f()` (request objects) or
`yq0.mo777f()` (SHFW request objects) implementation; see App. A. `ScooterRequest.copy()` is only
referenced by its own `copy$default` synthetic (`ScooterRequest.java:30-44`) and has no app caller.
No caller of `construct()` appends anything to the returned array — every site returns it directly
(as a method result) to the frame-wrapping layer.

### 6.2 `pe4.m12550k(byte[])` — the only frame wrapper

| Caller | Path | Evidence |
|---|---|---|
| `C1286ss.m14665b()` | queued command path: `pe4.m12550k(cmd.mo2827f())` → coroutine `C1249rs` → `m3244o` | `p000/C1286ss.java:84-96` (call at `:94`) |
| `ScooterFragment.m3303v0(byte[])` | direct send (used by the auth handshake and UI actions): `m12550k` → `m3244o` | `ScooterFragment.java:1984-1995`; callers `:758`, `:1473`, `:1486` |
| `C1312th` case 15 | SHFW write: `m12550k(req.mo777f())` called **3×**, then `m3244o` 3× | `p000/C1312th.java:435-444` |

### 6.3 `BleForegroundService.m3244o(byte[])` — exactly three callers

| Caller | Argument | Evidence |
|---|---|---|
| `p000/C1249rs.java:101` | `obj3` = the byte[] passed into `new C1249rs(this, pe4.m12550k(...), cmd, …)` (`p000/C1249rs.java:73,94-101`) | queued command path |
| `p000/C1312th.java:442` | `m12550k` of the SHFW request (3 iterations) | SHFW write path |
| `ScooterFragment.java:1993` | `m12550k(bArr)` inside `m3303v0` | direct/auth path |

In all three cases the argument is the **output of `pe4.m12550k`** (i.e. the checksum is already
present); neither `m3244o` nor `m3245p` appends any byte — they only slice the array
(`BleForegroundService.java:482-531`, `:567-633`). There is **no** fourth write path: the only
`writeCharacteristic` calls in the app are `BleForegroundService.java:525` (`m3244o`),
`BleForegroundService.java:627` (`m3245p`) and the queue drain `p000/C0993mx.java:255`.

`m3245p(byte[], UUID)` is the auth-characteristic ("writeElliptic") variant with the same chunking;
its three callers are `p000/C1527z7.java:1290,1305,1322`, which pre-chunk the data to 20 bytes
themselves.

---

## 7. What the envelope looks like end-to-end (worked example)

`new ScooterRequest(MASTER_TO_SCOOTER, READ, (byte) 0x66, new byte[]{6,0})`
(`p000/C1138os.java:95`) on a **Ninebot** scooter (`useCrypto = false`, `isXiaomi = false`):

```
construct()  →  3E 20 01 66 06 00                    (6 bytes, no checksum)
pe4 ord 0    →  5A A5 06 3E 20 01 66 06 00 2E FF
                └┬─┘ └┘ └─────┬─────┘ └──┬──┘
                 │   │        │          └── checksum, derived by pe4.java:1049-1055:
                 │   │        │              sum over bytes [2..8] =
                 │   │        │              0x06+0x3E+0x20+0x01+0x66+0x06+0x00 = 0xD1
                 │   │        │              chk = 0xD1 ^ 0xFFFF = 0xFF2E → LE → 2E FF
                 │   │        └── inner frame verbatim (copied at pe4.java:1048)
                 │   └── len byte = payload length (2)
                 └── 0x5A 0xA5 prefix
m3244o       →  one write of 11 bytes (11 ≤ 20, single chunk)
reply        →  5A A5 06 20 3E 01 66 <6 payload bytes> <sum_lo> <sum_hi>
                     └── addr then 0x3E = reply form; dispatcher keys on (0x66, 0x20)
```

(The `2E FF` checksum was **computed by hand** from the algorithm at `p000/pe4.java:1049-1055`, not
captured; the payload/position values come from `p000/C1138os.java:95`. Everything else in this
example is read directly from the cited code.)

---

## 8. Complete `construct()` call-site table (the "action table" as used in practice)

See **Appendix A** below: all 65 sites with direction, action, `position` and payload.

---

## 9. Unverified / needs hardware

1. **Whether real hardware accepts the request/reply byte-order convention.** Everything here is
   read from the app's own builder and parser. That the *scooter* also puts the address byte before
   `0x3E` in replies is proven only by the app's own expectations
   (`ScooterFragment.java:1129,1143`) — a real Ninebot capture is needed to confirm.
2. **Reply action codes.** `case 91`/`case 92` are explicit, but the comparison set
   `1 / 4 / 49 / 52 / 57` in the same dispatcher switch is jadx-flattened
   (`ScooterFragment.java:1604-1620`, `:1770-1780`). Which of those are action codes and which are
   register values could not be established from the decompiled text; re-decompiling
   `ScooterFragment.m3299r0` with a smali-level tool would settle it (not available offline here).
3. **`MASTER_TO_BLE_READ` (0x24).** The constant exists but is unreferenced; its interpretation as
   the "BLE reply address" rests on the Xiaomi converter mapping `36 → (0x21, 0x3E)`
   (`p000/oh3.java:417-419`) — **[inferred]**.
4. **The `0x23` / `0x25` double meanings** (Ninebot external BMS vs. Xiaomi reply addresses, §3.3).
   Only a live capture on an ESx/E with an external pack and on a Xiaomi model can separate them.
   In particular it is unclear what a Ninebot scooter uses `0x25` (37) for, and why the dispatcher
   lets it fall through to the ESC map.
5. **`0x11` for model `z10`** (`p000/pe4.java:1166-1168`): the override exists in code, but no
   comment/name survives and the reply direction for that model is unknown.
6. **Whether the scooter validates the checksum.** The app does not validate inbound checksums at
   all; if a reimplementation emits a wrong checksum the failure mode is unknown from static code.
7. **Variant 3 (`XiaomiCrypto`) exact envelope.** The `AES/CCM/NoPadding` decrypt, the 4-byte tag,
   the 2-byte counter and the 4-byte IV suffix derived from `frame[3],frame[4]` are visible
   (`ScooterFragment.java:995-1003`, `p000/pe4.java:1184-1300`), but the plaintext boundaries are
   obscured by a jadx-mangled `try/catch` (`ScooterFragment.java:1005-1040`), so the exact
   `len`/payload relationship for this variant should be treated as approximate.
8. **Whether `onMtuChanged` ever fires with a larger MTU.** No `requestMtu` call exists, so in
   practice `f19701r` should stay 20 — but if a peripheral or a future Android version initiates an
   MTU exchange the chunk size grows and both chunking and reassembly follow it
   (`p000/C0993mx.java:435-440`).
9. **`crc`/`sum` byte order on the wire** is taken from the builder
   (`bArr3[i4] = (byte)(i6 & 0xFF); bArr3[length+4] = (byte)(i6 >> 8)`, i.e. little-endian,
   `p000/pe4.java:1054-1055`). No inbound verification exists in the app to cross-check it.
10. **The role of `action = 0x31/0x32/0x33` (SHFW) semantics** (e.g. whether `SHFW_WRITE_NO_REPLY`
    is used by newer firmware paths) — the app never uses 0x33.

---

## Appendix A — all 65 `construct()` call sites

Generated by grepping every `new ScooterRequest(...).construct()` in `jadx-out/sources`; direction
and action names are resolved from the `xp0` field names. Every site is inside a
`mo2827f()`/`mo777f()` builder.

| # | file:line | direction | action | position | payload |
|---|---|---|---|---|---|
| 1 | `p000/C0059ay.java:450` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 119` | `new byte[]{1, 0}` |
| 2 | `p000/C1138os.java:95` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 102` | `new byte[]{6, 0}` |
| 3 | `p000/C1138os.java:97` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 124` | `new byte[]{1, 0}` |
| 4 | `p000/C1138os.java:99` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 47` | `new byte[]{2, 0}` |
| 5 | `p000/C1138os.java:101` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 62` | `new byte[]{2, 0}` |
| 6 | `p000/C1138os.java:103` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 27` | `new byte[]{2, 0}` |
| 7 | `p000/C1138os.java:105` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 123` | `new byte[]{2, 0}` |
| 8 | `p000/C1138os.java:107` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) -1` | `new byte[]{2, 0}` |
| 9 | `p000/C1138os.java:109` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 83` | `new byte[]{2, 0}` |
| 10 | `p000/C1138os.java:111` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 41` | `new byte[]{4, 0}` |
| 11 | `p000/C1138os.java:113` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 37` | `new byte[]{2, 0}` |
| 12 | `p000/C1138os.java:117` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 117` | `new byte[]{1, 0}` |
| 13 | `p000/C1138os.java:119` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) -70` | `new byte[]{2, 0}` |
| 14 | `p000/C1138os.java:121` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 71` | `new byte[]{2, 0}` |
| 15 | `p000/C1138os.java:123` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 50` | `new byte[]{4, 0}` |
| 16 | `p000/C1138os.java:125` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 52` | `new byte[]{4, 0}` |
| 17 | `p000/C1138os.java:127` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) -71` | `new byte[]{2, 0}` |
| 18 | `p000/C1138os.java:129` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) -38` | `new byte[]{12, 0}` |
| 19 | `p000/C1138os.java:131` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 125` | `new byte[]{2, 0}` |
| 20 | `p000/C1252rv.java:61` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 48` | `new byte[]{2, 0}` |
| 21 | `p000/C1252rv.java:63` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 64` | `new byte[]{32, 0}` |
| 22 | `p000/C1252rv.java:65` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 27` | `new byte[]{4, 0}` |
| 23 | `p000/C1252rv.java:67` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 59` | `new byte[]{2, 0}` |
| 24 | `p000/C1252rv.java:69` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 32` | `new byte[]{2, 0}` |
| 25 | `p000/C1252rv.java:71` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 16` | `new byte[]{14, 0}` |
| 26 | `p000/C1252rv.java:73` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 53` | `new byte[]{2, 0}` |
| 27 | `p000/C1252rv.java:75` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 24` | `new byte[]{2, 0}` |
| 28 | `p000/C1252rv.java:77` | MASTER_TO_BATTERY (0x22) | READ (0x01) | `(byte) 49` | `new byte[]{12, 0}` |
| 29 | `p000/C1524z4.java:45` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) -78` | `new byte[]{2, 0}` |
| 30 | `p000/C1524z4.java:47` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) 17` | `new byte[]{32, 0}` |
| 31 | `p000/C1524z4.java:49` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) 76` | `new byte[]{32, 0}` |
| 32 | `p000/C1524z4.java:51` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) -121` | `new byte[]{32, 0}` |
| 33 | `p000/al0.java:40` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 124` | `new byte[]{1, 0}` |
| 34 | `p000/al0.java:45` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 125` | `new byte[]{(byte) (intValue >>> 8), (byte) intValue}` |
| 35 | `p000/al0.java:50` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 125` | `new byte[]{(byte) (intValue2 >>> 8), (byte) intValue2}` |
| 36 | `p000/c70.java:43` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 70` | `new byte[]{1, 0}` |
| 37 | `p000/c70.java:45` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) -75` | `new byte[]{2, 0}` |
| 38 | `p000/d83.java:18` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) 1` | `new byte[]{32, 0}` |
| 39 | `p000/d83.java:20` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) 60` | `new byte[]{32, 0}` |
| 40 | `p000/d83.java:22` | MASTER_TO_SCOOTER (0x20) | SHFW_READ (0x31) | `(byte) 119` | `new byte[]{32, 0}` |
| 41 | `p000/ed2.java:97` | MASTER_TO_SCOOTER (0x20) | SHFW_WRITE (0x32) | `(byte) -78` | `new byte[]{(byte) i, (byte) (i >>> 8)}` |
| 42 | `p000/kd4.java:52` | xp0Var (?) | xp0Var2 (?) | `b` | `new byte[]{(byte) i, (byte) (i >>> 8)}` |
| 43 | `p000/kd4.java:58` | xp0Var3 (?) | xp0Var4 (?) | `b2` | `new byte[]{(byte) i2, (byte) (i2 >>> 8)}` |
| 44 | `p000/nj7.java:171` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 125` | `new byte[]{(byte) (intValue >>> 8), (byte) intValue}` |
| 45 | `p000/qv0.java:60` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 24` | `new byte[]{2, 0}` |
| 46 | `p000/qv0.java:62` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 48` | `new byte[]{2, 0}` |
| 47 | `p000/qv0.java:64` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 64` | `new byte[]{32, 0}` |
| 48 | `p000/qv0.java:66` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 27` | `new byte[]{4, 0}` |
| 49 | `p000/qv0.java:68` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 59` | `new byte[]{2, 0}` |
| 50 | `p000/qv0.java:70` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 32` | `new byte[]{2, 0}` |
| 51 | `p000/qv0.java:72` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 16` | `new byte[]{14, 0}` |
| 52 | `p000/qv0.java:74` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 53` | `new byte[]{2, 0}` |
| 53 | `p000/qv0.java:76` | MASTER_TO_EXTERNAL_BATTERY (0x23) | READ (0x01) | `(byte) 49` | `new byte[]{12, 0}` |
| 54 | `p000/r36.java:166` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 117` | `new byte[]{1, 0}` |
| 55 | `p000/rs1.java:216` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 123` | `new byte[]{1, 0}` |
| 56 | `p000/s49.java:176` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 123` | `new byte[]{2, 0}` |
| 57 | `p000/tr4.java:142` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 123` | `new byte[]{0, 0}` |
| 58 | `p000/un0.java:48` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 26` | `new byte[]{2, 0}` |
| 59 | `p000/un0.java:50` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) 16` | `new byte[]{14, 0}` |
| 60 | `p000/un0.java:52` | MASTER_TO_SCOOTER (0x20) | READ (0x01) | `(byte) -78` | `new byte[]{2, 0}` |
| 61 | `p000/vc2.java:41` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 112` | `new byte[]{1, 0}` |
| 62 | `p000/vc2.java:43` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 113` | `new byte[]{1, 0}` |
| 63 | `p000/wv3.java:25` | MASTER_TO_SCOOTER (0x20) | SHFW_WRITE (0x32) | `this.f33491a` | `this.f33492c` |
| 64 | `p000/zk0.java:35` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 124` | `new byte[]{0, 0}` |
| 65 | `p000/zk0.java:40` | MASTER_TO_SCOOTER (0x20) | WRITE (0x02) | `(byte) 125` | `new byte[]{(byte) (intValue >>> 8), (byte) intValue}` |

## Appendix B — key classes touched

| Class | Role |
|---|---|
| `p000/xp0.java` | the `direction`/`action` constant enum (byte in `f34564a`), values array `f34555d`, dead lambda `f34554c` |
| `com/basse/scootbatt/models/scooter/helpers/ScooterRequest.java` | builds the inner frame `3E dir action pos payload` |
| `p000/pe4.java` (`m12550k`, line 1034) | appends prefix + length + checksum (and encryption) → on-air frame; 4 protocol variants |
| `p000/xq0.java` | protocol variant enum: `Ninebot`, `NinebotCrypto`, `Xiaomi`, `XiaomiCrypto` |
| `p000/hl2.java` | chunk assembler; per-variant length overhead `{9,13,6,16}` |
| `p000/oh3.java` (`m11880i`, line 393) | Xiaomi→Ninebot frame converter (proves the `0x3E` position rule) |
| `p000/C1286ss.java`, `p000/C1249rs.java`, `p000/C1312th.java` | request queue, queue→write coroutine, SHFW 3×-retry writer |
| `com/basse/scootbatt/services/BleForegroundService.java` | `m3244o` (chunked write), `m3245p` (auth writes) |
| `p000/C0993mx.java` | GATT callback, `f19701r` chunk size / MTU handling, write queue drain |
| `ScooterFragment.java` | variant selection, `m3299r0` reply dispatcher, `m3303v0` direct send, auth frames |
| `p000/C1138os.java` / `C1252rv.java` / `qv0.java` / `d83.java` / `C1524z4.java` / `ed2.java` / `wv3.java` / `kd4.java` | per-device request/handler tables (ESC / BMS / eBMS / SHFW) |
