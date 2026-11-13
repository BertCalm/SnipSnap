"""Validate the published study assets without claiming browser or sampler playback."""
import hashlib
import io
import json
import sys
import wave
import zipfile
from pathlib import Path
root = Path(sys.argv[1])
m = json.loads((root / 'manifest.json').read_text())
assert len(m['clips']) == 10
for relative, digest in m['assetHashes'].items():
    f = root / relative
    assert hashlib.sha256(f.read_bytes()).hexdigest() == digest, relative
    with wave.open(str(f)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 3, 44100)
        assert w.getnframes() > 1000
for c in m['clips']:
    assert c['group'] in ['compare', 'parts', 'touch']
    assert (root / c['raw']).is_file() and (root / c['matched']).is_file()
for name, digest in m['sourceHashes'].items():
    assert hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() == digest, name
assert json.loads((root / 'model.json').read_text()) == json.loads(Path(__file__).with_name('model.json').read_text())
with zipfile.ZipFile(root / 'synthetic-snare-study.zip') as z:
    assert z.testzip() is None
    wavs = [n for n in z.namelist() if n.endswith('.wav')]
    assert len(wavs) == 9
    assert all(n.startswith('snare-') for n in wavs)
    assert not any('reference' in n or 'previous' in n for n in z.namelist())
    assert len({hashlib.sha256(z.read(n)).hexdigest() for n in wavs}) == 9
    for n in wavs:
        assert z.read(n) == (root / 'synthetic-wavs' / n).read_bytes()
        with wave.open(io.BytesIO(z.read(n))) as w:
            assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 3, 44100)
    assert z.read('model.json') == (root / 'model.json').read_bytes()
for name in ['study.md', 'comparison.png', 'report.json', 'model.json']:
    assert (root / name).is_file()
assert all(f.stat().st_size < 25 * 1024 * 1024 for f in root.rglob('*') if f.is_file())
print('PASS: 20 audition WAVs, nine distinct synthetic download WAVs, ZIP/PCM identity, hashes, model and links.')
