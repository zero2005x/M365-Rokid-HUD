use ninebot_ble::pairing::{PairingSession, PairingStatus};

fn frames() -> Vec<(Vec<u8>, Vec<u8>)> {
    include_str!("fixtures/ninebot_pairing.txt")
        .lines()
        .skip(1)
        .map(|line| {
            let parts: Vec<_> = line.split_whitespace().collect();
            (
                hex::decode(parts[1]).unwrap(),
                hex::decode(parts[2]).unwrap(),
            )
        })
        .collect()
}
fn pairing() -> PairingSession {
    PairingSession::new("NBSCOOTER", std::array::from_fn(|i| 0xf0 + i as u8)).unwrap()
}

#[test]
fn requires_matching_serial_button_and_final_confirmation() {
    let frames = frames();
    let mut session = pairing();
    assert_eq!(session.next_frame().unwrap(), frames[0].1);
    assert_eq!(
        session.receive(&frames[1].1).unwrap(),
        PairingStatus::SerialRequired
    );
    assert!(session.next_frame().is_err());
    assert!(session.set_serial("N4GSD000000000").is_err());
    assert_eq!(session.status(), PairingStatus::SerialRequired);
    session.set_serial("N4GSD123456789").unwrap();
    assert_eq!(session.next_frame().unwrap(), frames[2].1);
    assert_eq!(
        session.receive(&frames[3].1).unwrap(),
        PairingStatus::AwaitingConfirmation
    );
    assert_eq!(session.next_frame().unwrap(), frames[4].1);
    assert_eq!(
        session.receive(&frames[5].1).unwrap(),
        PairingStatus::Paired
    );
    assert!(session.next_frame().is_err());
}

#[test]
fn rejects_unsolicited_responses_and_bounds_retries() {
    let mut session = pairing();
    assert!(session.receive(&frames()[1].1).is_err());
    assert!(session.set_serial("N4GSD123456789").is_err());
    session.next_frame().unwrap();
    assert!(session.next_frame().is_err());
    assert_eq!(session.status(), PairingStatus::Failed);
    assert!(session.receive(&frames()[1].1).is_err());
    assert!(PairingSession::new("NBSCOOTER", [0; 16]).is_err());
}
