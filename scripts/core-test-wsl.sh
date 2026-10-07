#!/usr/bin/env bash
# Runs the pure-JVM core tests + Kover XML in WSL (native Windows Gradle cannot open a loopback socket here).
# Usage (from PowerShell): wsl.exe -d kali-linux --exec bash <wslpath of this file>
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
st=/home/kali/build/pev-core-stage
mkdir -p "$st"
rsync -a --delete --exclude build --exclude .gradle --exclude .git --exclude target \
  --exclude '*.jks' --exclude '*.bak' --exclude research --exclude docs --exclude .idea "$src/" "$st/"
cd "$st"
sed -i 's/\r$//' gradlew
printf 'sdk.dir=/home/kali/android-sdk\n' > local.properties
bash gradlew --no-daemon --offline :pev-protocol-core:test :pev-protocol-core:koverXmlReport 2>&1 | grep -v '^$' | tail -40
cat pev-protocol-core/build/test-results/test/*.xml | grep -o '<testsuite [^>]*' | sed 's/timestamp.*//'
tail -c 260 pev-protocol-core/build/reports/kover/report.xml