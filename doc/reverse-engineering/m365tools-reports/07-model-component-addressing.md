# 07 — Per-model board addressing (the transferable mechanism)

**Scope.** How M365 Tools 1.8.0 (`app.peretti.m365tools`) decides *which address to
send a command to*, and *which address a reply comes back on*, for each scooter
model. This is the single most transferable finding in the whole RE, because it is
exactly the thing M365-Rokid-HUD hard-codes.

All citations are smali under
`/home/kali/ScooterHacking/re/m365tools/apktool-out/smali/`. Literals were recovered
with `tools/m365tools_strings.py` (clean-room reimplementation of the app's string
decryptor); see `CONTEXT.md`.

## 1. The addressing record

`dalvik/O/O/a/b/O/c/b/O/o/wm.smali` is a three-field record. Its `toString()`
(`wm.java`, which jadx renders in full) names the fields, so their meaning is read,
not inferred:

| Field | Type | `toString()` label | Meaning |
|---|---|---|---|
| `OO00000OOOOOOOO0000O` | `xm` | `mType=` | which board: CTL / BLE / BMS / BMS2 / UPC / ANCHOR / TAG / PTZ |
| `OOOOOOO0OOOOO0O00OO0` | `byte` | `mID=` | the address the app **sends to** |
| `O0OOOOOO00OOOOO000O0` | `byte` | `mReceiveID=` | the address a reply from that board **arrives from** |
| `O00OOO0O00O00OOO0000` | `int` | `VersionMaskCode =` | initialised to `4095` (`0xFFF`); the version filter described below |

Evidence: `wm.java` `toString()`; constructor at `wm.smali`.

## 2. The reverse lookup

`zm.smali:1752` — `zm.O00OOO0O00O00OOO0000(int) -> wm` walks the model's board list
and returns the first entry whose **`mID` or `mReceiveID`** equals the argument:

```smali
zm.smali:1777   iget-byte v2, v1, L.../wm;->OOOOOOO0OOOOO0O00OO0:B   # mID
zm.smali:1781   if-eq v2, v3, :cond_1
zm.smali:1784   iget-byte v2, v1, L.../wm;->O0OOOOOO00OOOOO000O0:B   # mReceiveID
zm.smali:1786   if-ne v2, v3, :cond_0
```

and for one special address it synthesises a record on the fly:

```smali
zm.smali:1792   const/16 v0, 0x3e
zm.smali:1802   invoke-direct {p1, v1, v0, v0}, L.../wm;-><init>(L.../xm;BB)V
```

**`0x3E` (62) is a special address that is both a valid send and receive id**, and its
record is created rather than stored. *Cross-check:* the independently reverse
engineered Scootbatt app uses `0x3E` as the leading byte of every request envelope —
two unrelated apps agreeing on `0x3E` is strong corroboration that this is a
protocol constant, not an artefact of one app's design.

So the app can translate an address in **either** direction: given a received frame's
source address it can still name the board that produced it. That is what makes
per-model addressing work without a model-specific parser per board.

## 3. The table, per model

Extracted mechanically by `tools/model_table.py` into
`analysis/model-table.md` / `analysis/model-table.json`. `a` = `mID`, `b` = `mReceiveID`.

| Model (`id` string) | Boards (`mType(mID, mReceiveID)`) |
|---|---|
| `MI_SCOOTER_PRO` / `_PRO2` / `_1S` / `_1S_DE` / `_LITE` / `_3` (`M365*`) | CTL(32,35) BLE(33,36) BMS(34,37) |
| `ESCOOTER` (`M365`) | CTL(32,35) BLE(33,36) BMS(34,37) |
| `SCOOTER_G30` (`MAX`) | CTL(32,32) BLE(33,33) BMS(34,34) BMS2(35,35) |
| `SCOOTER2` (`ESx`) | CTL(32,32) BLE(33,33) BMS2(35,35) |
| `SCOOTER2_P` (`ESx_P`) | CTL(32,32) BLE(33,33) BMS2(35,35) |
| `F_Series30` / `F_Series60` (`F`) | CTL(32,32) BLE(33,33) BMS(34,34) |
| `MINI` (`Mini`) | CTL(10,13) BLE(11,14) BMS(12,15) |
| `NANO` (`MiniLite`) | CTL(36,36) BLE(38,38) BMS(39,39) |
| `MARK2` (`Mark2`) | CTL(17,17) BLE(11,14) BMS(19,19) BMS2(20,20) |
| `MARK3` (`Z`) | CTL(20,20) BLE(22,22) BMS(17,17) BMS2(18,18) UPC(8,8) |
| `VIO` (`S-Plus`) | CTL(4,4) BLE(6,6) BMS(7,7) UPC(8,8) ANCHOR(9,9) TAG(10,10) PTZ(12,12) |
| `MARK2`-era / `KART*` / `MINI_MAX` / `MINI_KIDS*` / `STEELDUST` / `MEBIKE` / `WILD_STEEL_DUST` / `SCOOTER_AIR` | no board list in the constructor form used (see §5) |

Two structural observations, both load-bearing:

1. **The Xiaomi family and the plain `M365` id share one identical table.** CTL 32/35,
   BLE 33/36, BMS 34/37. Whatever distinguishes `M365_3` from `M365_1S` on the wire, it
   is *not* the board addressing.
2. **A `+3` offset is the Xiaomi signature** (`32→35`, `33→36`, `34→37`). Every Ninebot
   family in the table uses `send == receive` instead. That single bit — does the model
   echo on a different address — separates the two addressing conventions.

## 4. What this means for M365-Rokid-HUD

`repo/ninebot-ble/src/model/mod.rs:127` currently hard-codes the send address:

```rust
impl Board {
  pub fn address(self) -> u8 {
    match self { Board::Esc => 0x20, Board::Bms => 0x22 }
  }
}
```

That is `mID` for one family only, and `mReceiveID` — the address replies arrive on — has
no representation anywhere in the crate. Consequences:

- The `+3` convention that every Xiaomi model in this table uses is not modelled. If a
  reply is matched by source address anywhere, it is matched against the wrong value.
- `Board` is a two-variant enum (Esc/Bms). The app's `xm` has at least seven components,
  including a **second battery** (`BMS2`) that the fork's `ModelCapabilities` already
  anticipates but cannot address, and non-scooter components (`UPC`, `ANCHOR`, `TAG`,
  `PTZ`) that the fork does not need to support.
- Adding a model currently means copying a whole `ModelProfile`. With an addressing table
  it becomes adding one row.

The suggested shape is a per-model `&'static [BoardAddress]` of
`{ component, send_id, receive_id }` on `ModelProfile`, with `Board::address()` replaced
by a lookup that takes the model. That is a strictly smaller change than a new profile
per model, and it is the mechanism the reference implementation actually uses.

## 5. What the app does *not* keep

`qn` (`dalvik/O/O/a/b/O/o/qn.smali`) is constructed as
`qn(rn type, int[] startVersions, xm[] boardTypes)` — and the primary constructor
**stores only `type`**:

```smali
qn.smali   .field public final OO00000OOOOOOOO0000O:Ldalvik/O/O/a/b/O/c/b/O/o/rn;
```

`startVersions` and `boardTypes` are null-checked and then discarded. The arrays still
exist at the call sites — `zm.java:78` allocates `new int[326]` and `zm.java:78` again
`new int[534]`, and `F_Series30`/`F_Series60` carry a 534-entry table — so this build
carries **dead** per-command version/board gating tables. Do not model them as live
behaviour; do not read a per-command version gate into this app's design on the strength
of those arrays.

`rn` (37 constants) carries **no** string fields at all (`grep -c 'vl0->' rn.smali` → 0),
so command semantics are not recoverable from the enum; they must come from the
`BaseCommand` implementations in the unobfuscated app package (see report 05).

## Unverified / needs hardware

- `mID`/`mReceiveID` semantics are **read** from `wm.toString()` and the reverse-lookup
  at `zm.smali:1784`. How the *framing* layer consumes them — which byte of an outgoing
  frame carries `mID`, and which byte of an incoming frame is compared against
  `mReceiveID` — was **not** established here and is the next thing to pin down.
- No frame has been observed on a wire. The `+3` convention is a table entry, not a
  captured behaviour. **Nothing here should be treated as verified against hardware.**
- The board tables for `KART*`, `MINI_MAX`, `MINI_KIDS*`, `STEELDUST`, `MEBIKE`,
  `WILD_STEEL_DUST` and `SCOOTER_AIR` are absent from the constructor form this
  extraction reads. They may be defined in the anonymous subclass initialisers that the
  extractor did not attribute, or they may genuinely have no board list. Unresolved.
- Only a Xiaomi M365 is available for testing. Every Ninebot row above is
  documentation-grade.
