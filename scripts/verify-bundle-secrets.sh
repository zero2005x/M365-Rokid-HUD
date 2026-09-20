#!/usr/bin/env bash
# verify-bundle-secrets.sh - fail if a release artifact carries Rokid credentials.
#
# ## Why this exists
#
# BuildConfig string constants are compiled into the DEX as plaintext, so any
# credential injected at build time is readable by anyone who downloads the app.
# A live Rokid Client Secret reached the Play AAB exactly this way.
#
# `app/build.gradle.kts` now injects the credentials into the debug variant only,
# so a release artifact should never contain them. This script is the regression
# guard for that invariant: it reads whatever credentials are configured locally
# and asserts their bytes appear nowhere in the artifact. If someone later moves
# the injection back into `defaultConfig`, a local release build fails here even
# though nothing else would notice.
#
# It checks the configured values rather than decompiling BuildConfig, so it has
# no dependency beyond python3's stdlib and works on an .aab or an .apk.
#
# Usage:
#   scripts/verify-bundle-secrets.sh app/build/outputs/bundle/release/app-release.aab
#   scripts/verify-bundle-secrets.sh app/build/outputs/apk/release/app-release.apk
#
# Exit codes: 0 clean, 1 a credential was found, 2 nothing to check / bad usage.
set -uo pipefail

artifact="${1:-}"
if [[ -z "$artifact" ]]; then
    echo "usage: $0 <path to .aab or .apk>" >&2
    exit 2
fi
if [[ ! -f "$artifact" ]]; then
    echo "error: $artifact not found" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"

# Same precedence the Gradle build uses: local.properties first, then the
# environment. Values are never printed.
python3 - "$artifact" "$root/local.properties" <<'PY'
import hashlib
import os
import sys
import zipfile

artifact, props_path = sys.argv[1], sys.argv[2]

KEYS = ("ROKID_CLIENT_ID", "ROKID_CLIENT_SECRET", "ROKID_ACCESS_KEY")

props = {}
if os.path.exists(props_path):
    for line in open(props_path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()

configured = {}
for k in KEYS:
    v = props.get(k) or os.environ.get(k) or ""
    if v:
        configured[k] = v

if not configured:
    print("verify-bundle-secrets: no Rokid credentials configured locally; "
          "nothing to look for.")
    print("  (this is the expected state for CI and for a clean release machine)")
    sys.exit(2)

# Read every entry that can hold compiled code. DEX for an APK/AAB, and the
# native libs in case a credential ever gets passed down to Rust.
blob = bytearray()
with zipfile.ZipFile(artifact) as z:
    names = [n for n in z.namelist()
             if n.endswith(".dex") or n.endswith(".so")]
    for n in names:
        blob += z.read(n)

print(f"verify-bundle-secrets: scanning {artifact}")
print(f"  {len(names)} code entries, {len(blob)} bytes")
print(f"  looking for {len(configured)} configured credential(s): "
      f"{', '.join(sorted(configured))}")

leaked = []
for k, v in configured.items():
    fp = hashlib.sha256(v.encode()).hexdigest()[:8]
    # Match on the value as configured and with dashes stripped: the published
    # leak stored the UUID form with its dashes removed, so a byte-exact search
    # for only the configured spelling would have missed it.
    for variant in {v, v.replace("-", "")}:
        if len(variant) >= 8 and variant.encode() in blob:
            leaked.append((k, fp, len(variant)))
            break

if leaked:
    print()
    for k, fp, ln in leaked:
        print(f"::error::{k} (sha256[:8]={fp}, len={ln}) is present in {artifact}")
    print()
    print("FAIL: the artifact carries Rokid credentials and must not be published.")
    print("      Credentials belong in the debug variant only - check that the")
    print("      buildConfigField calls are still inside `buildTypes { debug { ... } }`")
    print("      and not in defaultConfig.")
    sys.exit(1)

print()
print("OK: no configured Rokid credential appears in the artifact.")
sys.exit(0)
PY
