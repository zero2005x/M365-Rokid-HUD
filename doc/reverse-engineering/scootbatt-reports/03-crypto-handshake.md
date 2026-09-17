# 03 — Cryptography and the authentication/authorisation handshake

**Target:** `com.basse.scootbatt` 1.9.2 (versionCode 136), R8-obfuscated, decompiled with jadx to
`/home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/` (paths below are relative to that directory
unless absolute).
**Method:** static analysis only. No scooter, no phone, no vendor network.
**Author:** crypto/handshake sub-agent. Everything below is read from the decompilation unless it is
explicitly marked *inferred* or *unverified*.

---

## 0. Naming warning — read this before anything else

The task brief assumed `XiaomiCrypto` is "a fixed 16-byte key and a per-command mix". **That is not what
the app's `XiaomiCrypto` is.** Scootbatt has four protocol arms, and the names do not mean what a
Ninebot/Xiaomi-protocol reader would expect:

| enum arm | ordinal | wire magic | what it actually is |
|---|---|---|---|
| `Ninebot` | 0 | `5A A5` | plaintext Ninebot protocol, 16-bit ones-complement checksum |
| `NinebotCrypto` | 1 | `5A A5` | **the nbcrypt scheme** — fixed 16-byte constant + SHA-1 key schedule + AES-ECB keystream + per-message counter. This is the arm M365-class *crypto* scooters get. |
| `Xiaomi` | 2 | `55 AA` | plaintext Xiaomi/MiBeacon-style protocol |
| `XiaomiCrypto` | 3 | `55 AB` | **the elliptic / Xiaomi-cloud arm** — ECDH P-256 + HKDF-SHA256 + AES-CCM, needs `beaconKey`/`deviceToken`/`deviceInfo` |

Evidence for the enum: `p000/xq0.java:26-34`
```java
xq0 xq0Var  = new xq0("Ninebot", 0);        // f34610a
xq0 xq0Var2 = new xq0("NinebotCrypto", 1);  // f34611c
xq0 xq0Var3 = new xq0("Xiaomi", 2);         // f34612d
xq0 xq0Var4 = new xq0("XiaomiCrypto", 3);   // f34613e
```

Arm selection (`com/basse/scootbatt/p005ui/fragments/scooter/ScooterFragment.java:900`):
```java
m3291j0.f19861b = scooter.getUseCrypto() ? xq0.f34611c          // NinebotCrypto
                : scooter.isXiaomi()     ? xq0.f34612d          // Xiaomi
                :                          xq0.f34610a;         // Ninebot
```
and `ScooterFragment.java:1205` (`mo3236g`, the Xiaomi-service path) hard-sets `xq0.f34613e`
(`XiaomiCrypto`).

`useCrypto` and `isXiaomi` come from the *advertisement*, not from a model-name table:
`p000/C1142ow.java:32-141` parses manufacturer data at the marker `FF 4E 42`
(`bArr2 = {-1, 78, 66}`), requires the 6 bytes at that offset to have `[3]==0 && [4]==0`, and then:
* byte `[0]` = model code → table at `C1142ow.java:64-138` (`32`=`m365` isXiaomi=true, `33`=`esx`,
  `34`=`pro` isXiaomi=true, `40`=`pro2`, `37|43`=`1s`, `120`=`g65`, `-125`=`g2`, `127|-128|-127`=`f2` …),
* line `C1142ow.java:139`: `this.f22890c = copyOfRange[1] == 2;` → **`useCrypto`**.
`Scooter` is then built at `com/basse/scootbatt/p005ui/fragments/BluetoothScanFragment.java:761` with
`name = nx0Var.f21042e` (the BLE advertised name, default `"Scooter"`, `BluetoothScanFragment.java:757-760`).

**So: an M365 / Mi Pro / 1S that advertises manufacturer byte `[1] == 2` is driven with the
`NinebotCrypto` (nbcrypt) algorithm — not with `XiaomiCrypto`.** The `Xiaomi` plain arm is used for
Xiaomi-branded scooters *without* that flag.

There is also a fourth, orthogonal selector that overrides both: **BLE service discovery**, see §2.1.

### 0.1 Tooling caveats (important for anyone re-reading the decompilation)
* R8 merged unrelated classes into single files with a *discriminator field* and one constructor per
  original class. `p000/pe4.java` holds ~27 classes (`f23527a` is the discriminator: `9` = the protocol
  object, `6` = the elliptic-config store, `13` = a notification builder, …). `p000/C1375v6.java`,
  `p000/fm2.java`, `p000/sa6.java`, `p000/C1527z7.java`, `p000/hl2.java` are the same. Field names are
  meaningless; the *types* and the discriminator tell you which class you are in.
* jadx renders some integer constants using unrelated library field names. Everything named
  `org.spongycastle.jce.X509KeyUsage.digitalSignature` is `128`, `DESedeParameters.DES_EDE_KEY_LENGTH`
  is `24`, `yw5.zzm` is `21`, `JPAKEParticipant`-ish imports are noise. **These are not crypto calls.**
  Do not use the import histogram as a crypto inventory (I initially did; it is wrong).
* The base APK contains **no native libraries at all** (`unzip -l xapk/com.basse.scootbatt.apk | grep .so`
  → empty; the armeabi-v7a split only carries crashlytics/`androidx.graphics.path`/datastore stubs).
  **All protocol cryptography is Java/Kotlin and is fully visible in the decompilation.** There is no
  hidden native crypto step, and nothing in the handshake lives in a `.so`.

---

## 1. The frame container layer

### 1.1 Encoder — `p000/pe4.java:1034` `m12550k(byte[] raw)`
Input is always `ScooterRequest.construct()` = `[0x3E, direction, action, position, payload…]`
(`com/basse/scootbatt/models/scooter/helpers/ScooterRequest.java:67-112`; every `vp0.mo2827f()`
implementation returns exactly this, e.g. `p000/c70.java:36-44`, `p000/d83.java:15-24`).
Dispatch is on the protocol enum (`pe4.java:1038`).

| arm | source lines | output |
|---|---|---|
| `Ninebot` (0) | `pe4.java:1041-1056` | `5A A5 (rawLen-4) ‖ raw ‖ crc16` — `crc = ~Σ(bytes[2..len+2])`, little-endian |
| `NinebotCrypto` (1) | `pe4.java:1058-1141` | `5A A5 (rawLen-4) ‖ enc(raw) ‖ MIC(4) ‖ ctr(2)` (first message: `… ‖ 00 00 CRC16 00 00`) |
| `Xiaomi` (2) | `pe4.java:1143-1182` | `55 AA (rawLen-2) type act pos payload ‖ crc16` |
| `XiaomiCrypto` (3) | `pe4.java:1184-1295` | `55 AB (rawLen-2) ctr_lo ctr_hi ‖ AES-CCM(3-byte hdr ‖ payload ‖ rand4) ‖ tag(4) ‖ crc16` |

Frame-length identities (each verified against the reassembler's overhead, §1.2):
`Ninebot`: `lenByte = payloadLen`, `|frame| = lenByte + 9`.
`Xiaomi`: `lenByte = payloadLen + 2`, `|frame| = lenByte + 6`.
`XiaomiCrypto`: `lenByte = payloadLen + 2`, `|frame| = lenByte + 16`.

Cross-check: the reassembler's four hard-coded overheads (`hl2.java:34`, `:40`, `:44`, `:52` = 9, 13, 6,
16) are reproduced exactly by the four frame layouts above when the encoder is read literally —
independent code paths agreeing on the geometry, which is the strongest static confirmation available
here.

The `Xiaomi`/`XiaomiCrypto` "type" byte mapping (`pe4.java:1149-1168`) reproduces the direction, with a
z10 special case:
```java
byte b = bArr[1];                       // direction
if (b == 32) → 32 ; else if (33) → 33 ; else if (34) → 34 ;
else { b2 = bArr[0]; if (32) → 35; else if (33) → 36; else if (34) → 37; }
if (gy1.m6310f(this.f23528c, "z10")) type = 17;   // "z10" = scooter model
```
Observation (not crypto, but it bites a reimplementation): with `bArr[0]` always `0x3E` (=62) from
`construct()`, the `else` branch can never fire, so `MASTER_TO_EXTERNAL_BATTERY` (0x23) /
`MASTER_TO_BLE_READ` (0x24) requests on a Xiaomi-model scooter emit **type byte `0x00`**
(`pe4.java:1157-1165`). Directions `32/33/34` work because they hit the first three branches.

### 1.2 Reassembler (BLE chunking) — `p000/hl2.java:25-93`
`hl2(xq0 protocol, int mtuPayload)` stores the expected magic and an overhead:
```java
ordinal 0 → {90,-91} (5A A5), overhead 9
ordinal 1 → {90,-91} (5A A5), overhead 13   // hl2.java:37-40
ordinal 2 → {85,-86} (55 AA), overhead 6
ordinal 3 → {85,-85} (55 AB), overhead 16   // hl2.java:45-52
```
`m6915a(byte[] chunk)` accepts a notification only if `chunk[0..1] == magic`, then reassembles
`chunk[2] + overhead` bytes across multiple BLE notifications. **Note that `hl2` expects `55 AB` for
`XiaomiCrypto` (`hl2.java:50-51`), matching the encoder (`pe4.java:1246-1247`) — not `55 AA`.** MTU
payload size is `mtu - 3` (`p000/C0993mx.java`, `onMtuChanged`), default 20 (`C0993mx.java:94`,
`f19701r = 20`).

### 1.3 Xiaomi→internal response normaliser — `p000/oh3.java:393-437` `m11880i`
Converts a checksum-stripped `55 AA`/`55 AB` inner frame `[55,AA,len, type, act, pos, payload…]` into the
internal Ninebot-shaped frame `[5A,A5,len-8, 0x3E, dir, act, pos, payload…, crc16]`, using
`type 32→(3E,20)`, `33→(3E,21)`, `34→(3E,22)`, `35→(20,3E)`, `36→(21,3E)`, `37→(22,3E)`
(`oh3.java:404-423`). It recomputes the length as `|in| - 8` (`oh3.java:399`) — see §2.4 for why that is
consistent. Everything is then parsed by `ScooterFragment.m3299r0` (`ScooterFragment.java:1579-1594`),
which reads `len=bArr[2]`, `a=bArr[3]`, `b=bArr[4]`, `action=bArr[5]`, `pos=bArr[6]`,
`payload=bArr[7 … 7+len)`.

---

## 2. Per-family algorithm detail

### 2.1 Which arm a connection uses (the real selector)

`p000/C0993mx.java:450-509` walks the discovered GATT services:
```java
if (service == 6e400001-… (Nordic UART)) {
  if (service == 0000fe95-… (Xiaomi)) {          // C0993mx.java:465-472
     f19697n = TRUE;  Log "service elliptic uart";
     f19693j = NUS TX  6e400003   (read/notify)
     f19694k = NUS RX  6e400002   (write)
     f19695l = 00000010 (battery level, write)
     f19696m = 00000019 ("auth" characteristic, write+notify)
  }
  if (f19695l == null || f19696m == null) {      // C0993mx.java:473-474
     f19697n = FALSE; Log "service nrf uart";
     … pick write/read char by properties …
  }
}
```
Then after the CCCD write, `C0993mx.java:419-427`:
```java
if (f19697n.booleanValue())  bleForegroundService.mo3236g();   // Xiaomi elliptic path
else                         bleForegroundService.mo3233d();   // classic path
```
So **a scooter that exposes the Xiaomi service `0000fe95` next to NUS is always driven down the
`XiaomiCrypto` (mible/ECDH) path**, regardless of the model flags, and the handshake moves onto
characteristic `00000019` (§4.3). In that mode `onCharacteristicChanged` routes notifications by UUID
(`C0993mx.java:271-274` → `ScooterFragment.mo3234e`, `ScooterFragment.java:952-963`):
`6e400003` → NUS/elliptic sink, `00000010` → battery sink, `00000019` → auth sink.

UUID constants: `C0993mx.java:30-47` (`f19679u`=NUS RX, `f19680v`=NUS TX, `f19681w`=`0000fe95`,
`f19682x`=`00000010`, `f19683y`=`00000019`).

The BLE transport writes each 20-byte chunk with a 20 ms inter-chunk delay
(`p000/C1527z7.java:1271-1346`, `m18225w`), and `BleForegroundService.m3245p(byte[],UUID)`
(`services/BleForegroundService.java:567-640`) maps UUID→characteristic (`00000010`, `00000019`,
NUS RX).

---

### 2.2 `NinebotCrypto` — the fixed-key/per-command-mix arm (task item 2, and the real answer to item 1)

Implementation: **`p000/C1375v6.java`** (crypto state; constructor `C1375v6(String)` at
`C1375v6.java:1242-1253`) + **`p000/pe4.java:1058-1141`** (encrypt) + **`ScooterFragment.java:1074-1176`**
(`mo3235f`, decrypt).

#### Constants

`C1375v6.java:1245` — the fixed 16-byte key material (identical to `nbc_fw_data` in
`/home/kali/ScooterHacking/research/ninebotcrypto/nbcrypt.c:17`):
```java
byte[] bArr = {-105, -49, -72, 2, -124, 65, 67, -34, 86, 0, 43, 59, 52, 120, 10, 93};
//            0x97 0xCF 0xB8 0x02 0x84 0x41 0x43 0xDE 0x56 0x00 0x2B 0x3B 0x34 0x78 0x0A 0x5D
```
State fields (`C1375v6.java:26-42`, types are `Object` because of R8 merging):
`f31212a` = message counter (int), `f31213b` = the seed **String** (the BLE name),
`f31214c` = the 16-byte constant above, `f31215d` = 16-byte `ble_data`, `f31216e` = 16-byte `app_data`,
`f31217f` = 16-byte derived AES key.

#### Key schedule — `C1375v6.java:459-469` `m16086e(byte[] a, byte[] b)`
```java
byte[] buf = new byte[32];
System.arraycopy(a, 0, buf, 0, a.length);   // seed bytes (BLE name, UTF-8)
System.arraycopy(b, 0, buf, 16, b.length);  // 16-byte second half
MessageDigest md = MessageDigest.getInstance("SHA-1");
md.update(buf, 0, 32);
System.arraycopy(md.digest(), 0, (byte[]) this.f31217f, 0, 16);   // first 16 bytes of SHA-1
```
Constructor (`C1375v6.java:1250-1252`): `m16086e(name.getBytes(UTF_8), K_fw)`.
Instantiation: `ScooterFragment.java:906`, `:1211`, `:1226` → `new C1375v6(scooter.getName())`
(BLE advertised name, §0).

Effective definition (byte-exact for name lengths ≤ 16; for lengths ≥ 17 `arraycopy` at offset 16
overwrites the name tail, so it degenerates to `name[0:16] ‖ K_fw`):
**`K_init = SHA1( name ‖ 0x00… ‖ K_fw )[0:16]` over exactly 32 bytes of input.**

#### Primitives — `C1375v6.java:80-102`
```java
static byte[] m16072c(byte[] data, byte[] key) {        // C1375v6.java:80-87
    SecretKeySpec s = new SecretKeySpec(key, "AES");
    Cipher c = Cipher.getInstance("AES");               // JCE default = AES/ECB/PKCS5Padding
    c.init(1, s);                                        // ENCRYPT
    return c.doFinal(data);                              // 16-byte input → 32 bytes out
}
static byte[] m16073i(byte[] a, byte[] b) {              // C1375v6.java:91-102
    out[i] = a[i] ^ b[i];                                // a.length bytes
}
```
* **Cipher:** AES-128, **ECB, encryption direction only, single block**. Every caller is
  `System.arraycopy(m16072c(x, key), 0, dst, 0, 16)` (`pe4.java:1106`, `:1109`, `:1116`, `:1123`;
  `C1375v6.java:557`, `:588`), i.e. the extra PKCS#5 padding block that `"AES"` appends is always
  discarded. **Padding is therefore irrelevant/absent on the wire.** There is no GCM, no CCM and no
  authentication tag in this arm.
* **No IV**, no per-message IV generation, no nonce other than the counter block described below.

#### Keystream #1 (counter == 0) — `C1375v6.java:548-563` `m16090j(byte[] data)`
```java
ks = m16072c(K_fw /*f31214c*/, derivedKey /*f31217f*/);   // C1375v6.java:557, recomputed per 16-byte block
out[i] = data[i] ^ ks[i % 16];                            // C1375v6.java:558
```
A **fixed** 16-byte keystream block (`AES-ECB(K_fw, K)`) XORed repeatedly over the whole payload —
no counter, no chaining.

#### Keystream #n (counter != 0) — `C1375v6.java:567-594` `m16091k(int msgIt, byte[] data)`
```java
block[0] = 0x01;
block[1..4] = msgIt big-endian;      // C1375v6.java:573-577
arraycopy(ble_data /*f31215d*/, 0, block, 5, 8);   // C1375v6.java:578 — only 8 of 16 bytes used
block[15] = 0;                                     // C1375v6.java:579
for each ≤16-byte chunk:
    block[15] += 1;                                // C1375v6.java:585 — CTR over the last byte only
    ks = m16072c(block, derivedKey);               // C1375v6.java:588
    out = chunk ^ ks;                              // C1375v6.java:589
```
This is **AES-128-CTR-like with a 16-byte counter block** `01 ‖ msgIt(4, BE) ‖ ble_data[0:8] ‖ 00 00 00 ‖ ctr8`
where `ctr8` is a strictly per-16-byte-block byte increment that is **never reset between messages**
(it is a local zero each call — `C1375v6.java:570-579` — so it restarts at 1 for every message).

#### MIC ("CRC") for non-first messages — `pe4.java:1096-1131`
```java
tagBlock[0] = 89 (0x59);
tagBlock[1..4] = msgIt(BE);
arraycopy(ble_data, 0, tagBlock, 5, 8);
tagBlock[15] = (byte) payloadLen;             // pe4.java:1104 — payloadLen = raw command length
chain = AES-ECB(tagBlock, K);                 // pe4.java:1106
chain = AES-ECB( (5A A5 lenByte) ^ chain , K);        // pe4.java:1108-1109
for each ≤16-byte chunk of the *prefixed* frame:      // pe4.java:1112-1120
    chain = AES-ECB( chunk ^ chain , K );
micBlock[0] = 1; micBlock[15] = 0;            // pe4.java:1121-1122
mic = AES-ECB(micBlock, K)[0:4] ^ chain[0:4]  // pe4.java:1123-1125
```
i.e. a CBC-MAC-style 4-byte MIC over `5A A5 lenByte ‖ rawCommand`, keyed with the same derived key.
Emitted at `pe4.java:1128-1131`, followed by the counter low 2 bytes (`pe4.java:1132-1134`).

#### First-message variant — `pe4.java:1076-1092`
Counter 0 uses keystream #1 and a **2-byte** checksum instead of the 4-byte MIC:
`crc = ~(Σ raw command bytes)`, written at `[len+5],[len+6]` with four zero bytes around it
(`pe4.java:1085-1090`), counter bytes `00 00`. Then `counter++`.

#### Counter handling on receive — `ScooterFragment.java:1114-1150`
```java
int msgIt = ((frame[|f|-2] & 0xFF) << 8) + (frame[|f|-1] & 0xFF) + (counter & 0xFFFF0000);
byte[] src = frame[3 … |f|-7);         // ciphertext, payloadLen = |f|-9
if (msgIt == 0) { plain = m16090j(src); … }             // keystream #1, counter NOT touched
else {
    plain = m16091k(msgIt, src);
    …
    if (Integer.compare(counter ^ MIN_VALUE, MIN_VALUE ^ msgIt) > 0) counter = msgIt;  // unsigned counter > msgIt
    else counter++;
}
```
The `^ Integer.MIN_VALUE` trick implements an **unsigned compare**: resync backwards if the scooter's
counter is behind, otherwise advance locally.

---

### 2.3 `XiaomiCrypto` — the elliptic/cloud arm (task items 1 and 4)

This arm is **not** a fixed-key cipher. It is the Xiaomi *mible* local-connection handshake:
ECDH (P-256) → HKDF-SHA256 → AES-CCM, and it needs three per-device secrets.

#### 2.3.1 The three secrets — `com/basse/scootbatt/crypto/elliptic/ConfigurationElliptic.java`
```java
@bu3("beaconKey")   private final byte[] beaconKey;    // ConfigurationElliptic.java:11-12
@bu3("deviceInfo")  private final byte[] deviceInfo;   // :14-15
@bu3("deviceToken") private final byte[] deviceToken;  // :17-18
@bu3("ssid")        private final String ssid;         // :20-21
```
(`bu3` = Gson `@SerializedName`, so these are the JSON keys used on the wire between apps.)

Persisted in `SharedPreferences("app_prefs")` keyed by **SSID = the scooter's BLE MAC address**:
`ScooterFragment.m3296o0` (`ScooterFragment.java:1326-1355`) takes
`((Scooter)…).getBleDevice().getAddress()` and passes it as the lookup key to the `pe4` config store
(`p000/pe4.java:1727-1756`, which scans the stored list for `getSsid().equals(ssid)`), then starts the
login coroutine `q21` (`p000/q21.java:88-113`) which builds `new fm2(new pe4(prefs, ssid, null), …)` and
either runs the *register* branch (some key missing) or the *login* branch (`q21.java:99-106`).

Where the three secrets come from — **two routes, neither of them a Xiaomi-cloud HTTP call by this app**:

1. **Inter-app broadcast (the practical route).**
   `services/MajsiHomeReceiver.java:20-26` receives `Intent` action `MAJSI_HOME_RECEIVER`, extra
   `arg:majsi_data` (a Gson `ConfigurationElliptic`), validates `ssid.length()!=0`, `deviceInfo`,
   `deviceToken`, `beaconKey` non-null, and saves them with
   `new pe4(prefs, ssid, null).m12537P(deviceInfo, deviceToken, beaconKey)`
   (`pe4.java:569-588`). The sender list is enumerated in
   `services/RequestEllipticKeysReceiver.java:21`:
   ```java
   new m13("adriandp.m365dashboard",     "adriandp.core.service.MajsiHomeReceiver"),
   new m13("com.m365downgrade",          "com.nordicsemi.nrfUARTv2.core.MajsiHomeReceiver"),
   new m13("sh.cfw.utility",             "sh.cfw.utility.services.MajsiHomeReceiver"),
   new m13("dev.sh.cfw.utility",         "dev.sh.cfw.utility.services.MajsiHomeReceiver")
   ```
   and `RequestEllipticKeysReceiver.onReceive` (`:25-40`) broadcasts **this** app's stored keys to those
   four packages (`MAJSI_HOME_RECEIVER` + `arg:majsi_data`). So the "Xiaomi cloud values" are exchanged
   between ScooterHacking-family apps, not fetched by Scootbatt.
2. **Derived on-device over BLE (register/setup flow).**
   `p000/fm2.java:1463-1521`: if `deviceInfo == null` the app invents one —
   `0x00 ‖ "blt.4.159" + 10 random chars from [a-z0-9]` (`fm2.java:1465-1491`) — sends its ephemeral
   P-256 public key, receives the scooter's (`fm2.java:1494-1502`), runs ECDH
   (`fm2.java:1504-1512`) and
   ```java
   HKDF-SHA256(ikm = ECDH_shared, salt = 16 × 0x00, info = "mible-setup-info") → 64 bytes  // fm2.java:1514-1516
     [0:12]  → deviceToken    (hx1(0,11))    // fm2.java:1518
     [12:28] → beaconKey      (hx1(12,27))   // fm2.java:1519
     [28:44] → AES-CCM key for the deviceInfo blob                              // fm2.java:1520
       AES-CCM(key=that, nonce=hex 101112131415161718191a1b, tag=32 bit, AAD="devID")
   ```
   and on success persists them via `this.f8033b.m12537P(deviceInfo, deviceToken, beaconKey)`
   (`fm2.java:1669`).
   **No HTTP client exists in `fm2` or anywhere in the elliptic path** (only imports:
   `java.security.*`, `javax.crypto.*`, `org.spongycastle.jce.*` — `fm2.java:1-20`); a repo-wide grep for
   `io.mi.com|api.mi.com` returns nothing. The only cloud-ish dependency in the app is Firebase.
   A separate *verification* step exists (`m5364m`, `fm2.java:1618-1671`: sends mible command 19 and, if
   the scooter answers 17/18, proceeds) and its failure is surfaced as
   `new o20("REG_VERIFY_FAIL")` (`fm2.java:1605`) plus `m12549j()`
   (`fm2.java:1781` → `pe4.java:1020-1030`, which **deletes the stored `ConfigurationElliptic` entry**).

#### 2.3.2 Primitives actually used (concrete parameters)

| primitive | where | parameters |
|---|---|---|
| ECDH keypair | `fm2.java:76-83` | `KeyPairGenerator.getInstance("ECDH")`, `initialize(ECNamedCurveTable.getParameterSpec("secp256r1"))` — NIST P-256, SpongyCastle curve spec |
| ECDH public key export | `fm2.java:1183-1191` | `((ECPublicKey) kp.getPublic()).getQ().getEncoded(false)` → 65-byte uncompressed, `[1:]` = 64 bytes sent |
| ECDH peer import | `fm2.java:1494-1502` | `KeyFactory.getInstance("ECDH")` + `new ECPublicKeySpec(curve.decodePoint(0x04 ‖ 64 bytes), spec)` |
| ECDH agree | `fm2.java:1505-1512` | `KeyAgreement.getInstance("ECDH")`, `generateSecret()` |
| HKDF | `p000/bl0.java:46-64` | `np1` = SpongyCastle `DerivationFunction` driven by `new HMac(new SHA256Digest())` and `new HKDFParameters(ikm, salt, info)`; 64 bytes out. Info strings: `"mible-setup-info"` (`fm2.java:1516`), `"mible-login-info"` (`fm2.java:235`) |
| HMAC | `fm2.java:246-248`, `:258-260` | `Mac.getInstance("HmacSHA256")`, 32-byte output; keys = HKDF[0:16] (verify scooter) / HKDF[16:32] (app's own) |
| AES-CCM | `p000/bl0.java:26-42` | `Cipher.getInstance("AES/CCM/NoPadding")`, `SecretKeySpec(key,"AES")`, `GCMParameterSpec(32, iv)` → **32-bit tag**, 12-byte nonce. AAD `"devID"` **only when bit 3 of the flag is clear**: `(i & 8) != 0 → str = null` (`bl0.java:27`); calls pass `16` (AAD "devID") or `24` (no AAD, `bl0.java:31-36`) |
| provider | `bl0.java:20-22` | `static { Security.addProvider(new BouncyCastleProvider()); }` — SpongyCastle is registered process-wide; the `"ECDH"` KeyPairGenerator/KeyFactory/KeyAgreement and `ECNamedCurveTable` resolve to it (the platform provider does not register `"ECDH"`) — *inferred from provider registration order, not observed at runtime* |
| SHA-256 digest | `bl0.java:51-53` | `SHA256Digest` used inside the HKDF HMAC only |

#### 2.3.3 Session key schedule — `fm2.java:226-270` (`m5353b`, login)
```java
salt = appNonce ‖ scooterBlob          // fm2.java:232-233 (f8039h ‖ f8038g)
k64  = HKDF-SHA256(ikm = deviceToken, salt, info = "mible-login-info")   // fm2.java:235
f8040i = k64[0:16]     // rx key   (HMAC-verify scooter MIC + AES-CCM decrypt of responses)
f8041j = k64[16:32]    // tx key   (HMAC for our MIC      + AES-CCM encrypt of commands)
f8042k = k64[32:36]    // rx nonce prefix (4 bytes)
f8043l = k64[36:40]    // tx nonce prefix (4 bytes)
```
(Field roles proven by use: encrypt with `f8041j` at `pe4.java:1278`, decrypt with `f8040i` at
`ScooterFragment.java:993`; nonce prefixes `f8043l` at `pe4.java:1262`, `f8042k` at
`ScooterFragment.java:984`. The 4×16-byte split is `hx1(a,b)` inclusive ranges — verified against
`p000/AbstractC1539zj.java:312-324` + `p000/vj4.java:593-599` + `p000/hx1.java:34-36`.)

MIC exchange:
* scooter MIC (32 bytes, read from the device) must equal `HMAC-SHA256(K_rx, appNonce ‖ scooterBlob)`
  (`fm2.java:242-252`),
* app MIC = `HMAC-SHA256(K_tx, appNonce ‖ scooterBlob)`, 32 bytes, sent with mible command 10
  (`fm2.java:253-268`).

#### 2.3.4 Command encryption — `pe4.java:1188-1295`
```java
counter = fm2.f8044m;  counterLE = 4 bytes little-endian of counter;  fm2.f8044m++   // pe4.java:1227-1242
frame[0..1] = 55 AB ; frame[2] = rawLen-2 ; frame[3] = counterLE[0] ; frame[4] = counterLE[1]  // pe4.java:1245-1250
plain        = first3Bytes ‖ payload ‖ 4 random bytes (SecureRandom via ab3.f359c)             // pe4.java:1251-1261
nonce(12)    = f8043l ‖ 00 00 00 00 ‖ counterLE(4)                                            // pe4.java:1262-1274
ct           = AES-CCM(key = f8041j, nonce, plain, NO AAD /*flag 24*/)                        // pe4.java:1278
frame        = header ‖ ct ‖ ~Σ(frame) little-endian(2)                                       // pe4.java:1282-1295
```
If `f8043l` (nk) is null the whole branch returns null (`pe4.java:1275-1277`).

#### 2.3.5 Response decryption — `ScooterFragment.java:983-1041` (`mo3234e`, NUS TX only)
```java
if (frame[0]==0x55 && frame[1]==0xAB) {
  nonce(12) = f8042k ‖ 00 00 00 00 ‖ { frame[3], frame[4], 0, 0 }                       // :985
  ct        = frame[5 .. |f|-7]  ; tag = frame[|f|-6 .. |f|-3]                          // :988-989
  plain     = AES-CCM-DECRYPT(key = f8040i, nonce, ct‖tag, tagLen = 32 bit)            // :993-995
  rebuilt   = {55, AB, frame[2]} ‖ plain                                                // :1008-1010
  trimmed   = rebuilt[0 .. |rebuilt|-2]      // drop the last 2 of the 4 random bytes    // :1035-1037
  parse(oh3.m11880i(trimmed))                                                            // :1038-1040
}
```
Frame geometry (all identities verified against `hl2`'s overhead 16):
`|frame| = 18 + payloadLen`, length byte `= payloadLen + 2`, plaintext `= hdr(3) ‖ payload ‖ rand(4)`,
tag 4 bytes, trailing 16-bit ones-complement checksum over all preceding bytes.
The rx nonce only carries the **2 low counter bytes** from the wire, with the upper 2 counter bytes
forced to 0 (`ScooterFragment.java:985`) — so a session counter ≥ 65536 is ambiguous. *Unverified
whether the scooter ever reaches that.*

---

### 2.4 Plain arms (for completeness — no cryptography)
* `Ninebot` (0): `pe4.java:1041-1056`, checksum `~Σ` LE, magic `5A A5`, `lenByte = payloadLen`.
* `Xiaomi` (2): `pe4.java:1143-1182`, magic `55 AA`, `lenByte = payloadLen + 2`, same 16-bit `~Σ`
  checksum. Responses are normalised by `oh3.m11880i` and parsed by `m3299r0`.
* These arms are reached only when `f19697n == FALSE` (`C0993mx.java:474-506`) **and** no crypto flag is
  set — in which case the app declares the link paired immediately
  (`ScooterFragment.java:1517-1520` → `ee0.f6546j`, `false`).

---

## 3. `isNewNinebotGeneration` (task item 3)

```java
// com/basse/scootbatt/global/Scooter.java:170-172
public final boolean isNewNinebotGeneration() {
    return gy1.m6310f(this.model, "g2") || gy1.m6310f(this.model, "g65") || gy1.m6310f(this.model, "f2");
}
```
Straight model-string test on the model code resolved from the advertisement (`C1142ow`: `-125`→`g2`,
`120`→`g65`, `127|-128|-127`→`f2`).

**Everything it changes (exhaustive — four call sites, two features):**
1. `p000/vo3.java:243` and `p000/vo3.java:268` → the "Locked" settings chip is *omitted* for
   new-generation scooters (`if (scooter != null && !scooter.isNewNinebotGeneration())`).
2. `p000/ho3.java:122` and `:131` → the flag is passed as the 6th argument of
   `BleForegroundService.m3240k(…)`.
3. Inside the service it is stored as `f3922q` (`services/BleForegroundService.java:364`) and read at
   `:297`:
   ```java
   if (!this.f3922q) arrayList.add(new dv2(lockIcon, z ? "Unlock" : "Lock", f3912L));
   ```
   i.e. the lock/unlock notification action button is suppressed.

**It does NOT change the handshake or the crypto.** The protocol arm is chosen solely by
`useCrypto`/`isXiaomi` plus service discovery (§0, §2.1); the same `NinebotCrypto` handshake
(`m3297p0`) and the same frames are used for `g2`/`f65`/`f2` as for `esx`/`g30`. There is no second
key schedule, no second constant and no different magic for the new generation anywhere in the app.
This is a materially different picture from the community understanding that G2-class devices use a
separate pairing/auth scheme — **if such a scheme exists on the device, Scootbatt does not implement
it**, and the difference the flag encodes is "no direct BLE lock command" (consistent with `f3922q`
being used only for that button).

---

## 4. Handshake sequences (task item 5)

### 4.0 Connection state machine
`p000/ee0.java:34-49`: `PendingReconnect(0)`, `Disconnected(1)`, `PendingConnect(2)`, `Connected(3)`,
`PendingLegacyPairing(4)`, `PendingPairing(5)`, `Paired(6)`.
Only two places set the pairing states: `ScooterFragment.java:1355` (`PendingPairing`, Xiaomi path) and
`ScooterFragment.java:1465` (`PendingLegacyPairing`, NinebotCrypto path).

### 4.1 Xiaomi old / "M365-class crypto" → arm `NinebotCrypto` (5A A5)

All outgoing frames below are built by `ScooterFragment.m3303v0` → `pe4.m12550k(ordinal 1)` (so on the
wire they are `5A A5 len ‖ enc(raw) ‖ MIC/CRC ‖ ctr16`), and all incoming frames are decrypted by
`ScooterFragment.mo3235f` (`ScooterFragment.java:1074-1176`) before parsing.
The state machine is the suspend fun `ScooterFragment.m3297p0` (`:1405-1525`, reached through
`p000/lo3.java:151`/`p000/jo3.java:34`), started from `mo3233d` (`:882-921`).

| # | app → scooter (plaintext before encryption) | scooter → app (after decryption) | effect |
|---|---|---|---|
| 0 | — | — | `mo3233d` sets arm `NinebotCrypto` (`:900`), `new C1375v6(name)` (`:906`), state `PendingLegacyPairing` (`:1465`), `K = SHA1(name ‖ K_fw)[0:16]` |
| 1 | `3E 21 5B 00` (`{62,33,91,0}`, `:1472`) — repeated every **900 ms** until the reply arrives (`:1469-1479`) | inner frame starts `5A A5 1E 21 3E 5B >` (30-byte payload). Bytes **16…29** of the payload are copied into `f4059j3[14]` (the scooter UID/SN) `:1604-1608` | MIC/CRC is the 2-byte CRC form (counter 0). Key re-derivation: `ble_data := inner[7:23]`; `K := SHA1(name ‖ ble_data)[0:16]` (`:1129-1133`) |
| 2 | `3E 21 5C 00 ‖ 4A EE BD 73 E2 16 1C 11 2D 06 5A 49 CC 6E 8B B7` (20 bytes, `:1485`) | inner frame starts `5A A5 00 21 3E 5C 01` → sets `f4044U2 = true` (`:1764`); handled by the `case 92 / i5==1` branch (`:1761-1766`) | `app_data :=` the 16 literal bytes above (`pe4.java:1136-1138`). Key re-derivation on the reply: `K := SHA1(app_data ‖ ble_data)[0:16]` (`:1143-1144`) |
| 3 | `3E 21 5D 00 ‖ f4059j3[0:14]` (18 bytes, `m3284b0` `:751-759`) — repeated every 500 ms until the reply arrives (`:1503-1508`) | action `0x5D` → `f4046W2 = true; f4044U2 = true;` state := `Paired` (`:1818-1819`, `:1355`) | handshake complete |
| — | loop timing: 500 ms between retries (`ScooterFragment.java:1476`, `:1489`, `:1496`, `:1507`) | | if the `0x5C` reply carries position `1` while `f4045V2` is still true, the "on legacy pairing" dialog is shown (`:1765-1768`, strings `on_legacy_pairing_header/subtitle`) |

Byte-level facts (all *read*, not inferred): the `0x5C` app-data payload is a **hard-coded 16-byte
constant**, so the final session key is fully determined by the scooter's `ble_data` — no randomness is
involved in this arm at all (compare §6).
The three magic strings the decryptor looks for are `5A A5 1E 21 3E 5B` (`:1129`),
`5A A5 00 21 3E 5C 01` (`:1143`) and, on the encrypt path,
`5A A5 10 3E 21 5C 00` (`pe4.java:1136`).
Note the byte-order flip between directions: app→scooter inner frames are `3E dir act pos`, scooter→app
inner frames are `dir 3E act pos` — the app hard-codes both forms.

### 4.2 Ninebot old, non-crypto (`Ninebot`, 5A A5)
No handshake. `mo3233d` sets arm `Ninebot` (`:900`), `mo3233d`'s caller drives telemetry directly; the
state machine marks `Paired` without any exchange (`:1517-1520`, reached when
`protocol != NinebotCrypto`).

### 4.3 Ninebot "new" / any device exposing the Xiaomi service → arm `XiaomiCrypto` (55 AB)
Entry: `C0993mx.java:423` → `mo3236g` (`ScooterFragment.java:1187-1254`) →
`f19861b := XiaomiCrypto` (`:1205`), `new C1375v6(name)` (unused in this arm, `:1211`),
`new hl2(XiaomiCrypto, mtu)` (`:1214`), state `PendingPairing` (`:1355`) → `m3296o0` (`:1326-1355`) →
`q21` coroutine (`q21.java:88-113`) → `fm2`.

Two sub-cases chosen in `q21.java:99-106`: **register** (`m5356e`, if any of
keypair/deviceInfo/deviceToken/beaconKey is missing) or **login** (`m5365n`).

`m5357f(byte selector)` (`fm2.java:807-902`) is the dispatcher:
`36`→`m5355d` (setup), `21`→`m5362k`, `19`→`m5364m` (verify), `20`→`m5363l`, `-94 (0xA2)`→`m5370u`
(login), `-92 (0xA4)`→`m5371v`.

All mible frames are written to characteristic **`00000019`** as `00 00 <cmd> <arg>` (`fm2.java:1837`,
`m5366o`) or `00 00 00 <cmd> <chunkCount> 00` (`fm2.java:1886`, `m5367q`), and every "read" is an
**awaited notification on the same characteristic** with a timeout (`m18223u`, `C1527z7.java:1230-1231`;
chunked reads concatenate `resp[2:]` while `resp[0] == expectedIndex && resp[1] == 0`,
`fm2.java:1026-1058`).

**Login** (`m5354c` then `m5353b` then `m5360i`):
| # | step | where |
|---|---|---|
| 1 | `00 00 00 0B 01 00` (cmd 11, 16 bytes expected) → expect reply byte `1` | `fm2.java:418`, `m5351r(11,16,…)` |
| 2 | send 16 random bytes (app nonce) | `fm2.java:424-433`, `m5368s` |
| 3 | read `00000019`: require `len==6 && r[3]==13 && r[2]==0` (or the 20-byte variant `r[2]==2` → `00 00 03 00` and `scooterBlob = r[4:]`) | `fm2.java:476-533` |
| 4 | `00 00 01 01` | `fm2.java:484`, `m5350p(1,1)` |
| 5 | chunked 16-byte read → **scooterBlob** | `fm2.java:493`, `m5359h` |
| 6 | `00 00 01 00` | `fm2.java:503`, `m5350p(1,0)` |
| 7 | `HKDF-SHA256(deviceToken, appNonce‖scooterBlob, "mible-login-info")` → rx/tx keys + 2×4-byte nonce prefixes; verify `HMAC-SHA256(K_rx, …)` == the scooter's 32-byte MIC; send `HMAC-SHA256(K_tx, …)` via cmd 10 | `fm2.java:226-270` |
| 8 | from here ordinary commands use the `XiaomiCrypto` encoder (`pe4.m12550k` ordinal 3) over **NUS RX** and responses are decrypted in `ScooterFragment.mo3234e` | §2.3.4/§2.3.5 |

**Register / setup** (`m5355d` when `deviceInfo == null`):
| # | step | where |
|---|---|---|
| 1 | cmd `36` (50 ms) | `fm2.java:635`, `m5369t(36,50)` |
| 2 | `m5354c` (the login key-exchange above) | `fm2.java:687` |
| 3 | `m5353b` (the MIC exchange; needs `deviceToken`) | `fm2.java:647` |
| 4 | read reply, require byte `33` (0x21) | `fm2.java:659-670` |
| 5 | if `deviceInfo == null`: invent `0x00‖"blt.4.159"+10 rand`, send the app's P-256 public key (cmd 3, 64 bytes), receive the scooter's 65-byte point, ECDH, `HKDF(shared, 16×0x00, "mible-setup-info")` → `deviceToken[0:12]`, `beaconKey[12:28]`, AES-CCM key `[28:44]`; encrypt `deviceInfo` with nonce `101112131415161718191a1b`, AAD `"devID"` | `fm2.java:1183-1530` |
| 6 | verification: send cmd 19, accept reply `17` (or `18` then re-read) | `fm2.java:1618-1671`, `m5364m` |
| 7 | on success persist `deviceInfo/deviceToken/beaconKey` for this SSID | `fm2.java:1669` → `pe4.java:569-588` |
| 8 | on failure: `o20("REG_VERIFY_FAIL")` (`fm2.java:1605`) and the stored entry is removed (`fm2.java:1781` → `pe4.java:1020-1030`) | |

**Which frame must be authenticated before telemetry is answered?**
* `NinebotCrypto`: the three frames of §4.1. Until the `0x5D` reply arrives the app stays in
  `PendingLegacyPairing`/`PendingConnect` and `m3287f0().m15190e(ee0.f6546j, …)` (Paired) is never set;
  the ordered-request pipeline (`C1286ss`, `p000/C1286ss.java:84-96`) is only fed once the connection
  state allows it. Every subsequent command is encrypted with the post-step-2 key, so anything sent
  before step 3 completes is encrypted with a key the scooter has not yet accepted.
* `XiaomiCrypto`: the login MIC exchange (steps 6–7). Telemetry requests are then AES-CCM frames on
  NUS RX; a response whose CCM tag does not verify makes `cipher.doFinal` throw, which is swallowed
  (`ScooterFragment.java:996-997`) and yields `null` → the frame is dropped.
* Plain arms: nothing to authenticate.

---

## 5. Where the app cannot be reproduced without the vendor cloud

| capability | verdict |
|---|---|
| `NinebotCrypto` (5A A5) handshake and session | **Fully reproducible offline.** Only inputs are the BLE advertised name and bytes observed on the air plus one hard-coded 16-byte constant (`C1375v6.java:1245`) and one hard-coded 16-byte app-data payload (`ScooterFragment.java:1485`). No cloud, no per-device secret. |
| `Ninebot`/`Xiaomi` plain arms | **Fully reproducible.** Checksums only. |
| `XiaomiCrypto` transport crypto (ECDH/HKDF/AES-CCM) | **Reproducible.** Standard P-256 ECDH, HKDF-SHA256 with the two `"mible-*-info"` strings, AES-CCM with 32-bit tag — all parameters are in the code. |
| `XiaomiCrypto` **key material** (`deviceToken`, `beaconKey`, `deviceInfo`) | **Not obtainable from this app alone without either (a) another Mi-Home-adjacent app or (b) an accepted on-device registration.** Route (a): the four hard-coded packages in `RequestEllipticKeysReceiver.java:21` push/pull the three values over the `MAJSI_HOME_RECEIVER` broadcast; Scootbatt has **no** Xiaomi cloud client of its own (verified: no HTTP imports in `fm2`, no `*.mi.com` string anywhere). Route (b): the app derives the keys by ECDH+HKDF itself, but the flow contains an explicit *verify* step whose failure is `REG_VERIFY_FAIL` and which deletes the derived entry — the code does not show what the scooter requires for that verification to pass. |
| Anything in a native library | **Nothing — there are no native libraries in the APK.** No handshake step is hidden in `.so`. |

---

## 6. Comparison with `/home/kali/ScooterHacking/research/ninebotcrypto/`

**`nbcrypt.c`** (C port of Robert Trencheny's NinebotCrypt) is a **faithful match** for the app's
`NinebotCrypto` arm — every constant and every step:

| nbcrypt.c | Scootbatt | match |
|---|---|---|
| `nbc_fw_data[16] = {0x97,0xCF,…,0x5D}` (`:17`) | `C1375v6.java:1245` `{-105,-49,…}` | identical bytes |
| `nbc_CalcSha1Key`: `SHA1(d1‖d2)[0:16]`, `:274-282` | `m16086e`, `C1375v6.java:459-469` | identical |
| `nbc_CryptoFirst`: `XOR AES-ECB(nbc_fw_data, key)` repeated, `:156-167` | `m16090j`, `C1375v6.java:548-563` | identical |
| `nbc_CryptoNext`: block `01‖msgIt(BE)‖ble_data[0:8]‖000‖ctr8++`, `:169-200` | `m16091k`, `C1375v6.java:567-594` | identical |
| `nbc_CalcCrcNextMsg`: 4-byte CBC-MAC-style MIC, `:217-271` | `pe4.java:1096-1131` | identical (incl. `tagBlock[15] = payloadLen`) |
| `nbc_CalcCrcFirstMsg`: 2-byte `~Σ`, `:202-215` | `pe4.java:1077-1082`, `:1085-1090` | identical |
| `match[] = {5A,A5,1E,21,3E,5B}` → `ble_data := dst+7`, re-key with `name_data`, `:92-97` | `ScooterFragment.java:1129-1133` | identical |
| `match[] = {5A,A5,00,21,3E,5C,01}` → re-key with `app_data`, `:102-106` | `ScooterFragment.java:1143-1144` | identical |
| save `app_data` when `src == {5A,A5,10,3E,21,5C,00}`, `:145-149` | `pe4.java:1136-1138` | identical |
| counter rollover bump `if ((msg_it & 0x8000) > 0 && (src[len-2]>>7)==0) msg_it += 0x10000` (`:80-81`) | **absent**; replaced by the unsigned-compare resync at `ScooterFragment.java:1146-1150` | **divergence** — Scootbatt (and ProtocolNinebot.kt, `:44`) drop the 64 KiB-block bump |
| `nbc_decrypt` returns `dst` of `srcLength-6` with the 3-byte header copied (`:74-77`) | `ScooterFragment.java:1114-1120` | identical geometry |

**`ProtocolNinebot.kt`** (`adriandp.core.util`, i.e. the M365 Dashboard app — one of the broadcast
peers in `RequestEllipticKeysReceiver.java:21`) is **the same algorithm again**, including the
diagnostic strings: compare
`ProtocolNinebot.kt:56-58` `"\\${payload.toHexString()}\n\\${payloadD…}\n\\${payloadE…}\")"` with
`ScooterFragment.java:1125`, and `:76-79` with `ScooterFragment.java:1139`. Both apply the cipher twice
to self-check. Consequences:
* Scootbatt's `NinebotCrypto` is a port of (or shares an ancestor with) M365 Dashboard, not an
  independent implementation. Its `putInitialRequest` (`ProtocolNinebot.kt:312-348`) also shows the
  first frame arriving in **three** BLE chunks before decryption and takes the serial number from the
  **last 14 bytes** (`hex.substring(len-28)`), matching Scootbatt's 14-byte `f4059j3` filled from
  payload offset 16 (`ScooterFragment.java:1607`) — a useful cross-check of the "30-byte payload,
  UID at 16…29" reading.
* Difference: M365 Dashboard generates a random `randomKey` (`ProtocolNinebot.kt:338-341`, 32 chars from
  `"abcde1234567890"`) for the `5A A5 10 3E 21 5C 00` request, whereas **Scootbatt hard-codes the
  16-byte app-data payload** `4AEEBD73E2161C112D065A49CC6E8BB7` (`ScooterFragment.java:1485`). Scootbatt's
  session key is therefore deterministic given the scooter's response; M365 Dashboard's is not (unless
  the scooter only echoes). Worth verifying against hardware which variant the scooters accept.
* Neither `ProtocolNinebot.kt` nor Scootbatt implements the nbcrypt 64 KiB counter bump (§ above).
* Neither file has anything for the Xiaomi/mible/ECDH arm — that part exists only in Scootbatt
  (`fm2`/`bl0`).

---

## 7. Complete constant / parameter inventory (quick reference)

| item | value | evidence |
|---|---|---|
| Ninebot fixed key material `K_fw` | `97 CF B8 02 84 41 43 DE 56 00 2B 3B 34 78 0A 5D` | `p000/C1375v6.java:1245` |
| Ninebot SHA-1 seed | BLE advertised name, UTF-8 | `ScooterFragment.java:906`, `C1375v6.java:1250` |
| Ninebot key schedule | `SHA1(seed16 ‖ second16)[0:16]` | `C1375v6.java:459-469` |
| Ninebot cipher | AES-128 **ECB**, encrypt-only, single block, PKCS#5 padding block discarded | `C1375v6.java:80-87` |
| Ninebot counter block (n≠0) | `01 ‖ msgIt(4 BE) ‖ ble_data[0:8] ‖ 00 00 00 ‖ ctr8` | `C1375v6.java:567-594` |
| Ninebot MIC block | `59 ‖ msgIt(4 BE) ‖ ble_data[0:8] ‖ 00 00 00 ‖ payloadLen` | `pe4.java:1096-1104` |
| Ninebot handshake app-data payload | `4A EE BD 73 E2 16 1C 11 2D 06 5A 49 CC 6E 8B B7` | `ScooterFragment.java:1485` |
| Ninebot handshake selectors | dir `0x21`, actions `0x5B`, `0x5C`, `0x5D`, position `0` | `ScooterFragment.java:1472`, `:1485`, `m3284b0` `:751-759`; `p000/xp0.java:46` |
| Xiaomi elliptic curve | `secp256r1` (P-256), uncompressed points | `fm2.java:78`, `:1496-1497` |
| HKDF info — setup | `"mible-setup-info"` (salt = 16 × 0x00) | `fm2.java:1514-1516` |
| HKDF info — login | `"mible-login-info"` (salt = appNonce ‖ scooterBlob) | `fm2.java:232-235` |
| HKDF split | `[0:16]` rx key, `[16:32]` tx key, `[32:36]` rx nonce prefix, `[36:40]` tx nonce prefix | `fm2.java:237-240` + `pe4.java:1262/1278` + `ScooterFragment.java:984/993` |
| setup split | `[0:12]` deviceToken, `[12:28]` beaconKey, `[28:44]` deviceInfo CCM key | `fm2.java:1518-1520` |
| AES-CCM | `AES/CCM/NoPadding`, 32-bit tag, 12-byte nonce | `p000/bl0.java:30-31` |
| CCM AAD | `"devID"` when flag bit 3 clear (setup uses 16), **none** for command frames (24) | `bl0.java:27`, `bl0.java:31-36`, `pe4.java:1278` |
| command nonce | `txPrefix(4) ‖ 00 00 00 00 ‖ counter(4 LE)` | `pe4.java:1262-1274` |
| response nonce | `rxPrefix(4) ‖ 00 00 00 00 ‖ {f[3], f[4], 0, 0}` | `ScooterFragment.java:985` |
| MIC | HMAC-SHA256, 32 bytes, over `appNonce ‖ scooterBlob` | `fm2.java:246-260` |
| deviceInfo template | `0x00 ‖ "blt.4.159" ‖ 10 × [a-z0-9]` | `fm2.java:1465-1491` |
| setup CCM nonce | `10 11 12 13 14 15 16 17 18 19 1A 1B` | `fm2.java:1514`, `:1520` |
| magics | `5A A5` (Ninebot), `55 AA` (Xiaomi), `55 AB` (XiaomiCrypto) | `pe4.java:1046`, `:1147`, `:1246`; `hl2.java:32-52` |
| BLE chars | NUS RX `6e400002`, NUS TX `6e400003`, `0000fe95`, `00000010`, `00000019` | `C0993mx.java:30-47` |
| mible write frames | `00 00 <cmd> <arg>` / `00 00 00 <cmd> <chunks> 00` on `00000019` | `fm2.java:1837`, `:1886` |
| broadcast contract | action `MAJSI_HOME_RECEIVER`, extra `arg:majsi_data` (Gson `ConfigurationElliptic`, keys `ssid`/`deviceInfo`/`deviceToken`/`beaconKey`) | `MajsiHomeReceiver.java:20-26`, `RequestEllipticKeysReceiver.java:33-38`, `ConfigurationElliptic.java:11-21` |
| broadcast peers | `adriandp.m365dashboard`, `com.m365downgrade`, `sh.cfw.utility`, `dev.sh.cfw.utility` | `RequestEllipticKeysReceiver.java:21` |

---

## 8. Unverified / needs hardware

1. **Key acceptance.** Whether a scooter accepts `K = SHA1(app_data ‖ ble_data)[0:16]` with Scootbatt's
   *hard-coded* `app_data` (`4AEEBD73E2161C112D065A49CC6E8BB7`) — versus M365 Dashboard's random key — is
   not determinable statically. If the scooter signs/echoes the app-data it received, the constant is
   fine; if it validates it against something else, it is not.
2. **The `0x5C` reply's position byte.** `case 92` with `i5 == 1` is treated as the pairing
   confirmation (`ScooterFragment.java:1761-1766`); the meaning of other `i5` values is not visible.
3. **Counter rollover.** Scootbatt (and `ProtocolNinebot.kt`) omit nbcrypt's 64 KiB bump
   (`nbcrypt.c:80-81`). Whether the resync at `ScooterFragment.java:1146-1150` is sufficient in
   practice — especially across a reconnect where `C1375v6` is reconstructed and the counter resets to
   0 — needs a live capture.
4. **The mible command semantics.** Commands 1, 3, 10, 11, 19, 20, 21, 36 on characteristic `00000019`
   are known only by their call sites and the byte values they expect back (1, 0, 13, 2, 17, 18, 33).
   The authoritative Xiaomi `mible` state machine is not present in the app.
5. **`REG_VERIFY_FAIL`.** What the scooter (or the Xiaomi cloud, on behalf of the scooter) requires for
   verification to succeed is not visible anywhere in the APK — the app only reports the failure and
   deletes the derived keys (`fm2.java:1605`, `:1781`). Therefore: **whether a third-party app can
   obtain `beaconKey`/`deviceToken`/`deviceInfo` without the vendor cloud cannot be answered from this
   decompilation.** The in-app ECDH+HKDF path produces candidate values, but only hardware (or a Mi Home
   account) can confirm they are accepted.
6. **XiaomiCrypto wire geometry.** Every length identity in §2.3.4/§2.3.5 is self-consistent
   (`lenByte = payloadLen + 2`, `|frame| = lenByte + 16`) and matches `hl2`'s overhead, but the
   send/receive field interpretations were derived by arithmetic, not observed. A single captured frame
   pair would settle it.
7. **Counter width on receive.** The response nonce only carries 2 counter bytes
   (`ScooterFragment.java:985`); behaviour at counter ≥ 0x10000 is undefined by the code.
8. **Provider resolution.** That `KeyPairGenerator/KeyFactory/KeyAgreement("ECDH")` actually bind to
   SpongyCastle (rather than the platform provider) is inferred from `Security.addProvider` being
   called and from the platform not registering `"ECDH"`; the app swallows the exception
   (`fm2.java:80-82`, `:1500-1501`) and would silently disable the whole `XiaomiCrypto` path if it
   failed. Not observable statically.
9. **`f19697n` selection.** That the Xiaomi `0000fe95` service is present on real M365-class devices
   (and therefore that they take the elliptic path rather than `NinebotCrypto`) is a property of the
   devices, not of the code — it decides which of the two §0 arm selections actually runs.
10. **New-generation handshake.** No new-generation-specific crypto or pairing frame exists in the
    APK (§3). If G2/F2/F65 devices really require a different auth flow, it is not implemented in
    Scootbatt and cannot be documented from this APK — needs a capture with a G2-class device.
11. **`z10` special case.** `pe4.java:1166-1168` forces type byte `17` for model `"z10"`, which is not
    in the model table at `C1142ow.java:64-138`, so it can never be selected by advertisement parsing.
    Dead code, or reachable through another path — unresolved.
