mod mi_session;
pub mod commands;
mod info;
mod travel;
mod battery;
mod payload;
mod settings;
mod lock;
mod light;

pub use mi_session::MiSession;
pub use payload::Payload;
pub use info::{GeneralInfo, MotorInfo};
// `MiSession::send` takes a `&ScooterCommand`, so the command type and every
// type needed to build one must be nameable by downstream crates.
pub use commands::{ScooterCommand, Direction, ReadWrite, Attribute, CommandError};
// `supplementary_info()` returns `SupplementaryInfo`, which exposes `Kers`.
pub use settings::{TailLight, Kers, SupplementaryInfo};
// `battery_cell_voltages()` returns `BatteryCellsVoltage`.
pub use battery::{BatteryInfo, BatteryCellsVoltage};
