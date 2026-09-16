//! Ninebot legacy stream cipher ("NinebotCrypto").
//!
//! Encrypts the `5A A5` framing used by Ninebot ESx / Max G30 / E-series /
//! F-series / Air T15 and by the Xiaomi Pro / 1S / Lite BLE generations.
//!
//! # Provenance and licensing
//!
//! The algorithm was reverse-engineered by **majsi** and published by
//! ScooterHacking as [`NinebotCrypto`](https://github.com/scooterhacking/NinebotCrypto),
//! which is **AGPL-3.0**. Nothing here is copied from it: this is an independent
//! implementation of the *protocol*, written from the algorithm's parameters
//! (key derivation inputs, nonce layout, block ordering) which are facts about
//! the wire format rather than expressive code. The reference is cited at each
//! non-obvious step so a future maintainer can re-check against it.
//!
//! # ⚠️ Confidence: this is UNVERIFIED
//!
//! **No part of this module has been exercised against a real scooter.** It is
//! written from published references, and the only tests are self-consistency
//! tests (round-trip, block boundaries, parameter sensitivity). Those prove the
//! implementation is internally coherent — they do **not** prove it interoperates
//! with hardware.
//!
//! Do not enable this for a user-facing connection path without first validating
//! against a captured handshake. See `doc/NINEBOT_LEGACY_PROTOCOL.md`.
//!
//! # What is not here
//!
//! - **The `0x5B`/`0x5C`/`0x5D` pairing state machine.** This module provides the
//!   cipher and the frame codec; sequencing the handshake and handling the
//!   power-button press belongs to the connection layer, which does not exist
//!   yet for this family.
//! - **TEA / XTEA.** Those protect *firmware images* (`NinebotTEA`), not the
//!   telemetry link this crate implements. Including them here would imply this
//!   crate can flash, which it cannot.

use aes::Aes128;
// aes 0.7 sits on cipher 0.3, whose constructor trait is `NewBlockCipher` (later
// generations renamed it `KeyInit`).
use aes::{BlockEncrypt, NewBlockCipher};
use aes::cipher::generic_array::GenericArray;
use sha1::{Digest, Sha1};

use crate::mi_crypto::crc16;

/// The fixed 16-byte `fw_data` parameter used as the first block's AES input.
///
/// ⚠️ **This is not a secret and not a per-device key.** It is a constant
/// identical across every device and app version, and it is published in the
/// reference implementation. Without it an independent implementation cannot
/// complete the first handshake message, which is why it is essential for
/// interoperability (EU Directive 2009/24/EC, Article 6). It grants no access on
/// its own: a session still requires the key exchange and a physical button
/// press.
pub const FW_DATA: [u8; 16] = [
  0x97, 0xCF, 0xB8, 0x02, 0x84, 0x41, 0x43, 0xDE, 0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D,
];

/// Sync bytes that open every frame.
pub const SYNC: [u8; 2] = [0x5A, 0xA5];

/// Bytes a frame adds around the payload: 3 header + 6 trailer.
pub const FRAME_OVERHEAD: usize = 9;

/// Minimum frame size the codec will attempt to process.
pub const MIN_FRAME_LEN: usize = FRAME_OVERHEAD;

/// AES block size, and therefore the cipher's streaming granularity.
pub const BLOCK: usize = 16;

/// Domain-separation byte prefixed to the nonce for **payload** encryption.
///
/// The reference uses a different prefix for the checksum block (`0x59`), which
/// is what stops the two from ever producing the same keystream.
const NONCE_TAG_PAYLOAD: u8 = 0x01;

/// Domain-separation byte for the **checksum** block.
const NONCE_TAG_CHECKSUM: u8 = 0x59;

#[derive(Debug, PartialEq, Eq)]
pub enum LegacyCryptoError {
  /// Frame shorter than [`MIN_FRAME_LEN`] — nothing to parse.
  FrameTooShort(usize),
  /// The two sync bytes were not `5A A5`.
  BadSync([u8; 2]),
  /// A key-derivation input was not exactly 16 bytes.
  BadKeyLength { which: &'static str, got: usize },
}

impl core::fmt::Display for LegacyCryptoError {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    match self {
      Self::FrameTooShort(n) => write!(
        f,
        "frame is {n} bytes but a ninebot legacy frame needs at least {MIN_FRAME_LEN}"
      ),
      Self::BadSync(b) => write!(f, "expected sync 5A A5, got {:02X} {:02X}", b[0], b[1]),
      Self::BadKeyLength { which, got } => {
        write!(f, "{which} must be 16 bytes, got {got}")
      }
    }
  }
}

impl std::error::Error for LegacyCryptoError {}

/// A cipher instance for one connection.
///
/// Holds the derived `sha1_key`, the per-session `ble_data` nonce component, and
/// the monotonically increasing `msg_it` counter.
#[derive(Clone)]
pub struct LegacyCipher {
  sha1_key: [u8; 16],
  ble_data: [u8; 16],
  /// Increments once per frame. Part of the nonce, so it must never repeat for a
  /// given key — reusing it reuses the AES keystream, which lets an observer
  /// recover plaintext. [`encrypt`](Self::encrypt) advances it before use so a
  /// failed send cannot leave a counter unused-but-marked-used, and
  /// [`decrypt`](Self::decrypt) never lets the peer's counter move backwards.
  msg_it: u32,
  /// True until the first frame has been sent, which uses a different scheme.
  first_frame: bool,
}

impl core::fmt::Debug for LegacyCipher {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    // Never print key material, even in a debug log.
    f.debug_struct("LegacyCipher")
      .field("sha1_key", &"<redacted>")
      .field("ble_data", &"<redacted>")
      .field("msg_it", &self.msg_it)
      .field("first_frame", &self.first_frame)
      .finish()
  }
}

impl LegacyCipher {
  /// Creates a cipher from the initial pairing state.
  ///
  /// * `name` — the BLE advertised name as raw bytes (padded or truncated to 16).
  /// * `ble_data` — the 16-byte value captured from the scooter's first `0x5B`
  ///   reply, which the reference extracts from the decrypted frame.
  pub fn new(name: &[u8], ble_data: [u8; 16]) -> Self {
    Self {
      sha1_key: derive_sha1_key(name, &ble_data),
      ble_data,
      msg_it: 0,
      first_frame: true,
    }
  }

  /// Rebuilds a cipher for the pre-first-message phase.
  ///
  /// Before the scooter has answered, the nonce's second component is
  /// [`FW_DATA`] rather than a value from the device. This is the state the
  /// very first `0x5B` request is encrypted in.
  pub fn pre_handshake(name: &[u8]) -> Self {
    Self {
      sha1_key: derive_sha1_key(name, &FW_DATA),
      ble_data: FW_DATA,
      msg_it: 0,
      first_frame: true,
    }
  }

  /// Current frame counter.
  pub fn msg_it(&self) -> u32 {
    self.msg_it
  }

  /// True while no frame has been sent or received yet.
  pub fn is_first_frame(&self) -> bool {
    self.first_frame
  }

  /// Encrypts `src` into a complete frame.
  ///
  /// `src` must begin with the `5A A5` sync bytes; the codec passes them through
  /// in the clear, as the protocol requires.
  pub fn encrypt(&mut self, src: &[u8]) -> Result<Vec<u8>, LegacyCryptoError> {
    if src.len() < MIN_FRAME_LEN {
      return Err(LegacyCryptoError::FrameTooShort(src.len()));
    }
    if src[0..2] != SYNC {
      return Err(LegacyCryptoError::BadSync([src[0], src[1]]));
    }

    // Everything after the 3-byte header is encrypted.
    let payload = &src[3..];
    let mut out = vec![0u8; src.len() + 6];
    // Header travels in the clear.
    out[..3].copy_from_slice(&src[..3]);

    if self.first_frame {
      let crc = checksum_first(payload);
      let keystream = self.keystream_first();
      xor_into(&mut out[3..3 + payload.len()], payload, &keystream);
      // Trailer: 4 reserved bytes then the 2-byte CRC, little-endian.
      out[3 + payload.len()..].copy_from_slice(&[0, 0, 0, 0, crc[0], crc[1]]);
      self.msg_it += 1;
      self.first_frame = false;
    } else {
      // Advance BEFORE deriving the nonce: the counter is part of the nonce, so
      // deriving from the old value and then incrementing would encrypt two
      // frames with the same keystream after a retry.
      self.msg_it += 1;
      let crc = self.checksum_next(src, self.msg_it);
      let keystream = self.keystream_next(self.msg_it);
      xor_into(&mut out[3..3 + payload.len()], payload, &keystream);

      // Trailer: 4-byte checksum then the low 16 bits of the counter,
      // big-endian. Six bytes total.
      //
      // The counter's position is load-bearing and was got wrong twice. The
      // receiver reads `&frame[len-4]` and `&frame[len-3]` — the 5th and 4th
      // bytes from the end — NOT the final two. Writing it at the very end
      // instead makes the receiver decode two bytes of ciphertext as the
      // counter, which then selects the wrong keystream and produces garbage
      // that still looks like a valid frame.
      out[3 + payload.len()..3 + payload.len() + 4].copy_from_slice(&crc);
      let end = out.len();
      let low = (self.msg_it & 0x0000_FFFF) as u16;
      out[end - 4] = (low >> 8) as u8;
      out[end - 3] = (low & 0x00FF) as u8;
      out[end - 2] = 0x00;
      out[end - 1] = 0x00;
    }

    Ok(out)
  }

  /// Decrypts a received frame in place-equivalent form, returning `src.len() - 6`.
  ///
  /// The returned buffer keeps the 3-byte header, matching what the reference
  /// and the calling code expect: header, then decrypted payload.
  pub fn decrypt(&mut self, src: &[u8]) -> Result<Vec<u8>, LegacyCryptoError> {
    if src.len() < MIN_FRAME_LEN {
      return Err(LegacyCryptoError::FrameTooShort(src.len()));
    }
    if src[0..2] != SYNC {
      return Err(LegacyCryptoError::BadSync([src[0], src[1]]));
    }

    let mut out = vec![0u8; src.len() - 6];
    out[..3].copy_from_slice(&src[..3]);

    let payload_len = src.len() - FRAME_OVERHEAD;
    if payload_len == 0 {
      return Ok(out);
    }
    let payload = &src[3..3 + payload_len];

    let new_it = self.next_counter_from(src);

    if new_it == 0 {
      let keystream = self.keystream_first();
      xor_into(&mut out[3..], payload, &keystream);
    } else {
      let keystream = self.keystream_next(new_it);
      xor_into(&mut out[3..], payload, &keystream);

      // Learn the session key from the scooter's first `0x5C` acknowledgement.
      // Matching on the decrypted frame rather than on a caller-supplied flag
      // means the key is adopted exactly when the device actually sends it.
      const SAVE_KEY_MATCH: [u8; 7] = [0x5A, 0xA5, 0x10, 0x3E, 0x21, 0x5C, 0x00];
      if out.len() >= 7 && out[..7] == SAVE_KEY_MATCH {
        let mut app_data = [0u8; 16];
        if out.len() >= 7 + 16 {
          app_data.copy_from_slice(&out[7..23]);
          self.adopt_app_key(app_data);
        }
      }
    }

    // The counter must never move backwards: a replayed frame with a lower
    // counter would otherwise reset the nonce and reuse a keystream.
    if new_it > self.msg_it {
      self.msg_it = new_it;
    }
    self.first_frame = false;

    Ok(out)
  }

  /// The counter a received frame claims, using the C reference's carry rule.
  ///
  /// The low 16 bits travel in the last two bytes; the high bits carry over from
  /// the current counter, with one correction applied **before** the low half is
  /// replaced:
  ///
  /// ```text
  ///   if (msg_it & 0x0008000) != 0 && (src[len-2] >> 7) == 0 { msg_it += 0x10000 }
  ///   new = (msg_it & 0xFFFF0000) | transmitted_low_16
  /// ```
  ///
  /// The bit tested is **19** (`0x0008_0000`), not 15: it looks one bit above the
  /// transmitted 16-bit range, i.e. it asks whether the high half itself rolled
  /// over. The adjustment is applied to the *current* counter, before the
  /// transmitted low half overwrites those bits — order matters, and getting it
  /// wrong made this function silently wrong for exactly the counters that need
  /// the correction.
  ///
  /// The **Kotlin** reference omits this adjustment entirely, so the two
  /// published implementations disagree once bit 19 is set. The C behaviour is
  /// implemented here; that is a deliberate choice, not an oversight.
  fn next_counter_from(&self, src: &[u8]) -> u32 {
    let mut it = self.msg_it;

    if (it & 0x0008_0000) != 0 && (src[src.len() - 4] >> 7) == 0 {
      it = it.wrapping_add(0x0001_0000);
    }

    // The counter lives 4 and 3 bytes from the end, not in the final two.
    let transmitted = ((src[src.len() - 4] as u32) << 8) | (src[src.len() - 3] as u32);
    (it & 0xFFFF_0000) | (transmitted & 0xFFFF)
  }

  /// Switches the session to a newly learned `app_data` key.
  fn adopt_app_key(&mut self, app_data: [u8; 16]) {
    // Reference: sha1(random app data ‖ random ble data). The caller supplies the
    // app half; the ble half is this session's.
    let mut joined = [0u8; 32];
    joined[..16].copy_from_slice(&app_data);
    joined[16..].copy_from_slice(&self.ble_data);
    self.sha1_key = sha1_first16(&joined);
  }

  /// Keystream for the first frame: AES-ECB of [`FW_DATA`], used as a repeating block.
  fn keystream_first(&self) -> [u8; BLOCK] {
    aes_ecb_encrypt_block(&FW_DATA, &self.sha1_key)
  }

  /// Keystream for a subsequent frame at `msg_it`.
  ///
  /// Block 0 of the sequence; later blocks increment the last nonce byte. The
  /// caller XORs sequentially, so this returns the whole 16-byte block and
  /// [`xor_into`] advances through it — see [`Self::keystream_block`] for block
  /// `n`.
  fn keystream_next(&self, msg_it: u32) -> [u8; BLOCK] {
    self.keystream_block(msg_it, 0)
  }

  /// Nonce block construction — identical between payload and checksum except
  /// for the leading tag byte and the trailing three bytes.
  fn nonce(tag: u8, msg_it: u32, ble: &[u8; 16], tail: [u8; 3]) -> [u8; BLOCK] {
    let mut b = [0u8; BLOCK];
    b[0] = tag;
    b[1..5].copy_from_slice(&msg_it.to_be_bytes());
    b[5..13].copy_from_slice(&ble[..8]);
    b[13..16].copy_from_slice(&tail);
    b
  }

  fn keystream_block(&self, msg_it: u32, index: u8) -> [u8; BLOCK] {
    let mut block = Self::nonce(NONCE_TAG_PAYLOAD, msg_it, &self.ble_data, [0, 0, 0]);
    block[15] = index;
    aes_ecb_encrypt_block(&block, &self.sha1_key)
  }

  /// The 4-byte checksum carried in a subsequent frame's trailer.
  ///
  /// This is **not** a plain sum: the reference encrypts a nonce whose last three
  /// bytes encode `src.len() - 3`, then XOR-folds the whole source into it with
  /// further AES rounds. The 4 returned bytes are written little-endian.
  fn checksum_next(&self, src: &[u8], msg_it: u32) -> [u8; 4] {
    let encoded_len = (src.len() as u32).wrapping_sub(3);
    let tail = [
      ((encoded_len & 0x00FF_0000) >> 16) as u8,
      ((encoded_len & 0x0000_FF00) >> 8) as u8,
      (encoded_len & 0x0000_00FF) as u8,
    ];
    let nonce = Self::nonce(NONCE_TAG_CHECKSUM, msg_it, &self.ble_data, tail);
    let key = aes_ecb_encrypt_block(&nonce, &self.sha1_key);

    // Fold the first 3 source bytes into the key, then run successive AES rounds.
    let mut state = [0u8; BLOCK];
    state[..3].copy_from_slice(&src[..3]);
    for i in 0..BLOCK {
      state[i] ^= key[i];
    }

    let mut remaining = &src[3..];
    while !remaining.is_empty() {
      let round = aes_ecb_encrypt_block(&state, &self.sha1_key);
      let take = remaining.len().min(BLOCK);
      for (i, byte) in remaining[..take].iter().enumerate() {
        state[i] ^= round[i] ^ byte;
      }
      remaining = &remaining[take..];
    }

    [state[0], state[1], state[2], state[3]]
  }
}

/// XORs `src` with a repeating-block keystream into `dst`.
///
/// `dst.len()` must equal `src.len()`.
fn xor_into(dst: &mut [u8], src: &[u8], first_block: &[u8; BLOCK]) {
  debug_assert_eq!(dst.len(), src.len());
  for (i, (d, s)) in dst.iter_mut().zip(src.iter()).enumerate() {
    *d = s ^ first_block[i % BLOCK];
  }
}

/// Derives the session's first 16 key bytes: `SHA1(name ‖ ble_data)[0..16]`.
///
/// Both inputs are padded or truncated to 16 bytes, matching the reference,
/// which passes fixed 16-byte buffers.
pub fn derive_sha1_key(name: &[u8], ble_data: &[u8; 16]) -> [u8; 16] {
  let mut joined = [0u8; 32];
  let n = name.len().min(16);
  joined[..n].copy_from_slice(&name[..n]);
  joined[16..].copy_from_slice(ble_data);
  sha1_first16(&joined)
}

/// `SHA1(data)` truncated to its first 16 bytes.
pub fn sha1_first16(data: &[u8]) -> [u8; 16] {
  let mut hasher = Sha1::new();
  hasher.update(data);
  let digest = hasher.finalize();
  let mut out = [0u8; 16];
  out.copy_from_slice(&digest[..16]);
  out
}

/// One AES-128 ECB block encryption.
pub fn aes_ecb_encrypt_block(input: &[u8; 16], key: &[u8; 16]) -> [u8; 16] {
  let cipher = Aes128::new(GenericArray::from_slice(key));
  let mut block = GenericArray::clone_from_slice(input);
  cipher.encrypt_block(&mut block);
  let mut out = [0u8; 16];
  out.copy_from_slice(block.as_slice());
  out
}

/// The first frame's 2-byte checksum: `~sum(bytes)`, little-endian.
///
/// Delegates to [`crate::mi_crypto::crc16`], which is the same function already
/// pinned by a known-answer test in this crate. The reference expresses it as a
/// fold-then-invert, which is arithmetically identical to a plain `u16` wrapping
/// sum followed by inversion.
pub fn checksum_first(payload: &[u8]) -> [u8; 2] {
  crc16(payload)
}

#[cfg(test)]
mod tests {
  use super::*;

  fn name() -> &'static [u8] {
    b"MIScooter1234\0\0\0"
  }

  fn ble() -> [u8; 16] {
    [0x11; 16]
  }

  /// Builds a valid frame: `[sync][sync][len][payload…]`.
  ///
  /// The payload is padded up to the 6 bytes the 6-byte trailer requires, so a
  /// test can pass a short literal without tripping the length check. That check
  /// is exercised separately by `a_short_frame_is_rejected_rather_than_panicking`.
  fn frame(payload: &[u8]) -> Vec<u8> {
    let mut body = payload.to_vec();
    while body.len() < MIN_FRAME_LEN - 3 {
      body.push(0x00);
    }
    let mut v = vec![0x5A, 0xA5, (body.len() + 6) as u8];
    v.extend_from_slice(&body);
    v
  }

  // --- key derivation ----------------------------------------------------

  #[test]
  fn key_derivation_is_deterministic() {
    assert_eq!(derive_sha1_key(name(), &ble()), derive_sha1_key(name(), &ble()));
  }

  #[test]
  fn key_derivation_depends_on_both_inputs() {
    // If either input were ignored, two different scooters with the same name
    // (or the same device after a rename) would share a key.
    let a = derive_sha1_key(name(), &ble());
    let mut other_ble = ble();
    other_ble[0] ^= 0xFF;
    assert_ne!(a, derive_sha1_key(name(), &other_ble), "ble_data must affect the key");

    let other_name = b"MIScooter9999\0\0\0";
    assert_ne!(a, derive_sha1_key(other_name, &ble()), "name must affect the key");
  }

  #[test]
  fn sha1_truncation_takes_the_first_sixteen_bytes() {
    let full = Sha1::digest(b"abc");
    let mut expected = [0u8; 16];
    expected.copy_from_slice(&full[..16]);
    assert_eq!(sha1_first16(b"abc"), expected);
  }

  #[test]
  fn a_name_longer_than_sixteen_bytes_is_truncated_not_rejected() {
    let long = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    let truncated = b"ABCDEFGHIJKLMNOP";
    assert_eq!(derive_sha1_key(long, &ble()), derive_sha1_key(truncated, &ble()));
  }

  #[test]
  fn a_short_name_is_zero_padded() {
    // The reference passes fixed 16-byte buffers, so a short name must behave
    // the same as one padded with zeros.
    assert_eq!(derive_sha1_key(b"abc", &ble()), derive_sha1_key(b"abc\0\0\0\0\0\0\0\0\0\0\0\0\0", &ble()));
  }

  // --- AES block ---------------------------------------------------------

  #[test]
  fn aes_ecb_matches_the_published_fips_vector() {
    // FIPS-197 / NIST AES-128 known answer. Pins the primitive itself, so a
    // failure here is a cipher problem rather than a protocol one.
    let key = [
      0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
      0x0F,
    ];
    let plain = [
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE,
      0xFF,
    ];
    let expected = [
      0x69, 0xC4, 0xE0, 0xD8, 0x6A, 0x7B, 0x04, 0x30, 0xD8, 0xCD, 0xB7, 0x80, 0x70, 0xB4, 0xC5,
      0x5A,
    ];
    assert_eq!(aes_ecb_encrypt_block(&plain, &key), expected);
  }

  // --- checksum ----------------------------------------------------------

  #[test]
  fn first_frame_checksum_inverts_the_sum() {
    // ~(1+2+3) & 0xFFFF = 0xFFF9, little-endian => F9 FF
    assert_eq!(checksum_first(&[1, 2, 3]), [0xF9, 0xFF]);
  }

  #[test]
  fn first_frame_checksum_of_empty_input_is_all_ones() {
    assert_eq!(checksum_first(&[]), [0xFF, 0xFF]);
  }

  #[test]
  fn first_frame_checksum_wraps_like_a_u16() {
    // 300 bytes of 0xFF sum to 76500; mod 65536 = 10964 = 0x2AD4; ~ = 0xD52B.
    let payload = vec![0xFFu8; 300];
    assert_eq!(checksum_first(&payload), [0x2B, 0xD5]);
  }

  // --- frame round trip --------------------------------------------------

  #[test]
  fn a_frame_round_trips_through_encrypt_and_decrypt() {
    // Sender and receiver hold the same session state, as two ends of a link do.
    let mut tx = LegacyCipher::new(name(), ble());
    let mut rx = LegacyCipher::new(name(), ble());

    let plain = frame(b"hello scooter");
    let wire = tx.encrypt(&plain).expect("encrypt");

    // The header must travel in the clear.
    assert_eq!(&wire[..3], &plain[..3]);
    // Ciphertext must differ from plaintext.
    assert_ne!(&wire[3..3 + (plain.len() - 3)], &plain[3..]);

    let back = rx.decrypt(&wire).expect("decrypt");
    assert_eq!(back, plain, "round trip must recover the original frame");
  }

  #[test]
  fn multiple_frames_round_trip_with_advancing_counters() {
    let mut tx = LegacyCipher::new(name(), ble());
    let mut rx = LegacyCipher::new(name(), ble());

    for i in 0..5u8 {
      let plain = frame(&[i, i + 1, i + 2, i + 3]);
      let wire = tx.encrypt(&plain).expect("encrypt");
      let back = rx.decrypt(&wire).expect("decrypt");
      assert_eq!(back, plain, "frame {i} did not round trip");
    }
    assert!(tx.msg_it() >= 5);
  }

  #[test]
  fn the_same_plaintext_encrypts_differently_each_time() {
    // The counter is part of the nonce. Identical ciphertext for identical
    // plaintext would mean a reused keystream, which breaks the cipher.
    let mut c = LegacyCipher::new(name(), ble());
    let plain = frame(b"same bytes");
    let first = c.encrypt(&plain).expect("first");
    let second = c.encrypt(&plain).expect("second");
    assert_ne!(first, second, "keystream must not repeat across frames");
  }

  #[test]
  fn the_counter_advances_on_every_sent_frame() {
    let mut c = LegacyCipher::new(name(), ble());
    assert_eq!(c.msg_it(), 0);
    assert!(c.is_first_frame());
    c.encrypt(&frame(b"one")).expect("one");
    assert_eq!(c.msg_it(), 1);
    assert!(!c.is_first_frame());
    c.encrypt(&frame(b"two")).expect("two");
    assert_eq!(c.msg_it(), 2);
  }

  // --- framing validation ------------------------------------------------

  #[test]
  fn a_frame_without_sync_bytes_is_rejected() {
    let mut c = LegacyCipher::new(name(), ble());
    let mut bad = frame(b"payload");
    bad[0] = 0x00;
    assert_eq!(
      c.encrypt(&bad),
      Err(LegacyCryptoError::BadSync([0x00, 0xA5]))
    );
  }

  #[test]
  fn a_short_frame_is_rejected_rather_than_panicking() {
    let mut c = LegacyCipher::new(name(), ble());
    let short = vec![0x5A, 0xA5, 0x21];
    assert_eq!(c.encrypt(&short), Err(LegacyCryptoError::FrameTooShort(3)));
    assert_eq!(c.decrypt(&short), Err(LegacyCryptoError::FrameTooShort(3)));
  }

  #[test]
  fn decrypting_garbage_does_not_panic() {
    let mut c = LegacyCipher::new(name(), ble());
    for len in MIN_FRAME_LEN..40 {
      let junk = vec![0xA5u8; len];
      let _ = c.decrypt(&junk);
    }
  }

  #[test]
  fn a_replayed_frame_cannot_move_the_counter_backwards() {
    // Otherwise a replayed frame would reset the nonce and reuse a keystream.
    let mut tx = LegacyCipher::new(name(), ble());
    let mut rx = LegacyCipher::new(name(), ble());

    for _ in 0..3 {
      let wire = tx.encrypt(&frame(b"abc")).expect("encrypt");
      rx.decrypt(&wire).expect("decrypt");
    }
    let high_water = rx.msg_it();

    // Replay the very first frame, whose counter is far lower.
    let mut replay_tx = LegacyCipher::new(name(), ble());
    let old = replay_tx.encrypt(&frame(b"abc")).expect("encrypt");
    rx.decrypt(&old).expect("decrypt");

    assert!(
      rx.msg_it() >= high_water,
      "counter went backwards: {} < {}",
      rx.msg_it(),
      high_water
    );
  }

  // --- counter recovery --------------------------------------------------

  #[test]
  fn the_counter_is_read_from_the_trailer() {
    let c = LegacyCipher::new(name(), ble());
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    // Counter occupies bytes [len-4, len-3], big-endian.
    f[end - 4] = 0x00;
    f[end - 3] = 0x2A;
    assert_eq!(c.next_counter_from(&f), 0x2A);
  }

  #[test]
  fn the_counter_is_not_read_from_the_final_two_bytes() {
    // Regression: writing the counter at the very end made the receiver decode
    // two bytes of ciphertext as the counter, selecting the wrong keystream.
    let c = LegacyCipher::new(name(), ble());
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    f[end - 4] = 0x00;
    f[end - 3] = 0x01;
    f[end - 2] = 0xCD; // must be ignored
    f[end - 1] = 0xFA; // must be ignored
    assert_eq!(c.next_counter_from(&f), 1);
  }

  #[test]
  fn the_counter_keeps_its_high_half_across_frames() {
    let mut c = LegacyCipher::new(name(), ble());
    c.msg_it = 0x0001_0000;
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    f[end - 4] = 0x00;
    f[end - 3] = 0x05;
    assert_eq!(c.next_counter_from(&f), 0x0001_0005);
  }

  #[test]
  fn the_carry_rule_adds_a_high_bit_when_bit_19_is_set() {
    // Reference condition: bit 19 set on the current counter AND the transmitted
    // high byte's top bit clear => carry into the high half.
    let mut c = LegacyCipher::new(name(), ble());
    c.msg_it = 0x0008_0000;
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    f[end - 4] = 0x7F; // top bit clear
    f[end - 3] = 0x00;
    // 0x0008_0000 + 0x1_0000 = 0x0009_0000, low half replaced by 0x7F00.
    assert_eq!(c.next_counter_from(&f), 0x0009_7F00);
  }

  #[test]
  fn the_carry_rule_is_skipped_when_the_transmitted_top_bit_is_set() {
    let mut c = LegacyCipher::new(name(), ble());
    c.msg_it = 0x0008_0000;
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    f[end - 4] = 0x80; // top bit SET -> no carry
    f[end - 3] = 0x00;
    assert_eq!(c.next_counter_from(&f), 0x0008_8000);
  }

  #[test]
  fn the_carry_rule_is_skipped_below_bit_nineteen() {
    let mut c = LegacyCipher::new(name(), ble());
    c.msg_it = 0x0007_FFFF;
    let mut f = vec![0u8; 12];
    f[0] = 0x5A;
    f[1] = 0xA5;
    let end = f.len();
    f[end - 4] = 0x00;
    f[end - 3] = 0x05;
    assert_eq!(c.next_counter_from(&f), 0x0007_0005);
  }

  // --- nonce construction ------------------------------------------------

  #[test]
  fn payload_and_checksum_nonces_are_domain_separated() {
    // A shared prefix would let the two constructions produce the same
    // keystream for the same counter.
    let ble = ble();
    let payload = LegacyCipher::nonce(NONCE_TAG_PAYLOAD, 1, &ble, [0, 0, 0]);
    let checksum = LegacyCipher::nonce(NONCE_TAG_CHECKSUM, 1, &ble, [0, 0, 0]);
    assert_ne!(payload, checksum);
    assert_eq!(payload[0], 0x01);
    assert_eq!(checksum[0], 0x59);
  }

  #[test]
  fn the_nonce_carries_the_counter_big_endian() {
    let ble = ble();
    let n = LegacyCipher::nonce(NONCE_TAG_PAYLOAD, 0x0102_0304, &ble, [0, 0, 0]);
    assert_eq!(&n[1..5], &[0x01, 0x02, 0x03, 0x04]);
  }

  #[test]
  fn the_nonce_carries_the_first_eight_ble_bytes() {
    let mut ble = [0u8; 16];
    for (i, b) in ble.iter_mut().enumerate() {
      *b = i as u8;
    }
    let n = LegacyCipher::nonce(NONCE_TAG_PAYLOAD, 0, &ble, [0, 0, 0]);
    assert_eq!(&n[5..13], &[0, 1, 2, 3, 4, 5, 6, 7]);
  }

  // --- secrecy hygiene ---------------------------------------------------

  #[test]
  fn debug_output_does_not_leak_key_material() {
    let c = LegacyCipher::new(name(), ble());
    let rendered = format!("{c:?}");
    assert!(rendered.contains("redacted"), "key material must not be printable");
    assert!(
      !rendered.contains("11, 11, 11"),
      "ble_data must not appear in Debug output: {rendered}"
    );
  }

  // --- confidence honesty -------------------------------------------------

  #[test]
  fn pre_handshake_uses_fw_data_as_the_second_key_component() {
    // The first 0x5B request is encrypted before any device value is known, so
    // it must use the fixed parameter rather than a zero nonce.
    let pre = LegacyCipher::pre_handshake(name());
    let with_fw = LegacyCipher::new(name(), FW_DATA);
    assert_eq!(pre.sha1_key, with_fw.sha1_key);
    assert_ne!(pre.sha1_key, LegacyCipher::new(name(), [0u8; 16]).sha1_key);
  }
}

