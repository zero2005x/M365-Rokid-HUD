# AREA 5 — M365 Tools 1.8.0: write / control commands and their safety gating

Target: `app.peretti.m365tools` 1.8.0 (versionCode 180), sha256
`4451a47c86339b8f88d77bdad7aef77e1bc4f11b7c728a5803f2b1d342a16872`.

All smali paths are relative to `/home/kali/ScooterHacking/re/m365tools/apktool-out/smali/`.
"read" = literal instruction/constant in the DEX; "inferred" = a conclusion drawn from
that evidence, stated as such. **No scooter and no phone were attached.** See the
*Unverified / needs hardware* section at the end.

---

## 0. Correction to `CONTEXT.md` (important)

`CONTEXT.md:88` states *"The app does not use Nordic UART. No `6e4000xx` UUID appears
anywhere."* **This is wrong.** The UUIDs are string-encrypted, so plain `grep` cannot see
them, but the decryptor recovers them:

| Constant | Plaintext | Where |
|---|---|---|
| `op->OO00000OOOOOOOO0000O` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (NUS **TX**, write) | `dalvik/O/O/a/b/O/c/b/O/o/op.smali:140` |
| `op->OOOOOOO0OOOOO0O00OO0` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (NUS **RX**, notify) | `dalvik/O/O/a/b/O/c/b/O/o/op.smali:149` |

The write characteristic is NUS TX at every transport's actual ATT write:
`nn.smali:508`, `bn.smali:435`, `mn.smali:842`, `fn.smali:1292`, `gn.smali:974`,
`kn.smali:2364`. `po.smali:1290` subscribes to NUS RX. The Xiaomi
`0000fe95-…` service is used for **scan filtering only**, and a second family of
characteristics (`00000013/14/19-…`, `00000001/02/04/10-…`, `op.smali:158–222`) is used
by the `kn`/`io` handshake paths.

---

## 1. The frame envelope for a write

### 1.1 The logical message

Every command is one `um` record — `from`, `to`, `cmd`, `register`, `payload`
(`dalvik/O/O/a/b/O/o/um.smali:15–23`, constructor `um.smali:27–57`). Its `toString` is
`"%s%s%s: %02X @%02X %s"` = `from→to: reg @cmd payloadhex` (`um.smali:141`).

`from`/`to` are the `um$OO00000OOOOOOOO0000O` address enum (`um$OO00000OOOOOOOO0000O.smali:54–132`):

| Address | Value | Symbol |
|---|---|---|
| MOTOR | `0x01` | `um$…smali:60` |
| ESC | `0x20` | `um$…smali:70` |
| BLE | `0x21` | `um$…smali:82` |
| BMS | `0x22` | `um$…smali:94` |
| EXTBMS | `0x23` | `um$…smali:106` |
| BMS_NBSEC | `0x25` | `um$…smali:118` |
| HOST | `0x3E` | `um$…smali:130` |

`cmd` is the `BaseCommand$O0OOOOOO00OOOOO000O0` enum (read, decryptor keys
`-0x1183…`…`-0x120f…`, constants at `BaseCommand$O0OOOOOO00OOOOO000O0.smali:64–261`):
`READ=1`, `WRITE=2`, `WRITE_NR=3`, `RESPONSE_READING=4`, `RESPONSE_WRITING=5`,
`AUTHORIZATION=0xA0`, `EXT_READ=0xA1`, `EXT_Write=0xA2`, `EXT_LOADREGS=0xA3`,
`EXT_SAVEREGS=0xA4`, `EXT_GETPROFILE=0xA6`, `EXT_GETINFO=0xB0`, `ERROR=0xFF`.
**The two command bytes that matter for this report are `0x01` (read) and `0x02` (write).**

### 1.2 There *is* a checksum, and the app always sends it

`vm.OO00000OOOOOOOO0000O([B)S` (`dalvik/O/O/a/b/O/o/vm.smali:56–89`) is a one's-complement
16-bit sum:

```
sum = 0; for b in bytes: sum += (b & 0xFF)
return (short)(sum & 0xFFFF) ^ 0xFFFF
```

It is appended **little-endian** as the last two bytes of every outbound frame
(`nn.smali:402–425`, `bn.smali:319–348`). The checksum covers the **length byte and the
whole body**, but *not* the two magic header bytes — this is visible in the code order:
the body is accumulated into the buffer, checksummed, then the two magic bytes are
prepended (`nn.smali:450–466`, `bn.smali:373–389`).

### 1.3 Three distinct wire framings

| Transport | Frame | Evidence |
|---|---|---|
| **`nn`** (plaintext) | `55 AA \| len(payload+2) \| to \| cmd \| reg \| payload \| cksum16LE` | `nn.smali:336–458` (magic `nn.smali:450,455`; len `nn.smali:352–359`; body order `nn.smali:362–384`) |
| **`bn`** (plaintext) | `5A A5 \| len(payload) \| from \| to \| cmd \| reg \| payload \| cksum16LE` | `bn.smali:260–389` (magic `bn.smali:373,378`) |
| **`mn`** (encrypted) | `55 AB \| len(payload+6) \| AES(inner) \| cksum16LE`, where `inner = from \| cmd \| reg \| payload \| FF FF FF FF` | `mn.smali:522–780` (inner `mn.smali:528–614`, pad `mn.smali:601–614`, encrypt `mn.smali:702–712`, magic `mn.smali:768,773`) |
| **`fn`, `gn`** | `5A A5` family | `fn.smali:1241,1246`; `gn.smali:1523,1528` |
| **`kn`** | no `55`/`5A` magic; uses `00000013`/`00000019` characteristics and a length-prefixed AES path (`kn.smali:1771–1845, 2490–2626`) | read |

Note the len field asymmetry: `nn` counts `payload+2`, `bn` counts `payload`,
`mn` counts `payload+6`. **A reimplementation that reuses one length rule across
transports will produce frames the scooter rejects.**

### 1.4 Response checksum *is* verified

`nn.OO0OOOOOOOO00000O0O0([B)Z` (`nn.smali:539–594`) recomputes the one's-complement sum
over `bytes[2 .. len-2]` and compares against the trailing little-endian word.
`mn.smali:223` carries the literal `Checksum missmatch`; `nn.smali:293` carries
`Wrong Header` (it compares the first two bytes against `55aa`, `nn.smali:104,166`).

### 1.5 BLE write mechanics

Every transport writes in **20-byte (`0x14`) chunks** through
`kb0.O0OOOOOO00OOOOO000O0(UUID, byte[])`, clearing its RX queue first
(`nn.smali:485–533`, `bn.smali:408–457`, `mn.smali:822–864`). A whole command is
serialised into one buffer and then sliced, so the 20-byte chunking is *not*
command-aware — a long payload is split mid-frame.

---

## 2. Command inventory

Registers/commands come from the `BaseCommand$…` builders. `cmd` and `reg` are the
3rd and 4th `um` fields described above.

### 2.1 Writes

| # | Function | `from` | `to` | `cmd` | `reg` | Payload | Model gate | Requires auth | Conf. |
|---|---|---|---|---|---|---|---|---|---|
| W1 | **Scooter lock / unlock** | `0x3E` | model `CTL` addr | `0x02` | **`0x70` lock / `0x71` unlock** | 1 byte `{0x01}` | none seen | no | read (`op.smali:4280,4282,4842–4888`) |
| W2 | **KERS level** (weak/med/strong) | `0x3E` | `CTL` | `0x02` | **`0x7B`** | 2 bytes, **little-endian** short | `rn.KERS_MODE` | no | read (`op.smali:2238–2383`; `iq.smali`× via `tk.smali:66`,`uk.smali:58`) |
| W3 | **Cruise control on/off** | `0x3E` | `CTL` | `0x02` | **`0x7C`** | 1 byte `{0x00∣0x01}` | `rn.CRUISE_CONTROL` | no | read (`op.smali:1604,1851–1877`) |
| W4 | **Tail light ("Light set")** | `0x3E` | `CTL` | `0x02` | **`0x7D`** | 2 bytes LE short | `rn.BACK_LED` | no | read (`op.smali:5103,5360–5382`) |
| W5 | **Units km/h ↔ mph** | `0x3E` | `CTL` | `0x02` | **`0x7D`** | 2 bytes LE short | `rn.SPEED_UNIT` (not observed gating) | no | read (`op.smali:3823,3971`) |
| W6 | **Speed limit (all / eco)** | `0x3E` | `ESC` | `0x02` | caller-supplied byte | 2 bytes LE short | `rn.SPEED_LIMIT_ALL`, `rn.SPEED_LIMIT_ECO` | no | read (`op.smali:2110–2196`, gate `ScooterBasicInformationFragment.smali:6546,6602`) |
| W7 | **Dash/throttle display mode** | `0x3E` | `ESC` | **`0x03` (WRITE_NR)** | caller-supplied byte | 2 bytes LE short | — | no | read (`op.smali:2041–2053`) |
| W8 | **Bluetooth name** | `0x3E` | BLE `0x21` | **`0x50`** | `0x00` | name bytes (`String.getBytes()`) | `rn.SET_BT_NAME` | no | read (`ck.smali:222–230`; builder `BaseCommand$OOOOOOO0OOOOO0O00OO0.smali:18–37` — `cmd=0x50`, `reg=0`, **`z=false`**) |
| W9 | **"Atmosphere"/ambient light** | `0x3E` | BLE addr | `0x02` | caller-supplied | byte[] | `rn.ATMOSTHERE_LIGHT` | no | inferred (`ScooterBasicInformationFragment.smali:6509`; builder generic) |
| W10 | **LED colour + mode (Kart)** | `0x3E` | `CTL` | `0x02` | **`0xC6`, `0xC8`, `0xCA`, `0xCC`** | 1–2 bytes; `0xCC`/`0xCA`/`0xC8` take `{0xFF, mode}` | `zm` model subclass only | no | read (`op.smali:5740–5828`) |
| W11 | **Magic serial write (ESC, 12 B)** | `0x3E` | model addr | `0x58` | `0x00` | `LE32(a) ‖ LE32(b)` | — | needs `WriteSNException` handling | read (`BaseCommand.java`-equivalent `BaseCommand$O00OOO0O00O00OOO0000.smali:31`; used `ScooterInformationActivity$OO0OOOOOOOO00000O0O0.smali:484`) |
| W12 | **Magic serial write (14 B)** | `0x3E` | model addr | `0x59` | `0x00` | `LE32(a) ‖ LE32(b) ‖ LE16(c)` | — | same | read (`BaseCommand$OO00000OOOOOOOO0000O.smali`, used `ScooterInformationActivity$…:469`) |
| W13 | **BMS serial write** | `0x3E` | BMS `0x22` | `0x5C` | `0x00` | caller byte[] | — | no | read (`dm.smali:59–86`, `BaseCommand$O0O000OOOO0000OOOO0O` ctor) |
| W14 | **BLE module config** | `0x3E` | BLE `0x21` | `0x5D` | `0x00` | empty | — | no | read (`cm.smali:71`, `em.smali:41`) |
| W15 | **Extended write** (used for BT name, W8) | `0x3E` | enum addr | `0x50` | `0x00` | caller byte[] | — | no | read (`ck.smali:222`, `BaseCommand$OOOOOOO0OOOOO0O00OO0.smali:18–37`) |
| W16 | **Handshake response** | `0x3E` | BLE `0x21` | `0x5B` | `0x00` | empty (reply carries 30 bytes) | — | yes — it *is* the auth step | read (`BaseCommand$OO0OOOOOOOO00000O0O0.smali:180`, used `fn.smali:1587`) |

Notable **non**-writes: `BaseCommand$OO0O0OOO00O00OOO0000` is `cmd=1` (read) at `to=0x21`
with the literal `NinebotGetDeviceName` (`BaseCommand$OO0O0OOO00O00OOO0000.smali:79,163`);
`BaseCommand$OO00OOOOO00OO00OOOO0` is `cmd=1` (`BaseCommand$OO00OOOOO00OO00OOOO0.smali:147`).
`ui.smali:75–442` is a **read-polling queue** (registers `0x10,0x15,0x18,0x1C,0x20,0x22,
0x25,0x2C,0x30,0x67,0x72`), not a write list.

### 2.2 "Magic serial" semantics

`WriteSNException` (`BaseCommand$WriteSNException.smali`) is thrown by W11/W12 when the
scooter's reply is not `payload==empty && reg==1`, or when the reply *does* carry a
payload — i.e. the app treats a non-empty reply to a serial write as a hard failure, not
a soft one (`BaseCommand$O00OOO0O00O00OOO0000.smali:144–225`). The whole command aborts.

### 2.3 Risk notes (what the app itself says, and does not say)

* **W11/W12 magic serial.** Irreversible in practice. The app's only visible handling is
  the `WriteSNException` path; it carries **no user-facing warning string** that could be
  located in the DEX. The exception text is built from a decrypted template that includes
  the attempted serial (`BaseCommand$…:44`).
* **Region change (`rn.CHANGE_REGION`).** The capability exists as a model feature
  (`rn.smali` key `-0x…` → `CHANGE_REGION`, and it is present in the `M365_1S`,
  `M365_3`, `M365_1S_DE`, `M365_PRO2`, `M365_Lite`, `MAX`, `ESx`, `ESx_P`, `F`
  feature lists) but **no register number for it is reachable in this DEX** — the write
  builder for it was not located. Withheld rather than guessed.
* **Speed limit (W6).** Written as a plain 16-bit little-endian short with no range
  clamp visible at the call site (`op.smali:2110–2196`). The app shows no confirmation
  dialog on that path.
* **Lock (W1).** The UI entry point logs `Toggle ScooterLock clicked`
  (`vd.smali:109`, `ee.smali:99`) and writes immediately; **no confirmation dialog was
  found on this path**.
* **Firmware update.** No OTA / firmware-flash write command exists in `BaseCommand`.
  The app can read firmware version registers but there is **no code path that writes
  firmware** — this is a finding, not an omission in this report. (`op.smali`'s largest
  strings are telemetry labels, not flash commands.)

---

## 3. Gating

### 3.1 Model gate — a capability table, not a version comparison

Each scooter model is a **subclass of `zm`** (`zm.smali:98` declares
`OOOO00OOO0OO0O0000O:Lzm$OO00000OOOOOOOO0000O;`; 23 `zm$*` classes exist). Each model
subclass passes an `ArrayList<qn>` of supported features to the `zm` constructor
(`zm.smali:1516`, `:1643`). Each `qn` wraps one `rn` feature enum value
(`zm.smali:1919`).

The gate itself is:

```java
// zm.smali:1865–1942
public final boolean OO0O0OOO00O00OOO0000(rn feature) {
    for (qn q : this.OO0O00O0OO00OOO00O00) if (q.OO00000OOOOOOOO0000O == feature) return true;
    return false;
}
```

Per-model feature lists recovered by extracting the `rn` references from each `zm$*`
constructor:

| Model | Features the app will write/offer |
|---|---|
| **M365** | SET_BT_NAME, BACK_LED, LOCK, SPEED_UNIT, CRUISE_CONTROL, KERS_MODE |
| M365PRO | same six as M365 |
| M365_1S / M365_3 / M365_1S_DE / M365_PRO2 / M365_Lite | the six + SPEED_LIMIT_ALL, CHANGE_REGION, AUTOMATION |
| MAX | + SPEED_100M_H, SPEED_LIMIT_ECO |
| ESx / ESx_P | + CHARGING_OPTIMIZE_INFO, SECOND_BATTERY, ATMOSTHERE_LIGHT, SPEED_LIMIT_ECO |
| S-Plus | SET_BT_NAME, LOCK, SPEED_UNIT, ATMOSTHERE_LIGHT |
| Mini / MiniLite | SET_BT_NAME, ATMOSTHERE_LIGHT, (UN_FIRM) |
| Mark2 / Z | SET_BT_NAME, SECOND_BATTERY / UN_FIRM only |
| Wild_Steel_Dust, Kart, KartPro, MiniMax, N4MZ | empty list (nothing gated-in) |

Call sites of the gate in the UI layer: `ScooterBasicInformationFragment.smali:6130`
(CRUISE_CONTROL), `:6188` (BACK_LED), `:6244` (KERS_MODE), `:6509` (ATMOSTHERE_LIGHT),
`:6546` (SPEED_LIMIT_ALL), `:6602` (SPEED_LIMIT_ECO);
`DashboardFragment.smali:2922,2994`, `BatteryInformationFragment.smali:765`,
`op.smali` (SECOND_BATTERY), `ui.smali:284–290`.

**Consequence for the only hardware we have:** on an **M365** the app will not offer
region change or any speed-limit write. It *will* write lock, KERS, cruise, tail light
and units.

A second, independent protocol-family gate exists: `zm.OO0OOOOOOOO00000O0O0()`
(`zm.smali:1944–2037`) compares `String.valueOf(this.OO00OOOOOO000O0OO00O)` against the
list `{32, 34, 37, 40, 41, 43, 46}`. Transport selection in `po.smali:112–250` then
branches on `App.O000OO0OO0OOO00OO000()` combined with the `zm` ordinal, so *which*
framing (`nn` / `bn` / `mn` / `fn` / `gn` / `kn`) a model uses is decided there.

### 3.2 Auth gate — who gets an AuthToken

`po.smali:41–93` (the Rx `onNext` for a connected `kb0`) computes:

```
input = App.OO0OOOOOOOO00000O0O0()             // po.smali:45  (App.ScooterName, default "" at App.smali:85–91)
d = SHA-256(input.getBytes())                  // po.smali:49–66 (algo string 'SHA-256')
AuthToken = Arrays.copyOf(hexToBytes(hex(d)), 16)   // po.smali:75–93
op.O00000OO0OOOOO00OOOO = AuthToken            // po.smali:93  — the ONLY writer
```

So the "authenticated session" token is **`SHA-256(<device string>)[0..15]`**, not a
negotiated secret. It is handed to the `fn` and `mn` transports
(`po.smali:177`, `225`, `306`, `336`, `375`, `405`, `470`, `488`, `558`, …) and, in `mn`,
is used directly as the AES key for the frame body (`mn.smali:702–712`, key field
`mn->O00OOO0O00O00OOO0000:[B`, XOR helper `vm.OO0O0OOO00O00OOO0000([B,[B)[B` at
`vm.smali:91–126`). `nn` and `bn` receive **no** token (3-arg constructors,
`po.smali:235–240`, `po.smali:415–420`).

**Therefore: what a third-party app can do at all depends on the transport, not on a
credential.** On the plaintext `nn`/`bn` transports every write in §2.1 is available
without any handshake. On `mn`/`fn` the payload is encrypted with a value any client can
recompute from the scooter's own advertised name/MAC — there is no server, no pairing
secret and no per-device key exchange in this path. (The ECDH machinery that exists —
`po.smali` strings `nbsecf`, `nbsecft`, `ecdh_old` at `po.smali:154,267,447,623,734` — is
used for a *different* transport family and its output was not traced here.)

### 3.3 Handshake-gated writes

Only W16 (`cmd=0x5B`, `BaseCommand$OO0OOOOOOOO00000O0O0`) is explicitly a handshake
message; its reply is split into a 16-byte key and a 14-byte block
(`BaseCommand$OO0OOOOOOOO00000O0O0.smali:85–99`, `Arrays.copyOf(bArr,16)` /
`copyOfRange(bArr,16,30)`), stored in the `an` session object. It is issued by
`fn.smali:1587`. No *user-facing* setting write was found behind an auth check — the
gating is structural (which transport the model selected), not a permission test.

### 3.4 Confirmation dialogs, warnings, refusals

* **`SCOOTERLOCK_WARNING`.** Decrypts from `gq.smali:66` (`-0x30ee76b3f29eL`). It sits in
  `gq` next to `LOCK_OPTION` (`gq.smali:54`) and `LOCK_SCREEN_ROTATION_OPTION`
  (`gq.smali:62`) — i.e. it is a **preference key**, not a dialog string. The literal
  `-0x30ee76b3f29eL` occurs **exactly once in the whole DEX** (`gq.smali:66`), so in this
  build it is a **declared-but-unreferenced key**: no read, no write, no dialog uses it.
  Do not treat it as evidence that the app warns before locking.
* **A real confirmation dialog does exist for KERS.** `ScooterBasicInformationFragment_ViewBinding$O00000000OOOOOOOOO00.smali:210–250` builds an AlertDialog with a
  `setItems(CharSequence[], checkedIndex, listener)` list, a positive button (`oj`) and a
  negative button (`uk`). The listeners are `tk.smali`/`uk.smali`, which map the selection
  to `0/1/2` and call `op.OO00000OOOOOOOO0000O(S)` → write to `0x7B`. This is a *chooser*,
  not a danger warning.
* **No warning dialog was found** on the lock/unlock path (`vd.smali:78–109`,
  `ee.smali:68–99`) or on the serial-write path.
* **Firmware/OTA: deliberately absent.** No write path exists.
* **Premium/billing.** `App.smali:60–76` decrypts `m365_premium_id_2` and
  `m365_premium_id_3`; `BillingDataSource` and `Premium_Showroom_Activity` exist in the
  app package. The billing literals that surface in the string table are Play-Billing
  product ids and a sample purchase JSON (`dalvik/O/O/a/b/O/o/pq.smali:3393`). **No write
  or control command in §2.1 is behind a billing check** — the gate that is actually
  enforced at every write site is `zm.OO0O0OOO00O00OOO0000(rn)` (model capability), not
  a purchase state. (Confidence: read for "no billing check at the write sites"; the
  premium feature set itself was not enumerated.)

---

## 4. Retries and failure surfacing

* **One command at a time.** `vm$OO00000OOOOOOOO0000O.call()` grabs a global
  `AtomicBoolean` with `compareAndSet(false,true)`; a second concurrent command
  immediately returns integer `0` (`vm$OO00000OOOOOOOO0000O.smali:59–75,176–191`).
* **Send then wait.** `vm.OO00O0O0O0000000000O(baseCommand)` performs the ATT writes; if
  `BaseCommand.O0OOOOOO00OOOOO000O0` is `false` the callable returns `1` immediately
  without waiting (`vm$…:89–117`).
* **The `-2` re-read loop.** Otherwise it blocks on `vm.O00OOO0O00O00OOO0000(timeout)` and
  validates the reply with `BaseCommand.OO00000OOOOOOOO0000O(um)`. A return of `-2` means
  "not my reply" and the loop **fetches the next frame** rather than re-sending
  (`vm$…:126–154`). **Nothing re-sends the command itself.**
* **Timeouts.** A `TimeoutException` yields `-3` if `BaseCommand.OO00O0O0O0000000000O`
  (the "suppress" flag) is set, else it is rethrown (`vm$…:233–274`).
* **`WriteSNException` is swallowed when the suppress flag is set** and returns `1`
  (success) — `vm$…:198–231`. **That is the app's one dangerous failure mode for a write:
  a failed serial write can be reported as success.**
* **Return-code table.** `BaseCommand.<clinit>` builds an `ImmutableMap`
  (`BaseCommand.smali:37–184`) of 9 status codes — `0 OK`, `1 Out of bounds`,
  `2 Erase error`, `3 Write error`, `4 Not locked`, `5 Invalid address`,
  `6 Command in progress`, `7 Invalid payload len`, `8 Too many Errors or Retry attempts`
  (keys `-0x13fa76b3f29e` … `-0x146676b3f29e`). The built map is **discarded in the
  `<clinit>`** (no `sput-object` follows `BaseCommand.smali:181–183`), so the table is
  inert in this build; the *strings* are still the decoder ring for status codes.
* **A write can be fire-and-forget.** `cmd=0x50` (BT name, W8) is built with the
  `z` flag `false` (`BaseCommand$OOOOOOO0OOOOO0O00OO0.smali:29`) — `v6=0` is passed as the
  last constructor argument — so the callable returns `1` without waiting for any reply
  (`vm$…:94–117`). The app therefore **cannot know** whether the name write landed.
* **No backoff, no attempt counter** is implemented anywhere in the app's write path. The
  fork's `WriteRetryPolicy` has no counterpart here.

---

## 5. Comparison with M365-Rokid-HUD (`/home/kali/ScooterHacking/repo`)

| Capability | M365 Tools 1.8.0 | Fork | Difference |
|---|---|---|---|
| Frame envelope | `55 AA`/`5A A5`/`55 AB` + len + addr + cmd + reg + payload + **cksum16LE** | `len ‖ direction ‖ read_write ‖ attribute ‖ payload`, **no header, no checksum** (`ninebot-ble/src/session/commands.rs:115–130`) | Fork omits both magic bytes and the checksum. The scooter's own reply checksum verification the app performs (`nn.smali:539–594`) has no fork equivalent. |
| Length field | counts differently per transport (`payload+2` / `payload` / `payload+6`) | `payload.len() + 2` only (`commands.rs:121`, `MAX_PAYLOAD_LEN=253` `:92`) | Fork models only the `nn`-style rule. |
| Read/write byte | `cmd` **1 = read, 2 = write**, separate `reg` byte | `ReadWrite::Read=0x01`, `Write=0x03` (`commands.rs:30–37`) | **The fork uses `0x03` for write; the app uses `0x02`.** This is the single most consequential divergence found. |
| Lock / unlock | `reg 0x70` / `0x71`, payload `{01}` | `Attribute::Lock=0x70`, `Unlock=0x71` (`commands.rs:74–75`) — **registered but no writer** | Fork has the registers, deliberately sends neither (`ScooterSettingsWriter.kt:20–22`). |
| KERS | `reg 0x7B`, 2-byte **little-endian** short | `REG_KERS=0x7B`, `{code, 0x00}` (`ScooterSettingsWriter.kt:47,91–92`) | Agreement. |
| Cruise | `reg 0x7C`, **1 byte** `{00\|01}` (`op.smali:1851–1877`) | `REG_CRUISE=0x7C`, `{0x01,0x00}` 2 bytes (`:50,95–96`) | Fork sends one byte more than the app. |
| Tail light | `reg 0x7D`, 2-byte LE short | `REG_STATUS=0x7D`, **big-endian** (`:53,135–144`) with a documented read-LE/write-BE asymmetry | Fork *writes* BE; the app writes **LE** (`op.smali:3823,3971` uses `ByteOrder.LITTLE_ENDIAN`). Divergence — one of the two is wrong on hardware. |
| Units | `reg 0x7D`, LE short | same register, BE (`:118–125`) | Same divergence. |
| Speed limit / eco | `op.smali:2110–2196`, gated `SPEED_LIMIT_ALL`/`_ECO` | **absent** | Fork lacks it. |
| Region change | capability flag exists; **no register found** | absent | Both effectively lack it; the app's flag has no located implementation. |
| Magic serial (`0x58`/`0x59`) | present (`ScooterInformationActivity$…:469,484`) | absent | Fork lacks it. |
| BMS serial (`0x5C`) | present (`dm.smali:59`) | absent — the fork states "the reference app performs no BMS writes" | **The fork's premise is wrong for M365 Tools**: `cmd=0x5C` to BMS exists. |
| BLE config (`0x5D`), ext write (`0x50`), handshake (`0x5B`) | present | `NinebotHandshake.kt` exists but was not compared byte-for-byte here | Fork lacks the `0x50`/`0x5B`/`0x5D` builders in `commands.rs`. |
| Model gating | `zm.OO0O0OOO00O00OOO0000(rn)` capability list per model (`zm.smali:1865`) | `ScooterModelRegistry.kt` (386 lines) | Different mechanism; not byte-compared. |
| Retry | none; `-2` loop re-reads, and `WriteSNException` can be **swallowed as success** (`vm$…:198–231`) | `WriteRetryPolicy` = 3 attempts/chunk, 40 ms doubling to 250 ms, `DISCARDED_BY_PEER` gives up loudly (`WriteRetryPolicy.kt:41,44,53,106–133`) | Fork is stricter and safer. |
| Error codes | 9 generic status codes (`BaseCommand.smali:37–184`, table inert) | 40 ESC fault codes from Scootbatt (`ScooterErrorCodes.kt:36–70`) | Different code spaces; not comparable. |
| Readback verification | app re-reads after a write (`op.smali:2062` reads back what `:2037` wrote) | explicit `verify*` helpers (`ScooterSettingsWriter.kt:156–180`) | Agreement in principle. |

**Fork has, app lacks:** nothing found; the fork is a strict subset of the app's writes
plus stricter retry/verification.
**App has, fork lacks:** lock/unlock writes, speed limit, region (flag only), magic serial
(both lengths), BMS serial `0x5C`, BLE config `0x5D`, extended write `0x50`, handshake
`0x5B`, and the `5A A5` / `55 AB` framings.
**Gated differently:** the app gates on a per-model *capability list* keyed by an `rn`
enum; the fork models only three status registers and gates by explicit omission.

---

## 6. Unverified / needs hardware

**No scooter and no phone were attached during this analysis. Not one command in this
report has been executed, transmitted or observed. Nothing here should be sent to
hardware on the strength of this report alone.**

Specifically unverified:

1. **Every command byte and register in §2.1** is a *reading of the builder code*, not an
   observation. The mapping from `cmd=0x02 + reg=0x7B` to "KERS changes on a real M365"
   is inferred from the app's own UI wiring and from the fork's labels — never confirmed
   on a vehicle.
2. **The `0x7D` endianness conflict** between the app (little-endian, `op.smali:3823,3971`)
   and the fork (big-endian, `ScooterSettingsWriter.kt:135–144`) is *unresolved*. At most
   one is correct. Writing the wrong one to a shared status word would silently flip the
   other setting.
3. **The `mn` encrypted framing** (§1.3) is reconstructed from control flow, including a
   plaintext buffer (`mn.smali:522–688`) whose result is computed and then never
   transmitted. That dead path is reported as read but its *purpose* is not established.
4. **`kn`'s framing** is the least understood: no `55`/`5A` magic exists in the file and
   the AES path (`kn.smali:1771–1845`) was not fully traced. Any `kn`-family model
   (newest firmware) write is **withheld**.
5. **Which string feeds `App.OO0OOOOOOOO00000O0O0()`** (the AuthToken input) is not
   settled. Its default is `""` (`App.smali:85–91`) and it is set through
   `App$OO00000OOOOOOOO0000O.OO00O0O0O0000000000O(String)` (`:379–391`). If it is the BLE
   MAC the token is fully client-computable; if it is a user-chosen nickname it is not.
   **Do not assume a third-party app can reproduce the `mn`/`fn` token until this is
   settled.**
6. **Region change.** The `rn.CHANGE_REGION` flag exists but no write builder for it was
   found; the register is **withheld**, not guessed.
7. **`rn` feature lists** in §3.1 were recovered by extracting `rn` field references from
   each `zm$*` constructor. If R8 inlined any entry the list is short. Treat the M365 row
   as read-with-caveat and the other rows as inferred.
8. **`SCOOTERLOCK_WARNING`** is asserted to be unreferenced because its `const-wide`
   literal occurs once. A use site that reaches the string through a different key
   (the decryptor is not injective across all observed keys) would invalidate that; the
   claim is "no reference found", not "impossible to reference".
9. **The premium/billing conclusion** covers only the write sites. Whether some *read* or
   UI feature is paywalled was not enumerated.
10. **No write was checked for a range clamp.** Registers such as `0x70/0x71/0x7B/0x7C/0x7D`
    receive whatever the UI hands them (`op.smali:2110–2196` passes a caller-supplied
    byte and short straight through). What a scooter does with an out-of-range value is
    unknown and is exactly the class of mistake that cannot be undone remotely.
