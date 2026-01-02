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
    // Wrap entire function in catch_unwind for FFI safety
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (secret, public) = mi_crypto::gen_key_pair();
        
        let state = Box::new(KeyExchangeState {
            secret: Some(secret),
        });
        let ptr = Box::into_raw(state) as i64;
        
        let pk_bytes = public.to_encoded_point(false).as_bytes().to_vec();
        
        let mut result = Vec::with_capacity(8 + pk_bytes.len());
        result.extend_from_slice(&ptr.to_be_bytes());
        result.extend_from_slice(&pk_bytes);
        
        result
    }));
    
    match result {
        Ok(data) => env.byte_array_from_slice(&data).unwrap_or_else(|_| std::ptr::null_mut()),
        Err(_) => env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
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
    // Validate pointer
    if ctx_ptr == 0 {
        return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut());
    }
    
    // Restore context
    let mut state = unsafe { Box::from_raw(ctx_ptr as *mut KeyExchangeState) };
    
    // Safely take secret
    let secret = match state.secret.take() {
        Some(s) => s,
        None => return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
    };
    
    let remote_key_vec = match env.convert_byte_array(remote_key) {
        Ok(v) => v,
        Err(_) => return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
    };
    let remote_info_vec = match env.convert_byte_array(remote_info) {
        Ok(v) => v,
        Err(_) => return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
    };
    
    // Call calc_did with catch_unwind
    let (did_ct, token) = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
             mi_crypto::calc_did(&secret, &remote_key_vec, &remote_info_vec)
    })) {
        Ok(res) => res,
        Err(_) => return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
    };

    // Return format: [12 bytes Token][Rest DID Ciphertext]
    let mut output = Vec::new();
    output.extend_from_slice(&token);
    output.extend_from_slice(&did_ct);
    
    env.byte_array_from_slice(&output).unwrap_or_else(|_| std::ptr::null_mut())
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
        
        let session = Box::new(SessionState { keys });
        let ptr = Box::into_raw(session) as i64;
        
        let mut result = Vec::new();
        result.extend_from_slice(&ptr.to_be_bytes());
        result.extend_from_slice(&info);
        
        Ok::<Vec<u8>, &str>(result)
    }));
    
    match result {
        Ok(Ok(data)) => env.byte_array_from_slice(&data).unwrap_or_else(|_| std::ptr::null_mut()),
        _ => env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
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
     if session_ptr == 0 {
         return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut());
     }
     
     let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
         let session = unsafe { &*(session_ptr as *mut SessionState) };
         let payload_vec = env.convert_byte_array(payload).map_err(|_| "payload conversion failed")?;
         
         let encrypted = mi_crypto::encrypt_uart(&session.keys.app, &payload_vec, counter as u32, None);
         
         Ok::<Vec<u8>, &str>(encrypted)
     }));
     
     match result {
         Ok(Ok(data)) => env.byte_array_from_slice(&data).unwrap_or_else(|_| std::ptr::null_mut()),
         _ => env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
     }
}

#[no_mangle]
pub extern "system" fn Java_com_m365bleapp_ffi_M365Native_decrypt(
     env: JNIEnv,
     _class: JClass,
     session_ptr: jlong,
     encrypted: jbyteArray,
) -> jbyteArray {
     if session_ptr == 0 {
         return env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut());
     }
     
     let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
         let session = unsafe { &*(session_ptr as *mut SessionState) };
         let encrypted_vec = env.convert_byte_array(encrypted).map_err(|_| "encrypted conversion failed")?;
         
         mi_crypto::decrypt_uart(&session.keys.dev, &encrypted_vec)
             .map_err(|_| "decryption failed")
     }));
     
     match result {
         Ok(Ok(data)) => env.byte_array_from_slice(&data).unwrap_or_else(|_| std::ptr::null_mut()),
         _ => env.byte_array_from_slice(&[]).unwrap_or_else(|_| std::ptr::null_mut()),
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
