#!/usr/bin/env python3
"""Validate the actual audition assets without needing third-party audio libraries."""
import hashlib
import json
from pathlib import Path
import re
import sys
import wave
import zipfile

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'testkit/spore-station')
m = json.loads((root / 'manifest.json').read_text())
assert len(m['clips']) == 79
assert len({c['id'] for c in m['clips']}) == 79
wav_count = 0
for clip in m['clips']:
    for kind in ('raw', 'matched'):
        path = root / clip[kind]
        with wave.open(str(path)) as w:
            assert w.getframerate() == 44100 and w.getsampwidth() == 3
            assert w.getnchannels() == clip['channels'] and w.getnframes() == clip['frames']
        wav_count += 1
    assert hashlib.sha256((root / clip['raw']).read_bytes()).hexdigest() == clip['sha256']
    if clip['recipeHref']:
        assert json.loads((root / clip['recipeHref']).read_text()) == clip['recipe']
for download in m['downloads']:
    path = root / download['href']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == download['sha256']
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        assert sum(n.endswith('.wav') for n in z.namelist()) == 48
        assert sum(n.endswith('.xtd') for n in z.namelist()) == 1
        assert sum(n.endswith('.xpm') for n in z.namelist()) == 1
for href in re.findall(r'href="([^"]+)"', (root / 'index.html').read_text()):
    if not href.startswith(('https:', '#')):
        assert (root / href).is_file(), href
with zipfile.ZipFile(root / 'spore-listening-pack.zip') as z:
    assert z.testzip() is None
    assert sum(n.endswith('.wav') for n in z.namelist()) == 158
space_tail_energy = []
for step in (1, 5, 9):
    with wave.open(str(root / f'audio/sweep-space-{step}.wav')) as w:
        pcm = w.readframes(w.getnframes())
    samples = [int.from_bytes(pcm[i:i+3], 'little', signed=True) / 8388608
               for i in range(0, len(pcm), 3)]
    space_tail_energy.append(sum(x*x for x in samples[8820:]) / 44100)
assert space_tail_energy[0] < space_tail_energy[1] < space_tail_energy[2]
report = {
    'clips': len(m['clips']), 'wavFiles': wav_count, 'kitPrograms': 2,
    'velocitySamplesPerProgram': 48, 'wavHeadersAndHashes': 'passed',
    'spaceTailEnergyAfter200ms': space_tail_energy,
    'recipeFiles': 'passed', 'downloadArchives': 'passed', 'staticLinks': 'passed',
    'maximumAbsoluteMean': max(abs(c['dc']) for c in m['clips']),
    'dcNote': 'Short asymmetric kick waveforms retain a nonzero full-file mean; see per-clip metrics. No new DC correction applied.',
    'browserPlayback': 'not verified: managed browser QA tool unavailable',
    'ownerListening': 'pending', 'mpcHardware': 'pending'
}
(root / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
