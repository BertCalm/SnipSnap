#!/usr/bin/env python3
"""Make a size-bounded CHAMBER site copy with lossless diagnostic raw audio.

The rendered study is immutable. This command retains every matched WAV, archive,
recipe, source snapshot and ZIP, and replaces only selected root diagnostic raw
WAV containers with FLAC after checking every decoded PCM16 sample.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import wave

MIB = 1024 * 1024
FEATURED_SECTIONS = {"first-listen", "presets", "performance"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(MIB), b""):
            digest.update(block)
    return digest.hexdigest()


def directory_bytes(root: Path) -> int:
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")


def pcm16(path: Path) -> tuple[bytes, dict]:
    with wave.open(str(path), "rb") as stream:
        details = {"sampleRate": stream.getframerate(), "channels": stream.getnchannels(),
                   "bitsPerSample": stream.getsampwidth() * 8, "frames": stream.getnframes()}
        if details["sampleRate"] != 44100 or details["channels"] != 1 or details["bitsPerSample"] != 16:
            raise ValueError(f"Expected mono 44100 Hz PCM16: {path}")
        samples = stream.readframes(stream.getnframes())
    if len(samples) != details["frames"] * 2:
        raise ValueError(f"Truncated PCM16 WAV: {path}")
    return samples, details


def flac_details(path: Path) -> dict:
    # STREAMINFO is FLAC's mandatory first metadata block. Its packed sample
    # fields let us verify timing and format independently of the decoder.
    with path.open("rb") as stream:
        if stream.read(4) != b"fLaC":
            raise ValueError(f"Not native FLAC: {path}")
        header = stream.read(4)
        if len(header) != 4 or header[0] & 127 or int.from_bytes(header[1:], "big") != 34:
            raise ValueError(f"Missing FLAC STREAMINFO: {path}")
        block = stream.read(34)
    if len(block) != 34:
        raise ValueError(f"Truncated FLAC STREAMINFO: {path}")
    packed = int.from_bytes(block[10:18], "big")
    return {"sampleRate": packed >> 44, "channels": ((packed >> 41) & 7) + 1,
            "bitsPerSample": ((packed >> 36) & 31) + 1, "frames": packed & ((1 << 36) - 1)}


def encode_verified(ffmpeg: str, source: Path, output: Path) -> dict:
    original, details = pcm16(source)
    subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", str(source), "-map_metadata", "-1", "-c:a", "flac",
                    "-compression_level", "8", "-sample_fmt", "s16", str(output)],
                   check=True, capture_output=True)
    if flac_details(output) != details:
        raise ValueError(f"FLAC changed frame count, rate, channels or bit depth: {source}")
    decoded = subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                              "-i", str(output), "-f", "s16le", "-c:a", "pcm_s16le", "-"],
                             check=True, capture_output=True).stdout
    if decoded != original:
        raise ValueError(f"FLAC round trip changed PCM16 samples: {source}")
    return details | {"pcmSha256": hashlib.sha256(original).hexdigest(),
                      "decodedPcmSha256": hashlib.sha256(decoded).hexdigest(), "samplesIdentical": True}


def package(source: Path, destination: Path, target_bytes: int, presentation_js: Path, ffmpeg: str) -> dict:
    source, destination = source.resolve(), destination.resolve()
    if source == destination or source.is_relative_to(destination) or destination.is_relative_to(source):
        raise ValueError("Rendered source and site destination must be separate, non-nested directories")
    if not source.is_dir() or (destination.exists() and any(destination.iterdir())):
        raise ValueError("Use an existing rendered study and an empty or absent site destination")
    if target_bytes <= 0:
        raise ValueError("Site byte budget must be positive")
    original_manifest = (source / "manifest.json").read_bytes()
    manifest = json.loads(original_manifest)
    if manifest.get("auditionRevision") != "finite" or "hostingAudio" in manifest:
        raise ValueError("Package the original finite rendered study, not an already packaged site")
    version = subprocess.run([ffmpeg, "-version"], check=True, capture_output=True, text=True).stdout.splitlines()[0]
    source_hashes = {path.relative_to(source).as_posix(): sha256(path)
                     for path in source.rglob("*") if path.is_file()}
    if destination.exists():
        destination.rmdir()
    shutil.copytree(source, destination)
    (destination / "manifest-rendered.json").write_bytes(original_manifest)
    shutil.copyfile(presentation_js, destination / "app.js")
    script = Path(__file__).resolve()
    script_copy = destination / "hosting-source/package_chamber_site.py"
    script_copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(script, script_copy)
    presentation = {name: {"sha256": sha256(destination / name), "renderedSha256": source_hashes[name]}
                    for name in ["index.html", "app.js", "styles.css"]}
    report = {"schemaVersion": 1, "policy": "Lossless FLAC containers for selected root diagnostic raw PCM16 only",
              "renderedSourceRevision": manifest["sourceRevision"], "renderedManifest": "manifest-rendered.json",
              "renderedManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
              "encoder": version, "packagingScript": "hosting-source/package_chamber_site.py",
              "packagingScriptSha256": sha256(script_copy), "targetBytes": target_bytes,
              "originalBytes": sum((source / path).stat().st_size for path in source_hashes),
              "presentation": presentation, "compressed": []}
    candidates = []
    for clip in manifest["clips"]:
        relative = Path(clip["raw"])
        if clip["section"] in FEATURED_SECTIONS:
            continue
        if len(relative.parts) != 2 or relative.parts[0] != "raw" or relative.suffix.lower() != ".wav":
            raise ValueError(f"Unexpected root raw WAV path: {relative}")
        candidates.append(clip)
    candidates.sort(key=lambda clip: (-(destination / clip["raw"]).stat().st_size, clip["raw"]))
    remaining = directory_bytes(destination)
    replacements = set()
    # Reserve room for per-file proof metadata and the hosted manifest additions.
    reserve = 256 * 1024
    for clip in candidates:
        if remaining <= target_bytes - reserve:
            break
        relative = clip["raw"]
        wav = destination / relative
        flac = wav.with_suffix(".flac")
        if flac.exists():
            raise ValueError(f"Refusing to overwrite an existing FLAC: {flac}")
        verified = encode_verified(ffmpeg, wav, flac)
        before, after = wav.stat().st_size, flac.stat().st_size
        if after >= before:
            flac.unlink()
            continue
        compressed_path = flac.relative_to(destination).as_posix()
        proof = {"clipId": clip["id"], "originalWav": relative, "originalWavSha256": source_hashes[relative],
                 "originalWavBytes": before, "flac": compressed_path, "flacSha256": sha256(flac),
                 "flacBytes": after} | verified
        report["compressed"].append(proof)
        clip["raw"] = compressed_path
        clip["metrics"]["audioSha256"]["raw"] = proof["flacSha256"]
        clip["hostingRaw"] = {"format": "FLAC", "originalWav": relative,
                              "originalWavSha256": proof["originalWavSha256"],
                              "pcmSha256": proof["pcmSha256"], "frames": proof["frames"]}
        wav.unlink()
        replacements.add(relative)
        remaining += after - before
        print(f"{len(replacements)} lossless raw files; {remaining / MIB:.2f} MiB before proof metadata", flush=True)
    manifest["hostingAudio"] = {"report": "hosting-audio.json", "renderedManifest": "manifest-rendered.json",
                                "renderedManifestSha256": report["renderedManifestSha256"],
                                "compressedRawFiles": len(replacements), "samplesIdentical": True,
                                "presentation": presentation,
                                "description": "Selected diagnostic raw downloads use lossless FLAC. Their PCM16 samples and timing are unchanged. Every matched WAV, featured raw WAV, archived file and ZIP is retained."}
    manifest["normalization"]["hostingRaw"] = "Raw FLAC is a lossless container for the original PCM16 raw samples; no gain, resampling or tail changes. Featured raw clips and every matched clip remain WAV."
    write_json(destination / "manifest.json", manifest)
    # Verify every retained asset, including all historical archives and ZIPs.
    for relative, original_hash in source_hashes.items():
        if sha256(source / relative) != original_hash:
            raise ValueError(f"Packaging mutated the rendered source: {relative}")
        if relative in replacements or relative in {"manifest.json", "app.js"}:
            continue
        if sha256(destination / relative) != original_hash:
            raise ValueError(f"Packaging changed a retained asset: {relative}")
    report["verification"] = {"decodedPcmEqualityPassed": True, "renderedSourceFilesUnchanged": len(source_hashes),
                              "retainedAssetHashesMatched": len(source_hashes) - len(replacements) - 2,
                              "allMatchedWavsUnchanged": True, "featuredRawWavsUnchanged": True,
                              "allHistoricalArchivesUnchanged": True, "starterAndMpcZipsUnchanged": True}
    report["hostedBytes"] = 0
    report_path = destination / "hosting-audio.json"
    write_json(report_path, report)
    # One extra pass accounts for the final report's byte-count digits.
    report["hostedBytes"] = directory_bytes(destination)
    write_json(report_path, report)
    report["hostedBytes"] = directory_bytes(destination)
    write_json(report_path, report)
    actual = directory_bytes(destination)
    if actual > target_bytes:
        raise ValueError(f"Packaged site is {actual} bytes, above the {target_bytes}-byte budget")
    print(json.dumps({"destination": str(destination), "compressedRawFiles": len(replacements),
                      "hostedBytes": actual, "hostedMiB": actual / MIB,
                      "decodedPcmEqualityPassed": True, "retainedAssetHashesMatched": report["verification"]["retainedAssetHashesMatched"]}, indent=2))
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original generated finite study; never changed")
    parser.add_argument("destination", type=Path, help="Absent or empty separate hosting directory")
    parser.add_argument("--target-mib", type=int, default=248)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--presentation-js", type=Path,
                        default=Path(__file__).resolve().parent.parent / "synth/src/test/resources/chamber-audition/app.js")
    args = parser.parse_args()
    if not args.ffmpeg:
        parser.error("ffmpeg is required for verified lossless FLAC encoding")
    package(args.source, args.destination, args.target_mib * MIB, args.presentation_js, args.ffmpeg)


if __name__ == "__main__":
    main()
