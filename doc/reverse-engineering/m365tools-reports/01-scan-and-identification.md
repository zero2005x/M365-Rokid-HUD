# AREA 1 — BLE scanning and pre-connection model identification

**Target:** `app.peretti.m365tools` v1.8.0 (versionCode 180), sha256
`4451a47c86339b8f88d77bdad7aef77e1bc4f11b7c728a5803f2b1d342a16872`.
**Method:** static only. Smali under `apktool-out/smali/` is the citation of record;
`jadx-out-nodeobf/` was used for readability only. Decrypted strings came from
`tools/m365tools_strings.py` (the `--uses` line numbers it prints are the
`invoke-static vl0->OO00000OOOOOOOO0000O(J)` lines; the `const-wide` line holding the
key is normally 2 lines earlier — both are given where it matters).

**Citation convention.** Citations are `ClassName.smali:line` relative to
`apktool-out/`, i.e. `apktool-out/smali/<pkg>/<Class>.smali`. Resolving the ones used most:

* `smali/dalvik/O/O/a/b/O/c/b/O/o/` — `op` `hc` `ic` `qo` `iq` `gn` `b11` `eq` `ri` `ti` `zm` `nc` `gq`
* `smali_classes2/dalvik/O/O/a/b/O/c/b/O/o/` — `nj0` `kj0` `lb0` `lj0` `mj0` `zf0` `ih0`
* `smali/app/peretti/m365tools/` — `MainActivity` `ScooterInformationActivity`
  `ScooterService` `App` `App$OO00000OOOOOOOO0000O` `ScooterInformationActivity$O00OOO0O00O00OOO0000`
  `adapter/ScanResultsAdapter` `model/BaseCommand$OO0O0OOO00O00OOO0000`
  `fragments/ScooterBasicInformationFragment`
* `smali/dalvik/O/O/a/b/O/c/b/O/o/tn$OO00000OOOOOOOO0000O.smali` — the scan collector

Decrypted-string keys are quoted as the `const-wide` literal (e.g. `-0x2c8976b3f29eL`)
so they can be re-checked with `tools/m365tools_strings.py --uses`.

---

## 1. Corrections to the starting brief and to CONTEXT.md

Three starting facts were wrong or imprecise. They are corrected here because every one of
them changes the conclusion.

| # | Starting claim | What the code actually does | Evidence |
|---|---|---|---|
| 1 | "`tn.smali` … keeps a scan result only when the advertisement carries service data for `0000fe95-…`" | `tn.smali` is a Kotlin coroutine continuation with no filtering at all. The `fe95` UUID is **read out of** the scan record (a `getServiceData()` map lookup), never used as a `ScanFilter`. The scan-result gate is: **advertised name non-null** (`tn$…:150`), and list membership additionally requires **manufacturer-specific data for company id 0x424E to be present** (`hc.smali:151-198`, `hc.smali:341-361`). | `tn$OO00000OOOOOOOO0000O.smali:119-152` |
| 2 | "The app does not use Nordic UART / no `6e4000xx` UUID appears anywhere" (CONTEXT.md:88) | The NUS UUIDs **are present** but string-encrypted: `6e400001-…` at `ScooterInformationActivity.smali:159`, `6e400002-…` at `:258`, `6e400003-…` at `:263`, and `6e400002/0003` again in `op.smali:140,149`. `00001800-…` / `00002a00-…` are at `ScooterInformationActivity.smali:268,273`. None of them is used for **scanning**; they belong to the post-connect GATT layer. | `--uses '6e4000\|fe95\|0000-1000-8000-00805f9b34fb'` |
| 3 | "`NinebotGetDeviceName` is the advertised-name parser" | `NinebotGetDeviceName` is the label of a **`BaseCommand` subclass sent after connecting** (`BaseCommand$OO0O0OOO00O00OOO0000.smali:19-32` builds command `(0x3e, 0x21, …)`); the string itself is only used inside that command's exception message (`:79`). It is not an advertisement string and does not contain a name pattern. | `BaseCommand$OO0O0OOO00O00OOO0000.smali:19-32,79` |

Additional correction: the `fe95` UUID literal is **plaintext** in the DEX
(`tn$OO00000OOOOOOOO0000O.smali:125`), which is why `--uses 'fe95'` finds nothing —
it is not in the encrypted table.

---

## 2. Exact scan configuration

There are exactly **three** call sites of `RxBleClient.scanBleDevices(ScanSettings, ScanFilter…)`
(`eb0` = `com.polidea.rxandroidble2.RxBleClient`, `eb0.smali:64`):

| # | Site | ScanSettings | ScanFilters | Purpose |
|---|---|---|---|---|
| 1 | `MainActivity.smali:613-739` (`O0OOOO0000O0OO0O00O0`) | `nj0(2, 1, 0L, 1, 3, true)` (`:649-661`) | **one empty filter** (`:665-696`) | the user-visible device list |
| 2 | `ScooterInformationActivity.smali:1289-1376` (inside `O0OO000O0000O0O00O00(Z)`) | same `nj0(2, 1, 0L, 1, 3, true)` (`:1304-1316`) | **zero filters** — `new-array v3, v3, [Lkj0;` with `const/4 v3, 0x0` (`:1318`) | "wait for my scooter to appear / become alive" |
| 3 | `qo.smali:97-173` (retry after GATT status 133) | same `nj0(2, 1, 0L, 1, 3, true)` (`:97-109`) | **one filter on device address only** — `deviceAddress = op.OO0O00O0OO00OOO00O00` (`:132`, validated at `:137`), every other field null, `manufacturerId = -1` (`:129`) | reconnect to a known MAC |

### 2.1 ScanSettings — what the six constructor arguments mean

`nj0` is RxAndroidBle's `ScanSettings`; `zf0.smali:288-339` is the class that forwards the
fields to `android.bluetooth.le.ScanSettings.Builder`, which fixes every mapping:

| `nj0` field | Set to | Forwarded as | Means | Confidence |
|---|---|---|---|---|
| `OO00OOOOOO000O0OO00O` (`:34`) | `2` | `setScanMode` (`zf0.smali:331-334`) | `SCAN_MODE_LOW_LATENCY` (0/1 are special-cased as 500/2500 ms in `ih0.smali:54-89`, `-1` is opportunistic) | read |
| `OOOOO00O0O0OO00OOO0O` (`:36`) | `1` | `setCallbackType` (`zf0.smali:300-303`) | `CALLBACK_TYPE_ALL_MATCHES` | read |
| `OO0O00O0OO00OOO00O00` (`:36`) | `0L` | `setReportDelay` (`zf0.smali:323-326`) | 0 ms — results delivered immediately, not batched | read |
| `OOOOO0000OO0O00000O0` (`:42`) | `1` | `setMatchMode` (`zf0.smali:308-311`) | `MATCH_MODE_AGGRESSIVE` | read |
| `OOOO00O0OOO0OOOOO000` (`:38`) | `3` | `setNumOfMatches` (`zf0.smali:316-319`) | `MATCH_NUM_MAX_ADVERTISEMENT` | read |
| `OOOO00OOO0OO000OOO0O` (`:40`) | `true` | **not forwarded** — no `setLegacy` call exists anywhere in the DEX | a "legacy" flag carried by the settings object but never applied to the framework builder | read (absence) |

Setting/field order is taken from `nj0.smali:61-83` (constructor) and `:34-44` (field
declarations); the framework mapping from `zf0.smali:288-339`.

### 2.2 ScanFilters — all three are effectively "match everything"

`kj0` is RxAndroidBle's `ScanFilter`. Constructor order (`kj0.smali:79`, field map at the
`iput`s in `:85-113`, validated by the `Parcelable` reader's
`checkBluetoothAddress` / `"serviceDataUuid is null"` / `"invalid manufacture id"` checks in
`jadx-out-nodeobf/…/kj0.java`):

`(deviceName, deviceAddress, serviceUuid, serviceUuidMask, serviceDataUuid, serviceData,`
`serviceDataMask, manufacturerId, manufacturerData, manufacturerDataMask)`

| Field | Site 1 (`MainActivity.smali:665-692`) | Site 2 (`ScooterInformationActivity.smali:1318`) | Site 3 (`qo.smali:111-166`) |
|---|---|---|---|
| deviceName | `null` | (no filter object) | `null` |
| deviceAddress | `null` | | `op.OO0O00O0OO00OOO00O00` |
| serviceUuid / mask | `null` / `null` | | `null` / `null` |
| serviceDataUuid / data / mask | `null` / `null` / `null` | | `null` / `null` / `null` |
| **manufacturerId** | **`-1`** (unset) | | **`-1`** (unset) |
| manufacturerData / mask | `null` / `null` | | `null` / `null` |

Site 1 is byte-for-byte the library's own "match anything" constant
`kj0.O0000O00OO00OOOO0000 = new ScanFilter.Builder().build()` (`kj0.smali:29,67`).
Consequently:

* **No service UUID is used as a scan filter.** `0000fe95-…` is only used to index
  `ScanRecord.getServiceData()` (`tn$…:119-137`).
* **Company id 16974 (0x424E) is not a scan filter.** It is used as a `get()` key into
  `getManufacturerSpecificData()` (`tn$…:105-113`, `hc.smali:127-135`, `ic.smali:168-262`).
* The framework stack does the "filtering" only at site 3 (address match); sites 1–2 are
  filtered entirely in app code. RxAndroidBle's own note for this path is
  `"No library side filtering —> debug logs of scanned devices disabled"` (`zf0.smali:154`).

### 2.3 Which advertisement fields are read at all

`lj0` = `com.polidea.rxandroidble2.scan.ScanRecord`, `lb0` = `RxBleDevice`,
`mj0` = `ScanResult` (field/type map: `lb0.smali:7-38`, `lj0.smali:7-48`, `mj0.smali:7-19`).

| Advertisement field | Accessor | Where read | Used for |
|---|---|---|---|
| device name | `lj0->O00OOO0O00O00OOO0000()` / `lb0->getName()` | `tn$…:90`, `hc.smali:88,310`, `ic.smali:322-336`, `ScanResultsAdapter.smali:196-206` | display, non-null/non-empty gate, one model heuristic (§4.4), persisted as `SCOOTER_LAST_NAME` |
| MAC address | `lb0->O00OOO0O00O00OOO0000()` | `tn$…:82`, `hc.smali:64,115` | list identity, `AUTO_CONNECT<mac>` pref key, persisted as `SCOOTER_LAST_MAC` |
| RSSI | `mj0->OOOOOOO0OOOOO0O00OO0` | `tn$…:95` | display/sort |
| manufacturer data (all ids) | `lj0->O0OOOOOO00OOOOO000()` (`SparseArray`) | `tn$…:101-113`, `hc.smali:98-135` | 0x424E presence = device-eligibility gate; value stored per MAC |
| company **0x424E** payload | `SparseArray.get(0x424e)` | `tn$…:105`, `hc.smali:127`, `ic.smali:168-262` | bytes 0 and 1 → scooter type + protocol version (§5) |
| service data for **`0000fe95-…`** | `getServiceData().get(ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb"))` | `tn$…:119-137` | byte 4 = liveness counter (§6) |
| raw AD bytes | `lj0->OO00000OOOOOOOO0000O()` | `ic.smali:145-161` | **discarded** — `Arrays.copyOfRange(getBytes(), 0x11, len)` is computed and thrown away; no other reader |
| service UUID list | `lj0->OO00O0O0O0000000000O()` | none | never read |

---

## 3. The scan-result pipeline (what reaches the UI)

`MainActivity.O0OOOO0000O0OO0O00O0()` (`MainActivity.smali:613-786`):

1. `scanBleDevices(settings, filter)` (`:699`), `subscribeOn(computation)` (`:704-708`),
   `…subscribe(onNext = hc, onError = lc, onComplete = empty, onSubscribe = errorMissing)`
   (`:721-734`); the `Disposable` is stored in `MainActivity.OOOO00OOO00O000O0OOO` (`:739`).
   An Action (`oc`, `oc.smali:25-45`) stops the scan when the observable terminates.
2. `hc` (the `onNext` consumer, `hc.smali:26-390`) maintains
   `ScanResultsAdapter.OOOO00O0OOO0OOOOO000` (a `List<ScanResult>`) and a
   `HashMap<mac, byte[]>` of 0x424E payloads (`ScanResultsAdapter.smali:42-50`):
   * name `null` → **drop** (`hc.smali:85-92`);
   * `getManufacturerSpecificData().size() > 0` → `map[mac] = data[0x424E]` (may be `null`)
     (`hc.smali:95-135`);
   * auto-connect branch — fires only when **all four** hold: (a) a one-shot latch is still
     clear (`hc.smali:139-141`, latch set at `:198`), (b) this MAC is already present in the
     displayed list (`:143-149`), (c) the stored 0x424E array for this MAC is non-null
     (`:151-191`), (d) `prefs.getBoolean("AUTO_CONNECT"+mac, false)` was true (`:61-82`,
     tested at `:193`; the key is `"AUTO_CONNECT" + mac`, built by `gq.smali:582-597`). It
     then schedules a **1000 ms** delayed task (`hc.smali:200-238`,
     `0x3e8` at `:210`; the task is `jh.smali:33-50`, which invokes the click callback
     `ic.…`) — i.e. a debounce before acting on a device;
   * otherwise the row is refreshed in place (`hc.smali:240-303`);
   * new row is added only if the name is non-null **and not `""`** (`hc.smali:305-324`) and
     `map.get(mac)` is non-null (`hc.smali:327-361`; the `array-length >= 0` test at `:359-361`
     is always true, a vestigial check).
3. The same scan feeds `MainViewModel` through `ri` (a `MutableStateFlow<…<ScanResult>>`,
   `ri.smali:7-16,93-118`), collected in `tn$…` into
   `MainViewModel.O000000O000000000OO0` (`tn$…:76-238`); `ri`'s scan is started (inlined)
   at `ScooterInformationActivity.smali:1321-1376` and its `Disposable` stored at `:1376`.
   That flow is what the connect path observes (`ScooterInformationActivity$O00OOO0O00O00OOO0000.smali:79-285`).
4. Scan failures: `lc.smali:26-…` maps `BleScanException` error codes to a localized string
   (`:62-73`, `packed-switch`) and to decrypted messages; nothing is thrown.

---

## 4. Pre-connection identification rules

### 4.1 Eligibility gate (the only hard pre-connect filter)

A device is only listed/actionable when its advertisement contains **manufacturer-specific
data with company identifier `0x424E` (= 16974 decimal; the two little-endian bytes are
`4E 42` = ASCII `NB`)**. If the 0x424E entry is absent, the value stored in the map is
`null` and both the display path (`hc.smali:341-361`) and the click path
(`ic.smali:184-210`) bail out. Every other manufacturer id is ignored.

### 4.2 Scooter type + protocol version come from the 0x424E payload

The click handler for a scan row is `ic.OO00000OOOOOOOO0000O(ScanResult)`
(`ic.smali:26-398`):

* `mfr = map[mac]`; `mfr[0]` → `v2` (`ic.smali:235`), `mfr[1]` → `v1` (`ic.smali:262`);
* two developer `SeekBar`s override them when not left at their sentinels
  `seekBar_tx_manufacturerId != 0x65` (`:271-293`) and `seekBar_protocolVersion != 5`
  (`:295-303`);
* name and MAC are stored globally (`:322-350` → `App.O00OOO0O00O00OOO0000` = *setName*,
  `App.OO00O0O0O0000000000O` = *setMac*; `App$OO00000OOOOOOOO0000O.smali:29-70,379-425`);
* **`App.setProtocol(v1)`** (`ic.smali:381-383` → `App$…:454-503`, stores
  `App.O0000O00OO00OOOO0000`) and **`App.setType(v2)`** (`ic.smali:384-386` → `App$…:77-294`,
  stores `App.OO00O0O0O0000OO00OO0` after a model lookup);
* then it starts `ScooterInformationActivity` (`:388-395`) — **the connection has not been made yet**.

`App.setType` normalises the byte with `and-int/lit16 … 0xff` (`App$…:87-92`) and then scans
`zm.values()` for the enum whose `OO00OOOOOO000O0OO00O` equals it (`App$…:100-140`), falling
back to `zm.UNKNOWN` (`App$…:139`, `zm.smali:445`).

### 4.3 Persisted values are the second identification source

`nc.smali` is the "reconnect to last scooter" click handler (`MainActivity.smali:2160-2169`
installs it on `button_offline_mode`, fed from `SCOOTER_LAST_MAC` / `SCOOTER_LAST_NAME` read
at `MainActivity.smali:2116-2140`). It restores:

* `SCOOTER_LAST_SCOOTER_TYPE`, default `0` (`nc.smali:86-121`, key literal `:89`) → `App.setType`;
* `SCOOTER_LAST_SCOOTER_PROTOCOL`, default `0` (`nc.smali:122-153`, key literal `:123`) → `App.setProtocol`.

These two keys are written **only** in `ScooterInformationActivity.onCreate`
(`ScooterInformationActivity.smali:3020-3066`: `SCOOTER_LAST_NAME` `:3020-3031`,
`LAST_SCOOTER_SCOOTER_TYPE` `:3034-3049`, `LAST_SCOOTER_SCOOTER_PROTOCOL` `:3051-3066`,
`SCOOTER_LAST_MAC` `:3004-3012`) from the current `App` globals — i.e. from the advertisement
bytes or the user override, never from a device response.

### 4.4 The advertised name: displayed verbatim, and one heuristic

* Display: `ScanResultsAdapter.smali:196-206` shows `scanRecord.getDeviceName()`; the adapter
  shows **no model name**.
* The **only** name-based model rule in the app (`App$…:143-201`): if the type code passed in
  is `0`, then `name.startsWith(" ")` **or** `name.startsWith("N3M")`
  (strings at keys `-0x42b176b3f29e` / `-0x42b376b3f29e`; helper `b11.OOOOO00O0O0OO00OOO0O`
  is Kotlin `startsWith(prefix, ignoreCase)`, `b11.smali` — its `requireNonNull` message is
  `"prefix"` and it calls `String.startsWith` then a region-match helper) sets the model to
  **`zm.O00O00O00O000OO0O0OO` = `MINI`, type code 3** (`zm.smali:520-535`) and logs
  `"ScooterType: Mini detected%s"`.
  This is the **complete** list of name patterns: `" "` and `"N3M"`. There is no
  `"MIScooter"`, `"Ninebot"`, `"Segway"` or `"Xiaomi"` name check anywhere in the app
  (`--uses 'Ninebot|Segway|Xiaomi'` matches only `NinebotGetDeviceName`).
* Names are also used for the per-device auto-connect flag key `AUTO_CONNECT + mac`
  (`gq.smali:582-597`).

### 4.5 From "device seen" to "connect": the liveness gate

`ScooterInformationActivity.O0OO000O0000O0O00O00(Z)` (`:1186-…`) registers an observer on the
scan flow (`ScooterInformationActivity$O00OOO0O00O00OOO0000.smali`, observer added at
`:33-41`). For every result it:

1. keeps only `ti.macAddress == op.OO0O00O0OO00OOO00O00` (`:117-130`) — the MAC that
   `ScooterService.smali:3278` stored as the current target;
2. reads **`getOrNull(serviceDataForFe95, 4)`** of the *current* and of the *previous* result
   (`:158-201`; index `4` at `:160`; `eq.OO0OOOOOOOO00000O0O0([BI)Ljava/lang/Byte;` is Kotlin
   `ByteArray.getOrNull`, returning `null` out of range — `eq.smali`);
3. if the two byte values are **equal** (including both-`null`), it does nothing
   (`:218-226`); if the "already triggered" flag is set, it does nothing (`:229-231`);
4. when they **differ**, it removes the observer, disposes the scan
   (`:242-257`, `ri.OO00000OOOOOOOO0000O()` = "disposeScan", `ri.smali:139-165`), logs
   `'Connecting'` (key `-0x94f576b3f29e`, `:259-262`) and calls `op.OO00O0O0O0000000000O()`
   (`:276`) — the connect state machine.

So the pre-connect sequence is: *present in the scan list (0x424E present) → type/protocol
taken from the 0x424E payload or the stored prefs → the fe95 service-data byte 4 must change
between two advertisements of the target MAC → stop scanning → connect.*

---

## 5. Byte layout — manufacturer-specific data, company id 16974 (0x424E)

**Base of offsets:** the Java `byte[]` returned by `SparseArray.get(0x424E)`, which is the
manufacturer-specific payload **after** the 2-byte company identifier. All offsets are
**byte indices into that array**, not AD-structure offsets and not the raw
`ScanRecord.getBytes()` offsets.

| Offset (bytes) | Size | Meaning as used | Units | Confidence | Evidence |
|---|---|---|---|---|---|
| 0 | 1 | **Scooter type code.** Passed to `App.setType(int)`, normalised `& 0xFF`, compared against `zm` enum type codes; no match ⇒ `zm.UNKNOWN`. Also the initial value of the hidden "manufacturer id" SeekBar (sentinel 101 = "unset"). | byte index, unsigned after `and 0xFF` | **read** (that the app consumes it as a type code) / **inferred** (that Ninebot's on-air field is a "type"/model code rather than a serial fragment) | `ic.smali:235`, `ic.smali:271-293`, `App$…:87-140`, `App$…:199` |
| 1 | 1 | **Protocol version.** Passed to `App.setProtocol(int)`; stored in `App.O0000O00OO00OOOO0000`; read by the command layer (`iq.smali:499`, `iq.smali:1278`) and compared to `1` in `iq.O000OO0OO0OOO00OO000` (`iq.smali:489-545`). Hidden SeekBar default is 5. | byte index, small integer | **read** (that the app consumes it as the protocol version) / **inferred** (the name — the app only labels it `"protocol_version"` at `App$…:471`) | `ic.smali:262`, `ic.smali:295-303`, `ic.smali:381-383`, `App$…:454-503` |
| 2 … n-1 | n-2 | **never read.** No other `aget-byte` or slice of this array exists: the only readers of `ti.OOOO00O0OOO0OOOOO000` are the data class's own `equals`/`hashCode`/`toString` (`ti.smali:209,263`) and the merge logic, which only tests `null`/`array-length` (`tn$…:181-232`, `hc.smali:189`, `ic.smali:208`). | byte index | read (absence) | grep of `ti;->OOOO00O0OOO0OOOOO000` and of the adapter `HashMap` |

**Not established:** the byte length of the payload, any framing/length field, any MAC or
serial bytes, and whether byte 0/1 are stable per model. The app never validates the length
(no bound check before `aget-byte`), so a 1-byte payload would in principle satisfy the
"0x424E present" gate but would throw on the click path — the click path is not wrapped in
try/catch (`ic.smali:26-398`; the scan loop that calls it is, `hc.smali:51,374-386`).

### 5.1 The `zm` model table (type codes) — the destination of that byte

`zm` is a 29-constant enum (`zm.smali:44-104`); each constant is built with
`(name, ordinal, <String>, <int>, <int type code>, …)` and the field compared against the
advertised type byte is the **second int** (`p5`, assigned at `zm.smali:1697`). Names are
decrypted constants (e.g. `zm.smali:520-535` for `MINI`); codes for the constants not folded
into an anonymous subclass are literal in `zm.smali`'s `<clinit>`.

| # | Constant | Type code (byte 0 value) | Confidence |
|---|---|---|---|
| 0 | `UNKNOWN` | 0 | read |
| 1 | `ONE` | 2 | read |
| 2 | `MINI` | 3 | read |
| 3 | `MINI_MAX` | 24 | read |
| 4 | `MARK2` | 19 | read |
| 5 | `ESCOOTER` | 32 | read |
| 6 | `VIO` | 20 | read |
| 7 | `KART` | 48 | read |
| 8 | `KART_PRO` | 49 | read |
| 9 | `KART_PRO_LAMBO` | 50 | read |
| 10 | `NANO` | 22 | read |
| 11 | `MINI_KIDS_MI` | 23 | read |
| 12 | `MINI_KIDS` | 25 | read |
| 13 | `MARK3` | 18 | read |
| 14 | `SCOOTER2` | 33 | read |
| 15 | `SCOOTER2_P` | 39 | read |
| 16 | `MI_SCOOTER_PRO` | 34 | read |
| 17 | `MI_SCOOTER_1S_DE` | 37 | read |
| 18 | `MI_SCOOTER_LITE` | 41 | read |
| 19 | `MI_SCOOTER_1S` | 43 | read |
| 20 | `MI_SCOOTER_PRO2` | 40 | read |
| 21 | `MI_SCOOTER_3` | 46 | read |
| 22 | `WILD_STEEL_DUST` | 74 | read |
| 23 | `SCOOTER_G30` | 36 | read |
| 24 | `STEELDUST` | 64 | read |
| 25 | `MEBIKE` | 65 | read |
| 26 | `SCOOTER_AIR` | 35 | read |
| 27 | `F_Series30` | 44 | read |
| 28 | `F_Series60` | 45 | read |

Cross-validation for seven of these: `zm.OO0OOOOOOOO00000O0O0()` (`zm.smali:1944-2037`)
returns true only when `String.valueOf(typeCode)` is in `{"32","34","37","40","41","43","46"}`
— exactly ESCOOTER, MI_SCOOTER_PRO, MI_SCOOTER_1S_DE, MI_SCOOTER_PRO2, MI_SCOOTER_LITE,
MI_SCOOTER_1S, MI_SCOOTER_3. That independent list confirms both the extraction method and
the semantics of the field. `ScooterInformationActivity.smali:1195-1211` gates the special
connect dialog on `protocolVersion == 2 && model.OO0OOOOOOOO00000O0O0()`.

The `zm` constants also carry a per-model name/alias list (`OOOOO00O0O0OO00OOO0O`) and
register-map lists (`qn`/`pn`/`wm`), e.g. `'N4GEA1601C0001'` for `UNKNOWN`
(`zm.smali` `<clinit>`, decrypted key `-152292941886110`); **no code outside `zm` reads the
alias list** (grep of `zm;->OOOOO00O0O0OO00OOO0O`), so the app does not match those strings
against anything in this version. Register-map semantics belong to the model area, not here.

---

## 6. Byte layout — `0000fe95-…` service data

**Base of offsets:** the `byte[]` returned by `ScanRecord.getServiceData().get(ParcelUuid)`
for `0000fe95-0000-1000-8000-00805f9b34fb` — i.e. the service-data payload **after** the
16-bit UUID in its AD structure. Offsets are byte indices into that array.

| Offset (bytes) | Size | Meaning as used | Units | Confidence | Evidence |
|---|---|---|---|---|---|
| 4 | 1 | **Liveness / rolling counter.** Two consecutive advertisements from the target MAC must have *different* values here, else the app keeps scanning. This is the trigger that ends the pre-connect scan. | byte index | **read** (the app's requirement) / **inferred** (the interpretation "counter") | `ScooterInformationActivity$O00OOO0O00O00OOO0000.smali:158-262` (index `4` at `:160`, comparison at `:218-231`, trigger at `:242-276`) |
| 0–3, 5…n-1 | — | **never read.** The only byte-array `getOrNull` call sites in the whole DEX are the two above (`:165`, `:192`). | byte index | read (absence) | grep `eq;->OO0OOOOOOOO00000O0O0([BI)` |

For orientation only (our prior knowledge of the MiBeacon/Xiaomi family layout, **not**
established by this app): bytes 0–1 frame control, 2–3 product id, 4 frame counter, 5–10 MAC.
The app's use of byte 4 as a changing value is consistent with that, but the app itself
proves only "byte 4 changes". Nothing in the app maps bytes 2–3 (the product id) to a model —
that would have been the natural in-advertisement model discriminator and it is unused.

---

## 7. Pre-connect vs post-connect

**Everything used to choose behaviour is decided before or at the moment of connection:**

| Decision | Source | When | Evidence |
|---|---|---|---|
| Is this device a candidate at all? | presence of manufacturer data for 0x424E | during scan | `hc.smali:341-361`, `ic.smali:184-210` |
| Scooter type (model) | `mfr[0]` (or stored `SCOOTER_LAST_SCOOTER_TYPE`, or the hidden SeekBar) | at row click, before `startActivity` | `ic.smali:235,271-293,384-386`; `nc.smali:86-121` |
| Protocol version | `mfr[1]` (or stored `LAST_SCOOTER_SCOOTER_PROTOCOL`, or the hidden SeekBar) | at row click, before `startActivity` | `ic.smali:262,295-303,381-383`; `nc.smali:122-153` |
| Model fallback from name | `startsWith(" ") \|\| startsWith("N3M")` → `MINI` | inside `App.setType`, only when the type code is 0 | `App$…:143-201` |
| Go/no-go on connecting | fe95 service-data byte 4 changed between two ads of the target MAC | immediately before connecting | `ScooterInformationActivity$…:158-276` |

The writer census proves the direction of travel: `App.O0000O00OO00OOOO0000` (protocol) has
exactly one assignment site (`App$…:500`) reached only from `ic.smali:382` and `nc.smali:153`;
`App.OO00O0O0O0000OO00OO0` (type) likewise (`App$…:92,199` ← `ic.smali:385`, `nc.smali:121`).
Consumers read them from the command/handshake layer (`iq.smali:470,499,1275,1278`;
`ScooterBasicInformationFragment.smali:6125-6609`; `ScooterBasicInformationFragment.smali:6534,6592`).
**No code writes them from a device response**, so there is no post-connect re-identification
in this version: the app commits to a model/protocol dial from the advertisement (or the
user) and never revises it in code.

What *does* happen after connecting, in the same activity, is the usual command traffic
(`op`'s state machine, `ScooterService`, `gn`'s auth sequence — which is where
`NinebotGetDeviceName` is sent, `gn.smali:1926-1929`) and, on teardown, persisting
`SCOOTER_LAST_*` (`ScooterInformationActivity.smali:3004-3066`).

Relevant preference keys and their exact roles:

| Key | Written | Read | Meaning |
|---|---|---|---|
| `SCOOTER_LAST_MAC` | `ScooterInformationActivity.smali:3004-3012` | `gq.smali:509-521` (getter), `MainActivity.smali:2116-2121` | last device address, used to prefill the "reconnect" button |
| `SCOOTER_LAST_NAME` | `ScooterInformationActivity.smali:3020-3031` | `MainActivity.smali:2127-2140` | last advertised name (display only) |
| `LAST_SCOOTER_SCOOTER_TYPE` | `ScooterInformationActivity.smali:3034-3049` | `nc.smali:86-121` | last resolved **model type code** (the `zm` code) |
| `LAST_SCOOTER_SCOOTER_PROTOCOL` | `ScooterInformationActivity.smali:3051-3066` | `nc.smali:122-153` | last **protocol version** |
| `AUTO_CONNECT` + MAC | `ic.smali:139` | `hc.smali:68`, `ic.smali:93` | per-device auto-connect consent; drives the 1 s debounced connect |

All key *names* live in `gq.smali`'s `<clinit>` (`:74-88` for these four) and are inlined at
every use site, which is why a single key literal appears in two files.

---

## 8. What lets it fail safely

1. **Unknown/foreign devices are simply never listed or clicked.** The 0x424E requirement is
   a hard gate in both the display path (`hc.smali:341-361`) and the click path
   (`ic.smali:184-210`) — a random BLE device cannot be connected to by accident.
2. **Unmatched type code ⇒ `zm.UNKNOWN`, not a crash** (`App$…:139`, `zm.smali:445`). The
   fallback constant carries its own default register-map lists (`zm.smali` `<clinit>`,
   first constant), so the app degrades to a generic layout instead of refusing.
3. **Out-of-range advertisement reads yield `null`, not an exception.**
   `eq.OO0OOOOOOOO00000O0O0` is `getOrNull`, so a short/absent fe95 payload makes the
   comparison "equal" and the scan simply continues (`ScooterInformationActivity$…:162-201`).
   A device that never advertises the fe95 service therefore never triggers this
   auto-connect path — no exception, no connection.
4. **The scan-result loop is wrapped in try/catch** and reports to Crashlytics instead of
   crashing (`hc.smali:51,374-386`).
5. **Connection retry after GATT status 133** (`0x85`): the error consumer `qo`
   (`qo.smali:26-236`, registered at `op.smali:2672`) drops the cached `RxBleClient`
   (`qo.smali:59-66`), then re-scans **filtered to the known MAC** with a 1-second timeout
   (`qo.smali:88-190`) and re-enters the state machine (`qo.smali:183-190,236`) instead of
   retrying blindly. `BleAlreadyConnectedException` is special-cased (`qo.smali:59-66`).
6. **Scan failures are surfaced, not thrown** — `BleScanException` error codes map to
   localized text (`lc.smali:59-110` + `packed-switch` tables).

**What it does *not* do for safety:** there is no allow-list or block-list of company ids
beyond 0x424E, no length validation of the 0x424E payload before indexing bytes 0/1
(`ic.smali:235,262`), and no "model == UNKNOWN ⇒ refuse to send writes" guard anywhere
(`zm.UNKNOWN` is read only as a default at `App.smali:103`, `App$…:139`). Safety comes from
the advertisement gate, not from a post-identification check.

---

## 9. What the app does **not** do (explicit negatives)

* It does **not** put any `ScanFilter` on a service UUID, a service-data UUID, a
  manufacturer id, or a name — sites 1 and 2 filter nothing at all
  (`MainActivity.smali:665-699`, `ScooterInformationActivity.smali:1318-1321`).
* It does **not** use `ScanSettings` batching (`reportDelay = 0`, `MainActivity.smali:651`).
* It does **not** call `setLegacy` on the framework settings (no such call exists).
* It does **not** parse the advertised name for a brand: the complete pattern list is
  `" "` and `"N3M"`, both `startsWith`, both only when the type code is 0
  (`App$…:143-201`).
* It does **not** read the fe95 service data at any offset other than 4, and does not read
  the product-id bytes 2–3 (`ScooterInformationActivity$…:158-192`).
* It does **not** read 0x424E payload bytes beyond 0 and 1 (`ic.smali:235,262`).
* It does **not** derive the model or the protocol from anything the device says after
  connecting (writer census in §7).
* It does **not** keep a per-MAC model cache: `SCOOTER_LAST_*` and `LAST_SCOOTER_*` are
  global scalars, not keyed by address.
* It does **not** verify that the connected device is the same model it guessed — no
  comparison of a post-connect identifier against `App.O0O000O0O0OOO0OO00O0` exists.

---

## 10. Comparison with `/home/kali/ScooterHacking/repo` (M365-Rokid-HUD)

| Aspect | M365 Tools 1.8.0 (this analysis) | repo | Difference that matters |
|---|---|---|---|
| Scan call | `scanBleDevices(ScanSettings(low-latency, callbackType 1, delay 0, aggressive, 3 matches), [empty filter])` at `MainActivity.smali:649-699` | `scanner.startScan(callback)` — no settings, no filters (`app/src/main/java/com/m365bleapp/ble/BleManager.kt:286-303`) | repo inherits framework defaults (low-power, batched off) and relies entirely on software filtering; M365 Tools explicitly asks for low-latency/aggressive delivery |
| Identity rule | **0x424E manufacturer data must be present**; type/protocol read from its bytes 0/1 (`hc.smali:341-361`, `ic.smali:235,262`) | name prefix `"MIScooter"` **or** service UUID `fe95` (`ninebot-ble/src/identity.rs:65-68`, `:38-47`; `app/src/main/java/com/m365bleapp/protocol/ScooterModelRegistry.kt:4`) | M365 Tools never matches on a name prefix at scan time and never uses `fe95` as an identity signal; the repo never looks at manufacturer data at all — it cannot see a Ninebot that advertises no `MIScooter` name |
| Protocol/model choice | from the advertisement byte (`protocol = mfr[1]`, `type = mfr[0]`), overridable by stored prefs / hidden SeekBars (`ic.smali:262-303,381-386`; `nc.smali:89,123`) | name prefix → `ScooterModel` hint at `Confidence.UNVERIFIED`, manual override wins (`ScooterModelRegistry.kt:293-345`); dialect decided by **probing after connect** (`ninebot-ble/src/identity.rs:57-64`) | the repo's own docs say a scan "cannot establish a model or a protocol"; M365 Tools disagrees and *does* take a protocol dial from the advertisement (byte 1). If byte 1 is a protocol generation, that is a real, cheap signal the repo currently ignores |
| Unknown device | model = `zm.UNKNOWN` (code 0) with a default register map; device is still connectable if 0x424E was present (`App$…:139`) | `ScooterModel.UNKNOWN` + `Identification.isUnknown`, GATT layouts probed and an empty result is left empty rather than guessed (`BleManager.kt:69-84`) | M365 Tools has no "unknown ⇒ no writes" guard; the repo's explicit empty-profile reporting is strictly safer |
| Name heuristic | `startsWith(" ") \|\| startsWith("N3M")` ⇒ `MINI` (type 3) (`App$…:143-201`) | longest-prefix match over `MIScooter`/`Mi Scooter`/`NB`/`Ninebot`/`Segway` (`ScooterModelRegistry.kt:234-250,293-317`) | the repo's pattern list is far richer; M365 Tools' single rule is nearly useless and is not the basis of its identification |
| Retry on failure | re-scan filtered by MAC, 1 s timeout, then re-enter the state machine on GATT 133 (`qo.smali:88-190`) | not present in `identity.rs`/`ScooterModelRegistry.kt`; connection retry policy is in `BleManager.kt` (`onConnectionStateChange`, `:176-229`) and is state-based, not advertisement-based | M365 Tools uses the advertisement as a recovery signal; worth copying for flaky modules |
| NUS | NUS UUIDs exist as encrypted constants but are not used at scan time (`ScooterInformationActivity.smali:159,258,263`; `op.smali:140,149`) | repo's `BleManager` is NUS-first (`BleManager.kt:38-44`) | CONTEXT.md's "the app never uses NUS" was wrong; the app does know NUS, but only in the post-connect GATT layer |

---

## 11. Unverified / needs hardware

Nothing in this report was observed on a live link. All of the following need a device —
and only a **Xiaomi M365** (MAC `C7:B8:DC:3B:A1:B2`) is available, so most of it is
permanently out of reach for direct confirmation:

1. **The existence and semantics of the 0x424E manufacturer payload.** Not a single real
   advertisement was captured. Whether the M365 (or any Ninebot/Xiaomi scooter) emits
   company id 16974 at all, and with what length, is unverified. If the M365 only emits
   `fe95` service data and never 0x424E, then M365 Tools would not list it — a testable,
   falsifiable prediction from this analysis.
2. **Byte 0 = type code / byte 1 = protocol version.** Verified only as *how the app uses
   two bytes*; that the air interface actually encodes a Ninebot type code and a protocol
   generation there is inferred. Needs a capture plus a comparison against a known model.
3. **The type-code table in §5.1.** Read from the DEX (`zm` enum construction), but whether
   the codes match Ninebot's published model numbering, and whether the app's own
   `MI_SCOOTER_*` codes correspond to real advertising bytes, is unverified.
4. **fe95 byte 4 = rolling counter.** The app's requirement (must change) is read; "counter"
   is inferred from the MiBeacon family layout. A capture of two consecutive advertisements
   from the M365 would settle it and would also show whether the M365 advertises `fe95`
   service data at all.
5. **Whether the app's hidden SeekBars (`seekBar_tx_manufacturerId`,
   `seekBar_protocolVersion`) exist in the shipped layout** — the field names are read from
   `ic.smali:271,295`, but whether the Views are inflated (ids `0x7f…`) and what their max
   values are was not checked; the defaults 101/5 are read from the guard constants only.
6. **The protocol version's meaning.** `App.O0000O00OO00OOOO0000` is compared to `1` in
   `iq.O000OO0OO0OOO00OO000` and stored/restored as `LAST_SCOOTER_SCOOTER_PROTOCOL`, but no
   code path was traced here that maps it onto a concrete handshake; that belongs to the
   handshake area and needs confirmation.
7. **The discarded slice `Arrays.copyOfRange(scanRecord.getBytes(), 0x11, len)`
   (`ic.smali:145-161`)** may be a leftover or a stripped debug log. What offset 17 was
   meant to point at is unknown.
8. **Whether the 0x424E array is the manufacturer payload or the whole AD** — the
   `SparseArray` key type makes it the payload after the company id, but this rests on the
   RxAndroidBle API contract, not on a capture.
9. **Native code.** `libnative-lib.so` exports `Java_app_peretti_m365tools_ScooterInformationActivity_BluetoothItem`
   and `…_calculateK`, both called from smali (`op.smali:426`, `up.smali:38`, `ck.smali:181`,
   `op.smali:578,819`). `BluetoothItem()` output is Base64-decoded into a String at
   `op.smali:436-448` and `calculateK([B)` is the key-derivation entry point. Neither was
   disassembled here; nothing in this report depends on them, but the identity/auth story
   will not be complete without them.
