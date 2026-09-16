# 05 — Connection Establishment, Device Identification & Model/Protocol Dispatch

**Target:** `com.basse.scootbatt` 1.9.2 (versionCode 136), R8-obfuscated.
**Scope:** scan → connect → MTU → service discovery → subscription → identification → model/protocol
selection → dispatch table → polling/watchdog/reconnect. Nothing about the crypto internals or the
write-command inventory beyond what the connection flow needs (those are reports `01`, `03`, `04`).
**Method:** static only. Every claim carries `file:line`.

**Paths.** `SOURCES = /home/kali/ScooterHacking/re/scootbatt/jadx-out/sources/`. All `file:line`
citations are relative to `SOURCES`. A second, independent evidence source was generated while
working: a full baksmali disassembly of all 4 dex files at
`/home/kali/ScooterHacking/re/scootbatt/scratch/apk/smali{,_classes2,_classes3,_classes4}/`
(produced with `apktool d --no-res`). Where jadx's output is mangled (`Code decompiled
incorrectly`) I re-derived the semantics from that smali and say so explicitly. Note the naming
convention of the smali: jadx renames a class member `X` to `f<id>X` / `m<id>X` / `mo<id>X`, so
`ScooterFragment.f4047X2` is the dex field `X2`, `m3299r0` is the method `r0`, `p000.C0993mx` is
the **default-package** class `mx` (jadx renames the default package to `p000`).

---

## 0. TL;DR for the integrator

1. **Scanning is unfiltered** (no `ScanFilter`, no service-UUID filter). Devices are recognised
   from the **manufacturer-specific AD element of company ID `0x4E42`** ("NB"): after the bytes
   `FF 4E 42`, the following 6 bytes are `[modelCode][cryptoFlag][?][0][0][?]`. `SOURCES/p000/C1142ow.java:32-125`.
2. **`requestMtu` is never called** — not once, in any class (verified by grep over both the jadx
   tree and the full smali tree). Chunking therefore uses the 23-byte default ATT MTU → 20-byte
   payload (`C0993mx.f19701r = 20`). `SOURCES/p000/C0993mx.java:97`.
3. The **transport protocol variant is chosen at connect time from the GATT table**, in two steps:
   *presence of the Xiaomi `0000fe95` service* decides "elliptic/auth" vs "plain NUS", and only for
   plain NUS is the *Scooter flags* triple used (`useCrypto → NinebotCrypto`, `isXiaomi → Xiaomi`,
   else `Ninebot`). `SOURCES/p000/C0993mx.java:446-508`, `.../ScooterFragment.java:900`.
4. The **model→protocol key table lives in the advertisement parser** (`C1142ow`, §3.1), not in a
   database. A *second*, application-level identification (serial number → region/variant string in
   `ry4.f26566c`) happens after connect (`un0.mo2829h` case 1, §3.3).
5. **Two request/response dispatchers exist**: the global one in `ScooterFragment` (direction-keyed
   maps, §4) and per-screen ones (`dp3`, `an3`, `yn3`) keyed by the pair (register, direction)
   which only *enqueue* requests; all responses are parsed by the global maps.
6. **Steady-state telemetry = polled, not pushed**: a 1 Hz loop (500 ms while the battery screen is
   open) sends `READ` of register `0x31` to the BMS direction (`0x22`, or `0x23` when an external
   battery is present). There is **no explicit heartbeat/watchdog**; a dead link is detected by the
   GATT disconnect callback or by request time-outs (3 attempts, then the request is dropped).
7. **`BleForegroundService.f3921p = 7000` is _not_ a poll interval and _not_ a watchdog.** It
   throttles widget-snapshot persistence + Glance widget refresh triggered from the telemetry
   collector. `SOURCES/p000/C0359ix.java:130-137`, `SOURCES/p000/op3.java:22-24`.

---

## 1. GATT profile and constants

| Constant | Value | Meaning | Evidence |
|---|---|---|---|
| `C0993mx.f19677s` | `00002902-0000-1000-8000-00805f9b34fb` | CCCD | `C0993mx.java:25` |
| `C0993mx.f19678t` | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Nordic UART **service** (NUS) — used *only* in service discovery, **not** as a scan filter | `C0993mx.java:28`, `:455` |
| `C0993mx.f19679u` | `6e400002-…` | NUS RX — app writes requests here (also used as the write char in the auth path) | `C0993mx.java:31` |
| `C0993mx.f19680v` | `6e400003-…` | NUS TX — notifications (all responses) | `C0993mx.java:34` |
| `C0993mx.f19681w` | `0000fe95-0000-1000-8000-00805f9b34fb` | Xiaomi service — marks the "elliptic/auth" scooter | `C0993mx.java:37` |
| `C0993mx.f19682x` | `00000010-…` | char `0x0010` **inside the fe95 service** (log name `chrl`) | `C0993mx.java:40`, `:471` |
| `C0993mx.f19683y` | `00000019-…` | char `0x0019` **inside the fe95 service** (log name `authCh`) | `C0993mx.java:43`, `:472` |
| `C0993mx.f19701r` | `20` | write chunk size = "MTU − 3" | `C0993mx.java:97`, `:435-440` |
| `C0993mx.f19697n` | Boolean | `true` = auth/elliptic profile (fe95 present **and** both chars found) | `C0993mx.java:94`, `:466-473` |
| `C0993mx.f19698o` | boolean | a write is in flight (queue pump flag) | `C0993mx.java:85` |
| `C0993mx.f19699p` | boolean | "socket closed / disconnecting" — every callback returns early when set | `C0993mx.java:88` |
| `C0993mx.f19700q` | boolean | "fully subscribed, app-level connected" (set only in `onDescriptorWrite`) | `C0993mx.java:91`, `:428` |

> **Correction to the shared context.** `00000010`/`00000019` are **not** the standard Device
> Information service (that would be `0000180a-…` with `00002a26-…`). They are proprietary
> characteristics of Xiaomi's `0000fe95` service, and the app does not *read* them at all — it
> *subscribes* to them (CCCD) and consumes notifications. There is no `readCharacteristic` call
> anywhere in the app (grep over jadx tree + smali tree).

---

## 2. Connection flow, step by step

### 2.1 Scan

| Step | What happens | Evidence |
|---|---|---|
| Start trigger | `BluetoothScanFragment` state observer → `cm3.m3121f()` when the adapter is on and the location requirement is satisfied | `BluetoothScanFragment.java:964-984`, `cm3.java:77-93` |
| Settings | `wl3` → `xl3`: **scanMode = 2 (`SCAN_MODE_LOW_LATENCY`)**, callbackType = 1 (all matches), matchMode = 1 (aggressive), numOfMatches = 3, reportDelay **0** (batching off, `f33212i=false` ⇒ the delay is not even applied), legacy=false, phy not set | `cm3.java:82-88`, `wl3.java:8-47,51-69`, `C0059ay.java:2399-2409` |
| **Filters** | **none.** `m1730s(...)` passes `Collections.EMPTY_LIST`; `m1731t` therefore calls `bluetoothLeScanner.startScan(null, settings, cb)` — no hardware/software `ScanFilter` at all | `C0059ay.java:2277`, `:2310-2334` |
| Callback | `bm3` (per-fragment `rl3` implementation) receives every advertisement | `bm3.java:59-105`, `C0059ay.java:2334` |
| Model filter | `new C1142ow(scanRecord.rawBytes)` → if protocol key == `"unknown"` the device is **dropped** | `bm3.java:76-80` |
| RSSI filter | device kept only while `maxRssi >= -80` dBm (max is tracked per address) | `bm3.java:85-95`, `nx0.java:76-80`, `C0884k0.java:111` |
| Name | the AD **local name** (AD type 0x09/0x08) with non-printable chars stripped | `ul3.java:52-60`, `nx0.java:61-75` |
| Dedup | per-MAC `nx0` entries in a `ConcurrentHashMap`; last advertisement wins, max RSSI kept | `bm3.java:81-94`, `nx0.java:61-80` |
| Stop triggers | adapter off / location lost (`am3.java:46`, `bm3.java:48`), fragment pause (`BluetoothScanFragment.java:675-684`), scooter found (`:679`), connect attempt (`m3307z0`, `ScooterFragment.java:2079-2084`) | as cited |
| Timeout | **no scan timeout.** Continuous scan until one of the stop triggers. (`Duration.ofMinutes(1)` at `BluetoothScanFragment.java:155` is a staleness test for *saved* scooter entries, used at `C0288gy.java:76-90`; the scanner library's 10 000 ms constants `xl3.f34463p/f34464q` are only used for batched-scan flushing, `AbstractC1403vx.java:131`, `RunnableC1297t2.java:295-306`.) | as cited |

On a hit, the fragment builds the domain object and navigates:

```java
new Scooter(name, c1142ow.f22888a /*protocol key*/, c1142ow.f22892e /*friendly*/, 
            c1142ow.f22890c /*useCrypto*/, c1142ow.f22889b /*isXiaomi*/, bleDevice, isFav)
```
`BluetoothScanFragment.java:761`. `"unknown"` never reaches here. Nothing else (service UUID,
manufacturer ID whitelist, MAC prefix) is checked.

### 2.2 Bind + connect

`ScooterFragment.mo3236g`/`mo3233d` → `ho3.onServiceConnected` (`ho3.java:103-140`) →
`BleForegroundService.m3240k(...)` (`BleForegroundService.java:349-437`):

1. drains the events buffered by the service while the fragment was absent
   (`f3909E` = notifications, `f3910G` = errors/connected; `mo3231b/c/d/e/f`), `ho3.java:80-105`,
   `BleForegroundService.java:144-262`.
2. `if (c0993mx.f19700q || c0993mx.f19692i != null) { log "already connected"; return; }`
   (`BleForegroundService.java:373-376`).
3. registers the two `C0917kx` receivers: `com.basse.scootbatt.Disconnect` broadcast and the
   `BOND_STATE_CHANGED` + `PAIRING_REQUEST` filter (`:381-383`, `C0993mx.java:107-112`).
4. **`device.connectGatt(context, /*autoConnect*/ false, callback, /*transport*/ 2 = LE)`**
   (`:385`; smali `BleForegroundService.smali:1868`). `autoConnect=false` ⇒ a direct connect, which
   is why the scan must have seen the device recently.
5. `f3919m = true`, publishes `hi4.f10556k = <MAC>` (global "currently connected address", used by
   widget clicks `np3.java:204`, `MainActivity.java:340-341`), starts the foreground notification,
   and launches the telemetry collector `C0923l0(…, 4)` over 8 combined flows (`:391-423`).
6. starts the trip recorder `C0305he` if the user preference is on (`:424-436`).

`getGattCallback()` order: `onConnectionStateChange(STATE_CONNECTED)` → `discoverServices()`
(`C0993mx.java:324-331`).

### 2.3 MTU

* The app **never calls `requestMtu`** — grep for `requestMtu` over `SOURCES/` and over the whole
  smali tree returns nothing. The only GATT APIs used are `connectGatt`, `discoverServices`,
  `getServices`, `setCharacteristicNotification`, `writeDescriptor`, `writeCharacteristic`,
  `disconnect`, `close`.
* Consequence: the write chunk size stays at the constructor default **20 bytes**
  (`C0993mx.java:97`) and it is only ever updated in `onMtuChanged` (`:435-440`):
  `f19701r = mtu - 3` when `status == 0` (logged as "payload size N").
* **Hazard:** `onMtuChanged` unconditionally calls `m10816a(gatt)` — i.e. if the OS or the
  peripheral ever negotiates an MTU mid-session, the *whole* CCCD subscribe chain is re-run
  (`:441`), which re-writes the last descriptor and therefore re-invokes `mo3233d`/`mo3236g`
  (§2.5), resetting the app-level state. On Android 14+ the stack may perform MTU negotiation on
  its own, so this is not purely theoretical.
* The MTU-derived payload size is snapshotted into the reassembler at connect time:
  `m3291j0.f19863d = new hl2(mode, c0993mx.f19701r)` (`ScooterFragment.java:1198-1214`, `:893-909`).
  A later MTU change does **not** update `hl2`, so a mid-session MTU change would desynchronise
  chunk reassembly (§5).

### 2.4 Service discovery and profile selection

`onServicesDiscovered` (`C0993mx.java:446-508`) walks all services once, remembering:
`f19678t` (NUS) → `bluetoothGattService`, `f19681w` (fe95) → `bluetoothGattService2`.

*If both exist:*
```java
f19697n = TRUE;                       // auth/elliptic profile
f19693j = nus.getCharacteristic(6e400003)   // NUS TX  → notifications
f19694k = nus.getCharacteristic(6e400002)   // NUS RX  → writes
f19695l = fe95.getCharacteristic(00000010)  // "chrl"
f19696m = fe95.getCharacteristic(00000019)  // "authCh"
```
*Otherwise / if either fe95 characteristic is `null`:* `f19697n = FALSE` and the two NUS
characteristics are classified by their **write property bit (`properties & 8`)**:
both writable → `IOException("multiple write characteristics")`; exactly one → that one becomes the
write char `f19694k`, the other the notify char `f19693j`; neither → error
(`C0993mx.java:474-497`). Note this branch **overwrites** `f19693j/f19694k` with the NUS pair even
when the fe95 service was found but had no `0x10`/`0x19` char.

Failure path: if (`f19690g == null || f19693j == null || f19694k == null`) and not auth, or auth
without `f19695l/f19696m/f19693j` → `m10818c(IOException("no serial profile found"))`
(`:503-504`) → `BleForegroundService.mo3231b` → connect-error dialog.

### 2.5 Subscription (CCCD) order — this is a hard ordering constraint

`m10816a(gatt)` (`C0993mx.java:117-178`) is the subscribe state machine; it is driven again from
`onDescriptorWrite` (`:347-431`) using characteristic identity as the state:

**Plain NUS profile (`f19697n == FALSE`)** — one step:
1. `writeChar(f19694k).properties & 0x0C != 0` else `IOException("write characteristic not writable")`;
2. `setCharacteristicNotification(f19693j, true)`;
3. CCCD of `f19693j`: `ENABLE_INDICATION` (0x02 0x00) if `properties & 0x20`, else
   `ENABLE_NOTIFICATION` if `properties & 0x10`, else error;
4. `writeDescriptor(CCCD)`;
5. `onDescriptorWrite(char == f19693j)` → **`mo3233d()`** (protocol mode + objects) and
   `f19700q = true`, log `"connected"` (`:413-430`).

**Auth / elliptic profile (`f19697n == TRUE`)** — three steps, strictly serialised by
`onDescriptorWrite`:
1. `setCharacteristicNotification(f19696m /*fe95 0x0019 "authCh"*/, true)` + CCCD
   (indication if `&0x20`, else notification if `&0x10`) → `writeDescriptor`;
2. `onDescriptorWrite(char == f19696m)` → `setCharacteristicNotification(f19695l /*fe95 0x0010
   "chrl"*/, true)` + its CCCD → `writeDescriptor` (`:353-382`);
3. `onDescriptorWrite(char == f19695l)` → requires `f19695l.properties & 0x0C != 0`
   (i.e. `0x0010` must be writable — it carries the auth challenge), then
   `setCharacteristicNotification(f19693j /*NUS TX*/, true)` + its CCCD → `writeDescriptor`
   (`:383-412`);
4. `onDescriptorWrite(char == f19693j)` → **`mo3236g()`** + `f19700q = true`
   (`:413-430`).

Notes for a reimplementation:
* The status of **every** descriptor write is checked (`i != 0` → `IOException("write descriptor
  failed")`). There is no retry at this level.
* `onCharacteristicChanged` ignores the `f19700q` flag — data arriving between subscription and the
  final descriptor write would be processed (only `f19699p` gates it) (`:264-284`).
* `f19698o` + `f19684a` implement a **one-in-flight write queue** with FIFO chunking; the next frame
  is only written from `onCharacteristicWrite` (`C0993mx.java:237-260`, `:288-320`,
  `BleForegroundService.java:482-531`). Ordering of writes = queue order; nothing is written
  concurrently.
* `writeCharacteristic` failure at any point → `m10819d(IOException("write failed"))` → `mo3232c`
  (treated as "connection lost", not as a local error) (`:255-258`, `:296-297`, `:528-530`).

### 2.6 Protocol-mode selection and post-connect init

`BleForegroundService.mo3233d()` / `mo3236g()` land in `ScooterFragment.mo3233d()`
(`ScooterFragment.java:882-921`) or `.mo3236g()` (`:1187-1254`):

| Path | Mode assigned (`n13.f19861b` = `xq0`) | Evidence |
|---|---|---|
| Plain NUS (`mo3233d`) | `useCrypto ? NinebotCrypto : (isXiaomi ? Xiaomi : Ninebot)` | `ScooterFragment.java:900` + smali `ScooterFragment.smali` (`xq0;->c/d/a`) |
| fe95 auth (`mo3236g`) | **always `XiaomiCrypto`** (`xq0.f34613e`), then the elliptic pairing flow | `ScooterFragment.java:1205` + smali (`xq0;->e`) |

`xq0` = `{Ninebot(0), NinebotCrypto(1), Xiaomi(2), XiaomiCrypto(3)}` (`xq0.java:25-35`); this is the
*transport* selector used by `pe4.m12550k` and the frame reassembler `hl2`.

Then, in both paths: `new pe4(model, mode)`, `pe4.f23530e = new C1375v6(scooter.getName())` (the
Ninebot crypto object; the app feeds it `Scooter.getName()`, which for a scanned device is the
advertised local name — the actual key derivation is report `03`'s subject), `new hl2(mode, f19701r)`,
a new request queue `C1286ss(service, pe4)`, state → `Connected`.

* **Plain path** then runs `lo3` case 3 → `ScooterFragment.m3297p0()` (`ScooterFragment.java:919`,
  `lo3.java:144-160`). This coroutine only does something for **NinebotCrypto**; for every other
  mode it sets state = `Paired` immediately (smali `ScooterFragment.smali` method `p0`,
  `.line 79-98`).
* **Auth path** runs `m3296o0()` (`ScooterFragment.java:1326-1356`): creates the elliptic VM
  (`sa6` + `fm2` with `pe4(sharedPreferences "app_prefs", MAC, callback)`) and starts `q21` →
  `fm2.m5356e()` (connect/auth) → `fm2.m5365n()` (registration check) → on failure `C0057aw` case 4
  emits `"REG_VERIFY_FAIL"`. State → `PendingPairing`. (Details in report `03`.)

**NinebotCrypto pairing sequence** (smali `ScooterFragment.smali`, method `p0`; the two hard-coded
frames are in `.array-data` blocks at the end of `p0`, `0x3E 0x21 0x5B 0x00` and the 20-byte
`0x3E 0x21 0x5C 0x00 4A EE BD 73 E2 16 1C 11 2D 06 5A 49 CC 6E 8B B7`):

| Phase | Action | Loop condition |
|---|---|---|
| 0 | state → `PendingLegacyPairing` | — |
| 1 | write `3E 21 5B 00` (dir `0x21` BLE, action `0x5B` = read serial); **delay 900 ms** (`0x384`) | until flag `T2` (set by a `0x5B` response with len 30) |
| 2 | write the 20-byte `3E 21 5C 00 <16-byte key>` every **500 ms** (`0x1f4`); in the *first* iteration only, `b0()` (write serial `3E 21 5D 00 <14-byte serial>`) is sent after that 500 ms delay | until flag `U2` (`U2` is set by `0x5C` **only if `reg == 1` or the scooter is Xiaomi**, or by `0x5D`) |
| 3 | call `b0()` (write serial, action `0x5D`) every **500 ms** | until flag `W2` (set by a `0x5D` response with `reg == 1`) |
| 4 | state → `Paired` (a `0x5D` response with `reg == 1` also sets `Paired` immediately from the dispatcher) | — |

The flags are set by the notification dispatcher (smali `ScooterFragment.smali` method `r0`,
`:pswitch_0` = action `0x5D`, `:pswitch_1` = `0x5C`, `:pswitch_2` = `0x5B`): `T2` on action `0x5B`
(len==30 → the 14-byte serial is copied out of payload[16..30)), `U2`/`W2` as tabulated.
**This is the "must send X before Y" chain**: nothing else is polled until `Paired`.

### 2.7 State machine, error → reconnect

`ee0` = `{PendingReconnect(0), Disconnected(1), PendingConnect(2), Connected(3),
PendingLegacyPairing(4), PendingPairing(5), Paired(6)}` (`ee0.java:34-50`); the observer is
`ao3` case 5/8 (`ao3.java:181-226`, `:265-360`, mapping table `go3.f9446a`).

* Error callbacks: `C0993mx.m10818c` (fatal, from discovery/subscribe failures) →
  `BleForegroundService.mo3231b` → `ScooterFragment.mo3231b`; `C0993mx.m10819d` (write failure or
  `"gatt status N"` on an *already established* link) → `mo3232c`
  (`C0993mx.java:216-233`, `:333-340`).
* Both handlers: ignore if already `PendingReconnect`; cancel the local connect job; **if
  `Preferences.m3210f()` ("autoConnectImmediately", index 7 of `Preferences.java:238`) is set** →
  toast *"Connection lost. Trying to reconnect."* and state → `PendingReconnect`, else show the
  "serial error" dialog (`ScooterFragment.java:715-801`, `Preferences.java:566-568`).
* `PendingReconnect` (ao3 case 8, branch 1) restarts the **scanner** with the scooter-specific
  settings (`C0059ay.m1717k().m1730s(f4061l3, f4069t3)`, `ao3.java:268-279`). When the saved MAC
  re-appears in the scan callback, `bm3.mo2333c` case 1 stops the scan and re-binds the service
  (`bm3.java:106-136`), which re-runs `m3240k` → `connectGatt`. **Reconnect is thus
  scan-driven, has no backoff and no retry counter.**
* `Disconnected` (branch 2) cancels the poll job, clears the request queue and stops the service.
* `C0993mx.m10817b()` is the teardown: sets `f19699p = true`, clears the write queue, clears
  `f19693j/f19694k`, `gatt.disconnect()`, `gatt.close()`, unregisters both receivers
  (`C0993mx.java:182-212`). The service's `m3239j()` additionally stops the foreground state,
  cancels notification 1001, clears `hi4.f10556k` and calls `stopSelf()` (`BleForegroundService.java:320-345`).
* The notification's "Disconnect" action is a broadcast (`com.basse.scootbatt.Disconnect`,
  `BleForegroundService.java:367-370`) handled by `C0917kx` case 1: it raises
  `IOException("background disconnect")`, cancels the notification and calls `m10817b()`
  (`C0917kx.java:57-64`). `ScooterFragment.mo3232c` deliberately suppresses the UI error for that
  message (`ScooterFragment.java:775-777`).
* A `PAIRING_REQUEST` broadcast for the target device aborts the connection with the
  `R.string.pairing_request` message (`C0917kx.java:50-53`) — i.e. legacy BR/EDR pairing is treated
  as an error; the app never calls `createBond` (grep: no hits).

---

## 3. Device identification — three independent stages

### 3.1 Stage 1 (pre-connect): manufacturer-specific data → protocol key

`C1142ow(byte[] rawAdBytes)` (`C1142ow.java:32-125`) scans the **raw AD structure** for
`{0xFF, 0x4E, 0x42}` = AD type `0xFF` + company ID **`0x4E42`** ("NB", little-endian), then takes
the 6 bytes that follow — i.e. for the AD element `[len][0xFF][0x4E][0x42][d0..d5]` — and requires
`d3 == 0 && d4 == 0`:

```java
byte b = copyOfRange[0];          // d0 = model code
… switch(b) → m12236a(key, drawable, friendlyName, isXiaomi)
this.f22890c = copyOfRange[1] == 2;   // d1 == 2 → Scooter.useCrypto
```

| Code (dec / hex) | Protocol key `f22888a` | Display name `f22892e` | `isXiaomi` `f22889b` | Line |
|---|---|---|---|---|
| 32 / `0x20` | `m365` | Xiaomi M365 | **true** | `C1142ow.java:66-67` |
| 33 / `0x21` | `esx` | Ninebot ESx | false | `:68-69` |
| 34 / `0x22` | `pro` | Mi Pro | **true** | `:70-71` |
| 35 / `0x23` | `t15` | Ninebot Air T15 | false | `:72-73` |
| 36 / `0x24` | `max` | Ninebot G30 | false | `:74-75` |
| 37 / `0x25` or 43 / `0x2B` | `1s` | Mi 1S | **true** | `:84-85` |
| 39 / `0x27` | `e` | Ninebot E | false | `:80-81` |
| 40 / `0x28` | `pro2` | Mi Pro 2 | **true** | `:86-87` |
| 41 / `0x29` | `lite` | Mi Essential | **true** | `:88-89` |
| 44 / `0x2C` or 123 / `0x7B` | `f` | Ninebot F | false | `:94-95` |
| 45 / `0x2D` | `f65` | Ninebot F65 | false | `:96-97` |
| 46 / `0x2E` | `mi3` | Mi 3 | **true** | `:90-91` |
| 74 / `0x4A` | `bonk` | Ninebot X160 | false | `:112-115` |
| 112 / `0x70` | `gt1` | Ninebot GT1 | false | `:98-99` |
| 113 / `0x71` | `gt2` | Ninebot GT2 | false | `:100-101` |
| 114 / `0x72` | `d28` | Ninebot D28 | false | `:102-103` |
| 115 / `0x73` | `d38` | Ninebot D38 | false | `:104-105` |
| 116 / `0x74` | `d18` | Ninebot D18 | false | `:106-107` |
| 118 / `0x76` | `p65` | Ninebot P65 | false | `:108-109` |
| 119 / `0x77` | `p100` | Ninebot P100 | false | `:110-111` |
| 120 / `0x78` | `g65` | Ninebot G65 | false | `:76-77` |
| 125 / `0x7D` | `e2` | Ninebot E2 | false | `:82-83` |
| 127 / `0x7F`, −128 / `0x80`, −127 / `0x81` | `f2` | Ninebot F2 | false | `:92-93` |
| −125 / `0x83` | `g2` | Ninebot G2 | false | `:78-79` |
| anything else | `"unknown"` (device hidden) | — | — | `:112-113`, `bm3.java:79` |

Two bits of the same 6-byte record are also used:
* `record[1] == 2` → `Scooter.useCrypto = true` → **NinebotCrypto transport** (AES) if the device
  does *not* expose the Xiaomi service (`C1142ow.java:117`, `ScooterFragment.java:900`).
* `record[3]`, `record[4]` must be zero, otherwise the record is ignored (`C1142ow.java:64`).
  `record[2]` and `record[5]` are not used.
* The drawable in the scan list comes from the same table (`f22891d`, `:21-24`).

The `Scooter` domain object then exposes the derived capabilities
(`SOURCES/com/basse/scootbatt/global/Scooter.java`):

| Method | Rule | Line |
|---|---|---|
| `isNewNinebotGeneration()` | model ∈ {`g2`, `g65`, `f2`} | `:170-172` |
| `isEligibleForExternalBattery()` | model ∈ {`esx`, `e`} | `:155-157` |
| `isInPreviewMode()` | MAC == `00:00:00:00:00:00` (demo mode, no BLE) | `:165-167` |
| `useCrypto` | from AD record byte[1] == 2 | `:32`, `C1142ow.java:117` |
| `isXiaomi` | from the table above | `:33` |

The advertising strings cited in the task (`Ninebot G2`, `Ninebot G30`, `Ninebot F2`, `Ninebot D18`,
`Mi Pro 2`, `Ninebot Air T15`) are exactly the `f22892e` column; `Black M365 (EU)`/`Xiaomi` belong to
a *different* table (§3.3).

### 3.2 Stage 2 (connect time): GATT table → transport class

See §2.6. There is no reading of the Device Information service and no firmware-string match:
identification at connect time is purely structural (which services/characteristics exist).

### 3.3 Stage 3 (post-connect): serial number → region/variant label

For the plain path, one of the first requests is `un0` case 1:
`ScooterRequest(MASTER_TO_SCOOTER, READ, position=0x10, payload={14,0})`, time-out **1000 ms**
(`un0.java:49-56`, `:57-68`, registered at `ao3.java:194`). Its response handler (jadx failed to
decompile it; smali `un0.smali` method `h`) does:

1. `String sn = new String(payload, US_ASCII)` (`un0.java:125-127`);
2. switches on the **protocol key** (`this.f30468c`, i.e. `Scooter.getModel()`;
   `un0.java:134-257` — cases `e, f, 1s, e2, f2, g2, d18, d28, d38, esx, max, mi3, pro, t15, m365,
   p100, pro2`);
3. switches on characters of the serial and writes the human-readable variant into the global
   **`ry4.f26566c`** (`un0.java:259-…`; e.g. for `max`: `bArr[3]=='B' → ry4.f26567d[0]` etc.),
   validating the prefix (`N2G…` for Ninebot E generation, `un0.java:295-298`);
4. the string tables live in `ry4.java:33-64`: e.g. `f26567d` = G30 regions
   (`US - G30P`, `EU - G30`, `DE - G30D`, …), `f26568e` = ESx/E (`DE - 20 km/h (Locked)`, …),
   `f26569f` = Mi Pro 2 / Essential, `f26570g` = E22/E25/E45, `f26571h` = E2,
   `f26572i` = T15, `f26573j` = F20/F30/F40, `f26574k` = ?, `f26575l` = D18, `f26576m` = D28,
   `f26577n` = D38, `f26578o` = P100S/P100SE. `ry4.f26566c` starts as `"Unknown"`
   (`ry4.java:28`).

The same register is *also* read from the BMS direction for display purposes:
`C1252rv` case 5 (dir `0x22`, reg `0x10`, payload `{14,0}`, timeout 1000) parses the ASCII serial and
guesses the cell vendor from its prefix (`C1252rv.java:71-72`, `:202-230`).

**Model table search result:** the dex strings `Black M365 (EU/CN/US)`, `White M365 (…)`,
`Black M365 Pro (EU/CN)`, `M187` come from a **second** variant table that is only reachable from the
un-decompilable part of `un0.mo2829h` — `p000/ry4.f26566c` is assigned one of those literals
(`un0.java:1323-1351` in the smali fallback listing). They are M365 *region/colour variants*, not
protocols: `un0.java:1200-1330` maps 5-digit serial prefixes (`13678/13679/16057/16132/16133/
16348/16349/18832/21073/21074/21866/21886/…`) onto them. Treat them as labels for the `m365`/`pro`
protocol keys, nothing more.

### 3.4 Capability gating — what actually gates HUD features

There is **no model→capabilities map**. Gating is done with ad-hoc predicates and flow flags:

| Gate | Where | Effect |
|---|---|---|
| `Scooter.isNewNinebotGeneration()` (g2/g65/f2) passed as `z` into `BleForegroundService.m3240k` | `ho3.java:122`, `:131`; `BleForegroundService.java:349`, `:364` | `f3922q = z` ⇒ the **Lock/Unlock action is removed from the notification** for new-gen (`BleForegroundService.java:297-299`) |
| `isNewNinebotGeneration()` | `vo3.java:243`, `:268` | the "Locked" toggle is **not** added to the config screen |
| `isEligibleForExternalBattery()` (esx/e) | `dp3.java:212` | external-battery UI only for ESx/E |
| model == `"g30"` | `dp3.java:279-284` | ESC temperature tile hidden for G30 |
| `Scooter.isXiaomi()` | `ScooterFragment.java:1613`, `:1623`; `c70.java:78-100`; `ao3.java:190`, `:193` | selects Xiaomi MCU-name strings (`STM32F/GD32E/GD32F` vs `AT32F`), the speed scale (`0.1` vs `0.001`, `c70.java:102-110`), and the direction-`0x23` dispatch allowance (`ScooterFragment.java:1614`, `:1624`) |
| `isInPreviewMode()` | `ScooterFragment.java:2064-2084`, `ao3.java:222-224` | demo scooters never bind the BLE service |
| `uo3.f30567u0` (flow) = "external battery present" | `ScooterFragment.java:1950`, dispatcher cond_10; `jh4.java:418-423` | switches the poll target between dir `0x22` and `0x23` and enables the external-BMS response map |
| runtime register `0x11`/`0x39` results (`SHFW_INSTALLED`, `EXTERNAL_BATTERY_INSTALLED`) | `vo3.java:238-275`, `ao3.java:250-263` | swaps the whole configuration screen between stock and SHFW register sets, adds the external-battery menu item |
| `Preferences.m3209e()` (`adcVoltageInsteadOfBatteryInNotification`) | `ScooterFragment.java:1955-1957`, `an3.java:385-387` | adds register `0x47` (ADC voltage) to the poll set |

So "capabilities" are represented as: (a) flags derived from the AD model code, (b) values discovered
at runtime from specific registers, (c) user preferences — never as a static
model→capability table.

---

## 4. Request/response dispatch tables

### 4.1 Frame layout (needed to read the tables)

Inner frame from `ScooterRequest.construct()` = `[0x3E, direction, action, position(=register),
payload…]`, `length = payload.length + 4`, **no checksum here**
(`models/scooter/helpers/ScooterRequest.java:67-112`). Directions/actions:
`xp0 = {MASTER_TO_SCOOTER=0x20, MASTER_TO_BLE=0x21, MASTER_TO_BLE_READ=0x24,
MASTER_TO_BATTERY=0x22, MASTER_TO_EXTERNAL_BATTERY=0x23, READ=1, WRITE=2, WRITE_NO_REPLY=3,
SHFW_READ=49, SHFW_WRITE_NO_REPLY=51, SHFW_WRITE=50}` (`xp0.java:43-67`).

`pe4.m12550k(request)` adds the on-air envelope (`pe4.java:1034-1290`):

| Mode (`xq0` ordinal) | On-air frame |
|---|---|
| 0 Ninebot | `5A A5 <len=payload> 3E <dir> <action> <reg> <payload…> <crc_lo> <crc_hi>`, crc = `~(Σ bytes[2..len+3]) & 0xFFFF` (`:1041-1057`) |
| 1 NinebotCrypto | same skeleton, payload AES-encrypted + 4 extra bytes (seq/tag), crc16 (`:1058-1142`) |
| 2 Xiaomi | `55 AA <len=payload+2> <dir>' <action> <reg> <payload…> <crc16>`; dir' = dir if dir ∈ {32,33,34} else `dir+3` for {32,33,34}→{35,36,37}; **special case: model `"z10"` ⇒ dir `0x11`** (`:1143-1183`) |
| 3 XiaomiCrypto | `dir' <action> <reg> <payload>` AES-CCM-encrypted via the elliptic VM, wrapped as `55 AB <len> <ctr[0..1]> <ct> <tag> <crc16>`; **returns `null` (write silently dropped) if the elliptic session is not keyed yet** (`:1184-1290`) |

**Response** frames (after decode/reassembly) use the same `5A A5 <len> …` skeleton but with
`<dir>` and `0x3E` **swapped**: `5A A5 <len> <dir> 3E <action> <reg> <payload…>`. Verified three
ways: (a) the `r0` dispatcher reads `bArr[3]` as direction and `bArr[6]` as register (smali
`ScooterFragment.smali` method `r0`, and the jadx rendering `ScooterFragment.java:1590-1595`);
(b) the NinebotCrypto special cases test `bArr4[3]==0x21 && bArr4[4]==0x3E && bArr4[5]==0x5B/0x5C`
(`ScooterFragment.java:1129`, `:1143`); (c) `oh3.m11880i` re-frames Xiaomi frames into that layout
(`oh3.java:393-433`).

### 4.2 Global dispatcher (`ScooterFragment.m3299r0` — smali method `r0`)

jadx rendered `ScooterFragment.java:1579-1894` incorrectly ("Code decompiled incorrectly"); the
following is from the smali and is the authoritative logic:

```
len = b[lenIdx 2]; dir = b[3]; x = b[4](0x3E); action = b[5]; reg = b[6]; payload = b[7 .. 7+len)

switch (action) {
  case 0x5B: if (len != 30) return;
             copy payload[16..30) → this.j3 (serial), T2 = true;            // serial read answer
  case 0x5C: if (reg == 1 || isXiaomi()) U2 = true; … legacy-pairing dialog if V2
  case 0x5D: if (reg != 1) return; U2 = true; W2 = true; state = Paired;
}
// ---- main-controller map X2 (f4047X2) ----
if (action == 4) goto X2;
if (action != 1) goto next;
if (dir == 0x20 || (dir == 0x23 && isXiaomi)) {
   X2: handler = f4047X2.get(reg); if (handler != null) { handler.h(len, payload); queue.c(handler); }
}
// ---- SHFW map Y2 (f4048Y2) ----
next:
if (action == 0x31 || action == 0x34) { handler = f4048Y2.get(reg); goto dispatch; }   // dir NOT checked
if (action == 0x39 && (dir == 0x20 || (dir == 0x23 && isXiaomi))) { handler = new C1138os(uo3, 10); goto dispatch; }
goto bms;
dispatch: if (handler != null) { handler.h(len, payload); queue.c(handler); }
// ---- BMS maps (action NOT checked) ----
bms:
if (dir == 0x22 || dir == 0x25) → f4049Z2.get(reg)
else if (dir == 0x23)         → require uo3.u0 == TRUE, else return;  → f4050a3.get(reg)
handler.h(len, payload); queue.c(handler);
```

Mapping back to the jadx names: `f4047X2` → smali `X2`, `f4048Y2` → `Y2`, `f4049Z2` → `Z2`,
`f4050a3` → `a3`. `queue.c(handler)` = `C1286ss.m14666c(handler)` = "this request was answered"
(§5).

### 4.3 The maps

**(a) `X2` — main controller / ESC responses** (built in `ao3.java:186-211`; keys are *registers*):

| Register | Handler | Request it corresponds to (dir/action/payload) | Timeout |
|---|---|---|---|
| `0x1A` (26) | `un0(…, 0)` | `20/READ/{2,0}` → battery capacity (Wh/…) — result >1280 && <1792 ⇒ flag + −1024 | 300 ms (`un0.java:45-56`, `:102-118`) |
| `0x10` (16) | `un0(…, 1)` | `20/READ/{14,0}` → **serial → model/region** (§3.3) | 1000 ms |
| `0xB2` (−78) | `un0(…, 2)` | `20/READ/{2,0}` → "vehicle info"/model code | 300 ms |
| `0x46` (70) | `c70(isXiaomi, 0)` | `20/READ/{1,0}` → MCU type string | 300 ms |
| `0xB5` (−75) | `c70(isXiaomi, 1)` | `20/READ/{2,0}` → **speed** (i16 ×0.1 Ninebot / ×0.001 Xiaomi, mph if pref) | 300 ms |
| `0x66` (102) | `C1138os(0)` | `20/READ/{6,0}` | 300 ms |
| `0x1B` (27) | `C1138os(4)` | `20/READ/{2,0}` | 300 ms |
| `0x25` (37) | `C1138os(9)` | `20/READ/{2,0}` | 300 ms |
| `0x29` (41) | `C1138os(8)` | `20/READ/{4,0}` | 300 ms |
| `0x3E` (62) | `C1138os(3)` | `20/READ/{2,0}` | 300 ms |
| `0x7C` (124) | `C1138os(1)` | `20/READ/{1,0}` | 300 ms |
| `0x7D` (125) | `C1138os(18)` | `20/READ/{2,0}` | 300 ms |
| `0x7B` (123) | `C1138os(5)` | `20/READ/{2,0}` | 300 ms |
| `0x2F` (47) | `C1138os(2)` | `20/READ/{2,0}` | 300 ms |
| `0x75` (117) | `C1138os(11)` | `20/READ/{1,0}` | 300 ms |
| `0x32` (50) | `C1138os(14)` | `20/READ/{4,0}` | 300 ms |
| `0x34` (52) | `C1138os(15)` | `20/READ/{4,0}` | 300 ms |
| `0x47` (71) | `C1138os(13)` | `20/READ/{2,0}` → **ADC voltage** (polled only when the "ADC instead of %" pref is on) | 300 ms |
| `0x53` (83) | `C1138os(7)` | `20/READ/{2,0}` | 300 ms |
| `0xDA` (−38) | `C1138os(17)` | `20/READ/{12,0}` | 300 ms |
| `0xBA` (−70) | `C1138os(12)` | `20/READ/{2,0}` | 300 ms |
| `0xB9` (−71) | `C1138os(16)` | `20/READ/{2,0}` | 300 ms |

(Register `0xB2` appears in *both* `X2` (as `un0(…,2)`) and `Y2` (as `C1524z4(0)`), disambiguated by
the action byte: `READ(1)` vs `SHFW_READ(49)`. The last column is `vp0.mo2828g()`, the response
time-out.)

**(b) `Y2` — SHFW / config responses** (`ao3.java:210-221`, `d83.java`, `C1524z4.java`; requests use
**action = `SHFW_READ` (49)** on direction `0x20`):
`0xFF → C1138os(6)`, `0xB2 → C1524z4(0)`, `0x01 → d83(0)`, `0x11 → C1524z4(1)`, `0x3C → d83(1)`,
`0x4C → C1524z4(2)`, `0x77 → d83(2)`, `0x87 → C1524z4(3)`, and action `0x39` → `C1138os(10)`
(raw frame `3E 20 39 00`, `C1138os.java:114-115`). `d83`'s response handler is a no-op
(`d83.java:38-53`): the app requests registers `0x01`/`0x3C`/`0x77` with a 32-byte expected answer
and then discards it.

**(c) `Z2` — internal BMS (dir `0x22`) and dir `0x25`** — `C1252rv` handlers
(`ScooterFragment.java:476-487`): `0x10→(5) serial`, `0x20→(4) date`, `0x18→(7)`, `0x1B→(2)`,
`0x31→(8) telemetry`, `0x35→(6) temperatures`, `0x40→(1) 10×u16/1000`, `0x3B→(3) battery health`,
`0x30→(0) error flags`. Requests go to dir **`0x22`** (`C1252rv.java:61-78`), time-outs 300 ms
(case 0), 1000 ms (cases 1-7), 350 ms (case 8 = the 1 Hz main poll) (`C1252rv.java:84-105`).

**(d) `a3` — external BMS (dir `0x23`)** — `qv0` handlers, same register set, requests to dir `0x23`
(`qv0.java:57-104`; `ScooterFragment.java:488-498`). Only used when `uo3.u0 == TRUE`.

**(e) Per-screen request bursts (not response routers).** `dp3` (configuration,
`dp3.java:335-352`), `an3` (battery, `an3.java:371-390`) and `yn3` keep
`ConcurrentHashMap< m13(register, direction), vp0 >` and, when the screen is active and the link is
`Paired`, enqueue **one read per registered handler** (`C0057aw` cases 7/8/10, e.g.
`C0057aw.java:210-231`). Responses are still parsed by the global maps — the ViewModels are shared,
so both point at the same state holders. Example keys: `(0x10,0x22)`, `(0x20,0x22)`, `(0x18,0x22)`,
`(0x1B,0x22)`, `(0x3B,0x22)`, `(0x35,0x22)`, `(0x40,0x22)`, `(0x30,0x22)`, `(0x47,0x20)`,
`(0x53,0x20)` (`an3.java:371-390`).

---

## 5. Polling vs notifications, watchdog, reconnect

**Everything on the wire is polled; notifications only carry the answers (plus 3 unsolicited event
actions).** The app never calls `readCharacteristic`; every value is obtained by writing a `READ`
request and receiving the answer as a NUS-TX notification.

### 5.1 The request scheduler — strict one-outstanding serialisation

`C1286ss` (`C1286ss.java:84-115`, real code path is the BLE one):
* `f27917d` = `ConcurrentLinkedQueue<C1212qs>`; `f27914a` = "busy"; `f27918e` = the timeout `Job`.
* `m14665b()`: if not busy, poll the head, frame it (`pe4.m12550k`), write it
  (`BleForegroundService.m3244o`) and start a timeout coroutine.
* `m14666c(vp0)`: on a matching response, cancel the timeout job, clear the busy flag, remove the
  entry and immediately send the next queued request.
* **Retry**: `C1212qs(request, retryCount = 2)` (`C1212qs.java:15-19`). On timeout the coroutine
  (`C0923l0.java:1408-1448`) decrements and **re-appends the same object at the queue tail**;
  at `retryCount == 0` it is removed. With the initial 2 that means **3 transmissions** of the same
  request, with **no backoff**, before it is dropped. Timeouts are logged only
  (`C1175ps.java:23-31`: `"Sending request -> …"` / `"Retrying request -> …"`).
* Duplicate suppression: `C1312th` case 14 removes any queued entry with the same `vp0` instance
  before adding a fresh one with `retryCount` reset to 2 (`C1312th.java:440-452`).
* `m3300s0(vp0)` is a no-op unless the connection state is exactly **`Paired`**
  (`ScooterFragment.java:1898-1903`) — another ordering constraint: nothing is polled before the
  init/pairing sequence finishes.
* Write chunking: a frame longer than `f19701r` is split into `f19701r`-sized chunks and pushed into
  `C0993mx.f19684a`; `m10819d("write failed")` on `writeCharacteristic` failure
  (`BleForegroundService.java:482-531`, `C0993mx.java:237-260`).

### 5.2 Timers that actually poll

| Loop | Interval | Requests | Evidence |
|---|---|---|---|
| Base HUD poll (`ScooterFragment.m3302u0`) | **1000 ms**, **500 ms** while the battery fragment is the selected tab | `qv0(tn3,8)` if `uo3.u0==TRUE` else `C1252rv(rm3,8)` ⇒ `dir 0x23/0x22, READ reg 0x31, {12,0}`; plus `C1138os(uo3,13)` (reg `0x47`) if the ADC pref is on | launched by `m3306y0()` → `lo3` case 6 when the state is `Paired` (`ScooterFragment.java:2044-2062`, `ao3.java:349-354`, `lo3.java:197-213`); body: jadx `ScooterFragment.java:1927-1980` = smali method `u0` (delay `0x1f4`/`0x3e8`, nav id `0x7f0a00f5`) |
| Trip recorder (`jh4`) | **1000 ms** | same reg `0x31` request **+ `c70(uo3,isXiaomi,1)` = reg `0xB5` speed**; every 10th tick `C1138os(3)` (reg `0x3E`), `C1252rv(6)` (reg `0x35`), `C1138os(7)` (reg `0x53`) | `jh4.java:414-427`; smali `jh4.smali` delay `0x3e8` |
| SHFW profile fetch (`lo3` case 1) | **1000 ms** until a value arrives | `C1524z4(aj3, 0)` = `SHFW_READ reg 0xB2 {2,0}` | `lo3.java:107-124` |
| Screen bursts (`dp3`/`an3`/`yn3`) | one burst per activation (state observation), then the job self-cancels | every register of the screen's own map | `C0057aw.java:208-231`, `C0057aw.java:244-262`; started from the fragments' state observers, e.g. `yn3` → `scratch/apk/smali/un3.smali:630-660` |
| Notification/UI refresh (`mo3233`/`C0359ix`) | throttled to **7000 ms** | none (widget snapshot + Glance refresh only) | `C0359ix.java:123-137`, `BleForegroundService.java:126` |

### 5.3 Dead-connection detection — there is no heartbeat

* The only *implicit* liveness signal is the poll loop itself: a dead link shows up as request
  time-outs (3 attempts) and/or a `gatt status N` disconnect callback. Nothing counts time-outs,
  nothing disconnects after N failures, and no "last packet at" timestamp is kept for the link.
* Link loss reaches the app only via `onConnectionStateChange(STATE_DISCONNECTED)`
  (`C0993mx.java:333-340`) or via a failed write (`m10819d`, `:255-258`, `:296-297`).
* `f3920n` (a `long` on the service) looks like a watchdog timestamp but is only written by the 7 s
  widget throttle (`C0359ix.java:132`) — it is **not** read anywhere else.
* **Reconnect** = "autoConnectImmediately" preference + rescan (§2.7). No exponential backoff, no
  attempt cap, no `autoConnect=true` GATT fallback.

### 5.4 The 7000 ms constant, precisely

`BleForegroundService.f3921p = 7000` (`BleForegroundService.java:126`) has exactly **one** reader:
`C0359ix.java:131` (the telemetry collector coroutine that combines 8 scooter flows). Every emission
it (a) writes a `us4(battery%, distanceKm, now)` record for the current MAC into a map persisted via
`Preferences.f3898s0` (index 66 = **`widgetSnapshotsJson`**, `Preferences.java:238`,
`C0359ix.java:123-129`) and (b) at most once per 7 s calls `op3.m12056a(context)`
(`op3.java:22-24`) → `C0110cc` case 11 = **Glance app-widget refresh**
(`C0110cc.java:530-574`). It also runs on service teardown (`BleForegroundService.java:320-337`).
It does **not** gate BLE traffic in any way. Its practical meaning for a reimplementation: don't
refresh widgets/notifications more often than ~1/7 s while telemetry is streaming.

---

## 6. Timing & constants table (everything found)

| Value | Where | Gates what |
|---|---|---|
| **7000 ms** | `BleForegroundService.java:126`, read at `C0359ix.java:131` | widget snapshot + Glance refresh throttle (§5.4) |
| **1000 ms** | smali `ScooterFragment.smali` `u0` (`0x3e8`) | base HUD poll period (default) |
| **500 ms** | smali `ScooterFragment.smali` `u0` (`0x1f4`) | base poll period on the battery tab |
| **1000 ms** | `jh4.java:414-427` (smali `0x3e8`) | trip-recorder sample/poll period |
| **1000 ms** | `lo3.java:119` | SHFW profile re-request until non-null |
| **900 ms** | smali `ScooterFragment.smali` `p0` (`0x384`) | NinebotCrypto: interval of the `3E 21 5B 00` serial-read attempts |
| **500 ms** | smali `ScooterFragment.smali` `p0` (`0x1f4`) | NinebotCrypto: interval of the `0x5C` pair-request / `0x5D` serial-write attempts |
| **5000 ms** | `lo3.java:81` | GPS-permission sheet delay after connect (not BLE) |
| **1000 ms** | `AbstractC1491y9.m17747E(ctx, 1000L)` calls in `ao3.java:69,101,384,416,440` | dialog auto-dismiss (not BLE) |
| Request time-outs | `vp0.mo2828g()` implementations | 300 ms (`un0` 0/2, `c70`, `C1138os` all, `d83`, `C1524z4`), 350 ms (`C1252rv`/`qv0` case 8 = the main poll), 1000 ms (`un0` 1 serial, `C1252rv`/`qv0` cases 1-7, 1000 for `qv0` case 0) |
| Retry count | `C1212qs.java:18` | 2 ⇒ **3 attempts** per queued request, no backoff |
| Write chunk | `C0993mx.java:97` = 20 B | `writeCharacteristic` chunk size; `onMtuChanged` sets `mtu − 3` |
| Frame overhead per mode (chunk reassembly) | `hl2.java:30-53` | Ninebot 9, NinebotCrypto 13, Xiaomi 6, XiaomiCrypto 16 bytes; reassembly condition is `len(payload) + overhead <= MTU−3` (`hl2.java:59-93`) |
| Scan settings | `wl3.java:8-47` + `cm3.java:82-88` | LOW_LATENCY, reportDelay 0, no filters |
| Scanner-library 10000 ms | `xl3.java:105`, `AbstractC1403vx.java:131`, `RunnableC1297t2.java:295-306` | batched-scan flush only; unused (batching off) |
| `Duration.ofMinutes(1)` | `BluetoothScanFragment.java:155` | staleness of saved scooter entries (`C0288gy.java:76-90`) |
| RSSI cut-off `-80 dBm` | `bm3.java:85-95`, `C0884k0.java:111` | hides far devices from the scan list |
| `hi4.f10556k` | `BleForegroundService.java:393`, `:322` | global "connected MAC" (widget/MainActivity), cleared on teardown |

---

## 7. What the integrator must replicate

1. **Identity from advertising, not from GATT.** Parse the manufacturer element with company ID
   `0x4E42`; code byte at offset 3 of the six bytes after `FF 4E 42`; `useCrypto = byte[4] == 2`;
   ignore records where bytes 6/7 are non-zero. Unfiltered scanning is required because the model
   data is in the advertisement, and the filter list must stay empty on the vendor's side too.
2. **Do not expect `requestMtu` to have happened.** Budget 20-byte writes. If you do request a
   larger MTU, do it *before* subscribing, and fix the reassembly length afterwards (the original
   snapshots the MTU into its reassembler and never updates it).
3. **Subscribe before requesting anything.** The original only marks the link usable after the last
   CCCD write, and only then runs the init sequence; the first poll is issued only when the state
   machine reaches `Paired`.
4. **Subscribe order matters on fe95 devices:** `0x0019` (authCh) → `0x0010` (chrl) → NUS TX, each
   descriptor write confirmed before the next (status checked). One descriptor write at a time; the
   Android stack serialises descriptor writes anyway.
5. **One outstanding request.** The protocol has no request IDs — a response is matched by
   (direction, register, action, and the *identity of the handler instance*). Sending two requests
   concurrently makes the answers ambiguous. Keep a FIFO with a single in-flight request, a
   per-request time-out and (optionally) the same 3-attempt/no-backoff retry policy.
6. **Expected-length field.** Register reads carry the wanted answer length as a little-endian
   `u16` in the request payload (`{12,0}`, `{14,0}`, `{2,0}` …) and the answer's `len` byte matches
   it (the 0x5B serial event is the exception: len 30, serial at payload offset 16).
7. **Reassemble chunked notifications.** `len(payload) + mode_overhead <= mtu_payload`; otherwise the
   frame continues in the following notification(s); the first chunk starts with the mode magic
   (`5A A5` / `55 AA` / `55 AB`). Overheads per §6.
8. **Keep the poll going, but do not exceed ~1 Hz** per register on the classic protocol; the
   original's 0x31 poll (1 Hz) doubles as liveness detection. Treat 3 time-outs as "request lost",
   not as "disconnect" — only the GATT callback or a failed write means the link is gone.
9. **Reconnect by rescanning** the same MAC and re-running the whole connect + subscribe + init
   chain; there is no `autoConnect=true` path and no backoff in the original.
10. **Do not re-send `3E 21 5D 00 <serial>` (serial write/bind) or the `0x5C` pairing frame unless
    you really mean to** — in the original those run inside the NinebotCrypto init loop and they
    write to the scooter.
11. Model-specific quirks to carry over: Xiaomi speed scale ×0.001 vs Ninebot ×0.1 (`c70.java:102-110`),
    MPC direction mapping +3 for Xiaomi (`pe4.java:1160-1185`), dir `0x23` allowed for Xiaomi
    responses (`ScooterFragment.java:1614`), `z10` model ⇒ dir `0x11` (`pe4.java:1166-1168`).

---

## 8. Unverified / needs hardware

* **All of it is static.** No link was ever established; every claim about what a scooter *does*
  (as opposed to what the app *sends/expects*) is inference from the app's code.
* Whether real scooters ever send `onMtuChanged` without an explicit `requestMtu` (Android 14+
  auto-negotiation) — and whether the app's re-subscribe-on-MTU-change actually breaks a live
  connection — is untested. Treat §2.3's hazard as theory, not as observed behaviour.
* The **meaning of the AD record bytes 2 and 5** (unused) and the exact semantics of `record[1] == 2`
  ("useCrypto") are inferred from usage, not from a specification: it would be good to confirm with a
  scooter which advertises `… == 2` that the app indeed runs the AES/NinebotCrypto envelope.
* The **`X2` map's register semantics** are only known where the handler body was decompiled; a few
  `C1138os` cases (0x66, 0x25, 0x29, 0x7C, 0x32, 0x34, 0xDA, 0xBA, 0xB9, 0x53) map to telemetry
  fields whose meaning I did not chase (report `00`/`01` cover the decode side).
* `ry4.f26566c` (region/variant label) is a **process-global** static with no reset; which of the
  `Black M365 (EU/CN/US)` variants a given scooter maps to cannot be confirmed without a device —
  the code path lives in a jadx-failed method (`un0.mo2829h`), read here from raw smali.
* Whether the two 500 ms/900 ms init loops actually terminate on real hardware (e.g. whether a G2
  answers action `0x5B` at all) is unverified; a scooter that never answers will keep the app in
  `PendingLegacyPairing` forever with a write every 500 ms — a real risk for a reimplementation.
* The external-battery flag `uo3.u0` and its effect on the dispatch direction could not be traced to
  the register that sets it (it is set from the ESx/E external-battery flow, not from a register I
  could confirm).
* The per-screen burst re-trigger mechanism (`C0057aw` cases 7/8/10) is only partly understood: the
  burst itself is one-shot, but something re-arms it; assumed to be the screens' state flows, not
  verified.

---

### Cross-checks performed

* All UUIDs, the `connectGatt` transport argument, the absence of `requestMtu`, the subscribe order
  and the dispatcher's branch conditions were re-read from the baksmali output
  (`scratch/apk/smali*/…`) after jadx produced mangled Java for `m3299r0`, `un0.mo2829h`,
  `ScooterFragment.m3297p0` and `C0923l0`.
* The on-air frame layout was confirmed twice independently: `pe4.m12550k` (writer) and
  `oh3.m11880i` (reader/normaliser).
