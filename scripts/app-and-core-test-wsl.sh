#!/usr/bin/env bash
# Runs core tests + app unit tests + Kover XML in WSL.
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
st=/home/kali/build/pev-core-stage
mkdir -p "$st"
rsync -a --delete --exclude build --exclude .gradle --exclude .git --exclude target \
  --exclude '*.jks' --exclude '*.bak' --exclude research --exclude docs --exclude .idea "$src/" "$st/"
cd "$st"
sed -i 's/\r$//' gradlew
printf 'sdk.dir=/home/kali/android-sdk\n' > local.properties

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

bash gradlew --no-daemon --offline :pev-protocol-core:test :pev-protocol-core:koverXmlReport :app:testDebugUnitTest :app:koverXmlReportDebug -PskipRustBuild 2>&1 | grep -v '^$' | tail -40
python3 scripts/check-core-coverage.py pev-protocol-core/build/reports/kover/report.xml

echo "=== Core test suites ==="
cat pev-protocol-core/build/test-results/test/*.xml | grep -o '<testsuite [^>]*' | sed 's/timestamp.*//'

echo "=== App test suites ==="
cat app/build/test-results/testDebugUnitTest/*.xml | grep -o '<testsuite [^>]*' | sed 's/timestamp.*//'

echo "=== Core Kover summary ==="
tail -c 260 pev-protocol-core/build/reports/kover/report.xml

echo ""
echo "=== App Kover summary ==="
tail -c 260 app/build/reports/kover/reportDebug.xml
