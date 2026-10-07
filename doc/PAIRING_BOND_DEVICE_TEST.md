# RideFlux 配對金鑰實機互通測試

測試日期：2026-10-06（Asia/Taipei）。使用 ADB 連接 Redmi Note 11 Pro+ 5G，Android 13 / API 33。對端為手機已安裝的 RideFlux 0.1.10（versionCode 11）。HUD 測試版為目前工作樹的 1.5.2（versionCode 11）。

## 方法與資料範圍

以 Android 系統檔案選擇器與真實指紋驗證操作 UI，未對配對頁截圖。只以兩筆合成資料進行跨 App 交換：`HUDtestMi`（Xiaomi，12 bytes）與 `HUDtestNinebot`（Ninebot，16 bytes）。HUD 原有金鑰僅觀察遮罩摘要，在匯出前取消勾選；沒有匯出、取代或刪除它。

手機匯出的兩個檔案另外用 Python `hashlib.pbkdf2_hmac` 與 `cryptography` AES-GCM 解密，驗證 RFBOND v1 的 header/AAD、schema、筆數、family 與合成 credential bytes 完全一致。只輸出通過訊息，未記錄金鑰或口令。

## 已通過的實機檢查

| 項目 | 觀察結果 |
| --- | --- |
| 舊 Xiaomi 設定遷移 | 開啟頁面後列出原有金鑰，MAC 僅顯示最後兩組；重啟後可載入加密儲存 |
| 公開 known-answer 向量 | 正確口令可解密並新增一筆；錯誤口令留在對話框，顯示統一的口令錯誤／檔案損壞訊息 |
| 手動新增 Ninebot | 12 個 hex 字元 MAC、`0x` 前綴的 16-byte credential 可輸入，正常新增並顯示 Ninebot family |
| Xiaomi 長度檢查 | 錯誤長度拒絕儲存，對話框保留供修正 |
| 同 MAC 的手動衝突 | 取消取代保留原測試資料；再次輸入並明確確認取代後更新測試標籤與 credential |
| 原生身分確認 | 指紋成功後才開啟匯出口令對話框 |
| 60 秒匯出期限 | 身分確認逾時後在 CreateDocument 儲存，HUD 拒絕寫入；系統建立的檔案為 0 bytes；重新驗證後成功匯出 |
| HUD → RideFlux | RideFlux 解密並預覽兩個正確 family，確認後列表出現兩筆測試資料 |
| RideFlux → HUD：既有資料 | 解密預覽兩筆，取代勾選預設關閉；確認結果為新增 0、取代 0、保留 2 |
| RideFlux → HUD：重新新增 | 刪除 HUD 兩筆測試資料後重匯，結果為新增 2、取代 0、保留 0 |
| 獨立解密檢查 | HUD 與 RideFlux 的實際匯出檔都只有兩筆合成資料；family、bytes 完全一致 |
| 檔頭辨識 | 無效 magic 在要求口令前拒絕；有效檔案改名 `.bin` 仍進入口令對話框 |
| 256 KiB 上限 | 超過上限的檔案在要求口令前拒絕 |
| 本機儲存位置 | `run-as` 確認加密檔在 `no_backup/pairing-bonds.enc`，沒有讀取其明文內容 |
| 畫面保護 | 配對頁與返回前景後的 HUD window 帶有 `FLAG_SECURE`；離開頁面並完成導覽動畫後清除 |
| Logcat | 擷取 HUD UID 的現存 logcat，未包含兩筆測試 credential hex 或測試匯出口令 |

## 清理與限制

HUD 的兩筆合成金鑰已透過刪除確認移除，最後列表只有原有一筆。七個本次建立的手機 Download 測試檔均已移除。未清除任一 App 的資料。測試期間切換的輸入語言已切回。

RideFlux 0.1.10 配對頁沒有單筆刪除入口，且安裝套件不可 `run-as`，因此它保留 `HUDtestMi` 與 `HUDtestNinebot` 兩筆合成金鑰；未為清理它們而清除其他設定或行程資料。

測試 APK 使用 `-PskipRustBuild`，缺少 `ninebot_ffi`，僅用於 UI、儲存與備份互通測試。完成後已以保留資料的方式還原手機原本、原簽章且含 native library 的 HUD 1.5.0（versionCode 8）。測試 APK 不應用於日常連車。

沒有對實車進行 BLE 登入，因此 5C/5D 的實際車端接受／拒絕，以及 AES 工作階段金鑰派生仍屬 JVM 協定測試範圍。也未在此手機移除螢幕鎖來測試無鎖定裝置分支。之前完整 JVM 測試結果為 357 tests、0 failures、0 errors；本次實機互通未發現需要修改 production code 的問題。

2026-10-07 已補齊原生工具鏈並完成完整 HUD 1.5.2 的建置、手機安裝與啟動。手機與眼鏡的實際 Android JNI 各通過 56 項檢查；最新 APK、裝置狀態與範圍見 `PAIRING_BOND_NATIVE_BUILD.md`。
