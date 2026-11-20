"""FACSIMILE measurements: how close the replicas are, and whether velocity and round robin are
real. Python harness numbers, not Kotlin tests.

Per loop (replica groove against the reference loop, both mono; the glued groove where the
loop has a glue setting, and the raw sum beside it):
  band distance   mean |dB| between 26-band envelopes on 10 ms frames, over cells within 50 dB
                  of the loop's loudest; the number to compare is the calibration below
  balance         per-band difference of the whole-loop spectrum (tonal balance), mean |dB|
  calibration     the same band distance for the reference against itself shifted one 16th:
                  what a clearly wrong groove scores on this scale

Per drum (rendered at 4x, the house rate):
  fit distance    re-measured at 4x against the same target, averaged over the four takes
  own hits        the reference's own single hits against that target: the floor a single
                  take can reach, since every single strike wobbles around the average
  velocity        spectral-shape change of a v=.4 strike against v=1 at matched loudness, dB
                  (owner gate: a soft hit must differ in character, not only level)
  ring            the strongest peak above 1 kHz over its neighbourhood, replica against the
                  reference: whether a drum whistles where the reference does not
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


def ring(x: np.ndarray) -> float:
    """The strongest peak above 1 kHz, in dB over its +-1/6 octave neighbourhood, from 5 to
    150 ms: how much a drum whistles. A replica far above its reference rings where the drum
    does not (heard as ring modulation on the long shaker, 2026-11-18)."""
    a, b = int(0.005 * RATE), int(0.15 * RATE)
    x = x[a:b]
    if len(x) < 1024:
        x = np.pad(x, (0, 1024 - len(x)))
    f = np.fft.rfftfreq(1 << 15, 1 / RATE)
    sp = 20 * np.log10(np.abs(np.fft.rfft(x * np.hanning(len(x)), 1 << 15)) + 1e-12)
    lf, _, prom, _ = fit._prominence(sp, f)
    return float(prom[lf > 1000].max())


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
        raw = sf.read(grooves / f"loop_{key}.wav")[0]
        glued_path = grooves / f"loop_{key}_glued.wav"
        # the groove as it ships is the glued one where the loop has a glue setting
        rep = sf.read(glued_path)[0] if glued_path.exists() else raw
        bpm = inv[key]["bpm"]
        shift = int(round(60 / bpm / 4 * RATE))
        d = band_distance(ref, rep)
        cal = band_distance(ref, np.roll(ref, shift))
        bal = third_octave(rep) - third_octave(ref)

        def crest(x):
            x = x - np.mean(x)
            return round(float(20 * np.log10(np.abs(x).max() / np.sqrt(np.mean(x * x)))), 1)
        report["loops"][key] = dict(band_distance_db=round(d, 2),
                                    glued=glued_path.exists(),
                                    raw_band_distance_db=round(band_distance(ref, raw), 2),
                                    crest_db=crest(rep), raw_crest_db=crest(raw),
                                    reference_crest_db=crest(ref),
                                    shifted_16th_reference_db=round(cal, 2),
                                    balance_db=round(float(np.mean(np.abs(bal))), 2),
                                    balance_by_band=[round(float(v), 1) for v in bal],
                                    level_db=round(float(10 * np.log10(np.mean(rep ** 2) /
                                                                       np.mean(ref ** 2))), 2))
        L = report["loops"][key]
        print(f"loop {key:4s} band {d:5.2f} dB (raw {L['raw_band_distance_db']:5.2f}, a 16th off: "
              f"{cal:5.2f})  balance {L['balance_db']:4.2f} dB  level {L['level_db']:+.2f} dB  "
              f"crest {L['crest_db']} (raw {L['raw_crest_db']}, ref {L['reference_crest_db']})")

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
            # the floor: how far the reference's own single hits sit from the same target
            own = float(np.mean([np.mean(np.abs(target - fit.band_envelopes(h[fit.PRE:]))[live])
                                 for h in takes]))
            report["drums"][did] = dict(fit_distance_4x_db=round(dist, 2),
                                        reference_own_hits_db=round(own, 2),
                                        ring_db=round(float(np.median([ring(y) for y in ys])), 1),
                                        reference_ring_db=round(float(np.median(
                                            [ring(h[fit.PRE:]) for h in takes[:6]])), 1),
                                        velocity_shape_db=round(shape_change(hard, soft), 2),
                                        closest_takes_db=round(closest, 1))
    vel = [v["velocity_shape_db"] for v in report["drums"].values()]
    tk = [v["closest_takes_db"] for v in report["drums"].values()]
    fd = [v["fit_distance_4x_db"] for v in report["drums"].values()]
    own = [v["reference_own_hits_db"] for v in report["drums"].values()]
    over = sorted(((v["ring_db"] - v["reference_ring_db"], d) for d, v in report["drums"].items()),
                  reverse=True)
    print(f"ring over 1 kHz, replica minus reference: worst {over[0][1]} {over[0][0]:+.1f} dB, "
          f"{sum(o > 6 for o, _ in over)} drums more than 6 dB over")
    print(f"reference's own single hits from their target: median {np.median(own):.2f} dB "
          f"(the floor a single replica take is compared against)")
    print(f"drums {len(fd)}: fit at 4x median {np.median(fd):.2f} dB max {max(fd):.2f}; "
          f"velocity shape change min {min(vel):.2f} dB median {np.median(vel):.2f}; "
          f"closest takes max {max(tk):.1f} dB (a copy is > 40)")
    (models / "measure-report.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
