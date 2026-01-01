//! Lock/Unlock control for M365 scooter
//!
//! Provides motor lock and unlock functionality.
//! When locked, the scooter motor is disabled and cannot be used.
//!
//! ## Protocol Reference
//! - Lock: Write 0x0001 to address 0x70
//! - Unlock: Write 0x0001 to address 0x71
//! - Direction: Master to Motor Controller (0x20)
//! - Command Type: Write (0x03)

use super::MiSession;
use super::commands::{ScooterCommand, Direction, ReadWrite, Attribute};

use anyhow::Result;

impl MiSession {
    /// Lock the scooter motor
    ///
    /// When locked, the scooter cannot be ridden. The motor controller
    /// will ignore throttle input until unlocked.
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.lock().await?;
    /// println!("Scooter locked!");
    /// # Ok(())
    /// # }
    /// ```
    pub async fn lock(&mut self) -> Result<()> {
        tracing::debug!("Locking scooter motor");

        // Payload: [0x01, 0x00] - Write value 0x0001 (little-endian: LSB first)
        let payload = vec![0x01, 0x00];

        self.send(&ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::Lock,
            payload
        }).await?;

        Ok(())
    }

    /// Unlock the scooter motor
    ///
    /// Re-enables the motor after being locked. The scooter can be
    /// ridden normally after unlocking.
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.unlock().await?;
    /// println!("Scooter unlocked!");
    /// # Ok(())
    /// # }
    /// ```
    pub async fn unlock(&mut self) -> Result<()> {
        tracing::debug!("Unlocking scooter motor");

        // Payload: [0x01, 0x00] - Write value 0x0001 (little-endian: LSB first)
        let payload = vec![0x01, 0x00];

        self.send(&ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::Unlock,
            payload
        }).await?;

        Ok(())
    }

    /// Set scooter lock state
    ///
    /// Convenience method to lock or unlock the scooter based on a boolean.
    ///
    /// # Arguments
    /// * `locked` - `true` to lock, `false` to unlock
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.set_lock(true).await?;  // Lock the scooter
    /// session.set_lock(false).await?; // Unlock the scooter
    /// # Ok(())
    /// # }
    /// ```
    pub async fn set_lock(&mut self, locked: bool) -> Result<()> {
        if locked {
            self.lock().await
        } else {
            self.unlock().await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lock_command_bytes() {
        // Verify the ScooterCommand produces correct bytes for lock
        let cmd = ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::Lock,
            payload: vec![0x01, 0x00]  // 0x0001 little-endian: LSB first
        };

        let bytes = cmd.as_bytes();
        // Expected: Length(4) + Dir(0x20) + Cmd(0x03) + Addr(0x70) + Payload(0x01, 0x00)
        assert_eq!(bytes[0], 0x04); // length = payload.len() + 2 = 2 + 2 = 4
        assert_eq!(bytes[1], 0x20); // direction: MasterToMotor
        assert_eq!(bytes[2], 0x03); // read_write: Write
        assert_eq!(bytes[3], 0x70); // attribute: Lock
        assert_eq!(bytes[4], 0x01); // payload[0] - LSB
        assert_eq!(bytes[5], 0x00); // payload[1] - MSB
    }

    #[test]
    fn test_unlock_command_bytes() {
        // Verify the ScooterCommand produces correct bytes for unlock
        let cmd = ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::Unlock,
            payload: vec![0x01, 0x00]  // 0x0001 little-endian: LSB first
        };

        let bytes = cmd.as_bytes();
        // Expected: Length(4) + Dir(0x20) + Cmd(0x03) + Addr(0x71) + Payload(0x01, 0x00)
        assert_eq!(bytes[0], 0x04); // length = payload.len() + 2 = 2 + 2 = 4
        assert_eq!(bytes[1], 0x20); // direction: MasterToMotor
        assert_eq!(bytes[2], 0x03); // read_write: Write
        assert_eq!(bytes[3], 0x71); // attribute: Unlock
        assert_eq!(bytes[4], 0x01); // payload[0] - LSB
        assert_eq!(bytes[5], 0x00); // payload[1] - MSB
    }
}
