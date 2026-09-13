# 多車款擴充狀態

查核日期：2026-09-13。所有新增車款均為 Unverified；上游專案的實測不代表本專案已經實車驗證。

## 已實作範圍

ScooterProfile／ProfileRegistry 管理車款、BLE UUID、加密策略、暫存器與控制能力。M365 保留既有 Xiaomi 登入與 UART 行為；Android 相容解碼特別保留電量替代欄位、帶正負號的速度與里程、f32 精度及短封包溫度預設值。新車款經 Rust 正規化後共用 MotorInfo，glass-hud 不含車款分支。

新款以 NinebotCrypto 完成 0x5B／0x5C／0x5D 配對。手動序號須符合 14 位 ASCII 格式並與車端挑戰一致。每部裝置的序號與 App 隨機金鑰儲存在 Keystore 保護的 EncryptedSharedPreferences，並排除雲端備份與裝置移轉。最終確認前不寫入憑證；取消、斷線及逾時均釋放會話。

廣播名稱只用於篩選及加密初始化，不作為車型證據。連線後讀取 ESC 0x10 序號；加密路徑另核對 BLE 配對序號與 ESC 序號一致。辨識結果包含符合、車型不符、部分能力、未知車款及未開啟實驗性設定。手動車款覆寫仍須通過序號核對，不可強制啟用未知車輛。識別查詢完成前，Rust 車輛會話拒絕控制及遙測請求。

預設關閉「實驗性車款」。啟用後顯示所有附近 BLE 裝置；首次連線到每個未驗證車款設定時顯示確認對話框，車輛資訊與儀表板持續顯示標示。未定義控制會停用並說明原因。舊 ESx 可明確選擇明文；自動模式先以唯讀明文識別查詢探測，無回覆才開始加密配對，不會在驗證標籤失敗後降級。

## 車款與能力

| 車款 | 暫存器／識別來源 | 加密來源 | 已提供控制 | 測試覆蓋 | 驗證狀態 |
| --- | --- | --- | --- | --- | --- |
| M365 | 原始 session、Android 解碼與 ScooterHacking mi365 序號表 | 既有 mi_crypto | 鎖定、車尾燈 | 舊命令、UART 向量、正負號／精度／替代欄位 | Verified（依擁有者指定） |
| M365 Pro | M365 ESC 共用暫存器表、mi365 序號 18832／21886 | NinebotCrypto C++ 固定版本 | 鎖定 | 加密配對、ESC 識別、遙測、控制固定向量 | Unverified |
| Pro 2 | 同 Xiaomi 共用表、mi365 序號 26354／30371 | 同上 | 鎖定 | 車型解析、能力、資料表解碼 | Unverified |
| 1S | 同 Xiaomi 共用表、mi365 序號 25699 | 同上 | 鎖定 | 車型解析、能力、資料表解碼 | Unverified |
| Max G30／SNSC 2.0 | ES 快速區與 G30Protocol 交叉核對；nbmax 的 N4G／N4L | 同上 | 鎖定 | 加密配對、ESC 識別、遙測、控制固定向量 | Unverified |
| ES1–ES4 | ES 官方 PDF／ES2ESC；nbesx 的 N2 | 同上或舊版明文 | 鎖定、標準／節能／運動模式 | 明文官方向量、校驗損毀、識別與模式命令 | Unverified |

每列最後查核日均為 2026-09-13。新版 Xiaomi 的共用表依相同協定的文件進行實作，仍需各車款實機確認；速度比例為 /1000，G30／ESx 為 /10，兩者行程欄位均乘 10。新款不套用 Android 舊版 M365 的替代欄位行為。

## 明確限制

- 新車款的車燈寫入值尚無足夠一致的資料；Xiaomi／G30 的完整模式切換定義也尚未確認。因此這些功能以 ProfilePartial 停用，不宣稱全部控制皆可使用。
- 序號表僅涵蓋已確認的產品前綴。未列出的地區版本、改寫序號、BLE／ESC 不一致及未知韌體可能被拒絕，不能用廣播名稱放行。
- SNSC 2.x 並非單一相容範圍；目前 N4L 代表 SNSC 2.0。SNSC 2.2–2.4、C1、S90L、E2x、Max G2 不在此實作支援表。
- BLE 僅提供 5AAB 的版本尚未支援，會回報獨立訊息。對所有最新韌體的相容性不作保證。
- ST-Link 不存在適用全部車板的通用序號位址。指南提供原廠探針腳位與確認 MCU／記憶體配置後的唯讀流程，不猜測車板焊點或記憶體位址。
- 本次沒有實車測試；未驗證標示不因軟體測試通過而升級。

## ABI 與建置

既有 JNI 符號均保留。新增車款描述、正規化解碼、相容解碼、配對、受限車輛會話及辨識方法。Kotlin 新版須與本次四種 ABI 的 libninebot_ffi.so 一起發佈，不能搭配舊原生檔。

車款描述版本 1：版本、筆數，接著每筆車款 ID／驗證狀態／加密策略／控制位元。辨識結果版本 1：版本、結果（0 未知、1 符合、2 不符、3 部分能力、4 實驗性關閉）、實際車款、預期車款、控制位元；255 表示無車款。控制位元為鎖定 1、車燈 2、模式 4。

正規化資料依序為電量百分比、速度 km/h、平均速度 km/h、總里程 m、行程 m、秒數、攝氏溫度。開啟車輛會話會消耗已配對 handle；關閉方法可重複呼叫，已關閉 handle 不可再發送命令。

Rust 測試包含參考加密向量、表格解碼、車型不符／未知／降級、明文校驗與 M365 相容解碼。Android 測試包含配對持久化時機、通知分片、取消、辨識結果及能力限制；主機 JNI 整合測試直接載入 Rust 動態函式庫。CI 已加入 Rust 測試與主機 JNI 建置，不只測試 Kotlin 替身。

## 來源

- [ScooterHacking Xiaomi 序號表](https://wiki.scooterhacking.org/doku.php?id=mi365)
- [ScooterHacking Max 範圍與序號](https://wiki.scooterhacking.org/doku.php?id=nbmax)
- [ScooterHacking ESx 序號](https://wiki.scooterhacking.org/doku.php?id=nbesx)
- [ES 官方協定 PDF](https://cloud.scooterhacking.org/release/nbdoc.pdf)
- [M365 ESC 暫存器](https://github.com/etransport/ninebot-docs/wiki/M365ESC)
- [ES2 ESC 暫存器](https://github.com/etransport/ninebot-docs/wiki/ES2ESC)
- [NinebotCrypto](https://github.com/scooterhacking/NinebotCrypto/tree/9ba0c551488a8433cea173d1bed843240f63ec30)
- [G30 交叉核對實作](https://github.com/Bl0ck154/Ninebot-scooter-blocker/blob/main/app/src/main/java/com/bl0ck154/ninebotblocker/G30Protocol.java)
