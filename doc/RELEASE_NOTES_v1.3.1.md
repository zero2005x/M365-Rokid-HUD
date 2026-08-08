# v1.3.1 — Glasses APK & Release Automation | 眼鏡端 APK 與發布自動化

This is a **packaging and build-correctness release**. The phone app's
application code is unchanged from v1.3; everything below concerns the glasses
app, the native library, and how releases are produced.

這是一次**打包與建置正確性**的發布。手機端的應用程式碼與 v1.3 相同；以下變更集中在眼鏡端、原生函式庫，以及發布流程本身。

---

## Downloads | 下載

| APK | Description | 說明 | Size |
| --- | --- | --- | --- |
| **M365-Rokid-HUD-phone-v1.3.1.apk** | Phone app — M365 BLE client + Rokid gateway | 手機端 — M365 BLE 用戶端 + Rokid 閘道 | 24.6 MB |
| **M365-Rokid-HUD-glasses-v1.3.1.apk** | Glasses app — Rokid AR HUD display | 眼鏡端 — Rokid AR 抬頭顯示器 | 11.4 MB |
| **SHA256SUMS.txt** | Checksums for both APKs | 兩顆 APK 的雜湊值 | — |

> **v1.3 shipped only the phone APK.** The glasses APK is included again from
> this release onward, and both are now produced by the same automated build.
>
> **v1.3 只有手機端 APK。** 從本版起眼鏡端 APK 重新隨版本發布，且兩者由同一套自動化流程產出。

Verify your download before installing | 安裝前請先驗證下載內容：

```powershell
Get-FileHash .\M365-Rokid-HUD-phone-v1.3.1.apk -Algorithm SHA256
```

```bash
sha256sum -c SHA256SUMS.txt
```

---

## What's Fixed | 修正內容

### Glasses app: CXR-M transport was silently dead in release builds
### 眼鏡端：CXR-M 傳輸在 release 版本中無聲失效

The most consequential fix in this release, and one that **only affected
release builds** — debug builds were never impacted, which is why it went
unnoticed.

本次最重要的修正，且**只影響 release 版本**——debug 版本完全正常，所以一直沒被發現。

`CxrMClient` reaches the Rokid CXR SDK exclusively through string reflection:

`CxrMClient` 完全透過字串反射存取 Rokid CXR SDK：

```kotlin
Class.forName("com.rokid.cxr.CxrClient")
cxrClientClass.getMethod("sendData", ByteArray::class.java)
Proxy.newProxyInstance(..., arrayOf(Class.forName("com.rokid.cxr.DataCallback")))
```

Because nothing references those types at compile time, R8 treated the whole
package as unreachable and was free to strip or rename it — even though
`com.rokid.cxr:client-m` is a declared dependency. The keep rules for exactly
this situation were present in `proguard-rules.pro` but **commented out**.

由於編譯期沒有任何引用，R8 判定整個套件無法觸及，可自由移除或改名——即使
`com.rokid.cxr:client-m` 是明確宣告的相依套件。`proguard-rules.pro` 裡針對此情況的
保留規則雖然存在，卻是**被註解掉的**。

The runtime symptom: `Class.forName` threw `ClassNotFoundException`,
`isSdkAvailable()` reported `false`, and the CXR-M transport quietly vanished,
falling back to another connection path without any error surfaced to the user.

執行期症狀：`Class.forName` 拋出 `ClassNotFoundException`，`isSdkAvailable()` 回報
`false`，CXR-M 傳輸靜默消失並改用其他連線路徑，使用者看不到任何錯誤訊息。

### Glasses app: release build could not complete
### 眼鏡端：release 建置無法完成

R8 failed on nine missing classes belonging to OkHttp's **optional** TLS
providers (BouncyCastle JSSE, Conscrypt, OpenJSSE), which arrive transitively
through the Rokid SDK. OkHttp probes for each at runtime inside `try`/`catch`,
so their absence is normal and harmless — but the dangling references still
failed the build. Resolved with `-dontwarn` (not `-keep`: the classes genuinely
are absent and must not be retained).

R8 因九個缺失類別而失敗，它們屬於 OkHttp 的**選用** TLS 供應者（BouncyCastle JSSE、
Conscrypt、OpenJSSE），由 Rokid SDK 間接帶入。OkHttp 在執行期以 `try`/`catch` 逐一探測，
缺席屬正常且無害——但懸空引用仍會讓建置失敗。以 `-dontwarn` 解決（而非 `-keep`：
這些類別確實不存在，不應保留）。

### Native library rebuilt from current source
### 原生函式庫已由當前原始碼重新編譯

The phone APK now ships a `libninebot_ffi.so` compiled from the current tree for
all four ABIs. The v1.3 artifact was built before the most recent native
compilation, so its embedded library was not in step with the repository.

手機端 APK 現在四個 ABI 都內含由當前程式碼編譯的 `libninebot_ffi.so`。v1.3 的產物
建置於最近一次原生編譯之前，其內嵌函式庫與儲存庫並不同步。

Every shipped library is verified during the build to have all `PT_LOAD`
segments 16 KB aligned (`p_align = 0x4000`), which Google Play requires. The
linker flags live in `ninebot-ffi/.cargo/config.toml`, but a `RUSTFLAGS`
environment variable silently overrides them — so the check inspects the
produced artifact rather than trusting the configuration.

每個發布的函式庫在建置過程中都會驗證所有 `PT_LOAD` 區段為 16 KB 對齊
（`p_align = 0x4000`），這是 Google Play 的要求。連結器旗標定義於
`ninebot-ffi/.cargo/config.toml`，但環境變數 `RUSTFLAGS` 會無聲覆蓋它——因此檢查
是針對產出的檔案，而非信任設定檔。

---

## Release Automation | 發布自動化

Releases are now built by GitHub Actions on any `v*` tag
(`.github/workflows/release.yml`). Each run:

發布改由 GitHub Actions 在推送 `v*` tag 時自動執行（`.github/workflows/release.yml`）。
每次執行會：

1. Install Rust with all four Android targets plus `cargo-ndk`, and **rebuild the
   native library from source** rather than reusing whatever sits in the tree
   以完整四個 Android target 安裝 Rust 與 `cargo-ndk`，並**從原始碼重建原生函式庫**，
   而非沿用工作目錄中既有的檔案
2. Build `:app:assembleRelease` and `:glass-hud:assembleRelease`
   建置兩個模組的 release 版本
3. Verify 16 KB page alignment of every shipped `.so`
   驗證每個發布的 `.so` 的 16 KB 分頁對齊
4. Verify both APKs' signing certificates with `apksigner`
   以 `apksigner` 驗證兩顆 APK 的簽章憑證
5. Publish the APKs plus `SHA256SUMS.txt`, then delete the keystore
   發布 APK 與 `SHA256SUMS.txt`，並刪除金鑰庫

This removes the class of problem that produced v1.3: a release assembled by
hand, from a working tree whose state was not fully known.

這消除了造成 v1.3 問題的根源：以手動方式、從狀態未完全掌握的工作目錄組出發布。

---

## Signing | 簽章

Both APKs are signed with the same certificate as previous releases:

兩顆 APK 皆使用與先前版本相同的憑證簽署：

```
SHA-256: 7C:A3:A3:F7:BA:C7:48:3C:0D:16:BB:9E:1E:BD:B6:57:
         F0:94:CE:77:94:83:DD:BC:7F:E3:64:32:46:01:E3:0B
```

Existing installations update in place — no uninstall required.

現有安裝可直接更新，**無需先解除安裝**。

---

## Installation | 安裝方式

Install the phone APK first, then the glasses APK.

請先安裝手機端，再安裝眼鏡端。

1. **Phone | 手機端** — sideload `M365-Rokid-HUD-phone-v1.3.1.apk`, then grant
   Bluetooth and Location permissions when prompted
   側載手機端 APK，並在提示時授予藍牙與位置權限
2. **Glasses | 眼鏡端** — sideload `M365-Rokid-HUD-glasses-v1.3.1.apk` onto the
   Rokid glasses via ADB
   透過 ADB 將眼鏡端 APK 側載至 Rokid 眼鏡
3. **Pair | 配對** — enable the gateway in the phone app; the glasses connect
   automatically
   在手機端啟用 Gateway，眼鏡會自動連線

```bash
adb install -r M365-Rokid-HUD-glasses-v1.3.1.apk
```

> Battery optimization should be disabled for the phone app, or Android will
> suspend the gateway service in the background.
>
> 請關閉手機端的電池最佳化，否則 Android 會在背景暫停 Gateway 服務。

---

## Requirements | 系統需求

| | Phone 手機端 | Glasses 眼鏡端 |
| --- | --- | --- |
| Android | 10 (API 29) or higher | 10 (API 29) or higher |
| Target SDK | 36 | 36 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 | — |
| Package | `com.m365bleapp` | `com.m365hud.glass` |
| versionCode | 6 | 4 |

---

## Known Limitations | 已知限制

- **CXR-M channel discovery is not implemented.** `UnifiedConnectionManager`
  cannot obtain an ARTC channel id automatically, so the CXR-M path falls back
  to Wi-Fi or BLE unless a channel id is supplied explicitly. The R8 fix above
  makes the transport *reachable*; it does not complete the discovery mechanism.
  **CXR-M 頻道探索尚未實作。** `UnifiedConnectionManager` 無法自動取得 ARTC channel
  id，因此除非明確指定，CXR-M 路徑會退回 Wi-Fi 或 BLE。上述 R8 修正讓該傳輸*可被觸及*，
  但並未完成探索機制。
- The glasses release build now runs R8 for the first time. If you encounter
  behaviour that differs from a debug build, please open an issue.
  眼鏡端 release 版本首次啟用 R8。若發現與 debug 版本行為不同，請回報 issue。

---

## Upgrade Notes | 升級說明

Coming from **v1.3**: install both APKs. The phone app's behaviour is unchanged;
the glasses app gains a working CXR-M code path and is signed and versioned
alongside the phone app for the first time.

從 **v1.3** 升級：請安裝兩顆 APK。手機端行為不變；眼鏡端修復了 CXR-M 程式路徑，
並首次與手機端採用一致的簽章與版本編號。

Coming from **v1.1 or earlier**: see the v1.3 release notes for the Wi-Fi
gateway and security hardening changes introduced there.

從 **v1.1 或更早版本**升級：Wi-Fi 閘道與安全性強化的內容請參閱 v1.3 的發布說明。

**Full Changelog**: https://github.com/zero2005x/M365-Rokid-HUD/compare/v1.3...v1.3.1
