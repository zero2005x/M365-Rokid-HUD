# Log Collection Guide | 日誌蒐集指南

A guide for collecting diagnostic logs from both glasses and phone apps.
本指南說明如何從眼鏡端和手機端蒐集診斷日誌。

---

## Connection Stability Improvements (2026-01-11) | 連線穩定性改進

### New Features | 新增功能

| #   | Feature                      | 功能         | Description                                                    | 說明                                        |
| --- | ---------------------------- | ------------ | -------------------------------------------------------------- | ------------------------------------------- |
| 1   | Foreground Service           | 前景服務     | Prevents Android from killing the glasses app                  | 防止 Android 系統殺掉眼鏡 App               |
| 2   | Wake Lock                    | 喚醒鎖       | Keeps CPU running to maintain BLE connection                   | 保持 CPU 運作以維持 BLE 連線                |
| 3   | Auto-Reconnect               | 自動重連     | Automatically scans and reconnects after disconnection         | 斷線後自動重新掃描連接                      |
| 4   | Diagnostic Logs              | 詳細診斷日誌 | Records disconnect reasons for troubleshooting                 | 記錄斷線原因方便排查                        |
| 5   | Connection Quality Indicator | 連線品質指標 | Shows signal strength icon and stale data warning on HUD       | 在眼鏡 HUD 上顯示訊號強度圖示和資料過時警告 |
| 6   | Session Statistics           | 連線會話統計 | Records packet count, RSSI stats, disconnect count per session | 記錄每次會話的封包數、RSSI 統計、斷線次數等 |
| 7   | Battery Optimization Prompt  | 電池優化提示 | Guides user to disable battery optimization on phone           | 引導用戶關閉電池優化以確保穩定連線          |

### Log Tags | 日誌標籤

**Glasses App (glass-hud) | 眼鏡端:**

- `BleClient` - BLE connection details (with session stats) | BLE 連線詳情（含會話統計）
- `BleConnectionService` - Foreground service status | 前景服務狀態
- `MainActivity` - Activity lifecycle | Activity 生命週期
- `HudScreen` - UI rendering | UI 渲染
- `GattProfile` - GATT configuration | GATT 設定

**Phone App (app) | 手機端:**

- `ScooterRepository` - Scooter BLE communication | 滑板車 BLE 通訊
- `M365GattServer` - Gateway GATT server | 閘道 GATT 伺服器
- `GatewayService` - Gateway foreground service | 閘道前景服務
- `BleManager` - BLE connection management | BLE 連線管理

---

## Method 1: USB Debugging (Glasses) | 方法 1: USB 連接眼鏡

### Steps | 步驟

1. **Enable Developer Mode on Glasses | 啟用眼鏡開發者模式**

   - Go to Settings → About Device | 在眼鏡設定中找到「關於裝置」
   - Tap "Build Number" 7 times to enable Developer Options | 連續點擊「版本號碼」7 次啟用開發者選項
   - Return to Settings → Developer Options | 回到設定，進入「開發者選項」
   - Enable "USB Debugging" | 啟用「USB 偵錯」

2. **Connect Glasses to Computer | 連接眼鏡到電腦**

   - Use USB-C cable to connect glasses to computer | 使用 USB-C 線連接眼鏡到電腦
   - Authorize USB debugging on glasses | 在眼鏡上授權 USB 偵錯

3. **Collect Logs | 蒐集日誌**

   ```powershell
   # Verify glasses connected | 確認眼鏡已連接
   adb devices

   # Recommended: Filter BleClient logs | 推薦：篩選 BleClient 相關日誌
   adb logcat -s BleClient:* BleConnectionService:* MainActivity:* *:E

   # Alternative: Collect all glass-hud app logs | 或蒐集所有 glass-hud app 日誌
   adb logcat | Select-String "m365hud|BleClient|BleConnectionService" | Out-File -FilePath "glasses_log.txt"

   # Collect specific time range | 蒐集特定時間範圍
   adb logcat -d -t "01-11 12:50:00.000" | Out-File -FilePath "glasses_log.txt"
   ```

---

## Method 2: USB Debugging (Phone) | 方法 2: USB 連接手機

### Steps | 步驟

1. **Enable Developer Mode on Phone | 啟用手機開發者模式**

   - Go to Settings → About Phone | 在手機設定中找到「關於手機」
   - Tap "Build Number" 7 times | 連續點擊「版本號碼」7 次
   - Return to Settings → Developer Options | 回到設定，進入「開發者選項」
   - Enable "USB Debugging" | 啟用「USB 偵錯」

2. **Connect Phone to Computer | 連接手機到電腦**

   - Use USB cable to connect phone to computer | 使用 USB 線連接手機到電腦
   - Authorize USB debugging on phone | 在手機上授權 USB 偵錯

3. **Collect Phone Logs | 蒐集手機日誌**

   ```powershell
   # Verify phone connected | 確認手機已連接
   adb devices

   # Collect scooter BLE and gateway logs | 蒐集滑板車 BLE 和閘道日誌
   adb logcat -s ScooterRepository:* M365GattServer:* GatewayService:* BleManager:* *:E

   # Alternative: Collect all M365 app logs | 或蒐集所有 M365 app 日誌
   adb logcat | Select-String "m365bleapp|ScooterRepository|GattServer|Gateway" | Out-File -FilePath "phone_log.txt"

   # Collect specific time range | 蒐集特定時間範圍
   adb logcat -d -t "01-11 12:50:00.000" | Out-File -FilePath "phone_log.txt"
   ```

---

## Method 3: In-App Logging (Phone) | 方法 3: App 內建日誌（手機）

The phone app has built-in logging to CSV files.
手機 App 內建 CSV 日誌記錄功能。

### Enable Logging | 啟用日誌

1. Open M365 App → Dashboard → View Logs | 開啟 M365 App → 儀表板 → 查看日誌
2. Enable "Logging" switch | 開啟「日誌記錄」開關
3. Connect to scooter and use normally | 連接滑板車並正常使用
4. Export logs via "Export All" button | 透過「匯出全部」按鈕匯出日誌

### Log File Types | 日誌檔案類型

| Type              | 類型     | File Pattern           | 檔案格式                             | Content                   | 內容 |
| ----------------- | -------- | ---------------------- | ------------------------------------ | ------------------------- | ---- |
| Telemetry         | 遙測資料 | `m365_telemetry_*.csv` | Speed, battery, temperature, mileage | 速度、電量、溫度、里程    |
| BLE Communication | BLE 通訊 | `m365_ble_*.csv`       | Raw BLE packets (hex)                | 原始 BLE 封包（十六進位） |

---

## Important Log Messages | 需要關注的日誌訊息

### Glasses Logs | 眼鏡端日誌

```
CONNECTION HEALTH: Telemetry stale! No update for XXXms
連線健康：遙測資料過時！已 XXX 毫秒未更新

CONNECTION HEALTH: Initiating auto-reconnect...
連線健康：正在啟動自動重連...

AUTO-RECONNECT: Starting scan...
自動重連：開始掃描...

DISCONNECT REASON: Connection timeout - phone may be out of range
斷線原因：連線逾時 - 手機可能超出範圍

DISCONNECT REASON: Remote device terminated connection
斷線原因：遠端裝置終止連線

SIGNAL: Weak signal detected: -XX dBm
訊號：偵測到弱訊號：-XX dBm

SIGNAL: Poor signal detected: -XX dBm
訊號：偵測到差訊號：-XX dBm

SESSION SUMMARY: Duration=XXXs, Packets=XXX, Stale events=X, Reconnects=X
會話摘要：持續時間=XXX秒, 封包數=XXX, 過時事件=X, 重連次數=X

SESSION SUMMARY: RSSI - avg=-XXdBm, min=-XXdBm, max=-XXdBm
會話摘要：RSSI - 平均=-XXdBm, 最低=-XXdBm, 最高=-XXdBm
```

### Phone Logs | 手機端日誌

```
[ScooterRepository] Starting telemetry polling...
[ScooterRepository] 開始遙測輪詢...

[ScooterRepository] Telemetry update: speed=XX, battery=XX%
[ScooterRepository] 遙測更新：速度=XX, 電量=XX%

[M365GattServer] Glasses connected: XX:XX:XX:XX:XX:XX
[M365GattServer] 眼鏡已連接：XX:XX:XX:XX:XX:XX

[M365GattServer] Glasses disconnected
[M365GattServer] 眼鏡已斷線

[GatewayService] Gateway started, broadcasting telemetry
[GatewayService] 閘道已啟動，正在廣播遙測資料

[BleManager] Scooter connection lost, attempting reconnect...
[BleManager] 滑板車連線中斷，嘗試重連...
```

---

## Log Analysis Focus | 日誌分析重點

### 1. LATENCY STATS | 延遲統計

Updates per second | 每秒接收更新數

| Status   | 狀態 | Updates/sec | 更新/秒 |
| -------- | ---- | ----------- | ------- |
| Normal   | 正常 | 4-6         | 4-6     |
| Abnormal | 異常 | < 2         | < 2     |

### 2. SESSION SUMMARY | 會話統計

Output when disconnected | 斷線時輸出

| Field        | 欄位     | Description                               | 說明                         |
| ------------ | -------- | ----------------------------------------- | ---------------------------- |
| Duration     | 持續時間 | Connection duration in seconds            | 連線持續時間（秒）           |
| Packets      | 封包數   | Total telemetry packets received          | 收到的遙測封包總數           |
| Stale events | 過時事件 | Times data became stale (lower is better) | 資料過時事件次數（越少越好） |
| Reconnects   | 重連次數 | Number of reconnection attempts           | 重連嘗試次數                 |
| RSSI         | 訊號強度 | Average/Min/Max signal strength           | 平均/最低/最高訊號強度       |

### 3. Connection State Changes | 連線狀態變化

Frequent `Disconnected → Scanning → Connected` cycles indicate unstable connection.
頻繁的 `Disconnected → Scanning → Connected` 循環表示連線不穩定。

### 4. RSSI (Signal Strength) | RSSI（訊號強度）

| Quality | 品質 | RSSI Range     | RSSI 範圍      |
| ------- | ---- | -------------- | -------------- |
| Good    | 良好 | >= -80 dBm     | >= -80 dBm     |
| Weak    | 弱   | -80 to -90 dBm | -80 至 -90 dBm |
| Poor    | 差   | < -90 dBm      | < -90 dBm      |

---

## HUD Connection Quality Indicator | HUD 連線品質指標

The glasses HUD shows connection quality indicators:
眼鏡 HUD 上會顯示連線品質指標：

| Icon | 圖示 | Meaning                  | 含義               | Description                     | 說明                 |
| ---- | ---- | ------------------------ | ------------------ | ------------------------------- | -------------------- |
| 📶   | 📶   | Normal Signal            | 訊號正常           | RSSI >= -80 dBm                 |
| 📶⚠  | 📶⚠  | Signal OK but Stale Data | 訊號正常但資料過時 | No update for 2+ seconds        | 超過 2 秒未收到更新  |
| 📵   | 📵   | Not Connected            | 未連線             | Not connected to phone gateway  | 尚未連接到手機閘道   |
| 📵⚠  | 📵⚠  | Disconnected & Stale     | 未連線且資料過時   | Disconnected with no fresh data | 連線中斷且無最新資料 |

---

## Troubleshooting | 常見問題排查

| Symptom               | 症狀       | Possible Cause             | 可能原因            | Solution                                  | 解決方案                          |
| --------------------- | ---------- | -------------------------- | ------------------- | ----------------------------------------- | --------------------------------- |
| Frequent reconnects   | 頻繁重連   | Watchdog too sensitive     | Watchdog 觸發太敏感 | Increase `STALE_CHECKS_BEFORE_RECONNECT`  |
| Stuttering display    | 畫面卡頓   | Weak signal / interference | 訊號弱/干擾         | Reduce distance between phone and glasses | 減少手機與眼鏡距離                |
| No data updates       | 資料不更新 | Notifications not enabled  | Notification 未啟用 | Check CCCD write logs                     | 檢查 CCCD 寫入日誌                |
| App killed            | App 被殺掉 | Battery optimization       | 電池優化            | Verify foreground service is running      | 確認前景服務運作中                |
| HUD shows ⚠           | HUD 顯示 ⚠ | Stale data                 | 資料過時            | Check phone gateway status                | 檢查手機端閘道是否正常運作        |
| Poor connection       | 連線品質差 | Weak signal                | 訊號弱              | Check SESSION SUMMARY RSSI stats          | 查看 SESSION SUMMARY 的 RSSI 統計 |
| Glasses won't connect | 眼鏡連不上 | Gateway not enabled        | 閘道未啟用          | Enable Rokid HUD Gateway on phone         | 在手機上啟用 Rokid HUD 閘道       |
| Scooter disconnects   | 滑板車斷線 | BLE interference           | BLE 干擾            | Move away from WiFi routers               | 遠離 WiFi 路由器                  |

---

## Phone Battery Optimization Settings | 手機端電池優化設定

If glasses disconnect frequently, ensure battery optimization is disabled on the phone:
若眼鏡頻繁斷線，請確認手機端已關閉電池優化：

1. Open M365 App on phone | 開啟手機 M365 App
2. If you see "⚠️ Battery optimization enabled" warning banner, tap it | 若顯示「⚠️ 電池優化已開啟」警告橫幅，點擊它
3. In system settings, select "Don't optimize" or "Disable optimization" | 在系統設定中選擇「不限制」或「關閉優化」
4. Re-enable Rokid HUD Gateway | 重新啟用 Rokid HUD 閘道

---

## Collecting Logs for Bug Reports | 蒐集日誌用於問題報告

When reporting issues, please collect the following:
報告問題時，請蒐集以下資料：

1. **Glasses logs** (via USB or describe behavior) | **眼鏡日誌**（透過 USB 或描述行為）
2. **Phone logs** (via USB or in-app CSV export) | **手機日誌**（透過 USB 或 App 內 CSV 匯出）
3. **Telemetry CSV** (if available) | **遙測 CSV**（如有）
4. **BLE CSV** (if available) | **BLE CSV**（如有）
5. **Steps to reproduce** | **重現步驟**
6. **Device info** (phone model, glasses model, Android version) | **裝置資訊**（手機型號、眼鏡型號、Android 版本）

---

_Document Version: 2.0 | Last Updated: January 2026_
_文件版本：2.0 | 最後更新：2026 年 1 月_
