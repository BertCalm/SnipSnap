#!/usr/bin/env python3
"""Reproduce coefficients, fixtures and parity evidence for the Kotlin core port.

This preserves the heard Python processor and its published artifacts. Kotlin
parity replays exactly the Float32 samples produced by WavReader; the old Python
PCM24 divisor is a separate, reported decoder-boundary comparison. No threshold
combines that input difference with the accuracy of the new signal port.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import importlib.util
import json
import math
import platform
from pathlib import Path
import subprocess
import sys

import numpy as np
import scipy
from scipy import signal

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = REPO / "testkit/obliterate-kotlin-audition"
RATE = 44_100
SEED = 20261023
AMOUNTS = (.35, .7, 1.0)
SEEDS = (0, 1, SEED, 2**32 - 1)
# Same-input Float32 limits tightened after actual compact/full-matrix errors
# measured max1.49e-8 and RMS<=7.18e-11. A failure needs diagnosis, not looser bounds.
MAX_ERROR_LIMIT = 2e-7
RMS_ERROR_LIMIT = 2e-9


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def samples_sha(audio: np.ndarray, dtype: str = "<f8") -> str:
    return hashlib.sha256(np.asarray(audio, dtype=dtype).tobytes(order="C")).hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=REPO, text=True).strip()


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValueError(path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def modules():
    return load(HERE / "prototype.py", "obliterate_port_python_reference"), load(
        HERE / "generate_audition.py", "obliterate_port_audition_helpers")


def dump(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def boundary_pcm(path: Path, helpers) -> tuple[np.ndarray, np.ndarray, dict]:
    heard, info = helpers.read_pcm24(path)
    integer = np.rint(heard * 8_388_608).astype(np.int32)
    # Match Int/Float division in WavReader.kt, including its negative clamp.
    kotlin = np.maximum(integer.astype(np.float32) / np.float32(8_388_607), np.float32(-1))
    return np.asarray(kotlin, dtype="<f4"), heard, info


def provenance(extra: tuple[Path, ...] = ()) -> dict:
    names = {HERE / "prototype.py", HERE / "generate_audition.py", Path(__file__).resolve(), *extra}
    validator = HERE / "validate_kotlin_audition.py"
    if validator.is_file():
        names.add(validator)
    for name in ("ObliterateCore.kt", "ObliteratePcg64.kt"):
        path = REPO / "synth/src/main/kotlin/com/snipsnap/synth" / name
        if path.is_file():
            names.add(path)
    for name in ("KotlinPortRenderer.kt", "port.init.gradle"):
        path = HERE / name
        if path.is_file():
            names.add(path)
    return {
        "sourceCommit": git("rev-parse", "HEAD"), "sourceTree": git("rev-parse", "HEAD^{tree}"),
        "workingTreeDirty": bool(git("status", "--porcelain", "--untracked-files=normal")),
        "sourceHashes": {path.relative_to(REPO).as_posix(): sha(path) for path in sorted(names)},
        "sourceHashScope": "immutable Python processor and retained audition helpers, this parity pipeline, and present Kotlin core/RNG/runner sources; historical input-source hashes are not requirements on current production",
    }


def constants() -> dict:
    taps = signal.firwin(161, 1 / 8, window=("kaiser", 10.0))
    rng_cases = []
    for seed in SEEDS:
        generator = np.random.PCG64(seed)
        state = generator.state
        raw = generator.random_raw(16)
        rng_cases.append({
            "seed": seed, "initialStateHex": f"{state['state']['state']:032x}",
            "incrementHex": f"{state['state']['inc']:032x}",
            "seedSequenceUint64Hex": [f"{int(value):016x}" for value in
                                      np.random.SeedSequence(seed).generate_state(4, dtype=np.uint64)],
            "rawUint64Hex": [f"{int(value):016x}" for value in raw],
            "unitDoubles": [(int(value) >> 11) * 2**-53 for value in raw],
        })
    return {
        "schema": 1, "numpyVersion": np.__version__, "scipyVersion": scipy.__version__,
        "fir": {
            "formula": "scipy.signal.firwin(161,0.125,window=('kaiser',10.0)); scale by up after design",
            "baseTaps": taps.tolist(), "baseTapsSha256Float64LE": samples_sha(taps),
            "up8": {"up": 8, "down": 1, "nPrePad": 1, "nPreRemove": 81, "nPostPad": 0, "gain": 8,
                     "outputFrames": "8*nInput; after upfirdn retain [81:81+8*nInput]"},
            "down8": {"up": 1, "down": 8, "nPrePad": 8, "nPreRemove": 11, "nPostPad": 0, "gain": 1,
                       "outputFrames": "ceil(nInput/8); after upfirdn retain [11:11+ceil(nInput/8)]"},
            "padPolicy": "constant zero; whole-array call; compensated center80; up y[j]=8*sum(x[k]*h[j-8*k+80]); down y[i]=sum(x[k]*h[8*i-k+80])",
        },
        "sos": {
            "fuzzHighpass30At352800": signal.butter(2, 30, btype="highpass", fs=352800, output="sos").tolist(),
            "fuzzLowpass9000At352800": signal.butter(4, 9000, btype="lowpass", fs=352800, output="sos").tolist(),
            "fragmentHighpass20At44100": signal.butter(2, 20, btype="highpass", fs=44100, output="sos").tolist(),
        },
        "sosSemantics": "scipy.signal.sosfilt Double DFII-transposed, zero initial states, normalized a0=1; HP30 thenLP9k beforedownsample, HP20 afterfragment mix",
        "rng": {"name": "NumPy PCG64 XSL RR128/64, not PCG64DXSM", "uintToUnit": "(rawUnsigned64 >>>11)*2^-53", "cases": rng_cases},
    }


def write_f32(path: Path, audio: np.ndarray) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(np.asarray(audio, dtype="<f4").tobytes(order="C"))
    return sha(path)


def render(core, audio: np.ndarray, amount: float, stages: int, bpm=120.0, division=16, seed=SEED):
    settings = core.Settings(amount=amount, bpm=bpm, division=division, seed=seed)
    first = core.render_core(audio.astype(np.float64), settings, stages=stages)
    second = core.render_core(audio.astype(np.float64), settings, stages=stages)
    if not np.array_equal(first.audio, second.audio):
        raise ValueError("Python reference is not deterministic")
    return first, settings


def export_fixtures(core, resource: Path) -> dict:
    frames = 16_384
    t = np.arange(frames, dtype=np.float64) / RATE
    env = np.exp(-t * 4) * (1 + .25 * np.cos(2 * np.pi * 3.7 * t))
    left = env * (.27 * np.sin(2 * np.pi * 197 * t) + .16 * np.sin(2 * np.pi * 593 * t))
    right = env * (.24 * np.sin(2 * np.pi * 263 * t + .31) + .14 * np.cos(2 * np.pi * 947 * t))
    left[0] = .72
    right[0] = -.49
    stereo = np.column_stack((left, right)).astype("<f4")
    mono = (.52 * np.sin(2 * np.pi * 311 * t) * np.exp(-t * 9))[:, None].astype("<f4")
    input_audio = {"stereo": stereo, "mono": mono}
    for name, audio in input_audio.items():
        write_f32(resource / f"input-{name}.f32", audio)
    rows = []
    selected = [("stereo-fuzz-100", "stereo", 1., 1, 120., 16, SEED),
                ("stereo-fragments-100", "stereo", 1., 2, 120., 16, SEED),
                *[(f"stereo-full-{round(amount * 100):03}", "stereo", amount, 3, 120., 16, SEED) for amount in AMOUNTS],
                ("mono-full-035", "mono", .35, 3, 120., 16, SEED),
                ("stereo-odd-clock", "stereo", 1., 3, 123., 32, 2**32 - 1)]
    for name, source, amount, stages, bpm, division, seed in selected:
        audio = input_audio[source]
        result, _ = render(core, audio, amount, stages, bpm, division, seed)
        output_file = f"expected/{name}.f32"
        output_hash = write_f32(resource / output_file, result.audio)
        rows.append({
            "id": name, "inputFile": f"input-{source}.f32", "inputChannels": audio.shape[1],
            "inputFrames": len(audio), "inputSha256": sha(resource / f"input-{source}.f32"),
            "amount": amount, "bpm": bpm, "division": division, "seed": seed, "stages": stages,
            "outputFile": output_file, "outputChannels": result.audio.shape[1], "outputFrames": len(result.audio),
            "outputSha256": output_hash, "grainEvents": result.diagnostics["grain_events"],
            "guardGain": result.diagnostics["guard_gain"], "clockStepSeconds": result.diagnostics["clock_step_seconds"],
        })
    schedules = []
    for seed in SEEDS:
        settings = core.Settings(amount=1., bpm=120., division=16, seed=seed)
        schedules.append({"inputFrames": frames, **dataclasses.asdict(settings),
                          "grainEvents": [dataclasses.asdict(event) for event in core._schedule(frames, settings)]})
    settings = core.Settings(amount=1., bpm=240., division=32, seed=SEED)
    schedules.append({"inputFrames": 2000, **dataclasses.asdict(settings),
                      "grainEvents": [dataclasses.asdict(event) for event in core._schedule(2000, settings)]})
    manifest = {
        "schema": 1, "sampleRate": RATE, "inputEncoding": "frame-major little-endian IEEE754 Float32",
        "referencePolicy": "Input Float32 promoted exactly toFloat64 for immutable Python processor, result rounded toFloat32 at the Snip output boundary",
        "pythonProcessorSha256": sha(HERE / "prototype.py"), "generatorSha256": sha(Path(__file__).resolve()),
        "numpyVersion": np.__version__, "scipyVersion": scipy.__version__,
        "cases": rows, "scheduleCases": schedules, "rngReference": constants()["rng"],
        "sampleMaxErrorLimit": MAX_ERROR_LIMIT, "sampleRmsErrorLimit": RMS_ERROR_LIMIT,
        "thresholdPolicy": "measured Float32 port tolerance; compact and full matrix max1.49e-8/RMS<=7.18e-11; not a bit-exact claim",
    }
    dump(resource / "reference-manifest.json", manifest)
    return manifest


def export_reference(output: Path, fixtures: Path | None) -> dict:
    core, helpers = modules()
    before = provenance()
    old_path = REPO / "testkit/obliterate-core-inputs/input-baseline-manifest.json"
    old = json.loads(old_path.read_text())
    heard_path = REPO / "testkit/obliterate-core-audition/manifest.json"
    heard = json.loads(heard_path.read_text()) if heard_path.is_file() else None
    if heard and sha(HERE / "prototype.py") != heard["sourceHashes"]["experiments/obliterate-core/prototype.py"]:
        raise ValueError("The heard Python processor changed")
    dump(output / "reference-constants.json", constants())
    sources, cases = [], []
    for source in old["sources"]:
        source_id = source["id"]
        wav = old_path.parent / source["raw"]["file"]
        if sha(wav) != source["raw"]["sha256"]:
            raise ValueError("A retained heard source WAV changed")
        boundary, old_decode, info = boundary_pcm(wav, helpers)
        source_path = f"reference-inputs/{source_id}.f32"
        source_hash = write_f32(output / source_path, boundary)
        decode_difference = boundary.astype(np.float64) - old_decode
        sources.append({
            "id": source_id, "name": source["name"], "sourceRecipe": source["sourceRecipe"],
            "inputFile": source_path, "inputChannels": info["channels"], "inputFrames": info["frames"],
            "inputSha256": source_hash,
            "sourceWavFile": str(Path("../obliterate-core-inputs") / source["raw"]["file"]),
            "sourceWavSha256": source["raw"]["sha256"],
            "decoderBoundaryMaxError": float(np.max(np.abs(decode_difference))),
            "decoderBoundaryRmsError": float(np.sqrt(np.mean(decode_difference**2))),
            "inputDecodePolicy": "actual Kotlin WavReader Int24/8388607f, Float32 arithmetic, clamp at-1; historical Python wasInt24/8388608 Float64",
        })
        for amount in AMOUNTS:
            for stages in (1, 2, 3):
                name = f"{source_id}-a{round(amount * 100):03}-s{stages}"
                result, settings = render(core, boundary, amount, stages)
                historical = core.render_core(old_decode, settings, stages=stages)
                difference = result.audio - historical.audio
                output_file = f"python-f32/{name}.f32"
                cases.append({
                    "id": name, "sourceId": source_id, "amount": amount, "bpm": 120., "division": 16,
                    "seed": SEED, "stages": stages,
                    "pythonOutputFile": output_file, "pythonOutputSha256": write_f32(output / output_file, result.audio),
                    "outputChannels": result.audio.shape[1], "outputFrames": len(result.audio),
                    "pythonDoubleOutputSha256": samples_sha(result.audio),
                    "pythonMetrics": helpers.numeric_metrics(result.audio),
                    "diagnostics": result.diagnostics,
                    "historicalDecodeOutputMaxError": float(np.max(np.abs(difference))),
                    "historicalDecodeOutputRmsError": float(np.sqrt(np.mean(difference**2))),
                })
    after = provenance()
    if before != after:
        # Concurrent Kotlin files may be added while Python references are made;
        # only the immutable processor/generator inputs affect these renders.
        for name in ("prototype.py", "generate_audition.py", "kotlin_reference.py"):
            key = (HERE / name).relative_to(REPO).as_posix()
            if before["sourceHashes"][key] != after["sourceHashes"][key]:
                raise ValueError("A Python render source changed during export")
    manifest = {
        "schema": 1, "sampleRate": RATE, **after,
        "pythonProcessorSha256": sha(HERE / "prototype.py"),
        "historicalInputManifestSha256": sha(old_path),
        "historicalInputBaseCommit": old["baseGitCommit"],
        "historicalInputProvenancePolicy": "retained heard input WAV hashes are verified; old379 production file hashes describe their historical source generation and are not asserted against the current repository",
        "sampleBoundary": "both implementations receive the exact same little-endian Float32 Snip samples; Python promotes toDouble; Kotlin returns Float32",
        "sources": sources, "cases": cases,
        "thresholds": {"sampleMaxAbsolute": MAX_ERROR_LIMIT, "sampleRmsAbsolute": RMS_ERROR_LIMIT,
                       "eventFrameAbsolute": 1e-9, "eventParameterAbsolute": 1e-12},
        "runtime": {"python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__},
    }
    dump(output / "reference-inputs.json", manifest)
    if fixtures:
        fixture = export_fixtures(core, fixtures)
        print(f"Wrote {len(fixture['cases'])} compact Kotlin fixtures under {fixtures}")
    print(f"Wrote {len(cases)} actual-source reference cases under {output}")
    return manifest


def field(row: dict, snake: str):
    parts = snake.split("_")
    camel = parts[0] + "".join(part.title() for part in parts[1:])
    if snake in row:
        return row[snake]
    return row[camel]


def event_errors(expected: list[dict], actual: list[dict]) -> dict:
    if len(expected) != len(actual):
        return {"pass": False, "expectedCount": len(expected), "actualCount": len(actual), "reason": "event count differs"}
    frame_error, parameter_error, exact = 0., 0., True
    for left, right in zip(expected, actual):
        for name in ("onset_frame", "length_frames", "reverse"):
            exact &= field(left, name) == field(right, name)
        for name in ("source_first_frame", "source_last_frame"):
            frame_error = max(frame_error, abs(field(left, name) - field(right, name)))
        for name in ("pitch_ratio", "semitones", "balance"):
            parameter_error = max(parameter_error, abs(field(left, name) - field(right, name)))
    return {"pass": exact and frame_error <= 1e-9 and parameter_error <= 1e-12,
            "count": len(expected), "exactDiscreteFields": bool(exact),
            "maxSourceFrameError": frame_error, "maxParameterError": parameter_error}


def compare(output: Path, results_path: Path, wavs: bool) -> dict:
    core, helpers = modules()
    reference = json.loads((output / "reference-inputs.json").read_text())
    if sha(HERE / "prototype.py") != reference["pythonProcessorSha256"]:
        raise ValueError("Python processor changed since reference export")
    results = json.loads(results_path.read_text())
    if sha(output / "reference-inputs.json") != results["inputManifestSha256"]:
        raise ValueError("Kotlin renderer used a different reference input manifest")
    for name, expected_hash in results["sourceHashes"].items():
        if sha(REPO / name) != expected_hash:
            raise ValueError(f"Kotlin render source changed after rendering: {name}")
    actual_rows = {row["id"]: row for row in results["cases"]}
    sources = {row["id"]: row for row in reference["sources"]}
    if set(actual_rows) != {row["id"] for row in reference["cases"]}:
        raise ValueError("Kotlin renderer case inventory differs from the reference")
    actual_sources = {row["id"]: row for row in results["sources"]}
    for source_id, source in sources.items():
        rendered_source = actual_sources[source_id]
        for name in ("inputSha256", "inputChannels", "inputFrames", "sourceWavSha256"):
            if rendered_source[name] != source[name]:
                raise ValueError(f"Kotlin rendered different source input: {source_id} {name}")
        if not rendered_source["actualWavReaderFloat32BitEquality"]:
            raise ValueError("Kotlin renderer did not verify the actual WavReader boundary")
    rows, cache = [], {}
    for expected in reference["cases"]:
        source = sources[expected["sourceId"]]
        actual = actual_rows[expected["id"]]
        for name in ("sourceId", "amount", "bpm", "division", "seed", "stages"):
            if actual[name] != expected[name]:
                raise ValueError(f"Kotlin render setting differs from reference: {expected['id']} {name}")
        if (actual["frames"], actual["channels"], actual["sampleRate"]) != (
                expected["outputFrames"], expected["outputChannels"], RATE):
            raise ValueError("Kotlin output format differs from reference")
        python_file = output / expected["pythonOutputFile"]
        if sha(python_file) != expected["pythonOutputSha256"]:
            raise ValueError("Python reference output changed")
        kotlin_file = results_path.parent / actual.get("outputFile", actual.get("file", ""))
        if "outputSha256" in actual and sha(kotlin_file) != actual["outputSha256"]:
            raise ValueError("Kotlin output hash differs from its renderer manifest")
        expected_shape = (expected["outputFrames"], expected["outputChannels"])
        py = np.fromfile(python_file, dtype="<f4").reshape(expected_shape)
        kt = np.fromfile(kotlin_file, dtype="<f4").reshape(expected_shape)
        if not np.isfinite(kt).all():
            raise ValueError("Non-finite Kotlin output")
        difference = kt.astype(np.float64) - py.astype(np.float64)
        maximum = float(np.max(np.abs(difference)))
        rms = float(np.sqrt(np.mean(difference**2)))
        diag = actual["diagnostics"]
        geometry = event_errors(expected["diagnostics"]["grain_events"],
                                diag.get("grainEvents", diag.get("grain_events", [])))
        tail = field(diag, "tail_frames") == 33075
        clock = abs(field(diag, "clock_step_seconds") - expected["diagnostics"]["clock_step_seconds"]) <= 1e-15
        deterministic = actual.get("deterministicRerender", actual.get("deterministic", actual.get("determinismVerified", False)))
        scope = all(not field(diag, name) for name in ("shimmer", "feedback", "seamless_loop_claim", "streaming_causal"))
        scope &= bool(field(diag, "grain_reads_before_event_onset"))
        clock &= field(diag, "clock_origin_frame") == 0 and field(diag, "compensated_fir_latency_frames") == 0
        passed = maximum <= MAX_ERROR_LIMIT and rms <= RMS_ERROR_LIMIT and geometry["pass"] and tail and clock and deterministic and scope
        metric_py, metric_kt = helpers.numeric_metrics(py), helpers.numeric_metrics(kt)
        row = {
            "id": expected["id"], "sourceId": expected["sourceId"], "amount": expected["amount"], "stages": expected["stages"],
            "pass": bool(passed), "sampleMaxAbsolute": maximum, "sampleRmsAbsolute": rms,
            "errorRmsDbfs": helpers.db(rms), "differingFloat32Samples": int(np.count_nonzero(difference)),
            "differingFloat32BitPatterns": int(np.count_nonzero(kt.view(np.uint32) != py.view(np.uint32))),
            "geometry": geometry, "exactTailFrames": bool(tail), "sameClock": bool(clock),
            "coreOnlyScopeVerified": bool(scope),
            "kotlinDeterminismVerified": bool(deterministic),
            "pythonMetrics": metric_py, "kotlinMetrics": metric_kt,
            "peakDifference": metric_kt["peak"] - metric_py["peak"],
            "loudnessDbDifference": metric_kt["loudnessDb"] - metric_py["loudnessDb"],
            "kotlinOutputSha256": sha(kotlin_file),
            "historicalDecodeOutputMaxError": expected["historicalDecodeOutputMaxError"],
            "historicalDecodeOutputRmsError": expected["historicalDecodeOutputRmsError"],
        }
        rows.append(row)
        cache[expected["id"]] = (py, kt)
    report = {
        "schema": 1, **provenance(), "referenceManifestSha256": sha(output / "reference-inputs.json"),
        "kotlinResultsManifestSha256": sha(results_path),
        "kotlinRenderProvenance": {name: results[name] for name in ("sourceCommit", "sourceTree", "workingTreeDirty", "sourceHashes")},
        "pythonReferenceProvenance": {name: reference[name] for name in ("sourceCommit", "sourceTree", "workingTreeDirty", "sourceHashes")},
        "samplePolicy": reference["sampleBoundary"],
        "thresholds": {"sampleMaxAbsolute": MAX_ERROR_LIMIT, "sampleRmsAbsolute": RMS_ERROR_LIMIT,
                       "eventFrameAbsolute": 1e-9, "eventParameterAbsolute": 1e-12,
                       "policy": "tightened after measured compact/full matrix parity; historical reference-export thresholds may have been provisional"},
        "historicalDecoderDifferencePolicy": "reported separately; never included in the same-input port tolerance",
        "allPass": all(row["pass"] for row in rows), "cases": rows,
        "maximumSampleError": max(row["sampleMaxAbsolute"] for row in rows),
        "maximumRmsError": max(row["sampleRmsAbsolute"] for row in rows),
    }
    dump(output / "parity-report.json", report)
    if wavs and report["allPass"]:
        export_wavs(output, reference, rows, cache, helpers)
    print(json.dumps({key: report[key] for key in ("allPass", "maximumSampleError", "maximumRmsError")}, indent=2))
    return report


def export_wavs(output: Path, reference: dict, parity_rows: list[dict], audio: dict, helpers) -> None:
    """Use the previous player schema with a Python reference beside Kotlin."""
    source_records, isolation = [], None
    ref_rows = {row["id"]: row for row in reference["cases"]}
    parity = {row["id"]: row for row in parity_rows}
    wav_count = 0
    for source in reference["sources"]:
        selected = [row for row in reference["cases"] if row["sourceId"] == source["id"] and row["stages"] == 3]
        stage_rows = [ref_rows[f"percussion-a070-s{stage}"] for stage in (1, 2)] if source["id"] == "percussion" else []
        maximum_frames = max(row["outputFrames"] for row in selected + stage_rows)
        spacing = math.ceil((maximum_frames / RATE + .25) * 4) / 4
        starts = [0, round(spacing * RATE)]
        frames = starts[-1] + maximum_frames + round(.2 * RATE)

        def export(row: dict, implementation: int) -> dict:
            nonlocal wav_count
            name = ("python" if implementation == 0 else "kotlin")
            hit = audio[row["id"]][implementation].astype(np.float64)
            phrase = helpers.phrase_audio(hit, frames, starts)
            gain, guarded = helpers.match_gain(phrase)
            matched = phrase * gain
            pair = {"stereo": matched, "mono": helpers.mono_fold(matched),
                    "rawStereo": phrase, "rawMono": helpers.mono_fold(phrase)}
            paths = {key: f"audio/{row['id']}-{name}-{key}.wav" for key in pair}
            for key, value in pair.items():
                helpers.write_pcm24(output / paths[key], value)
                wav_count += 1
            raw_metrics, metrics = helpers.numeric_metrics(phrase), helpers.numeric_metrics(matched)
            return {
                "id": f"{row['id']}-{name}", "label": f"{'Python reference' if implementation == 0 else 'Kotlin port'} · AMT {round(row['amount'] * 100)}%",
                "amount": row["amount"], "implementation": name, "stages": row["stages"],
                **paths, **{key + "Sha256": sha(output / path) for key, path in paths.items()},
                "durationSeconds": frames / RATE, "sampleRate": RATE, "channels": 2,
                "peakDbfs": metrics["peakDbfs"], "foldLossDb": metrics["foldLossDb"], "correlation": metrics["correlation"],
                "metrics": metrics, "rawMetrics": raw_metrics,
                "recipe": {"settings": {key: row[key] for key in ("amount", "bpm", "division", "seed")},
                           "stages": row["stages"], "implementation": name,
                           "inputFloat32Sha256": source["inputSha256"], "printedHitFrames": len(hit),
                           "phraseFrames": frames, "hitStartsFrames": starts, "matchedGain": gain,
                           "matchedPeakGuardApplied": guarded, "parity": parity[row["id"]],
                           "levelPolicy": "one linked matched stereo gain per implementation clip, then unchanged mono fold; raw preserves processor gain",
                           "scope": "octave fuzz → captured fragments → stereo chop only; shimmer excluded"},
            }
        kotlin = [export(row, 1) for row in selected]
        python = [export(row, 0) for row in selected]
        source_records.append({"id": source["id"], "name": source["name"],
                               "description": "The same actual Kotlin WavReader Float32 input replayed through both implementations.",
                               "clips": kotlin, "baselineClips": python})
        if stage_rows:
            isolation = {"sourceId": source["id"], "includesFull": True,
                         "description": "Cumulative stages at 70% on the percussion phrase. Python and Kotlin share the exact input samples.",
                         "clips": [export(row, 1) for row in stage_rows] + [kotlin[1]],
                         "referenceClips": [export(row, 0) for row in stage_rows] + [python[1]]}
    dump(output / "manifest.json", {
        "schema": 1, "title": "OBLITERATE core · Kotlin port comparison", **provenance(),
        "description": "Same-input Python/Kotlin comparison for the approved octave-fuzz, captured-fragments and stereo-chop core; shimmer excluded.",
        "amounts": list(AMOUNTS), "settings": {"bpm": 120., "division": 16, "seed": SEED},
        "sources": source_records, "isolation": isolation,
        "parityReport": "parity-report.json", "referenceManifest": "reference-inputs.json",
        "audio": {"encoding": "PCM", "bitDepth": 24, "sampleRate": RATE},
        "comparisonPolicy": "same actual Float32 Snip input for both; accepted Python artifact uses an independently reported older decoder boundary",
        "listeningStatus": "Kotlin listening pending; numerical parity is not a listening verdict",
        "wavFiles": wav_count,
    })
    print(f"Wrote {wav_count} Python/Kotlin raw/matched stereo/mono WAVs")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("constants", "fixtures", "export", "compare"))
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--wavs", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.command == "constants":
        dump(args.output / "reference-constants.json", constants())
    elif args.command == "fixtures":
        core, _ = modules()
        fixtures = args.fixtures or REPO / "synth/src/test/resources/obliterate-core"
        export_fixtures(core, fixtures)
    elif args.command == "export":
        export_reference(args.output, args.fixtures)
    else:
        results = args.results or args.output / "kotlin-port-results.json"
        report = compare(args.output, results, args.wavs)
        return 0 if report["allPass"] else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
