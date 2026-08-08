use ccm::{Ccm, consts::{U4, U12}};
use ccm::aead::{Aead, NewAead, generic_array::GenericArray};
use aes::Aes128;
use ccm::aead::Payload;
use sha2::Sha256;
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::{PublicKey, ecdh::EphemeralSecret};
use rand_core::{OsRng, RngCore};
use anyhow::Result;
use thiserror::Error;

type HmacSha256 = Hmac<Sha256>;
type AesCcm = Ccm<Aes128, U4, U12>;

#[derive(Error, Debug)]
pub enum MiCryptoError {
  #[error("Header for message is invalid")]
  InvalidHeader,
  #[error("Error when tried decrypt uart message: {0}")]
  DecryptUart(ccm::aead::Error),
  #[error("Error when tried encrypt uart message: {0}")]
  EncryptUart(ccm::aead::Error),
  #[error("Malformed message: {0}")]
  MalformedMessage(&'static str),
  #[error("Checksum mismatch: computed {computed:04x}, received {received:04x}")]
  ChecksumMismatch { computed: u16, received: u16 },
  /// The frame counter only has [`UART_COUNTER_LEN`] bytes on the wire, so it
  /// cannot represent values outside the `u16` range without reusing a nonce.
  #[error("Frame counter {0} does not fit in the {1}-byte on-wire counter field")]
  CounterOverflow(u32, usize),
  #[error("Remote public key sent by the scooter is invalid")]
  InvalidRemoteKey,
  #[error("Crypto Failure: {0}")]
  Other(anyhow::Error)
}

impl From<anyhow::Error> for MiCryptoError {
  fn from(other: anyhow::Error) -> Self {
    MiCryptoError::Other(other)
  }
}

const NONCE : [u8; 12] = [
  0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
  0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b
];

fn encrypt_did(key: &[u8], did: &[u8]) -> Result<Vec<u8>, MiCryptoError> {
  let aad = b"devID";
  // tracing::debug!("Encrypting Did ({} bytes)", did.len());

  let nonce = GenericArray::from_slice(&NONCE);
  let key = GenericArray::from_slice(key);

  let aes_ccm = AesCcm::new(key);

  aes_ccm.encrypt(&nonce, Payload {
    msg: did,
    aad: aad
  }).map_err(MiCryptoError::EncryptUart)
}

fn derive_key(secret: &[u8], salt: Option<&[u8]>) -> [u8; 64] {
  let mut info = b"mible-setup-info";

  if salt.is_some() {
    info = b"mible-login-info";
  }

  let hk = Hkdf::<Sha256>::new(salt, secret);
  let mut okm = [0u8; 64];

  hk.expand(info, &mut okm)
    .expect("64 is a valid length for Sha256 to output");

  okm
}

pub type Hash = [u8; 32];
fn hash(secret : &[u8], data: &[u8]) -> Hash {
  let mut mac = HmacSha256::new_from_slice(secret)
    .expect("HMAC can take key of any size");
  mac.update(&data);
  let result = mac.finalize();

  result.into_bytes()[0..32].try_into().unwrap()
}

pub type AuthToken = [u8; 12];

/// Length of the header that precedes the device id inside `remote_info`.
const REMOTE_INFO_HEADER_LEN: usize = 4;

/// Derives the shared secret with the scooter and encrypts the device id.
///
/// `remote_key_bytes` and `remote_info` come straight off the BLE link and are
/// therefore untrusted: malformed input is reported as an error rather than
/// panicking.
pub fn calc_did(my_secret_key: &EphemeralSecret, remote_key_bytes: &[u8], remote_info: &[u8]) -> Result<(Vec<u8>, AuthToken), MiCryptoError> {
  // tracing::debug!("Calculating did with a {}-byte remote key", remote_key_bytes.len());

  if remote_info.len() <= REMOTE_INFO_HEADER_LEN {
    return Err(MiCryptoError::MalformedMessage("remote_info is shorter than its 4-byte header"));
  }

  let remote_public_key = PublicKey::from_sec1_bytes(remote_key_bytes)
    .map_err(|_| MiCryptoError::InvalidRemoteKey)?;

  let secret = my_secret_key.diffie_hellman(&remote_public_key);

  let derived_key = derive_key(secret.as_bytes(), None); // HKDF!

  let token    = &derived_key[0..12];
  let a        = &derived_key[28..44];

  let did_ct = encrypt_did(a, &remote_info[REMOTE_INFO_HEADER_LEN..])?;

  let mut final_token = [0u8; 12];
  final_token.copy_from_slice(token);

  Ok((did_ct, final_token))
}

#[derive(Clone)]
pub struct EncryptionKey {
  pub key: [u8; 16],
  pub iv: [u8; 4],
}

/**
 * List of keys used for encrypting uart communication
 */
#[derive(Clone)]
pub struct LoginKeychain {
  pub dev: EncryptionKey,
  pub app: EncryptionKey
}

pub fn calc_login_did(rand_key : &mut [u8], remote_info: &mut [u8], auth_token: &AuthToken) -> (Hash, Hash, LoginKeychain) {
  let mut salt : Vec<u8> = Vec::new();

  salt.extend_from_slice(rand_key);
  salt.extend_from_slice(remote_info);

  let mut salt_inv : Vec<u8> = Vec::new();

  salt_inv.extend_from_slice(remote_info);
  salt_inv.extend_from_slice(rand_key);

  let derived_key = derive_key(auth_token, Some(salt.as_slice()));

  let dev_key = &derived_key[0..16];
  let app_key = &derived_key[16..32];
  let dev_iv = &derived_key[32..36];
  let app_iv = &derived_key[36..40];

  let keys = LoginKeychain {
    dev: EncryptionKey {
      key: dev_key.try_into().unwrap(),
      iv: dev_iv.try_into().unwrap(),
    },

    app: EncryptionKey {
      key: app_key.try_into().unwrap(),
      iv: app_iv.try_into().unwrap(),
    },
  };

  let info = hash(app_key, &salt);
  let expected_remote_info = hash(dev_key, &salt_inv);

  (info, expected_remote_info, keys)
}

/**
 * Generate private and public key
 */
pub fn gen_key_pair() -> (EphemeralSecret, PublicKey) {
  let secret = EphemeralSecret::random(&mut OsRng);
  let public = secret.public_key();

  (secret, public)
}

pub type RandKey = [u8; 16];

/**
 * Generate rand key used for login
 */
pub fn gen_rand_key() -> RandKey {
  let mut data : RandKey = [0u8; 16];
  OsRng::fill_bytes(&mut OsRng, &mut data);

  data
}

const HEADER : [u8; 2] = [0x55, 0xab];

/// Number of frame-counter bytes carried on the wire.
pub const UART_COUNTER_LEN: usize = 2;

/// Wire layout of an encrypted UART frame:
///
/// ```text
/// offset 0..2   header (0x55 0xab)
/// offset 2      size byte (first byte of the plaintext command)
/// offset 3..5   frame counter, little-endian (UART_COUNTER_LEN bytes)
/// offset 5..n-2 AES-CCM ciphertext + tag
/// offset n-2..n checksum over bytes 2..n-2
/// ```
const FRAME_HEADER_LEN: usize = 2;
const FRAME_SIZE_LEN: usize = 1;
const FRAME_CHECKSUM_LEN: usize = 2;
/// Smallest frame that still has a (possibly empty) ciphertext field.
const FRAME_MIN_LEN: usize =
  FRAME_HEADER_LEN + FRAME_SIZE_LEN + UART_COUNTER_LEN + FRAME_CHECKSUM_LEN;

/// Builds the AES-CCM nonce from the frame counter bytes that are actually
/// carried on the wire.
///
/// Both [`encrypt_uart`] and [`decrypt_uart`] MUST derive the nonce this way.
/// The peer only ever sees `UART_COUNTER_LEN` counter bytes, so any nonce byte
/// derived from information that is not transmitted cannot be reconstructed by
/// the other side and would make every frame fail authentication.
fn uart_nonce(iv: &[u8; 4], counter: &[u8; UART_COUNTER_LEN]) -> [u8; 12] {
  let mut nonce = [0u8; 12];
  nonce[0..4].copy_from_slice(iv);
  // nonce[4..8] are reserved and stay zero.
  nonce[8..8 + UART_COUNTER_LEN].copy_from_slice(counter);
  // Remaining bytes stay zero.
  nonce
}

/// Encrypts a UART command frame.
///
/// `it` is the per-session frame counter. It MUST be strictly increasing for
/// the lifetime of `encryption_key`: AES-CCM is a counter-mode construction, so
/// reusing a counter under the same key reuses the keystream and lets an
/// observer recover plaintext and forge frames. Because only
/// [`UART_COUNTER_LEN`] bytes are transmitted, a session is limited to
/// `u16::MAX` frames before the keys must be renegotiated; exceeding that is
/// reported as [`MiCryptoError::CounterOverflow`] rather than silently wrapping.
pub fn encrypt_uart(encryption_key: &EncryptionKey, msg: &[u8], it : u32, rand: Option<[u8; 4]>) -> Result<Vec<u8>, MiCryptoError> {
  if msg.is_empty() {
    return Err(MiCryptoError::MalformedMessage("cannot encrypt an empty message"));
  }

  let counter = u16::try_from(it)
    .map_err(|_| MiCryptoError::CounterOverflow(it, UART_COUNTER_LEN))?
    .to_le_bytes();

  let rand = rand.unwrap_or_else(|| {
    let mut rand : [u8; 4] = [0u8; 4];
    OsRng::fill_bytes(&mut OsRng, &mut rand);
    rand
  });

  // tracing::debug!("Encrypting UART frame ({} bytes, counter {})", msg.len(), it);

  let size : &[u8] = &msg[0..1];

  let mut data : Vec<u8> = Vec::new();
  data.extend_from_slice(&msg[1..]);
  data.extend_from_slice(&rand);

  let nonce = uart_nonce(&encryption_key.iv, &counter);

  let key = GenericArray::from_slice(&encryption_key.key);
  let nonce = GenericArray::from_slice(&nonce);
  let aes_ccm = AesCcm::new(key);

  let ct = aes_ccm.encrypt(nonce, data.as_slice())
    .map_err(MiCryptoError::EncryptUart)?;

  let mut send_data : Vec<u8> = Vec::new();
  send_data.extend_from_slice(size);
  send_data.extend_from_slice(&counter);
  send_data.extend_from_slice(ct.as_slice());

  let crc = crc16(send_data.as_slice()); // checksum over size + counter + ciphertext

  send_data.insert(0, HEADER[1]); // second header
  send_data.insert(0, HEADER[0]); // new header starts here
  send_data.extend_from_slice(&crc);

  Ok(send_data)
}

/// Additive complement checksum used by the Mi UART framing.
///
/// The accumulator wraps explicitly: a signed accumulator overflows (and panics
/// in debug builds) once a frame exceeds ~128 bytes.
pub fn crc16(bytes: &[u8]) -> [u8; 2] {
  let mut sum : u16 = 0;
  for byte in bytes {
    sum = sum.wrapping_add(*byte as u16);
  }

  (!sum).to_le_bytes()
}

pub fn decrypt_uart(encryption_key: &EncryptionKey, msg: &[u8]) -> Result<Vec<u8>, MiCryptoError> {
  // Validate the length before any slicing: `msg` arrives straight off the BLE
  // link, so a truncated or malformed frame must not panic.
  if msg.len() < FRAME_MIN_LEN {
    // tracing::error!("UART frame too short: {} bytes (minimum {})", msg.len(), FRAME_MIN_LEN);
    return Err(MiCryptoError::MalformedMessage("uart frame shorter than the minimum frame size"));
  }

  let header = &msg[0..FRAME_HEADER_LEN];

  if header != HEADER {
    // tracing::error!("Invalid UART frame header");
    return Err(MiCryptoError::InvalidHeader)
  }

  let checksum_start = msg.len() - FRAME_CHECKSUM_LEN;

  // The CCM tag only covers the ciphertext (the aad is empty), so the size byte
  // and the counter are unauthenticated. Verify the checksum so corruption of
  // those fields is rejected instead of silently desynchronising the stream.
  let computed = crc16(&msg[FRAME_HEADER_LEN..checksum_start]);
  let received = &msg[checksum_start..];
  if computed != received {
    return Err(MiCryptoError::ChecksumMismatch {
      computed: u16::from_le_bytes(computed),
      received: u16::from_le_bytes([received[0], received[1]]),
    });
  }

  let counter_start = FRAME_HEADER_LEN + FRAME_SIZE_LEN;
  let counter: [u8; UART_COUNTER_LEN] = msg[counter_start..counter_start + UART_COUNTER_LEN]
    .try_into()
    .expect("slice is exactly UART_COUNTER_LEN bytes");
  let ct = &msg[counter_start + UART_COUNTER_LEN..checksum_start];

  let nonce = uart_nonce(&encryption_key.iv, &counter);

  let key = GenericArray::from_slice(&encryption_key.key);
  let nonce = GenericArray::from_slice(&nonce);
  let aes_ccm = AesCcm::new(key);

  let data = match aes_ccm.decrypt(nonce, Payload {
    msg: ct,
    aad: &[],
  }) {
    Ok(data) => data,
    Err(err) => {
      // tracing::error!("UART decryption failed: {}", err);
      return Err(MiCryptoError::DecryptUart(err))
    }
  };

  Ok(data)
}
