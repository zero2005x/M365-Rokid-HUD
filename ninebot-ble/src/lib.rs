extern crate uuid;

// 宣告模組
pub mod mi_crypto;
pub mod protocol;
pub mod consts;
pub mod login;
pub mod scanner;
pub mod session;
pub mod register;
pub mod connection;

// 引用
pub use scanner::{ScooterScanner, ScannerEvent};

pub use mi_crypto::AuthToken;
pub use register::{RegistrationRequest, RegistrationError};
pub use login::LoginRequest;
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
