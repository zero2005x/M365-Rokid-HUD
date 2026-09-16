## Xiaomi M365 ESC (motor controller)
### Hardware
 Ref | Name | Manufacturer | Function
-----|------|--------------|-------------
 U3 | TPS563200 | TI | DC-DC step-down converter (12V->5V)
 U4 | TPS54160A | TI | DC-DC step-down converter (VBAT->12V)
 U5 | SPX3819M5-L-3-3 | Exar | 3.3V LDO (5V->3V)
 U6 | STM32F103C8T6 | ST | Cortex-M3 MCU

### MCU I/O ports
 I/O  | Pin | Mode | Periph | Function 
----- | --- | ---- | ------ | --------
 PD1  |  6  | OPP  | -      | LED      
 PA2  | 12  | AIN  | ADC1   | VBatt
 PA0  | 10  | AIN  | ADC1   | ESC temperature NTC
 PA15 | 38  | AFOD | TIM2   | Tail light
 PC14 | 3   | INF  | -      | Power button
 PC15 | 4   | OPP  | -      | PS_HOLD
 PB4  | 40  | INF  | TIM3   | HALL_A
 PB5  | 41  | INF  | TIM3   | HALL_B
 PB0  | 18  | INF  | TIM3   | HALL_C
 PA8  | 29  | AFPP | TIM1   | MOTOR_HFET_A
 PA9  | 30  | AFPP | TIM1   | MOTOR_HFET_B
 PA10 | 31  | AFPP | TIM1   | MOTOR_HFET_C
 PB13 | 26  | AFPP | TIM1   | MOTOR_LFET_A
 PB14 | 27  | AFPP | TIM1   | MOTOR_LFET_B
 PB15 | 28  | AFPP | TIM1   | MOTOR_LFET_C
 PA3  | 13  | AIN  | ADC1/2 | IA
 PA4  | 14  | AIN  | ADC1/2 | IB
 PA5  | 15  | AIN  | ADC1/2 | IC
 PA6  | 16  | AIN  | ADC1/2 | VA
 PA7  | 17  | AIN  | ADC1/2 | VB (not used)
 PA9  | 19  | AIN  | ADC1/2 | VC (not processed
 PB6  | 42  | AFOD | USART1 | BLE RxTx, 115200, single wire half-duplex
 PB7  | 43  | INF  | USART1 | Not used
 PB11 | 22  | INF  | USART3 | BMS RxD, 115200
 PB10 | 21  | AFPP | USART3 | BMS TxD

### Memory map
 Addr | Name
----- | ----
08000000 | bootloader
08001000 | app
08008400 | update
0800F800 | app_config
0800FC00 | upd_config

### Error codes
 Code | Description
 ---- | ------------
 1 | Boot: UpdConfig.size invalid
 2 | Boot: Flash erase failed
 3 | Boot: Flash program failed
 4 | Boot: WriteUpdConfig failed
 5 | Boot: App MSP invalid
 6 | Boot: Update MSP invalid
 10 | No communication with BLE board
 11 | Motor phase A current out of range
 12 | Motor phase B current out of range
 13 | Motor phase C current out of range
 14 | Throttle handle position out of range
 15 | Brake handle position out of range
 18 | Motor Hall sensors stuck at 0/1
 21 | No communication with BMS
 22 | BMS config invalid
 23 | BMS not activated (has default S/N)
 24 | Supply voltage out of range
 27 | ESC config invalid
 28 | Motor overvoltage in idle
 29 | Motor voltage out of range
 35 | ESC not activated (has default S/N)
 39 | Battery overheat
 40 | ESC overheat
 
### Registers
Index | Size | Writable | Description
----- | ---- | -------- | -----------
 00 | 2 | - | Magic 0x515C
 0D | 2 | - | Motor phase A current
 0E | 2 | - | Motor phase B current
 0F | 2 | - | Motor phase C current
 10 | E | - | ESC serial number
 17 | 6 | + | Scooter PIN
 1A | 2 | - | ESC firmware version
 1B | 2 | - | Error code
 1C | 2 | - | Warning code
 1D | 2 | - | ESC status
 1F | 2 | - | ?
 22 | 2 | - | Battery level, %
 24 | 2 | - | Remaining mileage x0.8, km*100
 25 | 2 | - | Remaining mileage, km*100
 26 | 2 | - | Speed
 29 | 4 | - | Total mileage, m
 2F | 2 | - | Current mileage
 32 | 4 | - | Total run time
 34 | 4 | - | ? some run time
 3A | 2 | - | ? trip time
 3B | 2 | - | ? trip time
 3E | 2 | - | Frame temperature
 47 | 2 | - | ESC supply voltage (measured by ESC)
 48 | 2 | - | Battery voltage (from BMS)
 50 | 2 | - | Battery current (from BMS)
 53 | 2 | - | ?
 65 | 2 | - | Average speed
 67 | 2 | - | BMS firmware version
 68 | 2 | - | BLE firmware version
 69 | 2 | - | ?
 70 | 2 | + | Lock command (write 1 to lock)
 71 | 2 | + | Unlock command (write 1 to unlock)
 74 | 2 | + | ?
 75 | 2 | + | Eco mode
 78 | 2 | + | Reboot command (write 1 to reboot)
 79 | 2 | + | Powerdown command (write 1 to power off)
 7A | 2 | + | ?
 7B | 2 | + | KERS level
 7C | 2 | + | Cruise control enable
 7D | 2 | + | Tail light on
 B0 | 2 | - | Error code
 B1 | 2 | - | Warning code
 B2 | 2 | - | ESC status
 B3 | 2 | - | ?
 B4 | 2 | - | Battery level, %
 B5 | 2 | - | Speed, m/h
 B6 | 2 | - | Average speed
 B7 | 4 | - | Total mileage, m
 B9 | 2 | - | Trip distance, m*10
 BA | 2 | - | ?
 BB | 2 | - | Frame temperature
 BE | 2 | + | Previous warning code
 C6 | 2 | - | ?
 C8 | 2 | - | ?
 CA | 2 | - | ?
 CC | 2 | - | ?
 CE | 2 | - | ?
 DA | C | - | MCU UID copy (ESC activation)

