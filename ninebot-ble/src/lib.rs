extern crate uuid;

// ===========================================================================
// Protocol core
// ===========================================================================
//
// Always compiled. None of these modules touch a Bluetooth stack, so they can
// be unit-tested on any host with `cargo test --no-default-features` — which
// matters because btleplug's Linux backend needs `pkg-config` + `libdbus-1-dev`
// and will not build on a bare runner or a locked-down sandbox.

pub mod mi_crypto;

/// Scan-time identity rules. Pure, so the rule that decides whether a device is
/// a scooter at all is testable without a radio.
pub mod identity;

/// ATT MTU arithmetic. Shared by every writer so the `ATT_MTU - 3` rule exists
/// in exactly one place — an oversized write is discarded *silently* by the
/// peer, so a second copy of this arithmetic is a second silent failure mode.
pub mod mtu;

/// The seam the protocol talks through. Free of Bluetooth-stack types so the
/// framing logic is testable on a host with no radio.
pub mod transport;

/// Ninebot legacy stream cipher ("NinebotCrypto").
///
/// ⚠️ **Unverified against hardware.** See the module docs and
/// `doc/NINEBOT_LEGACY_PROTOCOL.md` before wiring this into a connection path.
pub mod ninebot_legacy;

/// Current-generation vehicles: Encryption2 identification and cipher.
///
/// Implements the handshake cryptography and protocol identification. Telemetry
/// is **deliberately not implemented** — see the module docs for why guessing
/// register addresses is worse than showing nothing.
pub mod encryption2;

/// Per-model register maps. Adding a scooter is a data entry here rather than
/// new protocol code, and each profile declares how far it can be trusted.
pub mod model;

/// Command framing: builds the `[len][D][T][attr][payload]` UART frames and
/// knows every register address the protocol uses.
pub mod session;

pub use mi_crypto::AuthToken;
pub use mtu::{chunk_count, chunk_size_for, fragment, ATT_OVERHEAD, DEFAULT_ATT_MTU, DEFAULT_CHUNK_SIZE};
pub use transport::{MockTransport, Notification, Transport};
pub use identity::{ScooterIdentity, XIAOMI_SCOOTER_MATCH};
pub use model::{Confidence, Field, ModelId, ModelProfile};

// ===========================================================================
// Transport (`ble` feature)
// ===========================================================================
//
// Everything below drives a real radio through btleplug. It is gated so the
// protocol core above stays testable without it.
//
// Multi-model note: model detection CANNOT live in this layer. Xiaomi, Ninebot
// and even current Segway models all advertise the same Nordic UART service,
// and some answer on only one of the services they advertise, so a scan-time
// lookup table cannot identify a protocol. Detection has to probe the live
// device and cache the result per MAC — see doc/PROTOCOL_FAMILIES.md §1.

#[cfg(feature = "ble")]
pub mod protocol;
pub mod profile;
pub mod ninebot_crypto;
pub mod pairing;
#[cfg(feature = "ble")]
pub mod consts;
#[cfg(feature = "ble")]
pub mod login;
#[cfg(feature = "ble")]
pub mod scanner;
#[cfg(feature = "ble")]
pub mod register;
#[cfg(feature = "ble")]
pub mod connection;

#[cfg(feature = "ble")]
pub use scanner::{ScooterScanner, ScannerEvent};
#[cfg(feature = "ble")]
pub use register::{RegistrationRequest, RegistrationError};
#[cfg(feature = "ble")]
pub use login::LoginRequest;
#[cfg(feature = "ble")]
pub use connection::ConnectionHelper;

// NOTE: this crate previously also declared `pub mod clone_connection;` and
// `pub mod android_api;`, plus a `Java_com_rokid_m365hud_BleManager_*` JNI
// surface built on top of them. Neither module file exists in the repository
// (they are not tracked in git either), so the crate did not compile at all.
//
// The JNI surface was also dead: nothing in the Android app or the glass-hud
// module binds to `com.rokid.m365hud.BleManager` — the app talks to native code
// through the separate `ninebot-ffi` crate
// (`com.m365bleapp.ffi.M365Native`). On top of being unbuildable it
// authenticated with a hard-coded all-zero `AuthToken`, held a `std::sync`
// mutex guard across `.await` points, panicked on `properties().unwrap()`
// inside a background scan loop, and had a `nativeStopScan` that could not
// actually stop the scan it started.
//
// It has been removed rather than resurrected. If a JNI surface is needed here
// again, build it on `ninebot-ffi`, take the auth token from the caller, and
// use `tokio::sync::Mutex` for state that is held across await points.

pub mod vehicle;
