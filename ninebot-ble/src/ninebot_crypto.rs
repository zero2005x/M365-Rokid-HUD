//! 依 NinebotCrypto C++／Kotlin 協定流程實作；與 Xiaomi 登入分開。
//! 參考版本：9ba0c551488a8433cea173d1bed843240f63ec30。
use aes::cipher::{generic_array::GenericArray, BlockEncrypt, NewBlockCipher};
use aes::Aes128;
use sha1::{Digest, Sha1};
use zeroize::Zeroize;

const BASIC: [u8; 16] = [
    0x97, 0xcf, 0xb8, 2, 0x84, 0x41, 0x43, 0xde, 0x56, 0, 0x2b, 0x3b, 0x34, 0x78, 0x0a, 0x5d,
];
pub const MAX_PAYLOAD: usize = 128;

/// 傳入 BLE 廣播名稱，而非序號。序號用於後續 0x5D 配對確認。
pub struct NinebotCrypto {
    name: [u8; 16],
    key: [u8; 16],
    ble_random: [u8; 16],
    app_random: Option<[u8; 16]>,
    counter: u32,
    challenge_received: bool,
    key_confirmed: bool,
    serial: Option<[u8; 14]>,
}

impl Drop for NinebotCrypto {
    fn drop(&mut self) {
        self.name.zeroize();
        self.key.zeroize();
        self.ble_random.zeroize();
        if let Some(key) = self.app_random.as_mut() {
            key.zeroize();
        }
        if let Some(serial) = self.serial.as_mut() {
            serial.zeroize();
        }
    }
}

fn derive(first: &[u8; 16], second: &[u8; 16]) -> [u8; 16] {
    let mut hash = Sha1::new();
    hash.update(first);
    hash.update(second);
    let mut digest = hash.finalize();
    let mut key = [0; 16];
    key.copy_from_slice(&digest[..16]);
    digest.as_mut_slice().zeroize();
    key
}

fn aes(key: &[u8; 16], block: &[u8; 16]) -> [u8; 16] {
    let cipher = Aes128::new(GenericArray::from_slice(key));
    let mut result = GenericArray::clone_from_slice(block);
    cipher.encrypt_block(&mut result);
    result.into()
}

fn validate(frame: &[u8]) -> Result<(), &'static str> {
    if frame.len() < 7 || frame[..2] != [0x5a, 0xa5] {
        return Err("Invalid Ninebot frame header");
    }
    if frame[2] as usize > MAX_PAYLOAD || frame.len() != frame[2] as usize + 7 {
        return Err("Invalid Ninebot frame length");
    }
    Ok(())
}

fn first_checksum(data: &[u8]) -> [u8; 2] {
    (!data
        .iter()
        .fold(0u16, |sum, byte| sum.wrapping_add(*byte as u16)))
    .to_le_bytes()
}

impl NinebotCrypto {
    pub fn new(name: &str) -> Result<Self, &'static str> {
        if name.is_empty()
            || !name.is_ascii()
            || name.len() > 16
            || name.bytes().any(|b| b.is_ascii_control())
        {
            return Err("BLE name must contain 1 to 16 printable ASCII bytes");
        }
        let mut padded = [0; 16];
        padded[..name.len()].copy_from_slice(name.as_bytes());
        Ok(Self {
            name: padded,
            key: derive(&padded, &BASIC),
            ble_random: [0; 16],
            app_random: None,
            counter: 0,
            challenge_received: false,
            key_confirmed: false,
            serial: None,
        })
    }

    pub fn serial(&self) -> Option<&[u8; 14]> {
        self.serial.as_ref()
    }
    pub fn key_confirmed(&self) -> bool {
        self.key_confirmed
    }

    fn nonce(&self, counter: u32) -> [u8; 16] {
        let mut nonce = [0; 16];
        nonce[0] = 1;
        nonce[1..5].copy_from_slice(&counter.to_be_bytes());
        nonce[5..13].copy_from_slice(&self.ble_random[..8]);
        nonce
    }

    fn crypt(&self, data: &[u8], counter: u32) -> Vec<u8> {
        let mut nonce = self.nonce(counter);
        let mut output = Vec::with_capacity(data.len());
        for (index, chunk) in data.chunks(16).enumerate() {
            let stream = if counter == 0 {
                aes(&self.key, &BASIC)
            } else {
                nonce[15] = (index + 1) as u8;
                aes(&self.key, &nonce)
            };
            output.extend(chunk.iter().zip(stream).map(|(a, b)| a ^ b));
        }
        output
    }

    fn tag(&self, frame: &[u8], counter: u32) -> [u8; 4] {
        let mut nonce = self.nonce(counter);
        nonce[0] = 0x59;
        nonce[15] = (frame.len() - 3) as u8;
        let mut state = aes(&self.key, &nonce);
        for (i, byte) in frame[..3].iter().enumerate() {
            state[i] ^= byte;
        }
        state = aes(&self.key, &state);
        for chunk in frame[3..].chunks(16) {
            for (i, byte) in chunk.iter().enumerate() {
                state[i] ^= byte;
            }
            state = aes(&self.key, &state);
        }
        nonce[0] = 1;
        nonce[15] = 0;
        let mask = aes(&self.key, &nonce);
        [
            state[0] ^ mask[0],
            state[1] ^ mask[1],
            state[2] ^ mask[2],
            state[3] ^ mask[3],
        ]
    }

    /// 無校驗尾碼的 5A A5 應用框架，輸出含六位元組加密尾碼。
    pub fn encrypt(&mut self, frame: &[u8]) -> Result<Vec<u8>, &'static str> {
        validate(frame)?;
        let setting_key = frame[3..7] == [0x3e, 0x21, 0x5c, 0];
        if setting_key && (frame[2] != 16 || !self.challenge_received || self.key_confirmed) {
            return Err("Pairing key is invalid for this state");
        }
        if setting_key {
            if let Some(key) = &self.app_random {
                if frame[7..] != key[..] {
                    return Err("Pairing retries must reuse the same random key");
                }
            }
        }
        let counter = if self.counter == 0 {
            0
        } else {
            self.counter.checked_add(1).ok_or("Counter exhausted")?
        };
        let mut output = frame[..3].to_vec();
        output.extend(self.crypt(&frame[3..], counter));
        if counter == 0 {
            output.extend([0, 0]);
            output.extend(first_checksum(&frame[3..]));
            output.extend([0, 0]);
        } else {
            output.extend(self.tag(frame, counter));
            output.extend((counter as u16).to_be_bytes());
        }
        self.counter = if counter == 0 { 1 } else { counter };
        if setting_key {
            let mut key = [0; 16];
            key.copy_from_slice(&frame[7..]);
            self.app_random = Some(key);
        }
        Ok(output)
    }

    /// 驗證尾碼後才更新金鑰與計數器；損毀封包不得推進配對。
    pub fn decrypt(&mut self, frame: &[u8]) -> Result<Vec<u8>, &'static str> {
        if frame.len() < 13
            || frame[..2] != [0x5a, 0xa5]
            || frame[2] as usize > MAX_PAYLOAD
            || frame.len() != frame[2] as usize + 13
        {
            return Err("Invalid encrypted Ninebot frame");
        }
        let end = frame.len() - 6;
        let low = u16::from_be_bytes([frame[end + 4], frame[end + 5]]) as u32;
        let mut counter = (self.counter & 0xffff0000) | low;
        if self.counter & 0x8000 != 0 && low & 0x8000 == 0 {
            counter = counter.checked_add(0x10000).ok_or("Counter exhausted")?;
        }
        if counter == 0 && self.challenge_received {
            return Err("Repeated initial challenge");
        }
        if counter != 0 && counter <= self.counter {
            return Err("Replayed Ninebot frame");
        }
        let mut plain = frame[..3].to_vec();
        plain.extend(self.crypt(&frame[3..end], counter));
        let expected = if counter == 0 {
            let crc = first_checksum(&plain[3..]);
            [0, 0, crc[0], crc[1]]
        } else {
            self.tag(&plain, counter)
        };
        let mismatch = expected
            .iter()
            .zip(&frame[end..end + 4])
            .fold(0u8, |acc, (a, b)| acc | (a ^ b));
        if mismatch != 0 {
            return Err("Ninebot authentication failed");
        }
        let response = &plain[3..7];
        if response[..3] == [0x21, 0x3e, 0x5b] {
            if counter != 0 || plain[2] != 30 {
                return Err("Invalid pairing challenge");
            }
            let mut serial = [0; 14];
            serial.copy_from_slice(&plain[23..37]);
            if !serial
                .iter()
                .all(|b| b.is_ascii_alphanumeric() || *b == b'/')
            {
                return Err("Invalid scooter serial");
            }
            self.ble_random.copy_from_slice(&plain[7..23]);
            self.key = derive(&self.name, &self.ble_random);
            self.serial = Some(serial);
            self.challenge_received = true;
        } else if response == [0x21, 0x3e, 0x5c, 1] {
            if plain[2] != 0 || !self.challenge_received || self.key_confirmed {
                return Err("Unexpected pairing confirmation");
            }
            let key = self.app_random.as_ref().ok_or("Pairing key not sent")?;
            self.key = derive(key, &self.ble_random);
            self.key_confirmed = true;
        }
        if counter != 0 {
            self.counter = counter;
        }
        Ok(plain)
    }
}
