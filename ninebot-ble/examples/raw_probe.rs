use std::error::Error;
use std::time::Duration;
use tokio::time;
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter, CharPropFlags, WriteType};
use btleplug::platform::Manager;
use uuid::Uuid;
use futures::stream::StreamExt;
use futures::FutureExt;

const READ_CHAR_UUID: Uuid = Uuid::from_u128(0x00000004_0000_1000_8000_00805f9b34fb);

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt::init();

    let target_mac = std::env::args().nth(1).expect("Please provide MAC address");
    println!("=== Raw Device Info Tool ===");
    println!("Target MAC: {}", target_mac);

    let manager = Manager::new().await?;
    let adapters = manager.adapters().await?;
    let central = adapters.into_iter().nth(0).expect("No Bluetooth adapters found");

    println!("\n[1] Scanning...");
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

    // Print advertisement data
    println!("\n=== Advertisement Data ===");
    if let Some(Ok(Some(props))) = device.properties().now_or_never() {
        println!("Name: {:?}", props.local_name);
        println!("Address: {}", props.address);
        println!("RSSI: {:?}", props.rssi);
        println!("Services: {:?}", props.services);
        println!("Service Data:");
        for (uuid, data) in &props.service_data {
            println!("  {}: {:02X?}", uuid, data);
        }
        println!("Manufacturer Data:");
        for (id, data) in &props.manufacturer_data {
            println!("  0x{:04X}: {:02X?}", id, data);
        }
    }

    println!("\n[2] Connecting...");
    device.connect().await?;
    time::sleep(Duration::from_secs(2)).await;

    println!("[3] Discovering services...");
    device.discover_services().await?;

    let chars = device.characteristics();
    
    println!("\n=== All Characteristics with Services ===");
    for c in &chars {
        println!("Service: {} | Char: {} | Props: {:?}", c.service_uuid, c.uuid, c.properties);
    }

    // Try to read the readable characteristic (0x04)
    println!("\n=== Reading Characteristic 0x04 ===");
    if let Some(read_char) = chars.iter().find(|c| c.uuid == READ_CHAR_UUID) {
        match device.read(read_char).await {
            Ok(data) => {
                println!("Raw bytes: {:02X?}", data);
                println!("As string: {:?}", String::from_utf8_lossy(&data));
            }
            Err(e) => println!("Read failed: {}", e),
        }
    }

    // Subscribe and try sending raw bytes without Xiaomi framing
    println!("\n=== Testing Raw Protocol Variations ===");
    
    let notify_chars: Vec<_> = chars.iter()
        .filter(|c| c.properties.contains(CharPropFlags::NOTIFY))
        .collect();

    for c in &notify_chars {
        let _ = device.subscribe(c).await;
    }

    let mut notification_stream = device.notifications().await?;

    // Find writable chars
    let write_chars: Vec<_> = chars.iter()
        .filter(|c| c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE))
        .collect();

    // Different protocol variations to try
    let raw_commands: Vec<(&str, Vec<u8>)> = vec![
        // Standard Xiaomi M365
        ("M365 Version", vec![0x55, 0xAA, 0x03, 0x20, 0x01, 0x1A, 0x02, 0xBF, 0xFF]),
        
        // Ninebot ES series format (different header)
        ("Ninebot ES", vec![0x5A, 0xA5, 0x01, 0x3E, 0x20, 0x01, 0x00, 0x9F]),
        
        // Some clones use simpler format without checksum
        ("Simple", vec![0x55, 0xAA, 0x03, 0x20, 0x01, 0x1A, 0x02]),
        
        // Lenzod protocol
        ("Lenzod Unlock", vec![0xA6, 0x12, 0x02, 0x10, 0x14]),
        
        // XBOT Z character
        ("XBOT Z", vec![0x5A]),
        
        // Some controllers respond to AT commands
        ("AT", b"AT\r\n".to_vec()),
        
        // Version query without proper framing
        ("Raw Query", vec![0x01, 0x1A]),
        
        // Full MiAuth init sequence (CMD_GET_INFO)
        ("MiAuth GetInfo", vec![0xA2, 0x00, 0x00, 0x00]),
        
        // MiAuth set key
        ("MiAuth SetKey", vec![0x15, 0x00, 0x00, 0x00]),
    ];

    for write_char in &write_chars {
        println!("\n--- Testing on {:?} ---", write_char.uuid);
        
        for (name, cmd) in &raw_commands {
            println!("Sending {}: {:02X?}", name, cmd);
            
            if let Err(e) = device.write(write_char, cmd, WriteType::WithoutResponse).await {
                println!("  Write failed: {}", e);
                continue;
            }

            // Short wait for response
            let timeout = time::sleep(Duration::from_millis(800));
            tokio::pin!(timeout);

            loop {
                tokio::select! {
                    Some(data) = notification_stream.next() => {
                        println!("  ✓ RESPONSE from {:?}: {:02X?}", data.uuid, data.value);
                        println!("    As string: {:?}", String::from_utf8_lossy(&data.value));
                    }
                    _ = &mut timeout => {
                        break;
                    }
                }
            }
        }
    }

    // Final wait
    println!("\n[4] Final wait (3s)...");
    let final_timeout = time::sleep(Duration::from_secs(3));
    tokio::pin!(final_timeout);

    loop {
        tokio::select! {
            Some(data) = notification_stream.next() => {
                println!("Late response from {:?}: {:02X?}", data.uuid, data.value);
            }
            _ = &mut final_timeout => {
                break;
            }
        }
    }

    device.disconnect().await?;
    
    println!("\n=== Analysis ===");
    println!("If still no response, this device may:");
    println!("  1. Require physical activation (press power button)");
    println!("  2. Need full MiAuth handshake via AVDTP/UPNP channels");
    println!("  3. Be a different type of clone with proprietary protocol");
    println!("\nTry: Turn on the scooter physically, then run this again.");
    
    Ok(())
}
