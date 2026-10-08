# Inmotion I1 receive envelope

This is a neutral, independently written structural contract. It does not identify a model,
define settings or certify physical telemetry. Reading the referenced research is not a legal
clean-room claim. No GPL/vendor implementation, tests, prose or capture is included in this module.

An I1 packet begins with two `AA` bytes and ends with two `55` bytes. Between them it contains
an escaped body and an additive one-byte checksum of the unescaped body, modulo 256. Body values
`AA`, `55` and `A5` are transmitted as `A5` followed by the original value. Historical references
also escape reserved checksum values; the receiver accepts that and a raw-checksum compatibility
form. This does not establish the correct host-to-wheel encoding for any setting.

The unescaped body has a 16-byte header:

| Offset | Width | Structural value |
|---|---:|---|
| 0 | 4 | unsigned little-endian CAN identifier, retained without model inference |
| 4 | 8 | raw CAN data; first four bytes contain unsigned little-endian extension length when length code is FE |
| 12 | 1 | length code:08 means this header alone; FE means an extension follows |
| 13 | 1 | raw channel |
| 14 | 1 | raw format |
| 15 | 1 | raw type |
| 16 | extension length | raw extended data |

Other length codes are rejected with diagnostics; their frame size is not guessed. Unknown
CAN identifiers/channel/format/type remain raw. The default extension budget is 512 bytes and
stream buffer 2048 bytes; custom smaller buffers can report overflow. Extensions above the configured
budget are rejected before wire-controlled allocation. The configurable ceiling of 16384 bytes
is a host memory policy, not a claimed limit of every vehicle protocol. Every partial escape, checksum and header
may cross notification boundaries. Reset discards all partial bytes at a session boundary.

The synthetic standard contract vector is
`AA AA 01 01 06 0F 01 02 03 04 05 06 07 08 08 05 01 00 49 55 55`.
Its body sum is23 (identifier bytes) +36 (CAN data bytes) +14 (metadata) =73 (`49`hex).
Its channel5 is a synthetic structural case, not a claim about captured receive channels.

The protocol shape is based on read-only `INMOTION_REFERENCE_DIFFERENTIAL.md` (including its
later correction section), `CROSS_BRAND_COMMAND_REFERENCE.md` and `inmotion_trace_corpus.json`
in PEVAppRE. Their SHA256 hashes and primary reference locations are recorded in
`pev-protocol-core/SOURCE_PROVENANCE.md`. Reference captures are GPL-associated external material
with no separate redistribution authorization here: they may be replayed locally into ignored
build output, but are not packaged as MIT fixtures. Historical498-frame analysis and later 501-frame
decoder counts must be distinguished; a new replay reports its own actual counts.

I1 and I2 share AA AA and do not share layouts/checksums. GATT UUID/name/magic alone cannot select
a model, physical scale, write capability or I2 layout. This receive-only codec emits no physical
fields, alerts or command plans. Model-specific speed, SOC, distance units, temperature locations,
alert values, auth and settings remain separate, unimplemented decisions.

After `scripts/verify-hud-wsl.sh` builds the current core JAR, reproduce the optional local replay:

```sh
bash scripts/replay-inmotion-i1-wsl.sh /home/kali/PEVAppRE/euc-programme/upstream/Wheellog.Android/app/src/test/resources
```

The independent MIT runner/transform are in `scripts/`. External CSVs are read-only inputs;
derived segments, report and compiled runner stay in the staging checkout's ignored `build/`.
