//! Protocol family identification and Encryption2 handshake support.
//!
//! # Why this module stops where it does
//!
//! Current-generation vehicles (G2, F2, D-series, GT/P-series, Max G3) speak
//! `5A A5` framing with **Encryption2**: AES-128 in a custom CTR mode with
//! CBC-MAC authentication. That much is fully documented and implemented below.
//!
//! What is **not** implemented is telemetry, and that is a deliberate refusal
//! rather than an omission.
//!
//! Encryption2 addresses registers the way current vehicles do: a `TARGET_ID`
//! selects a board, then an *index* selects a register **within that board**, and
//! those indices differ per vehicle class. There is no published per-model index
//! map for the scooters in scope, and the failure mode of guessing is
//! specifically bad — a community integration that guessed produced
//! "1924.9 km range" and "1387.5 km/h", which were the ASCII bytes of the
//! vehicle identifier being read as telemetry. Registers `0x43`–`0x48` on a Max
//! G3 look exactly like pack voltages and are in fact a static block that does
//! not move as the battery discharges.
//!
//! A wrong number that looks plausible is worse than no number. So
//! [`Encryption2Telemetry`] has no implementation, [`telemetry_support`] reports
//! [`TelemetrySupport::Unsupported`], and the UI is expected to say so plainly.
//!
//! # What this unlocks
//!
//! Identification. A device that completes `PRE_COMM` is confirmed to be a
//! current-generation Segway/Ninebot vehicle, which is the fact the app needs in
//! order to stop treating it as an M365 and stop showing it zeros. Detection
//! does not require reading a single register.
//!
//! # Confidence
//!
//! | Element | Confidence |
//! | --- | --- |
//! | Key derivation | 📗 Documented |
//! | Nonce and block formats | 📗 Documented |
//! | CBC-MAC construction | 📗 Documented |
//! | Counter rules | 📗 Documented |
//! | Handshake *sequencing* | ⚠️ Not implemented — see below |
//! | Register addresses | ⛔ **Unknown. Not guessed.** |
//!
//! No part of this has been exercised against hardware. The tests are
//! self-consistency and known-answer tests; they prove the construction is
//! coherent, not that it interoperates.

use aes::Aes128;
use aes::{BlockEncrypt, NewBlockCipher};
use aes::cipher::generic_array::GenericArray;
use sha1::{Digest, Sha1};

use crate::mtu;
use crate::ninebot_legacy::FW_DATA;

/// AES block size, and the cipher's streaming granularity.
pub const BLOCK: usize = 16;

/// Bytes of the 3-byte header, which travels in the clear.
pub const HEADER_LEN: usize = 3;

/// Bytes appended after the encrypted payload: 4-byte MAC + 2-byte counter.
pub const TAIL_LEN: usize = 6;

/// Length of the truncated MAC actually carried on the wire.
pub const TAG_LEN: usize = 4;

/// Length of the nonce built from counter and auth parameter.
pub const NONCE_LEN: usize = 13;

/// Domain-separation byte prefixing a CTR block.
const TAG_CTR: u8 = 0x01;

/// Domain-separation byte prefixing the CBC-MAC initial block.
const TAG_MAC: u8 = 0x59;

/// Which variant of Encryption2 a vehicle uses.
///
/// The two differ only in the static ECB input used before a session is
/// established, but that difference changes the ciphertext for identical
/// plaintext — so getting it wrong fails the very first handshake message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Encryption2Generation {
  /// Uses [`FW_DATA`] as the static ECB input.
  Gen2,
  /// Uses sixteen zero bytes.
  Gen3,
}

impl Encryption2Generation {
  /// The static ECB input for this generation.
  pub fn ecb_input(self) -> [u8; 16] {
    match self {
      Encryption2Generation::Gen2 => FW_DATA,
      Encryption2Generation::Gen3 => [0u8; 16],
    }
  }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Encryption2Error {
  /// Frame shorter than header + tail, so it cannot be a whole frame.
  FrameTooShort { got: usize, minimum: usize },
  /// Sync bytes were not `5A A5`.
  BadSync([u8; 2]),
  /// A counter at or below the last accepted one.
  ///
  /// Replay protection: reusing a counter reuses the CTR keystream, which lets
  /// an observer recover plaintext and forge frames.
  CounterReplay { received: u32, high_water: u32 },
  /// The frame's authentication tag did not match its contents.
  ///
  /// Either the frame was corrupted in flight or it was forged. Both mean the
  /// plaintext must not be used.
  MacMismatch,
}

impl core::fmt::Display for Encryption2Error {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    match self {
      Self::FrameTooShort { got, minimum } => {
        write!(f, "frame is {got} bytes, minimum for Encryption2 is {minimum}")
      }
      Self::BadSync(b) => write!(f, "expected sync 5A A5, got {:02X} {:02X}", b[0], b[1]),
      Self::CounterReplay { received, high_water } => write!(
        f,
        "counter {received} is not greater than the last accepted {high_water}; \
         refusing to reuse a keystream"
      ),
      Self::MacMismatch => write!(
        f,
        "the frame's authentication tag did not match; the frame was corrupted or forged"
      ),
    }
  }
}

impl std::error::Error for Encryption2Error {}

/// Builds the 13-byte nonce: `counter_BE[4] ‖ auth[0..8] ‖ 0x00`.
pub fn build_nonce(counter: u32, auth: &[u8]) -> [u8; NONCE_LEN] {
  let mut nonce = [0u8; NONCE_LEN];
  nonce[0..4].copy_from_slice(&counter.to_be_bytes());
  let n = auth.len().min(8);
  nonce[4..4 + n].copy_from_slice(&auth[..n]);
  // nonce[12] stays zero.
  nonce
}

/// CTR block `A_i = 0x01 ‖ nonce ‖ 0x00 ‖ i`.
pub fn ctr_block(nonce: &[u8; NONCE_LEN], index: u8) -> [u8; BLOCK] {
  let mut b = [0u8; BLOCK];
  b[0] = TAG_CTR;
  b[1..14].copy_from_slice(nonce);
  b[14] = 0x00;
  b[15] = index;
  b
}

/// CBC-MAC initial block `B_0 = 0x59 ‖ nonce ‖ 0x00 ‖ payload_len`.
///
/// `payload_len` is truncated to one byte by the block format, so a payload
/// longer than 255 bytes cannot be represented. [`MAX_PAYLOAD_LEN`] is that
/// bound; callers must reject anything larger rather than let it wrap.
pub fn mac_block(nonce: &[u8; NONCE_LEN], payload_len: usize) -> [u8; BLOCK] {
  let mut b = [0u8; BLOCK];
  b[0] = TAG_MAC;
  b[1..14].copy_from_slice(nonce);
  b[14] = 0x00;
  b[15] = payload_len as u8;
  b
}

/// Largest payload the one-byte length field in [`mac_block`] can express.
pub const MAX_PAYLOAD_LEN: usize = 0xFF;

/// Derives the AES key: `SHA-1(key1 ‖ key2)[0..16]`.
pub fn derive_aes_key(key1: &[u8], key2: &[u8]) -> [u8; 16] {
  let mut combined = [0u8; 32];
  let n1 = key1.len().min(16);
  combined[..n1].copy_from_slice(&key1[..n1]);
  let n2 = key2.len().min(16);
  combined[16..16 + n2].copy_from_slice(&key2[..n2]);

  let mut hasher = Sha1::new();
  hasher.update(combined);
  let digest = hasher.finalize();
  let mut out = [0u8; 16];
  out.copy_from_slice(&digest[..16]);
  out
}

/// One AES-128 ECB block encryption.
fn aes_block(input: &[u8; 16], key: &[u8; 16]) -> [u8; 16] {
  let cipher = Aes128::new(GenericArray::from_slice(key));
  let mut block = GenericArray::clone_from_slice(input);
  cipher.encrypt_block(&mut block);
  let mut out = [0u8; 16];
  out.copy_from_slice(block.as_slice());
  out
}

/// An Encryption2 session.
///
/// Holds the derived key, the auth parameter, and the replay-protection counter.
#[derive(Clone)]
pub struct Encryption2Session {
  aes_key: [u8; 16],
  auth: [u8; 8],
  /// Highest counter accepted or emitted. The next frame uses a strictly
  /// greater value.
  high_water: u32,
  generation: Encryption2Generation,
}

impl core::fmt::Debug for Encryption2Session {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    // Never print key material.
    f.debug_struct("Encryption2Session")
      .field("aes_key", &"<redacted>")
      .field("auth", &"<redacted>")
      .field("high_water", &self.high_water)
      .field("generation", &self.generation)
      .finish()
  }
}

impl Encryption2Session {
  /// Creates a session from the two key components and the device's auth parameter.
  pub fn new(
    key1: &[u8],
    key2: &[u8],
    auth: [u8; 8],
    generation: Encryption2Generation,
  ) -> Self {
    Self {
      aes_key: derive_aes_key(key1, key2),
      auth,
      high_water: 0,
      generation,
    }
  }

  /// The PRE_COMM phase: `key1` is the BLE name, `key2` the generation's static input.
  ///
  /// This is the state the very first `0x5B` message is encrypted in, before any
  /// device value is known.
  pub fn pre_comm(ble_name: &[u8], generation: Encryption2Generation) -> Self {
    let ecb = generation.ecb_input();
    Self::new(ble_name, &ecb, [0u8; 8], generation)
  }

  pub fn generation(&self) -> Encryption2Generation {
    self.generation
  }

  /// Highest counter seen so far.
  pub fn high_water(&self) -> u32 {
    self.high_water
  }

  /// Updates the auth parameter after the device supplies it.
  ///
  /// Also re-derives the key, because the auth parameter is the second key
  /// component for the `SET_PWD` and later phases.
  pub fn set_auth_and_rekey(&mut self, key1: &[u8], auth: [u8; 8]) {
    self.auth = auth;
    self.aes_key = derive_aes_key(key1, &auth);
  }

  fn nonce(&self, counter: u32) -> [u8; NONCE_LEN] {
    build_nonce(counter, &self.auth)
  }

  /// Encrypts a frame, returning `frame.len() + TAIL_LEN` bytes.
  ///
  /// The counter advances before use, so a retry never reuses a keystream.
  pub fn encrypt(&mut self, frame: &[u8]) -> Result<Vec<u8>, Encryption2Error> {
    if frame.len() < HEADER_LEN {
      return Err(Encryption2Error::FrameTooShort {
        got: frame.len(),
        minimum: HEADER_LEN,
      });
    }
    if frame[0..2] != [0x5A, 0xA5] {
      return Err(Encryption2Error::BadSync([frame[0], frame[1]]));
    }

    let payload = &frame[HEADER_LEN..];
    if payload.len() > MAX_PAYLOAD_LEN {
      return Err(Encryption2Error::FrameTooShort {
        got: payload.len(),
        minimum: MAX_PAYLOAD_LEN,
      });
    }

    self.high_water = self.high_water.wrapping_add(1);
    let counter = self.high_water;
    let nonce = self.nonce(counter);

    // Compute the MAC over the plaintext frame before encrypting.
    let raw_tag = self.cbc_mac_with(&nonce, frame);

    let mut out = Vec::with_capacity(frame.len() + TAIL_LEN);
    // Header in the clear.
    out.extend_from_slice(&frame[..HEADER_LEN]);

    // CTR-encrypt the payload, block index starting at 1.
    let mut index: u8 = 1;
    let mut offset = 0;
    while offset < payload.len() {
      let take = (payload.len() - offset).min(BLOCK);
      let keystream = aes_block(&ctr_block(&nonce, index), &self.aes_key);
      for i in 0..take {
        out.push(payload[offset + i] ^ keystream[i]);
      }
      offset += take;
      index = index.wrapping_add(1);
    }

    // Encrypt the tag with the A_0 keystream (index 0).
    let a0 = aes_block(&ctr_block(&nonce, 0), &self.aes_key);
    for i in 0..TAG_LEN {
      out.push(raw_tag[i] ^ a0[i]);
    }

    // Two-byte big-endian counter.
    let low = (counter & 0xFFFF) as u16;
    out.extend_from_slice(&low.to_be_bytes());

    Ok(out)
  }

  /// The full CBC-MAC under an explicit nonce.
  fn cbc_mac_with(&self, nonce: &[u8; NONCE_LEN], frame: &[u8]) -> [u8; 16] {
    let payload = &frame[HEADER_LEN..];
    let mut x = aes_block(&mac_block(nonce, payload.len()), &self.aes_key);

    let mut aad = [0u8; BLOCK];
    aad[..HEADER_LEN].copy_from_slice(&frame[..HEADER_LEN]);
    for i in 0..BLOCK {
      aad[i] ^= x[i];
    }
    x = aes_block(&aad, &self.aes_key);

    let mut offset = 0;
    while offset < payload.len() {
      let take = (payload.len() - offset).min(BLOCK);
      let mut block = [0u8; BLOCK];
      block[..take].copy_from_slice(&payload[offset..offset + take]);
      for i in 0..BLOCK {
        block[i] ^= x[i];
      }
      x = aes_block(&block, &self.aes_key);
      offset += take;
    }
    x
  }

  /// Decrypts a frame and verifies its MAC.
  ///
  /// Rejects a counter that is not strictly greater than the last accepted one,
  /// and rejects a frame whose tag does not match.
  ///
  /// Verification happens **inside** this call, against the same nonce used for
  /// decryption. An earlier revision exposed a separate `verify` that recomputed
  /// the nonce from whatever the counter had become, which meant it verified
  /// against a different counter than the frame was decrypted with — it could
  /// only ever pass by accident. Returning the plaintext only once it is
  /// authenticated removes the possibility of a caller using an unverified frame.
  pub fn decrypt(&mut self, wire: &[u8]) -> Result<Vec<u8>, Encryption2Error> {
    let minimum = HEADER_LEN + TAIL_LEN;
    if wire.len() < minimum {
      return Err(Encryption2Error::FrameTooShort {
        got: wire.len(),
        minimum,
      });
    }
    if wire[0..2] != [0x5A, 0xA5] {
      return Err(Encryption2Error::BadSync([wire[0], wire[1]]));
    }

    let payload_len = wire.len() - minimum;
    let counter = ((wire[wire.len() - 2] as u32) << 8) | (wire[wire.len() - 1] as u32);
    // Recover the high bits from the current counter, as the wire carries only
    // the low 16.
    let full_counter = (self.high_water & 0xFFFF_0000) | counter;

    if full_counter <= self.high_water && self.high_water != 0 {
      return Err(Encryption2Error::CounterReplay {
        received: full_counter,
        high_water: self.high_water,
      });
    }

    let nonce = self.nonce(full_counter);

    let mut plaintext = Vec::with_capacity(wire.len() - TAIL_LEN);
    plaintext.extend_from_slice(&wire[..HEADER_LEN]);

    let ciphertext = &wire[HEADER_LEN..HEADER_LEN + payload_len];
    let mut index: u8 = 1;
    let mut offset = 0;
    while offset < ciphertext.len() {
      let take = (ciphertext.len() - offset).min(BLOCK);
      let keystream = aes_block(&ctr_block(&nonce, index), &self.aes_key);
      for i in 0..take {
        plaintext.push(ciphertext[offset + i] ^ keystream[i]);
      }
      offset += take;
      index = index.wrapping_add(1);
    }

    // Recover the transmitted tag.
    let a0 = aes_block(&ctr_block(&nonce, 0), &self.aes_key);
    let enc_tag = &wire[HEADER_LEN + payload_len..HEADER_LEN + payload_len + TAG_LEN];
    let mut received = [0u8; TAG_LEN];
    for i in 0..TAG_LEN {
      received[i] = enc_tag[i] ^ a0[i];
    }

    // Verify against the SAME nonce, before the counter is committed. A frame
    // that fails authentication must not advance the counter, or an attacker
    // could desynchronise the session by injecting junk.
    let expected = self.cbc_mac_with(&nonce, &plaintext);
    if !constant_time_eq(&expected[..TAG_LEN], &received) {
      return Err(Encryption2Error::MacMismatch);
    }

    self.high_water = full_counter;
    Ok(plaintext)
  }
}

/// Compares two byte slices without an early exit.
///
/// A 4-byte tag is small, but a timing oracle on it is still an oracle: an
/// early-returning comparison leaks how many leading bytes matched, which is
/// enough to forge a tag one byte at a time.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
  if a.len() != b.len() {
    return false;
  }
  let mut diff = 0u8;
  for i in 0..a.len() {
    diff |= a[i] ^ b[i];
  }
  diff == 0
}

// ===========================================================================
// Protocol family identification
// ===========================================================================

/// The protocol dialect a device was found to speak.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProtocolFamily {
  /// Xiaomi Mi authentication (`fe95`, ECDH + AES-CCM). The shipped M365 path.
  XiaomiMiAuth,
  /// Ninebot legacy stream cipher on `5A A5`.
  NinebotLegacy,
  /// Plain `5A A5` with no encryption.
  NinebotPlain,
  /// Current generation: `5A A5` with Encryption2.
  Encryption2,
  /// Not identified.
  Unknown,
}

/// Whether this crate can produce telemetry for a family.
///
/// Returned as a value rather than inferred from an empty result, so the UI has
/// something explicit to render. An empty reading list and "we cannot read this
/// vehicle" are different messages to a rider.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TelemetrySupport {
  /// Fields are implemented and decoded.
  Supported,
  /// The protocol is understood, but the register layout for this vehicle is
  /// not published. **No readings will be produced, by design.**
  Unsupported,
}

/// What this crate can currently do with `family`.
pub fn telemetry_support(family: ProtocolFamily) -> TelemetrySupport {
  match family {
    // Implemented via the model registry.
    ProtocolFamily::XiaomiMiAuth => TelemetrySupport::Supported,
    // The cipher exists but no per-model register map does, and guessing one
    // produces confidently wrong numbers. See the module docs.
    ProtocolFamily::NinebotLegacy
    | ProtocolFamily::NinebotPlain
    | ProtocolFamily::Encryption2
    | ProtocolFamily::Unknown => TelemetrySupport::Unsupported,
  }
}

/// Human-readable reason telemetry is unavailable, for the UI to display.
pub fn unsupported_reason(family: ProtocolFamily) -> Option<&'static str> {
  match telemetry_support(family) {
    TelemetrySupport::Supported => None,
    TelemetrySupport::Unsupported => Some(match family {
      ProtocolFamily::Encryption2 => {
        "This scooter uses the current-generation protocol. The connection is \
         understood, but its register layout is not published, so readings are \
         not shown rather than guessed."
      }
      ProtocolFamily::NinebotLegacy | ProtocolFamily::NinebotPlain => {
        "This scooter uses the Ninebot legacy protocol. Its register layout has \
         not been verified on real hardware, so readings are not shown."
      }
      _ => "This scooter's protocol was not recognised, so readings are not available.",
    }),
  }
}

/// Decides which family a completed handshake implies.
///
/// Takes the *evidence* rather than the device, so the decision is testable and
/// so the caller cannot accidentally pass an advertised name — names and service
/// UUIDs do not identify a dialect. Xiaomi, Ninebot and current Segway models all
/// advertise the same Nordic UART service.
#[derive(Clone, Copy, Debug, Default)]
pub struct HandshakeEvidence {
  /// A `fe95` Mi-auth exchange completed.
  pub mi_auth_completed: bool,
  /// A legacy `0x5B` reply arrived.
  pub legacy_handshake_answered: bool,
  /// A `PRE_COMM` (Encryption2) reply arrived.
  pub encryption2_pre_comm_answered: bool,
}

impl HandshakeEvidence {
  /// The family the evidence points to.
  ///
  /// Order matters. Encryption2 is checked first because it is the only positive
  /// signal that is specific to current vehicles: a Mi-auth success or a legacy
  /// reply cannot be produced by a current-generation device. The legacy reply is
  /// checked before Mi-auth because a `0x5B` answer is cheap to obtain and
  /// unambiguous, whereas an incomplete Mi exchange can leave a partial flag set.
  pub fn family(&self) -> ProtocolFamily {
    if self.encryption2_pre_comm_answered {
      ProtocolFamily::Encryption2
    } else if self.legacy_handshake_answered {
      ProtocolFamily::NinebotLegacy
    } else if self.mi_auth_completed {
      ProtocolFamily::XiaomiMiAuth
    } else {
      ProtocolFamily::Unknown
    }
  }
}

/// Chunks an Encryption2 frame for the link.
///
/// Thin wrapper over [`mtu`] so callers of this module cannot forget the
/// `ATT_MTU - 3` rule. An oversized write is discarded by the peer silently.
pub fn frame_chunks(frame: &[u8], att_mtu: usize) -> Vec<&[u8]> {
  mtu::fragment(frame, att_mtu)
}

#[cfg(test)]
mod tests {
  use super::*;

  fn key1() -> [u8; 16] {
    *b"Ninebot-G2-1234\0"
  }

  fn session() -> Encryption2Session {
    Encryption2Session::new(&key1(), &[0u8; 16], [0xAA; 8], Encryption2Generation::Gen2)
  }

  /// A frame with a plausible header and the given payload.
  fn frame(payload: &[u8]) -> Vec<u8> {
    let mut v = vec![0x5A, 0xA5, (payload.len() + 6) as u8];
    v.extend_from_slice(payload);
    v
  }

  // --- known-answer: AES ------------------------------------------------

  #[test]
  fn aes_ecb_matches_the_fips_197_vector() {
    // Same vector as the legacy module, independently stated here so a
    // regression in either cipher is attributable.
    let key = [
      0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
      0x0F,
    ];
    let plain = [
      0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE,
      0xFF,
    ];
    assert_eq!(
      aes_block(&plain, &key),
      [
        0x69, 0xC4, 0xE0, 0xD8, 0x6A, 0x7B, 0x04, 0x30, 0xD8, 0xCD, 0xB7, 0x80, 0x70, 0xB4, 0xC5,
        0x5A
      ]
    );
  }

  // --- key derivation ----------------------------------------------------

  #[test]
  fn key_derivation_is_deterministic_and_input_sensitive() {
    let a = derive_aes_key(b"aaaaaaaaaaaaaaaa", b"bbbbbbbbbbbbbbbb");
    assert_eq!(a, derive_aes_key(b"aaaaaaaaaaaaaaaa", b"bbbbbbbbbbbbbbbb"));
    assert_ne!(a, derive_aes_key(b"aaaaaaaaaaaaaaaX", b"bbbbbbbbbbbbbbbb"));
    assert_ne!(a, derive_aes_key(b"aaaaaaaaaaaaaaaa", b"bbbbbbbbbbbbbbbX"));
  }

  #[test]
  fn a_null_second_key_behaves_as_sixteen_zero_bytes() {
    // The spec says a null key2 is sixteen zeros; an empty slice must not shift
    // the first key's position in the hash input.
    assert_eq!(
      derive_aes_key(b"aaaaaaaaaaaaaaaa", &[]),
      derive_aes_key(b"aaaaaaaaaaaaaaaa", &[0u8; 16])
    );
  }

  #[test]
  fn key_derivation_truncates_overlong_inputs() {
    let long = [0x41u8; 40];
    let short = [0x41u8; 16];
    assert_eq!(derive_aes_key(&long, &long), derive_aes_key(&short, &short));
  }

  // --- nonce and blocks --------------------------------------------------

  #[test]
  fn the_nonce_is_counter_then_auth_then_a_zero() {
    let n = build_nonce(0x0102_0304, &[1, 2, 3, 4, 5, 6, 7, 8]);
    assert_eq!(&n[0..4], &[0x01, 0x02, 0x03, 0x04]);
    assert_eq!(&n[4..12], &[1, 2, 3, 4, 5, 6, 7, 8]);
    assert_eq!(n[12], 0x00);
  }

  #[test]
  fn a_short_auth_parameter_is_zero_padded() {
    let n = build_nonce(0, &[0xFF, 0xFF]);
    assert_eq!(&n[4..6], &[0xFF, 0xFF]);
    assert_eq!(&n[6..13], &[0, 0, 0, 0, 0, 0, 0]);
  }

  #[test]
  fn ctr_and_mac_blocks_are_domain_separated() {
    // A shared tag would let the keystream and the MAC collide.
    let nonce = build_nonce(1, &[0u8; 8]);
    assert_eq!(ctr_block(&nonce, 0)[0], 0x01);
    assert_eq!(mac_block(&nonce, 0)[0], 0x59);
    assert_ne!(ctr_block(&nonce, 0), mac_block(&nonce, 0));
  }

  #[test]
  fn the_ctr_block_encodes_its_index_in_the_last_byte() {
    let nonce = build_nonce(0, &[0u8; 8]);
    assert_eq!(ctr_block(&nonce, 0)[15], 0);
    assert_eq!(ctr_block(&nonce, 7)[15], 7);
    assert_eq!(ctr_block(&nonce, 255)[15], 255);
  }

  #[test]
  fn the_mac_block_encodes_the_payload_length() {
    let nonce = build_nonce(0, &[0u8; 8]);
    assert_eq!(mac_block(&nonce, 0)[15], 0);
    assert_eq!(mac_block(&nonce, 42)[15], 42);
  }

  // --- generation --------------------------------------------------------

  #[test]
  fn gen2_and_gen3_use_different_static_inputs() {
    // Identical plaintext must not produce identical ciphertext across the two
    // generations; that difference is why the generation has to be known.
    assert_eq!(Encryption2Generation::Gen2.ecb_input(), FW_DATA);
    assert_eq!(Encryption2Generation::Gen3.ecb_input(), [0u8; 16]);
    assert_ne!(
      Encryption2Generation::Gen2.ecb_input(),
      Encryption2Generation::Gen3.ecb_input()
    );
  }

  #[test]
  fn pre_comm_keys_differ_between_generations() {
    let g2 = Encryption2Session::pre_comm(b"Ninebot-1234", Encryption2Generation::Gen2);
    let g3 = Encryption2Session::pre_comm(b"Ninebot-1234", Encryption2Generation::Gen3);
    assert_ne!(g2.aes_key, g3.aes_key);
  }

  // --- encryption round trip --------------------------------------------

  #[test]
  fn a_frame_round_trips_through_encrypt_and_decrypt() {
    let mut tx = session();
    let mut rx = session();

    let plain = frame(b"hello current gen");
    let wire = tx.encrypt(&plain).expect("encrypt");

    // Header in the clear; +6 bytes of tail.
    assert_eq!(&wire[..3], &plain[..3]);
    assert_eq!(wire.len(), plain.len() + TAIL_LEN);
    assert_ne!(&wire[3..wire.len() - TAIL_LEN], &plain[3..]);

    let back = rx.decrypt(&wire).expect("decrypt");
    assert_eq!(back, plain, "round trip must recover the frame");
  }

  #[test]
  fn the_same_plaintext_encrypts_differently_each_time() {
    let mut s = session();
    let plain = frame(b"identical bytes");
    assert_ne!(
      s.encrypt(&plain).expect("first"),
      s.encrypt(&plain).expect("second"),
      "the counter must make each frame unique"
    );
  }

  #[test]
  fn the_counter_advances_once_per_frame() {
    let mut s = session();
    assert_eq!(s.high_water(), 0);
    s.encrypt(&frame(b"one")).expect("one");
    assert_eq!(s.high_water(), 1);
    s.encrypt(&frame(b"two")).expect("two");
    assert_eq!(s.high_water(), 2);
  }

  #[test]
  fn the_wire_carries_the_low_sixteen_bits_of_the_counter() {
    let mut s = session();
    let wire = s.encrypt(&frame(b"abc")).expect("encrypt");
    let end = wire.len();
    let encoded = ((wire[end - 2] as u16) << 8) | wire[end - 1] as u16;
    assert_eq!(encoded, 1);
  }

  #[test]
  fn a_long_payload_spans_multiple_ctr_blocks() {
    let mut tx = session();
    let mut rx = session();
    for len in [1usize, 15, 16, 17, 31, 32, 33, 100, 255] {
      let plain = frame(&vec![0xA5u8; len]);
      let wire = tx.encrypt(&plain).expect("encrypt");
      let back = rx.decrypt(&wire).expect("decrypt");
      assert_eq!(back, plain, "payload length {len} did not round trip");
    }
  }

  // --- validation --------------------------------------------------------

  #[test]
  fn a_frame_without_sync_is_rejected() {
    let mut s = session();
    let mut bad = frame(b"payload");
    bad[0] = 0x00;
    assert_eq!(s.encrypt(&bad), Err(Encryption2Error::BadSync([0x00, 0xA5])));
  }

  #[test]
  fn a_frame_shorter_than_the_header_is_rejected() {
    let mut s = session();
    assert!(matches!(
      s.encrypt(&[0x5A, 0xA5]),
      Err(Encryption2Error::FrameTooShort { .. })
    ));
  }

  #[test]
  fn a_wire_frame_shorter_than_header_plus_tail_is_rejected() {
    let mut s = session();
    let short = [0x5Au8, 0xA5, 0x06, 0x00];
    assert!(matches!(
      s.decrypt(&short),
      Err(Encryption2Error::FrameTooShort { .. })
    ));
  }

  #[test]
  fn a_payload_over_the_length_field_limit_is_rejected() {
    // The MAC block encodes the payload length in a single byte; letting a
    // larger value through would silently wrap it.
    let mut s = session();
    let too_long = frame(&vec![0u8; MAX_PAYLOAD_LEN + 1]);
    assert!(s.encrypt(&too_long).is_err());
    // Exactly at the limit must still work.
    let at_limit = frame(&vec![0u8; MAX_PAYLOAD_LEN]);
    assert!(s.encrypt(&at_limit).is_ok());
  }

  // --- replay protection -------------------------------------------------

  #[test]
  fn a_replayed_frame_is_rejected() {
    let mut tx = session();
    let mut rx = session();
    let wire = tx.encrypt(&frame(b"replay me")).expect("encrypt");
    rx.decrypt(&wire).expect("first decrypt");

    match rx.decrypt(&wire) {
      Err(Encryption2Error::CounterReplay { .. }) => {}
      other => panic!("expected CounterReplay, got {other:?}"),
    }
  }

  #[test]
  fn counters_must_strictly_increase() {
    let mut tx = session();
    let mut rx = session();
    for _ in 0..3 {
      let wire = tx.encrypt(&frame(b"abc")).expect("encrypt");
      assert!(rx.decrypt(&wire).is_ok());
    }
    assert_eq!(rx.high_water(), 3);
  }

  // --- MAC ---------------------------------------------------------------

  #[test]
  fn the_mac_changes_when_any_payload_byte_changes() {
    let s = session();
    let nonce = build_nonce(1, &s.auth);
    let a = s.cbc_mac_with(&nonce, &frame(b"aaaaaaaa"));
    let b = s.cbc_mac_with(&nonce, &frame(b"aaaaaaab"));
    assert_ne!(a, b);
  }

  #[test]
  fn the_mac_changes_when_the_header_changes() {
    // The 3-byte header is authenticated as associated data even though it is
    // not encrypted.
    let s = session();
    let nonce = build_nonce(1, &s.auth);
    let mut f1 = frame(b"payload");
    let mut f2 = f1.clone();
    f2[2] = f2[2].wrapping_add(1);
    assert_ne!(s.cbc_mac_with(&nonce, &f1), s.cbc_mac_with(&nonce, &f2));

    // And a sync-byte change must also be detectable.
    f1[0] = 0x5B;
    assert_ne!(
      s.cbc_mac_with(&nonce, &frame(b"payload")),
      s.cbc_mac_with(&nonce, &f1)
    );
  }

  #[test]
  fn the_mac_changes_with_the_counter() {
    // Otherwise a captured tag would validate under a different counter.
    let s = session();
    let f = frame(b"payload");
    let a = s.cbc_mac_with(&build_nonce(1, &s.auth), &f);
    let b = s.cbc_mac_with(&build_nonce(2, &s.auth), &f);
    assert_ne!(a, b);
  }

  #[test]
  fn a_tampered_tag_is_rejected() {
    let mut tx = session();
    let mut rx = session();
    let mut wire = tx.encrypt(&frame(b"payload")).expect("encrypt");

    // Flip a bit in the 4-byte tag, which sits just before the 2-byte counter.
    let tag_index = wire.len() - TAIL_LEN;
    wire[tag_index] ^= 0x01;

    assert_eq!(rx.decrypt(&wire), Err(Encryption2Error::MacMismatch));
  }

  #[test]
  fn a_tampered_payload_is_rejected() {
    let mut tx = session();
    let mut rx = session();
    let mut wire = tx.encrypt(&frame(b"payload")).expect("encrypt");

    // Flip a bit in the ciphertext body.
    wire[HEADER_LEN] ^= 0x01;

    assert_eq!(rx.decrypt(&wire), Err(Encryption2Error::MacMismatch));
  }

  #[test]
  fn a_tampered_header_is_rejected() {
    // The 3-byte header is authenticated as associated data even though it is
    // sent in the clear, so modifying it must fail verification.
    let mut tx = session();
    let mut rx = session();
    let mut wire = tx.encrypt(&frame(b"payload")).expect("encrypt");

    wire[2] ^= 0x01; // the length byte

    assert!(rx.decrypt(&wire).is_err());
  }

  #[test]
  fn a_rejected_frame_does_not_advance_the_counter() {
    // Otherwise an injected junk frame would desynchronise the session and every
    // later legitimate frame would fail.
    let mut tx = session();
    let mut rx = session();

    let mut bad = tx.encrypt(&frame(b"payload")).expect("encrypt");
    let tag_index = bad.len() - TAIL_LEN;
    bad[tag_index] ^= 0x01;
    assert!(rx.decrypt(&bad).is_err());
    assert_eq!(rx.high_water(), 0, "a failed frame must not move the counter");

    // A genuine frame at the same counter must still be accepted.
    let mut tx2 = session();
    let good = tx2.encrypt(&frame(b"payload")).expect("encrypt");
    assert!(rx.decrypt(&good).is_ok());
  }

  #[test]
  fn constant_time_comparison_behaves_like_equality() {
    assert!(constant_time_eq(&[1, 2, 3], &[1, 2, 3]));
    assert!(!constant_time_eq(&[1, 2, 3], &[1, 2, 4]));
    assert!(!constant_time_eq(&[1, 2, 3], &[1, 2]));
    assert!(constant_time_eq(&[], &[]));
  }

  // --- rekeying ----------------------------------------------------------

  #[test]
  fn supplying_the_auth_parameter_rekeys_the_session() {
    // SET_PWD and later phases use `name ‖ auth`, not `name ‖ static`. Failing to
    // rekey would encrypt every post-handshake frame under the wrong key.
    let mut s = Encryption2Session::pre_comm(b"Ninebot-1234", Encryption2Generation::Gen2);
    let before = s.aes_key;
    s.set_auth_and_rekey(b"Ninebot-1234", [0x11; 8]);
    assert_ne!(before, s.aes_key);
    assert_eq!(s.auth, [0x11; 8]);
  }

  // --- secrecy hygiene ---------------------------------------------------

  #[test]
  fn debug_output_does_not_leak_key_material() {
    let s = session();
    let rendered = format!("{s:?}");
    assert!(rendered.contains("redacted"));
    assert!(!rendered.contains("AA, AA, AA"), "auth must not be printed: {rendered}");
  }

  // --- identification ----------------------------------------------------

  #[test]
  fn encryption2_evidence_wins_over_the_other_signals() {
    // A device that answered PRE_COMM is current-generation; the other flags
    // cannot legitimately be set at the same time, but if a probing sequence
    // leaves one behind, the specific signal must win.
    let e = HandshakeEvidence {
      mi_auth_completed: true,
      legacy_handshake_answered: true,
      encryption2_pre_comm_answered: true,
    };
    assert_eq!(e.family(), ProtocolFamily::Encryption2);
  }

  #[test]
  fn no_evidence_is_unknown_not_a_guess() {
    assert_eq!(HandshakeEvidence::default().family(), ProtocolFamily::Unknown);
  }

  #[test]
  fn a_legacy_reply_identifies_the_legacy_family() {
    let e = HandshakeEvidence {
      legacy_handshake_answered: true,
      ..Default::default()
    };
    assert_eq!(e.family(), ProtocolFamily::NinebotLegacy);
  }

  #[test]
  fn completed_mi_auth_identifies_the_xiaomi_family() {
    let e = HandshakeEvidence {
      mi_auth_completed: true,
      ..Default::default()
    };
    assert_eq!(e.family(), ProtocolFamily::XiaomiMiAuth);
  }

  // --- the honesty contract ---------------------------------------------

  #[test]
  fn only_the_xiaomi_family_claims_telemetry_support() {
    // This is the central claim of this module. If a future change makes another
    // family "supported", it must come with a real register map — so this test
    // failing is the intended prompt to justify it.
    assert_eq!(
      telemetry_support(ProtocolFamily::XiaomiMiAuth),
      TelemetrySupport::Supported
    );
    for family in [
      ProtocolFamily::NinebotLegacy,
      ProtocolFamily::NinebotPlain,
      ProtocolFamily::Encryption2,
      ProtocolFamily::Unknown,
    ] {
      assert_eq!(
        telemetry_support(family),
        TelemetrySupport::Unsupported,
        "{family:?} must not claim telemetry support without a verified register map"
      );
    }
  }

  #[test]
  fn every_unsupported_family_explains_itself_to_the_user() {
    // An empty reading list and "we cannot read this vehicle" are different
    // messages, and the UI needs the second one.
    for family in [
      ProtocolFamily::NinebotLegacy,
      ProtocolFamily::NinebotPlain,
      ProtocolFamily::Encryption2,
      ProtocolFamily::Unknown,
    ] {
      let reason = unsupported_reason(family)
        .unwrap_or_else(|| panic!("{family:?} must supply a user-facing reason"));
      assert!(!reason.is_empty());
      assert!(
        reason.len() > 20,
        "{family:?} reason is too terse to be useful: {reason}"
      );
    }
  }

  #[test]
  fn supported_families_have_no_reason_to_show() {
    assert_eq!(unsupported_reason(ProtocolFamily::XiaomiMiAuth), None);
  }

  #[test]
  fn the_encryption2_reason_says_readings_are_withheld_on_purpose() {
    // The message must not read like a failure the rider can retry away.
    let reason = unsupported_reason(ProtocolFamily::Encryption2).expect("reason");
    assert!(
      reason.contains("not published") || reason.contains("guessed"),
      "the reason should explain that the layout is unknown, not that it failed: {reason}"
    );
  }

  // --- framing integration ----------------------------------------------

  #[test]
  fn frames_are_chunked_by_the_att_mtu_rule() {
    // Encryption2 frames are still BLE writes and obey the same ATT_MTU - 3
    // limit; an oversized write is discarded silently.
    let f = vec![0u8; 100];
    assert_eq!(frame_chunks(&f, 23).len(), 5); // 20*5
    assert_eq!(frame_chunks(&f, 512).len(), 1); // capped at 255
    assert!(frame_chunks(&f, 23).iter().all(|c| c.len() <= 20));
  }
}
