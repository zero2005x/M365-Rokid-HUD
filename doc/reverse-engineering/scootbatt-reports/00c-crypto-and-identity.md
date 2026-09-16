# Scootbatt 協定逆向 — 第 3 部分：型號判定與加密握手

> ⚠️ 靜態分析，**未經實車驗證**。來源：`apktool-smali/smali/*.smali`、
> `jadx-out/sources/`。目標：Scootbatt 1.9.2 (136)。

---

## 1. 型號判定：從 BLE 廣播的 6-byte 區塊

`p000/C1142ow.java` 的建構子接收 BLE 掃描到的位元組陣列，流程為：

1. 在陣列中搜尋 **3-byte 標記 `FF 4E 42`**（`bArr2 = {-1, 78, 66}`，即 `0xFF 'N' 'B'`
   —— **"NB" = Ninebot**）。
2. 找到後取 **6 bytes**（`i` 到 `i+6`）。
3. `copyOfRange[0]`（型號 byte）→ 查表決定型號、顯示名與 **`isXiaomi`**。
4. `copyOfRange[1] == 2` → **`useCrypto`**（與型號表無關的另一個 byte！）。

> `C1142ow.java:36` 的 `byte[] bArr2 = {-1, 78, 66};` 是搜尋樣式。
> `copyOfRange[3] == 0 && copyOfRange[4] == 0` 是額外的前置條件檢查。
> `C1142ow.java:117`：`this.f22890c = copyOfRange[1] == 2;`

### 1.1 ⚠️ 兩個旗標是獨立的（初稿此處曾寫錯，已修正）

傳入 `Scooter` 的參數順序（`global/Scooter.java:24`）為
`Scooter(name, model, friendlyModel, useCrypto, isXiaomi, bleDevice, isFav)`，
呼叫點 `ui/fragments/BluetoothScanFragment.java:761`：

```java
new Scooter(str, c1142ow.f22888a, c1142ow.f22892e,
            c1142ow.f22890c,          // useCrypto  ← 廣告 byte[1]==2
            c1142ow.f22889b,          // isXiaomi   ← 型號表的那個 boolean
            nx0Var.f21039a, …)
```

| 旗標 | 來源 | 意義 |
|---|---|---|
| `useCrypto` | **廣告資料 `copyOfRange[1] == 2`** | 該車是否使用 `5A A5` 加密框 |
| `isXiaomi` | **型號表**（下方 boolean） | 是否走小米路線 |

**因此型號表的第三欄是 `isXiaomi`，不是 `useCrypto`。**

### 1.2 完整型號表（型號 byte → 內部名 / 顯示名 / **isXiaomi**）

| byte | 內部名 | 顯示名 | `isXiaomi` |
|---|---|---|---|
| 32 | `m365` | Xiaomi M365 | **true** |
| 33 | `esx` | Ninebot ESx | false |
| 34 | `pro` | Mi Pro | **true** |
| 35 | `t15` | Ninebot Air T15 | false |
| 36 | `max` | Ninebot G30 | false |
| 37 / 43 | `1s` | Mi 1S | **true** |
| 39 | `e` | Ninebot E | false |
| 40 | `pro2` | Mi Pro 2 | **true** |
| 41 | `lite` | Mi Essential | **true** |
| 46 | `mi3` | Mi 3 | **true** |
| 69 | — | （見 `C1142ow.java:110` 前的條目） | — |
| 74 | `bonk` | Ninebot X160 | false |
| 119 | `p100` | Ninebot P100 | false |
| 120 | `g65` | Ninebot G65 | false |
| 125 | `e2` | Ninebot E2 | false |
| **-125 (0x83)** | `g2` | **Ninebot G2** | **false** |
| 其餘條目 | `f`, `f2`, `f65`, `gt1`, `gt2`, `d18`, `d28`, `d38`, `p65` | 同名 Ninebot 各型 | false |

（`bonk` = Ninebot X160，作者取的玩笑名。）

`isXiaomi` 的通則：小米品牌（M365 / Mi Pro / Mi 1S / Mi Pro 2 / Mi Essential / Mi 3）
= true，Ninebot 品牌 = false。**但是否加密取決於廣告 byte[1]，不是這張表。**

### 1.3 型號判定結果如何決定協定

`ScooterFragment.java:900`（smali `6004-6030`）：

```java
// j0().b = crypto mode
cryptoMode = scooter.getUseCrypto() ? xq0.c   // NinebotCrypto
           : scooter.isXiaomi()    ? xq0.d   // Xiaomi（明文 55 AA）
           :                         xq0.a;  // Ninebot（明文 5A A5）
```

`xq0` 是協定家族 enum，四個值（`p000/xq0.java:26-34`）：

| 欄位 | 名稱 | 框頭 | 加密 |
|---|---|---|---|
| `xq0.a` | `Ninebot` | `5A A5` | 無（明文） |
| `xq0.c` | `NinebotCrypto` | `5A A5` | **固定金鑰 + 每命令混合** |
| `xq0.d` | `Xiaomi` | `55 AA` | 無（明文） |
| `xq0.e` | `XiaomiCrypto` | `55 AB` | **ECDH + HKDF + AES-CCM** |

> ⚠️ **enum 名稱會誤導**：那個「固定 16-byte 金鑰 + 每命令混合」的 scheme 叫
> **`NinebotCrypto`**（不是 `XiaomiCrypto`）；`XiaomiCrypto` 才是小米 mible
> 橢圓曲線／雲端那條。「M365 需要 NinebotCrypto」是正確的說法。

### 1.4 第三個、正交的覆寫條件

**若裝置在 NUS 之外同時曝露小米服務 `0000fe95`，App 一律走 `XiaomiCrypto`
（mible）路線，無視上面兩個旗標**（`p000/C0993mx.java:419-427`、`:465-474`），
握手也改到特徵值 `00000019` 上進行。

---

## 2. 加密握手：`5A A5` 框與 `0x5B/0x5C/0x5D` 狀態機

### 2.1 加密框格式

加密回應以 **`5A A5`** 開頭（`ScooterFragment.java` 內的 byte 比較）：

| 檢查式（decompiled） | 十六進位 | 意義 |
|---|---|---|
| `bArr[0]==90 && bArr[1]==-91 && bArr[2]==30 && bArr[3]==33 && bArr[4]==62 && bArr[5]==91` | `5A A5 1E 21 3E 5B` | **PRE_COMM 回應**（握手第 1 步回覆） |
| `bArr[0]==90 && bArr[1]==-91 && bArr[2]==0 && bArr[3]==33 && bArr[4]==62 && bArr[5]==92 && bArr[6]==1` | `5A A5 00 21 3E 5C 01` | **SET_PWD 已完成**（`0x5C` 回覆，flag=1 表示已配對） |
| `bArr4[3]==62 && bArr4[4]==33`（另一處） | `… 3E 21 …` | 加密框中的內層方向/動作 |

**與本工作區既有研究一致**：`repo/doc/PROTOCOL_FAMILIES.md:152` 記載舊世代
`3e 21 5b 00`（PRE_COMM）→ 每秒 `0x5C` 帶 16-byte 隨機金鑰 → `0x5D` 帶序號完成配對。

### 2.2 握手狀態機

`ScooterFragment.smali:11387` 有一個對 **`0x5B`** 的 `packed-switch`，三個分支
（`pswitch_2` / `pswitch_1` / `pswitch_0`），即 `0x5B` / `0x5C` / `0x5D` 三態。
`ScooterFragment.smali:4938` 另有 `const/16 v3, 0x5d`。

握手請求的送出位置（smali 行號）：`4938`（`0x5D`）、`7686`（`-0x5B`=回應比對）、
`7876`（`0x5B`）、`8045`（`-0x5B`）、`8089`（`0x5C`）。

> **重要**：握手邏輯位於 **UI fragment**（`ScooterFragment`），不在命令建構器
> （`ScooterRequest` / `C1138os`）內。因此**讀取遙測不需要握手**，握手只在
> 需要加密的型號（小米系列）與配對流程才走。

### 2.3 `NinebotCrypto` 完整演算法（byte-exact）

| 項目 | 值 | 證據 |
|---|---|---|
| 韌體固定金鑰 `K_fw` | `97 CF B8 02 84 41 43 DE 56 00 2B 3B 34 78 0A 5D` | `p000/C1375v6.java:1245`（與 `research/ninebotcrypto/nbcrypt.c:17` 完全相同） |
| 金鑰種子 | **BLE 廣播名稱** UTF-8（`new C1375v6(scooter.getName())`） | `ScooterFragment.java:906` |
| 會話金鑰 | `SHA1(name(≤16, 補 0) ‖ K_fw)[0:16]` | `C1375v6.java:459-469` |
| 加密 | **AES-128 ECB，只加密，單一 block**；`Cipher.getInstance("AES")` 產生的 PKCS#5 補齊 block 一律被 `arraycopy(…,16)` 丟棄 → **無 IV、無 padding、無 tag** | `C1375v6.java:80-87` |
| counter == 0 | 固定 keystream：`AES-ECB(K_fw, key)` 重複 XOR + 2-byte `~Σ` checksum | `C1375v6.java:548-563` |
| counter ≠ 0 | 類 AES-CTR block `01‖msgIt(4 BE)‖ble_data[0:8]‖00 00 00‖ctr8++` + 4-byte CBC-MAC 式 MIC + counter 低 2 bytes LE | `C1375v6.java:567-594`、`pe4.java:1096-1131` |
| 加密封包 | `5A A5 (rawLen-4) ‖ enc(raw) ‖ MIC(4) ‖ ctr(2)`，overhead 13；第一則為 `…‖00 00 crc16 00 00` | `hl2.java:40` |
| 金鑰重導 | `5A A5 1E 21 3E 5B` → `ble_data := inner[7:23]`，key := `SHA1(name‖ble_data)`；`5A A5 00 21 3E 5C 01` → key := `SHA1(app_data‖ble_data)`；`app_data` 擷取自 `5A A5 10 3E 21 5C 00` | `ScooterFragment.java:1074-1176`、`pe4.java:1136` |

**握手三步**（`ScooterFragment.m3297p0`，`:1405-1525`）：

1. `3E 21 5B 00`，每 **900 ms** 送一次 → 回應 payload 30 bytes，UID = `payload[16:30]`
2. `3E 21 5C 00 ‖ 4A EE BD 73 E2 16 1C 11 2D 06 5A 49 CC 6E 8B B7`
   （**硬編碼 16-byte 常數**，`:1485`）
3. `3E 21 5D 00 ‖ UID[14]`，每 **500 ms** 送一次（`m3284b0`，`:751-759`）
   → 收到 action `0x5D` 回應即進入 `Paired`（`:1818-1819`）

> 因為 `app_data` 是常數，**會話金鑰在已知車子回應的前提下是確定性的** ——
> 這對重實作是好消息：不需要隨機源，但也代表金鑰可被推導。

**與 `nbcrypt.c` 的差異**：Scootbatt（與 `ProtocolNinebot.kt:44`）**省略**了
64 KiB counter rollover 的進位修正（`nbcrypt.c:80-81`），改用無號比較重新同步
（`ScooterFragment.java:1146-1150`）。

---

## 3. 兩條互不相干的認證路徑

App 內有**兩套完全不同的密碼學**，不可混淆：

### 3.1 Ninebot 路線（`xq0.a` / `xq0.c`）

- 走 §2 的 `5A A5` 框 + `0x5B/0x5C/0x5D` 狀態機。
- 演算法實作**尚未定位到具體類別**。已排除：
  - `p000/m08.java`：Tink 的 AES-GCM 測試向量（第三方），非協定用。
  - `p000/fm2.java`：**小米 MiOT 路線**，見 §3.2。
  - `p000/f08.java`、`w08.java`、`z08.java`：Tink／Conscrypt。
- 待辦：以 `ScooterFragment` 的握手程式碼為起點向上追溯金鑰衍生
  （既有研究指出為 SHA-1 + 16-byte 金鑰 + XOR，見
  `research/ninebotcrypto/ProtocolNinebot.kt` 的 `calcSha1Key` / `createXor`）。

### 3.2 小米路線（`xq0.d` / `xq0.e`）— `p000/fm2.java`

`fm2.java` 明確是 **Xiaomi MiOT / MiBeacon 認證**：

| 證據 | 位置 |
|---|---|
| `"ECDH"`, `"secp256r1"` | `fm2.java` 字串常數 |
| `"HmacSHA256"` | 同上 |
| `"mible-login-info"`, `"mible-setup-info"` | 同上（MiBeacon 標準特徵值名稱） |
| `"CONFIRMATION EQUAL"` / `"CONFIRMATION NOT EQUAL"` | 同上（MiBeacon 比對結果） |
| `"REG_VERIFY_FAIL"` | 同上 |
| `SecretKeySpec` ×4、`Mac;->getInstance` ×2 | 呼叫點統計 |
| `ConfigurationElliptic(beaconKey, deviceToken, deviceInfo, ssid)` | `crypto/elliptic/ConfigurationElliptic.java` |

**`ConfigurationElliptic` 需要 `beaconKey` / `deviceToken` / `deviceInfo`**，
由 `services/RequestEllipticKeysReceiver.java` 與 `services/MajsiHomeReceiver.java` 取得。

### 3.3 `XiaomiCrypto` 完整演算法

| 階段 | 內容 | 證據 |
|---|---|---|
| 金鑰交換 | P-256 ECDH（`secp256r1`） | `fm2.java:76-83` |
| KDF | HKDF-SHA256（SpongyCastle HKDF + `HMac(SHA256Digest)`） | `bl0.java:46-64` |
| 對稱加密 | **AES-CCM，32-bit tag、12-byte nonce**；命令**無 AAD**，setup blob 用 AAD `"devID"` | `bl0.java:27-42` |
| 登入 | `HKDF(ikm=deviceToken, salt=appNonce‖scooterBlob, info="mible-login-info")` → `[0:16]`=rx key、`[16:32]`=tx key、`[32:36]`=rx nonce 前綴、`[36:40]`=tx nonce 前綴 | `fm2.java:235-240` |
| 登入 MIC | `HMAC-SHA256(appNonce‖scooterBlob)` | `fm2.java:246-268` |
| setup | `HKDF(ikm=ECDH shared, salt=16×00, info="mible-setup-info")` → `[0:12]`=deviceToken、`[12:28]`=beaconKey、`[28:44]`=AES-CCM key；deviceInfo = `0x00‖"blt.4.159"+10 rand`；nonce `101112131415161718191a1b` | `fm2.java:1463-1521` |
| 命令 nonce | `txPrefix(4)‖00000000‖counter(4 **LE**)`；rx 只用 wire 上 2 bytes counter | `pe4.java:1262-1274`、`ScooterFragment.java:985` |

### 3.4 金鑰從哪裡來 —— **不是廠商雲端**

APK 內**完全沒有 HTTP client 走小米雲端**（沒有任何 `.mi.com` 字串）。兩條取得途徑：

1. **App 間廣播**：`MAJSI_HOME_RECEIVER` / `arg:majsi_data`，與
   `adriandp.m365dashboard`、`com.m365downgrade`、`sh.cfw.utility`、
   `dev.sh.cfw.utility` 這些 ScooterHacking 生態 App 互通
   （`RequestEllipticKeysReceiver.java:21`、`MajsiHomeReceiver.java:20-26`）；
   以 **SSID = BLE MAC** 為 key 存在 `SharedPreferences("app_prefs")`
   （`ScooterFragment.java:1329-1353`）。
2. **裝置上自行推導** ECDH + HKDF，但受一個驗證步驟閘控，失敗即 `REG_VERIFY_FAIL`
   並刪除金鑰（`fm2.java:1605`）。

→ **加密本身完全可重現；但金鑰材料無法只從 Scootbatt 取得。**

---

## 4. 對整合（M365-Rokid-HUD）的直接影響

| 目標車型 | 框頭 | 是否加密 | 能否直接整合 |
|---|---|---|---|
| Ninebot G2 / G30 / F / E / D / GT / P / X160（`isXiaomi=false`，且廣告 byte[1]≠2） | `5A A5`→明文 `3E` | 否 | ✅ 明文 `3E dir act pos payload` 直接讀寫 |
| Xiaomi M365 / Mi Pro / 1S / Pro 2 / Essential / Mi 3（**廣告 byte[1]==2** 時） | `5A A5` | 是（`NinebotCrypto`） | ⚠️ 需實作 §2.3（金鑰、三命令握手、counter/MIC）；金鑰材料可由廣播名稱推得，**可行** |
| 曝露 `0000fe95` 服務的裝置 | `55 AB` | 是（`XiaomiCrypto`／mible） | ⚠️ 演算法公開（§3.3）但需 `deviceToken`/`beaconKey`，只能靠 App 間廣播或裝置端推導 |

**這正好對應你的專案**：`repo/doc/MODEL_SUPPORT.md` 與 `PROTOCOL_FAMILIES.md`
已把 M365 舊世代標為「需 `NinebotCrypto`」、G2 標為「新世代」。Scootbatt 的這張表
提供**權威的判定依據**，可直接補進你的文件 —— 但要注意判定用的是
**廣告 byte[1]==2**，不是型號 byte。

**`isNewNinebotGeneration` 的真相**（`Scooter.java:170-172`）：純粹是
`model ∈ {g2, g65, f2}`，而且只影響兩件事：
(i) 隱藏 "Locked" 設定 chip（`vo3.java:243/268`）、
(ii) 抑制鎖定/解鎖通知按鈕（`BleForegroundService.java:364` → `:297`，欄位 `f3922q`）。

> **APK 內不存在新世代專屬的加密、金鑰排程、封包格式或配對步驟。**
> 若 G2 級裝置實際上需要另一套認證流程，Scootbatt 並沒有實作它。

---

## 5. 未驗證 / 待辦

1. **Ninebot 加密實作類別未定位** —— 只知道握手在 `ScooterFragment`，金鑰衍生函式尚未找到。
2. **握手是否影響讀取**：目前證據顯示讀取不需要握手（讀取命令不經過 `ScooterFragment`
   的握手路徑），但**尚未確認車子在未配對狀態下是否回應讀取**。
3. **型號 byte 的完整清單不完整**：表中 `f`/`gt1`/`gt2`/`d18` 等的 byte 值未逐一抽取。
4. **`FF 4E 42` 的來源**：確認是 BLE 廣告資料（manufacturer data）還是 scan response，
   尚未逐 byte 對照 `BluetoothScanFragment` 的取用點。
5. **`C1142ow.java:37-58` 的 `if (i >= 0) return;` 使後續程式看似死碼** ——
   可能是 jadx 反編譯失真，也可能是真 bug；需以 smali 複核。
6. **`isNewNinebotGeneration` 的判定依據**未分析（`Scooter.isNewNinebotGeneration()`
   目前只看到比對字串 `"g2"`, `"g65"`, `"f2"`）。
