#!/usr/bin/env bash
# Core/phone/glasses verification without writing to the checked-out build outputs.
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
st="${PEV_VERIFY_STAGE:-/home/kali/build/pev-core-stage}"
case "$st" in /home/kali/build/pev-*) ;; *) echo "Refusing stage outside /home/kali/build/pev-*" >&2; exit 2 ;; esac
mkdir -p "$st"
rsync -a --delete --exclude build --exclude .gradle --exclude .git --exclude target \
  --exclude '*.jks' --exclude '*.bak' --exclude research --exclude docs --exclude .idea "$src/" "$st/"
cd "$st"
sed -i 's/\r$//' gradlew
printf 'sdk.dir=/home/kali/android-sdk\n' > local.properties
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p build/verification
offline_args=(--offline)
# Use PEV_GRADLE_ONLINE=1 for a first run with missing declared dependencies.
if [[ "${PEV_GRADLE_ONLINE:-0}" == 1 ]]; then offline_args=(); fi
# Existing Windows native artifacts are used explicitly. This does not validate
# their freshness across hosts or exercise Rust/JNI; release builds still enforce it.
bash gradlew --no-daemon "${offline_args[@]}" -PskipRustBuild \
  :pev-protocol-core:koverXmlReport :app:koverXmlReportDebug :glass-hud:koverXmlReportDebug \
  :app:lintDebug :glass-hud:lintDebug :app:assembleDebug :glass-hud:assembleDebug \
  2>&1 | tee build/verification/hud-gradle.log
python3 scripts/check-core-coverage.py pev-protocol-core/build/reports/kover/report.xml
python3 - <<'PY'
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET
for module, variant in [('pev-protocol-core', 'test'), ('app', 'testDebugUnitTest'), ('glass-hud', 'testDebugUnitTest')]:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = list(Path(module, 'build/test-results', variant).glob('TEST-*.xml'))
    if not reports:
        raise SystemExit(f'Missing test reports: {module}')
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    print(module, totals)
    if any(totals[key] for key in ['failures', 'errors', 'skipped']):
        raise SystemExit(f'Incomplete verification: {module}')
for module in ['app', 'glass-hud']:
    for apk in Path(module, 'build/outputs/apk/debug').glob('*.apk'):
        print(apk.resolve(), 'SHA256', hashlib.sha256(apk.read_bytes()).hexdigest())
PY
