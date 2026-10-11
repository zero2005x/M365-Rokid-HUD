# UI / Gateway V2 integration — 2026-10-12

分支：`fix/reconnect-after-session-retired`。本次先保存 10/11 的斷線修復，再整合車輛 UI、每車型號與 Wi-Fi V2。沒有改寫既有 commit；沒有修改 Xiaomi 交易隔離、nonce、命令 gate 或 B0 重試政策。核心既有 GatewayNegotiation、GatewayTransportFraming、VehicleProfileCodec 行為保持原樣。

## 實作與限制

- 車輛頁提供目前車輛、已儲存車輛、搜尋附近裝置按鈕。頁面進入不啟動掃描；Connecting／Handshaking／Ready／自動重連期間禁止掃描。切頁與開始連線會停止掃描。Ready transition 透過 AppShell callback 切到騎乘；Ready 時仍可返回車輛頁。
- ModelOverrideDialog 綁定明確 address。舊全域值只作待遷移資料，使用者選擇已儲存車輛或捨棄後清除，不自動套到下一台車。連線中修改同步更新 identification StateFlow 與 logger。騎乘與詳情收集同一 flow。
- 騎乘標題顯示 profile 名稱或型號、連線狀態、B0 資料接收時間；已可讀車型均為滑板車，顯示滑板車圖示，未知型號使用一般車輛圖示。眼鏡控制集中眼鏡頁。Gateway 開關讀 GatewayUiState，服務啟停後 refresh。UI flow 改用 collectAsStateWithLifecycle。
- 舊 LightControlRow／LockControlRow 已刪除。設定中的車輛區說明舊控制不可用；經明確同意的實驗性設定仍可能送出已稽核命令，所以不宣稱本版本完全不發命令。
- 掃描排序由核心 NearbyListOrder 提供：已儲存在上，依首次發現序號；RSSI 不參與排序。
- Wi-Fi 每 socket 保存協商策略，INCOMPATIBLE 不送 V1；斷線時移除相容狀態。新／新採 V2，舊／新和新／舊維持 V1。BLE 仍只送原 20-byte V1。眼鏡只回報 heartbeat、電量與 hello，不發車輛命令。
- V2 Wi-Fi payload 在未改動的核心 PEVG 前加 `sessionGeneration i64 BE`，以手機車輛 session epoch + sequence 去重。不是眼鏡 reconnect generation。TCP 外層長度包含 type、generation 與 PEVG。
- MotorInfoParser 保存實際接收／解碼時間及核心 Reading，Gateway 使用原 observedAt；generatedAt 為送出時間。眼鏡使用 generatedAt 減 observedAt 判斷欄位年齡，另用本地 elapsed time 判斷 socket 靜默。非 VALID 欄位顯示 —，不保留上一筆數值。
- B0 的速度、SOC、TEMP_FRAME、TOTAL_DISTANCE_M，以及實際收到的 0x3A trip distance/time 帶原欄位時間與證據。沒有可追溯觀測的 primitive defaults、舊 range 及其他欄位保持 NOT_PROVIDED。B0 更新不把舊 trip 的時間刷新。
- 沒有新增 Begode 或其他車種的 Ready session；沒有提高任何車種的實車支援等級。

## 單元測試與建置

新增測試：VehicleProfileStore 的明確遷移／捨棄、持久化與每車隔離、連線中型號即時更新 flow/logger；GatewayTypedTelemetry 的觀測時間／缺值；WifiGatewayServer 使用的 WifiPeerPolicy 四種新舊組合與 INCOMPATIBLE／非法 hello；眼鏡 TypedTelemetryReceiver.applyTyped 的 STALE 清值、重複／較舊 sequence、手機 generation 切換；NearbyListOrder 的 RSSI 更新順序。

WSL `kali-linux`，Linux staging：`/home/kali/build/M365-Rokid-HUD-linkfix`；以 `scripts/sync-wsl.py` 同步來源，保留原 Linux SDK／簽章設定。JDK 21、`/home/kali/android-sdk`，不使用 skipRustBuild。執行使用者要求的完整命令：

```sh
./gradlew :pev-protocol-core:test :app:testDebugUnitTest :glass-hud:testDebugUnitTest :app:assembleRelease :glass-hud:assembleRelease
```

交付版本完整命令結果：**BUILD SUCCESSFUL in 6m 46s**，156 actionable tasks（31 executed、4 from cache、121 up-to-date）。兩個 Release assemble 成功；Rust JNI 驗證任務在正常建置鏈中，未使用 skipRustBuild。逐檔比對 Linux staging 與工作區的 Kotlin／Gradle／resource XML，內容一致。

| 模組 | 測試數 | 失敗 | 錯誤 | 跳過 |
|---|---:|---:|---:|---:|
| pev-protocol-core | 196 | 0 | 0 | 0 |
| app | 439 | 0 | 0 | 0 |
| glass-hud | 20 | 0 | 0 | 0 |
| 合計 | 655 | 0 | 0 | 0 |

測試新增 12 項（手機 10、眼鏡 2）；核心仍為 196 項。第一輪編譯失敗的 import／型別可見性已修正，收尾程式碼後再次執行了全部五項工作，以上為最後交付版本結果。

結果與原始測試 XML 保存在忽略版控的 `build/ui-v2-integration-20261012/`；完整 log 為 `ui-v2-integration-20261012-delivery.log`，機器可讀摘要為 `summary.json`。Release APK 已收回工作區：

- `app/build/outputs/apk/release/app-release.apk`；SHA-256 `ffcf195b15bee8b10ea2db5eee162e0cbce489a8273e29a467e7c2edecb20907`。
- `glass-hud/build/outputs/apk/release/glass-hud-release.apk`；SHA-256 `8fa3735bfcff179580de4ec6771907f9c739e4978c009cac896b90c372a7f004`。


## 實機驗收

**未實機驗證。** Windows `adb devices -l` 僅列出 Rokid `1901092544022855`；Redmi Note 11 Pro+ `eeaas88ts4kn6l8t` 未連接。WSL ADB 未列出裝置。交付前再次查詢 Windows ADB 時已沒有任何裝置。本輪沒有安裝 APK 或操作車輛，不能用單元測試或 10/11 結果推定本輪實機通過。

| 必要驗收 | 本輪結果 | 原因 |
|---|---|---|
| 連線後自動跳騎乘，連線中車輛頁 logcat 無 startScan | 未實機驗證 | 缺少 Redmi 與實車連線 |
| 頁籤切換不斷線 | 未實機驗證 | 缺少 Redmi 與實車連線 |
| Wi-Fi negotiated_v2，眼鏡正常顯示 | 未實機驗證 | 只有眼鏡，沒有手機 Gateway |
| 舊版眼鏡 APK 仍顯示 | 未實機驗證 | 未進行手機與舊眼鏡版本配對；僅單元相容測試 |
| 靜止五分鐘無 SESSION_RETIRED | 未實機驗證 | 缺少 Redmi 與實車連線 |

仍需手機／眼鏡／車輛完成上述驗收；320dp、大字、旋轉、TalkBack 與 frame-time 實機檢查亦尚未執行。沒有宣稱 FPS 或實際騎乘改善。

## Commit 與變更檔案

已建立的 commits：

- `b594d1b` — 10/11 已實機驗證的斷線修復與診斷文件，單獨提交；包含 bin ignore 清理。
- `f180d40` — 修正拆分提交過程的 CRCRLF 換行差異，純格式，不改行為、不改寫 commit。
- `a35ac6a` — 保存上一輪已驗證的核心協商／framing／profile 新增內容，196 項測試維持通過。
- 本文件所在的 `fix: integrate vehicle profiles and fresh Wi-Fi V2 HUD` — UI、手機與眼鏡整合、單元測試、schema 與支援文件。精確 hash 在本輪交付訊息列出。

完整變更檔案如下（相對 repository 根目錄）：
開工前已存在的 `phone-screen.png`、`glasses-screen.png` 保留在工作區，不納入提交。IDE 輸出 `pev-protocol-core/bin/` 已刪除並加入 .gitignore。

- `.gitignore`
- `SUPPORT_MATRIX.md`
- `app/build.gradle.kts`
- `app/src/main/java/com/m365bleapp/ble/BleManager.kt`
- `app/src/main/java/com/m365bleapp/gateway/GatewayService.kt`
- `app/src/main/java/com/m365bleapp/gateway/GatewayTypedTelemetry.kt`
- `app/src/main/java/com/m365bleapp/gateway/GatewayUiState.kt`
- `app/src/main/java/com/m365bleapp/gateway/M365GattServer.kt`
- `app/src/main/java/com/m365bleapp/gateway/wifi/WifiGatewayServer.kt`
- `app/src/main/java/com/m365bleapp/gateway/wifi/WifiGatewayService.kt`
- `app/src/main/java/com/m365bleapp/gateway/wifi/WifiPeerPolicy.kt`
- `app/src/main/java/com/m365bleapp/protocol/ModelOverrideStore.kt`
- `app/src/main/java/com/m365bleapp/protocol/VehicleIdentificationState.kt`
- `app/src/main/java/com/m365bleapp/protocol/VehicleProfileStore.kt`
- `app/src/main/java/com/m365bleapp/repository/MotorInfoParser.kt`
- `app/src/main/java/com/m365bleapp/repository/ScooterRepository.kt`
- `app/src/main/java/com/m365bleapp/repository/XiaomiTransactionAuthority.kt`
- `app/src/main/java/com/m365bleapp/ui/AppShell.kt`
- `app/src/main/java/com/m365bleapp/ui/ExperimentalVehicleSettings.kt`
- `app/src/main/java/com/m365bleapp/ui/GatewayControl.kt`
- `app/src/main/java/com/m365bleapp/ui/GlassesScreen.kt`
- `app/src/main/java/com/m365bleapp/ui/HomeScreen.kt`
- `app/src/main/java/com/m365bleapp/ui/HudDisplayScreen.kt`
- `app/src/main/java/com/m365bleapp/ui/NavGraph.kt`
- `app/src/main/java/com/m365bleapp/ui/ScanScreen.kt`
- `app/src/main/java/com/m365bleapp/ui/ScooterInfoScreen.kt`
- `app/src/main/java/com/m365bleapp/ui/SettingsScreen.kt`
- `app/src/main/res/values-zh-rCN/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/m365bleapp/gateway/GatewayTypedTelemetryTest.kt`
- `app/src/test/java/com/m365bleapp/gateway/WifiPeerPolicyTest.kt`
- `app/src/test/java/com/m365bleapp/protocol/NearbyListOrderTest.kt`
- `app/src/test/java/com/m365bleapp/protocol/VehicleProfileStoreTest.kt`
- `app/src/test/java/com/m365bleapp/repository/XiaomiTransactionAuthorityTest.kt`
- `doc/CONNECTION_DIAGNOSIS_20261011.md`
- `doc/MULTIVEHICLE_UI_ASSESSMENT_20261011.md`
- `doc/UI_V2_INTEGRATION_20261012.md`
- `glass-hud/src/main/java/com/m365hud/glass/DataModels.kt`
- `glass-hud/src/main/java/com/m365hud/glass/HudScreen.kt`
- `glass-hud/src/main/java/com/m365hud/glass/SpeedText.kt`
- `glass-hud/src/main/java/com/m365hud/glass/wifi/TypedTelemetryReceiver.kt`
- `glass-hud/src/main/java/com/m365hud/glass/wifi/WifiGatewayClient.kt`
- `glass-hud/src/main/java/com/m365hud/glass/wifi/WifiHudProtocol.kt`
- `glass-hud/src/test/java/com/m365hud/glass/wifi/TypedTelemetryReceiverTest.kt`
- `pev-protocol-core/GATEWAY_SCHEMA.md`
- `pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/gateway/GatewayCodec.kt`
- `pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/gateway/GatewayNegotiation.kt`
- `pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/gateway/GatewayTransportFraming.kt`
- `pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/gateway/GatewayV2Frame.kt`
- `pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/vehicle/VehicleProfileCodec.kt`
- `pev-protocol-core/src/test/kotlin/io/github/zero2005x/pev/core/gateway/GatewayNegotiationTest.kt`
- `pev-protocol-core/src/test/kotlin/io/github/zero2005x/pev/core/vehicle/VehicleProfileCodecTest.kt`
