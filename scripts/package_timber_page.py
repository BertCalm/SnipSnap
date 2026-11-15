#!/usr/bin/env python3
"""Package saved-playback Kotlin audition tracks for a Page (16-bit listening copies).

The kit/export WAVs remain PCM24. This only concatenates the generator's playback
renders and adds silence; it performs no leveling or synthesis.
"""
import argparse
import json
import zipfile
from pathlib import Path
import numpy as np
from scipy.io.wavfile import read, write


def pcm(path):
    rate, x = read(path)
    assert rate == 44100 and x.ndim == 1
    return x.astype(float) / (2 ** (x.dtype.itemsize * 8 - 1))


def package(root):
    target = root / 'page'
    target.mkdir(exist_ok=True)
    manifest = []
    for folder in sorted(root.glob('timber-*')):
        if not folder.is_dir():
            continue
        kit = json.loads((folder / 'native/kit.json').read_text())
        groups = {'groove': ['groove.wav'],
                  'pad-walk': [f'pad-{i}.wav' for i in range(1, 15)],
                  'roll-riser': ['pad-15.wav', 'pad-16.wav'],
                  'touch': [f'touch-{i}.wav' for i in [1, 3, 6, 12]]}
        for label, files in groups.items():
            parts, starts, frames = [], [], 0
            for file in files:
                starts.append(dict(source=file, seconds=frames/44100))
                x = pcm(folder / file)
                parts.append(x)
                frames += len(x)
                if len(files) > 1:
                    gap = np.zeros(11025)
                    parts.append(gap)
                    frames += len(gap)
            y = np.concatenate(parts)
            assert np.isfinite(y).all() and np.max(np.abs(y)) < 1
            name = f'{folder.name}-{label}.wav'
            write(target / name, 44100, np.round(y*32767).astype(np.int16))
            assert (target / name).stat().st_size < 10*1024*1024
            manifest.append(dict(file=name, kit=kit['name'], track=label, starts=starts,
                peak=float(np.max(np.abs(y))), seconds=len(y)/44100))
    for ins in ['guitar', 'acoustic_bass', 'cello', 'upright']:
        parts, starts, frames = [], [], 0
        for gesture in ['palm', 'knuckle', 'slap', 'dead_string', 'harmonic', 'rake', 'scratch']:
            starts.append(dict(gesture=gesture, seconds=frames/44100))
            x = pcm(root/'contacts'/f'{ins}-{gesture}.wav')
            gap = np.zeros(11025)
            parts.extend([x, gap])
            frames += len(x)+len(gap)
        y = np.concatenate(parts)
        name = f'{ins}-all-techniques.wav'
        write(target/name, 44100, np.round(y*32767).astype(np.int16))
        manifest.append(dict(file=name, instrument=ins, track='dry techniques', starts=starts,
            peak=float(np.max(np.abs(y))), seconds=len(y)/44100))
    assert len(manifest) == 16
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    with zipfile.ZipFile(root / 'TIMBER-model2-kits.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for folder in sorted(root.glob('timber-*')):
            if folder.is_dir():
                for sub in ['native', 'mpc3', 'mpc2']:
                    for path in (folder/sub).rglob('*'):
                        if path.is_file():
                            z.write(path, path.relative_to(root))
        for name in ['README.txt', 'verification.csv', 'mpc2-readback.json']:
            z.write(root/name, name)
    print(json.dumps({'page_tracks': len(manifest), 'kit_archive_bytes': (root/'TIMBER-model2-kits.zip').stat().st_size}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    package(parser.parse_args().root)
