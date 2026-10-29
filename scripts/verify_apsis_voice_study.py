"""Read back the published PCM and measure controls independently of generation."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import apsis_voice_study as a


def distance(x, y):
    return float(np.linalg.norm(x / np.linalg.norm(x) - y / np.linalg.norm(y)))


def main(root):
    root = Path(root)
    manifest = json.loads((root / 'manifest.json').read_text())
    readbacks, activity, reference_errors = [], {}, {}
    for clip in manifest['clips']:
        for treatment in ('raw', 'matched'):
            path = root / 'audio' / f"{clip['id']}_{treatment}.wav"
            x, rate = a.sk.read_wav(str(path))
            assert rate == a.RATE and x.ndim == 1
            assert abs(len(x) / rate - clip['durationSeconds']) < 1 / rate
            assert np.isfinite(x).all() and np.max(np.abs(x)) <= .8501
            assert abs(float(np.mean(x))) < .005
            assert hashlib.sha256(path.read_bytes()).hexdigest() == clip[treatment + 'Sha256']
            readbacks.append(dict(file=path.name, frames=len(x), peak=float(np.max(np.abs(x))), dc=float(np.mean(x))))
    for voice in a.VOICES:
        activity[voice] = {}
        for macro in ('STRETCH', 'DISTANCE', 'BODIES', 'ALIGN', 'COUPLE'):
            lo, _ = a.render(voice, {macro: .15})
            mid, _ = a.render(voice, {macro: .5})
            value = distance(lo, mid)
            activity[voice][macro] = value
            assert value > .002, (voice, macro, value)
        shorter, _ = a.render(voice, {'HOLD': .15})
        longer, _ = a.render(voice, {'HOLD': .5})
        assert len(shorter) < len(longer)
        print(voice, 'controls passed', flush=True)
    # These deterministic tonal profiles allow a same-drive higher-rate comparison.
    # CORONA/GRAIN have rate-dependent noise samples, so this would compare different
    # noise realizations there. Errors also include contact sampling and resampling;
    # they are not pure alias-power measurements or a production aliasing certificate.
    for voice in ('PASSAGE', 'TETHER', 'VESSEL', 'ANCHOR'):
        low, _ = a.render(voice, midi=84, velocity=1., macros={'STRETCH': .85, 'COUPLE': .85})
        high, _ = a.render(voice, midi=84, velocity=1., macros={'STRETCH': .85, 'COUPLE': .85}, oversample=8)
        relative = float(np.linalg.norm(low - high) / np.linalg.norm(high))
        reference_errors[voice] = relative
        assert np.isfinite(relative)
        print(voice, '4x/8x waveform relative error', relative, flush=True)
    report = dict(pcmReadbackCount=len(readbacks), pcm=readbacks, lowToMiddleLevelIndependentWaveformDistance=activity,
                  c6BrightCorner4x8xRelativeWaveformError=reference_errors,
                  highRateLimit='Includes event sampling and output resampling; noise profiles excluded. Not a production aliasing certificate.')
    (root / 'reports/readback-and-controls.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output')
    main(p.parse_args().output)
