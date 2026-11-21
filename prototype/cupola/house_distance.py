"""Python port of CalabashTest.distanceDb (synth/src/test/kotlin/com/snipsnap/synth/CalabashTest.kt:192-238):
third-octave bands from 80 Hz, 5 ms hop, 4-hop Hann window, the first 0.15 s, RMS-normalised, RMS of
the dB differences. Python harness, not the Kotlin test; numbers should be close, not identical."""
import numpy as np

RATE = 44100
THIRD = 80.0 * 2.0 ** (np.arange(22) / 3.0)
EDGES = [(c / 2 ** (1 / 6), c * 2 ** (1 / 6)) for c in THIRD]


def spectro_db(x, frame_ms=5, total_s=0.15):
    take = min(len(x), int(total_s * RATE))
    rms = np.sqrt(np.mean(x[:take] ** 2)) + 1e-12
    hop = frame_ms * RATE // 1000
    win = 4 * hop
    size = 1
    while size < win:
        size <<= 1
    padded = np.zeros(max(take, win + hop))
    padded[:take] = x[:take] / rms
    hann = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(win) / (win - 1))
    f = np.arange(size // 2 + 1) * RATE / size
    masks = [(f >= lo) & (f < hi) for lo, hi in EDGES]
    rows = []
    i = 0
    while i < max(1, len(padded) - win):
        S = np.fft.rfft(padded[i:i + win] * hann, size)
        p = np.abs(S) ** 2
        rows.append([10 * np.log10(p[mk].sum() + 1e-12) for mk in masks])
        i += hop
    return np.array(rows)


def distance_db(a, b):
    A, B = spectro_db(a), spectro_db(b)
    n = min(len(A), len(B))
    return float(np.sqrt(np.mean((A[:n] - B[:n]) ** 2)))


def full_distance_db(a, b):
    n = max(len(a), len(b))
    a = np.pad(a, (0, n - len(a)))
    b = np.pad(b, (0, n - len(b)))
    A, B = spectro_db(a, 10, n / RATE), spectro_db(b, 10, n / RATE)
    return float(np.sqrt(np.mean((A - B) ** 2)))
