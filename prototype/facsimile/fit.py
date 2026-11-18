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


def target_for(takes: np.ndarray, template: np.ndarray, clean_s: float,
               low_share: float = 0.0) -> np.ndarray:
    fine = robust_mean_db([band_envelopes(h[PRE:]) for h in takes])
    tail = template_envelopes(template)
    join = max(0.02, min(clean_s - 0.006, 1.2))
    j = int(np.searchsorted(GRID, join))
    tgt = fine.copy()
    for b in range(len(fine)):
        # match the template to the fine envelope over the last grid points before the join
        k = slice(max(1, j - 2), j)
        offset = np.median(fine[b, k] - tail[b, k])
        tgt[b, j:] = tail[b, j:] + offset
        # a drum strike only decays after its peak: hold the target to that
        pk = int(np.argmax(tgt[b, :8]))
        tgt[b, pk:] = np.minimum.accumulate(tgt[b, pk:])
    if low_share > CHOKE_LOW_SHARE:
        uncut(tgt)
    else:
        no_stall(tgt)
    keep_falling(tgt)
    return np.maximum(tgt, FLOOR_DB)


STALL_AFTER_DB = 20.0   # a hat or snare that has fallen this far ...
STALL_DB_S = 30.0       # ... and then falls slower than this over the next 100 ms has hit the
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


def uncut(tgt: np.ndarray) -> None:
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
        return
    k = max(0, int(np.searchsorted(GRID, GRID[cut] - 0.05)))
    before = (total[cut] - total[k]) / max(GRID[cut] - GRID[k], 1e-3)
    if before < -SUSTAIN_DB_S:
        return  # it was already falling fast: a natural decay, not a cut
    for b in range(len(tgt)):
        span = max(GRID[cut] - GRID[k], 1e-3)
        slope = float(np.clip((tgt[b, cut] - tgt[b, k]) / span, -400, -15))
        tgt[b, cut + 1:] = tgt[b, cut] + slope * (GRID[cut + 1:] - GRID[cut])


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


def steady_peaks(takes: np.ndarray, clean_s: float, exclude: list[float]) -> list[dict]:
    """Ringing tones: peaks at least 12 dB proud of their +-1/6 octave neighbourhood in the
    median (in dB, across hits) spectrum of the first 150 ms."""
    a = PRE + int(0.005 * RATE)
    b = PRE + int(max(0.03, min(clean_s, 0.15)) * RATE)
    if b - a < 512:
        return []
    nfft = 1 << 15
    sp = np.median([20 * np.log10(np.abs(np.fft.rfft(h[a:b] * np.hanning(b - a), nfft)) + 1e-12)
                    for h in takes], axis=0)
    f = np.fft.rfftfreq(nfft, 1 / RATE)
    # neighbourhood level: median over +-1/6 octave, computed on a log-frequency resample
    lf = np.geomspace(30, 14_000, 2400)
    ls = np.interp(lf, f, sp)
    w = int(2400 / np.log2(14_000 / 30) / 6)
    base = signal.medfilt(ls, 2 * w + 1)
    prom = ls - base
    pk, pr = signal.find_peaks(prom, height=12, distance=w)
    peaks = []
    top = ls[pk].max() if len(pk) else 0
    for i in sorted(pk, key=lambda i: -ls[i]):
        if ls[i] < top - 30 or len(peaks) >= 10:
            break
        if any(abs(np.log2(lf[i] / e)) < 0.08 for e in exclude):
            continue
        peaks.append(dict(hz=float(lf[i]), prominence_db=float(prom[i])))
    return peaks


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
    target = target_for(takes, template, clean, low_share)
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
                             lock_phase=False, prominence_db=p["prominence_db"]))

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
        share = float(np.clip(1 - 10 ** (-p["prominence_db"] / 10), 0.3, 0.9))
        # a sine of amplitude A has power A^2/2: so the band power P maps to A = sqrt(2 P share)
        p["env_db"] = target[bt, cols] + 10 * np.log10(2 * share)
        noise_db[bt, cols] = np.minimum(noise_db[bt, cols],
                                        target[bt, cols] + 10 * np.log10(max(1 - share, 0.05)))
        pk = int(np.argmax(p["env_db"]))
        p["env_db"][pk:] = np.minimum.accumulate(p["env_db"][pk:])

    model = dict(id=drum["id"], length_s=round(length, 3), partials=partials,
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
        model["noise_db"] = np.clip(np.asarray(model["noise_db"]) + corr, FLOOR_DB,
                                    peak_db + CAP_OVER_PEAK_DB)
        model["noise_db"][:, ends] = FLOOR_DB
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
    report = dict(id=drum["id"], distance_db=round(min(history), 2),
                  best_pass=int(np.argmin(history)),
                  first_distance_db=round(history[0], 2), glide=glide_info,
                  steady_partials=sum(p["kind"] == "steady" for p in partials),
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
