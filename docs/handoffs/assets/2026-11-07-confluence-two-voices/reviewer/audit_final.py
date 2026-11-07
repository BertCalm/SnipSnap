"""Independent final-file audit. Reads shared outputs, writes only reviewer/final-audit.json."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import wave
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    with wave.open(str(path)) as wav:
        shape = (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes())
        data = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.uint8).reshape(-1, 3)
    pcm = data[:, 0].astype(np.int32) | (data[:, 1].astype(np.int32) << 8) | (data[:, 2].astype(np.int32) << 16)
    pcm = np.where(pcm >= 0x800000, pcm - 0x1000000, pcm)
    return shape, pcm, np.maximum(-1, pcm.astype(np.float32) / np.float32(8388607))


def loudness(path):
    result = subprocess.run(['ffmpeg', '-v', 'error', '-nostats', '-i', str(path),
                             '-af', 'ebur128=metadata=1:peak=true,ametadata=print:file=-',
                             '-f', 'null', '-'], capture_output=True, text=True, check=True)
    values = re.findall(r'lavfi\.r128\.I=([-+0-9.]+)', result.stdout)
    assert values, 'No independent R128 measurement'
    peaks = re.findall(r'lavfi\.r128\.true_peak=([-+0-9.]+)', result.stdout)
    return float(values[-1]), float(peaks[-1]) if peaks else None


def main():
    before = digest(ROOT / 'manifest.json')
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    report = json.loads((ROOT / 'reports/measurement.json').read_text())
    conditions = manifest['conditions']
    assert conditions['mono'] and conditions['dry'] and conditions['matched']
    assert (conditions['channels'], conditions['sampleRate'], conditions['seconds']) == (1, 44100, 3.0)
    expected_ids = ['a-alone', 'b-alone', 'a-plus-a', 'a-plus-b', 'synthetic-b-alone', 'a-plus-synthetic-b']
    assert [c['id'] for c in manifest['clips']] == expected_ids
    parts = {'a-alone': ['a'], 'b-alone': ['b'], 'a-plus-a': ['double-0', 'double-1'],
             'a-plus-b': ['a', 'b'], 'synthetic-b-alone': ['synthetic-b'],
             'a-plus-synthetic-b': ['a', 'synthetic-b']}
    stems = {}
    for key, info in report['componentMatching'].items():
        original = np.fromfile(ROOT / f'work/stem-{key}.f32', dtype='<f4')
        stems[key] = np.multiply(original, np.float32(info['constantGain']), dtype=np.float32)
    clips = []
    for clip in manifest['clips']:
        path = ROOT / clip['path']
        assert digest(path) == clip['sha256'] and path.stat().st_size == clip['bytes']
        shape, pcm, audio = read(path)
        assert shape == (1, 3, 44100, 132300)
        chosen = parts[clip['id']]
        expected = stems[chosen[0]]
        if len(chosen) == 2:
            expected = np.multiply(np.add(expected, stems[chosen[1]], dtype=np.float32),
                                   np.float32(1 / np.sqrt(2)), dtype=np.float32)
        expected = np.multiply(expected, np.float32(clip['level']['finalConstantGain']), dtype=np.float32)
        error = float(np.max(np.abs(audio - expected)))
        assert error <= 1.2e-7, f"Undeclared mix processing in {clip['id']}: {error}"
        measured_i, true_peak = loudness(path)
        reported_i = clip['level']['measuredAfterPcm24']['integratedLufs']
        assert abs(measured_i - reported_i) <= .002
        assert np.isfinite(audio).all() and np.max(np.abs(audio)) < .85
        assert np.all(pcm[-11025:] == 0)
        clips.append({'id': clip['id'], 'sha256': digest(path), 'shape': shape,
                      'independentIntegratedLufs': measured_i, 'reportedIntegratedLufs': reported_i,
                      'independentTruePeakLinear': true_peak, 'samplePeak': float(np.max(np.abs(audio))),
                      'maxVsDeclaredStemSumError': error, 'last250msExactlySilent': True})
    spread = max(c['independentIntegratedLufs'] for c in clips) - min(c['independentIntegratedLufs'] for c in clips)
    assert spread <= .1
    assert manifest['heard'] is False and 'pending' in manifest['status']
    assert 'not' in ' '.join(manifest['disclosures']).lower()
    sources = manifest['sources']
    assert len(sources) == 2 and len({s['singerId'] for s in sources}) == 2
    assert len({s['originalSha256'] for s in sources}) == 2
    for source in sources:
        assert digest(ROOT / source['relativePath']) == source['sha256']
        assert source['license'] == 'CC BY 4.0'
        assert source['licenseUrl'] == 'https://creativecommons.org/licenses/by/4.0/'
        assert all(name in source['attribution'] for name in ['Julia Wilkins', 'Prem Seetharaman', 'Alison Wahl', 'Bryan Pardo'])
    assert '/workspace/' not in (ROOT / 'manifest.json').read_text()
    assert '/workspace/' not in (ROOT / 'public-sources.json').read_text()
    references = [{'url': ref['url'], 'exists': (ROOT / ref['url']).is_file()} for ref in manifest['sourceReferences']]
    assert digest(ROOT / 'manifest.json') == before, 'Manifest changed during audit; rerun when frozen'
    result = {'allAudioAndSourceChecksPass': True, 'manifestSha256': before,
              'sourceEvidence': 'reviewer/source-audit.json', 'clips': clips,
              'independentLufsSpread': spread, 'referenceFiles': references,
              'allReferenceFilesPresent': all(r['exists'] for r in references),
              'ownerListeningVerdict': None,
              'limits': ['This verifies declared signal operations and source identities, not whether two people are perceived.',
                         'Raw source and delivered audition files are different processing stages.']}
    (ROOT / 'reviewer/final-audit.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: value for key, value in result.items() if key != 'clips'}, indent=2))


if __name__ == '__main__':
    main()
