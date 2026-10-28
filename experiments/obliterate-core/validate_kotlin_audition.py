#!/usr/bin/env python3
"""Read-only PCM/provenance validation for the Python/Kotlin port audition."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
RATE = 44_100
SCALE = 8_388_608
MODES = ("stereo", "mono", "rawStereo", "rawMono")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pcm24(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        assert wav.getsampwidth() == 3 and wav.getframerate() == RATE, path
        channels, frames = wav.getnchannels(), wav.getnframes()
        assert channels in (1, 2)
        data = np.frombuffer(wav.readframes(frames), dtype=np.uint8).reshape(-1, 3)
    value = data[:, 0].astype(np.int32) + (data[:, 1].astype(np.int32) << 8) + (data[:, 2].astype(np.int32) << 16)
    return ((value ^ 0x800000) - 0x800000).reshape(frames, channels)


def rows(manifest: dict) -> dict:
    clips = [clip for source in manifest["sources"] for key in ("clips", "baselineClips") for clip in source.get(key, [])]
    for key in ("clips", "referenceClips"):
        clips.extend(manifest.get("isolation", {}).get(key, []))
    return {clip["id"]: clip for clip in clips}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", type=Path, default=ROOT / "testkit/obliterate-kotlin-audition")
    args = parser.parse_args()
    output = args.output.resolve()
    report = json.loads((output / "parity-report.json").read_text())
    assert report["allPass"] and len(report["cases"]) == 36
    for key, value in report["sourceHashes"].items():
        assert sha(ROOT / key) == value, key
    assert sha(output / "reference-inputs.json") == report["referenceManifestSha256"]
    assert sha(output / "kotlin-port-results.json") == report["kotlinResultsManifestSha256"]
    actual_results = json.loads((output / "kotlin-port-results.json").read_text())
    for key, value in actual_results["sourceHashes"].items():
        assert sha(ROOT / key) == value, key
    assert actual_results["inputManifestSha256"] == report["referenceManifestSha256"]
    reference = json.loads((output / "reference-inputs.json").read_text())
    assert sha(ROOT / "experiments/obliterate-core/prototype.py") == reference["pythonProcessorSha256"]
    for source in reference["sources"]:
        wav = output / source["sourceWavFile"]
        assert sha(wav) == source["sourceWavSha256"]
        raw = output / source["inputFile"]
        assert sha(raw) == source["inputSha256"]
        expected = np.maximum(pcm24(wav).astype(np.float32) / np.float32(8_388_607), np.float32(-1))
        actual = np.fromfile(raw, dtype="<f4").reshape(source["inputFrames"], source["inputChannels"])
        assert np.array_equal(expected.view(np.uint32), actual.view(np.uint32)), source["id"]
    for case in report["cases"]:
        record = next(row for row in actual_results["cases"] if row["id"] == case["id"])
        assert sha(output / record["outputFile"]) == case["kotlinOutputSha256"]

    # Historical production files may change after their source generation.
    # The heard WAVs themselves, including stock baselines, remain immutable.
    heard_dir = ROOT / "testkit/obliterate-core-audition"
    heard = json.loads((heard_dir / "manifest.json").read_text())
    heard_wavs = {}
    for clip in rows(heard).values():
        for mode in MODES:
            heard_wavs[clip[mode]] = clip[mode + "Sha256"]
    for name, expected in heard_wavs.items():
        assert sha(heard_dir / name) == expected, name
    assert len(heard_wavs) == 120

    manifest = json.loads((output / "manifest.json").read_text())
    unique = rows(manifest)
    files, max_fold_error, max_gain_error = set(), 0., 0.
    for clip in unique.values():
        arrays = {}
        recipe = clip["recipe"]
        for mode in MODES:
            path = output / clip[mode]
            assert path.resolve().is_relative_to(output)
            assert sha(path) == clip[mode + "Sha256"]
            audio = pcm24(path)
            assert audio.shape == (recipe["phraseFrames"], 1 if mode in ("mono", "rawMono") else 2)
            assert np.max(np.abs(audio), initial=0) > 0
            arrays[mode] = audio
            files.add(clip[mode])
        for pair, fold in (("stereo", "mono"), ("rawStereo", "rawMono")):
            error = float(np.max(np.abs(2 * arrays[fold][:, 0].astype(np.int64) - arrays[pair].sum(axis=1, dtype=np.int64)), initial=0)) / 2
            assert error <= 1, (clip["id"], error)
            max_fold_error = max(max_fold_error, error)
        gain = recipe["matchedGain"]
        for target, raw in (("stereo", "rawStereo"), ("mono", "rawMono")):
            error = float(np.max(np.abs(arrays[target].astype(np.float64) - gain * arrays[raw]), initial=0))
            assert error <= .5 * abs(gain) + .5 + 1e-7
            max_gain_error = max(max_gain_error, error)
        starts, hit = recipe["hitStartsFrames"], recipe["printedHitFrames"]
        assert starts[0] == 0 and len(starts) == 2
        assert starts[1] >= hit + round(.25 * RATE)
        assert (recipe["phraseFrames"] - starts[1] - hit) >= round(.2 * RATE)
        for audio in arrays.values():
            assert np.array_equal(audio[:hit], audio[starts[1]:starts[1] + hit])
            assert not np.any(audio[hit:starts[1]]) and not np.any(audio[starts[1] + hit:])
    assert len(files) == manifest["wavFiles"]
    assert files == {path.relative_to(output).as_posix() for path in (output / "audio").glob("*.wav")}
    receipt = {
        "parityCasesVerified": 36, "newWavsVerified": len(files), "heardWavsUnchanged": len(heard_wavs),
        "wavBytes": sum((output / name).stat().st_size for name in files),
        "maxMonoFoldErrorPcm24Lsb": max_fold_error, "maxMatchedGainErrorPcm24Lsb": max_gain_error,
        "sameFloat32WavReaderBoundaryVerified": True, "repeatedHitsAndFullTailsVerified": True,
        "maximumPortSampleError": report["maximumSampleError"], "maximumPortRmsError": report["maximumRmsError"],
        "manifestSha256": sha(output / "manifest.json"), "parityReportSha256": sha(output / "parity-report.json"),
    }
    print(json.dumps(receipt, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
