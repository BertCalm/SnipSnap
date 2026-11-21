"""Measure WAVs (the owner's reference loops, or DRAWL renders): the numbers a design has to hit.

Python harness, not Kotlin. Run with: python3 -I analyze_refs.py <dir> <out.json>
Per file: centroid, band shares (LTAS), onset rate, pitch-track statistics
(glide fraction, glide speed, vibrato rate/depth), decay of the loudest isolated onset.
"""
import json, sys, wave, os
import numpy as np
from scipy import signal


def load(path):
    w = wave.open(path)
    n, ch, sw, sr = w.getnframes(), w.getnchannels(), w.getsampwidth(), w.getframerate()
    raw = np.frombuffer(w.readframes(n), dtype=np.uint8)
    if sw == 3:
        b = raw.reshape(-1, 3)
        v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v >= 1 << 23, v - (1 << 24), v).astype(np.float64) / (1 << 23)
    elif sw == 2:
        v = np.frombuffer(raw.tobytes(), dtype="<i2").astype(np.float64) / 32768.0
    else:
        raise ValueError(f"{path}: {8 * sw}-bit WAV not handled")
    x = v.reshape(-1, ch).mean(axis=1)
    if sr != 44100:
        x = signal.resample_poly(x, 147, 160)
        sr = 44100
    return x, sr


BANDS = [(40, 100), (100, 250), (250, 500), (500, 1000), (1000, 2000), (2000, 4000), (4000, 8000), (8000, 16000)]


def ltas(x, sr):
    f, p = signal.welch(x, sr, nperseg=8192)
    tot = p[(f >= 40) & (f < 16000)].sum()
    shares = {f"{lo}-{hi}": round(float(p[(f >= lo) & (f < hi)].sum() / tot), 3) for lo, hi in BANDS}
    cen = float((f * p).sum() / p.sum())
    # Smoothed LTAS peaks between 400 Hz and 4 kHz: the body/cone signature.
    db = 10 * np.log10(p + 1e-20)
    sm = np.convolve(db, np.ones(9) / 9, mode="same")
    m = (f > 400) & (f < 4000)
    idx, _ = signal.find_peaks(sm[m], prominence=2.0)
    peaks = sorted([(round(float(f[m][i])), round(float(sm[m][i] - np.median(sm[m])), 1)) for i in idx],
                   key=lambda t: -t[1])[:4]
    return cen, shares, peaks


def onsets(x, sr):
    hop = 512
    S = np.abs(signal.stft(x, sr, nperseg=2048, noverlap=2048 - hop)[2])
    flux = np.maximum(0, np.diff(np.log1p(100 * S), axis=1)).sum(axis=0)
    flux = (flux - np.median(flux)) / (np.std(flux) + 1e-9)
    pk, _ = signal.find_peaks(flux, height=2.0, distance=int(0.08 * sr / hop))
    return pk * hop / sr


def pitch_track(x, sr, hop=441, win=2048, fmin=60, fmax=1000):
    """Normalized-autocorrelation f0 per 10 ms frame (YIN-like, CMNDF)."""
    out = []
    lag_min, lag_max = int(sr / fmax), int(sr / fmin)
    e = np.sqrt(np.convolve(x * x, np.ones(win) / win, mode="same"))
    gate = 0.08 * np.percentile(e, 99)
    for start in range(0, len(x) - win - lag_max, hop):
        fr = x[start:start + win + lag_max]
        if e[start + win // 2] < gate:
            out.append(np.nan); continue
        a = fr[:win]
        # fast difference function via FFT
        n = len(fr)
        F = np.fft.rfft(fr, 2 * n)
        ac = np.fft.irfft(F * np.conj(np.fft.rfft(a, 2 * n)))[:lag_max + 1]
        cs = np.cumsum(fr * fr)
        energy_a = cs[win - 1]
        energy_t = np.array([cs[t + win - 1] - (cs[t - 1] if t > 0 else 0) for t in range(lag_max + 1)])
        dfn = energy_a + energy_t - 2 * ac
        cm = dfn[1:] * np.arange(1, lag_max + 1) / np.maximum(np.cumsum(dfn[1:]), 1e-12)
        cm = np.concatenate([[1], cm])
        cand = np.where(cm[lag_min:] < 0.2)[0]
        if len(cand) == 0:
            out.append(np.nan); continue
        t = cand[0] + lag_min
        while t + 1 < len(cm) and cm[t + 1] < cm[t]:
            t += 1
        if 1 <= t < len(cm) - 1:
            a1, b1, c1 = cm[t - 1], cm[t], cm[t + 1]
            den = a1 - 2 * b1 + c1
            t = t + (0.5 * (a1 - c1) / den if den != 0 else 0)
        out.append(sr / t)
    return np.array(out), hop / sr


def pitch_stats(f0, dt):
    cents = 1200 * np.log2(f0 / 440.0)
    voiced = np.isfinite(cents)
    vf = float(voiced.mean())
    # Contiguous voiced runs of >= 150 ms
    runs, cur = [], []
    for i, v in enumerate(voiced):
        if v: cur.append(i)
        elif cur:
            runs.append(cur); cur = []
    if cur: runs.append(cur)
    runs = [r for r in runs if len(r) * dt >= 0.15]
    slopes, vib_rates, vib_depths, glide_frames, total = [], [], [], 0, 0
    glide_extents = []
    for r in runs:
        c = cents[r]
        # Remove octave jumps
        c = np.where(np.abs(np.diff(c, prepend=c[0])) > 600, np.nan, c)
        c = c[np.isfinite(c)]
        if len(c) < 10: continue
        c = signal.medfilt(c, 5)
        sl = np.abs(np.gradient(c)) / dt  # cents per second
        sm = np.convolve(c, np.ones(15) / 15, mode="same")
        slope_sm = np.abs(np.gradient(sm)) / dt
        g = slope_sm > 300  # gliding faster than 3 semitones/s
        glide_frames += int(g.sum()); total += len(c)
        if g.any():
            slopes.append(float(np.median(slope_sm[g])))
            glide_extents.append(float(sm.max() - sm.min()))
        # vibrato: detrended residual spectrum in 3-9 Hz
        if len(c) * dt >= 0.6:
            resid = c - sm
            fr = np.fft.rfftfreq(len(resid), dt)
            sp = np.abs(np.fft.rfft(resid * np.hanning(len(resid))))
            band = (fr >= 3) & (fr <= 9)
            if band.any() and sp[band].max() > 0:
                vib_rates.append(float(fr[band][np.argmax(sp[band])]))
                vib_depths.append(float(np.std(resid) * 2 * np.sqrt(2)))
    return {
        "voiced_fraction": round(vf, 2),
        "glide_fraction": round(glide_frames / total, 3) if total else None,
        "glide_speed_cents_per_s_median": round(float(np.median(slopes))) if slopes else None,
        "glide_extent_cents_median": round(float(np.median(glide_extents))) if glide_extents else None,
        "vibrato_rate_hz_median": round(float(np.median(vib_rates)), 1) if vib_rates else None,
        "vibrato_depth_cents_pp_median": round(float(np.median(vib_depths))) if vib_depths else None,
        "f0_median_hz": round(float(np.nanmedian(f0)), 1) if np.isfinite(f0).any() else None,
    }


def decay(x, sr, ons):
    """T30-style slope (dB/s) after the loudest onset, over the next quiet-free stretch."""
    env = np.sqrt(np.convolve(x * x, np.ones(441) / 441, mode="same"))
    best = None
    for i, t in enumerate(ons):
        s = int(t * sr)
        e = int(ons[i + 1] * sr) if i + 1 < len(ons) else len(x)
        if (e - s) / sr < 0.4: continue
        seg = 20 * np.log10(env[s:e] + 1e-9)
        pk = int(np.argmax(seg[: int(0.05 * sr)]))
        tail = seg[pk:]
        tt = np.arange(len(tail)) / sr
        k = np.polyfit(tt, tail, 1)[0]
        if best is None or seg[pk] > best[1]:
            best = (k, seg[pk], (e - s) / sr)
    return (round(float(best[0]), 1), round(best[2], 2)) if best else (None, None)


def attack_ms(x, sr, ons):
    env = np.abs(signal.hilbert(x))
    env = np.convolve(env, np.ones(44) / 44, mode="same")
    vals = []
    for t in ons[:40]:
        s = max(0, int(t * sr) - int(0.02 * sr)); e = min(len(x), s + int(0.12 * sr))
        seg = env[s:e]
        if len(seg) < 10: continue
        p = seg.max(); i10 = np.argmax(seg > 0.1 * p); i90 = np.argmax(seg > 0.9 * p)
        vals.append((i90 - i10) / sr * 1000)
    return round(float(np.median(vals)), 1) if vals else None


def main():
    d, outp = sys.argv[1], sys.argv[2]
    res = {}
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".wav"): continue
        x, sr = load(os.path.join(d, fn))
        x = x[: 30 * sr]  # first 30 s is plenty
        cen, shares, peaks = ltas(x, sr)
        ons = onsets(x, sr)
        f0, dt = pitch_track(x, sr)
        ps = pitch_stats(f0, dt)
        dec, span = decay(x, sr, ons)
        key = fn.split("-", 1)[1] if "-" in fn[:9] else fn
        res[key] = {
            "seconds": round(len(x) / sr, 1),
            "centroid_hz": round(cen),
            "band_share": shares,
            "ltas_peaks_hz_db": peaks,
            "onsets_per_s": round(len(ons) / (len(x) / sr), 2),
            "attack_10_90_ms_median": attack_ms(x, sr, ons),
            "decay_db_per_s_loudest_note": dec,
            "decay_window_s": span,
            **ps,
        }
        print(key, json.dumps(res[key]), flush=True)
    json.dump(res, open(outp, "w"), indent=1)


if __name__ == "__main__":
    main()
