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

#[derive(Clone)]
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

impl Attribute {
  fn value(&self) -> u8 {
    match self {
      Attribute::GeneralInfo          => 0x10,
      Attribute::DistanceLeft         => 0x25,
      Attribute::Speed                => 0xB5,
      Attribute::TripDistance         => 0xB9,
      Attribute::BatteryVoltage       => 0x34,
      Attribute::BatteryCurrent       => 0x33,
      Attribute::BatteryPercent       => 0x32,
      Attribute::MotorInfo            => 0xB0,
      Attribute::BatteryCellVoltages  => 0x40,
      Attribute::Supplementary        => 0x7B,
      Attribute::Cruise               => 0x7C,
      Attribute::TailLight            => 0x7D,
      Attribute::BatteryInfo          => 0x31,
      Attribute::Lock                 => 0x70,
      Attribute::Unlock               => 0x71
    }
  }
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
    if self.payload.len() > MAX_PAYLOAD_LEN {
      return Err(CommandError::PayloadTooLong(self.payload.len()));
    }

    // Cannot overflow: checked against MAX_PAYLOAD_LEN above.
    let len = self.payload.len() as u8 + 2u8;

    let mut bytes : Vec<u8> = Vec::with_capacity(4 + self.payload.len());
    bytes.push(len);
    bytes.push(self.direction.value());
    bytes.push(self.read_write.value());
    bytes.push(self.attribute.value());
    bytes.extend_from_slice(&self.payload);
    Ok(bytes)
  }
}
