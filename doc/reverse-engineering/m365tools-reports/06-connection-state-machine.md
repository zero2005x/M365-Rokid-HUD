# AREA 6 — Connection lifecycle: state machine, reconnection, timeouts, MTU, error handling

Target: `app.peretti.m365tools` **M365 Tools 1.8.0** (versionCode 180), sha256
`4451a47c86339b8f88d77bdad7aef77e1bc4f11b7c728a5803f2b1d342a16872`.
Static analysis only. Source of record: the apktool decode at
`/home/kali/ScooterHacking/re/m365tools/apktool-out/`. apktool split the DEX into
`smali/` and `smali_classes2/`; both are cited, both are the same decode. No
`jadx-out/` path is cited anywhere in this report.

All paths below are relative to `/home/kali/ScooterHacking/re/m365tools/`.

---

## 0. Method, and how the Rx chain was read at all

R8 repackaged **the whole application and most of its libraries, including RxJava
and RxAndroidBle, into one obfuscated package** `dalvik/O/O/a/b/O/c/b/O/o/`. RxJava
method names are gone, so "look for `retryWhen`" finds nothing (verified: zero
occurrences of `retryWhen`, `repeatWhen`, `subscribeOn`, `observeOn`, `flatMap`,
`requestMtu`, `establishConnection` anywhere in the decode).

The chains were recovered by three independent means, and every operator named in
this report is justified by at least one of them:

1. **Interface shapes.** R8 kept the SAM method bodies, so the functional
   interfaces are identifiable with certainty:
   `pp0<T>` = `io.reactivex.functions.Consumer` (`O00OOO0O00O00OOO0000(Object)V`,
   `smali/dalvik/O/O/a/b/O/c/b/O/o/pp0.smali:18`); `kp0` = `Action` (`run()V`,
   `smali_classes2/dalvik/O/O/a/b/O/o/kp0.smali:7`); `qp0<T,R>` = `Function`
   (`OO00O0O0O0000000000O(Object)Object`, `smali_classes2/…/qp0.smali:20`);
   `rp0` = `Predicate` (`smali/dalvik/…/zn.smali:36` implements it);
   `to0<T>` = `ObservableSource` (`O0O000OOOO0000OOOO0O(vo0;)V`,
   `smali_classes2/…/to0.smali:18`); `gp0` = `Disposable`
   (`smali_classes2/…/gp0.smali:7,10`).
2. **Null-check strings RxJava emits.** `qo0` (= `io.reactivex.Observable`) keeps
   RxJava's own argument-validation messages verbatim: `"mapper is null"`,
   `"maxConcurrency"`, `"bufferSize"` at `smali_classes2/…/qo0.smali:925-938`
   identify `flatMap(Function, boolean, int)`; `"onNext is null" … "onAfterTerminate
   is null"` at `qo0.smali:1216-1234` identify the 4-argument
   `doOnEach(Consumer,Consumer,Action,Action)`; `"other is null"` at
   `qo0.smali:784` plus a generic `<U>` independent of `<T>` identifies
   `takeUntil(ObservableSource<U>)`; `"invalid device address "` at
   `smali/dalvik/…/op.smali:2488` is RxAndroidBle's own `getBleDevice` guard.
3. **Return types.** `qo0.OO0O0O0OOO0OOO00OO00(Consumer,Consumer,Action,Consumer)
   → Disposable` (`qo0.smali:993`) is `Observable.subscribe(...)`;
   `qo0.OO0OOOO00OO0O0OOO00O(long,TimeUnit,Scheduler) → Observable`
   (`qo0.smali:116`) is `Observable.timer`; `qo0.OOOO00O0OOO0OOOOO000(long,long,
   TimeUnit) → Observable<Long>` (`qo0.smali:179`) is `Observable.interval`.

**Vocabulary.** Every interesting literal is encrypted; the plaintexts below were
recovered with `tools/m365tools_strings.py`'s table (`analysis/strings.json`).
"Read" in the confidence columns means the instruction was read in smali;
"inferred" means a conclusion drawn from surrounding instructions.

---

## 1. Class map for this area

| Class (path under `apktool-out/`) | Identity | Evidence |
|---|---|---|
| `smali_classes2/dalvik/O/O/a/b/O/c/b/O/o/kb0.smali` | `RxBleConnection` — write / setupNotification / read | `:15,27,40` |
| `smali_classes2/…/kb0$OO00000OOOOOOOO0000O.smali` | `RxBleConnection.RxBleConnectionState` enum | `:26-34` |
| `smali_classes2/…/lb0.smali` | `RxBleDevice` — `establishConnection(Z)`, `observeConnectionStateChanges()`, `getConnectionState()`, `getMacAddress()` | `:15,19,23,27,31,35` |
| `smali/dalvik/…/op.smali` (5831 lines) | the BLE manager: connect, disconnect, teardown, queues, state | §2, §4 |
| `smali/dalvik/…/po.smali` (1468 lines) | per-model handshake + protocol (`vm`) selection; arms notifications | `:140-229`, `:1288-1355` |
| `smali/dalvik/…/vm.smali` + `vm$OO00000OOOOOOOO0000O.smali` | command transport base: checksum, XOR, one-command-at-a-time executor | `vm.smali:56-126,133-161` |
| `smali/dalvik/…/mn.smali`, `nn`, `fn`, `gn`, `bn`, `kn` | six `vm` protocol implementations (framing / fragmentation) | `po.smali:169-245,282-415,…` |
| `smali/dalvik/…/{sp,ho,rp,so}.smali` | the 10 ms command pump and its queue | §5.2 |
| `smali/dalvik/…/{yn,wn,zn,qt0}.smali` | the 3 s keep-alive chain | §5.3 |
| `smali/dalvik/…/{qo,lo,ao,pp,mo}.smali` | connection error classification & the GATT-133 path | §3 |
| `smali/app/peretti/m365tools/model/BaseCommand*.smali` | command objects, reply validation, status→message table | `BaseCommand.smali:37-184` |
| `smali/app/peretti/m365tools/ScooterService.smali` (3502 lines) | foreground service: notifications, poll loop, watchdog | §2.3, §4 |
| `smali/app/peretti/m365tools/ScooterService$*.smali` | poll runnable, 15 s watchdog, notification builder | §4 |

The app uses **greenrobot EventBus** (`org/greenrobot/eventbus/ThreadMode` in the
`onEvent` annotations at `ScooterService.smali:913-916`) for app-internal
distribution and **RxJava 2-style chains** for everything BLE.

---

## 2. The connection state machine

### 2.1 The only discrete states the app models

The app does **not** define its own `enum`. It re-uses
`RxBleConnectionState`, whose four constants are read directly from the enum's
`<clinit>`:

| Ordinal | Constant | Citation |
|---|---|---|
| 0 | `CONNECTING` | `smali_classes2/…/kb0$OO00000OOOOOOOO0000O.smali:48-54` |
| 1 | `CONNECTED` | `…kb0$OO00000OOOOOOOO0000O.smali:58-64` |
| 2 | `DISCONNECTED` | `…kb0$OO00000OOOOOOOO0000O.smali:68-74` |
| 3 | `DISCONNECTING` | `…kb0$OO00000OOOOOOOO0000O.smali:78-84` |

Everything else that a reimplementer would model as a state — *scanning*,
*discovering*, *handshaking*, *ready*, *error* — is represented **not as a state
but as a side effect**: the presence or absence of the static fields in `op`.

| "State" a reimplementer would expect | What the app actually holds | Citation |
|---|---|---|
| idle | `op.OO00OOOOOO000O0OO00O == null` (no `RxBleDevice`) | `op.smali:70`, `:2418-2422` |
| scanning (connect path) | no flag; the 1 s `takeUntil` timer is running | `op.smali:2514-2530` |
| device acquired | `op.OO00OOOOOO000O0OO00O != null` | `op.smali:2561`, `op$OO00000OOOOOOOO0000O.smali:56-61` |
| connecting | `device.getConnectionState() == CONNECTING` | `op.smali:2585-2593` |
| handshaking | the handshake runs *inside* the connection `onNext` (`po`) | `op.smali:2683-2688` → `po.smali` |
| ready / streaming | `op.OO0OOO00O0O00O00OO00 != null` (pump Disposable) + `op.O00000OOO0O0O00O0O0O` (error counter) | `ho.smali:94`; `op.smali:352` |
| disconnecting | `getConnectionState() == DISCONNECTING` | `op.smali:2595-2603` |
| error | the error counter and the teardown are the "state" | `qo.smali:52-56`, `lo.smali:38-80` |

`op.O0O000OOOO0000OOOO0O()` is the one boolean predicate the app treats as
"connected": `device != null && device.getConnectionState() == CONNECTED`
(`op.smali:2085-2107`). It is used as the gate for *every* command
(`op.smali:4203`, `:4912`, `:5030`, `:1571`).

### 2.2 Transition diagram (read from code)

```
                    user taps connect  (ScooterInformationActivity.smali:2473,
                                        ScooterService.smali:3431)
                            |
                            v
  [idle] --op.OO00O0O0O0000000000O()------------------------------+
     |   scanBleDevices(ScanFilter{mac}) + takeUntil(timer 1 s)   |
     |   op.smali:2501-2530                                       |
     v                                                            |
  [scan 1 s] --onNext(ScanResult)--> getBleDevice(mac)            |
     |   op.smali:2557, op$OO00000OOOOOOOO0000O.smali:56-61       |
     v                                                            |
  device == null ? --yes--> log "mbledevice null" (op.smali:2698) -+
     | no
     v
  already CONNECTED/DISCONNECTING ? --yes--> nothing (op.smali:2593-2603)
     | no
     v
  establishConnection(autoConnect = false)      op.smali:2650
     .subscribeOn(Schedulers.io())              op.smali:2654-2659
     .doOnComplete(dispose previous conn)       op.smali:2663-2668
     .doOnEach(onError = qo)                    op.smali:2672-2680
     .subscribe(onNext = po, onError = lo)      op.smali:2683-2693
     |
     +--> CONNECTING (emitted by RxAndroidBle on
     |    observeConnectionStateChanges, ScooterService.smali:3397-3412)
     |    -> ScooterService.onEvent(RxBleConnectionState) case 0:
     |       no branch (ScooterService.smali:2937-2943)
     |
     +--> onNext: po  == HANDSHAKE, then READY
     |    po.smali: picks a vm per protocol family (po.smali:140-245),
     |    writes auth, arms notify on the model's characteristic
     |    (po.smali:1288-1355), starts the 10 ms pump (ho)
     |    -> RxAndroidBle emits CONNECTED
     |    -> ScooterService.smali:2937 -> :3008-3037:
     |         foreground notification "CONNECTED",
     |         postDelayed(pollRunnable, 600 ms)
     |
     +--> onError: lo == FAILURE
          lo.smali:38-80: log "Error onConnectionFailure",
          remove all Handler callbacks, dispose pump + command
          subscription, clear both queues, full teardown
          -> NO retry, NO reconnect (see §3)

  [any state] -- user disconnect / logout --
       op.OO00OOOOOO000O0OO00O(boolean)   op.smali:3784-3994
       (queues a 0x7d protocol command first, then tears down)

  [any state] -- GATT/connection error --> [idle], everything cleared
```

`ScooterService.onEvent(RxBleConnectionState)` handles exactly two of the four
constants; the `ordinal` switch only tests 1 (`CONNECTED`) and 2
(`DISCONNECTED`) and falls through for 0 and 3 (`ScooterService.smali:2931-2943`).

On `DISCONNECTED` (ordinal 2) the service cancels notifications 1 and 2, clears
the trip queue, removes *all* Handler callbacks, sets the "started" flag
`ScooterService.OO0O0O0OO0OOO0OO0000` to false, `stopForeground(true)` and stops
location updates (`ScooterService.smali:2946-3005`). On `CONNECTED` it posts the
poll runnable 600 ms later (`ScooterService.smali:3025-3037`).

### 2.3 The state observer

The connection-state stream is subscribed **once**, in `onStartCommand`, and only
if the Disposable field is still null (`ScooterService.smali:3392-3427`):

```
device.observeConnectionStateChanges()          lb0.smali:23
  .doOnEach(EMPTY_CONSUMER, np /*onError*/, EMPTY_ACTION, EMPTY_ACTION)
  .subscribe(onNext = no, onError = bo, EMPTY_ACTION, EMPTY_CONSUMER)
  -> op.O00000000OOOOOOOOO00
```
`no` logs, re-posts the state on the EventBus, and — on `DISCONNECTED` — aborts
any in-flight command by resetting the protocol object's pending-reply holder
(`no.smali:36-85`, the `kb0$…OOOOO0000OO0O00000O0` test at `:69-71` and the
`vm.OOOOOOO0OOOOO0O00OO0` field access at `:74-81`). `bo` synthesises a
`DISCONNECTED` event when the stream itself errors (`bo.smali:36-72`, state post
at `:45-51`, reply-holder reset at `:61-68`).

---

## 3. Reconnection: what triggers it, what the backoff is, when it gives up

**There is no general automatic reconnection.** This is a finding, not an
omission in the analysis:

* The connection Observable is subscribed exactly once per user-initiated
  connect (`op.smali:2688`), and neither the subscription nor any operator on it
  retries. A repository-wide search for the RxJava retry/repeat operators finds
  none: the only `Function`-taking operators used in the app's own packages are
  `flatMap`-family calls (`qo0.smali:906,1059,1131`), and no `qp0` whose generic
  parameters form the `Function<Observable<Throwable>, ObservableSource<?>>`
  shape that `retryWhen` requires appears in the connection path.
* The connection `onError` handler `lo` performs a **one-way teardown** and
  returns (`lo.smali:38-80`): log `"Error onConnectionFailure"`
  (`lo.smali:44-49`, key `-0x233476b3f29e`), `Handler.removeCallbacksAndMessages`
  (`:52`), dispose `op.OO0OOO00O0O00O00OO00` (the pump, `:61`), clear
  `op.OOOOO0000OO0O00000O0` and `op.OO0OO000O000OOO0O00O`… i.e. both queues
  (`:68`, `:73`), dispose the connection subscription itself
  `op.OO0OO000O000OOO0O00O` (`:79`) and call the full teardown
  `op.OO0O00O0OO00OOO00O00()` (`:80`).
* `ScooterService.onEvent(DISCONNECTED)` (`ScooterService.smali:2946-3005`) does
  not call connect either.
* `op.OO00O0O0O0000000000O()` (the connect entry point) has only three callers in
  the whole app, and all three are user/lifecycle driven:
  `ScooterInformationActivity.smali:2473`,
  `ScooterInformationActivity$O00OOO0O00O00OOO0000.smali:276` and
  `ScooterService.smali:3431` (the latter inside `onStartCommand`).

**The one exception: GATT status 133 (`0x85`).** The connection chain's
`doOnEach` `onError` consumer `qo` special-cases it (`qo.smali:70-82`):

| Step | Code | Citation |
|---|---|---|
| Classify | `throwable instanceof BleDisconnectedException && ((BleDisconnectedException) t).status == 0x85` | `qo.smali:70-82` |
| Drop the client | `op.OO0OO0OOO0O0OOO0OO0O = null` (the `RxBleClient`) | `qo.smali:85` |
| Rebuild it | `App.O000000O000000000OO0()` | `qo.smali:88` |
| **Re-scan for the device** | `scanBleDevices(settings, ScanFilter{mac}).takeUntil(timer(1, SECONDS))` | `qo.smali:173-193` |
| Re-acquire the handle | on each ScanResult: `client.getBleDevice(mac)` → `op.OO00OOOOOO000O0OO00O` | `pp.smali:32-52` |
| Dispose the old link subscription | `op.OOOOO00OOO0O00OO0O0O.dispose()` | `pp.smali:55-70` |
| Subscribe | `subscribe(pp, ao /*BleScanException consumer*/, EMPTY_ACTION, EMPTY_CONSUMER)` → `op.OOOOO00OOO0O00OO0O0O` | `qo.smali:213-229` |
| Always | `op.OO0O00O0OO00OOO00O00()` (teardown) | `qo.smali:233` |
| `BleAlreadyConnectedException` | also drops `op.OO0OO0OOO0O0OOO0OO0O` (the client) | `qo.smali:59-67` |

Two things this path does **not** do, both material for a reimplementation:

1. It never calls `establishConnection` again. It refreshes the `RxBleDevice`
   handle and disposes the dead subscription; something else must call
   `op.OO00O0O0O0000000000O()` to actually reconnect.
2. It has no backoff and no attempt counter of its own. The **only** delay is the
   1 second `takeUntil` window on the re-scan (`qo.smali:177-193`), which is a
   scan bound, not a retry backoff.

**Attempt counter that does exist.** `op.O00000OOO0O0O00O0O0O:Ljava/util/concurrent/atomic/AtomicInteger`
is created at 0 (`op.smali:348-352`) and incremented on **every** connection
error (`qo.smali:52-56`). Nothing reads it to bound retries: a whole-file check of
`op.smali` shows no read of that field. It is telemetry only (it is reported to
Crashlytics alongside the MAC at `ScooterService.smali:3381-3389`).

**Does a reconnect re-run the handshake?** Yes, necessarily: the handshake *is*
the connection `onNext`. `op.OO00O0O0O0000000000O()` subscribes with
`onNext = po` (`op.smali:2683-2688`), and `po` is the routine that selects the
protocol implementation (`po.smali:140-245`), writes the auth token / ECDH
material, reads the serial/version, arms the notification channel
(`po.smali:1288-1355`) and starts the pump. There is **no session resumption, no
cached session, no "fast reconnect"** path: every `establishConnection` runs the
full handshake from scratch. The only thing cached across connections is the
model/protocol choice in preferences (`gq.smali:86`
`LAST_SCOOTER_SCOOTER_PROTOCOL`, `:82` `LAST_SCOOTER_SCOOTER_TYPE`).

**User-visible "give up".** Two places:
* After a successful connect, `ScooterService.onStartCommand` posts
  `ScooterService$OOOOOOO0OOOOO0O00OO0` with `0x3a98` = **15000 ms**
  (`ScooterService.smali:3477-3479`). That runnable checks
  `op.O0O000OOOO0000OOOO0O()`; if the link is still not CONNECTED it cancels
  notification id 1 and calls `stopForeground(true)` — the service stops
  advertising itself but is not stopped and does not retry
  (`ScooterService$OOOOOOO0OOOOO0O00OO0.smali:38-88`).
* A refused command surfaces as a toast `"Not connected"` in
  `op.OO0OOOOOOOO00000O0O0` (`op.smali:4931-4943`) and as a Crashlytics
  `RuntimeException("Not Connected" + command)` in `op.OO0O0OOO00O00OOO0000`
  (`op.smali:4225-4257`).

`onStartCommand` returns `2` = `START_STICKY` (`ScooterService.smali:3499-3502`),
so Android may restart the service; a sticky restart arrives with a null Intent
and the whole connect block is skipped (`ScooterService.smali:3166`).

---

## 4. Timeouts

Values are milliseconds unless stated. "Guarded operation" is what the constant
actually bounds in the code, not what its name suggests.

| Value (ms) | Constant / form | Guards | Citation | Confidence |
|---|---|---|---|---|
| 1000 | `takeUntil(Observable.timer(1, SECONDS, computation))` | The **connect-path device scan**: how long `op.OO00O0O0O0000000000O()` is willing to scan for the MAC before giving up and logging `"mbledevice null"` | `op.smali:2514-2530`, log at `:2698` | read |
| 1000 | same construct | The **GATT-133 recovery re-scan** | `qo.smali:177-193` | read |
| 230 | `0xe6` literal passed as the timeout argument | **Per-command reply deadline.** Every command queued by the normal dispatcher gets this budget; the protocol reads the notification queue until the wall-clock deadline, then throws `TimeoutException("Timeout")` | producer `op.smali:4214`; consumer `mn.smali:127-160`, throw at `mn.smali:347-355` | read |
| caller-supplied | `op.OO0OOOOOOOO00000O0O0(BaseCommand, int)` | Same reply deadline, for callers that need a different budget | `op.smali:4898-4929` | read |
| 600 | `0x258`, `Handler.postDelayed` | **Delay before the first telemetry poll** after `CONNECTED` (a settle delay after the handshake) | `ScooterService.smali:3031-3037` | read |
| 300 | `0x12c`, `Handler.postDelayed` | **Telemetry poll period.** The poll runnable re-posts itself unconditionally every 300 ms | `ScooterService$O0OOOOOO00OOOOO000O0.smali:100-108` | read |
| 10 | `Observable.interval(0, 10, MILLISECONDS)` | **Command-pump tick.** One queued command is dispatched per tick | `ho.smali:55-67` | read |
| 3000, initial delay 1000 | `Observable.interval(1, 3, SECONDS)` | **Keep-alive / session-refresh writes** on the auth characteristic (see §5.3) | `yn.smali:137-144` | read |
| 15000 | `0x3a98`, `Handler.postDelayed` | **Connect watchdog**: after 15 s without `CONNECTED`, remove the foreground notification and stop foreground | `ScooterService.smali:3477-3479`; body `ScooterService$OOOOOOO0OOOOO0O00OO0.smali:38-88` | read |
| ~4800 (16 × 300) | counter `> 0xf` inside the 300 ms poll runnable | Enqueue of the **periodic maintenance command** (`op.OOOOOOO00OOO0O00OOOO()`); counter reset each time it fires, so the period is 16 poll ticks | `ScooterService$O0OOOOOO00OOOOO000O0.smali:65-85`, call at `:72` | inferred (arithmetic read; period depends on the 300 ms value) |
| 40 entries (a bound, not a time) | `queue.size() > 0x28` | **Backpressure cap**: when more than 40 commands are pending, the whole queue is cleared instead of drained | `sp.smali:42-56` | read |

**Absent timeouts** (checked, not assumed):

* No timeout operator and no deadline is applied to `establishConnection`. The
  only bound on the connection attempt is the 15 s service watchdog above, and
  that watchdog does not cancel the attempt — it only hides the notification.
* No per-write timeout. `mn` fires every 20-byte chunk as an independent
  `writeCharacteristic` and discards the returned `Disposable`, so a write that
  is never acknowledged is never timed out by the app (`mn.smali:840-864`).
* No scan window in the user-facing scan:
  `MainActivity.smali:699-739` builds the chain
  `scanBleDevices → subscribeOn(io) → doOnComplete → subscribe` and stores the
  Disposable in `MainActivity.OOOO00OOO00O000O0OOO` (`:739`). No `take`, no
  `takeUntil`, no `timeout`. The scan ends when the activity disposes it.
* No heartbeat-style "reply watchdog" other than the 230 ms command deadline:
  there is no counter of unanswered polls anywhere in the poll path
  (`ScooterService$O0OOOOOO00OOOOO000O0.smali:45-110`).

---

## 5. The read / notify pump

### 5.1 Telemetry is **polled**, not pushed

The scooter pushes nothing the app acts on spontaneously. The app decides what to
read and when; incoming notifications are only ever *replies* to its own writes.

* The foreground service starts a `Runnable` 600 ms after `CONNECTED`
  (`ScooterService.smali:3035-3037`) that re-posts itself every 300 ms
  (`ScooterService$O0OOOOOO00OOOOO000O0.smali:105-108`).
* Each tick, if `op.O0OO0O00O0OOOO0OO0OO` (the "session live" `AtomicBoolean`) is
  true, it calls `op.O000000O000000000OO0()` and `op.O000OO0OO0OOO00OO000()`
  (`ScooterService$O0OOOOOO00OOOOO000O0.smali:50-62`). Both methods *enqueue*
  command Observables into `op.OOOO00O0OOO0OOOOO000`; they do not write
  (`op.smali:640-662`, `:910-953`).
* Every 16th tick the runnable also calls `op.OOOOOOO00OOO0O00OOOO()`
  (`ScooterService$O0OOOOOO00OOOOO000O0.smali:65-85`) which enqueues one more
  command (`op.smali:5613-5619`).
* The queue is drained by a **separate 10 ms pump** (`ho.smali:55-67`), started
  from the handshake when the notification channel is armed
  (`po.smali:1288-1307`): `Observable.interval(0, 10, MILLISECONDS)`
  `.doOnEach(sp, …)` `.filter(so)` `.subscribe(…)`, Disposable stored in
  `op.OO0OOO00O0O00O00OO00` (`ho.smali:94`).
* `sp` polls **exactly one** Observable per tick and subscribes it
  (`sp.smali:39-56`). If the queue has grown past 40 entries it is cleared first
  (`sp.smali:50-56`). The observer it passes (`rp`) only checks disposal state
  (`rp.smali:44-56`).

### 5.2 How overlapping commands are avoided — two independent mechanisms

1. **Global transport mutex.** `vm.O0OOOOOO00OOOOO000O0` is an `AtomicBoolean`
   used as a monitor: the executor does `synchronized(flag) { if
   (flag.compareAndSet(false, true)) { …run… } else { flag.set(false); return 0;
   } }` (`vm$OO00000OOOOOOOO0000O.smali:59-75` and the `cond_2` branch `:176-191`).
   A command that finds the transport busy is **dropped and returns integer 0** —
   it is not queued here. The flag is released in every exit path, including the
   `TimeoutException` and `WriteSNException` handlers (`:107-117`, `:166-174`,
   `:233-267`, `:277-283`).
2. **Queue + single dispatcher.** Pending work lives in one
   `ConcurrentLinkedQueue<Observable>` (`op.smali:308`) and is drained
   one-at-a-time on a 10 ms grid (§5.1). Because the command Observable is an
   `Observable.fromCallable` (`vm.smali:152-161`) whose `call()` blocks while it
   waits for the reply, the pump tick is serialised behind the command.

There is no priority, no per-command queue key and no fairness: it is FIFO, and
the only backpressure policy is "clear everything above 40".

### 5.3 The 3-second keep-alive (the closest thing to a heartbeat)

`fo` is the subscriber registered by the handshake when the data-notification
read completes (`po.smali:1415-1442`). Inside it, arming the notification is
wrapped in `doOnNext(yn)` (`fo.smali:65-72`). `yn` does three things:

1. Builds the session cipher object `op.OO0OOOO00OO0O0OOO00O:vp` from a
   device-derived byte string (`yn.smali:121-135`).
2. Starts `Observable.interval(1, 3, SECONDS)` — first tick after 1 s, then every
   3 s (`yn.smali:137-144`) — filtered by the `Predicate` `zn` and subscribed with
   the log-only consumer `kp`, the swallow-everything consumer `lp`, and the no-op
   action `mp` (`yn.smali:148-190`).
3. The doOnEach consumer `wn` performs the actual writes
   (`yn.smali:156-163` → `wn.smali:36-164`): depending on the session counter
   `vp.OO00000OOOOOOOO0000O`, it writes `op.O000OO0OO0OOO00OO000` (counter 0) or
   `op.O000000O000000000OO0` (counter 2) to the auth characteristic
   `op.OO0OOOOOOOO00000O0O0` = `00000010-0000-1000-8000-00805f9b34fb`, and in the
   `== 2` branch also writes the encrypted payload to
   `op.O00OOO0O00O00OOO0000` = `00000001-0000-1000-8000-00805f9b34fb`
   (`wn.smali:44-48,51-77,87-160`; UUID literals at `op.smali:194-201` and
   `op.smali:167-174`).

Important properties of this loop, all of which a reimplementation must decide
about explicitly:

* Its errors are **swallowed** (`lp.smali:32-39` is an empty `onError`), so a
  failing keep-alive write neither kills the session nor triggers anything.
* The teardown resets the session object (`op.OO0O00O0OO00OOO00O00` sets
  `vp.OO00000OOOOOOOO0000O = 0`, `vp.OOOOOOO0OOOOO0O00OO0 = false`, `op.smali:4006-4015`),
  which is how the keep-alive is stopped: the interval is never disposed
  explicitly, only its counter is reset by the teardown of the *connection*.
* It is **not** a link-liveness probe: nothing observes whether a keep-alive
  write succeeded, and no disconnect is raised on failure.

### 5.4 The reply path

Replies arrive as notifications into a per-protocol
`ConcurrentLinkedQueue<byte[]>` (`mn`'s second constructor argument,
`mn.smali:20`, field at `:9`). The command executor's waiter (`mn.O00OOO0O00O00OOO0000`,
`mn.smali:121-160`) is a **busy poll over that queue with a wall-clock deadline**
(`System.currentTimeMillis()` at `mn.smali:127` and `:148`, comparison at `:158`,
`TimeoutException("Timeout")` at `mn.smali:347-355`). Before each command the
queue is **cleared** (`mn.smali:822-824` in the transmit method), so a late reply
to the previous command can never be mistaken for the current one. Checksum
mismatches cause the loop to continue waiting rather than fail
(`mn.smali:219-226`, checksum function `vm.OO00000OOOOOOOO0000O([B)S` at
`vm.smali:56-89`).

---

## 6. MTU and fragmentation

**The app never requests an MTU, and never reads one.** Findings:

* The `RxBleConnection` interface the app holds has exactly three methods —
  write (`kb0.smali:15`), setupNotification (`:27`), read (`:40`). There is no
  `requestMtu` on it because R8 kept only the members the app uses; a
  repository-wide search for `requestMtu`, `Mtu`, `mtu` in the decode returns
  **zero** hits in code and **zero** hits in the decrypted string table.
* No `Integer`-returning `Single` (which `requestMtu(int): Single<Integer>` would
  be) exists anywhere in the app's classes.

Consequently fragmentation is hard-coded, in `mn.OO00O0O0O0000000000O`
(`mn.smali:826-864`):

```
ByteArrayOutputStream frame = <0x55 0xAB | payload | checksum(le)>   mn.smali:764-817
queue.clear()                                                        mn.smali:822-824
remaining = frame.length
while (remaining > 0) {                                              mn.smali:829-830
    n = min(remaining, 0x14)          // 0x14 = 20                  mn.smali:832-837
    connection.writeCharacteristic(
        UUID.fromString(op.OO00000OOOOOOOO0000O),   // 6e400002     mn.smali:840-846
        copyOfRange(frame, offset, offset + n))     // ob1 = Arrays.copyOfRange
        .subscribe()                 // Disposable discarded        mn.smali:848-858
    offset += n; remaining -= n
}
```

* **Chunk size arithmetic: 20 bytes, unconditionally.** `0x14` = 20 = the
  ATT_MTU 23 guaranteed minimum minus 3 bytes of ATT overhead. The app assumes
  the default MTU for the whole session.
* **No delay, no acknowledgement and no backpressure between chunks**: each chunk
  is written and subscribed independently, and the returned `Disposable` is
  dropped, so the app cannot tell whether a chunk landed. Ordering is preserved
  only because RxAndroidBle serialises GATT operations internally, and because
  the *receiver* of those writes is a serialised Rx chain
  (`mn.smali:854-858`).
* **There is a second, different write path.** `wn` (the 3 s keep-alive) writes
  short, pre-fragmented payloads directly — one `writeCharacteristic` call, no
  loop: `wn.smali:51-61`, `wn.smali:87-97`, `wn.smali:116-144`; `fo.smali:65`
  arms notifications. So "the write path" is not one function: the protocol/data
  frames go through the fragmentation loop, the auth/keep-alive frames do not.
* **Write-with-response only.** The only characteristic API used is
  `kb0.O0OOOOOO00OOOOO000O0(UUID,[B)` = `RxBleConnection.writeCharacteristic`
  (write request). There is **no write-no-response path** in this app and
  therefore no different chunk size for one. (The one place a "no reply expected"
  concept exists is protocol-level: `BaseCommand.O0OOOOOO00OOOOO000O0 == false`
  makes the executor skip the write and return 1 immediately,
  `vm$OO00000OOOOOOOO0000O.smali:92-99`.)

UUID roles, for the record (decrypted literals, final assignments in `<clinit>`):

| Field | Value | Used as | Citation |
|---|---|---|---|
| `op.OO00000OOOOOOOO0000O` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | **write** target of the fragmentation loop | `op.smali:450-457`, used `mn.smali:842` |
| `op.OOOOOOO0OOOOO0O00OO0` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | notify channel armed by the handshake | `op.smali:459-466`, used `po.smali:1290-1296` |
| `op.O00OOO0O00O00OOO0000` | `00000001-0000-1000-8000-00805f9b34fb` | notify channel armed by `fo`; write target in `wn` | `op.smali:167-174`, used `fo.smali:59-65`, `wn.smali:118,144` |
| `op.OO00O0O0O0000000000O` | `00000002-0000-1000-8000-00805f9b34fb` | read before arming `fo` | `op.smali:176-183`, used `po.smali:1415-1421` |
| `op.OO0OOOOOOOO00000O0O0` | `00000010-0000-1000-8000-00805f9b34fb` | keep-alive writes | `op.smali:194-201`, used `wn.smali:53,89` |
| `op.O0O000OOOO0000OOOO0O` | `00000010-0000-1000-8000-00805f9b34fb` | second field, same value, unused in this area | `op.smali:213-220` |
| `op.OO00OOOOO00OO00OOOO0` | `00000019-0000-1000-8000-00805f9b34fb` | auth/AVDTP | `op.smali:222-229` |
| `op.O0OOOOOO00OOOOO000O0` | `00000014-…` then **overwritten** with `new String(Base64.decode(ScooterInformationActivity.BluetoothItem()), UTF-8)` | model-dependent string | `op.smali:158-165` then `:424-448` |

Which of the six `vm` implementations is used is decided per model/protocol in
`po` (`po.smali:140-245`): `kn` at `:194`, `mn` at `:217`, `nn` at `:235`, `fn`
at `:169`, `gn`/`bn` at `:298`/`:415`. The condition for `mn` is an equality test
on the app's protocol-family int (`po.smali:210-214`); mapping that int to "M365"
is AREA 1/2's result, not established here — see §9.

---

## 7. Error handling

### 7.1 Classification

| Error class | Where classified | What happens | Citation |
|---|---|---|---|
| `BleAlreadyConnectedException` | `qo.smali:59-67` | drop the cached `RxBleClient` (`op.OO0OO0OOO0O0OOO0OO0O = null`) | `qo.smali:59-67` |
| `BleDisconnectedException`, GATT status `0x85` (133) | `qo.smali:70-82` | drop client, re-scan ≤1 s, re-acquire the device handle, dispose the dead subscription, then full teardown | §3 |
| any other connection error | `lo.smali:38-80` | log `"Error onConnectionFailure"`, clear queues, dispose everything, teardown | `lo.smali:38-80` |
| connection-state stream error | `bo.smali:36-72` | synthesise `DISCONNECTED` onto the EventBus, reset the protocol's reply holder | `bo.smali:36-72` |
| command issued while not CONNECTED | `op.smali:4203-4257` | Crashlytics `RuntimeException("Not Connected" + cmd)` and return an empty Observable | `op.smali:4223-4263` |
| same, via the 2-arg overload | `op.smali:4929-4947` | toast `"Not connected"` | `op.smali:4930-4943` |
| reply timeout | `vm$OO00000OOOOOOOO0000O.smali:233-274` | if the command is marked optional → return `-3`; else rethrow `TimeoutException` | `vm$OO00000OOOOOOOO0000O.smali:237-274` |
| `WriteSNException` | `vm$OO00000OOOOOOOO0000O.smali:198-231` | if optional → return `1`; else rethrow | `vm$OO00000OOOOOOOO0000O.smali:198-231` |
| transport busy | `vm$OO00000OOOOOOOO0000O.smali:176-191` | silently return `0` (command dropped) | `vm$OO00000OOOOOOOO0000O.smali:176-191` |
| malformed / unexpected reply frame | `BaseCommand$O000000O000000000OO0.smali:119-401` | throws `InvalidParameterException` with a formatted message (address + length) | `BaseCommand$O000000O000000000OO0.smali:162-202` |
| scan failure | `lc.smali:54-62`, `ao.smali:45` | read `BleScanException.status` and surface it | `lc.smali:54-62` |

### 7.2 The protocol status→message table ("ScooterErrorCodes")

`BaseCommand.<clinit>` builds an `ImmutableMap<Integer,String>` with nine entries
(`BaseCommand.smali:37-184`). Keys and plaintexts are read (keys decrypted with
`tools/m365tools_strings.py`):

| Key | Message | Citation |
|---|---|---|
| 0 | `OK` | `BaseCommand.smali:52-58` |
| 1 | `Out of bounds` | `:67-73` |
| 2 | `Erase error` | `:82-88` |
| 3 | `Write error` | `:97-103` |
| 4 | `Not locked` | `:112-118` |
| 5 | `Invalid address` | `:127-133` |
| 6 | `Command in progress` | `:142-148` |
| 7 | `Invalid payload len` | `:157-163` |
| 8 | `Too many Errors or Retry attempts` | `:172-178` |

`BaseCommand` also carries the retry budget: field `OOOOOOO0OOOOO0O00OO0:I`,
initialised to **3** in every constructor (`BaseCommand.smali:199-202`,
`:275-278`, `:329-332`), next to two booleans: `O0OOOOOO00OOOOO000O0:Z` = "expects
a response", default **true** (`:204-207`), and `OO00O0O0O0000000000O:Z` =
"tolerate failure", default **false** (`:209-210`). Note that a whole-file scan
of `op`, `vm` and the `BaseCommand` subclasses finds **no read** of the `3`
field: the number is set but the retry loop that consumes it is the `-2 →
repeat` loop inside the executor (`vm$OO00000OOOOOOOO0000O.smali:134-154`), which
is driven by the reply's status code, not by that constant. Treat "retry count 3"
as dead/unused until proven otherwise.

**The only retry loop that exists** is per-command and unbounded in count:
`vm$OO00000OOOOOOOO0000O.smali:134-154` re-invokes
`command.OO00000OOOOOOOO0000O(waiter)` while the returned status is `-2`
("send again"), with `goto :goto_0` and no attempt counter. Since each iteration
carries the same 230 ms deadline, a scooter stuck answering `-2` would spin
inside one pump tick. There is no exponential backoff anywhere in the app: a
whole-tree search for `BASE_BACKOFF`-style constants finds no delay between
retries; the only delays are the interval constants in §4.

**Fatal for the session?** Yes, for two classes:
* Any connection error → full teardown, queues cleared, handshake required again
  (§3).
* A `TimeoutException` or `WriteSNException` on a **non-optional** command
  propagates out of the `fromCallable`, killing that subscription; because the
  pump's error handler is the empty consumer `zp0.OO00O0O0O0000000000O`
  (`ho.smali:87-89`), the pump itself is **not** restarted — the Disposable stays
  in `op.OO0OOO00O0O00O00OO00` and the session is silently dead until the next
  disconnect/teardown. This is a real robustness defect worth not copying.

---

## 8. Robustness measures on real hardware that a naive implementation misses

1. **Settle delay after connect.** 600 ms between `CONNECTED` and the first poll
   (`ScooterService.smali:3035-3037`). A naive implementation polls immediately.
2. **Reply queue cleared before every write** (`mn.smali:822-824`), so a stale or
   late notification cannot be matched to the wrong command. Without it a single
   lost reply permanently desynchronises a request/response protocol.
3. **Checksum-gated reply acceptance**, with mismatch causing "keep waiting"
   rather than "accept garbage" (`mn.smali:219-226`; checksum one's-complement
   16-bit sum at `vm.smali:56-89`).
4. **One command in flight, globally.** A monitor + CAS on a static
   `AtomicBoolean`, with the loser silently dropped rather than queued
   (`vm$OO00000OOOOOOOO0000O.smali:59-75`).
5. **Bounded queue with an explicit drop policy** (>40 → clear,
   `sp.smali:50-56`) so a stalled link cannot grow the queue without bound.
6. **A dedicated 10 ms dispatcher** decoupled from the 300 ms producer, which
   keeps the write cadence off the UI/measurement cadence (`ho.smali:55-67`,
   `ScooterService$O0OOOOOO00OOOOO000O0.smali:105-108`).
7. **Connect watchdog that degrades the UI** rather than retrying blindly: after
   15 s the foreground notification is withdrawn (`ScooterService.smali:3477-3479`).
8. **The GATT-133 workaround**: drop the client, re-scan, re-acquire the device
   (`qo.smali:70-229`). Even though it does not reconnect by itself, refreshing
   the `RxBleDevice` is what makes the *next* user-initiated connect work on
   Android stacks that cache a bad handle.
9. **In-flight command abort on disconnect**: the state observer resets the
   protocol's reply holder, so a command blocked in its wait loop cannot return a
   reply that belongs to the next session (`no.smali:69-81`, `bo.smali:61-68`).
10. **Everything is torn down explicitly** on teardown: three Disposables, two
    queues, all Handler callbacks, two counters and a state object
    (`op.smali:3996-4187`). Nothing is left for a later session to trip over.
11. **Keep-alive traffic** every 3 s on the auth characteristic
    (`yn.smali:137-144`, `wn.smali`), which keeps a session-scoped cipher state
    fresh.
12. **`autoConnect = false`** — the app passes `false` to `establishConnection`
    (`op.smali:2650`), i.e. it wants a direct, fast connect, not the background
    auto-connect path.
13. **Per-command optionality** rather than a blanket error policy: commands can
    be marked "tolerate timeout" and then report `-3` instead of aborting
    (`vm$OO00000OOOOOOOO0000O.smali:237-274`).

---

## 9. What the app does *not* do (findings as important as the positives)

* **No automatic reconnection.** No `retryWhen`, no `repeatWhen`, no retry
  counter that terminates. `lo` tears down and stops (§3).
* **No exponential backoff of any kind** — not on connect, not on commands, not
  on the keep-alive.
* **No MTU negotiation and no MTU awareness.** 20-byte chunks, always (§6).
* **No write-no-response path**, hence no dual chunk-size arithmetic.
* **No heartbeat in the liveness sense**: the 3 s interval writes are fire and
  forget, their errors are swallowed (`lp.smali:32-39`), and nothing concludes
  "the link is dead" from a missing reply.
* **No per-write acknowledgement** in the fragmentation loop; the `Disposable` is
  discarded (`mn.smali:858`).
* **No timeouts on `establishConnection`, `writeCharacteristic` or service
  discovery** inside the BLE layer. Every deadline in this app is at the protocol
  layer (230 ms) or the service layer (15 s watchdog).
* **No scan window** for the user-facing scan (`MainActivity.smali:699-739`).
* **No persistence of a session**: every connect re-handshakes (§3).
* **No use of `RxBleConnection` state beyond the two handled ordinals** — the
  `CONNECTING` and `DISCONNECTING` events are ignored by the service
  (`ScooterService.smali:2931-2943`).
* **No bounded retry for a `-2` ("again") reply** — the loop has no counter
  (`vm$OO00000OOOOOOOO0000O.smali:134-154`).

---

## 10. Comparison with the fork (`/home/kali/ScooterHacking/repo`)

The fork already has three things the app does not: a real MTU negotiation, a
write-retry policy with backoff, and a bounded queue-free "one outstanding
request" polling loop with a failure counter. The gaps run the other way.

| # | App behaviour | Fork state | Materia for a Xiaomi M365? |
|---|---|---|---|
| 1 | **Every GATT callback has a deadline.** The command layer waits at most 230 ms for a reply (`op.smali:4214`; `mn.smali:127-160`) and a reply-less command cannot hang the loop. | `BleManager.connect()` (`app/src/main/java/com/m365bleapp/ble/BleManager.kt:323-357`) suspends until `onServicesDiscovered` fires; `write()` with `waitForResponse = true` (`:365-414`) suspends until `onCharacteristicWrite` fires; `enableNotifications()` (`:418-455`) until `onDescriptorWrite` fires. **None of the three has a timeout**; if the callback never arrives the coroutine hangs and the "busy" guard (`:324-328`, `:366-370`, `:419-422`) then fails every later attempt. | **Yes — highest priority.** A M365 whose GATT cache is stale (or that powers off mid-connect) is exactly the case where `onServicesDiscovered` never arrives. |
| 2 | **15 s connect watchdog** that at least surfaces the failure (`ScooterService.smali:3477-3479`). | No equivalent; a stuck `connect()` has no upper bound. | Yes. |
| 3 | **GATT status 133 handling**: drop the client, re-scan, re-acquire the device (`qo.smali:70-229`). | `onConnectionStateChange` treats any non-zero status the same: `gatt.close()` and resume `null` (`BleManager.kt:179-186`). No 133 special case, and `gatt.close()` without dropping the `BluetoothDevice`/`connectGatt` state is the classic path to a repeat-133 loop. | **Yes** — 133 is the single most common M365 connect failure. The fork does refresh the GATT cache (`BleManager.kt:190-197`), which the app does not; keep that. |
| 4 | **Session keep-alive every 3 s** on the auth characteristic (`yn.smali:137-144`, `wn.smali:36-164`). | No keep-alive. | Likely yes: the Mi-auth session state is written on a 3 s cadence and a link that stops being touched is the usual reason a scooter-side session expires. Needs hardware confirmation (§11). |
| 5 | **Retry with backoff per ATT chunk** — the app has none, but the fork's `WriteRetryPolicy` is the better design (`app/src/main/java/com/m365bleapp/protocol/WriteRetryPolicy.kt:92-133`). | Present and used (`ScooterRepository.kt:1485-1520`). | Keep. Note the fork retries `MAX_ATTEMPTS_PER_CHUNK = 3` (`WriteRetryPolicy.kt:41`) where the app loops a `-2` status unbounded — the fork is stricter and should stay so. |
| 6 | **MTU never negotiated; fixed 20-byte chunks** (`mn.smali:832-864`). | Negotiated MTU captured in `onMtuChanged` and used via `MtuFragmenter` (`BleManager.kt:159-173`, `MtuFragmenter.kt:63-86`), with a reset to 23 on disconnect (`BleManager.kt:204-211`). | The fork is strictly better here. Nothing to copy from the app. |
| 7 | **Queue cleared before each command** (`mn.smali:822-824`) so a stale reply cannot be matched. | `ScooterRepository` uses `withTimeoutOrNull` per chunk (`:1186-1200`) and re-reads; stale-notification handling was not verified in this area. | Worth checking: on M365 the notify stream is shared, and a late reply is the usual cause of "one command behind" bugs. |
| 8 | **One command in flight globally**, with a blocking wait (`vm$OO00000OOOOOOOO0000O.smali:59-75`). | The fork's `write()` returns `false` when busy (`BleManager.kt:366-370`) rather than serialising; the repository serialises at a higher level. The app's stronger property is that the loser cannot proceed. | Yes — cheap to add and prevents interleaved frames. |
| 9 | **Explicit full teardown**: 3 Disposables, 2 queues, Handler callbacks, counters, session object (`op.smali:3996-4187`). | `BleManager` clears the continuation, the notify callback and resets MTU on disconnect (`BleManager.kt:200-227`) — good — but there is no equivalent "reset the protocol session state" hook. | Yes if a keep-alive/session counter is ever added. |
| 10 | **Settle delay** 600 ms after connect before polling (`ScooterService.smali:3035-3037`). | `delay(500)  // Wait for connection to stabilize` exists at `ScooterRepository.kt:865`. | Already covered. |

**The single biggest gap: the fork's BLE primitives have no deadlines.** The app
never relies on a callback arriving; every wait is bounded above by a wall-clock
deadline it controls (`mn.smali:127-160`) and every connection attempt is bounded
by a 15 s watchdog (`ScooterService.smali:3477-3479`). On an M365 — where
`onServicesDiscovered` silently never firing after a bad cache, and status 133,
are the two dominant failure modes — a `connect()` that can suspend forever and a
busy-guard that then rejects every retry is the difference between "reconnect
works on the second try" and "the app must be force-stopped".

Second: **no GATT-133 recovery** (`qo.smali:70-229`). The fork closes the GATT
and stops; the app treats 133 as a distinct, recoverable class.

---

## 11. Unverified / needs hardware

No scooter and no phone are attached. Everything below is *not* established by
this analysis and must not be presented as measured behaviour:

1. **All timing is nominal, not measured.** The 300 ms poll period, the 600 ms
   settle delay, the 10 ms pump tick, the 3 s keep-alive period and the 15 s
   watchdog are constants read from code. Their *effective* period under load is
   unknown: the 10 ms pump blocks inside the command wait (up to 230 ms), so the
   real dispatch rate is bounded by the link, not by 10 ms. No measurement was
   possible.
2. **The 230 ms reply budget's adequacy is unverified.** Whether a real M365
   answers within 230 ms for every polled register is exactly the kind of thing
   that can only be measured. The app's own design implies that a miss is
   tolerated for commands marked optional (return `-3`), but which commands are
   marked optional was not enumerated here.
3. **The 3 s keep-alive's necessity and effect are unverified.** Whether the
   M365 requires it, tolerates it, or whether it is only needed for the
   Ninebot/newer families that use the `00000010`/`00000001` characteristics
   (`wn.smali:51-160`) is not determinable statically.
4. **Which `vm` implementation the M365 uses is not established here.** `mn`
   (the implementation whose fragmentation loop is documented in §6) is selected
   by one protocol-family constant at `po.smali:210-229`; mapping that constant
   to `M365` belongs to AREA 1/2 and was not re-derived. If the M365 turns out
   to use a different `vm`, the chunk size may differ (each of `mn`, `nn`, `fn`,
   `gn`, `bn`, `kn` has its own transmit method). Only `mn`'s `0x14` chunk size
   was read.
5. **Whether the app really never requests an MTU** rests on the absence of the
   API in the R8-pruned `kb0` interface plus zero `mtu` strings. That is strong
   negative evidence but it is still an argument from absence: a reflection-based
   or library-internal MTU request would not appear in `kb0`. Worth a runtime
   `hci snoop log` to confirm.
6. **The state machine's UI-visible states are inferred.** The four
   `RxBleConnectionState` constants are read; the mapping of "scanning /
   discovering / handshaking" onto field-presence side effects (§2.1) is this
   report's model of the code, not a structure the app names anywhere.
7. **Ordering between `qo` (doOnEach onError) and `lo` (subscribe onError) on a
   connection failure** is inferred from Rx semantics (`doOnEach` sits upstream of
   `subscribe`), not observed. If they run in the other order the teardown in `lo`
   would precede the re-scan scheduled by `qo`.
8. **Whether the "retry count 3" field is truly unused** rests on a textual scan
   for reads of `BaseCommand.OOOOOOO0OOOOO0O00OO0`. R8 could have inlined a read
   in a way that scan missed; the negative is probable, not certain.
9. **The claim that a command timeout permanently kills the pump** (the empty
   pump error consumer at `ho.smali:87-89`) is read from code but its runtime
   consequence — whether a later 300 ms tick re-enqueues enough to recover, or
   the session stays silently dead — cannot be settled without a device.
10. **The chunk loop's reliance on RxAndroidBle's internal serialisation** is an
    inference about the library's behaviour, not something this app's code
    states. On hardware the loop must be re-checked for interleaving.
