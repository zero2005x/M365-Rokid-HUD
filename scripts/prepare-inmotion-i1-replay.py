from pathlib import Path
import argparse
import csv
import hashlib
import json

parser = argparse.ArgumentParser(description='Preserve external I1 CSV segments for local, raw-envelope replay.')
parser.add_argument('--source-dir', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
source = args.source_dir
output = args.output
output.mkdir(parents=True, exist_ok=True)
manifest = {'sourceKind': 'historical-community-reference', 'redistributed': False, 'traces': {}}
for name in ['V5F', 'V8S', 'alerts']:
    path = source / f'RAW_inmotion_{name}.csv'
    segments = []
    with path.open(newline='') as file:
        for row in csv.reader(file):
            assert len(row) == 2, path
            segments.append(bytes.fromhex(row[1]))
    assert segments and all(0 < len(chunk) <= 65535 for chunk in segments)
    binary = b''.join(len(chunk).to_bytes(2, 'big') + chunk for chunk in segments)
    (output / f'{name}.segments').write_bytes(binary)
    manifest['traces'][name] = {
        'source': str(path), 'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'segments': len(segments), 'wireBytes': sum(map(len, segments)),
        'streamSha256': hashlib.sha256(b''.join(segments)).hexdigest(),
        'segmentedSha256': hashlib.sha256(binary).hexdigest(),
    }
manifest['transformSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
