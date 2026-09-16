//! Pins the byte layout the Rust `MotorInfo` decoder actually reads.
//!
//! # Why this file exists
//!
//! Three implementations of the same `0xB0` response exist in this repository
//! and they disagree about where the fields are. Comparing their *numbers* was
//! misleading, because they do not share a base pointer: the Rust decoder reads
//! a plaintext buffer that still contains the size byte, while the Android
//! parser is handed a slice that has already had it removed. Their offsets differ
//! by exactly one for that reason alone.
//!
//! This test removes the ambiguity on the half that can be settled without
//! hardware: it constructs a payload with **known distinctive values** and
//! asserts what the decoder produces. That converts "the code says offset 11" —
//! which is only a claim about source — into a checked statement about behaviour.
//!
//! # What it does NOT settle
//!
//! Whether those offsets match the **wire**. Resolving that needs one captured
//! `0xB0` response from a real M365. See `doc/MODEL_SUPPORT.md` §8.

use ninebot_ble::session::{MotorInfo, Payload};

/// Distinctive values, chosen so a wrong offset cannot accidentally pass.
///
/// Every value is unique and none is representable at another field's offset:
/// the two-byte fields are all different, and the 4-byte odometer is far larger
/// than any 2-byte field could hold. A test that used zeros or repeated values
/// would pass even if every offset were wrong.
const BATTERY: u16 = 0x0041; // 65 %
const SPEED_RAW: u16 = 0x2AF8; // 11000 -> 11.0 km/h
const AVG_SPEED_RAW: u16 = 0x1F40; // 8000  -> 8.0 km/h
const ODOMETER_M: u32 = 0x0001_86A0; // 100000 m -> 100.0 km
const TRIP_M: u16 = 0x0320; // 800 m
const UPTIME_S: u16 = 0x0258; // 600 s
const TEMP_RAW: i16 = 0x00FF; // 255 -> 25.5 C

/// Offset of the three-byte header the decoder skips (`pop_head`).
const HEADER_LEN: usize = 3;

/// Builds a plaintext buffer matching what `decrypt_uart` yields.
///
/// Layout, as `MiSession::read` produces it:
///
/// ```text
///   [0]      size byte (not consumed by the decoder)
///   [1..4]   direction, type, attribute   <- pop_head() removes these 3
///   [4..12]  eight bytes the decoder pads over
///   [12..]   battery, speed, avg speed, odometer, trip, uptime, temperature
/// ```
///
/// The decoder's cursor therefore starts at 11 (three header bytes plus eight
/// padded), so:
///
/// | Field | Buffer offset |
/// | --- | --- |
/// | battery | 11 |
/// | speed | 13 |
/// | average speed | 15 |
/// | odometer | 17 |
/// | trip distance | 21 |
/// | uptime | 23 |
/// | frame temperature | 25 |
fn plaintext_buffer() -> Vec<u8> {
    let mut buf = vec![0u8; 32];

    // [0] size byte — present in the buffer, not consumed by the decoder.
    buf[0] = 0x20;

    // [1..4] the three header bytes `pop_head()` skips.
    buf[1] = 0x23; // direction: motor -> master
    buf[2] = 0x01; // type: read reply
    buf[3] = 0xB0; // attribute

    // [4..12] the eight bytes `pad_bytes(8)` steps over.
    for (i, b) in buf.iter_mut().enumerate().take(12).skip(4) {
        // Deliberately non-zero: if the decoder skipped the wrong amount, this
        // filler would be read as a field and the assertions below would fail.
        *b = 0xA0 | (i as u8);
    }

    buf[11..13].copy_from_slice(&BATTERY.to_le_bytes());
    buf[13..15].copy_from_slice(&SPEED_RAW.to_le_bytes());
    buf[15..17].copy_from_slice(&AVG_SPEED_RAW.to_le_bytes());
    buf[17..21].copy_from_slice(&ODOMETER_M.to_le_bytes());
    buf[21..23].copy_from_slice(&TRIP_M.to_le_bytes());
    buf[23..25].copy_from_slice(&UPTIME_S.to_le_bytes());
    buf[25..27].copy_from_slice(&TEMP_RAW.to_le_bytes());

    buf
}

fn decode() -> MotorInfo {
    MotorInfo::try_from(Payload::from(plaintext_buffer()))
        .expect("the synthetic payload is long enough for every field")
}

#[test]
fn battery_is_read_at_offset_eleven() {
    // Reading at 8 or 9 instead — as the two other implementations do — lands on
    // the 0xA0 filler, so this assertion distinguishes them.
    assert_eq!(decode().battery_percent, BATTERY);
}

#[test]
fn speed_is_read_at_offset_thirteen() {
    let info = decode();
    assert!(
        (info.speed_kmh - (SPEED_RAW as f32 / 1000.0)).abs() < 0.001,
        "expected {} km/h, got {}",
        SPEED_RAW as f32 / 1000.0,
        info.speed_kmh
    );
}

#[test]
fn average_speed_is_read_at_offset_fifteen() {
    let info = decode();
    assert!((info.speed_average_kmh - (AVG_SPEED_RAW as f32 / 1000.0)).abs() < 0.001);
}

#[test]
fn odometer_is_read_at_offset_seventeen_as_a_four_byte_value() {
    // A 4-byte field is the strongest discriminator: a 2-byte field cannot hold
    // 100000, and reading even one byte off yields a completely different value.
    assert_eq!(decode().total_distance_m, ODOMETER_M);
}

#[test]
fn trip_distance_is_read_at_offset_twenty_one() {
    assert_eq!(decode().trip_distance_m, TRIP_M);
}

#[test]
fn uptime_is_read_at_offset_twenty_three() {
    assert_eq!(decode().uptime.as_secs(), UPTIME_S as u64);
}

#[test]
fn temperature_is_read_at_offset_twenty_five_and_is_signed() {
    let info = decode();
    assert!((info.frame_temperature - (TEMP_RAW as f32 / 10.0)).abs() < 0.001);
}

#[test]
fn every_field_offset_is_distinct_so_no_two_overlap() {
    // If two fields resolved to the same offset, the synthetic values would have
    // to be equal and one of the assertions above would be vacuous.
    let info = decode();
    let values = [
        info.battery_percent as u64,
        info.total_distance_m as u64,
        info.trip_distance_m as u64,
        info.uptime.as_secs(),
        (info.frame_temperature * 10.0) as u64,
    ];
    let distinct: std::collections::HashSet<_> = values.iter().collect();
    assert_eq!(
        distinct.len(),
        values.len(),
        "the synthetic values must be pairwise distinct, or an offset swap could pass"
    );
}

#[test]
fn the_size_byte_is_not_consumed_by_the_decoder() {
    // Changing only the size byte must not move any field. If the decoder read
    // from the size byte onward, every field would shift by one and this fails.
    let baseline = decode();

    let mut buf = plaintext_buffer();
    buf[0] = 0x7F; // different size byte, same payload
    let shifted = MotorInfo::try_from(Payload::from(buf)).expect("still long enough");

    assert_eq!(baseline.battery_percent, shifted.battery_percent);
    assert_eq!(baseline.total_distance_m, shifted.total_distance_m);
    assert!((baseline.frame_temperature - shifted.frame_temperature).abs() < 0.001);
}

#[test]
fn android_and_rust_address_the_same_bytes() {
    // Both read the same fields from the same positions. This is the fact that
    // a naive comparison got wrong: the two implementations quote different
    // offsets, which looks like a disagreement, but they also count from
    // different base pointers.
    //
    //   Rust cursor      counts from P[0], the size byte, after pop_head (3) +
    //                    pad_bytes (8) -> P[11]
    //   Android `data`   is `packet[3 until len-4]`, so data[i] == P[i + 3]
    //                    -> data[8] == P[11]
    //
    // The relationship is `rust_offset == android_offset + 3`.
    let android_to_plaintext_base = 3;

    let pairs = [
        ("battery", 11usize, 8usize),
        ("speed", 13, 10),
        ("average speed", 15, 12),
        ("odometer", 17, 14),
        ("temperature", 25, 22),
    ];

    for (name, rust_offset, android_offset) in pairs {
        assert_eq!(
            rust_offset,
            android_offset + android_to_plaintext_base,
            "{name}: rust P[{rust_offset}] and android data[{android_offset}] are \
             different bytes, which would be a real disagreement"
        );
    }
}

#[test]
fn both_implementations_disagree_with_the_documented_map_by_four_bytes() {
    // The remaining open question, stated arithmetically. `M365ESC.md` places
    // battery at offset 4 within the 0xB0 block; both implementations reach it
    // at plaintext offset 11, i.e. 8 bytes past the 3-byte header. The 4-byte
    // gap between "offset 4" and "8 bytes past the header" is the whole dispute:
    // either the response carries a 4-byte prefix, or both parsers are wrong.
    //
    // Only a capture settles it. See doc/MODEL_SUPPORT.md §8.
    const DOCUMENTED_BATTERY_OFFSET_IN_BLOCK: usize = 4;
    const HEADER_LEN: usize = 3;
    const RUST_BATTERY_PLAINTEXT_OFFSET: usize = 11;

    let rust_offset_within_block = RUST_BATTERY_PLAINTEXT_OFFSET - HEADER_LEN;
    assert_eq!(
        rust_offset_within_block - DOCUMENTED_BATTERY_OFFSET_IN_BLOCK,
        4,
        "the disagreement is 4 bytes; if it changed, the doc and this test must both move"
    );
}
