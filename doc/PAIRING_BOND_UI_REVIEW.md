# 配對金鑰頁排版與語言檢討

日期：2026-10-06。

## 發現與修正

原先列表讓名稱、MAC、家族名稱都使用相同正文樣式，文字刪除按鈕占用摘要寬度；匯入預覽的摘要 Column 沒有 weight，長翻譯可能超出可用寬度。口令對話框不可捲動，浮動欄位標籤在窄螢幕／大字體時也容易擁擠。此外，英文與繁中以外的 9 個 locale 各有 38 個配對頁字串仍是英文。

修正集中在 `bond/PairingKeysScreen.kt` 與配對頁字串：

- 列表改為卡片，名稱使用 titleSmall、MAC 與家族使用 bodyMedium，摘要約束寬度、名稱最多兩行。刪除改為標準 IconButton 並提供本地化的動作與遮罩 MAC 無障礙描述。
- 匯出／匯入／手動輸入按鈕各占完整一行；勾選與家族選擇支援點擊整個選項列，控制項垂直置中。
- 預覽摘要使用 weight，取代選項文字可換行。口令、預覽與手動輸入內容均可捲動。
- 實機發現阿拉伯文 2 倍字體的匯入確認與取消文字重疊 29 pixels；對話框改用各自完整一行的直向操作按鈕。加入系統列與 IME padding，並實際驗證鍵盤開啟時單次取消能關閉對話框，避免只憑 UI hierarchy 宣稱可操作。
- 欄位標籤移到輸入框外，可自然換行，保留輸入框本地化無障礙描述。MAC 與密碼欄位停用自動校正，使用 ASCII／密碼鍵盤。
- 標題列高度隨系統 font scale 增加。只調整本頁文字層級，不變更全 App 字型規模或停用字型縮放。
- Arabic 操作布局維持 RTL，MAC 摘要與 MAC 輸入固定 LTR。回應字串使用 LocalResources，讓資源依語言配置追蹤。
- 補齊簡中、日、韓、西、法、義、俄、烏與阿拉伯文 38 個字串，各自保留原始格式參數。

## 驗證方法與範圍

11 個語言資源集合皆通過 XML、重複 key、配對頁 key 完整性與格式參數檢查。非英文 locale 沒有直接複製英文的配對頁字串，Xiaomi 品牌名稱除外。

使用 Android 36、density 440 的不保存變更模擬器，將顯示寬度設為 880 pixels（320 dp）。以合成金鑰、較長英文字母名稱與 UI hierarchy 檢查列表、操作按鈕、手動輸入的翻譯文字、水平 bounds、捲動後可到達性與 RTL 的刪除按鈕方向。未截圖、錄影或關閉 FLAG_SECURE；此檢查不等於對字形細節進行逐像素視覺審核。

重新接上的 Redmi Note 11 Pro+ 5G（Android 13、1080×2400、density 440，約 393 dp）完成 17 組實機列表與手動輸入檢查：11 個語言的標準字體，以及繁中、法文、阿拉伯文各 1.3／2.0 倍字體。資料含 64 字元名稱、Xiaomi 與 Ninebot 兩種合成金鑰；檢查翻譯、水平 bounds、捲動可到達性及 RTL 操作方向。矩陣記錄位於忽略版控的 `build/adb-test/phone-layout-matrix.json`。

最終修正 APK 另通過阿拉伯文 2.0 倍字體的口令匯入、多筆衝突預覽、文字不重疊、FLAG_SECURE、實際取消，以及鍵盤開啟時 ASCII MAC 輸入與單次取消；繁中標準字體再測刪除確認。未完成所有語言的口令／預覽排列組合，也未截圖或進行字形的逐像素視覺審核。之前的雙向交換紀錄見 `PAIRING_BOND_DEVICE_TEST.md`。

測試 APK 以 skipRustBuild 編譯，不含 Ninebot JNI，僅用於 UI 測試。測後刪除本次兩筆合成金鑰及 Download fixture，保留原有金鑰，還原手機 HUD 1.5.0、繁中偏好與 font scale 1.0。眼鏡只確認 ADB 與版本，未安裝或修改 APP；本次未驗證實車 BLE。模擬器使用 `-read-only -no-snapshot-save`，測試不保存回使用者的 AVD。

全專案 lint 仍有三個既有 MissingPermission：`M365GattServer.kt` 兩處與 `ScooterRepository.kt` 一處；確認這些呼叫已存在於 HEAD。配對頁的 LocalContext 資源使用問題已修正，最新 lint 報告中配對頁無 Error。未把此次範圍擴大到 BLE 權限修改。

RideFlux 刪除與多語系交接指令另存於 `RIDEFLUX_BOND_UI_AGENT_PROMPT.md`；使用者已交給「Add bond key deletion」工作。本次只協調 ADB 使用與還原，不修改 RideFlux 程式碼。

2026-10-07 後續已完成含四種 ABI JNI 的完整建置，安裝 HUD 1.5.2 到手機並保留資料與設定；手機及眼鏡各通過 56 項 Android JNI 檢查。上方 1.5.0 還原記錄屬 10 月 6 日的 UI 測試收尾，最新裝置狀態與驗證見 `PAIRING_BOND_NATIVE_BUILD.md`。
