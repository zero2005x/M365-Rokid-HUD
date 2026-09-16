# Ninebot Legacy Protocol | Ninebot 舊世代協議

> ## ⚠️ Confidence: UNVERIFIED — read this first
>
> **No part of the implementation described here has been exercised against a
> real scooter.** It is written from published reverse-engineering references,
> and the only tests are self-consistency tests (round-trip, block boundaries,
> parameter sensitivity, counter carry).
>
> Those tests prove the implementation is **internally coherent**. They do **not**
> prove it interoperates with hardware. A round trip between two copies of the
> same code passes even if both are wrong in the same way.
>
> **Do not enable this for a user-facing connection path** until it has been
> validated against a captured handshake from a real ESx or Max G30.
>
> | Element | Confidence | Basis |
> | --- | --- | --- |
> | Key derivation (`SHA1(name ‖ ble_data)[0..16]`) | 📗 Documented | Two independent published ports agree |
> | AES-ECB keystream (first frame) | 📗 Documented | Two independent published ports agree |
> | AES-ECB keystream (subsequent) | 📗 Documented | Two independent published ports agree |
> | First-frame checksum (`~Σ` little-endian) | ✅ Verified | Identical to an already test-pinned function in this repo |
> | Counter carry rule | ⚠️ **Ports disagree** | See §6 — the C and Kotlin references differ |
> | Trailer layout | ⚠️ Derived | Reconstructed from the reference's read/write offsets |
> | `0x5B`/`0x5C`/`0x5D` handshake sequencing | 📗 Documented | Not implemented here |
> | Register maps per model | ⚠️ Unverified | See `MODEL_SUPPORT.md` |

---

## 1. What this protocol is

The legacy Ninebot stream cipher, known in the community as **NinebotCrypto**.
It protects the `5A A5` framing used by:

| Family | BLE versions (per NinebotCrypto) |
| --- | --- |
| Ninebot ESx | 109, 110 |
| Ninebot Max G30 | 110, 113, 114 |
| Ninebot E-series | 209, 213 |
| Ninebot F-series | 307 |
| Xiaomi M365 Pro | 110, 122 |
| Xiaomi Pro2 / 1S / Lite | 129, 132 (some units only enable `5AAB`) |

> ⚠️ **The static config table contradicts this** for ESx and Max G30, which it
> lists as `encrypt = 0` (no encryption). Both are true of *different firmware
> generations* of the same model name. See `MODEL_SUPPORT.md` §2. **This is why
> the dialect has to be probed at runtime rather than looked up.**

---

## 2. Provenance and licensing

The algorithm was reverse-engineered by **majsi** and published by ScooterHacking
as [`NinebotCrypto`](https://github.com/scooterhacking/NinebotCrypto), which is
**AGPL-3.0**.

The implementation in `ninebot-ble/src/ninebot_legacy.rs` is **not copied from
it**. It is an independent implementation of the protocol, written from the
algorithm's parameters — key-derivation inputs, nonce layout, block ordering,
field widths — which are facts about a wire format rather than expressive code.
Each non-obvious step cites the reference so a future maintainer can re-check it.

Anyone reusing *that* project's source directly would be obliged to license this
project under AGPL-3.0 as well. Do not do that.

### What is deliberately absent

- **TEA / XTEA.** Those encrypt *firmware images*
  ([`NinebotTEA`](https://github.com/scooterhacking/NinebotTEA)), not the
  telemetry link. Including them would imply this crate can flash firmware, which
  it cannot and should not.
- **The `0x5B`/`0x5C`/`0x5D` state machine.** The cipher and frame codec are
  implemented; sequencing the handshake belongs to a connection layer that does
  not exist yet for this family.

---

## 3. Wire format

```text
 ┌────────┬────────┬────────┬──────────────────────┬──────────────────────┐
 │ 5A A5  │  len   │  …     │  AES-encrypted body  │  6-byte trailer      │
 │ 2 bytes│ 1 byte │        │                      │                      │
 └────────┴────────┴────────┴──────────────────────┴──────────────────────┘
   ↑ header travels in the clear          ↑ trailer is NOT encrypted
```

Total frame length = `src.len() + 6`. A frame must be at least **9 bytes** for
the trailer to fit; anything shorter is rejected rather than parsed.

### Trailer layout (6 bytes)

```text
 offset from end:   -6  -5  -4  -3  -2  -1
 ┌────────────────┬───────┬───────┬────┬────┐
 │ checksum[0..4] │ ctrHi │ ctrLo │ 00 │ 00 │
 └────────────────┴───────┴───────┴────┴────┘
```

> ⚠️ **The counter sits at `len-4` and `len-3`, not in the final two bytes.**
> This was got wrong twice while implementing it. Writing the counter at the very
> end makes the receiver decode two bytes of *ciphertext* as the counter, which
> then selects the wrong keystream — producing garbage that still looks like a
> well-formed frame. There is a dedicated regression test for this.

---

## 4. Cryptography

### 4.1 Key derivation

```text
  sha1_key = SHA1( name[0..16] ‖ ble_data[0..16] ) [0..16]
```

Both inputs are 16 bytes, zero-padded if shorter and truncated if longer.

- `name` — the BLE advertised name as raw bytes.
- `ble_data` — a 16-byte value taken from the scooter's first `0x5B` reply.
- Before any reply is known, the second component is the fixed parameter
  [`FW_DATA`] instead. This is the state the very first `0x5B` request is
  encrypted in.

### 4.2 The fixed parameter

```text
  fw_data = 97 CF B8 02 84 41 43 DE 56 00 2B 3B 34 78 0A 5D
```

⚠️ **This is not a secret and not a per-device key.** It is a constant identical
across every device and every app version, and it is published in the reference
implementation. Without it an independent implementation cannot complete the
first handshake message, which is exactly the interoperability case contemplated
by Article 6 of EU Directive 2009/24/EC.

It grants no access on its own: establishing a session still requires the key
exchange **and a physical button press on the scooter** (§5).

### 4.3 Keystream

Two modes, selected by whether the frame counter is zero.

**First frame** — a single AES block, repeated:

```text
  keystream_block = AES-ECB(fw_data, sha1_key)
```

**Subsequent frames** — the nonce carries the counter and the session data:

```text
  nonce[0]     = tag              (0x01 payload / 0x59 checksum)
  nonce[1..5]  = msg_it           big-endian u32
  nonce[5..13] = ble_data[0..8]
  nonce[13..16]= tail             (0,0,0 for payload; length for checksum)
  nonce[15]    = block index      (increments per 16-byte block)
  keystream    = AES-ECB(nonce, sha1_key)
```

Data is XORed with the keystream in 16-byte blocks.

> 🔑 The tag byte provides **domain separation**. Payload and checksum use
> different tags (`0x01` vs `0x59`) so the two constructions can never emit the
> same keystream for the same counter. There is a test asserting this.

### 4.4 Checksums

**First frame** — a plain 16-bit sum, inverted, little-endian:

```text
  checksum = ~(Σ bytes) mod 2^16
```

This delegates to [`crate::mi_crypto::crc16`], which the existing M365 path
already uses and which is pinned by a known-answer test. The reference expresses
it as a repeated fold-then-invert; that is arithmetically identical to a plain
`u16` wrapping sum followed by inversion (verified numerically across 12,000
random inputs).

**Subsequent frames** — not a sum at all. A checksum nonce is built with tag
`0x59` and the frame length in its tail, AES-encrypted, then the source bytes are
folded into it with successive AES rounds. The first 4 bytes of the result go in
the trailer.

---

## 5. Pairing — the part that affects users

📗 Documented, **not implemented here**. Reproduced because it determines what
the UI must warn about.

```text
  1. Send cmd 0x5B.
     Reply is 30 bytes; the serial number is at offset 16, 14 bytes long.
     Keep it.

  2. Every second, send cmd 0x5C with a 16-byte random key.
     ⚠️ The key must stay CONSTANT for the whole pairing session.
        Re-randomising per loop restarts the exchange.

  3. THE USER MUST PRESS THE SCOOTER'S POWER BUTTON.

  4. Wait for reply 21 3E 5C 01 — the trailing 01 confirms the press.
     Some BLE versions acknowledge with ...00 first. Always wait for 01.

  5. Send cmd 0x5D with the saved serial number.
     Reply 21 3E 5D 01 means paired.
```

### 5.1 Two consequences the UI must state plainly

> ### ⚠️ A power-button press is required
>
> The rider must physically walk to the scooter and press its power button during
> pairing. This is not an app permission or a settings toggle, and it cannot be
> skipped or automated. A pairing attempt that appears to hang is usually waiting
> for this.
>
> ### ⚠️ The scooter remembers only ONE paired client
>
> Registering this app **overwrites the scooter's existing pairing**. If the
> official vendor app was paired, it will need to be re-paired — and conversely,
> re-pairing the official app will evict this one.
>
> There is no workaround: it is a property of the scooter's firmware, not of this
> app. The warning must be shown **before** the rider commits, not as an error
> afterwards.

---

## 6. ⚠️ Where the two published references disagree

The counter carry rule differs between the C and Kotlin ports of NinebotCrypto:

```text
  C:       if ((msg_it & 0x0008000) != 0 && (src[len-2] >> 7) == 0) msg_it += 0x10000;
           new = (msg_it & 0xFFFF0000) + transmitted_low_16

  Kotlin:  new = (msg_it & 0xFFFF0000) + transmitted_low_16
           // no carry adjustment
```

The bit tested is **19**, not 15 — it sits one bit above the transmitted 16-bit
range, asking whether the high half itself rolled over.

**The C behaviour is implemented here.** That is a deliberate choice, not an
oversight: the Kotlin omission looks like the bug, and the C port is the newer
of the two. It is flagged because a wrong choice here yields a silently wrong
keystream for exactly the counters that need the correction.

Neither choice has been validated against hardware. **A capture resolves it.**

---

## 7. Write operations are not implemented

Per the project's read-only decision, this phase ships telemetry only.

The Rust crate enforces this structurally rather than by convention: the modules
that issue `ReadWrite::Write` (`session/lock.rs`, `session/light.rs`,
`session/settings.rs`) are behind a **`write-ops` Cargo feature that is OFF by
default**.

```bash
cargo build                      # default: nothing in the library can change the vehicle
cargo build --features write-ops # opt in explicitly
```

Reading a wrong register yields a wrong number. **Writing one changes the
vehicle** — and a motor lock is safety-relevant while the scooter may be moving.
Keeping the default build incapable of it is a property worth being able to
state, not infer.

> ℹ️ **The Android app is unaffected by this gate.** Its lock and light controls
> live in `ScooterRepository` and talk to the scooter directly; the gate applies
> to the Rust library only. Wiring this family into the app would be the point at
> which that needs revisiting.

---

## 8. What would raise confidence

In rough order of value:

1. **An HCI snoop log of an ESx or Max G30 pairing**, captured with the official
   app. This validates key derivation, nonce layout, counter handling and — via
   §6 — which carry rule is correct. **This is the single most useful artefact.**
2. **A capture of one telemetry exchange**, to confirm the register offsets for
   that model independently of the M365 question in `MODEL_SUPPORT.md` §8.
3. **A note of the scooter's BLE version** (e.g. `BLE110`), since that determines
   whether this protocol or plaintext `5A A5` framing is even in play.

Without at least (1), this module stays at `Unverified` and must not be exposed
to users.

---

## 9. References

| Source | What it provides | Licence |
| --- | --- | --- |
| [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto) | The algorithm; C, C++, C#, Kotlin, Swift, Rust ports | **AGPL-3.0** |
| [ninebot-docs wiki](https://github.com/etransport/ninebot-docs/wiki) | Register maps (`M365ESC`, `M365PROESC`, `ES2ESC`, …) | — |
| [py9b](https://github.com/Informatic/py9b) | Transport split; scan filters | — |
| [ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble) | Register table with scalers and units | — |
| [ha-ninebot](https://github.com/BobMcGlobus/ha-ninebot) | Hardware-verified field notes | — |
| [NinebotTEA](https://github.com/scooterhacking/NinebotTEA) | Firmware-image TEA/XTEA (**out of scope here**) | — |
