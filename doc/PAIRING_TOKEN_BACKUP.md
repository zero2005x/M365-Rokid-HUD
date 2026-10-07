# Scooter pairing key backup (.rfbond)

Settings → Scooter pairing keys supports Xiaomi Mi 12-byte tokens and Ninebot legacy-crypto 16-byte app random values. Import before connecting on a new phone. Pairing keys control scooters: someone with the backup and its password can use them. Use only for your own vehicles.

This implementation was written from the public behavioral specification, without copying RideFlux Kotlin, tests, or XML resources. The wire specification and public known-answer vector are at https://github.com/zero2005x/RideFlux/blob/main/docs/BOND_BACKUP.md.

## Export

Select one or more entries (initially all selected). Confirm with device credentials or a biometric; a device without a screen lock cannot export. API 28 uses the framework credential intent; API 29+ uses framework BiometricPrompt with device credentials enabled. Confirmation permits a single export for less than 60 seconds, checked again immediately before writing. Enter the same password twice (at least 10 characters and 4 distinct characters), then choose a destination with CreateDocument. Cancellation wipes the sealed bytes.

## Import and manual entry

OpenDocument accepts content URIs only. Read at most 256 KiB and validate magic/header before asking for a password. A wrong password and damaged encrypted payload show the same error. Review each entry before saving; entries are initially selected, and existing MAC addresses are kept unless their individual replace boxes are selected. Unknown families are skipped and counted. The result reports added, replaced, and kept entries.

Manual entry supports both families. MAC addresses accept colon-separated, hyphen-separated, and 12 hex digits. Keys accept whitespace, colons, and an optional 0x prefix, with exactly 12 or 16 bytes for the selected family. An existing MAC requires a separate replacement confirmation.

## Wire format

All integers are big-endian. MIME: application/octet-stream. Suggested filename: m365-bonds-YYYYMMDD.rfbond. Detection uses magic, never the filename.

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 6 | ASCII RFBOND |
| 6 | 1 | Version 1 |
| 7 | 4 | PBKDF2 iteration count (100000–10000000; export default 600000) |
| 11 | 16 | Random salt |
| 27 | 12 | Random GCM nonce |
| 39 | variable | Ciphertext followed by 16-byte authentication tag |

PBKDF2-HMAC-SHA256 derives a 256-bit AES key from the UTF-8 password. AES-256-GCM authenticates the complete 39-byte header. The plaintext is UTF-8 rideflux-bond/v1 JSON with a UTC Instant createdAt and at most 64 unique MAC entries. Credential hex is lowercase on export and is written directly from bytes, without creating a hex String. Duplicate MACs, wrong credential lengths, invalid metadata, and malformed JSON reject the whole file.

## Local storage and connections

New code lives in com.m365bleapp.bond. BondStore serializes all mutations and dual-writes encrypted preferences. The app-private JSON is AES-256-GCM encrypted by Android Keystore and stored in noBackupFilesDir; updates fsync a temporary file before rename. Existing Android backup settings are unchanged.

- Xiaomi writes UPPER_COLON_MAC_token. Existing login reads are unchanged. Registration now calls putXiaomi, so newly paired scooters can be exported.
- Ninebot writes UPPER_COLON_MAC_nb_random, never _token. When stored, these 16 bytes supply both 5C and 5D payloads. Session crypto still uses afterHandshake(name, bleData). Rejection never falls back to APP_DATA.
- Without a Ninebot key, 5C remains APP_DATA and 5D remains the final UID byte. The constant is never stored as a pairing key.
- Opening the screen migrates valid existing _token values to BondStore without deleting preferences.
- Removing a MAC removes its store entry and both possible preference keys.

The screen uses FLAG_SECURE on resume and clears it when leaving. Lists and previews show masked MACs, optional labels/models, and family names. Secret input is password-masked. Flow buffers are wiped on submission/cancellation/disposal, and late background results are wiped after disposal. Auth and Ninebot handshake payloads are excluded from logcat and CSV BLE logs.

The pairing-key resources are localized for all eleven supported languages: English, Traditional and Simplified Chinese, Japanese, Korean, Spanish, French, Italian, Russian, Ukrainian, and Arabic. MAC summaries retain LTR order inside RTL layouts. Cards constrain long labels, actions use full-width buttons, and dialog content scrolls. Dialog actions occupy separate vertical rows with system-bar and keyboard padding. Field labels are separate from inputs so long translations can wrap. No Hilt, ViewModel, or androidx.biometric dependency was added. The former .m365key codec remains as inactive legacy source and is not reachable from the screen.

## Validation

JVM tests cover the public 256-byte known-answer vector in both directions, an independent JCE opener, password policy, tampering, size bounds, JSON validation, manual input, migration, dual writes, selection/conflicts, family replacement/removal, and export confirmation lifetime. Existing Ninebot tests retain the legacy bytes; new cases verify stored random in both 5C/5D and unchanged session-key derivation. Physical scooter acceptance and Android credential/picker lifecycle require device testing.
