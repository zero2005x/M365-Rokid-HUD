"""Record app logs on both Android devices while USB is disconnected; pull them later."""
import argparse
import datetime as dt
from pathlib import Path
import re
import shlex
import shutil
import subprocess

REMOTE = '/sdcard/Download/M365-diagnostics'
PHONE_FILTERS = ('ScooterRepo:I', 'BleManager:I', 'GatewayService:I', 'M365GattServer:I',
                 'WifiGatewayService:I', 'WifiGatewayServer:I', 'AndroidRuntime:E', '*:S')
GLASSES_FILTERS = ('BleClient:D', 'BleConnectionService:D', 'WifiGatewayClient:D',
                   'CxrMClient:I', 'MainActivity:I', 'AndroidRuntime:E', '*:S')


def detect_serials(adb):
    """Rokid glasses report product:glasses; with exactly one other device, that is the phone."""
    listing = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True, timeout=30).stdout
    attached = [line.split() for line in listing.splitlines()[1:] if ' device ' in f'{line} ']
    glasses = [fields[0] for fields in attached if 'product:glasses' in fields]
    phones = [fields[0] for fields in attached if 'product:glasses' not in fields]
    return (phones[0] if len(phones) == 1 else None), (glasses[0] if len(glasses) == 1 else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'status', 'collect', 'stop'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--phone', help='phone adb serial (default: auto-detect)')
    parser.add_argument('--glasses', help='glasses adb serial (default: auto-detect)')
    args = parser.parse_args()
    adb = shutil.which('adb')
    if not adb:
        raise SystemExit('adb is not on PATH')
    detected_phone, detected_glasses = detect_serials(adb)
    phone = args.phone or detected_phone
    glasses = args.glasses or detected_glasses
    if not phone or not glasses:
        raise SystemExit('Could not identify one phone and one pair of glasses; pass --phone and --glasses')
    devices = (('phone', phone, 'com.m365bleapp', PHONE_FILTERS),
               ('glasses', glasses, 'com.m365hud.glass', GLASSES_FILTERS))

    def run(serial, *parts, check=True):
        result = subprocess.run([adb, '-s', serial, *parts], capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=60)
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return result

    output = args.output or Path(__file__).resolve().parents[1] / 'build/adb-diagnostics' / dt.datetime.now().strftime('capture_%Y%m%d_%H%M%S')
    if args.action == 'collect':
        output.mkdir(parents=True, exist_ok=True)
    for name, serial, package, filters in devices:
        pidfile = f'{REMOTE}/recorder.pid'
        logfile = f'{REMOTE}/logcat.txt'
        # A stale PID may belong to another process. Never stop or reuse it unless
        # its command line identifies our exact logcat output file.
        running = f'''pid=$(cat {shlex.quote(pidfile)} 2>/dev/null); case "$pid" in ''|*[!0-9]*) exit 1;; esac
kill -0 "$pid" 2>/dev/null && tr '\\000' ' ' < /proc/"$pid"/cmdline | grep -F {shlex.quote(logfile)} >/dev/null'''
        active = run(serial, 'shell', running, check=False).returncode == 0
        if args.action == 'start':
            if active:
                print(f'{name}: recorder already running')
                continue
            uid_text = run(serial, 'shell', 'cmd', 'package', 'list', 'packages', '-U', package).stdout
            match = re.search(rf'package:{re.escape(package)} uid:(\d+)', uid_text)
            if not match:
                raise RuntimeError(f'{name}: app package UID unavailable')
            command = shlex.join(['logcat', '-b', 'main,system,crash', '-T', '1', '-v', 'threadtime,year,zone',
                                  f'--uid={match[1]}', '-f', logfile, '-r', '4096', '-n', '4', *filters])
            launch = f'''mkdir -p {shlex.quote(REMOTE)}
nohup sh -c {shlex.quote('exec ' + command)} > {shlex.quote(REMOTE + '/recorder.out')} 2>&1 < /dev/null &
echo $! > {shlex.quote(pidfile)}'''
            run(serial, 'shell', launch)
            if run(serial, 'shell', running, check=False).returncode:
                raise RuntimeError(f'{name}: recorder failed: ' + run(serial, 'shell', 'cat', REMOTE + '/recorder.out').stdout)
            print(f'{name}: recording app logs on device (4 MB x 5 files); survives USB removal, ends on reboot')
        elif args.action == 'status':
            print(f'{name}: recorder ' + ('running' if active else 'stopped'))
        elif args.action == 'stop':
            if active:
                run(serial, 'shell', f'{running} && kill "$pid"')
            print(f'{name}: recorder stopped; saved files retained')
        else:
            target = output / name
            target.mkdir(exist_ok=True)
            run(serial, 'pull', REMOTE, str(target))
            snapshot = run(serial, 'shell', shlex.join(['logcat', '-d', '-t', '4000', '-v', 'threadtime,year,zone', *filters]))
            (target / 'logcat-snapshot.txt').write_text(snapshot.stdout, encoding='utf-8')
            state = run(serial, 'shell', 'dumpsys', 'package', package).stdout
            metadata = [line.strip() for line in state.splitlines()
                        if 'versionCode=' in line or 'versionName=' in line]
            paths = run(serial, 'shell', 'pm', 'path', package).stdout.splitlines()
            for entry in paths:
                if entry.startswith('package:') and entry.endswith('/base.apk'):
                    metadata.append(run(serial, 'shell', 'sha256sum ' + shlex.quote(entry[8:])).stdout.strip())
            (target / 'installed-version.txt').write_text('\n'.join(metadata), encoding='utf-8')
            if name == 'phone':
                # Collect only this app's diagnostic CSVs, never pairing tokens or preferences.
                archive = f'{REMOTE}/app-logs.tgz'
                command = f'tar -czf {archive} -C /data/user/0/{package}/files logs'
                result = run(serial, 'shell', 'su -c ' + shlex.quote(command), check=False)
                if result.returncode:
                    print('phone: internal CSV pull unavailable; use Settings > Logs > Export all')
                else:
                    run(serial, 'pull', archive, str(target / 'app-logs.tgz'))
            print(f'{name}: saved to {target}')
    if args.action == 'collect':
        print(f'Collected diagnostic bundle: {output}')


if __name__ == '__main__':
    main()
