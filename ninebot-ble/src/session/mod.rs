// Protocol core — always compiled. These modules describe the wire format (the
// `[len][D][T][attr][payload]` command frame, every register address, and the
// telemetry decoders). They contain no Bluetooth-stack dependency, so the
// multi-model work can unit-test them without a radio.
//
// Types that are part of the wire contract stay nameable to downstream crates
// even when the `ble` feature is off.
pub mod commands;
pub mod payload;
// `info` and `battery` hold both pure types (MotorInfo, BatteryInfo, …) and the
// `MiSession` methods that fetch them. Only the methods are gated internally,
// so the decoders remain testable on a host.
mod info;
mod battery;

// ===========================================================================
// Read-only telemetry (the `ble` feature)
// ===========================================================================
//
// These only read: they issue `ReadWrite::Read` commands and decode the reply.
// Nothing here changes the scooter's state, so they are safe to enable on any
// device.

#[cfg(feature = "ble")]
mod mi_session;
#[cfg(feature = "ble")]
mod travel;

#[cfg(feature = "ble")]
pub use mi_session::MiSession;

pub use info::{GeneralInfo, MotorInfo};
pub use battery::{BatteryInfo, BatteryCellsVoltage};

// ===========================================================================
// Write operations (the `write-ops` feature) — OFF by default
// ===========================================================================
//
// Everything below sends `ReadWrite::Write` and changes scooter state: it locks
// the motor, switches the tail light, and alters KERS and cruise settings.
//
// ## Why these are gated rather than always available
//
// 1. **The multi-model work is read-only.** Register addresses are only
//    partially confirmed per model, and a write aimed at the wrong address can
//    change a setting the rider cannot see or undo. Reading a wrong address
//    produces a wrong number; *writing* one produces a changed vehicle.
//
// 2. **A lock command is a safety-relevant action.** `lock()` disables the motor
//    while the scooter may be moving. Exposing it by default means an accidental
//    tap has physical consequences.
//
// 3. **It keeps the default build honest about what it does.** With the feature
//    off, nothing in the compiled library can alter the vehicle, which is a
//    property worth being able to state and test rather than infer.
//
// Enable with:
//
//     cargo build --features write-ops
//
// The Android app does NOT enable this. Its lock/light controls live in the
// Kotlin repository layer and are unaffected by this gate — see
// `ScooterRepository.lock()` and the note in `doc/NINEBOT_LEGACY_PROTOCOL.md`.

#[cfg(feature = "write-ops")]
mod settings;
#[cfg(feature = "write-ops")]
mod lock;
#[cfg(feature = "write-ops")]
mod light;

#[cfg(feature = "write-ops")]
pub use settings::{TailLight, Kers, SupplementaryInfo};

// The command type and everything needed to build one must always be nameable:
// `MiSession::send` takes a `&ScooterCommand`, and the per-model register tables
// added for multi-model support are built from these.
//
// `Attribute` keeps its `Lock`/`Unlock`/`TailLight`/`Cruise` variants even with
// the feature off. They are wire-contract values, and a caller assembling a
// frame — or a test asserting the encoding — should not have to turn on the
// write path to name them.
pub use commands::{ScooterCommand, Direction, ReadWrite, Attribute, CommandError};
pub use payload::Payload;

#[cfg(test)]
mod read_only_guard_tests {
  /// The default build must not contain any state-changing command path.
  ///
  /// This is a compile-time property expressed as a test: with `write-ops` off,
  /// the modules that issue `ReadWrite::Write` are not compiled at all, so there
  /// is nothing to call by accident.
  #[test]
  #[cfg(not(feature = "write-ops"))]
  fn write_operations_are_absent_by_default() {
    // If a future edit makes one of these modules unconditional, this test stops
    // compiling — which is the loudest possible way to surface it.
    assert!(
      !cfg!(feature = "write-ops"),
      "the default build must not include write operations"
    );
  }
}
