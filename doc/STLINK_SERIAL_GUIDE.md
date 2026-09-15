# 使用 ST-Link 查核車身序號

優先使用車身標籤、原廠 App 或購買文件上的序號。此 App 的 Ninebot 配對流程會將輸入值與車端 0x5B 回覆比對；讀取 MCU 不是必要的日常配對步驟。

## 先確認晶片及板版

不要把車型名稱當成 MCU 型號。儀表板的 BLE MCU 與馬達控制板的 MCU 是不同元件；更換儀表板也可能改變晶片。M365 研究曾使用 Nordic BLE SoC，不能套用 STM32 的記憶體位址或 STM32CubeProgrammer 流程。[研究來源](https://francozappa.github.io/publication/2023/espoofer/paper.pdf)

[STM32CubeProgrammer](https://www.st.com/content/st_com/en/stm32cubeprogrammer.html) 支援 STM32。以下 CubeProgrammer 步驟只適用於已確認的 STM32 目標。若 BLE 板是 Nordic nRF51，應改用支援該晶片與探針的讀取工具；不得因 CubeProgrammer 無法連線就執行解除保護或清除。

目前沒有為每一種 M365／Pro／Pro 2／1S／G30／ESx 板版驗證焊點位置與序號儲存位址。因此本指南提供探針的標準腳位及唯讀流程，不宣稱有通用的車板焊點圖或固定序號位址。

## ST-LINK/V2 標準 20-pin 接頭

以下是原廠 ST-LINK/V2 的 CN3，並非所有 USB 外形仿製探針的腳位。請先看接頭方向與 Pin 1 記號，再以板版圖確認目標焊點。[ST UM1075，第 12 頁表 4](https://www.st.com/resource/en/user_manual/dm00026748-stlinkv2-incircuit-debuggerprogrammer-for-stm8-and-stm32-stmicroelectronics.pdf)

| 探針腳位 | 訊號 | 目標端 |
| --- | --- | --- |
| 1／2 | VAPP | 目標供電電壓參考；不是滑板車電池輸入 |
| 7 | SWDIO | MCU 的 SWD 資料腳 |
| 9 | SWCLK | MCU 的 SWD 時鐘腳 |
| 4／6／8 等 GND 腳 | GND | 目標共地 |
| 15 | NRST | 若需要硬體重置連線，接 MCU 重置腳 |

先切斷車板電源再接線。以已確認的低壓供電方式提供 MCU 電源；不要把滑板車電池電壓接到探針或 SWD 焊點，也不要同時接入未核對的第二組供電。

## STM32CubeProgrammer 唯讀步驟

1. 記錄板版、MCU 完整料號與探針型號，確認 SWD 焊點及供電條件。
2. 開啟 STM32CubeProgrammer，選擇 ST-LINK 與 SWD；以較低 SWD 時脈開始。連線後核對顯示的 Device ID、晶片名稱與 Flash 容量。
3. 若連線失敗，先檢查接線、供電與晶片支援；不要改寫 Option Bytes，也不要嘗試 Mass Erase、Read Unprotect 或 Recovery。
4. 在 Memory & File editing 檢視中，使用該 MCU 資料手冊所列的 Flash 起點與容量讀取。許多 STM32 的 Flash 起點為 0x08000000，但必須以實際型號確認。不要將這個位址套用到 Nordic。
5. 將讀取結果儲存成獨立的二進位備份，記錄讀取範圍、日期與雜湊；使用十六進位檢視器的 ASCII 搜尋功能尋找與車身標籤一致的序號。找到外觀相似字串不足以證明它就是有效序號，需與標籤或車端回覆交叉比對。
6. 中斷探針、恢復正常接線，於 App 輸入核對後的序號；App 仍須完成車端配對確認。

介面名稱及唯讀功能請以 [STM32CubeProgrammer 使用手冊 UM2237](https://www.st.com/resource/en/user_manual/dm00403500-stm32cubeprogrammer-software-description-stmicroelectronics.pdf) 為準。解除讀取保護可能觸發整片 Flash 清除；例如 STM32F10xxx 的 [PM0075](https://www.st.com/resource/en/programming_manual/pm0075-stm32f10xxx-flash-memory-microcontrollers-stmicroelectronics.pdf) 明確描述這項行為，無法讀取時應停止，而不是解除保護。

序號與配對金鑰儲存在 Android Keystore 保護的加密偏好設定；請勿把整份 Flash 備份、序號或配對金鑰貼到公開問題回報。
