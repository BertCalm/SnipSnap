"""SIGIL round 0: the three kits as data. Python harness, not Kotlin; nothing heard.

Each kit is sixteen pads in the house shape: fourteen hits, a roll on A15, a groove on A16. Every pad renders at three
velocity zones (.40, .70, 1.0) and four takes; a take is the same pad struck again (fresh noise, a hair of tuning and
decay), never a copy.

The kits follow the owner's three tempo clusters and answers (2026-11-20): "three kits, one per tempo"; wear
"mix it up" (so each kit has its own print: blown speaker, clean slam, tape); "the kick/snare sound is impactful";
"the percs are a bit esoteric, organic/inorganic hybrid".

Grooves are written as step strings, one character per sixteenth, a bar per sixteen characters:
  X = hard (1.0)   x = medium (.70)   o = soft (.40)   . = rest
  r = a 32nd ratchet (two hits in the sixteenth)   t = a triplet ratchet (three hits in the sixteenth)
"""
from __future__ import annotations

from sigil_proto import A2, C3, E3, G3, A3, A4, C5, E5

ZONES = (0.40, 0.70, 1.00)
TAKES = 4


def pad(name, voice, gain, **params):
    return {"name": name, "voice": voice, "gain": gain, "params": params}


# ------------------------------------------------------------------ IRON, 74 BPM: heavy, dark, blown speaker
# After the six heavy drum loops (70 to 90 BPM): sub-drop kicks, a dark snare with a long tail, hats a whisper,
# everything centred. One of those loops is a speaker pushed past its limit; that is this kit's print.

IRON = {
    "id": "iron", "title": "IRON", "bpm": 74, "swing": 52.0, "print": "blown", "print_amount": 0.45,
    "pads": [
        pad("Drop Kick", "kick", 0.95, f_start=170, f_end=45, sweep=0.06, decay=0.04, hold=0.015, len=0.6,
            click=0.5, knock=0.15, drive=0.05),
        pad("Blown Kick", "kick", 0.95, f_start=150, f_end=50, sweep=0.06, decay=0.11, hold=0.04, len=1.0,
            click=0.3, knock=0.1, drive=0.6, asym=0.15, print_amount=1.0),
        pad("Dark Snare", "snare", 0.92, tune=1.0, head=0.35, head_decay=0.04, wires=1.0, wire_lp=6000,
            wire_decay=0.055, crack=0.4, tail=0.05, tail_gain=0.4, drive=0.45, len=0.6),
        pad("Rim Knock", "rim", 0.7, tune=520, len=0.25),
        pad("Closed Hat", "hat", 0.45, ratio=1.0, metal=0.5, hp=6000, lp=9000, decay=0.03, len=0.25),
        pad("Grain Shaker", "shaker", 0.5, shape=0.2, decay=0.06, band=(4000, 9000), digital=0.45, len=0.3),
        pad("Log Low", "log_drum", 0.8, note=A2, bend=7, decay=0.16, wood=0.45, drive=0.4, len=0.8),
        pad("Log High", "log_drum", 0.75, note=E3, bend=5, decay=0.12, wood=0.5, drive=0.35, len=0.7),
        pad("Glass Bell", "glass_bell", 0.55, note=A4, decay=0.35, glass=0.5, len=1.6),
        pad("Clock Tick", "clock_tick", 0.5, tune=2900, len=0.12),
        pad("Breath", "breath", 0.55, pitch=140, voiced=0.35, vowel="o", ring=0.3, decay=0.09, len=0.4),
        pad("Hand Drum", "hand_drum", 0.75, tune=150, decay=0.22, slap=0.4, sub=0.5, len=0.6),
        pad("Water Drop", "water_drop", 0.55, tune=700, rise=30, decay=0.05, len=0.25),
        pad("Iron Rod", "rod", 0.45, note=E3, swell=0.08, decay=0.6, len=1.6),
    ],
    # A15: the dark snare dragged: strokes accelerating from eighths to thirty-seconds over two seconds
    "roll": {"name": "Snare Drag", "pad": 2, "seconds": 2.2, "from_div": 2, "to_div": 8, "beats": 4.0,
             "vel_from": 0.35, "vel_to": 1.0},
    "groove": {
        "bars": 2,
        "mix": {0: 1.0, 1: 0.9, 2: 1.0, 3: 0.5, 4: 0.36, 5: 0.25, 6: 0.4, 7: 0.35, 8: 0.35, 9: 0.3, 10: 0.4,
                11: 0.35, 12: 0.4, 13: 0.2},
        "steps": {
            0: "X......x..X..... X..x......X.....",
            1: "................ ..............x.",
            2: "....X.......X... ....X.......X..o",
            3: "..............o. ................",
            4: "o.o.o.o.o.o.o.o. o.o.o.o.o.o.o.o.",
            9: "..o...o...o...o. ..o...o...o...o.",
            6: "...........x.... ...x............",
            7: "................ ...........o....",
            8: "................ .......o........",
            10: "...............o ................",
            12: "................ .............o..",
            13: "X............... ................",
        },
    },
}

# ------------------------------------------------------------------ NEON, 90 BPM: clean, slammed, hat rolls
# After the slammed 90 BPM kit loop (an 808-style sub falling 126 to 38 Hz, a 2.4 kHz crack, ratcheting hats) and
# the 90 BPM percussion loops (clock hands, shakers, wood). Clean and loud: the print is a slam, not a colour.

NEON = {
    "id": "neon", "title": "NEON", "bpm": 90, "swing": 51.0, "print": "slam", "print_amount": 0.4,
    "pads": [
        pad("Sub Kick", "kick", 0.95, f_start=170, f_end=41, sweep=0.06, decay=0.07, hold=0.03, len=1.0,
            click=0.3, knock=0.08, drive=0.05, print_amount=0.25),
        pad("Punch Kick", "kick", 0.95, f_start=200, f_end=55, sweep=0.04, decay=0.04, hold=0.01, len=0.5,
            click=0.9, knock=0.25, drive=0.2),
        pad("Crack Snare", "snare", 0.92, tune=1.15, head=0.5, head_decay=0.03, wires=1.0, wire_lp=11000,
            wire_decay=0.025, crack=0.9, tail=0.025, tail_gain=0.2, drive=0.4, len=0.4),
        pad("Glitch Clap", "clap", 0.85, decay=0.06, drive=0.3, len=0.4),
        pad("Closed Hat", "hat", 0.45, ratio=1.1, metal=0.6, hp=7000, lp=14000, decay=0.035, len=0.25),
        pad("Open Hat", "hat", 0.42, ratio=1.1, metal=0.6, hp=6500, lp=13000, decay=0.25, len=0.8),
        pad("Grain Shaker", "shaker", 0.5, shape=0.1, decay=0.05, band=(5000, 11000), digital=0.6, len=0.25),
        pad("Clock Tick", "clock_tick", 0.5, tune=3400, gap=0.005, len=0.12),
        pad("Wood Chirp", "wood_chirp", 0.6, tune=900, chirp=0.8, decay=0.05, len=0.2),
        pad("Bell Ping", "glass_bell", 0.55, note=C5, decay=0.25, glass=0.35, len=1.2),
        pad("Log Mid", "log_drum", 0.75, note=C3, bend=12, decay=0.1, wood=0.3, drive=0.45, len=0.6),
        pad("Voice Chop", "breath", 0.6, pitch=190, voiced=0.7, vowel="a", ring=0.45, decay=0.06, len=0.3),
        pad("Metal Tick", "metal_tick", 0.45, tune=2100, decay=0.04, len=0.15),
        pad("Zap", "zap", 0.6, f_start=3000, f_end=90, sweep=0.03, decay=0.12, len=0.4),
    ],
    # A15: the hat roll: sixteenths, then 32nds, then triplet 32nds, rising a little in pitch
    "roll": {"name": "Hat Roll", "pad": 4, "seconds": 2.67, "from_div": 4, "to_div": 12, "beats": 4.0,
             "vel_from": 0.5, "vel_to": 1.0, "rise": 0.12},
    "groove": {
        "bars": 2,
        "mix": {0: 1.0, 1: 0.8, 2: 1.0, 3: 0.6, 4: 0.3, 5: 0.25, 6: 0.22, 7: 0.3, 8: 0.45, 9: 0.3, 10: 0.5,
                11: 0.4, 12: 0.3, 13: 0.4},
        "steps": {
            0: "X.....X...X..x.. X.........X.X...",
            2: "........X....... ........X.......",
            3: "........x....... ........x......o",
            4: "x.x.x.x.x.x.x.x. x.x.x.x.x.xrx.tt",
            5: "..............o. ................",
            6: "o.oo.oo.o.oo.oo. o.oo.oo.o.oo.oo.",
            7: ".o...o...o...o.. .o...o...o...o..",
            8: "......x.......x. ...x......x.....",
            9: "...o............ ................",
            10: "................ ......x.........",
            11: "...........o.... ................",
            12: "...o.......o.... ...o.......o....",
            13: "................ ...............o",
        },
    },
}

# ------------------------------------------------------------------ ASH, 115 BPM: tape-worn, percussion forward
# After the 115 BPM percussion loops (tuned low wood in F and A minor, shakers, bells) and the two tape-and-vinyl
# loops. Four on the floor, a soft backbeat, logs walking A minor, the print is tape.

ASH = {
    "id": "ash", "title": "ASH", "bpm": 115, "swing": 56.0, "print": "tape", "print_amount": 0.6,
    "pads": [
        pad("Round Kick", "kick", 0.95, f_start=160, f_end=55, sweep=0.05, decay=0.04, hold=0.015, len=0.5,
            click=0.45, knock=0.2, drive=0.15),
        pad("Tape Snare", "snare", 0.9, tune=1.08, head=0.45, head_decay=0.04, wires=1.0, wire_lp=7000,
            wire_decay=0.045, crack=0.4, tail=0.04, tail_gain=0.3, drive=0.35, len=0.5),
        pad("Rim", "rim", 0.7, tune=610, len=0.2),
        pad("Tambourine", "tambourine", 0.5, decay=0.09, len=0.4),
        pad("Shaker Push", "shaker", 0.5, shape=0.0, decay=0.05, band=(4500, 10000), digital=0.3, len=0.25),
        pad("Shaker Pull", "shaker", 0.5, shape=1.0, decay=0.04, band=(4500, 10000), digital=0.3, len=0.3),
        pad("Log Low", "log_drum", 0.8, note=A2, bend=12, decay=0.14, wood=0.35, drive=0.4, len=0.8),
        pad("Log Mid", "log_drum", 0.78, note=C3, bend=12, decay=0.12, wood=0.35, drive=0.4, len=0.7),
        pad("Log High", "log_drum", 0.75, note=E3, bend=7, decay=0.1, wood=0.45, drive=0.35, len=0.6),
        pad("Thumb Tine", "tine", 0.6, note=A4, decay=0.6, buzz=0.5, shadow=0.25, len=1.4),
        pad("Glass Bell", "glass_bell", 0.5, note=E5, decay=0.3, glass=0.6, len=1.4),
        pad("Hand Drum", "hand_drum", 0.7, tune=190, decay=0.16, slap=0.6, sub=0.25, len=0.5),
        pad("Breath", "breath", 0.5, pitch=160, voiced=0.25, vowel="u", ring=0.25, decay=0.08, len=0.35),
        pad("Wind Rod", "rod", 0.4, note=A3, swell=0.15, decay=0.8, len=1.8),
    ],
    # A15: the shaker rush: push and pull strokes doubling from eighths to thirty-seconds over a bar
    "roll": {"name": "Shaker Rush", "pad": 4, "alt_pad": 5, "seconds": 2.09, "from_div": 2, "to_div": 8,
             "beats": 4.0, "vel_from": 0.35, "vel_to": 1.0},
    "groove": {
        "bars": 2,
        "mix": {0: 0.85, 1: 0.8, 2: 0.5, 3: 0.4, 4: 0.4, 5: 0.35, 6: 0.32, 7: 0.4, 8: 0.45, 9: 0.4, 10: 0.35,
                11: 0.45, 12: 0.35, 13: 0.2},
        "steps": {
            0: "X...X...X...X... X...X...X...X...",
            1: "....x.......x... ....x.......x...",
            2: "...x..x....x..x. ...x..x...x.x...",
            3: "..x...x...x...x. ..x...x...x...x.",
            4: "x.x.x.x.x.x.x.x. x.x.x.x.x.x.x.x.",
            5: ".o.o.o.o.o.o.o.o .o.o.o.o.o.o.o.o",
            6: "x.......x.....x. x.....x.........",
            7: "......x......... ...x......x.....",
            8: "...x.......x.... .............x..",
            9: "................ ........o.......",
            10: "..............o. ................",
            11: "..o.......o...o. ..o.......o...o.",
            12: "................ .......o........",
            13: "o............... ................",
        },
    },
}

# ------------------------------------------------------------------ KILN, 100 BPM: shuffled hand drums, low tines, old sampler
# After the one strongly swung reference (the tape-and-vinyl jungle loop at 100 BPM: a 67 percent triplet shuffle, hand
# drums near a 1 kHz centroid gone in about 86 ms, half the energy in 150 to 400 Hz) and the 75 BPM lullaby and bell
# loops (low tuned tones, 82 percent in 150 to 400 Hz; bells in 400 Hz to 1 kHz). The print is an old sampler.

KILN = {
    "id": "kiln", "title": "KILN", "bpm": 100, "swing": 64.0, "print": "sampler", "print_amount": 0.7,
    "pads": [
        pad("Thump Kick", "kick", 0.95, f_start=150, f_end=50, sweep=0.05, decay=0.045, hold=0.015, len=0.6,
            click=0.35, knock=0.25, knock_hz=190, drive=0.15),
        pad("Ghost Kick", "kick", 0.75, f_start=120, f_end=55, sweep=0.03, decay=0.025, hold=0.005, len=0.35,
            click=0.2, knock=0.3, drive=0.1),
        pad("Crunch Snare", "snare", 0.92, tune=1.05, head=0.5, head_decay=0.04, wires=1.0, wire_lp=5000,
            wire_decay=0.05, crack=0.5, tail=0.05, tail_gain=0.3, drive=0.5, len=0.5),
        pad("Snap", "snap", 0.7, digital=0.35, len=0.25),
        pad("Closed Hat", "hat", 0.42, ratio=0.95, metal=0.4, hp=5500, lp=10000, decay=0.028, len=0.2),
        pad("Tambourine", "tambourine", 0.5, decay=0.07, len=0.35),
        pad("Bongo High", "hand_drum", 0.7, tune=330, decay=0.09, slap=0.8, sub=0.0, len=0.35),
        pad("Bongo Low", "hand_drum", 0.72, tune=230, decay=0.11, slap=0.7, sub=0.0, len=0.4),
        pad("Conga Low", "hand_drum", 0.78, tune=165, decay=0.2, slap=0.5, sub=0.35, len=0.55),
        pad("Low Tine C", "tine", 0.75, note=C3, decay=0.45, buzz=0.6, shadow=0.3, len=1.0),
        pad("Low Tine E", "tine", 0.72, note=E3, decay=0.4, buzz=0.6, shadow=0.3, len=0.9),
        pad("Bell A", "glass_bell", 0.5, note=A4, decay=0.3, glass=0.55, len=1.3),
        pad("Breath", "breath", 0.5, pitch=175, voiced=0.45, vowel="e", ring=0.3, decay=0.07, len=0.3),
        pad("Scrape", "scrape", 0.55, tune=2600, rate=70, len=0.22),
    ],
    # A15: the bongos rolled, high and low in turn, eighths to thirty-seconds over a bar
    "roll": {"name": "Bongo Roll", "pad": 6, "alt_pad": 7, "seconds": 2.4, "from_div": 2, "to_div": 8,
             "beats": 4.0, "vel_from": 0.35, "vel_to": 1.0},
    "groove": {
        "bars": 2,
        "mix": {0: 1.0, 1: 0.55, 2: 0.95, 3: 0.45, 4: 0.3, 5: 0.3, 6: 0.5, 7: 0.5, 8: 0.55, 9: 0.5, 10: 0.45,
                11: 0.3, 12: 0.35, 13: 0.4},
        "steps": {
            0: "X.....x...X..... X.....x.x.......",
            1: ".......o........ ...o...........o",
            2: "....X.......X... ....X.......X...",
            3: "....o.......o... ....o.......o...",
            4: "xoxoxoxoxoxoxoxo xoxoxoxoxoxoxoxo",
            5: "..x...x...x...x. ..x...x...x...x.",
            6: ".x.x...x.x.x...x .x.x...x.x.x.x.x",
            7: "x.....x...x..... x.....x...x.....",
            8: "...o......x..... ...o.......x....",
            9: "x.........x..... ................",
            10: "......x......... x.....x.........",
            11: "...............o ..........o.....",
            12: "................ .......o........",
            13: "..............x. ..............x.",
        },
    },
}

KITS = [IRON, NEON, ASH, KILN]
