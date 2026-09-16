//! Scan-time identity rules.
//!
//! Pure: no Bluetooth-stack dependency, so these run on any host. That matters
//! because the rule they encode is an identity claim — if it is wrong, the
//! scanner silently matches nothing and the symptom looks like a scooter that is
//! switched off.
//!
//! # Single source of truth
//!
//! The name prefix and service UUID below previously appeared independently in
//! the Rust scanner, in the Android UI layer, and in prose in `README.md` and
//! `doc/BLE_PROTOCOL_GUIDE.md`. Three copies of an identity rule means two of
//! them are eventually wrong, and the failure is silent.
//!
//! The documentation now **points here** rather than restating the values, and
//! [`tests::identity_spec_is_the_documented_one`] fails if either changes — which
//! is the prompt to re-read the prose that references it.

/// A scan-time identity rule.
///
/// [`name_prefix`](Self::name_prefix) and [`service_uuid`](Self::service_uuid)
/// are alternatives, not a conjunction: an advertisement matching **either** is
/// a match, because plenty of devices advertise the service without a name and
/// vice versa.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ScooterIdentity {
  /// Leading fragment of the advertised local name.
  pub name_prefix: &'static str,
  /// Service UUID present in the advertisement or service list.
  pub service_uuid: &'static str,
}

impl ScooterIdentity {
  /// True when `name` or any of `service_uuids` matches this rule.
  ///
  /// UUID comparison is case-insensitive: the same UUID arrives in upper or
  /// lower case depending on the platform stack and the transport.
  pub fn matches(&self, name: Option<&str>, service_uuids: &[String]) -> bool {
    if let Some(name) = name {
      if name.starts_with(self.name_prefix) {
        return true;
      }
    }
    service_uuids
      .iter()
      .any(|u| u.eq_ignore_ascii_case(self.service_uuid))
  }
}

/// How a Xiaomi-lineage scooter is recognised during a scan.
///
/// # What this can and cannot tell you
///
/// It identifies the **Xiaomi lineage** — not a specific model, and not a
/// protocol dialect:
///
/// - The `MIScooter` prefix covers M365, Pro, Pro2, 1S, Lite and Mi 3 alike.
/// - The `fe95` service is shared by the whole family.
/// - **A service list is not a protocol discriminator** in general. Xiaomi,
///   Ninebot and current Segway models all expose the same Nordic UART service,
///   and some answer on only one of the services they advertise.
///
/// Deciding which dialect a connected device actually speaks requires probing
/// it. See `doc/PROTOCOL_FAMILIES.md`.
pub const XIAOMI_SCOOTER_MATCH: ScooterIdentity = ScooterIdentity {
  name_prefix: "MIScooter",
  service_uuid: "0000fe95-0000-1000-8000-00805f9b34fb",
};

#[cfg(test)]
mod tests {
  use super::*;

  const FE95: &str = "0000fe95-0000-1000-8000-00805f9b34fb";

  /// Pins the documented identity values.
  ///
  /// If this fails, the scan rule changed — and `README.md` and
  /// `doc/BLE_PROTOCOL_GUIDE.md` reference this constant rather than restating
  /// it, so they need re-reading before the change is committed.
  #[test]
  fn identity_spec_is_the_documented_one() {
    assert_eq!(XIAOMI_SCOOTER_MATCH.name_prefix, "MIScooter");
    assert_eq!(XIAOMI_SCOOTER_MATCH.service_uuid, FE95);
  }

  #[test]
  fn the_name_prefix_matches() {
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(m.matches(Some("MIScooter7353"), &[]));
    assert!(
      m.matches(Some("MIScooter"), &[]),
      "the bare prefix with no suffix must match"
    );
  }

  #[test]
  fn an_unrelated_name_does_not_match() {
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(!m.matches(Some("JBL Flip 5"), &[]));
    assert!(!m.matches(Some("Mi Band"), &[]));
  }

  #[test]
  fn the_service_uuid_matches_without_a_name() {
    // Plenty of devices advertise the service and no name; requiring both would
    // miss them entirely.
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(m.matches(None, &[FE95.to_string()]));
  }

  #[test]
  fn the_uuid_match_is_case_insensitive() {
    // The same UUID arrives in either case depending on the platform stack.
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(m.matches(None, &[FE95.to_uppercase()]));
    assert!(m.matches(None, &[FE95.to_lowercase()]));
  }

  #[test]
  fn an_unrelated_service_does_not_match() {
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(!m.matches(None, &["0000180d-0000-1000-8000-00805f9b34fb".to_string()]));
  }

  #[test]
  fn an_empty_advertisement_matches_nothing() {
    // Guards the escape hatch: a nameless, serviceless advertisement must not be
    // claimed as a scooter, or the device list fills with noise.
    assert!(!XIAOMI_SCOOTER_MATCH.matches(None, &[]));
    assert!(!XIAOMI_SCOOTER_MATCH.matches(Some(""), &[]));
  }

  #[test]
  fn matching_is_by_either_signal_not_both() {
    // Name-only and service-only must each succeed independently; a conjunction
    // would halve the devices the scanner can see.
    let m = XIAOMI_SCOOTER_MATCH;
    assert!(m.matches(Some("MIScooter1"), &[]), "name alone must match");
    assert!(m.matches(None, &[FE95.to_string()]), "service alone must match");
  }

  #[test]
  fn a_match_does_not_claim_a_model_or_a_dialect() {
    // The rule carries exactly two fields by construction. This test exists to
    // make adding a third a deliberate act: a scan genuinely cannot establish a
    // model or a protocol, and a field implying otherwise would invite callers
    // to treat a hint as a fact.
    let m = XIAOMI_SCOOTER_MATCH;
    assert_eq!(m.name_prefix, "MIScooter");
    assert_eq!(m.service_uuid, FE95);
    // If a `model` or `protocol` field is ever added, this comment and the type's
    // documentation must change together.
  }
}
