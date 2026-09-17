# 04 — Inventory of state-changing (write) commands in `com.basse.scootbatt` 1.9.2 (136)

**Scope of this report:** every command the app can send that changes state on the scooter,
with the exact bytes, the UI that triggers it, risk, and the app's own guard rails.
Telemetry/read commands are only mentioned where they matter for a write (verification,
gating).

**Hard constraint:** static analysis only — no scooter, no BLE capture, no vendor network.
Everything below is read out of the decompiled code. Where a statement is an inference
rather than a direct code fact it is labelled **[inferred]**. Everything that could not be
settled statically is in §8.

**Method / completeness argument.** I did not trust class names (the app is R8-obfuscated).
Instead I enumerated:

* every construction site of `ScooterRequest` (66 in the tree, of which the *write* ones are
  listed in §1) — `grep -rn "new ScooterRequest(" jadx-out/sources`;
* every dispatch site of a write request — `WriteRegisterRequestEvent` has exactly **21**
  construction sites, all in `p000/vo3.java`, `p000/xo3.java`, `p000/C0938lf.java`,
  `p000/C1312th.java`;
* the single write execution path: `ScooterFragment.m3281C0(yq0)` (`ScooterFragment.java:590`)
  → coroutine `C1312th` case 15 (`C1312th.java:429-453`) → `pe4.m12550k()` → `BleForegroundService.m3244o()`
  → NUS RX `6e400002-…` (`C0993mx.java:255`);
* every `writeCharacteristic`/`m18225w` call in app code (`C0993mx.java:255`,
  `BleForegroundService.java:525/627`, `fm2.java:1841/1886/2012/2020/2085`) — no other write
  path exists.

Consequence: the set of write commands reachable in this build is exactly the set in §1.
Two `yq0` write implementations exist that are **not** reachable (no construction site) —
see §4.4.

---

## 1. Master table — every write command

Envelope from `ScooterRequest.construct()` (`ScooterRequest.java:67-112`):
`[0x3E, direction, action, position, payload…]`, length = `payload.length + 4`.
**Direction is `MASTER_TO_SCOOTER` (0x20) for every write in the app.**
Action is `READ`=0x01, `WRITE`=0x02, `WRITE_NO_REPLY`=0x03, `SHFW_READ`=0x31, `SHFW_WRITE`=0x32,
`SHFW_WRITE_NO_REPLY`=0x33 (`xp0.java:44-61`).
`position` is the register/index byte; for reads the payload is the 16-bit LE *length* to read,
for writes it is the value bytes.

| # | Feature (UI label / string res) | dir | action | position | payload (exact) | models | Reversible? | Risk | Evidence (`file:line`) |
|---|---|---|---|---|---|---|---|---|---|
| W1 | **Lock** ("Locked" switch, `chip_locked`) | 0x20 | 0x02 | **0x70** (112) | `{01,00}` | all **except** `g2`, `g65`, `f2` (switch hidden for those) | yes (W2) | **MED–HIGH** — immobiliser; the switch is also offered as a notification action | `vc2.java:41`; UI `vo3.java:113`; gate `vo3.java:243,268` + `Scooter.java:170-173` |
| W2 | **Unlock** (same switch, off-state) | 0x20 | 0x02 | **0x71** (113) | `{01,00}` | as W1 | yes (W1) | **MED** — defeats anti-theft | `vc2.java:43`; UI `vo3.java:115`; notification path `BleForegroundService.java:298,369` → `C0938lf.java:100-113` |
| W3 | **KERS mode = weak** (`kers_weak`, dialog `kers_mode`) | 0x20 | 0x02 | **0x7B** (123) | `{00,00}` | all | yes (choose another value) | **MED** — changes regenerative braking / brake feel; value range unverified | `tr4.java:142`; UI `vo3.java:142` + `C0074bc.java:295-308` |
| W4 | **KERS mode = medium** (`kers_medium`) | 0x20 | 0x02 | **0x7B** | `{01,00}` | all | yes | MED | `rs1.java:216`; UI `vo3.java:144` |
| W5 | **KERS mode = strong** (`kers_strong`) | 0x20 | 0x02 | **0x7B** | `{02,00}` | all | yes | MED | `s49.java:176`; UI `vo3.java:150` |
| W6 | **Cruise control ON** (`chip_cruise_control`) | 0x20 | 0x02 | **0x7C** (124) | `{01,00}` | all | yes (W7) | **HIGH (safety)** — vehicle can hold speed without throttle input | `al0.java:40`; UI `vo3.java:96` + `vo3.java:238` |
| W7 | **Cruise control OFF** | 0x20 | 0x02 | **0x7C** | `{00,00}` | all | yes (W6) | MED | `zk0.java:35`; UI `vo3.java:98` |
| W8 | **Taillight always-on = ON** (`chip_taillight_always_on`) | 0x20 | 0x02 | **0x7D** (125) | `{v>>8, v}` where **v = cached u16 \| 0x0002** | all | yes (W9) | **MED** — read-modify-write of a *shared* bitfield (bit 4 = mph unit lives in the same u16); stale/incorrect cache ⇒ neighbouring bits/register 0x7E can be corrupted | `nj7.java:171`; UI `vo3.java:103` + `vo3.java:239` |
| W9 | **Taillight always-on = OFF** | 0x20 | 0x02 | **0x7D** | `{v>>8, v}` where **v = cached u16 & ~0x0002** | all | yes (W8) | MED (same RMW caveat) | `al0.java:45`; UI `vo3.java:105` |
| W10 | **Use mph unit = ON** (`chip_use_mph_unit`) | 0x20 | 0x02 | **0x7D** | `{v>>8, v}` where **v = cached u16 \| 0x0010** | all | yes (W11) | **MED** — changes the *displayed* speed unit on the dash; wrong speed perception; shares the same RMW bitfield as W8/W9 | `al0.java:50`; UI `vo3.java:121` + `vo3.java:250` |
| W11 | **Use mph unit = OFF** | 0x20 | 0x02 | **0x7D** | `{v>>8, v}` where **v = cached u16 & ~0x0010** | all | yes (W10) | MED | `zk0.java:40`; UI `vo3.java:123` |
| W12 | **Active SHFW profile = 1..3** (`chip_active_shfw_profile` / `header_active_shfw_profile`) | 0x20 | **0x32** | **0xB2** (178) | `{idx, idx>>8}` = `{01,00}` / `{02,00}` / `{03,00}` (u16 LE; `idx` = list index + 1) | SHFW-firmware scooters only (row only built when SHFW detected **and** profile names were received) | yes (choose another) | **HIGH (safety)** — selects which custom-firmware profile is live (its speed/current/KERS limits). Written **twice back-to-back** (duplicate dispatch) | `ed2.java:97`; dispatch `C1312th.java:455-461` (x2); UI `vo3.java:62` → `vo3.java:133` → `wo3.java:60-95` |
| W13 | **SHFW: always-active headlight** (`chip_always_active_headlight`) | 0x20 | 0x32 | **base+21** (0x16) | `{v&0xFF, v>>8}` (u16 LE) where v = cached SHFW u16 **\| 0x0004** (off: **& ~0x0004** = `& 0xFFFB`) | SHFW only (write base derived from profile index) | yes | MED — RMW of the SHFW light bitfield; **no read-back verification** after write | `kd4.java:52` (ctor path `vo3.java:49`), UI `vo3.java:266` |
| W14 | **SHFW: always-active brakelight** (`chip_always_active_brakelight`) | 0x20 | 0x32 | **base+21** | u16 LE where v = cached u16 **\| 0x0008** (off: **& ~0x0008**) | SHFW only | yes | MED (same) | `kd4.java:58` (ctor path `vo3.java:283`), UI `vo3.java:263` |
| W15 | **SHFW: idle/active dash data value** (`chip_idle_dash_data`, `chip_active_dash_data`) | 0x20 | 0x32 | **base+22** (0x17) | 2 bytes, one of two orders depending on the dialog: `{oldCode, newCode}` (active) or `{newCode, oldCode}` (idle); codes 0..18 from `ui3` | SHFW only | yes | LOW (display only) — but the **byte order is ambiguous/inconsistent** in code | `wv3.java:25`; `xo3.java:68,75,109,116`; enum `ui3.java:31` |
| W16 | **SHFW: alternating dash data value** (`chip_alternating_dash_data`) | 0x20 | 0x32 | **base+23** (0x18) | `{dashCode (0..18), altSourceCode (0..5)}` | SHFW only | yes | LOW (display only) | `wv3.java:25`; `vo3.java:186`; enums `ui3.java:31`, `EnumC1513yv.java:29` |
| S1 | NinebotCrypto session: `3E 21 5B 00` | 0x21 | — (raw frame, no action byte) | 0x5B | `{}` (4-byte frame) | only when advertisement says crypto (`useCrypto`) | n/a | session setup, not a user feature; **required before any write on those models** | `ScooterFragment.java:1472` (raw `{62,33,91,0}`) |
| S2 | NinebotCrypto session: client key | 0x21 | — | 0x5C | 16-byte random key | as S1 | n/a | key exchange | `ScooterFragment.java:1485` |
| S3 | NinebotCrypto session: confirm/hash | 0x21 | — | 0x5D | 14 bytes from `f4059j3` | as S1 | n/a | key exchange (fills with the 14 bytes received from 0x5B reply, `ScooterFragment.java:1607`) | `ScooterFragment.java:751-758` (`m3284b0`) |
| S4 | Xiaomi elliptic ("XiaomiCrypto") session | — | — | chars `00000019-…` / `00000010-…` | `{00,00,op,sub,…}` and `{op,00,00,00}` | Xiaomi models on the legacy/indication transport | n/a | ECDH + AES-CCM session; keys come from Xiaomi cloud data stored/shared by other apps | `fm2.java:1836-1861,1886,2012,2020,2085`; entry `ScooterFragment.java:1205,1325-1354,993` |

`base` = SHFW profile register base returned by `aj3.m741e()` (`aj3.java:134-142`):
**1** (profile 1), **60** (0x3C, profile 2), **119** (0x77, profile 3) — selected by the profile
index that the user picked (`aj3.m742f`, `C1312th.java:456`). So the derived write addresses are:
W13/W14 → **22 / 81 / 140** (0x16/0x51/0x8C), W15 → **23 / 82 / 141** (0x17/0x52/0x8D),
W16 → **24 / 83 / 142** (0x18/0x53/0x8E). **[inferred from code arithmetic — the arithmetic is
certain, the choice of base is the app's]**

**Read-modify-write**: W8–W11 and W13/W14 are the only commands that *preserve* other bits of a
register by reading the current value first (`uo3.f30569v0`≡`f30510J` for 0x7D; `aj3.f657q`≡`f647g`
for the SHFW u16). W1–W7 and W12/W15/W16 are absolute writes of a fixed/full value.
No command writes a *flash/EEPROM image*, a serial number, a region, a wheel size or a
firmware blob — see §5.

---

## 2. Per-command detail

### 2.1 Wire framing (what actually goes out on the air)

`construct()` output is **not** what is written to the characteristic. It is wrapped by
`pe4.m12550k(byte[])` (`pe4.java:1034`) according to the transport enum `xq0`
(`xq0.java:26-33`: `Ninebot`, `NinebotCrypto`, `Xiaomi`, `XiaomiCrypto`):

| Transport | Wrapper | Bytes |
|---|---|---|
| `Ninebot` (plain) | `pe4.java:1041-1056` | `5A A5 <len=payload> <3E dir act pos payload…> <crc_lo> <crc_hi>`; crc = `~(sum of bytes from the len byte through the payload)) & 0xFFFF`, little-endian |
| `NinebotCrypto` | `pe4.java:1058-1141` | `5A A5 <len>` + encrypted frame + 4-byte MIC + 2-byte counter (rolling `C1375v6.f31212a`); a special-case captures the 16-byte key from the `5A A5 10 3E 21 5C 00 …` reply (`pe4.java:1136-1138`) |
| `Xiaomi` (plain) | `pe4.java:1143-1182` | `55 AA <len=payload+2> <dir> <act> <pos> <payload…> <crc_lo> <crc_hi>` — note the `0x3E` byte is **dropped** and the direction byte moves to index 3 |
| `XiaomiCrypto` | `pe4.java:1184-…` | `55 AB…`, elliptic/AES-CCM (uses `fm2`/`sa6`; **returns `null` if there is no elliptic session**, `pe4.java:1217-1220`) |

Worked example (derived arithmetic, not a capture) — **KERS = medium (W4)** on a plain Ninebot:
`construct()` = `3E 20 02 7B 01 00` → framed = `5A A5 02 3E 20 02 7B 01 00 21 FF`
(sum `02+3E+20+02+7B+01+00 = 0xDE`, complement `0xFF21`).

Two things a reimplementer must not assume:
* the checksum is computed over the *whole* wrapped frame excluding the `5A A5` prefix and the
  two CRC bytes itself — the `len` byte **is** included (`pe4.java:1050-1055`);
* the complement is `sum ^ 0xFFFF` on a 32-bit int, not `(~sum) & 0xFFFF` — identical for the
  small frames this app sends, but it would differ for sums ≥ 0x10000.

### 2.2 Execution path, retries, ordering

```
UI toggle  ──► h84.f10102f.mo9e(Boolean)            (C0211ew.java:85-87 default case)
           ──► vo3/xo3 lambda  ──► WriteRegisterRequestEvent   (21 sites, §7)
           ──► ScooterFragment.onWriteRegisterRequestEvent      (ScooterFragment.java:1386-1389)
           ──► ScooterFragment.m3281C0(yq0)                     (ScooterFragment.java:590-595)
                 guard: connection state must be ee0.f6546j == "Paired" (else silently dropped)
           ──► C1312th case 15                                  (C1312th.java:429-453)
                 loop 3×: pe4.m12550k(frame) → BleForegroundService.m3244o(bytes)
                 then: yq0.mo778r() (read-back, if any) → ScooterFragment.m3300s0()
```
* Every write is transmitted **three times unconditionally** (`C1312th.java:437-447`), with no
  ACK and no wait; the `CountDownLatch` there is created and immediately counted down
  (so it is effectively a no-op). Frames are chunked to MTU−3 by `BleForegroundService.m3244o`
  (`BleForegroundService.java:482-530`, chunk size `C0993mx.f19701r`, `C0993mx.java:438`).
* A write is only sent while the UI fragment is alive (`m6704t()`) and the session state is
  **Paired** — `m3281C0` returns early otherwise.
* After the writes, `mo778r()` re-reads the register: W3–W5 re-read 0x7B (`C1138os(…,5)`),
  W6–W11 re-read 0x7D (`C1138os(…,18)`) and W7 (`zk0` case 0) re-reads 0x7C
  (`C1138os(…,1)`), W1/W2 re-read 0xB2 (`un0(…,2)`). **W12–W16 (all SHFW writes) return
  `null` — no verification at all** (`kd4.java:65-70`, `wv3.java:30-32`, `ed2.java:107-109`).
* Minor app inconsistency (not a safety issue): *cruise control ON* (`al0` case 0, register
  0x7C) verifies by reading register **0x7D** — `al0.mo778r()` always returns
  `C1138os(…,18)` regardless of the variant (`al0.java:56-60`), whereas the OFF path
  (`zk0` case 0) correctly verifies 0x7C.

### 2.3 Stock register semantics (independent confirmation from the read path)

The read side confirms the field meaning of each stock register — the reader map in
`dp3.java:339-348` keys parsers by the register byte:

| Register | Write | Read parser | Parsed field | Evidence |
|---|---|---|---|---|
| 0x70/0x71 | lock / unlock | — (no read-back of 0x70; lock state comes from the notification stream) | `uo3.f30570w` ("Locked") | `te2.java:180`, `un0.java:883` |
| 0x7B | KERS 0/1/2 | `C1138os(…,5)` | `0 = Weak, 1 = Medium, 2 = Strong` → `f30512L`/`f30513M` | `C1138os.java:98,341-366` |
| 0x7C | cruise 0/1 | `C1138os(…,1)` | `(bArr[0] == 1)` → `f30566u` | `C1138os.java:96,189-193` |
| 0x7D | u16 bitfield | `C1138os(…,18)` | raw u16 LE (`m17773w`), bit1 → `f30568v` (taillight), bit4 → `f30572x` (mph) | `C1138os.java:131,619-625`; `AbstractC1491y9.java:498-501` |
| 0x75 | (dead write, §4.4) | `C1138os(…,11)` | `0 = Normal, 1 = ECO, 2 = Sport` → `f30550m` | `C1138os.java:117, …` |
| 0xB2 | SHFW profile | `C1524z4(…,0)` | `m742f(u16 LE)` = profile index + base selection | `C1524z4.java:45,72-73`, `aj3.java:151-167` |

The UI wiring is the strongest evidence for the write semantics: each switch row is created
with its string resource and its callback variant in the same call
(`vo3.java:236-253` for stock, `vo3.java:261-276` for SHFW).

### 2.4 SHFW register space and an address inconsistency you must not paper over

The app reads the SHFW configuration as 32-byte blocks:
`d83.java:18-22` reads 32 bytes at 1 / 60 / 119 (`SHFW_READ` 0x31) and
`C1524z4.java:45-51` reads 32 bytes at 17 / 76 / 135 = base+16, and parses (offsets relative to
the read start, `C1524z4.java:77-158`):

| read offset | field | observable |
|---|---|---|
| 10-11 (u16 LE) | bit2 → brakelight flag, bit3 → headlight flag | `f646f` / `f645e` (≡ switch display `f656p`/`f655o`, `aj3.java:96-110`), raw value `f647g` ≡ `f657q` (RMW cache) |
| 12 | idle dash data code | `f648h` |
| 13 | active dash data code | `f649i` |
| 14 | alternating dash data code | `f650j` |
| 15 | alternating source (0..5) | `f651k` ≡ `f663w` |

**⚠ Established inconsistency:** the *writes* for those same settings go to **base+21** (lights
u16), **base+22** and **base+23** (dash data) — `vo3.java:49,186,283` — while the *reads* parse
the fields at read offsets 10..15, i.e. registers base+26..base+31. The app therefore reads one
address and writes another (5-byte shift for the u16, 6..7 bytes for the dash fields). Either
SHFW's read and write handlers use different addressing conventions, or one side is wrong.
**[inferred/uncertain]** — do not publish the read-derived offsets as "the" write addresses;
use the write addresses in §1 verbatim, and treat base+21/22/23 as the only verified *write*
targets.

Additionally, `ed2` writes the profile index to register 0xB2 while on *stock* firmware the same
register is read as a **firmware/version value** by `un0` case 2 (`un0.java:52`, handler
`un0.java:2366-2389` checks a 1280…1792 range = "new-generation ESC"). Same address, different
meaning per firmware — flagged, unresolved without hardware.

---

## 3. Payload encodings, value ranges

| Command | Encoding | Range / allowed values | Notes |
|---|---|---|---|
| W1/W2 lock/unlock | fixed `{1,0}` | n/a | two *different* registers, not one register with 0/1 |
| W3–W5 KERS | `{mode, 00}` little-endian u16 | 0, 1, 2 only (enum `c22` = `WEAK/MEDIUM/STRONG`, `c22.java:22-29`); values >2 never produced | app never sends 3+; whether firmware accepts them is unknown |
| W6/W7 cruise | `{0|1, 00}` | 0, 1 | |
| W8–W11 0x7D | `{v>>8, v}` = **big-endian** u16 of the *whole register* | full 16-bit word; only bits 1 (taillight) and 4 (mph) are ever changed by the app | read path interprets the same register as **little-endian** u16 (`AbstractC1491y9.m17773w`, `C1138os.java:621`) → **byte-order asymmetry**, see §6 |
| W12 profile | `{idx, idx>>8}` u16 LE | 1, 2, 3 (list index + 1, `C1312th.java:455`) | value 0 is never written by the app |
| W13/W14 SHFW lights | u16 LE, full word | bit2 = headlight-always, bit3 = brakelight-always; other bits preserved from cache | cache is only populated after a successful 32-byte read; the row is disabled until then (§4.2) |
| W15/W16 SHFW dash | 2 raw bytes | dash code 0..18 (`ui3.java:31`: Off, ESC temp, avg speed km/h, uptime, trip km, battery level, system voltage, speed mph/km/h/m/s, avg mph/m/s, CC mph/km/h, battery temp, throttle, brake, current, power); alt-source 0..5 (`EnumC1513yv.java:29`) | W15's two bytes are `{old, new}` or `{new, old}` depending on which dialog branch runs (`xo3.java:68` vs `xo3.java:116`) — see §8 |

---

## 4. Guard rails the app itself applies

These are the places where the app authors clearly considered a write dangerous or
unsupported.

### 4.1 Model gating
* **Lock/Unlock (W1/W2) is hidden for "new generation" Ninebot** — the row is only added when
  `!scooter.isNewNinebotGeneration()`, i.e. the model is not `g2`, `g65` or `f2`
  (`vo3.java:243,268`; `Scooter.java:170-173`). Both branches (stock and SHFW) apply it.
* The whole SHFW configuration group is only built when the "SHFW installed" flag is true
  (`f30561r0`): `vo3.java:235` `gy1.m6310f((Boolean) obj, Boolean.FALSE)` → stock list,
  else SHFW list; the flag is set by the register-26 read parser `un0.java:103`.
* The "Active SHFW profile" row is built **only when a non-blank profile-name list exists**
  (`vo3.java:64-71`), which in turn only arrives from an external broadcast
  (see §4.5).
* External-BMS firmware row only for `esx`/`e` (`dp3.java:210-214`, `Scooter.java:161`).

### 4.2 "Value unknown ⇒ switch disabled"
`h84` (the switch row) accepts a list of required observables; while any of them is `null` the
row and the switch are created **disabled** and only enabled once all have produced a value
(`h84.java:44-97`; row/switch disabled at `62-63`, enabled at `83-84`). Used for:
* taillight (W8/W9) and mph (W10/W11): requires the cached register-0x7D value `f30569v0`
  (`vo3.java:241,250`),
* SHFW headlight/brakelight (W13/W14): requires the SHFW u16 cache `f657q` **and** the active
  profile index `f653m` (`vo3.java:263,266`).
The model→UI direction uses `setCheckedNoEvent` (`C1289sv.java:22-25`), so programmatic updates
from the read-back do **not** re-trigger a write (no write loop).

### 4.3 Mandatory connection state
Both read and write dispatch return immediately unless the session state is
`ee0.f6546j` = **"Paired"** (`ScooterFragment.java:590-595`, `m3300s0`,
`ScooterFragment.java:1898-1903`) and the fragment is attached. There is no write while
merely "Connected" or "PendingPairing".

### 4.4 Writes present in the code but NOT reachable (important negative result)
Two `yq0` write implementations exist that are **never constructed** in this build — they are
R8-merged leftovers from a shared code lineage (ScooterHacking Utility):

| Frame | Feature it would be | Why unreachable |
|---|---|---|
| `WRITE 0x75 {01,00}` | "set ride mode" (0x75 is *read* as Normal/ECO/Sport by `C1138os` case 11) | only `mo777f()` caller is `C1312th.java:439`; no construction site of `r36(uo3)`'s yq0 role exists (all 20 `new r36(...)` sites use other merged roles) |
| `WRITE 0x77 {01,00}` | unknown | same — no construction site of `C0059ay`'s yq0 role |

(Also dead: the string `notification_switch_to_eco_mode` — "Switch to ECO mode" — is never
referenced in code, and no ECO-mode write exists.)

### 4.5 No confirmation dialogs, but the app does depend on other apps
* **There is no "are you sure?" confirmation for any write.** Toggling a switch writes
  immediately (`C0211ew.java:85-87`). The dialogs in the flow (`DialogC1032nz`,
  `DialogC1330tz`) are value *pickers* (KERS weak/medium/strong, SHFW profile, dash data) —
  their callback performs the write with no second step.
* The KERS picker preselects and shows the **current** value read from the scooter
  (`C0074bc.java:290-307`), and the profile picker lists names received via the exported
  broadcast receiver `SHFWProfileNamesReceiver` (`SHFWProfileNamesReceiver.java:22-44`,
  manifest line 184) — i.e. from ScooterHacking Utility. A wrong/absent list means the user
  cannot tell which profile index they are selecting.
* `MajsiHomeReceiver` / `RequestEllipticKeysReceiver` are also **exported without permission**
  (`AndroidManifest.xml:162,173`), the latter *handing out* the stored Xiaomi elliptic
  credentials (`RequestEllipticKeysReceiver.java:36-50`).

---

## 5. What is NOT in this app (negative results, verified by exhaustive grep)

| Candidate feature | Result |
|---|---|
| Speed limit / max speed **write** | **absent.** `speedometer_max_speed_*`, speedometer mode/ticks etc. are *display* settings only, stored in local `Preferences` (`SpeedometerCustomizationFragment.java`, no `ScooterRequest` anywhere in `p005ui/fragments/settings/**` or `.../dashboard/**`) |
| Wheel size, zero-start, gear/ride-mode write, ECO write | **absent** (0x75 write is dead code, §4.4) |
| Serial number write | **absent.** `chip_serial_number` / `chip_mcu_identifier` rows have `null` callbacks (`dp3.java:184-192`) |
| Region change | **absent.** `chip_region` row has a `null` callback (`dp3.java:193-196`); region is read-only telemetry |
| Battery charge limit / BMS settings | **absent.** All 9 `MASTER_TO_BATTERY` (0x22) and 9 `MASTER_TO_EXTERNAL_BATTERY` (0x23) requests in `C1252rv.java:61-77` and `qv0.java:60-76` use `READ` (0x01); **no BMS write exists anywhere** |
| Firmware update / OTA / flashing / factory reset | **absent.** No `dfu`/`ota`/`flash`/`bootloader` strings or code paths; the app only *reads* and *displays* firmware versions and forwards them to Crashlytics (`vo3.java:206-229`) |
| "Magic" / custom-firmware features beyond SHFW registers | **absent** — the only custom-FW interaction is W12–W16 |

---

## 6. Do writes need an authenticated / encrypted session?

**Yes on crypto transports, no on plain ones.** The transport is chosen per scooter from the
BLE advertisement, not from the model name:

* `Scooter.useCrypto = (advert[1] == 2)` — parsed in the "0xFF 'N' 'B'" advertisement parser
  (`C1142ow.java:117`), consumed in `BluetoothScanFragment.java:761`.
* Protocol selection at connect: `scooter.getUseCrypto() ? NinebotCrypto : scooter.isXiaomi() ? Xiaomi : Ninebot`
  (`ScooterFragment.java:900`); the Xiaomi legacy/indication transport instead forces
  `XiaomiCrypto` (`ScooterFragment.java:1205`, entered from `C0993mx.java:423` → `mo3236g`).

| Transport | What a third-party app must implement to write |
|---|---|
| `Ninebot` (plain) | only the `5A A5` framing + 16-bit complement checksum (`pe4.java:1041-1056`). **No auth, no session.** |
| `Xiaomi` (plain) | only the `55 AA` framing + checksum (`pe4.java:1143-1182`). **No auth.** |
| `NinebotCrypto` | full in-band key exchange first: `3E 21 5B 00`, then `3E 21 5C 00 <16-byte random key>`, then `3E 21 5D 00 <14-byte value derived from the 0x5B reply>`; then every frame is encrypted with a rolling counter (`C1375v6`, session key captured at `pe4.java:1136-1138`). Without the handshake `pe4.m12550k` throws/never produces a frame. |
| `XiaomiCrypto` | ECDH (`secp256r1`) + AES-CCM session built from **Xiaomi cloud credentials** (`beaconKey`, `deviceInfo`, `deviceToken`, keyed by SSID) that Scootbatt itself does not obtain — it reads them from its own prefs and exchanges them with other apps (`ConfigurationElliptic.java`, `fm2.java:38-90`, `RequestEllipticKeysReceiver.java`). Handshake writes go to characteristics `00000019-…` and `00000010-…`, not through the NUS UART envelope (`fm2.java:1841,1886,2012,2020,2085`); responses are AES-CCM decrypted (`ScooterFragment.java:993`). |

Practical consequence for M365-Rokid-HUD: on plain Ninebot/Xiaomi scooters the write commands in
§1 can be reproduced byte-for-byte without any crypto; on `useCrypto` Ninebot models you must
reimplement the 0x5B/0x5C/0x5D handshake **and** `C1375v6`'s cipher; on XiaomiCrypto models you
need the cloud-derived elliptic material and the AES-CCM session. All writes ride the same NUS RX
characteristic `6e400002-…` except the Xiaomi elliptic handshake.

---

## 7. Risk assessment

### 7.1 Per-command risk (summary of §1, ordered by severity)

1. **W12 — active SHFW profile select (0xB2).** Highest consequence: it changes which
   custom-firmware parameter set is live (speed limit, motor current, KERS, …). If the profile
   name list is stale (it arrives by broadcast from another app) the user can select a profile
   index that does not correspond to the name they saw. Sent **twice**, no verification.
   Reversible, but the effect is immediate and invisible to the app.
2. **W6 — cruise control ON.** Safety-relevant rider behaviour (vehicle can maintain speed
   without throttle). Reversible (W7). The app applies no confirmation and no check that the
   scooter is stationary.
3. **W8–W11 — register 0x7D read-modify-write.** Three settings share one 16-bit word
   (taillight bit1, mph bit4, plus unknown bits). The app writes the *entire* cached word in
   **big-endian** while it reads it as **little-endian**. If the firmware's byte order is not
   what the write assumes, toggling the taillight can also flip the mph unit or write a
   neighbouring register (the payload's second byte lands on 0x7E). This is the most plausible
   "wrong value written to a shared setting" failure mode in the stock command set.
4. **W3–W5 — KERS mode.** Affects regenerative braking strength / brake feel. Reversible,
   small value domain. Only risk is a firmware accepting values outside 0..2 (the app never
   tests this).
5. **W1/W2 — lock/unlock.** Immobiliser. The app hides it for g2/g65/f2 and offers it as a
   notification action that reads the current state first (`C0938lf.java:100-113`), so it is
   not blindly toggled.
6. **W13–W16 — SHFW config (lights, dash data).** Cosmetic/display in intent, but they are
   **unverified writes**: no read-back, and the write addresses disagree with the app's own
   read offsets (§2.4). A wrong address in the custom firmware's register space is the least
   predictable of all these commands.
7. **W9/W11/W14/W16 "off" paths** — same as their "on" counterparts.

### 7.2 Bricking / irreversible damage
* **No command in this app is a firmware, bootloader or EEPROM-image write.** Every command is a
  short (≤ 2 data bytes) register write handled by the running ESC/BLE firmware. On that basis
  the *designed* write set cannot reflash or erase the controller. **[inferred from the absence
  of any flash/OTA path — see §5]**
* Residual brick risk comes from writing a *wrong address/value*, not from the command set
  itself: (a) the SHFW address mismatch of §2.4, (b) the 0x7D byte-order asymmetry of §7.1.3,
  (c) 0xB2 on stock firmware meaning something else than "profile". A reimplementation should
  therefore send only the exact frames in §1 and only after a read of the same register.
* Reversibility: all settings are revertible *if the previous value is known*. The app caches
  the value for the read-modify-write registers, but keeps **no history/undo** and offers no
  "restore defaults". Persistence across power cycles is a firmware property and was not
  verified.

### 7.3 Contrast: "read-modify-write settings" vs "firmware/EEPROM writes"
| Class | Commands | Character |
|---|---|---|
| Read-modify-write setting | W8–W11 (0x7D), W13/W14 (SHFW u16) | preserves unrelated bits; risk is *bit bleed* from a bad cache/byte order |
| Absolute setting write | W1–W7, W12, W15, W16 | overwrite a whole register (or a fixed value); risk is *semantic* (wrong profile/mode) |
| Session/auth writes | S1–S4 | not user features; required precondition for crypto transports |
| Firmware/EEPROM write | **none exist** | — |

---

## 8. Unverified / needs hardware

1. **Whether the scooter actually accepts these frames.** No capture exists; the framing rules
   in §2.1 are read from the app, not confirmed on the wire. In particular the Xiaomi frame's
   `len` byte is `payload+2` in this app (`pe4.java:1146`), which does not match the
   commonly documented `payload+3` layout — either the app is wrong or the documented layout is.
2. **0x7D byte order and bitfield width.** Read is little-endian u16 (`AbstractC1491y9.java:498`),
   write is `{hi,lo}` (`nj7.java:171`). Which side matches the firmware — and whether 0x7E/0x7F
   are separate registers — cannot be decided statically. Test by reading 0x7D back after a write
   before trusting W8–W11.
3. **SHFW register map.** The app reads profile fields at read offsets 10..15 of a 32-byte block
   starting at base+16, but writes at base+21/22/23 (§2.4). The mapping
   "register → setting" is therefore only **[inferred]** from UI wiring; the *addresses written*
   are established, the *meaning of the neighbouring bytes* is not. Also unknown: the valid
   value ranges (dash codes > 18? alt-source > 5?) and what the firmware does with them.
4. **Value 0 of register 0xB2** (SHFW "stock/off"?) is never written by the app — behaviour on
   real hardware unknown.
5. **KERS values > 2** and **cruise value ≠ 0/1** are never produced; firmware tolerance unknown.
6. **Persistence and atomicity.** Whether each setting is stored in flash and survives power-off,
   and whether an interrupted 3×-retry burst can leave a half-written register, is unknown.
7. **The read-back verification is not proof of success**: `m3300s0` dispatches a read whose
   result updates the UI, but a failure is only reflected as an unchanged switch value; the app
   shows no error dialog for a rejected write.
8. **`0x75`/`0x77` write semantics** (§4.4): unreachable here, but if a reimplementation enables
   them, their meaning is unknown — the old draft report `00b-write-commands.md` describes them
   (and `0x7B`/`0x7C`/`0xB2`) differently; see §9.

---

## 9. Reconciliation with the earlier draft `00b-write-commands.md`

That file (written before the UI/string-resource trace) lists 11 writes and several byte
values that this report contradicts. The corrections, with evidence:

| Draft claim | This report | Evidence |
|---|---|---|
| `0xD4` (212) = SHFW parameter write | **`0xB2` (178)**; payload is the **profile index**, not a generic parameter | `ed2.java:97` (`(byte) -78`), dispatch `C1312th.java:455-461` |
| `0x75 {1,0}` = ride-mode write is an app feature | unreachable dead code; only *read* as Normal/ECO/Sport | no construction site of `r36`'s yq0 role; `C1138os.java:117` |
| `0x77 {1,0}` = mode/setting write | unreachable dead code | no construction site of `C0059ay`'s yq0 role |
| `0x7B {0,0}` = "KERS off" | `0x7B` = KERS **weak** (0), medium (1), strong (2) — off is not offered | `c22.java:22-29`, `C0074bc.java:295-308`, `C1138os.java:341-366` |
| `0x7B` read = "signal quality", `0x7C` read = "error code" | 0x7B read → KERS enum; 0x7C read → cruise bool. The register→parser mapping is keyed by register byte in `dp3.java:339-348` and matches `C1138os.mo2827f()` case positions exactly | `dp3.java:344-345`, `C1138os.java:96-98` |
| `0x7D` = taillight only | 0x7D is a shared u16: bit1 taillight, bit4 mph unit; written big-endian, read little-endian | `C1138os.java:619-625`, `nj7.java:171`, `al0.java:45,50`, `zk0.java:40` |
| "write preconditions: version/model check — unconfirmed; confirmation — unconfirmed" | Established: state must be *Paired*, lock row hidden for g2/g65/f2, switches disabled until the register is read, **no confirmation dialog at all** | §4.1–4.3, §4.5 |
| 11 writes total | **16 user-visible writes** (W1–W16) + 3 NinebotCrypto session frames + Xiaomi elliptic handshake | §1, §7 of this report |

---

## 10. Index of evidence (files/lines used)

| Topic | Location |
|---|---|
| Envelope builder | `models/scooter/helpers/ScooterRequest.java:67-112` |
| Direction/action constants | `p000/xp0.java:44-61` |
| Transport enum | `p000/xq0.java:26-33` |
| Framing/encryption of outgoing frames | `p000/pe4.java:1034-1141` (Ninebot, NinebotCrypto), `1143-1182` (Xiaomi), `1184-1330` (XiaomiCrypto) |
| Write dispatch (3× retry) | `p000/C1312th.java:429-453` |
| Write entry + state guard | `p005ui/fragments/scooter/ScooterFragment.java:590-595, 1386-1389` |
| Chunking / characteristic write | `services/BleForegroundService.java:482-530`, `p000/C0993mx.java:239-258, 438` |
| All 21 write dispatches | `p000/vo3.java:49,57,59,96,98,103,105,113,115,121,123,142,144,150,186,283`; `p000/xo3.java:68,75,109,116`; `p000/C0938lf.java:106,110`; `p000/C1312th.java:460,461` |
| UI rows (stock config) | `p000/vo3.java:236-253`, `p000/dp3.java:180-230, 327-348` |
| UI rows (SHFW config) | `p000/vo3.java:254-276` |
| KERS dialog | `p000/C0074bc.java:293-309` |
| SHFW profile dialog | `p000/wo3.java:59-95`, `p000/C1312th.java:455-461` |
| Dash-data dialogs | `p000/xo3.java:50-125`, `p000/wo3.java:73-90` |
| Enum values (dash, KERS, alt-source) | `p000/ui3.java:31`, `p000/c22.java:22-29`, `p000/EnumC1513yv.java:29` |
| SHFW base selection | `p000/aj3.java:134-142, 151-167` |
| SHFW read parse | `p000/C1524z4.java:40-159`, `p000/d83.java:18-22` |
| Stock read parse / verification | `p000/C1138os.java:89-131, 189-193, 341-366, 619-625`, `p000/AbstractC1491y9.java:498-501` |
| Read-back wiring | `mo778r()` in `tr4.java`, `rs1.java`, `s49.java`, `nj7.java`, `vc2.java`, `zk0.java`, `al0.java` (returns `null` in `kd4.java`, `wv3.java`, `ed2.java`) |
| Switch row gating | `p000/h84.java:44-97` (62-63/83-84), `p000/C1289sv.java:24-25`, `p000/C0211ew.java:85-87` |
| Model gating | `global/Scooter.java:161-173`, `vo3.java:243,268` |
| useCrypto origin | `p000/C1142ow.java:117`, `p005ui/fragments/BluetoothScanFragment.java:761` |
| NinebotCrypto handshake | `p005ui/fragments/scooter/ScooterFragment.java:751-758, 900, 1455-1512, 1607` |
| Xiaomi elliptic session | `ScooterFragment.java:993, 1205, 1325-1354`; `p000/fm2.java:38-90, 925-1093, 1836-1861, 1886-2020, 2085`; `crypto/elliptic/ConfigurationElliptic.java` |
| Exported receivers | `resources/AndroidManifest.xml:162,173,184`; `services/SHFWProfileNamesReceiver.java`, `services/MajsiHomeReceiver.java`, `services/RequestEllipticKeysReceiver.java` |
