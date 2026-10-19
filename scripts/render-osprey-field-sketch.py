#!/usr/bin/env python3
"""Render a reproducible dry score from the unchanged OSPREY R3 handoff.

Requires Python 3 and NumPy. This sequences existing PCM24 samples; it does
not run the synth engine or establish how an MPC will play the MIDI file.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import struct
import subprocess
import sys
import wave
import xml.etree.ElementTree as ET
import zipfile

import numpy as np


RATE = 44_100
PPQ = 960
BPM = 90
BEATS_PER_BAR = 4
SCORE_BARS = 16
TAIL_BARS = 3
TOTAL_BARS = SCORE_BARS + TAIL_BARS
TEMPO_US = 666_667  # MIDI stores whole microseconds; nominal tempo is 90 BPM.
NOTE_TICKS = PPQ // 4
MIDI_CHANNEL = 10  # Human-facing numbering; status bytes use channel 9.
STEM = "osprey-r3-field-sketch"
SCHEDULE = ((1, 1), (3, 4), (5, 7), (7, 10), (9, 13), (10, 2),
            (11, 5), (12, 8), (13, 11), (14, 15), (15, 3), (16, 16))
SECTION_LABELS = ((1, "Five coastlines"), (10, "Cross-current dialogue"),
                  (14, "Weather turns"), (16, "Between shores"),
                  (17, "Let the tails finish"))


class InputError(ValueError):
    """An input does not match the handoff or the fixed dry render contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InputError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def relative_path(value: str) -> str:
    require(isinstance(value, str) and value != "", "missing archive-relative path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in value,
            "unsafe archive-relative path: " + value)
    require(str(path) == value, "noncanonical archive-relative path: " + value)
    return value


def decode_pcm24(blob: bytes) -> np.ndarray:
    with wave.open(io.BytesIO(blob), "rb") as reader:
        require(reader.getcomptype() == "NONE", "source WAV must be uncompressed PCM")
        require((reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getnframes())
                == (2, 3, RATE, RATE * 8), "source WAV must be 8-second PCM24, 44.1 kHz stereo")
        pcm = reader.readframes(reader.getnframes())
    require(len(pcm) == RATE * 8 * 2 * 3, "truncated source WAV")
    octets = np.frombuffer(pcm, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
    values = octets[:, 0] | (octets[:, 1] << 8) | (octets[:, 2] << 16)
    values = np.where(values >= 0x800000, values - 0x1000000, values)
    return (values.astype(np.float64) / 8_388_608.0).reshape(-1, 2)


def load_inputs(kit_path: Path, handoff_path: Path) -> tuple[dict, dict, dict, dict, bytes]:
    receipt_blob = handoff_path.read_bytes()
    receipt = json.loads(receipt_blob)
    require(receipt.get("engine") == "OSPREY" and receipt.get("renderModel") == 3,
            "requires an OSPREY model-3 handoff")
    require((receipt.get("sampleRate"), receipt.get("channels"), receipt.get("bitDepth"))
            == (RATE, 2, 24), "handoff audio format is not PCM24, 44.1 kHz stereo")
    archive_blob = kit_path.read_bytes()
    archive = receipt["archive"]
    require(len(archive_blob) == archive["bytes"], "kit archive byte count does not match handoff")
    require(sha256(archive_blob) == archive["sha256"], "kit archive SHA-256 does not match handoff")
    root = relative_path(receipt["bundleRoot"])
    kit_name = root + "/" + relative_path(receipt["snipsnapKit"])
    samples: dict[int, np.ndarray] = {}
    with zipfile.ZipFile(io.BytesIO(archive_blob)) as bundle:
        entries = bundle.namelist()
        require(len(entries) == len(set(entries)), "duplicate ZIP entry")
        expected = archive["entries"]
        require(len(entries) == archive["entryCount"] == len(expected), "ZIP entry count mismatch")
        inventory = {relative_path(row["path"]): row for row in expected}
        require(len(inventory) == len(expected) and set(entries) == set(inventory),
                "ZIP entry inventory mismatch")
        for name in entries:
            blob = bundle.read(name)
            row = inventory[name]
            require(len(blob) == row["bytes"] and sha256(blob) == row["sha256"],
                    "ZIP entry hash or length mismatch: " + name)
        kit = json.loads(bundle.read(kit_name))
        require(len(kit["pads"]) == 16 and len(receipt["pads"]) == 16, "requires all sixteen source pads")
        kit_pads = {row["slot"]: row for row in kit["pads"]}
        pads = {row["slot"]: row for row in receipt["pads"]}
        require(set(kit_pads) == set(pads) == set(range(1, 17)), "source pad slots must be 1 through 16")
        triggers = [pad["mpcTriggerMidi"] for pad in pads.values()]
        require(all(type(note) is int and 0 <= note <= 127 for note in triggers)
                and len(set(triggers)) == 16, "invalid or duplicate MPC trigger notes")
        # Check the receipt's irregular trigger map against the actual exported XPM.
        xpm_blob = bundle.read(root + "/" + relative_path(receipt["compatibilityProgram"]))
        require(b"<!DOCTYPE" not in xpm_blob.upper(), "unsupported XPM document type")
        xpm = ET.fromstring(xpm_blob)
        xpm_map = {int(row.attrib["number"]): int(row.findtext("Note", "-1"))
                   for row in xpm.findall("./Program/PadNoteMap/PadNote")}
        for slot in range(1, 17):
            pad, stored = pads[slot], kit_pads[slot]
            require(pad["label"] == f"A{slot:02}", "pad label/slot mismatch")
            require(xpm_map.get(slot) == pad["mpcTriggerMidi"], "MPC trigger does not match exported XPM")
            require(pad.get("sourceMidi") == 60 and pad.get("durationSeconds") == 8
                    and pad.get("modelVersion") == 3 and pad.get("velocity") == 1
                    and pad.get("loop") is False and pad.get("oneShot") is True
                    and pad.get("fx") is None and pad.get("normalizationGain") == 1,
                    "source pad violates the unchanged dry C4 one-shot contract")
            level = stored.get("level")
            require(type(level) in (int, float) and math.isfinite(level) and 0 < level <= 1,
                    "invalid source pad level")
            require(stored.get("pan") == 0.5 and stored.get("tuneCoarse") == 0
                    and stored.get("tuneFine") == 0 and stored.get("muteGroup") == 0
                    and stored.get("oneShot") is True, "source kit requires centered, untuned, unchoked one-shots")
            patch = stored["recipe"]["patch"]
            require(patch.get("engine") == "OSPREY" and patch.get("modelVersion") == 3
                    and patch.get("midi") == 60 and patch.get("durationSeconds") == 8
                    and patch.get("velocity") == 1 and patch.get("macros", {}).get("SPACE") == 0,
                    "stored recipe is not the dry model-3 C4 source")
            raw_name = root + "/" + relative_path(pad["rawFile"])
            require(raw_name == str(PurePosixPath(kit_name).parent / relative_path(stored["sample"])),
                    "kit.json sample filename does not match handoff")
            blob = bundle.read(raw_name)
            require(sha256(blob) == pad["rawSha256"], "source WAV SHA-256 does not match handoff")
            samples[slot] = decode_pcm24(blob)
        require(len([name for name in entries if name.lower().endswith(".wav")]) == 16,
                "source archive must contain exactly sixteen WAVs")
    return receipt, kit_pads, pads, samples, receipt_blob


def vlq(value: int) -> bytes:
    require(0 <= value < (1 << 28), "MIDI delta outside supported range")
    result = [value & 127]
    value >>= 7
    while value:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(reversed(result))


def meta(kind: int, data: bytes) -> bytes:
    return bytes((255, kind)) + vlq(len(data)) + data


def midi_track(events: list[tuple[int, bytes]], end_tick: int) -> bytes:
    body = bytearray()
    previous = 0
    for tick, message in sorted(events, key=lambda row: row[0]):
        body.extend(vlq(tick - previous))
        body.extend(message)
        previous = tick
    body.extend(vlq(end_tick - previous))
    body.extend(meta(0x2F, b""))
    return b"MTrk" + struct.pack(">I", len(body)) + body


def make_midi(events: list[dict], sections: list[dict]) -> bytes:
    end_tick = TOTAL_BARS * BEATS_PER_BAR * PPQ
    conductor = [(0, meta(3, b"OSPREY R3 Field sketch")),
                 (0, meta(0x51, TEMPO_US.to_bytes(3, "big"))),
                 (0, meta(0x58, bytes((4, 2, 24, 8))))]
    conductor += [(section["tick"], meta(6, section["label"].encode("utf-8"))) for section in sections]
    notes = [(0, meta(3, b"OSPREY R3 unchanged drum pads"))]
    for event in events:
        note = event["mpcTriggerMidi"]
        notes.append((event["tick"], bytes((0x99, note, 127))))
        notes.append((event["noteOffTick"], bytes((0x89, note, 0))))
    return (b"MThd" + struct.pack(">IHHH", 6, 1, 2, PPQ)
            + midi_track(conductor, end_tick) + midi_track(notes, end_tick))


def diagnostics(samples: np.ndarray) -> dict:
    window_frames = RATE // 5
    energy = np.mean(samples * samples, axis=1)
    cumulative = np.concatenate(([0.0], np.cumsum(energy, dtype=np.float64)))
    window_energy = (cumulative[window_frames:] - cumulative[:-window_frames]) / window_frames
    loudest_start = int(np.argmax(window_energy))
    return {"finite": bool(np.isfinite(samples).all()),
            "peak": float(np.max(np.abs(samples))),
            "rms": float(np.sqrt(np.mean(energy))),
            "loudest200msRms": math.sqrt(max(0.0, float(window_energy[loudest_start]))),
            "loudest200msStartFrame": loudest_start,
            "leftRms": float(np.sqrt(np.mean(samples[:, 0] ** 2))),
            "rightRms": float(np.sqrt(np.mean(samples[:, 1] ** 2))),
            "clippedSampleCount": int(np.count_nonzero(np.abs(samples) >= 1.0))}


def source_provenance() -> dict:
    script = Path(__file__).resolve()
    result = {"script": "scripts/render-osprey-field-sketch.py", "scriptSha256": sha256(script.read_bytes()),
              "generatorSourceCommit": None, "generatorWorkingTreeDirty": None}
    try:
        def git(*args: str) -> str:
            return subprocess.check_output(["git", "-C", str(script.parent), *args], stderr=subprocess.DEVNULL).decode().strip()
        result["generatorSourceCommit"] = git("rev-parse", "HEAD")
        result["generatorWorkingTreeDirty"] = bool(git("status", "--porcelain", "--untracked-files=all"))
    except (OSError, subprocess.CalledProcessError):
        pass
    return result


def pcm16_wav(samples: np.ndarray) -> tuple[bytes, np.ndarray]:
    require(bool(np.isfinite(samples).all()) and float(np.max(np.abs(samples))) < 1,
            "listening mix would clip")
    quantized = np.rint(samples * 32768.0).astype("<i2")
    destination = io.BytesIO()
    with wave.open(destination, "wb") as writer:
        writer.setnchannels(2)
        writer.setsampwidth(2)
        writer.setframerate(RATE)
        writer.writeframes(quantized.tobytes())
    return destination.getvalue(), quantized.astype(np.float64) / 32768.0


def render(kit_path: Path, handoff_path: Path, out: Path) -> dict:
    receipt, kit_pads, pads, samples, receipt_blob = load_inputs(kit_path, handoff_path)
    frames_per_bar = RATE * 60 * BEATS_PER_BAR // BPM
    frame_count = TOTAL_BARS * frames_per_bar
    mix = np.zeros((frame_count, 2), dtype=np.float64)
    events = []
    for bar, slot in SCHEDULE:
        pad, stored, source = pads[slot], kit_pads[slot], samples[slot]
        offset = (bar - 1) * frames_per_bar
        end = offset + source.shape[0]
        require(end <= frame_count, "score would truncate a source tail")
        mix[offset:end] += source * stored["level"]
        tick = (bar - 1) * BEATS_PER_BAR * PPQ
        events.append({"bar": bar, "beat": 1, "slot": slot, "pad": pad["label"], "name": pad["name"],
                       "tick": tick, "noteOffTick": tick + NOTE_TICKS,
                       "offsetFrames": offset, "offsetSeconds": offset / RATE,
                       "sourceFrames": source.shape[0], "endFrameExclusive": end,
                       "sourceSha256": pad["rawSha256"], "sourceFile": pad["rawFile"],
                       "mpcTriggerMidi": pad["mpcTriggerMidi"], "sourceMidi": 60,
                       "channel": MIDI_CHANNEL, "velocity": 127, "oneShot": True,
                       "originalPadLevel": stored["level"], "originalPadPan": stored["pan"]})
    raw_stats = diagnostics(mix)
    require(raw_stats["finite"] and raw_stats["peak"] > 0 and raw_stats["loudest200msRms"] > 0,
            "empty or nonfinite source mix")
    gain = min(0.12 / raw_stats["loudest200msRms"], 0.82 / raw_stats["peak"])
    listening = mix * gain
    wav_blob, decoded = pcm16_wav(listening)
    rendered_stats = diagnostics(decoded)
    sections = [{"bar": bar, "label": label, "tick": (bar - 1) * BEATS_PER_BAR * PPQ,
                 "offsetFrames": (bar - 1) * frames_per_bar,
                 "offsetSeconds": (bar - 1) * frames_per_bar / RATE}
                for bar, label in SECTION_LABELS]
    midi_blob = make_midi(events, sections)
    source_end = max(event["endFrameExclusive"] for event in events)
    readme = ("OSPREY R3 — FIELD SKETCH\n\n"
              "A sparse dry twelve-hit sketch from the existing sixteen-pad R3 kit.\n"
              "90 BPM, 4/4; sixteen score bars plus three tail bars (50.666667 seconds).\n"
              "Five calm coastlines introduce the bank, rough voices answer, two storm\n"
              "accents turn the weather, and BETWEEN SHORES closes the sketch.\n"
              "Every source is the original eight-second C4 stereo PCM24 one-shot.\n"
              "There is no transposition, resampling, loop, fade, FX or new synth render.\n\n"
              "LISTENING REFERENCE\n"
              "The WAV sums float64 samples with each existing kit.json pad level once.\n"
              "Center pan preserves the source left/right channels without another pan gain.\n"
              f"One constant listening gain ({gain:.15g}) is then applied to the whole mix:\n"
              "min(0.12 / loudest sliding 200 ms stereo RMS, 0.82 / sample peak).\n"
              "This preserves relative pad levels; it is not a compressor or a per-pad match.\n"
              "PCM16 output uses round-to-nearest with no dither. All eight-second tails\n"
              "finish by 48 seconds; the file retains another 2.666667 seconds of silence.\n\n"
              "MPC / MIDI\n"
              "Load the unchanged R3 XTD (or XPM) kit, then import the MIDI as a drum\n"
              "sequence. Use channel 10 and the exported irregular MPC trigger-note map.\n"
              "Nordic Calm uses MIDI trigger 82; this selects A04 and does not change C4.\n"
              "All twelve hits use velocity 127 and a 240-tick note-off at 960 PPQ.\n"
              "One-shot playback must let the entire sample finish despite note-off.\n"
              "The MIDI contains tempo, 4/4 signature, section markers and a nineteen-bar\n"
              "end; no bank/program changes, controllers, automation or audio are embedded.\n"
              "MIDI tempo is 666667 microseconds/quarter (89.999955 BPM), the nearest\n"
              "integer encoding to 90 BPM; render positions use exact nominal 90 BPM.\n"
              "The WAV's listening gain is not embedded in the MIDI. Set monitor level\n"
              "separately when comparing playback.\n\n"
              "EVIDENCE LIMIT\n"
              "Hashes verify the supplied kit and all sixteen source WAVs. The score\n"
              "records the twelve events, source provenance and complete-tail checks.\n"
              "This is a deterministic linear reference, not a recording of MPC hardware.\n"
              "MPC loading, gain/velocity behavior, note-off, stereo, pitch and overlapping\n"
              "playback remain unverified. It does not close phone save/reopen or recipe checks.\n"
              "The source kit and synthesizer code are not included in this sketch download.\n").encode("utf-8")
    payloads = {STEM + ".wav": wav_blob, STEM + ".mid": midi_blob, "README.txt": readme}
    score = {"schemaVersion": 1, "title": "OSPREY R3 — Field sketch", "engine": "OSPREY", "renderModel": 3,
             "tempoBpm": BPM, "timeSignature": [4, 4], "ppq": PPQ, "midiChannel": MIDI_CHANNEL,
             "scoreBars": SCORE_BARS, "tailBars": TAIL_BARS, "totalBars": TOTAL_BARS,
             "sampleRate": RATE, "channels": 2, "bitDepth": 16, "frameCount": frame_count,
             "durationSeconds": frame_count / RATE,
             "provenance": {**source_provenance(), "sourceCommit": receipt["sourceCommit"],
                            "sourceWorkingTreeDirty": receipt["workingTreeDirty"],
                            "sourceKitArchiveSha256": receipt["archive"]["sha256"],
                            "sourceKitArchiveBytes": receipt["archive"]["bytes"],
                            "handoffSha256": sha256(receipt_blob), "numpyVersion": np.__version__,
                            "sourceSampleSha256": {pads[slot]["label"]: pads[slot]["rawSha256"]
                                                   for slot in range(1, 17)}},
             "sourcePolicy": {"sourceMidi": 60, "sourceDurationSeconds": 8, "sourceBitDepth": 24,
                              "oneShot": True, "resampling": False, "transposition": False,
                              "loops": False, "fades": False, "fx": False, "newDsp": False,
                              "sourceWavBytesModified": False, "usedPadCount": len(events), "sourcePadCount": 16},
             "mixing": {"accumulator": "float64", "pcm24DecodeDivisor": 8388608,
                        "padLevels": "original kit.json level, applied once before summing",
                        "centerPan": "original stereo L/R preserved; no extra pan gain",
                        "listeningGain": gain,
                        "gainFormula": "min(0.12 / loudest200msRms, 0.82 / peak)",
                        "rmsWindowFrames": RATE // 5, "rmsWindow": "every consecutive 200ms window; mean L/R energy",
                        "rmsTarget": 0.12, "peakCeiling": 0.82,
                        "perVoiceGainChanges": False, "limiter": False, "dither": False,
                        "pcm16Quantization": "round-to-nearest(samples * 32768)"},
             "midiTiming": {"format": 1, "tracks": 2, "tempoMicrosecondsPerQuarter": TEMPO_US,
                            "encodedBpm": 60_000_000 / TEMPO_US, "noteDurationTicks": NOTE_TICKS,
                            "endTick": TOTAL_BARS * BEATS_PER_BAR * PPQ,
                            "maximumNominalTempoRoundingErrorSeconds": TOTAL_BARS * BEATS_PER_BAR * (TEMPO_US / 1e6 - 60 / BPM),
                            "noteOffPolicy": "short note-off; one-shot playback must preserve the complete source"},
             "sections": sections, "events": events,
             "diagnostics": {"unscaledMix": raw_stats, "listeningPcm16": rendered_stats},
             "validation": {"archiveHashAndInventoryVerified": True, "sourceWavsVerified": 16,
                            "mpcTriggersMatchExportedXpm": True, "originalLevelsAppliedOnce": True,
                            "completeTails": all(event["endFrameExclusive"] <= frame_count for event in events),
                            "lastSourceEndFrameExclusive": source_end,
                            "trailingSilenceFrames": frame_count - source_end,
                            "noClipping": rendered_stats["clippedSampleCount"] == 0,
                            "hardwareVerified": False, "phonePersistenceVerified": False,
                            "evidenceLimit": "Linear reference from verified source samples; MPC loading/playback and phone persistence remain unverified."},
             "files": {name: {"bytes": len(blob), "sha256": sha256(blob)} for name, blob in payloads.items()}}
    payloads[STEM + ".json"] = json_bytes(score)
    out.mkdir(parents=True, exist_ok=True)
    for name, blob in payloads.items():
        (out / name).write_bytes(blob)
    with zipfile.ZipFile(out / (STEM + ".zip"), "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(payloads):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, payloads[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return score


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit", type=Path, required=True, help="unchanged OSPREY R3 kit ZIP")
    parser.add_argument("--handoff", type=Path, required=True, help="adjacent handoff receipt JSON")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    args = parser.parse_args()
    try:
        score = render(args.kit, args.handoff, args.out)
    except (InputError, KeyError, TypeError, ValueError, OSError, zipfile.BadZipFile, wave.Error, ET.ParseError) as error:
        print("Cannot render OSPREY field sketch: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps({"output": str(args.out), "events": len(score["events"]),
                      "frames": score["frameCount"], "durationSeconds": score["durationSeconds"],
                      "listeningGain": score["mixing"]["listeningGain"],
                      "hardwareVerified": False}, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
