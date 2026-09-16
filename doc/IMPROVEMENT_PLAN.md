# M365-Rokid-HUD 改進計畫 v2（誠實盤點版）

> **v1 作廢。** v1 說「HUD 完全沒有加密、沒有輪詢排程、沒有寫入」是**錯的** ——
> 我當時只看了 `protocol/` 目錄，沒讀 `ScooterRepository.kt`（1,717 行）。
> 實際盤點後，HUD 比預期完整得多，真正的缺口完全不同。
>
> 依據：`re/scootbatt/reports/`（9 份、約 3,900 行）＋ `ScooterHacking CFW Builder` 參數表
> 使用者選擇：Ninebot + Xiaomi 都要、五項改進全做、寫入只做低風險可回復

---

## 1. 先更正我自己（三件事）

| 我先前說 | 事實 |
|---|---|
| 「HUD 沒有加密」 | ❌ **有**。`ninebot-ffi` 提供 `prepareHandshake` / `processHandshake` / `login` / `encrypt` / `decrypt`，`ScooterRepository` 用它做註冊、登入、`writeUartEncrypted`、`readEncryptedFrame` |
| 「HUD 沒有輪詢」 | ❌ **有**。`startTelemetryLoop()`（`:757`）做**分層輪詢**：`0xB0` 預設、`0x3A`/`0x25` 按間隔 |
| 「HUD checksum 有 bug」 | ❌ **假警報**。折疊只在 8-bit 和 > `0x7FFF` 時生效，幀最大約 32 B（和 ≤8160）永遠達不到；20 萬次隨機測試 0 次分歧 |
| 「ScooterHacking Utility Windows 版值得挖」 | ❌ **不值得**。PE 僅 ~900 KB、下載 12.8 MB → 安裝檔帶附加 payload，抽不到 BLE 字串；它是 UART/ST-Link **燒錄器** |

---

## 2. HUD 實際現況（1,932 行協定層 + 8 測試檔 + Rust FFI）

**已有**：
- Rust `ninebot-ffi`：Xiaomi 協定握手／登入／AES 加解密（session-based）
- `ScooterRepository`：`performRegistration`、`performLogin`、`writeUartEncrypted`、
  `readEncryptedFrame`、`parseTelemetry`、`tryLegacyParse`
- 寫入功能：`lock()`、`unlock()`、`setLock()`、`lightOn()`、`lightOff()`、
  `setLight()`、`readLightState()`、`beep()`
- 輪詢：`startTelemetryLoop()` 分層查詢 `0xB0` / `0x3A` / `0x25`
- 解析的 register：**只有 7 個** —— `0xB0`、`0x3A`、`0x25`、`0xB5`、`0x7D`、`0x7C`
- 協定層模組：`FrameCodec`(375)、`ScooterModelRegistry`(383)、`ProtocolProbe`(202)、
  `GattProfileDiscovery`(191)、`WriteRetryPolicy`(141)、`MtuFragmenter`(99)、
  `ModelOverrideStore`(86)

**真正缺的（＝改進空間）**：

| 缺口 | 嚴重度 | 對應 Scootbatt 的什麼 |
|---|---|---|
| **BMS 完全沒解析** | ✅ **已解決** | 已實作 `BmsTelemetryParser` + 23 測試 |
| **`FrameCodec` 是死碼（零引用）** | ✅ **已解決** | 已由 `PlaintextRegisterSession` 接上，見 §5 |
| **無 Ninebot 明文路徑** | ✅ **已解決（傳輸層）** | `PlaintextRegisterSession` 支援 P1/P2 兩種框；接進 UI 待辦 |
| **錯誤碼表完全沒有** | 🔴 高 | `0x1B` 54 個錯誤碼（診斷能力的核心） |
| **輪詢是手寫 while 迴圈** | 🟠 中 | Scootbatt 用**排程器**：單一未完成請求、3 次重送、逾時升級 |
| **無逾時升級／重連策略** | 🟠 中 | Scootbatt 連 3 次逾時就丟棄 → HUD 應升級為重連 |
| **顯示欄位少** | 🟡 中 | Scootbatt 有 19 個可顯示項（模式、KERS、訊號品質、電芯、健康度…） |
| **無 KERS／巡航／單位寫入** | 🟡 中 | `0x7B`/`0x7C`/`0x7D` |

> **註**：原先列的「入站 checksum 不驗證」**不是缺口** —— `FrameCodec.decode` 本來就會驗
> （`FrameCodec.kt:335-343`），而加密路徑由 Rust 的 `decrypt_uart` 驗 CRC16
> （`mi_crypto.rs:325-337`）。兩條路徑都已覆蓋。

---

## 3. 改進計畫（4 階段，依價值／風險排序）

### 階段 A：可靠性（對應「修 bug」＋「強化讀取穩定度」）

**A1. 入站 checksum 驗證** `FrameCodec`
- 新增 `verifyChecksum(frame): Boolean`，驗 `~sum(bytes[2..len])`（與既有 `checksum()` 同式）。
- 髒幀丟棄並計數，不進解析器。
- 測試：單 byte 翻轉、長度截斷、checksum 錯位。

**A2. 抽出請求排程器** `protocol/RequestScheduler.kt`（新）
- 依 Scootbatt `C1286ss` 語意：**單一未完成請求**、逾時重排到佇列尾、`retryCount=2`
  （共 3 次）、無 backoff、同請求去重。
- **比 Scootbatt 多做**：連續 N 次逾時 → 觸發重連（Scootbatt 完全沒有）。
- 把 `startTelemetryLoop` 的手寫 while 改成用它。
- 測試：假時鐘驗證順序、重送次數、去重、逾時升級。

**A3. 幀長邊界防護**
- Scootbatt 在 `LEN` 大於實際資料時 `ArrayIndexOutOfBounds` → Crashlytics。
- HUD 改為先驗 `LEN` 再解析。
- 測試：超長 `LEN`、空幀、只有 header。

### 階段 B：遙測欄位擴充（對應「擴充 HUD 顯示欄位」）

**B1. BMS 多電芯解析** `protocol/BmsTelemetryParser.kt`（新）
- `0x31`（12 B）：`+0` u16 剩餘 mAh、`+2` u16 %、`+4` int16/100 A、`+6` u16/100 V；
  功率 = V×I；`+2` 差值 = 已消耗 %
- `0x40`：10× u16/1000 V → **最高/最低電芯**
- `0x35`：2×(u8−20) °C
- `0x30` bit6 = 充電中；`0x18` 設計容量；`0x1B` 充電次數；`0x3B` 健康度 %
- 測試：合成幀逐 offset 驗證。

**B2. ESC 補齊** `protocol/EscTelemetryParser.kt`（新）
- `0x3E` int16/10 溫度、`0x53` int16/100 相電流、`0x47` u16/100 系統電壓、
  `0x75` 模式、`0x7B` KERS、`0x1B` 錯誤碼、`0x66`/`0x39` 版本、`0x29` 里程
- ⚠️ `0xB5` 速度單位未定案（m/h vs 0.1 km/h）→ 可設定常數 + 註解標示
- 測試：每個 register 一組向量。

**B3. 錯誤碼表** `protocol/ScooterErrorCodes.kt`（新）
- 移植 0–54 完整表（`00-protocol-core.md` §5），未知碼回 `"Unknown error code (N)"`。
- 測試：0、54、未知值、邊界 55。

**B4. 顯示欄位擴充**
- 把 B1/B2 的新值接到 `MotorInfo`/UI，眼鏡 HUD 增加可選欄位。

### 階段 C：Ninebot 明文路徑（「加上加密連線支援」的**真正缺口**）

> HUD 已有 Xiaomi 加密路徑（Rust FFI）。**真正缺的是 Ninebot 的兩條**。

**C1. Ninebot 明文**（G30/G2/F/E/D 系列，**不需加密**）
- 框：`5A A5 len 3E dir act pos payload sum_lo sum_hi`
- 目前 HUD 的 `FrameCodec` 已懂這個框（`SYNC_2_HI`/`BT_ID`），但 `ScooterRepository` 只走
  Xiaomi 加密路線 → 補上明文分支。
- 測試：建框／解框往返。

**C2. `NinebotCrypto`**（廣告 `byte[1] == 2` 的小米車）
- `K_fw = 97CFB802 844143DE 56002B3B 34780A5D`
- 金鑰 = `SHA1(BLE名稱(≤16,補0) ‖ K_fw)[0:16]`
- AES-128 **ECB 單塊、只加密、無 IV/padding/tag**
- 握手：`3E 21 5B 00`(900 ms) → UID=`payload[16:30]` →
  `3E 21 5C 00 ‖ 4AEEBD73E2161C112D065A49CC6E8BB7`(500 ms) →
  `3E 21 5D 00 ‖ UID[14]`(500 ms) → Paired
- ⚠️ **不要**移植 `nbcrypt.c` 的 64 KiB counter rollover 修正（Scootbatt 與
  `ProtocolNinebot.kt` 都用無號比較重新同步）
- 測試：固定向量驗金鑰衍生、keystream、MIC、counter。

### 階段 D：低風險寫入（僅可回復項）

> HUD 已有 `lock/unlock/light`。**不重做**，只補下列可回復項。

| 功能 | register | payload | 可回復 |
|---|---|---|---|
| KERS 弱/中/強 | `0x7B` | `{00/01/02, 00}` | ✅ |
| 巡航 關/開 | `0x7C` | `{00/01, 00}` | ✅ |
| 單位 km/h ↔ mph | `0x7D` | u16 **大端**，bit4 | ✅ |

- 🚫 **不做**：SHFW profile（`0xB2`，名稱來自無權限保護的廣播）、任何 BMS 寫入。
- ⚠️ **核心風險**：`0x7D` **讀小端、寫大端**，且尾燈（bit1）與 mph（bit4）共用同一個
  16-bit 字 → 必須 **read-modify-write**：先讀回、只改目標位元、寫回、再讀回驗證。
- 每筆寫入附確認對話框。

---

## 4. 執行順序與驗收

```
A1 → A2 → A3          可靠性（純邏輯，可 100% 測試）
   ↓
B1 → B2 → B3 → B4     遙測（純解析，可 100% 測試）
   ↓
C1 → C2               協定擴充（C1 可完整測試，C2 需向量）
   ↓
D                     寫入（依賴 A2，且需 read-modify-write）
```

**驗收**：`./gradlew :app:testDebugUnitTest` 全綠，既有 8 個測試檔不得退化。

**紅線**：
1. 每項新行為都要有單元測試，**不得只靠「看起來對」**。
2. 未經實車驗證的假設（`0xB5` 單位、wire SRC、`0x7D` 位元序）必須在程式碼註解與
   `doc/HANDOFF.md` 明確標示。
3. **不動** `FrameCodec.checksum()`（已證明與 ScooterHacking 等價）。
4. **不重做**既有的 lock/unlock/light/加密登入。

---

## 5. 已完成：`FrameCodec` 死碼問題（2026-09-16）

### 5.1 根因（兩個獨立原因，不是單純「忘了用」）

追查後發現 `FrameCodec` 閒置有兩個**不同**的原因：

1. **加密路徑上它是多餘的。** `FrameCodec` 實作的是**外層** link-layer 信封
   （`55 AA` / `5A A5` + 長度 + 反碼和）。但加密車款的信封是由 **Rust 的
   `encrypt_uart` / `decrypt_uart` 在 crypto 層內自己建與自己驗**的：
   - `ScooterRepository.buildPacket()`（`:904`）只建**內層** body
     `[size, dir, rw, attr, payload…]`，註解明寫「NO 55 AA header and NO checksum」
   - 入站由 `decrypt_uart` 先驗 CRC16（`mi_crypto.rs:325-337`）才解密
   → 所以 Kotlin 端從來不需要自己框。
2. **明文路徑根本不存在。** `FrameCodec` 正是為明文車款寫的，而那條路還沒實作。

**結論**：正確的「接上死碼」做法不是硬把它塞進加密路徑（那會變成
Kotlin 與 Rust 各框一次的重複工作），而是**實作它原本要服務的明文路徑**。

### 5.2 實作

新增 `protocol/PlaintextRegisterSession.kt`（`FrameCodec` 的第一個真實消費者）：

- `buildRead()` → 走 `FrameCodec.encodeRequest`，支援 P1（`55 AA`，小米/最舊）
  與 P2（`5A A5` + BT_ID，Ninebot）
- `accept()` → 走 `FrameCodec.decode`，因此**自動獲得 sync 檢查、長度一致性檢查
  與 checksum 驗證**
- `readRegister()` → 建框 → 送出 → 解框 → 用 `FrameCodec.isReplyFor` 過濾；
  陳舊通知回 `null`，畸形幀丟 `FrameException`
- `reassemble()` / `declaredTotalLength()` / `isComplete()` → 多片段重組，
  長度以幀自己的長度 byte 為唯一依據（與 `decode` 不會漂移）
- BLE 寫入以 `fun interface Write` **注入**，所以整條路徑可以無車測試

### 5.3 驗證

- 新增 `PlaintextRegisterSessionTest`：**16 個測試**
- 全 App：**146 個單元測試、0 失敗**（原 130 + 16，無退化）
- `FrameCodec` 的 19 個既有測試全部仍通過

### 5.4 待辦（尚未完成的部分，刻意不一次做完）

- **接進 `ScooterRepository`**：需要在 `connect()` 選擇明文 vs 加密分支，
  並在 `startTelemetryLoop()` 加明文輪詢。這是 1,717 行檔案的核心路徑改動，
  風險較高，應獨立一個變更。
- **明文遙測解析器**：`BmsTelemetryParser` 已就緒，但明文路徑的
  `Frame.payload` → 各 parser 的接線還沒做。
- **P1/P2 的車型對應**：目前由呼叫端決定；應接到 `ScooterModelRegistry`。

---

## 6. 已完成：A3 幀長防護 + B2/B3 ESC 解析與錯誤碼（2026-09-17）

### 6.1 A3 — 幀長防護，並修掉一個真實的 off-by-one

新增 `protocol/ScooterReply.kt`：把「宣告長度」變成第一個檢查項目，在算子任何
欄位 offset 之前就拒絕長度不符的幀。

**接線時發現一個真實 bug。** `parseTelemetry` 的註解聲稱解密後的資料「NO size byte
at the start」，但 `buildPacket` 顯示加密訊息是
`[size][direction][rw][attr][payload]` —— **size byte 確實存在**。舊程式碼因此：

- 把 **size byte 當成 direction** 讀
- 讓 `attr` 少讀一格 → 所有 register 分派都對錯位置

這解釋了為何先前 `parseTelemetry` 在真車上很可能從未正確分派過。已修正，並讓
`ScooterReply` 直接吃完整幀（含 size byte）。

**同時移除 `tryLegacyParse`**（確認是死碼）：它用「掃描前 5 byte 找 0xB0/0xB5」的
啟發式猜測，會把 payload 中碰巧等於 `0xB0` 的 byte 當成 register，再把後續全部資料
（含隨機填充）餵給 parser —— 正是 A3 要消滅的猜測式解析。沒有任何支援格式需要它。

**規格修正**：`encrypt_uart` 附加的 4-byte 隨機尾巴**不計入** size byte，所以
`size == frame.size` 的等號檢查會拒絕每一個合法回應。正確的檢查是
`frame.size >= size`（截斷防護）。

### 6.2 B3 — 54 個錯誤碼表

`protocol/ScooterErrorCodes.kt`：33 個有描述的碼 + 13 個保留碼（廠商表中本來就是空的），
含 `severityOf()` 分級。**未知碼一律視為 FAULT，絕不當成健康** —— 這是唯一有安全後果的
分類方向。

### 6.3 B2 — ESC 遙測解析器

`protocol/EscTelemetryParser.kt`：`0xB5` 速度（含 Xiaomi/其他雙刻度）、`0x25`/`0x2F`/`0xB9`
百分之一刻度、`0x29` 里程（**有號 32-bit**）、`0x3E` 溫度（有號十分之一）、`0x53` 相電流
（有號）、`0x47` 系統電壓、`0x1B` 錯誤、`0x75` 騎乘模式、`0x7B` KERS、`0x7C` 巡航、
`0x7D` 狀態位（**小端讀取**）、`0x66` 版本、`0x39` 識別碼、`0xFF` 哨兵值排除。

每個 decoder 對短 payload 都回 `null` 而不丟例外，並有測試覆盖。

### 6.4 demo 模式現在會跑真實解析器

`DemoRideSource` 不再直接算值，而是**編碼合成 register payload 再交給
`EscTelemetryParser` / `BmsTelemetryParser` 解碼**。這讓 demo 模式變成無硬體的
整合測試：offset 或刻度寫錯會直接顯示在眼鏡上。

`MotorInfo` 新增 16 個欄位（全部可為 null，所以「未回報」與「真值為 0」可區分）。

### 6.5 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **224 個通過、0 失敗**（A3 前為 176） |
| 實機安裝 | 手機 `pm install -r` 成功，release key 就地更新 |
| demo 啟動 | `DEMO: ride started (seed=376839)` |
| gateway | `subscribers: 1`，1.0–1.8 updates/sec |
| 眼鏡 HUD | `22.1 km/h`、`91%`、`69%`、`100%` |
| 解析錯誤 | **0 個 `Dropped malformed`** |

### 6.6 跳過的測試（需實車）

- 真實 `0xB5` 速度刻度（Xiaomi 0.001 vs 其他 0.1）**仍未定案**
- 真實 `0x25` 語意（速度 vs 剩餘里程）**仍未定案**
- 真實 `0x1B` 錯誤碼是否會出現、以及 72..200 版本窗口的意義
- 真車封包的 size byte 是否真的符合本實作的假設

---

## 7. 已完成：C1 Ninebot 明文路徑接線（2026-09-17）

### 7.1 實作

`ScooterRepository` 新增明文分支：

- `plaintextSession` 欄位 + `isPlaintextMode`
- `detectedProtocolOrPlaintext()`：**唯一**決定方言的地方（目前未探測時仍回
  `XIAOMI_MI`，保持既有行為）
- `startPlaintextTelemetryLoop()`：每 tick 讀一個 register，遵循**單一未完成請求**
  紀律；用 `isComplete`/`reassemble` 處理跨 notification 的分片
- `applyPlaintextReply()` → 委派給新的 `PlaintextTelemetryMapper`

`connect()` 在小米 AUTH 握手**之前**分支：明文車不會收到任何 auth 流量 ——
送了最好的情況是被忽略，最壞是把鏈路弄進明文迴圈讀不出來的狀態。

### 7.2 新增 `PlaintextTelemetryMapper`

把「register → 欄位」的對應抽成**純函式**（`decode` 回傳 sealed `Update`，
`apply` 回傳新樣本），因為這段邏輯原本內嵌在 repository 裡、被 Android 型別與
live GATT 連線包住，**無法測試**。

兩條設計規則：
1. **解不出來的值不改動任何東西** —— 寫 0 會讓移動中的滑板車顯示「0 km/h」，
   而騎士無法分辨那是真的一停還是解析失敗。
2. **只改被定址的那個 register** —— 每次更新是單一具名變更，不是整個新樣本，
   所以新增 register 不會默默重置無關欄位。

### 7.3 比 Scootbatt 多做的一項

`PLAINTEXT_FAILURES_BEFORE_RECONNECT = 5`：連續 5 次失敗就判定鏈路死亡並重連。
**Scootbatt 完全沒有這個機制** —— 它只靠 GATT callback，會對著可能已經斷掉的鏈路
繼續輪詢。HUD 顯示過期資料比重新連線更糟。

### 7.4 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **242 個通過、0 失敗**（C1 前 224） |
| 新增測試 | `PlaintextTelemetryMapperTest` 18 個 |
| 實機安裝 | `pm install -r` 成功，release key 就地更新 |
| 啟動 | 無 crash |
| demo + gateway | `subscribers: 1`，眼鏡收到 `speed=7.97` |
| 解析錯誤 | 0 個 `Dropped malformed` |

### 7.5 跳過的測試（需實車）

**整個 C1 分支在無實車時無法觸發** —— demo 模式不走明文路徑，所以這次只驗證了
「沒有破壞既有路徑」。需要實車才能確認的項目：

- 明文滑板車是否接受 `source = 0x3E`
- `NINEBOT_PLAIN` 是否真的對應 `FrameCodec.Protocol.P2`（`5A A5`）而非 P1
- 各 register 的讀取長度參數是否正確
- 明文車的 `0xB5` 速度刻度
- 連續失敗重連門檻 5 次是否合適

---

## 8. 已完成：C2 NinebotCrypto 加密握手（2026-09-17）

### 8.1 又一個死碼發現

`ninebot-ble/src/ninebot_legacy.rs`（812 行）**已經有完整的 NinebotCrypto 實作**，
但 `ninebot-ffi` 只匯出 7 個 `mi_crypto` 函式 → **Rust 那條路徑同樣無法從 App 抵達**。
這解釋了為何 NinebotCrypto 方言從未真正運作過。

我選擇**移植到 Kotlin** 而非擴充 FFI，理由：
- 純 JVM 可單元測試，**不需要 native 建置步驟**
- 與 `FrameCodec`／`EscTelemetryParser` 的作法一致
- 無實車時，可測試性比執行效能重要

### 8.2 `NinebotCryptoCipher`

| 步驟 | 內容 |
|---|---|
| 金鑰衍生 | `SHA1(name(≤16,補0) ‖ ble_data)[0:16]` |
| 密碼器 | AES-128 **ECB、只加密、無 padding**（必須用 `AES/ECB/NoPadding`；預設 `"AES"` 會加 PKCS#5 變成 32 bytes） |
| nonce | `tag(1) ‖ ctr(4 **big-endian**) ‖ ble_data[0:8] ‖ tail(3)` |
| counter 0 | keystream = `AES-ECB(FW_DATA, key)` 重複 |
| counter ≠ 0 | CTR 式 block + 4-byte CBC-MAC 式 checksum + trailer 帶 counter |

### 8.3 ⚠️ 一個與 `FrameCodec` 的關鍵差異

`FrameCodec.checksum()` 用**寬 Int 累加器**，實際上永不行回繞。
但 NinebotCrypto 的第一幀 checksum **必須在 16 bits 回繞**（Rust 用 `wrapping_add`）：

- 300 bytes 的 `0xFF` → 正確是 **`0x2AD4`**，不是 `0x84FF`

用錯會讓加密車拒絕每一道命令，而且症狀是「車子不理我」而非 checksum 錯誤。
`NinebotCryptoCipherTest` 有一道測試**直接比對兩者必須不同**，防止未來被換回去。

### 8.4 `NinebotHandshake` 狀態機

`5B` → `5C`+`APP_DATA` → `5D`+UID 末 byte，三態 + PAIRED/FAILED。
**不含 I/O**，所以整個序列可測。`APP_DATA` 是硬編碼常數
（`4AEEBD73E2161C112D065A49CC6E8BB7`），這就是為何會話金鑰是確定性的。

`runNinebotHandshake()` 在 repository 中驅動它，並加上 **`NINEBOT_HANDSHAKE_MAX_ATTEMPTS = 12`**
上限 —— 一個永不失敗的階段會永遠回報「配對中」，使用者看不到錯誤。

### 8.5 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **294 個通過、0 失敗**（C2 前 272） |
| 新增測試 | `NinebotCryptoCipherTest` 30 個、`NinebotHandshakeTest` 22 個 |
| 跨語言驗證 | Kotlin 移植通過 **Rust 的原始向量**：FIPS-197 AES、SHA-1、以及回繞 checksum |
| 實機安裝 | `pm install -r` 成功 |
| 啟動 + demo + gateway | 無 crash，`subscribers: 1`，眼鏡收到 `21.99 km/h` |

### 8.6 跳過的測試（需實車）

**整個 NinebotCrypto 分支在無實車時無法觸發**，所以這次只驗證「沒破壞既有路徑」。

- `APP_DATA` 是否被真實車輛接受（crypto 報告把這列為首要未解問題）
- 步驟順序與 900/500 ms 間隔是否正確
- `0x5B` 回應是否真的剛好 30 bytes、UID 是否真的在 offset 16
- `ble_data` 是否真的在 offset 7
- 配對是否需要使用者按電源鍵
- **加密遙測迴圈尚未實作** —— 配對成功後目前仍走明文輪詢做 fallback，
  這是已知的缺口，會在後續階段補上

---

## 9. 已完成：D 低風險可回復寫入（2026-09-17）

### 9.1 `ScooterSettingsWriter`

只模型化四種操作，全部是 ≤2-byte 設定 register：

| 操作 | register | payload |
|---|---|---|
| KERS 弱/中/強 | `0x7B` | `{00\|01\|02, 00}` |
| 巡航 關/開 | `0x7C` | `{00\|01, 00}` |
| 尾燈 開/關 | `0x7D` | 16-bit 字，**大端**，bit 1 |
| 單位 km/h ↔ mph | `0x7D` | 16-bit 字，**大端**，bit 4 |

**刻意排除**（附理由）：
- **鎖/解鎖 `0x70`/`0x71`** —— 可回復，但行進中誤鎖是真實安全事件；App 已有此功能，
  再開第二條路徑沒有收益
- **SHFW profile `0xB2`** —— 改變生效中的速度/電流上限，且 profile 名稱來自
  無權限保護的匯出廣播接收器，過期清單會讓騎士選到不是他看到的名字
- **任何 BMS 寫入** —— 參考 App 本身也完全不做

### 9.2 `0x7D` 的陷阱與 read-modify-write

`0x7D` **讀取是小端、寫入是大端**，而且尾燈（bit 1）與 mph（bit 4）**共用同一個 16-bit 字**。
所以本檔案中**每一道寫入都表達為「給定剛讀到的字，產生要寫的字」**，
`setTailLight` / `setUnits` 都**要求傳入剛讀回的值**，無法憑猜測組出 payload。

`setTailLightReversible` / `setUnitsReversible` 另外回傳**反轉寫入**，
所以呼叫端不必再讀一次就能還原。

### 9.3 測試抓到我自己兩個 bug

1. **運算子優先序**：Kotlin 的 `and` 綁得比 `shl` 緊，所以
   `a and 0xFF shl 8` 實際被解析為 `(a and 0xFF) shl 8`。改成明確的 helper。
2. **read-back 位元組語意搞反**：我原本預期「裝置回寫相同位元組就會驗證通過」，
   但因為兩端對位元組序的處理相反，**回寫相同位元組反而不會通過**。
   這件事本身就是要靠讀回驗證才能發現的問題，測試已明確記錄這個不對稱。

### 9.4 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **314 個通過、0 失敗**（D 前 294） |
| 新增測試 | `ScooterSettingsWriterTest` 20 個 |
| 實機安裝 | `pm install -r` 成功 |
| 啟動 + demo + gateway | 無 crash，`subscribers: 1`，眼鏡收到 `21.99 km/h` |
| 解析錯誤 | 0 個 `Dropped malformed` |

### 9.5 跳過的測試（需實車）——**這一階段幾乎全部跳過**

**沒有任何一道寫入曾被送到真實車輛。** 這是刻意的：寫入未知韌體的越界值無法遠端復原。
需要實車才能確認：

- 四道寫入是否被真實車輛接受
- `0x7D` 的寫入位元組序（本實作依 Scootbatt 採大端，但這是**從程式碼推斷**）
- 讀回值是否真的以相反位元組序回來（§9.3 的不對稱）
- KERS 值域是否只有 0/1/2
- 寫入是否需要已配對狀態
- **UI 尚未接線** —— `ScooterSettingsWriter` 目前只有單元測試，沒有任何呼叫端

---

## 10. ⚠️ 已知缺口：`ScooterSettingsWriter` 沒有呼叫端

五個階段的驗收都通過了，但**有一個新檔案是刻意的例外**：

| 檔案 | 呼叫端 |
|---|---|
| `ScooterReply` | ✅ 3 |
| `ScooterErrorCodes` | ✅ 5 |
| `EscTelemetryParser` | ✅ 30 |
| `BmsTelemetryParser` | ✅ 11 |
| `PlaintextRegisterSession` | ✅ 11 |
| `PlaintextTelemetryMapper` | ✅ 4 |
| `NinebotCryptoCipher` | ✅ 2 |
| `NinebotHandshake` | ✅ 2 |
| `DemoRideSource` | ✅ 5 |
| **`ScooterSettingsWriter`** | ❌ **0** |

`ScooterSettingsWriter` 通過了 20 個單元測試，但**沒有任何 UI 或 repository 呼叫它**，
原因有兩個且都是刻意的：

1. **寫入是唯一無法用 demo 模式驗證的類別。** demo 模式只驅動顯示路徑；寫入需要真的
   BLE 連線到真的車。在無實車條件下接好 UI，只會得到一顆**按下去必然失敗的按鈕**，
   而失敗原因（是 payload 錯？還是根本沒連線？）無法區分。
2. **這正是本專案先前產生死碼的模式**（`FrameCodec` 375 行、`ninebot_legacy.rs` 812 行
   都是這樣來的）。所以我不把它當成已完成，而是**明確列為缺口**。

### 接線的前置條件（實車到位後）

1. 讀 `0x7D` → 用 `EscTelemetryParser.statusBits` 解出目前狀態
2. 呼叫 `setTailLight`/`setUnits` 並帶入**剛讀到的字**
3. 送出 → **讀回驗證**（`verify`）→ 失敗則套用 `undo`
4. KERS/巡航需先讀 `0x7B`/`0x7C` 取得前值，才能提供 `undo`

**建議順序**：先在有實車的環境手動驗證 `0x7D` 的位元組序與讀回行為，再寫 UI。
在上述第 1–3 步於實車上確認之前，不要接線。
