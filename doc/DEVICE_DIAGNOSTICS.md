# 手機與眼鏡離線採集 logs

2026-10-09 修正版會保留 Xiaomi B0 的故障碼與帶正負號的速度，記錄實際送出的 UART 指令、解密後的遙測與交易終止原因。日誌不包含配對金鑰。倒退速度以負數呈現，例如 −2.5 km/h；速度接近零時可能有感測器雜訊，需以本次實車採集核對。

## 裝置

- 手機：`com.m365bleapp`
- 眼鏡：`com.m365hud.glass`

腳本會從 `adb devices -l` 自動辨識：`product:glasses` 為眼鏡，另一台為手機。同時接了多支手機時，請用 `--phone <序號> --glasses <序號>` 指定。

在電腦上執行一次：

```powershell
python scripts/collect-device-diagnostics.py start
python scripts/collect-device-diagnostics.py status
```

錄製程式在裝置上運行，拔除 USB 後仍持續記錄。每台裝置的 logcat 最多保留約 20 MB，舊內容循環覆寫，位置為 `Download/M365-diagnostics/`。**裝置重新開機會停止此 logcat 錄製；重開機後需接回 ADB 再執行 start。** 手機 App 自動存下的 CSV 不依賴此背景錄製，可在重新開機後保留。

## 採集時

1. 開啟手機 App 與眼鏡 HUD，手機連接滑板車，開啟原本使用的眼鏡 Gateway。日誌檢視器的「啟用日誌記錄」預設為開，請保持開啟。
2. 記下測試開始時間；分別記下連線、車輛警報出現、放開油門、關閉 App、向前／向後推行的時間。請在靜止或低速推行時觀察；車輛回報故障時停止騎乘，不需操作任何實驗性寫入設定來採集。
3. 故障橫幅僅由車輛實際回報的碼產生，不會由聲音描述自行填入 14。故障期間實驗性設定寫入會被阻擋，恢復後須重新啟用。
4. 測試完回到電腦接上兩台裝置，告知採集完成與大致時段即可；不需要先清除日誌。

## 取回資料

```powershell
python scripts/collect-device-diagnostics.py collect
```

輸出到 `build/adb-diagnostics/capture_年月日_時分秒/`，包含兩台裝置的循環錄製、當下 logcat 快照與版本資訊，以及手機的 BLE／遙測 CSV 壓縮檔。手機具備 root 時會直接取回 App 私有 logs；無 root 的手機（腳本會提示）可用「設定 → 日誌 → 全部匯出」取得 CSV。

完成分析後可停止背景錄製，既有資料會保留：

```powershell
python scripts/collect-device-diagnostics.py stop
```

「1 長 4 短」與 Xiaomi 公開支援文件的故障 14（油門）描述相符；這並未證明目前警報由硬體或 App 單獨造成，仍以本次車輛回報和指令紀錄分析。[Xiaomi 官方說明](https://www.mi.com/global/support/faq/details/KA-120329/)
