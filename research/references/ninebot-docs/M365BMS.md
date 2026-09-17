## Xiaomi M365 BMS (battery management system)

### Hardware
* ST STM8L151K6T6 MCU
* TI BQ7693003 Li battery AFE
* Ricoh R5434D40xA Li battery secondary protector

### I/O ports
 I/O  | Pin | Mode | Function 
----- | --- | ---- | --------
PA5 | 5 | IN | ?
PB0 | 13 | OUT | Red LED
PB1 | 14 | OUT | Blue LED 
PB6 | 19 | IO | AFE SDA
PB7 | 20 | IO | AFE SCL
PC2 | 27 | ? | UART 115200
PC3 | 28 | ? | UART 115200
PC5 | 30 | IN | Config strap, goes to reg51.0
PC6 | 31 | IN | Config strap, goes to reg51.1

### Registers
Index | Size | Description
----- | ---- | -----------
00 | 02 | Magic 5A5A
10 | 0E | Serial number
17 | 02 | Firmware version
18 | 02 | Factory capacity
19 | 02 | Actual capacity (this is not charge level !)
1B | 02 | Charge full cycles
1C | 02 | Charge count
20 | 02 | Manufacture date
30 | 02 | [Status](#30-status-register)
31 | 02 | Remaining capacity, mAh
32 | 02 | Remaining capacity, %
33 | 02 | Current, x10mA, positive - discharging, negative - charging
34 | 02 | Voltage, x10mV
35 | 02 | bTemperature1:bTemperature2, Deg C, 0 is -20
36 | 02 | Balancing bitmap
3B | 02 | Health, %
40 | 02 | Cell 1 voltage, mV
41 | 02 | Cell 2 voltage, mV
42 | 02 | Cell 3 voltage, mV
43 | 02 | Cell 4 voltage, mV
44 | 02 | Cell 5 voltage, mV
45 | 02 | Cell 6 voltage, mV
46 | 02 | Cell 7 voltage, mV
47 | 02 | Cell 8 voltage, mV
48 | 02 | Cell 9 voltage, mV
49 | 02 | Cell 10 voltage, mV
51 | 02 | Config straps, .0 - PC5, .1 - PC6
70 | 0C | Activation data (MCU UID copy)

#### 30 Status register
 Bit | Description
---- | -------
 0 | config valid
 1 | reg50!=0 - ?
 2 | n/a
 3 | n/a
 4 | ArgFromCmd52 - ? 
 5 | n/a
 6 | IsCharging
 7 | MainState.field_A - ?
 8 | n/a
 9 | overvoltage
10 | overheat
11 | n/a
12 | n/a
13 | n/a
14 | n/a
15 | n/a
