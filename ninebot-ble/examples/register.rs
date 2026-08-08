use tracing::Level;
use tracing_subscriber::fmt::format::FmtSpan;

use btleplug::platform::{Peripheral};
use btleplug::api::BDAddr;
use tokio::io::{BufWriter, AsyncWriteExt};
use tokio::fs::File;
use std::env;
use tracing_subscriber;
use std::path::Path;
use anyhow::Result;

use ninebot_ble::{
  ScooterScanner, ScannerEvent,
  RegistrationRequest, RegistrationError,
  ConnectionHelper, AuthToken
};

/// Creates the token file with owner-only permissions where the platform
/// supports it.
///
/// `File::create` would use the default 0o666 & umask (typically 0o644), which
/// leaves this credential world-readable.
#[cfg(unix)]
async fn create_token_file(path: &Path) -> Result<File> {
  use std::os::unix::fs::OpenOptionsExt;

  let f = tokio::fs::OpenOptions::new()
    .write(true)
    .create(true)
    .truncate(true)
    .mode(0o600)
    .open(path)
    .await?;

  Ok(f)
}

#[cfg(not(unix))]
async fn create_token_file(path: &Path) -> Result<File> {
  Ok(File::create(path).await?)
}

async fn save_token(token : &AuthToken) -> Result<()> {
  let path = Path::new(".mi-token");
  // Never log the token itself: it is the credential that grants authenticated
  // access to the scooter, and logs are frequently collected/shared.
  tracing::info!("Saving token at {:?}", path);
  let f = create_token_file(path).await?;
  {
    let mut writer = BufWriter::new(f);
    writer.write(token).await?;
    writer.flush().await?;
  }
  Ok(())
}

async fn register(device: &Peripheral) -> Result<()> {
  let mut connection = ConnectionHelper::new(&device);
  let mut retry_count = 0;
  const MAX_RETRIES: u32 = 5;

  loop {
    if retry_count >= MAX_RETRIES {
      tracing::error!("Max retries ({}) reached. Please restart the program and try again.", MAX_RETRIES);
      tracing::info!("TIP: Make sure to press the scooter power button within 5 seconds after you hear the beep!");
      return Err(anyhow::anyhow!("Registration failed after {} retries", MAX_RETRIES));
    }

    tracing::info!(">>> Press power button within 5 seconds after you hear the beep! (Attempt {}/{})", retry_count + 1, MAX_RETRIES);
    
    // Longer wait on retry to let Windows BLE driver fully reset
    if retry_count > 0 {
      tracing::info!("Waiting 8 seconds before retry to stabilize BLE connection...");
      tokio::time::sleep(std::time::Duration::from_secs(8)).await;
    }
    
    connection.reconnect().await?;
    
    // Add a small delay after reconnection to ensure stability
    tokio::time::sleep(std::time::Duration::from_secs(2)).await;

    let mut request = match RegistrationRequest::new(&device).await {
        Ok(req) => req,
        Err(e) => {
            tracing::error!("Failed to create registration request: {}", e);
            retry_count += 1;
            continue;
        }
    };

    match request.start().await {
      Ok(token) => {
        tracing::info!("✅ Registration successful!");
        save_token(&token).await?;
        break;
      },
      Err(RegistrationError::RestartNeeded) => {
        tracing::warn!("⚠️ Timeout - Did you press the power button after the beep?");
        retry_count += 1;
        continue;
      },
      Err(e) => {
        tracing::error!("Unhandled error: {}", e);
        retry_count += 1;
      }
    }
  }

  Ok(())
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
  tracing_subscriber::fmt()
    .with_max_level(Level::DEBUG)
    .with_span_events(FmtSpan::CLOSE)
    .init();

  let args: Vec<String> = env::args().collect();
  if args.len() < 2 || args[1].is_empty() {
    panic!("First argument is scooter mac address");
  }

  let mac = BDAddr::from_str_delim(&args[1]).expect("Invalid mac address");
  tracing::info!("Searching scooter with address: {}", mac);

  let mut scanner = ScooterScanner::new().await?;
  let mut rx = scanner.start().await?;

  while let Some(event) = rx.recv().await {
    match event {
      ScannerEvent::DiscoveredScooter(scooter) => {
        if scooter.addr == mac {
          tracing::info!("Found your scooter, starting registration");
          let device = scanner.peripheral(&scooter).await?;
          register(&device).await?;
          break;
        } else {
          tracing::info!(
            "Found scooter nearby: {} with mac: {}",
            scooter.name.as_deref().unwrap_or("Unknown"),
            scooter.addr
          );
        }
      }
    }
  }

  Ok(())
}
