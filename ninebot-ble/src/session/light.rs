//! Tail light control for M365 scooter
//!
//! Controls the rear/tail light of the scooter with direct on/off commands.
//!
//! ## Protocol Reference
//! - Light On: Write 0x0002 to address 0x7D
//! - Light Off: Write 0x0000 to address 0x7D
//! - Direction: Master to Motor Controller (0x20)
//! - Command Type: Write (0x03)
//!
//! ## Note
//! This module provides simple on/off control. For tail light mode settings
//! (Off, OnBrake, Always), see the `set_tail_light` method in `settings.rs`.

use super::MiSession;
use super::commands::{ScooterCommand, Direction, ReadWrite, Attribute};

use anyhow::Result;

impl MiSession {
    /// Turn on the tail light
    ///
    /// Sets the tail light to always-on mode.
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.light_on().await?;
    /// println!("Tail light is now on!");
    /// # Ok(())
    /// # }
    /// ```
    pub async fn light_on(&mut self) -> Result<()> {
        tracing::debug!("Turning tail light on");

        // Payload: [0x02, 0x00] - Write value 0x0002 (little-endian: LSB first) for "Always" mode
        let payload = vec![0x02, 0x00];

        self.send(&ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::TailLight,
            payload
        }).await?;

        Ok(())
    }

    /// Turn off the tail light
    ///
    /// Disables the tail light completely.
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.light_off().await?;
    /// println!("Tail light is now off!");
    /// # Ok(())
    /// # }
    /// ```
    pub async fn light_off(&mut self) -> Result<()> {
        tracing::debug!("Turning tail light off");

        // Payload: [0x00, 0x00] - Write value 0x0000 (little-endian) for "Off" mode
        let payload = vec![0x00, 0x00];

        self.send(&ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::TailLight,
            payload
        }).await?;

        Ok(())
    }

    /// Set tail light state
    ///
    /// Convenience method to turn the tail light on or off based on a boolean.
    ///
    /// # Arguments
    /// * `on` - `true` to turn on, `false` to turn off
    ///
    /// # Example
    /// ```rust,no_run
    /// # use anyhow::Result;
    /// # async fn example(session: &mut ninebot_ble::session::MiSession) -> Result<()> {
    /// session.set_light(true).await?;  // Turn on
    /// session.set_light(false).await?; // Turn off
    /// # Ok(())
    /// # }
    /// ```
    pub async fn set_light(&mut self, on: bool) -> Result<()> {
        if on {
            self.light_on().await
        } else {
            self.light_off().await
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_light_on_command_bytes() {
        // Verify the ScooterCommand produces correct bytes for light on
        let cmd = ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::TailLight,
            payload: vec![0x02, 0x00]  // 0x0002 little-endian: LSB first
        };

        let bytes = cmd.as_bytes();
        // Expected: Length(4) + Dir(0x20) + Cmd(0x03) + Addr(0x7D) + Payload(0x02, 0x00)
        assert_eq!(bytes[0], 0x04); // length = payload.len() + 2 = 2 + 2 = 4
        assert_eq!(bytes[1], 0x20); // direction: MasterToMotor
        assert_eq!(bytes[2], 0x03); // read_write: Write
        assert_eq!(bytes[3], 0x7D); // attribute: TailLight
        assert_eq!(bytes[4], 0x02); // payload[0] - LSB
        assert_eq!(bytes[5], 0x00); // payload[1] - MSB
    }

    #[test]
    fn test_light_off_command_bytes() {
        // Verify the ScooterCommand produces correct bytes for light off
        let cmd = ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Write,
            attribute: Attribute::TailLight,
            payload: vec![0x00, 0x00]
        };

        let bytes = cmd.as_bytes();
        // Expected: Length(4) + Dir(0x20) + Cmd(0x03) + Addr(0x7D) + Payload(0x00, 0x00)
        assert_eq!(bytes[0], 0x04); // length = payload.len() + 2 = 2 + 2 = 4
        assert_eq!(bytes[1], 0x20); // direction: MasterToMotor
        assert_eq!(bytes[2], 0x03); // read_write: Write
        assert_eq!(bytes[3], 0x7D); // attribute: TailLight
        assert_eq!(bytes[4], 0x00); // payload[0]
        assert_eq!(bytes[5], 0x00); // payload[1]
    }
}
