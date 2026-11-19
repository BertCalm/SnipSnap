"""STAGGER: measure the owner's reference recordings (Python, numpy only; nothing is committed, sliced or shipped).

Run:  python3 -I prototype/stagger/measure_references.py DIR_OF_MONO_44K1_WAVS
Writes prototype/stagger/references.json and prints a table.

Every number is a whole-mix statistic from a phone screen recording (compressed audio, the phone's own
playback chain, vocals and samples under the drums). Read them as "what the mix does", not as isolated drum
spectra. The onset detector is a band-split spectral flux, crude; its timing numbers say "not quantized" and
"which way", not milliseconds a kit should copy.

What is measured, and why:
  tempo       autocorrelation of the full-band onset strength, 60 to 150 BPM, comb-scored over four multiples
  grid        the eighth grid's phase that best lines up with the onsets; deviations are read against sixteenths
  kick        onsets in the 40 to 160 Hz flux; per kick, the first 40 ms share above 1 kHz (the click), the
              300 ms share above 400 Hz (the muffle), f0 at 0 to 80 ms and 100 to 300 ms, decay to -20 dB
  snare       onsets in the 1.5 to 6 kHz flux; the first 40 ms share above 1 kHz and its centroid (the crack)
  hats        the 6 to 14 kHz band against the full band, at its loudest tenth of frames (the whisper)
  clock       median deviation per class, kick minus snare (the drag), lag-one autocorrelation of successive
              deviations (a drift leans, a scatter does not), spread
  mix         95 percent energy ceiling, crest factor, the quietest tenth of 10 ms frames under the loudest
"""
from __future__ import annotations

import json
import math
import os
import sys
import wave

import numpy as np

RATE = 44_100
N_FFT = 1024
HOP = 256


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1 and w.getframerate() == RATE and w.getsampwidth() == 2, path
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return x


def stft_mag(x):
    win = np.hanning(N_FFT)
    n = 1 + (len(x) - N_FFT) // HOP
    frames = np.lib.stride_tricks.as_strided(x, shape=(n, N_FFT), strides=(x.strides[0] * HOP, x.strides[0]))
    return np.abs(np.fft.rfft(frames * win, axis=1))


FREQS = np.fft.rfftfreq(N_FFT, 1 / RATE)


def band(lo, hi):
    return (FREQS >= lo) & (FREQS < hi)


def flux(mag, mask):
    lm = np.log1p(mag[:, mask] * 100)
    d = np.diff(lm, axis=0)
    f = np.maximum(d, 0).sum(axis=1)
    return np.concatenate([[0.0], f])


def peaks(strength, min_gap_frames, k=2.0):
    med = np.median(strength)
    mad = np.median(np.abs(strength - med)) + 1e-9
    thr = med + k * 1.4826 * mad
    out = []
    last = -10 ** 9
    for i in range(1, len(strength) - 1):
        if strength[i] >= thr and strength[i] >= strength[i - 1] and strength[i] >= strength[i + 1] and i - last >= min_gap_frames:
            out.append(i)
            last = i
    return np.array(out)


def strongest(strength, min_gap_frames, k, keep):
    """The onsets above a stricter threshold, then only the strongest [keep] fraction of them: a mix's bass
    notes and samples also move the low band, and the backbeats are the snares worth timing."""
    p = peaks(strength, min_gap_frames, k)
    if p.size == 0:
        return p
    cut = np.quantile(strength[p], 1 - keep)
    return p[strength[p] >= cut]


def within_ms_of_eighth(onset_frames, beat_frames, phase, ms):
    """How many onsets sit within [ms] of an eighth line: a quantized track scores near 100, a random
    scatter scores about 2 * ms / eighth_ms."""
    if onset_frames.size == 0:
        return None
    eighth = beat_frames / 2
    dev = np.abs(((onset_frames - phase + eighth / 2) % eighth) - eighth / 2) * HOP / RATE * 1000
    return round(100 * float(np.mean(dev <= ms)), 1)


def tempo(strength):
    s = strength - strength.mean()
    ac = np.correlate(s, s, mode="full")[len(s) - 1:]
    ac /= ac[0] or 1.0
    best = None
    for lag in range(int(0.4 * RATE / HOP), int(1.0 * RATE / HOP)):
        score = sum(ac[min(len(ac) - 1, lag * m)] for m in (1, 2, 3, 4))
        if best is None or score > best[0]:
            best = (score, lag)
    lag = best[1]
    # refine by parabola
    if 1 <= lag < len(ac) - 1:
        a, b, c = ac[lag - 1], ac[lag], ac[lag + 1]
        den = a - 2 * b + c
        lag = lag + (0.5 * (a - c) / den if abs(den) > 1e-12 else 0.0)
    return 60.0 * RATE / HOP / lag


def grid_phase(onset_frames, beat_frames):
    """The eighth-grid phase (in frames, within one eighth) that the onsets line up with best."""
    eighth = beat_frames / 2
    best = None
    for p in np.linspace(0, eighth, 64, endpoint=False):
        dev = ((onset_frames - p + eighth / 2) % eighth) - eighth / 2
        score = np.sum(np.cos(2 * np.pi * dev / eighth))
        if best is None or score > best[0]:
            best = (score, p)
    return best[1]


def deviations_ms(onset_frames, beat_frames, phase):
    sixteenth = beat_frames / 4
    dev = ((onset_frames - phase + sixteenth / 2) % sixteenth) - sixteenth / 2
    return dev * HOP / RATE * 1000


def lag1(xs):
    xs = np.asarray(xs, dtype=float)
    if len(xs) < 4:
        return None
    xs = xs - xs.mean()
    v = float(np.dot(xs, xs)) or 1e-9
    return float(np.dot(xs[:-1], xs[1:]) / v)


def onset_window(n):
    """Flat for three quarters, a cosine fade for the last quarter: a Hann window would silence the click
    that sits in the first milliseconds of an onset segment, which is the thing being measured."""
    w = np.ones(n)
    k = n // 4
    w[n - k:] = 0.5 * (1 + np.cos(np.pi * np.arange(k) / k))
    return w


def share_above(seg, hz):
    spec = np.abs(np.fft.rfft(seg * onset_window(len(seg)))) ** 2
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    return float(spec[f >= hz].sum() / (spec.sum() or 1.0))


def centroid(seg):
    spec = np.abs(np.fft.rfft(seg * onset_window(len(seg))))
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    return float((spec * f).sum() / (spec.sum() or 1.0))


def lowpass(x, hz):
    a = math.exp(-2 * math.pi * hz / RATE)
    y = np.empty_like(x)
    s = 0.0
    for i in range(len(x)):
        s = (1 - a) * x[i] + a * s
        y[i] = s
    return y


def f0_low(seg):
    """Autocorrelation pitch of a low-passed segment, 35 to 200 Hz."""
    seg = lowpass(lowpass(seg, 300.0), 300.0)
    seg = seg - seg.mean()
    ac = np.correlate(seg, seg, mode="full")[len(seg) - 1:]
    lo, hi = int(RATE / 200), int(RATE / 35)
    if hi >= len(ac):
        return None
    k = lo + int(np.argmax(ac[lo:hi]))
    return RATE / k if ac[k] > 0.2 * ac[0] else None


def decay_ms(x_low, start, db=20.0):
    env = np.sqrt(np.convolve(x_low[start:start + int(0.8 * RATE)] ** 2, np.ones(441) / 441, mode="same"))
    if env.size == 0 or env.max() <= 0:
        return None
    peak_i = int(np.argmax(env[: int(0.08 * RATE)]))
    thr = env[peak_i] * 10 ** (-db / 20)
    below = np.where(env[peak_i:] < thr)[0]
    return float(below[0] / RATE * 1000) if below.size else None


def measure(path):
    x = read_wav(path)
    x = x[int(0.5 * RATE): len(x) - int(0.5 * RATE)]
    mag = stft_mag(x)
    full = flux(mag, band(30, 16_000))
    bpm = tempo(full)
    beat_frames = 60.0 * RATE / HOP / bpm
    kick_on = strongest(flux(mag, band(40, 160)), int(0.15 * RATE / HOP), k=3.5, keep=0.5)
    snare_on = strongest(flux(mag, band(1_500, 6_000)), int(0.15 * RATE / HOP), k=3.5, keep=0.5)
    hat_on = strongest(flux(mag, band(6_000, 14_000)), int(0.06 * RATE / HOP), k=2.5, keep=0.6)
    # The grid's phase is fitted on the snares alone: their flux peak sits within a hop of the strike (the
    # calibration below measures it), while a muffled kick's low-band flux peaks tens of ms after the beater,
    # and fitting the grid on both would hide that latency inside the phase.
    all_on = np.sort(np.concatenate([kick_on, snare_on]))
    phase = grid_phase(snare_on if snare_on.size >= 4 else all_on, beat_frames) if all_on.size else 0.0
    kd = deviations_ms(kick_on, beat_frames, phase)
    sd = deviations_ms(snare_on, beat_frames, phase)
    hd = deviations_ms(hat_on, beat_frames, phase)
    # per-event spectra
    x_low = lowpass(lowpass(x, 200.0), 200.0)
    kicks = []
    for fr in kick_on:
        s = fr * HOP
        if s + int(0.3 * RATE) >= len(x):
            continue
        w40 = x[s: s + int(0.04 * RATE)]
        w300 = x[s: s + int(0.3 * RATE)]
        kicks.append({
            "click_share_above_1k_first_40ms": share_above(w40, 1_000),
            "share_above_400_over_300ms": share_above(w300, 400),
            "f0_attack": f0_low(x[s: s + int(0.08 * RATE)]),
            "f0_body": f0_low(x[s + int(0.1 * RATE): s + int(0.3 * RATE)]),
            "decay_20db_ms": decay_ms(x_low, s),
        })
    snares = []
    for fr in snare_on:
        s = fr * HOP
        if s + int(0.12 * RATE) >= len(x):
            continue
        w40 = x[s: s + int(0.04 * RATE)]
        snares.append({"share_above_1k_first_40ms": share_above(w40, 1_000), "centroid_120ms": centroid(x[s: s + int(0.12 * RATE)])})

    def med(rows, key):
        v = [r[key] for r in rows if r[key] is not None]
        return round(float(np.median(v)), 4) if v else None

    # hats: the top band against the full band at its loudest tenth of frames
    top = (mag[:, band(6_000, 14_000)] ** 2).sum(axis=1)
    whole = (mag[:, band(30, 16_000)] ** 2).sum(axis=1)
    top_loud = np.sort(top)[int(0.9 * len(top)):].mean()
    whole_loud = np.sort(whole)[int(0.9 * len(whole)):].mean()
    hats_db = 10 * math.log10(top_loud / (whole_loud or 1e-12))
    # mix
    spec = (np.abs(np.fft.rfft(x)) ** 2)
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    cum = np.cumsum(spec) / spec.sum()
    ceiling = float(f[np.searchsorted(cum, 0.95)])
    rms = math.sqrt(float(np.mean(x ** 2))) or 1e-9
    crest = 20 * math.log10(float(np.max(np.abs(x))) / rms)
    fr10 = x[: len(x) // 441 * 441].reshape(-1, 441)
    lv = 10 * np.log10((fr10 ** 2).mean(axis=1) + 1e-12)
    lv = lv[lv > -70]  # drop digital silence
    quiet_under_loud = float(np.percentile(lv, 90) - np.percentile(lv, 10))
    # clock: successive deviations in time order, kicks and snares together
    order = np.argsort(np.concatenate([kick_on, snare_on]))
    both = np.concatenate([kd, sd])[order]
    return {
        "seconds": round(len(x) / RATE, 1),
        "bpm": round(bpm, 1),
        "kicks_found": int(len(kick_on)), "snares_found": int(len(snare_on)), "hats_found": int(len(hat_on)),
        "kick": {k: med(kicks, k) for k in ["click_share_above_1k_first_40ms", "share_above_400_over_300ms", "f0_attack", "f0_body", "decay_20db_ms"]},
        "snare": {k: med(snares, k) for k in ["share_above_1k_first_40ms", "centroid_120ms"]},
        "hats_top_band_db_under_full_at_loudest": round(hats_db, 1),
        "clock": {
            "kick_median_ms": round(float(np.median(kd)), 1) if kd.size else None,
            "snare_median_ms": round(float(np.median(sd)), 1) if sd.size else None,
            "hat_median_ms": round(float(np.median(hd)), 1) if hd.size else None,
            "kick_minus_snare_ms": round(float(np.median(kd) - np.median(sd)), 1) if kd.size and sd.size else None,
            "kick_iqr_ms": round(float(np.percentile(kd, 75) - np.percentile(kd, 25)), 1) if kd.size else None,
            "snare_iqr_ms": round(float(np.percentile(sd, 75) - np.percentile(sd, 25)), 1) if sd.size else None,
            "lag1_autocorr_kick_snare": None if lag1(both) is None else round(lag1(both), 2),
            "lag1_autocorr_hats": None if lag1(hd) is None else round(lag1(hd), 2),
            "kicks_within_30ms_of_eighth_pct": within_ms_of_eighth(kick_on, beat_frames, phase, 30),
            "snares_within_30ms_of_eighth_pct": within_ms_of_eighth(snare_on, beat_frames, phase, 30),
            "random_would_score_pct": round(100 * 60 / (60_000 / bpm / 2), 1),
        },
        "mix": {"ceiling_95pct_hz": round(ceiling), "crest_db": round(crest, 1), "loud_tenth_over_quiet_tenth_db": round(quiet_under_loud, 1)},
    }


def calibration_both():
    return {"open_kick": calibration(muffled=False), "muffled_kick_lp300_drive3": calibration(muffled=True)}


def calibration(muffled=False):
    """The same detector on a quantized phrase of the prototype's own kick and snare (known onsets): whatever
    kick-minus-snare it reports here is the detector's band latency, not a feel, and is subtracted when
    the references are read."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import stagger_proto as sp
    bpm = 90.0
    kick = sp.finish(sp.muffle(sp.kick_raw(), 300.0, 3.0, sp.INTERNAL) if muffled else sp.kick_raw())
    snare = sp.finish(sp.snare_raw())
    beat = 60.0 / bpm
    n = int((8 * beat + 1.0) * RATE)
    x = np.zeros(n)
    true_k, true_s = [], []
    for bar in range(2):
        for pos, hit, w in [(0.0, kick, 1.0), (1.5, kick, 0.8), (2.75, kick, 0.7), (1.0, snare, 1.0), (3.0, snare, 1.0)]:
            s = int((bar * 4 + pos) * beat * RATE)
            if s + len(hit) < n:
                x[s: s + len(hit)] += hit * w
                (true_k if hit is kick else true_s).append(s)
    x = np.concatenate([np.zeros(int(0.5 * RATE)), x, np.zeros(int(0.5 * RATE))])
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".calibration.wav")
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x / (np.max(np.abs(x)) or 1) * 0.9, -1, 1) * 32767).astype("<i2").tobytes())
    r = measure(tmp)
    # the detector's own latency: detected kick and snare frames against the frames they were written at
    y = read_wav(tmp)
    y = y[int(0.5 * RATE): len(y) - int(0.5 * RATE)]
    mag = stft_mag(y)
    det_k = strongest(flux(mag, band(40, 160)), int(0.15 * RATE / HOP), k=3.5, keep=0.5) * HOP
    det_s = strongest(flux(mag, band(1_500, 6_000)), int(0.15 * RATE / HOP), k=3.5, keep=0.5) * HOP
    def latency(det, true):
        if det.size == 0:
            return None
        return round(float(np.median([(d - true[np.argmin(np.abs(true - d))]) / RATE * 1000 for d in det])), 1)
    os.remove(tmp)
    return {"bpm": r["bpm"], "kicks_found": r["kicks_found"], "snares_found": r["snares_found"], "clock": r["clock"],
            "kick": r["kick"], "snare": r["snare"],
            "detector_latency_ms": {"kick": latency(det_k, np.array(sorted(true_k))), "snare": latency(det_s, np.array(sorted(true_s)))}}


def main(argv):
    d = argv[1]
    out = {"calibration_quantized_synthetic": calibration_both()}
    for name in sorted(os.listdir(d)):
        if name.endswith(".wav"):
            out[name[:-4]] = measure(os.path.join(d, name))
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "references.json"), "w") as f:
        json.dump(out, f, indent=1)
    print("ref   bpm  K  S  H | click% >400% f0a  f0b  dec | snr>1k% cent | hats dB | Kmed Smed K-S Kiqr Siqr ac(KS) ac(H) | ceil crest dyn")
    for name, cal in out["calibration_quantized_synthetic"].items():
        print("calibration %s: latency K %s S %s ms | bpm %.1f K %d S %d  K-S %s ms  Kiqr %s Siqr %s  within30: K %s S %s | kick click %.1f%% >400 %.1f%% | snare >1k %.1f%% cent %.0f" % (
            name, cal["detector_latency_ms"]["kick"], cal["detector_latency_ms"]["snare"], cal["bpm"], cal["kicks_found"], cal["snares_found"], cal["clock"]["kick_minus_snare_ms"], cal["clock"]["kick_iqr_ms"],
            cal["clock"]["snare_iqr_ms"], cal["clock"]["kicks_within_30ms_of_eighth_pct"], cal["clock"]["snares_within_30ms_of_eighth_pct"],
            100 * cal["kick"]["click_share_above_1k_first_40ms"], 100 * cal["kick"]["share_above_400_over_300ms"],
            100 * cal["snare"]["share_above_1k_first_40ms"], cal["snare"]["centroid_120ms"]))
    print("ref   bpm  K  S  H | click% >400% f0a  f0b  dec | snr>1k% cent | hats dB | Kmed Smed K-S Kiqr Siqr ac(KS) ac(H) K30 S30 | ceil crest dyn")
    for k, r in out.items():
        if "mix" not in r:
            continue
        c, kk, sn, m = r["clock"], r["kick"], r["snare"], r["mix"]
        print("%s %5.1f %2d %2d %2d | %5.1f %5.1f %4s %4s %4s | %5.1f %5.0f | %5.1f | %5s %5s %5s %4s %4s %5s %5s %4s %4s | %5d %4.1f %4.1f" % (
            k, r["bpm"], r["kicks_found"], r["snares_found"], r["hats_found"],
            100 * (kk["click_share_above_1k_first_40ms"] or 0), 100 * (kk["share_above_400_over_300ms"] or 0),
            "-" if kk["f0_attack"] is None else "%.0f" % kk["f0_attack"], "-" if kk["f0_body"] is None else "%.0f" % kk["f0_body"],
            "-" if kk["decay_20db_ms"] is None else "%.0f" % kk["decay_20db_ms"],
            100 * (sn["share_above_1k_first_40ms"] or 0), sn["centroid_120ms"] or 0, r["hats_top_band_db_under_full_at_loudest"],
            c["kick_median_ms"], c["snare_median_ms"], c["kick_minus_snare_ms"], c["kick_iqr_ms"], c["snare_iqr_ms"],
            c["lag1_autocorr_kick_snare"], c["lag1_autocorr_hats"], c["kicks_within_30ms_of_eighth_pct"], c["snares_within_30ms_of_eighth_pct"],
            m["ceiling_95pct_hz"], m["crest_db"], m["loud_tenth_over_quiet_tenth_db"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
