# M365 BLE Communication Protocol | M365 藍牙低功耗通訊協定

A comprehensive guide to Bluetooth Low Energy (BLE) communication with Xiaomi/Ninebot M365 electric scooters.
本指南詳細說明如何透過藍牙低功耗（BLE）與小米/Ninebot M365 電動滑板車進行通訊。

> ### 📌 Scope | 適用範圍
>
> **This guide documents ONE of three protocol families this app knows about.**
> It covers the Xiaomi Mi authentication path (`fe95` + ECDH + AES-CCM), which is
> what the M365 family uses and the only path that currently produces telemetry.
>
> **本指南只涵蓋三種協議家族中的一種**，即 Xiaomi Mi 認證路徑，也是目前唯一能產生
> 遙測資料的路徑。
>
> | Family | Document |
> | --- | --- |
> | Xiaomi Mi auth | **this document** |
> | Ninebot legacy (`5AA5` + chained AES) | [`NINEBOT_LEGACY_PROTOCOL.md`](NINEBOT_LEGACY_PROTOCOL.md) |
> | Encryption2 (current generation) | [`PROTOCOL_FAMILIES.md`](PROTOCOL_FAMILIES.md) |
>
> Which scooters are actually supported, and how far each can be trusted, is in
> [`MODEL_SUPPORT.md`](MODEL_SUPPORT.md). **Recognition is not readability**: this
> app identifies scooter families it cannot yet read, and withholds readings
> rather than guessing them.
>
> 哪些車型真正支援、可信度到哪裡，請見 [`MODEL_SUPPORT.md`](MODEL_SUPPORT.md)。
> **「認得出來」不等於「讀得到」**：本 App 會辨識出尚無法讀取的車型，並選擇不顯示
> 讀數，而不是猜測。

---

## Table of Contents | 目錄

1. [Overview | 概述](#overview--概述)
2. [BLE Services and Characteristics | BLE 服務與特徵](#ble-services-and-characteristics--ble-服務與特徵)
3. [Connection Flow | 連線流程](#connection-flow--連線流程)
4. [Registration (First-Time Pairing) | 註冊（首次配對）](#registration-first-time-pairing--註冊首次配對)
5. [Login (Subsequent Connections) | 登入（後續連線）](#login-subsequent-connections--登入後續連線)
6. [Encrypted UART Communication | 加密 UART 通訊](#encrypted-uart-communication--加密-uart-通訊)
7. [Telemetry Data Queries | 遙測資料查詢](#telemetry-data-queries--遙測資料查詢)
8. [Data Frame Format | 資料幀格式](#data-frame-format--資料幀格式)
9. [Cryptographic Implementation | 加密實作](#cryptographic-implementation--加密實作)
10. [Troubleshooting | 問題排查](#troubleshooting--問題排查)

---

## Overview | 概述

The M365 scooter uses a sophisticated BLE protocol with end-to-end encryption. Communication involves:
M365 滑板車使用精密的 BLE 協定搭配端對端加密。通訊過程包括：

1. **Discovery | 探索** - Find M365 scooters via BLE advertising | 透過 BLE 廣播尋找 M365 滑板車
2. **Registration | 註冊** - One-time ECDH key exchange to obtain authentication token | 一次性 ECDH 金鑰交換以取得認證權杖
3. **Login | 登入** - Establish encrypted session using saved token | 使用已儲存的權杖建立加密會話
4. **UART Communication | UART 通訊** - Send/receive encrypted commands and telemetry | 傳送/接收加密命令與遙測資料

### Cryptographic Stack | 加密技術堆疊

| Component      | 組件     | Algorithm              | 演算法                                 | Purpose                | 用途 |
| -------------- | -------- | ---------------------- | -------------------------------------- | ---------------------- | ---- |
| Key Exchange   | 金鑰交換 | ECDH (SECP256R1/P-256) | Generate shared secret                 | 產生共享密鑰           |
| Key Derivation | 金鑰衍生 | HKDF-SHA256            | Derive session keys from shared secret | 從共享密鑰衍生會話金鑰 |
| Authentication | 認證     | HMAC-SHA256            | Message authentication codes           | 訊息認證碼             |
| Encryption     | 加密     | AES-128-CCM            | Authenticated encryption for UART data | UART 資料的認證加密    |

---

## BLE Services and Characteristics | BLE 服務與特徵

### Nordic UART Service (NUS) | Nordic UART 服務

Used for encrypted command/telemetry communication after login.
登入後用於加密命令/遙測通訊。

| Name        | 名稱       | UUID                                   | Properties | 屬性 |
| ----------- | ---------- | -------------------------------------- | ---------- | ---- |
| Service     | 服務       | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | -          |
| TX (Write)  | TX（寫入） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write      | 寫入 |
| RX (Notify) | RX（通知） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify     | 通知 |

### Xiaomi Authentication Service | 小米認證服務

Used for registration and login handshakes.
用於註冊和登入握手。

| Name           | 名稱          | UUID                                   | Properties    | 屬性       |
| -------------- | ------------- | -------------------------------------- | ------------- | ---------- |
| Service        | 服務          | `0000fe95-0000-1000-8000-00805f9b34fb` | -             |
| UPNP (Control) | UPNP（控制）  | `00000010-0000-1000-8000-00805f9b34fb` | Write, Notify | 寫入、通知 |
| AVDTP (Data)   | AVDTP（資料） | `00000019-0000-1000-8000-00805f9b34fb` | Write, Notify | 寫入、通知 |

---

## Connection Flow | 連線流程

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
│   客戶端    │                              │   滑板車    │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. BLE Scan (XIAOMI_SCOOTER_MATCH)        │
       │     BLE 掃描（見 identity.rs 單一來源）    │
       │◄──────────────────────────────────────────►│
       │                                            │
       │  2. GATT Connect | GATT 連接               │
       │───────────────────────────────────────────►│
       │                                            │
       │  3. Discover Services | 探索服務           │
       │───────────────────────────────────────────►│
       │                                            │
       │  4. Enable Notifications (UPNP, AVDTP)     │
       │     啟用通知（UPNP, AVDTP）                │
       │───────────────────────────────────────────►│
       │                                            │
       │  5. Registration OR Login                  │
       │     註冊或登入                             │
       │◄──────────────────────────────────────────►│
       │                                            │
       │  6. Enable Notifications (UART RX)         │
       │     啟用通知（UART RX）                    │
       │───────────────────────────────────────────►│
       │                                            │
       │  7. Encrypted UART Communication           │
       │     加密 UART 通訊                         │
       │◄──────────────────────────────────────────►│
       │                                            │
```

### Step-by-Step | 步驟說明

1. **Scan for Devices | 掃描裝置**

   - The identity rule lives in ONE place: `ninebot-ble/src/identity.rs`,
     constant `XIAOMI_SCOOTER_MATCH`. A device matches when its advertised name
     starts with that rule's `name_prefix`, **or** its advertisement/service list
     contains its `service_uuid`. Either signal alone suffices.
     | 識別規則只有一處：`ninebot-ble/src/identity.rs` 的 `XIAOMI_SCOOTER_MATCH`。
     名稱前綴或 service UUID **任一**符合即視為滑板車。
   - ⚠️ This identifies a LINEAGE, not a model and not a protocol. The prefix
     covers M365 / Pro / Pro2 / 1S / Lite / Mi 3 alike, and a service list is not
     a protocol discriminator in general.
     | 這只識別「血統」，不是型號、也不是協議。名稱前綴涵蓋整個 Xiaomi 家族，
     而 service 清單在一般情況下無法區分協議。

2. **GATT Connection | GATT 連接**

   ```kotlin
   val gatt = device.connectGatt(context, false, gattCallback)
   gatt.requestMtu(512)  // Request larger MTU for efficiency | 請求較大的 MTU 以提高效率
   gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
   ```

3. **Enable Notifications | 啟用通知**

   - Enable notifications on `AUTH_UPNP` characteristic | 在 `AUTH_UPNP` 特徵上啟用通知
   - Enable notifications on `AUTH_AVDTP` characteristic | 在 `AUTH_AVDTP` 特徵上啟用通知

4. **Authentication | 認證**

   - If first time: Perform Registration | 首次：執行註冊
   - Otherwise: Perform Login | 否則：執行登入

5. **Enable UART Notifications | 啟用 UART 通知**
   - Enable notifications on `UART_RX` for telemetry responses | 在 `UART_RX` 上啟用通知以接收遙測回應

---

## Registration (First-Time Pairing) | 註冊（首次配對）

Registration is a **one-time process** to establish trust between the client and scooter. This generates a 12-byte authentication token that must be saved securely.
註冊是**一次性程序**，用於建立客戶端與滑板車之間的信任關係。這會產生一個必須安全儲存的 12 位元組認證權杖。

> ⚠️ **Warning | 警告**: Registration will unpair the scooter from all other apps (e.g., Mi Home).
> 註冊將使滑板車與所有其他應用程式（如米家）取消配對。

### Registration Sequence | 註冊序列

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
│   客戶端    │                              │   滑板車    │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. CMD_GET_INFO (A2 00 00 00)             │
       │───────────────────────────────────────────►│ UPNP
       │                                            │
       │  2. Remote Info (MiParcel)                 │
       │     遠端資訊                               │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  3. Generate ECDH Key Pair                 │
       │     產生 ECDH 金鑰對（本地操作）           │
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
       │     用戶按下滑板車電源按鈕                 │
       │                                            │
       │  7. Send Public Key (MiParcel)             │
       │     發送公鑰                               │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  8. RCV_OK (00 00 01 00)                   │
       │◄───────────────────────────────────────────│
       │                                            │
       │  9. Remote Public Key (MiParcel)           │
       │     接收遠端公鑰                           │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  10. Calculate Shared Secret & Derive Keys │
       │      計算共享密鑰並衍生金鑰（本地操作）    │
       │                                            │
       │  11. CMD_SEND_DID (00 00 00 00 02 00)      │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  12. RCV_RDY (00 00 01 01)                 │
       │◄───────────────────────────────────────────│
       │                                            │
       │  13. Send DID Ciphertext (MiParcel)        │
       │      發送加密的裝置 ID                     │
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
       │  ✅ SAVE TOKEN (12 bytes) | 儲存權杖       │
       │                                            │
```

### Key Derivation During Registration | 註冊時的金鑰衍生

```python
shared_secret = ECDH(remote_public_key, my_private_key)
derived_key   = HKDF-SHA256(shared_secret, info="mible-setup-info", length=44)

token     = derived_key[0:12]    # 12 bytes - SAVE THIS! | 12 位元組 - 必須儲存！
bind_key  = derived_key[12:28]   # 16 bytes | 16 位元組
a_key     = derived_key[28:44]   # 16 bytes | 16 位元組

# Encrypt Device ID | 加密裝置 ID
nonce     = remote_info[0:4]     # From step 2 | 來自步驟 2
aad       = "devID"
plaintext = remote_info[4:]
did_ct    = AES-CCM-Encrypt(a_key, plaintext, nonce, aad)
```

### Important Notes | 重要說明

- **User Action Required | 需要用戶操作**: The scooter waits for the user to press the power button before accepting the public key. Allow 30+ seconds timeout. | 滑板車會等待用戶按下電源按鈕才接受公鑰。請設定 30 秒以上的超時。
- **Token Storage | 權杖儲存**: Store the token securely (e.g., Android `EncryptedSharedPreferences`). | 請安全儲存權杖（如 Android `EncryptedSharedPreferences`）。
- **One Scooter Per App | 每個 App 只能配對一台**: Each registration invalidates previous pairings. | 每次註冊都會使先前的配對失效。

---

## Login (Subsequent Connections) | 登入（後續連線）

Login establishes an encrypted session using the previously saved token. This is required for every new connection.
登入使用先前儲存的權杖建立加密會話。每次新連線都需要登入。

### Login Sequence | 登入序列

```
┌─────────────┐                              ┌─────────────┐
│   Client    │                              │   Scooter   │
│   客戶端    │                              │   滑板車    │
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
       │     發送隨機金鑰（16 位元組）              │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  5. RCV_OK (00 00 01 00)                   │
       │◄───────────────────────────────────────────│
       │                                            │
       │  6. Remote Random Key (MiParcel)           │
       │     接收遠端隨機金鑰                       │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  7. Remote Info / HMAC (MiParcel)          │
       │     接收遠端資訊 / HMAC                    │
       │◄───────────────────────────────────────────│ AVDTP
       │                                            │
       │  8. Derive Session Keys                    │
       │     衍生會話金鑰（本地操作）               │
       │                                            │
       │  9. Verify Remote Info                     │
       │     驗證遠端資訊（本地操作）               │
       │                                            │
       │  10. CMD_SEND_INFO (00 00 00 0A 02 00)     │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  11. RCV_RDY (00 00 01 01)                 │
       │◄───────────────────────────────────────────│
       │                                            │
       │  12. Send Login Data / HMAC (MiParcel)     │
       │      發送登入資料 / HMAC                   │
       │───────────────────────────────────────────►│ AVDTP
       │                                            │
       │  13. RCV_OK (00 00 01 00)                  │
       │◄───────────────────────────────────────────│
       │                                            │
       │  14. LOGIN_OK (21 00 00 00)                │
       │◄───────────────────────────────────────────│
       │                                            │
       │  ✅ SESSION ESTABLISHED | 會話已建立       │
       │                                            │
```

### Session Key Derivation | 會話金鑰衍生

```python
my_random    = random(16)     # Generated in step 4 | 步驟 4 產生
remote_random = received      # Received in step 6 | 步驟 6 接收

salt     = my_random + remote_random
salt_inv = remote_random + my_random

derived = HKDF-SHA256(token, salt, info="mible-login-info", length=40)

dev_key = derived[0:16]    # Key for decrypting scooter messages | 解密滑板車訊息的金鑰
app_key = derived[16:32]   # Key for encrypting client messages | 加密客戶端訊息的金鑰
dev_iv  = derived[32:36]   # IV for decryption (4 bytes) | 解密用 IV
app_iv  = derived[36:40]   # IV for encryption (4 bytes) | 加密用 IV

# Calculate HMACs for verification | 計算 HMAC 用於驗證
info               = HMAC-SHA256(app_key, salt)       # Send to scooter | 發送給滑板車
expected_remote    = HMAC-SHA256(dev_key, salt_inv)   # Verify from scooter | 驗證滑板車回應
```

### Session Keys Summary | 會話金鑰摘要

| Key       | 金鑰     | Size      | 大小                          | Purpose                | 用途 |
| --------- | -------- | --------- | ----------------------------- | ---------------------- | ---- |
| `app_key` | 16 bytes | 16 位元組 | Encrypt messages TO scooter   | 加密發送給滑板車的訊息 |
| `dev_key` | 16 bytes | 16 位元組 | Decrypt messages FROM scooter | 解密來自滑板車的訊息   |
| `app_iv`  | 4 bytes  | 4 位元組  | Nonce prefix for encryption   | 加密用 Nonce 前綴      |
| `dev_iv`  | 4 bytes  | 4 位元組  | Nonce prefix for decryption   | 解密用 Nonce 前綴      |

---

## Encrypted UART Communication | 加密 UART 通訊

After successful login, all UART communication is encrypted using AES-128-CCM.
登入成功後，所有 UART 通訊都使用 AES-128-CCM 加密。

### Encryption (Client → Scooter) | 加密（客戶端 → 滑板車）

```python
# Build nonce (12 bytes) | 建構 nonce（12 位元組）
nonce = app_iv + [0x00, 0x00, 0x00, 0x00] + counter_le(4)

# Encrypt | 加密
ciphertext = AES-CCM-Encrypt(app_key, plaintext, nonce)

# Frame format (output) | 輸出幀格式
frame = [0x55, 0xAB] + length(2) + ciphertext + crc16(2)
```

### Decryption (Scooter → Client) | 解密（滑板車 → 客戶端）

```python
# Build nonce (12 bytes) | 建構 nonce（12 位元組）
nonce = dev_iv + [0x00, 0x00, 0x00, 0x00] + counter_le(4)

# Decrypt (strip 55 AB header and CRC first) | 解密（先去除 55 AB 標頭和 CRC）
plaintext = AES-CCM-Decrypt(dev_key, ciphertext, nonce)
```

### Counter Management | 計數器管理

> **Important | 重要**: In practice, the M365 scooter does **not** require incrementing counters. The counter can always be set to `0` for both encryption and decryption.
> 實際上，M365 滑板車**不需要**遞增計數器。加密和解密時計數器都可以設為 `0`。

```kotlin
val counter = 0L  // Always use counter=0 | 始終使用 counter=0
val encrypted = native.encrypt(sessionPtr, payload, counter)
```

---

## Telemetry Data Queries | 遙測資料查詢

The Mi Home app queries the scooter cyclically using these commands:
米家 App 使用這些命令循環查詢滑板車：

| Command         | 命令     | Address | 位址              | Param                         | 參數                   | Data Returned | 回傳資料 |
| --------------- | -------- | ------- | ----------------- | ----------------------------- | ---------------------- | ------------- | -------- |
| Motor Info      | 馬達資訊 | `0xB0`  | `0x20` (32 bytes) | Speed, battery, temp, mileage | 速度、電量、溫度、里程 |
| Trip Info       | 行程資訊 | `0x3A`  | `0x04` (4 bytes)  | Trip seconds, trip meters     | 行程秒數、行程公尺     |
| Remaining Range | 剩餘里程 | `0x25`  | `0x02` (2 bytes)  | Estimated remaining km        | 預估剩餘公里數         |

### Building a Query Packet | 建構查詢封包

For encrypted UART, the plaintext format is:
加密 UART 的明文格式為：

```
[size] [direction] [read/write] [attribute] [param...]

Where | 其中:
- size = param_length + 2
- direction = 0x20 (master to motor | 主控到馬達)
- read/write = 0x01 (read | 讀取) or 0x03 (write | 寫入)
- attribute = command address (e.g., 0xB0) | 命令位址
- param = parameter bytes | 參數位元組
```

**Example: Query Motor Info (0xB0) | 範例：查詢馬達資訊**

```kotlin
val payload = byteArrayOf(
    0x03,       // size = 1 + 2 = 3
    0x20,       // direction: master to motor | 方向：主控到馬達
    0x01,       // operation: read | 操作：讀取
    0xB0.toByte(), // attribute: motor info | 屬性：馬達資訊
    0x20        // param: read 32 bytes | 參數：讀取 32 位元組
)
```

### Parsing Motor Info Response (0xB0) | 解析馬達資訊回應

The decrypted response contains a standard M365 frame:
解密的回應包含標準 M365 幀：

```
55 AA [len] 23 01 B0 [data...] [crc16]
```

Data layout (32 bytes starting from offset 6) | 資料佈局（從偏移量 6 開始的 32 位元組）:

| Offset | 偏移 | Size          | 大小       | Field                       | 欄位            | Description | 說明 |
| ------ | ---- | ------------- | ---------- | --------------------------- | --------------- | ----------- | ---- |
| 0-1    | 2    | error_code    | 錯誤碼     | Error flags                 | 錯誤旗標        |
| 2-3    | 2    | warning_code  | 警告碼     | Warning flags               | 警告旗標        |
| 4-5    | 2    | flags         | 旗標       | Status flags                | 狀態旗標        |
| 6-7    | 2    | work_mode     | 工作模式   | Operating mode              | 運作模式        |
| 8-9    | 2    | battery       | 電量       | Battery % (0-100)           | 電池百分比      |
| 10-11  | 2    | speed         | 速度       | Current speed × 10          | 當前速度 × 10   |
| 12-13  | 2    | avg_speed     | 平均速度   | Average speed × 10          | 平均速度 × 10   |
| 14-17  | 4    | total_mileage | 總里程     | Total mileage in meters     | 總里程（公尺）  |
| 18-19  | 2    | reserved      | 保留       | Unknown                     | 未知            |
| 20-21  | 2    | frame_temp    | 控制器溫度 | Controller temperature × 10 | 控制器溫度 × 10 |
| 22-23  | 2    | reserved2     | 保留 2     | Unknown                     | 未知            |

**Conversion formulas | 轉換公式:**

```kotlin
val speed = (data[10].toUByte().toInt() + (data[11].toUByte().toInt() shl 8)) / 1000.0  // km/h
val battery = data[8].toUByte().toInt()  // %
val temp = (data[20].toUByte().toInt() + (data[21].toUByte().toInt() shl 8)) / 10.0  // °C
val mileage = ByteBuffer.wrap(data, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int / 1000.0  // km
```

### Parsing Trip Info Response (0x3A) | 解析行程資訊回應

| Offset | 偏移 | Size         | 大小     | Field             | 欄位         | Description | 說明 |
| ------ | ---- | ------------ | -------- | ----------------- | ------------ | ----------- | ---- |
| 0-1    | 2    | trip_seconds | 行程秒數 | Seconds this trip | 本次行程秒數 |
| 2-3    | 2    | trip_meters  | 行程公尺 | Meters this trip  | 本次行程公尺 |

### Parsing Remaining Range Response (0x25) | 解析剩餘里程回應

| Offset | 偏移 | Size            | 大小     | Field                         | 欄位                  | Description | 說明 |
| ------ | ---- | --------------- | -------- | ----------------------------- | --------------------- | ----------- | ---- |
| 0-1    | 2    | remaining_range | 剩餘里程 | Remaining range × 10 (meters) | 剩餘里程 × 10（公尺） |

```kotlin
val remainingKm = value / 100.0  // Convert to km | 轉換為公里
```

---

## Data Frame Format | 資料幀格式

### Standard M365 Frame (Unencrypted/Legacy) | 標準 M365 幀（未加密/舊版）

```
+-----+-----+-----+-----+-----+-----+--------+------+------+
| 55  | AA  |  L  |  D  |  T  |  C  | Data   | CK0  | CK1  |
+-----+-----+-----+-----+-----+-----+--------+------+------+
  │     │     │     │     │     │      │        └──────┴── Checksum | 校驗和
  │     │     │     │     │     │      └── Payload bytes | 負載位元組
  │     │     │     │     │     └── Command/Attribute byte | 命令/屬性位元組
  │     │     │     │     └── Type: 0x01=read, 0x03=write | 類型：讀取/寫入
  │     │     │     └── Device: 0x20=to motor, 0x23=from motor | 裝置：到馬達/從馬達
  │     │     └── Length: payload + 2 | 長度：負載 + 2
  └─────┴── Magic header | 魔術標頭
```

### Encrypted UART Frame | 加密 UART 幀

```
+-----+-----+--------+------------------+------+------+
| 55  | AB  | Length |    Ciphertext    | CRC0 | CRC1 |
+-----+-----+--------+------------------+------+------+
  │     │       │            │              └──────┴── CRC-16
  │     │       │            └── AES-CCM encrypted payload | AES-CCM 加密負載
  │     │       └── Length (2 bytes, little-endian) | 長度（2 位元組，小端序）
  └─────┴── Magic header (note: AB not AA) | 魔術標頭（注意：是 AB 不是 AA）
```

### Checksum Calculation | 校驗和計算

```kotlin
fun calculateChecksum(data: ByteArray): Int {
    var sum = 0
    for (b in data) {
        sum += b.toUByte().toInt()
    }
    return sum.inv() and 0xFFFF
}

// CK0 = checksum & 0xFF (LSB) | 低位元組
// CK1 = (checksum >> 8) & 0xFF (MSB) | 高位元組
```

---

## Cryptographic Implementation | 加密實作

### ECDH Key Exchange (SECP256R1) | ECDH 金鑰交換

```rust
use p256::{PublicKey, SecretKey, ecdh::SharedSecret};

let my_secret = SecretKey::random(&mut OsRng);
let my_public = my_secret.public_key();

let remote_public = PublicKey::from_sec1_bytes(&remote_bytes)?;
let shared_secret = my_secret.diffie_hellman(&remote_public);
```

### HKDF Key Derivation | HKDF 金鑰衍生

```rust
use hkdf::Hkdf;
use sha2::Sha256;

let hkdf = Hkdf::<Sha256>::new(None, shared_secret.as_bytes());
let mut derived = [0u8; 44];
hkdf.expand(b"mible-setup-info", &mut derived)?;
```

### AES-128-CCM Encryption | AES-128-CCM 加密

```rust
use ccm::{Ccm, aead::{Aead, KeyInit}};
use aes::Aes128;

type Aes128Ccm = Ccm<Aes128, U4, U12>;  // 4-byte tag, 12-byte nonce | 4 位元組標籤，12 位元組 nonce

let cipher = Aes128Ccm::new_from_slice(&key)?;
let nonce = build_nonce(iv, counter);
let ciphertext = cipher.encrypt(&nonce, payload)?;
```

---

## Troubleshooting | 問題排查

### Common Issues | 常見問題

| Problem                  | 問題             | Cause                             | 原因               | Solution                                             | 解決方案                            |
| ------------------------ | ---------------- | --------------------------------- | ------------------ | ---------------------------------------------------- | ----------------------------------- |
| Scooter not found        | 找不到滑板車     | Airlock enabled                   | 氣鎖已啟用         | Unbind from Mi Home, disable Airlock via DownG       | 從米家解綁，透過 DownG 停用氣鎖     |
| Scooter not found        | 找不到滑板車     | BLE not advertising               | BLE 未廣播         | Reset Bluetooth: Power + Brake + Throttle 5s         | 重置藍牙：同時按電源+煞車+油門 5 秒 |
| Scooter shows as Unknown | 滑板車顯示為未知 | Not using advertised name         | 未使用廣播名稱     | Use `scanRecord.deviceName` instead of `device.name` |
| Registration timeout     | 註冊超時         | User didn't press power button    | 用戶未按電源鍵     | Increase timeout to 30s, show clear prompt           | 增加超時到 30 秒，顯示明確提示      |
| GATT connection failed   | GATT 連接失敗    | BLE permissions not granted       | BLE 權限未授予     | Request `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`        |
| Login failed             | 登入失敗         | Token corrupted or expired        | 權杖損壞或過期     | Delete token, re-register                            | 刪除權杖，重新註冊                  |
| Decryption failed        | 解密失敗         | Wrong counter or keys             | 計數器或金鑰錯誤   | Ensure counter=0, verify key derivation              | 確保 counter=0，驗證金鑰衍生        |
| No telemetry data        | 無遙測資料       | UART RX notifications not enabled | UART RX 通知未啟用 | Enable notifications after login                     | 登入後啟用通知                      |

### Airlock (Anti-Theft Feature) | 氣鎖（防盜功能）

Xiaomi scooters have an "Airlock" feature that blocks third-party apps when:
小米滑板車有「氣鎖」功能，在以下情況會阻擋第三方 App：

- Scooter is bound to Mi Home app | 滑板車已綁定米家 App
- Airlock is enabled in firmware | 韌體中已啟用氣鎖

**How to disable Airlock | 如何停用氣鎖:**

1. Unbind scooter from Mi Home app, or | 從米家 App 解綁滑板車，或
2. Use DownG / M365 Tools app to disable Airlock, or | 使用 DownG / M365 Tools App 停用氣鎖，或
3. Register with this app (will unpair from Mi Home) | 使用本 App 註冊（會取消與米家的配對）

### Debug Logging | 除錯日誌

Enable verbose logging to trace the protocol:
啟用詳細日誌以追蹤協定：

```kotlin
Log.d("ScooterRepo", "Tx UPNP: ${data.toHex()}")
Log.d("ScooterRepo", "Rx AVDTP: ${data.toHex()}")
Log.d("ScooterRepo", "Encrypted: ${encrypted.toHex()}")
Log.d("ScooterRepo", "Decrypted: ${decrypted.toHex()}")
```

### MiParcel Protocol | MiParcel 協定

Data larger than 18 bytes is transferred using the MiParcel protocol:
大於 18 位元組的資料使用 MiParcel 協定傳輸：

**Header Frame | 標頭幀:**

```
[.. .. .. .. LenL LenH]  # Last 2 bytes = total frames count | 最後 2 位元組 = 總幀數
```

**Data Frames | 資料幀:**

```
[Index] [0x00] [Payload...]  # 18 bytes max payload per frame | 每幀最多 18 位元組負載
```

**Acknowledgments | 確認:**

- `00 00 01 01` = RCV_RDY (ready to receive | 準備接收)
- `00 00 01 00` = RCV_OK (received successfully | 接收成功)

---

## References | 參考資料

- [M365 BLE Protocol (CamiAlfa)](https://github.com/CamiAlfa/M365-BLE-PROTOCOL)
- [btleplug - Rust BLE Library](https://github.com/deviceplug/btleplug)
- [Nordic UART Service Specification](https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/nrf/libraries/bluetooth_services/services/nus.html)

---

_Document Version: 2.0 | Last Updated: January 2026_
_文件版本：2.0 | 最後更新：2026 年 1 月_


## 多車款擴充前置查核（2026-09-12）

### 既有實作與需求差異

- 本儲存庫既有 M365 路徑使用 Xiaomi 註冊／登入與 AES-CCM；不可因需求表格標示明文而改成明文連線。既有命令與加密框架亦不可直接統一替換為 `5A A5`。
- 本文件先前建議固定使用計數器 `0`，與目前 `session/mi_session.rs` 的遞增計數器實作不符；重構應保留現有遞增與耗盡檢查，不能依舊版文字取消。
- `MotorInfo` 現有解析使用速度除以 `1000`，溫度為有號值且位於資料偏移 `22`；上方表格的速度倍率與溫度偏移存在矛盾。第一階段應以現有程式及回歸測試保留行為，後續再以實機封包核對文件。

### 外部來源查核

[NinebotCrypto 原始說明](https://github.com/scooterhacking/NinebotCrypto) 將此協定描述為第一代 Ninebot／Xiaomi 加密，並列出依 BLE 韌體版本而異的支援限制；車型名稱不能單獨決定加密方式。其建議配對流程包含 `0x5B` 交換、`0x5C` 隨機通訊金鑰與電源按鈕確認，再以 `0x5D` 傳送序號。因此「只從序號衍生固定金鑰」不足以取代完整配對狀態機，也不能把既有 Xiaomi 登入直接視為同一種協定。

[CFW API 表格](https://wiki.scooterhacking.org/doku.php?id=cfwbuilder) 列出 ESx、Max、Pro、Pro 2；M365 與 1S 的建置服務已停止，E2x 標示開發中。此表是韌體建置服務狀態，不能作為 BLE 遙測或控制已驗證的證據。

### 建置閘門現況

目前尚未實作多車款支援。初次沙箱查核無法存取工具鏈，誤判為未安裝；以提升權限重新查核，確認 Visual Studio Build Tools 2026 已具備 MSVC 14.51.36231 與 Windows SDK 10.0.26100.0，無須重裝。以可存取工具鏈的環境執行 `cargo test`，15 項測試與 6 項文件測試全部通過。Android 基準建置遇到 JVM 的本機通訊錯誤（`Unable to establish loopback connection`），尚未通過建置閘門。

`ninebot-ble/Makefile` 目前執行本 crate 的 `cargo ndk`，但 Android 載入 `ninebot-ffi` 產出的 `libninebot_ffi.so`。後續 FFI 驗證必須同時確認建置的是正確 crate，並更新 Android 打包的所有 ABI，不能僅以目前的 `make` 指令成功作為 FFI 已更新的證據。


### ES 協定來源補充

[Ninebot ES 通訊協定 V1.5](https://cloud.scooterhacking.org/release/nbdoc.pdf) 的框架有獨立的來源與目標位元組，長度欄位只計資料段；這與現有 Xiaomi 命令的長度規則不同。其校驗值為除標頭外各位元組總和取反的低 16 位元，小端序傳送。文件第 6 頁溫度讀取範例是 `5A A5 01 3D 20 01 3E 02 60 FF`，使用串列主機來源 `0x3D`；BLE 手機來源則是 `0x3E`。此範例不可直接當成手機 BLE 封包。該頁敘述稱讀取回應為 `0x05`，但命令表列 `0x04` 為讀取回應，需以封包與其他來源交叉確認，不能忽略此矛盾。


### NinebotCrypto 原始碼世代差異

實際比對上游 `Rust/mi_crypto.rs` 與 `Kotlin/ProtocolNinebot.kt` 後，確認兩份檔案並非同一套加密流程的直接語言對照：Rust 檔使用 ECDH、HKDF 與 AES-CCM，和此儲存庫現有 Xiaomi 路徑同系；Kotlin 建構子則以 BLE 廣播名稱與固定資料計算初始金鑰，隨交換的隨機資料更新。序號仍用於配對確認，但不能誤寫成單靠序號衍生完整工作階段金鑰。後續整合需依 Kotlin／其他一致實作交叉驗證，保留 Xiaomi 原有策略。


## 多車款實作查核（2026-09-13）

ESC 0x10 為 14 位序號，0x1A 為主控韌體版本。BLE 名稱不作為車型證據；手動序號也不直接指定車款。配對完成後另讀 ESC 序號，要求與 BLE 回傳序號一致，通過車款解析及實驗性設定後才開放控制。

ES 官方快速暫存器區 0xB0 起的欄位與 Xiaomi 外觀相似，但 ES／G30 的 B5、B6 為 0.1 km/h；Xiaomi 共用表的 B5 為 m/h。B7 為 32 位總里程（m），B9 行程單位 10 m，BB 溫度單位 0.1 °C。原 Android M365 的行程來源與短封包處理另以相容解碼保留，不以新表默默修正。

ES 官方模式控制為 0x75，數值 0 標準、1 節能、2 運動。0x90 的頭燈控制在文件標為 IoT；ES2ESC 另列 0x7D 尾燈但未完整定義寫入值。此版本不推定所有新車款共用頭燈／尾燈命令。G30Protocol 交叉確認速度 /10、總里程、溫度及鎖定暫存器；尚未確認的功能以部分能力停用。

原任務將所有 SNSC 2.x 一併列入，但 ScooterHacking 明確區分 SNSC 2.0／2.1 與不公開支援的 2.2–2.4。本版僅識別已列明的 N4G、N4L，以及 ESx 的 N2；不將其他前綴強制當成 G30。來源與每車款狀態見 [多車款狀態](MULTI_SCOOTER_STATUS.md)。

明文校驗採官方 PDF 已列出之完整範例：5A A5 01 3D 20 01 3E 02 60 FF，校驗加總包含長度欄位；社群協定頁將 Ninebot 校驗起點寫成來源位址，與此 PDF 範例有差異，實作及測試採 PDF。加密框架則採 NinebotCrypto C++ 的初始校驗／鏈式標籤，不混用明文校驗算法。
