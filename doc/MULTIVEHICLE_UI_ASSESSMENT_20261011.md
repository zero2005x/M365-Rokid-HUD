# 多車種支援與 Android 操作介面評估

日期：2026-10-11。評估對象：目前 M365-Rokid-HUD 工作目錄，以及唯讀的 `/home/kali/PEVAppRE`。本次交付為架構與操作設計評估；沒有新增車種驅動、安裝新 APK 或進行新的實車測試。稍早 M365 斷線修復的未提交變更保留。

## 結論

建議保留既有 MIT `pev-protocol-core`，把手機改成「車輛／騎乘／眼鏡」三個固定入口，以每台裝置的協議、欄位與操作能力決定畫面。先改善目前 M365 的操作，再完成手機到眼鏡的型別化 Gateway V2，接著完成 Begode A2 的實車讀值校驗。電動單車與電動滑板另立協議家族，不能套用 M365 的 register 與加密流程。

DarknessBot 可以作為產品覆蓋範圍參考。其 [Android 商店頁](https://play.google.com/store/apps/details?hl=en_US&id=com.darknessproduction.darknessbot) 宣稱 18 品牌、150 多款，涵蓋獨輪車、滑板車、電動滑板、電動單車及智慧 BMS；這是該產品的支援宣稱，不是本專案的相容性證據。不同平台及「介紹／更新內容」清單也可能不一致，必須記錄平台、版本與查核日期。

## 1. 技能安裝

已透過 skill-installer 的 GitHub 安裝器安裝使用者指定的來源：

- Repository：`wshobson/agents`
- Path：`plugins/ui-design/skills/mobile-android-design`
- 安裝位置：`C:/Users/liangtinglin/.codex/skills/mobile-android-design/SKILL.md`
- 本次已讀取 SKILL.md 與 references/details.md 的設計規則；採用 Material 3、狀態提升、適應螢幕尺寸、48dp 觸控目標及可讀的操作標籤。

後續新工作階段可直接使用這個技能。安裝器不是透過使用者給的 npx 指令執行，但安裝的是同一個指定技能來源。

## 2. 支援範圍：產品清單與協議實作分開管理

| 類別／目標 | 本專案目前可確認狀態 | 下一個必要步驟 |
|---|---|---|
| Xiaomi M365 滑板車 | 有手機加密會話、核心解碼與實車讀值；既有眼鏡 V1 路徑；稍早完成靜止連線測試 | 保留修復、改善 UI；騎乘測試仍未完成。M365 結果不能外推全部 Pro／Pro2／1S |
| Ninebot／Segway 等滑板車 | 有名稱／型號辨識與既有 native 基礎，但多家族核心整合未完成 | 逐協議世代確認認證、解碼、讀取能力；辨識得到型號不等於可以正常讀值 |
| Begode A2 獨輪車 | 核心有 24-byte raw codec 與 11,424 CAP-A 幀回放；物理單位、電池模型尚未完成 | 手機 session、CAP-B 對照量測、速度／電壓／電量校驗，再接 V2 HUD |
| Inmotion I1 獨輪車 | 有接收封包與 raw CAN 核心、501 歷史幀參考 | 認證與身份確認、欄位語義、手機連線；不能宣稱 V11／V12 等全系列支援 |
| KingSong、Veteran／NOSFET、Inmotion I2、其他 EUC | PEVAppRE 有不同程度研究；HUD 相應核心 codec 尚未完成 | 各家族独立 codec、資料集、物理驗證及能力表 |
| ADO 電動單車 | DarknessBot 列入產品範圍；本 HUD 沒有 ADO 協議實作 | 確認具体型號與手機連線協議，取得遙測封包；先做讀值 |
| ACTON 電動滑板 | DarknessBot 列入產品範圍；本 HUD 沒有 ACTON 協議實作 | 確認目前硬體／韌體世代、遙測與認證；不能靠品牌名稱選 parser |
| VESC 控制器 | DarknessBot 列入產品範圍；本 HUD 沒有 VESC codec | 依公開協議獨立實作，驗證 BLE bridge、韌體及馬達／輪徑參數；控制器不等於固定車種 |
| Xiaoxiang／Ant 智慧 BMS | 應是附屬資料來源；本 HUD 沒有相應 session | 獨立來源身份與電池欄位，不把 BMS 電量誤認為整車速度等資料 |
| Exway 電動滑板 | PEVAppRE 已有專門研究；目前屬額外候選，不能因研究存在就算 DarknessBot 支援 | Tuya／ThingClips 的產品 DP、認證、手機到遙控器拓樸仍待釐清 |

上表的品牌例子取自上述商店頁；本專案狀態以實際程式碼與 SUPPORT_MATRIX 為準。其他電動單車系統可另做候選評估；不將 ANT+ 當成 Redmi 現有 BLE 能力，也不把標準 Battery Service 直接當作主動力電池。

### 研究資料的使用方式

PEVAppRE 提供協議線索、封包與證據，不直接複製第三方 App 或 GPL RideFlux 實作到 MIT 核心。每個功能保留來源與驗證程度，分清「商店宣稱、靜態研究、歷史回放、當前實車驗證」。

重要更新：`darknessbot-dyn/BREAKTHROUGH_2026-10-11.md` 已走通假輪子的掃描／GATT／CCCD 路徑，但仍未捕獲完整協議 payload；不能把動態連線成功當作解析器已完成。`DARKNESSBOT_DISPATCH_KEY_CONFIRMED.md` 確認參考 App 多數按 advertised name 分派；本專案仍應使用多項身份證據及連線後確認，避免相同名稱或 UUID 選錯協議。

## 3. 可確認的操作問題與修正

這是程式碼與流程審查。尚未做本輪 frame-time／Perfetto 量測，因此下表不宣稱已找到 GPU 或掉幀根因。

| 優先順序 | 證據／使用者遇到的問題 | 建議修改 |
|---|---|---|
| P0 | `ScanScreen.kt` 以 RSSI 排序，訊號更新後項目可能換位置 | 已儲存裝置固定在上方；附近裝置按首次發現順序固定，RSSI 只更新標籤；手動重新整理才重排 |
| P0 | 主頁以連線狀態在掃描／儀表間切换；已連線後切車需要先斷線 | 永久保留「車輛」入口。切換由 session manager 執行，顯示目前車輛與連線進度；一次一個整車 session |
| P0 | `HomeScreen.kt` 已連線標題只有狀態，電池標籤固定滑板車圖示 | 顯示使用者命名＋型號＋資料狀態；依車種替換圖示、欄位及細節頁名稱 |
| P0 | light／lock 元件仍可顯示，但 `enabled = false` | 從騎乘頁移除永遠不可操作的按鈕。需要解釋的功能放到車輛設定，寫明不可用原因；不得為了 UX 繞過既有命令 gate |
| P0 | 首頁固定 Column、固定大字與按鈕、沒有捲動 | 可捲動儀表，短螢幕縮小速度區但不裁掉按鈕；測試橫向、系統大字與 TalkBack |
| P0 | `ModelOverrideStore.kt` 是全域保存的手動型號 | 改成每台裝置 profile 保存；切到另一台車時不沿用上一台 override。舊全域值首次遷移需讓使用者綁到具体裝置 |
| P1 | 首頁以 2 秒輪詢眼鏡狀態，掃描與設定頁也有 Gateway 控制 | 集中 Gateway 狀態為 StateFlow；車輛連線與眼鏡連線分開顯示；同一個狀態與控制入口 |
| P1 | `SettingsScreen.kt` 最前方是實驗性車輛控制，混合顯示／連線／App 偏好 | 車輛控制移至「車輛 → 該車 → 設定」；眼鏡傳輸／版面到「眼鏡」；語言、記錄、診斷留 App 設定 |
| P1 | `HomeScreen.kt` 頁面層收集 motorInfo，使用 collectAsState | 使用 lifecycle-aware collection，拆出高頻速度區；量測後才決定是否需要額外節流。UI 頁籤切換不能停止前景連線服務 |
| P1 | 新車種帶來缺值與不同感測來源 | UI 保留 VALID／STALE／INVALID／NOT_PROVIDED／UNSUPPORTED；缺值顯示 —，過期值顯示時間，不能轉為 0 |

已存在的良好設計應保留：掃描 LazyColumn 已使用 address 作為 key、contentType 與 derivedStateOf；重新連線時保持儀表頁；未取得速度時顯示 —。`isRegistered()` 現在只是 SharedPreferences contains，沒有證據支持它是昂貴 JNI 呼叫，不能據此指認掃描卡頓原因。

Compose 的狀態收集建議依 [Android 官方 State 文件](https://developer.android.com/develop/ui/compose/state)，效能量測與穩定列表依 [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)。觸控與語義依 [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)。

## 4. 建議畫面與操作流程

三個頂層頁籤「騎乘／車輛／眼鏡」；App 設定由標示清楚的設定入口開啟。小螢幕用 NavigationBar，大螢幕可用 NavigationRail。頁籤保留各自捲動位置，不觸發斷線。

```text
騎乘                          車輛                         眼鏡
我的 M365 · 已連線             已儲存                       Rokid · 已連線
資料：剛剛更新                 我的 M365 [目前使用]           車輛資料：剛剛更新
                             Begode A2 [診斷階段]           顯示預覽／欄位設定
           0.0 km/h           [+ 新增車輛]                  傳輸方式：BLE / Wi-Fi
車輛電量 80%｜手機電量 60%                                    連線協助
眼鏡：已連線                  附近裝置
可用的次要數據                [建議装置] [所有 BLE]
[車輛詳情]                    固定位置的掃描列表
```

上面的名稱與數字為設計示意，不是本輪實測資料。診斷階段裝置可以保存與查閱研究狀態；沒有實作 session 的型號不能提供看似可用的連線按鈕。

新增車輛：掃描 → 選裝置 → 顯示推測的品牌／協議及確認方式 → 執行已實作的認證 → 首次讀值 → 保存命名與 profile。未辨識裝置可進入手動協議選擇／診斷，僅列出真正有 driver 的選項。

切換車輛：點選已儲存車輛 → 結束舊 session 與舊命令 → 更新 connection generation → 建立新 session → 清除上一車的數值 → 新身份與資料到達後更新儀表及眼鏡。若新連線失敗，呈現失敗與重試入口，不保留上一車數值作為新車讀值。

故障與狀態分開：車輛中斷時顯示「車輛重新連線中」；只有眼鏡中斷時顯示「手機仍在讀取，眼鏡未連線」。不要用單一綠色「已連線」代表整條鏈路健康。

## 5. 多車種架構的必要變更

1. **裝置目錄與身份**：`VehicleCategory` 是呈現分類；`ProtocolFamily` 是實際協議，兩者不能一對一假設。Profile 包含穩定身份、型號、韌體、身份來源、已驗證讀值能力、手動選擇與使用者命名。MAC 可以作為定位線索，但不是所有裝置的永久身份。
2. **Family driver 與單一 session 管理**：各 driver 負責探測／認證／bounded framing／decode／poll；session manager 負責連線生命週期與切換。把 M365 已有 `XiaomiPhoneSession` 納入共同介面，不從零重寫有效的交易隔離與加密路徑。
3. **能力驅動畫面**：讀值與控制能力分開；欄位包含單位、來源、證據、更新時間。EUC 的 PWM／pitch／roll 等目前不在共用 FieldId，須先定義語義與來源，再加入線路格式與畫面；相電流不等於電池電流。
4. **Gateway V2 到 production**：核心已有 schema，手機與眼鏡尚未完成版本協商、BLE 分片／Wi-Fi 封裝、時鐘／freshness 與型別化 UI。新車種不得塞入舊 M365 primitive V1 以假數值補齊。
5. **附屬感測來源**：BMS 與整車 session 分開，快照 merge 保留來源；多來源配對及更高連線數可在單車流程穩定後加入，不和第一輪多家族接入同時擴大。
6. **命令仍由 coordinator 執行**：手機限定，依裝置／session／profile 綁定；實驗性同意不跨重連或切車繼承。眼鏡只顯示遙測與告警。

文件需跟程式碼同步：SUPPORT_MATRIX 部分「手機接線尚未完成」描述已落後於目前 XiaomiPhoneSession，應改為具體列出哪些讀值、命令、認證與實車項目完成，不用整個品牌的單一勾選。

## 6. 實作次序與驗收

| 階段 | 可交付成果 | 驗收重點 |
|---|---|---|
| A：現有 UI 改善 | 固定掃描排序、可捲動首頁、身份標題、不可用控制的原因；保留 M365 斷線修復 | 同時收到多筆 RSSI 更新時列位置穩定；320dp／大字下能觸及操作；鎖屏前景讀值維持 |
| B：多裝置流程 | 固定三頁籤、每裝置 profile、車輛／眼鏡獨立狀態、session 切換 | 頁籤切換不斷線；舊 session 的回覆及同意不能污染新 session；旋轉與返回不遺失選擇 |
| C：V2 眼鏡链路 | negotiation、分片與 freshness、語義顯示及告警去重 | 舊手機／舊眼鏡相容測試；截斷、重複、CRC 錯誤、序號與 generation；未知欄位不變 0 |
| D：第一個非 M365 車種 | Begode A2 讀值與資料來源校驗 | 同步儀表／官方 App／封包對照速度、電壓與電量；靜止、重新連線、移動場景；證據記錄韌體與輪徑 |
| E：逐家族擴充 | 依硬體可取得性選下一 EUC、ADO 或 VESC／ACTON | 每個家族均需 codec 測試、實際接入、手機到眼鏡驗證；不建立空 driver 後宣稱支援 |

流暢度驗收另做 release/profileable 基線：掃描約 30 秒、開啟詳情、切頁、重連及大字捲動，記錄 frame duration、慢幀與狀態更新延遲，再與修改版同設備同情境對照；沒有量測前不承諾某一 FPS 或改善百分比。協議純邏輯依現有 coverage gate，UI 以必要狀態與配置測試加上真機操作驗收。

## 7. 查閱來源

HUD：`SUPPORT_MATRIX.md`、`doc/MULTIVEHICLE_HANDOFF.md`、`pev-protocol-core/GATEWAY_SCHEMA.md`、`telemetry/Telemetry.kt`、`repository/XiaomiPhoneSession.kt`、`ui/{NavGraph,HomeScreen,ScanScreen,SettingsScreen}.kt`、`protocol/ModelOverrideStore.kt`。

PEVAppRE 根目錄：`\\wsl.localhost\kali-linux\home\kali\PEVAppRE`。重点資料：

- `README.md`
- `euc-programme/findings/PROTOCOL_MATURITY_MATRIX.md`
- `euc-programme/findings/DARKNESSBOT_STORE_RECON_2026-10-08.md`
- `euc-programme/findings/DARKNESSBOT_VEHICLE_SUPPORT_MECHANISM.md`
- `euc-programme/findings/DARKNESSBOT_DISPATCH_KEY_CONFIRMED.md`
- `euc-programme/findings/BOARDS_BMS_CONTROLLERS_BLE_RECON.md`
- `euc-programme/findings/ebike_ble_telemetry_recon.md`
- `euc-programme/findings/APP_W1_ZYDTECH.md`
- `darknessbot-dyn/BREAKTHROUGH_2026-10-11.md`
- `exway-re/EXWAY_PLAN.md`

PEVAppRE 研究項目的建議排名與結論需再核對原始來源；本報告沒有把其中所有外部系統推薦直接當成已驗證協議。
