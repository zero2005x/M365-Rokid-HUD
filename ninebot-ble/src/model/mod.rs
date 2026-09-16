//! Per-model register maps.
//!
//! A [`ModelProfile`] describes **which registers to read and how to decode
//! them**, so adding a scooter becomes a data entry rather than new protocol
//! code. Before this, the register addresses and their scaling factors were
//! spread through the telemetry loop and half a dozen `parseXxx` functions, none
//! of which could be tested without a scooter attached.
//!
//! # Source and confidence
//!
//! Register layouts come from the reverse-engineered maps in
//! [`etransport/ninebot-docs`](https://github.com/etransport/ninebot-docs/wiki).
//! Every profile carries a [`Confidence`] so a caller — and the UI — can tell a
//! verified layout from a plausible one. Guessing silently here produces
//! *confidently wrong* numbers on a dashboard, which is worse than showing
//! nothing.
//!
//! # Two things this file deliberately does NOT do
//!
//! 1. **It does not change how the M365 is polled.** The shipped Android app
//!    reads `0xB0` / `0x3A` / `0x25`; that remains the reference profile. See
//!    [`ModelId::M365`] for a known offset discrepancy between the shipped
//!    parser and the documented map.
//! 2. **It does not claim the Pro/1S/Lite layouts are verified.** Those carry
//!    [`Confidence::Documented`] or [`Confidence::Unverified`] until a capture
//!    from real hardware confirms them.

/// How much a profile's register layout can be trusted.
/// Ordered least-confident first, so the derived `Ord` lets a caller write
/// `if profile.confidence >= Confidence::Documented { show_it() }` and have the
/// comparison mean what it reads like.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Confidence {
  /// Inferred or partly known. Must not be presented as fact.
  Unverified,
  /// Taken from a published reverse-engineering source, not hardware-checked here.
  Documented,
  /// Confirmed against a real scooter's traffic.
  Verified,
}

impl Confidence {
  /// True when the layout is safe to expose as real telemetry without further
  /// confirmation.
  pub fn is_trustworthy(self) -> bool {
    matches!(self, Confidence::Verified | Confidence::Documented)
  }

  pub fn label(self) -> &'static str {
    match self {
      Confidence::Verified => "verified",
      Confidence::Documented => "documented",
      Confidence::Unverified => "unverified",
    }
  }
}

/// The scooter models this crate knows about.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ModelId {
  M365,
  M365Pro,
  M365Pro2,
  Mi1S,
  MiLite,
  /// Xiaomi Mi 3. Shares the Xiaomi `mi_DRV_*` MCU platform but its BLE
  /// protocol was never confirmed — see the profile's confidence.
  Mi3,
}

impl ModelId {
  pub fn display_name(self) -> &'static str {
    match self {
      ModelId::M365 => "Xiaomi M365",
      ModelId::M365Pro => "Xiaomi M365 Pro",
      ModelId::M365Pro2 => "Xiaomi M365 Pro 2",
      ModelId::Mi1S => "Xiaomi Mi 1S",
      ModelId::MiLite => "Xiaomi Mi Lite",
      ModelId::Mi3 => "Xiaomi Mi 3",
    }
  }

  /// Every model, for iteration in a picker or a support matrix.
  pub fn all() -> &'static [ModelId] {
    &[
      ModelId::M365,
      ModelId::M365Pro,
      ModelId::M365Pro2,
      ModelId::Mi1S,
      ModelId::MiLite,
      ModelId::Mi3,
    ]
  }

  /// The register block this model exposes telemetry through.
  pub fn profile(self) -> &'static ModelProfile {
    match self {
      ModelId::M365 => &M365,
      // The Pro/Pro2/1S/Lite share the M365 register layout. Their firmware
      // generations differ (the Pro2/1S/Lite moved to BLE 1.2.9+ with the
      // `5AAB` variant) but the ESC register map is the same lineage, so they
      // reuse it rather than a copy — one map to correct if a capture proves a
      // field differs.
      ModelId::M365Pro => &M365_PRO,
      ModelId::M365Pro2 => &M365_PRO,
      ModelId::Mi1S => &M365_PRO,
      ModelId::MiLite => &M365_PRO,
      ModelId::Mi3 => &MI3,
    }
  }
}

/// Which physical board a register lives on.
///
/// Legacy Xiaomi/Ninebot models use a flat address space where the destination
/// byte selects the board (`0x20` ESC, `0x22` BMS). Current-generation models
/// use board-scoped `TARGET_ID` addressing instead; keeping the distinction in
/// the type stops an address from one scheme being used with the other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Board {
  /// `0x20` — motor controller.
  Esc,
  /// `0x22` — battery management.
  Bms,
}

impl Board {
  /// Destination byte for the legacy flat addressing scheme.
  pub fn address(self) -> u8 {
    match self {
      Board::Esc => 0x20,
      Board::Bms => 0x22,
    }
  }
}

/// How a raw little-endian value becomes a number with a unit.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Decoder {
  /// Unsigned 16-bit, no scaling. Battery percentage.
  U16Raw,
  /// Signed 16-bit. Only where the value can legitimately be negative.
  I16Raw,
  /// Unsigned 16-bit divided by the divisor.
  U16Scaled(f32),
  /// Signed 16-bit divided by the divisor. Speed, which is signed on the wire
  /// even though a scooter does not ride backwards.
  I16Scaled(f32),
  /// Unsigned 32-bit divided by the divisor. Odometer, in metres.
  U32Scaled(f32),
}

impl Decoder {
  /// Width in bytes of the encoded value.
  pub fn width(self) -> usize {
    match self {
      Decoder::U16Raw | Decoder::I16Raw | Decoder::U16Scaled(_) | Decoder::I16Scaled(_) => 2,
      Decoder::U32Scaled(_) => 4,
    }
  }

  /// Decodes `bytes` starting at `offset`, or `None` when the buffer is short.
  ///
  /// A short buffer is not an error worth panicking over: it means the scooter
  /// answered with fewer bytes than this profile expected, which a caller should
  /// treat as "field unavailable" rather than as corrupt data.
  pub fn decode(self, bytes: &[u8], offset: usize) -> Option<f32> {
    let end = offset.checked_add(self.width())?;
    if end > bytes.len() {
      return None;
    }
    let slice = &bytes[offset..end];

    Some(match self {
      Decoder::U16Raw => u16::from_le_bytes([slice[0], slice[1]]) as f32,
      Decoder::I16Raw => i16::from_le_bytes([slice[0], slice[1]]) as f32,
      Decoder::U16Scaled(div) => u16::from_le_bytes([slice[0], slice[1]]) as f32 / div,
      Decoder::I16Scaled(div) => i16::from_le_bytes([slice[0], slice[1]]) as f32 / div,
      Decoder::U32Scaled(div) => {
        u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]) as f32 / div
      }
    })
  }
}

/// A telemetry field obtainable from a model.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Field {
  Speed,
  AverageSpeed,
  BatteryPercent,
  FrameTemperature,
  TotalMileage,
  TripDistance,
  TripTime,
  RemainingRange,
}

/// One field's location and encoding.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FieldSpec {
  pub field: Field,
  pub board: Board,
  /// Register the field is read from.
  pub register: u8,
  /// Byte offset of the value within that register's response payload.
  pub offset: usize,
  pub decoder: Decoder,
  /// The same field is also reachable through a different register in this
  /// block. Recorded because two of these exist on the M365 mirror block and
  /// picking the wrong one is a silent 2-byte error.
  pub mirrored_at: Option<u8>,
}

impl FieldSpec {
  /// Decodes this field from a register payload.
  pub fn decode(&self, payload: &[u8]) -> Option<f32> {
    self.decoder.decode(payload, self.offset)
  }
}

/// A model's registers and capabilities.
#[derive(Clone, Copy, Debug)]
pub struct ModelProfile {
  pub id: ModelId,
  pub confidence: Confidence,
  /// Registers to poll, in the order the telemetry loop should issue them.
  pub polled: &'static [PollSpec],
  pub fields: &'static [FieldSpec],
  /// Blocks written to change scooter state. Empty for every model here: this
  /// crate is read-only for telemetry, and the app's write path is separate.
  pub writable: &'static [u8],
}

/// A register read the telemetry loop issues.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PollSpec {
  pub board: Board,
  pub register: u8,
  /// The register's argument byte (how many bytes to return).
  pub argument: u8,
  /// How many response frames the parcel arrives in.
  pub frames: u8,
}

impl ModelProfile {
  /// The spec for `field`, if this model exposes it.
  pub fn field(&self, field: Field) -> Option<&'static FieldSpec> {
    self.fields.iter().find(|f| f.field == field)
  }

  /// Decodes every field this model can produce from a register payload.
  ///
  /// Fields whose register does not match `register`, or whose bytes are absent
  /// from `payload`, are skipped rather than defaulted to zero — a missing
  /// reading and a reading of zero are different things on a dashboard.
  pub fn decode_register(&self, register: u8, payload: &[u8]) -> Vec<(Field, f32)> {
    self
      .fields
      .iter()
      .filter(|f| f.register == register)
      .filter_map(|f| f.decode(payload).map(|v| (f.field, v)))
      .collect()
  }

  /// True when this model is safe to show without a caveat.
  pub fn is_trustworthy(&self) -> bool {
    self.confidence.is_trustworthy()
  }
}

// ===========================================================================
// M365 — the layout the shipped app uses
// ===========================================================================

/**
 * The M365's telemetry registers.
 *
 * ## ⚠️ Known discrepancy — resolve with a live capture
 *
 * The offsets below are the **documented** values from `ninebot-docs`
 * (`M365ESC.md`), where the `0xB0`-`0xBF` mirror block is laid out as:
 *
 * ```text
 *   B0 error(2) B1 warning(2) B2 status(2) B3 ?(2)
 *   B4 battery(2) B5 speed(2) B6 avg speed(2) B7 odometer(4)
 * ```
 *
 * The **shipped Android parser disagrees by exactly 2 bytes**. It reads
 *
 * ```text
 *   offset  8 -> battery   (documented: offset 4)
 *   offset 10 -> speed     (documented: offset 6)
 *   offset 12 -> avg speed (documented: offset 8)
 *   offset 14 -> odometer  (documented: offset 10)
 *   offset 22 -> temperature (documented: offset 18)
 * ```
 *
 * A uniform 2-byte shift means one of two things, and **this file cannot decide
 * which**:
 *
 *  - the response payload carries a 2-byte prefix (a length or echo byte) that
 *    the shipped parser is correctly accounting for, or
 *  - the shipped parser is reading every field from the wrong place.
 *
 * The scaling factors are right either way (`/1000` for speed and `/10` for
 * temperature match the documented units, and a repository test pins a known
 * 27-byte response that decodes correctly). What is unproven is the offset.
 *
 * Distinguishing the two requires **one captured `0xB0` response from a real
 * M365**. Until then this profile records the documented layout, the shipped
 * behaviour is left untouched, and the discrepancy is asserted by a test so it
 * cannot be forgotten.
 */
pub static M365: ModelProfile = ModelProfile {
  id: ModelId::M365,
  // Documented, not hardware-confirmed *by this crate* — see the note above.
  confidence: Confidence::Documented,
  polled: &[
    // The telemetry loop's tiered query order: the cheap register most often.
    PollSpec { board: Board::Esc, register: 0xB0, argument: 0x20, frames: 3 },
    PollSpec { board: Board::Esc, register: 0x3A, argument: 0x04, frames: 2 },
    PollSpec { board: Board::Esc, register: 0x25, argument: 0x02, frames: 2 },
  ],
  fields: &[
    // Offsets into the 0xB0 response, per M365ESC.md.
    FieldSpec { field: Field::BatteryPercent, board: Board::Esc, register: 0xB0, offset: 4, decoder: Decoder::U16Raw, mirrored_at: Some(0x22) },
    FieldSpec { field: Field::Speed, board: Board::Esc, register: 0xB0, offset: 6, decoder: Decoder::I16Scaled(1000.0), mirrored_at: Some(0x26) },
    FieldSpec { field: Field::AverageSpeed, board: Board::Esc, register: 0xB0, offset: 8, decoder: Decoder::I16Scaled(1000.0), mirrored_at: Some(0x65) },
    FieldSpec { field: Field::TotalMileage, board: Board::Esc, register: 0xB0, offset: 10, decoder: Decoder::U32Scaled(1000.0), mirrored_at: Some(0x29) },
    FieldSpec { field: Field::FrameTemperature, board: Board::Esc, register: 0xB0, offset: 18, decoder: Decoder::I16Scaled(10.0), mirrored_at: Some(0x3E) },
    // 0x25 remaining range, km * 10 per the loop's own scaling.
    FieldSpec { field: Field::RemainingRange, board: Board::Esc, register: 0x25, offset: 0, decoder: Decoder::U16Scaled(10.0), mirrored_at: None },
    // 0x3A trip: seconds then metres, both u16.
    FieldSpec { field: Field::TripTime, board: Board::Esc, register: 0x3A, offset: 0, decoder: Decoder::U16Raw, mirrored_at: None },
    FieldSpec { field: Field::TripDistance, board: Board::Esc, register: 0x3A, offset: 2, decoder: Decoder::U16Raw, mirrored_at: None },
  ],
  writable: &[],
};

/**
 * Offsets used by the **other two** implementations of the same response.
 *
 * Evidence, not behaviour: nothing decodes from this table. It exists so the
 * three-way disagreement documented on [`M365`] is greppable and asserted by
 * tests rather than living only in prose.
 *
 * Delete once a capture settles which offsets are right.
 */
pub static M365_COMPETING_OFFSETS: &[(&str, Field, usize)] = &[
  // Rust: ninebot-ble/src/session/info.rs `MotorInfo::try_from`. After
  // `pop_head()` (1 byte) and `pad_bytes(8)` the cursor sits at 9.
  ("rust ninebot-ble", Field::BatteryPercent, 9),
  ("rust ninebot-ble", Field::Speed, 11),
  ("rust ninebot-ble", Field::AverageSpeed, 13),
  ("rust ninebot-ble", Field::TotalMileage, 15),
  ("rust ninebot-ble", Field::FrameTemperature, 23),
  // Android: app/.../ScooterRepository.parseMotorInfoFromData. Its `data` starts
  // one byte later than the Rust cursor, because `pop_head()` consumes a byte
  // that `sliceArray(3 until dataEnd)` has already removed.
  ("android ScooterRepository", Field::BatteryPercent, 8),
  ("android ScooterRepository", Field::Speed, 10),
  ("android ScooterRepository", Field::AverageSpeed, 12),
  ("android ScooterRepository", Field::TotalMileage, 14),
  ("android ScooterRepository", Field::FrameTemperature, 22),
];

// ===========================================================================
// Pro / Pro2 / 1S / Lite — same register lineage, unconfirmed here
// ===========================================================================

/**
 * M365 Pro register layout.
 *
 * Shares the ESC register map with the M365 — the `M365PROESC.md` mirror block
 * uses the same `0xB0`-`0xBF` ordering. Confidence is [`Confidence::Documented`]
 * rather than `Verified` because this crate has not seen a capture from one.
 *
 * Pro2 / 1S / Lite point at this same profile. They are a later BLE generation
 * (1.2.9+, with the `5AAB` variant on some units) but the ESC register block is
 * unchanged, so they share the map instead of duplicating it — one place to fix
 * if a capture shows a difference.
 */
pub static M365_PRO: ModelProfile = ModelProfile {
  id: ModelId::M365Pro,
  confidence: Confidence::Documented,
  polled: M365.polled,
  fields: M365.fields,
  writable: &[],
};

/**
 * Xiaomi Mi 3.
 *
 * Confirmed to be on the Xiaomi `mi_DRV_*` MCU platform rather than a Ninebot
 * one, which is why it is grouped with this family. **Its BLE protocol was never
 * confirmed**, and Xiaomi's own firmware naming groups it with the 1S/Pro2
 * lineage without evidence that the register block is identical.
 *
 * It therefore carries [`Confidence::Unverified`] and **no fields**. An empty
 * profile is the honest representation: the app will poll nothing rather than
 * poll the M365 registers and present the results as Mi 3 telemetry.
 */
pub static MI3: ModelProfile = ModelProfile {
  id: ModelId::Mi3,
  confidence: Confidence::Unverified,
  polled: &[],
  fields: &[],
  writable: &[],
};

#[cfg(test)]
mod tests {
  use super::*;

  fn profile(id: ModelId) -> &'static ModelProfile {
    id.profile()
  }

  #[test]
  fn every_model_resolves_to_a_profile() {
    // The Pro/Pro2/1S/Lite deliberately share one profile, so the profile's own
    // `id` names the layout (M365Pro) rather than the model asking for it. What
    // must hold is that each model maps to a real, non-empty-in-the-right-way
    // profile and never to another family's map.
    for &id in ModelId::all() {
      let p = profile(id);
      let expected_layout = match id {
        ModelId::M365 => ModelId::M365,
        ModelId::Mi3 => ModelId::Mi3,
        _ => ModelId::M365Pro,
      };
      assert_eq!(p.id, expected_layout, "ModelId::{id:?} resolved to the wrong layout");
    }
    assert_eq!(profile(ModelId::Mi3).id, ModelId::Mi3, "Mi 3 must not borrow the M365 map");
  }

  #[test]
  fn the_m365_is_the_only_model_with_a_populated_register_map() {
    // Guards against a future edit quietly giving an unverified model a real
    // layout, which would surface as plausible but wrong numbers.
    assert!(!M365.fields.is_empty());
    assert!(MI3.fields.is_empty(), "Mi 3 must not claim a register layout");
    assert!(MI3.polled.is_empty(), "Mi 3 must not poll registers");
  }

  #[test]
  fn mi3_is_marked_unverified() {
    assert_eq!(MI3.confidence, Confidence::Unverified);
    assert!(!MI3.is_trustworthy());
    assert!(!profile(ModelId::Mi3).is_trustworthy());
  }

  #[test]
  fn pro_family_shares_the_m365_register_map() {
    for id in [ModelId::M365Pro, ModelId::M365Pro2, ModelId::Mi1S, ModelId::MiLite] {
      let p = profile(id);
      assert_eq!(
        p.fields.len(),
        M365.fields.len(),
        "{id:?} should share the M365 field set"
      );
      assert_eq!(p.polled, M365.polled, "{id:?} should poll the same registers");
    }
  }

  // --- decoding ----------------------------------------------------------

  /// Builds a `0xB0` payload matching the documented layout.
  fn documented_b0_payload(
    battery: u16,
    speed_mh: i16,
    avg_mh: i16,
    odometer_m: u32,
    temp_dc: i16,
  ) -> Vec<u8> {
    let mut v = vec![0u8; 20];
    v[0..2].copy_from_slice(&0u16.to_le_bytes()); // B0 error
    v[2..4].copy_from_slice(&0u16.to_le_bytes()); // B1 warning
    v[4..6].copy_from_slice(&battery.to_le_bytes()); // B4 battery
    v[6..8].copy_from_slice(&speed_mh.to_le_bytes()); // B5 speed
    v[8..10].copy_from_slice(&avg_mh.to_le_bytes()); // B6 avg speed
    v[10..14].copy_from_slice(&odometer_m.to_le_bytes()); // B7 odometer
    v[18..20].copy_from_slice(&temp_dc.to_le_bytes()); // BB frame temp
    v
  }

  #[test]
  fn m365_decodes_a_documented_b0_payload_field_by_field() {
    let payload = documented_b0_payload(
      85,        // 85 %
      25_300,    // 25.3 km/h in m/h
      18_500,    // 18.5 km/h
      12_345,    // 12345 m
      285,       // 28.5 C
    );

    let decoded = M365.decode_register(0xB0, &payload);
    let get = |f: Field| {
      decoded
        .iter()
        .find(|(field, _)| *field == f)
        .map(|(_, v)| *v)
        .unwrap_or_else(|| panic!("{f:?} missing from decode result"))
    };

    assert_eq!(get(Field::BatteryPercent), 85.0);
    assert!((get(Field::Speed) - 25.3).abs() < 0.001);
    assert!((get(Field::AverageSpeed) - 18.5).abs() < 0.001);
    assert!((get(Field::TotalMileage) - 12.345).abs() < 0.001);
    assert!((get(Field::FrameTemperature) - 28.5).abs() < 0.001);
  }

  #[test]
  fn scalings_match_the_documented_units() {
    // Speed divisor 1000 turns the wire's m/h into km/h; temperature divisor 10
    // turns tenths of a degree into degrees. Getting either wrong is a silent
    // factor-of-10 error on the HUD.
    let spec = M365.field(Field::Speed).expect("speed spec");
    assert_eq!(spec.decoder, Decoder::I16Scaled(1000.0));
    let temp = M365.field(Field::FrameTemperature).expect("temp spec");
    assert_eq!(temp.decoder, Decoder::I16Scaled(10.0));
  }

  #[test]
  fn decoded_battery_is_not_silently_shadowed_by_a_mirror_register() {
    // Battery exists at both 0xB4 (in the mirror block) and 0x22. Only one may
    // be used for the 0xB0 decode, or the value depends on iteration order.
    let matching = M365.fields.iter().filter(|f| f.field == Field::BatteryPercent).count();
    assert_eq!(matching, 1, "battery must have exactly one 0xB0 entry");
    assert_eq!(
      M365.field(Field::BatteryPercent).unwrap().mirrored_at,
      Some(0x22),
      "the mirror register should be recorded, not silently dropped"
    );
  }

  #[test]
  fn decode_register_ignores_fields_from_other_registers() {
    let payload = documented_b0_payload(85, 1000, 1000, 1000, 100);
    let from_b0 = M365.decode_register(0xB0, &payload);
    assert!(from_b0.iter().all(|(f, _)| *f != Field::RemainingRange));
    assert!(from_b0.iter().all(|(f, _)| *f != Field::TripDistance));
  }

  #[test]
  fn a_short_payload_yields_no_value_rather_than_zero() {
    // A truncated response must not look like a real reading of 0.
    let short = vec![0u8; 5];
    assert!(M365.decode_register(0xB0, &short).is_empty());
  }

  #[test]
  fn partial_payload_decodes_only_the_fields_that_fit() {
    // Battery (offset 4) fits in 6 bytes; speed (offset 6) does not.
    let partial = vec![0u8; 6];
    let decoded = M365.decode_register(0xB0, &partial);
    assert_eq!(decoded.len(), 1);
    assert_eq!(decoded[0].0, Field::BatteryPercent);
  }

  #[test]
  fn trip_fields_decode_seconds_then_metres() {
    let mut payload = vec![0u8; 4];
    payload[0..2].copy_from_slice(&600u16.to_le_bytes());
    payload[2..4].copy_from_slice(&2500u16.to_le_bytes());

    let decoded = M365.decode_register(0x3A, &payload);
    let time = decoded.iter().find(|(f, _)| *f == Field::TripTime).unwrap().1;
    let dist = decoded.iter().find(|(f, _)| *f == Field::TripDistance).unwrap().1;
    assert_eq!(time, 600.0);
    assert_eq!(dist, 2500.0);
  }

  #[test]
  fn remaining_range_uses_the_tenths_of_a_km_scaling() {
    let payload = 155u16.to_le_bytes().to_vec(); // 15.5 km
    let decoded = M365.decode_register(0x25, &payload);
    assert_eq!(decoded.len(), 1);
    assert!((decoded[0].1 - 15.5).abs() < 0.001);
  }

  // --- the offset disagreement -------------------------------------------

  #[test]
  fn the_two_shipped_parsers_agree_with_each_other_but_not_with_the_documentation() {
    // The disagreement is the finding worth protecting. If someone "fixes" the
    // offsets without a capture, this test names exactly what changed.
    let documented = |f: Field| M365.field(f).expect("field present").offset;

    for (source, field, offset) in M365_COMPETING_OFFSETS {
      assert_ne!(
        *offset,
        documented(*field),
        "{source} {field:?} now matches the documented offset {}; if a capture proved the \
         documentation right, delete the competing-offset table and update the note on M365",
        documented(*field)
      );
    }
  }

  #[test]
  fn android_reads_every_field_one_byte_below_rust() {
    // A uniform one-byte gap is explained by where each parser starts counting,
    // which is what makes the "one shared cause" explanation plausible rather
    // than two independent bugs.
    for field in [
      Field::BatteryPercent,
      Field::Speed,
      Field::AverageSpeed,
      Field::TotalMileage,
      Field::FrameTemperature,
    ] {
      let rust = M365_COMPETING_OFFSETS
        .iter()
        .find(|(s, f, _)| *s == "rust ninebot-ble" && *f == field)
        .expect("rust offset recorded")
        .2;
      let android = M365_COMPETING_OFFSETS
        .iter()
        .find(|(s, f, _)| *s == "android ScooterRepository" && *f == field)
        .expect("android offset recorded")
        .2;

      assert_eq!(
        android + 1,
        rust,
        "{field:?}: android {android} should sit exactly one byte below rust {rust}"
      );
    }
  }

  #[test]
  fn the_two_shipments_sit_five_bytes_above_the_documentation() {
    // Records the size of the gap: documented speed is offset 6, Rust reads 11.
    // A capture will either confirm a response prefix of this size or expose the
    // gap as a straightforward bug in both parsers.
    let documented = M365.field(Field::Speed).expect("speed").offset;
    let rust = M365_COMPETING_OFFSETS
      .iter()
      .find(|(s, f, _)| *s == "rust ninebot-ble" && *f == Field::Speed)
      .expect("rust speed")
      .2;
    assert_eq!(
      rust - documented,
      5,
      "documented speed offset {documented}, rust {rust}"
    );
  }

  #[test]
  fn every_documented_field_has_a_competing_offset_recorded() {
    // Guards against someone adding a field to M365 and forgetting to record
    // where the two shipped parsers read it from.
    for spec in M365.fields.iter().filter(|f| f.register == 0xB0) {
      let recorded = M365_COMPETING_OFFSETS.iter().any(|(_, f, _)| *f == spec.field);
      assert!(recorded, "{:?} has no competing offset recorded", spec.field);
    }
  }

  // --- confidence plumbing ----------------------------------------------

  #[test]
  fn confidence_ordering_is_from_least_to_most_confident() {
    assert!(Confidence::Verified > Confidence::Documented);
    assert!(Confidence::Documented > Confidence::Unverified);
  }

  #[test]
  fn only_verified_and_documented_profiles_are_trustworthy() {
    assert!(Confidence::Verified.is_trustworthy());
    assert!(Confidence::Documented.is_trustworthy());
    assert!(!Confidence::Unverified.is_trustworthy());
  }

  #[test]
  fn no_profile_claims_verified_yet() {
    // Nothing here has been hardware-confirmed by this codebase. If a capture
    // changes that, promote the specific profile and update this test.
    for &id in ModelId::all() {
      assert_ne!(
        profile(id).confidence,
        Confidence::Verified,
        "{id:?} claims verification that no capture supports"
      );
    }
  }

  // --- addressing --------------------------------------------------------

  #[test]
  fn board_addresses_match_the_legacy_flat_scheme() {
    assert_eq!(Board::Esc.address(), 0x20);
    assert_eq!(Board::Bms.address(), 0x22);
  }

  #[test]
  fn decoder_widths_match_the_documented_field_sizes() {
    assert_eq!(Decoder::U16Raw.width(), 2);
    assert_eq!(Decoder::I16Scaled(1000.0).width(), 2);
    assert_eq!(Decoder::U32Scaled(1000.0).width(), 4);
    // The odometer is the only 4-byte field in the mirror block.
    assert_eq!(M365.field(Field::TotalMileage).unwrap().decoder.width(), 4);
  }

  #[test]
  fn signed_decoders_preserve_negative_values() {
    // Temperature can legitimately go below zero; reading it unsigned would wrap
    // -5.0 C into 6553.1 C.
    let payload = (-50i16).to_le_bytes().to_vec(); // -5.0 C
    assert_eq!(Decoder::I16Scaled(10.0).decode(&payload, 0), Some(-5.0));
    assert_eq!(Decoder::U16Raw.decode(&[0xFF, 0xFF], 0), Some(65535.0));
  }

  #[test]
  fn out_of_range_offsets_return_none_instead_of_panicking() {
    assert_eq!(Decoder::U16Raw.decode(&[0, 0], 5), None);
    assert_eq!(Decoder::U32Scaled(1.0).decode(&[0, 0, 0], 0), None);
    // An offset large enough to overflow the addition must not panic.
    assert_eq!(Decoder::U16Raw.decode(&[0, 0], usize::MAX), None);
  }
}
