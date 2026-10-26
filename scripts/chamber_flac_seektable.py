"""Add a checked FLAC seek table while preserving every encoded audio frame."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess


def _metadata(data: bytes) -> tuple[list[tuple[int, bytes]], int]:
    if data[:4] != b"fLaC":
        raise ValueError("Expected a native FLAC container")
    blocks = []
    offset = 4
    while True:
        if offset + 4 > len(data):
            raise ValueError("Truncated FLAC metadata header")
        flag = data[offset]
        size = int.from_bytes(data[offset + 1:offset + 4], "big")
        end = offset + 4 + size
        if end > len(data) or flag & 127 == 127:
            raise ValueError("Invalid FLAC metadata block")
        blocks.append((flag & 127, data[offset + 4:end]))
        offset = end
        if flag & 128:
            break
    if blocks[0][0] != 0 or len(blocks[0][1]) != 34 or sum(k == 0 for k, _ in blocks) != 1:
        raise ValueError("Expected exactly one leading FLAC STREAMINFO block")
    return blocks, offset


def _packets(ffprobe: str, path: Path) -> list[dict]:
    result = subprocess.run(
        [ffprobe, "-v", "error", "-select_streams", "a:0", "-show_packets",
         "-show_entries", "packet=pts,duration,pos,size", "-of", "json", str(path)],
        check=True, capture_output=True, text=True,
    )
    packets = json.loads(result.stdout)["packets"]
    return [{key: int(packet[key]) for key in ("pts", "duration", "pos", "size")}
            for packet in packets]


def _pcm(ffmpeg: str, path: Path) -> bytes:
    return subprocess.run(
        [ffmpeg, "-nostdin", "-v", "error", "-i", str(path), "-f", "s16le",
         "-c:a", "pcm_s16le", "-"], check=True, capture_output=True,
    ).stdout


def index_flac(ffmpeg: str, ffprobe: str, path: Path) -> dict:
    """Index each real frame, then independently check frame bytes, points and PCM.

    Native browser seeking can fail on otherwise decodable FFmpeg FLAC files
    without SEEKTABLE metadata. Existing padding is reused when possible. No
    audio frame is re-encoded, resampled or changed.
    """
    original = path.read_bytes()
    blocks, audio_start = _metadata(original)
    packets = _packets(ffprobe, path)
    frame_count = int.from_bytes(blocks[0][1][10:18], "big") & ((1 << 36) - 1)
    expected_sample, expected_offset = 0, audio_start
    points = []
    for packet in packets:
        sample, offset, frames, size = (packet[key] for key in ("pts", "pos", "duration", "size"))
        if (sample != expected_sample or offset != expected_offset or not 0 < frames < 65536
                or size < 2 or original[offset] != 255 or original[offset + 1] & 254 != 248):
            raise ValueError(f"Non-contiguous or invalid FLAC audio frame: {path}")
        points.append((sample, offset - audio_start, frames))
        expected_sample += frames
        expected_offset += size
    if not points or expected_sample != frame_count or expected_offset != len(original):
        raise ValueError(f"FLAC frame inventory does not cover its complete audio: {path}")
    table = b"".join(sample.to_bytes(8, "big") + offset.to_bytes(8, "big")
                     + frames.to_bytes(2, "big") for sample, offset, frames in points)
    retained = [(kind, data) for kind, data in blocks if kind not in (1, 3)]
    retained.append((3, table))
    remaining = audio_start - 4 - sum(4 + len(data) for _, data in retained)
    if remaining >= 4:
        retained.append((1, bytes(remaining - 4)))
    encoded_metadata = b"fLaC" + b"".join(
        bytes([kind | (128 if i == len(retained) - 1 else 0)])
        + len(data).to_bytes(3, "big") + data
        for i, (kind, data) in enumerate(retained)
    )
    indexed = encoded_metadata + original[audio_start:]
    original_pcm = _pcm(ffmpeg, path)
    path.write_bytes(indexed)
    checked = path.read_bytes()
    new_blocks, new_audio_start = _metadata(checked)
    actual_tables = [data for kind, data in new_blocks if kind == 3]
    checked_packets = _packets(ffprobe, path)
    checked_points = [(packet["pts"], packet["pos"] - new_audio_start, packet["duration"])
                      for packet in checked_packets]
    decoded_pcm = _pcm(ffmpeg, path)
    if (actual_tables != [table] or checked_points != points
            or checked[new_audio_start:] != original[audio_start:] or decoded_pcm != original_pcm):
        raise ValueError(f"Indexed FLAC failed frame, seek-point or PCM identity checks: {path}")
    digest = lambda data: hashlib.sha256(data).hexdigest()
    return {"seekPoints": len(points), "everyFrameIndexed": True,
            "originalContainerSha256": digest(original), "indexedContainerSha256": digest(checked),
            "encodedAudioFramesSha256": digest(original[audio_start:]),
            "encodedAudioFramesIdentical": True, "seekPointsVerified": True,
            "pcmSha256": digest(original_pcm), "decodedPcmSha256": digest(decoded_pcm),
            "samplesIdentical": True, "originalAudioOffset": audio_start,
            "indexedAudioOffset": new_audio_start}
