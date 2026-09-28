# AREA 3 — Handshake and authentication flows, per protocol family

**Target:** `app.peretti.m365tools` 1.8.0 (versionCode 180), sha256 `4451a47c…a16872`.
**Method:** static analysis of the apktool smali only. **No scooter, no phone** — see
*Unverified / needs hardware* at the end.

## Citation conventions

To keep citations readable, `S/` expands to
`apktool-out/smali/dalvik/O/O/a/b/O/c/b/O/o/` (the R8-repackaged app classes).
Paths for the non-obfuscated app package are given in full as
`apktool-out/smali/app/peretti/m365tools/…`.

**Operational note for other areas:** the APK has **two smali roots** —
`apktool-out/smali/` and `apktool-out/smali_classes2/`. A `grep -r` restricted to
`smali/` silently misses classes such as `S/lb0.smali`
(`= apktool-out/smali_classes2/dalvik/O/O/a/b/O/c/b/O/o/lb0.smali`, the RxBleDevice
abstraction) and all of Google Tink. Every "nothing references X" claim must cover both.

## 0. Headline findings

1. **There are four handshake families, and the app never reads a version or serial
   before starting one.** The family is chosen from *(a)* the detected model enum
   ordinal, *(b)* a per-device **protocol version int** (0/1/2) loaded from
   preferences, and *(c)* three Firebase Remote Config flags (`nbsecft`, `nbsecf`,
   `ecdh_old`). The branch table is §2.
2. **The app does contain a real ECDH handshake** — NIST **P-256**, ephemeral
   keypair, HKDF-SHA256 with info **`mible-setup-info`**, then a **Xiaomi "mible"
   login** with info `mible-login-info`. It lives in class `S/kn.smali`, into which
   R8 merged the original `app.peretti.m365tools.model.XiaomiECDH`.
3. **`ecdh_old` does not select "ECDH vs. not-ECDH".** `ecdh_old == 1` selects a
   **static-key** transport (`gn`), and `ecdh_old != 1` selects the ECDH transport
   (`kn`). The flag is a kill-switch, not a description (§2.3).
4. **`decrypt2` (MainActivity.smali:1421) has nothing to do with BLE.** It is a
   Kotlin null-check message inside the **PiracyChecker** licence-key base64 decode
   in `onCreate` (§9). Treat it as a false lead.
5. The app's own session crypto uses exactly **two** `Cipher` modes:
   `AES/CCM/NoPadding` (`S/kn.smali:1787`) and `AES/ECB/NoPadding`
   (`S/an.smali:124`), plus **RC4** by hand (`S/vp$OO00000OOOOOOOO0000O.smali`).
   There is **no** AES-CTR / CBC-MAC "Encryption2" anywhere in the app's protocol
   classes (§8.4).
6. **`AuthToken` is client-computable**: it is `SHA-256(<BLE MAC address string>)[0..15]`
   — MAC confirmed in §7.1.

---

## 1. State sequence from GATT-connected to "telemetry flows"

Entry point: `S/op.smali:2397` `op.OO00O0O0O0000000000O()V` (the connect routine).
It calls `establishConnection` on the device handle (`S/op.smali:2650`,
`lb0.OO00000OOOOOOOO0000O(Z)` — `lb0` = RxBleDevice), then subscribes with
**onNext = `po`** (`S/op.smali:2683`, error handler `lo` at `:2688`).

```
 1. GATT connected → po.S/po.smali:36 receives the RxBleConnection handle (kb0).
    Gate: the Observable emits a kb0. No cryptographic work happens before this.

 2. po computes the device token:
      token = HexDecode(HexEncode(SHA-256(App.OO0OOOOOOOO00000O0O0() UTF-8)))[0..16]
    S/po.smali:41-93 (MessageDigest "SHA-256" S/po.smali:56, copyOf(16) S/po.smali:89,
    single writer of op.O00000OO0OOOOO00OOOO at S/po.smali:93).
    App.OO0OOOOOOOO00000O0O0() is the BLE MAC address string (§7.1).
    Gate: none — always computed, even on paths that never use it.

 3. po stores the connection handle (S/po.smali:108) and resets the frame counter
    (S/po.smali:101-105).

 4. po SELECTS THE PROTOCOL FAMILY (§2) and stores the chosen session object in
    op.OO0O0O0OO0OOO0OO0000 (S/po.smali:181,204,229,245,292,310,340,356,379,409,
    425,474,492,513,562,580,601,648,666,696,712,761,779,809,825).
    Branch condition: model enum ordinal × protocol version int × Remote Config flag.

 5. po subscribes to notifications on characteristic 6e400003-… (NUS TX):
    S/po.smali:1288-1296 (UUID string op.OOOOOOO0OOOOO0O00OO0, set at S/op.smali:156),
    filter/subscribe chain S/po.smali:1300-1355, Disposable stored in
    op.OOO00O0OO00OOO0OOOO0 (S/po.smali:1355).
    Gate: always executed, before any handshake byte is sent.

 6a. IF protocol version == 2 (S/po.smali:1358-1375):
       session.OOOOOOO0OOOOO0O00OO0(deviceName, callback)  — the register/login entry.
       For the ECDH transport kn that entry is S/kn.smali:2689, which first READS
       characteristic 00000004-… (op.OO0O0OOO00O00OOO0000, S/op.smali:192)
       and only then runs the handshake of §5.
       → §5 (ECDH) or §6 (chained AES), depending on the family chosen in step 4.

 6b. ELSE IF protocol version == 1 (S/po.smali:1379-1443):
       read 00000014-…  → consumer up        (S/po.smali:1392; UUID from
                                              S/op.smali:165, overridden at
                                              S/op.smali:426-448 by a Base64 literal)
       read 00000002-…  → BiConsumer fo      (S/po.smali:1421, UUID S/op.smali:183)
       fo (S/fo.smali:36) stores the reply in op.OOO0O0OO0000O0O0000O (S/fo.smali:54)
       and then READS 00000001-… (S/fo.smali:57-65), piping the result into the
       RC4 AuthToken state machine io (S/fo.smali:108) → §4.

 6c. ELSE (protocol version ∉ {1,2}):
       no handshake at all; the family selected in step 4 has already set the
       "session ready" flag (§3), so telemetry starts immediately.

 7. Telemetry tick: ScooterService$O0OOOOOO00OOOOO000O0.run()
    (apktool-out/smali/app/peretti/m365tools/ScooterService$O0OOOOOO00OOOOO000O0.smali:46-60)
    Gate: `op.O0OO0O00O0OOOO0OO0OO.get()` must be TRUE, else nothing is sent.
    When true it calls op.O000000O000000000OO0(), op.O000OO0OO0OOO00OO000() each tick
    and op.OOOOOOO00OOO0O00OOOO() every 16th tick; the individual commands are
    additionally phase-gated on `System.currentTimeMillis() % 3` (S/op.smali:497-515)
    and on the protocol version (S/op.smali:518-534).
```

**What is *not* read before the handshake:** no BLE/ESC/BMS version register read,
no serial read. The BLE firmware identity is exchanged *inside* the handshake
(the app compares/echoes `blt.4.17`, S/kn.smali / `S/hm.smali:592`), and the
scooter UID arrives in the handshake reply (§6).

### 1.1 The "session ready" flag

`op.O0OO0O00O0OOOO0OO0OO` (AtomicBoolean, initialised **false**, S/op.smali:355-359)
is the single gate between "connected" and "telemetry":

| Event | Effect | Citation |
|---|---|---|
| Family chosen is a no-auth one (`nn`,`bn`) | set **true** immediately | S/po.smali:248,361,717,830,425,513,601 |
| Family `gn` chosen | set **true** immediately | S/po.smali:315,384,497,585,671,784 |
| `io` handshake confirmed (ver==1) | set **true** | S/io.smali:211,280 |
| `kn` ECDH/login succeeded | set **true** | S/hm.smali:1522,1593; S/tp.smali:41 |
| disconnect / reset | set **false**, vp state → 0 | S/op.smali:4007-4015 |

---

## 2. The selector — which family applies

### 2.1 Inputs

| Input | Source | Citation |
|---|---|---|
| Model enum ordinal (`zm`) | `App.O00OO00OOOOOO0OOO00O()` | apktool-out/smali/app/peretti/m365tools/App.smali:153-165 |
| Protocol version int | `App.O000OO0OO0OOO00OO000()`; set from pref `LAST_SCOOTER_SCOOTER_PROTOCOL` | App.smali:139-151; S/ic.smali:382; S/nc.smali:123-153 |
| Remote Config longs | `nbsecft`, `nbsecf`, `ecdh_old` | S/po.smali:154,447,535,623,734 |

`getLong` returns 0 for a missing key, so **the defaults below are what a device
with no Remote Config override uses** (inferred, marked in §2.3).

### 2.2 Branch table (read)

Ordinals are `zm.ordinal()`. The switch is `S/po.smali:110-150`.

| ordinal(s) | ver==2, flag unset | ver==2, flag set (=1) | ver==1 | ver ∉ {1,2} |
|---|---|---|---|---|
| 2 | `fn` (key) S/po.smali:748-761 | `gn` (key)+ready S/po.smali:766-784 | `mn` (key) S/po.smali:797-809 | `nn`+ready S/po.smali:815-830 |
| 5 | `kn` (no key) S/po.smali:637-648 | `gn`+ready (`ecdh_old`) S/po.smali:653-671 | `mn` S/po.smali:684-696 | `nn`+ready S/po.smali:702-717 |
| 14 | `fn` (key) S/po.smali:549-562 | `gn`+ready (`nbsecf`) S/po.smali:567-585 | `bn`+ready S/po.smali:591-606 | `bn`+ready S/po.smali:591-606 |
| 16 | `kn` (no key) S/po.smali:281-292 | `gn`+ready (`ecdh_old`) S/po.smali:297-315 | `mn` S/po.smali:328-340 | `nn`+ready S/po.smali:346-361 |
| 17,18,19,20,21 | `kn` (no key) S/po.smali:194-204 | — (no flag consulted) | `mn` S/po.smali:217-229 | `nn`+ready S/po.smali:235-250 |
| 23 | `fn` (key) S/po.smali:461-474 | `gn`+ready (`nbsecf`) S/po.smali:480-497 | `bn`+ready S/po.smali:503-518 | `bn`+ready S/po.smali:503-518 |
| everything else | `fn` (key) S/po.smali:169-181 (`nbsecft`) | `gn`+ready S/po.smali:366-384 | `mn` S/po.smali:397-409 | `bn`+ready S/po.smali:415-430 |

Family meanings: `kn` = Xiaomi mible ECDH (§5); `fn`/`gn` = NinebotSecureCryptor
chained AES, four-argument constructor receives the SHA-256(MAC) token (§4);
`mn` = RC4 session keyed by that token (§3.5); `nn`/`bn` = **no cryptography and
no handshake at all** (three-argument constructors, §8.1).

### 2.3 The `ecdh_old` / `nbsecf` / `nbsecft` polarity (read — the headline)

`cmp-long` is followed by `if-nez … :cond_X`, i.e. the **"set" branch is taken when
the flag equals 1**:

* ordinal 16, ver==2, `ecdh_old` (`S/po.smali:267-315`): flag **== 1 → `gn`**
  (`S/po.smali:298-310`); flag **!= 1 → `kn`** (`S/po.smali:282-292`).
* ordinal 5, ver==2, same key (`S/po.smali:623-666`): same polarity.
* ordinals 2/14/23/default, ver==2, `nbsecf`/`nbsecft`: flag **== 1 → `gn`**;
  flag **!= 1 → `fn`**.

So the flag's name is misleading in both directions: `ecdh_old = 1` **removes** the
ECDH transport in favour of a static-key one. Because Remote Config is absent on a
stock install, the **default for the ECDH-capable ordinals is the ECDH transport
`kn`** (inferred from `getLong` defaulting to 0 plus the polarity above — the
inference is the only unread step here).

### 2.4 What the app does *not* select on

There is no negotiation: the app never asks the scooter which protocol it speaks
before choosing. The version int is taken from a **preference that the app itself
wrote** (`LAST_SCOOTER_SCOOTER_PROTOCOL`, S/ic.smali:382, S/nc.smali:123-153), i.e.
the family is remembered from a previous session or from the scan-time model
detection. If neither is set, the value is 0 and the app takes the **no-auth**
`bn`/`nn` path.

---

## 3. Protocol version 1 — the RC4 "AuthToken" handshake (`io` / `yn` / `vp` / `fo`)

Class roles: `fo` = read chaining + token store; `yn` = per-device token setup;
`io` = the 3-state machine; `vp`/`vp$…` = the cipher; `mn` = the frame codec.

### 3.1 Cipher — it is RC4, not AES (read)

`S/vp$OO00000OOOOOOOO0000O.smali` is a textbook RC4:

* S-box init and KSA: `S/vp$OO00000OOOOOOOO0000O.smali:26-147` (i↔j swap at
  `:110-119`, `j = (j + S[i] + K[i mod keylen]) & 0xff`).
* PRGA / `crypt`: `S/vp$OO00000OOOOOOOO0000O.smali:223-293`
  (`i=(i+1)&0xff`, `j=(j+S[i])&0xff`, swap, `out = in ^ S[(S[i]+S[j])&0xff]`).
* Key length guard `1..256` with the message "key must be between 1 and 256 bytes"
  (`S/vp$OO00000OOOOOOOO0000O.smali:32-53,130-138`).
* A second, non-cryptographic helper `OOOOOOO0OOOOO0O00OO0([B,[B)[B`
  (`S/vp$OO00000OOOOOOOO0000O.smali:149-219`) builds a 12-byte value:
  `key[0..4] XOR other[0..4] ‖ key[4..12]`.

### 3.2 The two 8-byte tokens

`vp.<init>(byte[] key, byte[] p2, byte[] p3)` (`S/vp.smali:29-176`) derives:

| field | value | citation |
|---|---|---|
| `OO0O0OOO00O00OOO0000` | the 12-byte session key (arg 1) | S/vp.smali:58 |
| `O0OOOOOO00OOOOO000O0` (token 1) | `p2[0],p2[2],p2[5],p3[0],p3[0],p2[4],p2[5],p2[1]` | S/vp.smali:64-120 |
| `O00OOO0O00O00OOO0000` (token 2) | `p2[0],p2[2],p2[5],p3[1],p2[4],p2[0],p2[5],p3[0]` | S/vp.smali:123-173 |

`p2`/`p3` are the "LoginConfirmationKey"/"AuthToken" material; they are assembled in
`yn` (§3.3). Index pattern (0,2,5,4,1) plus one or two bytes of `p3` is exactly the
Ninebot ESx-style byte-scrambled token derivation.

### 3.3 `yn` — where the session key and the tokens come from

`S/yn.smali:36-195`, invoked from `fo` (`S/fo.smali:69-72`):

1. Gate: `op.O0O000OOOO0000OOOO0O()` must be true **and**
   `op.OOO0O0OO0000O0O0000O` (the byte[] stored by `fo` from the 00000002 read)
   must be non-null; otherwise the handshake is skipped entirely
   (`S/yn.smali:50-61`).
2. `App.OO0OOOOOOOO00000O0O0()` (= MAC address) → `.replace(":","")` → **hex-decoded
   to 6 bytes** → **reversed** (`S/yn.smali:65-117`; hex decoder `S/v3.smali`
   `OOO00O0OO00OOO0OOOO0(String)[B`).
3. `new vp(Arrays.copyOf(op.O00000OO0OOOOO00OOOO, 12) /* session key */,
   reversedMacBytes /* 6 B */, op.OOO0O0OO0000O0O0000O /* char 00000002 reply */)`
   (`S/yn.smali:121-135`).

So the 12-byte RC4 session key is the **first 12 bytes of SHA-256(MAC)**, the token
scramble uses the **colon-stripped, byte-reversed MAC**, and the third input is the
2+-byte value read from characteristic 00000002.

### 3.4 The 3-state machine `io`

`S/io.smali:36-336`, state in `vp.OO00000OOOOOOOO0000O`:

| state | what is sent / checked | citation |
|---|---|---|
| 0 | computes `stored = RC4(sessionKey, first 4 notification bytes)` and saves it in `vp.OO00O0O0O0000000000O`; builds `mix = sessionKey[0..4] XOR stored[0..4] ‖ sessionKey[4..12]`; **writes `RC4(mix,"09acbf93")` to 00000001**; state → 1; log `Write LoginConfirmationKey: ` | S/io.smali:70-134; magic S/op.smali:270-281; mix S/vp$OO00000OOOOOOOO0000O.smali:149-219 |
| 1 | success iff `notification[0..4] == RC4(mix,"C9589A36")` → `vp.OOOOOOO0OOOOO0O00OO0=true`, ready=true, log **`Handshake set`** | S/io.smali:154-221; magic S/op.smali:283-294 |
| 1 fail | state → 2; `op.OO00OOOOO00OO00OOOO0(true)` (retry/reset) | S/io.smali:224-234 |
| 2 | success iff `RC4(token1, sessionKey)` equals `RC4(token2, notification)` → ready=true, log **`Write AuthToken: `**, then **write `RC4(sessionKey,"92ab54fa")` to 00000001** | S/io.smali:237-332; magic S/op.smali:257-268 |

`op.OO00OOOOO00OO00OOOO0(false)` (S/io.smali:331) is the retry/reset call
(`S/op.smali:3767`). Three 4-byte magic constants are consumed —
`09acbf93` (write, state 0), `C9589A36` (expected, state 1) and `92ab54fa` (write,
state 2); two further constants in the same table, `90CA85DE` (S/op.smali:231-242)
and `00BC43CD` (S/op.smali:244-255), are loaded but not used by `io`.

So in this family the app sends **two** 4-byte magic constants — `09acbf93`
(LoginConfirmationKey) and `92ab54fa` (AuthToken) — each RC4-encrypted under a key
derived from the MAC, and validates the scooter's reply against its own derivation.
Note the "AuthToken" here is a *4-byte command*, not the 16-byte SHA-256 value of §7.

### 3.5 `mn` — the version-1 frame codec

`mn` is a 4-argument transport that receives the 16-byte SHA-256(MAC) token
(`S/po.smali:217-229` etc.) and stores it at `S/mn.smali:39`. It uses it as an
**RC4 key**: a fresh `vp$OO00000OOOOOOOO0000O` is constructed **per frame** and the
payload is streamed through it — `S/mn.smali:702-712` (send) and `S/mn.smali:232`
(receive). Because the RC4 keystream is restarted for each frame, identical command
payloads produce identical ciphertext on the wire (a real weakness; read).

> **Correction to reports/05-write-commands.md:** the token in `mn` is an **RC4**
> key (`vp$OO00000OOOOOOOO0000O` = RC4, `S/vp$OO00000OOOOOOOO0000O.smali:223-293`),
> not an AES key, and the helper involved is *not* the XOR helper
> (`vm.OO0O0OOO00O00OOO0000`, `S/vm.smali:91-126`).

---

## 4. Protocol version 2, static-key family — NinebotSecureCryptor (`fn`/`gn` + `an`)

`an` (= "NinebotSecureCryptor", `toString()` at `S/an.smali:335-384`) is the
chained-AES engine:

| Item | Value | Citation |
|---|---|---|
| Fixed key (constant across devices) | `97 CF B8 02 84 41 43 DE 56 00 2B 3B 34 78 0A 5D` | S/an.smali:33-40 |
| Cipher | `AES/ECB/NoPadding`, `Cipher.init(mode, SecretKeySpec(key,"AES"))` | S/an.smali:124-143 |
| Per-state key derivation | `key = HexDecode(HexEncode(SHA-1(state32 ‖ fixedKey16)))[0..16]` | S/an.smali:89-143 (SHA-1 at S/jn.smali:111-152) |
| Chain states | three 32-byte buffers + a state index 0/1/2 | S/an.smali:52-67, 194-259 |
| Encryption | `Cipher.doFinal(data)` (ECB, no IV) | S/an.smali:260-270 |
| Sizing | one 28-byte buffer printed as "ESC SN" | S/an.smali:74-79, 365-375 |

The three states are selected by `an.O0OOOOOO00OOOOO000O0(I)`
(set by `cm`/`dm`/`em`/`yl`: `S/cm.smali:68`, `S/dm.smali:56,151`, `S/em.smali:38`,
`S/yl.smali:56`) and consumed by `an.OO00000OOOOOOOO0000O([B,I)`
(`S/an.smali:194-270`), which is called only from `fn` and `gn`
(e.g. `S/fn.smali:282,471,585,904,…`; `S/gn.smali:323,515,635,…`).

`fn` and `gn` are structurally identical (same five methods, same cryptor); the
difference is the reactive plumbing (`gn` additionally uses `lw0`/Completable,
`S/gn.smali:859`). Both override the register entry
(`S/fn.smali:1525`, `S/gn.smali:1827`).

`fn`'s register method writes the 30-byte handshake reply into the cryptor's
primary chain buffer (`S/fn.smali:1584`, `S/gn.smali:1904`), matching
reports/05's finding that the reply to command **0x5B** on BLE address **0x21** is
30 bytes = 16-byte key + 14-byte UID. The four-argument constructor's token
(SHA-256(MAC)[0..16]) is stored at `S/fn.smali:59`; **it is never read again inside
`fn`** — the chained-AES key comes from the reply, not from the token (read).

This is the family the fork calls the `0x5B`/`0x5C`/`0x5D` pairing: the app's
`an` states plus `fn` are that state machine (§8.2).

---

## 5. Protocol version 2, ECDH family — Xiaomi "mible" (`kn`)

### 5.1 Where the ECDH code lives

`S/ln.smali:10-17` carries the Kotlin metadata
`c = "app.peretti.m365tools.model.XiaomiECDH$performHandshake$timedObservable$2$job$1"`,
and its only instantiator is `S/hm.smali:997`, whose `this$0` field is typed `kn`
(`S/hm.smali:39`, created at `S/rm.smali:83`). R8 therefore merged the class
`XiaomiECDH` **into `kn`**: the whole ECDH handshake is `S/hm.smali`, backed by
`kn`'s crypto helpers. `kn` is selected for model ordinals 5, 16, 17–21 when
protocol version == 2 and `ecdh_old != 1` (§2.2).

### 5.2 Ordered handshake (read)

Entry: `S/kn.smali:2689-2753` reads characteristic **00000004** and dispatches the
coroutine `hm`.

```
 A. kn.O000000O000000000OO0()Z  — S/kn.smali:146-222 (fast path / "login")
    A0. read pref "devToken"+<suffix>; absent → return false → full pairing runs.
    A1. write "24000000" to char 00000010                                    :120
    A2. write "0000000b0100" to char 00000019; read 2 B (0.2 s) == "0101"    :121-128
    A3. 16 random bytes; write "0100"‖rand16; read 2 B == "0100";
        read 4 B == "000d0100"                                               :129-136
    A4. write "00000101"; read 16 B (2.0 s) = scooter random                 :137-140
    A5. write "00000100"; read 4 B == "000c0200"                             :141-146
    A6. HKDF(ikm = devToken, salt = rand16‖scooterRand16,
             info = "mible-login-info", 64 B)                                :147
        → [0:16)  sessionScooterKey   [16:32) sessionLocalKey
          [32:36) sessionScooterIV    [36:40) sessionLocalIV                 :149-152
    A7. localHMAC   = HMAC-SHA256(sessionLocalKey,   rand16‖scooterRand16)   :177-186
        scooterHMAC = HMAC-SHA256(sessionScooterKey, scooterRand16‖rand16)   :187-196
    A8. write "00000101"; read 32 B (0.3 s) and compare with scooterHMAC      :197-205
        MISMATCH → DELETE the "devToken" preference and return false          :203
    A9. write "00000100", "0000000a0200"; read 2 B == "0101"                  :206-214
    A10.write "0100"‖localHMAC[0:18], "0200"‖localHMAC[18:32]                 :215-220
    A11.read 2 B == "0100" and 4 B == "21000000"                              :221
    Success → ready flag set (S/tp.smali:41, S/hm.smali:74-79).

 B. Full pairing (only if A returned false) — S/hm.smali:83-1531
    B1. KeyPairGenerator "EC" + ECGenParameterSpec("secp256r1"); generateKeyPair
                                                                   :86-108
    B2. x = ECPoint.affineX.toByteArray() trimmed to 32 B (drop BigInteger sign
        byte via copyOfRange(1..len)); same for y; clientPub = x‖y (64 B)  :144-311
    B3. write "a2000000"                                              :325-336
    B4. write "00000101"; read 4 B (0.2 s) ∈ {"00000200","00000100"}   :338-393
    B5. write "0100"‖pub[0:18], "0200"‖pub[18:36], "0300"‖pub[36:54],
        "0400"‖pub[54:64]                                             :462-471
    B6. read 2 B (0.2 s) == "0100"; start the 500 ms ticker ln (S/ln.smali:87-159)
        ; read 4 B (2.4 s) == "00030400"                              :473-475
    B7. write "00000101"; sleep 100 ms; read 64 B (0.1 s) = scooter x‖y :476-482
    B8. peerPub = 0x04‖those 64 B; length check 65; ECPublicKeySpec(pt, params)
        from KeyFactory "EC"; KeyAgreement.getInstance("ECDH"); init(our priv);
        doPhase(peerPub,true); generateSecret("ECDH") = 32 B shared secret
                                                                     :492-515
    B9. HKDF(ikm = sharedSecret, salt = 16 zero bytes,
             info = "mible-setup-info", 64 B)                        :517-519
        → [0:12) devToken   [12:28) unused/login key   [28:44) devID key
                                                                     :520-522
    B10.AES-CCM(key = [28:44), nonce = 101112131415161718191a1b,
             data = device-info blob (e.g. "blt.4.17"+0x00+11 random chars),
             aad = "devID", tag arg = 0x20)                          :528-531
    B11.write "000000000200"; read 2 B (0.2 s) == "0101"             :532-537
    B12.write "0100"‖ct[0:18], "0200"‖ct[18:]                        :538-543
    B13.read 2 B (0.3 s) == "0100"                                   :544-546
    B14.write "13000000"; read 4 B (0.3 s) == "11000000"              :547-551
    B15.STORE pref ("devToken"+suffix) = hex(devToken 12 B)           :552
    B16.re-run A to verify; success → ready flag, else sleep 2000 ms and
        report failure to the UI callback                            :553-561, 1590-1610
```

Helpers: HKDF = `S/kn.smali:2490-2567` (`hkdf.extractAndExpand(salt, ikm, info, 64)`
via `uq`/HmacSHA256); AES-CCM encrypt = `S/kn.smali:1771-1844`; the
`read(len, seconds)` primitive = `S/kn.smali:2415-2488` (polls the receive buffer
`kn.OOOOO00O0O0OO00OOO0O`, returns `byte[0]` on timeout); frame builder =
`S/kn.smali:1046`; command send = `S/kn.smali:1846`.

### 5.3 Session key material in the ECDH family

| Derived item | Bytes | Role | Citation |
|---|---|---|---|
| `devToken` | HKDF[0:12] | persisted per device; sole input to the login HKDF | S/kn.smali:520,552 |
| devID key | HKDF[28:44] | AES-CCM key that encrypts the device-info blob | S/kn.smali:522,530 |
| (unused) | HKDF[12:28], [44:64] | logged but not used by this class | S/kn.smali:521,523-526 |
| sessionScooterKey / sessionLocalKey | login HKDF[0:16) / [16:32) | HMAC-SHA256 mutual auth | S/kn.smali:149-150 |
| sessionScooterIV / sessionLocalIV | login HKDF[32:36) / [36:40) | 4-byte IV component of the per-direction frame nonce | S/kn.smali:151-152 |

---

## 6. Cryptographic parameters

| Parameter | Value | Where | Confidence |
|---|---|---|---|
| Curve | NIST P-256 (`secp256r1`), via JCA `EC` | S/hm.smali:86-102 | read |
| Ephemeral keypair | generated per handshake, not stored | S/hm.smali:108 | read |
| Client public key wire form | 64 B = affine X ‖ Y, each trimmed to 32 B (leading sign byte removed); sent in four 18/18/18/10-byte chunks prefixed `0100`,`0200`,`0300`,`0400` | S/hm.smali:210-311, 462-471 | read |
| Peer public key wire form | 64 B read in one 64-byte read; `0x04` prepended locally → 65 B uncompressed point | S/hm.smali:481-499 | read |
| Key agreement | `KeyAgreement.getInstance("ECDH")`, `doPhase(peer,true)`, `generateSecret("ECDH")` | S/hm.smali:1208-1225 | read |
| Shared secret | 32 B (`sharedKey.encoded`) | S/hm.smali:1238 | read |
| KDF | HKDF-HMAC-SHA256, extract-and-expand, 64 B output | S/kn.smali:2490-2567 | read |
| KDF info (pairing) | `mible-setup-info` | S/hm.smali:1256 | read |
| KDF info (login) | `mible-login-info` | S/kn.smali:147 | read |
| KDF salt (pairing) | 16 zero bytes `00000000000000000000000000000000` | S/hm.smali:1246 | read |
| KDF salt (login) | `rand16 ‖ scooterRand16` (32 B) | S/kn.smali:147 | read |
| Session AEAD | `AES/CCM/NoPadding` | S/kn.smali:1787 | read |
| AEAD key length | 16 B used here (HKDF slices) | S/hm.smali:522, S/kn.smali:149-152 | read |
| AEAD tag length argument | `0x20` passed as `GCMParameterSpec(tLen, nonce)` tLen | S/kn.smali:1803-1807, S/hm.smali:1335 | read (value) / **inferred** (meaning) |
| AEAD tag length, effective | 4 bytes — if Conscrypt reads tLen in bits, 0x20 = 32 bits; the fork independently uses a 4-byte tag (§8.3) | S/kn.smali:1805 vs repo `ninebot-ble/src/mi_crypto.rs:14` | inferred |
| Pairing AEAD nonce | fixed `10 11 12 13 14 15 16 17 18 19 1A 1B` | S/hm.smali:1312 | read |
| Pairing AEAD AAD | ASCII `devID` | S/hm.smali:1325 | read |
| Frame AEAD nonce (per direction) | 4-byte IV from login HKDF (sessionScooterIV / sessionLocalIV) + a counter; counter width/increment not re-derived here | S/kn.smali:151-152 (IVs) | inferred |
| Legacy chained-AES cipher | `AES/ECB/NoPadding`, no IV | S/an.smali:124-143 | read |
| Legacy fixed key | `97CFB802844143DE56002B3B34780A5D` | S/an.smali:33 | read |
| Legacy per-state key | first 16 **ASCII hex characters** of `SHA-1(state32 ‖ fixedKey16)` | S/an.smali:106-122, S/jn.smali:111-152 | read |
| Legacy chain states | 3 × 32-byte buffers, index 0/1/2 | S/an.smali:52-67, 194-259 | read |
| Version-1 stream cipher | RC4 (KSA + PRGA), key 1–256 B | S/vp$OO00000OOOOOOOO0000O.smali:26-147,223-293 | read |
| Version-1 session key | `SHA-256(MAC)[0..12]` | S/po.smali:41-93, S/yn.smali:123-133 | read |
| Version-1 token inputs | `p2` = MAC with `:` removed, hex-decoded (6 B), reversed; `p3` = bytes read from char 00000002 | S/yn.smali:65-135, S/fo.smali:54 | read |
| AuthToken (16 B, static families) | `SHA-256(<BLE MAC string>)[0..15]` | S/po.smali:41-93 | read (input) / see §7.1 for the exact string form |
| Checksum | 16-bit one's-complement of the byte sum (`sum(data) & 0xffff`, XOR `0xffff`) | S/vm.smali:56-89 | read |

---

## 7. Token and registration semantics

### 7.1 `AuthToken` — the 16-byte static token

* Produced exactly once, at `S/po.smali:93`, from
  `SHA-256(App.OO0OOOOOOOO00000O0O0())`, hex-encoded and re-decoded, truncated to 16 B.
* `App.OO0OOOOOOOO00000O0O0()` returns the field `App.O00000OOO0O0O00O0O0O`
  (`apktool-out/smali/app/peretti/m365tools/App.smali:280-292`), default `""`
  (App.smali:84-91), written **only** by
  `App$OO00000OOOOOOOO0000O.OO00O0O0O0000000000O(String)`
  (`App$OO00000OOOOOOOO0000O.smali:379-391`) whose parameter is asserted under the
  name **`macAddress`** (`:382`).
* Its two callers pass the connected device's MAC:
  `S/ic.smali:371-379` (Kotlin assertion string `result.bleDevice.macAddress`) and
  `S/nc.smali:75-84` (local name `mDeviceAddress`). The value originates in
  `S/tn$OO00000OOOOOOOO0000O.smali:83-86` as `lb0.O00OOO0O00O00OOO0000()` =
  `RxBleDevice.getMacAddress()` = `BluetoothDevice.getAddress()`
  (implementation in `apktool-out/smali_classes2/`, e.g. `S/ac0.smali:91`).
* **No transformation** (no case change, no separator strip) happens between
  `getAddress()` and the digest input, so the input is the platform form
  `AA:BB:CC:DD:EE:FF`. *Inferred*: the app itself does not normalise, but the exact
  byte string is unverified against hardware.
* Scope: it is **per device but not per pairing** — anyone who knows the MAC can
  compute it. It is *not* stored anywhere (only recomputed) and *not* sent as such;
  it is used as key material for `fn`/`gn` (§4) and as the RC4 key in `mn` (§3.5).
* In the ECDH family the analogous persisted secret is the 12-byte `devToken`
  (preference key `"devToken" + suffix`, suffix `op.OO0O00O0OO00OOO00O00`,
  S/kn.smali:552; deleted on auth mismatch at S/kn.smali:203).

### 7.2 `SCOOTER_PIN_CODE`

* The literal exists in the decrypted string table and appears **only** in
  `S/gq.smali:22-24`, inside the preference-key holder class `gq` (whose getters
  return keys such as `SCOOTER_LAST_MAC` at S/gq.smali:512, `AUTO_CONNECT` at
  S/gq.smali:582, `NOTIFICATION_FLAGS` at S/gq.smali:493). It is part of the app's
  preference-name inventory.
* **No handshake path reads it.** The version-1 handshake's token material comes
  from the MAC and a value read from the scooter (char 00000002), not from a stored
  PIN, and the ECDH path uses `devToken`. The PIN-facing UI strings
  (`mPinCode`, `Pincode read from Preferences is:`,
  `apktool-out/smali/app/peretti/m365tools/ScooterInformationActivity.smali:2578,6047`)
  are in the activity layer.
* **Conclusion (read):** `SCOOTER_PIN_CODE` is not part of any authentication flow
  analysed here; treating it as the protocol secret would be a mistake.

### 7.3 What happens on a mismatch

* ECDH login mismatch → the `devToken` preference is **deleted**
  (`S/kn.smali:202-205`) and full pairing (button press) is required again.
* Version-1 mismatch → the state machine advances to state 2 and only then writes an
  AuthToken (`S/io.smali:224-234`); there is no retry counter in `io` — the retry
  helper is `op.OO00OOOOO00OO00OOOO0(Z)` (`S/op.smali:3767`).
* Nothing is persisted for `fn`/`gn`; their session lives entirely in the `an`
  instance (`S/fn.smali:16-18`), so a failed chain simply restarts from state 0.

---

## 8. What is skipped, and what is absent

### 8.1 Plaintext / no-auth paths exist (read)

`nn` and `bn` are three-argument transports with **no key material and no crypto
import**. They are selected for several model/version combinations, and in those
cases `po` sets the ready flag at selection time: `S/po.smali:248`(nn),
`:361`(nn), `:717`(nn), `:830`(nn), `:425`(bn), `:513`(bn), `:601`(bn).
The handshake block in `po` is never entered for them (§1 step 6c), so **those
models get no authentication whatsoever**, and telemetry starts immediately.

### 8.2 The `0x5B`/`0x5C`/`0x5D` chained pairing is present (read)

Yes — as `an` (NinebotSecureCryptor) + `fn`/`gn` + the `cm`/`dm`/`em`/`yl` helpers.
The decision to use it is the model-ordinal/protocol-version/flag combination of
§2.2, and the 30-byte reply to `0x5B` on BLE address `0x21` fills the state-0 chain
buffer (`S/fn.smali:1584`, `S/an.smali:52-67`). The app never uses literal
`0x5B`/`0x5C`/`0x5D` byte constants in the protocol classes — they arrive as part
of the frame built by `fn` (consistent with reports/05).

### 8.3 There is no "Encryption2"

No AES-CTR / CBC-MAC code exists in the app's protocol classes. The evidence is the
`Cipher.getInstance` census: the only strings under `dalvik/O/O/a/b/O/c/b/O/o/` are
`AES/CCM/NoPadding` (`S/kn.smali:1787`) and `AES/ECB/NoPadding` (`S/an.smali:124`);
`AES/CTR/...`, `AES/GCM/...`, `AES256_CMAC`, `AES128_CTR_HMAC_SHA256`, etc. occur
only inside `com/google/android/gms/internal/firebase-auth-api/**` (Google Tink) and
AndroidX (`apktool-out/smali_classes2/`), and the app's only AES primitive outside
`kn` is `an`'s ECB `doFinal` (`S/an.smali:260-270`), which is reached exclusively
from `fn`/`gn` (§4). The fork's `0x5A 0xA5` sync constant has no counterpart in the
app's handshake classes either. *Caveat:* this is an absence-of-evidence argument
from a call-site census; a hand-rolled CTR/CBC-MAC using `an`'s ECB block mode
cannot be excluded with certainty without tracing every `fn`/`gn` frame, which was
not done here.

### 8.4 Skipped steps

* No handshake for `nn`/`bn` models (§8.1).
* For protocol version 1, `po` does **not** call the session object's register entry
  — the `if (version == 2)` guard at `S/po.smali:1358-1362` is the only caller, and
  `vm.OOOOOOO0OOOOO0O00OO0` throws `NotImplementedException` by default
  (`S/vm.smali:167-178`).
* The version-2 register entry is likewise never called for version 0.
* `kn`'s fast path skips the whole ECDH when a valid `devToken` exists
  (`S/hm.smali:63-81` → `goto_a`) — the common case on reconnection.
* The app never sends a "which protocol do you speak?" probe before choosing; the
  choice is made from remembered state only (§2.4).

---

## 9. `decrypt2` is a false lead

`apktool-out/smali/app/peretti/m365tools/MainActivity.smali:1421` loads the string
`decrypt2` as the **Intrinsics null-check message** for a
`new String(byte[], charset)` inside the **PiracyChecker** licence decode:
`PiracyChecker` is constructed at `MainActivity.smali:990-1005`, its callbacks are
installed at `:1011-1015`, and the base64 licence/signature blobs are decoded at
`:1394-1430` (the same basic block that contains `:1421`). It has no relationship to
BLE, the handshake, or any protocol cipher. Nothing in the app's protocol classes
references it.

---

## 10. Comparison with M365-Rokid-HUD

The fork is **ahead of this report** in two places and **behind** in one. Details:

| Aspect | M365 Tools | Fork | Verdict |
|---|---|---|---|
| Xiaomi mible ECDH | P-256 ECDH + HKDF-SHA256 (`mible-setup-info`) + AES-CCM, token = HKDF[0:12], devID key = HKDF[28:44], AAD `devID`, nonce `101112131415161718191a1b` (S/hm.smali:1256-1335) | Identical: `mi_crypto.rs:14` `Ccm<Aes128,U4,U12>`, `:44-47` the same 12-byte nonce, `:49-62` AAD `devID`, `:64-79` both info strings, `:100-140` `token = derived_key[0..12]`, `a = derived_key[28..44]` | **Agreement, byte for byte** |
| mible login | `mible-login-info` HKDF → 2 keys + 2 × 4-byte IVs, HMAC-SHA256 both ways, devToken deleted on mismatch (S/kn.smali:146-222) | `mi_crypto.rs:140` `calc_login_did`, `LoginKeychain`, `hash()` HMAC-SHA256 (`:81-90`) | **Agreement** |
| CCM tag length | passes `0x20` as the flag's tLen (S/hm.smali:1335) | fixed 4-byte tag (`mi_crypto.rs:14` `U4`) | **Consistent only if 0x20 means 32 bits**; must be confirmed on hardware |
| Frame layout / checksum | checksum = one's complement of the 16-bit byte sum (`S/vm.smali:56-89`); counter/IV model consistent with 2-byte counter + 4-byte IV | `HEADER = [0x55,0xab]`, size byte, 2-byte LE counter, ciphertext+tag, crc16 (`mi_crypto.rs:205-298`); nonce = iv(4)‖0‖counter(2)‖0 (`:233-240`) | **Agreement** |
| Chained-AES NinebotSecureCryptor | `an`: fixed key `97CFB802844143DE56002B3B34780A5D`, `AES/ECB/NoPadding`, key = first 16 ASCII hex chars of SHA-1(state32‖fixedKey) (`S/an.smali:33,124,106-122`) | `ninebot_legacy.rs:52-56` `FW_DATA` = the same 16 bytes; `NinebotCryptoCipher.kt:117-135` `deriveSha1Key`; `:143-149` `aesEcbEncryptBlock`; `:47-71` SYNC `5A A5`, nonce tags `0x01`/`0x59` | **Agreement** |
| 0x5B/0x5C/0x5D state machine | `fn`/`gn` + `an` chain states 0/1/2, 30-byte 0x5B reply → state-0 buffer (`S/fn.smali:1584`) | `NinebotHandshake.kt:53-83` `ADDRESS_BLE 0x21`, `ACTION_PRE_COMM 0x5B`, `SET_PWD 0x5C`, `AUTH 0x5D`, reply length 30, `UID_OFFSET 16 / LEN 14`, `BLE_DATA_OFFSET 7 / LEN 16` | **Agreement** |
| RC4 version-1 flow (`io`/`vp`/`mn`) | present, with two magic commands `09acbf93` / `92ab54fa` and MAC-derived key (S/io.smali, S/vp$…, S/mn.smali:702-712) | **Not present** in the files listed for this comparison (no RC4 module, no `09acbf98`/`92ab54fa`) | **Fork gap** |
| Plaintext path | `nn`/`bn`: no crypto, handshake skipped, ready flag set at selection (S/po.smali:248,425) | `PlaintextRegisterSession.kt` exists, but it is a *Kotlin* register session with `source=0x3E`, `destination=0x20` and `FrameCodec` 55AA/5AA5 framing, i.e. it frames plaintext reads itself | **Different mechanism**: the app's plaintext classes reuse the same per-model frame builders, the fork uses a generic register codec |
| Family selection | model ordinal × protocol-version int × Firebase flags, per §2.2 | `ProtocolProbe.kt`/`ScooterModelRegistry.kt` decide at runtime (not reviewed here) | **Selection logic lives elsewhere in the fork**; the app's per-ordinal table is the missing artefact |
| `AuthToken` semantics | SHA-256(MAC)[0..15], per device, recomputed, never stored; RC4 key in `mn`, KDF input in `fn`/`gn` (S/po.smali:93) | `mi_crypto.rs`'s `AuthToken` is a **different** thing: 12 bytes, HKDF[0..12] from the ECDH secret (`mi_crypto.rs:90,100-140`) | **Name collision — do not merge these two concepts in the fork** |
| Encryption2 (AES-CTR + CBC-MAC) | absent from the app (§8.3) | implemented (`encryption2.rs:57-175`) | Fork exceeds the app — fine, but it is *not* derived from M365 Tools |

Two differences worth acting on:

1. The app's **version-1 RC4 family is unimplemented in the fork**, yet it is the
   family the app uses for protocol version 1 devices.
2. The fork's `AuthToken` type name collides with the app's `AuthToken` string
   (S/io.smali:285). They are unrelated values.

---

## 11. Unverified / needs hardware

No scooter and no phone are attached. **Nothing in this report has been confirmed
against real traffic.** Specifically unverified:

1. **The exact MAC string fed to SHA-256.** The code path is read (`getAddress()` →
   `App.OO0OOOOOOOO00000O0O0()`), but whether Android returns upper-case
   colon-separated text on the target device, and whether any OEM/custom scan path
   rewrites it, is untested. A wrong case or separator yields a completely
   different `AuthToken` and a failed handshake.
2. **The AES-CCM tag length.** The app passes `0x20` into `GCMParameterSpec`
   together with `AES/CCM/NoPadding` (S/kn.smali:1803-1807). Conscrypt's CCM
   interpretation of that integer (bits vs bytes) is *inferred* to mean 32 bits =
   4-byte tag, matching the fork's `U4`. If it actually means 4 bytes, the two
   implementations disagree by 12 bytes of tag. Only a live handshake can settle it.
3. **The per-frame nonce/counter layout for the `kn` session** is inferred from the
   4-byte IV fields (S/kn.smali:151-152) plus the fork's verified model. The app's
   own frame builder for `kn` frames was not fully traced; the counter's increment
   rule, its endianness, and whether responses use the scooter IV as assumed are
   unverified.
4. **`ecdh_old`/`nbsecf` defaults.** The polarity (`== 1` → static-key transport) is
   read; that Remote Config is empty on a stock install (making `kn` the default for
   ordinals 5/16/17-21) is inferred from `getLong` semantics only. The live values
   for these flags today are unknown.
5. **The model-enum ordinal → model-name mapping.** This report uses raw ordinals
   (2, 5, 14, 16, 17-21, 23); which scooter each ordinal denotes is AREA 1's
   deliverable and is not asserted here.
6. **`fn` vs `gn` equivalence.** They are structurally identical and both use `an`;
   whether they differ in wire behaviour (timeouts, retry, MTU, frame ordering) was
   not established, so "same protocol, different reactive wrapper" is an inference.
7. **The 0x5B handshake's exact byte sequence** is taken from reports/05 and the
   fork's Kotlin, not re-derived here; the cryptor's role (which chain state is used
   in which pairing step) is read only at the level of "state 0 is filled from the
   0x5B reply".
8. **`mn`'s per-frame RC4 restart** is read from the code shape (a fresh cipher
   object inside the frame builder) but its observable consequence (keystream reuse
   on the wire) is not confirmed.
9. **The 00000014/00000002/00000001 characteristic semantics for version 1** are
   read as "read → read → read/write handshake"; what the scooter actually returns
   and whether 00000002 is a per-device seed is unknown.

Static-analysis-only scope: **cryptographic constants and protocol structure are
facts and may be reimplemented freely; the app's source, assets and string tables
are not to be copied.** No code from `app.peretti.m365tools` was reproduced here —
only constants, byte layouts, orderings and control-flow facts.
