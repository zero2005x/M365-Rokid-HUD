//! ATT MTU arithmetic, shared by every layer that writes to a scooter.
//!
//! ## Why this is its own module
//!
//! A BLE write carrying more than `ATT_MTU - 3` bytes is **discarded by the
//! peer with no error surfaced anywhere**. There is no callback, no
//! disconnection, no status code — the frame simply does not arrive, and the
//! symptom is indistinguishable from the scooter refusing the command or the
//! pairing being rejected. A community project misdiagnosed exactly this as an
//! "account lock" across several releases before locating it.
//!
//! Because the failure is silent, the rule has to be applied in exactly one
//! place. It previously was not: the write path hard-coded a 20-byte chunk
//! (`NB_CHUNK_SIZE`) while separately requesting a 512-byte MTU, and nothing
//! recorded what was actually granted, so the hard-coded value could neither
//! scale up nor detect a smaller link.
//!
//! The three bytes are ATT overhead: one opcode byte plus a two-byte attribute
//! handle. This is fixed by the Bluetooth Core Specification, not a tuning
//! parameter.

/// ATT overhead per write: 1 opcode byte + 2 attribute-handle bytes.
pub const ATT_OVERHEAD: usize = 3;

/// The ATT_MTU every Bluetooth LE stack is required to support.
///
/// This is the only value that is safe to assume before negotiation completes.
/// Assuming anything larger is a guess that fails *silently* on a peer which
/// never negotiated up.
pub const DEFAULT_ATT_MTU: usize = 23;

/// Usable payload with an un-negotiated link: `23 - 3 = 20` bytes.
pub const DEFAULT_CHUNK_SIZE: usize = DEFAULT_ATT_MTU - ATT_OVERHEAD;

/// Largest payload this crate will ever emit in a single ATT write.
///
/// The protocol's length field is one byte, so a chunk above this could not be
/// framed even if the link allowed it.
pub const MAX_CHUNK_SIZE: usize = 0xFF;

/// Usable ATT payload for a negotiated MTU.
///
/// A nonsensical `att_mtu` — 0 before negotiation, or a value too small to hold
/// the ATT header — falls back to [`DEFAULT_CHUNK_SIZE`] rather than yielding a
/// zero-sized chunk, which would make the fragmentation loop never advance.
pub fn chunk_size_for(att_mtu: usize) -> usize {
  if att_mtu <= ATT_OVERHEAD {
    return DEFAULT_CHUNK_SIZE;
  }
  (att_mtu - ATT_OVERHEAD).min(MAX_CHUNK_SIZE)
}

/// Number of ATT writes `payload_len` bytes require on a link with `att_mtu`.
///
/// Zero for an empty payload: there is nothing to send, and a caller looping
/// over the result must perform no write at all.
pub fn chunk_count(payload_len: usize, att_mtu: usize) -> usize {
  if payload_len == 0 {
    return 0;
  }
  let size = chunk_size_for(att_mtu);
  (payload_len + size - 1) / size
}

/// Splits `payload` into chunks no larger than the link permits.
///
/// Returns an empty vector for an empty payload, so iterating the result is
/// always safe.
pub fn fragment(payload: &[u8], att_mtu: usize) -> Vec<&[u8]> {
  if payload.is_empty() {
    return Vec::new();
  }
  payload.chunks(chunk_size_for(att_mtu)).collect()
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn default_chunk_size_is_twenty() {
    assert_eq!(DEFAULT_CHUNK_SIZE, 20);
    assert_eq!(chunk_size_for(23), 20);
  }

  #[test]
  fn chunk_size_is_three_below_the_negotiated_mtu() {
    assert_eq!(chunk_size_for(23), 20);
    assert_eq!(chunk_size_for(27), 24);
    assert_eq!(chunk_size_for(32), 29);
    assert_eq!(chunk_size_for(64), 61);
    assert_eq!(chunk_size_for(185), 182);
  }

  #[test]
  fn chunk_size_is_capped_by_the_one_byte_length_field() {
    // 512 - 3 = 509, but the protocol length byte is a single byte, so 255 wins.
    assert_eq!(chunk_size_for(512), MAX_CHUNK_SIZE);
    assert_eq!(chunk_size_for(512), 255);
    assert_eq!(chunk_size_for(258), 255);
    assert_eq!(chunk_size_for(255), 252);
  }

  #[test]
  fn nonsensical_mtu_falls_back_instead_of_yielding_zero() {
    for bad in [0usize, 1, 2, 3] {
      assert_eq!(
        chunk_size_for(bad),
        DEFAULT_CHUNK_SIZE,
        "MTU {bad} must fall back, not produce a zero chunk"
      );
      assert!(chunk_size_for(bad) > 0);
    }
  }

  #[test]
  fn empty_payload_produces_no_writes() {
    assert_eq!(chunk_count(0, 23), 0);
    assert!(fragment(&[], 23).is_empty());
  }

  #[test]
  fn exact_boundary_does_not_produce_a_trailing_empty_chunk() {
    assert_eq!(chunk_count(20, 23), 1);
    assert_eq!(chunk_count(21, 23), 2);
    assert_eq!(fragment(&[0u8; 20], 23).len(), 1);
    assert_eq!(fragment(&[0u8; 21], 23).len(), 2);
  }

  #[test]
  fn the_27_byte_pairing_frame_splits_into_20_plus_7() {
    // The concrete case from the field report: a 27-byte AUTH frame must go out
    // as 20 + 7 on an un-negotiated link.
    let chunks = fragment(&[0u8; 27], 23);
    assert_eq!(chunks.len(), 2);
    assert_eq!(chunks[0].len(), 20);
    assert_eq!(chunks[1].len(), 7);
  }

  #[test]
  fn no_chunk_ever_exceeds_the_limit() {
    for mtu in [23usize, 32, 64, 185, 512] {
      let limit = chunk_size_for(mtu);
      for len in [1usize, 19, 20, 21, 27, 40, 100, 255, 256, 1000] {
        let payload = vec![0u8; len];
        for chunk in fragment(&payload, mtu) {
          assert!(
            chunk.len() <= limit,
            "chunk of {} exceeds limit {} for mtu {} payload {}",
            chunk.len(),
            limit,
            mtu,
            len
          );
        }
      }
    }
  }

  #[test]
  fn fragmentation_is_lossless_and_ordered() {
    let payload: Vec<u8> = (0..1000).map(|i| (i % 256) as u8).collect();
    for mtu in [23usize, 64, 512] {
      let joined: Vec<u8> = fragment(&payload, mtu).concat();
      assert_eq!(joined, payload, "round trip failed for mtu {mtu}");
    }
  }

  #[test]
  fn chunk_count_agrees_with_fragment() {
    for mtu in [23usize, 64, 512] {
      for len in [1usize, 20, 21, 100, 511, 512, 513] {
        let payload = vec![0u8; len];
        assert_eq!(
          chunk_count(len, mtu),
          fragment(&payload, mtu).len(),
          "count mismatch for len {len} mtu {mtu}"
        );
      }
    }
  }
}
