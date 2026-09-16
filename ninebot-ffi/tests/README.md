# JNI tests

Run on a Linux host with Rust and JDK 17+:

```sh
cargo test --manifest-path ninebot-ffi/Cargo.toml
bash ninebot-ffi/tests/run-jni-tests.sh
```

The five Rust tests exercise the production session registry and release helper:
invalid/stale handles, idempotent release, isolation, concurrent release while
an operation retains an Arc, and unique allocation across threads.

The Java harness compiles a class with the same package and native method
signatures as Android M365Native, loads the real host cdylib, and resolves all
seven JNI exports. It runs with -Xcheck:jni. Its 56 checks cover handshake
output layout and single-use handles, symmetric ECDH/DID, malformed inputs,
login output, nonce counter bounds, encrypted framing/checksum, device-direction
decryption, corrupted CCM tags (with repaired checksum), truncated input, use
after free and Java-thread concurrency.

The fixed login and device-frame vectors are synthetic, independently generated
using Python cryptography HKDF-SHA256/AESCCM and Python hmac:
- Token = bytes 0..11; app random = 0..15; device random = 16..31.
- HKDF salt = app random || device random; info = mible-login-info; length = 64.
- Login proof = HMAC-SHA256(derived[16:32], salt).
- Device AES key = derived[0:16], IV = derived[32:36].
- Nonce = IV || four zero bytes || counter 42 as u16 LE || two zero bytes.
- AES-CCM tag length = 4, empty associated data.
- Decrypted bytes = 23 01 b0 34 12 aa bb cc dd.
- UART body = 05 || counter u16 LE || ciphertext/tag, prefixed with 55 ab
  and suffixed by the u16 LE complement of the body-byte sum.

These are host JNI contract tests, not hardware protocol validation or Android
runtime tests. Android ABIs are separately rebuilt by Gradle. Python is only
the provenance of the committed vectors; running these tests does not require it.
