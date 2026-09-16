// The `MiSession` methods at the bottom drive a real radio; the info types and
// their payload decoders above are pure protocol and stay available without the
// `ble` feature, so the multi-model register tables can be tested on a host.
#[cfg(feature = "ble")]
use super::MiSession;
use super::Payload;
#[cfg(feature = "ble")]
use super::commands::{ScooterCommand, Direction, Attribute, ReadWrite};

use std::time::Duration;
use anyhow::Result;
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct GeneralInfo {
  // Public: returned from `MiSession::general_info()`, so callers outside the
  // crate must be able to read these without serialising the struct.
  pub serial: String,
  pub pin: String,
  pub version: String
}

#[derive(Debug, Serialize)]
pub struct MotorInfo {
  /**
   * Percent value between 0 and 100
   */
  pub battery_percent: u16,
  /**
   * Speed in kilometers per hour
   */
  pub speed_kmh: f32,
  /**
   * Speed in kilometers per hour
   */
  pub speed_average_kmh: f32,
  /**
   * Distance is in meters
   */
  pub total_distance_m: u32,
    /**
   * Distance is in meters
   */
  pub trip_distance_m: u16,
  pub uptime: Duration,
  /**
   * Temperature in celsius
   */
  pub frame_temperature: f32
}

impl TryFrom<Payload> for MotorInfo {
  type Error = anyhow::Error;

  fn try_from(payload: Payload) -> Result<Self, Self::Error> {
    Self::from_profile(payload, &crate::profile::M365Profile)
  }
}

impl MotorInfo {
  /// 舊 API 的輸出型別保留，數值欄位由車款資料表解碼。
  pub fn from_profile(payload: Payload, profile: &dyn crate::profile::ScooterProfile) -> Result<Self> {
    let bytes = payload.into_bytes();
    let data = bytes.get(3..).ok_or_else(|| anyhow::anyhow!("Truncated response header"))?;
    let t = profile.telemetry_decoder().decode(profile.register_map(), data)
      .map_err(anyhow::Error::msg)?;
    Ok(Self {
      battery_percent: t.battery_percent as u16,
      speed_kmh: t.speed_kmh as f32,
      speed_average_kmh: t.average_speed_kmh as f32,
      total_distance_m: t.odometer_m as u32,
      trip_distance_m: t.trip_m as u16,
      uptime: Duration::from_secs(t.uptime_s as u64),
      frame_temperature: t.temperature_c as f32,
    })
  }
}

#[cfg(feature = "ble")]
impl MiSession {
  pub async fn general_info(&mut self) -> Result<GeneralInfo> {
    tracing::debug!("Reading general information");

    let cmd = ScooterCommand {
      direction: Direction::MasterToMotor,
      read_write: ReadWrite::Read,
      attribute: Attribute::GeneralInfo,
      payload: vec![0x16]
    };

    self.send(&cmd).await?;
    //          [                      SERIAL                          ][          PIN         ][ VER  ]
    // payload: /x31/x36/x31/x33/x32/x2f/x30/x30/x30/x39/x35/x32/x39/x32/x30/x30/x30/x30/x30/x30/x38/x01
    let mut payload = self.read(2).await?;

    payload.pop_head()?;

    let serial = payload.pop_string_utf8(11)?;
    let pin = payload.pop_string_utf8(6)?;
    let version = payload.pop_string_utf8(2)?;

    Ok(GeneralInfo { serial, pin, version })
  }

  /**
   * Read scooter serial number
   */
  pub async fn serial_number(&mut self) -> Result<String> {
    tracing::debug!("Reading serial number");
    let cmd = ScooterCommand {
      direction: Direction::MasterToMotor,
      read_write: ReadWrite::Read,
      attribute: Attribute::GeneralInfo,
      payload: vec![0x0e]
    };

    self.send(&cmd).await?;
    let mut payload = self.read(2).await?;
    payload.pop_head()?;

    let serial = payload.pop_string_utf8(14)?;

    Ok(serial)
  }

  pub async fn motor_info(&mut self) -> Result<MotorInfo> {
    tracing::debug!("Reading motor info");

    self.send(&ScooterCommand {
      direction: Direction::MasterToMotor,
      read_write: ReadWrite::Read,
      attribute: Attribute::MotorInfo,
      payload: vec![0x20]
    }).await?;

    let payload = self.read(3).await?;

    MotorInfo::from_profile(payload, self.profile())
  }
}
