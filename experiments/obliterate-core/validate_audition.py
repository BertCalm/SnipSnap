#!/usr/bin/env python3
"""Independently validate printed OBLITERATE-core PCM and its provenance.

No audio is regenerated or edited. Quantized mono and matched-gain comparisons
allow only the error caused by independent PCM24 rounding, rather than a taste
tolerance. Fresh-state DSP determinism is verified by the generator/probe suite.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import wave

import numpy as np

RATE = 44_100
SCALE = 8_388_608
MODES = ("stereo", "mono", "rawStereo", "rawMono")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        assert wav.getframerate() == RATE and wav.getsampwidth() == 3, path
        assert wav.getnchannels() in (1, 2), path
        frames, channels = wav.getnframes(), wav.getnchannels()
        data = np.frombuffer(wav.readframes(frames), dtype=np.uint8).reshape(-1, 3)
    value = data[:, 0].astype(np.int32) + (data[:, 1].astype(np.int32) << 8) + (data[:, 2].astype(np.int32) << 16)
    return ((value ^ 0x800000) - 0x800000).reshape(frames, channels)


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=repo, text=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?")
    parser.add_argument("--inputs")
    parser.add_argument("--wav-baseline", help="Optional earlier path/hash JSON; changes are reported, not rejected")
    parser.add_argument("--require-current-head", action="store_true", help="Require render commit to equal the current checkout HEAD")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = Path(args.output).resolve() if args.output else repo / "testkit/obliterate-core-audition"
    inputs = Path(args.inputs).resolve() if args.inputs else repo / "testkit/obliterate-core-inputs"
    manifest = json.loads((output / "manifest.json").read_text())
    current_head = git(repo, "rev-parse", "HEAD")
    assert manifest["sourceTree"] == git(repo, "rev-parse", manifest["sourceCommit"] + "^{tree}")
    if args.require_current_head:
        assert manifest["sourceCommit"] == current_head
    for name, expected in manifest["sourceHashes"].items():
        assert sha(repo / name) == expected, name
    assert sha(inputs / "input-baseline-manifest.json") == manifest["inputManifestSha256"]
    kotlin = {source["id"]: source for source in manifest["inputProvenance"]["sources"]}
    core_ids, unique, file_hashes = set(), {}, {}
    for source in manifest["sources"]:
        assert len(source["clips"]) == 4 and len(source["baselineClips"]) == 3
        assert {round(clip["amount"], 2) for clip in source["clips"]} == {0, .35, .7, 1}
        assert {round(clip["amount"], 2) for clip in source["baselineClips"]} == {.35, .7, 1}
        source_rows = source["clips"] + source["baselineClips"]
        assert len({row["recipe"]["phraseFrames"] for row in source_rows}) == 1
        assert len({tuple(row["recipe"]["hitStartsFrames"]) for row in source_rows}) == 1
        for row in source_rows:
            unique[row["id"]] = (source["id"], row)
            if row["recipe"]["kind"] == "core":
                core_ids.add(row["id"])
    isolation = manifest["isolation"]
    assert isolation["includesFull"] and len(isolation["clips"]) == 3
    assert isolation["clips"][-1]["id"] in core_ids
    for row in isolation["clips"]:
        old = unique.get(row["id"])
        if old:
            assert old[1] == row
        unique[row["id"]] = (isolation["sourceId"], row)
    assert len(unique) == 30

    max_fold_error = 0.0
    max_gain_error = 0.0
    raw_peak, matched_peak = 0, 0
    for source_id, row in unique.values():
        recipe = row["recipe"]
        arrays = {}
        for mode in MODES:
            path = output / row[mode]
            assert path.resolve().is_relative_to(output.resolve())
            actual_hash = sha(path)
            assert actual_hash == row[mode + "Sha256"], path
            file_hashes[row[mode]] = actual_hash
            arrays[mode] = read(path)
            assert arrays[mode].shape == (recipe["phraseFrames"], 1 if mode in ("mono", "rawMono") else 2)
            assert np.max(np.abs(arrays[mode]), initial=0) > 0, path
        for stereo_key, mono_key in (("stereo", "mono"), ("rawStereo", "rawMono")):
            pair, fold = arrays[stereo_key], arrays[mono_key]
            # Two independent 24-bit quantizations can differ by one mono LSB.
            error = np.max(np.abs(2 * fold[:, 0].astype(np.int64) - pair.sum(axis=1, dtype=np.int64)), initial=0) / 2
            assert error <= 1.0, (row["id"], stereo_key, error)
            max_fold_error = max(max_fold_error, float(error))
        gain = recipe["matchedGain"]
        for target, raw in (("stereo", "rawStereo"), ("mono", "rawMono")):
            error = np.max(np.abs(arrays[target].astype(np.float64) - gain * arrays[raw]), initial=0)
            rounding_budget = .5 + .5 * abs(gain) + 1e-7
            assert error <= rounding_budget, (row["id"], target, error, rounding_budget)
            max_gain_error = max(max_gain_error, float(error))
        starts = recipe["hitStartsFrames"]
        hit = recipe["printedHitFrames"]
        assert starts[0] == 0 and len(starts) in (1, 2)
        assert len(arrays["stereo"]) / RATE <= 8
        if len(starts) == 2:
            assert starts[1] >= hit + round(.25 * RATE)
        for mode, audio in arrays.items():
            first = audio[:hit]
            for start in starts:
                assert np.array_equal(audio[start:start + hit], first), (row["id"], mode, "repeated hit")
            if len(starts) == 2:
                assert not np.any(audio[hit:starts[1]]), (row["id"], mode, "tail gap")
            assert not np.any(audio[starts[-1] + hit:]), (row["id"], mode, "end silence")
        if recipe["kind"] in ("original", "stock-baseline"):
            entry = kotlin[source_id]["raw"] if recipe["kind"] == "original" else next(
                item for item in kotlin[source_id]["baselines"] if abs(item["amount"] - row["amount"]) < 1e-6)
            assert sha(inputs / entry["file"]) == entry["sha256"]
            source_pcm = read(inputs / entry["file"])
            if source_pcm.shape[1] == 1:
                source_pcm = np.repeat(source_pcm, 2, axis=1)
            assert np.array_equal(arrays["rawStereo"][:hit], source_pcm), row["id"]
        else:
            assert recipe["freshStateDeterminismVerified"]
            assert recipe["diagnostics"]["shimmer"] is False
            assert recipe["diagnostics"]["seamless_loop_claim"] is False
            assert recipe["printedHitFrames"] <= recipe["sourceFrames"] + round(.75 * RATE)
        raw_peak = max(raw_peak, int(np.max(np.abs(arrays["rawStereo"]), initial=0)))
        matched_peak = max(matched_peak, int(np.max(np.abs(arrays["stereo"]), initial=0)))
    assert len(file_hashes) == 120
    assert set(file_hashes) == {path.relative_to(output).as_posix() for path in (output / "audio").glob("*.wav")}
    assert matched_peak / SCALE <= .9900001
    changes = None
    if args.wav_baseline:
        old = json.loads(Path(args.wav_baseline).read_text())
        changes = {"changed": sorted(name for name in file_hashes if name in old and old[name] != file_hashes[name]),
                   "new": sorted(set(file_hashes) - set(old)), "missing": sorted(set(old) - set(file_hashes))}
    receipt = {
        "wavFilesVerified": len(file_hashes), "uniqueClips": len(unique),
        "selectedSourceFilesVerified": len(manifest["sourceHashes"]),
        "maxMonoFoldErrorPcm24Lsb": max_fold_error,
        "maxMatchedGainErrorPcm24Lsb": max_gain_error,
        "rawPeak": raw_peak / SCALE, "matchedPeak": matched_peak / SCALE,
        "actualKotlinOriginalAndStockBytesRetained": True,
        "commonSourceFramingVerified": True, "completeRepeatedHitsAndSilenceVerified": True,
        "sourceCommit": manifest["sourceCommit"], "workingTreeDirty": manifest["workingTreeDirty"],
        "renderCommitMatchesCurrentHead": manifest["sourceCommit"] == current_head,
        "wavBytes": sum((output / path).stat().st_size for path in file_hashes),
        "manifestSha256": sha(output / "manifest.json"), "baselineComparison": changes,
    }
    print(json.dumps(receipt, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
