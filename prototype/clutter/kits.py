"""CLUTTER kits: the sixteen-object audition kit and four genre kits.

Each kit is sixteen one-shot pads in the AutoPlace layout (A01 kick, A02 snare, A03/A04 closed and
open hat, A05 second kick, A06 clap or second snare, A07/A08 second hat pair, A09/A10 toms, then
percussion), its own tempo and swing, its own pad roles, and a two-bar groove to hear it in. A03
chokes A04 and A07 chokes A08, as hats do.

The owner's gate asks for genre kits that are "authored musical choices with distinct roles, balance
and character", not one kit at other effect strengths. So the four kits differ in which objects fill
each role, how they are hit (TOOL), how loose they are (RATTLE), how damped (DAMP), where the phone is
(MIC: outdoors is close and dry, the kitchen is the room), and in tempo, swing and groove. Every
setting below is "shape": a first authored choice for the owner's listen.

Pad tuple: (pad, label, object, macros, class). Groove tuple: (pad, sixteenth step, velocity).
"""
from __future__ import annotations

import clutter_proto as e

_CLASS = e.DRUM_CLASS

BASE = {
    "id": "clutter", "name": "CLUTTER", "bpm": 92.0, "swing": 0.5,
    "character": "The sixteen objects at their defaults, one per pad: the audition kit.",
    "pads": [
        ("A01", "Bucket", "BUCKET", {}, "KICK"),
        ("A02", "Rice Box", "BOX", {}, "SNARE"),
        ("A03", "Rice Jar", "JAR", {}, "HAT_CLOSED"),
        ("A04", "Key Ring", "KEYS", {}, "HAT_OPEN"),
        ("A05", "Suitcase", "SUITCASE", {}, "KICK"),
        ("A06", "Book Slam", "BOOK", {}, "CLAP"),
        ("A07", "Matchbox", "MATCHBOX", {}, "HAT_CLOSED"),
        ("A08", "Cutlery Drawer", "DRAWER", {}, "HAT_OPEN"),
        ("A09", "Tub", "TUB", {}, "TOM"),
        ("A10", "Frying Pan", "PAN", {}, "TOM"),
        ("A11", "Knuckle", "TABLE", {}, "PERC"),
        ("A12", "Saucepan", "POT", {}, "PERC"),
        ("A13", "Bottle", "BOTTLE", {}, "PERC"),
        ("A14", "Mug", "MUG", {}, "PERC"),
        ("A15", "Wine Glass", "GLASS", {}, "PERC"),
        ("A16", "Zipper", "ZIP", {}, "PERC"),
    ],
    # The round-0 groove, kept at the owner's word ("groove can stay").
    "groove": (
        [p for b in (0, 16) for p in (
            [("A01", b + 0, 1.0), ("A05", b + 7, .55), ("A01", b + 10, .9), ("A02", b + 4, 1.0), ("A02", b + 12, 1.0),
             ("A02", b + 15, .35), ("A11", b + 5, .45), ("A14", b + 11, .55), ("A04", b + 14, .8), ("A03", b + 13, .4)]
            + [("A03", b + s, .9 if s % 4 == 0 else .55) for s in range(0, 16, 4)]
            + [("A07", b + s, .6 if s % 4 == 2 else .4) for s in range(1, 16, 2) if s != 13])]
        + [("A09", 26, .8), ("A10", 27, .7), ("A06", 28, .9), ("A11", 30, .35), ("A16", 31, .6), ("A15", 3, .5)]
    ),
}

LOFI = {
    "id": "lofi", "name": "CLUTTER KITCHEN LO-FI", "bpm": 82.0, "swing": 0.58,
    "character": "Bedroom lo-fi hip hop. Palms and wooden spoons, everything damped by a resting hand, the phone "
                 "on the counter so the kitchen is in every hit. Soft, warm, swung.",
    "pads": [
        ("A01", "Soft Bucket", "BUCKET", {"TUNE": .42, "TOOL": .05, "DAMP": .45, "RATTLE": .2, "MIC": .35}, "KICK"),
        ("A02", "Rice Box Brush", "BOX", {"TUNE": .45, "TOOL": .05, "RATTLE": .45, "DAMP": .45, "MIC": .4}, "SNARE"),
        ("A03", "Matchbox", "MATCHBOX", {"TOOL": .35, "RATTLE": .45, "DAMP": .45, "MIC": .35}, "HAT_CLOSED"),
        ("A04", "Loose Keys", "KEYS", {"TOOL": .2, "RATTLE": .35, "DAMP": .45, "MIC": .4}, "HAT_OPEN"),
        ("A05", "Floor Stomp", "STOMP", {"TUNE": .45, "TOOL": .15, "RATTLE": .25, "MIC": .45}, "KICK"),
        ("A06", "Board Slap", "BOARD", {"TOOL": .05, "RATTLE": .25, "MIC": .45}, "CLAP"),
        ("A07", "Sand Jar", "JAR", {"TOOL": .1, "DAMP": .5, "RATTLE": .4, "MIC": .3}, "HAT_CLOSED"),
        ("A08", "Drawer Pushed", "DRAWER", {"TOOL": .2, "RATTLE": .3, "DAMP": .5, "MIC": .45}, "HAT_OPEN"),
        ("A09", "Tub Low", "TUB", {"TUNE": .3, "TOOL": .1, "DAMP": .5, "MIC": .4}, "TOM"),
        ("A10", "Pot Thump", "POT", {"TUNE": .3, "TOOL": .1, "DAMP": .65, "RATTLE": .2, "MIC": .4}, "TOM"),
        ("A11", "Mug Tink", "MUG", {"TOOL": .5, "DAMP": .45, "RATTLE": .3, "MIC": .4}, "PERC"),
        ("A12", "Knuckle", "TABLE", {"TOOL": .05, "RATTLE": .2, "MIC": .4}, "PERC"),
        ("A13", "Glass and Ice", "GLASS", {"RATTLE": .7, "DAMP": .35, "MIC": .45}, "PERC"),
        ("A14", "Book Close", "BOOK", {"TOOL": .2, "RATTLE": .3, "DAMP": .45, "MIC": .45}, "PERC"),
        ("A15", "Bottle Spoon", "BOTTLE", {"TUNE": .4, "TOOL": .4, "DAMP": .4, "MIC": .4}, "PERC"),
        ("A16", "Slow Zip", "ZIP", {"TOOL": .15, "MIC": .35}, "PERC"),
    ],
    "groove": (
        [p for b in (0, 16) for p in (
            [("A01", b + 0, .95), ("A05", b + 7, .5), ("A01", b + 10, .85), ("A02", b + 4, .9), ("A02", b + 12, .95),
             ("A12", b + 6, .35), ("A11", b + 11, .45), ("A04", b + 14, .6)]
            + [("A03", b + s, .7 if s % 4 == 0 else .4) for s in range(0, 16, 2) if s != 14])]
        + [("A13", 3, .5), ("A07", 21, .4), ("A09", 29, .6), ("A10", 30, .55), ("A16", 31, .5)]
    ),
}

STREET = {
    "id": "street", "name": "CLUTTER STREET BUCKET", "bpm": 120.0, "swing": 0.5,
    "character": "Busker drumline. Drumsticks and a ladle, everything loose and ringing, played outdoors so the "
                 "phone hears it close and dry. Bright, loud, straight.",
    "pads": [
        ("A01", "Big Bucket", "BUCKET", {"TUNE": .38, "TOOL": .55, "RATTLE": .6, "DAMP": .2, "MIC": .12}, "KICK"),
        ("A02", "Bucket Rim", "BUCKET", {"TUNE": .92, "TOOL": .9, "RATTLE": .1, "DAMP": .5, "MIC": .12}, "SNARE"),
        ("A03", "Can Tick", "CAN", {"TOOL": .9, "DAMP": .55, "MIC": .1}, "HAT_CLOSED"),
        ("A04", "Bin Lid", "BINLID", {"TOOL": .75, "RATTLE": .5, "MIC": .15}, "HAT_OPEN"),
        ("A05", "Suitcase Stomp", "SUITCASE", {"TOOL": .6, "RATTLE": .7, "MIC": .12}, "KICK"),
        ("A06", "Pan Clang", "PAN", {"TOOL": .95, "DAMP": .55, "RATTLE": .6, "MIC": .12}, "SNARE"),
        ("A07", "Keys Choked", "KEYS", {"TOOL": .9, "DAMP": .75, "RATTLE": .3, "MIC": .1}, "HAT_CLOSED"),
        ("A08", "Drawer Slam", "DRAWER", {"TOOL": .95, "RATTLE": .8, "MIC": .15}, "HAT_OPEN"),
        ("A09", "Tub Floor", "TUB", {"TUNE": .25, "TOOL": .7, "MIC": .12}, "TOM"),
        ("A10", "Bucket Mid", "BUCKET", {"TUNE": .7, "TOOL": .7, "RATTLE": .3, "DAMP": .35, "MIC": .12}, "TOM"),
        ("A11", "Ladle Pot", "POT", {"TOOL": .95, "RATTLE": .55, "MIC": .15}, "PERC"),
        ("A12", "Stockpot", "POT", {"TUNE": .15, "TOOL": .7, "RATTLE": .9, "MIC": .15}, "PERC"),
        ("A13", "Bottle Key", "BOTTLE", {"TOOL": .95, "RATTLE": .6, "MIC": .12}, "PERC"),
        ("A14", "Lid Choke", "BINLID", {"TOOL": .9, "DAMP": .85, "MIC": .1}, "PERC"),
        ("A15", "Board Stick", "BOARD", {"TOOL": .9, "RATTLE": .6, "MIC": .12}, "PERC"),
        ("A16", "Bag Pop", "BAG", {"TOOL": .7, "MIC": .15}, "CLAP"),
    ],
    "groove": (
        [p for b in (0, 16) for p in (
            [("A01", b + 0, 1.0), ("A01", b + 3, .7), ("A01", b + 8, .9), ("A05", b + 11, .8), ("A02", b + 4, 1.0),
             ("A02", b + 12, 1.0), ("A02", b + 14, .45), ("A06", b + 7, .5)]
            + [("A03", b + s, .8 if s % 4 == 0 else .5) for s in range(0, 16) if s not in (4, 12)])]
        + [("A04", 0, .9), ("A11", 10, .6), ("A13", 22, .6), ("A09", 28, .9), ("A10", 29, .85), ("A09", 30, .9),
           ("A16", 31, 1.0)]
    ),
}

BOOMBAP = {
    "id": "boombap", "name": "CLUTTER JUNK BOOM BAP", "bpm": 90.0, "swing": 0.56,
    "character": "Rap drums from junk. A hard suitcase and heel for the knock, a paper bag and a slammed book for "
                 "the crack, tight damped hats, the kitchen half way in. Punchy, a little swung.",
    "pads": [
        ("A01", "Suitcase Knock", "SUITCASE", {"TUNE": .4, "TOOL": .3, "DAMP": .35, "RATTLE": .25, "MIC": .3}, "KICK"),
        ("A02", "Paper Bag Crack", "BAG", {"TOOL": .6, "RATTLE": .35, "MIC": .35}, "SNARE"),
        ("A03", "Tight Jar", "JAR", {"TOOL": .4, "DAMP": .65, "RATTLE": .4, "MIC": .25}, "HAT_CLOSED"),
        ("A04", "Key Shake", "KEYS", {"TOOL": .55, "RATTLE": .55, "DAMP": .35, "MIC": .3}, "HAT_OPEN"),
        ("A05", "Heel Stomp", "STOMP", {"TUNE": .35, "TOOL": .7, "RATTLE": .2, "MIC": .3}, "KICK"),
        ("A06", "Book Slam", "BOOK", {"TOOL": .75, "RATTLE": .55, "MIC": .35}, "CLAP"),
        ("A07", "Matchbox Flick", "MATCHBOX", {"TOOL": .85, "DAMP": .5, "MIC": .25}, "HAT_CLOSED"),
        ("A08", "Drawer Shut", "DRAWER", {"TOOL": .4, "RATTLE": .45, "DAMP": .4, "MIC": .35}, "HAT_OPEN"),
        ("A09", "Tub Low", "TUB", {"TUNE": .2, "TOOL": .4, "DAMP": .45, "MIC": .3}, "TOM"),
        ("A10", "Dead Pan", "PAN", {"TOOL": .5, "DAMP": .75, "RATTLE": .3, "MIC": .3}, "TOM"),
        ("A11", "Board Rim", "BOARD", {"TOOL": .8, "DAMP": .5, "MIC": .3}, "PERC"),
        ("A12", "Knuckle Fork", "TABLE", {"TOOL": .9, "RATTLE": .6, "MIC": .3}, "PERC"),
        ("A13", "Rice Box Crack", "BOX", {"TUNE": .35, "TOOL": .4, "RATTLE": .8, "DAMP": .3, "MIC": .35}, "SNARE"),
        ("A14", "Can Flick", "CAN", {"TOOL": .6, "MIC": .3}, "PERC"),
        ("A15", "Low Bottle", "BOTTLE", {"TUNE": .25, "TOOL": .6, "DAMP": .35, "MIC": .3}, "PERC"),
        ("A16", "Mug Spoon", "MUG", {"TOOL": .9, "RATTLE": .6, "MIC": .3}, "PERC"),
    ],
    "groove": (
        [p for b in (0, 16) for p in (
            [("A01", b + 0, 1.0), ("A05", b + 7, .6), ("A01", b + 10, .95), ("A02", b + 4, 1.0), ("A02", b + 12, 1.0),
             ("A13", b + 15, .35), ("A04", b + 14, .7)]
            + [("A03", b + s, .85 if s % 4 == 0 else .5) for s in range(0, 16, 2) if s != 14]
            + [("A07", b + 13, .4)])]
        + [("A06", 28, .9), ("A11", 6, .5), ("A12", 22, .45), ("A15", 27, .6), ("A16", 31, .5)]
    ),
}

MICRO = {
    "id": "micro", "name": "CLUTTER MICROBEAT", "bpm": 100.0, "swing": 0.5,
    "character": "Tiny objects, tuned up, damped and bone dry, the phone almost touching them. Clicks, ticks and "
                 "tinks in a busy, detailed pattern.",
    "pads": [
        ("A01", "Tiny Bucket", "BUCKET", {"TUNE": .78, "TOOL": .35, "DAMP": .65, "RATTLE": 0.0, "MIC": .03}, "KICK"),
        ("A02", "Small Bag Pop", "BAG", {"TUNE": .85, "TOOL": .1, "RATTLE": .2, "MIC": .03}, "SNARE"),
        ("A03", "Switch", "SWITCH", {"TOOL": .8, "MIC": .02}, "HAT_CLOSED"),
        ("A04", "Short Zip", "ZIP", {"TOOL": .95, "TUNE": .7, "RATTLE": 0.0, "MIC": .03}, "HAT_OPEN"),
        ("A05", "Box Thump", "BOX", {"TUNE": .85, "TOOL": .3, "RATTLE": 0.0, "DAMP": .7, "MIC": .03}, "KICK"),
        ("A06", "Board Tap", "BOARD", {"TUNE": .85, "TOOL": .5, "DAMP": .6, "MIC": .03}, "CLAP"),
        ("A07", "Sand Tick", "JAR", {"TOOL": .05, "DAMP": .8, "RATTLE": .25, "TUNE": .8, "MIC": .02}, "HAT_CLOSED"),
        ("A08", "Tiny Keys", "KEYS", {"TUNE": .9, "RATTLE": .1, "DAMP": .55, "TOOL": .8, "MIC": .03}, "HAT_OPEN"),
        ("A09", "Mug High", "MUG", {"TUNE": .85, "DAMP": .55, "RATTLE": .25, "MIC": .03}, "TOM"),
        ("A10", "Can High", "CAN", {"TUNE": .85, "DAMP": .5, "MIC": .03}, "TOM"),
        ("A11", "Glass Tick", "GLASS", {"TUNE": .9, "DAMP": .7, "RATTLE": .3, "MIC": .03}, "PERC"),
        ("A12", "Bottle Tick", "BOTTLE", {"TUNE": .9, "DAMP": .65, "RATTLE": 0.0, "MIC": .03}, "PERC"),
        ("A13", "Fork Tap", "TABLE", {"TOOL": 1.0, "TUNE": .8, "RATTLE": .3, "DAMP": .5, "MIC": .03}, "PERC"),
        ("A14", "Coin Jar", "JAR", {"TOOL": .95, "RATTLE": .2, "TUNE": .7, "MIC": .03}, "PERC"),
        ("A15", "Slow Zip", "ZIP", {"TOOL": .05, "TUNE": .85, "MIC": .03}, "PERC"),
        ("A16", "Switch Slow", "SWITCH", {"TOOL": .1, "RATTLE": .8, "TUNE": .75, "MIC": .03}, "PERC"),
    ],
    "groove": (
        [p for b in (0, 16) for p in (
            [("A01", b + 0, 1.0), ("A01", b + 6, .7), ("A05", b + 11, .8), ("A02", b + 4, 1.0), ("A02", b + 12, 1.0),
             ("A13", b + 3, .5)]
            + [("A03", b + s, (1.0, .45, .65, .4)[s % 4]) for s in range(0, 16) if s not in (4, 12)])]
        + [("A15", 9, .6), ("A09", 13, .7), ("A10", 14, .7), ("A08", 22, .6), ("A11", 25, .5), ("A12", 26, .5),
           ("A16", 31, .6)]
    ),
}

GENRE = [LOFI, STREET, BOOMBAP, MICRO]
ALL = [BASE] + GENRE

# The one-shot that replaces the round-0 roll on bank B (owner, 2026-11-13: "Roll becomes one shot").
B01 = ("B01", "Bin Lid", "BINLID", {}, "HAT_OPEN")

CHOKES = {"A03": ["A04"], "A07": ["A08"]}
MIX = {"KICK": 1.0, "SNARE": .85, "CLAP": .7, "HAT_CLOSED": .42, "HAT_OPEN": .4, "TOM": .65, "PERC": .45}


def check():
    for k in ALL:
        assert len(k["pads"]) == 16, k["id"]
        assert [p[0] for p in k["pads"]] == [f"A{i:02d}" for i in range(1, 17)], k["id"]
        for pad, label, obj, macros, cls in k["pads"]:
            assert obj in e.OBJECTS, (k["id"], pad, obj)
            assert set(macros) <= set(e.MACROS), (k["id"], pad, macros)
        pads = {p[0] for p in k["pads"]}
        assert all(g[0] in pads for g in k["groove"]), k["id"]


check()
