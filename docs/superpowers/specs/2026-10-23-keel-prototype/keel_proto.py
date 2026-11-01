"""KEEL prototype: a bass engine whose gesture is a note that settles.

The characteristic gesture is: attack with character -> the upper spectrum distils away
-> the sub settles in under the note -> release.

Three voices share one settle clock s(t) = 2^(-t / half_life):
  UPRIGHT  a plucked string (KS) through a fixed wooden body; a curing low-pass closes toward
           the fundamental as s(t) falls (Omnibus Oaken's curing filter, on a one-shot timescale).
  REDUCE   2-op FM whose index is I0 * s(t) + floor, so the spectrum falls to a near-sine
           (Omnibus Omega's distillation, half-life 0.05..1.2 s instead of 2..20 s).
  BELLY    a warm sine-cubed belly plus a detuned saw bite through a closing low-pass; the bite
           fades as s(t) falls (Omnibus Overbite's belly/bite pair).
All three add a phase-locked sine one octave below the note whose level grows as the note
settles (Omnibus Ogre's gravity, where the sub and drive rise as a held note ages).

Macros: TUNE SETTLE WEIGHT GRIT SLIDE LENGTH (+HOLD, which here is the settled-state loop).
Every constant below is a "shape" value. Python harness, not Kotlin. Nothing here was heard.

Run: <venv>/bin/python -I keel_proto.py out_dir
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

VOICES = ["UPRIGHT", "REDUCE", "BELLY"]
MACROS = {"TUNE": 0.5, "SETTLE": 0.5, "WEIGHT": 0.4, "GRIT": 0.2, "SLIDE": 0.5, "LENGTH": 0.5}
ROOT_MIDI = 36            # C2 (65.4 Hz) at TUNE .5; TUNE spans C1..C3
TUNE_SEMITONES = 24
LN2 = math.log(2.0)

# ----------------------------------------------------------------- shared laws

def midi_for(tune: float) -> int:
    return ROOT_MIDI + int(round((tune - 0.5) * TUNE_SEMITONES))


def hz_for(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def length_seconds(m: dict) -> float:
    """-60 dB time of the amplitude envelope. Buffer is 1.15x this and stays under the 1.5 s
    LOOP threshold (Classifier.LOOP_MIN_SECONDS) so a one-shot files TONAL."""
    return sk.exp_map(m["LENGTH"], 0.20, 1.25)


def half_life_seconds(m: dict) -> float:
    """The settle clock. SETTLE .5 gives 0.245 s: a note is spectrally 'settled' (s < 0.06)
    one second in. This is the quantity SETTLE changes; LENGTH never touches it."""
    return sk.exp_map(m["SETTLE"], 0.05, 1.2)


def settle_curve(n: int, rate: int, half_life: float) -> np.ndarray:
    t = np.arange(n) / rate
    return np.exp2(-t / half_life)


def pitch_track(hz: float, slide: float, n: int, rate: int) -> tuple[np.ndarray, np.ndarray]:
    """SLIDE is bipolar around .5 (Dsp.around in the Kotlin): .5 = no slide; 0 = start an
    octave below and rise; 1 = start an octave above and fall. The slide runs in the log
    domain (as SIREN does) with a one-pole that reaches 1% of the offset at glide_time.
    Returns (hz per sample, phase in cycles per sample)."""
    offset = sk.around(slide, 0.0, -12.0, 12.0)
    glide_time = 0.03 + 0.25 * abs(offset) / 12.0
    tau = glide_time / 4.6
    t = np.arange(n) / rate
    f = hz * np.exp2((offset / 12.0) * np.exp(-t / tau))
    phase = np.cumsum(f / rate)
    return f, phase


def amp_env(n: int, rate: int, length: float) -> np.ndarray:
    t = np.arange(n) / rate
    attack = np.minimum(t / 0.003, 1.0)
    return attack * np.exp(-6.9078 * t / length)


def source_envelope(src: np.ndarray, rate: int) -> np.ndarray:
    """RMS envelope of the source (one-pole on the square, 30 Hz), normalised to its early
    peak. The sub is scaled by this so it borrows level from the note and never outlives it
    (v2 let the sub outlive UPRIGHT's string and the tail turned into a sub-octave sine)."""
    g = 1.0 - math.exp(-2.0 * math.pi * 30.0 / rate)
    e = signal.lfilter([g], [1.0, -(1.0 - g)], src * src)
    env = np.sqrt(np.maximum(e, 0.0))
    head = env[: int(0.08 * rate)]
    return env / max(1e-9, float(np.max(head)))


def sub_layer(phase: np.ndarray, f: np.ndarray, s: np.ndarray, weight: float, rate: int) -> np.ndarray:
    """A sine one octave under the note, phase-locked to the same pitch track (so it follows
    the slide), whose level grows from 35% to 100% of WEIGHT as the note settles. Below a
    20 Hz sub the level fades to nothing (the house 20 Hz high-pass would eat it anyway, and
    an MPC speaker cannot play it): a law, not a cliff."""
    gain = 0.7 * weight ** 1.5                       # at WEIGHT 1 the sub sits about 1 dB under the (0.8-peak) source, so the root stays the note by level
    grow = 0.35 + 0.65 * (1.0 - s)
    sub_hz = f / 2.0
    low_fade = np.clip((sub_hz - 20.0) / 10.0, 0.0, 1.0)
    return gain * grow * low_fade * np.sin(math.pi * phase)   # pi*phase = 2*pi*(phase/2)


def grit(x: np.ndarray, amount: float) -> np.ndarray:
    """tanh(k x)/tanh(k), FATHOM's own drive law (k 1..48 there; 1..24 here, bass needs less).
    Applied to the source only; the sub is added clean afterwards so GRIT never turns the
    sub into a square."""
    k = sk.lin(amount, 1.0, 24.0)
    return np.tanh(k * x) / math.tanh(k)


def closing_lowpass(x: np.ndarray, cutoff_hz: np.ndarray, rate: int, order: int = 2, block: int = 128) -> np.ndarray:
    """A cascade of one-poles whose cutoff is a per-sample array, updated every `block`
    samples (Overbite refreshes its SVF every 16 samples; the Kotlin would use Dsp.TptSvf per
    sample). Two poles = 12 dB/oct, Oaken's curing SVF slope."""
    y = x.copy()
    for _ in range(order):
        out = np.empty_like(y)
        zi = np.zeros(1)
        for start in range(0, len(y), block):
            fc = float(cutoff_hz[start])
            g = 1.0 - math.exp(-2.0 * math.pi * fc / rate)
            seg, zi = signal.lfilter([g], [1.0, -(1.0 - g)], y[start:start + block], zi=zi)
            out[start:start + block] = seg
        y = out
    return y


def polyblep_saw(phase: np.ndarray, dt: np.ndarray) -> np.ndarray:
    """A band-limited saw (the Kotlin has PolyBLEP in SIREN and STOOP). phase in cycles."""
    p = phase % 1.0
    naive = 2.0 * p - 1.0
    t1 = p / dt
    c1 = np.where(p < dt, 2.0 * t1 - t1 * t1 - 1.0, 0.0)
    t2 = (p - 1.0) / dt
    c2 = np.where(p > 1.0 - dt, t2 * t2 + 2.0 * t2 + 1.0, 0.0)
    return naive - c1 - c2


# ----------------------------------------------------------------- the voices

def ks_string(excitation: np.ndarray, period: np.ndarray, loop_gain: float, loop_lp_hz: float, rate: int) -> np.ndarray:
    """Karplus-Strong with a per-sample fractional period (so the string follows the slide),
    a one-pole loop low-pass and a loop gain. The Kotlin equivalent is Strings.Loop with
    retune(); its tuning budgets the loop filter's phase delay, this one does not, so expect
    a few cents flat and read the report's cents column with that in mind."""
    n = len(excitation)
    size = int(np.max(period)) + 4
    buf = [0.0] * size
    out = np.empty(n)
    g = 1.0 - math.exp(-2.0 * math.pi * loop_lp_hz / rate)
    lp = 0.0
    w = 0
    exc = excitation.tolist()
    per = period.tolist()
    for i in range(n):
        p = per[i]
        r = (w - p) % size
        i0 = int(r)
        frac = r - i0
        i1 = (i0 + 1) % size
        delayed = buf[i0] * (1.0 - frac) + buf[i1] * frac
        lp += g * (delayed - lp)
        y = exc[i] + loop_gain * lp
        buf[w] = y
        out[i] = y
        w = (w + 1) % size
    return out


BODY_MODES = [(190.0, 0.7, 0.11), (540.0, 0.4, 0.08), (1050.0, 0.25, 0.06)]   # fixed Hz: a body, not a partial set (Oaken's 180/520/1000 bands). Rings shorter than any note so it never outlives the fundamental (v1 had 0.22 s and stole the pitch)


def voice_upright(m: dict, hz: float, n: int, rate: int, s: np.ndarray, f: np.ndarray, phase: np.ndarray, seed: int,
                  bow_period_frames: int | None = None) -> np.ndarray:
    """bow_period_frames (HOLD only): a seeded noise segment of exactly that many internal
    frames, tiled, low-passed and fed into the loop as a bow (Oaken's bow exciter is filtered
    noise times pressure). Because the bow repeats with the loop period and the string is
    time-invariant at a fixed pitch, the held output is loop-periodic once the warm-up has
    passed, which is what lets the seam close: the house's 'periodic driver' strategy."""
    length = length_seconds(m)
    # pluck: two periods of noise, linearly decaying (Oaken), low-passed so the pick is not glass
    burst_len = int(2.0 * rate / f[0])
    noise = sk.Noise(seed).array(burst_len)
    noise *= np.linspace(1.0, 0.0, burst_len)
    noise = signal.lfilter(*signal.butter(2, 3000.0 / (rate / 2)), noise)
    excitation = np.zeros(n)
    excitation[:burst_len] = noise * 0.6
    # loop gain so the string alone reaches -60 dB a little after the amp envelope does
    loop_gain = min(0.9995, max(0.85, 10 ** (-3.0 / (3.0 * length * hz))))
    if bow_period_frames:
        seg = sk.Noise(seed + 7).array(bow_period_frames)
        bow = np.tile(seg, n // bow_period_frames + 1)[:n]
        bow = signal.lfilter(*signal.butter(2, 900.0 / (rate / 2)), bow) * 0.04
        excitation = excitation + bow
        loop_gain = 0.98                      # time constant = period/(1-g): 0.75 s at C2, so 4.5 s of warm-up is 6 time constants
    string = ks_string(excitation, rate / f, loop_gain, 2200.0, rate)
    string /= max(1e-9, np.max(np.abs(string)))
    # curing low-pass: from bright to "the fundamental and a little second" as s(t) falls
    cutoff = np.clip(f * (1.5 + 45.0 * s), 60.0, 8000.0)
    cured = closing_lowpass(string, cutoff, rate)
    body = sk.modal_ring(cured, 1.0, BODY_MODES, rate, compensate=True)
    body /= max(1e-9, np.max(np.abs(body)))
    src = 0.82 * cured + 0.18 * body
    return src


def voice_reduce(m: dict, hz: float, n: int, rate: int, s: np.ndarray, f: np.ndarray, phase: np.ndarray, seed: int) -> np.ndarray:
    ratio = 1.0                      # unison modulator: the distilled end state is the carrier alone
    index0 = 7.0                     # shape; Omega's default index 3 squared by its own bug reads 9
    index = index0 * s + 0.08
    mod = np.sin(2.0 * math.pi * phase * ratio)
    return np.sin(2.0 * math.pi * phase + index * mod)


def voice_belly(m: dict, hz: float, n: int, rate: int, s: np.ndarray, f: np.ndarray, phase: np.ndarray, seed: int, bite_detune_cents: float = 9.0) -> np.ndarray:
    sine = np.sin(2.0 * math.pi * phase)
    belly = sine + 0.3 * sine ** 3                                  # Overbite's sine shape
    detune = 2.0 ** (bite_detune_cents / 1200.0)
    bite = polyblep_saw(phase * detune, f * detune / rate)
    cutoff = np.clip(f * (2.0 + 28.0 * s), 80.0, 7000.0)
    bite = closing_lowpass(bite, cutoff, rate)
    bite_level = 0.65 * (0.25 + 0.75 * s)                           # the bite fades as the note settles
    return 0.75 * belly + bite_level * bite


VOICE_FN = {"UPRIGHT": voice_upright, "REDUCE": voice_reduce, "BELLY": voice_belly}


def render(voice: str, macros: dict, midi: int, rate: int = sk.INTERNAL, held: bool = False,
           seconds: float | None = None, hz_override: float | None = None, bite_detune_cents: float | None = None,
           bow_period_frames: int | None = None) -> np.ndarray:
    """The engine. Pure: same inputs, same samples. `held` renders the sustain state for HOLD:
    no slide, no amplitude decay, so the loop planner can cut a settled stretch."""
    m = {**MACROS, **macros}
    hz = hz_override if hz_override else hz_for(midi)
    length = length_seconds(m)
    total = seconds if seconds else 1.15 * length
    n = int(total * rate)
    s = settle_curve(n, rate, half_life_seconds(m))
    f, phase = pitch_track(hz, 0.5 if held else m["SLIDE"], n, rate)
    seed = sk.seed_for("KEEL", voice, midi)
    kwargs = {}
    if voice == "BELLY" and bite_detune_cents is not None:
        kwargs["bite_detune_cents"] = bite_detune_cents
    if voice == "UPRIGHT" and held and bow_period_frames:
        kwargs["bow_period_frames"] = bow_period_frames
    src = VOICE_FN[voice](m, hz, n, rate, s, f, phase, seed, **kwargs)
    src /= max(1e-9, np.max(np.abs(src))) / 0.8
    src = grit(src, m["GRIT"])
    sub = sub_layer(phase, f, s, m["WEIGHT"], rate) * np.minimum(1.0, source_envelope(src, rate))
    mix = src + sub
    if held:
        return mix
    return mix * amp_env(n, rate, length)


# ----------------------------------------------------------------- measurement

def pitch_fields(out: np.ndarray, ref_hz: float, start: float = 0.35, window: float = 0.25) -> dict:
    """The probe's pitch window sits after the slide and after most of the settle, with a
    25 Hz floor so C1 (32.7 Hz) and its sub (16 Hz, which should be gone) are in range."""
    hz, conf = pr.pitch(out, sk.RATE, lo_hz=25.0, hi_hz=1000.0, window_seconds=window, start_seconds=start)
    hz40, conf40 = pr.pitch(out, sk.RATE, lo_hz=40.0, hi_hz=1000.0, window_seconds=window, start_seconds=start)
    return {"pitch_hz_floor25": round(hz, 2), "conf25": round(conf, 3),
            "cents_from_ref_floor25": round(pr.cents(hz, ref_hz), 1) if hz > 0 else None,
            "pitch_hz_floor40": round(hz40, 2), "conf40": round(conf40, 3),
            "cents_from_ref_floor40": round(pr.cents(hz40, ref_hz), 1) if hz40 > 0 else None}


def finished(voice: str, macros: dict, midi: int) -> tuple[np.ndarray, np.ndarray]:
    raw = render(voice, macros, midi)
    return raw, sk.finish(raw, sk.MELODIC_LOUDNESS_TARGET)


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    R: dict = {"renders": [], "macro_activity_dB": {}, "alias_proxy": {}, "weight_octave": [],
               "slide": [], "low_end": [], "loops": [], "render_seconds_python": {}}

    # 1. voices at three notes
    for voice in VOICES:
        t0 = time.time()
        for tune in (0.25, 0.5, 0.75):
            midi = midi_for(tune)
            raw, out = finished(voice, {"TUNE": tune}, midi)
            name = f"{voice}_tune{tune:.2f}_midi{midi}"
            sk.write_wav(os.path.join(out_dir, name + ".wav"), out)
            rep = pr.report(out, sk.RATE, name, ref_hz=hz_for(midi))
            rep.update(pitch_fields(out, hz_for(midi)))
            rep["raw_peak_internal"] = round(float(np.max(np.abs(raw))), 3)
            R["renders"].append(rep)
        R["render_seconds_python"][voice] = round((time.time() - t0) / 3, 2)

    # 2. every macro changes the sound (both extremes, levelled, log-spectral distance)
    for voice in VOICES:
        R["macro_activity_dB"][voice] = {}
        midi = midi_for(0.5)
        for macro in MACROS:
            if macro == "TUNE":
                continue
            lo = sk.finish(render(voice, {macro: 0.0}, midi), sk.MELODIC_LOUDNESS_TARGET)
            hi = sk.finish(render(voice, {macro: 1.0}, midi), sk.MELODIC_LOUDNESS_TARGET)
            R["macro_activity_dB"][voice][macro] = round(pr.macro_activity(lo, hi), 2)
            sk.write_wav(os.path.join(out_dir, f"{voice}_{macro}_0.wav"), lo)
            sk.write_wav(os.path.join(out_dir, f"{voice}_{macro}_1.wav"), hi)

    # 3. aliasing at the brightest corner: GRIT 1, SETTLE 1 (slowest settle = brightest longest), top note
    for voice in VOICES:
        raw = render(voice, {"GRIT": 1.0, "SETTLE": 1.0, "TUNE": 1.0}, midi_for(1.0))
        R["alias_proxy"][voice] = round(pr.alias_proxy(raw, sk.INTERNAL), 6)

    # 4. WEIGHT and the octave question: at which WEIGHT does the detector (and so the ear,
    #    probably) read the sub as the note?
    midi = midi_for(0.5)
    for voice in ("REDUCE", "UPRIGHT"):
        for w in (0.0, 0.4, 0.7, 1.0):
            out = sk.finish(render(voice, {"WEIGHT": w}, midi), sk.MELODIC_LOUDNESS_TARGET)
            pf = pitch_fields(out, hz_for(midi))
            fw, pw = signal.welch(out, sk.RATE, nperseg=16384)
            pf.update({"voice": voice, "WEIGHT": w,
                       "energy_share_below_45Hz": round(float(pw[fw < 45].sum() / max(1e-12, pw.sum())), 4)})
            R["weight_octave"].append(pf)
            if voice == "REDUCE":
                sk.write_wav(os.path.join(out_dir, f"REDUCE_WEIGHT_{w:.1f}.wav"), out)

    # 5. SLIDE: pitch early (first 80 ms) vs late, at SLIDE 0 (from below) and 1 (from above)
    for sl in (0.0, 1.0, 0.5):
        out = sk.finish(render("REDUCE", {"SLIDE": sl, "WEIGHT": 0.0}, midi), sk.MELODIC_LOUDNESS_TARGET)
        early, _ = pr.pitch(out, sk.RATE, lo_hz=25.0, hi_hz=1000.0, window_seconds=0.06, start_seconds=0.01)
        late, _ = pr.pitch(out, sk.RATE, lo_hz=25.0, hi_hz=1000.0, window_seconds=0.25, start_seconds=0.4)
        R["slide"].append({"SLIDE": sl, "early_cents": round(pr.cents(early, hz_for(midi)), 1),
                           "late_cents": round(pr.cents(late, hz_for(midi)), 1)})
        sk.write_wav(os.path.join(out_dir, f"REDUCE_SLIDE_{sl:.1f}.wav"), out)

    # 6. the low end: C1 with full WEIGHT; the sub is 16 Hz and should be gone by law, and the
    #    root should still read C1 after the 20 Hz high-pass
    for voice in VOICES:
        out = sk.finish(render(voice, {"TUNE": 0.0, "WEIGHT": 1.0}, midi_for(0.0)), sk.MELODIC_LOUDNESS_TARGET)
        f_, p_ = signal.welch(out, sk.RATE, nperseg=16384)
        below20 = float(p_[f_ < 20].sum() / max(1e-12, p_.sum()))
        pf = pitch_fields(out, hz_for(midi_for(0.0)))
        pf.update({"voice": voice, "midi": midi_for(0.0), "energy_share_below_20Hz": round(below20, 6),
                   "peak": round(pr.peak(out), 3)})
        R["low_end"].append(pf)
        sk.write_wav(os.path.join(out_dir, f"{voice}_C1_WEIGHT1.wav"), out)

    # 7. HOLD: the settled state as a whole-period loop (Mercury's plan), seam measured
    #    against the real preceding cycle. Period of the whole = 2/f0 because of the sub, so
    #    plan in periods of f0/2. BELLY's bite detune is retuned so its beat fits the loop
    #    (an integer number of beats per loop), the house "retune until the seam closes".
    hz = hz_for(midi)
    loop_frames = sk.whole_period_loop_frames(hz / 2.0, sk.RATE, 3.0)
    periods = round(loop_frames / sk.RATE * hz / 2.0)
    hz_fit = periods * sk.RATE / loop_frames * 2.0
    loop_s = loop_frames / sk.RATE
    for voice in ("REDUCE", "BELLY", "UPRIGHT"):
        warm = 4.5 if voice == "UPRIGHT" else 2.5
        kw = {}
        if voice == "BELLY":
            nominal = hz_fit * (2 ** (9.0 / 1200.0) - 1.0)          # beat Hz of the bite against the belly
            beats = max(1, round(nominal * loop_s))
            fitted_detune_hz = beats / loop_s
            kw["bite_detune_cents"] = 1200.0 * math.log2(1.0 + fitted_detune_hz / hz_fit)
        if voice == "UPRIGHT":
            kw["bow_period_frames"] = loop_frames * sk.OVERSAMPLE
        raw = render(voice, {}, midi, held=True, seconds=warm + loop_s + 0.25, hz_override=hz_fit, **kw)
        y = sk.decimate4(sk.band_limit(raw, sk.INTERNAL))
        y = sk.dc_block(y)
        loop_start = int(warm * sk.RATE)
        seam = sk.seam_error(y[: loop_start + loop_frames], loop_start)
        loop = sk.level_to(y[loop_start: loop_start + loop_frames], sk.MELODIC_LOUDNESS_TARGET)
        sk.write_wav(os.path.join(out_dir, f"{voice}_LOOP_x4.wav"), np.tile(loop, 4))
        fl, pl = signal.welch(loop, sk.RATE, nperseg=16384)
        above100 = float(pl[fl > 100].sum() / max(1e-12, pl.sum()))
        lp_hz, lp_conf = pr.pitch(loop, sk.RATE, lo_hz=40.0, hi_hz=1000.0, window_seconds=0.5, start_seconds=0.1)
        R["loops"].append({"voice": voice, "frames": loop_frames, "seconds": round(loop_s, 4),
                           "loop_energy_share_above_100Hz": round(above100, 4),
                           "loop_pitch_hz_floor40": round(lp_hz, 2), "loop_pitch_cents": round(pr.cents(lp_hz, hz_fit), 1),
                           "hz_fit": round(hz_fit, 3), "cents_off": round(pr.cents(hz_fit, hz), 2),
                           "bite_detune_cents_fitted": round(kw.get("bite_detune_cents", 0.0), 3),
                           "seam_error": seam, "passes_MAX_SEAM_ERROR": bool(seam < sk.MAX_SEAM_ERROR)})

    with open(os.path.join(out_dir, "report.json"), "w") as fh:
        json.dump(R, fh, indent=2)
    print(json.dumps(R, indent=2))
    print(f"\nwrote {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "keel_out"))
