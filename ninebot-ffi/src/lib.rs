use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jbyteArray, jlong};
mod mi_crypto;
use elliptic_curve::sec1::ToEncodedPoint;
use p256::ecdh::EphemeralSecret;
// use pretty_hex::*;

// We need to store state across JNI calls. 
// For simplicity, we can return the state to Java as a pointer (jlong), 
// effectively manually managing memory (unsafe but standard JNI pattern).

struct KeyExchangeState {
    secret: Option<EphemeralSecret>,
}

struct SessionState {
    keys: mi_crypto::LoginKeychain,
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
    let (secret, public) = mi_crypto::gen_key_pair();
    
    // Store secret in a heap box and return pointer? 
    // Wait, the handshake flow is:
    // 1. Android asks "Give me your public key" (and keeps the private key context).
    // 2. Android receives Remote Public Key -> Calls "Process Handshake" with context.
    
    // So we need to return `(ptr, publicKeyBytes)`.
    // But JNI returns 1 arg.
    // Better: Return a serialized byte array containing address + pubkey?
    // Or return an object.
    // Or just store it in a static map with ID? (Statics are messy).
    // Let's use `long` handle. But we need to return both handle and pubkey.
    // Let's make `prepareHandshake` return the PUBLIC KEY bytes only? 
    // And where is the secret?
    // We should make `long createHandshakeContext()` and `byte[] getPublicKey(long ctx)`.
    
    // Simplified: `prepareHandshake` returns a composite structure or we split it.
    // Let's just return a standard Java object? No, too much boilerplate.
    
    // Helper: We simply return the Public Key here (encoded).
    // BUT we must persist the PRIVATE key.
    // We will allocate the Context on heap and return its address as `jlong`.
    // Wait, `processHandshake` needs the private key.
    
    // Function: `createContext() -> jlong`
    
    // Let's combine:
    // Returns byte[] which is [8 bytes pointer][public key bytes...]
    
    let state = Box::new(KeyExchangeState {
        secret: Some(secret),
    });
    let ptr = Box::into_raw(state) as i64;
    
    let pk_bytes = public.to_encoded_point(false).as_bytes().to_vec();
    
    let mut result = Vec::with_capacity(8 + pk_bytes.len());
    result.extend_from_slice(&ptr.to_be_bytes());
    result.extend_from_slice(&pk_bytes);
    
    env.byte_array_from_slice(&result).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_processHandshake(
    env: JNIEnv,
    _class: JClass,
    ctx_ptr: jlong,
    remote_key: jbyteArray,
    remote_info: jbyteArray,
) -> jbyteArray {
    // Restore context
    let mut state = unsafe { Box::from_raw(ctx_ptr as *mut KeyExchangeState) };
    // Function consumes context (one-time use)
    
    let secret = state.secret.take().expect("Secret already used");
    
    let remote_key_vec = env.convert_byte_array(remote_key).unwrap();
    let remote_info_vec = env.convert_byte_array(remote_info).unwrap();
    
    // Call calc_did
    let (did_ct, token) = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
             mi_crypto::calc_did(&secret, &remote_key_vec, &remote_info_vec)
    })) {
        Ok(res) => res,
        Err(_) => return env.byte_array_from_slice(&[]).unwrap(), // Error
    };

    // Return format: [12 bytes Token][Rest DID Ciphertext]
    let mut output = Vec::new();
    output.extend_from_slice(&token);
    output.extend_from_slice(&did_ct);
    
    env.byte_array_from_slice(&output).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_login(
     env: JNIEnv,
     _class: JClass,
     token: jbyteArray,
     rand_key: jbyteArray,
     remote_key: jbyteArray,
     _remote_info: jbyteArray,
) -> jbyteArray { // Returns [8 bytes Ptr][Login Data...]
    let token_vec = env.convert_byte_array(token).unwrap();
    let mut rand_key_vec = env.convert_byte_array(rand_key).unwrap();
    let mut remote_key_vec = env.convert_byte_array(remote_key).unwrap();
    // remote_info unused for derivation now, but maybe for verification?
    // For now, ignoring remote_info as per mi_crypto change.
    
    if token_vec.len() != 12 { return env.byte_array_from_slice(&[]).unwrap(); }
    
    let mut token_arr = [0u8; 12];
    token_arr.copy_from_slice(&token_vec);
    
    // calc_login_did modifies inputs!
    let (info, _, keys) = mi_crypto::calc_login_did(
        &mut rand_key_vec,
        &mut remote_key_vec,
        &token_arr
    );
    
    let session = Box::new(SessionState { keys });
    let ptr = Box::into_raw(session) as i64;
    
    let mut result = Vec::new();
    result.extend_from_slice(&ptr.to_be_bytes());
    result.extend_from_slice(&info);
    
    env.byte_array_from_slice(&result).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_encrypt(
     env: JNIEnv,
     _class: JClass,
     session_ptr: jlong,
     payload: jbyteArray,
     counter: jlong,
) -> jbyteArray {
     if session_ptr == 0 {
         return env.byte_array_from_slice(&[]).unwrap();
     }
     let session = unsafe { &*(session_ptr as *mut SessionState) };
     let payload_vec = env.convert_byte_array(payload).unwrap();
     
     let encrypted = mi_crypto::encrypt_uart(&session.keys.app, &payload_vec, counter as u32, None);
     
     env.byte_array_from_slice(&encrypted).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_decrypt(
     env: JNIEnv,
     _class: JClass,
     session_ptr: jlong,
     encrypted: jbyteArray,
) -> jbyteArray {
     if session_ptr == 0 {
         return env.byte_array_from_slice(&[]).unwrap();
     }
     let session = unsafe { &*(session_ptr as *mut SessionState) };
     let encrypted_vec = env.convert_byte_array(encrypted).unwrap();
     
     match mi_crypto::decrypt_uart(&session.keys.dev, &encrypted_vec) {
         Ok(data) => env.byte_array_from_slice(&data).unwrap(),
         Err(_) => env.byte_array_from_slice(&[]).unwrap() // Error indicator
     }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_freeSession(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        let _ = unsafe { Box::from_raw(ptr as *mut SessionState) };
    }
}
