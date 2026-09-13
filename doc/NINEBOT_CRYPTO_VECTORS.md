# NinebotCrypto 固定向量

來源為 scooterhacking/NinebotCrypto 的 C++ 實作，版本 9ba0c551488a8433cea173d1bed843240f63ec30。測試資料位於 ninebot-ble/tests/fixtures/ninebot_pairing.txt。

以 MSVC 編譯原始 NinebotCrypto.cpp，僅將標頭中的 private 改成 public 供測試注入車端狀態；AES-ECB 與 SHA-1 相依函式以 Windows BCrypt 包裝。沒有改動封包、金鑰推導、標籤或計數器算法。此測試工具只在 build/crypto-vectors 下執行，不隨應用程式發佈。

固定 BLE 名稱為 NBSCOOTER，序號 N4GSD123456789，BLE 隨機值為 00 至 0F，App 隨機值為 F0 至 FF。fixture 第一列為初始金鑰，其餘每列為方向、明文框架與加密框架。序列涵蓋 0x5B、0x5C、0x5D 與一般讀取命令，包含兩次金鑰變更。

Rust 測試逐幀比對 C++ 結果，並逐位元組破壞接收框架以驗證拒絕損毀資料、保留狀態、拒絕重播、短封包及變更配對隨機值。這是軟體互通性測試，不是實車驗證。

## 車輛會話向量

同目錄的 xiaomi_vehicle.txt（序號 21886/12345678）與 g30_vehicle.txt（序號 N4GSD123456789）另外涵蓋 ESC 序號查詢、遙測與鎖定。兩者使用同一組固定名稱與隨機值；Xiaomi 速度比例為 /1000，G30 為 /10。Android 主機 JNI 測試亦讀取 Xiaomi 向量，驗證配對 handle 移轉與車輛會話。

## 重現方法

測試工具原始碼已保存在 ninebot-ble/tests/reference/。generate_pairing.cpp 與 generate_vehicle.cpp 注入車端狀態，aes.hpp 與 sha1.h 提供 BCrypt 包裝。上游演算法須另外從前述固定版本取得並遵守其授權；不複製進本專案的產品程式。

在 Developer PowerShell 中，從專案根目錄執行以下命令。若防毒攔截測試工具，停止執行並交由防毒查核，不加入排除或改名重試。

```powershell
$vectorRoot = Join-Path (Get-Location) 'build/vector-reproduction'
New-Item -ItemType Directory -Force -Path $vectorRoot | Out-Null
git clone https://github.com/scooterhacking/NinebotCrypto.git "$vectorRoot/upstream"
git -C "$vectorRoot/upstream" checkout --detach 9ba0c551488a8433cea173d1bed843240f63ec30
Copy-Item "$vectorRoot/upstream/C++/NinebotCrypto.cpp" "$vectorRoot/NinebotCrypto.cpp"
$vectorHeader = Get-Content -Raw "$vectorRoot/upstream/C++/NinebotCrypto.h"
Set-Content -Encoding utf8 "$vectorRoot/NinebotCrypto.h" ($vectorHeader -replace 'private:', 'public:')
Copy-Item ninebot-ble/tests/reference/* $vectorRoot
Push-Location $vectorRoot
cl /nologo /EHsc /std:c++17 /I. generate_pairing.cpp NinebotCrypto.cpp /Fegenerate_pairing.exe /link bcrypt.lib
if ($LASTEXITCODE -ne 0) { throw '配對向量工具編譯失敗' }
cl /nologo /EHsc /std:c++17 /I. generate_vehicle.cpp NinebotCrypto.cpp /Fegenerate_vehicle.exe /link bcrypt.lib
if ($LASTEXITCODE -ne 0) { throw '車輛向量工具編譯失敗' }
./generate_pairing.exe | Set-Content -Encoding ascii ninebot_pairing.txt
./generate_vehicle.exe 21886/12345678 | Set-Content -Encoding ascii xiaomi_vehicle.txt
./generate_vehicle.exe N4GSD123456789 | Set-Content -Encoding ascii g30_vehicle.txt
Pop-Location
foreach ($vectorName in 'ninebot_pairing.txt', 'xiaomi_vehicle.txt', 'g30_vehicle.txt') {
    $expected = Get-Content "ninebot-ble/tests/fixtures/$vectorName"
    $actual = Get-Content "$vectorRoot/$vectorName"
    if (Compare-Object $expected $actual) { throw "向量不符：$vectorName" }
}
```

比較以文字列為準，忽略 Windows／Unix 換行差異。一般測試只讀取 fixture，不需執行 C++ 工具。
