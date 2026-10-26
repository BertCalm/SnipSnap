#!/usr/bin/env python3
"""Package twelve fresh R7/R8 depth tracks for a new compact audition.

Both versions are raw Kotlin renders on the current runtime. No earlier audio or
accepted-reference roles are imported. Historical R7 files stay untouched at the
external archive URL. Lossless transport changes only PCM24 data words and restores
the entire original WAV, including sampler metadata, for playback and download.
"""

import argparse
import gzip
import json
from pathlib import Path
import re
import shutil

from package_skitter_audition import SUFFIX, predict_wav
from package_skitter_r6 import LIMIT, archive_size, inside, sha


VOICES = {"bass", "lead", "hollow", "flutter", "chirp", "fray"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("proof", type=Path, help="Raw generateSkitterDepthAudition output directory")
    parser.add_argument("destination", type=Path, help="New empty hosted output directory")
    parser.add_argument("--template", type=Path, default=Path(__file__).resolve().parents[1] /
                        "synth/src/test/resources/audition/skitter-depth.html")
    args = parser.parse_args()
    proof, destination = args.proof.resolve(), args.destination.resolve()
    if proof == destination or proof in destination.parents or destination in proof.parents:
        parser.error("destination must be separate from the raw proof")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    manifest = json.loads((proof / "r8/manifest.json").read_text())
    if manifest["revision"] != "R8" or not re.fullmatch(r"[0-9a-f]{40}", manifest["source"]["baseCommit"]):
        parser.error("expected an R8 proof with an explicit full source commit")
    voices = {voice["id"]: voice for voice in manifest["voices"]}
    if set(voices) != VOICES or len(manifest["voices"]) != 6:
        parser.error("expected all six Skitter voices")
    if manifest["defaults"] != {"voice": "lead", "cue": "hard"}:
        parser.error("expected LEAD/hard as the initial comparison")
    mapping, encoded, records, assets, seen = {}, {}, {}, [], set()
    for voice_id, voice in voices.items():
        if voice["role"] != "comparison" or voice["defaultRevision"] != "R8" or "acceptedRevision" in voice:
            parser.error("all six voices must be fresh switchable comparisons")
        if [track["revision"] for track in voice["tracks"]] != ["R7", "R8"]:
            parser.error("each voice requires R7 compatibility and R8 candidate tracks")
        for track in voice["tracks"]:
            expected_id = voice_id + "-" + track["revision"].lower()
            if track["id"] != expected_id or expected_id in seen or track.get("snapshot") is not False:
                parser.error("duplicate, inconsistent or archival track in a fresh-render proof")
            seen.add(expected_id)
            provenance = track["provenance"]
            expected_kind = "fresh-compatibility-baseline" if track["revision"] == "R7" else "fresh-depth-candidate"
            if (provenance["kind"] != expected_kind or provenance["sourceCommit"] != manifest["source"]["baseCommit"] or
                    provenance["sourceSha256"] != manifest["source"]["skitterSourceSha256"]):
                parser.error("fresh track source provenance differs from the manifest")
            if [cue["id"] for cue in track["cues"]] != ["soft", "medium", "hard"]:
                parser.error("each track requires three complete velocity-note cues")
            raw = inside(proof, track["file"], "r8").read_bytes()
            if sha(raw) != track["sha256"]:
                parser.error("raw depth WAV does not match its recorded SHA256")
            record = inside(proof, track["renderRecord"], "r8")
            record_json = json.loads(record.read_text())
            if record_json["source"] != manifest["source"] or record_json["revision"] != track["revision"]:
                parser.error("render record source or revision differs from its track")
            if record_json["renderSettings"]["probe"]["depthDisabled"] != (track["revision"] == "R7"):
                parser.error("R7 compatibility baseline and R8 depth probe settings differ")
            hosted = track["file"] + SUFFIX
            mapping[track["file"]] = hosted
            encoded[hosted] = gzip.compress(predict_wav(raw), compresslevel=9, mtime=0)
            records[track["renderRecord"]] = record
            assets.append({"trackId": track["id"], "file": hosted, "transportSha256": sha(encoded[hosted]),
                           "decodedSha256": track["sha256"], "renderRecord": track["renderRecord"],
                           "renderRecordSha256": sha(record.read_bytes())})
    if len(seen) != 12 or len(encoded) != 12 or len(records) != 12:
        parser.error("expected exactly twelve fresh WAVs and twelve render records")

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(manifest)
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["transportNotes"] = "Lossless PCM24 transport restores exact original raw WAV bytes. No earlier recordings are imported or modified."
    manifest["wavFiles"] = 12
    manifest["newWavFiles"] = 12
    manifest["variants"] = 36
    manifest["compressedAudioBytes"] = sum(map(len, encoded.values()))
    manifest["transportAssets"] = assets
    template = args.template.read_text()
    marker = "/* SKITTER_DEPTH_MANIFEST */null"
    if template.count(marker) != 1:
        parser.error("template requires exactly one depth manifest slot")
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
    (destination / "r8/manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    (destination / "index.html").write_text(html)
    sizes = [path.stat().st_size for path in destination.rglob("*") if path.is_file()]
    tar_bytes = archive_size(destination)
    if tar_bytes + 65_536 > LIMIT or max(sizes) > 25 * 1024 * 1024:
        raise ValueError("depth pack exceeds the archive or individual asset hosting limit")
    print(json.dumps({"destination": str(destination), "wavFiles": 12, "newWavFiles": 12,
                      "variants": 36, "files": len(sizes), "bytes": sum(sizes),
                      "compressedAudioBytes": manifest["compressedAudioBytes"], "tarBytes": tar_bytes,
                      "archiveLimitBytes": LIMIT,
                      "note": "All R7 files remain untouched; the archive is linked externally."}))


if __name__ == "__main__":
    main()
