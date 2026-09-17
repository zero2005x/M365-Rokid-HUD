pub use super::payload::Payload;
use super::commands::ScooterCommand;
use crate::protocol::MiProtocol;
use crate::mi_crypto::{encrypt_uart, decrypt_uart, LoginKeychain};
use crate::consts::Registers;

use anyhow::{anyhow, Result};
use btleplug::platform::Peripheral;

pub struct MiSession {
  protocol: MiProtocol,
  keys: LoginKeychain,
  /// Monotonic AES-CCM frame counter.
  ///
  /// This is fed into the nonce for every outgoing frame. It must never repeat
  /// for the lifetime of `keys`: reusing a counter reuses the CCM keystream,
  /// which lets an observer recover plaintext and forge commands.
  seq: u32,
}

impl MiSession {
  pub async fn new(device: &Peripheral, keys: &LoginKeychain) -> Result<Self> {
    let protocol = MiProtocol::new(device).await?;
    let keys = keys.clone();

    Ok(Self { protocol, keys, seq: 0 })
  }

  /**
   * Serialize, encrypt and send command to scooter
   */
  pub async fn send(&mut self, cmd: &ScooterCommand) -> Result<()> {
    // Advance the counter before use so that the very first frame is not sent
    // with counter 0 twice if a previous send failed mid-flight.
    self.seq = self.seq.checked_add(1)
      .ok_or_else(|| anyhow!("UART frame counter exhausted; the session must be re-established"))?;

    let bytes = encrypt_uart(&self.keys.app, &cmd.as_bytes()?, self.seq, None)?;
    self.protocol.write_nb_parcel(&Registers::TX, &bytes).await?;
    Ok(())
  }

  /**
   * Wait for response from scooter. You can specify number of frames that you expect to receive
   */
  pub async fn read(&mut self, frames: u8) -> Result<Payload> {
    let data = self.protocol.read_nb_parcel(frames).await?;
    let response = decrypt_uart(&self.keys.dev, &data)?;
    let payload = Payload::from(response);
    Ok(payload)
  }
}
