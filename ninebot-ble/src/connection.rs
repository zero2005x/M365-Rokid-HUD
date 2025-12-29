use btleplug::platform::{Peripheral};
use btleplug::api::{Peripheral as _};
use anyhow::Result;
use tokio::time;
use std::time::Duration;

// Windows BLE needs longer stabilization time after connection
#[cfg(target_os = "windows")]
const POST_CONNECT_DELAY_MS: u64 = 3000;
#[cfg(not(target_os = "windows"))]
const POST_CONNECT_DELAY_MS: u64 = 1000;

// Windows BLE needs longer delay between disconnect and reconnect
#[cfg(target_os = "windows")]
const RECONNECT_DELAY_SECS: u64 = 8;
#[cfg(not(target_os = "windows"))]
const RECONNECT_DELAY_SECS: u64 = 3;

pub struct ConnectionHelper {
  device: Peripheral
}

impl ConnectionHelper {
  pub fn new(device: &Peripheral) -> Self {
    Self { device: device.clone() }
  }

  /// Check if the device is actually connected and stable
  pub async fn is_stable_connected(&self) -> Result<bool, btleplug::Error> {
    // First check: is_connected()
    if !self.device.is_connected().await? {
      return Ok(false);
    }
    
    // On Windows, double-check after a short delay
    #[cfg(target_os = "windows")]
    {
      time::sleep(Duration::from_millis(100)).await;
      if !self.device.is_connected().await? {
        return Ok(false);
      }
    }
    
    Ok(true)
  }

  pub async fn connect(&self) -> Result<bool, btleplug::Error> {
    tracing::debug!("Connecting to device.");
    let mut retries = 5;
    while retries >= 0 {
      if self.is_stable_connected().await? {
        tracing::debug!("Connected to device");
        // Extra stabilization delay for Windows
        time::sleep(Duration::from_millis(POST_CONNECT_DELAY_MS)).await;
        // Verify still connected after delay
        if self.is_stable_connected().await? {
          tracing::debug!("Connection stable");
          return Ok(true);
        } else {
          tracing::debug!("Connection dropped after stabilization delay");
        }
      }
      match self.device.connect().await {
        Ok(_) => {
          // Wait for connection to stabilize
          time::sleep(Duration::from_millis(POST_CONNECT_DELAY_MS)).await;
          if self.is_stable_connected().await? {
            tracing::debug!("Connected to device");
            // Additional stabilization for Windows
            #[cfg(target_os = "windows")]
            time::sleep(Duration::from_millis(1000)).await;
            return Ok(true);
          } else {
            tracing::debug!("Connect call succeeded but device is not connected");
            retries -= 1;
            if retries > 0 {
              time::sleep(Duration::from_secs(2)).await;
            }
          }
        },
        Err(err) if retries > 0 => {
          retries -= 1;
          tracing::debug!("Retrying connection: {} retries left, reason: {}", retries, err);
          time::sleep(Duration::from_secs(2)).await;
        },

        Err(err) => return Err(err)
      }
    }

    Ok(true)
  }

  pub async fn disconnect(&self) -> Result<bool> {
    // Check multiple times on Windows due to connection state instability
    let mut actually_connected = false;
    for _ in 0..3 {
      if self.device.is_connected().await? {
        actually_connected = true;
        break;
      }
      time::sleep(Duration::from_millis(100)).await;
    }
    
    if !actually_connected {
      tracing::debug!("Already disconnected.");
      return Ok(true);
    }

    if let Err(error) = self.device.disconnect().await {
      tracing::error!("Could not disconnect: {}", error);
      return Ok(false)
    }

    // Wait for disconnect to complete on Windows
    #[cfg(target_os = "windows")]
    {
      time::sleep(Duration::from_millis(500)).await;
      // Force wait until actually disconnected
      let mut wait_count = 0;
      while self.device.is_connected().await.unwrap_or(false) && wait_count < 10 {
        time::sleep(Duration::from_millis(200)).await;
        wait_count += 1;
      }
    }

    tracing::debug!("Disconnected from device");
    Ok(true)
  }

  pub async fn reconnect(&self) -> Result<bool> {
    tracing::debug!("Reconnecting...");
    self.disconnect().await?;
    
    // Windows BLE driver needs significant time between disconnect and reconnect
    tracing::debug!("Waiting {}s before reconnecting (Windows BLE stabilization)...", RECONNECT_DELAY_SECS);
    time::sleep(Duration::from_secs(RECONNECT_DELAY_SECS)).await;
    
    self.connect().await?;
    Ok(true)
  }
}
