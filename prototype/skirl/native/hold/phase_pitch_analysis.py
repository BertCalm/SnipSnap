#!/usr/bin/env python3
"""Diagnose low-note HOLD onsets using delivered PCM16, without changing DSP.

The known-target autocorrelation peak lookup is diagnostic. It does not replace
the unconstrained JVM Pitch.detect decision or establish listening acceptance.
NumPy/SciPy are the existing prototype requirements.
"""
import argparse
import hashlib
import json
import wave
from pathlib import Path

import numpy as np
from scipy import signal


def frames(seconds, rate):
    # Match the JVM Float multiplication used for its detector's window bounds.
    return int(np.float32(np.float32(seconds) * np.float32(rate)))


def analyze(pack, voice):
    identifier = f"{voice}-low-soft-loop"
    path = pack / "production" / f"{identifier}.wav"
    recipe = json.loads((pack / "recipes" / f"{identifier}.patch.json").read_text())
    with wave.open(str(path)) as wav:
        assert (wav.getnchannels(), wav.getsampwidth()) == (1, 2)
        rate = wav.getframerate()
        cycle = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(float) / 32768
    target = 440 * 2 ** ((recipe["midi"] - 69) / 12)
    repeated = np.tile(cycle, 8)
    period = rate / target
    n = frames(.30, rate)
    minimum = int(rate / 1200)
    maximum = int(rate / 40)
    phases = []
    for start in np.arange(0, .60, .05):
        begin = frames(start, rate)
        window = repeated[begin:begin + n]
        autocorrelation = signal.correlate(window, window, mode="full", method="fft")[n - 1:]
        autocorrelation /= window @ window
        global_peak = float(max(autocorrelation[minimum:maximum + 1]))
        global_lag = int(np.argmax(autocorrelation[minimum:maximum + 1])) + minimum
        peaks = signal.find_peaks(autocorrelation[minimum + 1:maximum])[0] + minimum + 1
        nearby = [int(lag) for lag in peaks if abs(lag - period) < .12 * period]
        peak = min(nearby, key=lambda lag: abs(lag - period)) if nearby else None
        row = {"phaseSeconds": float(start), "windowSeconds": .30,
               "globalCorrelationMaximum": global_peak, "globalMaximumLag": global_lag}
        if peak is not None:
            curve = autocorrelation[peak - 1] - 2 * autocorrelation[peak] + autocorrelation[peak + 1]
            shift = np.clip(.5 * (autocorrelation[peak - 1] - autocorrelation[peak + 1]) / curve,
                            -.5, .5) if abs(curve) > 1e-12 else 0
            candidate = rate / (peak + shift)
            row.update({"diagnosticRootCandidateHz": float(candidate),
                        "diagnosticRootCandidateCents": float(1200 * np.log2(candidate / target)),
                        "rootPeakCorrelation": float(autocorrelation[peak]),
                        "rootPeakShareOfMaximum": float(autocorrelation[peak] / global_peak),
                        "passesDetectorRelativePeakGate": bool(autocorrelation[peak] > .85 * global_peak)})
        phases.append(row)
    # A repeating cycle has discrete Fourier-series lines. Use the exact cycle,
    # without a Hann window, to avoid confusing window leakage with those lines.
    frequencies = np.fft.rfftfreq(len(cycle), 1 / rate)
    powers = abs(np.fft.rfft(cycle)) ** 2
    total = float(powers[1:].sum())
    carrier = round(target * len(cycle) / rate)
    strongest = np.argsort(powers[1:])[::-1][:10] + 1
    return {"voice": voice, "wav": f"production/{identifier}.wav",
            "wavSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "midi": recipe["midi"], "velocity": recipe["velocity"],
            "frames": len(cycle), "rate": rate, "targetHz": target,
            "cycleSeconds": len(cycle) / rate,
            "targetNearestPeriodicLineHz": float(frequencies[carrier]),
            "targetNearestPeriodicLineCents": float(1200 * np.log2(frequencies[carrier] / target)),
            "targetNearestPeriodicLinePowerShare": float(powers[carrier] / total),
            "powerShare50To85Hz": float(powers[(frequencies > 50) & (frequencies < 85)].sum() / total),
            "strongestPeriodicLines": [{"bin": int(i), "hz": float(frequencies[i]),
                                         "centsFromTarget": float(1200 * np.log2(frequencies[i] / target)),
                                         "totalPowerShare": float(powers[i] / total)} for i in strongest],
            "phaseWindows": phases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, help="Generated skirl-hold-audition directory")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    result = {"measurement": "Python NumPy/SciPy analysis of delivered PCM16, not JVM Pitch.detect.",
              "interpretation": "Known-target local-peak lookup diagnoses refusal; it is not an unconstrained pitch estimate or listening acceptance.",
              "voices": [analyze(arguments.pack, voice) for voice in ("roll", "whirr")]}
    arguments.output.write_text(json.dumps(result, indent=2) + "\n")
    for voice in result["voices"]:
        rejected = [round(row["phaseSeconds"], 2) for row in voice["phaseWindows"]
                    if not row.get("passesDetectorRelativePeakGate", False)]
        print(voice["voice"], "relative-gate refusals", rejected,
              "strongest periodic line", voice["strongestPeriodicLines"][0])


if __name__ == "__main__":
    main()
