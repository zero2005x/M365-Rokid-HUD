# v1.4.0 — 2025/2026 Google Play Compliance, API Modernization & AAB Packaging | 應用商店合規性、API 現代化與 AAB 打包

This release updates the project to the latest Android 16 (API 36) specifications, fully satisfies Google Play Store 2025/2026 publishing policies, packages both APK and AAB (Android App Bundle) artifacts, resolves deprecated APIs, and integrates automated CI & SonarQube code quality pipelines.

本次發布全面更新至 Android 16 (API 36) 最新標準，全方位符合 Google Play Store 2025/2026 最新上架政策，同步打包 APK 與 AAB (Android App Bundle) 檔案，修復過時 API，並整合自動化 CI 測試與 SonarQube 代碼品質檢查流水線。

---

## Downloads | 檔案下載

| File 檔案 | Format 格式 | Description | 說明 |
| :--- | :---: | :--- | :--- |
| **M365-Rokid-HUD-phone-v1.4.0.apk** | APK | Phone app — M365 BLE client + Rokid gateway (sideload) | 手機端 — M365 BLE 用戶端 + Rokid 閘道（直接安裝） |
| **M365-Rokid-HUD-glasses-v1.4.0.apk** | APK | Glasses app — Rokid AR HUD display (sideload) | 眼鏡端 — Rokid AR 抬頭顯示器（直接安裝） |
| **M365-Rokid-HUD-phone-v1.4.0.aab** | AAB | Phone app — Google Play publishing bundle | 手機端 — Google Play 商店上架專用 App Bundle |
| **M365-Rokid-HUD-glasses-v1.4.0.aab** | AAB | Glasses app — Google Play publishing bundle | 眼鏡端 — Google Play 商店上架專用 App Bundle |
| **SHA256SUMS.txt** | Checksums | SHA256 hashes for all release artifacts | 所有發布產物之 SHA256 雜湊驗證碼 |

Verify downloads | 下載驗證指令：
```powershell
Get-FileHash .\M365-Rokid-HUD-phone-v1.4.0.apk -Algorithm SHA256
```
```bash
sha256sum -c SHA256SUMS.txt
```

---

## Highlights & Changes | 重點更新與變更

### 1. Google Play Store 2025/2026 Policy Compliance | 符合應用商店最新政策
- **16 KB Memory Page Size Support (強制 16 KB 記憶體分頁對齊)**:
  - Ensured all native shared libraries (`libninebot_ffi.so`) across all 4 architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) strictly comply with 16 KB ELF segment alignment (`p_align >= 0x4000`), passing Google Play 2025/2026 mandatory verification.
  - 確保所有四種架構的 Rust 原生動態庫均符合 16 KB 分頁對齊標準，完全滿足 Google Play 強制要求。
- **Battery Optimization Policy Compliance (移除高風險電池權限)**:
  - Removed restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission from manifests to eliminate Google Play companion app rejection risks.
  - Replaced direct exemption requests with safe system settings navigation (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), allowing users to grant unrestricted battery usage voluntarily.
  - 徹底移除高被拒風險的受限權限，改由安全引導使用者至系統電池設定進行手動排除。
- **Foreground Service & Data Safety (前台服務與資料安全)**:
  - Both foreground services declare `connectedDevice` type and `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission, with runtime checks before activation.
  - Preserved `neverForLocation` flags and capped location permissions to API 30, eliminating redundant location disclosures in modern Data Safety forms.
  - 確保前台服務型態與藍牙權限檢驗完全符合規範，且免除現代 Android 上的非必要位置追蹤申報。

### 2. API Modernization & Platform Features | API 現代化與系統特性
- **Target SDK 36 (Android 16)**:
  - Full compatibility with Android 15 & Android 16 runtime behaviors.
  - 完全適配 Android 15 與 Android 16 平台最新行為規範。
- **Edge-to-Edge Enforced Display (邊緣延伸全螢幕適配)**:
  - Added explicit `enableEdgeToEdge()` call in `MainActivity`, ensuring seamless full-screen layout across all Android versions.
  - 在 `MainActivity` 啟用邊緣到邊緣繪製，配合 `Scaffold` 提供沉浸式版面。
- **Predictive Back Navigation (預測性返回支援)**:
  - Enabled `android:enableOnBackInvokedCallback="true"` in both phone and glasses manifests for modern gesture animations.
  - 雙模組清單皆啟用預測性返回回調，支援系統返回動畫。
- **Deprecated API Fixes (修復過時 API)**:
  - `BluetoothHelper`: Replaced deprecated `Settings.Secure.LOCATION_MODE` with `LocationManager.isLocationEnabled` on API 28+.
  - `LocaleHelper`: Replaced deprecated `Locale(String, String)` constructors with modern `Locale.Builder()`.
  - `LogViewerScreen`: Upgraded deprecated Material 3 `TabRow` to `PrimaryTabRow`.
  - `WifiGatewayClient`: Replaced deprecated `serviceInfo.host` with `serviceInfo.hostAddresses` on API 34+.
  - Dependencies: Upgraded `androidx.core:core-ktx` to `1.17.0` and `androidx.lifecycle:lifecycle-runtime-ktx` to `2.10.0`.

### 3. Release & CI Automation | 發布與持續整合自動化
- **AAB & APK Dual Packaging (支援 AAB 與 APK 雙發布)**:
  - Release workflow now automatically produces, signs, and attaches both `.apk` and `.aab` artifacts to GitHub Releases.
  - 自動化發布流水線全面支援同時建置、簽名並上傳 APK 與 Google Play 專用的 AAB 格式。
- **CI Pipeline with SonarQube (CI 與程式碼品質檢查)**:
  - Added `.github/workflows/ci.yml` running unit tests, debug builds, 16 KB page alignment verification, and SonarQube static code analysis on every push and pull request.
  - 建立正式的 CI 流水線，自動執行單元測試、16 KB 分頁對齊驗證以及 SonarQube 代碼品質掃描。
