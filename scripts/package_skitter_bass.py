#!/usr/bin/env python3
"""Package the focused C1/C2/C3 BASS R8/R9 audition without changing R8 assets.

Six fresh raw tracks retain all eighteen full finite-note cues. R8 compatibility
renders keep depth enabled and identify the immutable original R8 source plus the
actual current render runtime. Sealed class files never enter this package or Git.
"""

import argparse
import gzip
import json
from pathlib import Path
import re
import shutil

from package_skitter_audition import SUFFIX, predict_wav
from package_skitter_r6 import LIMIT, archive_size, inside, sha


REGISTERS = {"c1": ("C1", 24), "c2": ("C2", 36), "c3": ("C3", 48)}
BASE_COMMIT = "eab42bf300d4607e72d4122e3c5efe2ea5751857"
BASE_SOURCE_SHA = "03425e88f13033df0e00832b0a72dbc354bb0620d8cb3a4606057b52540e71de"
BASE_CLASS_SHA = "e2f7598df1c8d4dd00b4cbe1e5ee096bc7e2bd16fa77ec9a350b7ef01e1001b3"
FLAGS = ("memoryFrozen", "agitationDisabled", "equalRecovery", "noiseForcingDisabled",
         "articulationDisabled", "contactMemoryDisabled", "bodyDisabled", "quartetDisabled",
         "depthDisabled", "filterReturnDisabled", "depthExchangeDisabled", "bassFoundationDisabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("proof", type=Path, help="Raw generateSkitterBassAudition output directory")
    parser.add_argument("destination", type=Path, help="New empty hosted output directory")
    parser.add_argument("--template", type=Path, default=Path(__file__).resolve().parents[1] /
                        "synth/src/test/resources/audition/skitter-bass.html")
    args = parser.parse_args()
    proof, destination = args.proof.resolve(), args.destination.resolve()
    if proof == destination or proof in destination.parents or destination in proof.parents:
        parser.error("destination must be separate from the raw proof")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    manifest = json.loads((proof / "r9/manifest.json").read_text())
    if manifest["revision"] != "R9" or not re.fullmatch(r"[0-9a-f]{40}", manifest["source"]["baseCommit"]):
        parser.error("expected an R9 proof with an explicit full render-runtime source commit")
    baseline = manifest["baseline"]
    if (baseline["sourceCommit"], baseline["sourceSha256"], baseline["engineClassSha256"]) != (
            BASE_COMMIT, BASE_SOURCE_SHA, BASE_CLASS_SHA):
        parser.error("immutable original R8 source/class provenance differs")
    if baseline["classCount"] != 29 or not re.fullmatch(r"[0-9a-f]{64}", baseline["provenanceSha256"]):
        parser.error("sealed R8 provenance is incomplete")
    if manifest["defaults"] != {"register": "c2", "cue": "medium"} or manifest["common"]["voice"] != "BASS":
        parser.error("expected the focused BASS C2/medium default")
    registers = {group["id"]: group for group in manifest["registers"]}
    if set(registers) != set(REGISTERS) or len(manifest["registers"]) != 3:
        parser.error("expected exactly C1, C2 and C3 registers")
    mapping, encoded, records, assets, seen = {}, {}, {}, [], set()
    for register_id, group in registers.items():
        note, midi = REGISTERS[register_id]
        if (group["note"], group["midi"], group["role"], group["defaultRevision"]) != (note, midi, "comparison", "R9"):
            parser.error("register identity or revision contract differs")
        if [track["revision"] for track in group["tracks"]] != ["R8", "R9"]:
            parser.error("each register requires R8 compatibility and R9 candidate tracks")
        for track in group["tracks"]:
            expected_id = register_id + "-" + track["revision"].lower()
            if (track["id"] != expected_id or expected_id in seen or track.get("snapshot") is not False or
                    (track["note"], track["midi"]) != (note, midi)):
                parser.error("duplicate, inconsistent or archival track in a fresh-render proof")
            seen.add(expected_id)
            provenance = track["provenance"]
            is_baseline = track["revision"] == "R8"
            expected = {"kind": "fresh-r8-compatibility-baseline" if is_baseline else "fresh-r9-candidate",
                        "sourceCommit": BASE_COMMIT if is_baseline else manifest["source"]["baseCommit"],
                        "sourceSha256": BASE_SOURCE_SHA if is_baseline else manifest["source"]["skitterSourceSha256"],
                        "renderRuntimeCommit": manifest["source"]["baseCommit"],
                        "renderRuntimeSourceSha256": manifest["source"]["skitterSourceSha256"]}
            if provenance != expected:
                parser.error("physical side source and current render-runtime provenance differ")
            if [cue["id"] for cue in track["cues"]] != ["soft", "medium", "hard"]:
                parser.error("each track requires three complete velocity-note cues")
            raw = inside(proof, track["file"], "r9").read_bytes()
            if sha(raw) != track["sha256"]:
                parser.error("raw BASS WAV does not match its recorded SHA256")
            record = inside(proof, track["renderRecord"], "r9")
            data = json.loads(record.read_text())
            if data["source"] != manifest["source"] or data["revision"] != track["revision"]:
                parser.error("render record runtime source or revision differs from its track")
            expected_flags = {flag: False for flag in FLAGS}
            expected_flags["bassFoundationDisabled"] = is_baseline
            settings = data["renderSettings"]
            if settings["probe"] != expected_flags or (settings["voice"], settings["midi"]) != ("BASS", midi):
                parser.error("wrong foundation compatibility, depth state, voice or register render settings")
            hosted = track["file"] + SUFFIX
            mapping[track["file"]] = hosted
            encoded[hosted] = gzip.compress(predict_wav(raw), compresslevel=9, mtime=0)
            records[track["renderRecord"]] = record
            assets.append({"trackId": track["id"], "file": hosted, "transportSha256": sha(encoded[hosted]),
                           "decodedSha256": track["sha256"], "renderRecord": track["renderRecord"],
                           "renderRecordSha256": sha(record.read_bytes())})
    if len(seen) != 6 or len(encoded) != 6 or len(records) != 6:
        parser.error("expected exactly six fresh WAVs and six render records")

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(manifest)
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["transportNotes"] = "Lossless PCM24 transport restores exact complete raw WAV bytes. No old R8 artifacts or sealed class files are copied."
    manifest["wavFiles"] = 6
    manifest["newWavFiles"] = 6
    manifest["variants"] = 18
    manifest["compressedAudioBytes"] = sum(map(len, encoded.values()))
    manifest["transportAssets"] = assets
    template = args.template.read_text()
    marker = "/* SKITTER_BASS_MANIFEST */null"
    if template.count(marker) != 1:
        parser.error("template requires exactly one BASS manifest slot")
    html = template.replace(marker, json.dumps(manifest, separators=(",", ":")).replace("<", "\\u003c"))
    destination.mkdir(parents=True, exist_ok=True)
    for relative, path in records.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    for relative, data in encoded.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "r9/manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    (destination / "index.html").write_text(html)
    sizes = [path.stat().st_size for path in destination.rglob("*") if path.is_file()]
    tar_bytes = archive_size(destination)
    if tar_bytes + 65_536 > LIMIT or max(sizes) > 25 * 1024 * 1024:
        raise ValueError("BASS proof exceeds the archive or individual asset hosting limit")
    print(json.dumps({"destination": str(destination), "wavFiles": 6, "newWavFiles": 6, "variants": 18,
                      "files": len(sizes), "bytes": sum(sizes), "compressedAudioBytes": manifest["compressedAudioBytes"],
                      "tarBytes": tar_bytes, "archiveLimitBytes": LIMIT,
                      "note": "Focused BASS proof only; all old R8 artifacts and the external sealed baseline remain untouched."}))


if __name__ == "__main__":
    main()
