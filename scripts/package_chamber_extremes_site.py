#!/usr/bin/env python3
"""Add the wider-range listening study without replacing any accepted recording."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

from package_chamber_site import directory_bytes, encode_verified, sha256
from chamber_flac_seektable import index_flac


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")


def contained(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Missing or escaping study asset: {relative}")
    return path


def package(current: Path, rendered: Path, destination: Path, ffmpeg: str, limit: int,
            ffprobe: str = "ffprobe") -> dict:
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
    proof = {"schemaVersion": 1, "policy": "Verified lossless FLAC with frame seek tables for new recordings and study-local copies of reused FLAC; prior site files retain their original bytes",
             "acceptedManifestSha256": before["manifest.json"],
             "renderedManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
             "sourceRevision": manifest["sourceRevision"], "compressed": [], "reused": [],
             "indexedReferences": [],
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
                    reuse = {"clipId": clip["id"], "baselineClipId": baseline_id,
                             "field": field, "path": clip[field], "sha256": sha256(asset)}
                    if field in ("raw", "matched") and asset.suffix.lower() == ".flac":
                        local = study / "references" / old[field]
                        local.parent.mkdir(parents=True, exist_ok=True)
                        if local.exists():
                            raise ValueError(f"Duplicate indexed reference destination: {local}")
                        shutil.copyfile(asset, local)
                        indexed = index_flac(ffmpeg, ffprobe, local)
                        entry = {"clipId": clip["id"], "baselineClipId": baseline_id, "mode": field,
                                 "originalFlac": clip[field], "originalFlacSha256": sha256(asset),
                                 "flac": local.relative_to(study).as_posix(), "flacSha256": sha256(local),
                                 "originalBytes": asset.stat().st_size, "flacBytes": local.stat().st_size,
                                 "seekTable": indexed, "samplesIdentical": True}
                        proof["indexedReferences"].append(entry)
                        clip.setdefault("hostingAudio", {})[field] = entry
                        clip[field] = entry["flac"]
                        reuse.update({"originalPath": reuse["path"], "originalSha256": reuse["sha256"],
                                      "path": clip[field], "sha256": entry["flacSha256"],
                                      "samplesIdentical": True})
                    proof["reused"].append(reuse)
                    if field in ("raw", "matched"):
                        clip["metrics"]["audioSha256"][field] = reuse["sha256"]
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
            indexed = index_flac(ffmpeg, ffprobe, flac)
            if indexed["pcmSha256"] != verified["pcmSha256"]:
                raise ValueError("Seek-table insertion changed the verified render samples")
            container = flac.relative_to(study).as_posix()
            entry = {"clipId": clip["id"], "mode": field, "originalWav": relative,
                     "originalWavSha256": sha256(original), "flac": container,
                     "flacSha256": sha256(flac), "originalBytes": wav.stat().st_size,
                     "flacBytes": flac.stat().st_size, "seekTable": indexed} | verified
            proof["compressed"].append(entry)
            hosted[field] = entry
            clip[field] = container
            clip["metrics"]["audioSha256"][field] = entry["flacSha256"]
            wav.unlink()
        clip["hostingAudio"] = hosted
    if len(new_ids) != 20 or len(proof["compressed"]) != 40 or len(proof["reused"]) != 66:
        raise ValueError("Expected 20 new raw/matched pairs and 22 complete prior references")
    manifest["hostingAudio"] = {"report": "hosting-audio.json", "renderedManifest": "manifest-rendered.json",
                                "policy": "Lossless FLAC with verified frame seek tables; reused FLAC gets a study-local indexed copy without changing prior files",
                                "indexedReferenceFiles": len(proof["indexedReferences"]), "samplesIdentical": True}
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
    # Retain every packaging source so the immutable render and containers are reproducible.
    scripts = study / "hosting-source"
    scripts.mkdir(exist_ok=True)
    proof["hostingSourceFiles"] = []
    for name in ("package_chamber_extremes_site.py", "package_chamber_site.py", "chamber_flac_seektable.py"):
        source = Path(__file__).resolve().with_name(name)
        shutil.copyfile(source, scripts / name)
        proof["hostingSourceFiles"].append({"path": "hosting-source/" + name, "sha256": sha256(scripts / name)})
    # Include this report's own final digit count in the expanded-size proof.
    proof["expandedBytes"] = 0
    for _ in range(10):
        write_json(study / "hosting-audio.json", proof)
        measured = directory_bytes(destination)
        if proof["expandedBytes"] == measured:
            break
        proof["expandedBytes"] = measured
    else:
        raise ValueError("Expanded-byte report did not reach a fixed point")
    expanded = directory_bytes(destination)
    if expanded >= limit:
        raise ValueError(f"Expanded site exceeds its hosting budget: {expanded} >= {limit}")
    print(json.dumps({"newRecordings": 20, "losslessFiles": 40,
                      "indexedReferenceFiles": len(proof["indexedReferences"]),
                      "retainedPriorFiles": proof["preservedPriorFiles"], "expandedBytes": expanded,
                      "headroomBytes": limit - expanded, "allConvertedPcmSamplesIdentical": True}))
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("current", type=Path)
    parser.add_argument("rendered", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--limit-bytes", type=int, default=256 * 1024 * 1024)
    args = parser.parse_args()
    package(args.current, args.rendered, args.destination, args.ffmpeg, args.limit_bytes, args.ffprobe)


if __name__ == "__main__":
    main()
