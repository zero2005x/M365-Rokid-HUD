# Windows 原始碼／Kali 建置

主專案位於 `C:\Users\liangtinglin\Documents\codebase\Android\M365-Rokid-HUD`。
修改這份原始碼；舊的 `/home/kali/ScooterHacking/repo` 保留作為遷移來源，不再是本流程的建置輸入。

## 執行

在專案根目錄的 PowerShell 執行：

```powershell
.\scripts\build-wsl.ps1
```

預設使用 `kali-linux`。可指定 `-Distribution` 切換發行版，但該發行版必須具有同樣工具鏈。

腳本將：

1. 將 Git 納管及未被忽略的原始碼同步至 `/home/kali/ScooterHacking/windows-build`。
2. 載入 `/home/kali/ScooterHacking/env.sh`，沿用 Kali 的 JDK、Rust、Android SDK、NDK 和 Gradle 快取。
3. 執行 Rust 核心、JNI registry 和真實 JVM/JNI 測試。
4. 重建四種 Android ABI，執行手機及眼鏡 Kotlin 測試，包含 Windows 既有的原生整合測試。
5. 將新建的兩個 Debug APK 和測試報告放回 Windows 的各模組 `build` 目錄。

產物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `glass-hud/build/outputs/apk/debug/glass-hud-debug.apk`
- 各模組的 `build/reports/tests/testDebugUnitTest/index.html`

原始碼在 Windows，編譯暫存位於 Linux，以避免 Windows 與 Linux 的 Gradle／Cargo 產物互相污染。不要在 `windows-build` 編輯檔案，下次同步會覆蓋它們。兩份原始碼不會雙向自動合併。

## 設定與限制

Windows 的 `.git`、`local.properties`、簽章金鑰及 IDE 設定均保留。腳本不複製這些私有設定；Kali 暫存目錄只產生指向 Linux SDK 的 `local.properties`。這是 Debug 驗證流程，沒有配置 release 簽章或 Rokid CXR-M 私有憑證，BLE／WiFi HUD 不需要該憑證。

可在 WSL 設定 `M365_WSL_ENV` 或 `M365_WSL_STAGE`，更換工具鏈設定檔或獨立建置目錄。不要對同一 staging 目錄並行執行建置。

原本納管的四個 JNI `.so` 改為每次由 Gradle 產生，不再提交。Windows 磁碟上先前存在的 `.so` 不代表新原始碼；請使用上述流程產生的新 APK。

## 遷移來源與研究資料

Windows 基底為 `2849da1`，Kali 未提交工作以 `7c764cb` 為共同祖先。合併前 Windows 檔案與分類清單位於本機 `build/migration-backup`，不納入 Git。

精選 22 份協定／參考文件在 `research/references`，合計約 82 KB。來源路徑、SHA-256 及快照版本見 `research/SOURCES.json`、`research/README.md`。大型反編譯資料、下載資產、工具鏈及舊 APK 留在 Kali。

## 合併後的功能界線

保留 Windows 的六款 native ProfileRegistry、序號核對、加密配對、受能力限制的控制與 JNI 入口。Kali 的 `model`、`identity`、`encryption2` 等模組提供研究／測試與廣播提示，不能取代 native 連線後的辨識結果。兩者的車款數字 ID 不可互換。

UI 使用 Kali 的單頁入口、設定中心、離線資訊及 HUD 欄位設定。實驗性車款開關在設定頁；連線對話框的進階選項保留原有車款與協定覆寫，但仍須通過序號驗證。已連線時的控制能力以 native profile 為準。

Kali 的 `HANDOFF.md`、`MODEL_SUPPORT.md` 等包含遷移前紀錄；涉及「只有七個 JNI」或「新車款尚未接入」的描述不再代表合併後的整個專案。七個 Xiaomi JNI 的測試與 Windows 新增 JNI 測試現在共同執行。Windows 原有 M365 驗證標記沿用其歷史設定，不是本次新增的實機驗證；其他車款仍未實測。

## 本次整合驗證

- 手機 Kotlin：123 個測試，0 失敗、0 跳過（包含 native profile 與解析器 JNI 測試）。
- 眼鏡 Kotlin：10 個測試，0 失敗、0 跳過。
- Rust 協定核心：160 個測試通過；JNI registry：5 個測試通過。
- 真實 JVM/JNI 合約：56 項檢查通過。
- 兩個 Debug APK 建置完成；四種 ABI 的 JNI 原始碼指紋驗證通過。
- Android aarch64 全 feature 編譯通過。
- 精選 22 份參考檔案 SHA-256 全部符合來源。

這些結果不包含真實車輛／眼鏡測試，也不表示 GitHub CI 或 SonarCloud 已在遠端通過。修改尚未 commit 或 push。
