"""Verify regenerated PCM WAVs against the original R3 audition evidence."""
from pathlib import Path
import hashlib
import json
import sys

HERE = Path(__file__).resolve().parent
OUTPUT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else HERE.parents[1] / 'testkit' / 'afterimage-audition'
manifest = json.loads((HERE / 'evidence' / 'r3-wav-manifest.json').read_text())
failures = []
for row in manifest:
    path = OUTPUT / row['path']
    if not path.is_file():
        failures.append(f"missing: {row['path']}")
    elif hashlib.sha256(path.read_bytes()).hexdigest() != row['sha256']:
        failures.append(f"changed: {row['path']}")
if failures:
    raise SystemExit('\n'.join(failures))
print(f'All {len(manifest)} WAV files match the original R3 SHA-256 manifest.')
