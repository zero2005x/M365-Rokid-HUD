# Architecture

Package `io.github.zero2005x.pev.core`.

| Package | Responsibility |
|---|---|
| `telemetry` | `FieldId` (distinct semantic fields), `Reading` (value + state + observedAt + evidence), `TelemetrySnapshot` (merge, aging). Unknown is `NOT_PROVIDED`, never 0. |
| `identity` | `DeviceIdentity` (family/model/firmware/board/pack params + `IdentitySource`). GATT hints never make a model "exact". `profileKey` binds write consent. |
| `codec` | `StreamReassembler` + `FrameSplitter`: bounded fragmentation/coalescing/resync, raw diagnostics. Family codecs live in `codec/<family>/` (one owner per namespace). |
| `command` | `CommandSpec` (scope, params+unit/range, evidence, confirm kind, retry, risk), `CommandPlan`, `CommandGate` (default-deny), `WriteSession` (per-device experimental opt-in), `CommandCoordinator` (single writer). |
| `transport` | `PevTransport`: bytes only. Implemented in the app layer (GATT). |

## Invariants
1. Codecs never write; only `CommandCoordinator` writes, one plan at a time (second caller is `REJECTED`).
2. `CommandGate` is default-deny: authenticated session, exact identity, scope (model + firmware),
   evidence or per-session experimental opt-in, parameter set/range, fresh standstill speed for
   riding-affecting commands. Opt-in only lifts the evidence requirement.
3. Experimental consent is bound to `profileKey`; any identity change revokes it implicitly.
4. Outcomes distinguish `ACK_CONFIRMED`, `READBACK_CONFIRMED`, `SENT_UNCONFIRMED`. No auto-retry on timeout.
5. Fixtures carry an `Evidence` label; synthetic vectors never become vehicle-verified.
6. Glasses never send vehicle settings (enforced in the gateway layer, not here).

## Adding a family
Create `codec/<family>/`, implement a `FrameSplitter`, a decoder producing `TelemetrySnapshot`,
`CommandSpec`s + plan builders, fixtures under `src/test/resources/fixtures/<family>/` with a
provenance header, and add rows to `SUPPORT_MATRIX.md` / `COMMAND_MATRIX.md` and entries to
`SOURCE_PROVENANCE.md`. Do not edit shared registry/build files unless you are the integration owner.
