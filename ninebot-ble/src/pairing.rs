//! 配對狀態機；序號須與車端挑戰回覆一致，成功前不提供應用控制。
use crate::ninebot_crypto::NinebotCrypto;
use zeroize::Zeroize;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum PairingStatus {
    AwaitingChallenge = 0,
    SerialRequired = 1,
    AwaitingButton = 2,
    AwaitingConfirmation = 3,
    Paired = 4,
    Failed = 5,
}

pub struct PairingSession {
    cipher: NinebotCrypto,
    app_key: [u8; 16],
    status: PairingStatus,
    attempts: u8,
}
impl Drop for PairingSession {
    fn drop(&mut self) {
        self.app_key.zeroize();
    }
}

impl PairingSession {
    pub fn new(name: &str, app_key: [u8; 16]) -> Result<Self, &'static str> {
        if app_key == [0; 16] {
            return Err("Pairing key must be randomly generated");
        }
        Ok(Self {
            cipher: NinebotCrypto::new(name)?,
            app_key,
            status: PairingStatus::AwaitingChallenge,
            attempts: 0,
        })
    }
    pub(crate) fn encrypt_application(&mut self, frame: &[u8]) -> Result<Vec<u8>, &'static str> {
        if self.status != PairingStatus::Paired {
            return Err("尚未完成配對");
        }
        self.cipher.encrypt(frame)
    }
    pub(crate) fn decrypt_application(&mut self, frame: &[u8]) -> Result<Vec<u8>, &'static str> {
        if self.status != PairingStatus::Paired {
            return Err("尚未完成配對");
        }
        self.cipher.decrypt(frame)
    }
    pub fn status(&self) -> PairingStatus {
        self.status
    }
    pub fn serial(&self) -> Option<&[u8; 14]> {
        self.cipher.serial()
    }

    pub fn set_serial(&mut self, input: &str) -> Result<(), &'static str> {
        if self.status != PairingStatus::SerialRequired {
            return Err("Serial is not expected in this state");
        }
        if input.len() != 14
            || !input
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'/')
        {
            return Err("Serial must contain 14 ASCII letters, digits or slash");
        }
        if self.cipher.serial().map(|s| s.as_slice()) != Some(input.as_bytes()) {
            return Err("Entered serial does not match the connected scooter");
        }
        self.status = PairingStatus::AwaitingButton;
        self.attempts = 0;
        Ok(())
    }

    /// 呼叫端負責每秒重試及整體逾時；狀態機另限制傳送次數。
    pub fn next_frame(&mut self) -> Result<Vec<u8>, &'static str> {
        let (command, payload, limit) = match self.status {
            PairingStatus::AwaitingChallenge => (0x5b, Vec::new(), 1),
            PairingStatus::AwaitingButton => (0x5c, self.app_key.to_vec(), 60),
            PairingStatus::AwaitingConfirmation => (
                0x5d,
                self.cipher.serial().ok_or("Missing serial")?.to_vec(),
                3,
            ),
            _ => return Err("No pairing frame is available in this state"),
        };
        if self.attempts >= limit {
            self.status = PairingStatus::Failed;
            return Err("Pairing attempts exhausted; reconnect required");
        }
        let mut frame = vec![0x5a, 0xa5, payload.len() as u8, 0x3e, 0x21, command, 0];
        frame.extend(payload);
        let encrypted = self.cipher.encrypt(&frame)?;
        self.attempts += 1;
        Ok(encrypted)
    }

    pub fn receive(&mut self, encrypted: &[u8]) -> Result<PairingStatus, &'static str> {
        if matches!(
            self.status,
            PairingStatus::Paired | PairingStatus::Failed | PairingStatus::SerialRequired
        ) {
            return Err("Pairing response is not expected");
        }
        if self.attempts == 0 {
            return Err("No pairing request has been sent");
        }
        let plain = self.cipher.decrypt(encrypted)?;
        match (self.status, &plain[3..7]) {
            (PairingStatus::AwaitingChallenge, [0x21, 0x3e, 0x5b, 0 | 1]) if plain[2] == 30 => {
                self.status = PairingStatus::SerialRequired
            }
            (PairingStatus::AwaitingButton, [0x21, 0x3e, 0x5c, 0]) if plain[2] == 0 => {
                return Ok(self.status)
            }
            (PairingStatus::AwaitingButton, [0x21, 0x3e, 0x5c, 1])
                if plain[2] == 0 && self.cipher.key_confirmed() =>
            {
                self.status = PairingStatus::AwaitingConfirmation
            }
            (PairingStatus::AwaitingConfirmation, [0x21, 0x3e, 0x5d, 1]) if plain[2] == 0 => {
                self.status = PairingStatus::Paired
            }
            _ => {
                self.status = PairingStatus::Failed;
                return Err("Unexpected pairing response");
            }
        }
        self.attempts = 0;
        Ok(self.status)
    }
}
