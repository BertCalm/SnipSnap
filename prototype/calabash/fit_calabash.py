"""Fit CALABASH's hand drums, bells, claves, shaker and claps to CC0 recordings. Measured numbers only; no
recording ships.

Why: the owner has heard synthetic hand drums as "drum machine" and "synthetic" (docs/TERRA_HANDS.md), and the
TRIAD congas as "weak and flat" (docs/TRIAD_HANDS_R2.md). EMBER's cymbals stopped sounding like sheet metal once they
were resynthesized from real cymbals (docs/EMBER.md, round 0b). So every CALABASH voice with a CC0 recording behind
it starts from that recording's own resonances, the way BREAKER's approved cymbals do (scripts/fit_breaker_cymbals.py),
adapted for drums that are short, low and struck by a hand:

- bands from 40 Hz (BREAKER's start at 125 Hz, under a tumba's 138 Hz head and its 80 Hz air);
- a 93 ms analysis window (BREAKER's 186 ms is longer than a bongo's useful ring), zero-padded to 2.7 Hz bins;
- every mode keeps two decay slopes (the first 150 ms, then after), because a hand drum's head dies fast and then
  its shell and air ring on;
- amplitudes are absolute, in units of the recording normalized to unit rms over its first 0.5 s, so the model and
  what it leaves out (the residual) are on one scale;
- the residual is measured on a fine grid over the first 30 ms (a 2 ms window every 1 ms), so the hand's slap and
  the stick's click stay sharp instead of smearing into a 15 ms puff;
- the strongest low mode's pitch glide is measured (a struck head starts sharp and settles: tension modulation);
- velocity: per band, the soft recording's level minus the hard one's, each re its own rms, 30 to 120 ms; plus the
  soft recording's own attack and residual.

Source: Versilian Community Sample Library (VCSL), CC0 1.0, github.com/sgossner/VCSL at
c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e. The files, their VCSL folders and sha256 are in NOTICE.txt. Decode each to
mono 44.1 kHz float WAV first (the claps are 48 kHz):
  ffmpeg -i Conga_HitN_v3_rr1_Sum.wav -ac 1 -ar 44100 -c:a pcm_f32le out/Conga_HitN_v3_rr1_Sum.wav

Usage: python3 -I prototype/calabash/fit_calabash.py <dir of decoded WAVs> <out.json>
"""
from __future__ import annotations

import json
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

FS = 44100
BANDS = [40.0 * 2 ** (k / 3) for k in range(27)]           # 40 Hz .. 16 kHz, third octaves
# Envelope times: fine over the first 30 ms (the contact), coarser after.
TIMES = [0, .001, .002, .003, .005, .0075, .01, .015, .02, .03, .045, .06, .09, .12, .18, .25, .35, .5, .7, 1.0, 1.4]
N, NFFT, HOP = 4096, 16384, 256
EARLY = .15                                                # the head's fast decay, then the shell and air
CAL = (.03, .06, .12)                                      # times the band gains and the soft tilt are matched at

SOURCE = ("Versilian Community Sample Library (VCSL), CC0 1.0, github.com/sgossner/VCSL@"
          "c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e; fitted by prototype/calabash/fit_calabash.py. "
          "The recordings do not ship.")


def load(path):
    r, x = wavfile.read(path)
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    assert r == FS, f"{path}: {r} Hz"
    a = np.abs(x)
    i = max(0, int(np.argmax(a > .05 * a.max())) - int(.0005 * FS))
    x = x[i:]
    return x / (np.sqrt(np.mean(x[:int(.5 * FS)] ** 2)) + 1e-20)


def band_sos(fc):
    lo, hi = fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), FS * .49)
    return signal.butter(4, [lo / (FS / 2), hi / (FS / 2)], "band", output="sos")


SOS = [band_sos(fc) for fc in BANDS]


def band_of(hz):
    for b, fc in enumerate(BANDS):
        if fc / 2 ** (1 / 6) <= hz < fc * 2 ** (1 / 6):
            return b
    return None


def env_db(y, hop_s, win_s):
    hop, win = max(1, int(hop_s * FS)), max(2, int(win_s * FS))
    p = np.convolve(y ** 2, np.ones(win) / win, "same")
    return 10 * np.log10(p[::hop] + 1e-20), hop


def points(x):
    """Each band's energy at TIMES, absolute dB (x is already unit rms over its first 0.5 s): a 2 ms window every 1 ms
    up to 30 ms, a 15 ms window every 5 ms after."""
    out = []
    for sos in SOS:
        y = signal.sosfiltfilt(sos, x)
        fine, fh = env_db(y, .001, .002)
        coarse, ch = env_db(y, .005, .015)
        row = []
        for t in TIMES:
            e, h = (fine, fh) if t < .03 else (coarse, ch)
            row.append(float(e[min(int(t * FS / h), len(e) - 1)]))
        out.append(row)
    return np.array(out)


def fit_modes(x, max_modes, floor_hz=40.0):
    """Peaks of a 93 ms window starting 6 ms after the onset, each tracked for its two decay slopes. The amplitude is
    the window's estimate carried back to the onset along the early slope (at most +12 dB)."""
    if max_modes <= 0:
        return []
    f, t, Z = signal.stft(x, FS, window="hann", nperseg=N, noverlap=N - HOP, nfft=NFFT, boundary=None, padded=False)
    mag = np.abs(Z) * 2              # scipy's stft already divides by the window's sum: a sine of amplitude A reads A/2
    #                                 (BREAKER's fitter divides again; harmless there, where every render is
    #                                  normalized, but this fit is absolute)
    db = 20 * np.log10(mag + 1e-12)
    ref = 1
    spec = db[:, ref]
    pk, _ = signal.find_peaks(spec - signal.medfilt(spec, 201), height=3, distance=4)
    pk = [i for i in pk if floor_hz < f[i] < 16000 and spec[i] > spec.max() - 70]
    pk = sorted(pk, key=lambda i: -spec[i])[:max_modes]
    tc = t                                         # frame centres from the onset
    floor = np.percentile(db, 10, axis=1)
    modes = []
    for i in pk:
        tr = db[max(i - 2, 0):i + 3, :].max(axis=0)
        alive = np.where(tr[ref:] < floor[i] + 6)[0]
        end = ref + (alive[0] if len(alive) else len(tr) - ref)
        split = int(np.searchsorted(tc, EARLY))
        e1 = min(end, split)
        early = np.polyfit(tc[ref:e1], tr[ref:e1], 1)[0] if e1 - ref >= 3 else -60.0
        late = np.polyfit(tc[split:end], tr[split:end], 1)[0] if end - split >= 4 else early
        early, late = min(early, -1.0), min(late, -1.0)
        a, b, c = db[i - 1, ref], db[i, ref], db[i + 1, ref]
        d = .5 * (a - c) / (a - 2 * b + c + 1e-12)
        amp = mag[i, ref] * 10 ** (min(12.0, -early * tc[ref]) / 20)
        modes.append([float((i + d) * FS / NFFT), float(amp), float(early), float(late)])
    return modes


def glide(x, modes):
    """The strongest mode under 1.5 kHz: its frequency over 8 to 40 ms against 150 to 300 ms, from the phase of the
    band-passed signal. Returns (fractional sharpness at the start, the settling time constant used)."""
    low = [m for m in modes if m[0] < 1500]
    if not low:
        return [0.0, .05]
    hz = max(low, key=lambda m: m[1])[0]
    sos = signal.butter(2, [hz * .85 / (FS / 2), hz * 1.15 / (FS / 2)], "band", output="sos")
    y = signal.sosfiltfilt(sos, x[:int(.35 * FS)])
    inst = np.diff(np.unwrap(np.angle(signal.hilbert(y)))) * FS / (2 * np.pi)
    a = np.median(inst[int(.008 * FS):int(.04 * FS)])
    b = np.median(inst[int(.15 * FS):int(.3 * FS)]) if len(inst) > int(.3 * FS) else hz
    g = float(np.clip(a / b - 1, -.02, .08))
    return [round(g, 4), .05]


def render_modes(modes, seconds, tilt=None, w=0.0):
    """The fitting's own reference render: every mode from rest (a sine at phase 0), two slopes, absolute units."""
    n = int(seconds * FS)
    t = np.arange(n) / FS
    out = np.zeros(n)
    for k, (hz, a, early, late) in enumerate(modes):
        g = 1.0
        if tilt is not None:
            b = band_of(hz)
            g = 10 ** (tilt[b] * w / 20) if b is not None else 1.0
        dbt = np.where(t < EARLY, early * t, early * EARLY + late * (t - EARLY))
        out += g * a * 10 ** (dbt / 20) * np.sin(2 * np.pi * hz * t)
    return out


def residual(rec, mod, floor_hz=40.0):
    """What the model leaves out of the recording, per band and time, absolute dB; where the model already carries a
    band, the residual sits 30 dB under the recording. Bands under `floor_hz` are left out (-200 dB): under a bell or
    a clave the microphone heard the room's rumble, not the instrument (EMBER's residual leaves out 200 Hz down)."""
    p = 10 ** (rec / 10) - 10 ** (mod / 10)
    out = 10 * np.log10(np.maximum(p, 10 ** ((rec - 30) / 10)))
    out[np.array(BANDS) * 2 ** (1 / 6) < floor_hz] = -200.0
    return out


def fit_voice(hard_path, soft_path, max_modes, floor_hz=40.0):
    hard, soft = load(hard_path), load(soft_path)
    seconds = min(1.4, len(hard) / FS)
    modes = fit_modes(hard, max_modes, floor_hz)
    want = points(hard)
    ic = [TIMES.index(c) for c in CAL]
    if modes:
        gain = np.ones(len(BANDS))
        for _ in range(4):
            have = points(render_modes([[hz, a * gain[band_of(hz)], e, l] for hz, a, e, l in modes], seconds))
            err = np.clip((want - have)[:, ic].mean(axis=1), -12, 12)
            gain *= 10 ** (err / 20 * .8)
        modes = [[hz, a * gain[band_of(hz)], e, l] for hz, a, e, l in modes]
        # BREAKER's second pass: bend each band's late slopes toward the recording's level at 0.5 s (two damped
        # passes), so the shell and air ring as long as the recording's and no longer.
        i12, i50 = TIMES.index(.12), TIMES.index(.5)
        span = .5 - .12
        if seconds > .55:
            for _ in range(2):
                err = want - points(render_modes(modes, seconds))
                for mode in modes:
                    b = band_of(mode[0])
                    d = (np.clip(err[b][i50], -25, 25) - np.clip(err[b][i12], -25, 25)) / span
                    mode[3] = min(mode[3] + .6 * d, -1.0)
        model = points(render_modes(modes, seconds))
    else:
        model = np.full_like(want, -200.0)
    tilt = points(soft)[:, ic].mean(axis=1) - want[:, ic].mean(axis=1)
    model_soft = points(render_modes(modes, seconds, tilt, 1.0)) if modes else np.full_like(want, -200.0)
    r = lambda a: [[round(float(v), 2) for v in row] for row in a]
    return dict(
        modes=[[round(hz, 3), float(f"{a:.6g}"), round(e, 2), round(l, 2), round(float(tilt[band_of(hz)]), 2)]
               for hz, a, e, l in modes],
        glide=glide(hard, modes),
        residual=r(residual(want, model, floor_hz)), residualSoft=r(residual(points(soft), model_soft, floor_hz)),
        tilt=[round(float(v), 2) for v in tilt],
        seconds=round(seconds, 3))


# name -> (hard file, soft file, modes, floor Hz). Modes 0: a noise-like voice, fitted as band envelopes only. The
# floor: no mode and no residual under it (the drums keep everything from 40 Hz, their air and shell).
VOICES = {
    "quinto_open": ("Quinto_HitN_v3_rr1_Sum", "Quinto_HitN_v1_rr2_Sum", 120, 40),
    "quinto_muff": ("Quinto_HitFM1_v2_rr1_Sum", "Quinto_HitFM1_v1_rr1_Sum", 120, 40),
    "conga_open": ("Conga_HitN_v3_rr1_Sum", "Conga_HitN_v1_rr2_Sum", 120, 40),
    "conga_muff": ("Conga_HitFM_v2_rr1_Sum", "Conga_HitFM_v1_rr2_Sum", 120, 40),
    "tumba_open": ("Tumba_HitN_v4_rr1_Sum", "Tumba_HitN_v1_rr2_Sum", 120, 40),
    "tumba_muff": ("Tumba_HitFM_v4_rr1_Sum", "Tumba_HitFM_v2_rr2_Sum", 120, 40),
    "bongo_hi_open": ("BongoH_Hit1_v3_rr1_Mid", "BongoH_Hit1_v1_rr2_Mid", 120, 40),
    "bongo_hi_muff": ("BongoH_HitMuted1_v3_rr1_Mid", "BongoH_HitMuted1_v1_rr1_Mid", 120, 40),
    "bongo_lo_open": ("BongoL_Hit1_v3_rr1_Mid", "BongoL_Hit1_v1_rr2_Mid", 120, 40),
    "bongo_lo_muff": ("BongoL_HitMuted2_v3_rr1_Mid", "BongoL_HitMuted2_v1_rr1_Mid", 120, 40),
    "bell1_mouth": ("Cowbell1_Hit_v4_rr1_Mid", "Cowbell1_Hit_v2_rr1_Mid", 160, 300),
    "bell1_body": ("Cowbell1_Normal_v3_rr1_Mid", "Cowbell1_Normal_v2_rr1_Mid", 160, 300),
    "bell1_muted": ("Cowbell1_Muted_v3_rr1_Mid", "Cowbell1_Muted_v2_rr1_Mid", 160, 300),
    "bell2_open": ("Cowbell2_Normal_v3_rr1_Mid", "Cowbell2_Normal_v2_rr1_Mid", 160, 300),
    "bell2_muted": ("Cowbell2_Muted_v3_rr1_Mid", "Cowbell2_Muted_v2_rr1_Mid", 160, 300),
    "agogo_hi": ("Agogo_High_v3_rr1_Mid", "Agogo_High_v1_rr1_Mid", 120, 600),
    "agogo_lo": ("Agogo_Low_v2_rr1_Mid", "Agogo_Low_v1_rr1_Mid", 120, 600),
    "clave1": ("Claves1_Hit_v3_rr1_Mid", "Claves1_Hit_v1_rr1_Mid", 60, 800),
    "clave2": ("Claves2_Hit_v3_rr1_Mid", "Claves2_Hit_v1_rr1_Mid", 60, 800),
    "shaker_down": ("LShaker_Shake1D_rr3_Mid", "LShaker_Shake1D_rr2_Mid", 0, 300),
    "shaker_up": ("LShaker_Shake1U_rr4_Mid", "LShaker_Shake1U_rr1_Mid", 0, 300),
    "shaker_hit": ("LShaker_Hit_rr1_Mid", "LShaker_Hit_rr2_Mid", 0, 300),
    "cabasa_rub": ("Cabasa1_Rub_v2_rr1_Mid", "Cabasa1_Rub_v1_rr2_Mid", 0, 300),
    "cabasa_hit": ("Cabasa1_Hit_rr1_Mid", "Cabasa1_Hit_rr2_Mid", 0, 300),
    "clap_solo": ("SoloClap_vl4", "SoloClap_vl1", 0, 150),
    "clap_group": ("Clap_rr1", "Clap_rr5", 0, 150),
}

if __name__ == "__main__":
    d, outp = sys.argv[1], sys.argv[2]
    data = dict(source=SOURCE, bands=[round(b, 3) for b in BANDS], times=TIMES, early=EARLY, voices={})
    for name, (h, s, mm, fl) in VOICES.items():
        v = fit_voice(f"{d}/{h}.wav", f"{d}/{s}.wav", mm, fl)
        v["floor"] = fl
        v["files"] = [h, s]
        data["voices"][name] = v
        top = max(v["modes"], key=lambda m: m[1])[0] if v["modes"] else 0
        print(f"{name:14s} {len(v['modes']):3d} modes  strongest {top:7.1f} Hz  glide {v['glide'][0]:+.4f}", flush=True)
    json.dump(data, open(outp, "w"), separators=(",", ":"))
