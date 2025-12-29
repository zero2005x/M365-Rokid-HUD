# M365 BLE App

![Android](https://img.shields.io/badge/Android-29+-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue)
![Rust](https://img.shields.io/badge/Rust-FFI-orange?logo=rust)

A modern Android application for connecting to and monitoring Xiaomi/Ninebot M365 electric scooters via Bluetooth Low Energy (BLE).

> 📖 **[繁體中文](doc/README_zh-TW.md)** | **[简体中文](doc/README_zh-CN.md)**

---

## ✨ Features

- 🔍 **BLE Scanner** - Discover nearby M365 scooters automatically
- 🔐 **Secure Registration** - ECDH key exchange for first-time pairing
- 🔑 **Token-based Login** - Fast reconnection with saved authentication
- 📊 **Real-time Telemetry** - Monitor speed, battery, temperature, and more
- 🌍 **Multi-language Support** - 11 languages supported
- 🎨 **Modern UI** - Built with Jetpack Compose and Material3 design

## 📸 Screenshots

|    Scan Screen    |    Dashboard    |    Details     |
| :---------------: | :-------------: | :------------: |
| Discover scooters | Real-time stats | Full telemetry |

## 🌐 Supported Languages

| Language                       | Code           |
| ------------------------------ | -------------- |
| English                        | `en` (default) |
| 简体中文 (Simplified Chinese)  | `zh-CN`        |
| 繁體中文 (Traditional Chinese) | `zh-TW`        |
| Español (Spanish)              | `es`           |
| Français (French)              | `fr`           |
| 日本語 (Japanese)              | `ja`           |
| Русский (Russian)              | `ru`           |
| 한국어 (Korean)                | `ko`           |
| Українська (Ukrainian)         | `uk`           |
| العربية (Arabic)               | `ar`           |
| Italiano (Italian)             | `it`           |

## 🏗️ Architecture

```
M365-Rokid-HUD/
├── app/                          # Android Application
│   └── src/main/
│       ├── java/com/m365bleapp/
│       │   ├── ble/              # BLE Manager
│       │   ├── ffi/              # Rust FFI bindings
│       │   ├── repository/       # Data layer (ScooterRepository)
│       │   ├── ui/               # Jetpack Compose screens
│       │   └── utils/            # Utilities (logging, etc.)
│       ├── res/
│       │   ├── values/           # English strings (default)
│       │   ├── values-zh-rCN/    # Simplified Chinese
│       │   ├── values-zh-rTW/    # Traditional Chinese
│       │   └── values-*/         # Other languages
│       └── jniLibs/              # Native .so libraries
├── ninebot-ffi/                  # Rust FFI library for Android
│   └── src/
│       ├── lib.rs                # JNI exports
│       └── mi_crypto.rs          # Cryptographic functions
└── ninebot-ble/                  # Core Rust BLE library
    └── src/
        ├── connection.rs         # BLE connection handling
        ├── protocol.rs           # M365 protocol implementation
        ├── mi_crypto.rs          # ECDH, HKDF, AES-CCM encryption
        └── ...
```

## 🔐 Protocol Overview

The app implements the Xiaomi M365 encrypted BLE protocol:

| Component      | Algorithm        | Description                            |
| -------------- | ---------------- | -------------------------------------- |
| Key Exchange   | ECDH (SECP256R1) | Elliptic curve key exchange            |
| Key Derivation | HKDF-SHA256      | Derive session keys from shared secret |
| Authentication | HMAC-SHA256      | Message authentication                 |
| Encryption     | AES-128-CCM      | Authenticated encryption for UART data |

### Telemetry Data

| Query      | Address | Data                                             |
| ---------- | ------- | ------------------------------------------------ |
| Motor Info | `0xB0`  | Speed, battery %, controller temp, total mileage |
| Trip Info  | `0x3A`  | Current trip time, distance                      |
| Range      | `0x25`  | Estimated remaining range (km)                   |

## 🛠️ Requirements

### Android App

- **Minimum SDK**: Android 10 (API 29)
- **Target SDK**: Android 15 (API 35)
- **Permissions**: Bluetooth, Location (for BLE scanning)

### Build Environment

- Android Studio Iguana or later
- Kotlin 1.9+
- Rust toolchain (for building native libraries)
- Android NDK

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/your-repo/M365-Rokid-HUD.git
cd M365-Rokid-HUD
```

### 2. Build Rust Libraries (if needed)

```bash
cd ninebot-ffi

# Install cargo-ndk if not already installed
cargo install cargo-ndk

# Add Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Build for Android
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
```

### 3. Build Android App

Open the project in Android Studio and build:

```bash
./gradlew assembleDebug
```

Or directly install to connected device:

```bash
./gradlew installDebug
```

## 📖 Usage

1. **Launch the app** and grant Bluetooth/Location permissions
2. **Scan** for nearby M365 scooters
3. **First-time pairing**: Check "Register" and press scooter power button when prompted
4. **Subsequent connections**: Just tap the device to connect
5. **View telemetry** on the Dashboard or Details screen

> ⚠️ **Warning**: Registration will unpair the scooter from other apps (e.g., Mi Home). Only register devices you own.

## 📁 Project Components

| Module        | Description                                      |
| ------------- | ------------------------------------------------ |
| `app`         | Main Android application with Jetpack Compose UI |
| `ninebot-ffi` | Rust library with JNI bindings for Android       |
| `ninebot-ble` | Core Rust library for M365 BLE protocol          |

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [btleplug](https://github.com/deviceplug/btleplug) - Cross-platform BLE library for Rust
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI toolkit
- M365 reverse engineering community

---

**Disclaimer**: This project is for educational purposes only. Use at your own risk. The authors are not responsible for any damage to your scooter or violations of manufacturer warranties.
