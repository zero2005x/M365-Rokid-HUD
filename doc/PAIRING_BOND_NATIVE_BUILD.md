# 配對金鑰新版完整原生建置與驗證

日期：2026-10-07。

## 建置

在忽略版控的 `build/native-toolchain` 安裝 Rust 1.99.0 minimal 與 cargo-ndk 4.1.2，使用本機既有 Android NDK。未修改全域 PATH、Gradle 建置規則或 Rust 原始碼，也未沿用舊 APK 的 JNI。[Rust 官方安裝入口](https://rust-lang.org/tools/install/)。下載的 Android 標準函式庫逐一核對官方 manifest 的 SHA-256。

執行 `:app:assembleDebug`，未使用 `skipRustBuild`；5 分 30 秒建置成功。`copyRustJniLibs` 和 `verifyRustJniLibs` 確認四種 ABI 都存在且符合目前原始碼指紋。

完整測試 APK：`build/native-toolchain/hud-bond-complete-1.5.2.apk`。版本 1.5.2／versionCode 11，屬可偵錯的 debug APK，重新簽章後憑證與手機原有 HUD 相同。SHA-256：`93c34535713884d1b9c67cc95adb6fbf72a573076eb6c61e8014b69b2fe63ad0`。

逐一驗證 APK 中的 `libninebot_ffi.so` 與本次 Rust 輸出逐 byte 相同；armeabi-v7a、arm64-v8a、x86、x86_64 的 ELF LOAD segment alignment 都為 16384。此檢查只針對本專案 JNI，不代表其他 SDK 函式庫也完成同樣審核。

## 測試

- `ninebot-ffi` 的 5 個 Rust registry／handle 測試全部通過。
- `ninebot-ble --no-default-features` 的 148 個 Rust 協定測試全部通過。
- Windows 真實 JNI DLL，Java `-Xcheck:jni` 契約的 56 項檢查全部通過。
- 實體 RG glasses（Android 12、arm64-v8a）透過 Dalvik 載入本次 ARM64 JNI，56 項契約檢查全部通過。僅使用合成向量，不連線到滑板車。
- 實體 Redmi（Android 13、arm64-v8a）同樣通過 56 項 Android JNI 契約檢查。

Android 測試使用既有 Java harness 的暫存副本；只將 Java 17 `HexFormat` helper 換成相容的兩位 hex 解析，56 項檢查及 JNI 方法簽名不變。測試 jar 與 JNI 放於專用 `/data/local/tmp/m365-bond-jni-20261007`，結束後逐檔刪除並移除空目錄。沒有切換或更新眼鏡 APP。

收到 RideFlux 工作明確釋出 ADB 後，以保留資料的 `install -r` 安裝完整 HUD 1.5.2／code 11。啟動後依新程序 PID 的 logcat 確認 JNI 載入與初始化成功；繁中配對頁仍只有原有一筆遮罩尾碼 A1:B2 的金鑰。安裝前後逐項比對 font scale、wm size、wm density 與 language prefs，皆未改變。手機保留新版完整 debug APK 並返回 Home，不再還原缺乏新功能的 1.5.0；眼鏡已安裝 APP 維持原樣。兩部裝置的 JNI 測試暫存檔皆已移除。

## 範圍

此前配對頁排版與多語系測試見 `PAIRING_BOND_UI_REVIEW.md`，RFBOND 雙向交換見 `PAIRING_BOND_DEVICE_TEST.md`。此前缺少 JNI 的 UI APK 已由本次完整 APK 取代；既有 357 個 JVM 測試結果不在本次重複執行。

本次尚未確認實車 5C／5D 接受或拒絕，也不等同眼鏡 HUD 投影或 CXR-M 連線測試。建置警告指出本機 debug 的 Rokid CXR-M credentials 尚未配置；JNI 測試不需要這些 credentials。
