#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build
classes="target/jni-test-classes"
mkdir -p "$classes"
javac -d "$classes" tests/java/com/m365bleapp/ffi/M365Native.java
java -Xcheck:jni -cp "$classes" com.m365bleapp.ffi.M365Native "$(pwd)/target/debug/libninebot_ffi.so"
