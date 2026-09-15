//! 車款描述與線路資料共用核心；不依賴 BLE 或 Android。
use std::sync::Arc;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ScooterModel {
    M365 = 0,
    M365Pro = 1,
    M365Pro2 = 2,
    OneS = 3,
    MaxG30 = 4,
    Esx = 5,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VerificationStatus {
    Verified,
    Unverified,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CryptoStrategy {
    None,
    XiaomiLogin,
    NinebotCrypto,
}

#[derive(Clone, Copy, Debug)]
pub struct BleUuids {
    pub service: &'static str,
    pub tx: &'static str,
    pub rx: &'static str,
    pub auth: Option<AuthUuids>,
}

#[derive(Clone, Copy, Debug)]
pub struct AuthUuids {
    pub service: &'static str,
    pub control: &'static str,
    pub data: &'static str,
}

#[derive(Clone, Copy, Debug)]
pub struct BleFilter {
    pub name_prefixes: &'static [&'static str],
    pub service: &'static str,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Feature {
    Lock,
    Light,
    RideMode,
}

#[derive(Clone, Copy, Debug)]
pub struct ControlCommand {
    pub address: u8,
    pub value: u16,
}
impl ControlCommand {
    pub fn bytes(self) -> Vec<u8> {
        let value = self.value.to_le_bytes();
        vec![4, 0x20, 3, self.address, value[0], value[1]]
    }
}

#[derive(Debug)]
pub struct CommandSet {
    pub lock: Option<ControlCommand>,
    pub unlock: Option<ControlCommand>,
    pub light_on: Option<ControlCommand>,
    pub light_off: Option<ControlCommand>,
    pub ride_modes: &'static [ControlCommand],
}
impl CommandSet {
    pub fn missing_features(&self) -> Vec<Feature> {
        let mut missing = Vec::new();
        if self.lock.is_none() || self.unlock.is_none() {
            missing.push(Feature::Lock);
        }
        if self.light_on.is_none() || self.light_off.is_none() {
            missing.push(Feature::Light);
        }
        if self.ride_modes.is_empty() {
            missing.push(Feature::RideMode);
        }
        missing
    }
}

#[derive(Clone, Copy, Debug)]
pub struct NumericField {
    pub offset: usize,
    pub width: usize,
    pub signed: bool,
    pub divisor: f64,
}
impl NumericField {
    pub fn decode(self, data: &[u8]) -> Result<f64, &'static str> {
        let end = self
            .offset
            .checked_add(self.width)
            .ok_or("Register offset overflow")?;
        let bytes = data
            .get(self.offset..end)
            .ok_or("Truncated register response")?;
        let raw = match (self.width, self.signed) {
            (2, false) => u16::from_le_bytes([bytes[0], bytes[1]]) as f64,
            (2, true) => i16::from_le_bytes([bytes[0], bytes[1]]) as f64,
            (4, false) => u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as f64,
            _ => return Err("Unsupported register encoding"),
        };
        if !self.divisor.is_finite() || self.divisor <= 0.0 {
            return Err("Invalid register scale");
        }
        Ok(raw / self.divisor)
    }
}

#[derive(Debug)]
pub struct RegisterMap {
    pub serial: u8,
    pub serial_length: u8,
    pub firmware: u8,
    pub motor_info: u8,
    pub motor_info_length: u8,
    pub battery: NumericField,
    pub speed: NumericField,
    pub average_speed: NumericField,
    pub odometer: NumericField,
    pub trip: NumericField,
    pub uptime: NumericField,
    pub temperature: NumericField,
    /// 順序對應舊版 Attribute；僅供既有 API 遷移。
    pub legacy_addresses: &'static [u8],
}

#[derive(Debug, PartialEq)]
pub struct Telemetry {
    pub battery_percent: f64,
    pub speed_kmh: f64,
    pub average_speed_kmh: f64,
    pub odometer_m: f64,
    pub trip_m: f64,
    pub uptime_s: f64,
    pub temperature_c: f64,
}

pub trait TelemetryDecoder: Send + Sync {
    fn decode(&self, registers: &RegisterMap, data: &[u8]) -> Result<Telemetry, &'static str>;
}
pub struct TableTelemetryDecoder;
impl TelemetryDecoder for TableTelemetryDecoder {
    fn decode(&self, r: &RegisterMap, data: &[u8]) -> Result<Telemetry, &'static str> {
        Ok(Telemetry {
            battery_percent: r.battery.decode(data)?,
            speed_kmh: r.speed.decode(data)?,
            average_speed_kmh: r.average_speed.decode(data)?,
            odometer_m: r.odometer.decode(data)?,
            trip_m: r.trip.decode(data)?,
            uptime_s: r.uptime.decode(data)?,
            temperature_c: r.temperature.decode(data)?,
        })
    }
}

pub trait ScooterProfile: Send + Sync {
    fn id(&self) -> ScooterModel;
    fn verification_status(&self) -> VerificationStatus;
    fn ble_filter(&self) -> BleFilter;
    fn service_uuids(&self) -> BleUuids;
    fn crypto_strategy(&self) -> CryptoStrategy;
    fn register_map(&self) -> &'static RegisterMap;
    fn telemetry_decoder(&self) -> &dyn TelemetryDecoder;
    fn command_set(&self) -> &'static CommandSet;
    fn decode_legacy_android(&self, _data: &[u8]) -> Result<Telemetry, &'static str> {
        Err("此車款未提供舊版 Android 解碼")
    }
}

const fn field(offset: usize, width: usize, signed: bool, divisor: f64) -> NumericField {
    NumericField {
        offset,
        width,
        signed,
        divisor,
    }
}
static M365_REGISTERS: RegisterMap = RegisterMap {
    serial: 0x10,
    serial_length: 14,
    firmware: 0x1a,
    motor_info: 0xb0,
    motor_info_length: 32,
    battery: field(8, 2, false, 1.0),
    speed: field(10, 2, false, 1000.0),
    average_speed: field(12, 2, false, 1000.0),
    odometer: field(14, 4, false, 1.0),
    trip: field(18, 2, false, 1.0),
    uptime: field(20, 2, false, 1.0),
    temperature: field(22, 2, true, 10.0),
    legacy_addresses: &[
        0x10, 0xb0, 0x25, 0xb5, 0xb9, 0x34, 0x33, 0x32, 0x40, 0x7b, 0x7c, 0x7d, 0x31, 0x70, 0x71,
    ],
};
static M365_COMMANDS: CommandSet = CommandSet {
    lock: Some(ControlCommand {
        address: 0x70,
        value: 1,
    }),
    unlock: Some(ControlCommand {
        address: 0x71,
        value: 1,
    }),
    light_on: Some(ControlCommand {
        address: 0x7d,
        value: 2,
    }),
    light_off: Some(ControlCommand {
        address: 0x7d,
        value: 0,
    }),
    ride_modes: &[],
};
static DECODER: TableTelemetryDecoder = TableTelemetryDecoder;
pub struct M365Profile;
impl ScooterProfile for M365Profile {
    fn decode_legacy_android(&self, data: &[u8]) -> Result<Telemetry, &'static str> {
        // 保留既有 Android 的短溫度預設、電量替代值、帶正負號里程及 f32 精度。
        if data.len() < 22 {
            return Err("馬達資料不完整");
        }
        let r = self.register_map();
        let mut t = self.telemetry_decoder().decode(r, &{
            let mut padded = data.to_vec();
            if padded.len() < 24 {
                padded.resize(24, 0);
            }
            padded
        })?;
        if !(1.0..=100.0).contains(&t.battery_percent) {
            t.battery_percent = data[7] as f64;
        }
        let speed =
            i16::from_le_bytes(data[r.speed.offset..r.speed.offset + 2].try_into().unwrap());
        let avg = u16::from_le_bytes(
            data[r.average_speed.offset..r.average_speed.offset + 2]
                .try_into()
                .unwrap(),
        );
        t.speed_kmh = (speed as f32 / r.speed.divisor as f32) as f64;
        t.average_speed_kmh = (avg as f32 / r.average_speed.divisor as f32) as f64;
        t.temperature_c = if data.len() >= 24 {
            (i16::from_le_bytes([data[22], data[23]]) as f32 / 10.0) as f64
        } else {
            0.0
        };
        t.odometer_m = i32::from_le_bytes(
            data[r.odometer.offset..r.odometer.offset + 4]
                .try_into()
                .unwrap(),
        ) as f64;
        Ok(t)
    }
    fn id(&self) -> ScooterModel {
        ScooterModel::M365
    }
    fn verification_status(&self) -> VerificationStatus {
        VerificationStatus::Verified
    }
    fn ble_filter(&self) -> BleFilter {
        BleFilter {
            name_prefixes: &["MIScooter"],
            service: self
                .service_uuids()
                .auth
                .map(|auth| auth.service)
                .unwrap_or(self.service_uuids().service),
        }
    }
    fn service_uuids(&self) -> BleUuids {
        BleUuids {
            service: "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            tx: "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            rx: "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            auth: Some(AuthUuids {
                service: "0000fe95-0000-1000-8000-00805f9b34fb",
                control: "00000010-0000-1000-8000-00805f9b34fb",
                data: "00000019-0000-1000-8000-00805f9b34fb",
            }),
        }
    }
    fn crypto_strategy(&self) -> CryptoStrategy {
        CryptoStrategy::XiaomiLogin
    }
    fn register_map(&self) -> &'static RegisterMap {
        &M365_REGISTERS
    }
    fn telemetry_decoder(&self) -> &dyn TelemetryDecoder {
        &DECODER
    }
    fn command_set(&self) -> &'static CommandSet {
        &M365_COMMANDS
    }
}

pub struct ProfileRegistry;
impl ProfileRegistry {
    /// 僅解析已實作的車款；未知值不得回退到 M365。
    pub fn resolve(model: ScooterModel) -> Option<Arc<dyn ScooterProfile>> {
        match model {
            ScooterModel::M365 => Some(Arc::new(M365Profile)),
            ScooterModel::M365Pro | ScooterModel::M365Pro2 | ScooterModel::OneS => {
                Some(Arc::new(DocumentedProfile {
                    model,
                    registers: &XIAOMI_REGISTERS,
                    commands: &XIAOMI_COMMANDS,
                }))
            }
            ScooterModel::MaxG30 => Some(Arc::new(DocumentedProfile {
                model,
                registers: &NINEBOT_REGISTERS,
                commands: &XIAOMI_COMMANDS,
            })),
            ScooterModel::Esx => Some(Arc::new(DocumentedProfile {
                model,
                registers: &NINEBOT_REGISTERS,
                commands: &ESX_COMMANDS,
            })),
        }
    }
    pub fn available() -> Vec<Arc<dyn ScooterProfile>> {
        [
            ScooterModel::M365,
            ScooterModel::M365Pro,
            ScooterModel::M365Pro2,
            ScooterModel::OneS,
            ScooterModel::MaxG30,
            ScooterModel::Esx,
        ]
        .into_iter()
        .filter_map(Self::resolve)
        .collect()
    }
}

/// 新版 Xiaomi 的共用表來自 M365 ESC 文件；硬體驗證前維持實驗性。
static XIAOMI_REGISTERS: RegisterMap = RegisterMap {
    serial: 0x10,
    serial_length: 14,
    firmware: 0x1a,
    motor_info: 0xb0,
    motor_info_length: 24,
    battery: field(8, 2, false, 1.0),
    speed: field(10, 2, false, 1000.0),
    average_speed: field(12, 2, false, 1000.0),
    odometer: field(14, 4, false, 1.0),
    trip: field(18, 2, false, 0.1),
    uptime: field(20, 2, false, 1.0),
    temperature: field(22, 2, true, 10.0),
    legacy_addresses: &[],
};
/// 文件未定義新車款的車燈數值與完整模式切換，不能沿用其他車款的寫入值。
static XIAOMI_COMMANDS: CommandSet = CommandSet {
    lock: Some(ControlCommand {
        address: 0x70,
        value: 1,
    }),
    unlock: Some(ControlCommand {
        address: 0x71,
        value: 1,
    }),
    light_on: None,
    light_off: None,
    ride_modes: &[],
};
pub struct DocumentedProfile {
    model: ScooterModel,
    registers: &'static RegisterMap,
    commands: &'static CommandSet,
}
impl ScooterProfile for DocumentedProfile {
    fn id(&self) -> ScooterModel {
        self.model
    }
    fn verification_status(&self) -> VerificationStatus {
        VerificationStatus::Unverified
    }
    fn ble_filter(&self) -> BleFilter {
        BleFilter {
            name_prefixes: &["MIScooter", "NBSCOOTER", "Ninebot"],
            service: self.service_uuids().service,
        }
    }
    fn service_uuids(&self) -> BleUuids {
        let mut uuids = M365Profile.service_uuids();
        uuids.auth = None;
        uuids
    }
    fn crypto_strategy(&self) -> CryptoStrategy {
        CryptoStrategy::NinebotCrypto
    }
    fn register_map(&self) -> &'static RegisterMap {
        self.registers
    }
    fn telemetry_decoder(&self) -> &dyn TelemetryDecoder {
        &DECODER
    }
    fn command_set(&self) -> &'static CommandSet {
        self.commands
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DetectionOutcome {
    ProfileResolved(ScooterModel),
    ProfileMismatch {
        expected: ScooterModel,
        actual: ScooterModel,
    },
    ProfilePartial {
        model: ScooterModel,
        missing_features: Vec<Feature>,
    },
    ExperimentalDisabled(ScooterModel),
    UnknownFamily,
}

/// 僅接受控制器識別暫存器；廣播名稱與使用者輸入均不得作為此函式的證據。
pub fn identify_serial(serial: &[u8]) -> Option<ScooterModel> {
    if serial.len() != 14
        || !serial
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || *b == b'/')
    {
        return None;
    }
    if serial.iter().all(u8::is_ascii_alphanumeric)
        && (serial.starts_with(b"N4G") || serial.starts_with(b"N4L"))
    {
        return Some(ScooterModel::MaxG30);
    }
    if serial.iter().all(u8::is_ascii_alphanumeric) && serial.starts_with(b"N2") {
        return Some(ScooterModel::Esx);
    }
    if serial[5] != b'/'
        || !serial[..5]
            .iter()
            .chain(&serial[6..])
            .all(u8::is_ascii_digit)
    {
        return None;
    }
    match &serial[..5] {
        b"13678" | b"13679" | b"16133" | b"21074" | b"16132" | b"21073" | b"16349" | b"16348" => {
            Some(ScooterModel::M365)
        }
        b"18832" | b"21886" => Some(ScooterModel::M365Pro),
        b"26354" | b"30371" => Some(ScooterModel::M365Pro2),
        b"25699" => Some(ScooterModel::OneS),
        _ => None,
    }
}
pub fn resolve_identification(
    serial: &[u8],
    expected: Option<ScooterModel>,
    experimental: bool,
) -> DetectionOutcome {
    let Some(actual) = identify_serial(serial) else {
        return DetectionOutcome::UnknownFamily;
    };
    if let Some(expected) = expected {
        if expected != actual {
            return DetectionOutcome::ProfileMismatch { expected, actual };
        }
    }
    let Some(profile) = ProfileRegistry::resolve(actual) else {
        return DetectionOutcome::UnknownFamily;
    };
    if profile.verification_status() == VerificationStatus::Unverified && !experimental {
        return DetectionOutcome::ExperimentalDisabled(actual);
    }
    let missing_features = profile.command_set().missing_features();
    if missing_features.is_empty() {
        DetectionOutcome::ProfileResolved(actual)
    } else {
        DetectionOutcome::ProfilePartial {
            model: actual,
            missing_features,
        }
    }
}

/// ES 官方協定快速區；G30 的速度、里程及溫度亦與 G30Protocol 交叉核對。
static NINEBOT_REGISTERS: RegisterMap = RegisterMap {
    serial: 0x10,
    serial_length: 14,
    firmware: 0x1a,
    motor_info: 0xb0,
    motor_info_length: 24,
    battery: field(8, 2, false, 1.0),
    speed: field(10, 2, true, 10.0),
    average_speed: field(12, 2, true, 10.0),
    odometer: field(14, 4, false, 1.0),
    trip: field(18, 2, false, 0.1),
    uptime: field(20, 2, false, 1.0),
    temperature: field(22, 2, true, 10.0),
    legacy_addresses: &[],
};
static ESX_COMMANDS: CommandSet = CommandSet {
    lock: Some(ControlCommand {
        address: 0x70,
        value: 1,
    }),
    unlock: Some(ControlCommand {
        address: 0x71,
        value: 1,
    }),
    light_on: None,
    light_off: None,
    ride_modes: &[
        ControlCommand {
            address: 0x75,
            value: 0,
        },
        ControlCommand {
            address: 0x75,
            value: 1,
        },
        ControlCommand {
            address: 0x75,
            value: 2,
        },
    ],
};
