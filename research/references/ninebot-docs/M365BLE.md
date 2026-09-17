## Xiaomi M365 BLE (Head board)
### Hardware
 Ref | Name | Manufacturer | Function
-----|------|--------------|---------
 U1 | nRF51822 | Nordic Semiconductor | Cortex-M0 MCU with BLE
 U1 | ? | ? | 3.3V LDO "L323"
 U4 | ? | ? | CC step-up white LED driver (5V->up to 36V)

### U1 LDO alternatives
SGM2019-3.3 "YJ33"

### U4 alternatives
The U4 wasn't identified yet. The list below contains pin- and Vref-compatible alternatives:

 Name | Marking | Tested
------|---------|-------
 AP3032 | GJL | +
 HT7938 | 7938 | -
 HT7939 | 7939 | -
 HT7939A-1 | 39A-1 | -
 HT7939A-2 | 39A-2 | -
 DIO5661 | 61YW | -
 SP6699 | PB | -
 MT9201 | B9HBxx | + Used in Aliexpress clones
 WD3139 | B39F | -
 SGM3732 | SKAxx | -
 MP3302 | ? | -

### MCU I/O ports
 I/O  | Pin | Mode | Periph | Function 
----- | --- | ---- | ------ | --------
 AIN3 |  6  | AIN  | ADC    | Throttle position sensor
 AIN4 |  7  | AIN  | ADC    | Brake position sensor
 AIN5 |  8  | AIN  | ADC    | ACH3
 AIN6 |  9  | AIN  | ADC    | ACH0
 P0.09 | 15 | O | GPIO | Beeper
 P0.10 | 16 | O | GPIO | Headlight
 P0.21 | 40 | O | GPIO | Bar LED 1 green
 P0.29 | 48 | O | GPIO | Bar LED 1 white
 P0.00 | 4 | O | GPIO | Bar LED 2
 P0.30 | 3 | O | GPIO | Bar LED 3
 P0.14 | 20 | O | GPIO | Bar LED 4
 P0.15 | 21 | IO | UART | Communication bus

       