//! Re-export of the one and only crypto implementation.
//!
//! This crate used to carry its own copy of `mi_crypto.rs`, duplicated from
//! ninebot-ble. The copies were byte-identical apart from commented-out tracing
//! calls, which is exactly the kind of drift that is invisible until it is a
//! security problem: the ECDH / HKDF / AES-CCM code would get a fix in one
//! crate and silently keep the old behaviour in the other, and the Android app
//! links the *ffi* copy while the tests exercise the *ninebot-ble* copy.
//!
//! The implementation now lives in `ninebot-ble` and is re-exported here, so a
//! fix cannot land in one place only. `ninebot-ffi` depends on that crate with
//! `default-features = false` so this does not drag in btleplug.
//!
//! `mod mi_crypto;` in `lib.rs` is private and every call site uses the
//! `mi_crypto::…` path, so re-exporting keeps the internal API unchanged.

pub use ninebot_ble::mi_crypto::*;
