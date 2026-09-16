### Xiaomi
#### Vehicle network
Upper is master to lower  

Phone  
|  
Bluetooth LE  
|  
[[BLE|M365BLE]]  
|  
Single wire half-duplex UART 115200 8n1  
|  
[[ESC|M365ESC]]  
||  
Two wire UART 115200 8n1  
||  
[[BMS|M365BMS]]

#### Packet
55 AA bLen bAddr bCmd bArg bPayload[bLen-2] wChecksumLE

* bLen - length of <bCmd bArg bPayload[]> part (length of payload plus 2)
* bAddr:
  * 0x20 - request to ESC
  * 0x21 - request to BLE
  * 0x22 - request to BMS
  * 0x23 - reply from ESC
  * 0x24 - reply from BLE
  * 0x25 - reply from BMS
  * 0x01 - ?
  * 0xFF - ?
* bCmd - command, see below
* bArg - argument, depends on command type
* bPayload[] - optional command payload, can be empty
* wChecksumLE - little-endian checksum, 0xFFFF xor (16-bit sum of bytes <bLen bAddr bCmd bArg bPayload[]>)

### Ninebot
#### Vehicle network
Upper is master to lower  

Phone  
|  
[[BLE|ES2BLE]]  
|  
Single wire half-duplex UART 115200 8n1  
|  
[[ESC|ES2ESC]] ==Two wire UART 115200 8n1== [[External BMS|ES2BMS]]  
||  
Two wire UART 115200 8n1  
||  
[[Internal BMS|ES2BMS]]

#### Packet
5A A5 bLen bSrcAddr bDstAddr bCmd bArg bPayload[bLen] wChecksumLE

* bLen - length of bPayload
* bSrcAddr - source address:
  * 0x20 - ESC
  * 0x21 - BLE
  * 0x22 - BMS
  * 0x23 - External BMS (ESC translates it to/from 0x22 but forwards to/from external port)
  * 0x00 - ? - ESC handles it as 0x20
  * 0x01 - ?
  * 0x3D, 0x3E, 0x3F - Application
* bDstAddr - destination address: same as above
* bCmd - command, see below
* bArg - argument, depends on command type
* bPayload[] - optional command payload, can be empty
* wChecksumLE - little-endian checksum, 0xFFFF xor (16-bit sum of bytes <bSrcAddr bDstAddr bCmd bArg bPayload[]>)

### Commands
| bCmd | bArg | bPayload[] | BLE | ESC | BMS | Model | Response bCmd bArg bPayload[] | Description |
| ---- | ---- | ---------- | --- | --- | --- | ----- | ----------------------------- | ----------- |
| 01   | ofs  | [0] - len  |  +  | +   | +   | Xiaomi | 01 ofs data[len] | Read register(s). ofs is in 16-bit words|
| 01   | ofs  | [0] - len  |  +  | +   | +   | Ninebot | 04 ofs data[len] | Read register(s). ofs is in 16-bit words|
| 02   | ofs  | data[]     | -   | +   | +   | Xiaomi | 02 ofs bResult | Write register(s) |
| 02   | ofs  | data[]     | -   | +   | +   | Ninebot | 05 bResult - | Write register(s) |
| 03   | ofs  | data[]     | -   | +   | +   | All | None | Write register(s) w/o response |
| 05   | ofs_l| [0] ofs_h, [1] len| - | + | - | All | 05 ofs_l {ofs_h,data} | Read registers extended |
| 07 | any | dUpdateSize | - | + | + | Xiaomi | 07 bResult - | Start firmware update |
| 07 | any | dUpdateSize | + | - | - | Xiaomi | 07 bResult bDummy | Start firmware update |
| 07 | any | dUpdateSize | + | + | + | Ninebot | 0B bResult - | Start firmware update |
| 08 | idx | data[] | - | + | + | Xiaomi | 08 bResult - | Write update |
| 08 | idx | data[] | + | - | - | Xiaomi | 08 bResult bDummy | Write update |
| 08 | idx | data[] | + | + | + | Ninebot | 0B bResult - | Write update |
| 09 | any | dChecksum | - | + | + | Xiaomi | 09 bResult - | Finish update |
| 09 | any | dChecksum | + | - | - | Xiaomi | 09 bResult bDummy | Finish update |
| 09 | any | dChecksum | + | + | + | Ninebot | 0B bResult - | Finish update |
| 0A | any | - | + | + | + | - | All | Reboot after update |
| 18 | 10 | bNewSN[], dAuth | - | + | + | All |  18 bResult - | Program serial number |
| 50 | any | bDevName[] | + | + | - | - | All | Set device name |
| 52 | ? | ? | - | - | + | 01 30 wStatus | All | Refresh status |
| 54 | ? | ? | - | - | + | All | - | ? |
| 55 | ? | ? | - | - | + | All | 55 30 {regs 30-35} | ? |
| 57 | any | wValue | - | - | + | ? | All | write reg 21 |
| 57 | any | dAuth0, dAuth1, dReg69 | - | + | - | All | - | Activate (with speed limit) |
| 58 | ? | ? | - | - | + | All | ? | Factory reset |
| 58 | any | dAuth0, dAuth1, wUnused | - | + | - | All | - | Factory reset (total) |
| 59 | ? | ? | - | + | - | All | ? | Activate (without limit) |
| 5A | 'N' | 'S' | - | + | - | All | - | User reset (pin and settings) |
| 5C | any | dAuth, dOdometer, wUnused | - | + | - | All | - | Set odometer |
| 61 | ofs | bReadLen, bHeadDataLen, bHeadData[bHeadDataLen] | - | + | - | All | 01 ofs data[] | Read regs, update head inputs |
| 64 | any | bDataLen, bThrottle, bBrake, bIsUptatingBLEFw, bIsBeeping [, wBLEFwVersion] | - | + | - | All | see below | Update head inputs and request outputs |
| 64 | any | bFlags, bBattLevelBars, bHeadlightLevel, bBeeps | + | - | - | M365 | - | Update head outputs |
| 64 | any | bFlags, bBattLevel, bHeadlightLevel, bBeeps, bSpeed, bErrorCode | + | - | - | Ninebot, M365 Pro | - | Update head outputs |
| 65 | any | bDataLen, bThrottle, bBrake, bIsUptatingBLEFw, bIsBeeping [, wBLEFwVersion] | - | + | - | All | - | Update head inputs |
