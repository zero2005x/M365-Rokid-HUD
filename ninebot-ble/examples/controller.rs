use std::io::{self, Write, BufRead};
use std::time::Duration;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::time;
use tokio::sync::mpsc;
use btleplug::api::BDAddr;
use anyhow::Result;
use tracing::Level;
use tracing_subscriber::fmt::format::FmtSpan;
use chrono::{Local, DateTime};

use ninebot_ble::{
    ScooterScanner, ConnectionHelper, LoginRequest, session::MiSession,
    AuthToken, session::TailLight
};

// Data structures for logging
#[derive(Debug, Clone)]
struct ScooterStatus {
    timestamp: DateTime<Local>,
    battery_percent: u16,
    speed_kmh: f32,
    avg_speed_kmh: f32,
    trip_m: i16,
    total_m: u32,
    frame_temp: f32,
    uptime_s: u64,
    voltage: f32,
    current: f32,
    capacity: u16,
    batt_temp_1: u8,
    batt_temp_2: u8,
    range_km: f32,
}

impl ScooterStatus {
    fn to_csv_header() -> &'static str {
        "timestamp,battery_percent,speed_kmh,avg_speed_kmh,trip_m,total_m,frame_temp_c,uptime_s,voltage_v,current_a,capacity_mah,batt_temp_1_c,batt_temp_2_c,range_km"
    }
    
    fn to_csv_row(&self) -> String {
        format!(
            "{},{},{:.1},{:.1},{},{},{:.1},{},{:.2},{:.2},{},{},{},{:.1}",
            self.timestamp.format("%Y-%m-%d %H:%M:%S"),
            self.battery_percent,
            self.speed_kmh,
            self.avg_speed_kmh,
            self.trip_m,
            self.total_m,
            self.frame_temp,
            self.uptime_s,
            self.voltage,
            self.current,
            self.capacity,
            self.batt_temp_1,
            self.batt_temp_2,
            self.range_km
        )
    }
}

enum Command {
    Quit,
    Help,
    Status,
    Cruise(bool),
    TailLight(TailLight),
    Log(bool),
    Interval(u64),
    Unknown(String),
}

fn parse_command(input: &str) -> Command {
    let parts: Vec<&str> = input.trim().split_whitespace().collect();
    if parts.is_empty() {
        return Command::Unknown(String::new());
    }
    
    match parts[0].to_lowercase().as_str() {
        "q" | "quit" | "exit" => Command::Quit,
        "h" | "help" | "?" => Command::Help,
        "s" | "status" => Command::Status,
        "cruise" => {
            if parts.len() > 1 {
                match parts[1].to_lowercase().as_str() {
                    "on" | "1" | "true" => Command::Cruise(true),
                    "off" | "0" | "false" => Command::Cruise(false),
                    _ => Command::Unknown(format!("Invalid cruise value: {}", parts[1]))
                }
            } else {
                Command::Unknown("Usage: cruise <on|off>".to_string())
            }
        }
        "light" | "tail" | "taillight" => {
            if parts.len() > 1 {
                match parts[1].to_lowercase().as_str() {
                    "off" | "0" => Command::TailLight(TailLight::Off),
                    "brake" | "1" => Command::TailLight(TailLight::OnBrake),
                    "on" | "always" | "2" => Command::TailLight(TailLight::Always),
                    _ => Command::Unknown(format!("Invalid light mode: {}. Use: off, brake, always", parts[1]))
                }
            } else {
                Command::Unknown("Usage: light <off|brake|always>".to_string())
            }
        }
        "log" => {
            if parts.len() > 1 {
                match parts[1].to_lowercase().as_str() {
                    "on" | "start" | "1" => Command::Log(true),
                    "off" | "stop" | "0" => Command::Log(false),
                    _ => Command::Unknown(format!("Invalid log value: {}", parts[1]))
                }
            } else {
                Command::Unknown("Usage: log <on|off>".to_string())
            }
        }
        "interval" => {
            if parts.len() > 1 {
                match parts[1].parse::<u64>() {
                    Ok(secs) if secs >= 1 => Command::Interval(secs),
                    _ => Command::Unknown("Interval must be >= 1 second".to_string())
                }
            } else {
                Command::Unknown("Usage: interval <seconds>".to_string())
            }
        }
        _ => Command::Unknown(format!("Unknown command: {}", parts[0]))
    }
}

fn print_help() {
    println!("\n╔══════════════════════════════════════════════════════════════╗");
    println!("║                    Available Commands                        ║");
    println!("╠══════════════════════════════════════════════════════════════╣");
    println!("║  status, s          - Show current status                    ║");
    println!("║  cruise <on|off>    - Enable/disable cruise control          ║");
    println!("║  light <mode>       - Set tail light (off/brake/always)      ║");
    println!("║  log <on|off>       - Start/stop CSV logging                 ║");
    println!("║  interval <secs>    - Set update interval (default: 1s)      ║");
    println!("║  help, h, ?         - Show this help                         ║");
    println!("║  quit, q            - Exit the program                       ║");
    println!("╚══════════════════════════════════════════════════════════════╝\n");
}

async fn read_status(session: &mut MiSession) -> Result<ScooterStatus> {
    let motor = session.motor_info().await?;
    let battery = session.battery_info().await?;
    let range = session.distance_left().await.unwrap_or(0.0);
    
    Ok(ScooterStatus {
        timestamp: Local::now(),
        battery_percent: motor.battery_percent,
        speed_kmh: motor.speed_kmh,
        avg_speed_kmh: motor.speed_average_kmh,
        trip_m: motor.trip_distance_m,
        total_m: motor.total_distance_m,
        frame_temp: motor.frame_temperature,
        uptime_s: motor.uptime.as_secs(),
        voltage: battery.voltage,
        current: battery.current,
        capacity: battery.capacity,
        batt_temp_1: battery.temperature_1,
        batt_temp_2: battery.temperature_2,
        range_km: range,
    })
}

fn print_status(status: &ScooterStatus, logging: bool, interval: u64) {
    print!("\x1B[2J\x1B[1;1H"); // Clear screen
    println!("╔══════════════════════════════════════════════════════════════╗");
    println!("║       M365 Scooter Controller - {}        ║", status.timestamp.format("%Y-%m-%d %H:%M:%S"));
    println!("╠══════════════════════════════════════════════════════════════╣");
    println!("║  🔋 Battery:     {:>3}%          📍 Range:    {:>5.1} km        ║", status.battery_percent, status.range_km);
    println!("║  🚀 Speed:       {:>5.1} km/h     📊 Avg:      {:>5.1} km/h      ║", status.speed_kmh, status.avg_speed_kmh);
    println!("║  📍 Trip:        {:>6} m       🛣️  Total:    {:>6.1} km       ║", status.trip_m, status.total_m as f32 / 1000.0);
    println!("╠══════════════════════════════════════════════════════════════╣");
    println!("║  🔌 Voltage:     {:>5.2} V        ⚡ Current:  {:>5.2} A        ║", status.voltage, status.current);
    println!("║  📦 Capacity:    {:>5} mAh      🌡️  Batt:     {}°C / {}°C      ║", status.capacity, status.batt_temp_1, status.batt_temp_2);
    println!("║  🌡️  Frame:      {:>5.1}°C        ⏱️  Uptime:   {:>5}s          ║", status.frame_temp, status.uptime_s);
    println!("╠══════════════════════════════════════════════════════════════╣");
    println!("║  📝 Logging: {:>3}    ⏰ Interval: {}s    Type 'help' for cmds  ║", 
        if logging { "ON" } else { "OFF" }, interval);
    println!("╚══════════════════════════════════════════════════════════════╝");
    print!("> ");
    io::stdout().flush().unwrap();
}

async fn load_token() -> Result<AuthToken> {
    let path = std::path::Path::new(".mi-token");
    let token = tokio::fs::read(path).await?;
    Ok(token.try_into().expect("Invalid token length"))
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
        eprintln!("Usage: controller <MAC_ADDRESS>");
        eprintln!("Example: controller C7:B8:DC:3B:A1:B2");
        std::process::exit(1);
    }

    let mac = BDAddr::from_str_delim(&args[1]).expect("Invalid MAC address");
    println!("🔍 Searching for scooter: {}", mac);

    // Load token
    let token = load_token().await?;
    println!("🔑 Token loaded");

    // Find and connect
    let mut scanner = ScooterScanner::new().await?;
    let scooter = scanner.wait_for(&mac).await?;
    let device = scanner.peripheral(&scooter).await?;

    println!("📶 Found scooter, connecting...");
    let connection = ConnectionHelper::new(&device);
    connection.reconnect().await?;

    println!("🔐 Logging in...");
    let mut session = login(&device, &token).await?;
    println!("✅ Connected! Type 'help' for available commands.\n");

    // Setup command channel
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<Command>(32);
    let running = Arc::new(AtomicBool::new(true));
    let running_clone = running.clone();

    // Spawn stdin reader thread
    std::thread::spawn(move || {
        let stdin = io::stdin();
        for line in stdin.lock().lines() {
            if let Ok(input) = line {
                let cmd = parse_command(&input);
                if cmd_tx.blocking_send(cmd).is_err() {
                    break;
                }
            }
        }
    });

    // State
    let mut logging = false;
    let mut log_file: Option<std::fs::File> = None;
    let mut interval_secs: u64 = 1;
    let mut last_status: Option<ScooterStatus> = None;

    // Main loop
    let mut interval = time::interval(Duration::from_secs(interval_secs));
    
    while running.load(Ordering::Relaxed) {
        tokio::select! {
            _ = interval.tick() => {
                match read_status(&mut session).await {
                    Ok(status) => {
                        // Log to file if enabled
                        if logging {
                            if let Some(ref mut file) = log_file {
                                writeln!(file, "{}", status.to_csv_row()).ok();
                            }
                        }
                        
                        print_status(&status, logging, interval_secs);
                        last_status = Some(status);
                    }
                    Err(e) => {
                        eprintln!("\n⚠️  Read error: {}. Attempting reconnect...", e);
                        if let Err(re) = connection.reconnect().await {
                            eprintln!("❌ Reconnect failed: {}", re);
                            break;
                        }
                        match login(&device, &token).await {
                            Ok(new_session) => {
                                session = new_session;
                                println!("✅ Reconnected!");
                            }
                            Err(le) => {
                                eprintln!("❌ Re-login failed: {}", le);
                                break;
                            }
                        }
                    }
                }
            }
            
            Some(cmd) = cmd_rx.recv() => {
                match cmd {
                    Command::Quit => {
                        println!("\n👋 Exiting...");
                        running_clone.store(false, Ordering::Relaxed);
                        break;
                    }
                    Command::Help => {
                        print_help();
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                    Command::Status => {
                        if let Some(ref status) = last_status {
                            print_status(status, logging, interval_secs);
                        }
                    }
                    Command::Cruise(on) => {
                        print!("\n⚙️  Setting cruise control {}...", if on { "ON" } else { "OFF" });
                        io::stdout().flush().unwrap();
                        match session.set_cruise(on).await {
                            Ok(_) => println!(" ✅ Done!"),
                            Err(e) => println!(" ❌ Failed: {}", e),
                        }
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                    Command::TailLight(mode) => {
                        print!("\n💡 Setting tail light to {:?}...", mode);
                        io::stdout().flush().unwrap();
                        match session.set_tail_light(mode).await {
                            Ok(_) => println!(" ✅ Done!"),
                            Err(e) => println!(" ❌ Failed: {}", e),
                        }
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                    Command::Log(on) => {
                        if on && !logging {
                            let filename = format!("scooter_log_{}.csv", Local::now().format("%Y%m%d_%H%M%S"));
                            match std::fs::File::create(&filename) {
                                Ok(mut file) => {
                                    writeln!(file, "{}", ScooterStatus::to_csv_header()).ok();
                                    log_file = Some(file);
                                    logging = true;
                                    println!("\n📝 Started logging to: {}", filename);
                                }
                                Err(e) => println!("\n❌ Failed to create log file: {}", e),
                            }
                        } else if !on && logging {
                            log_file = None;
                            logging = false;
                            println!("\n📝 Logging stopped.");
                        }
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                    Command::Interval(secs) => {
                        interval_secs = secs;
                        interval = time::interval(Duration::from_secs(secs));
                        println!("\n⏰ Update interval set to {}s", secs);
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                    Command::Unknown(msg) => {
                        if !msg.is_empty() {
                            println!("\n❓ {}", msg);
                        }
                        print!("> ");
                        io::stdout().flush().unwrap();
                    }
                }
            }
        }
    }

    // Cleanup
    if logging {
        println!("📝 Closing log file...");
    }
    
    println!("🔌 Disconnecting...");
    connection.disconnect().await?;
    println!("👋 Goodbye!");
    
    Ok(())
}
