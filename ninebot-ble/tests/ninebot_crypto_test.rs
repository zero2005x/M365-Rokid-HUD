use ninebot_ble::ninebot_crypto::NinebotCrypto;

fn vectors() -> Vec<(String, Vec<u8>, Vec<u8>)> {
    include_str!("fixtures/ninebot_pairing.txt")
        .lines()
        .skip(1)
        .map(|line| {
            let parts: Vec<_> = line.split_whitespace().collect();
            (
                parts[0].to_owned(),
                hex::decode(parts[1]).unwrap(),
                hex::decode(parts[2]).unwrap(),
            )
        })
        .collect()
}

fn apply(cipher: &mut NinebotCrypto, step: &(String, Vec<u8>, Vec<u8>)) {
    if step.0 == "tx" {
        assert_eq!(cipher.encrypt(&step.1).unwrap(), step.2);
    } else {
        assert_eq!(cipher.decrypt(&step.2).unwrap(), step.1);
    }
}

#[test]
fn matches_upstream_cpp_pairing_and_application_frames() {
    let mut cipher = NinebotCrypto::new("NBSCOOTER").unwrap();
    for step in vectors() {
        apply(&mut cipher, &step);
    }
    assert_eq!(cipher.serial().unwrap(), b"N4GSD123456789");
    assert!(cipher.key_confirmed());
}

#[test]
fn corrupted_frames_do_not_advance_state() {
    let vectors = vectors();
    for (index, step) in vectors.iter().enumerate().filter(|(_, s)| s.0 == "rx") {
        for byte in 0..step.2.len() {
            let mut cipher = NinebotCrypto::new("NBSCOOTER").unwrap();
            for prior in &vectors[..index] {
                apply(&mut cipher, prior);
            }
            let mut bad = step.2.clone();
            bad[byte] ^= 1;
            assert!(
                cipher.decrypt(&bad).is_err(),
                "accepted byte {byte} in step {index}"
            );
            apply(&mut cipher, step);
            assert!(cipher.decrypt(&step.2).is_err());
        }
    }
}

#[test]
fn rejects_truncation_names_and_changed_pairing_keys() {
    for name in ["", "12345678901234567", "滑板車", "name\0"] {
        assert!(NinebotCrypto::new(name).is_err());
    }
    let vectors = vectors();
    for len in 0..vectors[1].2.len() {
        let mut cipher = NinebotCrypto::new("NBSCOOTER").unwrap();
        assert!(cipher.decrypt(&vectors[1].2[..len]).is_err());
    }
    let mut cipher = NinebotCrypto::new("NBSCOOTER").unwrap();
    assert!(cipher.encrypt(&vectors[2].1).is_err());
    for step in &vectors[..3] {
        apply(&mut cipher, step);
    }
    let mut changed = vectors[2].1.clone();
    changed[7] ^= 1;
    assert!(cipher.encrypt(&changed).is_err());
    apply(&mut cipher, &vectors[3]);
}
