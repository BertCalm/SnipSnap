"""BOROUGH round 3: the owner's listen to the kits the app generates, against the Python kits they accepted.

Reads the app's own export (`./gradlew :shell:generateBoroughKits`, testkit/borough-kits): each pad's
touch-NN.wav holds the nine clips the pad resolver plays from the MPC 3 export (soft, medium and hard, three
takes each, in order). It also reads the accepted round-2 Python render (build.py's out/kits and out/stacks).
Both banks play the same seeded groove (build.groove: each kit's own loop feel, with its default stack on
A13-A16). Both then go through the same glue bus at the recipe's limiter drive: build.bus_preview, the glue heard
on the round-2 page, which the app is to switch on when a BOROUGH kit loads (not built yet). So an A/B differs only in the pads.

    python3 listen_app.py <app export dir> <python out dir> <out dir>

Writes <out>/<kit>-app.wav and <out>/<kit>-python.wav (level-matched: the louder is turned down to the quieter's
RMS), <out>/<kit>-app-pads.wav and <out>/<kit>-python-pads.wav (each pad's hard first take, A01-A16,
unprocessed) and <out>/listen.json (each groove's raw RMS and peak, and the match applied). No reference audio is read.
"""
from __future__ import annotations
import json, sys
from pathlib import Path
import numpy as np
from scipy.io import wavfile

import build as B   # imports BOROUGH's kits before EMBER's

RECIPES = Path(__file__).resolve().parents[2] / 'synth/src/main/resources/borough/v1/recipes.json'


def _read(path):
    sr, x = wavfile.read(path)
    assert sr == B.SR, (path, sr)
    # Scale by the file's own sample type: the app writes 32-bit PCM, build.py 16-bit. (Reading the app's
    # 32-bit files as 16-bit put every pad 96 dB over full scale; the pad run then clipped. Owner, round 3:
    # "Stoop hard is extremely overblown on the individual pad walkthrough".)
    if x.dtype.kind in 'iu':
        x = x.astype(np.float64) / float(2 ** (8 * x.dtype.itemsize - 1))
    x = x.astype(np.float64)
    assert np.max(np.abs(x)) <= 1.0, path
    return x if x.ndim == 1 else x.mean(axis=1)


def _trim(x):
    nz = np.nonzero(np.abs(x) > 1e-7)[0]
    return x[:nz[-1] + 1] if len(nz) else x[:1]


def app_pad(folder: Path, slot: int):
    """[zone][take] from the app's touch strip: nine clips at an equal stride."""
    strip = _read(folder / f'touch-{slot:02d}.wav')
    n = len(strip) // 9
    assert n * 9 == len(strip), (folder, slot)
    clips = [_trim(strip[i * n:(i + 1) * n]) for i in range(9)]
    return [[clips[z * 3 + t] for t in range(3)] for z in range(3)]


def python_pad(folder: Path, slot: int):
    return [[_read(next(folder.glob(f'A{slot:02d}-*-v{z + 1}-t{t + 1}.wav'))) for t in range(3)] for z in range(3)]


def _db(x):
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)


def main(app_dir, py_dir, out):
    app_dir, py_dir, out = Path(app_dir), Path(py_dir), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    recipes = {k['id']: k for k in json.load(open(RECIPES))['kits']}
    stacks = {s['id']: s for s in B.STACKS}
    report = []
    for kit in B.K.KITS:
        r = recipes[kit['id']]
        st = stacks[r['default_stack']]
        a_bank = [app_pad(app_dir / kit['id'], s) for s in range(1, 13)]
        a_stack = [app_pad(app_dir / kit['id'], s) for s in range(13, 17)]
        assert all(0.05 < np.max(np.abs(g[2][0])) <= 0.9 for g in a_bank + a_stack), kit["id"]   # never past the 0.89 level law
        p_bank = [python_pad(py_dir / 'kits' / kit['id'], s) for s in range(1, 13)]
        p_stack = [python_pad(py_dir / 'stacks' / st['id'], s) for s in range(13, 17)]
        drive = r['glue_limiter_drive_db']
        a = B.bus_preview(B.groove(kit, a_bank, st, a_stack), drive)
        p = B.bus_preview(B.groove(kit, p_bank, st, p_stack), drive)
        n = min(len(a), len(p))
        raw_app_db, raw_py_db = _db(a[:n]), _db(p[:n])
        # Level-matched to the quieter of the two: a louder side wins an A/B by level alone.
        g = 10 ** (-abs(raw_app_db - raw_py_db) / 20)
        a, p = (a * g, p) if raw_app_db > raw_py_db else (a, p * g)
        B.write(out / f"{kit['id']}-app.wav", a)
        B.write(out / f"{kit['id']}-python.wav", p)
        for tag, pads in (('app', a_bank + a_stack), ('python', p_bank + p_stack)):
            tour = []
            for g in pads:
                tour += [g[2][0][:int(1.4 * B.SR)], np.zeros(int(.12 * B.SR))]
            B.write(out / f"{kit['id']}-{tag}-pads.wav", np.concatenate(tour))
        report.append(dict(kit=kit['id'], name=kit['name'], stack=st['id'], limiter_drive_db=drive,
                           app_rms_db=round(float(raw_app_db), 2), python_rms_db=round(float(raw_py_db), 2),
                           level_matched_by_db=round(float(abs(raw_app_db - raw_py_db)), 2),
                           app_peak=round(float(np.max(np.abs(a))), 3), python_peak=round(float(np.max(np.abs(p))), 3)))
        print(report[-1], flush=True)
    (out / 'listen.json').write_text(json.dumps(report, indent=1))


if __name__ == '__main__':
    main(*sys.argv[1:4])
