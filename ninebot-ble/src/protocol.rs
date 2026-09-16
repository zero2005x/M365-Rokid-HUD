use crate::consts::{MiCommands, Registers};
use crate::mtu::{chunk_size_for, DEFAULT_CHUNK_SIZE};
use crate::transport::{Notification, Transport};
use uuid::Uuid;
use futures::Stream;
use futures::stream::StreamExt;
use pretty_hex::*;
use std::{pin::Pin, boxed::Box};
use btleplug::platform::{Peripheral};
use tokio::time::timeout;
use std::time::Duration;
use btleplug::api::{Peripheral as _, Characteristic, WriteType, ValueNotification};
use anyhow::{Context, Result, anyhow};

// ATT MTU arithmetic lives in `crate::mtu`, shared with every other writer.
// It used to be defined here as well, which is how a hard-coded 20-byte chunk
// and a 512-byte MTU request ended up in the same code path.

/// Bytes of Mi framing that sit in front of each chunk's data.
///
/// A Mi parcel chunk is `[index][0x00][data…]`, so only
/// `usable_payload - MI_CHUNK_HEADER` bytes of the parcel fit in one write.
/// The previous code hard-coded the resulting 18 and never derived it, which
/// meant the two constants could drift apart silently.
const MI_CHUNK_HEADER : usize = 2;

/// Per-frame timeout while reading a multi-frame Mi parcel. Without it a lost
/// frame blocks the caller forever.
const MI_PARCEL_TIMEOUT : Duration = Duration::from_secs(5);

/// Default timeout used by [`MiProtocol::wait_for_notification`].
const DEFAULT_NOTIFICATION_TIMEOUT : Duration = Duration::from_secs(10);

/**
 * This structs hides all bluetooth shenanigans under easy to use commands.
 */
pub struct MiProtocol {
  device: Peripheral,
  avdtp: Characteristic,
  upnp: Characteristic,
  tx: Characteristic,
  rx: Characteristic,
  stream: Pin<Box<dyn Stream<Item = ValueNotification> + Send>>,
  /// Largest ATT payload for this link, from the negotiated MTU.
  ///
  /// Stored on the instance rather than read from a global because the MTU is a
  /// property of one connection: two peripherals can grant different values.
  chunk_size: usize,
}

impl MiProtocol {
  pub async fn new(device: &Peripheral) -> Result<Self> {
    let (avdtp, upnp, tx, rx) = setup_channels(&device).await?;
    let stream : Pin<Box<dyn Stream<Item = ValueNotification> + Send>> = device.notifications().await
      .with_context(|| format!("Could not load notifications stream"))?;
    let device = device.clone();

    let instance = Self {
      device,
      stream,
      avdtp,
      upnp,
      tx,
      rx,
      chunk_size: DEFAULT_CHUNK_SIZE,
    };

    Ok(instance)
  }

  /**
   * Sets the usable ATT payload from a negotiated ATT_MTU.
   *
   * Call this from the platform's MTU-changed callback. Until it is called the
   * connection uses [`DEFAULT_CHUNK_SIZE`], which is correct but slower than
   * necessary on a link that granted a larger MTU.
   *
   * Deliberately not a constructor argument: the MTU callback can fire after
   * the connection object exists, and `btleplug` does not expose the negotiated
   * value synchronously.
   */
  pub fn set_att_mtu(&mut self, att_mtu: usize) {
    self.chunk_size = chunk_size_for(att_mtu);
    tracing::debug!("ATT MTU {} -> chunk size {} bytes", att_mtu, self.chunk_size);
  }

  /// Largest ATT payload currently in use for this link.
  pub fn chunk_size(&self) -> usize {
    self.chunk_size
  }

  pub async fn dispose(&self) -> Result<bool> {
    self.device.unsubscribe(&self.avdtp).await?;
    self.device.unsubscribe(&self.upnp).await?;
    self.device.unsubscribe(&self.rx).await?;

    Ok(true)
  }

  fn reg_to_channel(&self, reg : &Registers) -> Option<&Characteristic> {
    match reg {
      Registers::RX => Some(&self.rx),
      Registers::TX => Some(&self.tx),
      Registers::AVDTP => Some(&self.avdtp),
      Registers::UPNP => Some(&self.upnp),
      _ => None
    }
  }

  /// Resolves a register to its characteristic, or reports an error.
  ///
  /// Registers such as `AUTH`/`UART` name services rather than writable
  /// characteristics, so this is ordinary invalid input and must not panic.
  fn channel_for(&self, reg : &Registers) -> Result<&Characteristic> {
    self.reg_to_channel(reg)
      .ok_or_else(|| anyhow!("Register {:?} does not map to a writable characteristic", reg))
  }

  /**
   * Read next notification
   */
  pub async fn next(&mut self) -> Option<ValueNotification> {
    tracing::debug!("Waiting for notifications...");
    self.stream.next().await
  }

  /**
   * Returns a human-readable name for one of the four characteristics this
   * protocol uses, or `None` for anything else.
   *
   * The name is the same UUID string that [`Transport`] callers pass in, so the
   * trait implementation below is a pure lookup rather than a re-derivation of
   * which characteristic is which.
   */
  fn characteristic_name(uuid: &Uuid) -> Option<&'static str> {
    let uart = Registers::UART.to_uuid();
    let auth = Registers::AUTH.to_uuid();
    if *uuid == Registers::TX.to_uuid() { Some(TX_NAME) }
    else if *uuid == Registers::RX.to_uuid() { Some(RX_NAME) }
    else if *uuid == Registers::AVDTP.to_uuid() { Some(AVDTP_NAME) }
    else if *uuid == Registers::UPNP.to_uuid() { Some(UPNP_NAME) }
    else {
      // Guard against the two service UUIDs being passed by mistake; they are
      // not writable characteristics and would otherwise look plausible.
      let _ = (uart, auth);
      None
    }
  }

  /// Resolves a [`Transport`] characteristic name back to its handle.
  fn channel_for_name(&self, name: &str) -> Result<&Characteristic> {
    match name {
      TX_NAME => Ok(&self.tx),
      RX_NAME => Ok(&self.rx),
      AVDTP_NAME => Ok(&self.avdtp),
      UPNP_NAME => Ok(&self.upnp),
      other => Err(anyhow!(
        "Unknown characteristic '{other}'. Expected one of {TX_NAME}, {RX_NAME}, {AVDTP_NAME}, {UPNP_NAME}."
      )),
    }
  }

  /**
   * Waits for the next notification, honouring a caller-supplied timeout.
   *
   * This is the primitive the [`Transport`] implementation needs; the public
   * [`MiProtocol::next`] keeps its unbounded signature because it is used by
   * interactive example code.
   */
  pub async fn next_with_timeout(&mut self, duration: Duration) -> Result<ValueNotification> {
    match timeout(duration, self.stream.next()).await {
      Ok(Some(notification)) => Ok(notification),
      Ok(None) => Err(anyhow!(
        "Notification stream ended: the scooter disconnected or unsubscribed"
      )),
      Err(_) => Err(anyhow!(
        "Timed out after {:?} waiting for a notification from the scooter",
        duration
      )),
    }
  }

  pub async fn wait_for_scooter_to_receive_data(&mut self) -> Result<bool> {
    match self.next_mi_response().await {
      Some(MiCommands::RCV_RDY) => Ok(true),
      Some(state) => Err(anyhow!("Expected state: {:?}, but received: {:?}", MiCommands::RCV_RDY, state)),
      None => Err(anyhow!("Invalid response received from scooter"))
    }
  }

  pub async fn wait_for_scooter_to_ack_data(&mut self) -> Result<bool> {
    match self.next_mi_response().await {
      Some(MiCommands::RCV_OK) => Ok(true),
      Some(state) => Err(anyhow!("Expected state: {:?}, but received: {:?}", MiCommands::RCV_OK, state)),
      None => Err(anyhow!("Invalid response received from scooter"))
    }
  }

  /**
   * Try to read next notification as MiCommand response
   */
  pub async fn next_mi_response(&mut self) -> Option<MiCommands> {
    if let Some(data) = self.next().await {
      if let Ok(cmd) = MiCommands::try_from(data.clone()) {
        tracing::debug!("<- {:?}", cmd);
        return Some(cmd)
      } else {
        tracing::debug!("These bytes don't look like mi response: {:?}", data);
      }
    }

    None
  }

  /**
   * Try to read next notification, If nothing comes in specified duration throw error
   */
  pub async fn wait_for_notification_with_timeout(&mut self, duration : Duration) -> Result<ValueNotification> {
    let response = timeout(duration, self.next()).await?;//TODO: map to timeout error

    if let Some(notification) = response {
      return Ok(notification)
    }

    Err(anyhow!("Received empty message from mi scooter..."))
  }

  /**
   * Try to read next notification. If nothing comes in
   * `DEFAULT_NOTIFICATION_TIMEOUT` (10 seconds) raise an error.
   */
  pub async fn wait_for_notification(&mut self) -> Result<ValueNotification> {
    self.wait_for_notification_with_timeout(DEFAULT_NOTIFICATION_TIMEOUT).await
  }

  /**
   * Send mi command to register on scooter
   */
  pub async fn write(&self, reg: &Registers, command: MiCommands) -> Result<bool> {
    let channel = self.channel_for(reg)?;
    tracing::debug!("-> {:?} -> {:?}", command, &reg);

    self.device.write(&channel, &command.to_bytes(), WriteType::WithoutResponse).await
      .with_context(|| format!("Could not write command: {:?} to {:?}", command, &reg))?;

    Ok(true)
  }

  /**
   * Ninebot protocol sends multiple messages. I don't know how long they will be, but this is persistent per command, so you can specify it as arg
   */
  pub async fn read_nb_parcel(&mut self, frames: u8) -> Result<Vec<u8>> {
    let mut buffer : Vec<u8> = Vec::new();
    let mut frames_left = frames;
    let duration = Duration::from_secs(5);

    tracing::debug!("Reading nb frames: {}", frames_left);
    while frames_left > 0 {
      tracing::debug!("  Reading frame...");
      let notification = self.wait_for_notification_with_timeout(duration).await?;
      tracing::debug!("  Received data: {:?}", notification.value.hex_dump());
      buffer.extend_from_slice(notification.value.as_slice());
      frames_left -= 1;
    }

    tracing::debug!("  Finished reading: {:?}", buffer.hex_dump());
    Ok(buffer)
  }

  /**
   * Read parcel data send in multiple messages from scooter using mi protocol
   */
  pub async fn read_mi_parcel(&mut self, reg: &Registers) -> Result<Vec<u8>> {
    tracing::debug!("Reading parcel...");

    let mut received_data : Vec<u8> = Vec::new();

    // The header carries the frame count at offset 4..6. Read it through a
    // timeout so a silent scooter cannot block the caller forever, and validate
    // the length before indexing: this data is attacker/fault controlled.
    let header = self.wait_for_notification_with_timeout(MI_PARCEL_TIMEOUT).await
      .with_context(|| "Timed out waiting for the Mi parcel header")?;

    if header.value.len() < 6 {
      return Err(anyhow!(
        "Mi parcel header is too short: {} bytes (expected at least 6)",
        header.value.len()
      ));
    }

    let total_frames : u16 = header.value[4] as u16 + 0x100 * header.value[5] as u16;
    tracing::debug!("Expecting {} frames", total_frames);

    MiProtocol::write(self, reg, MiCommands::RCV_RDY).await?;

    let mut frames_seen : u16 = 0;
    loop {
      let data = self.wait_for_notification_with_timeout(MI_PARCEL_TIMEOUT).await
        .with_context(|| format!(
          "Timed out after {} of {} Mi parcel frames", frames_seen, total_frames
        ))?;

      let current_frame = what_frame(&data.value)?;
      tracing::debug!("Current frame {}", current_frame);

      received_data.extend_from_slice(&data.value[2..]);
      frames_seen = frames_seen.saturating_add(1);

      if current_frame == total_frames {
        break;
      }
    }

    // Only acknowledge once the whole parcel actually arrived. Acknowledging a
    // partial parcel desynchronises the Mi protocol on both ends.
    MiProtocol::write(self, reg, MiCommands::RCV_OK).await?;

    Ok(received_data)
  }

  pub async fn write_nb_parcel(&self, reg: &Registers, data: &[u8]) -> Result<bool> {
    let channel = self.channel_for(reg)?;

    for chunk in data.chunks(self.chunk_size) {
      tracing::debug!("Writing nb chunk to {:?}: {:?}", reg, chunk.hex_dump());
      self.device.write(&channel, &chunk, WriteType::WithoutResponse).await
        .with_context(|| format!("Could not write mi chunk: for channel: {:?}", channel))?;
    }

    Ok(true)
  }

  /**
   * Send big data parcel to scooter using mi protocol
   */
  pub async fn write_mi_parcel(&self, reg: &Registers, data: &[u8]) -> Result<bool> {
    let channel = self.channel_for(reg)?;

    // The Mi framing header occupies part of every write, so only the remainder
    // is available for parcel data.
    let mi_chunk_size = self.chunk_size.saturating_sub(MI_CHUNK_HEADER).max(1);

    // The chunk index is a single byte on the wire, so an oversized parcel
    // would overflow it: a panic in debug builds, and in release a silent wrap
    // that corrupts the frame sequence and leaves the receiver waiting for
    // frames that never arrive.
    let chunks = (data.len() + mi_chunk_size - 1) / mi_chunk_size;
    if chunks > u8::MAX as usize {
      return Err(anyhow!(
        "Mi parcel is {} bytes ({} chunks at {} bytes each), but only {} chunks can be addressed",
        data.len(), chunks, mi_chunk_size, u8::MAX
      ));
    }

    let mut buffer : Vec<u8> = Vec::with_capacity(MI_CHUNK_HEADER + mi_chunk_size);

    for (i, chunk) in data.chunks(mi_chunk_size).enumerate() {
      // Safe: `chunks` was bounded by u8::MAX above, so i + 1 <= 255.
      let chunk_index = (i + 1) as u8;

      buffer.clear();
      buffer.push(chunk_index);
      buffer.push(0);
      buffer.extend_from_slice(chunk);

      tracing::debug!("Writing mi chunk {} to {:?}: {:?}", chunk_index, reg, buffer.hex_dump());
      self.device.write(channel, &buffer, WriteType::WithoutResponse).await
        .with_context(|| format!("Could not write mi chunk: {} for channel: {:?}", chunk_index, channel))?;
    }

    Ok(true)
  }
}

async fn find_characteristic(device : &Peripheral, service_uuid: Uuid, char_uuid: Uuid) -> Result<Characteristic> {
  device.discover_services().await
    .with_context(|| format!("Could not enable discovering devices"))?;

  for ch in device.characteristics() {
    if ch.uuid == char_uuid && ch.service_uuid == service_uuid {
      tracing::debug!("Found Characteristic: {:?}", ch);
      return Ok(ch)
    } else {
      tracing::debug!("Skipped Characteristic: {:?}", ch);
    }
  }

  Err(anyhow!("Could not find characteristic: {}", char_uuid))
}

/// Reads the little-endian frame counter from the first two bytes of a Mi frame.
///
/// The parentheses are load-bearing: `&` binds looser than `+`/`*` in Rust, so
/// the unparenthesised form collapsed to `bytes[0]` and any frame count above
/// 255 was misread as its low byte, which made the loop in `read_mi_parcel`
/// never reach its terminating condition.
fn what_frame(bytes: &[u8]) -> Result<u16> {
  if bytes.len() < 2 {
    return Err(anyhow!("Mi frame is too short to contain a frame counter ({} bytes)", bytes.len()));
  }

  Ok((bytes[0] as u16 & 0xff) + 0x100 * (bytes[1] as u16 & 0xff))
}

async fn setup_channels(device : &Peripheral) -> Result<(Characteristic, Characteristic, Characteristic, Characteristic)> {
  let mut retries = 5;
  loop {
    // Windows BLE: verify connection is stable before discovering services
    if !device.is_connected().await.unwrap_or(false) {
      if retries == 0 {
        return Err(anyhow!("Not connected"));
      }
      tracing::warn!("Device not connected, waiting... ({} retries left)", retries);
      retries -= 1;
      tokio::time::sleep(Duration::from_millis(2000)).await;
      continue;
    }
    
    // Additional stabilization delay before service discovery on Windows
    #[cfg(target_os = "windows")]
    tokio::time::sleep(Duration::from_millis(500)).await;
    
    match device.discover_services().await {
      Ok(_) => {
        // Verify we got services
        let services = device.services();
        if services.is_empty() {
          if retries == 0 {
            return Err(anyhow!("No services discovered"));
          }
          tracing::warn!("No services found, retrying... ({} retries left)", retries);
          retries -= 1;
          tokio::time::sleep(Duration::from_millis(2000)).await;
          continue;
        }
        break;
      },
      Err(e) => {
        if retries == 0 {
          return Err(e.into());
        }
        tracing::warn!("Failed to discover services, retrying... ({})", e);
        retries -= 1;
        tokio::time::sleep(Duration::from_millis(2000)).await;
      }
    }
  }

  // Auth channels
  tracing::debug!("Setting up AUTH channels");
  let avdtp = find_characteristic(device, Registers::AUTH.to_uuid(), Registers::AVDTP.to_uuid()).await?;
  let upnp = find_characteristic(device, Registers::AUTH.to_uuid(), Registers::UPNP.to_uuid()).await?;

  // UART channels
  tracing::debug!("Setting up UART channels");
  let tx = find_characteristic(device, Registers::UART.to_uuid(), Registers::TX.to_uuid()).await?;
  let rx = find_characteristic(device, Registers::UART.to_uuid(), Registers::RX.to_uuid()).await?;

  tracing::debug!("Enabling notify for AVDTP");
  device.subscribe(&avdtp).await
    .with_context(|| format!("Could not subscribe to scooter AVDTP notifications"))?;

  tracing::debug!("Enabling notify for UPNP");
  device.subscribe(&upnp).await
    .with_context(|| format!("Could not subscribe to scooter UPNP notifications"))?;

  tracing::debug!("Enabling notify for RX");
  device.subscribe(&rx).await
    .with_context(|| format!("Could not subscribe to scooter RX notifications"))?;

  Ok((avdtp, upnp, tx, rx))
}

// ===========================================================================
// Transport implementation
// ===========================================================================

/// Canonical names for the four characteristics, used as the `&str` keys in
/// [`Transport`]. These are the raw UUID strings, so a caller can pass a
/// `char.uuid.to_string()` value directly without a translation table.
pub const TX_NAME: &str = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
pub const RX_NAME: &str = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
pub const AVDTP_NAME: &str = "00000019-0000-1000-8000-00805f9b34fb";
pub const UPNP_NAME: &str = "00000010-0000-1000-8000-00805f9b34fb";

/// Lets the protocol layer be driven through [`crate::transport::Transport`],
/// so framing, fragmentation and reply parsing can be exercised against a mock
/// instead of a physical scooter.
///
/// The MTU comes from [`MiProtocol::chunk_size`], which
/// [`MiProtocol::set_att_mtu`] updates from the platform's MTU callback. The
/// split itself is `data.chunks(self.chunk_size)`, exactly as the inherent
/// write methods do — one rule, one implementation.
#[async_trait::async_trait]
impl Transport for MiProtocol {
  fn chunk_size(&self) -> usize {
    self.chunk_size
  }

  async fn write(&mut self, characteristic: &str, payload: &[u8]) -> Result<usize> {
    let channel = self.channel_for_name(characteristic)?;

    if payload.is_empty() {
      return Ok(0);
    }

    let mut written = 0usize;
    for chunk in payload.chunks(self.chunk_size) {
      self.device
        .write(channel, chunk, WriteType::WithoutResponse)
        .await
        .with_context(|| {
          format!(
            "Could not write chunk {} ({} bytes) to {characteristic}",
            written, chunk.len()
          )
        })?;
      written += 1;
    }

    Ok(written)
  }

  async fn read_notification(&mut self, timeout: Duration) -> Result<Notification> {
    let value = self.next_with_timeout(timeout).await?;
    let name = MiProtocol::characteristic_name(&value.uuid)
      .map(|s| s.to_string())
      // An unexpected characteristic is worth surfacing rather than dropping:
      // it usually means the scooter's GATT layout differs from the M365 one.
      .unwrap_or_else(|| value.uuid.to_string());
    Ok(Notification::new(name, value.value))
  }

  async fn subscribe(&mut self, characteristic: &str) -> Result<()> {
    let channel = self.channel_for_name(characteristic)?;
    self
      .device
      .subscribe(channel)
      .await
      .with_context(|| format!("Could not subscribe to {characteristic}"))?;
    Ok(())
  }
}
