#!/usr/bin/env python3
"""Package SKITTER with lossless PCM24 second differences and gzip transport.

The listening page restores each original WAV byte, including sampler metadata.
NumPy optionally accelerates the predictor; the standard-library path is exact.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import json
from pathlib import Path
import re
import shutil
import struct

try:
    import numpy as np
except ImportError:
    np = None


SUFFIX = ".pcm24d2.gz"


def predict_wav(original):
    data = bytearray(original)
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("expected RIFF WAVE")
    position = 12
    fmt = None
    data_chunks = []
    while position + 8 <= len(data):
        kind = data[position:position + 4]
        size = struct.unpack_from("<I", data, position + 4)[0]
        start = position + 8
        if start + size > len(data):
            raise ValueError("truncated WAV chunk")
        if kind == b"fmt ":
            fmt = struct.unpack_from("<HHI", data, start) + (struct.unpack_from("<H", data, start + 14)[0],)
        elif kind == b"data":
            data_chunks.append((start, size))
        position = start + size + (size & 1)
    if fmt != (1, 1, 44100, 24) or len(data_chunks) != 1:
        raise ValueError("expected one mono 44.1 kHz PCM24 data chunk")
    start, size = data_chunks[0]
    if size % 3:
        raise ValueError("partial PCM24 frame")
    if np is not None:
        triplets = np.frombuffer(data, dtype=np.uint8, count=size, offset=start).reshape(-1, 3)
        samples = triplets[:, 0].astype(np.int32) | (triplets[:, 1].astype(np.int32) << 8) | (triplets[:, 2].astype(np.int32) << 16)
        differences = samples.copy()
        differences[1:] -= 2 * samples[:-1]
        differences[2:] += samples[:-2]
        differences &= 0xffffff
        encoded = np.empty_like(triplets)
        encoded[:, 0] = differences & 255
        encoded[:, 1] = (differences >> 8) & 255
        encoded[:, 2] = (differences >> 16) & 255
        data[start:start + size] = encoded.tobytes()
    else:
        previous = previous2 = 0
        for offset in range(start, start + size, 3):
            sample = data[offset] | data[offset + 1] << 8 | data[offset + 2] << 16
            difference = (sample - 2 * previous + previous2) & 0xffffff
            data[offset] = difference & 255
            data[offset + 1] = (difference >> 8) & 255
            data[offset + 2] = (difference >> 16) & 255
            previous2, previous = previous, sample
    return bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--template", type=Path, required=True, help="Current repository audition player template")
    args = parser.parse_args()
    source, destination = args.source.resolve(), args.destination.resolve()
    if not (source / "manifest.json").is_file():
        parser.error("source needs a complete generated manifest.json")
    if destination == source or source in destination.parents:
        parser.error("destination must be outside the generated source directory")
    if destination.exists() and any(destination.iterdir()):
        parser.error("destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    wavs = sorted(source.rglob("*.wav"))
    mapping = {p.relative_to(source).as_posix(): p.relative_to(source).as_posix() + SUFFIX for p in wavs}

    def compress(path):
        target = destination / mapping[path.relative_to(source).as_posix()]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(gzip.compress(predict_wav(path.read_bytes()), compresslevel=9, mtime=0))

    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(compress, wavs))
    for path in source.rglob("*"):
        if path.is_file() and path.suffix != ".wav" and path.name not in {"manifest.json", "index.html"}:
            target = destination / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        return mapping.get(value, value) if isinstance(value, str) else value

    manifest = rewrite(json.loads((source / "manifest.json").read_text()))
    manifest["transportEncoding"] = "pcm24-delta2-gzip"
    manifest["transportNotes"] = "Playback and WAV downloads restore the original PCM24 WAV bytes from lossless second differences and gzip. No sample conversion or level change."
    (destination / "manifest.json").write_text(json.dumps(manifest, separators=(",", ":")) + "\n")
    html = args.template.read_text()
    embedded = json.dumps(manifest, separators=(",", ":")).replace("<", "\\u003c")
    pattern = r'(<script id="audition-manifest" type="application/json">)[\s\S]*?(</script>)'
    if "/* SKITTER_MANIFEST */null" in html:
        html = html.replace("/* SKITTER_MANIFEST */null", embedded)
    elif "__SKITTER_MANIFEST__" in html:
        html = html.replace("__SKITTER_MANIFEST__", embedded)
    elif re.search(pattern, html):
        html = re.sub(pattern, lambda m: m[1] + embedded + m[2], html, count=1)
    else:
        raise ValueError("player has no manifest slot")
    (destination / "index.html").write_text(html)
    sizes = [p.stat().st_size for p in destination.rglob("*") if p.is_file()]
    print(json.dumps({"destination": str(destination), "wavFiles": len(wavs), "files": len(sizes), "bytes": sum(sizes), "maxAssetBytes": max(sizes)}))


if __name__ == "__main__":
    main()
