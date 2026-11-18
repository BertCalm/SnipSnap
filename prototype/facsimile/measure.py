"""FACSIMILE measurements: how close the replicas are, and whether velocity and round robin are
real. Python harness numbers, not Kotlin tests.

Per loop (replica groove against the reference loop, both mono):
  band distance   mean |dB| between 26-band envelopes on 10 ms frames, over cells within 50 dB
                  of the loop's loudest; the number to compare is the calibration below
  balance         per-band difference of the whole-loop spectrum (tonal balance), mean |dB|
  calibration     the same band distance for the reference against itself shifted one 16th:
                  what a clearly wrong groove scores on this scale

Per drum (rendered at 4x, the house rate):
  fit distance    re-measured at 4x against the same target, averaged over the four takes
  velocity        spectral-shape change of a v=.4 strike against v=1 at matched loudness, dB
                  (owner gate: a soft hit must differ in character, not only level)
  takes           the closest pair of four takes, 20 log10(rms(a) / rms(a - b)); a copy
                  measures above 40 dB (the house's threshold for "copies")

    python3 -I measure.py REF_DIR WORK_DIR MODELS_DIR GROOVE_DIR
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: import our own modules
import fit  # noqa: E402
import synth  # noqa: E402

RATE = synth.RATE
EDGES = synth.BAND_EDGES


def band_frames(x: np.ndarray, frame_s: float = 0.01) -> np.ndarray:
    hop = int(frame_s * RATE)
    out = []
    for lo, hi in zip(EDGES[:-1], EDGES[1:]):
        y = signal.sosfiltfilt(signal.butter(3, [lo, hi], btype="bandpass", fs=RATE,
                                             output="sos"), x)
        p = np.add.reduceat(y * y, np.arange(0, len(y), hop)) / hop
        out.append(10 * np.log10(p + 1e-14))
    return np.array(out)


def band_distance(a: np.ndarray, b: np.ndarray) -> float:
    A, B = band_frames(a), band_frames(b)
    n = min(A.shape[1], B.shape[1])
    A, B = A[:, :n], B[:, :n]
    live = A > A.max() - 50
    return float(np.mean(np.abs(A - B)[live]))


def third_octave(x: np.ndarray) -> np.ndarray:
    sp = np.abs(np.fft.rfft(x, 1 << int(np.ceil(np.log2(len(x)))))) ** 2
    f = np.fft.rfftfreq(1 << int(np.ceil(np.log2(len(x)))), 1 / RATE)
    return np.array([10 * np.log10(sp[(f >= lo) & (f < hi)].sum() + 1e-14)
                     for lo, hi in zip(EDGES[:-1], EDGES[1:])])


def shape_change(a: np.ndarray, b: np.ndarray) -> float:
    """Mean |dB| between third-octave spectra after matching overall level: a change in
    character, with loudness taken out."""
    sa, sb = third_octave(a), third_octave(b)
    live = np.maximum(sa, sb) > max(sa.max(), sb.max()) - 50
    d = (sa - sb)[live]
    return float(np.mean(np.abs(d - np.mean(d))))


def take_distance(a: np.ndarray, b: np.ndarray) -> float:
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    return float(20 * np.log10(np.sqrt(np.mean(a * a)) / (np.sqrt(np.mean((a - b) ** 2)) + 1e-15)))


def main(ref_dir: Path, work: Path, models: Path, grooves: Path) -> None:
    report = {"loops": {}, "drums": {}}
    inv = json.loads((work / "inventory.json").read_text())
    for p in sorted(ref_dir.glob("*.wav")):
        key = str(int(re.search(r"TRNDY_(\d+)_", p.stem).group(1)))
        ref = sf.read(p, always_2d=True)[0].mean(1)
        rep = sf.read(grooves / f"loop_{key}.wav")[0]
        bpm = inv[key]["bpm"]
        shift = int(round(60 / bpm / 4 * RATE))
        d = band_distance(ref, rep)
        cal = band_distance(ref, np.roll(ref, shift))
        bal = third_octave(rep) - third_octave(ref)
        report["loops"][key] = dict(band_distance_db=round(d, 2),
                                    shifted_16th_reference_db=round(cal, 2),
                                    balance_db=round(float(np.mean(np.abs(bal))), 2),
                                    balance_by_band=[round(float(v), 1) for v in bal],
                                    level_db=round(float(10 * np.log10(np.mean(rep ** 2) /
                                                                       np.mean(ref ** 2))), 2))
        print(f"loop {key:4s} band {d:5.2f} dB (a 16th off: {cal:5.2f})  balance "
              f"{report['loops'][key]['balance_db']:4.2f} dB  level "
              f"{report['loops'][key]['level_db']:+.2f} dB")

    for f in sorted(models.glob("[0-9]*.json")):
        loop = json.loads(f.read_text())
        drums = {d["id"]: d for d in inv[f.stem]["drums"]}
        for did, m in loop["drums"].items():
            takes = np.load(work / "hits" / f"{did}_takes.npy").astype(float)
            tpl = np.load(work / "hits" / f"{did}_template.npy")
            low = float((tpl[fit.TEMPLATE_EDGES < 150] ** 2).sum() / (tpl ** 2).sum())
            target = fit.target_for(takes, tpl, float(drums[did]["clean_seconds"]), low)
            ys = [synth.render(m, 1.0, t) for t in range(4)]
            hard = ys[0]
            live = target > target.max() - 50
            live &= (fit.GRID < m["length_s"] - 0.01)[None, :]
            # the distance a listener meets across repeated hits: the mean over the four takes
            dist = float(np.mean([np.mean(np.abs(target - fit.band_envelopes(y))[live])
                                  for y in ys]))
            soft = synth.render(m, 0.4, 0)
            closest = max(take_distance(ys[i], ys[j]) for i in range(4) for j in range(i + 1, 4))
            report["drums"][did] = dict(fit_distance_4x_db=round(dist, 2),
                                        velocity_shape_db=round(shape_change(hard, soft), 2),
                                        closest_takes_db=round(closest, 1))
    vel = [v["velocity_shape_db"] for v in report["drums"].values()]
    tk = [v["closest_takes_db"] for v in report["drums"].values()]
    fd = [v["fit_distance_4x_db"] for v in report["drums"].values()]
    print(f"drums {len(fd)}: fit at 4x median {np.median(fd):.2f} dB max {max(fd):.2f}; "
          f"velocity shape change min {min(vel):.2f} dB median {np.median(vel):.2f}; "
          f"closest takes max {max(tk):.1f} dB (a copy is > 40)")
    (models / "measure-report.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
