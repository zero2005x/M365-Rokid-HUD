# v1.5.1 — Play Console fixes, full translations | Play Console 修正、翻譯補齊

Phone app `com.m365bleapp` versionCode 11, glasses app `com.m365hud.glass`
versionCode 8. Both report versionName 1.5.1.

Play already serves an earlier 1.5.1 build as versionCode 10, which was cut
before the per-model addressing work (#10) landed on `main`. This build
supersedes it, so the versionCode moves up while the versionName stays 1.5.1.

手機端 `com.m365bleapp` versionCode 11，眼鏡端 `com.m365hud.glass` versionCode 8，
versionName 皆為 1.5.1。

Play 上已有 versionCode 10 的 1.5.1 版本，該版本是在 per-model addressing（#10）
合併進 `main` 之前建置的。本版取代它，因此 versionCode 遞增、versionName 維持 1.5.1。

---

## Play Console issues addressed | 已處理的 Play Console 問題

| Issue | Fix |
| :--- | :--- |
| DEX optimization / obfuscation / shrinking at 13% (threshold 25%) | Blanket R8 keep rules removed. Phone DEX 22.5 MB → 2.5 MB, 91% of classes now renamed; glasses 95%. |
| Deprecated `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` (`b.r.a`) | Came from androidx.activity `enableEdgeToEdge()`; the app now sets up edge-to-edge itself and never uses that mode. |
| Edge-to-edge may not display for all users | Same change. |

| 問題 | 修正 |
| :--- | :--- |
| DEX 最佳化／模糊化／縮減僅 13%（門檻 25%） | 移除過寬的 R8 keep 規則。手機端 DEX 22.5 MB → 2.5 MB，91% 類別已混淆；眼鏡端 95%。 |
| 已淘汰的 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`（`b.r.a`） | 來源為 androidx.activity 的 `enableEdgeToEdge()`；改為自行處理無邊框，不再使用此模式。 |
| 無邊框畫面可能無法向所有使用者顯示 | 同上。 |

## Other changes | 其他變更

- All 10 translations completed (58–72 missing strings each). | 補齊 10 種語言翻譯（每種缺 58–72 條）。
- Arabic now lays out right-to-left. | 阿拉伯文改為由右至左版面。
- Motor lock buttons no longer break in Russian / Ukrainian. | 修正俄文／烏克蘭文的馬達鎖定按鈕版面。
- Demo Ride switch in Settings reflects its state. | 設定中的模擬騎乘開關會正確顯示狀態。
- Refreshed store screenshots for 11 languages and a promo video. | 更新 11 種語言商店截圖與宣傳影片。
- Scooters are now recognised by the model code in their BLE advertisement, with the advertised name as a fallback; the GATT characteristic lookup scans every discovered service. Covered by unit tests only, not yet verified on a scooter. | 改以藍牙廣播中的型號代碼辨識滑板車（廣播名稱作為備援）；GATT 特徵查找會掃描所有已探索的服務。僅有單元測試涵蓋，尚未在實機驗證。

## Play "What's new" text | Play「最新異動」文字

en-US:

```
• Fully translated into all 11 supported languages
• Arabic now displays right-to-left
• Fixed motor lock buttons in Russian and Ukrainian
• Smaller, faster app (optimized release build)
• Better display on Android 15 edge-to-edge screens
```

zh-TW:

```
• 11 種語言翻譯全數補齊
• 阿拉伯文改為由右至左顯示
• 修正俄文、烏克蘭文的馬達鎖定按鈕版面
• 安裝檔更小、執行更有效率
• 改善 Android 15 無邊框畫面的顯示
```

## Build | 建置

Built in WSL (Rust toolchain) with the release key
(`SHA256 7C:A3:A3:F7:…`, same as v1.5.0), so it updates the existing Play
listing. `libninebot_ffi.so` is present for all four ABIs with 16 KB
`PT_LOAD` alignment. No locally configured Rokid credential appears in either
bundle. Upload the matching `mapping-*.txt` to Play Console for readable
crash reports.

於 WSL（含 Rust 工具鏈）以正式金鑰建置（`SHA256 7C:A3:A3:F7:…`，與 v1.5.0 相同），
可直接更新既有 Play 上架。四種 ABI 皆含 16 KB 對齊的 `libninebot_ffi.so`，兩個
bundle 內皆無本機設定的 Rokid 憑證。請一併上傳對應的 `mapping-*.txt` 至 Play Console，
以取得可讀的當機報告。
