# Scootbatt 協定逆向 — 第 1 部分：封包信封、命令表、ESC 回應解析

> 目標 APK：`com.basse.scootbatt` 1.9.2 (versionCode 136)，minSdk 32 / targetSdk 36
> 來源：APKPure XAPK（**非**使用者手機上的安裝版本；見文末「版本差異風險」）
> 方法：jadx 1.5.1 反編譯 + apktool smali 位元組碼交叉驗證
> 證據基準：`apktool-smali/smali/*.smali`（原始名稱）與 `jadx-out/sources/`（jadx 命名）
> 所有結論皆為**靜態分析**，**尚未經任何實車驗證**。

jadx 會把混淆後的類別改名（`os` → `C1138os`、`xp0` 保持原樣）。smali 才是權威，因為
jadx 對 `os.h()` 這個方法放棄了部分反編譯，而 smali 完整保留。

---

## 1. 傳輸層（BLE）

App 完全走 **Nordic UART Service (NUS)**，不碰廠商私有 GATT 服務。

| 常數（`xp0` 前的欄位名） | UUID | 用途 | 證據 |
|---|---|---|---|
| `f19678t` | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NUS 服務；掃描過濾條件 | `p000/C0993mx.java:28` |
| `f19679u` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | **App → 車**：寫入命令 | `p000/C0993mx.java:31` |
| `f19680v` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | **車 → App**：通知（遙測） | `p000/C0993mx.java:34` |
| `f19681w` | `0000fe95-0000-1000-8000-00805f9b34fb` | 小米服務 | `p000/C0993mx.java:37` |
| `f19677s` | `00002902-0000-1000-8000-00805f9b34fb` | CCCD（訂閱） | `p000/C0993mx.java:25` |
| `f19682x` | `00000010-0000-1000-8000-00805f9b34fb` | **小米 `0000fe95` 服務的私有特徵值**（不是標準 GATT Battery Level） | `p000/C0993mx.java:40` |
| `f19683y` | `00000019-0000-1000-8000-00805f9b34fb` | **小米 `0000fe95` 服務的私有特徵值**（不是標準 GATT Firmware Revision） | `p000/C0993mx.java:43` |

> ⚠️ **初稿把 `00000010`/`00000019` 標成標準 GATT 的 Battery Level / Firmware Revision，
> 這是錯的。** 它們是 `0000fe95` 小米服務的私有特徵值，App 只在**橢圓認證路徑**中
> 訂閱（CCCD）它們，從不 `readCharacteristic`
> （`BleForegroundService.m3245p`，`:567-600`，日誌字串為 `"writeElliptic: "`）。

**正確的兩條呼叫路徑**（初稿把兩者混為一談）：

| 路徑 | 鏈路 | 用途 |
|---|---|---|
| **遙測（主要）** | `C0993mx.onCharacteristicChanged`（`p000/C0993mx.java:264`）→ `service.mo3235f(value)`（`services/BleForegroundService.java:228`）→ `RunnableC1454x9:121` → `ScooterFragment.mo3235f`（`ScooterFragment.java:1074`）→ `hl2.m6915a`（重組）→ **`ScooterFragment.m3299r0`（`:1579`）= 全 App 唯一的解析分派點** | NUS-TX 通知 |
| **寫入輔助** | `BleForegroundService.m3245p(byte[], UUID)`（`:567`） | 依 UUID 選特徵值寫入；**不是通知入口**（初稿誤標） |

`m3299r0` 是唯一解析點，已證明：整個 dex 只有一個 `vp0.h(...)` 呼叫站點。

---

## 2. 命令信封（兩層）

### 2.1 內層框 — `ScooterRequest.construct()`

由 `models/scooter/helpers/ScooterRequest.java:67-112` 產生：

```
offset 0    1          2        3          4..
       0x3E  direction  action   position   payload…
```

- 長度 = `payload.length + 4`（`new byte[this.payload.length + 4]`）。
- **內層框沒有長度 byte、沒有 checksum、沒有結束符。**
- **小端、無號 16-bit** 為數值讀取慣例（`AbstractC1491y9.m17773w()`：
  `ByteBuffer…LITTLE_ENDIAN.getShort() & 0xFFFF`，`p000/AbstractC1491y9.java:498-501`）。
- 請求 payload 幾乎都是 `{長度, 0}`（例如 `{2,0}`、`{4,0}`），即「我要幾個 byte 的回應」。
- `position` 實為 **register／記憶體位址**（例如 `0x70`、`0x7B`），**不是序號**。

### 2.2 外層信封 + checksum — `pe4.m12550k()`

**⚠️ 初稿此處曾誤判「無 checksum」，原因是只看了 `construct()`。**
checksum 由 `p000/pe4.java:1034` 的 `m12550k(byte[])` 依協定家族加上，共四種變體：

| 協定家族 | 外層格式 | 總長度 |
|---|---|---|
| `Ninebot`（明文） | `5A A5 ‖ len=payload ‖ 內層框 ‖ sum_lo sum_hi` | payload + 9 |
| `NinebotCrypto` | `5A A5 ‖ len ‖ AES(內層框) ‖ 00 00 ‖ sum_lo sum_hi ‖ ctr_hi ctr_lo` | payload + 13 |
| `Xiaomi`（明文） | `55 AA ‖ len=payload+2 ‖ dir ‖ action ‖ pos ‖ payload ‖ sum_lo sum_hi`（**無 `0x3E`**） | payload + 8 |
| `XiaomiCrypto` | `55 AB ‖ len ‖ ctr(2) ‖ AES-CCM ‖ tag(4) ‖ sum_lo sum_hi` | payload + 16 |

**checksum 演算法**（`pe4.java:1053`、`pe4.java:1179`）：

```java
int i3 = 0;
for (int i5 = 2; i5 < length + 3; i5++) i3 += bArr3[i5] & 255;  // 8-bit 累加
int i6 = i3 ^ 65535;                                            // 取一補數
bArr3[len]     = (byte) (i6 & 255);      // 小端：低位先
bArr3[len + 1] = (byte) (i6 >> 8);
```

- 覆蓋範圍：**長度 byte 起到最後一個 payload byte**（`5A A5` 前綴不計）。
- **是「8-bit 和的一補數」，不是 CRC-16/CCITT。** 全 App 找不到 CRC-16 多項式
  （`0x1021`/`0xA001`/`0x8408` 只出現在打包的 PDF 庫與 DTS 音訊 CRC）。
- **App 從不驗證入站 checksum。** 重實作時務必自行驗證。

### 2.3 分塊與 MTU

| 項目 | 值 | 證據 |
|---|---|---|
| 每塊大小 `f19701r` | 預設 **20** | `p000/C0993mx.java:97` |
| MTU 協商後 | `mtu - 3` | `C0993mx.java:435-440`（`onMtuChanged`） |
| `requestMtu()` 呼叫 | **全 App 沒有** → 維持 Android 預設 ATT MTU 23 ⇒ payload 20 | grep 全樹無命中 |
| 分塊寫入 | 第一塊立即寫，其餘進佇列 | `BleForegroundService.m3244o()`，`:482-531` |
| 多塊重組 | `hl2` 以 `frame[2] + overhead` 算總長後 stitch | `p000/hl2.java:25-93`，overhead `{9,13,6,16}` |

因此一個 32-byte 的 BMS 電芯電壓回應 = 41-byte 外層框 = **3 個 notification**。

### 2.4 Direction／address 語意

`direction` 是**裝置位址**；而 `0x3E` 標記的**位置**才編碼傳輸方向：

- 請求：`… 3E addr action pos …`（App → 車）
- 回應：`… addr 3E action pos …`（車 → App）

證據：`oh3.m11880i`（`p000/oh3.java:404-422`）把 Xiaomi dir 32/33/34 映射為
`(0x3E, addr)`、35/36/37 映射為 `(addr-3, 0x3E)`；且 App 自身的認證請求為
`3E 21 5B 00`（`ScooterFragment.java:1472`），期望回應為
`5A A5 30 21 3E 5B …`（`:1129`）。回應解析：len@[2]、addr@[3]、`0x3E`@[4]、
action@[5]、register@[6]、payload@[7..]（`ScooterFragment.java:1590-1599`）。

位址表：`0x20` ESC、`0x21` BLE、`0x22` 內建 BMS、`0x23` 外掛/eBMS（由旗標
`uo3.f30567u0` 決定，僅 esx/e 型號，`jh4.java:414-419`）、`0x25` = Xiaomi BMS 回應、
`0x11` = Xiaomi "z10" 的強制方向（`pe4.java:1166`）。

> **位址 byte 本身就扮演「BMS index」角色** —— App 內沒有 `bmsIndex` 這種東西。

### 2.5 Direction 位址表（`p000/xp0.java` 靜態初始化，完整）

| 常數 | 值 | 語意 |
|---|---|---|
| `MASTER_TO_SCOOTER` | `0x20` (32) | 發給 ESC |
| `MASTER_TO_BLE` | `0x21` (33) | 發給 BLE 模組（**App 未使用**） |
| `MASTER_TO_BATTERY` | `0x22` (34) | 發給 BMS |
| `MASTER_TO_EXTERNAL_BATTERY` | `0x23` (35) | 發給外掛電池 |
| `MASTER_TO_BLE_READ` | `0x24` (36) | 讀 BLE 模組（**App 未使用**） |

呼叫次數統計（全部 65 個 `construct()` 站點）：`0x20`→47、`0x22`→9、`0x23`→9。

> `xp0.f34555d` 只是 `$VALUES` 的複本，**僅供 `construct()` 做 byte→常數的線性掃描**；
> `xp0.f34554c = new cr8(21)` **不是 map**，是未使用的 R8 synthetic lambda。
> byte→名稱沒有查表，未知 byte 會直接 **NPE**（`ScooterRequest.java:91,107`）。

### 2.6 Action／操作類型（同一 enum）

| 常數 | 值 | 語意 |
|---|---|---|
| `READ` | `0x01` | 讀 |
| `WRITE` | `0x02` | 寫（要回應） |
| `WRITE_NO_REPLY` | `0x03` | 寫（不要回應） |
| `SHFW_READ` | `0x31` (49) | **ScooterHacking Firmware** 讀 |
| `SHFW_WRITE` | `0x32` (50) | **SHFW** 寫 |
| `SHFW_WRITE_NO_REPLY` | `0x33` (51) | **SHFW** 寫（不回應） |

SHFW 這組代表 Scootbatt 直接支援 ScooterHacking 自訂韌體的擴充命令集 —— 對照
`/home/kali/ScooterHacking/research/` 既有研究，這條線值得優先對接。

---

## 3. 命令與解析的調度表

App 用 **register byte** 當 key 查表，值是一個實作 `h(int len, byte[] data)` 的回應處理器。

> ⚠️ **權威來源**：`ao3.java:182-228` 是**唯一**同時給出 register byte 與 handler 的地方。
> 初稿是從 `dp3.java:339-348` 反推，但那份只涵蓋部分 register 且我誤讀了幾個十進位值
> （把 `0x66` 寫成 `0x9A`、`0x39` 寫成 `0x47`、`0x2F` 寫成 `0x1B`）。
> **本節已全部改用 `ao3.java` 重建。**
>
> **請求 case index ≠ 回應 case index**：`C1138os.mo2827f()`（建請求）與
> `C1138os.mo2829h()`（解回應）是**兩套不同的 case 編號**，初稿把兩者混為一談，
> 這是「case 2 與 case 4 同時對應兩個 opcode」那個假衝突的成因。

### 3.1 四張調度表

`ScooterFragment` 內有四個 `ConcurrentHashMap`（`ao3.java:186-187`、
`ScooterFragment.java:375-378`）：

| 表 | 欄位 | 方向 | 內容 |
|---|---|---|---|
| ESC 主表 | `f4047X2` | `0x20` / `0x21` | ESC register（`ao3.java:186-221`） |
| ESC 副表 | `f4048Y2` | `0x20` | 少數 register + `un0`/`d83`/`C1524z4`（`ao3.java:222-228`） |
| 內建 BMS | `f4049Z2` | `0x22` | `C1252rv`（`ScooterFragment.java:477-487`） |
| 外掛 eBMS | `f4050a3` | `0x23` | `qv0`（`ScooterFragment.java:488-497`） |

### 3.2 ESC register 完整表（`ao3.java:186-228`，權威）

| register | 十進位 | handler | 解析內容（見 §4） |
|---|---|---|---|
| `0x10` | 16 | `un0` case 1 | 序號（model-aware） |
| `0x1A` | 26 | `un0` case 0 | 序號（model-aware） |
| `0x1B` | 27 | `C1138os` case 4 | **錯誤／警告碼** |
| `0x25` | 37 | `C1138os` case 9 | `u16 / 100`（速度類，支援 mi 換算） |
| `0x29` | 41 | `C1138os` case 8 | `u32 / 1000`（里程類，支援 mi 換算） |
| `0x2F` | 47 | `C1138os` case 2 | `u16 / 100`（速度類） |
| `0x32` | 50 | `C1138os` case 14 | `u32` → 日期字串 |
| `0x34` | 52 | `C1138os` case 15 | `u32` → 日期字串 |
| `0x39` | 57 | `C1138os` case 10 | 序號／MAC（`len >= 5`） |
| `0x3E` | 62 | `C1138os` case 3 | `s16 * 0.1` 取整（溫度類） |
| `0x46` | 70 | `c70` case 0 | — |
| `0x47` | 71 | `C1138os` case 13 | `u16 / 100` → double |
| `0x53` | 83 | `C1138os` case 7 | `s16 * 0.01` |
| `0x66` | **102** | `C1138os` case 0 | 三組 16-bit + 版本（BCD 風格） |
| `0x75` | 117 | `C1138os` case 11 | **騎乘模式**：0=Normal／1=ECO／2=Sport |
| `0x7B` | 123 | `C1138os` case 5 | **訊號品質**：0=Weak／1=Medium／2=Strong |
| `0x7C` | 124 | `C1138os` case 1 | 單 byte 布林 |
| `0x7D` | 125 | `C1138os` case 18 | 12-byte 區塊 |
| `0xB5` | 181 | `c70` case 1 | — |
| `0xB9` | 185 | `C1138os` case 16 | `u16 / 100`（支援 mi 換算） |
| `0xBA` | 186 | `C1138os` case 12 | `u16` → 日期字串 |
| `0xDA` | 218 | `C1138os` case 17 | 12-byte，交給 `La14` 迭代 |
| `0xB2` | 178 | `un0` case 2 ＋ `C1524z4` case 0 | **SHFW profile**（寫入見 00b §1.2） |
| `0x87` | 135 | `C1524z4` case 3 | — |
| `0x4C` | 76 | `C1524z4` case 2 | — |
| `0x11` | 17 | `C1524z4` case 1 | — |
| `0x3C` / `0x77` / `0x01` | 60 / 119 / 1 | `d83` | — |
| `0xFF` | 255 | `C1138os` case 6 | `(b[1]<<8) \| b[0]`，排除 0 與 255 |
| `0xD4` | 212 | `un0` case 2 群組 | — |

### 3.3 BMS／eBMS 表

| register | 十進位 | 內建 BMS (`C1252rv`) | 外掛 eBMS (`qv0`) |
|---|---|---|---|
| `0x10` | 16 | case 5 | case 6 |
| `0x18` | 24 | case 7 | case 0 |
| `0x1B` | 27 | case 2 | case 3 |
| `0x20` | 32 | case 4 | case 5 |
| `0x30` | 48 | case 0 | case 1 |
| `0x31` | 49 | — | case 8 |
| `0x35` | 53 | case 6 | case 7 |
| `0x3B` | 59 | case 3 | case 4 |
| `0x40` | 64 | case 1 | case 2 |

**已確認的 BMS 附加能力**：`C1252rv` 內建電芯廠牌辨識字串
`Blue cells (LG)`、`Blue cells (EVE ICR18650)`、`Grey cells`、`Purple cells`，
以及健康度 `Healthy` / `Normal` / `Poor`（`p000/C1252rv.java` 字串常數）。

---

## 4. ESC 回應解析逐條對照（核心成果）

來源：`apktool-smali/smali/os.smali` 方法 `h(I[B)V`（第 1599–5048 行），
`packed-switch` 表在第 36 行處。以下 `w(off)` = 小端無號 16-bit、`v(b)` = 32-bit、
`x(val, z)` = 日期字串轉換。

**case → register 對應取自 `ao3.java:186-228`（權威），不是 `dp3.java`。**

| case | register（權威） | 讀取 | 縮放／轉換 | 寫入欄位 | 備註 |
|---|---|---|---|---|---|
| 0 | **`0x66`** | `w(0)`、`w(2)`、`w(4)`、`b[4]`、`b[5]` | 版本號 BCD 風格：`((b[4]&0xF0)>>4)*10 + b[5]*100 + (b[4]&0x0F)` | `f30538g`、`f30536f`、`f30534e`、`f30514N` | `f30514N` = 版本異常旗標（值 <72 或 >200 視為異常） |
| 1 | `0x7C` | `b[0]` | `== 1` | `f30566u` | 僅當 `len == 1` |
| 2 | **`0x2F`** | `w(0)` | `/100.0`，英里模式 ×0.621371 | `f30556p`（數值）、`f30558q`（`"%.2f mi"` / `"%.1f km"`） | 速度類 |
| 3 | `0x3E` | `getShort(0)*0.1` | 四捨五入取整 | `f30548l` | 溫度類 |
| 4 | `0x1B` | `w(0)` | 錯誤碼 → 文字 | `f30544j`（碼）、`f30542i`（文字） | 僅當 `len == 2`；完整錯誤碼表見 §5 |
| 5 | `0x7B` | `b[0]` | 0/1/2 → Weak/Medium/Strong | `f30512L`、`f30513M` | 僅當 `len == 2` |
| 6 | `0xFF` | `(b[1]&0xFF)<<8 \| (b[0]&0xFF)` | 排除 0 與 255 | `f30574y`、`f30496A`（皆設 true）、`f30532d` | 僅當 `len == 2` |
| 7 | `0x53` | `getShort(0)*0.01` | — | `f30509I` | |
| 8 | `0x29` | `v(b)` | `/1000.0`，英里 ×0.621371 | `f30546k` | 里程類 |
| 9 | `0x25` | `w(0)` | `/100.0`，英里 ×0.621371 | `f30552n`（數值）、`f30554o`（`"…mi"` / `"…km"`） | 速度類 |
| 10 | `0x39` | `b[0..3]` | 前三 byte 併成字串再 parse 成 int；另組 `a.b.c` | `f30576z`、`f30574y`=true、`f30496A`=false、`f30532d` | 序號／MAC（僅當 `len >= 5`） |
| 11 | `0x75` | `b[0]` | 0=Normal／1=ECO／2=Sport／其他 `"N/D"` | `f30550m` | 僅當 `len == 1` |
| 12 | **`0xBA`** | `w(0)` | → 日期字串（`x(v,false)`） | `f30562s` | |
| 13 | `0x47` | `w(0)` | `/100.0` | `f30508H` | |
| 14 | `0x32` | `v(b)` | → 日期字串（`x(v,true)`） | `f30560r` | SHFW 相關 |
| 15 | `0x34` | `v(b)` | → 日期字串 | `f30564t` | SHFW 相關 |
| 16 | **`0xB9`** | `w(0)` | `/100.0`，英里 ×0.621371 | `f3054E`、`f3054F` | |
| 17 | `0xDA` | 12 bytes | 交由 `La14` 逐項處理後寫入 | `Luo3;->c` | 僅當 `len == 12` |
| 18 | `0x7D` | 12 bytes | `La14` 迭代 | `Luo3;->c` | 僅當 `len == 12` |

> ✅ **原本標記的 case↔opcode 衝突已解決。** 成因是初稿混淆了
> `C1138os.mo2827f()`（**建請求**）與 `C1138os.mo2829h()`（**解回應**）兩套不同的
> case 編號。改用 `ao3.java` 的 register→handler 表後，每個 case 都只有一個 register。
> 詳細的請求 case 表見 `01-frame-envelope.md` 附錄。

**解析器共同特徵**（對重實作很重要）：
- 每個 case 都先檢查 `len`（`if-eq v1, …`），長度不符就整個 case 跳過 —— 這解釋了為何
  觸發順序與長度協商（MTU）會直接影響資料是否更新。
- 數值欄位幾乎都寫入 `uo2` 型別的 `MutableStateFlow`-like 物件（`Lxb2;->i(Object)`），
  即 UI 是反應式更新；整合時可視為「每個欄位一個可觀察值」。
- 英里／公里切換由 `Preferences.P()` 決定，套用點在解析階段而非顯示階段。

---

## 5. ESC 錯誤／警告碼表（完整，`0x7C`）

`apktool-smali/smali/os.smali` case 4 的第二層 `packed-switch`（42 個條目），
值為 `w(0)` 讀到的錯誤碼：

| 碼 | 訊息 | 碼 | 訊息 |
|---|---|---|---|
| 0 | None - all OK | 35 | ESC has default S/N |
| 10 | BLE or ESC failure | 36 | eBMS connector or charging failure |
| 11 | Phase A sensor failure | 37 | BMS connector or charging failure |
| 12 | Phase B sensor failure | 38 | Charging over-current |
| 13 | Phase C sensor failure | 39 | Battery overheat |
| 14 | Throttle handle failure | 41 | Ext battery overheat |
| 15 | Brake handle failure | 42 | No eBMS data |
| 18 | Hall sensors failure | 43 | Invalid eBMS config |
| 19 | Wrong main battery voltage | 44 | eBMS has default S/N |
| 20 | Wrong ext battery voltage | 45 | Battery cell deep discharge |
| 21 | No BMS data | 46 | Ext battery cell deep discharge |
| 22 | Invalid BMS config | 49 | Wrong BMS firmware version |
| 23 | BMS has default S/N | 50 | Wrong eBMS firmware version |
| 24 | Supply voltage out of range | 51 | Wrong BLE firmware version |
| 27 | ESC config invalid (change SN) | 52 | BMS firmware incompatible with DRV |
| 32 | Missing IoT device | 53 | Incompatible external battery |
| | | 54 | Motor C phase disconnected |

其餘碼 → `"Unknown error code"`。注意訊息是英文硬編碼，代表**這個 App 的錯誤表不隨語系變動**。

---

## 6. 與本工作區既有 register 文件對照

對照對象：`repo/doc/PROTOCOL_FAMILIES.md` §8.1/8.2（來源 etransport/ninebot-docs）。

### 6.1 語意一致的 register（Scootbatt 可作為獨立佐證）

| register | Scootbatt 解析方式 | 既有文件語意 | 判定 |
|---|---|---|---|
| `0x1B` | `w(0)` → 錯誤碼表 | error | ✅ 一致 |
| `0x25` | `w(0) / 100`，支援 mi 換算 | remaining mileage (km×100) | ✅ 一致（**剩餘里程**） |
| `0x29` | `v(b) / 1000`，4 byte | total mileage (m, 4B) | ✅ 一致（**總里程**） |
| `0x3E` | `s16 * 0.1` 取整 | frame temperature | ✅ 一致（**溫度**） |
| `0x53` | `s16 * 0.01` | motor phase current (0.01 A) | ✅ 一致 |
| `0x1A` | `un0` case 0（model-aware） | ESC firmware version | ✅ 一致 |
| `0x75` | `b[0]`：0/1/2 → Normal/ECO/Sport | operation mode (0=N 1=E 2=S) | ✅ 一致 |

→ **`0x25` 是速度或剩餘里程？** 既有文件寫「remaining mileage」但社群另一處寫
`0x26 = speed`。Scootbatt 的 `0x25` 有 `km`/`mi` 單位字串且 `len == 2`，
**兩種解釋都成立**，需實車定案（列為未驗證）。

### 6.2 Scootbatt 有讀、既有文件未記載的 register（新資訊）

`0x2F`（`u16/100`，支援 mi 換算）、`0x32`、`0x34`、`0xBA`（皆 `u32` → 日期字串）、
`0x39`（序號／MAC，`len >= 5`）、`0x66`（3×u16 + BCD 版本）、`0x7B`（3 態列舉）、
`0x7C`（單 byte 布林）、`0x7D`（12-byte 區塊）、`0xDA`（12-byte）、
`0xB2`（SHFW profile）、`0xB5`、`0xB9`、`0x87`、`0x4C`、`0x46`、`0x11`、`0x3C`、`0x77`、`0x01`、`0xFF`。

### 6.3 ⚠️ 既有文件有、**Scootbatt 完全沒讀**的 register（互補，非衝突）

| register | 既有文件語意 |
|---|---|
| `0x22` | battery % |
| `0x26` | speed |
| `0x48` | battery V |
| `0x50` | battery current |
| `0x65` | average speed |
| `0xB4` / `0xB6` / `0xB7` / `0xBB` | mirror block：電量／平均速度／里程／溫度 |
| `0x00` | magic `0x515C` 探測 |
| `0x1C` | warning |
| `0x1F`,`0x3F`,`0x49`,`0xBC`,`0xBD`,`0xBF` | ESx 超集欄位 |

> **這是本節最重要的發現：兩份資料是互補的。** Scootbatt 只輪詢 24 個 ESC register，
> 電池百分比、瞬時速度、平均速度、電流等**它一律不讀**（那些欄位在 Scootbatt 是靠
> `0x25`/`0x29`/`0x7D` 等其他 register 推導，或直接不顯示）。
> 因此 **M365-Rokid-HUD 不應以 Scootbatt 為唯一依據** —— 你的既有 register 表涵蓋更廣，
> 兩者應合併使用。

### 6.4 型號 byte 的對照

`PROTOCOL_FAMILIES.md:145` 的裝置位址表（`0x20` ESC · `0x21` BLE · `0x22` BMS ·
`0x23` ext BMS · `0x3D`/`0x3E`/`0x3F` apps）與 Scootbatt 的 `xp0` 完全一致，
且 Scootbatt 額外確認 `0x3E` 同時是**內層框標記**（見 §2.4）。

## 7. 未驗證 / 需要實車

**已於後續子分析解決（原本列在此處，現已不再是未知）**：

- ~~Checksum 是否存在~~ → **已解**：由 `pe4.m12550k()` 附加，是「8-bit 和的一補數」
  （非 CRC-16），見 §2.2。**但 App 從不驗證入站 checksum**。
- ~~加密／握手尚未分析~~ → **已解**：見 `00c-crypto-and-identity.md`。
- ~~寫入命令尚未清點~~ → **已解**：16 條，見 `00b-write-commands.md`。
- ~~尚未與 `research/ninebotcrypto/` 對照~~ → **已解**：`K_fw` 常數與 `nbcrypt.c:17`
  完全相同，握手序列與 `ProtocolNinebot.kt` 一致（見 03 報告的 cross-check 段）。
- ~~case↔opcode 衝突~~ → **已解**：改用 `ao3.java` 的 register→handler 表後每個 case
  只有一個 register。原本的「衝突」是請求 case 與回應 case 兩套編號被混用所致（見 §4）。

**仍然未解**：

1. **`0x25` 究竟是速度還是剩餘里程** —— 縮放與單位字串兩者都成立（§6.1）。
2. **欄位語意**：`f30556p` 等欄位名經 R8 混淆，本文件的「速度／里程／溫度」是由縮放係數
   與單位字串推斷，**未經 UI 標籤對照**。
3. **`MASTER_TO_BLE_READ` (0x24) 的用途** —— App 完全未使用。
4. **`0x23`/`0x25` 的雙重語意**；Ninebot 用 `0x25` 做什麼。
5. **`0x11`（Xiaomi "z10" 強制方向）**的適用範圍。
6. **實車是否驗證 checksum** —— App 不驗，但車子可能驗。
7. **版本差異風險**：本分析對象是 APKPure 上的 1.9.2，**不是**使用者手機安裝的版本
   （手機離線無法確認）。若手機為舊版，命令表可能不同。
8. **`0x3E` 位元組順序慣例的實車確認**（請求 `3E addr` / 回應 `addr 3E`）。
9. **`onMtuChanged` 是否真的會觸發**（App 不呼叫 `requestMtu`，故可能永遠維持 20）。
