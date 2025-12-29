extern crate uuid;

// 宣告模組
pub mod mi_crypto;
pub mod protocol;
pub mod consts;
pub mod clone_connection;
pub mod login;
pub mod scanner;
pub mod session;
pub mod android_api;
pub mod register;
pub mod connection;

// 引用
pub use clone_connection::ScooterConnection;
pub use scanner::{ScooterScanner, ScannerEvent};

pub use mi_crypto::AuthToken;
pub use register::{RegistrationRequest, RegistrationError};
pub use login::LoginRequest;
pub use connection::ConnectionHelper;

#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use jni::objects::{JClass, JObject, JString, JValue, GlobalRef};
#[cfg(target_os = "android")]
use jni::JavaVM;
#[cfg(target_os = "android")]
use log::{info, error, LevelFilter};
#[cfg(target_os = "android")]
use android_logger::Config;

#[cfg(target_os = "android")]
use tokio::runtime::Runtime;
#[cfg(target_os = "android")]
use tokio::sync::mpsc;
#[cfg(target_os = "android")]
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter};
#[cfg(target_os = "android")]
use btleplug::platform::Manager;
#[cfg(target_os = "android")]
use std::time::Duration;
#[cfg(target_os = "android")]
use std::sync::{Mutex, Arc};
#[cfg(target_os = "android")]
use std::str::FromStr;
#[cfg(target_os = "android")]
use once_cell::sync::Lazy;

// --- Globals & Types (Thread-Safe + Arc) ---

#[cfg(target_os = "android")]
static JAVA_VM: Lazy<Mutex<Option<Arc<JavaVM>>>> = Lazy::new(|| Mutex::new(None));

#[cfg(target_os = "android")]
static BLE_MANAGER_CLASS: Lazy<Mutex<Option<GlobalRef>>> = Lazy::new(|| Mutex::new(None));

#[cfg(target_os = "android")]
#[derive(Debug)]
pub enum BleEvent {
    Status(String),
    DeviceFound { name: String, address: String },
    Data { speed: f64, battery: i32, temp: f64 },
}

#[cfg(target_os = "android")]
static EVENT_TX: Lazy<Mutex<Option<mpsc::Sender<BleEvent>>>> = Lazy::new(|| Mutex::new(None));

// --- 1. Init ---
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_rokid_m365hud_BleManager_nativeInit(
    env: JNIEnv,
    this: JObject,
    _context: JObject,
) {
    android_logger::init_once(
        Config::default()
            .with_tag("RustNinebot")
            .with_max_level(LevelFilter::Info)
    );
    info!("Rust JNI Initialized - Lifetime Fixed Version");

    // Initialize btleplug
    let _ = btleplug::platform::init(&env);

    if let Ok(vm) = env.get_java_vm() {
        let mut global_vm = JAVA_VM.lock().unwrap();
        *global_vm = Some(Arc::new(vm));
    }

    if let Ok(class) = env.get_object_class(this) {
        if let Ok(global_class) = env.new_global_ref(class) {
            let mut global_cls = BLE_MANAGER_CLASS.lock().unwrap();
            *global_cls = Some(global_class);
        }
    }

    // Start background runtime
    std::thread::spawn(|| {
        let rt = Runtime::new().unwrap();
        let (tx, mut rx) = mpsc::channel::<BleEvent>(32);
        
        {
            let mut global_tx = EVENT_TX.lock().unwrap();
            *global_tx = Some(tx);
        }

        // JNI Callback Loop
        rt.block_on(async {
            // [Fix] Keep VM alive in this scope
            let vm_arc = {
                let guard = JAVA_VM.lock().unwrap();
                guard.as_ref().cloned()
            };

            if let Some(vm) = vm_arc {
                // Attach once, creating a guard that lives as long as `vm`
                if let Ok(_guard) = vm.attach_current_thread_permanently() {
                    loop {
                        if let Some(event) = rx.recv().await {
                             if let Ok(env) = vm.attach_current_thread() {
                                let class_guard = BLE_MANAGER_CLASS.lock().unwrap();
                                if let Some(global_class) = class_guard.as_ref() {
                                    let jclass_obj = JClass::from(global_class.as_obj());

                                    match event {
                                        BleEvent::Status(msg) => {
                                            if let Ok(jmsg) = env.new_string(msg) {
                                                let _ = env.call_static_method(jclass_obj, "onNativeStatus", "(Ljava/lang/String;)V", &[JValue::Object(jmsg.into())]);
                                            }
                                        },
                                        BleEvent::DeviceFound { name, address } => {
                                            if let Ok(jname) = env.new_string(name) {
                                                if let Ok(jaddr) = env.new_string(address) {
                                                    let _ = env.call_static_method(jclass_obj, "onNativeDeviceFound", "(Ljava/lang/String;Ljava/lang/String;)V", &[JValue::Object(jname.into()), JValue::Object(jaddr.into())]);
                                                }
                                            }
                                        },
                                        BleEvent::Data { speed, battery, temp } => {
                                            let _ = env.call_static_method(jclass_obj, "onNativeUpdate", "(DID)V", &[JValue::Double(speed), JValue::Int(battery), JValue::Double(temp)]);
                                        }
                                    }
                                    if env.exception_check().unwrap_or(false) { env.exception_clear().unwrap(); }
                                }
                             }
                        }
                    }
                }
            }
        });
    });
}

// --- 2. Start Scan ---
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_rokid_m365hud_BleManager_nativeStartScan(
    _env: JNIEnv,
    _this: JObject,
) {
    info!("Native Start Scan Called");
    
    std::thread::spawn(|| {
        let rt = Runtime::new().unwrap();
        rt.block_on(async {
            // [Fix] 1. Retrieve VM and keep ownership in this block
            let vm_arc = {
                let guard = JAVA_VM.lock().unwrap();
                guard.as_ref().cloned()
            };

            // [Fix] 2. Unwrap safely
            let vm = match vm_arc {
                Some(v) => v,
                None => {
                    error!("JAVA_VM not initialized");
                    return;
                }
            };

            // [Fix] 3. Attach using the local `vm` variable
            // `_guard` will borrow from `vm`, and both live until end of scope
            let _guard = match vm.attach_current_thread_permanently() {
                Ok(g) => g,
                Err(e) => {
                    error!("Failed to attach scan thread: {:?}", e);
                    send_status("Rust: JVM Attach Failed").await;
                    return;
                }
            };
            
            send_status("Rust: Init Scan...").await;

            let manager = match Manager::new().await {
                Ok(m) => m,
                Err(e) => {
                    error!("Failed to init manager: {:?}", e);
                    send_status("Rust: BLE Manager Error").await;
                    return;
                }
            };

            let adapters = manager.adapters().await.unwrap();
            if adapters.is_empty() { 
                error!("No Bluetooth Adapters found");
                send_status("Rust: No BLE Adapter").await;
                return; 
            }
            let adapter = &adapters[0];

            if let Err(e) = adapter.start_scan(ScanFilter::default()).await {
                error!("Failed to start scan: {:?}", e);
                send_status(&format!("Scan Error: {:?}", e)).await;
                return;
            }
            
            send_status("Rust: Scanning...").await;

            loop {
                tokio::time::sleep(Duration::from_millis(1000)).await;
                let peripherals = adapter.peripherals().await.unwrap_or_default();
                
                info!("Discovered {} devices", peripherals.len());

                for p in peripherals {
                    let addr = p.address().to_string();
                    let props = p.properties().await.unwrap().unwrap();
                    let name = props.local_name.unwrap_or("Unknown".to_string());

                    if !name.is_empty() {
                        if let Some(tx) = EVENT_TX.lock().unwrap().clone() {
                            let _ = tx.send(BleEvent::DeviceFound { name: name.clone(), address: addr.clone() }).await;
                        }
                    }
                }
            }
        });
    });
}

// --- 3. Connect & Monitor ---
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_rokid_m365hud_BleManager_nativeConnect(
    env: JNIEnv,
    _this: JObject,
    j_address: JString,
) {
    let address: String = match env.get_string(j_address) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    info!("Rust: Connecting to device: {}", address);

    std::thread::spawn(move || {
        let rt = Runtime::new().unwrap();
        rt.block_on(async {
            // Retrieve VM safely
            let vm_arc = {
                let guard = JAVA_VM.lock().unwrap();
                guard.as_ref().cloned()
            };

            let vm = match vm_arc {
                Some(v) => v,
                None => {
                    error!("JAVA_VM not initialized");
                    return;
                }
            };

            let _guard = match vm.attach_current_thread_permanently() {
                Ok(g) => g,
                Err(e) => {
                    error!("Connect thread attach failed: {:?}", e);
                    return;
                }
            };

            send_status("Initializing scanner...").await;

            // Create scanner and find device
            let mut scanner = match ScooterScanner::new().await {
                Ok(s) => s,
                Err(e) => {
                    error!("Failed to create scanner: {:?}", e);
                    send_status(&format!("Scanner Error: {}", e)).await;
                    return;
                }
            };

            let bd_addr = match btleplug::api::BDAddr::from_str(&address) {
                Ok(addr) => addr,
                Err(e) => {
                    error!("Invalid MAC address: {:?}", e);
                    send_status("Invalid MAC Address").await;
                    return;
                }
            };

            send_status("Scanning for device...").await;

            let tracked_device = match scanner.wait_for(&bd_addr).await {
                Ok(device) => device,
                Err(e) => {
                    error!("Device not found: {:?}", e);
                    send_status("Device Not Found").await;
                    return;
                }
            };

            send_status("Device found. Connecting...").await;

            let peripheral = match scanner.peripheral(&tracked_device).await {
                Ok(p) => p,
                Err(e) => {
                    error!("Failed to get peripheral: {:?}", e);
                    send_status("Peripheral Error").await;
                    return;
                }
            };

            // Connect to device
            if let Err(e) = peripheral.connect().await {
                error!("Connection failed: {:?}", e);
                send_status(&format!("Connection Failed: {}", e)).await;
                return;
            }

            send_status("Connected. Authenticating...").await;

            // Use dummy token for demo (in real app, use proper authentication)
            let token: AuthToken = [0u8; 12];

            let mut login_req = match LoginRequest::new(&peripheral, &token).await {
                Ok(req) => req,
                Err(e) => {
                    error!("Login request failed: {:?}", e);
                    send_status(&format!("Login Init Failed: {}", e)).await;
                    return;
                }
            };

            let session = match login_req.start().await {
                Ok(sess) => sess,
                Err(e) => {
                    error!("Authentication failed: {:?}", e);
                    send_status(&format!("Auth Failed: {}", e)).await;
                    return;
                }
            };

            send_status("Authenticated. Starting monitoring...").await;

            // Store session globally for JNI calls
            {
                *crate::android_api::SESSION.lock().unwrap() = Some(session);
            }

            send_status("Ready").await;

            // Start real-time monitoring loop
            let mut ticker = tokio::time::interval(Duration::from_millis(1000));

            loop {
                tokio::select! {
                    _ = ticker.tick() => {
                        // Query real-time data
                        if let Some(tx) = EVENT_TX.lock().unwrap().clone() {
                            if let Some(ref mut session) = *crate::android_api::SESSION.lock().unwrap() {
                                let result: Result<crate::session::MotorInfo, anyhow::Error> = session.motor_info().await;
                                match result {
                                    Ok(info) => {
                                        let _ = tx.send(BleEvent::Data {
                                            speed: info.speed_kmh as f64,
                                            battery: info.battery_percent as i32,
                                            temp: info.frame_temperature as f64
                                        }).await;
                                    },
                                    Err(e) => {
                                        error!("Failed to get motor info: {:?}", e);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    });
}

// 輔助函數
#[cfg(target_os = "android")]
async fn send_status(msg: &str) {
    if let Some(tx) = EVENT_TX.lock().unwrap().clone() {
        let _ = tx.send(BleEvent::Status(msg.to_string())).await;
    }
}

#[cfg(target_os = "android")]
async fn send_data(speed: f64, battery: i32, temp: f64) {
    if let Some(tx) = EVENT_TX.lock().unwrap().clone() {
        let _ = tx.send(BleEvent::Data { speed, battery, temp }).await;
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_rokid_m365hud_BleManager_nativeStopScan(_env: JNIEnv, _this: JObject) {}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_rokid_m365hud_BleManager_nativeStartMonitoring(env: JNIEnv, this: JObject, j_address: JString) {
    Java_com_rokid_m365hud_BleManager_nativeConnect(env, this, j_address);
}
