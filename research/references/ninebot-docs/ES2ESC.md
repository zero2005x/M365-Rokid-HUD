## Ninebot ES2 ESC (motor controller)
### Memory map
 Addr | Name
----- | ----
08000000 | bootloader
08001000 | app
0800E800 | update
0801C000 | app_config
0801F800 | upd_config

### MCU I/O ports
 I/O  | Pin | Mode | Periph | Function 
----- | --- | ---- | ------ | --------
 PA0  | 10  | AIN  | ADC1   | ESC temperature NTC - not installed
 PA1  | 11  | AIN  | ADC1   | Motor supply rail V
 PA6  | 16  | AIN  | ADC1   | Int battery V
 PA7  | 17  | AIN  | ADC1   | Ext battery V
 PB2  | 20  | AIN  | ?      | BOOT1 10k to GND
 PD0  |  5  | OPP  | -      | Ext battery FET
 PD1  |  6  | OPP  | -      | Int battery FET
 PB9  | 46  | AFOD | TIM2   | Tail light
 PB4  | 40  | IN   | TIM3   | HALL_A
 PB5  | 41  | IN   | TIM3   | HALL_B
 PB0  | 18  | IN   | TIM3   | HALL_C
 PB3  | 39  | AFPP | DMA1   | Bottom lights? J4.blue
 PA15 | 38  | OPP  | -      | LED
 PA8  | 29  | AFPP | TIM1   | MOTOR_H_A
 PA9  | 30  | AFPP | TIM1   | MOTOR_H_B
 PA10 | 31  | AFPP | TIM1   | MOTOR_H_C
 PB13 | 26  | AFPP | TIM1   | MOTOR_L_A
 PB14 | 27  | AFPP | TIM1   | MOTOR_L_B
 PB15 | 28  | AFPP | TIM1   | MOTOR_L_C
 PA11 | 32  | OPP  | -      | PS_HOLD
 PA12 | 33  | IN   | -      | Power button
 PA3  | 13  | AIN  | ADC1/2 | IA
 PA4  | 14  | AIN  | ADC1/2 | IB
 PA5  | 15  | AIN  | ADC1/2 | IC
 PA2  | 12  | AFOD | USART2 | BLE RxTx "port 1", 115200
 PB10 | 21  | AFPP | USART3 | INT BMS TxD, 115200, "port 0"
 PB11 | 22  | IN   | USART3 | INT BMS RxD
 PB6  | 42  | AFOD | USART1 | EXT BMS TxD, 115200, "port 2"
 PB7  | 43  | IN   | USART1 | EXT BMS RxD


### Error codes
 Code | Description
 ---- | ------------
 10 | No communication with BLE board
 11 | Motor phase A current out of range
 12 | Motor phase B current out of range
 13 | Motor phase C current out of range
 14 | Throttle handle position out of range
 15 | Brake handle position out of range
 18 | Motor Hall sensors stuck at 0/1
 19 | Internal battery voltage out of range
 20 | External battery voltage out of range
 21 | No communication with internal BMS
 22 | Internal BMS config invalid
 23 | Internal BMS not activated (has default S/N)
 24 | Supply voltage out of range
 27 | ESC config invalid
 32 | No communication with IoT device
 35 | ESC not activated (has default S/N)
 36 | Internal BMS - ???
 37 | Internal BMS - ???
 38 | Charging overcurrent
 39 | Internal battery overheat
 41 | External battery overheat
 42 | No communication with external BMS
 43 | External BMS config invalid
 44 | External BMS not activated (has default S/N)
 45 | Internal BMS deep cell discharge
 46 | External BMS deep cell discharge
 47 | Internal BMS - ???
 48 | External BMS - ???
 49 | Internal BMS firmware version mismatch
 50 | External BMS firmware version mismatch

### Registers
Index | Size | Permission | Description
----- | ---- | -------- | -----------
 00 | U16 | R | Magic 0x515C
 0D | U16 | R | Motor phase A current
 0E | U16 | R | Motor phase B current
 0F | U16 | R | Motor phase C current
 10 | 14xU8 | R | ESC serial number
 17 | 6xU8 | R/W | Scooter PIN
 1A | U16 | R | ESC firmware version
 1B | U16 | R | Error code
 1C | U16 | R | Alarm code
 1D | U16 | R | ESC status (boolean state word)
 1F | U16 | R | Current operation mode, 0 NORMAL; 1 ECO; 2 SPORT
 20 | U16 | R | Volume of storage battery 1
 21 | S16 | R | Volume of storage battery 2
 22 | U16 | R | Battery level, 0-100%
 24 | S16 | R | Remaining mileage x0.8, km*100 (unit:10m)
 25 | S16 | R | Predicted remaining mileage, km*100 (unit:10m)
 26 | S16 | R | Current speed, 0.1km/h
 29 | S32 | R | Total mileage, m
 2F | S16 | R | Current mileage,10m
 32 | S32 | R | Total operation time, sec
 34 | S32 | R | Total riding rime, sec
 3A | S16 | R | Single operation time, sec
 3B | S16 | R | Single riding time, sec
 3E | S16 | R | Frame temperature, 0.1C
 3F | S16 | R | Battery 1 temperature, 0.1C
 40 | S16 | R | Battery 2 temperature, 0.1C
 41 | S16 | R | MOS pipe temperature, 0.1C
 47 | S16 | R | ESC supply voltage (measured by ESC)
 48 | S16 | R | Battery voltage (from BMS)
 49 | S16 | R | Battery current (from BMS)
 50 | S16 | R | External battery temperature 2, 1C
 53 | S16 | R | Motor phase current, 0.01A
 65 | S16 | R | Average speed, 0.1km/h
 66 | U16 | R | External battery firmware version 
 67 | U16 | R | Internal battery firmware version
 68 | U16 | R | BLE firmware version
 70 | S16 | R/W | Lock command (write 1 to lock), scooter reset automatically
 71 | S16 | R/W | Unlock command (write 1 to unlock), scooter reset automatically
 72 | S16 | R/W | Speed limit or speed limit release
 73 | S16 | R/W | Speed limit value in normal mode, 0.1km/h 
 74 | S16 | R/W | Speed limit value in eco mode, 0.1km/h
 75 | S16 | R/W | Operating mode: 0 NORMAL; 1 ECO; 2 SPORT;
 77 | S16 | R/W | Start or shut down the engine
 78 | S16 | R/W | Reboot command (write 1 to reboot)
 79 | S16 | R/W | Powerdown command (write 1 to power off)
 7A | S16 | R/W | ?
 7B | S16 | R/W | KERS level
 7C | S16 | R/W | Cruise control enable
 7D | S16 | W | Tail light on
 B0 | S16 | R | Error code
 B1 | S16 | R | Alarm code
 B2 | S16 | R | ESC status (boolean state word)
 B3 | S16 | R | Volume of battery 1 and battery 2. Lower 8 for #1, higher for #2. 0-100%
 B4 | S16 | R | Battery level, 0-100%
 B5 | S16 | R | Current speed, 100m/h (1=0.1km/h)
 B6 | S16 | R | Average speed, 100m/h (1=0.1km/h)
 B7 | S32 | R | Total mileage, m
 B9 | S16 | R | Single mileage, m*10
 BA | S16 | R | Single operation time
 BB | S16 | R | Frame temperature, 0.1C
 BC | S16 | R | Current speed limit value, with the lower 8b for current and higher 8b for full range, 0.1km/h
 BD | S16 | R | Scooter power, unit:W
 BE | S16 | R | Previous alarm code (for delay reset)
 BF | S16 | R | Predicted remaining mileage, km*100 (unit:10m)
 C6 | S16 | ? | Display mode of chassis lamp strip
 C8 | 2xS16 | R/W | Color of chassis lamp strip 1 
 CA | 2xS16 | R/W | Color of chassis lamp strip 2
 CC | 2xS16 | R/W | Color of chassis lamp strip 3 
 CE | 2xS16 | R/W | Color of chassis lamp strip 4 
