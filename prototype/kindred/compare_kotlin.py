"""A/B the Kotlin KINDRED pads against the accepted Python round-2 renders.

Run after `./gradlew :synth:generateKindredAudition`:
    KINDRED_MODEL=2 python3 compare_kotlin.py ../../testkit/kindred-audition/kotlin OUT.json
For each kit recipe at full velocity, take 0: align by cross-correlation, match RMS, then report the
third-octave spectral-shape distance (dB), the waveform correlation and the residual (dB under the signal).
Noise pads cannot match sample for sample (Dsp.Noise in place of numpy), so their shape distance is the measure.
"""
import json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("KINDRED_MODEL", "2")
import kindred_kits as kk
from scipy import signal

kot_dir, out = sys.argv[1], sys.argv[2]
rows = []
for kit, K in kk.KITS.items():
    for i, p in enumerate(K["pads"]):
        rid = f"{kit}_A{i + 1:02d}"
        py = kk.render_pad(kit, p)
        kt, _ = kk.sk.read_wav(f"{kot_dir}/pads/{rid.lower()}.wav")
        n = min(len(py), len(kt))
        c = signal.correlate(kt[:n], py[:n], mode="full", method="fft")
        lag = int(np.argmax(np.abs(c))) - (n - 1)
        lag = max(-200, min(200, lag))
        a = py[max(0, -lag):][: n - abs(lag)]; b = kt[max(0, lag):][: n - abs(lag)]
        b = b * (kk.sk.rms(a) / max(kk.sk.rms(b), 1e-12))
        corr = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-20))
        resid = float(10 * np.log10(np.sum((a - b) ** 2) / max(np.sum(a * a), 1e-20) + 1e-20))
        noisy = kk.params_for(p)["lnoise"] > 0.01 or kk.params_for(p)["nmod"] > 0.01
        rows.append(dict(id=rid, name=p["name"], noise=bool(noisy), lag=lag, shape_db=round(kk.kp.shape_change_db(a, b), 2),
                         corr=round(corr, 3), residual_db=round(resid, 1),
                         seconds_py=round(len(py) / 44100, 3), seconds_kt=round(len(kt) / 44100, 3)))
json.dump(rows, open(out, "w"), indent=1)
for r in rows:
    print(f"{r['id']:14s} {r['name']:11s} {'noise' if r['noise'] else 'tone ':5s} shape {r['shape_db']:5.2f} dB  corr {r['corr']:6.3f}  resid {r['residual_db']:6.1f} dB  lag {r['lag']:4d}  len {r['seconds_py']}/{r['seconds_kt']}")
tone = [r for r in rows if not r["noise"]]; noise = [r for r in rows if r["noise"]]
print("tone pads:", len(tone), "worst shape", max(r["shape_db"] for r in tone), "min corr", min(r["corr"] for r in tone))
print("noise pads:", len(noise), "worst shape", max(r["shape_db"] for r in noise))
