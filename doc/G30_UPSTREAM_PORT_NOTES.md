# Experimental Ninebot G30 encrypted telemetry port

This branch is based on `zero2005x/M365-Rokid-HUD:main` at
`bfa2aaa40cec758d6e530f862fe42f623d6f6e00`. It does not replace the
upstream BLE lifecycle, session epochs, Xiaomi login, or Ninebot cipher.

## What is new

- Read-only SHU inner `5A A5` frame builder / strict register-reply matching
  (different from the upstream plaintext P2 frame layout).
- Complete-frame length accounting for the six-byte encrypted trailer.
- Encrypted request/response adapter using the **existing upstream**
  `NinebotCryptoCipher`; permits the documented 7-byte minimum inner frame.
- Connection-scoped polling after the existing Ninebot handshake, with bounded
  timeouts, BLE fragmentation, epoch gating, and no write/lock commands.
- UI enters Ready only after a matching, parsed telemetry reply. Consecutive
  failures retire the session rather than advertising a working connection.

## Validation status

**Draft, NOT hardware verified.** Static protocol reasoning and JVM unit
tests cannot demonstrate that a Ninebot Max G30 accepts the authentication
sequence, that its firmware uses this frame layout, or that encrypted replies
are correctly authenticated. The upstream handshake remains unchanged.

Before enabling this by default in a release, test a real G30 across:
1. Initial pairing, power-button prompt, reconnect, and wrong credentials.
2. A stationary speed read, battery data and unplugged/partial notifications.
3. Riding speed agreement with the dashboard; never show phantom readings.
4. Bluetooth disconnect during a multi-chunk response and post-reconnect.
5. Non-G30 Xiaomi and non-Xiaomi models to avoid cross-protocol regressions.

The existing cipher implementation currently decodes ciphertext without a
verified per-frame authenticity check. Do not treat synthetic vectors as a
security guarantee. No write or lock controls are enabled by this change.

Provenance: structure and register-read request were compared with the
existing `Bl0ck154/G30-Rokid-HUD` SHU port; implementation here is isolated
and uses the upstream project's own crypto state machine.
