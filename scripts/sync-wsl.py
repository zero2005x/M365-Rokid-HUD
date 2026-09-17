"""Copy source into a Linux build directory; never overwrite Windows local settings."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

source = Path(sys.argv[1]).resolve()
stage = Path(sys.argv[2]).resolve()
if stage == source or stage in source.parents or source in stage.parents:
    raise SystemExit('Source and staging directories must be separate.')
if '--collect' in sys.argv:
    for module in ('app', 'glass-hud'):
        for relative in ('outputs/apk/debug', 'test-results/testDebugUnitTest', 'reports/tests/testDebugUnitTest'):
            src = stage / module / 'build' / relative
            dst = source / module / 'build' / relative
            if src.exists():
                shutil.copytree(src, dst, dirs_exist_ok=True)
    print('Collected rebuilt Debug APKs and test reports into the Windows project.')
else:
    raw = subprocess.check_output(['git', '-C', str(source), 'ls-files', '-z', '--cached', '--others', '--exclude-standard'])
    files = set()
    for name in raw.decode().split('\0'):
        if not name or name.startswith('app/src/main/jniLibs/'):
            continue
        path = source / name
        if path.is_file():
            if path.is_symlink() or not path.resolve().is_relative_to(source):
                raise SystemExit('Source path escapes project: ' + name)
            files.add(name)
    stage.mkdir(parents=True, exist_ok=True)
    manifest = stage / '.wsl-source-manifest.json'
    previous = set(json.loads(manifest.read_text())) if manifest.exists() else set()
    for name in previous - files:
        target = (stage / name).resolve()
        if not target.is_relative_to(stage):
            raise SystemExit('Stale manifest path escapes staging directory.')
        target.unlink(missing_ok=True)
    for name in sorted(files):
        dst = stage / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        data = (source / name).read_bytes()
        if not dst.exists() or dst.read_bytes() != data:
            dst.write_bytes(data)
    manifest.write_text(json.dumps(sorted(files), indent=2))
    print(f'Staged {len(files)} source files from {source} into {stage}.')
