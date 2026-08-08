use hex_literal::hex;
use ninebot_ble::mi_crypto::{crc16, decrypt_uart, encrypt_uart, EncryptionKey};
use tracing::Level;
use tracing_subscriber::fmt::format::FmtSpan;

#[test]
fn it_crc16() {
    let bytes = [0xa1, 0x21, 0xf3, 4, 5, 6, 7, 8, 9];
    let crc = crc16(&bytes);

    assert_eq!(crc, hex!("23fe"));
}

#[test]
fn it_encrypts_uart() {
    let encryption_key = EncryptionKey {
        key: hex!("5066d82368375a1f6a0a3eba1317b525"),
        iv: hex!("28cee53e"),
    };

    let rand: [u8; 4] = hex!("897045e7");
    let cmd: [u8; 5] = hex!("032001100e");
    let ct = encrypt_uart(&encryption_key, &cmd, 0, Some(rand)).unwrap();

    let expected_result = hex!("55ab03000016b2eddb0b680532a988c4f2dbf9");

    assert_eq!(ct.len(), expected_result.len());
    assert_eq!(ct, expected_result)
}

/// Regression test for the AES-CCM nonce mismatch between the two directions.
///
/// `encrypt_uart` used to build the nonce from the full 4-byte big-endian
/// counter while only transmitting its top two bytes, and `decrypt_uart`
/// rebuilt it from the two transmitted bytes plus two zero bytes. The two
/// layouts only agreed when the counter was 0, so any ordinary incrementing
/// counter produced frames the peer could not authenticate.
#[test]
fn it_round_trips_uart_with_a_nonzero_counter() {
    let encryption_key = EncryptionKey {
        key: hex!("5066d82368375a1f6a0a3eba1317b525"),
        iv: hex!("28cee53e"),
    };

    let cmd: [u8; 5] = hex!("032001100e");

    for counter in [0u32, 1, 2, 255, 256, 4096, u16::MAX as u32] {
        let frame = encrypt_uart(&encryption_key, &cmd, counter, Some(hex!("897045e7")))
            .unwrap_or_else(|e| panic!("encrypt failed for counter {counter}: {e}"));

        // The counter must be recoverable from the wire so the peer can
        // reconstruct the nonce.
        assert_eq!(
            u16::from_le_bytes([frame[3], frame[4]]),
            counter as u16,
            "counter {counter} is not carried on the wire"
        );

        let plaintext = decrypt_uart(&encryption_key, &frame)
            .unwrap_or_else(|e| panic!("decrypt failed for counter {counter}: {e}"));

        // encrypt_uart moves the size byte out of the ciphertext and appends a
        // 4-byte random suffix, so compare against the remaining command bytes.
        assert_eq!(&plaintext[..cmd.len() - 1], &cmd[1..]);
    }
}

/// A counter beyond the on-wire field must be rejected rather than silently
/// wrapping onto an already-used nonce.
#[test]
fn it_rejects_a_counter_that_does_not_fit_on_the_wire() {
    let encryption_key = EncryptionKey {
        key: hex!("5066d82368375a1f6a0a3eba1317b525"),
        iv: hex!("28cee53e"),
    };

    let cmd: [u8; 5] = hex!("032001100e");
    assert!(encrypt_uart(&encryption_key, &cmd, u16::MAX as u32 + 1, None).is_err());
}

/// Malformed frames must produce errors, not panics.
#[test]
fn it_rejects_malformed_frames() {
    let encryption_key = EncryptionKey {
        key: hex!("462f3fcc74200ca5f77ee2a581c42af0"),
        iv: hex!("f8901a05"),
    };

    // Truncated frames used to panic on slicing.
    for len in 0..7usize {
        let truncated = vec![0x55u8; len];
        assert!(decrypt_uart(&encryption_key, &truncated).is_err());
    }

    // A frame whose checksum does not match must be rejected: the CCM tag does
    // not cover the size byte or the counter.
    let mut corrupted: [u8; 32] =
        hex!("55ab1001009a70888f3a27d8378bb07f7d8ce4cce88ab54a50595ad6c019c7f2");
    corrupted[2] ^= 0xff; // flip the unauthenticated size byte
    assert!(decrypt_uart(&encryption_key, &corrupted).is_err());
}

#[test]
fn it_decrypt_uart() {
    tracing_subscriber::fmt()
        .with_max_level(Level::DEBUG)
        .with_span_events(FmtSpan::CLOSE)
        .init();
    let encryption_key = EncryptionKey {
        key: hex!("462f3fcc74200ca5f77ee2a581c42af0"),
        iv: hex!("f8901a05"),
    };

    let encrypted: [u8; 32] =
        hex!("55ab1001009a70888f3a27d8378bb07f7d8ce4cce88ab54a50595ad6c019c7f2");
    let decrypted = decrypt_uart(&encryption_key, &encrypted).unwrap();
    let bytes = &decrypted[3..decrypted.len() - 4];
    let text = String::from_utf8_lossy(bytes);

    assert_eq!("26354/00467353", text)
}
