#!/usr/bin/env python3
"""Validate the actual audition assets without needing third-party audio libraries."""
import hashlib
import gzip
import io
import json
from pathlib import Path
import re
import sys
import wave
import zipfile

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'testkit/spore-station')
m = json.loads((root / 'manifest.json').read_text())
assert len(m['clips']) == 97
assert len({c['id'] for c in m['clips']}) == 97
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
        native = next(n for n in z.namelist() if n.endswith('/kit.json'))
        kit = json.loads(z.read(native))
        assert len(kit['pads']) == 18
        assert all(len(p['chain']['boundaries']) == 9 and len(p['chain']['zones']) == 3 for p in kit['pads'])
        assert sum(n.endswith('.wav') for n in z.namelist()) == 18
        sfz_name = next(n for n in z.namelist() if n.endswith('.sfz'))
        sfz = z.read(sfz_name).decode()
        sample_root = re.search(r'default_path=(.*)', sfz)[1].strip()
        regions = [line for line in sfz.splitlines() if line.startswith('<region>')]
        assert len(regions) == 162
        for i, pad in enumerate(kit['pads']):
            wav_name = str(Path(sfz_name).parent / sample_root / pad['sample'])
            with wave.open(io.BytesIO(z.read(wav_name))) as w:
                frames = w.getnframes()
            boundaries = pad['chain']['boundaries'] + [frames]
            for slice_index, line in enumerate(regions[i*9:(i+1)*9]):
                zone = pad['chain']['zones'][slice_index // 3]
                fields = dict(re.findall(r'(\w+)=([^ ]+)', line))
                assert int(fields['offset']) == boundaries[slice_index]
                assert int(fields['end']) == boundaries[slice_index+1] - 1
                assert int(fields['seq_length']) == 3 and int(fields['seq_position']) == slice_index % 3 + 1
                assert int(fields['lovel']) == zone['velStart'] and int(fields['hivel']) == zone['velEnd']
        # Confirm what XTD actually writes, while keeping its absent WAV slice map disclosed.
        xtd = next(n for n in z.namelist() if n.endswith('.xtd'))
        tree = json.loads(gzip.decompress(z.read(xtd)).decode().split('\n', 5)[5])
        def instruments(node):
            if isinstance(node, dict):
                if 'instruments' in node:
                    return node['instruments']
                for value in node.values():
                    found = instruments(value)
                    if found is not None:
                        return found
            elif isinstance(node, list):
                for value in node:
                    found = instruments(value)
                    if found is not None:
                        return found
        for pad, instrument in zip(kit['pads'], instruments(tree)):
            for zone, layer in zip(reversed(pad['chain']['zones']), instrument['layersv']):
                assert layer['sliceCycleLength'] == 3 and layer['sliceIncrement'] == 1
                assert layer['sliceIndex'] == zone['baseSlice']
                assert layer['velocityStart'] == zone['velStart'] and layer['velocityEnd'] == zone['velEnd']
        assert sum(n.endswith('.sfz') for n in z.namelist()) == 1
        assert sum(n.endswith('.xtd') for n in z.namelist()) == 1
        assert sum(n.endswith('.xpm') for n in z.namelist()) == 1
for href in re.findall(r'href="([^"]+)"', (root / 'index.html').read_text()):
    if not href.startswith(('https:', '#')):
        assert (root / href).is_file(), href
with zipfile.ZipFile(root / 'spore-listening-pack.zip') as z:
    assert z.testzip() is None
    assert sum(n.endswith('.wav') for n in z.namelist()) == 194
space_tail_energy = []
for step in (1, 5, 9):
    with wave.open(str(root / f'audio/sweep-space-{step}.wav')) as w:
        pcm = w.readframes(w.getnframes())
    samples = [int.from_bytes(pcm[i:i+3], 'little', signed=True) / 8388608
               for i in range(0, len(pcm), 3)]
    space_tail_energy.append(sum(x*x for x in samples[8820:]) / 44100)
assert space_tail_energy[0] < space_tail_energy[1] < space_tail_energy[2]
report = {
    'clips': len(m['clips']), 'wavFiles': wav_count, 'kitPrograms': 3,
    'takesPerProgram': 162, 'packedWavsPerProgram': 18, 'wavHeadersAndHashes': 'passed',
    'spaceTailEnergyAfter200ms': space_tail_energy,
    'recipeFiles': 'passed', 'downloadArchives': 'passed', 'staticLinks': 'passed',
    'maximumAbsoluteMean': max(abs(c['dc']) for c in m['clips']),
    'dcNote': 'Short asymmetric kick waveforms retain a nonzero full-file mean; see per-clip metrics. No new DC correction applied.',
    'browserPlayback': 'not verified: managed browser QA tool unavailable',
    'ownerListening': 'pending', 'mpcHardware': 'pending; XTD slice map absent, XPM first-take fallback',
    'sfzGridRegions': '162 explicit velocity and sequence regions per program; sample offsets verified'
}
(root / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
