"""Numerical checks on a BOROUGH audition folder, plus the fit report. Python harness, not Kotlin.

- every WAV finite, peak <= 0.9, |DC| < 0.005, last 10 ms quiet
- velocity is timbral, not only gain: gain-matched soft-vs-hard residual and centroid shift
- takes are distinct strikes: gain-matched residual between takes in a zone
- struck sounds are loudest at the strike: no pad (rolls aside) rises more than 1 dB after its
  first 40 ms (the ride "dead spot", round 1.2, rose 2 dB; every EMBER cymbal through the chain at
  tilt >= 0 rises under 0.3 dB)
- fitted pads against their reference descriptors (numbers only), re-scored from the final render

    python3 verify.py OUT_DIR targets.json fitted.json
"""
from __future__ import annotations
import json, sys
from pathlib import Path
import numpy as np
from scipy.io import wavfile
import extract as X
import fit as F
import voices as V


def load(p):
    sr, x = wavfile.read(p)
    return x.astype(float) / 32768


def resid_db(a, b):
    n = min(len(a), len(b)); a, b = a[:n], b[:n]
    g = np.dot(a, b) / max(np.dot(b, b), 1e-20)
    return float(20 * np.log10(max(np.linalg.norm(a - g * b), 1e-20) / max(np.linalg.norm(a), 1e-20)))


def centroid(x):
    x = x[:int(.08 * V.SR)]
    X_ = np.abs(np.fft.rfft(x * np.hanning(len(x)))); f = np.fft.rfftfreq(len(x), 1 / V.SR)
    return float(np.sum(f * X_) / max(np.sum(X_), 1e-12))


def rise_db(x):
    """How much louder a hit gets after its first 40 ms (10 ms RMS envelope)."""
    w = int(.01 * V.SR); e = np.sqrt(np.convolve(x * x, np.ones(w) / w, 'same')) + 1e-12
    return float(20 * np.log10(e.max() / e[:int(.04 * V.SR)].max()))


def main(out, targets_path, fitted_path):
    out = Path(out); rep = {'files': 0, 'fail': []}
    worst = dict(peak=0, dc=0, tail=0)
    pads = {}
    for p in sorted(out.glob('*/*/*.wav')):
        x = load(p); rep['files'] += 1
        pk, dc, tail = np.max(np.abs(x)), abs(np.mean(x)), np.sqrt(np.mean(x[-441:] ** 2))
        worst = dict(peak=max(worst['peak'], pk), dc=max(worst['dc'], dc), tail=max(worst['tail'], tail))
        if not (np.all(np.isfinite(x)) and pk <= .9 and dc < .005 and tail < .003):
            rep['fail'].append(p.name)
        stem = p.stem.rsplit('-v', 1)[0]
        if 'roll' not in stem and rise_db(x) > 1.0:
            rep.setdefault('rises_after_strike', []).append(f'{p.parent.name}/{p.name} {rise_db(x):.1f} dB')
        z, t = p.stem.rsplit('-v', 1)[1].split('-t')
        pads.setdefault((p.parent.name, stem), {})[(int(z), int(t))] = x
    rep['worst'] = {k: round(float(v), 5) for k, v in worst.items()}
    vel, take = [], []
    for (folder, stem), g in pads.items():
        soft, hard = g[(1, 1)], g[(3, 1)]
        vel.append(dict(pad=f'{folder}/{stem}', soft_hard_resid_db=round(resid_db(hard, soft), 1),
                        centroid_soft=round(centroid(soft)), centroid_hard=round(centroid(hard))))
        take.append(round(max(resid_db(g[(3, 1)], g[(3, 2)]), resid_db(g[(3, 1)], g[(3, 3)])), 1))
    rep['velocity'] = vel
    rep['velocity_timbral'] = sum(v['soft_hard_resid_db'] > -20 for v in vel)
    rep['take_resid_db_max'] = max(take); rep['take_resid_db_min'] = min(take)
    # fit report: re-score the shipped hard take-1 pads against the reference descriptors
    tg = json.load(open(targets_path))['loops']; fitted = json.load(open(fitted_path))
    man = json.load(open(out / 'manifest.json'))
    fits = []
    for kit in man['kits']:
        for pad, role, loop in ((1, 'kick', kit['primary']), (2, 'snare', kit['primary']), (3, 'hat', kit['primary']),
                                (5, 'kick', kit['secondary']), (6, 'snare', kit['secondary'])):
            tgt = tg[loop].get(role)
            if role == 'hat' and kit.get('ride'):   # a ride kit's A03 is the ride, fitted on the cymbal descriptor
                role = 'ride'
            if not tgt or role not in fitted.get(loop, {}):
                continue
            label = kit['pads'][pad - 1]['label'].lower().replace(' ', '-')
            x = load(out / 'kits' / kit['id'] / f'A{pad:02d}-{label}-v3-t1.wav')
            t, b, tm, _ = F.masks(tgt, role)
            # flat reference: what a do-nothing guess scores (a pink-ish click), for scale
            fits.append(dict(kit=kit['id'], pad=pad, role=role, loop=loop,
                             resid_db=round(F.score(X.to_db(X.bank_env(x), -80), t, b, tm), 2),
                             fit_resid_db=fitted[loop][role]['residual_db']))
    rep['fit'] = fits
    (out / 'verification.json').write_text(json.dumps(rep, indent=1))
    rep['fail'] += rep.get('rises_after_strike', [])
    print(json.dumps({k: rep[k] for k in ('files', 'worst', 'velocity_timbral', 'take_resid_db_max', 'take_resid_db_min')}),
          'fail', len(rep['fail']), '(of which rise after strike:', len(rep.get('rises_after_strike', [])), ')')
    for f in fits:
        print(f"  {f['kit']:16s} A{f['pad']:02d} {f['role']:5s} {f['loop']:24s} shipped {f['resid_db']:5.2f} dB (fit {f['fit_resid_db']})")


if __name__ == '__main__':
    main(*sys.argv[1:4])
