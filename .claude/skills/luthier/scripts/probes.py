"""probes: the house measurements, in Python, for prototypes and for reviewing WAVs.

Each probe mirrors a test SnipSnap engines carry (snipsnap-conventions.md §5). None of these
is the Kotlin implementation; they are close enough to rank designs and to catch the classic
failures (DC, aliasing, a root that is not the note, a macro that does nothing, a loop that
does not close). Report the number and the probe name, and say "Python harness" so nobody
mistakes it for a Kotlin test result.

    python3 probes.py some.wav            # prints a report
    python3 probes.py a.wav b.wav         # adds the macro-activity distance between them

As a library:
    from probes import report, pitch, alias_proxy, macro_activity, never_grows
"""
from __future__ import annotations

import json
import sys

import numpy as np
from scipy import signal

RATE = 44_100


def peak(x: np.ndarray) -> float:
    return float(np.max(np.abs(x))) if len(x) else 0.0


def dc(x: np.ndarray) -> float:
    return float(np.mean(x)) if len(x) else 0.0


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(x * x))) if len(x) else 0.0


def finite(x: np.ndarray) -> bool:
    return bool(np.all(np.isfinite(x)))


def loudness_proxy(x: np.ndarray, rate: int = RATE) -> float:
    """A K-weighting-flavoured RMS: a 2nd-order high-pass at 100 Hz, then RMS. Not the house
    Loudness.of; use it to compare renders with each other."""
    sos = signal.butter(2, 100 / (rate / 2), btype="high", output="sos")
    return rms(signal.sosfilt(sos, x))


def pitch(x: np.ndarray, rate: int = RATE, lo_hz: float = 40.0, hi_hz: float = 2000.0,
          window_seconds: float = 0.25, start_seconds: float = 0.05) -> tuple[float, float]:
    """Autocorrelation pitch (NSDF, McLeod) over one window after the attack. Returns
    (hz, confidence 0..1). SnipSnap's Pitch.detect is also autocorrelation-based with a local
    peak rule; tests there accept confidence >= 0.3 (Skitter asks 0.75). Confidence here is
    the normalized peak of the NSDF at the chosen lag."""
    s = int(start_seconds * rate)
    n = int(window_seconds * rate)
    seg = x[s: s + n]
    if len(seg) < 64:
        return 0.0, 0.0
    seg = seg - np.mean(seg)
    if rms(seg) < 1e-6:
        return 0.0, 0.0
    max_lag = int(rate / lo_hz)
    min_lag = int(rate / hi_hz)
    # NSDF via FFT autocorrelation
    f = np.fft.rfft(seg, 2 * len(seg))
    acf = np.fft.irfft(f * np.conj(f))[: len(seg)]
    # cumulative energy terms m(tau) = sum x[i]^2 + x[i+tau]^2
    e = np.cumsum(seg ** 2)
    total = e[-1]
    m = np.empty(len(seg))
    for tau in range(len(seg)):
        # sum x[i]^2 over i in [0, n-tau) is e[n-tau-1]; sum x[i+tau]^2 over the same i is total - e[tau-1].
        # The earlier form subtracted the first term from the total, which halved m at short lags,
        # let the NSDF exceed 1 and clipped every confidence to 1.0 (found in review of SnipSnap PR 796).
        m[tau] = e[len(seg) - tau - 1] + (total - (e[tau - 1] if tau > 0 else 0.0))
    nsdf = 2 * acf / np.maximum(m, 1e-12)
    cand = nsdf[min_lag: max_lag]
    if len(cand) == 0:
        return 0.0, 0.0
    # first local maximum above a fraction of the global max, the McLeod rule, avoids octave errors
    gmax = np.max(cand)
    thresh = 0.8 * gmax
    best = None
    for i in range(1, len(cand) - 1):
        if cand[i] > cand[i - 1] and cand[i] >= cand[i + 1] and cand[i] >= thresh:
            best = i
            break
    if best is None:
        best = int(np.argmax(cand))
    lag = best + min_lag
    # parabolic refinement
    if 1 <= lag < len(nsdf) - 1:
        y0, y1, y2 = nsdf[lag - 1], nsdf[lag], nsdf[lag + 1]
        denom = (y0 - 2 * y1 + y2)
        if abs(denom) > 1e-12:
            lag = lag + 0.5 * (y0 - y2) / denom
    hz = rate / lag
    conf = float(min(1.0, max(0.0, nsdf[int(round(lag))])))
    return float(hz), conf


def cents(hz: float, ref_hz: float) -> float:
    return 1200.0 * np.log2(hz / ref_hz) if hz > 0 and ref_hz > 0 else float("inf")


def spectral_centroid(x: np.ndarray, rate: int = RATE) -> float:
    f, p = signal.welch(x, rate, nperseg=4096)
    return float(np.sum(f * p) / np.sum(p)) if np.sum(p) > 0 else 0.0


def alias_proxy(raw_internal: np.ndarray, internal_rate: int, output_rate: int = RATE) -> float:
    """Share of energy above the OUTPUT Nyquist in the oversampled raw signal, before band
    limiting. If this is large, decimation will fold it back unless bandLimit removes it; if
    the band-limited version still shows energy near Nyquist the exciter is too sharp.
    Returns a ratio (0 = clean)."""
    f, p = signal.welch(raw_internal, internal_rate, nperseg=8192)
    above = p[f >= output_rate / 2].sum()
    return float(above / p.sum()) if p.sum() > 0 else 0.0


def partials_above_nyquist(x: np.ndarray, rate: int = RATE, floor_db: float = -80.0) -> int:
    """The SHOAL test: count spectral peaks above 0.95 * Nyquist louder than floor_db (should
    be 0 after decimation)."""
    f, p = signal.welch(x, rate, nperseg=8192)
    pdb = 10 * np.log10(np.maximum(p, 1e-20) / max(p.max(), 1e-20))
    sel = f >= 0.95 * rate / 2
    return int(np.sum(pdb[sel] > floor_db))


def macro_activity(a: np.ndarray, b: np.ndarray, rate: int = RATE) -> float:
    """Log-spectral distance (dB RMS over Welch bins) between two renders after leveling.
    The house test is 'every macro changes the sound': a macro whose extremes give a distance
    near 0 is a dead control (Omnibus D004)."""
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    _, pa = signal.welch(a, rate, nperseg=4096)
    _, pb = signal.welch(b, rate, nperseg=4096)
    la = 10 * np.log10(np.maximum(pa, 1e-14))
    lb = 10 * np.log10(np.maximum(pb, 1e-14))
    return float(np.sqrt(np.mean((la - lb) ** 2)))


def envelope(x: np.ndarray, rate: int = RATE, block_ms: float = 10.0) -> np.ndarray:
    n = max(1, int(block_ms * 1e-3 * rate))
    m = len(x) // n
    return np.sqrt(np.mean(x[: m * n].reshape(m, n) ** 2, axis=1))


def never_grows(x: np.ndarray, rate: int = RATE, attack_seconds: float = 0.1) -> tuple[bool, float]:
    """Energy should not rise after the attack. The peak is taken over an independent attack
    window so the check can fail (a peak over the whole render never can; the SILK lesson).
    Returns (ok, worst later block RMS / attack peak block RMS)."""
    env = envelope(x, rate)
    k = max(1, int(attack_seconds / 0.01))
    attack_peak = env[:k].max() if k < len(env) else env.max()
    later = env[k:]
    if len(later) == 0 or attack_peak <= 0:
        return True, 0.0
    ratio = float(later.max() / attack_peak)
    return ratio <= 1.0, ratio


def decay_seconds(x: np.ndarray, rate: int = RATE, floor_db: float = -60.0) -> float:
    env = envelope(x, rate)
    if env.max() <= 0:
        return 0.0
    db = 20 * np.log10(np.maximum(env, 1e-9) / env.max())
    above = np.where(db > floor_db)[0]
    return float((above[-1] + 1) * 0.01) if len(above) else 0.0


def report(x: np.ndarray, rate: int = RATE, name: str = "render", ref_hz: float | None = None) -> dict:
    hz, conf = pitch(x, rate)
    ok, ratio = never_grows(x, rate)
    out = {
        "name": name,
        "frames": int(len(x)),
        "seconds": round(len(x) / rate, 4),
        "finite": finite(x),
        "peak": round(peak(x), 4),
        "dc": round(dc(x), 6),
        "rms": round(rms(x), 4),
        "loudness_proxy": round(loudness_proxy(x, rate), 4),
        "pitch_hz": round(hz, 2),
        "pitch_confidence": round(conf, 3),
        "centroid_hz": round(spectral_centroid(x, rate), 1),
        "decay_to_-60dB_s": decay_seconds(x, rate),
        "never_grows": ok,
        "later_over_attack": round(ratio, 3),
        "partials_above_0.95_nyquist": partials_above_nyquist(x, rate),
    }
    if ref_hz:
        out["cents_from_ref"] = round(cents(hz, ref_hz), 1)
    return out


def spectrogram_png(x: np.ndarray, rate: int, path: str, title: str = "") -> bool:
    """Writes a spectrogram image if matplotlib is available. Returns False if not."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        return False
    f, t, s = signal.spectrogram(x, rate, nperseg=4096, noverlap=3072)
    plt.figure(figsize=(9, 4))
    db = 10 * np.log10(np.maximum(s, 1e-12))
    plt.pcolormesh(t, f, db, shading="gouraud", vmin=db.max() - 90, vmax=db.max())
    # a log frequency axis: musical spectra live in octaves, and a bell at C3 is invisible on a
    # linear 0..12 kHz axis
    plt.yscale("log")
    plt.ylim(30, rate / 2)
    plt.xlabel("s")
    plt.ylabel("Hz (log)")
    plt.title(title or path)
    plt.tight_layout()
    plt.savefig(path, dpi=110)
    plt.close()
    return True


def _main(argv: list[str]) -> int:
    import os
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from snipkit import read_wav
    renders = []
    for p in argv:
        x, rate = read_wav(p)
        renders.append((p, x, rate))
        print(json.dumps(report(x, rate, name=os.path.basename(p)), indent=2))
    if len(renders) == 2:
        (_, a, ra), (_, b, _) = renders
        print(json.dumps({"macro_activity_dB": round(macro_activity(a, b, ra), 2)}, indent=2))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    sys.exit(_main(sys.argv[1:]))
