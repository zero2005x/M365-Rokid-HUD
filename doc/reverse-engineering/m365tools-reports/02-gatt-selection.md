# 02 — GATT service/characteristic selection, fallback, and the read/write/notify transport

**Target.** `app.peretti.m365tools` 1.8.0 (versionCode 180), sha256
`4451a47c86339b8f88d77bdad7aef77e1bc4f11b7c728a5803f2b1d342a16872`.
**Method.** Static only. Smali under `apktool-out/` is the source of record for every
citation; `jadx-out-nodeobf/` was used only for orientation. Every UUID below was
recovered by decrypting the app's string table with
`tools/m365tools_strings.py` — **no GATT UUID of interest is a plaintext literal.**

---

## 0. Correction to a previously recorded "established fact"

`CONTEXT.md:88` states "The app does not use Nordic UART. No `6e4000xx` UUID appears
anywhere." **That is false**, and the way it is false matters for anyone reading this
tree:

| Claim | Reality |
|---|---|
| `grep 6e4000` over the APK finds nothing | true — the literals are encrypted |
| therefore the app does not use NUS | **false** |

`6e400002-b5a3-f393-e0a9-e50e24dcca9e` and `6e400003-b5a3-f393-e0a9-e50e24dcca9e` are
loaded at `apktool-out/smali/dalvik/O/O/a/b/O/c/b/O/o/op.smali:140` and `:149` in that
class's `<clinit>`, and the authoritative assignments are `op.smali:450-457` and
`op.smali:459-466`. They are the app's **primary write and notify characteristics**.
`6e400001-b5a3-f393-e0a9-e50e24dcca9e` (the NUS *service* UUID) is present in the
table but **never used** — see §1.1.

A second trap the same episode exposes: **a decrypted string can have only dead load
sites.** The string obfuscator was applied to the DEX *after* R8, so it rewrote
`const-string` in code R8 had already orphaned, producing `invoke-static …decrypt(key)`
whose result is discarded. `ScooterInformationActivity.<clinit>`
(`apktool-out/smali/app/peretti/m365tools/ScooterInformationActivity.smali:143-233`
and `:258-276`) is 26 such dead decrypts in a row, including four of the UUIDs below.
`--uses` therefore tells you *where a UUID string is loaded*, not that it is used.
This report labels those rows **dead**.

---

## 1. The full UUID set

### 1.1 Table

Roles are as the app uses them. "Where defined" gives the smali that writes the
literal into a field or passes it to `UUID.fromString`. All entries are encrypted
strings unless marked *plaintext*.

| # | UUID | Role | Where defined | Confidence |
|---|---|---|---|---|
| 1 | `0000fe95-0000-1000-8000-00805f9b34fb` | **Scan filter only.** Read out of the advertisement's service-data map to obtain the model byte. Never used as a GATT service. *(plaintext string literal)* | `dalvik/…/tn$OO00000OOOOOOOO0000O.smali:125` | read |
| 2 | `00002902-0000-1000-8000-00805f9b34fb` | **CCCD**, written by RxAndroidBle when a notification is enabled; the app never names it. *(plaintext)* | `dalvik/…/je0.smali:39`, used `je0.smali:89-111` | read |
| 3 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NUS **service**. **Dead** — its only load site is inside the dead block `ScooterInformationActivity.smali:159`. The app never filters or looks up a service UUID. | `op.smali` table, load site `ScooterInformationActivity.smali:159` | read (never used) |
| 4 | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | **Write** characteristic. Used by *every* command strategy: `fn`, `gn`, `mn`, `bn`, `nn`, `kn`. | value set `op.smali:140`, field `op.OO00000OOOOOOOO0000O` final `op.smali:450-457`; write sites `mn.smali:842-854`, `gn.smali:972-982`, `fn.smali:1296-1304`, `bn.smali:437-447`, `nn.smali:508-522`, `kn.smali:2367-2377` | read |
| 5 | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | **Notify** characteristic — the only notification subscription made on every connection, unconditionally. | value set `op.smali:149`, field `op.OOOOOOO0OOOOO0O00OO0` final `op.smali:459-466`; subscribe `po.smali:1288-1296` | read |
| 6 | `00000001-0000-1000-8000-00805f9b34fb` | Xiaomi "classic" **write** (auth handshake) and, on the `protocolVersion == 1` path, **notify**. | `op.smali:167`, field `op.O00OOO0O00O00OOO0000`; write `io.smali:128-134`, `io.smali:292-312`; notify `fo.smali:59-65` | read |
| 7 | `00000002-0000-1000-8000-00805f9b34fb` | Xiaomi classic **read** (only when `protocolVersion == 1`). | `op.smali:176`, field `op.OO00O0O0O0000000000O`; read `po.smali:1415-1421` | read |
| 8 | `00000004-0000-1000-8000-00805f9b34fb` | **Read** — used only by the `kn` strategy. | `op.smali:185`, field `op.OO0O0OOO00O00OOO0000`; read `kn.smali:2709` | read |
| 9 | `00000010-0000-1000-8000-00805f9b34fb` | **Write + notify.** Two separate static fields hold this identical string. `wn` writes a 4-byte heartbeat to it every 3 s; `kn` both subscribes to it and writes an auxiliary frame to it. | field A `op.OO0OOOOOOOO00000O0O0` ← `op.smali:194-201`; field B `op.O0O000OOOO0000OOOO0O` ← `op.smali:213-220`; write `wn.smali:53-61`, `wn.smali:89-97`; notify `kn.smali:895-901`; write `kn.smali:2643` | read |
| 10 | `00000013-0000-1000-8000-00805f9b34fb` | **Dead.** Decrypted twice by `op`'s `<clinit>` and never stored. | `op.smali:203`, `op.smali:208` | read (dead) |
| 11 | `00000014-0000-1000-8000-00805f9b34fb` | **Read** (only when `protocolVersion == 1`). The live value is delivered at run time by a native call, not by the initialiser. | decoy `op.smali:158-165`; live `op.smali:424-448` ← `ScooterInformationActivity.BluetoothItem()` (native); read `po.smali:1386-1392` | read |
| 12 | `00000019-0000-1000-8000-00805f9b34fb` | **Write + notify** — used only by the `kn` strategy. | `op.smali:222`, field `op.OO00OOOOO00OO00OOOO0`; notify `kn.smali:961-967`; write `kn.smali:1031` | read |
| 13 | `00001800-0000-1000-8000-00805f9b34fb` (GAP) | **Dead** — one dead load site. | `ScooterInformationActivity.smali:268` | read (dead) |
| 14 | `00002a00-0000-1000-8000-00805f9b34fb` (Device Name) | **Dead** — one dead load site. | `ScooterInformationActivity.smali:273` | read (dead) |

Notes:

- Rows 10, 13 and 14 are **not used by any live code path.** Rows 3, 6 and 7 are
  used only on the hidden `protocolVersion == 1` path (§4).
- There is **no Ninebot/Segway custom service**, no `6e400001-0000-0000-006e-…`
  vendor UUID, no HMSoft `ffe0`/`ffe1`, and no `0000fff0`-family UUID anywhere in the
  decrypted table. The complete set of UUID-shaped plaintexts in
  `analysis/strings.txt:104-112` and `:164-166` is exactly rows 3-14 above.
- There are exactly **two** UUID string literals in the clear in the whole APK
  (rows 1 and 2) — confirming the earlier whole-APK pass, and explaining why it
  concluded wrongly.

### 1.2 How `00000014` is hidden

`op`'s static field `O0OOOOOO00OOOOO000O0` is initialised to `00000014-…` at
`op.smali:158-165`, then **overwritten** at `op.smali:424-448` with

```
new String(Base64.decode(ScooterInformationActivity.BluetoothItem(), 2),
           Charset.forName(<decrypted>))
```

`BluetoothItem()` is a **native** method (`ScooterInformationActivity.smali:417`).
The payload is `libnative-lib.so`: the string
`MDAwMDAwMTQtMDAwMC0xMDAwLTgwMDAtMDA4MDVmOWIzNGZi` sits next to the JNI symbol
`Java_app_peretti_m365tools_ScooterInformationActivity_BluetoothItem`, and base64-decodes
to `00000014-0000-1000-8000-00805f9b34fb` — the same value as the decoy. The native hop
exists to break static extraction, not to change the value. (Verified as a byte string
in `apktool-out/lib/arm64-v8a/libnative-lib.so`; the decode is independently checkable.)

Also in `op`'s `<clinit>`, five 4-byte arrays are hex-decoded from encrypted strings
(`op.smali:231-294`): `90CA85DE`, `00BC43CD`, `92ab54fa`, `09acbf93`, `C9589A36`.
Two of them are the **classic-M365 heartbeat packets** written to characteristic
`00000010` every three seconds (`wn.smali:36-61`; interval created at
`yn.smali:137-144` as `Observable.interval(1, 3, SECONDS)`).

---

## 2. How a characteristic is chosen

**There is no service-UUID selection at all, and no exact-UUID-set requirement.**

The app never calls `BluetoothGatt.getService(UUID)` (zero occurrences anywhere in the
APK's smali) and never inspects a service UUID. Every GATT access goes through one of
three RxAndroidBle methods on the connection object, each taking only a
*characteristic* UUID:

| RxAndroidBle interface (`kb0` after R8 shrinking) | Real method | Returns | Declared |
|---|---|---|---|
| `kb0.O0OOOOOO00OOOOO000O0(UUID,[B)` | `writeCharacteristic` | `Single<byte[]>` | `dalvik/…/kb0.smali:15` |
| `kb0.OO00000OOOOOOOO0000O(UUID)` | `setupNotification` | `Observable<Observable<byte[]>>` | `kb0.smali:27` |
| `kb0.OOOOOOO0OOOOO0O00OO0(UUID)` | `readCharacteristic` | `Single<byte[]>` | `kb0.smali:40` |

Resolution happens inside RxAndroidBle, in `mb0.call()`
(`apktool-out/smali_classes2/dalvik/O/O/a/b/O/c/b/O/o/mb0.smali:42-92`), which:

1. iterates **every** discovered `BluetoothGattService` in
   `nb0.OO00000OOOOOOOO0000O` (`mb0.smali:46-52`),
2. calls `service.getCharacteristic(uuid)` on each (`mb0.smali:70`),
3. returns the **first non-null match**, and
4. throws `BleCharacteristicNotFoundException(uuid)` if none matched
   (`mb0.smali:80-84`).

So the app is *characteristic-addressed and service-agnostic*: whatever service a
device happens to expose the characteristic in, the app will find it. (Service
discovery itself is triggered by RxAndroidBle on first use —
`ag0.smali:121` calls `BluetoothGatt.discoverServices()` — and the app never calls it
directly.)

Before dispatching, RxAndroidBle enforces **properties**:

- writes require property mask `0x4C` = `WRITE | WRITE_NO_RESPONSE | WRITE_SIGNED`
  (`le0$O0OOOOOO00OOOOO000O0.smali:64-68` → `md0` → `xe0.smali:29-41`), so at least
  one write property must be present;
- notifications require mask `0x10` = `PROPERTY_NOTIFY` only (`me0.smali:59-61`).

A violation raises `BleIllegalOperationException` (`md0$OO00000OOOOOOOO0000O.smali:59-80`,
message format `pd0.smali:98`: *"Characteristic %s supports properties: %s (%d) does not
have any property matching %s (%d)"*).

### 2.1 Fallback: there is none

Nothing in the app handles `BleCharacteristicNotFoundException` or any other
`com.polidea…` exception:

- zero `instanceof Lcom/polidea/…Exception` checks outside the library itself;
- zero uses of `onErrorResumeNext` outside RxAndroidBle internals
  (`qo0.O00O0O000O0000OO0OOO` is called only from `xg0.smali:54` and
  `qi0$OO00000OOOOOOOO0000O.smali:88`, both library code);
- no retry against a different characteristic UUID anywhere.

What happens instead is a **log line and, at most, a disconnect**:

| Failing stream | Error consumer | Behaviour |
|---|---|---|
| `setupNotification(6e400003)` (`po.smali:1296,1338`) | `vn` | logs `'Error on subscribeing '` + `Throwable.toString()`; returns (`vn.smali:36-51`) |
| `setupNotification(00000001)` (`fo.smali:65,110`) | `ro` | logs `'Notifications error: '` then calls `op.OO0O00O0OO00OOO00O00()` (`ro.smali:44-56`) |
| write failures on the classic path | `ko` | logs `'Write error: '` (`ko.smali:44-56`) |
| `op.OO0O00O0OO00OOO00O00()` | — | resets handshake state, logs `'TriggerDisconnect'`, reports to Firebase, disposes the connection (`op.smali:3996-4180`, strings at `:4018-4031`) |

**The "fallback" that does exist is not exception-driven; it is chosen before any
characteristic is touched**, from the model table and the (hidden) protocol-version
setting. See §4. If the chosen variant's characteristic is absent, the connection dies
with a log line — there is no second attempt.

---

## 3. Notifications: notify, never indicate

Notifications are enabled through one call only — RxAndroidBle
`setupNotification(uuid)` = `kb0.OO00000OOOOOOOO0000O(UUID)`. There are exactly four
subscription sites:

| Site | Characteristic | Condition |
|---|---|---|
| `po.smali:1288-1296` | `6e400003` | **always**, on every successful connection |
| `kn.smali:895-901` | `00000010` | `kn` strategy only |
| `kn.smali:961-967` | `00000019` | `kn` strategy only |
| `fo.smali:59-65` | `00000001` | `protocolVersion == 1` path only |

**The app never uses indications.** Evidence:

1. `kb0` (RxAndroidBle's `RxBleConnection`, R8-shrunk) declares **three** methods
   (`kb0.smali:15,27,40`); `setupIndication` is not among them. R8 only removes an
   interface method that is never invoked, so no call site exists in the DEX.
2. The notification-setup operation hard-codes `isIndication = false`
   (`me0.smali:59-75` passes literal `0` into the `ee0` callable), so the CCCD is
   always written with `BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE`
   (`ma0.smali:52`); `ENABLE_INDICATION_VALUE` (`la0.smali:52`) is never selected.
3. The property pre-check is `PROPERTY_NOTIFY` (`0x10`) only (`me0.smali:59`), so a
   characteristic that supports **only** `PROPERTY_INDICATE` is rejected outright.

Consequence: the app cannot talk to a device whose notify channel is indicate-only.
The fork's `GattProfileDiscovery` accepts `PROPERTY_NOTIFY or PROPERTY_INDICATE`
(`GattProfileDiscovery.kt:65`, `BleManager.kt:111-114`) but its `BleManager` then writes
`ENABLE_NOTIFICATION_VALUE` unconditionally (`BleManager.kt:444`) — it would advertise
support for an indicate-only channel and then enable it with the notify value, which is
the same bug in a different place.

CCCD mechanics live entirely inside RxAndroidBle
(`je0.smali:39,89-111`): it takes the CCCD descriptor from the *given* characteristic,
and if the descriptor is missing it fails with
`BleCannotSetCharacteristicNotificationException(chr, 2, null)` (`je0.smali:95-111`).
The app never reads or writes the CCCD itself.

---

## 4. Per-model / per-protocol transport selection

The transport is **not** one transport. A strategy object
`op.OO0O0O0OO0OOO0OO0000` of abstract type `vm`
(`dalvik/…/vm.smali`, declared `op.smali:74`) is constructed once per connection inside
the connection callback `po.accept(RxBleConnection)` — `po.smali` has exactly one
method, `O00OOO0O0O00OOO0000(Ljava/lang/Object;)V`, entered at `po.smali:36`; the
connection is stored at `po.smali:108`.

The choice is a two-key dispatch performed at `po.smali:111-183` and its labelled
branches:

- **key 1** = `App.O00OO00OOOOOO0OOO00O().ordinal()` — the scooter model enum `zm`
  (`App.smali:153-165`).
- **key 2** = `App.O000OO0OO0OOO00OO000()` — an app-wide `int`
  (`App.smali:139-151`), set only from `MainActivity.seekBar_protocolVersion`
  (`ic.smali:295-316,382`) or a stored preference (`nc.smali:120-153`). The SeekBar is
  `android:max="5" android:progress="5"` and `android:visibility="gone"`
  (`apktool-out/res/layout/activity_main.xml:21`), so the value is **0-5, defaults to
  5, and is not user-reachable in normal use.**
- three Firebase Remote Config flags act as remote switches inside some branches:
  `nbsecft`, `nbsecf`, `ecdh_old` (`po.smali:156`, `:447`, `:535`, `:623`, `:734`).

`zm` ordinals (declaration order; the six Xiaomi entries were extracted and match
report 08):

| ordinal | model | `po.smali` branch |
|---|---|---|
| 0 | `UNKNOWN` | default |
| 1 | `ONE` | default |
| 2 | `MINI` | `:722` |
| 3 | `MINI_MAX` | default |
| 4 | `MARK2` | default |
| 5 | `ESCOOTER` | `:611` |
| 6 | `VIO` | default |
| 7 | `KART` | default |
| 8 | `KART_PRO` | default |
| 9 | `KART_PRO_LAMBO` | default |
| 10 | `NANO` | default |
| 11 | `MINI_KIDS_MI` | default |
| 12 | `MINI_KIDS` | default |
| 13 | `MARK3` | default |
| 14 | `SCOOTER2` | `:523` |
| 15 | `SCOOTER2_P` | default |
| 16 | `MI_SCOOTER_PRO` | `:255` (`pswitch_1`) |
| 17 | `MI_SCOOTER_1S_DE` | `:186` (`pswitch_0`) |
| 18 | `MI_SCOOTER_LITE` | `:186` |
| 19 | `MI_SCOOTER_1S` | `:186` |
| 20 | `MI_SCOOTER_PRO2` | `:186` |
| 21 | `MI_SCOOTER_3` | `:186` |
| 22 | `WILD_STEEL_DUST` | default |
| 23 | `SCOOTER_G30` | `:435` |
| 24 | `STEELDUST` | default |
| 25 | `MEBIKE` | default |
| 26 | `SCOOTER_AIR` | default |
| 27 | `F_Series30` | default |
| 28 | `F_Series60` | default |

Ordinals 0-5, 7, 9, 11, 12, 24, 25, 26 were read directly from the `<clinit>`
constructions; 6, 8, 10, 13-23, 27, 28 are **inferred** from declaration order, which
the thirteen directly-read values confirm is ordinal order.

Dispatch result, by model group and `protocolVersion` (`pv`):

| model group | `pv == 2` | `pv == 1` | any other `pv` (incl. default 5) |
|---|---|---|---|
| `MINI` (2) — `po.smali:722-832` | RC `nbsecf == 1` → `fn`, else `gn` | `mn` | `nn` |
| `ESCOOTER` (5) — `po.smali:611-719` | RC `ecdh_old == 1` → `kn`, else `gn` | `mn` | `nn` |
| `SCOOTER2` (14) — `po.smali:523-608` | RC `nbsecf == 1` → `fn`, else `gn` | `bn` | `bn` |
| `MI_SCOOTER_PRO` (16) — `po.smali:255-318` | RC `ecdh_old == 1` → `gn`, else `kn` | `mn` | `nn` |
| `MI_SCOOTER_1S_DE/LITE/1S/PRO2/3` (17-21) — `po.smali:186-252` | `kn` | `mn` | `nn` |
| `SCOOTER_G30` (23) — `po.smali:435-520` | RC `nbsecf == 1` → `fn`, else `gn` | `bn` | `bn` |
| every other ordinal — `po.smali:142-183`, `:345-432` | RC `nbsecft == 1` → `gn`, else `fn` | `mn` | `bn` |
| *no model* (device object null) — `po.smali:2697-2702` | logs `'mbledevice null'` and returns | | |

Each strategy is a `vm` subclass constructed with the same
`RxBleConnection` + two `ConcurrentLinkedQueue`s:

| strategy | class | framing (cross-check, see report 03/05) |
|---|---|---|
| `fn` | `dalvik/…/fn.smali` (extends `vm`, `fn.smali:2-3`) | `5A A5` (`fn.smali:135`, `:1241`) |
| `gn` | `dalvik/…/gn.smali` (`gn.smali:2-3`) | `5A A5` (`gn.smali:155`, `:221`) |
| `mn` | `dalvik/…/mn.smali` (`mn.smali:2-3`) | `55 AB` (`mn.smali:768`) |
| `bn` | `dalvik/…/bn.smali` (`bn.smali:2-3`) | `5A A5` (`bn.smali:373`) |
| `nn` | `dalvik/…/nn.smali` (`nn.smali:2-3`) | `55 AA` (`nn.smali:450`) |
| `kn` | `dalvik/…/kn.smali` (`kn.smali:2-3`) | no `55`/`5A` magic; separate AES path; ctor constants `0x20,0x23,0x21,0x24,0x22,0x25` at `kn.smali:88-116` |

**Transport versus framing.** Framing varies by strategy; the *GATT* transport does
not, with one exception:

- `fn`, `gn`, `mn`, `bn`, `nn` — write `6e400002`, notify `6e400003`, nothing else.
- `kn` — write `6e400002` for the main command path (`kn.smali:2377`), plus
  **notify `00000010` and `00000019`** (`kn.smali:895-901`, `:961-967`) and a
  **read of `00000004`** (`kn.smali:2709`), plus auxiliary writes to `00000019`
  (`kn.smali:1031`) and `00000010` (`kn.smali:2643`). `kn` is the only strategy that
  overrides `vm.OOOOOOO0OOOOO0O00OO0` (read-a-register-and-callback; the base class
  throws `NotImplementedException`, `vm.smali:167-177`), and the only caller is
  `po.smali:1375`, gated on `protocolVersion == 0`.
- Additionally, **`protocolVersion == 1` leaves NUS entirely**: `po.smali:1383-1442`
  reads `00000014` then `00000002`, and the continuation `fo` subscribes to
  **notify `00000001`** and starts a 3-second heartbeat that writes the 4-byte
  constants `90CA85DE` / `00BC43CD` to **`00000010`** (`fo.smali:52-115`,
  `yn.smali:120-190`, `wn.smali:36-160`). The auth handshake on that path writes its
  16-byte token to `00000001` (`io.smali:126-134`, `:289-312`).

So: **two GATT layouts exist in the app — NUS (`6e400002`/`6e400003`) and the Xiaomi
classic layout (`00000001`/`00000002`/`00000004`/`00000010`/`00000014`/`00000019`) —
and which one is used is decided by the model ordinal plus the hidden protocol-version
integer, not by what the device advertises.**

---

## 5. MTU and write fragmentation

**The app never negotiates an MTU and never learns one.**

- `requestMtu` occurs **zero times** in the entire decoded APK (app and library).
- `onMtuChanged` occurs once, as RxAndroidBle's pass-through override
  (`oe0$OOOOOOO0OOOOO0O00OO0.smali:739-756`), whose body is a logging call
  (`af0.OO0O0OOO00O00OOO0000`) followed by `invoke-super`; the value is not stored.
- `RxBleConnection`'s interface `kb0` has no `requestMtu` and no `observeMtuChanges`
  method (`kb0.smali:15,27,40`), so there is no call site for either.
- No string in the decrypted table contains "MTU".

**Chunk size is a hard-coded 20 bytes.** Every command strategy ends with the same
loop; `mn.smali:826-864` is the clearest instance and reads, in Java terms:

```java
byte[] data = out.toByteArray();
queue.clear();
int remaining = data.length, off = 0;
while (remaining > 0) {
    int n = Math.min(remaining, 0x14);              // 20 bytes, literal
    connection.writeCharacteristic(
        UUID.fromString(op.OO00000OOOOOOOO0000O),   // 6e400002
        Arrays.copyOfRange(data, off, off + n))
      .subscribe();                                 // fire-and-forget, result discarded
    remaining -= n; off += n;
}
```

Identical loops: `fn.smali:1280-1310`, `gn.smali:960-990`, `bn.smali:415-451`,
`nn.smali:490-525`, `kn.smali:2353-2377`. There is **no** `MTU − 3` arithmetic
anywhere: `0x14` is written as a literal and is never derived from anything.

Two further details that a reimplementation must not assume away:

- **Every write is a write-with-response.** There is no `setWriteType` call anywhere
  in the APK and no use of `WRITE_TYPE_DEFAULT` / `WRITE_TYPE_NO_RESPONSE`. RxAndroidBle's
  write operation only does `setValue(bytes)` then `BluetoothGatt.writeCharacteristic(…)`
  (`ef0.smali:114-119`), so Android's constructor default `WRITE_TYPE_DEFAULT`
  (ATT_WRITE_REQ, acknowledged) applies to all traffic. The app's own
  `WRITE` / `WRITE_NR` command-type enum
  (`app/peretti/m365tools/model/BaseCommand$O0OOOOOO00OOOOO000O0.smali:84,98`) is a
  protocol-level notion, **not** a GATT write type, and does not survive to the radio.
- **Chunks are submitted without waiting.** `.subscribe()` is called and the returned
  `Disposable` is dropped (`mn.smali:858`), so the next chunk is enqueued immediately;
  ordering relies entirely on RxAndroidBle's internal operation queue.

---

## 6. Comparison with `/home/kali/ScooterHacking/repo`

### 6.1 Where the fork is stricter (and correct where M365 Tools is not)

| Topic | M365 Tools 1.8.0 | Fork | Assessment |
|---|---|---|---|
| Characteristic lookup | enumerate **all** services, take the first `getCharacteristic(uuid)` hit — no service UUID checked (`mb0.smali:46-84`) | `gatt.getService(serviceUuid)?.getCharacteristic(charUuid)` — requires the service UUID too (`BleManager.kt:382-383`, `:424-425`) | **Fork is stricter.** M365 Tools matches a characteristic in *any* service, so it is immune to a vendor moving a characteristic between services; the fork returns `false` on a service-UUID mismatch even when the characteristic is present elsewhere. For maximum compatibility, iterate services like `mb0` does and treat a missing service UUID as non-fatal. |
| MTU | never requested, never read; 20-byte literal chunk (`mn.smali:832-835`) | records granted MTU (`BleManager.kt:159-173`) and sizes chunks `MTU − 3`, clamped to 255 (`MtuFragmenter.kt:30-66`) | **Fork is better**, and M365 Tools confirms the failure mode the fork documents: the reference app is permanently pinned to 20-byte writes even on a link that granted 512. |
| Write type | implicit `WRITE_TYPE_DEFAULT` always (`ef0.smali:114-119`) | explicit per call (`BleManager.kt:390-400`) | Fork better; also means M365 Tools' `WRITE_NR` command class never actually becomes a no-response ATT write. |
| Write acknowledgement | fire-and-forget `.subscribe()`, no completion wait (`mn.smali:858`) | awaits `onCharacteristicWrite` when `waitForResponse` (`BleManager.kt:365-413`) | Fork better; but note M365 Tools' model is *deliberately* pipelined — it never blocks on a chunk. |
| Notify vs indicate | notify only, hard-wired (`me0.smali:59`, `ma0.smali:52`); indicate-only devices rejected | discovery accepts `NOTIFY or INDICATE` (`GattProfileDiscovery.kt:65`, `BleManager.kt:111-114`) but enablement writes `ENABLE_NOTIFICATION_VALUE` only (`BleManager.kt:444`) | **Fork has a latent bug**: it will select an indicate-only channel and then arm it with the notify value. Either accept only `PROPERTY_NOTIFY`, or branch the descriptor value on `PROPERTY_INDICATE`. M365 Tools avoids the problem by refusing indicate-only outright. |
| Missing characteristic | `BleCharacteristicNotFoundException` → log (or disconnect); **no retry with another UUID** (`vn.smali:36-51`, `ro.smali:44-56`) | `cont.resume(false)`; caller decides (`BleManager.kt:384-388`) | Equivalent honesty; neither falls back. |

### 6.2 Where M365 Tools does something the fork does not

| Topic | M365 Tools | Fork | What is missing in the fork |
|---|---|---|---|
| **Second GATT layout** | Full Xiaomi classic layout: write/notify `00000001`, read `00000002` and `00000014`, read `00000004`, write+notify `00000010`, write+notify `00000019` (§1.1, §4) | Only NUS is implemented; `AUTH_UPNP = 00000010` and `AUTH_AVDTP = 00000019` exist as constants (`BleManager.kt:42-43`) and are used against service `0000fe95` (`ScooterRepository.kt:826,830,888,…`), but `00000001`, `00000002`, `00000004` and `00000014` appear nowhere | The fork's auth handshake writes `00000010`/`00000019` **inside the `0000fe95` service**. M365 Tools, by contrast, never uses `0000fe95` as a GATT service at all — it only reads the `0000fe95` *service-data* field from the advertisement (`tn$OO00000OOOOOOOO0000O.smali:120-130`) — and addresses `00000010`/`00000019` service-agnostically. If the fork's `getService(0000fe95)` returns null on a device that exposes those characteristics under another service, the fork's handshake silently no-ops. **This is the single most actionable difference in this report.** |
| **Model-selected transport** | Strategy chosen from `zm.ordinal()` × hidden `protocolVersion` before any GATT call (§4) | Protocol selected from observed behaviour (`detectedProtocolOrPlaintext()`, e.g. `ScooterRepository.kt:757-760`) | M365 Tools shows the vendor itself needs per-generation framing; the fork's behavioural detection is arguably better (it does not rely on a model table), but it has no equivalent of the `pv == 1` classic path. |
| **GAP / Device Name** | Present in the string table (`00001800`, `00002a00`) but dead — the app never reads the Device Name characteristic | — | Nothing to adopt; recorded so the next reader does not chase it. |
| **Heartbeat** | 3-second `interval(1, 3, SECONDS)` writing `90CA85DE` / `00BC43CD` to `00000010` while the handshake is unfinished (`yn.smali:137-144`, `wn.smali:36-61`, `op.smali:231-255`) | none | Only relevant if the fork ever implements the classic layout. |

### 6.3 Where the two agree

- Both use NUS `6e400002` (write) / `6e400003` (notify) as the primary data plane.
- Both pin the default chunk to 20 bytes.
- Neither implements a fallback from a missing characteristic to an alternative UUID.
- Both treat "no reply" as a protocol failure rather than an empty read.

---

## 7. What the app does **not** do

Stated explicitly, because several of these are easy to assume:

1. It does **not** require, filter on, or even mention a service UUID when connecting.
   No `getService(UUID)`. `0000fe95` is a *scan* filter only; `6e400001` (NUS service)
   is present in the string table and dead.
2. It does **not** enumerate services and pick by predicate. It delegates to
   RxAndroidBle's `getCharacteristic(uuid)`, which scans every discovered service for
   that one characteristic UUID and returns the first hit (`mb0.smali:46-84`). The
   *predicate* is "characteristic UUID equals X"; the service is never part of it.
3. It does **not** fall back when the expected characteristic is absent — no
   exception-type check, no `onErrorResumeNext`, no retry against a different UUID.
   The variant is picked ahead of time and a miss is terminal for the session.
4. It **never uses indications**, and cannot use an indicate-only characteristic.
5. It **never negotiates an MTU** and **never records** the one it is given.
6. It **never writes without response** at the ATT level, despite having a `WRITE_NR`
   protocol command class.
7. It **never writes the CCCD itself** — that is entirely inside RxAndroidBle.
8. It never performs a `UUID(long,long)` construction or a 16-bit-short-to-base-UUID
   expansion in app code. The only such code in the APK is RxAndroidBle's own
   deprecated `UUIDUtil` (`gj0.smali:40-219`), which the app does not call; all app
   UUIDs come from `UUID.fromString` on decrypted full-length strings.
9. It does not read the Device Name characteristic (`00002a00`) or the GAP service
   (`00001800`) on any live path.

---

## 8. Unverified / needs hardware

No scooter and no phone are attached. Everything here is read from the DEX, the
resources and one native library. Specifically unverified:

- **No frame has been exchanged with any scooter.** The claim that a given model
  *actually* exposes the characteristics in §1.1 under the service layout the app
  assumes is an inference from the code, not an observation. In particular: whether a
  Xiaomi M365 exposes `00000001`/`00000002`/`00000010`/`00000014` in a service other
  than `0000fe95` — which is what the fork's design assumes it does *not* — cannot be
  settled without a GATT dump.
- **The `protocolVersion` semantics are unknown.** It is an `int` 0-5 with default 5,
  set from a hidden SeekBar and from a stored preference; nothing read here says what
  the numbers *mean* or which one a real scooter needs. It is **not** claimed to be a
  protocol version in the scooter's own sense.
- **The Remote Config flags (`nbsecft`, `nbsecf`, `ecdh_old`) are unread at run time.**
  They are `FirebaseRemoteConfig.getLong(key)` values whose live settings are decided
  server-side. The branch tables in §4 therefore describe the *code paths*, not which
  path a shipping install takes today.
- **`zm` ordinals 6, 8, 10, 13-23, 27 and 28 are inferred** from declaration order
  (directly-read ordinals 0-5, 7, 9, 11, 12, 24, 25, 26 agree with that ordering).
  A single reordering in the enum would shift the whole `po` dispatch table.
- **The `pv == 1` classic path is entirely unexercised** by any reading here. It is
  reachable only when `App.O0000O00OO00OOOO0000 == 1`, which the visible UI cannot set.
- **Whether the 20-byte chunk is sufficient on real hardware is untested.** 20 is the
  ATT-default payload size, so it is safe on an un-negotiated 23-byte link — but the
  app never checks, and if a scooter's ATT_MTU were ever below 23 (not permitted by the
  spec, but see the fork's `MtuFragmenter.kt:63-66` guard) the fragments would be
  oversized and silently discarded.
- **`libnative-lib.so` was read as a byte string, not disassembled.** The base64
  constant decoding to `00000014-…` is verifiable independently; that
  `BluetoothItem()` returns *that* constant — rather than assembling it or returning
  something else — is inferred from the constant's presence next to the JNI symbol.
- Only a Xiaomi M365 (MAC `C7:B8:DC:3B:A1:B2`) is available for eventual verification.
  Every Ninebot/Segway conclusion in this report is documentation-grade at best.
