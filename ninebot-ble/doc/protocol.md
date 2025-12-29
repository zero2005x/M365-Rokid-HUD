# Protocol Documentation | 協議文件

---

## Overview | 概述

**English:**  
The protocol uses Elliptic Curve Diffie-Hellman (ECDH) key exchange, HMAC-based extract-and-expand key derivate (HKDF) and SHA-1 + SHA-256 cryptographic hash functions and AES block cipher with CBC-MAC (AES-CCM) for encryption/decryption.

**中文：**  
本協議使用橢圓曲線 Diffie-Hellman（ECDH）金鑰交換、基於 HMAC 的擴展與提取金鑰派生（HKDF）、SHA-1 + SHA-256 雜湊函式，以及 AES 區塊加密搭配 CBC-MAC（AES-CCM）進行加密/解密。

---

## Cryptographic Components | 加密元件

| Component      | Algorithm              | Description (EN)                        | 描述 (中文)            |
| -------------- | ---------------------- | --------------------------------------- | ---------------------- |
| Key Exchange   | ECDH (SECP256R1/P-256) | Elliptic curve key exchange             | 橢圓曲線金鑰交換       |
| Key Derivation | HKDF-SHA256            | Derive multiple keys from shared secret | 從共享密鑰派生多個金鑰 |
| Authentication | HMAC-SHA256            | Message authentication                  | 訊息認證               |
| Encryption     | AES-128-CCM            | Authenticated encryption                | 認證加密               |

---

## Registration | 註冊

**English:** Registration is a one-time process to obtain the authentication token.

**中文：** 註冊是一次性的過程，用於取得認證 Token。

### Steps | 步驟

1. **Generate ECDH Key Pair | 產生 ECDH 金鑰對**

   - Client (Alice) generates a key pair using SECP256R1 curve
   - 客戶端使用 SECP256R1 曲線產生金鑰對

2. **Key Exchange | 金鑰交換**

   - Alice exchanges public keys with the scooter (Jay/Bob)
   - Alice 與滑板車交換公鑰

3. **Generate Ciphertext | 產生密文**

   ```
   shared_secret = ECDH(jay_public_key, alice_private_key)
   derived_key = HKDF(shared_secret, info="mible-setup-info")

   token    = derived_key[0:12]    # 12 bytes - SAVE THIS!
   bind_key = derived_key[12:28]   # 16 bytes
   a_key    = derived_key[28:44]   # 16 bytes

   did_ct = AES-CCM(a_key, jay_remote_info[4:], nonce, aad="devID")
   ```

   - **Important | 重要：** Token must be saved for later usage! | Token 必須儲存供後續使用！

4. **Send DID and Confirm | 發送 DID 並確認**
   - Alice sends the ciphertext (did_ct) to Jay
   - Scooter responds with AUTH_OK or AUTH_ERR
   - Alice 將密文發送給滑板車，滑板車回應 AUTH_OK 或 AUTH_ERR

---

## Login | 登入

**English:** Login is required for every new connection to establish an encrypted session.

**中文：** 每次新連線都需要登入以建立加密會話。

### Steps | 步驟

1. **Generate Random Key | 產生隨機金鑰**

   - Alice generates a 16-byte random key
   - Alice 產生 16 位元組的隨機金鑰

2. **Exchange Random Keys | 交換隨機金鑰**

   - Alice sends random key to scooter
   - Scooter responds with its random key and remote_info (32 bytes)
   - Alice 發送隨機金鑰給滑板車，滑板車回應其隨機金鑰和 remote_info

3. **Derive Session Keys | 派生會話金鑰**

   ```
   salt = alice_random_key + jay_random_key
   salt_inv = jay_random_key + alice_random_key

   derived = HKDF(token, salt, info="mible-login-info")

   dev_key = derived[0:16]    # For decrypting scooter messages
   app_key = derived[16:32]   # For encrypting client messages
   dev_iv  = derived[32:36]   # IV for decryption
   app_iv  = derived[36:40]   # IV for encryption

   info = HMAC(app_key, salt)                    # Send to scooter
   expected_remote_info = HMAC(dev_key, salt_inv) # Verify from scooter
   ```

   - **All keys and IVs need to be saved! | 所有金鑰和 IV 都需要儲存！**

4. **Validate and Confirm | 驗證並確認**
   - Verify that received remote_info matches expected_remote_info
   - Send calculated info to scooter
   - Scooter responds with LOGIN_OK or LOGIN_ERR
   - 驗證收到的 remote_info 與預期相符，發送計算的 info 給滑板車

---

## UART Communication | UART 通訊

**English:** After successful login, the client can access the UART service with encrypted communication.

**中文：** 登入成功後，客戶端可以使用加密通訊存取 UART 服務。

### Message Encoding (Client → Scooter) | 訊息編碼（客戶端 → 滑板車）

```
nonce = app_iv + [0x00, 0x00, 0x00, 0x00] + message_counter (little-endian)
ciphertext = AES-CCM-Encrypt(app_key, plaintext, nonce)
```

### Message Decoding (Scooter → Client) | 訊息解碼（滑板車 → 客戶端）

```
nonce = dev_iv + [0x00, 0x00, 0x00, 0x00] + message_counter (little-endian)
plaintext = AES-CCM-Decrypt(dev_key, ciphertext, nonce)
```

### Counter Management | 計數器管理

- Message counter increments with each message | 訊息計數器隨每則訊息遞增
- Counter is 4 bytes, little-endian | 計數器為 4 位元組，小端序
- Separate counters for TX and RX | 發送和接收各有獨立計數器

===================================================

adv setup
Advertisement bluetooth

name MIScooterXXXX
CustomAD ff4e422000000000df
Scan resp
UUID128Complete 6e400001b5a3f393e0a9e50e24dcca9e

The nordic serial bluetoot service described here is used

https://devzone.nordicsemi.com/documentation/nrf51/6.0.0/s110/html/a00066.html

## Frames are sent in the RX and the answer is obtained in the tx

MIhome APP operation

when starting ask the scooter: 10,1a,67,17
-serial
-version firmware
-version bms
-something I don't know what var104 is
-password
from then on ask the scooter cyclically 3a,25,b0
seconds of this trip,
meters of this trip
remaining km
battery, speed, average speed, total km, temperature...
When opening the menu ask the scooter 7c,7d,7b,76
cruise status
Rear led status
Regenerative braking status
battery information

---

data frames

// +---+---+---+---+---+---+---+---+---+
// |x55|xAA| L | D | T | c |...|ck0|ck1|
// +---+---+---+---+---+---+---+---+---+

ck0, ck1 checksum of bytes from l to end of data (...)
(the sum of the bytes starting from L except ck0 and ck1) XOR 0xFFFF
CK0=least significant byte of the result
CK1=most significant byte of the result
... refers to the data or parameters, sends the least significant byte first.
L=amount of data (...)+2
D=device 0x20=master to scooter, 0x23=scooter to master ,0x22=master to battery , 0x25=battery to master
34 / 5,000
Translation results
T=type 0x01=read 0x03=write
--the notify (tx) does not change so after writing something it asks to confirm the change of that value

Scooter serial

55aa 03 2001 10 0e bdff ---C 0x10 = 16, Param 0x0e=14 -serial
55aa 10 2301 10 3132 3334 352f 3132 3334 3536 3738 ---Var16-22=serial(string, ascii)12345/12345678
1efd

---

Version del firmware

55AA 03 2001 1A 02 BFFF ---C 0x1a= 26,param 2
55aa 04 2301 1a 3401 88ff ---Var26=version=01.3.4

---

Posiblemente la version del BMS

55AA 03 2001 67 04 70FF ---C 0x67= 103,param 4
55aa 06 2301 67 1501 7100 e7fe ---Var103=¿bms?=01.1.5 var104=¿?=0x71=113

---

Código pin (este codigo es totalmente inutil)

55aa 03 2001 17 06 beff ---C 0x17 = 23, Param 6 -pin
55aa 08 2301 17 31:32:33:34:35:36 87fe ---Var23-25=pin(string, ascii)123456
escritura
55aa 08 2003 17 31:32:33:34:35:36 88fe

---

Información sobre este viaje

55aa 03 2001 3a 04 9dff ---C 0x3a= 58,param 4
55aa 06 2301 3a 7b02 0a00 14ff ---Var58=¿segundos-esteviaje?=0x027b=635= 10min:35seg
---Var59=¿metros-viaje?=0x000a=10m

55aa 06 2301 3a 7c02 0a00 13ff ---Var58=¿segundos-esteviaje?=0x027b=635= 10min:36seg
---Var59=¿metros-viaje?=0x000a=10m

55AA 06 2301 3A 3A01 0000 60FF ---Var58=¿segundos-esteviaje?=0x013a=314= 5min:14seg
---Var59=¿metros-viaje?=0x0000=0m

55AA 06 2301 3A A201 0000 F8FE ---Var58=¿segundos-esteviaje?=0x01a2=418= 6min:58seg
---Var59=¿metros-viaje?=0x0000=0m

---

Km restantes

55aa 03 2001 25 02 b4ff ---C 0x25= 37,param 2
55aa 04 2301 25 2607 85ff ---Var37=¿KMrestantes/10?=0x0726=1830=18.3km

---

batería, velocidad, temperatura, etc

55aa 03 2001 b0 20 0bff --3 ---C 0xb0= 176,param 20
55aa 22 2301 b0 0000 0000 0000 0000 3d00 0000 5046 ---Var176=¿error?=0x0000
8a08 0000 0500 7c02 1801 0000 0000 0000 0000 08fd ---Var177=¿warning?=0x0000
malformed -en esta trama envia un paquete vacio ---Var178=¿flags?=0x0000=¿?
---Var179=¿workmode?=0x0000
---Var180=%batt=0x003d=61%
---Var181=¿velocidad metros/h?=0x0000=0km/h
---Var182=¿velocidad prom m/h?=0x4650=18km/h
---Var183-184=m-total=0x0000088a=2.1km
---Var185=¿?=0x0005=5
---Var186=¿?=0x027c=636
---Var187=temp\*10=0x0118=28°C
---var188-191=0

55aa 22 2301 b0 0000:0000:0000:0000:3d00:0000:5046 --igual que el anterior var176-182
8a08:0000:0500:7402:1801:0000:0000:0000:0000:10fd ---Var183-184=m-total=0x0000088a=2.1km
---Var185=¿?=0x0005=5
---Var186=¿?=0x0274=628
---Var187=temp\*10=0x0118=28.0°C
---var188-191=¿?=0

55AA-22-2301-B0-0000-0000-0000-0000-2F00-0000-0000 --igual que el anterior var176-179
8665-0000-0000-1002-2C01-0000-0000-0000-0000-B0FD ---Var180=%batt=0x002f=47%
---Var181-182 =0
---Var183-184=m-total=0x00006586=25.99km
---Var185=¿?=0x0000=0
---Var186=¿?=0x0210=528
---Var187=temp\*10=0x012c=30.0°C

55AA-22-2301-B0-0000-0000-0000-0000-6300-0000-0000 ---igual que el anterior var176-179
8665-0000-0000-5200-2201-0000-0000-0000-0000-46-FD ---Var180=%batt=0x002f=99%
---Var181-182 =0
---Var183-184=m-total=0x00006586=25.99km
---Var185=¿?=0x0000=0
---Var186=¿?=0x0052=82 -tiene pinta de ser los segundos desde que se encendio
---Var187=temp\*10=0x0122=29.0°C

---

crucero
lectura
55aa 03 2001 7c 02 5dff ---C 0x7c= 124,param 2
55aa:04:2301 7c 0100 5aff ---Var124=cruise=0x0001=encendido
escritura
55aa:04:2003:7c:0100:5bff ---Escritura Var124=cruise on-param 0x0001
55aa:04:2003:7c:0000:5cff ---cruise off

---

led
lectura
55aa:03:2001:7d:02:5cff ---C 0x7d= 125,param 2
55aa:04:2301:7d:0000:5aff ---Var125=led=0x0000=apagado
escritura
55aa 04 2003 7d 0200 59ff ---Escritura Var125=led 0x0002=encendido
55aa 04:2003 7d:0000:5bff --led apagado

---

frenada regenerativa
lectura
55aa:03:2001:7b:02:5eff ---C 0x7b= 123,param 2
55aa:04:2301:7b:0000:5cff ---Var123=frenadaReg=0x0000=weak
escritura
55aa:04:2003:7b:0100:5cff ---escritura Var123=frenadaReg 0x0001=medium
55aa:04:2003:7b:0200:5bff ---0x0002=strong
55aa:04:2003:7b:0000:5dff ---0x0000=weak

---

no se---relacionado con la batería?
55aa:03:2001:69:02:70ff
55aa:04:2301:69:0000:6e:ff

---

55aa:03:2001:17:16:aeff --pregunta por la contraseña y version, errores y advertencias

55:aa:18:2301:17:31:32:33:34:35:36 3401:0000:0000:0000
0000:0000:0000:0000:42fe

---

55:aa:03:2001:3e:02:9bff ---esta es una ¿temperatura?
55:aa:04:2301:3e:1801:80ff ---28°

---

55aa:03:2001:73:04:64ff ---velocidad limite????
55aa:06:2301:73:204e:1027:bd:fe 0x4e20=20000 0x10000

---

---

Batería
Serial en ascii, algo ¿fecha? , capacidad 1e78=7800
55aa:03:2201:10:12:b7:ff
55aa:14:2501:10:33:4c 41 42 41 54 54 44 45 43 41 4d 49 4c 4f
15:01:78:1e:e0fa

---

no se ¿mAh recargados en el patinete?
55aa:03:2201:20:06:b3ff
55aa:08:2501:20:a2:22:00:00:00:00:edfe

---

¿numero de ciclos y cargas?
55aa:03:2201:1b:04:baff
55aa:06:2501:1b:01:00:03:00:b4ff

---

Fields 0x31 - 0x35
pregunta 10 cosas, mAh en la batería, porcentaje, current in A /100, voltaje de la batería, temperatura
55aa:03:2201:31:0a:9eff
55aa:0c:2501:31:361e:6300:0100:0910:3131:69fe

---

no se
55aa:03:2201:3b:02:9cff
55aa:04:2501:3b:6200:38ff

---

Voltajes de las celdas

55aa:03:2201:40:1e:7bff
55aa:20:2501:40:0210:0a10:0b10:0910:0610:0d10:0e10 --pack1=0x1002=4.098v, pack2=0x100a=1.106v..pack10=0x100f=4.111v
0d10:0f10:0710:00:00:00:00:00:00:00:00:00:00:75:fe

---
