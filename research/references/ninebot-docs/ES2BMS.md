## Ninebot ES2 BMS (battery management system)

### Hardware
Whole bms is split into two separate boards, upper part is low-power and consists of
* ST STM8L151K6T6 MCU
* TI BQ7693003 Li battery AFE
* Ricoh R5434D40xA Li battery secondary protector (earlier models)
* 3x 1.5mm pitch double row 8 pin connector

Second part consists mostly of charge/discharge mosfets circuitry


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
51 | 02 | Config straps?
70 | 0C | Activation data (MCU UID copy)

#### 30 Status register
 Bit | Description
---- | -------
 0 | config valid
 1 | battery activated
 2 | battery charge protection
 3 | chraging enabled
 4 | register write lock
 5 | is discharging
 6 | is charging
 7 | is charger inserted
 8 | discharge error
 9 | overvoltage detected
10 | overheat
11 | n/a
12 | n/a
13 | charge error
14 | deep UV error
15 | n/a
