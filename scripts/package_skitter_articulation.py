#!/usr/bin/env python3
"""Add the four-track R5 proof to a retained R4 hosted pack without rerendering it."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil

from package_skitter_audition import SUFFIX, predict_wav


ARCHIVE_BANNER = ('<aside data-skitter-r4-archive style="padding:16px;background:#fff1c7;'
                  'color:#241d16;font:16px/1.5 system-ui">Archived R4 audition. '
                  '<a href="index.html" style="color:#241d16;text-decoration:underline">'
                  'Open the R5 touch and articulation comparison</a>.</aside>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("retained", type=Path, help="Complete, unchanged R4 hosted directory")
    parser.add_argument("proof", type=Path, help="Generated testkit/skitter-articulation directory")
    parser.add_argument("destination", type=Path, help="New, empty hosted output directory")
    parser.add_argument("--template", type=Path, default=Path(__file__).resolve().parents[1] /
                        "synth/src/test/resources/audition/skitter-articulation.html")
    args = parser.parse_args()
    retained, proof, destination = (p.resolve() for p in (args.retained, args.proof, args.destination))
    for source in (retained, proof):
        if source == destination or source in destination.parents or destination in source.parents:
            parser.error("destination must be separate from both input directories")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    old_index = (retained / "index.html").read_bytes()
    if old_index.count(b"<body>") != 1 or (retained / "r5").exists() or (retained / "r4.html").exists():
        parser.error("retained input must be the original R4 hosted pack")
    manifest = json.loads((proof / "r5/manifest.json").read_text())
    tracks = [track for voice in manifest["voices"] for track in voice["tracks"]]
    if len(tracks) != 4 or {t["revision"] for t in tracks} != {"R4", "R5"}:
        parser.error("proof must contain the four R4/R5 LEAD/HOLLOW raw tracks")
    mapping = {}
    encoded = {}
    for track in tracks:
        relative = Path(track["file"])
        if relative.is_absolute() or ".." in relative.parts or relative.parts[0] != "r5":
            parser.error("proof audio must remain under r5/")
        original = (proof / relative).read_bytes()
        if hashlib.sha256(original).hexdigest() != track["sha256"]:
            parser.error("proof audio does not match its recorded SHA256")
        path = relative.as_posix()
        mapping[path] = path + SUFFIX
        encoded[mapping[path]] = gzip.compress(predict_wav(original), compresslevel=9, mtime=0)

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(manifest)
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["transportNotes"] = "Lossless transport restores the original unlevelled PCM24 WAV bytes for playback and download."
    manifest["compressedAudioBytes"] = sum(map(len, encoded.values()))
    template = args.template.read_text()
    marker = "/* SKITTER_ARTICULATION_MANIFEST */null"
    if template.count(marker) != 1:
        parser.error("template needs exactly one articulation manifest marker")
    embedded = json.dumps(manifest, separators=(",", ":")).replace("<", "\\u003c")
    html = template.replace(marker, embedded)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(retained, destination, dirs_exist_ok=True)
    (destination / "r4.html").write_bytes(old_index.replace(b"<body>", b"<body>" + ARCHIVE_BANNER.encode(), 1))
    for path in (proof / "r5").rglob("*"):
        if path.is_file() and path.suffix != ".wav" and path.name != "manifest.json":
            target = destination / path.relative_to(proof)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    for relative, data in encoded.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (destination / "r5/manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    (destination / "index.html").write_text(html)
    sizes = [p.stat().st_size for p in destination.rglob("*") if p.is_file()]
    added = sum(sizes) - sum(p.stat().st_size for p in retained.rglob("*") if p.is_file())
    if added > 8_000_000 or max(sizes) > 25 * 1024 * 1024:
        raise ValueError("proof exceeds its added-byte or individual-asset budget")
    print(json.dumps({"destination": str(destination), "files": len(sizes), "bytes": sum(sizes),
                      "addedBytes": added, "compressedAudioBytes": manifest["compressedAudioBytes"],
                      "note": "Check the final Git archive against the 256 MiB limit before publication."}))


if __name__ == "__main__":
    main()
