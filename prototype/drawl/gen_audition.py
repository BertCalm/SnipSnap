"""DRAWL round 0 audition: renders, probes and the comparison with the owner's references.

Python harness, not Kotlin. Writes OUT/<section>/*.wav and OUT/report.json.

    python3 prototype/drawl/gen_audition.py OUT [REFS_METRICS_JSON]

REFS_METRICS_JSON is measure.py's output over the owner's fifteen reference loops (they are
commercial sample-pack audio and are never committed; only their measured numbers are used).
"""
from __future__ import annotations

import itertools
import json
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import drawl_proto as dp  # noqa: E402
import measure  # noqa: E402

sk = dp.sk
RATE = dp.RATE
MACROS = ["TUNE", "SLIDE", "WAVER", "PICK", "BODY", "RING", "HOLD"]
CHORDS = {
    "CONE": ([0, 7, 12, 16], "open D major, bar across"),
    "BOTTLE": ([0, 7, 12], "E power chord plus octave"),
    "LAP": ([0, 3, 7, 12], "D minor, bar slides the chord"),
    "STEEL": ([0, 4, 7, 12], "E major, pedals raise the third and the top into it"),
    "FLATTOP": ([0, 4, 7, 12, 16, 19], "six-string D major strum"),
    "GUT": ([0, 3, 7, 12], "E minor"),
}


def save(out, section, name, y):
    d = os.path.join(out, section)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name + ".wav")
    sk.write_wav(path, y)
    return os.path.relpath(path, out)


def centroid(y):
    f, p = signal.welch(y, RATE, nperseg=4096)
    return float((f * p).sum() / p.sum())


def onset_centroid(y, ms=80):
    """Magnitude-weighted centroid of the first `ms` (the StrikeVelocityTest probe shape:
    the power-weighted one hides a string's overtones under its fundamental)."""
    seg = y[: int(ms * RATE / 1000)] * np.hanning(int(ms * RATE / 1000))
    mag = np.abs(np.fft.rfft(seg, 16384))
    f = np.fft.rfftfreq(16384, 1 / RATE)
    return float((f * mag).sum() / mag.sum())


def log_spec(y):
    f, p = signal.welch(y, RATE, nperseg=4096)
    return 10 * np.log10(p[(f > 40) & (f < 12000)] + 1e-14)


def env_db(y, frames=40):
    n = len(y) // frames
    return np.array([20 * np.log10(np.sqrt(np.mean(y[i * n:(i + 1) * n] ** 2)) + 1e-9) for i in range(frames)])


def distance(a, b):
    """Macro activity: mean absolute log-spectrum difference (dB) plus envelope difference."""
    n = min(len(a), len(b))
    return float(np.mean(np.abs(log_spec(a[:n]) - log_spec(b[:n]))) + .25 * np.mean(np.abs(env_db(a[:n]) - env_db(b[:n]))))


def steady_pitch_cents(y, target_hz, start=.8, end=1.6):
    f0, dt = measure.pitch_track(y, RATE)
    i0, i1 = int(start / dt), int(end / dt)
    seg = f0[i0:i1]
    seg = seg[np.isfinite(seg)]
    if len(seg) < 5:
        return None, 0.0
    c = 1200 * np.log2(np.median(seg) / target_hz)
    return round(float(c), 1), round(len(seg) / max(1, i1 - i0), 2)


def zipper(mode, voice="CONE"):
    """HF artifact during a 12-semitone slide with full vibrato: energy above 10 kHz in the
    glide window over the same band once landed, with pick click and scrape off so the true
    signal has almost nothing up there. Lower is cleaner."""
    m = dict(dp.DEFAULTS[voice], SLIDE=1.0, WAVER=1.0, PICK=.2)
    saved_click, saved_scrape = dict(dp.CLICK), dp.scrape
    dp.CLICK[voice] = 0.0
    dp.scrape = lambda path, *a, **k: np.zeros(len(path))
    try:
        raw, p = dp.render_raw(voice, m, .75, 0, mode=mode)
    finally:
        dp.CLICK.update(saved_click)
        dp.scrape = saved_scrape
    y = dp.condition(raw)
    hp = signal.butter(6, 10000 / (RATE / 2), "high", output="sos")
    h = signal.sosfilt(hp, y)
    g0, g1 = int(.03 * RATE), int((p.slide_dur + .02) * RATE)
    s0, s1 = int(.6 * RATE), int(1.2 * RATE)
    full = lambda a, b: float(np.sqrt(np.mean(y[a:b] ** 2)))
    hf = lambda a, b: float(np.sqrt(np.mean(h[a:b] ** 2)))
    return {"hf_share_glide_db": round(20 * np.log10(hf(g0, g1) / full(g0, g1)), 1),
            "hf_share_vibrato_db": round(20 * np.log10(hf(s0, s1) / full(s0, s1)), 1)}


def main():
    out = sys.argv[1]
    refs = json.load(open(sys.argv[2])) if len(sys.argv) > 2 else {}
    os.makedirs(out, exist_ok=True)
    rep = {"voices": {}, "files": {}, "probes": {}, "zipper": {}}

    for v in dp.VOICES:
        vr = {}
        # Defaults, at three notes.
        for tune, tag in ((.25, "low"), (.5, "mid"), (.75, "high")):
            y, p = dp.render(v, {"TUNE": tune})
            rep["files"][f"voices/{v}/{tag}"] = save(out, "voices", f"{v.lower()}-{tag}", y)
            if tag == "mid":
                vr["seconds"] = round(len(y) / RATE, 2)
                vr["drum_class"] = dp.drum_class(len(y) / RATE)
                vr["peak"] = round(float(np.max(np.abs(y))), 3)
                vr["dc"] = round(float(np.mean(y)), 5)
                vr["finite"] = bool(np.all(np.isfinite(y)))
                vr["centroid_hz"] = round(centroid(y))
                # Root: a still bar (WAVER 0), read once the slide or pedal has landed, in
                # both loop reads (the house allpass retune and the Lagrange read).
                landed = (max(.4, p.swell * .8) + p.slide_dur if v == "STEEL" else p.slide_dur) + .15
                for mode, key in ((0, "house"), (1, "lagrange")):
                    ys, ps = dp.render(v, {"TUNE": tune, "WAVER": 0.0}, mode=mode)
                    roots = {}
                    for t2, tag2 in ((.25, "low"), (.5, "mid"), (.75, "high")):
                        yy, pp = dp.render(v, {"TUNE": t2, "WAVER": 0.0}, mode=mode)
                        roots[tag2] = steady_pitch_cents(yy, dp.hz(pp.midi), start=landed, end=landed + .5)
                    vr[f"root_cents_{key}"] = {k: r[0] for k, r in roots.items()}
                    vr[f"root_voiced_share_{key}"] = {k: r[1] for k, r in roots.items()}
                tmp = os.path.join(out, "_tmp.wav")
                sk.write_wav(tmp, y)
                x, sr = measure.load(tmp)
                cen, shares, peaks = measure.ltas(x, sr)
                f0, dt = measure.pitch_track(x, sr)
                vr["band_share"] = shares
                vr["ltas_peaks_hz_db"] = peaks
                vr.update({k: val for k, val in measure.pitch_stats(f0, dt).items()
                           if k.startswith(("glide", "vibrato"))})
                ons = np.array([0.0])
                vr["decay_db_per_s"] = measure.decay(x, sr, ons)[0]
                os.remove(tmp)
                # Determinism: byte-identical renders.
                y2, _ = dp.render(v, {"TUNE": tune})
                vr["deterministic"] = bool(np.array_equal(y, y2))
        # Velocity: soft, medium, hard at one shared performance gain.
        vel = {}
        for name, vv in (("soft", .3), ("medium", .75), ("hard", 1.0)):
            y, _ = dp.render(v, velocity=vv)
            rep["files"][f"velocity/{v}/{name}"] = save(out, "velocity", f"{v.lower()}-{name}", y)
            # Strike character is read with the bar still (SLIDE 0): the bar's scrape does not
            # follow the pick, so at a soft touch it would stand in for the pick in this probe.
            ys0, _ = dp.render(v, {"SLIDE": 0.0}, velocity=vv)
            matched = ys0 / max(sk.rms(ys0[: int(.3 * RATE)]), 1e-9)
            vel[name] = {"rms_db": round(20 * np.log10(sk.rms(y) + 1e-12), 1),
                         "onset_centroid_hz_level_matched": round(onset_centroid(matched))}
        vr["velocity"] = vel
        vr["velocity_timbre_monotonic"] = (vel["soft"]["onset_centroid_hz_level_matched"]
                                           < vel["medium"]["onset_centroid_hz_level_matched"]
                                           < vel["hard"]["onset_centroid_hz_level_matched"])
        # Round robin: four takes, same target pitch, different strikes.
        takes = []
        for t in range(4):
            y, p = dp.render(v, take=t)
            rep["files"][f"takes/{v}/{t}"] = save(out, "takes", f"{v.lower()}-take{t}", y)
            takes.append(y)
        pair = [distance(a, b) for a, b in itertools.combinations(takes, 2)]
        still = [dp.render(v, {"WAVER": 0.0}, take=t) for t in range(4)]
        tp = [steady_pitch_cents(y, dp.hz(pp.midi), start=landed, end=landed + .5)[0] for y, pp in still]
        vr["takes"] = {"pairwise_distance_min": round(min(pair), 2), "pairwise_distance_max": round(max(pair), 2),
                       "identical_pairs": sum(1 for d in pair if d == 0.0),
                       "steady_pitch_cents": tp,
                       "velocity_soft_hard_distance": None}
        ys, _ = dp.render(v, velocity=.3)
        yh, _ = dp.render(v, velocity=1.0)
        vr["takes"]["velocity_soft_hard_distance"] = round(distance(ys, yh), 2)
        # Each macro swept 0 and 1, the rest at default.
        act = {}
        for mname in MACROS:
            ya, _ = dp.render(v, {mname: 0.0})
            yb, _ = dp.render(v, {mname: 1.0})
            if mname != "TUNE":
                rep["files"][f"macros/{v}/{mname}/0"] = save(out, "macros", f"{v.lower()}-{mname.lower()}-0", ya)
                rep["files"][f"macros/{v}/{mname}/1"] = save(out, "macros", f"{v.lower()}-{mname.lower()}-1", yb)
            act[mname] = round(distance(ya, yb), 2)
        # WAVER moves pitch, not the long-term spectrum: probe it on the pitch track.
        wv = {}
        for w in (0.0, .5, 1.0):
            yw, pw = dp.render(v, {"WAVER": w})
            f0, dt = measure.pitch_track(yw, RATE)
            c = 1200 * np.log2(f0[int((landed + .4) / dt):int((landed + 1.2) / dt)] / dp.hz(pw.midi))
            c = c[np.isfinite(c) & (np.abs(c) < 600)]
            wv[str(w)] = round(float(np.std(c)), 1) if len(c) > 10 else None
        act["WAVER_pitch_std_cents_at_0_.5_1"] = wv
        # HOLD is finite: it lengthens the note (the HALOBAR/ACCORD/OSTERIA policy).
        act["HOLD_seconds_at_0_1"] = [round(len(dp.render(v, {"HOLD": h})[0]) / RATE, 2) for h in (0.0, 1.0)]
        yu, _ = dp.render(v, {"NOT_A_MACRO": 1.0})
        y0, _ = dp.render(v)
        act["unknown_key_identical"] = bool(np.array_equal(yu, y0))
        yn, _ = dp.render(v, {"SLIDE": float("nan")})
        act["nan_falls_back_to_default"] = bool(np.array_equal(yn, y0))
        vr["macro_activity"] = act
        # Pair corners: SLIDE x WAVER (the bar's two hands).
        for s, w in ((0, 0), (0, 1), (1, 0), (1, 1)):
            y, _ = dp.render(v, {"SLIDE": float(s), "WAVER": float(w)})
            rep["files"][f"pairs/{v}/slide{s}-waver{w}"] = save(out, "pairs", f"{v.lower()}-slide{s}-waver{w}", y)
        # STRUM.
        iv, label = dp_chord = CHORDS[v]
        for direction in ("DOWN", "UP"):
            y, _ = dp.render(v, intervals=iv, sweep=.06 if v != "FLATTOP" else .09, direction=direction)
            rep["files"][f"strum/{v}/{direction}"] = save(out, "strum", f"{v.lower()}-{direction.lower()}", y)
        vr["strum_chord"] = label
        rep["voices"][v] = vr
        print(v, json.dumps({k: vr[k] for k in ("root_cents_house", "root_cents_lagrange", "centroid_hz", "velocity_timbre_monotonic", "deterministic")}), flush=True)

    rep["zipper"] = {"house_allpass_retune": zipper(0), "lagrange_read": zipper(1)}
    print("zipper", rep["zipper"])
    # References: the numbers a voice should land near (measured, not heard).
    rep["references"] = refs
    json.dump(rep, open(os.path.join(out, "report.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
