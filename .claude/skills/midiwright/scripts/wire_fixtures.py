#!/usr/bin/env python3
"""Hostile MIDI 1.0 byte-stream fixtures for a stateful parser like SnipSnap's MidiWire.

Each fixture is a list of deliveries (the chunks a transport hands over) and the typed
events a correct parser emits. The rules mirror MidiWire's documented contract:

  - a message may be split across deliveries
  - running status carries across deliveries
  - real-time bytes (F8..FF) land anywhere and do not cancel running status or disturb the
    message around them; F8, FA, FB, FC come out as ("Clock"|"Start"|"Continue"|"Stop", stamp)
    at the position they arrive (inside a SysEx too), F9, FD, FE, FF are dropped
  - the stamp is the delivery's own clock reading; only the real-time tuples carry it (it is
    the last element), the note tuples are still unstamped
  - system common (F1..F7) cancels running status
  - SysEx is skipped byte by byte until F7 or the next non-real-time status byte
  - note-on with velocity 0 is a note-off
  - pitch bend, aftertouch and program change are length-honoured and dropped

The script runs its own reference parser over every fixture and refuses to print a set
whose expected events disagree with the reference, so a typo in a fixture is caught here
and not in a Kotlin test. Extend the fixture list; the reference parser is the contract.

Usage:
  python3 wire_fixtures.py            # JSON
  python3 wire_fixtures.py --kotlin   # a Kotlin test table (strings of hex, expected events)
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field


@dataclass
class Fixture:
    name: str
    why: str
    deliveries: list[list[int]]
    expected: list[tuple] = field(default_factory=list)
    stamps: list[int] | None = None   # one clock reading per delivery; 0 each when omitted


# ---------------------------------------------------------------------------
# Reference parser (the contract). One instance per port, state kept across feed().
# ---------------------------------------------------------------------------
class RefWire:
    def __init__(self) -> None:
        self.running = 0          # last channel status byte, 0 before any
        self.pending: list[int] = []
        self.in_sysex = False

    @staticmethod
    def _length(status: int) -> int:
        hi = status & 0xF0
        if hi in (0xC0, 0xD0):
            return 1
        return 2

    REALTIME = {0xF8: "Clock", 0xFA: "Start", 0xFB: "Continue", 0xFC: "Stop"}

    def feed(self, chunk: list[int], stamp: int = 0) -> list[tuple]:
        out: list[tuple] = []
        for b in chunk:
            if b >= 0xF8:              # real-time: report the four, touch nothing else
                if b in self.REALTIME:
                    out.append((self.REALTIME[b], stamp))
                continue
            if self.in_sysex:
                if b == 0xF7:
                    self.in_sysex = False
                elif b & 0x80:         # a status byte ends a SysEx that lost its F7
                    self.in_sysex = False
                    self._status(b)
                continue
            if b & 0x80:
                self._status(b)
                continue
            # data byte
            if self.running == 0:
                continue               # orphan data, nothing to attach it to
            self.pending.append(b)
            if len(self.pending) == self._length(self.running):
                out.extend(self._emit())
                self.pending = []
        return out

    def _status(self, b: int) -> None:
        self.pending = []
        if b == 0xF0:
            self.in_sysex = True
            self.running = 0
        elif 0xF1 <= b <= 0xF7:        # system common cancels running status
            self.running = 0
        else:
            self.running = b

    def _emit(self) -> list[tuple]:
        s = self.running
        hi, ch = s & 0xF0, s & 0x0F
        d = self.pending
        if hi == 0x90:
            if d[1] == 0:
                return [("NoteOff", ch, d[0])]
            return [("NoteOn", ch, d[0], d[1])]
        if hi == 0x80:
            return [("NoteOff", ch, d[0])]
        if hi == 0xB0:
            return [("Control", ch, d[0], d[1])]
        return []                      # bend, aftertouch, program: honoured, dropped


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
FIXTURES: list[Fixture] = [
    Fixture("whole_note", "the simplest case, one message in one delivery",
            [[0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("split_after_status", "status byte in one delivery, data in the next",
            [[0x90], [60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("split_mid_data", "one data byte per delivery",
            [[0x90, 60], [100]], [("NoteOn", 0, 60, 100)]),
    Fixture("running_status_two_notes", "status once, then two notes of data",
            [[0x90, 60, 100, 64, 90]], [("NoteOn", 0, 60, 100), ("NoteOn", 0, 64, 90)]),
    Fixture("running_status_across_deliveries", "running status survives a delivery boundary",
            [[0x90, 60, 100], [64, 90]], [("NoteOn", 0, 60, 100), ("NoteOn", 0, 64, 90)]),
    Fixture("velocity_zero_is_off", "note-on at velocity 0 is a note-off",
            [[0x90, 60, 0]], [("NoteOff", 0, 60)]),
    Fixture("clock_inside_message_is_heard", "F8 between status and data and between data bytes is heard, the note still assembles",
            [[0x90, 0xF8, 60, 0xF8, 100]], [("Clock", 0), ("Clock", 0), ("NoteOn", 0, 60, 100)]),
    Fixture("clock_between_status_and_data", "a clock in its own delivery between a status byte and its data",
            [[0x90], [0xF8], [60, 100]], [("Clock", 2), ("NoteOn", 0, 60, 100)], stamps=[1, 2, 3]),
    Fixture("clock_flood", "1000 clocks inside running status: one event each, both notes survive",
            [[0x91, 60, 100] + [0xF8] * 1000 + [62, 100]],
            [("NoteOn", 1, 60, 100)] + [("Clock", 0)] * 1000 + [("NoteOn", 1, 62, 100)]),
    Fixture("transport_bytes_in_order", "start, clock, continue, stop come out in order with the delivery's stamp",
            [[0xFA, 0xF8, 0xFB, 0xFC]], [("Start", 7), ("Clock", 7), ("Continue", 7), ("Stop", 7)], stamps=[7]),
    Fixture("dropped_realtime_bytes", "F9, FD, FE and FF produce nothing",
            [[0xF9, 0xFD, 0xFE, 0xFF]], []),
    Fixture("clock_inside_sysex", "a clock inside a SysEx is heard; the SysEx data stays skipped",
            [[0xF0, 0x7E, 0xF8, 0x01, 0xF7]], [("Clock", 0)]),
    Fixture("active_sensing_in_running_status", "FE does not cancel running status",
            [[0x90, 60, 100, 0xFE, 64, 90]], [("NoteOn", 0, 60, 100), ("NoteOn", 0, 64, 90)]),
    Fixture("song_position_cancels_running_status", "F2 cancels running status; data after it is orphaned",
            [[0x90, 60, 100, 0xF2, 0x10, 0x00, 64, 90]], [("NoteOn", 0, 60, 100)]),
    Fixture("sysex_skipped", "a SysEx between two notes is skipped whole",
            [[0x90, 60, 100, 0xF0, 0x7E, 0x7F, 0x09, 0x01, 0xF7, 0x90, 64, 90]],
            [("NoteOn", 0, 60, 100), ("NoteOn", 0, 64, 90)]),
    Fixture("sysex_across_deliveries", "SysEx spanning three deliveries",
            [[0xF0, 0x41, 0x10], [0x42, 0x12, 0x40], [0x00, 0x7F, 0x00, 0x41, 0xF7, 0x90, 60, 100]],
            [("NoteOn", 0, 60, 100)]),
    Fixture("sysex_lost_f7", "a SysEx with no F7 ends at the next status byte",
            [[0xF0, 0x41, 0x10, 0x42], [0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("long_sysex_8k", "an 8192-byte SysEx (the BLE and RtMidi failure size) does not stall",
            [[0xF0] + [0x55] * 8190 + [0xF7], [0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("pitch_bend_dropped_length_honoured", "E0 takes two data bytes; the note after stays in step",
            [[0xE0, 0x00, 0x40, 0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("program_change_one_byte", "C0 takes one data byte",
            [[0xC0, 5, 0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("channel_pressure_one_byte_running", "D0 with running status takes one byte each",
            [[0xD0, 10, 20, 0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("poly_aftertouch_two_bytes", "A0 takes two data bytes",
            [[0xA0, 60, 50, 0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("control_change", "a CC on channel 2",
            [[0xB1, 64, 127]], [("Control", 1, 64, 127)]),
    Fixture("sustain_then_note_off_running", "CC 64 then running-status CC 64 off",
            [[0xB0, 64, 127, 64, 0]], [("Control", 0, 64, 127), ("Control", 0, 64, 0)]),
    Fixture("orphan_data_before_any_status", "data with no status is ignored",
            [[60, 100, 0x90, 60, 100]], [("NoteOn", 0, 60, 100)]),
    Fixture("note_off_explicit", "80 is a note-off whatever its velocity",
            [[0x80, 60, 64]], [("NoteOff", 0, 60)]),
    Fixture("all_notes_off", "CC 123 is the panic every controller has a button for",
            [[0xB0, 123, 0]], [("Control", 0, 123, 0)]),
    Fixture("two_channels_interleaved", "channel 1 and channel 10 alternate",
            [[0x90, 60, 100, 0x99, 36, 120, 0x90, 60, 0]],
            [("NoteOn", 0, 60, 100), ("NoteOn", 9, 36, 120), ("NoteOff", 0, 60)]),
    Fixture("empty_delivery", "an empty delivery changes nothing",
            [[0x90, 60], [], [100]], [("NoteOn", 0, 60, 100)]),
    Fixture("burst_of_16_notes_one_delivery", "a chord of 16 in one delivery",
            [[0x90] + [b for n in range(36, 52) for b in (n, 100)]],
            [("NoteOn", 0, n, 100) for n in range(36, 52)]),
]


def validate() -> list[str]:
    errors = []
    for f in FIXTURES:
        wire = RefWire()
        got: list[tuple] = []
        for i, chunk in enumerate(f.deliveries):
            got.extend(wire.feed(chunk, f.stamps[i] if f.stamps else 0))
        if got != f.expected:
            errors.append(f"{f.name}: expected {f.expected} got {got}")
    return errors


def as_json() -> str:
    return json.dumps([
        {
            "name": f.name,
            "why": f.why,
            "deliveries": [" ".join(f"{b:02X}" for b in d) for d in f.deliveries],
            "expected": [list(e) for e in f.expected],
        } for f in FIXTURES
    ], indent=2)


def as_kotlin() -> str:
    lines = ["// Generated by midiwright/scripts/wire_fixtures.py. Hex deliveries, expected events.",
             "val WIRE_FIXTURES: List<Triple<String, List<String>, List<String>>> = listOf("]
    for f in FIXTURES:
        d = ", ".join(f'"{" ".join(f"{b:02X}" for b in chunk)}"' for chunk in f.deliveries)
        e = ", ".join(f'"{"/".join(str(x) for x in ev)}"' for ev in f.expected)
        lines.append(f'    Triple("{f.name}", listOf({d}), listOf({e})),  // {f.why}')
    lines.append(")")
    return "\n".join(lines)


if __name__ == "__main__":
    errs = validate()
    if errs:
        print("fixture set does not round-trip through the reference parser:", file=sys.stderr)
        for e in errs:
            print("  " + e, file=sys.stderr)
        sys.exit(1)
    print(as_kotlin() if "--kotlin" in sys.argv else as_json())
