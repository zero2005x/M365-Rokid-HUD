# 給 RideFlux 實作 agent 的 prompt

請在 `C:\Users\liangtinglin\Documents\codebase\Android\RideFlux` 實作以下修正，直接開工，不需再詢問產品方向。先讀取適用的 AGENTS.md，保留使用者未提交的變更，不要修改 M365-Rokid-HUD 專案。

## 背景與目標

2026-10-06 已用 Android 13 / Redmi Note 11 Pro+ 5G 實機驗證 RideFlux 0.1.10 與 M365-Rokid-HUD 的 RFBOND v1 雙向交換，Xiaomi 12-byte token 與 Ninebot 16-byte app random 都可正確交換。但 RideFlux「配對金鑰」頁沒有單筆刪除入口，測試資料無法清理。請補上安全的單筆刪除，並重新檢查本頁、所有對話框與多國語言排版。

參考本專案實際檔案（先確認目前版本，行號可能變動）：

- `app/src/main/kotlin/com/rideflux/app/ui/bond/BondBackupScreen.kt`
- `app/src/main/kotlin/com/rideflux/app/ui/bond/BondBackupDialogs.kt`
- `app/src/main/kotlin/com/rideflux/app/ui/bond/BondBackupViewModel.kt`
- `app/src/main/kotlin/com/rideflux/app/ui/bond/BondBackupState.kt`
- `data/preferences/src/main/kotlin/com/rideflux/data/preferences/EncryptedFileBondStore.kt`：已有 `remove(mac)`，先核對它是否涵蓋所有連線讀取來源。

## 單筆刪除

1. 每個已儲存金鑰項目提供容易發現、至少 48 dp 觸控區的刪除按鈕，具備翻譯後的 TalkBack 描述與該項目的遮罩 MAC／名稱。不要讓長翻譯搶掉摘要寬度。
2. 先顯示確認對話框，識別所選項目的名稱／遮罩 MAC，說明刪除後可能需要重新配對。取消不得改動任何資料；確認才呼叫 ViewModel 的刪除操作。
3. 刪除只針對該 MAC。核對 BondStore、連線端偏好設定／快取等來源，避免刪除後仍能用殘留資料登入，或舊資料遷移又把金鑰加回。不得連帶移除其他車輛、橋接配對、行程紀錄或設定。
4. busy 時避免重複操作；失敗顯示本地化錯誤且可重試。成功重新載入列表，移除失效的匯出選取，正確處理刪除最後一筆、取消與併發匯入。
5. 維持現有儲存加密、buffer 清理、FLAG_SECURE、遮罩 MAC、禁止 secret log 的要求。不要改 RFBOND wire format、身份確認時限或登入協定。

## 排版與多國語言

1. 審視主列表、風險提示、空列表、匯出／匯入／手動新增入口、口令、匯入預覽、取代確認、刪除確認與錯誤提示。建立清楚的名稱、MAC、family 字級層級；控制元件垂直對齊，長文字可換行。
2. 正確約束 Row／Column 寬度，摘要使用 weight，操作不與文字重疊。必要時讓操作垂直排列；不要以縮小全 App 字級或禁止系統字體縮放處理。
3. 對話框内容可捲動，鍵盤出現、窄螢幕與大字體時，欄位與確認／取消仍可到達。長名稱可合理省略，風險說明、错误與確認動作不可因截斷失去意義。
4. 核對 RideFlux 實際支援的所有語言，補齊新增和相關既有字串，不能把英文複製進 locale 目錄冒充翻譯。所有使用者可見字串與無障礙描述使用資源；保留正確的 `%1$d` 等格式參數、XML escaping 與複數規則。
5. 阿拉伯文等 RTL 要正確鏡像操作布局；MAC／hex 的字元順序保持 LTR。測試中英混合名稱、日韓文字、長歐洲語言、阿拉伯文、emoji 與 64 code-point 名稱。金鑰不可顯示在列表或確認對話框。

## 驗證與交付

- 以適當 ViewModel／store 測試驗證確認才刪除、取消不變、僅刪所選 MAC、選取同步、失敗可重試和重啟後不復活。
- 掃描所有 locale 的 key、重複 key、參數型別與數量，確認字串無意外英文 fallback；通過資源編譯與相關 lint。
- 用可用的 ADB 裝置或 emulator 測試所有支援語言，至少 320 dp 寬及 font scale 1.0、1.3、2.0，涵蓋 RTL、鍵盤、長名稱與多筆預覽。對話框完成動畫後再取得 bounds；實際點擊確認／取消，驗證預期狀態轉換，不能只憑 AX 可見就判定鍵盤下可操作。特別檢查 RTL 大字體的兩行確認動作是否重疊取消。不要停留在只確認 XML 能編譯。
- 不要對金鑰頁截圖／錄影，不能為了 QA 關閉 FLAG_SECURE。使用 Compose layout／semantics、測試資料與 UI hierarchy 驗證布局，並如實區分可證實的布局結果與未做的視覺檢查。
- 實機目前有 `HUDtestMi`（遮罩尾碼 EE:FF）與 `HUDtestNinebot`（遮罩尾碼 60:06）兩筆假資料。先核對名字與遮罩，再用新增的刪除 UI 清掉它們；不要清除 App 資料、刪其他車輛或匯出真正金鑰。
- 如果 ADB 被其他 agent 使用，先確認裝置操作安排，避免同時切換 App。交付變更摘要、測試結果、locale／尺寸測試矩陣與未驗證限制；保留使用者原本資料和語言／字體設定。
