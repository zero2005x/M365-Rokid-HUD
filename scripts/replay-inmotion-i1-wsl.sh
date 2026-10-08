#!/usr/bin/env bash
set -euo pipefail
impl="$(cd "$(dirname "$0")/.." && pwd)"
stage="${PEV_CORE_STAGE:-/home/kali/build/pev-core-stage}"
reference_dir="${1:?Pass the external directory containing the three RAW_inmotion CSV files}"
out="$stage/build/pev-inmotion-i1-replay"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
python3 -B "$impl/scripts/prepare-inmotion-i1-replay.py" --source-dir "$reference_dir" --output "$out"
core_jar="$stage/pev-protocol-core/build/libs/pev-protocol-core-0.1.0-SNAPSHOT.jar"
kotlin_jar=$(find /home/kali/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name 'kotlin-stdlib-2.2.10.jar' -print -quit)
test -n "$kotlin_jar"
javac --release 17 -cp "$core_jar:$kotlin_jar" -d "$out" "$impl/scripts/InmotionI1Replay.java"
java -cp "$out:$core_jar:$kotlin_jar" InmotionI1Replay "$out" > "$out/replay.json"
python3 - "$core_jar" "$out" "$kotlin_jar" "$impl" <<'PY'
from pathlib import Path
import hashlib,json,sys
out=Path(sys.argv[2])
data=json.loads((out/'replay.json').read_text())
data['coreJarSha256']=hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()
data['kotlinRuntimePath']=sys.argv[3]
data['kotlinRuntimeSha256']=hashlib.sha256(Path(sys.argv[3]).read_bytes()).hexdigest()
data['runnerClassSha256']=hashlib.sha256((out/'InmotionI1Replay.class').read_bytes()).hexdigest()
data['runnerSourceSha256']=hashlib.sha256(Path(sys.argv[4],'scripts/InmotionI1Replay.java').read_bytes()).hexdigest()
data['codecSha256']=hashlib.sha256(Path(sys.argv[4],'pev-protocol-core/src/main/kotlin/io/github/zero2005x/pev/core/codec/inmotioni1/InmotionI1Codec.kt').read_bytes()).hexdigest()
data['transform']=json.loads((out/'manifest.json').read_text())
(out/'replay.json').write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps({name:value['original'] for name,value in data['traces'].items()},indent=2))
PY
