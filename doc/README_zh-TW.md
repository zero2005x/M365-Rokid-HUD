# M365 藍牙應用

![Android](https://img.shields.io/badge/Android-29+-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue)
![Rust](https://img.shields.io/badge/Rust-FFI-orange?logo=rust)

一款現代化的 Android 應用程式，用於透過藍牙低功耗（BLE）連接和監控小米/九號 M365 電動滑板車。

> 📖 **[English](README.md)** | **[简体中文](README_zh-CN.md)**

---

## ✨ 功能特點

- 🔍 **藍牙掃描** - 自動發現附近的 M365 滑板車
- 🔐 **安全註冊** - ECDH 金鑰交換進行首次配對
- 🔑 **Token 登入** - 使用已儲存的認證快速重新連接
- 📊 **即時遙測** - 監控速度、電量、溫度等數據
- 🌍 **多語言支援** - 支援 11 種語言
- 🎨 **現代化介面** - 採用 Jetpack Compose 和 Material3 設計

## 📸 螢幕截圖

|  掃描畫面  |  儀表板  | 詳細資訊 |
| :--------: | :------: | :------: |
| 發現滑板車 | 即時數據 | 完整遙測 |

## 🌐 支援的語言

| 語言                   | 代碼         |
| ---------------------- | ------------ |
| English（英文）        | `en`（預設） |
| 简体中文               | `zh-CN`      |
| 繁體中文               | `zh-TW`      |
| Español（西班牙文）    | `es`         |
| Français（法文）       | `fr`         |
| 日本語（日文）         | `ja`         |
| Русский（俄文）        | `ru`         |
| 한국어（韓文）         | `ko`         |
| Українська（烏克蘭文） | `uk`         |
| العربية（阿拉伯文）    | `ar`         |
| Italiano（義大利文）   | `it`         |

## 🏗️ 架構

```
M365-Rokid-HUD/
├── app/                          # Android 應用程式
│   └── src/main/
│       ├── java/com/m365bleapp/
│       │   ├── ble/              # BLE 管理器
│       │   ├── ffi/              # Rust FFI 綁定
│       │   ├── repository/       # 資料層（ScooterRepository）
│       │   ├── ui/               # Jetpack Compose 畫面
│       │   └── utils/            # 工具類（日誌等）
│       ├── res/
│       │   ├── values/           # 英文字串（預設）
│       │   ├── values-zh-rCN/    # 簡體中文
│       │   ├── values-zh-rTW/    # 繁體中文
│       │   └── values-*/         # 其他語言
│       └── jniLibs/              # 原生 .so 函式庫
├── ninebot-ffi/                  # Android 用 Rust FFI 函式庫
│   └── src/
│       ├── lib.rs                # JNI 匯出
│       └── mi_crypto.rs          # 加密函式
└── ninebot-ble/                  # 核心 Rust BLE 函式庫
    └── src/
        ├── connection.rs         # BLE 連接處理
        ├── protocol.rs           # M365 協議實作
        ├── mi_crypto.rs          # ECDH、HKDF、AES-CCM 加密
        └── ...
```

## 🔐 協議概述

本應用實作了小米 M365 加密 BLE 協議：

| 元件     | 演算法           | 說明                   |
| -------- | ---------------- | ---------------------- |
| 金鑰交換 | ECDH (SECP256R1) | 橢圓曲線金鑰交換       |
| 金鑰派生 | HKDF-SHA256      | 從共享密鑰派生會話金鑰 |
| 認證     | HMAC-SHA256      | 訊息認證               |
| 加密     | AES-128-CCM      | UART 資料的認證加密    |

### 遙測數據

| 查詢     | 位址   | 資料                                 |
| -------- | ------ | ------------------------------------ |
| 馬達資訊 | `0xB0` | 速度、電量百分比、控制器溫度、總里程 |
| 行程資訊 | `0x3A` | 當前行程時間、距離                   |
| 續航里程 | `0x25` | 預估剩餘續航里程（公里）             |

## 🛠️ 系統需求

### Android 應用

- **最低 SDK**：Android 10（API 29）
- **目標 SDK**：Android 15（API 35）
- **權限**：藍牙、位置（用於 BLE 掃描）

### 建置環境

- Android Studio Iguana 或更新版本
- Kotlin 1.9+
- Rust 工具鏈（用於建置原生函式庫）
- Android NDK

## 🚀 開始使用

### 1. 複製專案

```bash
git clone https://github.com/your-repo/M365-Rokid-HUD.git
cd M365-Rokid-HUD
```

### 2. 建置 Rust 函式庫（如需要）

```bash
cd ninebot-ffi

# 如尚未安裝，安裝 cargo-ndk
cargo install cargo-ndk

# 新增 Android 目標
rustup target add aarch64-linux-android armv7-linux-androideabi

# 為 Android 建置
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
```

### 3. 建置 Android 應用

在 Android Studio 開啟專案並建置：

```bash
./gradlew assembleDebug
```

或直接安裝到已連接的裝置：

```bash
./gradlew installDebug
```

## 📖 使用方式

1. **啟動應用程式**並授予藍牙/位置權限
2. **掃描**附近的 M365 滑板車
3. **首次配對**：勾選「註冊」，並在提示時按下滑板車電源鍵
4. **後續連接**：直接點擊裝置即可連接
5. 在儀表板或詳細資訊畫面**查看遙測數據**

> ⚠️ **警告**：註冊將使滑板車與其他應用程式（如米家）解除配對。請只註冊您擁有的裝置。

## 📁 專案組成

| 模組          | 說明                                            |
| ------------- | ----------------------------------------------- |
| `app`         | 使用 Jetpack Compose UI 的主要 Android 應用程式 |
| `ninebot-ffi` | 為 Android 提供 JNI 綁定的 Rust 函式庫          |
| `ninebot-ble` | M365 BLE 協議的核心 Rust 函式庫                 |

## 🤝 貢獻

歡迎貢獻！請隨時提交 Pull Request。

## 📄 授權

本專案採用 MIT 授權 - 詳見 [LICENSE](LICENSE) 檔案。

## 🙏 致謝

- [btleplug](https://github.com/deviceplug/btleplug) - Rust 跨平台 BLE 函式庫
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 現代化 Android UI 工具包
- M365 逆向工程社群

---

**免責聲明**：本專案僅供教育用途。使用風險自負。作者不對您的滑板車的任何損壞或違反製造商保固承擔責任。
