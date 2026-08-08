use crate::consts::{MiCommands, Registers};
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

const NB_CHUNK_SIZE : usize = 20;
const MI_CHUNK_SIZE : usize = 18;

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
      rx
    };

    Ok(instance)
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

    self.write(reg, MiCommands::RCV_RDY).await?;

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
    self.write(reg, MiCommands::RCV_OK).await?;

    Ok(received_data)
  }

  pub async fn write_nb_parcel(&self, reg: &Registers, data: &[u8]) -> Result<bool> {
    let channel = self.channel_for(reg)?;

    for chunk in data.chunks(NB_CHUNK_SIZE) {
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

    // The chunk index is a single byte on the wire, so an oversized parcel
    // would overflow it: a panic in debug builds, and in release a silent wrap
    // that corrupts the frame sequence and leaves the receiver waiting for
    // frames that never arrive.
    let chunks = (data.len() + MI_CHUNK_SIZE - 1) / MI_CHUNK_SIZE;
    if chunks > u8::MAX as usize {
      return Err(anyhow!(
        "Mi parcel is {} bytes ({} chunks), but only {} chunks can be addressed",
        data.len(), chunks, u8::MAX
      ));
    }

    let mut buffer : Vec<u8> = Vec::with_capacity(2 + MI_CHUNK_SIZE);

    for (i, chunk) in data.chunks(MI_CHUNK_SIZE).enumerate() {
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
