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
3. Experimental consent is bound to device/connection and `profileKey` (including source and pack
   parameters). Observe every identity transition and allocate a new connection ID on reconnect;
   returning to an old profile never restores consent.
4. Outcomes distinguish `ACK_CONFIRMED`, `READBACK_CONFIRMED`, `SENT_UNCONFIRMED`. No auto-retry on timeout.
5. Fixtures carry an `Evidence` label; synthetic vectors never become vehicle-verified.
6. Glasses never send vehicle settings (enforced in the gateway layer, not here).

## Execution contract (0.1.0 snapshot)
`CommandCoordinator.execute(plan, context)` has no ungated overload. The phone supplies
`CurrentWriteState` via a live provider, including identity, authenticated `WriteSession`, parameter
values, telemetry and current time. The coordinator rechecks authorization before each step,
readback and confirmation. Plans own defensive copies of bytes and nested scope metadata.

Transport adapters must implement atomic `writeForConnection(bytes, expectedConnectionId)`:
capture/validate the exact GATT/native session before encrypting or submitting; a new link may
never inherit an old write. `TransportNotification` owns its bytes and carries an epoch/cursor.
ACK requires its protocol-specific predicate. Readback ignores unrelated replies until deadline;
no match is TIMEOUT, with no retries. Cancellation, changed authorization or reconnect prevents
later steps/readback and false confirmation. RMW plans additionally bind their read observation
to device/connection/profile and a two-second validity window.

The API still trusts audited typed builders to encode their declared parameters: arbitrary
hand-built plans are not a supported UI surface. Receive cursors exclude buffered replies but
cannot identify every delayed same-register reply. The App must isolate polling and settings
through one writer before production adoption. That Rust/GATT adapter and phone UI are pending;
current repository writes are not yet routed through this coordinator.

`BegodeA2Codec` reuses bounded framing and returns raw branch diagnostics. It emits an empty
physical snapshot, including for unknown profiles: it cannot grant battery/voltage/SOC/PWM truth.

## Adding a family
Create `codec/<family>/`, implement a `FrameSplitter`, a decoder producing `TelemetrySnapshot`,
`CommandSpec`s + plan builders, fixtures under `src/test/resources/fixtures/<family>/` with a
provenance header, and add rows to `SUPPORT_MATRIX.md` / `COMMAND_MATRIX.md` and entries to
`SOURCE_PROVENANCE.md`. Do not edit shared registry/build files unless you are the integration owner.
