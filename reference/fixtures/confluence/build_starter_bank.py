#!/usr/bin/env python3
"""Build the bounded runtime bank from explicitly curated, wordless source fixtures.

This is a packaging step, not source selection or audio processing. WAV bytes are copied exactly;
the source acquisition/curation script and original recordings remain beside this script.
"""

import array
import hashlib
import json
import shutil
import struct
import sys
import wave
from pathlib import Path


FIXTURES = Path(__file__).resolve().parent
ROOT = FIXTURES.parents[2]
OUTPUT = ROOT / "synth/src/main/resources/confluence/wordless-starter-v1"

VOICE_DETAILS = {
    "low-ah": ("LOW AH", ["low", "straight", "sustained", "ah"], "FOUNDATION"),
    "breathy-arc": ("BREATHY ARC", ["airy", "breathy", "moving", "ah"], "AIRY"),
    "clear-arc": ("CLEAR ARC", ["clear", "straight", "moving", "ah"], "ANSWER"),
    "round-oo": ("ROUNDED OO", ["low", "rounded", "sustained", "oo"], "FOUNDATION"),
}


def canonical_pcm_hash(path):
    with wave.open(str(path), "rb") as stream:
        assert stream.getnchannels() == 1 and stream.getframerate() == 44100 and stream.getsampwidth() == 2
        frames = stream.getnframes()
        pcm = array.array("h", stream.readframes(frames))
    if sys.byteorder != "little":
        pcm.byteswap()
    # Match SnipSnap WavReader's established PCM16 decode exactly: /32767f with
    # the negative full-scale code clamped to -1. The canonical identity hashes
    # decoded float PCM, not a generic normalized WAV interpretation.
    floats = array.array("f", (max(-1.0, sample / 32767) for sample in pcm))
    assert floats.itemsize == 4
    if sys.byteorder != "little":
        floats.byteswap()
    return hashlib.sha256(struct.pack("<ii", 44100, frames) + floats.tobytes()).hexdigest()


def assembly(name, voices, low_id, slots, gains, *, seed, space, divergence, call, delay, rest, breath):
    bindings = {"FOUNDATION": low_id, "AIRY": "breathy-arc", "ANSWER": "clear-arc"}
    return bindings, {
        "engine": "CONFLUENCE_ASSEMBLY_PROTOTYPE", "schema": 1, "model": 1, "analysis": 1,
        "name": name, "seed": seed, "seconds": 24, "space": space, "divergence": divergence,
        "callStartSeconds": call, "answerDelaySeconds": delay, "exchangeRestSeconds": rest, "breathSeconds": breath,
        "roles": [{"role": role, "gain": gain, "performerIds": ids, "region": voices[bindings[role]]["region"]}
                  for role, ids, gain in zip(["FOUNDATION", "AIRY", "ANSWER"], slots, gains)],
    }


def main():
    metadata = json.loads((FIXTURES / "manifest.json").read_text())
    catalogue = {source["id"]: source for source in metadata["sources"]}
    assert set(metadata["starterVoiceIds"]) == set(VOICE_DETAILS)
    (OUTPUT / "voices").mkdir(parents=True, exist_ok=True)
    voices = []
    for id, (title, styles, role) in VOICE_DETAILS.items():
        source = catalogue[metadata["starterVoiceIds"][id]]
        assert source["wordless"] is True
        assert source["license"] == "CC BY 4.0"
        assert "row" not in source["originalArchiveMember"].lower()
        path = FIXTURES / source["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == source["sha256"]
        destination = f"voices/{id}.wav"
        shutil.copyfile(path, OUTPUT / destination)
        region = {
            "assetHash": canonical_pcm_hash(path), "startFrame": source["phraseStartFrame"], "endFrame": source["phraseEndFrame"],
            "mode": "PITCHED", "referenceMidi": source["referenceMidi"],
            "sustainStartFrame": source.get("sustainStartFrame"), "sustainEndFrame": source.get("sustainEndFrame"),
        }
        voices.append({
            "id": id, "title": title, "assetPath": destination, "fixtureId": source["id"],
            "styles": styles, "technique": source["sourceTechnique"], "wordless": True,
            "roles": [role], "fileSha256": source["sha256"], "frames": source["frames"],
            "region": region, "provenance": source,
        })
    by_id = {voice["id"]: voice for voice in voices}
    configurations = [
        ("first-gathering", "FIRST GATHERING", "A low ah foundation, the complete breathy upper arc, and a clear wordless answer. Eight persistent transformed performers, close enough to hear the source gestures.",
         "low-ah", [[0, 1, 2], [3, 4, 5], [6, 7]], [.55, .45, 1.05],
         dict(seed=20261018, space=.18, divergence=.18, call=1.2, delay=.4, rest=.8, breath=.2)),
        ("breathing-room", "BREATHING ROOM", "Six performers leave more space around the complete breathy arc. A quieter low foundation and longer individual breath windows give the airy group more room.",
         "low-ah", [[0, 1], [2, 3], [4, 5]], [.4, .64, .9],
         dict(seed=20261019, space=.48, divergence=.1, call=1.8, delay=.7, rest=.9, breath=.28)),
        ("round-response", "ROUND RESPONSE", "A rounded oo recording supports a smaller upper group and three clear wordless responders. A drier room and shorter answer gap keep the exchange close.",
         "round-oo", [[0, 1, 2], [3, 4], [5, 6, 7]], [.62, .33, 1.1],
         dict(seed=20261020, space=.08, divergence=.35, call=.65, delay=.2, rest=.7, breath=.16)),
    ]
    presets = []
    for id, title, description, low_id, slots, gains, settings in configurations:
        bindings, recipe = assembly(title, by_id, low_id, slots, gains, **settings)
        presets.append({"id": id, "title": title, "description": description, "roleVoiceIds": bindings, "recipe": recipe})
    bank = {
        "format": "snipsnap-confluence-voice-bank", "schema": 1, "id": "confluence-wordless-starter", "version": 1,
        "content": "wordless-vocals", "mainPresetId": "first-gathering",
        "license": {"id": "CC-BY-4.0", "url": "https://creativecommons.org/licenses/by/4.0/", "path": "LICENSE-CC-BY-4.0.txt"},
        "voices": voices, "presets": presets,
    }
    (OUTPUT / "bank.json").write_text(json.dumps(bank, indent=2, ensure_ascii=False) + "\n")
    shutil.copyfile(FIXTURES / "LICENSE-CC-BY-4.0.txt", OUTPUT / "LICENSE-CC-BY-4.0.txt")
    print(f"Wrote {len(voices)} wordless voices and {len(presets)} assembly presets to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
