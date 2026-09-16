# Scootbatt 逆向 — 總索引與核心結論

> 目標：`com.basse.scootbatt` 1.9.2 (versionCode 136)，minSdk 32 / targetSdk 36
> 來源 APK：APKPure XAPK（**非**使用者手機上的安裝版本 — 手機離線，見 §6）
> 方法：jadx 1.5.1 + apktool smali 位元組碼交叉驗證
> **所有結論均為靜態分析，尚未經任何實車驗證。**

## 報告導覽

| 檔案 | 內容 | 狀態 |
|---|---|---|
| `00-protocol-core.md` | 傳輸層、兩層信封、checksum、MTU、direction、調度表、**ESC 回應解析逐條對照**、錯誤碼表 | ✅ 我親自驗證 |
| `00b-write-commands.md` | **16 條寫入命令**、payload、風險排序、App 自身防護、認證需求 | ✅ 已修正初稿 4 處錯誤 |
| `00c-crypto-and-identity.md` | 型號判定（廣告 byte）、四種協定家族、`NinebotCrypto` byte-exact 演算法、小米 mible | ✅ 已修正初稿旗標錯誤 |
| `01-frame-envelope.md` | 子代理報告：xp0 全表、四種外層框、checksum 數學、position 語意、MTU | ✅ 已交叉驗證 |
| `03-crypto-handshake.md` | 子代理報告：完整握手序列、金鑰衍生、mible、與 nbcrypt.c 對照 | ✅ 684 行 |
| `04-write-commands.md` | 子代理報告：寫入完整清單、可達性證明、防護機制 | ✅ 427 行 |
| `05-connection-flow.md` | 子代理報告：**連線流程、輪詢排程器、訂閱順序硬約束、三段式型號識別、逾時/重連** | ✅ 690 行 |
| `02-telemetry-parsing.md` | 子代理報告：遙測欄位逐項 offset（撰寫中） | ⏳ |

---

## 1. 一句話結論

**Scootbatt 講的是標準 Ninebot/M365 ESC 協定，不是自創格式。**
傳輸走 Nordic UART Service，命令是 `0x3E + 位址 + 動作 + register + payload`，
外層再加 `5A A5` 信封與 16-bit 一補數 checksum。**Ninebot 品牌的車完全不需要加密**，
可直接與你的 M365-Rokid-HUD 對接。

## 2. 傳輸層

| UUID | 用途 |
|---|---|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Nordic UART Service（掃描過濾） |
| `6e400002-…` | **App → 車**：寫命令 |
| `6e400003-…` | **車 → App**：通知（遙測） |
| `0000fe95-…` | 小米服務（出現即強制走 mible 路線） |
| `00002902-…` | CCCD 訂閱 |

## 3. 封包兩層結構

```
內層（ScooterRequest.construct()，無長度、無 checksum）
  3E  <位址>  <動作>  <register>  <payload…>

外層（pe4.m12550k()，依協定家族）
  Ninebot        5A A5  len  <內層>                          sum_lo sum_hi
  NinebotCrypto  5A A5  len  AES(內層)  00 00  sum_lo sum_hi  ctr_hi ctr_lo
  Xiaomi         55 AA  len  <位址 動作 register payload>     sum_lo sum_hi
  XiaomiCrypto   55 AB  len  ctr(2)  AES-CCM  tag(4)         sum_lo sum_hi
```

- **checksum = 8-bit 和取一補數**（`sum ^ 0xFFFF`，小端），覆蓋長度 byte 到最後 payload
  byte。**不是 CRC-16。App 從不驗證入站 checksum。**
- 數值一律 **小端無號**，唯一例外是 `0x7D` 的**寫入**用大端（見 §5 風險）。
- MTU：`f19701r = 20`，`onMtuChanged` 改 `mtu-3`；**App 不呼叫 `requestMtu`**。
- `position` 是 **register 位址，不是序號**；多塊重組由 `hl2` 在 BLE 層完成。

## 4. 讀取（與你的 HUD 直接相關）

19 個可顯示遙測項（`ui3` 的完整詞彙表）：速度（km/h、mph、m/s）、平均速度（三種單位）、
CC 速度（km/h、mph）、電量、電池溫度、ESC 溫度、系統電壓、電流、功率、
油門值、煞車值、**續航里程、總里程、單趟里程、運行時間**。

ESC register 對照（**權威來源 `ao3.java:186-228`**，完整表見 `00-protocol-core.md` §3.2）：

| register | 讀取內容 | 解析 |
|---|---|---|
| `0x66` (102) | 版本（3×u16 + BCD） | `C1138os` case 0 |
| `0x39` (57) | 序號／MAC | case 10 |
| `0x1B` (27) | **錯誤碼** | case 4，完整錯誤表見 `00-protocol-core.md` §5 |
| `0x25` (37) | 速度或剩餘里程 ⚠️ | case 9，`u16 / 100`＋mi 換算 |
| `0x2F` (47) | 速度類 | case 2，`u16 / 100`＋mi 換算 |
| `0x29` (41) | **總里程** | case 8，`u32 / 1000`＋mi 換算 |
| `0x3E` (62) | **溫度** | case 3，`s16 * 0.1` 取整 |
| `0x75` (117) | **騎乘模式** | case 11，`0=Normal 1=ECO 2=Sport` |
| `0x7B` (123) | **訊號品質** | case 5，`0=Weak 1=Medium 2=Strong` |
| `0x7C` (124) | 單 byte 布林 | case 1 |
| `0x7D` (125) | 12-byte 區塊 | case 18 |
| `0x47`/`0x53`/`0xB9`/`0xBA`/`0x32`/`0x34`/`0xDA` | 其他 | case 13/7/16/12/14/15/17 |
| `0x10`/`0x1A` | 序號（model-aware） | `un0` case 1 / 0 |
| `0xB2` (178) | SHFW profile | `un0`/`C1524z4` |
| `0xB5`/`0x46` | — | `c70` case 1 / 0 |
| direction `0x22` / `0x23` | 內建 BMS / 外掛 eBMS | `C1252rv` / `qv0`（含電芯廠牌辨識、健康度） |

> ⚠️ **Scootbatt 只讀 24 個 ESC register，而且不讀電池百分比（`0x22`）、瞬時速度
> （`0x26`）、平均速度（`0x65`）、電流（`0x50`）。** 你的既有 register 表涵蓋更廣，
> **兩份資料互補，不可只用 Scootbatt**（詳見 `00-protocol-core.md` §6.3）。

## 5. 寫入（16 條，全部 needs-verification）

| 功能 | register | payload |
|---|---|---|
| 鎖 / 解鎖 | `0x70` / `0x71` | `{01,00}` |
| KERS 弱/中/強 | `0x7B` | `{00/01/02, 00}` |
| 巡航 關/開 | `0x7C` | `{00/01, 00}` |
| 尾燈、mph 單位 | `0x7D` | `u16` **大端**（讀取是小端 ⚠️） |
| SHFW profile | `0xB2` | profile index（**送兩次**） |
| SHFW 燈光/Dash | `base+21/22/23` | — |

**沒有 brick 等級命令**：無 OTA/DFlash/EEPROM/序號/地區/速度限制寫入，**零 BMS 寫入**。
最高風險：SHFW profile 切換（名稱來自無權限保護的廣播接收器）、巡航開啟、
`0x7D` 的位元組序不一致。

## 6. 對 M365-Rokid-HUD 整合的直接建議

1. **Ninebot 品牌車（G2/G30/F/E/D/GT/P/X160）→ 明文可直接做。** 用 §3 的兩層框 +
   §4 的 register 表即可穩定取遙測。**唯一要補的是 checksum**（你的既有實作可能已有）。
2. **遙測是「輪詢」不是「推送」。** App **從不呼叫 `readCharacteristic`** —— 每個值都是
   寫一個 `READ` 請求、再由 NUS-TX 通知拿回答。基準輪詢 **1000 ms**（電池頁 **500 ms**）。
3. **照抄它的請求排程器**（`C1286ss`）：**嚴格一次只允許一個未完成請求**，
   收到回應才送下一個；逾時則把同一請求**重排到佇列尾端**，`retryCount` 預設 **2**
   （`C1212qs.java:18`）→ **同一請求共送 3 次、無 backoff**，之後丟棄。
   這是你「穩定取得資訊」最該複製的部分。
4. **小米車（M365/Mi Pro/1S/Pro 2/Essential/Mi 3）→ 判定條件是「廣告資料 byte[1]==2」**，
   不是型號。若為 2 則需 `NinebotCrypto`：金鑰 = `SHA1(BLE名稱 ‖ K_fw)[0:16]`，
   `K_fw = 97CFB802 844143DE 56002B3B 34780A5D`，AES-128 ECB 單塊、無 IV/padding。
   握手三步 `5B/5C/5D`（`0x5B` 每 **900 ms**、`0x5C`/`0x5D` 每 **500 ms**），
   **因為 `app_data` 是硬編碼常數，會話金鑰是確定性的**。
5. **⚠️ Scootbatt 沒有任何心跳／超時計數機制** —— 連線死亡只靠 GATT disconnect callback
   或寫入失敗得知，而且沒有重連次數上限。**這一塊你要自己補強**，不要照抄。
6. **別把 `research/ninebotcrypto/nbcrypt.c` 的 64 KiB counter rollover 修正帶進來** ——
   Scootbatt 與 `ProtocolNinebot.kt` 都省略了它，改用無號比較重新同步。
7. **SS 回應解析前務必檢查長度** —— Scootbatt 的每個 case 都先驗長度，不符就整段跳過，
   這是它「拿到錯值」的防線。
8. **`PROTOCOL_FAMILIES.md` 的 register 表要與本文件的 §6 對照合併使用**（互補，非取代）。

## 7. 未驗證（最重要的一節）

1. **沒有任何一項經過實車驗證。** 沒有滑板車、沒有手機、沒有網路。
2. 分析對象是 APKPure 的 **1.9.2**，不是使用者手機上的版本 → 命令表可能不同。
3. `0x7D` 位元組序矛盾、SHFW register 語意與值域、Xiaomi 框長度 byte 差異。
4. `case↔opcode` 兩處衝突（case 2 與 case 4 各對應兩個 opcode）。
5. 實車是否驗證 checksum（App 不驗，車可能驗）。
6. `MASTER_TO_BLE_READ` (0x24) 用途未知；`0x23`/`0x25` 雙重語意未解。
7. 設定是否跨斷電保存；車輛對越界值的容忍度。
