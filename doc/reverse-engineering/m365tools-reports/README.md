# m365 Tools 逆向 — 總索引與對本專案的結論

> 目標：`app.peretti.m365tools` **m365 Tools 1.8.0** (versionCode 180)
> 來源 APK：APKPure，sha256 `4451a47c86339b8f88d77bdad7aef77e1bc4f11b7c728a5803f2b1d342a16872`
> 方法：jadx 1.5.1 + apktool smali 交叉驗證；字串表以 clean-room 重實作的解密器還原
> （該工具留在分析工作區，未進版控 — 本目錄只收結論）
> **所有結論均為靜態分析，尚未經任何實車驗證。** 唯一可用的硬體是一台 Xiaomi M365。

## 報告導覽

| 檔案 | 內容 | 狀態 |
|---|---|---|
| `01-scan-and-identification.md` | **連線前識別**：`0x424E` manufacturer data 的 `mfr[0]`/`mfr[1]`、掃描設定、fe95 只是活性計數 | ✅ 已交叉驗證 |
| `02-gatt-selection.md` | UUID 全表、**跨 service 的 characteristic 找法**、notify-only、MTU 從不協商 | ✅ 已交叉驗證 |
| `03-handshake-and-auth.md` | **完整握手序列**、四種協定家族、`AuthToken = SHA-256(MAC)`、mible ECDH、RC4 | ✅ 已交叉驗證 |
| `04-register-maps.md` | 各緩衝區逐欄位 offset、單位、模型差異 | ✅ 已交叉驗證 |
| `05-write-commands.md` | 寫入命令清單、**checksum 演算法**、三種 framing、模型能力閘門、風險 | ✅ 已交叉驗證 |
| `06-connection-state-machine.md` | 狀態、重連、逾時表、輪詢幫浦、錯誤分類 | ✅ 已交叉驗證 |
| `07-model-component-addressing.md` | **每個車種的送/收位址**（`mID`/`mReceiveID`） | ✅ 本專案自行驗證 |
| `08-xiaomi-family-uniformity.md` | 六個 Xiaomi 車種在參考實作中是同一套協定 | ✅ 本專案自行驗證 |
| `09-device-table.md` | 29 個車種的元件位址／指令集／欄位表（機器抽取） | ✅ 本專案自行驗證 |

---

## 1. 三句最重要的話

1. **協定是在連線前就決定的，不是連線後探測的。** 車種碼與協定版本來自廣告中
   company id `0x424E` 的 manufacturer data：`mfr[0]` = 車種碼、`mfr[1]` = 協定版本
   （`hc.smali:341-361`、`ic.smali:184-210`）。這推翻了本專案「掃描無法決定協定」的前提。
2. **同一個 characteristic 不一定掛在預期的 service 底下。** 參考實作**從不**用
   `getService(UUID)`；它只用 characteristic UUID 找，讓 BLE 層跨所有 service 列舉
   （`mb0.smali:42-92`）。它也從不把 `0000fe95` 當 service 用 —— 只讀它的廣告 service-data。
3. **認證是可重現的。** `AuthToken = SHA-256(<BLE MAC 字串>)[0..16]`（`po.smali:41-93`），
   沒有任何伺服器或配對祕密參與，因此第三方實作能自行算出並完成握手。

## 2. 與本專案直接相關的落差

| # | 參考實作 | 本專案現況 | 影響 |
|---|---|---|---|
| 1 | characteristic 跨 service 找 | `BleManager` 以 `getService(AUTH_SERVICE)` 取 `00000010`/`00000019` | 若裝置把它們掛在別的 service 底下，會**靜默**失敗。已於本次修正 |
| 2 | 要求 `PROPERTY_NOTIFY` | `GattProfileDiscovery` 接受 NOTIFY 或 INDICATE，但 `BleManager` 一律寫入 notify 值 | 選到 indicate-only 通道時會用錯的 CCCD 值 |
| 3 | 每個等待都有上限（命令 230 ms、連線 15 s watchdog） | `BleManager.connect()`／`write()`／`enableNotifications()` **沒有逾時** | **最高優先**：GATT cache 失效時 `onServicesDiscovered` 不來，coroutine 永久懸置，之後 busy-guard 拒絕所有重試 |
| 4 | GATT 133 視為可恢復 | 任何非零 status 都 `gatt.close()` | 133 是 M365 最常見的連線失敗 |
| 5 | 遙測為**輪詢**，300 ms 排程 + 10 ms 幫浦，一次一個命令 | 已有序列化 | 概念一致 |
| 6 | MTU 從不協商，固定 20 byte | 已協商並用 `MtuFragmenter` | **本專案較好**，勿改 |
| 7 | 每個 ATT chunk 不重試 | 已有 `WriteRetryPolicy` 退避 | **本專案較好**，勿改 |

## 3. 兩份參考實作互相矛盾之處（勿單方面改動）

- **`0x7D` 的寫入位元組序**：Scootbatt 用大端（本專案跟隨），m365 Tools 用小端
  （`ByteBuffer.allocate(2).order(LITTLE_ENDIAN)`）。兩者讀取都是小端。
  在 `ScooterSettingsWriter.statusWordWrite` 留有對照表，需以硬體裁決。
- **`AuthToken` 是同名不同物**：參考實作的 16-byte `AuthToken`（SHA-256(MAC)）**不是**
  本專案 `mi_crypto.rs` 的 12-byte HKDF token。不可合併。

## 4. 未驗證（最重要的一節）

1. **沒有任何一項經過實車驗證。** 沒有滑板車、沒有手機、沒有藍牙擷取。
2. `0x424E` 廣告 payload 在真車上的形狀從未被捕捉過 —— `mfr[0]`/`mfr[1]` 的語意
   是「讀到用法、推論名稱」。
3. `protocolVersion` 0–5 的**意義**未知；Firebase Remote Config 旗標由伺服器控制，
   分支表描述的是程式路徑，不是今日的實際上線行為。
4. 各項逾時都是常數，不是量測值。M365 是否需要 3 秒 keep-alive 未知。
5. `zm` 的 ordinal 6/8/10/13–23/27/28 是由宣告順序推得，非直接讀出。
6. 密碼學參數全部為靜態讀取；CCM tag 參數 `0x20` 的解讀（位元 vs 位元組）未定。
7. 只有 M365 可用，因此**所有 Ninebot 相關結論都是文件等級**。
