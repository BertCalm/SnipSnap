#!/usr/bin/env python3
"""Add exactly two R6 WAVs to the frozen R5 site, preserving all earlier assets.

Contract: retained is the complete published R5 hosted tree; proof is the raw
SkitterCharacterR6Audition output. R4/R5 comparison audio is reused by original
path/hash/cue, never encoded again. Only r6/ is added. Old index moves to r5.html
with one banner insertion; every other old file, including r4.html, stays exact.
The current page and r6/manifest.json contain the rewritten hosted asset paths.
"""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import tarfile

from package_skitter_audition import SUFFIX, predict_wav


ARCHIVE_BANNER = (
    '<aside data-skitter-r5-archive style="padding:16px;background:#fff1c7;'
    'color:#241d16;font:16px/1.5 system-ui">Archived R5 articulation proof. '
    '<a href="index.html" style="color:#241d16;text-decoration:underline">'
    'Open the R4 / R5 / R6 body comparison</a>. '
    '<a href="r4.html" style="color:#241d16;text-decoration:underline">'
    'Open the full R4 archive</a>.</aside>'
)
LIMIT = 256 * 1024 * 1024


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inside(root, relative, namespace):
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != namespace:
        raise ValueError(f"asset must remain under {namespace}/: {relative}")
    resolved = (root / path).resolve()
    if root not in resolved.parents:
        raise ValueError("asset leaves its input directory")
    return resolved


def restored_asset(path):
    """Decode the existing transport for a targeted baseline integrity check."""
    data = bytearray(path.read_bytes())
    if not path.name.endswith(SUFFIX):
        return bytes(data)
    data = bytearray(gzip.decompress(data))
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("invalid frozen WAV transport")
    at = 12
    while at + 8 <= len(data):
        count = struct.unpack_from("<I", data, at + 4)[0]
        start = at + 8
        if start + count > len(data):
            raise ValueError("truncated frozen WAV")
        if data[at:at + 4] == b"data":
            if count % 3:
                raise ValueError("partial frozen PCM24 frame")
            previous = previous2 = 0
            for i in range(start, start + count, 3):
                difference = data[i] | data[i + 1] << 8 | data[i + 2] << 16
                x = (difference + 2 * previous - previous2) & 0xffffff
                data[i], data[i + 1], data[i + 2] = x & 255, (x >> 8) & 255, (x >> 16) & 255
                previous2, previous = previous, x
        at = start + count + (count & 1)
    return bytes(data)


class Counter:
    def __init__(self):
        self.size = 0

    def write(self, data):
        self.size += len(data)
        return len(data)

    def flush(self):
        pass


def archive_size(root):
    """Count a complete GNU tar stream without storing a second 250+ MB copy."""
    counter = Counter()
    with tarfile.open(fileobj=counter, mode="w|", format=tarfile.GNU_FORMAT) as archive:
        for path in sorted(root.rglob("*")):
            archive.add(path, arcname=path.relative_to(root).as_posix(), recursive=False)
    return counter.size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("retained", type=Path, help="Complete unchanged R5 hosted tree")
    parser.add_argument("proof", type=Path, help="Raw generated R6 proof directory")
    parser.add_argument("destination", type=Path, help="New empty hosted output directory")
    parser.add_argument("--template", type=Path, default=Path(__file__).resolve().parents[1] /
                        "synth/src/test/resources/audition/skitter-character-r6.html")
    args = parser.parse_args()
    retained, proof, destination = (p.resolve() for p in (args.retained, args.proof, args.destination))
    for path in (retained, proof):
        if path == destination or path in destination.parents or destination in path.parents:
            parser.error("destination must be separate from both inputs")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    old_index = (retained / "index.html").read_bytes()
    if old_index.count(b"<body>") != 1 or (retained / "r6").exists() or (retained / "r5.html").exists():
        parser.error("retained must be the original complete R5 hosted tree")
    frozen_manifest_bytes = (retained / "r5/manifest.json").read_bytes()
    frozen = json.loads(frozen_manifest_bytes)
    manifest = json.loads((proof / "r6/manifest.json").read_text())
    frozen_tracks = {t["id"]: t for v in frozen["voices"] for t in v["tracks"]}
    tracks = [t for v in manifest["voices"] for t in v["tracks"]]
    if manifest["revision"] != "R6" or len(tracks) != 6 or len(frozen_tracks) != 4:
        parser.error("expected two voices, each with R4/R5/R6; four frozen R5 proof tracks")
    mapping, encoded = {}, {}
    checked_baselines = set()
    for track in tracks:
        if track["revision"] in {"R4", "R5"}:
            original = frozen_tracks[track["id"]]
            for key in ("revision", "sha256", "frames", "seconds", "renderRecord"):
                if track[key] != original[key]:
                    parser.error(f"frozen track field differs: {track['id']}/{key}")
            cues = {c["id"]: c for c in original["cues"]}
            for cue in track["cues"]:
                for key in ("startFrame", "endFrame", "velocity", "hold"):
                    if cue[key] != cues[cue["id"]][key]:
                        parser.error(f"frozen cue timing/input differs: {track['id']}/{cue['id']}/{key}")
            original_path = inside(retained, original["file"], "r5")
            if sha(restored_asset(original_path)) != track["sha256"]:
                parser.error("retained baseline WAV does not match the frozen raw recording")
            mapping[track["file"]] = original["file"]
            checked_baselines.add(track["id"])
        elif track["revision"] == "R6":
            original_path = inside(proof, track["file"], "r6")
            raw = original_path.read_bytes()
            if sha(raw) != track["sha256"]:
                parser.error("new R6 WAV does not match its recorded SHA256")
            hosted = track["file"] + SUFFIX
            mapping[track["file"]] = hosted
            encoded[hosted] = gzip.compress(predict_wav(raw), compresslevel=9, mtime=0)
        else:
            parser.error("unknown revision")
    if len(encoded) != 2 or checked_baselines != set(frozen_tracks):
        parser.error("only two new WAVs may be encoded; every frozen R4/R5 track must be reused")

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(manifest)
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["compressedAudioBytes"] = sum(map(len, encoded.values()))
    manifest["retention"] = {"r4Page": "r4.html", "r5Page": "r5.html",
                             "r5IndexSha256": sha(old_index), "r5HostedManifestSha256": sha(frozen_manifest_bytes),
                             "note": "Every earlier non-index asset remains byte-exact; old index differs only by the archival banner inserted after body."}
    template = args.template.read_text()
    marker = "/* SKITTER_R6_MANIFEST */null"
    if template.count(marker) != 1:
        parser.error("template needs one R6 manifest marker")
    html = template.replace(marker, json.dumps(manifest, separators=(",", ":")).replace("<", "\\u003c"))
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(retained, destination, dirs_exist_ok=True)
    (destination / "r5.html").write_bytes(old_index.replace(b"<body>", b"<body>" + ARCHIVE_BANNER.encode(), 1))
    for track in tracks:
        if track["revision"] == "R6":
            record = inside(proof, track["renderRecord"], "r6")
            target = destination / track["renderRecord"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(record, target)
    for relative, data in encoded.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "r6/manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    (destination / "index.html").write_text(html)
    old_bytes = sum(p.stat().st_size for p in retained.rglob("*") if p.is_file())
    sizes = [p.stat().st_size for p in destination.rglob("*") if p.is_file()]
    added = sum(sizes) - old_bytes
    tar_bytes = archive_size(destination)
    # Leave slack for the Git archive's global header; root also checks the actual upload tar.
    if added > 2_000_000 or tar_bytes + 65_536 > LIMIT or max(sizes) > 25 * 1024 * 1024:
        raise ValueError(f"R6 exceeds its added-byte/final archive budget: added={added}, tar={tar_bytes}")
    print(json.dumps({"destination": str(destination), "newWavFiles": 2, "reusedWavFiles": 4,
                      "files": len(sizes), "bytes": sum(sizes), "addedBytes": added,
                      "compressedAudioBytes": manifest["compressedAudioBytes"], "tarBytes": tar_bytes,
                      "archiveLimitBytes": LIMIT,
                      "note": "Full GNU tar measured. Verify the final Git archive before publication."}))


if __name__ == "__main__":
    main()
