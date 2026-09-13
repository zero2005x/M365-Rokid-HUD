use core::fmt::Debug;
use pretty_hex::*;
use thiserror::Error;

#[derive(Clone)]
pub enum Direction {
  MasterToMotor,
  MasterToBattery,
  MotorToMaster,
  BatteryToMaster,
}

impl Direction {
  fn value(&self) -> u8 {
    match self {
      Direction::MasterToMotor      => 0x20,
      Direction::MasterToBattery    => 0x22,
      Direction::MotorToMaster      => 0x23,
      Direction::BatteryToMaster    => 0x25,
    }
  }
}

#[derive(Clone)]
pub enum ReadWrite {
  Read,
  Write
}

impl ReadWrite {
  fn value(&self) -> u8 {
    match self {
      ReadWrite::Read     => 0x01,
      ReadWrite::Write    => 0x03
    }
  }
}

#[derive(Clone, Copy)]
#[repr(usize)]
pub enum Attribute {
  GeneralInfo,
  MotorInfo,
  DistanceLeft,
  Speed,
  TripDistance,
  BatteryVoltage,
  BatteryCurrent,
  BatteryPercent,
  BatteryCellVoltages,
  Supplementary,
  Cruise,
  TailLight,
  BatteryInfo,
  Lock,
  Unlock
}

#[derive(Clone)]
pub struct ScooterCommand {
  pub direction: Direction,
  pub read_write: ReadWrite,
  pub attribute: Attribute,
  pub payload: Vec<u8>
}

/// Largest payload that still fits in the single-byte length field.
///
/// The length byte encodes `read_write + attribute + payload`, so two bytes of
/// the budget are already spoken for.
pub const MAX_PAYLOAD_LEN: usize = u8::MAX as usize - 2;

#[derive(Error, Debug)]
pub enum CommandError {
  #[error("Payload is {0} bytes, but the length field can only encode up to {MAX_PAYLOAD_LEN}")]
  PayloadTooLong(usize),
  #[error("Command is not defined by this profile")]
  UnsupportedCommand,
}

impl Debug for ScooterCommand {
  fn fmt(&self, form: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
    match self.as_bytes() {
      Ok(bytes) => write!(form, "{:?}", bytes.hex_dump()),
      Err(err) => write!(form, "<invalid ScooterCommand: {}>", err),
    }
  }
}

impl ScooterCommand {
  /// Serialises the command into its on-wire representation.
  ///
  /// The leading length byte counts `read_write + attribute + payload`. An
  /// oversized payload is rejected instead of being silently truncated (or
  /// wrapping around) into a corrupt frame.
  pub fn as_bytes(&self) -> Result<Vec<u8>, CommandError> {
    self.as_bytes_for(&crate::profile::M365Profile)
  }

  /// 依連線綁定的車款查表，不回退到其他車款。
  pub fn as_bytes_for(&self, profile: &dyn crate::profile::ScooterProfile) -> Result<Vec<u8>, CommandError> {
    if self.payload.len() > MAX_PAYLOAD_LEN {
      return Err(CommandError::PayloadTooLong(self.payload.len()));
    }

    // Cannot overflow: checked against MAX_PAYLOAD_LEN above.
    let len = self.payload.len() as u8 + 2u8;

    let mut bytes : Vec<u8> = Vec::with_capacity(4 + self.payload.len());
    bytes.push(len);
    bytes.push(self.direction.value());
    bytes.push(self.read_write.value());
    bytes.push(*profile.register_map().legacy_addresses.get(self.attribute as usize)
      .ok_or(CommandError::UnsupportedCommand)?);
    bytes.extend_from_slice(&self.payload);
    Ok(bytes)
  }
}
