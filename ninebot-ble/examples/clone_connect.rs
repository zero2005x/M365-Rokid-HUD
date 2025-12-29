use std::error::Error;
use std::time::Duration;
use tokio::time;
use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter};
use btleplug::platform::Manager;
use ninebot_ble::clone_connection::ScooterConnection;
use futures::FutureExt;

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

    // Force non-M365 to use Nordic UART
    let is_m365 = false;
    println!("Forced non-M365 mode (Nordic UART): {}", is_m365);

    println!("Found device, connecting...");
    
    let connection = ScooterConnection::connect(&device, is_m365).await?;
    println!("Connected and subscribed!");

    // Skip unlock for M365 clones (direct protocol works)
    if !is_m365 {
        println!("Sending Unlock commands (non-M365)...");
        let _ = connection.send_command(&[0x5A]).await; // Xbot Z
        time::sleep(Duration::from_millis(500)).await;
        let _ = connection.send_command(&[0xA6, 0x12, 0x02, 0x10, 0x14]).await; // Lenzod
        time::sleep(Duration::from_millis(500)).await;
    }

    println!("Reading Firmware Version...");
    match connection.get_version().await {
        Ok(data) => println!("Version Data: {:02X?}", data),
        Err(e) => println!("Failed to read version: {}", e),
    }

    println!("Reading Battery Level...");
    match connection.get_battery_level().await {
        Ok(level) => println!("Battery Level: {}%", level),
        Err(e) => println!("Failed to read battery: {}", e),
    }

    println!("Disconnecting...");
    device.disconnect().await?;
    Ok(())
}
