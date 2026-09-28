# 08 — The Xiaomi family is one protocol in the reference implementation

**Scope.** What distinguishes the six Xiaomi entries in M365 Tools 1.8.0's device
table (`zm`) from one another. This is the evidence base for any claim that a
given Xiaomi model shares the M365 register layout — the exact question
`ninebot-ble/src/model/mod.rs`'s `MI3` doc comment says is unanswered.

Source: `jadx-out-nodeobf/sources/dalvik/O/O/a/b/O/c/b/O/o/zm.java`, literals
decrypted with `tools/m365tools_strings.py`. Method: each model constant is
constructed by an anonymous subclass; its instance-initialiser body was extracted,
every encrypted literal replaced by `LIT`, every local name normalised, and the
ordinal masked, then the bodies were compared character by character.

## Finding

All six Xiaomi entries are **structurally identical** apart from their name, their
serial prefix and one per-model scalar pair:

| Model | Boards | Commands | per-model scalar (`int i` / `int i2`) |
|---|---|---|---|
| `MI_SCOOTER_1S` | CTL(32,35) BLE(33,36) BMS(34,37) | 9, same set and order | 43 |
| `MI_SCOOTER_1S_DE` | identical | identical | 37 |
| `MI_SCOOTER_LITE` | identical | identical | 41 |
| `MI_SCOOTER_PRO2` | identical | identical | 40 |
| **`MI_SCOOTER_3`** | **identical** | **identical** | **46** |
| `MI_SCOOTER_PRO` | identical | *differs* — a different `qn` list | — |

The command list, in order, is the same nine `rn` constants for 1S, 1S_DE, Lite,
Pro2 and **Mi 3**:

```
rn.OOOO00O0OOO0OOOOO000, rn.O00000OOO0O0O00O0O0O, rn.OOO0O0OO0O00O00O00O0,
rn.O0OO000O00O0O00O0O0O, rn.OO0O00OO000000000OO0, rn.OO0O0OO00000O0O00O00,
rn.O0OO000O0000O0O00O00, rn.OOOO0OO00OO0O0O00000, rn.O0OOOO0000O0OO0O00O0
```

`MI_SCOOTER_PRO` is the exception: it carries a different command list, so the Pro
is *not* interchangeable with the rest in this app's model of the world — even
though the board addressing is the same.

## Why this matters to M365-Rokid-HUD

`ninebot-ble/src/model/mod.rs` gives `Mi3` `Confidence::Unverified` and no fields,
with this stated reason:

> **Its BLE protocol was never confirmed**, and Xiaomi's own firmware naming groups
> it with the 1S/Pro2 lineage without evidence that the register block is identical.

The reference implementation is a second, independent source, and it does more than
group Mi 3 with the lineage by naming: it gives it the *same board addressing and
the same command set in the same order* as the 1S, differing only in a scalar.
That is materially stronger than the firmware-naming argument the doc rejects.

**But it is evidence about commands, not about response offsets.** Which registers
the app reads is settled by this table; what the bytes in the replies *mean* is
decided in the command handlers, which this comparison did not read. So the honest
position is:

- Mi 3's **addressing and command set** are now documented → `Documented`, not
  `Verified` (no capture exists, exactly like the 1S and Lite rows the fork already
  marks `Documented` with "no capture").
- Whether it decodes the same **field offsets** needs the command handlers, and is
  the thing to check before promoting it further.

Note the asymmetry the fork already relies on: 1S and Lite are `Documented` *without
any capture*, so requiring a capture for Mi 3 would be a stricter bar than the one
the same family already meets.

## Unverified / needs hardware

- No frame has been exchanged with any Xiaomi scooter here. This is a static
  comparison of one app's model table.
- The per-model scalar (43 / 40 / 46 / …) is not identified. It is almost certainly
  a model or firmware code, but nothing in this pass establishes that, and it is
  **not** claimed to be a protocol version.
- `MI_SCOOTER_PRO`'s differing command list is observed, not explained.
- A Xiaomi M365 is the only hardware available, and it is not a Mi 3.
