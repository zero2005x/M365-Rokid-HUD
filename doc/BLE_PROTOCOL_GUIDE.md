# M365 BLE Communication Protocol

A comprehensive guide to Bluetooth Low Energy (BLE) communication with Xiaomi/Ninebot M365 electric scooters.

---

## Table of Contents

1. [Overview](#overview)
2. [BLE Services and Characteristics](#ble-services-and-characteristics)
3. [Connection Flow](#connection-flow)
4. [Registration (First-Time Pairing)](#registration-first-time-pairing)
5. [Login (Subsequent Connections)](#login-subsequent-connections)
6. [Encrypted UART Communication](#encrypted-uart-communication)
7. [Telemetry Data Queries](#telemetry-data-queries)
8. [Data Frame Format](#data-frame-format)
9. [Cryptographic Implementation](#cryptographic-implementation)
10. [Troubleshooting](#troubleshooting)

---

## Overview

The M365 scooter uses a sophisticated BLE protocol with end-to-end encryption. Communication involves:

1. **Discovery** - Find M365 scooters via BLE advertising
2. **Registration** - One-time ECDH key exchange to obtain authentication token
3. **Login** - Establish encrypted session using saved token
4. **UART Communication** - Send/receive encrypted commands and telemetry

### Cryptographic Stack

| Component      | Algorithm              | Purpose                                |
| -------------- | ---------------------- | -------------------------------------- |
| Key Exchange   | ECDH (SECP256R1/P-256) | Generate shared secret                 |
| Key Derivation | HKDF-SHA256            | Derive session keys from shared secret |
| Authentication | HMAC-SHA256            | Message authentication codes           |
| Encryption     | AES-128-CCM            | Authenticated encryption for UART data |

---

## BLE Services and Characteristics

### Nordic UART Service (NUS)

Used for encrypted command/telemetry communication after login.

| Name        | UUID                                   | Properties |
| ----------- | -------------------------------------- | ---------- |
| Service     | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | -          |
| TX (Write)  | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write      |
| RX (Notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify     |

### Xiaomi Authentication Service

Used for registration and login handshakes.

| Name           | UUID                                   | Properties    |
| -------------- | -------------------------------------- | ------------- |
| Service        | `0000fe95-0000-1000-8000-00805f9b34fb` | -             |
| UPNP (Control) | `00000010-0000-1000-8000-00805f9b34fb` | Write, Notify |
| AVDTP (Data)   | `00000019-0000-1000-8000-00805f9b34fb` | Write, Notify |

---

## Connection Flow

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. BLE Scan (filter: "MIScooter")         │
       │◄──────────────────────────────────────────►│
       │                                            │
       │  2. GATT Connect                           │
       │───────────────────────────────────────────►│
       │                                            │
       │  3. Discover Services                      │
       │───────────────────────────────────────────►│
       │                                            │
       │  4. Enable Notifications (UPNP, AVDTP)     │
       │───────────────────────────────────────────►│
       │                                            │
       │  5. Registration OR Login                  │
       │◄──────────────────────────────────────────►│
       │                                            │
       │  6. Enable Notifications (UART RX)         │
       │───────────────────────────────────────────►│
       │                                            │
       │  7. Encrypted UART Communication           │
       │◄──────────────────────────────────────────►│
       │                                            │
```

### Step-by-Step

1. **Scan for Devices**

   - Filter by device name starting with "MIScooter"
   - Advertisement contains UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`

2. **GATT Connection**

   ```kotlin
   val gatt = device.connectGatt(context, false, gattCallback)
   gatt.requestMtu(512)  // Request larger MTU for efficiency
   gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
   ```

3. **Enable Notifications**

   - Enable notifications on `AUTH_UPNP` characteristic
   - Enable notifications on `AUTH_AVDTP` characteristic

4. **Authentication**

   - If first time: Perform [Registration](#registration-first-time-pairing)
   - Otherwise: Perform [Login](#login-subsequent-connections)

5. **Enable UART Notifications**
   - Enable notifications on `UART_RX` for telemetry responses

---

## Registration (First-Time Pairing)

Registration is a **one-time process** to establish trust between the client and scooter. This generates a 12-byte authentication token that must be saved securely.

> ⚠️ **Warning**: Registration will unpair the scooter from all other apps (e.g., Mi Home).

### Registration Sequence

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. CMD_GET_INFO (A2 00 00 00)             │
       │───────────────────────────────────────────►│ UPNP
       │                                            │
       │  2. Remote Info (MiParcel)                 │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  3. Generate ECDH Key Pair                 │
       │  (local operation)                         │
       │                                            │
       │  4. CMD_SET_KEY (15 00 00 00)              │
       │───────────────────────────────────────────►│ UPNP
       │                                            │
       │  5. CMD_SEND_DATA (00 00 00 03 04 00)      │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  6. RCV_RDY (00 00 01 01)                  │
       │◄───────────────────────────────────────────│
       │                                            │
       │  ⚠️ USER PRESSES SCOOTER POWER BUTTON ⚠️   │
       │                                            │
       │  7. Send Public Key (MiParcel)             │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  8. RCV_OK (00 00 01 00)                   │
       │◄───────────────────────────────────────────│
       │                                            │
       │  9. Remote Public Key (MiParcel)           │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  10. Calculate Shared Secret & Derive Keys │
       │  (local operation)                         │
       │                                            │
       │  11. CMD_SEND_DID (00 00 00 00 02 00)      │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  12. RCV_RDY (00 00 01 01)                 │
       │◄───────────────────────────────────────────│
       │                                            │
       │  13. Send DID Ciphertext (MiParcel)        │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  14. RCV_OK (00 00 01 00)                  │
       │◄───────────────────────────────────────────│
       │                                            │
       │  15. CMD_AUTH (13 00 00 00)                │
       │───────────────────────────────────────────►│ UPNP
       │                                            │
       │  16. RCV_AUTH_OK (11 00 00 00)             │
       │◄───────────────────────────────────────────│
       │                                            │
       │  ✅ SAVE TOKEN (12 bytes)                  │
       │                                            │
```

### Key Derivation During Registration

```
shared_secret = ECDH(remote_public_key, my_private_key)
derived_key   = HKDF-SHA256(shared_secret, info="mible-setup-info", length=44)

token     = derived_key[0:12]    # 12 bytes - SAVE THIS!
bind_key  = derived_key[12:28]   # 16 bytes
a_key     = derived_key[28:44]   # 16 bytes

# Encrypt Device ID
nonce     = remote_info[0:4]     # From step 2
aad       = "devID"
plaintext = remote_info[4:]
did_ct    = AES-CCM-Encrypt(a_key, plaintext, nonce, aad)
```

### Important Notes

- **User Action Required**: The scooter waits for the user to press the power button before accepting the public key. Allow 30+ seconds timeout.
- **Token Storage**: Store the token securely (e.g., Android `EncryptedSharedPreferences`).
- **One Scooter Per App**: Each registration invalidates previous pairings.

---

## Login (Subsequent Connections)

Login establishes an encrypted session using the previously saved token. This is required for every new connection.

### Login Sequence

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. CMD_LOGIN (24 00 00 00)                │
       │───────────────────────────────────────────►│ UPNP
       │                                            │
       │  2. CMD_SEND_KEY (00 00 00 0B 01 00)       │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  3. RCV_RDY (00 00 01 01)                  │
       │◄───────────────────────────────────────────│
       │                                            │
       │  4. Send Random Key (16 bytes, MiParcel)   │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  5. RCV_OK (00 00 01 00)                   │
       │◄───────────────────────────────────────────│
       │                                            │
       │  6. Remote Random Key (MiParcel)           │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  7. Remote Info / HMAC (MiParcel)          │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  8. Derive Session Keys                    │
       │  (local operation)                         │
       │                                            │
       │  9. Verify Remote Info                     │
       │  (local operation)                         │
       │                                            │
       │  10. CMD_SEND_INFO (00 00 00 0A 02 00)     │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  11. RCV_RDY (00 00 01 01)                 │
       │◄───────────────────────────────────────────│
       │                                            │
       │  12. Send Login Data / HMAC (MiParcel)     │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  13. RCV_OK (00 00 01 00)                  │
       │◄───────────────────────────────────────────│
       │                                            │
       │  14. LOGIN_OK (21 00 00 00)                │
       │◄───────────────────────────────────────────│
       │                                            │
       │  ✅ SESSION ESTABLISHED                    │
       │                                            │
```

### Session Key Derivation

```
my_random    = random(16)     # Generated in step 4
remote_random = received      # Received in step 6

salt     = my_random + remote_random
salt_inv = remote_random + my_random

derived = HKDF-SHA256(token, salt, info="mible-login-info", length=40)

dev_key = derived[0:16]    # Key for decrypting scooter messages
app_key = derived[16:32]   # Key for encrypting client messages
dev_iv  = derived[32:36]   # IV for decryption (4 bytes)
app_iv  = derived[36:40]   # IV for encryption (4 bytes)

# Calculate HMACs for verification
info               = HMAC-SHA256(app_key, salt)       # Send to scooter
expected_remote    = HMAC-SHA256(dev_key, salt_inv)   # Verify from scooter
```

### Session Keys Summary

| Key       | Size     | Purpose                       |
| --------- | -------- | ----------------------------- |
| `app_key` | 16 bytes | Encrypt messages TO scooter   |
| `dev_key` | 16 bytes | Decrypt messages FROM scooter |
| `app_iv`  | 4 bytes  | Nonce prefix for encryption   |
| `dev_iv`  | 4 bytes  | Nonce prefix for decryption   |

---

## Encrypted UART Communication

After successful login, all UART communication is encrypted using AES-128-CCM.

### Encryption (Client → Scooter)

```
# Build nonce (12 bytes)
nonce = app_iv + [0x00, 0x00, 0x00, 0x00] + counter_le(4)

# Encrypt
ciphertext = AES-CCM-Encrypt(app_key, plaintext, nonce)

# Frame format (output)
frame = [0x55, 0xAB] + length(2) + ciphertext + crc16(2)
```

### Decryption (Scooter → Client)

```
# Build nonce (12 bytes)
nonce = dev_iv + [0x00, 0x00, 0x00, 0x00] + counter_le(4)

# Decrypt (strip 55 AB header and CRC first)
plaintext = AES-CCM-Decrypt(dev_key, ciphertext, nonce)
```

### Counter Management

> **Important**: In practice, the M365 scooter does **not** require incrementing counters. The counter can always be set to `0` for both encryption and decryption.

```kotlin
val counter = 0L  // Always use counter=0
val encrypted = native.encrypt(sessionPtr, payload, counter)
```

---

## Telemetry Data Queries

The Mi Home app queries the scooter cyclically using these commands:

| Command         | Address | Param             | Data Returned                            |
| --------------- | ------- | ----------------- | ---------------------------------------- |
| Motor Info      | `0xB0`  | `0x20` (32 bytes) | Speed, battery, temp, mileage, avg speed |
| Trip Info       | `0x3A`  | `0x04` (4 bytes)  | Trip seconds, trip meters                |
| Remaining Range | `0x25`  | `0x02` (2 bytes)  | Estimated remaining km                   |

### Building a Query Packet

For encrypted UART, the plaintext format is:

```
[size] [direction] [read/write] [attribute] [param...]

Where:
- size = param_length + 2
- direction = 0x20 (master to motor)
- read/write = 0x01 (read) or 0x03 (write)
- attribute = command address (e.g., 0xB0)
- param = parameter bytes
```

**Example: Query Motor Info (0xB0)**

```kotlin
val payload = byteArrayOf(
    0x03,       // size = 1 + 2 = 3
    0x20,       // direction: master to motor
    0x01,       // operation: read
    0xB0.toByte(), // attribute: motor info
    0x20        // param: read 32 bytes
)
```

### Parsing Motor Info Response (0xB0)

The decrypted response contains a standard M365 frame:

```
55 AA [len] 23 01 B0 [data...] [crc16]
```

Data layout (32 bytes starting from offset 6):

| Offset | Size | Field         | Description                                  |
| ------ | ---- | ------------- | -------------------------------------------- |
| 0-1    | 2    | error_code    | Error flags                                  |
| 2-3    | 2    | warning_code  | Warning flags                                |
| 4-5    | 2    | flags         | Status flags                                 |
| 6-7    | 2    | work_mode     | Operating mode                               |
| 8-9    | 2    | battery       | Battery % (0-100)                            |
| 10-11  | 2    | speed         | Current speed × 10 (m/h → km/h = value/1000) |
| 12-13  | 2    | avg_speed     | Average speed × 10                           |
| 14-17  | 4    | total_mileage | Total mileage in meters                      |
| 18-19  | 2    | reserved      | Unknown                                      |
| 20-21  | 2    | frame_temp    | Controller temperature × 10                  |
| 22-23  | 2    | reserved2     | Unknown                                      |

**Conversion formulas:**

```kotlin
val speed = (data[10].toUByte().toInt() + (data[11].toUByte().toInt() shl 8)) / 1000.0  // km/h
val battery = data[8].toUByte().toInt()  // %
val temp = (data[20].toUByte().toInt() + (data[21].toUByte().toInt() shl 8)) / 10.0  // °C
val mileage = ByteBuffer.wrap(data, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int / 1000.0  // km
```

### Parsing Trip Info Response (0x3A)

| Offset | Size | Field        | Description       |
| ------ | ---- | ------------ | ----------------- |
| 0-1    | 2    | trip_seconds | Seconds this trip |
| 2-3    | 2    | trip_meters  | Meters this trip  |

### Parsing Remaining Range Response (0x25)

| Offset | Size | Field           | Description                   |
| ------ | ---- | --------------- | ----------------------------- |
| 0-1    | 2    | remaining_range | Remaining range × 10 (meters) |

```kotlin
val remainingKm = value / 100.0  // Convert to km
```

---

## Data Frame Format

### Standard M365 Frame (Unencrypted/Legacy)

```
+-----+-----+-----+-----+-----+-----+--------+------+------+
| 55  | AA  |  L  |  D  |  T  |  C  | Data   | CK0  | CK1  |
+-----+-----+-----+-----+-----+-----+--------+------+------+
  │     │     │     │     │     │      │        └──────┴── Checksum
  │     │     │     │     │     │      └── Payload bytes
  │     │     │     │     │     └── Command/Attribute byte
  │     │     │     │     └── Type: 0x01=read, 0x03=write
  │     │     │     └── Device: 0x20=to motor, 0x23=from motor
  │     │     └── Length: payload + 2
  └─────┴── Magic header
```

### Encrypted UART Frame

```
+-----+-----+--------+------------------+------+------+
| 55  | AB  | Length |    Ciphertext    | CRC0 | CRC1 |
+-----+-----+--------+------------------+------+------+
  │     │       │            │              └──────┴── CRC-16
  │     │       │            └── AES-CCM encrypted payload
  │     │       └── Length (2 bytes, little-endian)
  └─────┴── Magic header (note: AB not AA)
```

### Checksum Calculation

```kotlin
fun calculateChecksum(data: ByteArray): Int {
    var sum = 0
    for (b in data) {
        sum += b.toUByte().toInt()
    }
    return sum.inv() and 0xFFFF
}

// CK0 = checksum & 0xFF (LSB)
// CK1 = (checksum >> 8) & 0xFF (MSB)
```

---

## Cryptographic Implementation

### ECDH Key Exchange (SECP256R1)

```rust
use p256::{PublicKey, SecretKey, ecdh::SharedSecret};

let my_secret = SecretKey::random(&mut OsRng);
let my_public = my_secret.public_key();

let remote_public = PublicKey::from_sec1_bytes(&remote_bytes)?;
let shared_secret = my_secret.diffie_hellman(&remote_public);
```

### HKDF Key Derivation

```rust
use hkdf::Hkdf;
use sha2::Sha256;

let hkdf = Hkdf::<Sha256>::new(None, shared_secret.as_bytes());
let mut derived = [0u8; 44];
hkdf.expand(b"mible-setup-info", &mut derived)?;
```

### AES-128-CCM Encryption

```rust
use ccm::{Ccm, aead::{Aead, KeyInit}};
use aes::Aes128;

type Aes128Ccm = Ccm<Aes128, U4, U12>;  // 4-byte tag, 12-byte nonce

let cipher = Aes128Ccm::new_from_slice(&key)?;
let nonce = build_nonce(iv, counter);
let ciphertext = cipher.encrypt(&nonce, payload)?;
```

---

## Troubleshooting

### Common Issues

| Problem                | Cause                             | Solution                                      |
| ---------------------- | --------------------------------- | --------------------------------------------- |
| Registration timeout   | User didn't press power button    | Increase timeout to 30s, show clear prompt    |
| GATT connection failed | BLE permissions not granted       | Request `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` |
| Login failed           | Token corrupted or expired        | Delete token, re-register                     |
| Decryption failed      | Wrong counter or keys             | Ensure counter=0, verify key derivation       |
| No telemetry data      | UART RX notifications not enabled | Enable notifications after login              |

### Debug Logging

Enable verbose logging to trace the protocol:

```kotlin
Log.d("ScooterRepo", "Tx UPNP: ${data.toHex()}")
Log.d("ScooterRepo", "Rx AVDTP: ${data.toHex()}")
Log.d("ScooterRepo", "Encrypted: ${encrypted.toHex()}")
Log.d("ScooterRepo", "Decrypted: ${decrypted.toHex()}")
```

### MiParcel Protocol

Data larger than 18 bytes is transferred using the MiParcel protocol:

**Header Frame:**

```
[.. .. .. .. LenL LenH]  # Last 2 bytes = total frames count
```

**Data Frames:**

```
[Index] [0x00] [Payload...]  # 18 bytes max payload per frame
```

**Acknowledgments:**

- `00 00 01 01` = RCV_RDY (ready to receive)
- `00 00 01 00` = RCV_OK (received successfully)

---

## References

- [M365 BLE Protocol (CamiAlfa)](https://github.com/CamiAlfa/M365-BLE-PROTOCOL)
- [btleplug - Rust BLE Library](https://github.com/deviceplug/btleplug)
- [Nordic UART Service Specification](https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/nrf/libraries/bluetooth_services/services/nus.html)

---

_Document Version: 1.0 | Last Updated: December 2024_
