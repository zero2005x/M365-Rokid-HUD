# M365 蓝牙应用

![Android](https://img.shields.io/badge/Android-29+-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue)
![Rust](https://img.shields.io/badge/Rust-FFI-orange?logo=rust)

一款现代化的 Android 应用程序，用于通过蓝牙低功耗（BLE）连接和监控小米/九号 M365 电动滑板车。

> 📖 **[English](../README.md)** | **[繁體中文](README_zh-TW.md)**

---

## ✨ 功能特点

- 🔍 **蓝牙扫描** - 自动发现附近的 M365 滑板车
- 🔐 **安全注册** - ECDH 密钥交换进行首次配对
- 🔑 **Token 登录** - 使用已保存的认证快速重新连接
- 📊 **实时遥测** - 监控速度、电量、温度等数据
- 🌍 **多语言支持** - 支持 11 种语言
- 🎨 **现代化界面** - 采用 Jetpack Compose 和 Material3 设计

## 📸 屏幕截图

|  扫描界面  |  仪表盘  | 详细信息 |
| :--------: | :------: | :------: |
| 发现滑板车 | 实时数据 | 完整遥测 |

## 🌐 支持的语言

| 语言                   | 代码         |
| ---------------------- | ------------ |
| English（英文）        | `en`（默认） |
| 简体中文               | `zh-CN`      |
| 繁體中文（繁体中文）   | `zh-TW`      |
| Español（西班牙语）    | `es`         |
| Français（法语）       | `fr`         |
| 日本語（日语）         | `ja`         |
| Русский（俄语）        | `ru`         |
| 한국어（韩语）         | `ko`         |
| Українська（乌克兰语） | `uk`         |
| العربية（阿拉伯语）    | `ar`         |
| Italiano（意大利语）   | `it`         |

## 🏗️ 架构

```
M365-Rokid-HUD/
├── app/                          # 主要 Android 应用程序（手机端）
│   └── src/main/
│       ├── java/com/m365bleapp/
│       │   ├── ble/              # BLE 管理器（扫描、GATT）
│       │   ├── ffi/              # Rust FFI 绑定
│       │   ├── gateway/          # GATT 服务器，转发数据至眼镜端
│       │   ├── repository/       # 数据层（ScooterRepository）
│       │   ├── ui/               # Jetpack Compose 界面
│       │   │   ├── ScanScreen.kt       # 设备扫描
│       │   │   ├── DashboardScreen.kt  # 实时遥测仪表盘
│       │   │   ├── ScooterInfoScreen.kt# 详细信息
│       │   │   ├── LoggingScreen.kt    # 日志设置
│       │   │   ├── LogViewerScreen.kt  # 日志文件查看器
│       │   │   └── LanguageScreen.kt   # 语言选择
│       │   └── utils/            # 工具类（TelemetryLogger 等）
│       ├── res/
│       │   ├── values/           # 英文字符串（默认）
│       │   ├── values-zh-rCN/    # 简体中文
│       │   ├── values-zh-rTW/    # 繁体中文
│       │   └── values-*/         # 其他语言
│       └── jniLibs/              # 原生 .so 库
├── glass-hud/                    # Rokid AR 眼镜 HUD 客户端
│   └── src/main/
│       └── java/com/m365hud/glass/
│           ├── BleClient.kt      # BLE 客户端（连接手机 App）
│           ├── GattProfile.kt    # GATT 服务定义
│           ├── HudScreen.kt      # AR HUD 显示界面
│           └── DataModels.kt     # 共用数据结构
├── ninebot-ffi/                  # Android 用 Rust FFI 库
│   └── src/
│       ├── lib.rs                # JNI 导出
│       └── mi_crypto.rs          # 加密函数
├── ninebot-ble/                  # 核心 Rust BLE 库
│   └── src/
│       ├── connection.rs         # BLE 连接处理
│       ├── protocol.rs           # M365 协议实现
│       ├── mi_crypto.rs          # ECDH、HKDF、AES-CCM 加密
│       └── ...
└── doc/                          # 文档
    ├── BLE_PROTOCOL_GUIDE.md     # 详细协议文档
    └── README_*.md               # 多语言 README
```

## 🔐 协议概述

本应用实现了小米 M365 加密 BLE 协议：

| 组件     | 算法             | 说明                   |
| -------- | ---------------- | ---------------------- |
| 密钥交换 | ECDH (SECP256R1) | 椭圆曲线密钥交换       |
| 密钥派生 | HKDF-SHA256      | 从共享密钥派生会话密钥 |
| 认证     | HMAC-SHA256      | 消息认证               |
| 加密     | AES-128-CCM      | UART 数据的认证加密    |

### 遥测数据

| 查询     | 地址   | 数据                                 |
| -------- | ------ | ------------------------------------ |
| 电机信息 | `0xB0` | 速度、电量百分比、控制器温度、总里程 |
| 行程信息 | `0x3A` | 当前行程时间、距离                   |
| 续航里程 | `0x25` | 预估剩余续航里程（公里）             |

## 🛠️ 系统要求

### Android 应用

- **最低 SDK**：Android 10（API 29）
- **目标 SDK**：Android 15（API 35）
- **权限**：蓝牙、位置（用于 BLE 扫描）

### 构建环境

- Android Studio Iguana 或更新版本
- **Java 17**（JDK 17+）
- Kotlin 1.9.22+
- Rust 工具链（用于构建原生库）
- Android NDK

### 蓝牙扫描策略

应用程序通过以下方式识别 M365 滑板车：

1. **设备名称**：以 `MIScooter` 开头（优先使用广播名称）
2. **服务 UUID**：包含小米服务 `0000fe95-0000-1000-8000-00805f9b34fb`

设备排序依据：已注册 → 滑板车 → 有名称 → 信号强度（RSSI）

## 🚀 开始使用

### 1. 克隆项目

```bash
git clone https://github.com/your-repo/M365-Rokid-HUD.git
cd M365-Rokid-HUD
```

### 2. 构建 Rust 库（如需要）

```bash
cd ninebot-ffi

# 如尚未安装，安装 cargo-ndk
cargo install cargo-ndk

# 添加 Android 目标
rustup target add aarch64-linux-android armv7-linux-androideabi

# 为 Android 构建
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
```

### 3. 构建 Android 应用

在 Android Studio 打开项目并构建：

```bash
./gradlew assembleDebug
```

或直接安装到已连接的设备：

```bash
./gradlew installDebug
```

## 📖 使用方式

1. **启动应用程序**并授予蓝牙/位置权限
2. **扫描**附近的 M365 滑板车
3. **首次配对**：勾选「注册」，并在提示时按下滑板车电源键
4. **后续连接**：直接点击设备即可连接
5. 在仪表盘或详细信息界面**查看遥测数据**

> ⚠️ **警告**：注册将使滑板车与其他应用程序（如米家）解除配对。请只注册您拥有的设备。

## 📁 项目组成

| 模块          | 说明                                                     |
| ------------- | -------------------------------------------------------- |
| `app`         | 主要 Android 应用程序（手机端），使用 Jetpack Compose UI |
| `glass-hud`   | Rokid AR 眼镜 HUD 客户端，显示来自 `app` 的遥测数据      |
| `ninebot-ffi` | 为 Android 提供 JNI 绑定的 Rust 库                       |
| `ninebot-ble` | M365 BLE 协议的核心 Rust 库                              |
| `doc`         | 协议文档和多语言 README                                  |

## 🤝 贡献

欢迎贡献！请随时提交 Pull Request。

## 📄 许可

本项目采用 MIT 许可 - 详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

- [btleplug](https://github.com/deviceplug/btleplug) - Rust 跨平台 BLE 库
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化 Android UI 工具包
- M365 逆向工程社区

---

**免责声明**：本项目仅供教育用途。使用风险自负。作者不对您的滑板车的任何损坏或违反制造商保修承担责任。
