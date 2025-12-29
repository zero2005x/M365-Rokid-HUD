#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use jni::objects::{JClass, JString};
#[cfg(target_os = "android")]
use jni::sys::{jstring};
#[cfg(target_os = "android")]
use std::sync::{Mutex};
#[cfg(target_os = "android")]
use once_cell::sync::Lazy;
#[cfg(target_os = "android")]
use tokio::runtime::Runtime;
#[cfg(target_os = "android")]
use crate::scanner::ScooterScanner;
#[cfg(target_os = "android")]
use crate::session::MiSession;
#[cfg(target_os = "android")]
use btleplug::api::BDAddr;
#[cfg(target_os = "android")]
use std::str::FromStr;
#[cfg(target_os = "android")]
use crate::login::LoginRequest;
#[cfg(target_os = "android")]
use crate::mi_crypto::AuthToken;
#[cfg(target_os = "android")]
use hex;

#[cfg(target_os = "android")]
static RUNTIME: Lazy<Runtime> = Lazy::new(|| Runtime::new().unwrap());
#[cfg(target_os = "android")]
static SCANNER: Lazy<Mutex<Option<ScooterScanner>>> = Lazy::new(|| Mutex::new(None));
#[cfg(target_os = "android")]
pub static SESSION: Lazy<Mutex<Option<MiSession>>> = Lazy::new(|| Mutex::new(None));

// Initialize btleplug on library load
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jni::sys::jint {
    // Initialize btleplug with the JNIEnv
    if let Ok(env) = vm.get_env() {
        let _ = btleplug::platform::init(&env);
    }
    
    // Initialize logger
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Debug));
    
    jni::sys::JNI_VERSION_1_6
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_startScan(env: JNIEnv, _: JClass) -> jstring {
    let result = RUNTIME.block_on(async {
        let mut scanner = ScooterScanner::new().await.map_err(|e| e.to_string())?;
        let _rx = scanner.start().await.map_err(|e| e.to_string())?;
        
        let mut global_scanner = SCANNER.lock().unwrap();
        *global_scanner = Some(scanner);
        Ok::<(), String>(())
    });

    match result {
        Ok(_) => env.new_string("Scan started").unwrap().into_inner(),
        Err(e) => env.new_string(format!("Error: {}", e)).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getDevices(env: JNIEnv, _: JClass) -> jstring {
    let devices = RUNTIME.block_on(async {
        let scanner_guard = SCANNER.lock().unwrap();
        if let Some(scanner) = scanner_guard.as_ref() {
            let devices = scanner.scooters().await;
            // Format as simple string for demo: "name,addr;name,addr"
            let mut list = String::new();
            for d in devices {
                list.push_str(&format!("{},{};", d.name.clone().unwrap_or_default(), d.addr));
            }
            list
        } else {
            String::from("Scanner not initialized")
        }
    });
    
    env.new_string(devices).unwrap().into_inner()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_connect(
    env: JNIEnv, 
    _: JClass, 
    addr: JString,
) -> jstring {
    // In jni 0.19, get_string returns JavaStr. We convert it to String.
    let addr_str: String = match env.get_string(addr) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("Error: Cannot get string").unwrap().into_inner()
    };
    
    let result = RUNTIME.block_on(async {
        let bd_addr = BDAddr::from_str(&addr_str).map_err(|e| format!("Invalid MAC: {}", e))?;
        
        let scanner_guard = SCANNER.lock().unwrap();
        let scanner = scanner_guard.as_ref().ok_or("Scanner not initialized")?;
        
        let devices = scanner.devices().await;
        let target = devices.iter().find(|d| d.addr == bd_addr)
            .ok_or("Device not found in scan results")?;
            
        let peripheral = scanner.peripheral(target).await.map_err(|e| format!("Peripheral error: {}", e))?;
        
        let token: AuthToken = [0u8; 12]; // DUMMY TOKEN
        
        let mut login_req = LoginRequest::new(&peripheral, &token).await.map_err(|e| format!("Login init error: {}", e))?;
        
        match login_req.start().await {
            Ok(session) => {
                let mut session_guard = SESSION.lock().unwrap();
                *session_guard = Some(session);
                Ok("Connected and Logged In".to_string())
            },
            Err(e) => {
                 Err(format!("Login failed: {}", e))
            }
        }
    });

    match result {
        Ok(msg) => env.new_string(msg).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getBatteryVoltage(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.battery_voltage().await
                .map(|voltage| format!("{:.2}", voltage))
                .map_err(|e| format!("Battery voltage error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(voltage) => env.new_string(voltage).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getBatteryAmperage(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.battery_amperage().await
                .map(|amperage| format!("{:.2}", amperage))
                .map_err(|e| format!("Battery amperage error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(amperage) => env.new_string(amperage).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getBatteryPercentage(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.battery_percentage().await
                .map(|percentage| format!("{:.0}", percentage))
                .map_err(|e| format!("Battery percentage error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(percentage) => env.new_string(percentage).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getBatteryInfo(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.battery_info().await
                .map(|info| format!(
                    "{{\"capacity\":{},\"percent\":{},\"current\":{:.2},\"voltage\":{:.2},\"temperature_1\":{},\"temperature_2\":{}}}",
                    info.capacity, info.percent, info.current, info.voltage, info.temperature_1, info.temperature_2
                ))
                .map_err(|e| format!("Battery info error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(info_json) => env.new_string(info_json).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getCurrentSpeed(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.motor_info().await
                .map(|info| format!("{:.2}", info.speed_kmh))
                .map_err(|e| format!("Current speed error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(speed) => env.new_string(speed).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getAverageSpeed(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.motor_info().await
                .map(|info| format!("{:.2}", info.speed_average_kmh))
                .map_err(|e| format!("Average speed error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(speed) => env.new_string(speed).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ninebot_ble_NativeLib_getMotorInfo(env: JNIEnv, _: JClass) -> jstring {
    let result: Result<String, String> = RUNTIME.block_on(async {
        let mut session_guard = SESSION.lock().unwrap();
        if let Some(ref mut session) = *session_guard {
            session.motor_info().await
                .map(|info| format!(
                    "{{\"battery_percent\":{},\"speed_kmh\":{:.2},\"speed_average_kmh\":{:.2},\"total_distance_m\":{},\"trip_distance_m\":{},\"uptime_s\":{},\"frame_temperature\":{:.1}}}",
                    info.battery_percent,
                    info.speed_kmh,
                    info.speed_average_kmh,
                    info.total_distance_m,
                    info.trip_distance_m,
                    info.uptime.as_secs(),
                    info.frame_temperature
                ))
                .map_err(|e| format!("Motor info error: {}", e))
        } else {
            Err("No active session".to_string())
        }
    });

    match result {
        Ok(info_json) => env.new_string(info_json).unwrap().into_inner(),
        Err(e) => env.new_string(e).unwrap().into_inner()
    }
}
