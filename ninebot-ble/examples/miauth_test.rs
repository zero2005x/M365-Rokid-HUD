use std::error::Error;
use std::time::Duration;
use tokio::time;
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter, WriteType};
use btleplug::platform::Manager;
use uuid::Uuid;
use futures::stream::StreamExt;
use futures::FutureExt;

// FE95 Service Characteristics (discovered from your device)
const UPNP_UUID: Uuid = Uuid::from_u128(0x00000010_0000_1000_8000_00805f9b34fb);  // TX
const AVDTP_UUID: Uuid = Uuid::from_u128(0x00000019_0000_1000_8000_00805f9b34fb); // RX

// MiAuth Commands
const CMD_GET_INFO: [u8; 4] = [0xA2, 0x00, 0x00, 0x00];
const CMD_SET_KEY: [u8; 4] = [0x15, 0x00, 0x00, 0x00];
const CMD_AUTH: [u8; 4] = [0x13, 0x00, 0x00, 0x00];
const CMD_LOGIN: [u8; 4] = [0x24, 0x00, 0x00, 0x00];

// Expected responses
const RCV_SEND_DID: [u8; 6] = [0x00, 0x00, 0x00, 0x00, 0x02, 0x00];
#[allow(dead_code)]
const RCV_RDY: [u8; 4] = [0x00, 0x00, 0x01, 0x01];
#[allow(dead_code)]
const RCV_OK: [u8; 4] = [0x00, 0x00, 0x01, 0x00];

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt::init();

    let target_mac = std::env::args().nth(1).expect("Please provide MAC address");
    println!("=== MiAuth Clone Test ===");
    println!("Target: {}\n", target_mac);

    let manager = Manager::new().await?;
    let adapters = manager.adapters().await?;
    let central = adapters.into_iter().nth(0).expect("No Bluetooth adapters found");

    println!("[1] Scanning...");
    central.start_scan(ScanFilter::default()).await?;
    time::sleep(Duration::from_secs(5)).await;

    let peripherals = central.peripherals().await?;
    let device = peripherals.into_iter()
        .find(|p| {
            if let Some(Ok(Some(props))) = p.properties().now_or_never() {
                props.address.to_string().contains(&target_mac)
            } else {
                false
            }
        })
        .expect("Device not found");

    println!("[2] Connecting...");
    device.connect().await?;
    time::sleep(Duration::from_secs(2)).await;

    println!("[3] Discovering services...");
    device.discover_services().await?;

    let chars = device.characteristics();
    let upnp = chars.iter().find(|c| c.uuid == UPNP_UUID).expect("UPNP char not found");
    let avdtp = chars.iter().find(|c| c.uuid == AVDTP_UUID).expect("AVDTP char not found");

    // Subscribe to BOTH notification characteristics
    device.subscribe(upnp).await?;
    device.subscribe(avdtp).await?;
    println!("[4] Subscribed to UPNP and AVDTP\n");

    let mut notification_stream = device.notifications().await?;

    // Helper to send and wait for response
    async fn send_and_wait(
        device: &btleplug::platform::Peripheral,
        char: &btleplug::api::Characteristic,
        cmd: &[u8],
        name: &str,
        stream: &mut (impl StreamExt<Item = btleplug::api::ValueNotification> + Unpin),
    ) -> Option<Vec<u8>> {
        println!(">>> Sending {}: {:02X?}", name, cmd);
        if let Err(e) = device.write(char, cmd, WriteType::WithoutResponse).await {
            println!("    Write failed: {}", e);
            return None;
        }

        let timeout = time::sleep(Duration::from_millis(2000));
        tokio::pin!(timeout);

        loop {
            tokio::select! {
                Some(data) = stream.next() => {
                    println!("<<< Response from {:?}: {:02X?}", data.uuid, data.value);
                    return Some(data.value);
                }
                _ = &mut timeout => {
                    println!("    (timeout)");
                    return None;
                }
            }
        }
    }

    // === MiAuth Handshake Sequence ===
    println!("=== Starting MiAuth Handshake ===\n");

    // Step 1: Send CMD_GET_INFO to UPNP, expect response on AVDTP
    println!("--- Step 1: Get Info ---");
    let resp = send_and_wait(&device, upnp, &CMD_GET_INFO, "CMD_GET_INFO", &mut notification_stream).await;
    
    if let Some(r) = &resp {
        if r.as_slice() == RCV_SEND_DID {
            println!("✓ Device wants us to send DID (Device ID)\n");
        } else {
            println!("? Unknown response, continuing...\n");
        }
    }

    // Step 2: Try sending a fake DID
    // Format: CMD_SEND_DID header + DID bytes
    // Typical DID is a 20-byte string
    println!("--- Step 2: Send DID ---");
    let fake_did = b"did:12345678901234567890";
    let mut did_packet = vec![0x00, 0x00, 0x00, 0x00, 0x02, 0x00]; // CMD_SEND_DID header
    did_packet.extend_from_slice(fake_did);
    let _ = send_and_wait(&device, upnp, &did_packet, "CMD_SEND_DID", &mut notification_stream).await;

    // Step 3: Try CMD_SET_KEY
    println!("--- Step 3: Set Key ---");
    let _ = send_and_wait(&device, upnp, &CMD_SET_KEY, "CMD_SET_KEY", &mut notification_stream).await;

    // Step 4: Generate and send ECDH public key
    // For testing, we'll send random bytes as a "key"
    println!("--- Step 4: Send Public Key ---");
    let fake_pubkey: Vec<u8> = (0..64).map(|i| i as u8).collect();
    let mut key_packet = vec![0x00, 0x00, 0x00, 0x0b, 0x01, 0x00]; // CMD_SEND_KEY header
    key_packet.extend_from_slice(&fake_pubkey);
    let _ = send_and_wait(&device, upnp, &key_packet, "CMD_SEND_KEY (fake)", &mut notification_stream).await;

    // Step 5: Try AUTH command
    println!("--- Step 5: Auth ---");
    let _ = send_and_wait(&device, upnp, &CMD_AUTH, "CMD_AUTH", &mut notification_stream).await;

    // Step 6: Try LOGIN command
    println!("--- Step 6: Login ---");
    let _ = send_and_wait(&device, upnp, &CMD_LOGIN, "CMD_LOGIN", &mut notification_stream).await;

    // Step 7: Now try UART commands
    println!("\n=== Testing UART after handshake ===\n");
    
    // Try Xiaomi protocol commands on both UPNP and the NUS TX
    let nus_tx = chars.iter().find(|c| c.uuid == Uuid::from_u128(0x6e400002_b5a3_f393_e0a9_e50e24dcca9e));
    
    let version_cmd = vec![0x55, 0xAA, 0x03, 0x20, 0x01, 0x1A, 0x02, 0xBF, 0xFF];
    let battery_cmd = vec![0x55, 0xAA, 0x03, 0x20, 0x01, 0x32, 0x02, 0xA7, 0xFF];

    if let Some(tx) = nus_tx {
        device.subscribe(&chars.iter().find(|c| c.uuid == Uuid::from_u128(0x6e400003_b5a3_f393_e0a9_e50e24dcca9e)).unwrap()).await.ok();
        
        println!("--- Testing on NUS TX ---");
        let _ = send_and_wait(&device, tx, &version_cmd, "Get Version", &mut notification_stream).await;
        let _ = send_and_wait(&device, tx, &battery_cmd, "Get Battery", &mut notification_stream).await;
    }

    println!("--- Testing on UPNP (0x10) ---");
    let _ = send_and_wait(&device, upnp, &version_cmd, "Get Version", &mut notification_stream).await;
    let _ = send_and_wait(&device, upnp, &battery_cmd, "Get Battery", &mut notification_stream).await;

    // Final wait
    println!("\n[5] Waiting for any delayed responses...");
    let final_timeout = time::sleep(Duration::from_secs(3));
    tokio::pin!(final_timeout);

    loop {
        tokio::select! {
            Some(data) = notification_stream.next() => {
                println!("Late: {:02X?} from {:?}", data.value, data.uuid);
            }
            _ = &mut final_timeout => {
                break;
            }
        }
    }

    device.disconnect().await?;
    
    println!("\n=== Complete ===");
    println!("This device requires full MiAuth. To fully authenticate:");
    println!("1. Use the existing 'register' example to get a token");
    println!("2. Then use 'login' example to establish a session");
    println!("3. After that, UART commands will work");
    
    Ok(())
}
