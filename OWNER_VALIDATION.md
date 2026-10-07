# Owner validation checklist (M365 and Begode A2 only)

Nothing below has been executed. Do not mark a row passed without recording date, firmware, app version and raw log.

## Xiaomi M365 — status-word write byte order (resolves BE vs LE)
Preconditions: M365 stationary on stand, wheel off ground, official Mi Home app available for comparison, phone build with experimental mode enabled for this device only.
1. Read `0x7D` (`03 20 01 7D 02`); record the word W0 and note tail-light and unit state in the official app.
2. Experimental: tail light ON via `StatusWordWriteOrder.BIG_ENDIAN`. Read back.
   - Readback == W0|0x0002 and official app shows tail light on, unit unchanged -> BIG_ENDIAN confirmed.
   - Readback differs or the units flipped -> STOP. Do not retry. Restore using the *other* order and the original W0, record raw frames.
3. If step 2 failed, repeat once with `LITTLE_ENDIAN` from the restored state.
4. Stop conditions: any unintended unit/tail-light change, any error reply, link loss mid-write.
5. Record: firmware, both raw write frames, both readbacks, official-app screenshots.
Outcome feeds `COMMAND_MATRIX.md` (set evidence H, drop the unresolved note, give `StatusWordWriteOrder` a default).

## Xiaomi M365 — KERS / cruise (stand only, wheel off ground)
Write each value, confirm via readback and official app. Cruise: verify OFF after test. Record raw frames.

## Begode A2
Not started (queue item 3).
