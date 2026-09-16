# Scooter BLE Protocol Families | 滑板車 BLE 協議家族

> **Status**: research reference. Every claim below is tagged with a confidence
> marker. Read the markers before implementing anything from this document.
>
> | Marker | Meaning |
> | --- | --- |
> | ✅ **Verified** | Confirmed against a public reference implementation or the canonical register-map source. |
> | 📗 **Documented** | Stated in public project documentation, but not independently cross-checked. |
> | ⚠️ **Unverified** | Plausible / from a single community source. **Must be confirmed on hardware before use.** |
> | ⛔ **Unknown** | No public information found. Do not guess. |

---

## 1. Why this document exists

The app originally assumed there is one scooter protocol — the Xiaomi M365
`fe95` encrypted handshake. That assumption is wrong in a way that is not
obvious from the scan results, and it drives the whole multi-model design.

Two findings change the architecture:

1. **The GATT service UUID is not a protocol discriminator.** Xiaomi M365,
   Ninebot ESx/Max/E/F/T15 *and* some brand-new Segway models all expose the
   Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`. Some devices
   expose both Segway's own service and NUS but answer only on NUS — a Max G3
   does exactly this. Others expose NUS alone and are indistinguishable from a
   classic scooter until a handshake is attempted. ✅ Verified
   ([ha-ninebot](https://github.com/BobMcGlobus/ha-ninebot))

   **Consequence: model detection must be probe-and-fallback against the live
   device, with the result cached per MAC. A scan-time lookup table cannot work.**

2. **The advertised name is also not a discriminator.** `MIScooter` only covers
   the Xiaomi lineage. Ninebot advertises `NBScooter`, `NB<serial>`,
   `Ninebot-XXXX`, `S1D<serial>` and `Segway`. ✅ Verified
   ([py9b](https://github.com/Informatic/py9b) filters `MISc`, `NBSc`, `JP2`, `Seg`)

---

## 2. The four wire generations

Two vendor lineages, four incompatible wire formats. ✅ Verified
([segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble),
[miauth](https://github.com/dnandha/miauth),
[NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto))

| Generation | Frame header | Crypto | Covers |
| --- | --- | --- | --- |
| **Protocol 1** | `55 AA` | none / XOR | very old M365 (BLE < 1.2.9) |
| **Protocol 2** (legacy NB) | `5A A5` | none / XOR | older Ninebot |
| **Xiaomi Mi auth** | n/a — auth on `fe95`, data on NUS | ECDH P-256 + HKDF-SHA256 + AES-128-CCM | **M365, Pro, Pro2, 1S, Lite, Mi3** |
| **NinebotCrypto** | `5A A5` + `5B/5C/5D` handshake | AES chained, SHA-1 key derivation, `msgIt` counter | **ESx, Max G30, E-series, F-series, T15** |
| **Encryption2** | `5A A5` | AES-128 custom CTR + CBC-MAC, 16-bit BE counter | **G2, F2, D-series and all current models** |
| **Encryption3 / V3Auth** | `5A A5` | AES-128 CTR, different handshake | newest; selected at runtime only |

> ⚠️ **Unverified**: Encryption3 has no publicly documented handshake. No device
> sets `encrypt=3` statically — it is negotiated at runtime.

### 2.1 Protocol selection (from the official app)

The vendor app derives the wire protocol from advertisement data. ✅ Verified
(`DynamicDevice.createBleProtocol()`):

```
protocol=2, encrypt=2 → BleEncryption2Protocol2   (most devices)
protocol=2, encrypt=3 → BleEncryption3Protocol2   (newest)
protocol=2, encrypt=0 → BleProtocol2              (XOR only)
protocol=1, encrypt=2 → BleEncryption2Protocol1
protocol=1, encrypt=0 → BleProtocol1              (no encryption)
```

`encrypt` from the advertisement is only a **static default** — the real
version is negotiated at runtime. Treat it as a hint, never as truth.

---

## 3. Xiaomi Mi auth lineage (what the app implements today)

The existing M365 path. ✅ Verified
([miauth](https://github.com/dnandha/miauth),
[`ninebot-ble/src/mi_crypto.rs`](../ninebot-ble/src/mi_crypto.rs))

| Stage | Algorithm |
| --- | --- |
| Key exchange | ECDH secp256r1 (P-256) |
| Key derivation | HKDF-SHA256 |
| Authentication | HMAC-SHA256 |
| UART encryption | AES-128-CCM |

- **Registration**: ECDH keypair → derive `did, token, bind` → send encrypted
  DID. **The token must be persisted**; it is the credential for every later
  connection.
- **Login**: `salt = alice_random ‖ jay_random`, then HKDF(token, salt) yields
  `alice_key, jay_key, alice_iv, jay_iv`.
- **UART**: `nonce = alice_iv ‖ '0000' ‖ counter`, then AES-CCM.

### GATT layout ✅ Verified

```
UART service   6e400001-b5a3-f393-e0a9-e50e24dcca9e
  TX (write)   6e400002-b5a3-f393-e0a9-e50e24dcca9e
  RX (notify)  6e400003-b5a3-f393-e0a9-e50e24dcca9e
AUTH service   0000fe95-0000-1000-8000-00805f9b34fb
  UPNP char    00000010-0000-1000-8000-00805f9b34fb   write + notify
  AVDTP char   00000019-0000-1000-8000-00805f9b34fb   write + notify
  KEY char     00000014-0000-1000-8000-00805f9b34fb   legacy 55AB key read
```

### Handshake verbs ✅ Verified

| Verb | Bytes |
| --- | --- |
| `CMD_GET_INFO` | `a2 00 00 00` |
| `CMD_SET_KEY` | `15 00 00 00` |
| `CMD_SEND_DATA` | `00 00 00 03 04 00` |
| `CMD_SEND_DID` | `00 00 00 00 02 00` |
| `CMD_AUTH` | `13 00 00 00` |
| `CMD_LOGIN` | `24 00 00 00` |
| `CMD_SEND_KEY` | `00 00 00 0b 01 00` |
| `CMD_SEND_INFO` | `00 00 00 0a 02 00` |
| `RCV_RDY` | `00 00 01 01` |
| `RCV_OK` | `00 00 01 00` |
| `RCV_AUTH_OK` / `ERR` | `11 00 00 00` / `12 00 00 00` |
| `RCV_LOGIN_OK` / `ERR` | `21 00 00 00` / `23 00 00 00` |

> ℹ️ The M365 also has an **older** path: read char `00000014`, issue
> `55 aa 03 22 01 50 20` to recover a 16-byte XOR key, then use `55AB`
> XOR-crypto frames. The `fe95` path supersedes it on BLE ≥ 1.2.9, but the XOR
> path still exists on old firmware. 📗 Documented (py9b)

---

## 4. Ninebot legacy lineage (`5A A5` framing)

✅ Verified ([py9b](https://github.com/Informatic/py9b),
[ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble))

Plain framing, no crypto:

```
[5A A5] [len] [src] [dst] [cmd] [arg] [payload...] [crc16 LE]
```

Device addresses: `0x20` ESC · `0x21` BLE · `0x22` BMS · `0x23` ext BMS ·
`0x3D`/`0x3E`/`0x3F` apps (PC / phone / IoT).

### 4.1 NinebotCrypto pairing (overlaid on the framing)

✅ Verified ([NinebotCrypto README](https://github.com/scooterhacking/NinebotCrypto))

1. Send `3e 21 5b 00` (cmd `0x5B`). Reply is 30 bytes, **serial number at
   offset 16, 14 bytes long**. Keep it.
2. Every second, send cmd `0x5C` with a 16-byte random key. **The key must stay
   constant for the whole pairing session** — do not re-randomise per loop.
3. **The user must press the scooter's power button.**
4. Wait for reply `21 3e 5c 01` — the `01` argument confirms the button press.
   Some BLE versions acknowledge with `...00`; always wait for `01`.
5. Send cmd `0x5D` with the saved serial number → reply `21 3e 5d 01`. Paired.

Crypto is AES chained with a monotonically increasing `msgIt` counter and a
SHA-1-derived key.

> 💡 **Fallback rule** (from the NinebotCrypto README): if the initial `0x5B`
> gets no answer, bypass crypto and treat the device as legacy. This is how
> crypto and non-crypto devices coexist.

**Known-good BLE versions** ✅ Verified:

| Family | BLE versions |
| --- | --- |
| M365 Pro | 110, 122 |
| Pro2 / 1S / Lite | 129, 132 (some devices only enable `5AAB`; prefer 129) |
| Ninebot ESx | 109, 110 |
| Ninebot Max | 110, 113, 114 |
| Ninebot E-series | 209, 213 |
| Ninebot F-series | 307 |

> ⚠️ **Unverified conflict**: the E/F-series generations are ambiguous. The
> NinebotCrypto list above implies legacy, but the vendor config table lists
> F65/G65 as `encrypt=2` (Encryption2). Both may be true for different
> sub-models. **Settle it per device with probe-and-fallback.**

---

## 5. Encryption2 (current generation)

✅ Verified ([segway-ninebot-ble encryption docs](https://codeberg.org/NootNooot/segway-ninebot-ble))

- AES-128-ECB, custom CTR + CBC-MAC, 4-byte truncated tag.
- **16-bit big-endian monotonically increasing counter, replay-protected.**
- `aes_key = SHA1(key1_pad16 ‖ key2_pad16)[0:16]`
- `nonce[13] = counter_BE[4] ‖ auth[0:8] ‖ 0x00`
- Gen2 static ECB input `fw_data = 97CFB802844143DE56002B3B34780A5D`; Gen3 uses
  zeros. Gen2 and Gen3 therefore produce **different ciphertext for the same
  plaintext**.
- 3-phase handshake: `PRE_COMM(0x5B)` → 16-byte `auth_param` + 14-byte serial
  (INDEX flags whether a password is already stored) → `SET_PWD(0x5C)` →
  `AUTH(0x5D)` → COMM.
- Key evolution: PRE_COMM uses `key1 = BLE name`, `key2 = null`; SET_PWD uses
  `key2 = auth`; AUTH/COMM use `key1 = session password`, `key2 = auth`.

> ⚠️ **The BLE device name is key material here** — not just an identifier.

---

## 6. GATT profiles

Three exist, chosen by hardware generation. ✅ Verified

### Ninebot Custom ("Hospitality") — modern primary
Suffix `006e-696e65626f74` is ASCII `\x00ninebot`.

```
service   6e400001-0000-0000-006e-696e65626f74
write     6e400002-0000-0000-006e-696e65626f74   app → device
RCTP      6e400003-0000-0000-006e-696e65626f74   secondary write, NOT notify
notify    6e400004-0000-0000-006e-696e65626f74   device → app  (0004, not 0003!)
```

### Nordic UART (compatibility)
`6e400001` / `6e400002` / `6e400003` `-b5a3-f393-e0a9-e50e24dcca9e`

### HMSoft (very old)
service `0000ffe0-0000-1000-8000-00805f9b34fb`, notify `0000ffe1-…`

---

## 7. ⚠️ Fragmentation: a defect that fails silently

**Writing more than `MTU − 3` bytes in a single GATT write causes the device to
silently discard the entire frame. The symptom is indistinguishable from a
pairing refusal.** ✅ Verified — a real project
([ha-ninebot](https://github.com/BobMcGlobus/ha-ninebot), v0.10.0 → v0.11.0,
issue #4) spent multiple releases misdiagnosing this as an "account lock".

A 27-byte AUTH frame must be split `20 + 7`. Fragmentation is **bidirectional** —
reassembly on the notify path needs the same care.

> **This affects the current codebase.** `ScooterRepository.kt` hardcodes
> `val mtu = 20` for writes while separately calling `gatt.requestMtu(512)`.
> The two are inconsistent, and 20 is wrong for any negotiated MTU above 23.
> See `doc/MODEL_SUPPORT.md` for status.

---

## 8. Telemetry addressing: two incompatible models

⚠️ **Do not assume register addresses transfer between generations.**

- **Legacy** (M365 / ESx / Max G30 / E / F / T15): flat register table, read via
  `cmd 0x01` with `arg = register index`, `data = [len]`.
- **Encryption2 / new-gen**: **board-scoped** addressing — a `TARGET_ID` selects
  the module, then an `INDEX` selects the register. **Indexes are per board and
  differ by vehicle class.** ✅ Verified

Board target IDs: `dis=1, mcu=2, ble=4, bms3=5, bms2=6, bms1=7, ecu=9, bfg=16,
hep=17, light=21, abs-f/r=27/28, chg=32, tft=35`.

> **Why this matters.** Reading dashboard indexes out of a Max G3's VCU yielded
> "1924.9 km range" and "1387.5 km/h" — those were the ASCII bytes of the
> vehicle identifier. On that same G3, registers `0x43`–`0x48` *look* like
> voltages but are a static block that did not move across a 52.9 V → 47.6 V
> discharge. ✅ Verified (ha-ninebot)

### 8.1 M365 ESC register map ✅ Verified
Source: [etransport/ninebot-docs](https://github.com/etransport/ninebot-docs)

| Addr | Meaning |
| --- | --- |
| `0x00` | magic `0x515C` — cheap "is this a scooter board" probe |
| `0x10` | serial number (14 B) |
| `0x1A` | ESC firmware version |
| `0x1B` / `0x1C` | error / warning |
| `0x22` | battery % |
| `0x25` | remaining mileage (km × 100) |
| `0x26` | speed |
| `0x29` | total mileage (m, 4 B) |
| `0x3E` | frame temperature |
| `0x47` / `0x48` | ESC supply V / battery V |
| `0x50` | battery current |
| `0x65` | average speed |

Mirror block `0xB0`–`0xBE`: `0xB4` battery %, `0xB5` speed, `0xB6` avg speed,
`0xB7` odometer (4 B), `0xB9` trip, `0xBB` frame temp.

Writable (not used by this app — read-only telemetry only): `0x70` lock,
`0x71` unlock, `0x75` eco, `0x7B` KERS, `0x7C` cruise, `0x7D` tail light.

### 8.2 Ninebot ESx ESC — a superset of M365 ✅ Verified

Adds: `0x1F` operation mode (0 NORMAL / 1 ECO / 2 SPORT) · `0x3F` battery 1
temp (0.1 °C) · `0x40` battery 2 temp · `0x41` MOS temp · `0x49` battery current
· `0x53` motor phase current (0.01 A) · `0xBC` current speed limit · `0xBD`
scooter power (W) · `0xBF` predicted range.

> ⚠️ **Unverified conflict**: register `0xBA` is called "single operation time"
> by one community implementation and "power (W)" by another, and there is no
> hardware confirmation either way. **Do not implement `0xBA` without testing.**

### 8.3 BMS register table ✅ Verified
Source: [ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble)

| Addr | Meaning |
| --- | --- |
| `0x31` | remaining capacity (mAh) |
| `0x32` | remaining % |
| `0x33` | current (÷100 A, **signed**) |
| `0x34` | voltage (÷100 V) |
| `0x35` | temp1 = lo byte − 20, temp2 = hi byte − 20 |
| `0x36` | balancing status |
| `0x37` / `0x38` | cell under- / overvoltage flags |
| `0x3B` | health % |

### 8.4 Reply command differs by vendor ✅ Verified

**Xiaomi replies `01 <ofs> <data>`; Ninebot replies `04 <ofs> <data>`.**
A frame parser that accepts only one will fail on the other lineage.

### 8.5 Per-model data caveats ✅ Verified (hardware-confirmed)

These are the traps that make "it parsed a number" different from "the number is
real":

- **BMS registers read all-zero on an F40** (serial, firmware, balancing,
  counters) but are genuine on a G30D. *A successful read does not imply a
  valid value.*
- **Tail light is a bitfield, not 0/1** — `512` on F40, `1` on G30D. Treat any
  non-zero as ON.
- **Battery health reads a flat 100 %** on at least F40 — nominal, not measured.
- **There is no power register**; power must be computed as V × I, which goes
  negative while charging.
- **No per-cell voltages exist** in this protocol — only under/overvoltage
  condition flags.

---

## 9. Pairing side effects to warn users about

✅ Verified

- **Xiaomi Mi**: ECDH registration once (persist the token). No button press on
  later connections.
- **Ninebot legacy**: `5B/5C/5D` **requires a power-button press**, and the
  scooter remembers a **single** paired client. Pairing this app can force the
  official app to re-pair, and vice versa.
- **Encryption2**: same 3-phase shape. Vehicles already paired with the official
  Segway app report `stored password: True`, and **nothing clears it** — not
  in-app unlinking, not deleting the OS Bluetooth bond, not clearing app data,
  not a factory reset, not a different account. The practical workaround is to
  reuse the app's 16-byte password (recoverable from an unencrypted iOS backup
  plist key `<SERIAL>_decrypt`, or from a first-pairing HCI capture).
  **A wrong password fails silently** — the frame is dropped and you simply time
  out at AUTH.

---

## 10. Model families

Firmware mirror layout ✅ Verified
([firmware.scooterhacking.org](https://firmware.scooterhacking.org/)):

```
1s/ d/ e/ esx/ f/ f2/ g2/ lite/ m365/ max/ mi3/ pro/ pro2/ t15/
```

Each contains `DRV/`, `BLE/`, `BMS/`, except: `d/` splits into `d18/` and
`d28/`; `f2/` has no `BLE/`; `f/` has no `BMS/`.

Files are named `<version>.bin`, `<version>.bin.enc`, `<version>.zip` — **not**
`drv_xxx.bin`. Each ZIP contains `FIRM.bin`, `FIRM.bin.enc` and **`info.json`**.

### `info.json` is the machine-readable source of truth ✅ Verified

Real example, `m365/DRV/1.5.6.zip`:

```json
{"schemaVersion":1,
 "firmware":{"displayName":"1.5.6","model":"m365","enforceModel":true,
             "type":"DRV","compatible":["mi_DRV_STM32F103CxT6"],
             "encryption":"both","md5":{"bin":"6f19...","enc":"34b3..."}}}
```

Observed `model` values: `m365, pro, pro2, 1s, lite, mi3, esx, e, max, f, f2,
g2, t15`.

`compatible` MCU codes reveal the platform lineage:

| MCU code | Platform |
| --- | --- |
| `mi_DRV_STM32F103CxT6`, `mi_DRV_GD32F103CxT6`, `mi_DRV_GD32E103CxT6` | **Xiaomi** — m365, pro, pro2, 1s, lite, **mi3** |
| `esx_DRV_STM32F103CxT6`, `f_DRV_STM32F103CxT6`, `t15_DRV_STM32F103CxT6` | Ninebot STM32 |
| `nb_BLE_NRF51822QFAA` | all Ninebot BLE boards (Nordic nRF51) |
| `f2_DRV_AT32F415CxT7` | **F2 uses an Artery AT32 — a different MCU family entirely** |

> 🔑 **Key insight: `mi3` is on the `mi_*` Xiaomi platform, not Ninebot.** It is
> far closer to M365 / 1S / Pro2 than to any Ninebot model.

### Not in the mirror

Not SHFW-flashable through this pipeline: D38, D40, F65, G65, E2/E3, P65,
P100S, GT1, GT2, Supersoco GT3, ZT3 Pro, UiFi, ST2 Pro, Max G3/G3 Plus,
eMopeds, e-bikes, GoKarts, self-balancing, power stations.

> ScooterHacking **deliberately withholds** support for SNSC 2.2–2.4, C1 and
> S90L to reduce rental-scooter theft. ✅ Verified. Any multi-model work here
> should respect that.

---

## 11. Reference implementations

| Source | What it gives you |
| --- | --- |
| [segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble) | **Most complete modern reference**: transport, framing, Encryption2, 3-phase handshake, board addressing, per-family protocol table for 66 families |
| [miauth](https://github.com/dnandha/miauth) | Xiaomi Mi + Ninebot BLE auth; canonical UUID map; ECDH/HKDF/AES-CCM. **AGPL-3.0 — see §12** |
| [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto) | `5B/5C/5D` pairing walkthrough; C/C++/C#/Kotlin/Swift/Rust ports |
| [etransport/ninebot-docs](https://github.com/etransport/ninebot-docs) | Classic register maps (M365ESC, ES2ESC, M365BLE, ES2BMS) + cross-vendor command table |
| [py9b](https://github.com/Informatic/py9b) | Reference transport split; manufacturer-data scan filters |
| [ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble) | Cleanest Python legacy client; register table with scalers and units |
| [ha-ninebot](https://github.com/BobMcGlobus/ha-ninebot) | **Most current hardware-verified field notes**; both generations incl. Encryption2; pairing-password recovery |
| [CamiAlfa/M365-BLE-PROTOCOL](https://github.com/CamiAlfa/M365-BLE-PROTOCOL) | Original 55AA reverse engineering; `m365_register_map.h` |

---

## 12. Licensing

This project is **MIT**. Two of the most useful references are **AGPL-3.0**
([miauth](https://github.com/dnandha/miauth)) and copyleft-adjacent
([ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble)).

Use them to learn **protocol facts** — register addresses, algorithm
parameters, framing layouts — which are not copyrightable. **Do not copy their
source code, comments, or structure into this repository.** When in doubt,
re-derive from the wire format and cite the reference in a comment.

---

## 13. Open questions

> ⚠️ **Item 1 below has since been resolved.** A second source was found —
> [segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble) — that
> documents **66 families** with a full per-model command reference, including
> the G2, F2 and D-series. See [`MODEL_SUPPORT.md`](./MODEL_SUPPORT.md). The
> original text is kept below for the record.

1. ~~⛔ **No per-model register map exists for the new-generation kick scooters**
   (Max G3, G2, F2, F65, D-series). The most current community integration has
   only **6 registers mapped on a Max G3** and explicitly refuses to guess the
   rest. This is reverse-engineering work, not a lookup.~~
   **Resolved** — register *indices* are documented for these families. The
   remaining gap is *semantics*: units and scaling factors are often absent, so
   values may need empirical calibration.
2. ⚠️ **Xiaomi Mi3 protocol unconfirmed.** It is confirmed to be on the Xiaomi
   `mi_DRV_*` MCU platform, but whether it uses `fe95` Mi-auth, NinebotCrypto or
   Encryption2 was not verified. Forum reports group BLE 1.5.x across
   "1S / Pro2 / Mi3", suggesting a shared stack.
3. ⚠️ **No empirical per-model service-UUID sweep** has been done. The name
   prefixes and manufacturer payloads above come from library scan filters, not
   from a live sweep of one unit per family.
4. ⛔ **Encryption3 / V3Auth handshake is undocumented.**
5. ⚠️ **Static `encrypt` values cannot be trusted.** The generated family table
   in `MODEL_SUPPORT.md` §2 shows Ninebot **ESx and Max G30 as `encrypt = 0`**
   (no encryption), while NinebotCrypto lists the same families as
   crypto-capable. Both are true of *different firmware generations*, and the
   config field is only a static default. **This is the strongest single
   argument for probe-and-fallback detection.**
