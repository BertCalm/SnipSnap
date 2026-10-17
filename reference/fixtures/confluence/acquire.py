#!/usr/bin/env python3
"""Reproduce Confluence's CC BY 4.0 VocalSet fixtures using Python's stdlib."""

import array
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import urllib.request
import wave
import zlib


ROOT = Path(__file__).resolve().parent
MANIFEST = json.loads((ROOT / "manifest.json").read_text())
URL = MANIFEST["sourceArchiveUrl"]


def read_range(start, end):
    request = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{end}"})
    with urllib.request.urlopen(request, timeout=60) as response:
        if response.status != 206:
            raise RuntimeError("Server did not honor a partial ZIP request")
        data = response.read()
    if len(data) != end - start + 1:
        raise RuntimeError("Incomplete partial ZIP response")
    return data


def central_directory():
    record_url = "https://zenodo.org/api/records/1193957"
    with urllib.request.urlopen(record_url, timeout=60) as response:
        record = json.load(response)
    archive = next(f for f in record["files"] if f["key"] == "VocalSet.zip")
    size = archive["size"]
    tail = read_range(max(0, size - 65557), size - 1)
    at = tail.rfind(b"PK\x05\x06")
    if at < 0:
        raise RuntimeError("ZIP directory footer missing")
    footer = struct.unpack_from("<4s4H2IH", tail, at)
    directory_size, directory_offset = footer[5:7]
    data = read_range(directory_offset, directory_offset + directory_size - 1)
    members = {}
    position = 0
    while position < len(data):
        fields = struct.unpack_from("<4s6H3I5H2I", data, position)
        if fields[0] != b"PK\x01\x02":
            raise RuntimeError("Invalid ZIP directory member")
        name_len, extra_len, comment_len = fields[10:13]
        name = data[position + 46:position + 46 + name_len].decode("utf-8")
        members[name] = {
            "method": fields[4], "crc": fields[7], "compressed": fields[8],
            "size": fields[9], "offset": fields[16],
        }
        position += 46 + name_len + extra_len + comment_len
    return members


def download_member(name, member):
    offset = member["offset"]
    header = read_range(offset, offset + 29)
    fields = struct.unpack("<4s5H3I2H", header)
    if fields[0] != b"PK\x03\x04":
        raise RuntimeError("Invalid ZIP local header")
    content_offset = offset + 30 + fields[-2] + fields[-1]
    payload = read_range(content_offset, content_offset + member["compressed"] - 1)
    if member["method"] == 8:
        payload = zlib.decompress(payload, -15)
    elif member["method"] != 0:
        raise RuntimeError("Unsupported ZIP compression")
    if len(payload) != member["size"] or zlib.crc32(payload) != member["crc"]:
        raise RuntimeError(f"ZIP checksum mismatch for {name}")
    return payload


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    missing = [s for s in MANIFEST["sources"] if not (ROOT / s["originalPath"]).exists()]
    directory = central_directory() if missing else {}
    for source in MANIFEST["sources"]:
        original = ROOT / source["originalPath"]
        if not original.exists():
            payload = download_member(source["originalArchiveMember"], directory[source["originalArchiveMember"]])
            if digest(payload) != source["originalSha256"]:
                raise RuntimeError("Pinned original SHA-256 mismatch")
            original.parent.mkdir(parents=True, exist_ok=True)
            original.write_bytes(payload)
        if digest(original.read_bytes()) != source["originalSha256"]:
            raise RuntimeError(f"Original content changed: {original}")
        with wave.open(str(original), "rb") as recording:
            if recording.getnchannels() != 1 or recording.getsampwidth() != 2 or recording.getframerate() != 44100:
                raise RuntimeError("Expected mono 44.1 kHz PCM16 original")
            recording.setpos(source["originalCropStartFrame"])
            pcm = array.array("h", recording.readframes(source["originalCropEndFrame"] - source["originalCropStartFrame"]))
        if sys.byteorder != "little":
            pcm.byteswap()
        maximum = max(abs(x) for x in pcm)
        gain = 10 ** (-3 / 20) / (maximum / 32768)
        output = array.array("h", (max(-32768, min(32767, round((x / 32768) * gain * 32767))) for x in pcm))
        if sys.byteorder != "little":
            output.byteswap()
        path = ROOT / source["path"]
        with wave.open(str(path), "wb") as recording:
            recording.setnchannels(1)
            recording.setsampwidth(2)
            recording.setframerate(44100)
            recording.writeframes(output.tobytes())
        if digest(path.read_bytes()) != source["sha256"]:
            raise RuntimeError(f"Canonical SHA-256 mismatch: {path}")
        print(f"Verified {source['id']}: {source['frames']} frames")


if __name__ == "__main__":
    main()
