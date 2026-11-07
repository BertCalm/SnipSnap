"""Rebuild the current/louder listening copies from saved matched PCM24 (Python 3, NumPy, SciPy and ffmpeg); no DSP/model change."""
import argparse
from pathlib import Path
import hashlib
import json
import subprocess
import wave

import numpy as np
from scipy.signal import resample_poly

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--render', type=Path, required=True, help='Kotlin render directory containing manifest.json and its WAV files')
parser.add_argument('--output', type=Path, required=True, help='Artifact parent; named delivery files and working subdirectories are created here')
parser.add_argument('--overwrite', action='store_true', help='Replace this helper\'s existing output files')
args = parser.parse_args()
ARTIFACTS = args.output.expanduser().resolve()
ROOT = args.render.expanduser().resolve()
if ARTIFACTS.is_relative_to(ROOT):
    parser.error('--output must be outside the input render directory')
if not (ROOT / 'manifest.json').is_file():
    parser.error('--render must contain manifest.json')
OUT = ARTIFACTS / 'resonant-body-v2-level-probe' / 'listening-master'
DIRECT = ARTIFACTS / 'resonant-body-v2-louder-listen.flac'
existing = [p for p in [OUT, DIRECT] if p.exists()]
if existing and not args.overwrite:
    parser.error('Existing output: ' + ', '.join(str(p) for p in existing) + '; use --overwrite to replace it')
OUT.mkdir(parents=True, exist_ok=True)
manifest = json.loads((ROOT / 'manifest.json').read_text())
by_id = {c['id']: c for c in manifest['cases']}
case_ids = ['default-burst-v2-harmonic', 'default-burst-v2-bar',
            'default-sustained-v2-harmonic', 'default-sustained-v2-bar']
gain_db = {'default-burst': 1.5, 'default-sustained': 12.0}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

before = {str(p.relative_to(ROOT)): digest(p) for p in ROOT.rglob('*') if p.is_file()}

def load_pcm24(path):
    with wave.open(str(path), 'rb') as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 3 and wav.getframerate() == 44100
        data = wav.readframes(wav.getnframes())
    a = np.frombuffer(data, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
    values = a[:, 0] | (a[:, 1] << 8) | (a[:, 2] << 16)
    return ((values ^ 0x800000) - 0x800000).astype(np.int32), data

def pcm_bytes(values):
    return values.astype('<i4').view(np.uint8).reshape(-1, 4)[:, :3].tobytes()

def metrics(values):
    x = values.astype(np.float64) / 8388608.0
    assert np.isfinite(x).all()
    return {'samplePeak': float(np.max(np.abs(x))),
            'rms': float(np.sqrt(np.mean(x*x))),
            'mean': float(np.mean(x)),
            'truePeak4xScipy': float(np.max(np.abs(resample_poly(x, 4, 1, window=('kaiser', 8.6), padtype='constant'))))}

def flac(stem, data, target=None):
    wav_path = OUT / (stem + '.wav')
    target = target or OUT / (stem + '.flac')
    with wave.open(str(wav_path), 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(3); wav.setframerate(44100); wav.writeframes(data)
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(wav_path), '-map_metadata', '-1',
                    '-c:a', 'flac', '-compression_level', '12', '-metadata_header_padding', '0', str(target)], check=True)
    decoded = subprocess.check_output(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(target),
                                       '-f', 's24le', '-c:a', 'pcm_s24le', 'pipe:1'])
    assert decoded == data, target
    return {'path': str(target), 'file': target.name, 'bytes': target.stat().st_size,
            'sha256': digest(target), 'decodedPcm24Sha256': hashlib.sha256(data).hexdigest(),
            'decodedPcmEqualsExpected': True}

record_cases = []
combined = bytearray()
timeline = []
gap_frames = round(.15 * 44100)
gap = bytes(gap_frames * 3)

for case_id in case_ids:
    case = by_id[case_id]
    assert case['modelVersion'] == 2 and case['settings']['mix'] == 1
    source = ROOT / case['matched']
    assert digest(source) == case['matchedSha256']
    current, current_pcm = load_pcm24(source)
    assert pcm_bytes(current) == current_pcm
    db = gain_db[case['comparisonGroup']]
    gain = 10.0 ** (db / 20.0)
    # Only a fixed source-group gain and PCM24 rounding. There is no limiter,
    # compression, per-case normalization, dither, fade, resampling or DSP rerender.
    scaled = np.rint(current.astype(np.float64) * gain)
    assert np.isfinite(scaled).all()
    assert np.max(np.abs(scaled)) / 8388608.0 < .98, (case_id, 'headroom would be exceeded')
    assert scaled.min() >= -8388608 and scaled.max() <= 8388607
    louder = scaled.astype(np.int32)
    louder_pcm = pcm_bytes(louder)
    current_metrics, louder_metrics = metrics(current), metrics(louder)
    assert louder_metrics['samplePeak'] < .98
    baseline = flac(case_id + '-current', current_pcm)
    boost = flac(case_id + '-louder', louder_pcm)
    for label, data in [('CURRENT', current_pcm), ('LOUDER', louder_pcm)]:
        start = len(combined) / 3 / 44100
        combined.extend(data); combined.extend(gap)
        timeline.append({'caseId': case_id, 'label': label, 'startSeconds': start,
                         'clipDurationSeconds': len(data) / 3 / 44100,
                         'appliedGainDb': 0.0 if label == 'CURRENT' else db})
    record_cases.append({'caseId': case_id, 'sourceGroup': case['comparisonGroup'],
                         'sourceMatchedWav': str(source), 'sourceMatchedSha256': digest(source),
                         'frames': len(current), 'sampleRate': 44100, 'channels': 1,
                         'currentMatchingGainAlreadyApplied': case['matchedGain'],
                         'previewGainDb': db, 'previewGainLinear': gain,
                         'current': {**baseline, 'metrics': current_metrics},
                         'louder': {**boost, 'metrics': louder_metrics}})

direct = flac('combined-listening-master', bytes(combined), DIRECT)
after = {str(p.relative_to(ROOT)): digest(p) for p in ROOT.rglob('*') if p.is_file()}
assert before == after, 'Original render evidence changed'
record = {'schema': 1, 'purpose': 'Listening-master gain preview only; no DSP or model change',
          'implementationSha256': manifest['implementationSha256'],
          'sourceManifestSha256': before['manifest.json'],
          'modelVersion': 2, 'sourceGroupGainDb': gain_db,
          'gainPolicy': 'One fixed gain per source group, shared by both bodies; no per-clip normalization',
          'processingChanged': False, 'defaultsChanged': False, 'guardApplied': False,
          'compressionOrLimitingApplied': False, 'resampled': False,
          'rounding': 'round-to-nearest-even to signed PCM24 after fixed double-precision gain; no dither',
          'truePeakMethod': 'SciPy resample_poly 4x with Kaiser beta 8.6, constant zero padding; not the repository TruePeak implementation',
          'originalFilesUnchanged': True, 'originalFilesChecked': len(before),
          'samplePeakCeilingTest': .98, 'cases': record_cases, 'timeline': timeline,
          'directAudio': {**direct, 'durationSeconds': len(combined) / 3 / 44100,
                          'gapSeconds': .15, 'trailingGapIncluded': True}}
(OUT / 'record.json').write_text(json.dumps(record, indent=2) + '\n')
lines = ['Model 2 listening-master gain preview',
         'No processor/model change. Each case plays CURRENT then LOUDER.',
         'Noise-burst clips: fixed +1.5 dB. Sustained clips: fixed +12 dB. Same gain for both bodies in a source group.', '']
for item in timeline:
    lines.append(f'{item["startSeconds"]:.3f}s  {item["caseId"]}  {item["label"]} ({item["appliedGainDb"]:+g} dB)')
(OUT / 'tracks.txt').write_text('\n'.join(lines) + '\n')
print(json.dumps({'directAudio': record['directAudio'],
                  'files': [{'id': c['caseId'], 'current': c['current']['file'],
                             'louder': c['louder']['file'], 'gainDb': c['previewGainDb'],
                             'samplePeak': c['louder']['metrics']['samplePeak'],
                             'truePeak4x': c['louder']['metrics']['truePeak4xScipy']} for c in record_cases],
                  'allClipFlacBytes': sum(c['current']['bytes']+c['louder']['bytes'] for c in record_cases),
                  'originalFilesChecked':len(before)},indent=2))
