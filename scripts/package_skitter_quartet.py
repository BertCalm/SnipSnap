#!/usr/bin/env python3
"""Package a new compact R7 site without changing the frozen R6 archive.

The generated proof supplies eight new raw PCM24 tracks. The accepted LEAD and
HOLLOW transports and render records are copied byte for byte from the published
R6 tree, never recompressed. Only those two references are imported; the complete
history remains at the manifest's external archive URL. All ten audio files are
local to the new site, so playback/downloads do not depend on cross-origin audio.
"""

import argparse
import gzip
import json
from pathlib import Path
import shutil

from package_skitter_audition import SUFFIX, predict_wav
from package_skitter_r6 import LIMIT, archive_size, inside, restored_asset, sha


COMPARISONS = {"bass", "flutter", "chirp", "fray"}
REFERENCES = {"lead", "hollow"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_hosted", type=Path, help="Complete frozen published R6 hosted tree")
    parser.add_argument("proof", type=Path, help="Generated raw R7 quartet proof")
    parser.add_argument("destination", type=Path, help="New empty compact hosted directory")
    parser.add_argument("--template", type=Path, default=Path(__file__).resolve().parents[1] /
                        "synth/src/test/resources/audition/skitter-quartet.html")
    args = parser.parse_args()
    references, proof, destination = (p.resolve() for p in (args.reference_hosted, args.proof, args.destination))
    for source in (references, proof):
        if source == destination or source in destination.parents or destination in source.parents:
            parser.error("destination must be separate from both inputs")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    frozen_bytes = (references / "r6/manifest.json").read_bytes()
    frozen = json.loads(frozen_bytes)
    manifest = json.loads((proof / "r7/manifest.json").read_text())
    if frozen["revision"] != "R6" or manifest["revision"] != "R7":
        parser.error("expected frozen R6 references and a new R7 proof")
    if manifest["referenceSource"] != frozen["source"]:
        parser.error("accepted reference source provenance differs from the published R6 proof")
    voices = {voice["id"]: voice for voice in manifest["voices"]}
    if set(voices) != COMPARISONS | REFERENCES or len(manifest["voices"]) != 6:
        parser.error("expected four comparison voices and two accepted references")
    frozen_tracks = {track["id"]: track for voice in frozen["voices"] for track in voice["tracks"]}
    mapping, encoded, copied_assets, records, reference_records = {}, {}, {}, {}, []
    seen = set()
    for voice_id, voice in voices.items():
        is_reference = voice_id in REFERENCES
        expected_revisions = ["R6"] if is_reference else ["R6", "R7"]
        expected_role = "accepted-reference" if is_reference else "comparison"
        if voice["role"] != expected_role or [t["revision"] for t in voice["tracks"]] != expected_revisions:
            parser.error("voice revision/role contract differs from the quartet proof")
        if voice["defaultRevision"] != ("R6" if is_reference else "R7"):
            parser.error("voice has an invalid default revision")
        for track in voice["tracks"]:
            expected_id = voice_id + "-" + track["revision"].lower()
            if track["id"] != expected_id or expected_id in seen:
                parser.error("duplicate or inconsistent track id")
            seen.add(expected_id)
            if is_reference:
                if voice.get("acceptedRevision") != "R6":
                    parser.error("accepted reference must identify R6")
                original = frozen_tracks[expected_id]
                for key in ("revision", "sha256", "frames", "seconds", "renderRecord"):
                    if track[key] != original[key]:
                        parser.error(f"accepted reference differs: {expected_id}/{key}")
                old_cues = {cue["id"]: cue for cue in original["cues"]}
                for cue in track["cues"]:
                    for key in ("startFrame", "endFrame", "velocity", "hold"):
                        if cue[key] != old_cues[cue["id"]][key]:
                            parser.error(f"accepted reference cue differs: {expected_id}/{cue['id']}/{key}")
                provenance = track["provenance"]
                if (provenance["kind"] != "accepted-recording" or
                        provenance["sourceCommit"] != frozen["source"]["baseCommit"] or
                        provenance["sourceSha256"] != frozen["source"]["skitterSourceSha256"]):
                    parser.error("accepted reference provenance differs from its original source")
                original_path = inside(references, original["file"], "r6")
                if sha(restored_asset(original_path)) != track["sha256"]:
                    parser.error("accepted transport does not restore its original raw WAV bytes")
                record = inside(references, original["renderRecord"], "r6")
                mapping[track["file"]] = original["file"]
                copied_assets[original["file"]] = original_path
                records[original["renderRecord"]] = record
                reference_records.append({"trackId": expected_id, "file": original["file"],
                                          "transportSha256": sha(original_path.read_bytes()),
                                          "decodedSha256": track["sha256"], "renderRecord": original["renderRecord"],
                                          "renderRecordSha256": sha(record.read_bytes())})
            else:
                raw = inside(proof, track["file"], "r7").read_bytes()
                if sha(raw) != track["sha256"]:
                    parser.error("new raw quartet WAV does not match its recorded SHA256")
                hosted = track["file"] + SUFFIX
                mapping[track["file"]] = hosted
                encoded[hosted] = gzip.compress(predict_wav(raw), compresslevel=9, mtime=0)
                records[track["renderRecord"]] = inside(proof, track["renderRecord"], "r7")
    if len(seen) != 10 or len(encoded) != 8 or len(copied_assets) != 2 or len(records) != 10:
        parser.error("expected exactly eight new tracks and two unchanged accepted reference tracks")

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(manifest)
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["wavFiles"] = 10
    manifest["newWavFiles"] = 8
    manifest["referenceWavFiles"] = 2
    manifest["variants"] = 30
    manifest["compressedNewAudioBytes"] = sum(map(len, encoded.values()))
    manifest["compressedAudioBytes"] = manifest["compressedNewAudioBytes"] + sum(p.stat().st_size for p in copied_assets.values())
    manifest["references"] = {"hostedManifestSha256": sha(frozen_bytes),
                              "rawManifestSha256": manifest["source"]["referenceManifestSha256"],
                              "archiveUrl": manifest["archive"]["url"], "assets": reference_records,
                              "note": "Accepted R6 compressed WAV assets and source records copied byte for byte, without rerendering or re-encoding."}
    template = args.template.read_text()
    marker = "/* SKITTER_QUARTET_MANIFEST */null"
    if template.count(marker) != 1:
        parser.error("template requires exactly one quartet manifest slot")
    html = template.replace(marker, json.dumps(manifest, separators=(",", ":")).replace("<", "\\u003c"))
    destination.mkdir(parents=True, exist_ok=True)
    for relative, path in {**copied_assets, **records}.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    for relative, data in encoded.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "r7/manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    (destination / "index.html").write_text(html)
    sizes = [p.stat().st_size for p in destination.rglob("*") if p.is_file()]
    tar_bytes = archive_size(destination)
    if tar_bytes + 65_536 > LIMIT or max(sizes) > 25 * 1024 * 1024:
        raise ValueError("compact quartet pack exceeds the final archive or per-asset hosting limit")
    print(json.dumps({"destination": str(destination), "wavFiles": 10, "newWavFiles": 8,
                      "referenceWavFiles": 2, "files": len(sizes), "bytes": sum(sizes),
                      "compressedNewAudioBytes": manifest["compressedNewAudioBytes"],
                      "compressedAudioBytes": manifest["compressedAudioBytes"], "tarBytes": tar_bytes,
                      "archiveLimitBytes": LIMIT,
                      "note": "New compact site only; the complete R6 archive is left untouched."}))


if __name__ == "__main__":
    main()
