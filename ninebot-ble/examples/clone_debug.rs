use std::error::Error;
use std::time::Duration;
use tokio::time;
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter, CharPropFlags, WriteType};
use btleplug::platform::Manager;
use uuid::Uuid;
use futures::stream::StreamExt;
use futures::FutureExt;

// Known UUIDs from your device
const UPNP_UUID: Uuid = Uuid::from_u128(0x00000010_0000_1000_8000_00805f9b34fb);
const AVDTP_UUID: Uuid = Uuid::from_u128(0x00000019_0000_1000_8000_00805f9b34fb);
const NUS_TX_UUID: Uuid = Uuid::from_u128(0x6e400002_b5a3_f393_e0a9_e50e24dcca9e);
const NUS_RX_UUID: Uuid = Uuid::from_u128(0x6e400003_b5a3_f393_e0a9_e50e24dcca9e);

fn calculate_checksum(data: &[u8]) -> u16 {
    let sum: u32 = data.iter().map(|&b| b as u32).sum();
    ((sum ^ 0xFFFF) & 0xFFFF) as u16
}

fn build_xiaomi_packet(payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0x55, 0xAA];
    packet.extend_from_slice(payload);
    let checksum = calculate_checksum(payload);
    packet.push((checksum & 0xFF) as u8);
    packet.push((checksum >> 8) as u8);
    packet
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt::init();

    let target_mac = std::env::args().nth(1).expect("Please provide MAC address");
    println!("=== Clone Controller Debug Tool ===");
    println!("Target MAC: {}", target_mac);

    let manager = Manager::new().await?;
    let adapters = manager.adapters().await?;
    let central = adapters.into_iter().nth(0).expect("No Bluetooth adapters found");

    println!("\n[1] Scanning for device...");
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
        .expect("Scooter not found");

    println!("[2] Connecting...");
    device.connect().await?;
    time::sleep(Duration::from_secs(2)).await;

    println!("[3] Discovering services...");
    device.discover_services().await?;

    let chars = device.characteristics();
    
    // Find our target characteristics
    let upnp = chars.iter().find(|c| c.uuid == UPNP_UUID);
    let avdtp = chars.iter().find(|c| c.uuid == AVDTP_UUID);
    let nus_tx = chars.iter().find(|c| c.uuid == NUS_TX_UUID);
    let nus_rx = chars.iter().find(|c| c.uuid == NUS_RX_UUID);

    println!("\n=== Characteristic Status ===");
    println!("UPNP (0x10):   {}", if upnp.is_some() { "Found ✓" } else { "Missing ✗" });
    println!("AVDTP (0x19):  {}", if avdtp.is_some() { "Found ✓" } else { "Missing ✗" });
    println!("NUS TX:        {}", if nus_tx.is_some() { "Found ✓" } else { "Missing ✗" });
    println!("NUS RX:        {}", if nus_rx.is_some() { "Found ✓" } else { "Missing ✗" });

    // Subscribe to ALL notification characteristics
    println!("\n[4] Subscribing to notifications...");
    let notify_chars: Vec<_> = chars.iter()
        .filter(|c| c.properties.contains(CharPropFlags::NOTIFY))
        .collect();

    for c in &notify_chars {
        match device.subscribe(c).await {
            Ok(_) => println!("  Subscribed: {:?}", c.uuid),
            Err(e) => println!("  Failed {:?}: {}", c.uuid, e),
        }
    }

    let mut notification_stream = device.notifications().await?;

    // Test commands with correct checksums
    println!("\n=== Testing Communication ===");

    // Xiaomi protocol commands
    let commands: Vec<(&str, Vec<u8>)> = vec![
        // Read Serial Number: Dev=0x20 (master->scooter), Cmd=0x01 (read), Attr=0x10, Len=0x16
        ("Get Serial", build_xiaomi_packet(&[0x03, 0x20, 0x01, 0x10, 0x16])),
        
        // Read Firmware Version: Dev=0x20, Cmd=0x01, Attr=0x1A, Len=0x02
        ("Get Version", build_xiaomi_packet(&[0x03, 0x20, 0x01, 0x1A, 0x02])),
        
        // Read Battery %: Dev=0x20, Cmd=0x01, Attr=0x32, Len=0x02
        ("Get Battery", build_xiaomi_packet(&[0x03, 0x20, 0x01, 0x32, 0x02])),
        
        // Simple ping - some clones respond to this
        ("Ping", vec![0x55, 0xAA, 0x00, 0x00, 0xFF, 0xFF]),
    ];

    // Strategy 1: Use NUS TX -> NUS RX pair
    if let Some(tx) = nus_tx {
        println!("\n--- Strategy 1: NUS (TX=6e400002 -> RX=6e400003) ---");
        for (name, cmd) in &commands {
            println!("Sending {}: {:02X?}", name, cmd);
            
            let write_type = if tx.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) {
                WriteType::WithoutResponse
            } else {
                WriteType::WithResponse
            };
            
            if let Err(e) = device.write(tx, cmd, write_type).await {
                println!("  Write failed: {}", e);
                continue;
            }

            // Wait for response
            let timeout = time::sleep(Duration::from_millis(1500));
            tokio::pin!(timeout);

            loop {
                tokio::select! {
                    Some(data) = notification_stream.next() => {
                        println!("  ✓ Response from {:?}: {:02X?}", data.uuid, data.value);
                    }
                    _ = &mut timeout => {
                        println!("  (no response)");
                        break;
                    }
                }
            }
        }
    }

    // Strategy 2: Use UPNP (0x10) - bidirectional
    if let Some(upnp_char) = upnp {
        println!("\n--- Strategy 2: UPNP (0x10) bidirectional ---");
        for (name, cmd) in &commands {
            println!("Sending {}: {:02X?}", name, cmd);
            
            if let Err(e) = device.write(upnp_char, cmd, WriteType::WithoutResponse).await {
                println!("  Write failed: {}", e);
                continue;
            }

            let timeout = time::sleep(Duration::from_millis(1500));
            tokio::pin!(timeout);

            loop {
                tokio::select! {
                    Some(data) = notification_stream.next() => {
                        println!("  ✓ Response from {:?}: {:02X?}", data.uuid, data.value);
                    }
                    _ = &mut timeout => {
                        println!("  (no response)");
                        break;
                    }
                }
            }
        }
    }

    // Strategy 3: Use AVDTP (0x19) - bidirectional
    if let Some(avdtp_char) = avdtp {
        println!("\n--- Strategy 3: AVDTP (0x19) bidirectional ---");
        for (name, cmd) in &commands {
            println!("Sending {}: {:02X?}", name, cmd);
            
            if let Err(e) = device.write(avdtp_char, cmd, WriteType::WithoutResponse).await {
                println!("  Write failed: {}", e);
                continue;
            }

            let timeout = time::sleep(Duration::from_millis(1500));
            tokio::pin!(timeout);

            loop {
                tokio::select! {
                    Some(data) = notification_stream.next() => {
                        println!("  ✓ Response from {:?}: {:02X?}", data.uuid, data.value);
                    }
                    _ = &mut timeout => {
                        println!("  (no response)");
                        break;
                    }
                }
            }
        }
    }

    // Final wait for any delayed responses
    println!("\n[5] Waiting for delayed responses (5s)...");
    let final_timeout = time::sleep(Duration::from_secs(5));
    tokio::pin!(final_timeout);

    loop {
        tokio::select! {
            Some(data) = notification_stream.next() => {
                println!("  Late response from {:?}: {:02X?}", data.uuid, data.value);
            }
            _ = &mut final_timeout => {
                break;
            }
        }
    }

    println!("\n[6] Disconnecting...");
    device.disconnect().await?;
    
    println!("\n=== Debug Complete ===");
    println!("If no responses were received, possible causes:");
    println!("  1. Scooter is in sleep mode - try turning it on physically");
    println!("  2. Need MiAuth authentication first (unlikely for clones)");
    println!("  3. This clone uses a proprietary protocol");
    println!("  4. The scooter was previously paired to another device");
    
    Ok(())
}
