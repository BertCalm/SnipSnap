"""Fit BREAKER's crash and ride to CC0 recordings: a dense modal model, measured only.

The recordings never ship. This writes the fitted numbers BREAKER's cymbal synth reads
(synth/src/main/resources/breaker/cymbals.json); the recordings stay wherever you decoded them.

Sources: Virtuosity Drums (CC0 1.0), github.com/sfzinstruments/virtuosity_drums at
9f04cf9a734527edfbb0a4eee1f674e45bbf71bc, overhead mic:
  Samples/oh/ride/oh_ride_ride_vl3_rr1.flac    sha256 855f22d2408439f7944c056dab306003b8fc172310c92b37b63a6aef21aa4f5c (hard)
  Samples/oh/ride/oh_ride_ride_vl1_rr1.flac    sha256 df47cd9bee89a98afa6626e38a7363c36ac6091e9964f113693807d0675fd0ab (soft)
  Samples/oh/crash/oh_crash_crash_vl3_rr1.flac sha256 28ac4dc5c130175555ef54009f9186e418a664c544c1f996058b5acd3b85ccb9 (hard)
  Samples/oh/crash/oh_crash_crash_vl1_rr1.flac sha256 3307ad060004db51990b81b80badc0d26c90dae87e7b0f9b9ebcf099224fd823 (soft)
Decode each to mono 44.1 kHz float WAV first, e.g.
  ffmpeg -i oh_ride_ride_vl3_rr1.flac -ac 1 -ar 44100 -c:a pcm_f32le oh_ride_ride_vl3_rr1.wav

Usage: python3 -I scripts/fit_breaker_cymbals.py <dir of decoded WAVs> <out.json>

The model (docs/BREAKER.md, "The cymbals"):
- modes: the hard recording's resonances from a long-window STFT (8192 points, 5.4 Hz bins), up to 400 (ride) or
  600 (crash), each a frequency, an amplitude and two decay slopes (the first 300 ms, then after);
- per third-octave band (125 Hz to 16 kHz): one level correction, matched to the recording over 80 to 320 ms, then the
  late slopes bent toward the recording's level at 2.2 s (two damped passes);
- the stick: band levels over the first 40 ms, hard and soft;
- velocity: per band, the soft recording's level minus the hard one's (each re its own rms, 80 to 320 ms). Every
  band of both soft recordings sits at least 7 dB over its own floor (the last 2 s), where noise moves a level by
  under 1 dB, so the tilts are the strikes', not hiss: a softer crash is middier, a softer ride rings lower;
- tight (the owner's pick, 2026-11-11): each mode keeps only the steeper of its two slopes, which leaves out the
  slow tail the overhead mic took from the room.
The JSON folds each band's level correction into its modes' amplitudes and its velocity tilt into each mode.
"""
import json, sys
import numpy as np
from scipy import signal
from scipy.io import wavfile

FS = 44100
N, HOP = 8192, 1024
BANDS = [125 * 2 ** (k / 3) for k in range(22)]
TIMES = [0, .002, .005, .01, .02, .04, .08, .16, .32, .64, 1.0, 1.5, 2.2, 3.2, 4.5, 6.0]
ENV_HOP = int(.005 * FS)


def load(path):
    r, x = wavfile.read(path)
    x = x.astype(np.float64)
    if x.ndim > 1: x = x.mean(axis=1)
    assert r == FS, f"{path}: {r} Hz"
    return x / np.max(np.abs(x))


def onset(x):
    a = np.abs(x)
    return max(0, int(np.argmax(a > .05 * a.max())) - int(.001 * FS))


def band_sos(fc):
    lo, hi = fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), FS * .49)
    return signal.butter(4, [lo / (FS / 2), hi / (FS / 2)], 'band', output='sos')


def points(x):
    """Each band's smoothed energy envelope at TIMES, dB re the clip's rms over its first 0.5 s."""
    ref = 10 * np.log10(np.mean(x[:int(.5 * FS)] ** 2) + 1e-20)
    out = []
    for fc in BANDS:
        y = signal.sosfiltfilt(band_sos(fc), x)
        n = len(y) // ENV_HOP
        e = 10 * np.log10(np.array([np.mean(y[i * ENV_HOP:(i + 1) * ENV_HOP] ** 2) for i in range(n)]) + 1e-20)
        e = np.convolve(e, np.ones(3) / 3, 'same')
        out.append([float(e[min(int(t * FS / ENV_HOP), len(e) - 1)]) - ref for t in TIMES])
    return np.array(out)


def band_of(hz):
    for b, fc in enumerate(BANDS):
        if fc / 2 ** (1 / 6) <= hz < fc * 2 ** (1 / 6): return b
    return None


def fit_modes(x, max_modes):
    f, t, Z = signal.stft(x, FS, window='hann', nperseg=N, noverlap=N - HOP, boundary=None, padded=False)
    db = 20 * np.log10(np.abs(Z) * 2 / np.hanning(N).sum() + 1e-12)
    ref = 2
    spec = db[:, ref]
    pk, _ = signal.find_peaks(spec - signal.medfilt(spec, 101), height=3, distance=2)
    pk = [i for i in pk if 120 < f[i] < 16000 and spec[i] > spec.max() - 75]
    pk = sorted(pk, key=lambda i: -spec[i])[:max_modes]
    tt = t - t[ref]
    floor = np.percentile(db, 10, axis=1)
    modes = []
    for i in pk:
        tr = db[max(i - 1, 0):i + 2, :].max(axis=0)
        alive = np.where(tr[ref:] < floor[i] + 6)[0]
        end = ref + (alive[0] if len(alive) else len(tr) - ref)
        split = ref + int(.3 * FS / HOP)
        e1 = min(end, split)
        early = np.polyfit(tt[ref:e1], tr[ref:e1], 1)[0] if e1 - ref >= 3 else -40.0
        late = np.polyfit(tt[split:end], tr[split:end], 1)[0] if end - split >= 4 else early
        a, b, c = db[i - 1, ref], db[i, ref], db[i + 1, ref]
        d = .5 * (a - c) / (a - 2 * b + c + 1e-12)
        modes.append([float((i + d) * FS / N), float(10 ** (spec[i] / 20)), float(min(early, -1.0)), float(min(late, -1.0))])
    return modes


def stick(x):
    seg = x[:int(.04 * FS)]; rms = np.sqrt(np.mean(seg ** 2))
    return [float(np.sqrt(np.mean(signal.sosfiltfilt(band_sos(fc), seg) ** 2)) / rms) for fc in BANDS]


def render(model, gain, seconds, take=0):
    """The fitting's own reference render (numpy; the app's is Kotlin): full strength, two-slope modes."""
    n = int(seconds * FS); t = np.arange(n) / FS
    rng = np.random.default_rng(1000 + take)
    out = np.zeros(n)
    for hz, a, early, late in model["modes"]:
        b = band_of(hz)
        g = gain[b] if b is not None else 1.0
        g *= 10 ** rng.normal(0, .08 if take else 0)
        dbt = np.where(t < .3, early * t, early * .3 + late * (t - .3))
        out += g * a * 10 ** (dbt / 20) * np.sin(2 * np.pi * (hz + (rng.uniform(-.3, .3) if take else 0)) * t + rng.uniform(0, 2 * np.pi))
    m = int(.06 * FS); burst = np.zeros(n)
    for fc, lv in zip(BANDS, model["stick"]):
        burst[:m] += lv * signal.sosfilt(band_sos(fc), rng.standard_normal(m))
    burst[:m] *= np.exp(-t[:m] / .008) * np.minimum(1, t[:m] / .0004)
    out = out / np.sqrt(np.mean(out[:int(.3 * FS)] ** 2) + 1e-20)
    burst = burst / np.sqrt(np.mean(burst[:int(.02 * FS)] ** 2) + 1e-20)
    y = out + .6 * burst
    return y / np.max(np.abs(y)) * .9


def fit_voice(hard_path, soft_path, max_modes):
    hard = load(hard_path); hard = hard[onset(hard):]
    soft = load(soft_path); soft = soft[onset(soft):]
    seconds = min(4.0, len(hard) / FS)
    model = dict(modes=fit_modes(hard, max_modes), stick=stick(hard))
    want = points(hard)
    i80, i16, i32, i22 = TIMES.index(.08), TIMES.index(.16), TIMES.index(.32), TIMES.index(2.2)
    gain = np.ones(len(BANDS))
    for _ in range(3):
        err = want - points(render(model, gain, seconds))
        gain *= 10 ** (np.clip(err[:, [i80, i16, i32]].mean(axis=1), -12, 12) / 20 * .8)
    for _ in range(2):
        err = want - points(render(model, gain, seconds))
        for mode in model["modes"]:
            b = band_of(mode[0])
            if b is None: continue
            d = (np.clip(err[b][i22], -25, 25) - np.clip(err[b][i32], -25, 25)) / (2.2 - .32)
            mode[3] = min(mode[3] + .6 * d, -1.0)
    tilt = (points(soft)[:, [i80, i16, i32]].mean(axis=1) - want[:, [i80, i16, i32]].mean(axis=1))
    out = []
    for hz, a, early, late in model["modes"]:
        b = band_of(hz)
        out.append([round(hz, 3), float(f"{a * (gain[b] if b is not None else 1.0):.6g}"),
                    round(min(early, late), 3), round(float(tilt[b]) if b is not None else 0.0, 3)])
    return dict(modes=out, stick=[round(v, 5) for v in model["stick"]], stickSoft=[round(v, 5) for v in stick(soft)])


if __name__ == "__main__":
    d, outp = sys.argv[1], sys.argv[2]
    data = dict(
        source="Virtuosity Drums (CC0 1.0), sfzinstruments/virtuosity_drums@9f04cf9a734527edfbb0a4eee1f674e45bbf71bc, "
               "overhead mic; fitted by scripts/fit_breaker_cymbals.py. The recordings do not ship.",
        bands=[round(b, 3) for b in BANDS],
        voices=dict(ride=fit_voice(f"{d}/oh_ride_ride_vl3_rr1.wav", f"{d}/oh_ride_ride_vl1_rr1.wav", 400),
                    crash=fit_voice(f"{d}/oh_crash_crash_vl3_rr1.wav", f"{d}/oh_crash_crash_vl1_rr1.wav", 600)))
    json.dump(data, open(outp, "w"), separators=(",", ":"))
    for k, v in data["voices"].items(): print(f"{k}: {len(v['modes'])} modes")
