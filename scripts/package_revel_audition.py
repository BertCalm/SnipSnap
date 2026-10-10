#!/usr/bin/env python3
"""Package a generated REVEL audition with lossless gzip asset transport.

The player restores original PCM24 WAV and diagnostic bytes in the browser.
No synthesis, level changes, or sample conversion occurs here.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import json
from pathlib import Path
import re
import shutil
import zlib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--template", type=Path, help="Use the repository player template")
    args = parser.parse_args()
    source = args.source.resolve()
    destination = args.destination.resolve()
    if not (source / "manifest.json").is_file():
        parser.error("source must contain a generated manifest.json")
    if destination == source or source in destination.parents:
        parser.error("destination must be outside the generated source directory")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)

    assets = [p for folder in ("raw", "matched", "diagnostics")
              for p in (source / folder).rglob("*") if p.is_file()]
    mapping = {p.relative_to(source).as_posix(): p.relative_to(source).as_posix() + ".gz"
               for p in assets}

    def compress(path):
        target = destination / mapping[path.relative_to(source).as_posix()]
        target.parent.mkdir(parents=True, exist_ok=True)
        with path.open("rb") as src, target.open("wb") as dst:
            # Filtered deflation suits the repeated numeric fields in traces;
            # the resulting stream is ordinary gzip and restores exact bytes.
            strategy = zlib.Z_FILTERED if path.suffix == ".csv" else zlib.Z_DEFAULT_STRATEGY
            encoder = zlib.compressobj(9, zlib.DEFLATED, 31, 8, strategy)
            while chunk := src.read(1024 * 1024):
                dst.write(encoder.compress(chunk))
            dst.write(encoder.flush())

    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(compress, assets))

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(json.loads((source / "manifest.json").read_text()))
    manifest["transportEncoding"] = "gzip"
    manifest["transportNotes"] = (
        "Audio and downloadable diagnostics use lossless gzip transport. "
        "The player restores the original PCM24 WAV and diagnostic bytes for "
        "playback and downloads; source and recipe fingerprints are unchanged."
    )
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    (destination / "manifest.json.gz").write_bytes(gzip.compress(manifest_bytes, compresslevel=9, mtime=0))
    html = (args.template if args.template else source / "index.html").read_text()
    embedded = json.dumps(manifest, separators=(",", ":")).replace("</", "<\\/")
    pattern = r'(<script id="audition-manifest" type="application/json">)[\s\S]*?(</script>)'
    if re.search(pattern, html):
        html = re.sub(pattern, lambda match: match[1] + embedded + match[2], html, count=1)
    elif "__AUDITION_MANIFEST__" in html:
        html = html.replace("__AUDITION_MANIFEST__", embedded)
    else:
        raise ValueError("player has no embedded manifest slot")
    (destination / "index.html").write_text(html)
    for path in source.iterdir():
        if path.is_file() and path.name not in ("manifest.json", "index.html"):
            shutil.copy2(path, destination / path.name)
    readme = destination / "README.md"
    with readme.open("a") as note:
        note.write("\nHosting transport: " + manifest["transportNotes"] + "\n")
    size = sum(path.stat().st_size for path in destination.rglob("*") if path.is_file())
    print(json.dumps({"destination": str(destination), "assetCount": len(assets),
                      "expandedBytes": size, "expandedMiB": size / 1048576}))


if __name__ == "__main__":
    main()
