#!/usr/bin/env python3
"""Regenerate the nine approved SOURCE WAVs, without fetching or altering source audio."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess

import numpy as np


RATE = 44_100
APPROVAL_SHA256 = "2ea504d802161b415814540922691cff00d1365ef56680ccd231c73d793f3df7"
SOURCES = {
    "hindustani-yaman": {
        "files": [("hindustani-yaman-preview.mp3", "eb1e810fb29b79817ec5f48eccff81f18a4188836ca0b89284703bc8a48ee8a9", 7_713_688),
                  ("freesound-193920-yaman-hq-preview.mp3", "eb1e810fb29b79817ec5f48eccff81f18a4188836ca0b89284703bc8a48ee8a9", 7_713_688)],
        "channels": 2, "frames": 15_196_487,
        "monoSha256": "6439fe01d35d79fe348be20214a18ca082a8f132c6b456ee76ce0f9a3fe6eb7d",
    },
    "swedish-kulning": {
        "files": [("swedish-kulning.flac", "18f2f0c0b448203ae56c5a765204b64fd36e589912ffd0ae4c3b6966453dc099", 1_338_885),
                  ("kulning-lossless.flac", "18f2f0c0b448203ae56c5a765204b64fd36e589912ffd0ae4c3b6966453dc099", 1_338_885),
                  ("kulning-original.wav", "cc6a668a15ba49d324102a18247106332edcf5fab39da0da501a680931cff3cc", 8_921_016)],
        "channels": 2, "frames": 2_229_696,
        "monoSha256": "efb66319601e93f1047733e17ef279adfd2c3e2cd75c416554d282055e780bc1",
    },
    "khoomei": {
        "files": [("khoomei-preview.mp3", "0c5e61b4cb8c2bb7559d9cb25742c15e5473d7c11cba35f8f14adf106e816be5", 280_007),
                  ("freesound-240916-khoomei-hq-preview.mp3", "0c5e61b4cb8c2bb7559d9cb25742c15e5473d7c11cba35f8f14adf106e816be5", 280_007)],
        "channels": 1, "frames": 552_875,
        "monoSha256": "c7c6b590d8a64848c8d55e8163eb492af42aa704a99c3206350402c1c0b74160",
    },
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def asset_path(value):
    path = Path(value)
    require(not path.is_absolute() and path.parts == ("voices", path.name)
            and path.suffix == ".wav", "Expected a voices/<name>.wav asset path")
    return path


def decode(inputs, source_id):
    spec = SOURCES[source_id]
    chosen = next(((inputs / name, digest, size) for name, digest, size in spec["files"]
                   if (inputs / name).is_file()), None)
    require(chosen is not None, f"Missing cached input for {source_id}: {spec['files'][0][0]}")
    path, expected_hash, expected_bytes = chosen
    require(path.stat().st_size == expected_bytes, f"Input byte count differs: {path.name}")
    require(sha256(path.read_bytes()) == expected_hash, f"Input checksum differs: {path.name}")
    probe = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
        "stream=sample_rate,channels", "-of", "json", str(path),
    ]))["streams"][0]
    require(int(probe["sample_rate"]) == RATE and probe["channels"] == spec["channels"],
            f"Unexpected source format: {source_id}; no resampling is allowed")
    # This is the original preparation command: gapless sample zero, no -ar/-ac or filters.
    decoded = subprocess.check_output([
        "ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
        "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1",
    ])
    channels = spec["channels"]
    require(len(decoded) == spec["frames"] * channels * 4,
            f"Gapless decoded frame count differs: {source_id}")
    audio = np.frombuffer(decoded, dtype="<f4").reshape(-1, channels)
    require(np.isfinite(audio).all(), f"Nonfinite decoded source: {source_id}")
    # Float32 summation/division matches the original script and Cleanup.toMono.
    mono = audio[:, 0].copy() if channels == 1 else (
        audio.sum(axis=1, dtype=np.float32) / np.float32(channels))
    require(sha256(mono.astype("<f4").tobytes()) == spec["monoSha256"],
            f"Decoded mono PCM differs: {source_id}; check the decoder version")
    return mono, {"id": source_id, "file": path.name, "sha256": expected_hash,
                  "frames": len(mono), "channels": channels}


def pcm24(samples):
    require(np.isfinite(samples).all() and np.max(np.abs(samples)) <= 1,
            "PCM24 would clip; source gain must not be changed")
    # WavWriter: scale by 8388607 and round nearest, ties toward positive infinity.
    integers = np.floor(samples.astype(np.float64) * 8_388_607.0 + .5).astype(np.int32)
    unsigned = integers.astype(np.uint32)
    packed = np.stack([(unsigned >> offset) & 255 for offset in (0, 8, 16)],
                      axis=1).astype(np.uint8).tobytes()
    padding = b"\0" if len(packed) % 2 else b""
    wav = (b"RIFF" + struct.pack("<I", 36 + len(packed) + len(padding)) + b"WAVEfmt "
           + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 3, 3, 24)
           + b"data" + struct.pack("<I", len(packed)) + packed + padding)
    # The canonical identity describes WavReader's decoded float PCM, not the container bytes.
    decoded = np.maximum(integers.astype(np.float32) / np.float32(8_388_607), np.float32(-1))
    digest = sha256(struct.pack("<ii", RATE, len(decoded)) + decoded.astype("<f4").tobytes())
    return wav, digest


def prepare(args):
    here = Path(__file__).resolve().parent
    approval_bytes = (here / "approved-selections.json").read_bytes()
    require(sha256(approval_bytes) == APPROVAL_SHA256, "The approved selection export changed")
    approval = json.loads(approval_bytes)
    selections = {section["id"]: section for section in approval["sections"]}
    require(len(selections) == len(approval["sections"]) == 9, "Expected exactly nine approved selections")
    manifest_bytes = args.manifest.read_bytes()
    bank = json.loads(manifest_bytes)
    require(bank["format"] == "snipsnap-confluence-selected-bank" and bank["schema"] == 1,
            "Unsupported shipping bank manifest")
    require(len(bank["voices"]) == 9, "Expected the nine shipping SOURCE voices")
    sources = {source["id"]: source for source in bank["sources"]}
    require(set(sources) == set(SOURCES), "The three shipping source identities changed")
    require({voice["provenance"]["selectionId"] for voice in bank["voices"]} == set(selections),
            "Shipping voices no longer match the approved selection IDs")
    inputs, output = args.inputs.resolve(), args.output.resolve()
    require(inputs.is_dir(), "The cached input directory is missing")
    require(not (inputs == output or inputs in output.parents or output in inputs.parents),
            "Input and output directories must be separate")
    require(output != here and here not in output.parents and output not in here.parents,
            "Output must be outside this reference bundle")
    require(not output.exists() or (output.is_dir() and not any(output.iterdir())),
            "Output must be a new or empty directory; existing files are never replaced")
    assets = {asset["path"]: asset for asset in bank["assets"]}
    outputs, checks, input_checks = {}, [], []
    for source_id in SOURCES:
        source = sources[source_id]
        primary_hash = SOURCES[source_id]["files"][0][1]
        require(source["listeningFileSha256"] == primary_hash, "Shipping listening-file identity changed")
        mono, input_check = decode(inputs, source_id)
        input_checks.append(input_check)
        for voice in (v for v in bank["voices"] if v["sourceId"] == source_id):
            provenance = voice["provenance"]
            section = selections[provenance["selectionId"]]
            require(section["source"]["id"] == source_id
                    and section["listeningFile"]["sha256"] == primary_hash,
                    "Approved source and listening-file identities differ")
            start = math.floor(section["startSeconds"] * RATE + .5)
            end = math.floor(section["endSeconds"] * RATE + .5)
            require(0 <= start < end <= len(mono), "Approved selection is outside its decoded source")
            selected = provenance["nativeDecodedSelection"]
            require(start == selected["startFrame"] and end == selected["endFrame"]
                    and end - start == voice["frames"], "Shipping selection coordinates changed")
            wav, pcm_hash = pcm24(mono[start:end].copy())
            path = asset_path(voice["assetPath"]).as_posix()
            require(path not in outputs, "Duplicate shipping voice path")
            digest = sha256(wav)
            require(digest == voice["fileSha256"] == assets[path]["sha256"]
                    and len(wav) == assets[path]["bytes"],
                    f"Regenerated WAV checksum/size differs: {voice['id']}")
            require(pcm_hash == voice["canonicalPcmSha256"],
                    f"Regenerated canonical PCM checksum differs: {voice['id']}")
            outputs[path] = wav
            checks.append({"id": voice["id"], "path": path, "frames": end - start,
                           "fileSha256": digest, "canonicalPcmSha256": pcm_hash})
    require(len(outputs) == 9, "Not every shipping SOURCE WAV was regenerated")
    report = {"format": "snipsnap-confluence-selected-reproduction/v1", "allPass": True,
              "approvalSha256": APPROVAL_SHA256, "bankManifestSha256": sha256(manifest_bytes),
              "ffmpeg": subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0],
              "numpy": np.__version__, "sources": input_checks, "voices": checks}
    # Publish only after all source, coordinate, file and PCM checks pass. No full decode is stored.
    output.mkdir(parents=True, exist_ok=True)
    for relative, data in outputs.items():
        path = output / relative
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(data)
    (output / "reproduction.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"allPass": True, "voices": len(outputs), "wavBytes": sum(map(len, outputs.values())),
                      "bankManifestSha256": report["bankManifestSha256"]}, indent=2))


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True, help="Directory of hash-pinned local recordings")
    parser.add_argument("--output", type=Path, required=True, help="Separate new or empty output directory")
    parser.add_argument("--manifest", type=Path,
                        default=here.parents[2] / "synth/src/main/resources/confluence-selected-bank/bank.json",
                        help="Shipping bank.json (defaults to this checkout's packaged bank)")
    args = parser.parse_args()
    try:
        prepare(args)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Preparation refused: {error}\n")


if __name__ == "__main__":
    main()
