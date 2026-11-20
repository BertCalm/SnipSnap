"""FACSIMILE stage 3: fit each drum's synth model to its reference, in a closed loop.

Reads the reference hit (median isolated audio, trusted until the drum's next hit) and the
merged NMFD template (trusted for the tail), builds one target per drum, and writes models/
<loop>.json: numbers only. The synth never sees the reference.

The target: 26 band envelopes on the synth's time grid. Up to the "clean" time they come from
the isolated audio with short smoothing (attack detail); after it, from the template (a clean
single strike, smeared by its 46 ms window, which only matters for fast events and those are
inside the clean span). The two are joined per band at the clean time.

The model:
  * a falling partial if the drum has a pitched low body or a sweep (kick, 808, tom, zap):
    pitch tracked backwards from where it is steady, then f(t) = f1 + (f0 - f1) exp(-t / tau)
    fitted by least squares; its harmonics 2..4 as locked partials, sized by measurement;
  * up to 10 steady partials where the spectrum shows a peak at least 12 dB proud of its
    neighbourhood (a snare's shell, a tom's ring, a bell, a hat's metal);
  * the rest of each band's energy as noise.
Then render, measure the render exactly as the target was measured, and correct every band
by the difference (closed loop, damped). The remaining difference is the reported distance.

    python3 -I fit.py WORK_DIR MODELS_DIR
    python3 -I fit.py WORK_DIR MODELS_DIR --events-only   # new hit times, same sounds
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: import our own synth
import glidefit  # noqa: E402
import synth  # noqa: E402
from synth import BAND_EDGES, FLOOR_DB, GRID_MS, RATE

PRE = int(0.003 * RATE)           # drums.py keeps 3 ms before each onset
TEMPLATE_HOP = 256
TEMPLATE_EDGES = np.geomspace(30, 18_000, 122)[1:-1]   # template band centres (decompose.py)
ITERATIONS = 6
DAMPING = 0.8
GRID = np.array(GRID_MS) / 1000.0


# ---------- measurement: the same function measures target and replica ----------

def band_envelopes(x: np.ndarray) -> np.ndarray:
    """26 band envelopes in dB on the grid, from audio that starts at the onset. Smoothing is a
    couple of periods of each band's centre (at least 1.5 ms), so attacks stay sharp up high
    and low bands do not ripple at their own frequency."""
    out = np.full((len(BAND_EDGES) - 1, len(GRID)), FLOOR_DB)
    idx = (GRID * RATE).astype(int)
    for b in range(len(BAND_EDGES) - 1):
        lo, hi = BAND_EDGES[b], BAND_EDGES[b + 1]
        y = signal.sosfiltfilt(signal.butter(3, [lo, hi], btype="bandpass", fs=RATE,
                                             output="sos"), x)
        w = max(int(0.0015 * RATE), int(2.0 / np.sqrt(lo * hi) * RATE))
        p = np.convolve(y * y, np.ones(w) / w, mode="same")
        ok = idx < len(p)
        out[b, ok] = 10 * np.log10(p[idx[ok]] + 1e-14)
    return out


def template_envelopes(template: np.ndarray) -> np.ndarray:
    """The NMFD template folded into the 26 bands, in dB on the grid (power-like units)."""
    out = np.full((len(BAND_EDGES) - 1, len(GRID)), FLOOR_DB)
    t = np.arange(template.shape[1]) * TEMPLATE_HOP / RATE
    for b in range(len(BAND_EDGES) - 1):
        rows = (TEMPLATE_EDGES >= BAND_EDGES[b]) & (TEMPLATE_EDGES < BAND_EDGES[b + 1])
        if not rows.any():
            rows = np.argmin(np.abs(TEMPLATE_EDGES - np.sqrt(BAND_EDGES[b] * BAND_EDGES[b + 1])))
            rows = np.eye(len(TEMPLATE_EDGES), dtype=bool)[rows]
        p = (template[rows] ** 2).sum(0)
        out[b] = 10 * np.log10(np.interp(GRID, t, p, right=0) + 1e-20)
    return out


def robust_mean_db(envs: list[np.ndarray]) -> np.ndarray:
    """Average loudness across hits that one bleed-heavy hit cannot drag up: split the hits into
    three groups, average each in power, take the median of the three. A plain median of noise
    sits about 1.6 dB under its mean (the replica is measured as a mean), so it is not used."""
    p = 10 ** (np.array(envs) / 10)
    if len(p) < 6:
        return 10 * np.log10(p.mean(axis=0) + 1e-30)
    groups = np.array_split(np.arange(len(p)), 3)
    means = [p[g].mean(axis=0) for g in groups]
    return 10 * np.log10(np.median(means, axis=0) + 1e-30)


SWELL_LOOPS = {"113"}   # loops whose layers may build after the strike (as drums.KICK_FIRST)
SWELL_DB = 6.0          # a band builds if it later stands this far over its first 14 ms


def peaks_of_bands(fine: np.ndarray, upto: int, swell: bool) -> np.ndarray:
    """Where each band peaks, as a grid index. A strike peaks in its first 14 ms (the first 8
    grid points). With `swell`, a band that later stands SWELL_DB over that (on the robust
    mean, so one hit's bleed cannot make it) peaks where it really does: the knocker snare's
    room layer starts 30 dB under its body and builds for 75 ms, and held to its first 14 ms
    it was rendered 30 dB too quiet, a dry snare with silence around every hit (2026-11-19)."""
    early = np.argmax(fine[:, :8], axis=1)
    if not swell or upto <= 8:
        return early
    late = 8 + np.argmax(fine[:, 8:upto], axis=1)
    rows = np.arange(len(fine))
    builds = fine[rows, late] - fine[rows, early] >= SWELL_DB
    return np.where(builds, late, early)


def target_for(takes: np.ndarray, template: np.ndarray, clean_s: float,
               low_share: float = 0.0, swell: bool = False) -> np.ndarray:
    fine = robust_mean_db([band_envelopes(h[PRE:]) for h in takes])
    tail = template_envelopes(template)
    join = max(0.02, min(clean_s - 0.006, 1.2))
    j = int(np.searchsorted(GRID, join))
    peaks = peaks_of_bands(fine, j, swell)
    target_for.peaks = peaks          # read by fit_drum: the noise keeps the same shape
    tgt = fine.copy()
    for b in range(len(fine)):
        # match the template to the fine envelope over the last grid points before the join
        k = slice(max(1, j - 2), j)
        offset = np.median(fine[b, k] - tail[b, k])
        tgt[b, j:] = tail[b, j:] + offset
        # a drum strike only decays after its peak: hold the target to that
        pk = int(peaks[b])
        tgt[b, pk:] = np.minimum.accumulate(tgt[b, pk:])
    if TEMPLATE_BOUND:
        template_bound(tgt, tail)
    extended = False
    if low_share > CHOKE_LOW_SHARE:
        extended = uncut(tgt)
    else:
        no_stall(tgt)
    keep_falling(tgt)
    target_for.extended = extended   # read by fit_drum: the groove chokes an extended tail
    return np.maximum(tgt, FLOOR_DB)


# Off until the owner has heard both (2026-11-19). On, 4 of 49 drums ring over 1.5x their
# template (was 20 of 57), but the grooves measured further from their references (loop 113's
# band distance 6.9 to 11.1 dB, loop 163's 11.3 to 17.6): the references seem to carry room or
# reverb that the long tails were partly standing in for.
TEMPLATE_BOUND = False
EARLY_CAP_DB = 10.0    # at the attack a band may stand this much over what the template allows
DECAY_SLACK_DB = 3.0   # and after BOUND_FROM_S it may fall this much slower than the template
BOUND_FROM_S = 0.025   # (the template's 46 ms window smears anything earlier)


def template_bound(tgt: np.ndarray, tail: np.ndarray) -> None:
    """The NMFD template is one clean strike of this drum; the hits it is fitted to are cut
    from a dense loop and carry the tails and attacks of the drums around them, and the mix's
    own room. Measured, 20 of 57 replicas rang 1.5 to 5 times longer than their templates (a
    snare 459 ms to -30 dB against 157), which a groove plays as a wash behind every hit, heard
    as echo (2026-11-19); and an 808 carried another drum's click in its attack. So the template
    bounds the target: no band louder at the attack than the template's own level for it plus
    EARLY_CAP_DB (levels anchored on the drum's strongest band), and after BOUND_FROM_S no band
    falling slower than the template's decay in that band plus DECAY_SLACK_DB."""
    tail = np.maximum(tail, FLOOR_DB)
    early = GRID <= 0.014
    t_peak = tail[:, early].max(axis=1)
    b0 = int(np.argmax(t_peak))
    gain = tgt[b0, early].max() - t_peak[b0]
    cap = t_peak + gain + EARLY_CAP_DB
    np.minimum(tgt, cap[:, None], out=tgt)
    late = GRID >= BOUND_FROM_S
    for b in range(len(tgt)):
        anchor = tgt[b, early].max()
        bound = anchor + (tail[b] - t_peak[b]) + DECAY_SLACK_DB
        tgt[b, late] = np.minimum(tgt[b, late], np.maximum(bound[late], FLOOR_DB))


STALL_AFTER_DB = 20.0   # a hat or snare that has fallen this far ...
STALL_DB_S = 8.0        # ... and then falls slower than this over the next 100 ms has hit the
                        # template floor, not a ring: it keeps falling at FLOOR_SLOPE_DB_S


def no_stall(tgt: np.ndarray) -> None:
    """For drums that are not low-heavy: a weak NMFD component's floor sits only 30 to 50 dB
    under its peak, above keep_falling's threshold, and leaves a hat or snare sustaining. Find
    where the drum, having fallen STALL_AFTER_DB, stops falling, and continue its fall from
    there. A low-heavy drum (an 808, a boom tom) may ring low for a long time and is left as
    measured."""
    total = 10 * np.log10((10 ** (tgt / 10)).sum(0) + 1e-30)
    pk = int(np.argmax(total))
    for i in range(pk + 1, len(GRID)):
        if total[i] > total[pk] - STALL_AFTER_DB:
            continue
        j = int(np.searchsorted(GRID, GRID[i] + 0.1))
        if j >= len(GRID):
            return
        if (total[i] - total[j]) / (GRID[j] - GRID[i]) < STALL_DB_S:
            ceiling = tgt[:, i:i + 1] - FLOOR_SLOPE_DB_S * (GRID[i:] - GRID[i])[None, :]
            tgt[:, i:] = np.minimum(tgt[:, i:], ceiling)
            return


FLOOR_AFTER_DB = 40.0   # once the drum is this far below its peak ...
FLOOR_SLOPE_DB_S = 60.0  # ... it keeps falling at least this fast


def keep_falling(tgt: np.ndarray) -> None:
    """NMFD templates start every cell at a tiny value, and where no hit ever shows a drum's
    late tail, the cell stays there: a flat plateau 45 to 60 dB down that lasts the whole
    template. A struck drum never holds a level, so once it is FLOOR_AFTER_DB under its peak,
    every band falls at least FLOOR_SLOPE_DB_S from there on. The body is untouched."""
    total = 10 * np.log10((10 ** (tgt / 10)).sum(0) + 1e-30)
    pk = int(np.argmax(total))
    under = np.nonzero(total[pk:] < total[pk] - FLOOR_AFTER_DB)[0]
    if len(under) == 0:
        return
    i = pk + int(under[0])
    ceiling = tgt[:, i:i + 1] - FLOOR_SLOPE_DB_S * (GRID[i:] - GRID[i])[None, :]
    tgt[:, i:] = np.minimum(tgt[:, i:], ceiling)


CLIFF_DB = 25.0      # a fall this deep ...
CLIFF_S = 0.035      # ... this fast is a cut (a choke by the next note), not a decay ...
SUSTAIN_DB_S = 150.0  # ... but only if the drum was falling slower than this just before
CHOKE_LOW_SHARE = 0.5  # and only for a low-heavy drum (808, kick): a hat or a snap falls this
                       # fast on its own, and continuing its body would give it a long wash


def uncut(tgt: np.ndarray) -> bool:
    """Trap 808s are monophonic: the next note chokes the last, so the reference never lets a
    tail ring out and the target falls off a cliff. Where the whole drum falls CLIFF_DB within
    CLIFF_S, continue every band at the slope it had over the 50 ms before the cut (between
    15 and 400 dB a second). The groove replica reproduces the cut by choking, as a sampler
    does; the pad gets its natural tail."""
    total = 10 * np.log10((10 ** (tgt / 10)).sum(0) + 1e-30)
    for i in range(2, len(GRID)):
        j = np.searchsorted(GRID, GRID[i] + CLIFF_S, side="right") - 1
        if j > i and total[i] - total[j] > CLIFF_DB and total[i] > total.max() - 40:
            cut = i
            break
    else:
        return False
    k = max(0, int(np.searchsorted(GRID, GRID[cut] - 0.05)))
    before = (total[cut] - total[k]) / max(GRID[cut] - GRID[k], 1e-3)
    if before < -SUSTAIN_DB_S:
        return False  # it was already falling fast: a natural decay, not a cut
    for b in range(len(tgt)):
        span = max(GRID[cut] - GRID[k], 1e-3)
        slope = float(np.clip((tgt[b, cut] - tgt[b, k]) / span, -400, -15))
        tgt[b, cut + 1:] = tgt[b, cut] + slope * (GRID[cut + 1:] - GRID[cut])
    return True


# ---------- partials ----------

def band_index_over_time(p: dict) -> np.ndarray:
    """Which of the 26 bands the partial sounds in at each grid time (a glide moves down)."""
    if p["tau_s"] > 0:
        f = glidefit.freq(p.get("law", "exp"), p["f0"], p["f1"], p["tau_s"], GRID)
    else:
        f = np.full(len(GRID), p["f1"])
    return np.clip(np.searchsorted(BAND_EDGES, f) - 1, 0, len(BAND_EDGES) - 2)


def root_guesses(x: np.ndarray, span: float, template: np.ndarray) -> list[float]:
    """Where the low body settles, two ways: the strongest 30..400 Hz peak in the audio from
    30 ms to the end of the clean span, and the template's strongest body band. Below 30 Hz is
    left out: what the separation leaves down there is rumble, not a drum."""
    out = []
    seg = x[int(0.03 * RATE):int(span * RATE)]
    if len(seg) > int(0.04 * RATE):
        sp = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 16))
        f = np.fft.rfftfreq(1 << 16, 1 / RATE)
        sel = (f >= 30) & (f <= 400)
        out.append(float(f[sel][np.argmax(sp[sel])]))
    t0 = int(0.04 * RATE / TEMPLATE_HOP)
    body = template[:, t0:t0 + 20].sum(1)
    sel = (TEMPLATE_EDGES >= 30) & (TEMPLATE_EDGES <= 400)
    tg = float(TEMPLATE_EDGES[sel][np.argmax(body[sel])])
    if not out or abs(np.log2(tg / out[0])) > 0.1:
        out.append(tg)
    return out


def glide_envelope(tt, amp, template, f_end) -> np.ndarray:
    """A glide partial's loudness on the grid, straight from the tracked amplitudes. Before the
    first tracked frame it holds; after the last it keeps falling at the template's own decay
    rate in the band where the partial ends (the tail outlives the clean span); it only decays
    after its peak."""
    db = 20 * np.log10(np.maximum(amp, 1e-9))
    env = np.interp(GRID, tt, db)
    # tail slope from the template band at the partial's end frequency
    row = int(np.argmin(np.abs(TEMPLATE_EDGES - f_end)))
    tr = 20 * np.log10(template[row] + 1e-12)
    t_tpl = np.arange(template.shape[1]) * TEMPLATE_HOP / RATE
    after = GRID > tt[-1]
    if after.any():
        a, b = tt[-1], min(tt[-1] + 0.25, t_tpl[-1])
        ia, ib = np.searchsorted(t_tpl, [a, b])
        slope = (tr[ib - 1] - tr[ia]) / max(b - a, 1e-3) if ib - 1 > ia else -200.0
        slope = min(slope, -20.0)  # a struck body never grows; at least 20 dB/s down
        env[after] = db[-1] + slope * (GRID[after] - tt[-1])
    pk = int(np.argmax(env))
    env[pk:] = np.minimum.accumulate(env[pk:])
    return np.maximum(env, FLOOR_DB)


STEADY_PROMINENCE_DB = 12.0       # a ringing tone stands this far proud of its neighbourhood
HIGH_STEADY_PROMINENCE_DB = 18.0  # above HIGH_HZ noise peaks of a repeated sample reach 12 dB
HIGH_HZ = 2000.0
STABLE_PROMINENCE_DB = 8.0        # ... and is still this proud in at least 2 of 3 time slices
STABLE_FROM_HZ = 1000.0           # (checked from here up: a 48 ms slice blurs a low tone's peak
                                  # wider than its neighbourhood, and the false peaks are high)


def _median_spectrum(takes, a, b, nfft):
    return np.median([20 * np.log10(np.abs(np.fft.rfft(h[a:b] * np.hanning(b - a), nfft)) + 1e-12)
                      for h in takes], axis=0)


def _prominence(sp, f):
    """Level over the +-1/6 octave neighbourhood median, on a log-frequency resample."""
    lf = np.geomspace(30, 14_000, 2400)
    ls = np.interp(lf, f, sp)
    w = int(2400 / np.log2(14_000 / 30) / 6)
    return lf, ls, ls - signal.medfilt(ls, 2 * w + 1), w


def steady_peaks(takes: np.ndarray, clean_s: float, exclude: list[float]) -> list[dict]:
    """Ringing tones: peaks proud of their neighbourhood in the median (across hits) spectrum
    of the first 150 ms, which also RING: still proud in at least two of three consecutive
    slices of that window. A noise peak sits in one moment's spectrum and not the next; when a
    loop repeats one sample, the median across hits cannot remove it, and rendered as a sine it
    rides the noise like ring modulation (heard on the long shaker, 2026-11-18)."""
    a = PRE + int(0.005 * RATE)
    b = PRE + int(max(0.03, min(clean_s, 0.15)) * RATE)
    if b - a < 512:
        return []
    nfft = 1 << 15
    f = np.fft.rfftfreq(nfft, 1 / RATE)
    med = _median_spectrum(takes, a, b, nfft)
    power = 10 ** (med / 10)
    lobe = max(2, int(2 * nfft / (b - a)))       # half the Hann main lobe, in bins
    lf, ls, prom, w = _prominence(med, f)
    edges = np.linspace(a, b, 4).astype(int)
    slices = [_prominence(_median_spectrum(takes, edges[i], edges[i + 1], nfft), f)[2]
              for i in range(3)] if (b - a) // 3 >= 512 else None
    pk, _ = signal.find_peaks(prom, height=STEADY_PROMINENCE_DB, distance=w)
    peaks = []
    top = ls[pk].max() if len(pk) else 0
    tol = max(1, int(2400 / np.log2(14_000 / 30) / 24))   # +-1/24 octave
    for i in sorted(pk, key=lambda i: -ls[i]):
        if ls[i] < top - 30 or len(peaks) >= 10:
            break
        if lf[i] > HIGH_HZ and prom[i] < HIGH_STEADY_PROMINENCE_DB:
            continue
        if any(abs(np.log2(lf[i] / e)) < 0.08 for e in exclude):
            continue
        if lf[i] >= STABLE_FROM_HZ:
            if slices is None:
                continue  # too short to show that it rings: leave it to the noise
            held = sum(sl[max(0, i - tol):i + tol + 1].max() >= STABLE_PROMINENCE_DB
                       for sl in slices)
            if held < 2:
                continue
        # the tone's real share of its band's energy, read off the spectrum: the peak's main
        # lobe against the whole band. Sized from prominence instead, a tone took up to 90% of
        # its band and stood out 8 dB more than the reference's own ring did.
        k = int(np.argmin(np.abs(f - lf[i])))
        band = int(np.clip(np.searchsorted(BAND_EDGES, lf[i]) - 1, 0, len(BAND_EDGES) - 2))
        in_band = (f >= BAND_EDGES[band]) & (f < BAND_EDGES[band + 1])
        share = float(power[max(0, k - lobe):k + lobe + 1].sum() / (power[in_band].sum() + 1e-30))
        peaks.append(dict(hz=float(lf[i]), prominence_db=float(prom[i]),
                          share=float(np.clip(share, 0.02, 0.9))))
    return peaks


MODE_MAX_HZ = 200.0      # tail tones are measured below this
MODE_FROM_S = 0.06       # ... from here, once the pitch fall has settled
MODE_TO_S = 0.45         # ... to here (or the clean end)
MODE_ORDER = 16          # matrix-pencil model order (8 complex pairs and room for noise)
MODE_RANGE_DB = 30.0     # tones this far under the strongest are left to the noise
MODE_FADE_S = 0.02       # the tones fade in over MODE_FROM_S - MODE_FADE_S .. MODE_FROM_S
MODE_END_DB = 8.0        # the tones stop where the reference falls this far under them
MODE_END_FADE_S = 0.04   # ... over this long
MODE_END_HOLD = 5        # "stays there": five 20 ms windows (the take's last ms can hold the
                         # next drum's bleed, so "to the end" never held)


def tail_modes(takes: np.ndarray, clean_s: float) -> list[dict]:
    """A kick's low tail as measured damped tones, each with its frequency, decay, level and
    phase. The knocker kick's tail pulses: its level under 150 Hz dips about 70 ms after the
    click, swells back 10 dB and dips again, the same on every hit (one sample). Rendered as
    narrow noise bands it swelled at random moments on each take, heard as extra soft hits
    inside the kick (2026-11-19). A matrix pencil on the mean of the clean takes, low-passed
    and decimated to 1.1 kHz, over MODE_FROM_S..MODE_TO_S, finds a cluster of tones at 67 to
    82 Hz with about the same 1.1 s decay (one boom whose level pulses) and a few at 93 to
    113 Hz; summed with their measured phases they follow the reference's pulse within about
    2 dB to 360 ms. The kick then stops (it falls 27 dB from 400 to 520 ms, the sample's own
    end), which decaying tones cannot do: they end, fading over MODE_END_FADE_S, where the
    reference falls MODE_END_DB under them and stays there for 100 ms. Returns [] if they explain less
    than 3/4 of the tail."""
    t1 = min(MODE_TO_S, clean_s - 0.02)
    if t1 - MODE_FROM_S < 0.15:
        return []
    sos = signal.butter(4, MODE_MAX_HZ * 1.1, "low", fs=RATE, output="sos")
    full = [h for h in takes if np.count_nonzero(np.abs(h)) / RATE >= t1 + PRE / RATE]
    if not full:
        return []
    x = np.mean([signal.sosfiltfilt(sos, h) for h in full], axis=0)
    dec = 40
    r = RATE / dec
    x = signal.resample_poly(x, 1, dec)
    seg = x[int((PRE / RATE + MODE_FROM_S) * r):int((PRE / RATE + t1) * r)]
    N = len(seg)
    L = N // 2
    Y = np.array([seg[i:i + L + 1] for i in range(N - L)])
    V = np.linalg.svd(Y, full_matrices=False)[2][:MODE_ORDER].conj().T
    z = np.linalg.eigvals(np.linalg.pinv(V[:-1]) @ V[1:])
    a = np.linalg.lstsq(np.vander(z, N, increasing=True).T, seg.astype(complex), rcond=None)[0]
    hz = np.angle(z) * r / (2 * np.pi)
    keep = (hz > 30) & (hz < MODE_MAX_HZ) & (np.abs(z) < 1)
    if not keep.any():
        return []
    amp = 2 * np.abs(a)
    keep &= amp >= amp[keep].max() * 10 ** (-MODE_RANGE_DB / 20)
    rec = 2 * np.real(np.vander(z[keep], N, increasing=True).T @ a[keep])
    if np.sum((seg - rec) ** 2) > 0.25 * np.sum(seg ** 2):
        return []
    # where the reference falls away from the tones for good, on 20 ms windows to the clean end
    start = int((PRE / RATE + MODE_FROM_S) * r)
    stop = int((PRE / RATE + clean_s - 0.01) * r)
    tail = x[start:stop]
    rec_all = 2 * np.real(np.vander(z[keep], len(tail), increasing=True).T @ a[keep])
    w = int(0.02 * r)
    db = lambda y: 10 * np.log10(np.array([np.mean(y[i:i + w] ** 2) for i in
                                           range(0, len(y) - w + 1, w)]) + 1e-20)
    below = db(tail) < db(rec_all) - MODE_END_DB
    end_s = clean_s
    for i in range(len(below)):
        if below[i:i + MODE_END_HOLD].all() and len(below[i:i + MODE_END_HOLD]) == MODE_END_HOLD:
            end_s = MODE_FROM_S + i * w / r
            break
    modes = []
    for zk, ak, f in zip(z[keep], a[keep], hz[keep]):
        a0 = ak * zk ** (-MODE_FROM_S * r)          # referred back to the strike (t = 0)
        modes.append(dict(kind="mode", hz=round(float(f), 3),
                          decay_s=round(float(1 / (-np.log(np.abs(zk)) * r)), 4),
                          amp_db=round(float(20 * np.log10(2 * np.abs(a0))), 3),
                          phase=round(float(np.angle(a0)), 4),
                          start_s=MODE_FROM_S - MODE_FADE_S, fade_s=MODE_FADE_S,
                          end_s=round(float(end_s), 3), end_fade_s=MODE_END_FADE_S))
    return sorted(modes, key=lambda m: -m["amp_db"])


KICK_TONE_HZ = 400.0     # under this a rebuilt kick is tone only: its pitch fall and harmonics,
                         # then its tail tones


def tone_only_low(partials: list[dict], target: np.ndarray) -> np.ndarray | None:
    """A rebuilt kick's low end as tones only. Fitted as sines plus third-octave noise at the
    same frequencies, the two drift in and out of phase at random. Under 100 Hz the knocker
    kick fell to -23 dB at 35 ms and bounced back twice by 80 ms where the reference holds its
    body flat for 20 ms and then falls smoothly; and with its harmonics (147, 220, 293 Hz) on
    fresh random phases each take, two takes in four split the attack into a click, a hole at
    15 ms and the body, where every reference kick is one solid 20 ms block. Both were heard
    as a fragmented kick (2026-11-20). So the bands wholly under KICK_TONE_HZ get no noise;
    at each grid time each band's measured power goes to the harmonic nearest it (a sine of
    amplitude A has power A^2/2), and every harmonic keeps its phase on every take, as a
    sampled kick does. Returns the mask of those bands, or None when there is no glide."""
    glides = [q for q in partials if q["kind"] == "glide"]
    if not any(q.get("harmonic") == 1 for q in glides):
        return None
    low = BAND_EDGES[1:] <= KICK_TONE_HZ * 1.001
    centres = np.sqrt(BAND_EDGES[:-1] * BAND_EDGES[1:])
    freqs = np.array([glidefit.freq(q.get("law", "exp"), q["f0"], q["f1"], q["tau_s"], GRID)
                      for q in glides])
    power = np.zeros((len(glides), len(GRID)))
    for b in np.nonzero(low)[0]:
        near = np.argmin(np.abs(np.log2(freqs / centres[b])), axis=0)
        power[near, np.arange(len(GRID))] += 10 ** (target[b] / 10)
    for q, pw in zip(glides, power):
        env = 10 * np.log10(2 * pw + 1e-30)
        env = np.maximum(env, FLOOR_DB)
        pk = int(np.argmax(env))
        env[pk:] = np.minimum.accumulate(env[pk:])
        q["env_db"] = env
        q["lock_phase"] = True
        q["tone_only_low"] = True
    return low


def hand_over(p: dict) -> None:
    """A low glide partial stops where the tail tones take over: full to 40 ms, -6 dB at 55,
    silent from 75 (the grid's own points around the tones' 40 to 60 ms fade-in)."""
    if p["kind"] != "glide" or p["f1"] >= MODE_MAX_HZ:
        return
    e = np.asarray(p["env_db"], dtype=float)
    g = np.asarray(GRID)
    e[g >= MODE_FROM_S - 0.005] -= 6.0
    e[g >= MODE_FROM_S + 0.015] = FLOOR_DB
    p["env_db"] = e


def strike_shape(env_db: np.ndarray, peak: int | None = None) -> np.ndarray:
    """A struck sound's loudness in one band: it rises to its peak within the first 14 ms and
    then only falls. Corrected point by point, the loop otherwise zigzags 15 to 30 dB between
    grid points a few ms apart, and that fast loudness switching adds buzzy sidebands, a kind
    of ring modulation."""
    e = np.array(env_db, dtype=float)
    pk = int(np.argmax(e[:8])) if peak is None else int(peak)   # a building layer: its own
    e[:pk + 1] = np.maximum.accumulate(e[:pk + 1])
    e[pk:] = np.minimum.accumulate(e[pk:])
    return e


# ---------- the fit ----------

FIT_SEEDS = 3            # noise recordings the fit averages over
CAP_OVER_PEAK_DB = 6.0   # no band or partial may be set louder than the drum's loudest target


def expected_envelopes(model: dict) -> np.ndarray:
    """The replica's band envelopes averaged (in power) over FIT_SEEDS noise recordings. One
    recording's narrow-band noise wanders 4 to 6 dB at any grid point; fitted to one, the loop
    learns that recording's wobble, and every other take (and the 4x render) misses by more."""
    p = np.mean([10 ** (band_envelopes(synth.render(model, 1.0, 0, oversample=1, noise_seed=k))
                        / 10) for k in range(FIT_SEEDS)], axis=0)
    return 10 * np.log10(p + 1e-30)


def fit_drum(drum: dict, work: Path) -> tuple[dict, dict]:
    takes = np.load(work / "hits" / f"{drum['id']}_takes.npy").astype(float)
    template = np.load(work / "hits" / f"{drum['id']}_template.npy")
    clean = float(drum["clean_seconds"])
    low_share = float((template[TEMPLATE_EDGES < 150] ** 2).sum() / (template ** 2).sum())
    target = target_for(takes, template, clean, low_share,
                        swell=drum["id"].split("-")[0] in SWELL_LOOPS)
    # only a band that builds gets its late peak; a strike keeps finding its own early one
    band_peaks = [int(pk) if pk >= 8 else None for pk in target_for.peaks]
    extended_tail = bool(target_for.extended)
    # length: where every band has fallen 60 dB below the drum's peak, plus a little
    peak_db = target.max()
    alive = np.nonzero((target > peak_db - 60).any(0))[0]
    length = float(min(1.4, GRID[alive[-1]] + 0.08 if len(alive) else 0.3))

    partials = []
    glide_info = None
    if low_share > 0.2 or drum.get("force_glide", False):
        span = float(np.clip(clean - 0.004, 0.03, 0.45))
        # a zap starts near 10 kHz; a kick's body does not, and a narrower search is faster
        max_f0 = 12_000.0 if low_share < 0.5 else 6_000.0
        g, root, which = None, None, None
        # Fit each of the three loudest hits on its own and keep the cleanest: hits differ by
        # fractions of a millisecond, which is enough to cancel a sweep in any average of them
        for ti, h in enumerate(takes[:3]):
            x = h[PRE:]
            for guess in root_guesses(x, span, template):
                cand = glidefit.fit(x, RATE, guess, span, max_f0)
                if cand is not None and 1 in cand["amp"] and (g is None or
                                                               cand["captured"] > g["captured"]):
                    g, root, which = cand, guess, ti
        if g is not None:
            glide_info = dict(f0=g["f0"], f1=g["f1"], tau=g["tau"], law=g["law"],
                              captured=g["captured"], root_guess=root, from_hit=which)
        if g is not None and g["captured"] > 0.3:
            tt = g["t"]
            for k, a in g["amp"].items():
                env = glide_envelope(tt, a, template, g["f1"] * k)
                if k > 1 and env.max() < 20 * np.log10(g["amp"][1].max() + 1e-12) - 40:
                    continue  # a harmonic 40 dB under the fundamental is not worth a partial
                partials.append(dict(kind="glide", f0=g["f0"] * k, f1=g["f1"] * k,
                                     tau_s=g["tau"], law=g["law"], phase=0.0,
                                     lock_phase=k == 1,
                                     harmonic=k, env_db=env, measured=True))
        elif glide_info is not None:
            glide_info["rejected"] = True
    exclude = [p["f1"] for p in partials]
    for p in steady_peaks(takes, clean, exclude):
        partials.append(dict(kind="steady", f0=p["hz"], f1=p["hz"], tau_s=0.0,
                             phase=float(np.random.default_rng(int(p["hz"])).uniform(0, 6.28)),
                             lock_phase=False, prominence_db=p["prominence_db"],
                             share=p["share"]))

    # a kick rebuilt whole (drums.kick_first): its low tail as measured tones, phase-locked
    is_kick = bool((drum.get("kick_first") or {}).get("kick"))
    modes = tail_modes(takes, clean) if is_kick else []
    tone_low = tone_only_low(partials, target) if is_kick else None
    if modes:
        for p in partials:
            hand_over(p)

    # initial split of each band's target between partials in it and noise
    noise_db = target.copy()
    cols = np.arange(len(GRID))
    for p in partials:
        bt = band_index_over_time(p)
        if p["kind"] == "glide":
            # the band keeps as noise only what the measured sine does not already explain
            sine_pow = 10 ** ((np.asarray(p["env_db"]) - 3.01) / 10)
            band_pow = 10 ** (noise_db[bt, cols] / 10)
            rest = np.maximum(band_pow - sine_pow, band_pow * 0.05)
            noise_db[bt, cols] = 10 * np.log10(rest)
            continue
        share = p["share"]
        # a sine of amplitude A has power A^2/2: so the band power P maps to A = sqrt(2 P share)
        p["env_db"] = target[bt, cols] + 10 * np.log10(2 * share)
        noise_db[bt, cols] = np.minimum(noise_db[bt, cols],
                                        target[bt, cols] + 10 * np.log10(max(1 - share, 0.05)))
        pk = int(np.argmax(p["env_db"]))
        p["env_db"][pk:] = np.minimum.accumulate(p["env_db"][pk:])

    noise_db = np.array([strike_shape(r, pk) for r, pk in zip(noise_db, band_peaks)])
    if tone_low is not None:
        noise_db[tone_low] = FLOOR_DB
    model = dict(id=drum["id"], length_s=round(length, 3), partials=partials + modes,
                 self_choke=extended_tail,
                 noise_db=noise_db,
                 velocity=dict(tilt_db_per_oct=-2.5, decay_loss=0.15, glide_depth_loss=0.3,
                               source="house law"),
                 round_robin=dict(band_sigma_db=float(np.clip(
                     (drum.get("instance_spread_db") or 0.5) * 0.4, 0.3, 1.2)),
                     pitch_cents=3.0, tau_spread=0.05,
                     source="instance spread of the reference, clamped 0.3..1.2 dB"))

    # closed loop: render (no oversampling during the fit, for speed; the final check renders
    # at 4x), measure exactly as the target was measured, correct by the damped difference
    history = []
    # Grid points at or past the render's end can never be heard or measured; correcting them
    # only pumps them up pass after pass (a swell into the last sample, 8 to 20 dB too loud).
    ends = GRID >= length - 0.01
    for it in range(ITERATIONS + 1):
        got = expected_envelopes(jsonable(model))
        err = target - got
        live = (target > peak_db - 50) & ~ends[None, :]
        history.append(float(np.mean(np.abs(err[live]))))
        if history[-1] == min(history):
            best = jsonable(model)  # keep the best pass: the loop may never hand back worse
        if it == ITERATIONS:
            break
        corr = DAMPING * np.clip(err, -18, 18)
        corr[~live] = np.minimum(corr[~live], 0)   # never boost what the target says is quiet
        corr[:, ends] = 0
        model["noise_db"] = np.array([strike_shape(r, pk) for r, pk in zip(np.clip(
            np.asarray(model["noise_db"]) + corr, FLOOR_DB, peak_db + CAP_OVER_PEAK_DB),
            band_peaks)])
        model["noise_db"][:, ends] = FLOOR_DB
        if tone_low is not None:
            model["noise_db"][tone_low] = FLOOR_DB
        for p in partials:
            bt = band_index_over_time(p)
            c = corr[bt, cols]
            if p["kind"] == "glide":
                # measured directly, so only a gentle, smooth trim: three-point average in
                # time and at most 3 dB a pass, so the curve cannot start to jump again
                c = np.clip(np.convolve(np.pad(c, 1, mode="edge"), np.ones(3) / 3, "valid"),
                            -3, 3)
            e = np.clip(np.asarray(p["env_db"]) + c, FLOOR_DB, peak_db + CAP_OVER_PEAK_DB)
            e[ends] = FLOOR_DB
            pk = int(np.argmax(e))
            e[pk:] = np.minimum.accumulate(e[pk:])  # a ringing mode only decays
            p["env_db"] = e
            if modes:
                hand_over(p)
    report = dict(id=drum["id"], distance_db=round(min(history), 2),
                  best_pass=int(np.argmin(history)),
                  first_distance_db=round(history[0], 2), glide=glide_info,
                  steady_partials=sum(p["kind"] == "steady" for p in partials),
                  tail_modes=len(modes),
                  low_share=round(low_share, 3), length_s=round(length, 3))
    return best, report


def jsonable(m):
    if isinstance(m, dict):
        return {k: jsonable(v) for k, v in m.items()}
    if isinstance(m, (list, tuple)):
        return [jsonable(v) for v in m]
    if isinstance(m, np.ndarray):
        return [jsonable(v) for v in m.tolist()]
    if isinstance(m, (np.floating, float)):
        return round(float(m), 3)
    if isinstance(m, np.integer):
        return int(m)
    return m


def main(work: Path, models: Path, only: str | None = None) -> None:
    models.mkdir(parents=True, exist_ok=True)
    inv = json.loads((work / "inventory.json").read_text())
    if only == "--events-only":
        # hit times and loudness changed, the sounds did not: copy the events, refit nothing
        for key, loop in inv.items():
            f = models / f"{key}.json"
            m = json.loads(f.read_text())
            for d in loop["drums"]:
                m["drums"][d["id"]]["events"] = d["events"]
            f.write_text(json.dumps(m, separators=(",", ":")))
        print("events copied into", len(inv), "loops")
        return
    reports = {}
    for key, loop in inv.items():
        if only and key != only:
            continue
        out = dict(name_bpm=loop["bpm"], seconds=loop["seconds"], drums={})
        for d in loop["drums"]:
            model, rep = fit_drum(d, work)
            model["events"] = d["events"]
            out["drums"][d["id"]] = model
            reports[d["id"]] = rep
            g = rep["glide"]
            gs = (f"glide {g['f0']:.0f}->{g['f1']:.1f} Hz tau {1000 * g['tau']:.0f} ms "
                  f"{g['law']} cap {g['captured']:.2f}{' REJ' if g.get('rejected') else ''}"
                  if g else "no glide")
            print(f"{d['id']:8s} dist {rep['first_distance_db']:5.2f} -> "
                  f"{rep['distance_db']:5.2f} dB  {gs:34s} steady={rep['steady_partials']} "
                  f"len={rep['length_s']}")
        (models / f"{key}.json").write_text(json.dumps(out, separators=(",", ":")))
    old = json.loads((models / "fit-report.json").read_text()) \
        if (models / "fit-report.json").exists() and only else {}
    old.update(reports)
    (models / "fit-report.json").write_text(json.dumps(old, indent=1))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else None)
