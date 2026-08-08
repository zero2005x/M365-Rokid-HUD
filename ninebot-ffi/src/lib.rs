use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jbyteArray, jlong};
mod mi_crypto;
use elliptic_curve::sec1::ToEncodedPoint;
use p256::ecdh::EphemeralSecret;

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

// State has to survive across JNI calls. Handing raw `Box::into_raw` pointers
// to Java is unsound: Java can hand the same value back twice (double free),
// keep using it after `freeSession` (use-after-free), or call `encrypt` on one
// thread while another frees the same pointer (data race).
//
// Instead we keep the state in a process-wide registry and hand Java an opaque,
// monotonically increasing handle. A stale or forged handle simply misses in
// the map and is reported as a failure, and freeing is idempotent by
// construction.

struct KeyExchangeState {
    secret: EphemeralSecret,
}

struct SessionState {
    keys: mi_crypto::LoginKeychain,
}

/// Handles start at 1 so that 0 stays reserved for "invalid handle".
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn next_handle() -> i64 {
    NEXT_HANDLE.fetch_add(1, Ordering::Relaxed)
}

fn handshakes() -> &'static Mutex<HashMap<i64, KeyExchangeState>> {
    static HANDSHAKES: OnceLock<Mutex<HashMap<i64, KeyExchangeState>>> = OnceLock::new();
    HANDSHAKES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn sessions() -> &'static Mutex<HashMap<i64, Arc<SessionState>>> {
    static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<SessionState>>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Looks up a session and clones the `Arc` out of the registry.
///
/// The registry lock is released before the caller does any crypto, and the
/// cloned `Arc` keeps the session alive even if another thread frees the handle
/// concurrently, so an in-flight `encrypt`/`decrypt` can never observe freed
/// state.
fn session_for(handle: jlong) -> Option<Arc<SessionState>> {
    if handle == 0 {
        return None;
    }
    sessions().lock().ok()?.get(&handle).cloned()
}

/// Every failure path returns an empty array, which is the failure signal the
/// Kotlin bindings already expect (see `M365Native.kt`).
fn empty(env: &JNIEnv) -> jbyteArray {
    env.byte_array_from_slice(&[])
        .unwrap_or_else(|_| std::ptr::null_mut())
}

fn to_java(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(data)
        .unwrap_or_else(|_| std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_init(
    _env: JNIEnv,
    _class: JClass,
) {
    // optional logging init
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_prepareHandshake(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    // Wrap entire function in catch_unwind for FFI safety
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (secret, public) = mi_crypto::gen_key_pair();

        let pk_bytes = public.to_encoded_point(false).as_bytes().to_vec();

        let handle = next_handle();
        // Register only after the public key has been produced, so a failure
        // above cannot leave an orphaned entry behind.
        handshakes()
            .lock()
            .map_err(|_| "handshake registry poisoned")?
            .insert(handle, KeyExchangeState { secret });

        let mut result = Vec::with_capacity(8 + pk_bytes.len());
        result.extend_from_slice(&handle.to_be_bytes());
        result.extend_from_slice(&pk_bytes);

        Ok::<Vec<u8>, &str>(result)
    }));

    match result {
        Ok(Ok(data)) => to_java(&env, &data),
        _ => empty(&env),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_processHandshake(
    env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
    remote_key: jbyteArray,
    remote_info: jbyteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // Removing the entry makes this call one-shot: a retry with the same
        // handle misses the registry and fails cleanly instead of reusing
        // freed state.
        let state = handshakes()
            .lock()
            .map_err(|_| "handshake registry poisoned")?
            .remove(&ctx_ptr)
            .ok_or("unknown or already-consumed handshake handle")?;

        let remote_key_vec = env
            .convert_byte_array(remote_key)
            .map_err(|_| "remote_key conversion failed")?;
        let remote_info_vec = env
            .convert_byte_array(remote_info)
            .map_err(|_| "remote_info conversion failed")?;

        let (did_ct, token) =
            mi_crypto::calc_did(&state.secret, &remote_key_vec, &remote_info_vec)
                .map_err(|_| "handshake calculation failed")?;

        // Return format: [12 bytes Token][Rest DID Ciphertext]
        let mut output = Vec::with_capacity(token.len() + did_ct.len());
        output.extend_from_slice(&token);
        output.extend_from_slice(&did_ct);

        Ok::<Vec<u8>, &str>(output)
    }));

    match result {
        Ok(Ok(data)) => to_java(&env, &data),
        _ => empty(&env),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_login(
     env: JNIEnv,
     _class: JClass,
     token: jbyteArray,
     rand_key: jbyteArray,
     remote_key: jbyteArray,
     _remote_info: jbyteArray,
) -> jbyteArray { // Returns [8 bytes Handle][Login Data...]
    // Wrap in catch_unwind for FFI safety
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token_vec = env.convert_byte_array(token).map_err(|_| "token conversion failed")?;
        let mut rand_key_vec = env.convert_byte_array(rand_key).map_err(|_| "rand_key conversion failed")?;
        let mut remote_key_vec = env.convert_byte_array(remote_key).map_err(|_| "remote_key conversion failed")?;

        if token_vec.len() != 12 { return Err("token length invalid"); }

        let mut token_arr = [0u8; 12];
        token_arr.copy_from_slice(&token_vec);

        let (info, _, keys) = mi_crypto::calc_login_did(
            &mut rand_key_vec,
            &mut remote_key_vec,
            &token_arr
        );

        let handle = next_handle();
        sessions()
            .lock()
            .map_err(|_| "session registry poisoned")?
            .insert(handle, Arc::new(SessionState { keys }));

        let mut result = Vec::with_capacity(8 + info.len());
        result.extend_from_slice(&handle.to_be_bytes());
        result.extend_from_slice(&info);

        Ok::<Vec<u8>, &str>(result)
    }));

    match result {
        Ok(Ok(data)) => to_java(&env, &data),
        _ => empty(&env),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_encrypt(
     env: JNIEnv,
     _class: JClass,
     session_ptr: jlong,
     payload: jbyteArray,
     counter: jlong,
) -> jbyteArray {
     let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
         let session = session_for(session_ptr).ok_or("unknown session handle")?;

         // `counter` crosses the boundary as a jlong. Truncating it with `as
         // u32` would turn a large or negative value into a counter that has
         // already been used, reusing an AES-CCM nonce.
         let counter = u32::try_from(counter).map_err(|_| "counter out of range")?;

         let payload_vec = env.convert_byte_array(payload).map_err(|_| "payload conversion failed")?;

         mi_crypto::encrypt_uart(&session.keys.app, &payload_vec, counter, None)
             .map_err(|_| "encryption failed")
     }));

     match result {
         Ok(Ok(data)) => to_java(&env, &data),
         _ => empty(&env),
     }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_decrypt(
     env: JNIEnv,
     _class: JClass,
     session_ptr: jlong,
     encrypted: jbyteArray,
) -> jbyteArray {
     let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
         let session = session_for(session_ptr).ok_or("unknown session handle")?;

         let encrypted_vec = env.convert_byte_array(encrypted).map_err(|_| "encrypted conversion failed")?;

         mi_crypto::decrypt_uart(&session.keys.dev, &encrypted_vec)
             .map_err(|_| "decryption failed")
     }));

     match result {
         Ok(Ok(data)) => to_java(&env, &data),
         _ => empty(&env),
     }
}

/// Releases a session handle.
///
/// Idempotent: freeing an unknown or already-freed handle is a no-op, and any
/// `encrypt`/`decrypt` still in flight keeps its own `Arc` alive until it
/// finishes.
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_freeSession(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }
    if let Ok(mut sessions) = sessions().lock() {
        sessions.remove(&ptr);
    }
}
