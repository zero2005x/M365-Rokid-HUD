#[path = "../../ninebot-ble/src/ninebot_crypto.rs"]
pub mod ninebot_crypto;
#[path = "../../ninebot-ble/src/pairing.rs"]
pub mod pairing;
// 與 BLE crate 共用同一份純資料核心，避免帶入平台 BLE 相依套件。
#[path = "../../ninebot-ble/src/profile.rs"]
pub mod profile;
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

/// 固定版本的描述格式：[版本、筆數、每筆車型／驗證狀態／加密策略／能力位元]。
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_availableProfiles(
    env: JNIEnv, _class: JClass,
) -> jbyteArray {
    let result = std::panic::catch_unwind(|| {
        let profiles = profile::ProfileRegistry::available();
        let mut bytes = vec![1, profiles.len() as u8];
        for p in profiles {
            let verified = u8::from(p.verification_status() == profile::VerificationStatus::Verified);
            let crypto = match p.crypto_strategy() {
                profile::CryptoStrategy::None => 0,
                profile::CryptoStrategy::XiaomiLogin => 1,
                profile::CryptoStrategy::NinebotCrypto => 2,
            };
            let missing = p.command_set().missing_features();
            let mut capabilities = 0;
            for (bit, feature) in [(1, profile::Feature::Lock), (2, profile::Feature::Light), (4, profile::Feature::RideMode)] {
                if !missing.contains(&feature) { capabilities |= bit; }
            }
            bytes.extend_from_slice(&[p.id() as u8, verified, crypto, capabilities]);
        }
        bytes
    });
    match result { Ok(bytes) => to_java(&env, &bytes), Err(_) => empty(&env) }
}

/// 正規化遙測：[電量、速度、平均速度、總里程、公尺行程、秒數、攝氏溫度]。
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_decodeMotorInfo(
    env: JNIEnv, _class: JClass, model_id: jni::sys::jint, data: jbyteArray,
) -> jni::sys::jdoubleArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let p = profile::ProfileRegistry::available().into_iter()
            .find(|p| p.id() as i32 == model_id).ok_or("Unknown profile")?;
        let bytes = env.convert_byte_array(data).map_err(|_| "Invalid data")?;
        let t = p.telemetry_decoder().decode(p.register_map(), &bytes)?;
        Ok::<_, &str>([t.battery_percent, t.speed_kmh, t.average_speed_kmh, t.odometer_m, t.trip_m, t.uptime_s, t.temperature_c])
    }));
    let values: Vec<f64> = match result { Ok(Ok(values)) => values.to_vec(), _ => Vec::new() };
    match env.new_double_array(values.len() as i32) {
        Ok(array) => {
            if env.set_double_array_region(array, 0, &values).is_ok() { array } else { std::ptr::null_mut() }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn pairing_sessions() -> &'static Mutex<HashMap<i64, Arc<Mutex<pairing::PairingSession>>>> {
    static PAIRING: OnceLock<Mutex<HashMap<i64, Arc<Mutex<pairing::PairingSession>>>>> = OnceLock::new();
    PAIRING.get_or_init(|| Mutex::new(HashMap::new()))
}

fn pairing_for(handle: jlong) -> Option<Arc<Mutex<pairing::PairingSession>>> {
    pairing_sessions().lock().ok()?.get(&handle).cloned()
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_beginPairing(
    env: JNIEnv, _class: JClass, name: jni::objects::JString, app_key: jbyteArray,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let name: String = env.get_string(name).ok()?.into();
        let key = zeroize::Zeroizing::new(env.convert_byte_array(app_key).ok()?);
        let key: [u8; 16] = key.as_slice().try_into().ok()?;
        let session = pairing::PairingSession::new(&name, key).ok()?;
        let handle = next_handle();
        pairing_sessions().lock().ok()?.insert(handle, Arc::new(Mutex::new(session)));
        Some(handle)
    })).ok().flatten().unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_pairingNext(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jbyteArray {
    let result = std::panic::catch_unwind(|| {
        let session = pairing_for(handle)?;
        let result = session.lock().ok()?.next_frame().ok(); result
    });
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_pairingReceive(
    env: JNIEnv, _class: JClass, handle: jlong, frame: jbyteArray,
) -> jni::sys::jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(frame).ok()?;
        let session = pairing_for(handle)?;
        let result = session.lock().ok()?.receive(&bytes).ok().map(|s| s as i32); result
    })).ok().flatten().unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_pairingSetSerial(
    env: JNIEnv, _class: JClass, handle: jlong, serial: jni::objects::JString,
) -> jni::sys::jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let serial: String = env.get_string(serial).ok()?.into();
        let session = pairing_for(handle)?;
        let result = session.lock().ok()?.set_serial(&serial).ok(); result
    }));
    u8::from(matches!(result, Ok(Some(()))))
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_freePairing(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(mut sessions) = pairing_sessions().lock() { sessions.remove(&handle); }
}

#[path = "../../ninebot-ble/src/vehicle.rs"]
pub mod vehicle;

fn vehicle_sessions() -> &'static Mutex<HashMap<i64, Arc<Mutex<vehicle::VehicleSession>>>> {
    static VEHICLES: OnceLock<Mutex<HashMap<i64, Arc<Mutex<vehicle::VehicleSession>>>>> = OnceLock::new();
    VEHICLES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn vehicle_for(handle: jlong) -> Option<Arc<Mutex<vehicle::VehicleSession>>> {
    vehicle_sessions().lock().ok()?.get(&handle).cloned()
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_openVehicle(_env: JNIEnv, _class: JClass, pairing_handle: jlong) -> jlong {
    std::panic::catch_unwind(|| {
        let vehicle = if pairing_handle == 0 { vehicle::VehicleSession::plaintext() } else {
            let session = pairing_sessions().lock().ok()?.remove(&pairing_handle)?;
            let pairing = Arc::try_unwrap(session).ok()?.into_inner().ok()?;
            vehicle::VehicleSession::paired(pairing).ok()?
        };
        let handle = next_handle();
        vehicle_sessions().lock().ok()?.insert(handle, Arc::new(Mutex::new(vehicle)));
        Some(handle)
    }).ok().flatten().unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_vehicleRequest(env: JNIEnv, _class: JClass, handle: jlong, action: jni::sys::jint, feature: jni::sys::jint, value: jni::sys::jint) -> jbyteArray {
    let result = std::panic::catch_unwind(|| {
        let session = vehicle_for(handle)?;
        let mut session = session.lock().ok()?;
        match action { 0 => session.identify(), 1 => session.telemetry(), 2 => session.control(u8::try_from(feature).ok()?, u8::try_from(value).ok()?), _ => return None }.ok()
    });
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_vehicleReceive(env: JNIEnv, _class: JClass, handle: jlong, bytes: jbyteArray) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(bytes).ok()?;
        let session = vehicle_for(handle)?;
        let result = session.lock().ok()?.receive(&bytes).ok(); result
    }));
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_vehicleResolve(env: JNIEnv, _class: JClass, handle: jlong, expected: jni::sys::jint, experimental: jni::sys::jboolean) -> jbyteArray {
    let result = std::panic::catch_unwind(|| {
        let model = vehicle::model_from_id(expected);
        if expected != -1 && model.is_none() { return None; }
        let session = vehicle_for(handle)?;
        let result = session.lock().ok()?.resolve(model, experimental != 0);
        Some(vehicle::outcome_bytes(result, model))
    });
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_resolveIdentification(env: JNIEnv, _class: JClass, serial: jbyteArray, expected: jni::sys::jint, experimental: jni::sys::jboolean) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let serial = env.convert_byte_array(serial).ok()?;
        let model = vehicle::model_from_id(expected);
        if expected != -1 && model.is_none() { return None; }
        Some(vehicle::outcome_bytes(profile::resolve_identification(&serial, model, experimental != 0), model))
    }));
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}
#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_freeVehicle(_env: JNIEnv, _class: JClass, handle: jlong) {
    if let Ok(mut sessions) = vehicle_sessions().lock() { sessions.remove(&handle); }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_decodeLegacyMotorInfo(
    env: JNIEnv, _class: JClass, model_id: jni::sys::jint, data: jbyteArray,
) -> jni::sys::jdoubleArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let p = profile::ProfileRegistry::available().into_iter()
            .find(|p| p.id() as i32 == model_id).ok_or("Unknown profile")?;
        let bytes = env.convert_byte_array(data).map_err(|_| "Invalid data")?;
        let t = p.decode_legacy_android(&bytes)?;
        Ok::<_, &str>([t.battery_percent, t.speed_kmh, t.average_speed_kmh, t.odometer_m, t.trip_m, t.uptime_s, t.temperature_c])
    }));
    let values: Vec<f64> = match result { Ok(Ok(values)) => values.to_vec(), _ => Vec::new() };
    match env.new_double_array(values.len() as i32) {
        Ok(array) => {
            if env.set_double_array_region(array, 0, &values).is_ok() { array } else { std::ptr::null_mut() }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_profileControl(env: JNIEnv, _class: JClass, model: jni::sys::jint, feature: jni::sys::jint, value: jni::sys::jint) -> jbyteArray {
    let result = std::panic::catch_unwind(|| {
        let p = profile::ProfileRegistry::resolve(vehicle::model_from_id(model)?)?;
        let commands = p.command_set();
        match (feature, value) { (0, 0) => commands.unlock, (0, 1) => commands.lock, (1, 0) => commands.light_off, (1, 1) => commands.light_on, _ => None }.map(|c| c.bytes())
    });
    match result { Ok(Some(bytes)) => to_java(&env, &bytes), _ => empty(&env) }
}
