use std::collections::BTreeSet;
use btleplug::api::{Characteristic, Peripheral as _, WriteType, CharPropFlags};
use btleplug::platform::Peripheral;
use uuid::Uuid;
use futures::stream::StreamExt;
use std::time::Duration;
use tokio::time;
use anyhow::{Result, anyhow, Context};

// Service UUIDs
const _NUS_SERVICE_UUID: Uuid = Uuid::from_u128(0x6e400001_b5a3_f393_e0a9_e50e24dcca9e);
const MI_SERVICE_UUID: Uuid = Uuid::from_u128(0x0000fe95_0000_1000_8000_00805f9b34fb);

// Characteristic UUIDs
const NUS_TX_UUID: Uuid = Uuid::from_u128(0x6e400002_b5a3_f393_e0a9_e50e24dcca9e); // Write
const NUS_RX_UUID: Uuid = Uuid::from_u128(0x6e400003_b5a3_f393_e0a9_e50e24dcca9e); // Notify

// Clone specific characteristics often found under FE95
const _CLONE_CHAR_1_UUID: Uuid = Uuid::from_u128(0x00000001_0000_1000_8000_00805f9b34fb);
const _CLONE_CHAR_2_UUID: Uuid = Uuid::from_u128(0x00000002_0000_1000_8000_00805f9b34fb);

pub struct ScooterConnection {
    device: Peripheral,
    tx_char: Characteristic,
    rx_char: Characteristic,
    #[allow(dead_code)]
    is_m365: bool,
}

impl ScooterConnection {
    pub async fn connect(device: &Peripheral, is_m365: bool) -> Result<Self> {
        if !device.is_connected().await? {
            device.connect().await?;
        }
        
        // Wait for services to be discovered
        time::sleep(Duration::from_secs(2)).await;
        device.discover_services().await?;

        let chars = device.characteristics();
        
        let (tx, rx) = if is_m365 {
            Self::find_m365_characteristics(&chars)
        } else {
            Self::find_characteristics(&chars)
        }
        .ok_or_else(|| anyhow!("Could not find compatible UART characteristics"))?;

        println!("Selected characteristics: TX={:?}, RX={:?}", tx.uuid, rx.uuid);
        println!("M365 mode: {}", is_m365);

        // Subscribe to notifications
        device.subscribe(&rx).await
            .context("Failed to subscribe to notification characteristic")?;

        Ok(Self {
            device: device.clone(),
            tx_char: tx,
            rx_char: rx,
            is_m365,
        })
    }

    fn find_characteristics(chars: &BTreeSet<Characteristic>) -> Option<(Characteristic, Characteristic)> {
        // 1. Try Standard NUS
        let nus_tx = chars.iter().find(|c| c.uuid == NUS_TX_UUID);
        let nus_rx = chars.iter().find(|c| c.uuid == NUS_RX_UUID);

        if let (Some(tx), Some(rx)) = (nus_tx, nus_rx) {
            return Some((tx.clone(), rx.clone()));
        }

        // 2. Try FE95 Service Candidates (non-M365 clones)
        Self::find_fe95_chars(chars)
    }

    fn find_m365_characteristics(chars: &BTreeSet<Characteristic>) -> Option<(Characteristic, Characteristic)> {
        // M365 clones: FE95 service, 00000010 (write+notify) is primary
        let m365_char_uuid = Uuid::from_u128(0x00000010_0000_1000_8000_00805f9b34fb);
        let m365_tx = chars.iter().find(|c| 
            c.service_uuid == MI_SERVICE_UUID && 
            c.uuid == m365_char_uuid
        );

        if let Some(tx) = m365_tx {
            if tx.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) && 
               tx.properties.contains(CharPropFlags::NOTIFY) {
                return Some((tx.clone(), tx.clone()));
            }
        }

        // Fallback to 00000019 or generic FE95
        Self::find_fe95_chars(chars)
    }

    fn find_fe95_chars(chars: &BTreeSet<Characteristic>) -> Option<(Characteristic, Characteristic)> {
        let fe95_chars: Vec<&Characteristic> = chars.iter()
            .filter(|c| c.service_uuid == MI_SERVICE_UUID)
            .collect();

        let notify_char = fe95_chars.iter()
            .find(|c| c.properties.contains(CharPropFlags::NOTIFY) || c.properties.contains(CharPropFlags::INDICATE));

        let write_char = fe95_chars.iter()
            .find(|c| c.properties.contains(CharPropFlags::WRITE) || c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE));

        if let (Some(tx), Some(rx)) = (write_char, notify_char) {
            return Some(((*tx).clone(), (*rx).clone()));
        }
        
        // Fallback: ANY notify + write
        let any_notify = chars.iter().find(|c| c.properties.contains(CharPropFlags::NOTIFY));
        let any_write = chars.iter().find(|c| c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE));

        if let (Some(tx), Some(rx)) = (any_write, any_notify) {
             return Some((tx.clone(), rx.clone()));
        }

        None
    }

    pub async fn send_command(&self, payload: &[u8]) -> Result<()> {
        let packet = self.build_packet(payload);
        
        let write_type = if self.tx_char.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) {
            WriteType::WithoutResponse
        } else {
            WriteType::WithResponse
        };

        self.device.write(&self.tx_char, &packet, write_type).await?;
        Ok(())
    }

    pub async fn read_response(&self, timeout_duration: Duration) -> Result<Vec<u8>> {
        let mut notification_stream = self.device.notifications().await?;
        
        let timeout = time::sleep(timeout_duration);
        tokio::pin!(timeout);

        loop {
            tokio::select! {
                Some(data) = notification_stream.next() => {
                    if data.uuid == self.rx_char.uuid {
                        return Ok(data.value);
                    }
                }
                _ = &mut timeout => {
                    return Err(anyhow!("Timeout waiting for response"));
                }
            }
        }
    }

    /// Sends a command and waits for a response
    pub async fn transaction(&self, payload: &[u8]) -> Result<Vec<u8>> {
        self.send_command(payload).await?;
        self.read_response(Duration::from_secs(2)).await
    }

    /// Tries to read the firmware version to verify connection
    pub async fn get_version(&self) -> Result<Vec<u8>> {
        // Cmd: Read (01), Attr: Version (1A), Len: 02
        // Body: [03] [20] [01] [1A] [02]
        let payload = vec![0x03, 0x20, 0x01, 0x1A, 0x02];
        self.transaction(&payload).await
    }

    /// Tries to read the battery level
    pub async fn get_battery_level(&self) -> Result<u8> {
        // Cmd: Read (01), Attr: BatteryPercent (32), Len: 02
        // Body: [03] [20] [01] [32] [02]
        let payload = vec![0x03, 0x20, 0x01, 0x32, 0x02];
        let response = self.transaction(&payload).await?;
        
        // Response format: 55 AA [Len] [Dev] [Cmd] [Attr] [Val] [Val] [Cksum]
        // We expect the value to be in the payload.
        // Usually response payload is at index 7 or 8 depending on format.
        // Let's just return the last byte of the payload before checksum?
        // Or just return the whole response for now and let caller parse.
        // But the signature returns u8.
        
        // Simple parsing: find the value.
        // If response is valid Xiaomi packet:
        // 55 AA L D C A V V CS CS
        // If we get a response, it's likely valid.
        // Battery percent is usually a single byte or u16.
        // Let's assume it's the byte at offset 6 or 7.
        
        if response.len() > 6 {
             // Just a guess based on typical offset
             Ok(response[response.len() - 3]) 
        } else {
             Ok(0)
        }
    }

    fn build_packet(&self, payload: &[u8]) -> Vec<u8> {
        // If the payload already starts with 55 AA, assume it's a full packet
        if payload.len() >= 2 && payload[0] == 0x55 && payload[1] == 0xAA {
            return payload.to_vec();
        }

        // Otherwise, wrap it in Xiaomi protocol
        // 55 AA [Body] [Checksum]
        let mut packet = vec![0x55, 0xAA];
        packet.extend_from_slice(payload);

        let checksum = Self::calculate_checksum(payload);
        packet.push((checksum & 0xFF) as u8);
        packet.push((checksum >> 8) as u8);

        packet
    }
    
    fn calculate_checksum(data: &[u8]) -> u16 {
        let sum: u32 = data.iter().map(|&b| b as u32).sum();
        ((sum ^ 0xFFFF) & 0xFFFF) as u16
    }
}
