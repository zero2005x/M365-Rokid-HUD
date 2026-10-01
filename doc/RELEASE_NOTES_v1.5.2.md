# v1.5.2 — Per-model scooter detection, Play Console fixes, full translations | 依型號辨識滑板車、Play Console 修正、翻譯補齊

Phone app `com.m365bleapp` versionCode 11, glasses app `com.m365hud.glass`
versionCode 8. Both report versionName 1.5.2.

手機端 `com.m365bleapp` versionCode 11，眼鏡端 `com.m365hud.glass` versionCode 8，
versionName 皆為 1.5.2。

This is the first GitHub Release since v1.4.0 and it includes everything from
1.5.0 and 1.5.1:

- **1.5.0** has no GitHub Release. Its notes are in
  `doc/RELEASE_NOTES_v1.5.0.md`.
- **1.5.1** (versionCode 10) went to Google Play only. The `v1.5.1` tag exists,
  but its release workflow failed before publishing, so there is no GitHub
  Release for it. Its notes are in `doc/RELEASE_NOTES_v1.5.1.md`.
- **1.5.2** adds per-model scooter detection on top of 1.5.1.

這是 v1.4.0 之後的第一個 GitHub Release，內容包含 1.5.0 與 1.5.1 的全部變更：

- **1.5.0** 沒有 GitHub Release，說明見 `doc/RELEASE_NOTES_v1.5.0.md`。
- **1.5.1**（versionCode 10）僅發佈於 Google Play。`v1.5.1` tag 雖然存在，但其發布
  流程在發布前失敗，所以沒有對應的 GitHub Release，說明見 `doc/RELEASE_NOTES_v1.5.1.md`。
- **1.5.2** 在 1.5.1 之上新增依型號辨識滑板車。

---

## ⚠️ Experimental — not verified on a scooter | 實驗性 — 未經實車驗證

The model detection added in this release is covered by unit tests only and has
not been verified on a scooter. Support for Ninebot-family scooters rests on
static reverse engineering, and the register layouts for those families are not
established (see `doc/MODEL_SUPPORT.md`). Controls are capability-gated per
model and demo data is marked `DEMO`. Do not rely on any reading or command as
correct until it is verified on your scooter.

本版新增的型號辨識僅有單元測試涵蓋，尚未在實車上驗證。對 Ninebot 系列滑板車的支援
建立在靜態逆向分析上，這些車系的暫存器配置尚未確立（見 `doc/MODEL_SUPPORT.md`）。
控制項依車型能力顯示，示範資料標記為 `DEMO`。在你的滑板車上驗證之前，切勿把任何讀數
或命令當成正確。

---

## Downloads | 檔案下載

| File 檔案 | Format 格式 | Description | 說明 |
| :--- | :---: | :--- | :--- |
| **M365-Rokid-HUD-phone-v1.5.2.apk** | APK | Phone app — M365 BLE client + Rokid gateway (sideload) | 手機端 — M365 BLE 客戶端 + Rokid 閘道（側載安裝） |
| **M365-Rokid-HUD-glasses-v1.5.2.apk** | APK | Glasses app — Rokid AR HUD display (sideload) | 眼鏡端 — Rokid AR HUD 顯示（側載安裝） |
| **M365-Rokid-HUD-phone-v1.5.2.aab** | AAB | Phone app — Google Play publishing bundle | 手機端 — Google Play 發佈用 App Bundle |
| **M365-Rokid-HUD-glasses-v1.5.2.aab** | AAB | Glasses app — Google Play publishing bundle | 眼鏡端 — Google Play 發佈用 App Bundle |
| **SHA256SUMS.txt** | Checksums | SHA256 hashes for all release artifacts | 所有產物的 SHA256 校驗值 |

Verify downloads | 下載校驗：
```bash
sha256sum -c SHA256SUMS.txt
```

Signed with the same release key as v1.4.0 (`SHA256 7C:A3:A3:F7:…`), so existing
installs update by sideload without uninstalling.
以與 v1.4.0 相同的 release key 簽章（`SHA256 7C:A3:A3:F7:…`），既有安裝可直接側載
升級，無需先解除安裝。

---

## Highlights | 重點更新

### 1. New in 1.5.2: per-model scooter detection | 1.5.2 新增：依型號辨識滑板車
- Scooters are now recognised by the model code in their BLE advertisement
  (manufacturer data, company id `0x424E`). The advertised name stays as a
  fallback and is reported as unverified. Only codes this project can name are
  mapped; an unknown code falls back instead of being forced onto the nearest
  model.
- The GATT characteristic lookup now scans every discovered service instead of
  only the expected one, so a device that exposes the same characteristics under
  a different service no longer fails silently. The expected service is tried
  first, so existing devices behave as before.
- Per-model board addressing in the Rust core, documented in
  `doc/MODEL_SUPPORT.md`. The Xiaomi profiles keep the addresses the code
  already used.
- 現在會依 BLE 廣播中的型號代碼（廠商自訂資料，company id `0x424E`）辨識滑板車；
  廣播名稱僅作為備援，並標示為未驗證。只對應本專案能確認的代碼，未知代碼會走備援
  流程，不會被硬套到最接近的車型。
- GATT 特徵查找現在會掃描所有已探索的服務，而非只找預期的服務，因此將相同特徵放在
  其他服務下的裝置不再無聲失敗。預期的服務會優先嘗試，既有裝置行為不變。
- Rust 核心新增依車型的板子位址，記錄於 `doc/MODEL_SUPPORT.md`。小米車型沿用程式
  原本使用的位址。

### 2. From 1.5.1: Play Console fixes | 來自 1.5.1：Play Console 修正
- Narrowed R8 keep rules: phone DEX 22.5 MB → 2.5 MB, with the large majority of
  classes now renamed (96% phone, 99% glasses in this build). This clears the
  Play "DEX optimization 13%" warning.
- Edge-to-edge is set up by the app itself and no longer uses the deprecated
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`.
- 收窄 R8 keep 規則：手機端 DEX 22.5 MB → 2.5 MB，絕大多數類別已混淆（本版手機端
  96%、眼鏡端 99%），解決 Play「DEX 最佳化 13%」警告。
- 無邊框顯示改由 app 自行處理，不再使用已淘汰的
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`。

### 3. From 1.5.1: translations and layout | 來自 1.5.1：翻譯與版面
- All 10 translations completed (58–72 missing strings each).
- Arabic now lays out right-to-left.
- Motor lock buttons no longer break in Russian / Ukrainian.
- Demo Ride switch in Settings reflects its state.
- Refreshed store screenshots for 11 languages and a promo video.
- 補齊 10 種語言翻譯（每種缺 58–72 條）。
- 阿拉伯文改為由右至左版面。
- 修正俄文／烏克蘭文的馬達鎖定按鈕版面。
- 設定中的模擬騎乘開關會正確顯示狀態。
- 更新 11 種語言商店截圖與宣傳影片。

### 4. Build and release pipeline | 建置與發布流程
- Release builds no longer compile Rokid CXR-M credentials into the app, and the
  release workflow scans every artifact for them before publishing.
- Fixed the release workflow aborting on the scan's "nothing configured" result,
  which is the normal state in CI.
- Both modules report versionName 1.5.2.
- 發布版不再把 Rokid CXR-M 憑證編進 app，發布流程會在上架前掃描每個產物。
- 修正發布流程在掃描回傳「未設定憑證」（CI 的正常狀態）時中止的問題。
- 兩個模組的 versionName 皆為 1.5.2。

---

## Play "What's new" text | Play「最新異動」文字

en-US:

```
• Recognises scooters by their model code
• Fully translated into all 11 supported languages
• Arabic now displays right-to-left
• Smaller, faster app (optimized release build)
• Better display on Android 15 edge-to-edge screens
```

zh-TW:

```
• 依型號代碼辨識滑板車
• 11 種語言翻譯全數補齊
• 阿拉伯文改為由右至左顯示
• 安裝檔更小、執行更有效率
• 改善 Android 15 無邊框畫面的顯示
```

## Build | 建置

Upload the matching `mapping-*.txt` to Play Console for readable crash reports.
`libninebot_ffi.so` must be present for all four ABIs with 16 KB `PT_LOAD`
alignment, and `scripts/verify-bundle-secrets.sh` should report no Rokid
credential in either bundle.

請一併上傳對應的 `mapping-*.txt` 至 Play Console，以取得可讀的當機報告。四種 ABI 都必須
含有 16 KB 對齊的 `libninebot_ffi.so`，且 `scripts/verify-bundle-secrets.sh` 應顯示兩個
bundle 內都沒有 Rokid 憑證。
