use std::sync::Arc;
use crate::profile::{ScooterProfile, M365Profile, CryptoStrategy};
pub use super::payload::Payload;
use super::commands::ScooterCommand;
use crate::protocol::MiProtocol;
use crate::mi_crypto::{encrypt_uart, decrypt_uart, LoginKeychain};
use crate::consts::Registers;

use anyhow::{anyhow, Result};
use btleplug::platform::Peripheral;

pub struct MiSession {
  protocol: MiProtocol,
  profile: Arc<dyn ScooterProfile>,
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
    Self::with_profile(device, keys, Arc::new(M365Profile)).await
  }

  /// 車款須由呼叫端辨識；此登入工作階段只接受 Xiaomi 登入策略。
  pub async fn with_profile(device: &Peripheral, keys: &LoginKeychain, profile: Arc<dyn ScooterProfile>) -> Result<Self> {
    if profile.crypto_strategy() != CryptoStrategy::XiaomiLogin {
      return Err(anyhow!("Profile requires a different session strategy"));
    }
    let protocol = MiProtocol::with_profile(device, profile.as_ref()).await?;
    let keys = keys.clone();

    Ok(Self { protocol, profile, keys, seq: 0 })
  }

  pub fn profile(&self) -> &dyn ScooterProfile { self.profile.as_ref() }

  /**
   * Serialize, encrypt and send command to scooter
   */
  pub async fn send(&mut self, cmd: &ScooterCommand) -> Result<()> {
    self.send_bytes(&cmd.as_bytes_for(self.profile.as_ref())?).await
  }

  /// 未定義控制命令時，在加密與 BLE 寫入前拒絕。
  pub(crate) async fn send_control(&mut self, command: Option<crate::profile::ControlCommand>) -> Result<()> {
    let command = command.ok_or_else(|| anyhow!("Control is not supported by this profile"))?;
    self.send_bytes(&command.bytes()).await
  }

  async fn send_bytes(&mut self, payload: &[u8]) -> Result<()> {
    // Advance the counter before use so that the very first frame is not sent
    // with counter 0 twice if a previous send failed mid-flight.
    self.seq = self.seq.checked_add(1)
      .ok_or_else(|| anyhow!("UART frame counter exhausted; the session must be re-established"))?;

    let bytes = encrypt_uart(&self.keys.app, payload, self.seq, None)?;
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
