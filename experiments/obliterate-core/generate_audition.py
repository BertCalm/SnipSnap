#!/usr/bin/env python3
"""Render an honest first OBLITERATE-core listening comparison.

The inputs and stock comparison are actual Kotlin renders. The new core is
a Python signal prototype, not a registered SnipSnap effect. Raw PCM preserves
input/output gain; matched PCM applies one linked stereo gain before deriving
the unchanged mono fold. No shimmer, seamless-loop or listening claim is made.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import importlib.util
import json
import math
import platform
import subprocess
import sys
import time
import wave
from pathlib import Path
from typing import Any

import numpy as np
import scipy
from scipy import signal

RATE = 44_100
AMOUNTS = (.35, .70, 1.0)
DEFAULT_BPM = 120.0
DEFAULT_DIVISION = 16
DEFAULT_SEED = 20261023
MATCH_TARGET = .03
MATCH_PEAK_CEILING = .99
HIT_GAP_SECONDS = .25
END_SILENCE_SECONDS = .20
MAX_PHRASE_SECONDS = 8.0
PCM_SCALE = 8_388_608
CONCEPT_URL = (
    "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/"
    "Docs%2Fspecs%2F2026-04-05-epic-chains-fx-design.md"
)
CHAIN = ["Octave fuzz", "Pitched captured fragments", "Stereo chop"]


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sample_hash(audio: np.ndarray) -> str:
    """Canonical frame-major float64 little-endian fingerprint, before PCM."""
    return hashlib.sha256(np.asarray(audio, dtype="<f8").tobytes(order="C")).hexdigest()


def read_pcm24(path: Path) -> tuple[np.ndarray, dict[str, int]]:
    with wave.open(str(path), "rb") as wav:
        info = dict(frames=wav.getnframes(), channels=wav.getnchannels(),
                    sampleRate=wav.getframerate(), bitDepth=wav.getsampwidth() * 8)
        if info["sampleRate"] != RATE or info["bitDepth"] != 24 or info["channels"] not in (1, 2):
            raise ValueError(f"Expected native PCM24 mono/stereo: {path}: {info}")
        raw = np.frombuffer(wav.readframes(info["frames"]), dtype=np.uint8).reshape(-1, 3)
    values = (raw[:, 0].astype(np.int32) | (raw[:, 1].astype(np.int32) << 8) |
              (raw[:, 2].astype(np.int32) << 16))
    values = (values ^ 0x800000) - 0x800000
    return values.astype(np.float64).reshape(info["frames"], info["channels"]) / PCM_SCALE, info


def write_pcm24(path: Path, audio: np.ndarray) -> None:
    check_audio(audio, str(path))
    # Fail rather than hide output growth in a raw listening file.
    if np.max(np.abs(audio), initial=0) > 1.0:
        raise ValueError(f"Raw PCM would clip: {path}")
    pcm = np.clip(np.rint(audio * PCM_SCALE), -PCM_SCALE, PCM_SCALE - 1).astype(np.int32)
    flat = pcm.ravel()
    raw = np.column_stack((flat & 255, (flat >> 8) & 255, (flat >> 16) & 255)).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(audio.shape[1])
        wav.setsampwidth(3)
        wav.setframerate(RATE)
        wav.writeframes(raw.tobytes())


def stereo(audio: np.ndarray) -> np.ndarray:
    if audio.shape[1] == 2:
        return audio
    return np.repeat(audio, 2, axis=1)


def mono_fold(audio: np.ndarray) -> np.ndarray:
    if audio.shape[1] != 2:
        raise ValueError("Mono fold requires the exported stereo pair")
    return ((audio[:, 0] + audio[:, 1]) / 2.0)[:, None]


def check_audio(audio: np.ndarray, label: str, allow_silence: bool = False) -> None:
    if audio.ndim != 2 or audio.shape[1] not in (1, 2) or audio.shape[0] == 0:
        raise ValueError(f"Invalid frame-major mono/stereo shape: {label}: {audio.shape}")
    if not np.isfinite(audio).all():
        raise ValueError(f"Non-finite audio: {label}")
    if not allow_silence and np.max(np.abs(audio), initial=0) <= 1e-12:
        raise ValueError(f"Silent audio: {label}")


def meter(audio: np.ndarray) -> float:
    """Float64 Python port of Loudness.of's formula, not bit-exact Kotlin/LUFS."""
    mono = audio.mean(axis=1)
    a = 1.0 - math.exp(-2.0 * math.pi * 120.0 / RATE)
    low = signal.lfilter([a], [1.0, -(1.0 - a)], mono)
    filtered = mono - .7 * low
    square_sum = np.concatenate(([0.0], np.cumsum(filtered * filtered)))
    window, hop = int(.2 * RATE), int(.05 * RATE)
    best = 0.0
    for start in range(0, len(filtered), hop):
        end = min(len(filtered), start + window)
        best = max(best, float((square_sum[end] - square_sum[start]) / (end - start)))
        if end == len(filtered):
            break
    return math.sqrt(max(0.0, best))


def db(value: float) -> float:
    return float(20.0 * math.log10(max(abs(value), 1e-12)))


def match_gain(audio: np.ndarray) -> tuple[float, bool]:
    gain = MATCH_TARGET / max(meter(audio), 1e-12)
    peak = float(np.max(np.abs(audio), initial=0))
    guarded = peak * gain > MATCH_PEAK_CEILING
    if guarded:
        gain = MATCH_PEAK_CEILING / peak
    return gain, guarded


def numeric_metrics(audio: np.ndarray) -> dict[str, Any]:
    check_audio(audio, "metric input")
    pair = stereo(audio)
    fold = mono_fold(pair)
    peak = float(np.max(np.abs(pair), initial=0))
    rms = float(np.sqrt(np.mean(pair * pair)))
    channel_level = (meter(pair[:, :1]) + meter(pair[:, 1:])) / 2.0
    correlation = float(np.corrcoef(pair[:, 0], pair[:, 1])[0, 1])
    if not math.isfinite(correlation):
        correlation = 1.0 if np.array_equal(pair[:, 0], pair[:, 1]) else 0.0
    frequency, power = signal.welch(fold[:, 0], RATE, nperseg=min(4096, len(fold)))
    total_power = float(power.sum())
    centroid = float(np.sum(frequency * power) / total_power) if total_power > 0 else 0.0
    near_nyquist = float(power[frequency >= .95 * RATE / 2].sum() / total_power) if total_power else 0.0
    return {
        "finite": True, "peak": peak, "peakDbfs": db(peak), "rms": rms,
        "rmsDbfs": db(rms), "crestDb": db(peak / max(rms, 1e-12)),
        "channelMeans": [float(x) for x in pair.mean(axis=0)],
        "channelRms": [float(x) for x in np.sqrt(np.mean(pair * pair, axis=0))],
        "loudnessProxy": meter(pair), "loudnessDb": db(meter(pair)),
        "foldLoudnessDb": db(meter(fold)),
        "foldLossDb": db(meter(fold) / max(channel_level, 1e-12)),
        "correlation": correlation, "spectralCentroidHz": centroid,
        "energyAbove95PercentNyquistRatio": near_nyquist,
        "nearNyquistMeaning": "Python Welch output-band energy ratio; not a measured alias-rejection ratio",
        "maxAdjacentSampleDelta": float(np.max(np.abs(np.diff(pair, axis=0)), initial=0)),
        "lastFramePeak": float(np.max(np.abs(pair[-1]), initial=0)),
        "meter": "Float64 Python port of SnipSnap Loudness.of: 120 Hz one-pole low cut retaining 30% body, loudest 200 ms RMS, 50 ms hop; not bit-exact Kotlin or LUFS",
    }


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=repo, text=True).strip()


def load_core(path: Path):
    spec = importlib.util.spec_from_file_location("obliterate_core_prototype", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot import prototype: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def json_safe(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        return json_safe(dataclasses.asdict(value))
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [json_safe(item) for item in value]
    if isinstance(value, np.ndarray):
        return json_safe(value.tolist())
    if isinstance(value, np.generic):
        return json_safe(value.item())
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Non-finite diagnostic/manifest number")
    if value is None or isinstance(value, (int, float, str, bool)):
        return value
    raise ValueError(f"Unserializable diagnostic value: {type(value)}")


def shape_constants(core) -> dict[str, Any]:
    if hasattr(core, "SHAPE_CONSTANTS"):
        return json_safe(core.SHAPE_CONSTANTS)
    return {key: json_safe(value) for key, value in vars(core).items()
            if key.isupper() and isinstance(value, (int, float, str, tuple, list, dict))}


def source_hashes(repo: Path, input_manifest: dict, core_path: Path) -> dict[str, str]:
    names = set(input_manifest["sourceFileHashes"])
    for path in (Path(__file__).resolve(), core_path.resolve()):
        names.add(path.relative_to(repo).as_posix())
    validator = Path(__file__).with_name("validate_audition.py")
    if validator.is_file():
        names.add(validator.resolve().relative_to(repo).as_posix())
    for name, expected in input_manifest["sourceFileHashes"].items():
        if file_hash(repo / name) != expected:
            raise ValueError(f"Kotlin input source changed after source generation: {name}")
    return {name: file_hash(repo / name) for name in sorted(names)}


def phrase_audio(hit: np.ndarray, phrase_frames: int, hit_starts: list[int]) -> np.ndarray:
    out = np.zeros((phrase_frames, 2), dtype=np.float64)
    hit = stereo(hit)
    for start in hit_starts:
        if start + len(hit) > phrase_frames:
            raise ValueError("Phrase would truncate a printed hit")
        out[start:start + len(hit)] = hit
    return out


def checked_input(root: Path, entry: dict) -> np.ndarray:
    path = root / entry["file"]
    if file_hash(path) != entry["sha256"]:
        raise ValueError(f"Input WAV hash mismatch: {path}")
    audio, info = read_pcm24(path)
    for name in ("frames", "channels", "sampleRate"):
        if info[name] != entry[name]:
            raise ValueError(f"Input WAV {name} mismatch: {path}")
    check_audio(audio, str(path))
    return audio


def benchmark_host() -> dict[str, Any]:
    cpu = platform.processor()
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.is_file():
        cpu = next((line.split(":", 1)[1].strip() for line in cpuinfo.read_text().splitlines()
                    if line.startswith("model name")), cpu)
    return {
        "implementation": "Python prototype on this host; not Kotlin, Android or phone timing",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "platform": platform.platform(), "cpu": cpu,
        "loadPolicy": "unisolated host timing; concurrent jobs and scheduling are uncontrolled; indicative prototype cost only",
        "timingMethod": "time.perf_counter around render_core only; each render repeated to verify fresh-state determinism; file writing and metrics excluded",
    }


def generate(args: argparse.Namespace) -> dict[str, Any]:
    repo = Path(__file__).resolve().parents[2]
    inputs = Path(args.inputs).resolve() if args.inputs else repo / "testkit/obliterate-core-inputs"
    output = Path(args.output).resolve() if args.output else repo / "testkit/obliterate-core-audition"
    core_path = Path(args.module).resolve() if args.module else Path(__file__).with_name("prototype.py")
    input_path = inputs / "input-baseline-manifest.json"
    input_manifest = json.loads(input_path.read_text())
    if len(input_manifest["sources"]) != 4:
        raise ValueError("This first proof expects four actual Kotlin input sources")
    core = load_core(core_path)
    output.mkdir(parents=True, exist_ok=True)
    hashes_before = source_hashes(repo, input_manifest, core_path)
    commit = git(repo, "rev-parse", "HEAD")
    tree = git(repo, "rev-parse", "HEAD^{tree}")
    dirty = bool(git(repo, "status", "--porcelain", "--untracked-files=normal"))
    input_manifest_hash = file_hash(input_path)
    constants = shape_constants(core)
    records, all_clips, timing_rows = [], [], []
    isolation = None

    def render(source_id: str, audio: np.ndarray, amount: float, stages: int = 3) -> tuple[np.ndarray, dict]:
        settings = core.Settings(amount=amount, bpm=args.bpm, division=args.division, seed=args.seed)
        times = []
        rendered = []
        diagnostic = None
        for _ in range(2):
            start = time.perf_counter()
            result = core.render_core(audio, settings, stages=stages)
            times.append((time.perf_counter() - start) * 1000)
            sample = np.asarray(result.audio, dtype=np.float64)
            check_audio(sample, f"{source_id} {amount} stage{stages}")
            rendered.append(sample)
            diagnostic = json_safe(result.diagnostics)
        if not np.array_equal(rendered[0], rendered[1]):
            raise ValueError(f"Fresh core renders differ: {source_id} {amount} stage{stages}")
        timing_rows.append({"sourceId": source_id, "amount": amount, "stages": stages,
                            "inputFrames": len(audio), "outputFrames": len(rendered[0]),
                            "firstMilliseconds": times[0], "repeatMilliseconds": times[1]})
        return rendered[0], {"settings": json_safe(settings), "diagnostics": diagnostic,
                             "shapeConstants": constants, "stages": CHAIN[:stages],
                             "freshStateDeterminismVerified": True}

    for source in input_manifest["sources"]:
        source_id = source["id"]
        dry = checked_input(inputs, source["raw"])
        input_snapshot = sample_hash(dry)
        budget = int(source["coreBudget"]["frames"])
        if budget != len(dry) + round(.75 * RATE):
            raise ValueError(f"Unexpected input/core tail contract: {source_id}")
        candidates = [{"id": f"{source_id}-original", "label": "Original", "amount": 0.0,
                       "audio": dry, "recipe": {"kind": "original", "stages": [], "inputGain": 1.0}}]
        for amount in AMOUNTS:
            audio, recipe = render(source_id, dry, amount)
            if len(audio) > budget:
                raise ValueError(f"Core exceeded 750 ms tail budget: {source_id}")
            candidates.append({"id": f"{source_id}-core-amt{round(amount * 100):03}",
                               "label": f"Core · AMT {round(amount * 100)}%", "amount": amount,
                               "audio": audio, "recipe": {"kind": "core", **recipe}})
        baselines = []
        for baseline in source["baselines"]:
            if not any(abs(baseline["amount"] - amount) < 1e-6 for amount in AMOUNTS):
                raise ValueError("Unexpected baseline amount")
            audio = checked_input(inputs, baseline)
            baselines.append({"id": f"{source_id}-stock-amt{round(baseline['amount'] * 100):03}",
                              "label": f"Stock Grains + Gate · AMT {round(baseline['amount'] * 100)}%",
                              "amount": baseline["amount"], "audio": audio,
                              "recipe": {"kind": "stock-baseline", "implementation": "actual Kotlin Grains.render → Gate.chop",
                                         "stockBehaviorDisclosure": "legacy Grains normalizes and returns mono; native-pitch comparison; no new octave fuzz or captured-history pitch/reverse mechanism",
                                         **baseline["recipe"], "validation": baseline.get("validation", {}),
                                         "inputWavSha256": baseline["sha256"]}})
        if len(baselines) != 3:
            raise ValueError(f"Expected all three actual stock baselines: {source_id}")
        stage_candidates = []
        if source_id == "percussion":
            for stage in (1, 2):
                audio, recipe = render(source_id, dry, .7, stages=stage)
                if len(audio) > budget:
                    raise ValueError("Cumulative stage exceeded source tail budget")
                stage_candidates.append({"id": f"{source_id}-stage-{stage}",
                                         "label": " → ".join(CHAIN[:stage]), "amount": .7,
                                         "audio": audio, "recipe": {"kind": "cumulative-stage", **recipe}})
        if sample_hash(dry) != input_snapshot:
            raise ValueError("Prototype mutated its source input")
        longest = max(len(candidate["audio"]) for candidate in candidates + baselines + stage_candidates)
        if longest > budget:
            raise ValueError("Comparison would exceed its disclosed common tail budget")
        spacing = math.ceil((longest / RATE + HIT_GAP_SECONDS) * 4) / 4
        starts = [int(round(index * spacing * RATE)) for index in range(args.hits)]
        frames = starts[-1] + longest + round(END_SILENCE_SECONDS * RATE)
        if frames / RATE > MAX_PHRASE_SECONDS:
            raise ValueError("Audition phrase exceeds eight seconds; choose --hits 1 explicitly")

        def export(candidate: dict) -> dict:
            phrase = phrase_audio(candidate["audio"], frames, starts)
            gain, guarded = match_gain(phrase)
            matched = phrase * gain
            raw_mono = mono_fold(phrase)
            matched_mono = mono_fold(matched)
            if not np.array_equal(matched_mono[:, 0], (matched[:, 0] + matched[:, 1]) / 2):
                raise ValueError("Mono is not the unchanged matched stereo fold")
            paths = {
                "stereo": f"audio/{candidate['id']}-matched-stereo.wav",
                "mono": f"audio/{candidate['id']}-matched-mono.wav",
                "rawStereo": f"audio/{candidate['id']}-raw-stereo.wav",
                "rawMono": f"audio/{candidate['id']}-raw-mono.wav",
            }
            arrays = {"stereo": matched, "mono": matched_mono, "rawStereo": phrase, "rawMono": raw_mono}
            for field, path in paths.items():
                write_pcm24(output / path, arrays[field])
            raw_metrics, matched_metrics = numeric_metrics(phrase), numeric_metrics(matched)
            recipe = {
                "experiment": "OBLITERATE_CORE", "version": 1,
                "scope": "octave fuzz → pitched captured fragments → stereo chop only; shimmer excluded",
                "source": source["sourceRecipe"], "sourceInputWavSha256": source["raw"]["sha256"],
                "sourceAudioSha256": input_snapshot, "inputGain": 1.0,
                "amount": candidate["amount"], "bpm": args.bpm, "division": args.division,
                "prototypeSeed": args.seed if candidate["recipe"]["kind"] in ("core", "cumulative-stage") else None,
                "sourceFrames": len(dry), "printedHitFrames": len(candidate["audio"]),
                "printedHitAudioSha256": sample_hash(candidate["audio"]),
                "phraseFrames": frames, "hitStartsFrames": starts,
                "hitStartsSeconds": [frame / RATE for frame in starts],
                "statePolicy": (
                    "two fresh core renders verified equal; one complete printed hit copied at each start; no shared effect state or overlapping tails"
                    if candidate["recipe"]["kind"] in ("core", "cumulative-stage") else
                    "actual Kotlin WAV verified by input manifest hash; one complete printed hit copied at each start, without overlapping tails"
                ),
                "timingPolicy": "phase and seed restart with each printed hit; explicit fixed BPM and division, no live tempo sync",
                "levelPolicy": "raw keeps source/processor gain; matched applies one linked stereo gain per clip then unchanged mono average; no independent mono matching",
                "matchedGain": gain, "matchedGainDb": db(gain), "matchedPeakGuardApplied": guarded,
                "sourceChannels": dry.shape[1], "printedHitChannels": candidate["audio"].shape[1],
                **candidate["recipe"],
            }
            row = {
                "id": candidate["id"], "label": candidate["label"], "amount": candidate["amount"],
                **paths, **{field + "Sha256": file_hash(output / path) for field, path in paths.items()},
                "durationSeconds": frames / RATE, "sampleRate": RATE, "channels": 2,
                "sourceChannels": dry.shape[1], "printedHitFrames": len(candidate["audio"]),
                "peakDbfs": matched_metrics["peakDbfs"], "loudnessDb": matched_metrics["loudnessDb"],
                "foldLossDb": matched_metrics["foldLossDb"], "correlation": matched_metrics["correlation"],
                "metrics": matched_metrics, "rawMetrics": raw_metrics, "recipe": recipe,
            }
            all_clips.append(row)
            return row

        clip_rows = [export(candidate) for candidate in candidates]
        baseline_rows = [export(candidate) for candidate in baselines]
        records.append({
            "id": source_id, "name": source["name"],
            "description": source.get("description", "Actual Kotlin source with preserved input level."),
            "sourceRecipe": source["sourceRecipe"], "sourceChannels": dry.shape[1],
            "sourceDurationSeconds": len(dry) / RATE,
            "hitStartsSeconds": [frame / RATE for frame in starts],
            "clips": clip_rows, "baselineClips": baseline_rows,
        })
        if stage_candidates:
            isolation = {"sourceId": source_id, "amount": .7, "includesFull": True,
                         "description": "Three cumulative stages at 70%; the third is the exact main core 70% clip. Raw preserves gain; matched isolates timbral changes with one stereo gain per clip.",
                         "clips": [export(candidate) for candidate in stage_candidates] + [clip_rows[2]]}
        print(f"OBLITERATE core: {source_id}, {len(candidates)} main + {len(baselines)} stock, {frames / RATE:.2f}s", flush=True)

    if isolation is None:
        raise ValueError("The actual percussion phrase is required for cumulative stage comparisons")
    if hashes_before != source_hashes(repo, input_manifest, core_path):
        raise ValueError("A selected render source changed during generation")
    if input_manifest_hash != file_hash(input_path) or commit != git(repo, "rev-parse", "HEAD"):
        raise ValueError("Input manifest or Git HEAD changed during generation")
    manifest = {
        "schema": 1, "title": "OBLITERATE core · First listening proof", "experiment": "OBLITERATE_CORE",
        "description": "A Python octave fuzz → pitched captured fragments → stereo chop prototype against actual Kotlin Grains + Gate. Shimmer is excluded; this is not the complete OBLITERATE board.",
        "repository": "https://github.com/BertCalm/SnipSnap", "conceptReference": CONCEPT_URL,
        "sourceCommit": commit, "sourceTree": tree, "workingTreeDirty": dirty,
        "commitMeaning": "Git HEAD at render time; actual selected file hashes identify any working-tree changes",
        "sourceHashes": hashes_before,
        "sourceHashScope": "actual Kotlin source generator dependency list plus Python prototype, audition generator and validator; selected sources, not an exhaustive repository fingerprint",
        "inputManifestSha256": input_manifest_hash,
        "inputProvenance": input_manifest,
        "status": "first Python core proof; Kotlin landing and owner listening pending",
        "chain": CHAIN, "amounts": [0.0, *AMOUNTS],
        "settings": {"bpm": args.bpm, "division": args.division, "seed": args.seed},
        "shapeConstants": constants,
        "audio": {"encoding": "PCM", "bitDepth": 24, "sampleRate": RATE},
        "levelPolicy": {
            "default": "matched", "raw": "unchanged source/processor gain; prototype never receives audition matching",
            "matched": "one gain per full stereo phrase to 0.03 house-formula Python meter target, guarded at 0.99 sample peak; mono derived afterwards unchanged",
            "meter": "Float64 Python formula port, not bit-exact Kotlin or LUFS",
            "mono": "(L+R)/2 after the selected raw/matched stereo gain; independent PCM24 rounding only",
        },
        "listeningNotes": [
            "Compare Original, the new core and actual stock Grains + Gate at the same amount. Switch raw/matched to separate dynamics from timbre.",
            "The stock Grains baseline normalizes and returns mono by its existing implementation; the prototype preserves input gain and stereo. Raw exposes that difference.",
            "Every source uses identical phrase lengths and hit starts across all comparisons. Each printed tail is complete, followed by at least 250 ms silence before another hit.",
            "BPM, division and seed are explicit. Printed hits restart phase/state; this proof does not demonstrate live tempo sync or seamless loops.",
            "Only the octave-fuzz/fragments/chop core is represented. Shimmer and full OBLITERATE board integration are excluded.",
        ],
        "sources": records, "isolation": isolation,
        "benchmark": {**benchmark_host(), "renders": timing_rows},
        "numericProbePolicy": "finite/peak/DC/correlation/fold/centroid/output-Nyquist-energy measurements in Python; pitch and decay preservation are not requirements of this destructive effect; no listening verdict inferred",
    }
    manifest = json_safe(manifest)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, allow_nan=False) + "\n")
    total_bytes = sum((output / clip[field]).stat().st_size for clip in all_clips
                      for field in ("stereo", "mono", "rawStereo", "rawMono"))
    receipt = {"uniqueClips": len(all_clips), "wavFiles": len(all_clips) * 4,
               "wavBytes": total_bytes, "sourceCommit": commit, "workingTreeDirty": dirty}
    (output / "generation-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2), flush=True)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs")
    parser.add_argument("--output")
    parser.add_argument("--module", help="Python prototype module; defaults to adjacent prototype.py")
    parser.add_argument("--hits", type=int, choices=(1, 2), default=2)
    parser.add_argument("--bpm", type=float, default=DEFAULT_BPM)
    parser.add_argument("--division", type=int, default=DEFAULT_DIVISION)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args()
    generate(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
