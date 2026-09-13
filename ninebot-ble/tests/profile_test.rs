use ninebot_ble::profile::*;
use ninebot_ble::session::{Attribute, Direction, ReadWrite, ScooterCommand};

#[test]
fn registry_never_falls_back_to_m365() {
    assert_eq!(ProfileRegistry::available().len(), 6);
    let p = ProfileRegistry::resolve(ScooterModel::M365).unwrap();
    assert_eq!(p.verification_status(), VerificationStatus::Verified);
    assert_eq!(p.crypto_strategy(), CryptoStrategy::XiaomiLogin);
    for model in [ScooterModel::MaxG30, ScooterModel::Esx] {
        let p = ProfileRegistry::resolve(model).unwrap();
        assert_eq!(p.id(), model);
        assert_eq!(p.verification_status(), VerificationStatus::Unverified);
    }
}

#[test]
fn all_legacy_command_addresses_remain_identical() {
    let cases = [
        (Attribute::GeneralInfo, 0x10),
        (Attribute::MotorInfo, 0xb0),
        (Attribute::DistanceLeft, 0x25),
        (Attribute::Speed, 0xb5),
        (Attribute::TripDistance, 0xb9),
        (Attribute::BatteryVoltage, 0x34),
        (Attribute::BatteryCurrent, 0x33),
        (Attribute::BatteryPercent, 0x32),
        (Attribute::BatteryCellVoltages, 0x40),
        (Attribute::Supplementary, 0x7b),
        (Attribute::Cruise, 0x7c),
        (Attribute::TailLight, 0x7d),
        (Attribute::BatteryInfo, 0x31),
        (Attribute::Lock, 0x70),
        (Attribute::Unlock, 0x71),
    ];
    let p = M365Profile;
    for (attribute, address) in cases {
        let cmd = ScooterCommand {
            direction: Direction::MasterToMotor,
            read_write: ReadWrite::Read,
            attribute,
            payload: vec![2],
        };
        assert_eq!(cmd.as_bytes_for(&p).unwrap(), vec![3, 0x20, 1, address, 2]);
    }
    let c = p.command_set();
    assert_eq!(c.lock.unwrap().bytes(), [4, 0x20, 3, 0x70, 1, 0]);
    assert_eq!(c.unlock.unwrap().bytes(), [4, 0x20, 3, 0x71, 1, 0]);
    assert_eq!(c.light_on.unwrap().bytes(), [4, 0x20, 3, 0x7d, 2, 0]);
    assert_eq!(c.light_off.unwrap().bytes(), [4, 0x20, 3, 0x7d, 0, 0]);
    assert_eq!(c.missing_features(), [Feature::RideMode]);
}

#[test]
fn decoder_preserves_unsigned_counters_and_negative_temperature() {
    let p = M365Profile;
    let mut data = [0u8; 24];
    data[8..10].copy_from_slice(&87u16.to_le_bytes());
    data[10..12].copy_from_slice(&45000u16.to_le_bytes());
    data[12..14].copy_from_slice(&17000u16.to_le_bytes());
    data[14..18].copy_from_slice(&3000000000u32.to_le_bytes());
    data[18..20].copy_from_slice(&50000u16.to_le_bytes());
    data[20..22].copy_from_slice(&60000u16.to_le_bytes());
    data[22..24].copy_from_slice(&(-125i16).to_le_bytes());
    let t = p
        .telemetry_decoder()
        .decode(p.register_map(), &data)
        .unwrap();
    assert_eq!(
        t,
        Telemetry {
            battery_percent: 87.0,
            speed_kmh: 45.0,
            average_speed_kmh: 17.0,
            odometer_m: 3000000000.0,
            trip_m: 50000.0,
            uptime_s: 60000.0,
            temperature_c: -12.5
        }
    );
    for len in 0..24 {
        assert!(p
            .telemetry_decoder()
            .decode(p.register_map(), &data[..len])
            .is_err());
    }
}

#[test]
fn xiaomi_detection_refuses_mismatch_unknown_and_disabled_profiles() {
    for (serial, model) in [
        (b"21886/12345678", ScooterModel::M365Pro),
        (b"26354/12345678", ScooterModel::M365Pro2),
        (b"25699/12345678", ScooterModel::OneS),
    ] {
        assert_eq!(
            resolve_identification(serial, None, false),
            DetectionOutcome::ExperimentalDisabled(model)
        );
        assert_eq!(
            resolve_identification(serial, Some(ScooterModel::M365), true),
            DetectionOutcome::ProfileMismatch {
                expected: ScooterModel::M365,
                actual: model
            }
        );
        assert_eq!(
            resolve_identification(serial, Some(model), true),
            DetectionOutcome::ProfilePartial {
                model,
                missing_features: vec![Feature::Light, Feature::RideMode]
            }
        );
        let p = ProfileRegistry::resolve(model).unwrap();
        assert_eq!(p.id(), model);
        assert_eq!(p.verification_status(), VerificationStatus::Unverified);
        let mut data = [0; 24];
        data[8] = 90;
        data[10..12].copy_from_slice(&25000u16.to_le_bytes());
        data[18..20].copy_from_slice(&123u16.to_le_bytes());
        data[22..24].copy_from_slice(&(-50i16).to_le_bytes());
        let t = p
            .telemetry_decoder()
            .decode(p.register_map(), &data)
            .unwrap();
        assert_eq!(
            (t.speed_kmh, t.trip_m, t.temperature_c),
            (25.0, 1230.0, -5.0)
        );
    }
    for serial in [
        b"99999/12345678".as_slice(),
        b"26354-12345678",
        b"26354/abcdefgh",
        b"MIScooter",
    ] {
        assert_eq!(
            resolve_identification(serial, Some(ScooterModel::M365Pro2), true),
            DetectionOutcome::UnknownFamily
        );
    }
}

#[test]
fn android_legacy_decoder_preserves_old_fallbacks_sign_and_float_precision() {
    let p = M365Profile;
    let mut data = [0u8; 24];
    data[7] = 79;
    data[8..10].copy_from_slice(&101u16.to_le_bytes());
    data[10..12].copy_from_slice(&(-12345i16).to_le_bytes());
    data[12..14].copy_from_slice(&65432u16.to_le_bytes());
    data[14..18].copy_from_slice(&3_000_000_000u32.to_le_bytes());
    data[22..24].copy_from_slice(&(-123i16).to_le_bytes());
    let t = p.decode_legacy_android(&data).unwrap();
    assert_eq!(t.battery_percent, 79.0);
    assert_eq!(t.speed_kmh, (-12345f32 / 1000.0) as f64);
    assert_eq!(t.average_speed_kmh, (65432f32 / 1000.0) as f64);
    assert_eq!(t.temperature_c, (-123f32 / 10.0) as f64);
    assert_eq!(t.odometer_m, 3_000_000_000u32 as i32 as f64);
    assert_eq!(
        p.decode_legacy_android(&data[..22]).unwrap().temperature_c,
        0.0
    );
    assert_eq!(
        p.decode_legacy_android(&data[..23]).unwrap().temperature_c,
        0.0
    );
    for len in 0..22 {
        assert!(p.decode_legacy_android(&data[..len]).is_err());
    }
    data[8..10].copy_from_slice(&100u16.to_le_bytes());
    assert_eq!(
        p.decode_legacy_android(&data).unwrap().battery_percent,
        100.0
    );
    for model in [
        ScooterModel::M365Pro,
        ScooterModel::M365Pro2,
        ScooterModel::OneS,
        ScooterModel::MaxG30,
        ScooterModel::Esx,
    ] {
        assert!(ProfileRegistry::resolve(model)
            .unwrap()
            .decode_legacy_android(&data)
            .is_err());
    }
}
