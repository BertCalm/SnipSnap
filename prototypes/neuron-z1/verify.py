"""Validate regenerated audio against the published byte hashes, then package it."""
from pathlib import Path
import hashlib
import json
import shutil
import sys
import wave
import xml.etree.ElementTree as ET
import zipfile

prototype = Path(__file__).resolve().parent
repo = prototype.parents[1]
output = repo / 'build/neuron-z1'
mode = sys.argv[1] if len(sys.argv) == 2 else ''
assert mode in ('original', 'all'), 'Usage: verify.py original|all'
manifest = json.loads((prototype / 'reference-audio-sha256.json').read_text())
selected = {k: v for k, v in manifest.items() if mode == 'all' or k.startswith('audition/')}
assert len(selected) == (146 if mode == 'all' else 86)
for relative, expected in selected.items():
    path = output / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, relative
    with wave.open(str(path), 'rb') as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getnframes()) == (44100, 1, 3, 61740), relative
print(f'{len(selected)} WAVs match the published bytes and expected format.')
if mode == 'original':
    raise SystemExit(0)

tests = []
for name in ('MorphProofTest', 'MorphSpaceRevisionTest', 'StringsTest'):
    suite = ET.parse(output / 'checks' / f'TEST-com.snipsnap.synth.{name}.xml').getroot()
    tests.append({'name': name, **{k: int(suite.get(k, 0)) for k in ('tests','failures','errors','skipped')}})
assert sum(t['tests'] for t in tests) == 43
assert all(t['failures'] == t['errors'] == t['skipped'] == 0 for t in tests)
result = {'tests': tests, 'published_wavs_byte_identical': len(selected),
          'source_sha256': {str(p.relative_to(prototype)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted((prototype / 'src').glob('*.kt'))},
          'listening_approval': False}
(output / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')

# Assemble a fresh local audition; retain the shipped evidence as historical
# evidence and keep this run's measurements in the rendered directories.
site = output / 'site'
site.mkdir(exist_ok=True)
shutil.copytree(output / 'audition', site, dirs_exist_ok=True)
shutil.copy2(site / 'index.html', site / 'original.html')
shutil.copy2(prototype / 'site/index.html', site / 'index.html')
shutil.copytree(output / 'space-r2', site / 'space-r2', dirs_exist_ok=True)
shutil.copy2(prototype / 'DESIGN.md', site / 'space-r2/README.md')
shutil.copy2(prototype / 'evidence/space-r2/verification.json', site / 'space-r2/verification.json')
shutil.copy2(prototype / 'evidence/original/verification.json', site / 'verification.json')
shutil.copy2(output / 'verification.json', site / 'fresh-checkout-verification.json')

def archive_tree(source, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob('*')):
            if path.is_file() and path.name != target.name:
                relative = str(path.relative_to(source))
                if source == site and relative == 'index.html':
                    archive.writestr(relative, path.read_text().replace(
                        '<a href="space-r2-comparison.zip" download>Download this comparison</a>', ''))
                else:
                    archive.write(path, relative)

shutil.copy2(prototype / 'evidence/original/verification.json', output / 'audition/verification.json')
archive_tree(output / 'audition', output / 'neuron-z1-audition-site.zip')
shutil.copy2(output / 'neuron-z1-audition-site.zip', site / 'neuron-z1-audition-site.zip')
# Package outside the site directory to avoid an archive containing itself.
archive_tree(site, output / 'space-r2-comparison.zip')
shutil.copy2(output / 'space-r2-comparison.zip', site / 'space-r2-comparison.zip')
print(json.dumps(result, indent=2))
print(f'Local audition: {site / "index.html"}')
print('New ZIP timestamps and timings differ; only audio-byte equality is claimed.')
