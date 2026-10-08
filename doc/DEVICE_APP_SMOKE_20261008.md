# Device App smoke test — 2026-10-08

Scope: user requested phone and glasses App verification, with only A2 powered;
M365 scooter validation is deferred. This run tested the already-installed
RideFlux pair. It did not deploy the in-progress M365 phone source or the frozen
M365 debug APKs. Installed M365 phone/glasses 1.5.2 were inventoried only.

## Installed artifacts

Both devices were ADB-authorized and awake. Phone model: Xiaomi 21091116UG;
glasses model: RG_glasses. Both RideFlux Apps reported 0.1.11, versionCode 12,
last updated 2026-10-08 about 17:26 device local time.

| Role | Package | Installed base.apk SHA-256 |
| --- | --- | --- |
| Phone | com.rideflux.app | ca386d82047260510ec8c36eba5de3cdafa71230fe3a1dc9899a1b9e1da15b70 |
| Glasses | com.rideflux.hud | bd8d86dfef1c1a6a3d7192a51c55328b4ccc96608b1fe81e3bc2a641e887ffc3 |

Hashes were read on-device with `pm path` and `sha256sum`. Version equality does
not establish a Git commit identity. No APK was installed, App data cleared,
pairing token reset, or Bluetooth system setting changed during this run.

## Observed results

| Check | Result | Evidence |
| --- | --- | --- |
| Launch both installed Apps | PASS | Phone scanner renders; glasses waiting screen renders. |
| Discover A2 advertisement | PASS | GotWay_75436 classified as Family G; displayed signal -92 dBm. |
| Phone-to-glasses Android BLE standby link | PASS | Phone reports glasses connected; glasses displays phone connected, phone 96%, glasses 100%. These are endpoint battery levels, not A2 SOC. |
| Phone App background service | PASS, short smoke check | Phone HOME key; BridgeService remains isForeground=true, foregroundId=7421. |
| Glasses App process restart | PASS | Force-stop only com.rideflux.hud and relaunch; scan begins 22:37:10.322, authenticated handshake confirmed 22:37:11.699, screen again shows phone connected. Existing authorization survives. |
| Phone HUD hide/show commands | PASS | Foreground phone volume-down produces blank glasses framebuffer/UI; volume-up restores connected standby display. No vehicle command was used. |
| RideFlux crash buffer | No matching entries observed | Read-only logcat crash-buffer queries on both devices after test. This is a smoke observation, not a full stability certification. |
| Live A2 telemetry | FAIL on first attempt; retry pending | 22:35:36 connection state status=0/newState=2; MTU callback missing at 1500 ms; discovery watchdog retries after 3500 ms and exhausts retries at 22:35:45.737. No discovered service/notification/vehicle telemetry evidence. |
| M365 vehicle/auth/settings | NOT EXECUTED | User deferred scooter testing. |
| Installed M365 pair / latest M365 source on hardware | NOT EXECUTED | This checkpoint applies only to the installed RideFlux pair. |
| Rokid CXR, WiFi gateway, road ride, optical visibility | NOT EXECUTED | Android BLE and framebuffer were inspected only. |

No headlight, pedal mode, beep, speed limit, calibration, power-off, lock/unlock,
or battery-pack controls were pressed. A2 connection did not reach service
discovery; no vehicle command write was observed. The dashboard's existing
16S preference was left untouched and its physical correctness was not checked.

The failure cause is unresolved. Weak advertised signal is a plausible factor,
but this run does not establish RF distance or rule out another central/App
occupying the wheel or a BLE transport issue. The user was asked to move the
phone within roughly 0.5–1 m of A2 and ensure other Apps are disconnected, then
reply when ready. Do not label this A2 telemetry acceptance as passed.

## Evidence and continuation

Local ignored evidence is in `build/device-smoke/`:

- `phone-rideflux-start.xml/png`, `glasses-rideflux-start.xml/png`.
- `phone-rideflux-a2.xml/png`, `glasses-rideflux-a2.xml/png`.
- `phone-background-reconnect.xml/png/log`, `glasses-background-reconnect.xml/png/log`.
- `phone-hud-hidden.xml/png/log`, `glasses-hud-hidden.xml/png/log`.
- `phone-hud-restored.xml/png/log`, `glasses-hud-restored.xml/png/log`.

Filtered logs redact BLE addresses and handshake token fragments. Screenshots
and XML are local diagnostic evidence and may include device advertisement
identities; they were not published. Original device log buffers were preserved.
The MIUI uiautomator process printed a missing theme compatibility XML stack
trace but completed its dump; this was not a RideFlux App crash.

At handoff the phone is on the RideFlux scanner, Android BLE HUD bridge enabled,
glasses connected in standby, HUD visible. The A2 dashboard has been closed;
no wheel target is assigned to the bridge. Await the user's proximity reply,
refresh the scanner's observation, connect to A2 using the observed device row,
and inspect service discovery/notifications before assigning its data to the
HUD bridge. Retain unknown data as unknown and do not infer the correctness of
physical scaling from a successful connection. No vehicle settings writes.
