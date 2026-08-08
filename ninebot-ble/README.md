# ninebot-ble

![MIT license](https://img.shields.io/github/license/zero2005x/ninebot-ble)
![Crates.io version](https://img.shields.io/crates/v/ninebot-ble)

A lightweight Rust library for BLE communication with Ninebot/Xiaomi electric scooters (M365, Mi Pro, etc.).

> 📖 **[中文文档 / Chinese Documentation](./doc/README_zh.md)**

## Features

- 🔍 **Scanner** - Find nearby M365 scooters
- 🔐 **Registration** - Pair with scooter using ECDH key exchange
- 🔑 **Login** - Authenticate with saved token
- 📊 **Read Data** - Battery, speed, distance, temperature, etc.
- ⚙️ **Settings** - Control cruise mode, tail light, KERS level
- 🎮 **Interactive Controller** - Real-time monitoring and control

## Supported Platforms

This library uses [btleplug](https://crates.io/crates/btleplug) for cross-platform BLE support:

- Windows 10/11
- macOS
- Linux
- iOS
- **Android** (via JNI)

## Android Integration

### Prerequisites

1. **Rust Toolchain**: Rust installed
2. **Android NDK**: Installed via Android Studio
3. **cargo-ndk**: Cargo plugin for Android compilation

### Setup Environment

Install `cargo-ndk` and required Android targets:

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
```

### Build Library

Compile the library for Android architectures:

```bash
# Build Release version (recommended)
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 -o ./jniLibs build --release
```

This generates `.so` files in the `jniLibs` directory (e.g., `arm64-v8a/libninebot_ble.so`).

### Android Studio Integration

1. **Copy Libraries**: Copy the generated `jniLibs` folder to your Android project's `app/src/main/` directory.

2. **Create JNI Class**: Create a corresponding Kotlin/Java class. The native method names must match those in `src/android_api.rs`. Currently configured for package `com.ninebot.ble` with class name `NativeLib`.

#### Kotlin Example (`NativeLib.kt`)

```kotlin
package com.ninebot.ble

class NativeLib {
    companion object {
        init {
            // Load the Rust compiled library (name matches Cargo.toml)
            System.loadLibrary("ninebot_ble")
        }
    }

    /**
     * Initialize and start scanning
     * @return Status message
     */
    external fun startScan(): String

    /**
     * Get list of scanned devices
     * @return Formatted as "name,mac;name,mac;" string
     */
    external fun getDevices(): String

    /**
     * Connect to specified device
     * @param macAddress Bluetooth MAC address
     * @return Connection status
     */
    external fun connect(macAddress: String): String

    /**
     * Get current speed (km/h)
     */
    external fun getCurrentSpeed(): String

    /**
     * Get average speed (km/h)
     */
    external fun getAverageSpeed(): String

    /**
     * Get battery voltage (V)
     */
    external fun getBatteryVoltage(): String

    /**
     * Get battery current (A)
     */
    external fun getBatteryAmperage(): String

    /**
     * Get battery percentage
     */
    external fun getBatteryPercentage(): String

    /**
     * Get complete battery information (JSON)
     */
    external fun getBatteryInfo(): String

    /**
     * Get complete motor information (JSON)
     */
    external fun getMotorInfo(): String
}
```

### Important Notes

1. **Permissions**: Your Android app needs Bluetooth permissions in `AndroidManifest.xml`:

   ```xml
   <uses-permission android:name="android.permission.BLUETOOTH" />
   <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
   <!-- Android 12+ -->
   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
   ```

2. **btleplug Initialization**: The Rust code includes `JNI_OnLoad` to initialize btleplug. Ensure the library is loaded before calling any functions.

3. **Threading**: Current native methods are blocking. Call them in background threads (`Dispatchers.IO`) or coroutines to avoid ANR (Application Not Responding).

## Supported Scooters

| Model             | Status       |
| ----------------- | ------------ |
| Xiaomi M365       | ✅ Supported |
| Xiaomi Mi 1S      | ✅ Supported |
| Xiaomi Mi Pro     | ✅ Supported |
| Xiaomi Mi Pro 2   | ✅ Supported |
| Xiaomi Mi Pro 3   | ✅ Supported |
| Clone controllers | ⚠️ Partial   |

## Quick Start

### Installation

Add to your `Cargo.toml`:

```toml
[dependencies]
ninebot-ble = "0.1.2"
```

### Examples

#### 1. Find MAC Address

```bash
cargo run --example scanner
```

Output:

```
INFO scanner: Found scooter nearby: MIScooter7353 with mac: C7:B8:DC:3B:A1:B2
```

#### 2. Register (First Time Only)

⚠️ **Warning:** Registering unpairs the device from all other apps!

```bash
cargo run --example register C7:B8:DC:3B:A1:B2
```

This saves the auth token to `.mi-token` file.

#### 3. Login

```bash
cargo run --example login C7:B8:DC:3B:A1:B2
```

#### 4. Read Information

```bash
cargo run --example about C7:B8:DC:3B:A1:B2
```

Output:

```
Battery info: BatteryInfo { capacity: 7392, percent: 63, voltage: 36.74 }
Serial number: 26354/00467353
Motor info: MotorInfo { speed_kmh: 0, total_distance_m: 1306083 }
```

#### 5. Interactive Controller

```bash
cargo run --example controller C7:B8:DC:3B:A1:B2
```

## BLE Protocol

### Services & Characteristics

| UUID           | Name         | Description               |
| -------------- | ------------ | ------------------------- |
| `FE95`         | AUTH Service | Xiaomi Authentication     |
| `0x0010`       | UPNP         | Command Control           |
| `0x0019`       | AVDTP        | Data Exchange             |
| `6e400002-...` | TX           | Write (Client → Scooter)  |
| `6e400003-...` | RX           | Notify (Scooter → Client) |

### UART Frame Format

```
+-----+-----+-----+-----+-----+-----+-------+------+------+
| 0x55| 0xAA|  L  |  D  |  T  |  C  |  ...  | CK0  | CK1  |
+-----+-----+-----+-----+-----+-----+-------+------+------+
  Header     Len   Dev   Cmd   Attr  Payload  Checksum
```

| Field       | Description                                        |
| ----------- | -------------------------------------------------- |
| `0x55 0xAA` | Frame header                                       |
| `L`         | Length = payload + 2                               |
| `D`         | Device: `0x20`=Master→Motor, `0x22`=Master→Battery |
| `T`         | Type: `0x01`=Read, `0x03`=Write                    |
| `CK0, CK1`  | Checksum = (sum of bytes from L) XOR 0xFFFF        |

## Cryptographic Flow

### Registration (Once)

```
Client                              Scooter
  │                                   │
  │──── CMD_GET_INFO ────────────────►│
  │◄──── Remote Info ─────────────────│
  │                                   │
  │  Generate ECDH KeyPair (P-256)    │
  │──── My Public Key ───────────────►│
  │◄──── Scooter Public Key ──────────│
  │                                   │
  │  Calculate:                       │
  │  - SharedSecret (ECDH)            │
  │  - Token, BindKey (HKDF-SHA256)   │
  │  - DID_CT (AES-CCM encrypted)     │
  │                                   │
  │──── DID_CT ──────────────────────►│
  │◄──── AUTH_OK ─────────────────────│
  │                                   │
  │  Save Token (12 bytes)            │
```

### Login (Every Connection)

```
Client                              Scooter
  │                                   │
  │──── CMD_LOGIN ───────────────────►│
  │──── Random Key (16 bytes) ───────►│
  │◄──── Remote Random Key ───────────│
  │◄──── Remote Info (32 bytes) ──────│
  │                                   │
  │  Derive Keys (HKDF-SHA256):       │
  │  - DevKey, AppKey (AES-128)       │
  │  - DevIV, AppIV (4 bytes each)    │
  │                                   │
  │  Verify: HMAC(DevKey, salt)       │
  │                                   │
  │──── DID Info ────────────────────►│
  │◄──── LOGIN_OK ────────────────────│
```

### UART Encryption (AES-128-CCM)

```
Encrypt (Client → Scooter):
  nonce = AppIV + "0000" + counter
  ciphertext = AES-CCM(AppKey, message, nonce)

Decrypt (Scooter → Client):
  nonce = DevIV + "0000" + counter
  plaintext = AES-CCM(DevKey, ciphertext, nonce)
```

## Available Data

| Category | Data                                                |
| -------- | --------------------------------------------------- |
| Motor    | Speed, Average Speed, Distance, Uptime, Temperature |
| Battery  | Voltage, Current, Capacity, %, Cell Voltages, Temp  |
| Settings | Cruise Mode, Tail Light, KERS Level                 |
| Info     | Serial Number, PIN, Firmware Version                |

## API Reference

### Scanner

```rust
use ninebot_ble::ScooterScanner;

let mut scanner = ScooterScanner::new().await?;
let scooter = scanner.wait_for(&mac_address).await?;
let device = scanner.peripheral(&scooter).await?;
```

### Registration

```rust
use ninebot_ble::{RegistrationRequest, ConnectionHelper};

let connection = ConnectionHelper::new(&device);
connection.reconnect().await?;
let mut request = RegistrationRequest::new(&device).await?;
let token = request.start().await?;
```

### Login & Session

```rust
use ninebot_ble::{LoginRequest, ConnectionHelper};

let connection = ConnectionHelper::new(&device);
connection.reconnect().await?;
let mut login = LoginRequest::new(&device, &token).await?;
let session = login.start().await?;

// Read data
let battery = session.battery_info().await?;
let motor = session.motor_info().await?;
```

## Project Structure

```
ninebot-ble/
├── src/
│   ├── lib.rs              # Library entry point
│   ├── scanner.rs          # BLE device scanner
│   ├── connection.rs       # BLE connection management
│   ├── clone_connection.rs # Alternative connection handler
│   ├── protocol.rs         # MiAuth protocol implementation
│   ├── register.rs         # Device registration
│   ├── login.rs            # Authentication
│   ├── mi_crypto.rs        # Cryptographic operations
│   ├── consts.rs           # Constants
│   ├── android_api.rs      # Android JNI interface
│   └── session/            # Session commands
│       ├── mod.rs          # Module exports
│       ├── mi_session.rs   # Session management
│       ├── battery.rs      # Battery info
│       ├── info.rs         # General info
│       ├── settings.rs     # Scooter settings
│       ├── commands.rs     # Command definitions
│       ├── payload.rs      # Payload parsing
│       └── travel.rs       # Travel/distance info
├── examples/
│   ├── scanner.rs          # Find scooters
│   ├── register.rs         # Register with scooter
│   ├── login.rs            # Login example
│   ├── about.rs            # Read all info
│   ├── settings.rs         # Change settings
│   ├── controller.rs       # Interactive controller
│   ├── monitor.rs          # Monitoring mode
│   └── speed.rs            # Speed monitoring
└── tests/
    ├── crypto_test.rs
    ├── motor_info_test.rs
    ├── responses_test.rs
    └── uart_test.rs
```

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.

## Acknowledgments

- Based on research from [CamiAlfa's M365-BLE-PROTOCOL](https://github.com/CamiAlfa/M365-BLE-PROTOCOL)
- Uses [btleplug](https://crates.io/crates/btleplug) for cross-platform BLE support
