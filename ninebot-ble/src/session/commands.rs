use core::fmt::Debug;
use pretty_hex::*;
use thiserror::Error;

/// The four addressing bytes of the **Xiaomi** frame layout.
///
/// These are not four independent directions: they are two boards, each with the
/// id a request is sent to and the id its reply arrives on. `MasterToMotor`/`MotorToMaster`
/// are the ESC's `send_id`/`receive_id` (`0x20`/`0x23`) and
/// `MasterToBattery`/`BatteryToMaster` are the BMS's (`0x22`/`0x25`), exactly the
/// Xiaomi pair in [`crate::model::XIAOMI_BOARDS`].
///
/// **They are only correct for the Xiaomi lineage.** Every Ninebot family answers
/// on the id it was addressed on, and several sit at different addresses
/// altogether, so a session built on this enum cannot be reused for those models.
/// New code should take its addresses from
/// [`crate::model::ModelProfile::board_address`].
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

/// The operation selector byte.
///
/// ⚠️ [`ReadWrite::Write`] is `0x03` here, but **m365 Tools** uses `0x02` for a
/// write and `0x03` for a *write without response*. See
/// [`ScooterCommand::as_bytes`] for why this is documented rather than changed.
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
  ///
  /// ⚠️ **This is not the frame the reference implementation sends, and nothing in
  /// this repository calls it.** Two divergences were confirmed against
  /// **m365 Tools** (`app.peretti.m365tools`, statically analysed):
  ///
  /// 1. **No sync word and no checksum.** The reference frame is
  ///    `55 AA | len | to | cmd | reg | payload | cksum16LE` (or `5A A5 | len |
  ///    from | to | …`), where `cksum = (~Σ bytes) & 0xFFFF` over the length byte
  ///    and the body, little-endian. This function emits neither the magic nor the
  ///    checksum, and the length byte's meaning differs per framing (`+2`, `+0`
  ///    or `+6` — this models only the `+2` form).
  /// 2. **The write selector is wrong.** [`ReadWrite::Write`] is `0x03` here; the
  ///    reference uses `0x02` for a write and `0x03` for a write-without-response.
  ///
  /// `ninebot-ffi` does not reference this module, so no shipping path is affected
  /// today — which is why it is documented rather than patched blind. Before this
  /// is wired up, both must be corrected and checked against hardware. See
  /// `doc/reverse-engineering/m365tools-reports/05-write-commands.md`.
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
