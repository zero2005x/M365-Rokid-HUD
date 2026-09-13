use ninebot_ble::pairing::PairingSession;
use ninebot_ble::profile::*;
use ninebot_ble::vehicle::*;

fn fixture(text: &str) -> Vec<(Vec<u8>, Vec<u8>)> {
    text.lines()
        .skip(1)
        .map(|line| {
            let p: Vec<_> = line.split_whitespace().collect();
            (hex::decode(p[1]).unwrap(), hex::decode(p[2]).unwrap())
        })
        .collect()
}
fn paired(frames: &[(Vec<u8>, Vec<u8>)], serial: &str) -> VehicleSession {
    let mut p = PairingSession::new("NBSCOOTER", std::array::from_fn(|i| 0xf0 + i as u8)).unwrap();
    assert_eq!(p.next_frame().unwrap(), frames[0].1);
    p.receive(&frames[1].1).unwrap();
    p.set_serial(serial).unwrap();
    assert_eq!(p.next_frame().unwrap(), frames[2].1);
    p.receive(&frames[3].1).unwrap();
    assert_eq!(p.next_frame().unwrap(), frames[4].1);
    p.receive(&frames[5].1).unwrap();
    VehicleSession::paired(p).unwrap()
}
#[test]
fn cpp_vectors_cover_identification_telemetry_and_controls() {
    for (text, serial, model) in [
        (
            include_str!("fixtures/xiaomi_vehicle.txt"),
            "21886/12345678",
            ScooterModel::M365Pro,
        ),
        (
            include_str!("fixtures/g30_vehicle.txt"),
            "N4GSD123456789",
            ScooterModel::MaxG30,
        ),
    ] {
        let f = fixture(text);
        let mut session = paired(&f, serial);
        assert!(session.control(0, 1).is_err());
        assert!(session.telemetry().is_err());
        assert_eq!(session.resolve(None, true), DetectionOutcome::UnknownFamily);
        assert_eq!(session.identify().unwrap(), f[6].1);
        assert_eq!(session.receive(&f[7].1).unwrap(), serial.as_bytes());
        assert_eq!(
            session.resolve(None, false),
            DetectionOutcome::ExperimentalDisabled(model)
        );
        assert!(session.control(0, 1).is_err());
        assert!(matches!(
            session.resolve(Some(model), true),
            DetectionOutcome::ProfilePartial { .. }
        ));
        assert_eq!(session.telemetry().unwrap(), f[8].1);
        assert!(session.control(0, 1).is_err());
        let payload = session.receive(&f[9].1).unwrap();
        let profile = ProfileRegistry::resolve(model).unwrap();
        let t = profile
            .telemetry_decoder()
            .decode(profile.register_map(), &payload)
            .unwrap();
        assert_eq!(
            (
                t.battery_percent,
                t.speed_kmh,
                t.odometer_m,
                t.trip_m,
                t.uptime_s,
                t.temperature_c
            ),
            (85.0, 25.3, 123456.0, 420.0, 80.0, -5.0)
        );
        assert!(session.control(1, 1).is_err());
        assert_eq!(session.control(0, 1).unwrap(), f[10].1);
        assert!(matches!(
            session.resolve(Some(ScooterModel::M365), true),
            DetectionOutcome::ProfileMismatch { .. }
        ));
        assert!(session.control(0, 1).is_err());
    }
}
#[test]
fn plain_frame_matches_official_pdf_and_detects_corruption() {
    let request = hex::decode("5aa5013d20013e02").unwrap();
    let wire = hex::decode("5aa5013d20013e0260ff").unwrap();
    assert_eq!(encode_plain(&request).unwrap(), wire);
    assert_eq!(decode_plain(&wire).unwrap(), request);
    for i in 0..wire.len() {
        let mut damaged = wire.clone();
        damaged[i] ^= 1;
        assert!(decode_plain(&damaged).is_err());
    }
    for len in 0..wire.len() {
        assert!(decode_plain(&wire[..len]).is_err());
    }
}
#[test]
fn old_esx_binds_only_after_checked_identification_and_supports_documented_modes() {
    let mut session = VehicleSession::plaintext();
    assert!(session.control(2, 1).is_err());
    session.identify().unwrap();
    let mut response = vec![0x5a, 0xa5, 14, 0x20, 0x3e, 4, 0x10];
    response.extend_from_slice(b"N2GTX1939C0123");
    let wire = encode_plain(&response).unwrap();
    session.receive(&wire).unwrap();
    assert!(matches!(
        session.resolve(None, true),
        DetectionOutcome::ProfilePartial {
            model: ScooterModel::Esx,
            ..
        }
    ));
    assert_eq!(
        decode_plain(&session.control(2, 1).unwrap()).unwrap(),
        [0x5a, 0xa5, 2, 0x3e, 0x20, 3, 0x75, 1, 0]
    );
    assert!(session.control(2, 3).is_err());
    assert!(session.control(1, 1).is_err());
}
#[test]
fn unknown_and_out_of_scope_serials_cannot_be_overridden() {
    for serial in [
        b"N4ZSD123456789",
        b"NAGSD123456789",
        b"99999/12345678",
        b"N5GSD123456789",
    ] {
        assert_eq!(
            resolve_identification(serial, Some(ScooterModel::MaxG30), true),
            DetectionOutcome::UnknownFamily
        );
    }
    assert_eq!(model_from_id(-1), None);
    assert_eq!(model_from_id(256), None);
}
