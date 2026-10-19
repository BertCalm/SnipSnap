"""End-to-end checks for the fixed R3 field sketch, without synth code or devices.

Run: python3 -m unittest discover -s scripts/tests -p 'test_osprey_field_sketch.py' -v
"""

import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import wave
import zipfile

import numpy as np


SCRIPT = Path(__file__).resolve().parents[1] / "render-osprey-field-sketch.py"
RATE = 44100
STEM = "osprey-r3-field-sketch"
NOTES = [37, 36, 42, 82, 40, 38, 46, 44, 48, 47, 45, 43, 49, 55, 51, 53]
SCHEDULE = [(1, 1), (3, 4), (5, 7), (7, 10), (9, 13), (10, 2),
            (11, 5), (12, 8), (13, 11), (14, 15), (15, 3), (16, 16)]
NAMES = [coast + " " + sea for coast in ("ATLANTIC", "NORDIC", "MEDITERRANEAN", "PACIFIC", "SOUTHERN")
         for sea in ("CALM", "ROUGH", "STORM")] + ["BETWEEN SHORES"]


def digest(blob):
    return hashlib.sha256(blob).hexdigest()


def encode_wav(pcm, width=3):
    if width == 3:
        unsigned = pcm.reshape(-1).astype(np.int32) & 0xffffff
        data = np.column_stack((unsigned & 255, (unsigned >> 8) & 255, (unsigned >> 16) & 255)).astype(np.uint8).tobytes()
    else:
        data = (pcm >> 8).astype("<i2").tobytes()
    target = io.BytesIO()
    with wave.open(target, "wb") as writer:
        writer.setnchannels(2)
        writer.setsampwidth(width)
        writer.setframerate(RATE)
        writer.writeframes(data)
    return target.getvalue()


def make_fixture(folder, *, pulse=False, bad_pan=False, bad_format=False, bad_trigger=False):
    """Real 8-second PCM24 fixtures with exact, independently known sample values."""
    folder.mkdir(parents=True, exist_ok=True)
    root = "Test Osprey R3"
    shared = "mpc/Test_[TrackData]"
    payloads = {}
    pads, kit_pads, levels, values = [], [], {}, {}
    for slot in range(1, 17):
        # Unequal L/R, nonzero last frame and original unequal mixer levels catch
        # mono folding, fade-outs, note-off gates and applying a mixer level twice.
        value = np.array([slot * 20000, -(17 - slot) * 30000], dtype=np.int32)
        if pulse:
            value = np.array([1000, -1700], dtype=np.int32)
        pcm = np.tile(value, (RATE * 8, 1))
        if pulse:
            pcm[0] = [7_800_000, -7_200_000]
        values[slot] = value
        levels[slot] = 0.4 + slot * 0.01
        filename = f"A{slot:02}_Tonal_{slot:02}.wav"
        raw_path = shared + "/" + filename
        blob = encode_wav(pcm, width=2 if bad_format and slot == 1 else 3)
        payloads[root + "/" + raw_path] = blob
        pads.append({"slot": slot, "label": f"A{slot:02}", "name": NAMES[slot - 1],
                     "mpcTriggerMidi": 83 if bad_trigger and slot == 4 else NOTES[slot - 1],
                     "sourceMidi": 60, "durationSeconds": 8, "modelVersion": 3, "velocity": 1,
                     "loop": False, "oneShot": True, "fx": None, "normalizationGain": 1,
                     "rawFile": raw_path, "rawSha256": digest(blob)})
        kit_pads.append({"slot": slot, "sample": filename, "level": levels[slot],
                         "pan": 0.6 if bad_pan and slot == 1 else 0.5,
                         "tuneCoarse": 0, "tuneFine": 0, "muteGroup": 0, "oneShot": True,
                         "recipe": {"patch": {"engine": "OSPREY", "modelVersion": 3,
                                               "midi": 60, "durationSeconds": 8, "velocity": 1,
                                               "macros": {"SPACE": 0}}}})
    kit_relative = shared + "/kit.json"
    payloads[root + "/" + kit_relative] = json.dumps({"pads": kit_pads}).encode()
    xpm_relative = shared + "/Test.xpm"
    xpm = ("<MPCVObject><Program><PadNoteMap>" + "".join(
        f'<PadNote number="{slot}"><Note>{note}</Note></PadNote>' for slot, note in enumerate(NOTES, 1)
    ) + "</PadNoteMap></Program></MPCVObject>")
    payloads[root + "/" + xpm_relative] = xpm.encode()
    kit_path = folder / "fixture-kit.zip"
    with zipfile.ZipFile(kit_path, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name in sorted(payloads):
            bundle.writestr(name, payloads[name])
    blob = kit_path.read_bytes()
    receipt = {"engine": "OSPREY", "renderModel": 3, "sampleRate": RATE, "channels": 2, "bitDepth": 24,
               "sourceCommit": "1234567890123456789012345678901234567890", "workingTreeDirty": False,
               "bundleRoot": root, "snipsnapKit": kit_relative, "compatibilityProgram": xpm_relative,
               "pads": pads, "archive": {"bytes": len(blob), "sha256": digest(blob),
               "entryCount": len(payloads), "entries": [
                   {"path": name, "bytes": len(data), "sha256": digest(data)} for name, data in payloads.items()]}}
    handoff = folder / "handoff.json"
    handoff.write_text(json.dumps(receipt))
    return kit_path, handoff, values, levels


def run_renderer(kit, handoff, out):
    return subprocess.run([sys.executable, str(SCRIPT), "--kit", str(kit), "--handoff", str(handoff), "--out", str(out)],
                          text=True, capture_output=True, check=False)


def read_vlq(blob, offset):
    value = 0
    for _ in range(4):
        byte = blob[offset]
        offset += 1
        value = (value << 7) | (byte & 127)
        if not byte & 128:
            return value, offset
    raise AssertionError("oversized MIDI VLQ")


def parse_midi(blob):
    """Parse emitted bytes independently; inspect real track deltas and statuses."""
    if blob[:4] != b"MThd":
        raise AssertionError("missing MIDI header")
    length, format_, count, ppq = struct.unpack(">IHHH", blob[4:14])
    if length != 6:
        raise AssertionError("unexpected MIDI header size")
    offset = 14
    tracks = []
    for _ in range(count):
        if blob[offset:offset + 4] != b"MTrk":
            raise AssertionError("missing track chunk")
        size = struct.unpack(">I", blob[offset + 4:offset + 8])[0]
        cursor, end, tick = offset + 8, offset + 8 + size, 0
        track = []
        while cursor < end:
            delta, cursor = read_vlq(blob, cursor)
            tick += delta
            status = blob[cursor]
            cursor += 1
            if status == 255:
                kind = blob[cursor]
                cursor += 1
                data_size, cursor = read_vlq(blob, cursor)
                data = blob[cursor:cursor + data_size]
                cursor += data_size
                track.append((tick, status, kind, data))
            elif status & 0xf0 in (0x80, 0x90):
                track.append((tick, status, blob[cursor], blob[cursor + 1]))
                cursor += 2
            else:
                raise AssertionError(f"unexpected MIDI status {status:#x}")
        if cursor != end:
            raise AssertionError("MIDI chunk length mismatch")
        tracks.append(track)
        offset = end
    if offset != len(blob):
        raise AssertionError("unparsed MIDI bytes")
    return format_, ppq, tracks


class OspreyFieldSketchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="osprey-field-sketch-test-")
        cls.folder = Path(cls.temporary.name)
        cls.kit, cls.handoff, cls.values, cls.levels = make_fixture(cls.folder / "input")
        cls.output = cls.folder / "first-output"
        result = run_renderer(cls.kit, cls.handoff, cls.output)
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.score = json.loads((cls.output / (STEM + ".json")).read_text())

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_pcm24_stereo_mix_original_levels_single_gain_and_complete_tails(self):
        with wave.open(str(self.output / (STEM + ".wav")), "rb") as audio:
            self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()),
                             (2, 2, RATE, 19 * 117600))
            actual = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").reshape(-1, 2)
        expected_mix = np.zeros((19 * 117600, 2), dtype=np.float64)
        for bar, slot in SCHEDULE:
            start = (bar - 1) * 117600
            expected_mix[start:start + RATE * 8] += self.values[slot] / 8388608 * self.levels[slot]
        # These constant fixtures have plateaus longer than 200ms; their maximum
        # instantaneous stereo RMS independently gives the loudest window RMS.
        rms = float(np.sqrt(np.max(np.mean(expected_mix ** 2, axis=1))))
        peak = float(np.max(np.abs(expected_mix)))
        expected_gain = min(0.12 / rms, 0.82 / peak)
        self.assertAlmostEqual(self.score["mixing"]["listeningGain"], expected_gain, places=10)
        expected = np.rint(expected_mix * expected_gain * 32768).astype("<i2")
        np.testing.assert_array_equal(actual, expected)
        # The eighth second of A16 remains nonzero, followed by exactly one bar
        # of silence; short note-offs, sixteen-bar file ends or fades fail here.
        self.assertTrue(np.any(actual[48 * RATE - 1] != 0))
        self.assertFalse(np.any(actual[48 * RATE:]))
        self.assertFalse(np.array_equal(actual[:, 0], actual[:, 1]))
        self.assertLess(float(np.max(np.abs(actual.astype(np.int32)))) / 32768, 0.821)
        self.assertEqual(self.score["validation"]["lastSourceEndFrameExclusive"], 48 * RATE)
        self.assertEqual(self.score["validation"]["trailingSilenceFrames"], RATE * 8 // 3)
        self.assertTrue(self.score["validation"]["completeTails"])
        self.assertTrue(self.score["validation"]["noClipping"])
        self.assertIs(self.score["validation"]["hardwareVerified"], False)

    def test_midi_trigger_notes_and_exact_ticks_tempo_markers_and_one_shot_note_offs(self):
        format_, ppq, tracks = parse_midi((self.output / (STEM + ".mid")).read_bytes())
        self.assertEqual((format_, ppq, len(tracks)), (1, 960, 2))
        self.assertEqual([(tick, data) for tick, status, kind, data in tracks[0] if status == 255 and kind == 0x51],
                         [(0, (666667).to_bytes(3, "big"))])
        self.assertIn((0, 255, 0x58, bytes([4, 2, 24, 8])), tracks[0])
        expected_notes = [37, 82, 46, 47, 49, 36, 40, 44, 45, 51, 42, 53]
        ons = [(tick, status, note, velocity) for tick, status, note, velocity in tracks[1] if status != 255 and status & 0xf0 == 0x90]
        offs = [(tick, status, note, velocity) for tick, status, note, velocity in tracks[1] if status != 255 and status & 0xf0 == 0x80]
        self.assertEqual(ons, [((bar - 1) * 3840, 0x99, note, 127) for (bar, _), note in zip(SCHEDULE, expected_notes)])
        self.assertEqual(offs, [(tick + 240, 0x89, note, 0) for tick, _, note, _ in ons])
        markers = [(tick, data.decode()) for tick, status, kind, data in tracks[0] if status == 255 and kind == 6]
        self.assertEqual(markers, [(0, "Five coastlines"), (9 * 3840, "Cross-current dialogue"),
                                   (13 * 3840, "Weather turns"), (15 * 3840, "Between shores"),
                                   (16 * 3840, "Let the tails finish")])
        self.assertTrue(all(track[-1] == (19 * 3840, 255, 0x2f, b"") for track in tracks))
        self.assertEqual([event["mpcTriggerMidi"] for event in self.score["events"]], expected_notes)
        self.assertEqual([event["offsetFrames"] for event in self.score["events"]], [(bar - 1) * 117600 for bar, _ in SCHEDULE])
        self.assertTrue(all(event["sourceMidi"] == 60 and event["oneShot"] for event in self.score["events"]))

    def test_deterministic_outputs_and_archive_provenance(self):
        other = self.folder / "a-different-output-directory"
        result = run_renderer(self.kit, self.handoff, other)
        self.assertEqual(result.returncode, 0, result.stderr)
        expected_names = {STEM + ".wav", STEM + ".mid", STEM + ".json", STEM + ".zip", "README.txt"}
        self.assertEqual({path.name for path in other.iterdir()}, expected_names)
        for name in expected_names:
            self.assertEqual((self.output / name).read_bytes(), (other / name).read_bytes(), name)
        with zipfile.ZipFile(self.output / (STEM + ".zip")) as bundle:
            self.assertEqual(set(bundle.namelist()), expected_names - {STEM + ".zip"})
            for name in bundle.namelist():
                self.assertEqual(bundle.read(name), (self.output / name).read_bytes())
                self.assertEqual(bundle.getinfo(name).date_time, (1980, 1, 1, 0, 0, 0))
        provenance = self.score["provenance"]
        self.assertEqual(provenance["sourceKitArchiveSha256"], digest(self.kit.read_bytes()))
        self.assertEqual(provenance["handoffSha256"], digest(self.handoff.read_bytes()))
        self.assertEqual(provenance["scriptSha256"], digest(SCRIPT.read_bytes()))
        self.assertEqual(len(provenance["sourceSampleSha256"]), 16)
        self.assertIsInstance(provenance["generatorWorkingTreeDirty"], bool)
        for name, info in self.score["files"].items():
            self.assertEqual(info, {"bytes": (self.output / name).stat().st_size,
                                    "sha256": digest((self.output / name).read_bytes())})

    def test_peak_cap_for_high_crest_sources(self):
        kit, handoff, _, _ = make_fixture(self.folder / "pulse-input", pulse=True)
        output = self.folder / "pulse-output"
        result = run_renderer(kit, handoff, output)
        self.assertEqual(result.returncode, 0, result.stderr)
        score = json.loads((output / (STEM + ".json")).read_text())
        stats = score["diagnostics"]["unscaledMix"]
        self.assertLess(0.82 / stats["peak"], 0.12 / stats["loudest200msRms"])
        self.assertAlmostEqual(score["mixing"]["listeningGain"], 0.82 / stats["peak"])
        self.assertLessEqual(score["diagnostics"]["listeningPcm16"]["peak"], 0.82 + 1 / 32768)
        self.assertTrue(score["validation"]["noClipping"])

    def test_invalid_or_mismatched_inputs_rejected_before_outputs(self):
        for kind in ("archive-hash", "archive-length", "source-format", "pan", "trigger-map"):
            with self.subTest(kind=kind):
                folder = self.folder / ("invalid-" + kind)
                kit, handoff, _, _ = make_fixture(folder, bad_format=kind == "source-format",
                                                  bad_pan=kind == "pan", bad_trigger=kind == "trigger-map")
                if kind in ("archive-hash", "archive-length"):
                    receipt = json.loads(handoff.read_text())
                    if kind == "archive-hash":
                        receipt["archive"]["sha256"] = "0" * 64
                    else:
                        receipt["archive"]["bytes"] += 1
                    handoff.write_text(json.dumps(receipt))
                out = folder / "output"
                result = run_renderer(kit, handoff, out)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Cannot render OSPREY field sketch:", result.stderr)
                self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
