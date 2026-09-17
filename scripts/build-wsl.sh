#!/usr/bin/env bash
set -euo pipefail
source_root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
source "${M365_WSL_ENV:-/home/kali/ScooterHacking/env.sh}"
stage="${M365_WSL_STAGE:-/home/kali/ScooterHacking/windows-build}"
python3 "$source_root/scripts/sync-wsl.py" "$source_root" "$stage"
cd "$stage"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
cargo test --manifest-path ninebot-ble/Cargo.toml --no-default-features
cargo test --manifest-path ninebot-ffi/Cargo.toml
bash ninebot-ffi/tests/run-jni-tests.sh
export M365_NATIVE_TEST_LIBRARY="$stage/ninebot-ffi/target/debug/libninebot_ffi.so"
bash gradlew --no-daemon :app:assembleDebug :glass-hud:assembleDebug :app:testDebugUnitTest :glass-hud:testDebugUnitTest
python3 "$source_root/scripts/sync-wsl.py" "$source_root" "$stage" --collect
