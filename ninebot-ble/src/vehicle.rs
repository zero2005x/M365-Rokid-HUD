//! 完成配對後的共用會話；辨識前僅允許序號查詢，控制一律查表。
use crate::pairing::{PairingSession, PairingStatus};
use crate::profile::*;
use std::sync::Arc;

pub fn frame(command: u8, index: u8, payload: &[u8]) -> Result<Vec<u8>, &'static str> {
    if payload.len() > 128 {
        return Err("封包過長");
    }
    let mut bytes = vec![0x5a, 0xa5, payload.len() as u8, 0x3e, 0x20, command, index];
    bytes.extend_from_slice(payload);
    Ok(bytes)
}
pub fn encode_plain(frame: &[u8]) -> Result<Vec<u8>, &'static str> {
    validate(frame)?;
    let checksum = !frame[2..]
        .iter()
        .fold(0u16, |sum, b| sum.wrapping_add(*b as u16));
    let mut bytes = frame.to_vec();
    bytes.extend_from_slice(&checksum.to_le_bytes());
    Ok(bytes)
}
pub fn decode_plain(bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
    if bytes.len() < 9 {
        return Err("封包不完整");
    }
    let plain = &bytes[..bytes.len() - 2];
    if encode_plain(plain)? != bytes {
        return Err("封包校驗失敗");
    }
    Ok(plain.to_vec())
}
fn validate(bytes: &[u8]) -> Result<(), &'static str> {
    if bytes.len() < 7
        || bytes[..2] != [0x5a, 0xa5]
        || bytes[2] > 128
        || bytes.len() != bytes[2] as usize + 7
    {
        return Err("封包格式不符");
    }
    Ok(())
}

pub struct VehicleSession {
    pairing: Option<PairingSession>,
    profile: Option<Arc<dyn ScooterProfile>>,
    serial: Option<[u8; 14]>,
    pending: Option<(u8, u8)>,
}
impl VehicleSession {
    pub fn paired(pairing: PairingSession) -> Result<Self, &'static str> {
        if pairing.status() != PairingStatus::Paired {
            return Err("尚未完成配對");
        }
        Ok(Self {
            pairing: Some(pairing),
            profile: None,
            serial: None,
            pending: None,
        })
    }
    pub fn plaintext() -> Self {
        Self {
            pairing: None,
            profile: None,
            serial: None,
            pending: None,
        }
    }
    fn encode(&mut self, bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
        match &mut self.pairing {
            Some(p) => p.encrypt_application(bytes),
            None => encode_plain(bytes),
        }
    }
    fn read(&mut self, address: u8, length: u8) -> Result<Vec<u8>, &'static str> {
        if self.pending.is_some() {
            return Err("前次查詢尚未完成");
        }
        let bytes = self.encode(&frame(1, address, &[length])?)?;
        self.pending = Some((address, length));
        Ok(bytes)
    }
    pub fn identify(&mut self) -> Result<Vec<u8>, &'static str> {
        if self.profile.is_some() {
            return Err("會話已綁定車款");
        }
        self.read(0x10, 14)
    }
    pub fn receive(&mut self, bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
        let plain = match &mut self.pairing {
            Some(p) => p.decrypt_application(bytes)?,
            None => decode_plain(bytes)?,
        };
        validate(&plain)?;
        let (address, length) = self.pending.ok_or("未送出查詢")?;
        if plain[3] != 0x20
            || plain[4] != 0x3e
            || ![1, 4].contains(&plain[5])
            || plain[6] != address
            || plain[2] != length
        {
            return Err("回覆與查詢不符");
        }
        let payload = &plain[7..];
        if address == 0x10 {
            let serial: [u8; 14] = payload.try_into().map_err(|_| "序號長度錯誤")?;
            if let Some(p) = &self.pairing {
                if p.serial() != Some(&serial) {
                    return Err("控制器序號與配對序號不一致");
                }
            }
            self.serial = Some(serial);
        }
        self.pending = None;
        Ok(payload.to_vec())
    }
    pub fn resolve(
        &mut self,
        expected: Option<ScooterModel>,
        experimental: bool,
    ) -> DetectionOutcome {
        self.profile = None;
        let Some(serial) = self.serial else {
            return DetectionOutcome::UnknownFamily;
        };
        let result = resolve_identification(&serial, expected, experimental);
        let model = match &result {
            DetectionOutcome::ProfileResolved(model)
            | DetectionOutcome::ProfilePartial { model, .. } => Some(*model),
            _ => None,
        };
        if let Some(model) = model {
            // 舊版明文只在辨識為 ESx 後啟用；不可因加密失敗降級其他車款。
            if self.pairing.is_none() && model != ScooterModel::Esx {
                return DetectionOutcome::UnknownFamily;
            }
            self.profile = ProfileRegistry::resolve(model);
        }
        result
    }
    pub fn telemetry(&mut self) -> Result<Vec<u8>, &'static str> {
        let map = self.profile.as_ref().ok_or("尚未辨識車款")?.register_map();
        self.read(map.motor_info, map.motor_info_length)
    }
    pub fn control(&mut self, feature: u8, value: u8) -> Result<Vec<u8>, &'static str> {
        if self.pending.is_some() {
            return Err("遙測查詢尚未完成");
        }
        let commands = self.profile.as_ref().ok_or("尚未辨識車款")?.command_set();
        let command = match (feature, value) {
            (0, 0) => commands.unlock,
            (0, 1) => commands.lock,
            (1, 0) => commands.light_off,
            (1, 1) => commands.light_on,
            (2, mode) => commands.ride_modes.get(mode as usize).copied(),
            _ => None,
        }
        .ok_or("此車款未定義這項控制")?;
        self.encode(&frame(3, command.address, &command.value.to_le_bytes())?)
    }
}

pub fn model_from_id(id: i32) -> Option<ScooterModel> {
    match id {
        0 => Some(ScooterModel::M365),
        1 => Some(ScooterModel::M365Pro),
        2 => Some(ScooterModel::M365Pro2),
        3 => Some(ScooterModel::OneS),
        4 => Some(ScooterModel::MaxG30),
        5 => Some(ScooterModel::Esx),
        _ => None,
    }
}
pub fn outcome_bytes(outcome: DetectionOutcome, expected: Option<ScooterModel>) -> Vec<u8> {
    let (kind, model) = match outcome {
        DetectionOutcome::UnknownFamily => (0, None),
        DetectionOutcome::ProfileResolved(m) => (1, Some(m)),
        DetectionOutcome::ProfileMismatch { actual, .. } => (2, Some(actual)),
        DetectionOutcome::ProfilePartial { model, .. } => (3, Some(model)),
        DetectionOutcome::ExperimentalDisabled(m) => (4, Some(m)),
    };
    let capabilities = model
        .and_then(ProfileRegistry::resolve)
        .map(|p| {
            let c = p.command_set();
            u8::from(c.lock.is_some() && c.unlock.is_some())
                | (u8::from(c.light_on.is_some() && c.light_off.is_some()) << 1)
                | (u8::from(!c.ride_modes.is_empty()) << 2)
        })
        .unwrap_or(0);
    vec![
        1,
        kind,
        model.map(|m| m as u8).unwrap_or(255),
        expected.map(|m| m as u8).unwrap_or(255),
        capabilities,
    ]
}
