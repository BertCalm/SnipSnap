"""FACSIMILE synth: renders one drum strike from its numeric model. Never reads PCM.

A drum here is two things added together:

  partials  sine oscillators, each with a pitch that can fall exponentially from f0 to f1
            (a kick's or an 808's drop, a zap's sweep) or hold (a tom's or a bell's ring),
            and its own loudness curve over time.
  noise     26 bands of filtered noise, roughly a third of an octave each, each with its own
            loudness curve. This is the snap of a snare, the sizzle of a hat, a kick's click.

Every loudness curve is a list of decibel values at fixed times (GRID_MS) and is interpolated
in decibels between them, which is how drum decays behave (straight lines in dB).

Velocity and round robin are part of the render, as the owner's gates ask:
  velocity  softer strikes are quieter AND different: the noise tilts darker (by the model's
            measured or house tilt), decays a little faster, and a falling pitch falls less far.
  take      every take is a fresh strike: new noise, new partial phases (the lowest partial of
            a kick keeps its sine start so the punch is the same), small per-band level scatter
            sized from how much the reference's own repeats differed, a few cents of pitch.

Rendering runs at 4x the output rate, then band-limits and decimates, as the house does.
"""
from __future__ import annotations

import hashlib

import numpy as np
from scipy import signal

RATE = 44_100
OVERSAMPLE = 4
GRID_MS = [0, 1, 2, 3, 5, 7, 10, 14, 20, 28, 40, 55, 75, 100, 130, 170, 220, 280, 350, 440,
           550, 700, 850, 1000, 1200, 1400]
BAND_EDGES = np.geomspace(35, 17_000, 27)
FLOOR_DB = -120.0
WARM_S = 0.25        # noise lead-in for the band filters, discarded


def seed_for(*parts) -> int:
    return int.from_bytes(hashlib.sha256(":".join(map(str, parts)).encode()).digest()[:8],
                          "little")


def env_curve(env_db, n: int, rate: int, scale_t: float = 1.0) -> np.ndarray:
    """Linear interpolation in dB on the grid, then to amplitude. `scale_t` < 1 shortens it."""
    t_ms = np.arange(n) / rate * 1000 / scale_t
    db = np.interp(t_ms, GRID_MS, env_db, right=FLOOR_DB)
    return 10 ** (np.maximum(db, FLOOR_DB) / 20)


def glide(f0: float, f1: float, tau_s: float, n: int, rate: int, law: str = "exp"
          ) -> np.ndarray:
    """'exp' falls fast in Hz then settles (a kick body); 'logexp' falls in octaves (a zap)."""
    t = np.arange(n) / rate
    if tau_s <= 0 or abs(f0 - f1) < 1e-6:
        return np.full(n, f1)
    e = np.exp(-t / tau_s)
    if law == "logexp":
        return f1 * (f0 / f1) ** e
    return f1 + (f0 - f1) * e


def band_sos(lo: float, hi: float, rate: int):
    return signal.butter(3, [lo, hi], btype="bandpass", fs=rate, output="sos")


_SOS_CACHE: dict = {}


def render(model: dict, velocity: float = 1.0, take: int = 0, oversample: int = OVERSAMPLE,
           noise_seed: int | None = None) -> np.ndarray:
    """`noise_seed` (fitting only) swaps the noise for another recording of the same strike,
    leaving every other choice of the take alone, so the fit can average over recordings."""
    rate = RATE * oversample
    length = model["length_s"]
    n = int(length * rate)
    v = float(np.clip(velocity, 0.05, 1.0))
    soft = 1.0 - v                                   # 0 at full strike
    vel = model["velocity"]
    rr = model["round_robin"]
    rng = np.random.default_rng(seed_for("facsimile", model["id"], take))
    first_take = take == 0
    out = np.zeros(n)

    # Partials
    for i, p in enumerate(model["partials"]):
        cents = 0.0 if first_take else rng.normal(0, rr["pitch_cents"])
        detune = 2 ** (cents / 1200)
        depth = 1 - vel["glide_depth_loss"] * soft   # softer strike: the pitch falls less far
        f1 = p["f1"] * detune
        f0 = (f1 + (p["f0"] * detune - f1) * depth)
        tau = p["tau_s"] * (1 if first_take else 1 + rng.normal(0, rr["tau_spread"]))
        f = glide(f0, f1, tau, n, rate, p.get("law", "exp"))
        f = np.minimum(f, rate * 0.45)
        phase0 = p["phase"] if (first_take or p.get("lock_phase")) else rng.uniform(0, 2 * np.pi)
        ph = phase0 + 2 * np.pi * np.cumsum(f) / rate
        level_db = 0.0 if first_take else rng.normal(0, rr["band_sigma_db"])
        e = env_curve(np.asarray(p["env_db"]) + level_db, n, rate,
                      1 - vel["decay_loss"] * soft)
        out += np.sin(ph) * e

    # Noise bands
    # Each band filter runs over a lead-in first, discarded, so a narrow low band is already at
    # full level when the strike begins: started from silence, a 10 Hz-wide band takes 50 to
    # 100 ms to ring up, which is exactly when a drum's energy is.
    warm = int(WARM_S * rate)
    if noise_seed is not None:
        noise = np.random.default_rng(seed_for("facsimile", model["id"], "fit",
                                               noise_seed)).standard_normal(n + warm)
    elif first_take:
        noise = np.random.default_rng(seed_for("facsimile", model["id"], "noise0")
                                      ).standard_normal(n + warm)
    else:
        noise = rng.standard_normal(n + warm)
    centres = np.sqrt(BAND_EDGES[:-1] * BAND_EDGES[1:])
    for b, env_db in enumerate(model["noise_db"]):
        if max(env_db) <= FLOOR_DB + 1:
            continue
        lo, hi = BAND_EDGES[b], BAND_EDGES[b + 1]
        key = (lo, hi, rate)
        if key not in _SOS_CACHE:
            _SOS_CACHE[key] = band_sos(lo, hi, rate)
        y = signal.sosfilt(_SOS_CACHE[key], noise)[warm:]
        y /= np.sqrt(np.mean(y ** 2)) + 1e-12
        tilt = vel["tilt_db_per_oct"] * soft * np.log2(centres[b] / 1000.0)
        level_db = tilt + (0.0 if first_take else rng.normal(0, rr["band_sigma_db"]))
        out += y * env_curve(np.asarray(env_db) + level_db, n, rate,
                             1 - vel["decay_loss"] * soft)

    # Level: amplitude follows velocity squared (a quiet ghost note is about -12 dB at v=.5).
    # The groove's measured loudness of a soft hit already is its level, so the darker tilt
    # must not make it quieter on top: the tilt changes the character at the same energy.
    out *= v ** 2 * tilt_compensation(model, soft)
    # Strike onset: a 0.2 ms ramp so the first sample is not a step (a click we did not fit)
    ramp = int(0.0002 * rate)
    out[:ramp] *= np.linspace(0, 1, ramp)
    return finish(out, oversample)


def tilt_compensation(model: dict, soft: float) -> float:
    """Gain that restores a strike's energy after the velocity tilt, worked out from the model's
    own band and partial energies (the tilt only moves noise between bands)."""
    if soft <= 0:
        return 1.0
    # integrate each loudness curve at 1 ms steps along the synth's own dB interpolation
    t = np.arange(0, model["length_s"] * 1000, 1.0)

    def energy(env_db):
        return float(np.sum(10 ** (np.interp(t, GRID_MS, env_db) / 10)))
    centres = np.sqrt(BAND_EDGES[:-1] * BAND_EDGES[1:])
    tilt = model["velocity"]["tilt_db_per_oct"] * soft * np.log2(centres / 1000.0)
    band_e = np.array([energy(e) for e in model["noise_db"]])
    part_e = sum(energy(p["env_db"]) / 2 for p in model["partials"])
    before = band_e.sum() + part_e
    after = np.sum(band_e * 10 ** (tilt / 10)) + part_e
    return float(np.sqrt(before / max(after, 1e-30)))


def finish(x: np.ndarray, oversample: int) -> np.ndarray:
    """House output stage in Python: band-limit and decimate, remove DC, fade the tail 4 ms.
    Levelling is left to the caller: the replica keeps the reference loop's absolute level, so
    a whole groove rebuilt from replicas keeps the reference's mix balance."""
    if oversample > 1:
        x = signal.resample_poly(x, 1, oversample, window=("kaiser", 8.0))
    x = x - np.mean(x)
    x = signal.sosfilt(signal.butter(1, 20, btype="high", fs=RATE, output="sos"), x)
    fade = int(0.004 * RATE)
    x[-fade:] *= np.linspace(1, 0, fade)
    return x
