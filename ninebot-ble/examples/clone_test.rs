use std::error::Error;
use std::time::Duration;
use tokio::time;
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter, CharPropFlags};
use btleplug::platform::Manager;
// use uuid::Uuid;
use futures::stream::StreamExt;
use futures::FutureExt; // Import FutureExt for now_or_never

// Nordic UART Service UUIDs (Commonly used by clones)
// const UART_SERVICE_UUID: Uuid = Uuid::from_u128(0x6e400001_b5a3_f393_e0a9_e50e24dcca9e);
// const UART_RX_CHAR_UUID: Uuid = Uuid::from_u128(0x6e400003_b5a3_f393_e0a9_e50e24dcca9e); // Notify
// const UART_TX_CHAR_UUID: Uuid = Uuid::from_u128(0x6e400002_b5a3_f393_e0a9_e50e24dcca9e); // Write

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt::init();

    let target_mac = std::env::args().nth(1).expect("Please provide MAC address");
    println!("Looking for clone scooter with MAC: {}", target_mac);

    let manager = Manager::new().await?;
    let adapters = manager.adapters().await?;
    let central = adapters.into_iter().nth(0).expect("No Bluetooth adapters found");

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

    println!("Found device, connecting...");
    device.connect().await?;
    println!("Connected! Waiting for stabilization...");
    time::sleep(Duration::from_secs(2)).await;

    println!("Discovering services...");
    device.discover_services().await?;

    let chars = device.characteristics();
    for c in &chars {
        println!("Found char: UUID={:?}, Props={:?}", c.uuid, c.properties);
    }

    // Try to find ANY notify char and subscribe
    let notify_chars: Vec<_> = chars.iter()
        .filter(|c| c.properties.contains(CharPropFlags::NOTIFY) || c.properties.contains(CharPropFlags::INDICATE))
        .collect();

    for c in &notify_chars {
        println!("Subscribing to {:?}...", c.uuid);
        if let Err(e) = device.subscribe(c).await {
            println!("Failed to subscribe to {:?}: {}", c.uuid, e);
        }
    }

    let mut notification_stream = device.notifications().await?;

    // Try Xbot "Z" Unlock
    let cmd_unlock_z = vec![0x5A];
    let cmd_unlock_z_crlf = vec![0x5A, 0x0D, 0x0A];
    let cmd_lenzod = vec![0xA6, 0x12, 0x02, 0x10, 0x14];
    let cmd_version = vec![0x55, 0xAA, 0x03, 0x20, 0x01, 0x1A, 0x02, 0xBF, 0xFF];

    let commands = vec![
        ("Xbot 'Z'", cmd_unlock_z),
        ("Xbot 'Z+CRLF'", cmd_unlock_z_crlf),
        ("Lenzod", cmd_lenzod),
        ("Version", cmd_version),
    ];

    let write_chars: Vec<_> = chars.iter()
        .filter(|c| c.properties.contains(CharPropFlags::WRITE) || c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE))
        .collect();

    for c in &write_chars {
        for (name, cmd) in &commands {
            println!("Sending {} ({:02X?}) to {:?}...", name, cmd, c.uuid);
            if let Err(e) = device.write(c, cmd, btleplug::api::WriteType::WithoutResponse).await {
                 println!("Failed to write to {:?}: {}", c.uuid, e);
            }
            // Wait for response or timeout while listening
            let sleep = time::sleep(Duration::from_millis(1000));
            tokio::pin!(sleep);

            loop {
                tokio::select! {
                    Some(data) = notification_stream.next() => {
                        println!("Received data: {:02X?} (Triggered by {})", data.value, name);
                    }
                    _ = &mut sleep => {
                        break;
                    }
                }
            }
        }
    }

    println!("Waiting for any late responses...");
    let timeout = time::sleep(Duration::from_secs(5));
    tokio::pin!(timeout);

    loop {
        tokio::select! {
            Some(data) = notification_stream.next() => {
                println!("Received data: {:02X?}", data.value);
            }
            _ = &mut timeout => {
                println!("Timeout waiting for response.");
                break;
            }
        }
    }

    println!("Disconnecting...");
    device.disconnect().await?;
    Ok(())
}
