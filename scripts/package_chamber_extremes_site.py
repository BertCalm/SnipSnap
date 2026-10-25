#!/usr/bin/env python3
"""Add the wider-range listening study without replacing any accepted recording."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

from package_chamber_site import directory_bytes, encode_verified, sha256


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")


def contained(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Missing or escaping study asset: {relative}")
    return path


def package(current: Path, rendered: Path, destination: Path, ffmpeg: str, limit: int) -> dict:
    current, rendered, destination = (p.resolve() for p in (current, rendered, destination))
    for a, b in ((current, rendered), (current, destination), (rendered, destination)):
        if a == b or a.is_relative_to(b) or b.is_relative_to(a):
            raise ValueError("Use three separate, non-nested site, render and output directories")
    if not current.is_dir() or not rendered.is_dir():
        raise ValueError("The existing hosted site and new rendered study must exist")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Use a new or empty output directory")
    if (current / "extremes").exists():
        raise ValueError("This packaging step adds a new study; it cannot overwrite a prior one")
    before = {p.relative_to(current).as_posix(): sha256(p) for p in current.rglob("*") if p.is_file()}
    current_manifest = json.loads((current / "manifest.json").read_text())
    accepted = {clip["id"]: clip for clip in current_manifest["clips"]}
    original_manifest = (rendered / "manifest.json").read_bytes()
    manifest = json.loads(original_manifest)
    checks = json.loads((rendered / "checks.json").read_text())
    if len(manifest["clips"]) != 42 or not checks["passed"]:
        raise ValueError("The complete checked 42-clip endpoint study is required")
    if manifest["models"] != {"accepted": 3, "wider": 4}:
        raise ValueError("Unexpected current/wider sound-model identities")
    if any(not c["productionFloat32Exact"] or not c["rawFloat32Exact"] for c in checks["acceptedCenters"]):
        raise ValueError("A wider default changed the accepted recording")
    if destination.exists():
        destination.rmdir()
    shutil.copytree(current, destination)
    study = destination / "extremes"
    shutil.copytree(rendered, study)
    (study / "manifest-rendered.json").write_bytes(original_manifest)
    proof = {"schemaVersion": 1, "policy": "New study audio in verified lossless FLAC; prior recordings retain their original bytes",
             "acceptedManifestSha256": before["manifest.json"],
             "renderedManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
             "sourceRevision": manifest["sourceRevision"], "compressed": [], "reused": [],
             "priorFiles": len(before), "priorBytes": directory_bytes(current), "limitBytes": limit}
    new_ids = []
    for clip in manifest["clips"]:
        if clip["variant"] != "wider":
            baseline_id = clip["baselineClipId"]
            old = accepted[baseline_id]
            for field in ("raw", "matched", "recipe"):
                if old.get(field):
                    asset = contained(current, old[field])
                    clip[field] = "../" + old[field]
                    proof["reused"].append({"clipId": clip["id"], "baselineClipId": baseline_id,
                                             "field": field, "path": clip[field], "sha256": sha256(asset)})
                    if field in ("raw", "matched"):
                        clip["metrics"]["audioSha256"][field] = sha256(asset)
            clip["sourceRevision"] = old.get("sourceRevision", current_manifest["sourceRevision"])
            continue
        new_ids.append(clip["id"])
        hosted = {}
        for field in ("raw", "matched"):
            relative = clip[field]
            original = contained(rendered, relative)
            wav = contained(study, relative)
            flac = wav.with_suffix(".flac")
            if wav.suffix.lower() != ".wav" or flac.exists():
                raise ValueError("Expected distinct newly rendered WAVs")
            verified = encode_verified(ffmpeg, wav, flac)
            container = flac.relative_to(study).as_posix()
            entry = {"clipId": clip["id"], "mode": field, "originalWav": relative,
                     "originalWavSha256": sha256(original), "flac": container,
                     "flacSha256": sha256(flac), "originalBytes": wav.stat().st_size,
                     "flacBytes": flac.stat().st_size} | verified
            proof["compressed"].append(entry)
            hosted[field] = entry
            clip[field] = container
            clip["metrics"]["audioSha256"][field] = entry["flacSha256"]
            wav.unlink()
        clip["hostingAudio"] = hosted
    if len(new_ids) != 20 or len(proof["compressed"]) != 40 or len(proof["reused"]) != 66:
        raise ValueError("Expected 20 new raw/matched pairs and 22 complete prior references")
    manifest["hostingAudio"] = {"report": "hosting-audio.json", "renderedManifest": "manifest-rendered.json",
                                "policy": "Lossless FLAC for new recordings only", "samplesIdentical": True}
    manifest["baseline"]["hostedManifestSha256"] = before["manifest.json"]
    write_json(study / "manifest.json", manifest)
    index = destination / "index.html"
    text = index.read_text()
    marker = '        <a class="text-link hero-link" href="#first-listen">'
    start = text.find(marker)
    if start < 0:
        raise ValueError("The existing audition's listening link could not be located")
    end = text.index("</a>", start) + len("</a>")
    text = text[:end] + '\n        <a class="text-link hero-link" style="display:block;margin-top:14px" href="extremes/index.html">New: wider control ranges <span aria-hidden="true">↗</span></a>' + text[end:]
    index.write_text(text)
    proof["navigation"] = {"path": "index.html", "beforeSha256": before["index.html"], "afterSha256": sha256(index)}
    proof["preservedPriorFiles"] = sum(sha256(contained(destination, path)) == digest
                                       for path, digest in before.items() if path != "index.html")
    if proof["preservedPriorFiles"] != len(before) - 1:
        raise ValueError("An earlier audio, recipe, archive or source asset changed")
    # Retain both scripts so the immutable render and hosting containers are reproducible.
    scripts = study / "hosting-source"
    scripts.mkdir(exist_ok=True)
    for name in ("package_chamber_extremes_site.py", "package_chamber_site.py"):
        source = Path(__file__).resolve().with_name(name)
        shutil.copyfile(source, scripts / name)
    write_json(study / "hosting-audio.json", proof)
    proof["expandedBytes"] = directory_bytes(destination)
    write_json(study / "hosting-audio.json", proof)
    expanded = directory_bytes(destination)
    if expanded >= limit:
        raise ValueError(f"Expanded site exceeds its hosting budget: {expanded} >= {limit}")
    print(json.dumps({"newRecordings": 20, "losslessFiles": 40,
                      "retainedPriorFiles": proof["preservedPriorFiles"], "expandedBytes": expanded,
                      "headroomBytes": limit - expanded, "allConvertedPcmSamplesIdentical": True}))
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("current", type=Path)
    parser.add_argument("rendered", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--limit-bytes", type=int, default=256 * 1024 * 1024)
    args = parser.parse_args()
    package(args.current, args.rendered, args.destination, args.ffmpeg, args.limit_bytes)


if __name__ == "__main__":
    main()
