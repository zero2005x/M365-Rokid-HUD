# v1.5.0 — Reverse-Engineered Scooter Protocol Stack & Phone UI Overhaul | 逆向滑板車協定堆疊與手機端 UI 重整

This release imports a reverse-engineered Xiaomi/Ninebot scooter BLE protocol
stack (telemetry parsers, error codes, plaintext + NinebotCrypto handshake),
rebuilds the phone-side UI around a single control surface, resolves all
SonarCloud issues, and wires Kotlin code coverage into CI.

本次發布匯入了逆向工程得出的小米／Ninebot 滑板車 BLE 協定堆疊（遙測解析器、
錯誤碼、明文與 NinebotCrypto 握手），將手機端 UI 重整為單一控制介面，解決全部
SonarCloud 議題，並把 Kotlin 覆蓋率接進 CI。

---

## ⚠️ Pre-release — experimental & not hardware-verified | 預先發布 — 實驗性、未經實車驗證

**The scooter protocol stack in this release has never been verified against a
real scooter.** Every protocol conclusion comes from static reverse engineering
only. The encrypted (NinebotCrypto) and plaintext (Ninebot) connection paths
cannot be exercised without hardware, register offsets / scales are unconfirmed,
and write commands (lock / lights / settings) are guarded and, in the case of
settings, intentionally unwired. Controls are capability-gated per model and
demo data is clearly marked `DEMO`. Treat this build as experimental; do not rely
on any reading or command as correct until it is verified on your scooter.

**本次發布的滑板車協定堆疊從未經實車驗證。** 所有協定結論皆來自純靜態逆向分析。
加密（NinebotCrypto）與明文（Ninebot）連線路徑在沒有硬體時無法觸發，暫存器 offset／
刻度尚未確認，寫入命令（鎖車／燈光／設定）皆有防護、其中設定寫入刻意未接線。控制項
依車型能力顯示，示範資料明確標記為 `DEMO`。請將此版視為實驗性；在你的滑板車上驗證
之前，切勿把任何讀數或命令當成正確。

---

## Downloads | 檔案下載

| File 檔案 | Format 格式 | Description | 說明 |
| :--- | :---: | :--- | :--- |
| **M365-Rokid-HUD-phone-v1.5.0.apk** | APK | Phone app — M365 BLE client + Rokid gateway (sideload) | 手機端 — M365 BLE 客戶端 + Rokid 閘道（側載安裝） |
| **M365-Rokid-HUD-glasses-v1.5.0.apk** | APK | Glasses app — Rokid AR HUD display (sideload) | 眼鏡端 — Rokid AR HUD 顯示（側載安裝） |
| **M365-Rokid-HUD-phone-v1.5.0.aab** | AAB | Phone app — Google Play publishing bundle | 手機端 — Google Play 發佈用 App Bundle |
| **M365-Rokid-HUD-glasses-v1.5.0.aab** | AAB | Glasses app — Google Play publishing bundle | 眼鏡端 — Google Play 發佈用 App Bundle |
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

### 1. Reverse-engineered protocol stack (experimental) | 逆向協定堆疊（實驗性）
- New `protocol/` layer: ESC & BMS telemetry parsers, `ScooterReply` frame
  validator, 46-entry error-code table, plaintext register session, NinebotCrypto
  cipher + `5B/5C/5D` handshake, and the `FrameCodec` now wired into a real
  consumer.
- Rust core: added `encryption2`, `identity`, `ninebot_legacy`, `mtu`,
  `transport`, and a `model` registry.
- Demo ride mode feeds synthetic telemetry through the real parsers so the display
  chain can be exercised without a scooter.
- 新增 `protocol/` 層：ESC 與 BMS 遙測解析器、`ScooterReply` 幀驗證器、46 條錯誤碼表、
  明文暫存器會話、NinebotCrypto 密碼器與 `5B/5C/5D` 握手，`FrameCodec` 首次接上真實
  消費端。Rust 核心新增 `encryption2`／`identity`／`ninebot_legacy`／`mtu`／
  `transport` 與 `model` 登錄。示範騎乘模式以真實解析器餵入合成遙測，讓顯示鏈路可在
  無滑板車時被驗證。

### 2. Phone-side UI overhaul | 手機端 UI 重整
- Removed the separate dashboard: Home's connected face **is** the dashboard, and
  the vehicle detail page is the single detail view.
- Home controls: Lights · Gateway · Lock (all capability-gated) + Details.
- Centralized the gateway Bluetooth + permission flow (no more silently-failing
  toggles); lock now requires confirmation and shows an honest "state unknown".
- Missing telemetry shows an em-dash instead of a fake `0`; WiFi gateway moved to
  Settings; UI strings extracted to resources.
- 移除獨立儀表板：Home 連線後的畫面「就是」儀表板，車輛詳情頁為唯一詳情視圖。Home
  控制列：燈光・閘道・鎖車（皆依能力顯示）＋詳情。閘道的藍牙與權限流程收斂為單一安全
  路徑；鎖車需二次確認並顯示誠實的「狀態未知」。未取得的遙測顯示破折號而非假的 `0`；
  WiFi 閘道移至設定；UI 字串抽為資源。

### 3. Reverse-engineering documentation | 逆向工程文件
- `doc/reverse-engineering/` — Scootbatt static-analysis reports + ScooterHacking
  wiki reference; plus `IMPROVEMENT_PLAN`, `PROTOCOL_FAMILIES`, `MODEL_SUPPORT`,
  `NINEBOT_LEGACY_PROTOCOL`.
- `doc/reverse-engineering/` — Scootbatt 靜態分析報告與 ScooterHacking wiki 參考；
  另有 `IMPROVEMENT_PLAN`、`PROTOCOL_FAMILIES`、`MODEL_SUPPORT`、
  `NINEBOT_LEGACY_PROTOCOL`。

### 4. Code quality & coverage | 程式品質與覆蓋率
- Resolved all 20 SonarCloud issues (constructor-injected dispatchers, extracted
  duplicated constants, section-split composables, unused-param cleanup, and
  justified suppressions on inherently-complex concurrency methods).
- Wired Kotlin **Kover** coverage into CI and SonarCloud; the protocol/logic layer
  reports 80–96% coverage.
- 解決全部 20 個 SonarCloud 議題（建構子注入 dispatcher、抽出重複常數、拆分 composable、
  清理未用參數、對本質複雜的並行方法加註合理抑制）。將 Kotlin **Kover** 覆蓋率接進 CI
  與 SonarCloud；協定／邏輯層覆蓋率達 80–96%。

---

## Known limitations | 已知限制
- No protocol path verified on a real scooter (see the pre-release banner above).
- The Android / UI / gateway / BLE layers have no unit tests yet (they need
  Robolectric / instrumented tests) and are excluded from the coverage ratio.
- 無任何協定路徑經實車驗證（見上方預先發布警告）。Android／UI／閘道／BLE 層尚無單元
  測試（需 Robolectric／instrumented 測試），已排除於覆蓋率計算之外。
