# Model Support Matrix | 車型支援對照表

> **Status**: living reference. Confidence markers follow
> [`PROTOCOL_FAMILIES.md`](./PROTOCOL_FAMILIES.md):
> ✅ **Verified** · 📗 **Documented** · ⚠️ **Unverified** · ⛔ **Unknown**
>
> **Read §0 first if you only want to know whether your scooter works.** §1 onward
> is the research provenance behind that table.

---

## 0. Does my scooter work?

**Three different things, and they are not the same.** The app distinguishes them
on purpose, because collapsing them is how a dashboard ends up showing a wrong
number that looks plausible.

| | Meaning |
| --- | --- |
| **Recognised** | The app knows what the scooter is, from its advertisement. |
| **Connectable** | The app can complete the protocol handshake. |
| **Readable** | The app can produce telemetry, because a register layout is published for it. |

### The matrix | 對照表

`—` means the capability is not implemented. Capabilities come from
`ScooterModelRegistry` (Kotlin), which is synchronised with the Rust registry by a
test.

| Model | Recognised | Connectable | Speed | Battery | Temp | Odometer | Range | Trip | Confidence | Blocker |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | --- | --- |
| **Xiaomi M365** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 📗 Documented | ⚠️ offset dispute — §8 |
| **Xiaomi M365 Pro** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 📗 Documented | ⚠️ offset dispute — §8 |
| **Xiaomi M365 Pro 2** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 📗 Documented | ⚠️ no capture |
| **Xiaomi Mi 1S** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 📗 Documented | ⚠️ no capture |
| **Xiaomi Mi Lite** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 📗 Documented | ⚠️ no capture |
| **Xiaomi Mi 3** | ✅ | ⛔ | — | — | — | — | — | — | ⚠️ Unverified | Protocol never confirmed |
| **Ninebot ESx** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot Max G30** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot E-series** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot F-series** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot Air T15** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot Max G2** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot F2** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Ninebot D-series** | ✅ | 📗 | — | — | — | — | — | — | ⚠️ Unverified | No register map |
| **Segway GT / P / ZT3 / Max G3** | ⚠️ | 📗 | — | — | — | — | — | — | ⛔ Unknown | Not in scope |

**Capability cells are deliberately empty, not zero.** A model with no published
register map gets `ModelCapabilities.NONE`, so the detail screen hides the row
entirely rather than rendering `0%` — which would read as a measurement.

> ### ⚠️ Nothing is marked ✅ Verified
>
> No capture from real hardware has been supplied for **any** model. "Documented"
> means a published reverse-engineering source describes the layout; it does not
> mean this app has been seen working on that device.
>
> A **manual model override** lets a rider pin a model when identification fails.
> It does **not** raise the confidence: choosing a layout says which registers to
> *try*, not that they are correct. The badge and the log both keep saying so.

### What would move a row | 如何才能升級

| Artefact | Unlocks |
| --- | --- |
| One captured `0xB0` response from an **M365** | Resolves the three-way offset dispute (§8). Affects **current users**. |
| An **ESx or Max G30** HCI snoop log | Validates the legacy cipher (§3) and resolves the C-vs-Kotlin counter disagreement. |
| A **G2 / F2 / D-series** HCI snoop log | The only route to any register map for those models. |

How to capture: Android developer options → **Enable Bluetooth HCI snoop log** →
connect once → export. The app's own log (`Settings → Advanced → Log Viewer`)
carries the model, model id and confidence per row, so a capture is
self-attributing.

---

## 1. The one thing to take away

**Two sources, two lineages, and they barely overlap.**

| | Source A | Source B |
| --- | --- | --- |
| **Is** | [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto) + [etransport/ninebot-docs](https://github.com/etransport/ninebot-docs) | [segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble) |
| **Covers** | Xiaomi lineage + Ninebot ESx/Max/E/F/T15 | Ninebot + Segway, **66 families** |
| **Gives you** | Crypto + `0x5B/0x5C/0x5D` pairing, register maps | Per-model command reference (every register index), Encryption2 spec, board addressing |
| **Misses** | New-generation models entirely | Xiaomi M365/1S/Pro/Pro2/Lite/Mi3 entirely |

Source B is the single most valuable artefact found so far. It is **not an APK
dump** — it is committed documentation generated from Segway's own device-config
packages, with 62 per-device pages and machine-generated command tables. See §4.

---

## 2. Per-model protocol assignment

✅ Verified — generated from the config packages
(`segway-ninebot-ble/docs/_generated/family_protocol.md`).

### ⚠️ The headline finding contradicts a common assumption

| Family | HW id | Commands | `ble_protocol` | `encrypt` |
| --- | --- | --- | --- | --- |
| Ninebot KickScooter **ES** | 33 | 84 | 2 | **0** (none) |
| Ninebot KickScooter **Max** | 36 | 68 | 2 | **0** (none) |
| Ninebot KickScooter **E** | 39 | 106 | 2 | – |
| Ninebot KickScooter **F** | 44 / 123 | 63 / 55 | 2 | – |
| Ninebot KickScooter **Air (T15)** | 35 | 55 | 2 | – |
| Ninebot KickScooter **D18 / D28 / D38** | 116 / 114 / 115 | 46 / 47 / 46 | – | **2** (Encryption2) |
| Ninebot KickScooter **F2 / F2 Plus / F2 Pro** | 127 / 128 / 129 | 104 | 2 | – |
| Ninebot KickScooter **F65 / G65** | 45 / 120 | 59 / 63 | – | **2** |
| Ninebot KickScooter **MAX G2** | 131 | 121 | – | **2** |
| Segway **GT1 / GT2 / P65 / P100S / ST2 Pro** | 112 / 113 / 118 / 119 / 136 | 83–91 | – | **2** |
| Segway **ZT3 Pro / GT3 / MAX G3** | 256 / 257 / 258 | 191–192 | 2 | **2** |
| Segway **eScooter E** (E125S) | 66 | 512 | 2 | – (live-confirmed Enc2) |

**ESx and Max G30 are Protocol 2 with `encrypt = 0` — no encryption — on the
current official-app path.** ⚠️ But [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto)
lists ESx BLE109/110 and Max BLE110/113/114 as crypto-capable.

> **How can both be true?** They are not contradictory — they are different
> generations of the *same model name*. Early ESx/Max firmware used
> NinebotCrypto; later ones moved to plaintext Protocol 2 or Encryption2. The
> config's `encrypt` field is explicitly only a *static default*; the real
> version is negotiated at runtime from advertisement data.
>
> **Consequence — this is the single strongest argument for probe-and-fallback:**
> you cannot decide the crypto from the model name, the hardware ID, or even the
> static config value. You have to try.
>
> ⚠️ **Unverified**: whether a given ESx/Max unit on the shelf today uses
> NinebotCrypto or plaintext. **Confirm per unit before shipping.**

---

## 3. Against the originally requested targets

| Requested | Verdict | Source |
| --- | --- | --- |
| Xiaomi M365 / 1S / Pro / Pro2 / Lite | ✅ Register maps + crypto known | A |
| Xiaomi **Mi3** | ⚠️ `mi_*` MCU platform confirmed; protocol not confirmed | A (partial) |
| Ninebot **ESx** | ✅ 84-command reference available | B |
| Ninebot **Max G30** | ✅ 68-command reference available | B |
| Ninebot **E-series** | ✅ 106-command reference available | B |
| Ninebot **F-series** | ✅ 63/55-command reference available | B |
| Ninebot **T15 (Air)** | ✅ 55-command reference available | B |
| Ninebot **G2** | ✅ 121-command reference available | **B — previously believed unavailable** |
| Ninebot **F2 / F2 Plus / F2 Pro** | ✅ 104-command reference available | **B — previously believed unavailable** |
| Ninebot **D18 / D28 / D38** | ✅ 46/47/46-command reference available | **B — previously believed unavailable** |

> ℹ️ This **revises** `PROTOCOL_FAMILIES.md` §13 item 1, which said no per-model
> register map exists for G2/F2/D-series. That was true of the sources searched
> at the time; Source B closes it. The remaining caveat is *semantic*: the
> reference gives you every register index, but not always its units or scaling.

---

## 4. Why Source B matters more than an APK dump

`generate_reference.py` states its own provenance:

> "Regenerate the shared reference tables from the **pulled API config
> packages**, which are **ground truth**."

So the per-model register maps do **not** live in the APK. They arrive as
device-config packages from Segway's API, which the app decrypts at runtime. The
generator reads `../device_configs` (a sibling checkout) plus a curated
`board_ids.json`.

**What each artefact actually contains:**

| Artefact | Contains | Where it comes from |
| --- | --- | --- |
| `docs/devices/*.md` (62 pages) | Per-model command reference: name, index, ops, data length | Config packages |
| `docs/_generated/family_protocol.md` | Protocol + encryption per family | Config packages + scan config |
| `docs/_generated/module_map.md` | Module → numeric BLE target byte | **APK** (`BoardConfig`), see below |
| `board_ids.json` | Module → target byte, with `confirmed` flags | **APK** + E125S hardware |
| `docs/encryption.md` | Full Encryption2: nonce, CBC-MAC, `fw_data`, key evolution | Decompilation of `libnbcrypto.so` |
| `docs/protocol.md` | All 4 wire generations, frame diagrams, checksums | Decompilation of `BleEncryption*Protocol.java` |

**The only thing genuinely APK-bound is the module → target-byte map**, and it
has already been extracted *and* hardware-confirmed (`dis=0x01`, `ble=0x04`,
`ecu=0x09`, `bms1=0x07`, `bms2=0x06`, `chg=0x20`, `mcu=0x02`, …).

Note also that Source B committed its `_generated/` fragments, so the derived
tables are readable **without** re-pulling the config packages.

---

## 5. Legal posture (read before reusing anything)

Source B states it plainly and the reasoning applies to us too:

> "This research is conducted under Article 6 of the EU Software Directive
> (2009/24/EC), which permits decompilation to achieve interoperability when the
> information is not available through other means. No proprietary source code,
> firmware images, or cryptographic keys are redistributed."

Its `encryption.md` explains why publishing `fw_data` is defensible: it is a
fixed protocol parameter identical across all devices and app versions, **not**
derived from any per-device or per-user secret, and it does not by itself grant
access — per-device authentication still requires a key exchange plus a physical
button press.

**Rules this project follows:**

1. **Protocol facts are fine to reuse** — register addresses, frame layouts,
   algorithm parameters. These are not copyrightable.
2. **Do not copy Source A or B source code, comments, or structure** into this
   MIT-licensed repository. Source A includes **AGPL-3.0** components. Re-derive
   and cite.
3. **Never ship a capability whose only purpose is to defeat authentication.**
   Extracting another user's stored pairing password from a device backup is a
   security bypass, not interoperability. Out of scope, permanently.
4. **Respect ScooterHacking's deliberate omissions.** They withhold SNSC
   2.2–2.4, C1 and S90L to reduce rental-fleet theft. Do not add them.

---

## 6. Revised reading of the architecture

The two lineages need different treatment:

```
                         ┌──────────────────────────────┐
   Xiaomi lineage ───────┤ fe95 + ECDH + AES-CCM        │  ← already implemented
   M365/1S/Pro/Pro2/     │ (this app, today)            │
   Lite/Mi3              └──────────────────────────────┘
                         ┌──────────────────────────────┐
   Ninebot/Segway ───────┤ 5AA5 framing                 │  ← NEW
   ESx/Max/E/F/T15       │  ├ encrypt=0  → plaintext    │  Source B + A
                         │  └ NinebotCrypto (legacy)    │
                         └──────────────────────────────┘
                         ┌──────────────────────────────┐
   New generation ───────┤ 5AA5 + Encryption2           │  ← NEW
   G2/F2/D/F65/GT/       │ AES-128 CTR + CBC-MAC        │  Source B (full spec)
   P-series/ZT3/Max G3   │ + board-scoped TARGET_ID     │
                         └──────────────────────────────┘
```

All three converge on the same requirement: **probe the live device, cache the
result per MAC.** No lookup table can substitute for it (§2).

---

## 7. Open questions after this pass

1. ⚠️ **Which ESx/Max units actually use NinebotCrypto today?** Static config
   says plaintext; NinebotCrypto says crypto-capable. Needs a per-unit probe.
2. ⚠️ **Unit scaling is often absent from Source B.** It gives every register
   index but not always units or divisors. Xiaomi M365 registers are well
   documented in Source A; newer ones may need empirical calibration.
3. ⚠️ **Xiaomi Mi3 protocol still unconfirmed** — same status as before.
4. ⛔ **Encryption3 / V3Auth handshake still undocumented** (Source B documents
   that it exists and is runtime-selected, but not the handshake).
5. ⛔ **Whether the config packages can be pulled reproducibly.** The generator
   expects a sibling `device_configs` checkout that is not in the public repo.
   The committed `_generated/` fragments cover current needs, but refreshing
   them would need the pull mechanism.

---

## 8. ⚠️ The M365 `0xB0` offsets — RESOLVED to one open question

> **Corrected.** An earlier revision of this section claimed three or four
> implementations disagreed. That was **wrong**, and the error was mine: I
> compared offsets that are counted from different base pointers. Re-deriving the
> byte accounting from source (§8.1) shows the two shipped parsers read **the
> same bytes**. One question remains, and it is narrower than originally stated.

### 8.1 What the parsers actually read

`M365ESC.md` documents the `0xB0` block as:

```text
B0 error(2)  B1 warning(2)  B2 status(2)  B3 ?(2)
B4 battery(2)  B5 speed(2)  B6 avg speed(2)  B7 odometer(4)
...  BB frame temperature(2)
```

`decrypt_uart` yields a plaintext buffer:

```text
P = [size][D][T][attr][data…]
```

- **Rust** (`ninebot_ble::session::info`): `pop_head()` consumes `D`,`T`,`attr`,
  then `pad_bytes(8)` steps over eight more. Its cursor therefore reaches the
  battery at **`P[11]`**.
- **Android** (`ScooterRepository.parseMotorInfoFromData`) is handed
  `data = packet[3 until len-4]`, so `data[i] == P[i+3]`. Its battery offset `8`
  is therefore **`P[11]`** as well.

| Field | Rust (in `P`) | Android offset | Android in `P` | Same byte? |
| --- | --- | --- | --- | :-: |
| battery | 11 | 8 | 11 | ✅ |
| speed | 13 | 10 | 13 | ✅ |
| average speed | 15 | 12 | 15 | ✅ |
| odometer | 17 | 14 | 17 | ✅ |
| frame temperature | 25 | 22 | 25 | ✅ |

The relation is `rust_P_offset == android_offset + 3`. There is **no
disagreement between the two shipped parsers** — they read identical bytes.

This is now pinned by `ninebot-ble/tests/motor_info_offsets_test.rs`, which
builds a payload with known distinctive values (a 4-byte odometer of 100000,
which no 2-byte field could hold) and asserts what the decoder produces. That
converts the claim from "the source says offset 11" into checked behaviour.

### 8.2 The one question that remains

Both parsers reach the battery **8 bytes past the 3-byte header**, while the
documented block places it at **offset 4**. The gap is 4 bytes:

- either the `0xB0` response carries a **4-byte prefix** the documentation does
  not describe, and the shipped parsers are correct; or
- the documented offsets are right and **both shipped parsers are wrong** by 4
  bytes.

Nothing available offline distinguishes these. The deciding evidence is small:

> **Five values from one real `0xB0` response**, plus the battery percentage the
> scooter's own app or dashboard showed at that moment. That single capture
> identifies the battery byte unambiguously, which resolves all five fields.

### 8.3 What is not in doubt

The **scaling factors**: `/1000` for speed, `/10` for frame temperature, metres
for the odometer. Every source agrees, and they match the documented units.

### 8.4 What did NOT help

- **The Android test fixture** (`app/.../MotorInfoParserTest.kt`) is a
  re-implementation of the parser, not a test of it. It uses `speed@4`,
  `battery@26`, `odometer@8` — offsets the production parser does not use. It
  cannot detect a bug in the real parser, because it never calls it.
- **`ninebot-ble/tests/responses_test.rs`** holds one valid vector
  (`23 01 25 32 0a 6a f8 94 11`) whose payload decodes to `0x0A32` = 2610. By the
  documented `km*100` scaling for register `0x25` that is 26.1 km, which is
  plausible — but the test is named `it_guess_what_distance_is_left` and 2610 is
  equally plausible as a raw metre count. It constrains remaining range only, and
  says nothing about the `0xB0` block.

### 8.5 Current stance

- `ninebot-ble`'s `model::M365` records the **documented** layout;
- the shipped Android behaviour is **deliberately untouched**, so existing users
  see no change;
- the remaining question is recorded and asserted by tests rather than papered
  over, so it cannot be quietly forgotten or "fixed" without evidence.
