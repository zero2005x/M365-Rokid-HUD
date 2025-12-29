use std::io::{self, Write};
use std::time::Duration;
use tokio::time;
use btleplug::api::BDAddr;
use anyhow::Result;
use tracing::Level;
use tracing_subscriber::fmt::format::FmtSpan;

use ninebot_ble::{
    ScooterScanner, ConnectionHelper, LoginRequest, session::MiSession,
    AuthToken
};

async fn load_token() -> Result<AuthToken> {
    let path = std::path::Path::new(".mi-token");
    let token = tokio::fs::read(path).await?;
    Ok(token.try_into().expect("Invalid token length"))
}

async fn print_status(session: &mut MiSession) -> Result<()> {
    // Clear line and print header
    print!("\x1B[2J\x1B[1;1H"); // Clear screen
    println!("╔══════════════════════════════════════════════════════════════╗");
    println!("║           M365 Scooter Live Monitor                          ║");
    println!("╠══════════════════════════════════════════════════════════════╣");

    // Read motor info
    match session.motor_info().await {
        Ok(info) => {
            println!("║  🔋 Battery:     {:>3}%                                        ║", info.battery_percent);
            println!("║  🚀 Speed:       {:>5.1} km/h                                   ║", info.speed_kmh);
            println!("║  📊 Avg Speed:   {:>5.1} km/h                                   ║", info.speed_average_kmh);
            println!("║  📍 Trip:        {:>7} m                                    ║", info.trip_distance_m);
            println!("║  🛣️  Total:       {:>7} m ({:.1} km)                       ║", 
                info.total_distance_m, info.total_distance_m as f32 / 1000.0);
            println!("║  🌡️  Temp:        {:>5.1}°C                                     ║", info.frame_temperature);
            println!("║  ⏱️  Uptime:      {:?}                                    ║", info.uptime);
        }
        Err(e) => {
            println!("║  ⚠️  Motor info error: {:?}                              ║", e);
        }
    }

    println!("╠══════════════════════════════════════════════════════════════╣");

    // Read battery info
    match session.battery_info().await {
        Ok(info) => {
            println!("║  🔌 Voltage:     {:>5.2} V                                     ║", info.voltage);
            println!("║  ⚡ Current:     {:>5.2} A                                     ║", info.current);
            println!("║  📦 Capacity:    {:>5} mAh                                   ║", info.capacity);
            println!("║  🌡️  Batt Temp:   {}°C / {}°C                                  ║", info.temperature_1, info.temperature_2);
        }
        Err(e) => {
            println!("║  ⚠️  Battery info error: {:?}                            ║", e);
        }
    }

    println!("╠══════════════════════════════════════════════════════════════╣");

    // Read distance left
    match session.distance_left().await {
        Ok(km) => {
            println!("║  📍 Range Left:  {:>5.1} km                                    ║", km);
        }
        Err(_) => {}
    }

    println!("╠══════════════════════════════════════════════════════════════╣");
    println!("║  Press Ctrl+C to exit                                        ║");
    println!("╚══════════════════════════════════════════════════════════════╝");

    io::stdout().flush()?;
    Ok(())
}

async fn login(device: &btleplug::platform::Peripheral, token: &AuthToken) -> Result<MiSession> {
    let mut login = LoginRequest::new(device, token).await?;
    let session = login.start().await?;
    Ok(session)
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(Level::WARN)
        .with_span_events(FmtSpan::CLOSE)
        .init();

    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        panic!("Usage: monitor <MAC_ADDRESS>");
    }

    let mac = BDAddr::from_str_delim(&args[1]).expect("Invalid MAC address");
    println!("🔍 Searching for scooter: {}", mac);

    // Load token
    let token = load_token().await?;
    println!("🔑 Token loaded");

    // Find and connect to scooter
    let mut scanner = ScooterScanner::new().await?;
    let scooter = scanner.wait_for(&mac).await?;
    let device = scanner.peripheral(&scooter).await?;

    println!("📶 Found scooter, connecting...");

    let connection = ConnectionHelper::new(&device);
    connection.reconnect().await?;

    println!("🔐 Logging in...");

    // Login - returns MiSession directly
    let mut session = login(&device, &token).await?;

    println!("✅ Logged in! Starting monitor...");
    time::sleep(Duration::from_millis(500)).await;

    // Main loop - read data every second
    let mut interval = time::interval(Duration::from_secs(1));
    
    loop {
        interval.tick().await;
        
        if let Err(e) = print_status(&mut session).await {
            eprintln!("Error reading status: {}", e);
            
            // Try to reconnect
            println!("🔄 Attempting to reconnect...");
            if let Err(e) = connection.reconnect().await {
                eprintln!("❌ Reconnection failed: {}", e);
                break;
            }
            
            // Re-login
            match login(&device, &token).await {
                Ok(new_session) => {
                    session = new_session;
                    println!("✅ Reconnected!");
                }
                Err(e) => {
                    eprintln!("❌ Re-login failed: {}", e);
                    break;
                }
            }
        }
    }

    println!("👋 Disconnecting...");
    connection.disconnect().await?;
    
    Ok(())
}
