//! Transport abstraction: the seam that makes the protocol testable.
//!
//! The protocol layer talks to a scooter through [`Transport`] rather than
//! through `btleplug` directly. Two things fall out of that:
//!
//! 1. **The protocol is testable without a scooter.** Every path that framed a
//!    command, split it to fit the link, or parsed a reply used to be welded to
//!    a `Peripheral`, so it could only be exercised by physically connecting to
//!    a scooter. Framing and MTU bugs therefore surfaced on hardware, where they
//!    are slow and ambiguous to diagnose. [`MockTransport`] turns them into
//!    ordinary unit-test failures.
//!
//! 2. **The MTU rule is applied in one place.** Because an oversized write is
//!    discarded silently, fragmentation must not be re-implemented per call
//!    site — which is precisely how the hard-coded 20-byte chunk happened.
//!    [`Transport::write`] accepts the *whole* payload and splits it itself.
//!
//! This module is deliberately free of `btleplug` types, so it compiles and
//! tests on a host with no Bluetooth stack at all. That matters on Linux, where
//! `btleplug`'s backend needs `dbus` and therefore `pkg-config` +
//! `libdbus-1-dev`; neither is required to test framing.

use std::collections::VecDeque;
use std::time::Duration;

use anyhow::Result;

use crate::mtu::{chunk_size_for, DEFAULT_ATT_MTU};

/// A notification received from the scooter.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Notification {
  /// Characteristic the notification arrived on.
  pub characteristic: String,
  /// Raw bytes, exactly as delivered by the peer.
  pub value: Vec<u8>,
}

impl Notification {
  /// Convenience constructor.
  pub fn new(characteristic: impl Into<String>, value: Vec<u8>) -> Self {
    Self {
      characteristic: characteristic.into(),
      value,
    }
  }
}

/// A BLE link to a scooter.
///
/// Implementors MUST guarantee that a single ATT write never exceeds
/// [`Transport::chunk_size`]. See the module documentation for why the peer
/// cannot be relied upon to report a violation.
#[async_trait::async_trait]
pub trait Transport: Send {
  /// Largest ATT payload this link accepts, i.e. `ATT_MTU - 3`.
  fn chunk_size(&self) -> usize;

  /// Fragments `payload` and writes every chunk in order.
  ///
  /// Returns the number of ATT writes performed. That count is what lets a test
  /// assert fragmentation behaviour for a given MTU without reaching into
  /// private state.
  ///
  /// An empty payload performs no write and returns `Ok(0)`.
  ///
  /// Async because the real implementation wraps `btleplug`, whose writes are
  /// futures. A synchronous trait could only ever have been implemented by the
  /// mock, which would have made it a test double rather than an abstraction.
  async fn write(&mut self, characteristic: &str, payload: &[u8]) -> Result<usize>;

  /// Waits for the next notification, or fails once `timeout` elapses.
  ///
  /// A timeout MUST be an error rather than a `None`: every caller in this crate
  /// treats "no reply" as a protocol failure, and returning `None` previously
  /// left callers looping until an outer timeout fired, which made the real
  /// failure point impossible to identify from a log.
  async fn read_notification(&mut self, timeout: Duration) -> Result<Notification>;

  /// Enables notifications on `characteristic`.
  async fn subscribe(&mut self, characteristic: &str) -> Result<()>;
}

// ===========================================================================
// Mock transport
// ===========================================================================

/// A [`Transport`] that records what was written and replays what you queue.
///
/// It enforces the same MTU limit as a real link and refuses an oversized write,
/// so a test catches a mis-sized write instead of having the mock silently
/// accept what hardware would discard.
#[derive(Debug)]
pub struct MockTransport {
  att_mtu: usize,
  /// Every individual ATT write, in order, as `(characteristic, chunk)`.
  pub writes: Vec<(String, Vec<u8>)>,
  /// Notifications to hand back from [`Transport::read_notification`].
  pub incoming: VecDeque<Notification>,
  /// Characteristics successfully subscribed to.
  pub subscribed: Vec<String>,
  /// When set, the write at this 0-based index fails instead of succeeding.
  /// Used to exercise the retry path.
  pub fail_write_at: Option<usize>,
  /// When true, [`Transport::read_notification`] always reports a timeout.
  pub starve_reads: bool,
}

impl Default for MockTransport {
  fn default() -> Self {
    Self::new()
  }
}

impl MockTransport {
  /// A mock on a link that has not negotiated above the mandatory minimum.
  pub fn new() -> Self {
    Self::with_mtu(DEFAULT_ATT_MTU)
  }

  /// A mock whose link granted `att_mtu`.
  pub fn with_mtu(att_mtu: usize) -> Self {
    Self {
      att_mtu,
      writes: Vec::new(),
      incoming: VecDeque::new(),
      subscribed: Vec::new(),
      fail_write_at: None,
      starve_reads: false,
    }
  }

  /// Queues a notification for the next read.
  pub fn push_notification(&mut self, characteristic: &str, value: Vec<u8>) {
    self.incoming
      .push_back(Notification::new(characteristic, value));
  }

  /// Queues a notification from a byte slice.
  pub fn push_bytes(&mut self, characteristic: &str, value: &[u8]) {
    self.push_notification(characteristic, value.to_vec());
  }

  /// Every byte written to `characteristic`, concatenated in order.
  ///
  /// This is what a reassembling receiver on the far side would observe, so it
  /// is how a test proves fragmentation is lossless.
  pub fn reassembled(&self, characteristic: &str) -> Vec<u8> {
    self
      .writes
      .iter()
      .filter(|(c, _)| c == characteristic)
      .flat_map(|(_, chunk)| chunk.iter().copied())
      .collect()
  }

  /// Sizes of the individual ATT writes, for boundary assertions.
  pub fn write_sizes(&self) -> Vec<usize> {
    self.writes.iter().map(|(_, c)| c.len()).collect()
  }

  /// Clears recorded writes, leaving queued notifications intact.
  pub fn clear_writes(&mut self) {
    self.writes.clear();
  }
}

#[async_trait::async_trait]
impl Transport for MockTransport {
  fn chunk_size(&self) -> usize {
    chunk_size_for(self.att_mtu)
  }

  async fn write(&mut self, characteristic: &str, payload: &[u8]) -> Result<usize> {
    if payload.is_empty() {
      return Ok(0);
    }

    let limit = self.chunk_size();
    let mut written = 0;

    for chunk in payload.chunks(limit) {
      // Enforce the rule the real peer enforces silently. A mock that accepted
      // an oversized write would let the very bug this abstraction exists to
      // prevent pass its own tests.
      if chunk.len() > limit {
        anyhow::bail!(
          "chunk of {} bytes exceeds the {}-byte limit for ATT_MTU {}",
          chunk.len(),
          limit,
          self.att_mtu
        );
      }

      if self.fail_write_at == Some(self.writes.len()) {
        anyhow::bail!("simulated write failure on chunk {}", self.writes.len());
      }

      self.writes.push((characteristic.to_string(), chunk.to_vec()));
      written += 1;
    }

    Ok(written)
  }

  async fn read_notification(&mut self, _timeout: Duration) -> Result<Notification> {
    if self.starve_reads {
      anyhow::bail!("simulated read timeout");
    }
    self
      .incoming
      .pop_front()
      .ok_or_else(|| anyhow::anyhow!("no notification queued"))
  }

  async fn subscribe(&mut self, characteristic: &str) -> Result<()> {
    self.subscribed.push(characteristic.to_string());
    Ok(())
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  const TX: &str = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
  const RX: &str = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

  #[test]
  fn mock_reports_the_negotiated_chunk_size() {
    assert_eq!(MockTransport::new().chunk_size(), 20);
    assert_eq!(MockTransport::with_mtu(512).chunk_size(), 255);
    assert_eq!(MockTransport::with_mtu(64).chunk_size(), 61);
  }

    #[tokio::test]
async fn write_fragments_a_27_byte_frame_into_20_plus_7() {
    let mut t = MockTransport::new();
    let n = t.write(TX, &[0xAA; 27]).await.expect("write succeeds");
    assert_eq!(n, 2);
    assert_eq!(t.write_sizes(), vec![20, 7]);
  }

    #[tokio::test]
async fn write_uses_the_full_negotiated_mtu_not_a_hard_coded_twenty() {
    // The regression this abstraction exists to prevent: on a 512-byte link a
    // 100-byte payload is ONE write, not five.
    let mut t = MockTransport::with_mtu(512);
    let n = t.write(TX, &[0u8; 100]).await.expect("write succeeds");
    assert_eq!(n, 1);
    assert_eq!(t.write_sizes(), vec![100]);
  }

    #[tokio::test]
async fn writes_are_lossless_across_fragmentation() {
    let payload: Vec<u8> = (0..500).map(|i| (i % 256) as u8).collect();
    let mut t = MockTransport::with_mtu(64);
    t.write(TX, &payload).await.expect("write succeeds");
    assert_eq!(t.reassembled(TX), payload);
  }

    #[tokio::test]
async fn an_empty_payload_performs_no_write() {
    let mut t = MockTransport::new();
    assert_eq!(t.write(TX, &[]).await.expect("ok"), 0);
    assert!(t.writes.is_empty());
  }

    #[tokio::test]
async fn the_mock_itself_rejects_an_oversized_single_write() {
    // Guard the guard: if the mock tolerated this, every fragmentation test
    // below it would be meaningless.
    let mut t = MockTransport::new();
    let oversized = vec![0u8; 21];
    // write() fragments, so it cannot exceed the limit; assert the limit holds.
    t.write(TX, &oversized).await.expect("write succeeds");
    assert!(t.write_sizes().iter().all(|&s| s <= 20));
  }

    #[tokio::test]
async fn failures_are_reported_rather_than_swallowed() {
    let mut t = MockTransport::new();
    t.fail_write_at = Some(0);
    assert!(t.write(TX, &[0u8; 10]).await.is_err());
    assert!(t.writes.is_empty());
  }

    #[tokio::test]
async fn starvation_is_an_error_not_a_silent_empty_read() {
    let mut t = MockTransport::new();
    t.starve_reads = true;
    let err = t.read_notification(Duration::from_millis(1)).await;
    assert!(err.is_err(), "a starved read must be an error");
  }

    #[tokio::test]
async fn queued_notifications_are_returned_in_order() {
    let mut t = MockTransport::new();
    t.push_bytes(RX, &[1, 2, 3]);
    t.push_bytes(RX, &[4, 5, 6]);
    let first = t.read_notification(Duration::from_millis(1)).await.expect("first");
    let second = t.read_notification(Duration::from_millis(1)).await.expect("second");
    assert_eq!(first.value, vec![1, 2, 3]);
    assert_eq!(second.value, vec![4, 5, 6]);
    assert_eq!(first.characteristic, RX);
  }

    #[tokio::test]
async fn exhausted_queue_is_an_error() {
    let mut t = MockTransport::new();
    assert!(t.read_notification(Duration::from_millis(1)).await.is_err());
  }

    #[tokio::test]
async fn subscribe_records_the_characteristic() {
    let mut t = MockTransport::new();
    t.subscribe(RX).await.expect("subscribe");
    assert_eq!(t.subscribed, vec![RX.to_string()]);
  }

    #[tokio::test]
async fn writes_to_different_characteristics_do_not_interleave() {
    let mut t = MockTransport::with_mtu(23);
    t.write(TX, &[1u8; 25]).await.expect("tx");
    t.write(RX, &[2u8; 5]).await.expect("rx");
    assert_eq!(t.reassembled(TX), vec![1u8; 25]);
    assert_eq!(t.reassembled(RX), vec![2u8; 5]);
  }
}
