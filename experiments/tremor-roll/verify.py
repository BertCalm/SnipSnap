#!/usr/bin/env python3
"""Check regenerated R3 audio against the published listening set and its probes.

Requires NumPy. The browser and independent diagnostic-copy grain audit are
archived evidence; this command verifies the fresh audio render, not those jobs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import wave

import numpy as np


HERE = Path(__file__).resolve().parent
RATE = 44100


def read(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as stream:
        assert (stream.getnchannels(), stream.getframerate(), stream.getsampwidth()) == (1, RATE, 2), path
        return np.frombuffer(stream.readframes(stream.getnframes()), "<i2").astype(float) / 32768


def pitch_error(samples: np.ndarray, midi: int) -> float:
    samples = samples[:16384]
    assert len(samples) >= 512
    n = 65536
    power = np.abs(np.fft.rfft(samples * np.hanning(len(samples)), n)) ** 2
    low = max(1, int(20 * n / RATE))
    index = low + int(np.argmax(power[low:]))
    assert 1 <= index < len(power) - 1
    values = np.log(power[index - 1:index + 2] + 1e-30)
    offset = .5 * (values[0] - values[2]) / (values[0] - 2 * values[1] + values[2])
    hz = (index + offset) * RATE / n
    target = 440 * 2 ** ((midi - 69) / 12)
    return float(1200 * np.log2(hz / target))


def loudness(samples: np.ndarray) -> float:
    filtered = np.empty_like(samples)
    low, coefficient = 0., 1 - math.exp(-2 * math.pi * 120 / RATE)
    for i, sample in enumerate(samples):
        low += coefficient * (sample - low)
        filtered[i] = sample - .7 * low
    powers = []
    for start in range(0, len(filtered), 2205):
        end = min(len(filtered), start + 8820)
        powers.append(float(np.mean(filtered[start:end] ** 2)))
        if end == len(filtered):
            break
    return math.sqrt(max(powers))


def guard(samples: np.ndarray, case_id: str) -> None:
    assert len(samples) > 0 and np.isfinite(samples).all(), case_id
    assert float(np.max(abs(samples))) < .99, case_id
    assert abs(float(samples.mean())) < .005, case_id


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render-dir", type=Path, default=HERE / "build/reproduced/rendered")
    args = parser.parse_args()
    rendered = args.render_dir.resolve()
    manifest = json.loads((rendered / "render-manifest.json").read_text())
    expected = json.loads((HERE / "expected-audio.json").read_text())
    assert manifest["phase"] == expected["phase"] == "tremor_roll_shaker_r3"
    assert manifest["candidateSha256"] == expected["candidateSha256"]
    assert {c["id"] for c in manifest["cases"]} == set(expected["clips"])
    recipes = {r["id"]: r for r in manifest["recipes"]}
    errors, held_errors = [], []
    for case in manifest["cases"]:
        case_id = case["id"]
        pin = expected["clips"][case_id]
        for field in ("nativeFloatSha256", "matchedFloatSha256", "frames", "loopStartFrame", "presetId", "variant", "touch"):
            assert case[field] == pin[field], (case_id, field)
        path = rendered / case["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == pin["wavSha256"], case_id
        samples = read(path)
        guard(samples, case_id)
        assert len(samples) == case["frames"] and abs(loudness(samples) - .03) < .001, case_id
        assert case["railHits"] == 0 and case["mechanicsMatch"], case_id
        diagnostic = case["shakerDiagnostics"]
        assert diagnostic["droppedPackets"] == diagnostic["pendingAtEnd"] == 0, case_id
        midi = recipes[case["presetId"]]["midi"]
        error = pitch_error(samples, midi)
        assert abs(error) < 25, (case_id, error)
        errors.append(abs(error))
        if case["loopStartFrame"] >= 0:
            assert case["loopStartSeconds"] == case["loopStartFrame"] / RATE
            held = pitch_error(samples[case["loopStartFrame"]:], midi)
            assert abs(held) < 25 and case["constructedSeam"] < .001, (case_id, held)
            held_errors.append(abs(held))

    check = manifest["validation"]
    assert check["deterministicCandidateRepeats"] == check["mechanicsPairsExact"] == 9
    assert check["otherVoiceBuffersExact"] == 20
    assert len(check["matrix"]) == 54
    for case in check["matrix"]:
        samples = read(rendered / case["file"])
        guard(samples, case["id"])
        assert case["rails"] == 0 and case["mechanicsMatch"]
        diagnostic = case["shakerDiagnostics"]
        assert diagnostic["droppedPackets"] == diagnostic["pendingAtEnd"] == 0
        assert diagnostic.get("grainTraceDroppedRows", 0) == 0
        assert 0 <= diagnostic.get("extraArrivalMaxMs", 0) <= 60
        error = pitch_error(samples, case["midi"])
        assert abs(error) < 25, (case["id"], error)
        errors.append(abs(error))
        if case["loopStartFrame"] >= 0:
            held = pitch_error(samples[case["loopStartFrame"]:], case["midi"])
            assert abs(held) < 25 and case["seam"] < .001, (case["id"], held)
            held_errors.append(abs(held))
    report = {
        "status": "passed", "publishedWavHashesExact": len(expected["clips"]),
        "nativeAndMatchedFloatPinsExact": len(expected["clips"]), "matrixCases": 54,
        "deterministicRepeats": 9, "mechanicalPairsExact": 9, "otherVoicesExact": 20,
        "worstAttackCents": max(errors), "worstHeldCents": max(held_errors),
        "limit": "Fresh render verification; does not rerun browser QA or the independent 536-grain diagnostic-copy audit.",
    }
    (rendered.parent / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
