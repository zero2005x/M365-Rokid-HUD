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

/// Adaptive reconnection delay configuration with exponential backoff.
/// Implements progressive delay strategy to avoid overwhelming the BLE stack.
pub struct AdaptiveReconnect {
  base_delay_ms: u64,
  max_delay_ms: u64,
  current_delay_ms: u64,
  consecutive_failures: u32,
}

impl Default for AdaptiveReconnect {
  fn default() -> Self {
    Self::new()
  }
}

impl AdaptiveReconnect {
  /// Create a new AdaptiveReconnect with default settings.
  /// Base delay: 1s, Max delay: 30s
  pub fn new() -> Self {
    Self {
      base_delay_ms: 1000,
      max_delay_ms: 30000,
      current_delay_ms: 1000,
      consecutive_failures: 0,
    }
  }
  
  /// Create with custom delay settings.
  pub fn with_delays(base_ms: u64, max_ms: u64) -> Self {
    Self {
      base_delay_ms: base_ms,
      max_delay_ms: max_ms,
      current_delay_ms: base_ms,
      consecutive_failures: 0,
    }
  }
  
  /// Get the next delay duration using exponential backoff.
  pub fn next_delay(&mut self) -> Duration {
    let delay = self.current_delay_ms;
    // Exponential backoff: double the delay each time, up to max
    self.current_delay_ms = (self.current_delay_ms * 2).min(self.max_delay_ms);
    self.consecutive_failures += 1;
    tracing::debug!(
      "AdaptiveReconnect: next delay = {}ms, failures = {}", 
      delay, 
      self.consecutive_failures
    );
    Duration::from_millis(delay)
  }
  
  /// Reset the delay to base after a successful connection.
  pub fn reset(&mut self) {
    tracing::debug!(
      "AdaptiveReconnect: resetting after {} failures", 
      self.consecutive_failures
    );
    self.current_delay_ms = self.base_delay_ms;
    self.consecutive_failures = 0;
  }
  
  /// Get the current failure count.
  pub fn failure_count(&self) -> u32 {
    self.consecutive_failures
  }
  
  /// Check if we should give up (too many consecutive failures).
  pub fn should_give_up(&self, max_failures: u32) -> bool {
    self.consecutive_failures >= max_failures
  }
}

pub struct ConnectionHelper {
  device: Peripheral,
  adaptive_reconnect: AdaptiveReconnect,
}

impl ConnectionHelper {
  pub fn new(device: &Peripheral) -> Self {
    Self { 
      device: device.clone(),
      adaptive_reconnect: AdaptiveReconnect::new(),
    }
  }
  
  /// Create with custom adaptive reconnect settings.
  pub fn with_adaptive_reconnect(device: &Peripheral, base_delay_ms: u64, max_delay_ms: u64) -> Self {
    Self {
      device: device.clone(),
      adaptive_reconnect: AdaptiveReconnect::with_delays(base_delay_ms, max_delay_ms),
    }
  }
  
  /// Reset adaptive reconnect counter after successful operations.
  pub fn reset_reconnect_counter(&mut self) {
    self.adaptive_reconnect.reset();
  }
  
  /// Get current failure count.
  pub fn failure_count(&self) -> u32 {
    self.adaptive_reconnect.failure_count()
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

  pub async fn reconnect(&mut self) -> Result<bool> {
    tracing::debug!("Reconnecting...");
    self.disconnect().await?;
    
    // Use adaptive delay for reconnection
    let adaptive_delay = self.adaptive_reconnect.next_delay();
    let base_delay = Duration::from_secs(RECONNECT_DELAY_SECS);
    
    // Use the larger of adaptive delay or platform-specific minimum
    let actual_delay = adaptive_delay.max(base_delay);
    
    tracing::debug!(
      "Waiting {:?} before reconnecting (adaptive: {:?}, platform min: {:?}, failures: {})...", 
      actual_delay,
      adaptive_delay,
      base_delay,
      self.adaptive_reconnect.failure_count()
    );
    time::sleep(actual_delay).await;
    
    match self.connect().await {
      Ok(true) => {
        // Reset adaptive reconnect on successful connection
        self.adaptive_reconnect.reset();
        Ok(true)
      },
      Ok(false) => Ok(false),
      Err(e) => Err(e.into()),
    }
  }
  
  /// Reconnect with a maximum failure limit.
  /// Returns Err if max failures reached.
  pub async fn reconnect_with_limit(&mut self, max_failures: u32) -> Result<bool> {
    if self.adaptive_reconnect.should_give_up(max_failures) {
      tracing::error!(
        "AdaptiveReconnect: giving up after {} consecutive failures (max: {})",
        self.adaptive_reconnect.failure_count(),
        max_failures
      );
      return Err(anyhow::anyhow!("Max reconnection attempts exceeded"));
    }
    
    self.reconnect().await
  }
}
