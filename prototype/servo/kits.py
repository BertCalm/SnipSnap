"""SERVO kits: three genre kits from one machine. Python harness, not Kotlin.

Each kit is sixteen one-shot pads in the AutoPlace layout (A01 kick, A02 snare, A03/A04 closed and
open hat, A05 second kick, A06 clap or second snare, A07/A08 second hat pair, A09/A10 toms, then
percussion), a tempo, a two-bar groove, and two baked phrases for bank B: B01 a roll and B02 a
riser. A03 chokes A04 and A07 chokes A08, as hats do.

The owner's gate asks for genre kits that are "authored musical choices with distinct roles, balance
and character", not one kit at other effect strengths. So the three differ in where the drums are
tuned, how long they ring, which voices fill the roles, and which of the rail's three readouts
leads: ELECTRO leads with the dive (ZAP high, a clean cool rail), JIT with the flat-top (HEAT high,
everything short and pitched up), NEURO with the snarl (GROWL high, a rail that rings). Every
setting is "shape": a first authored choice for the owner's listen, written before anyone heard it.

Pad tuple: (pad, label, voice, macros, class). Groove tuple: (pad, sixteenth step 0..31, velocity).
"""
from __future__ import annotations


def _m(tune=.5, decay=.5, zap=.4, sag=.45, growl=.35, heat=.4):
    return {"TUNE": tune, "DECAY": decay, "ZAP": zap, "SAG": sag, "GROWL": growl, "HEAT": heat}


def _bars(pattern):
    """Repeat a one-bar pattern (pad, step, velocity) across both bars."""
    return [(p, b + s, v) for b in (0, 16) for (p, s, v) in pattern]


ELECTRO = {
    "id": "electro", "name": "SERVO ELECTRO", "bpm": 126.0, "rail_growl": 0.2,
    "character": "Electro. A long low boom, a snap, a cowbell and toms that fall like lasers. The rail is "
                 "cool and well damped: every hit dives and comes straight back, clean and robotic.",
    "pads": [
        ("A01", "Boom Kick", "KICK", _m(.25, .78, .5, .3, .15, .25), "KICK"),
        ("A02", "Snap Snare", "SNARE", _m(.45, .45, .35, .4, .2, .25), "SNARE"),
        ("A03", "Tick Hat", "HAT", _m(.5, .12, .3, .4, .2, .2), "HAT_CLOSED"),
        ("A04", "Open Hat", "HAT", _m(.5, .7, .3, .4, .2, .2), "HAT_OPEN"),
        ("A05", "Short Kick", "KICK", _m(.45, .35, .65, .35, .15, .3), "KICK"),
        ("A06", "Clap", "CLAP", _m(.5, .55, .3, .4, .2, .25), "CLAP"),
        ("A07", "Zap Hat", "HAT", _m(.75, .2, .9, .5, .2, .25), "HAT_CLOSED"),
        ("A08", "Sizzle Hat", "HAT", _m(.3, .9, .5, .45, .25, .25), "HAT_OPEN"),
        ("A09", "Low Zap Tom", "TOM", _m(.3, .5, .7, .4, .15, .25), "TOM"),
        ("A10", "High Zap Tom", "TOM", _m(.65, .4, .75, .4, .15, .25), "TOM"),
        ("A11", "Cowbell", "BELL", _m(.55, .5, .2, .4, .2, .3), "PERC"),
        ("A12", "Rim", "RIM", _m(.5, .5, .3, .4, .2, .3), "PERC"),
        ("A13", "Laser", "LASER", _m(.55, .5, .7, .45, .2, .2), "PERC"),
        ("A14", "Bass Hit", "STAB", _m(.4, .4, .3, .45, .2, .3), "PERC"),
        ("A15", "Robot Tom", "TOM", _m(.48, .3, .95, .8, .3, .35), "TOM"),
        ("A16", "Crash", "CRASH", _m(.5, .6, .4, .4, .2, .2), "PERC"),
    ],
    "groove": (
        _bars([("A01", 0, 1.0), ("A01", 6, .9), ("A01", 10, 1.0), ("A02", 4, 1.0), ("A02", 12, 1.0)]
              + [("A03", s, .9 if s % 4 == 0 else .5) for s in range(16) if s != 14]
              + [("A04", 14, .8), ("A11", 2, .6), ("A11", 7, .65), ("A11", 9, .55),
                 ("A14", 0, .9), ("A14", 3, .65), ("A14", 8, .8), ("A14", 11, .65)])
        + [("A12", 15, .7), ("A06", 28, .9), ("A13", 16, .5), ("A01", 29, .8),
           ("A10", 26, .8), ("A10", 27, .65), ("A09", 30, .85), ("A15", 31, .9), ("A07", 13, .7), ("A07", 21, .7)]
    ),
    # (pad, time in beats, velocity). The roll and riser are baked on ONE rail, so the pile-up
    # of hits drags it down: the roll dives as it tightens.
    "roll": [("A01", 0, 1.0)] + [("A10", .25 * i, .45 + .05 * i) for i in range(4)]
            + [("A09", 1 + .125 * i, .55 + .055 * i) for i in range(8)],
    "riser": [("A07", .5 * i, .35 + .04 * i) for i in range(4)] + [("A07", 2 + .25 * i, .5 + .05 * i) for i in range(4)]
             + [("A15", 3 + .125 * i, .6 + .05 * i) for i in range(8)] + [("A13", 0, .5), ("A01", 4, 1.0), ("A16", 4, 1.0)],
}

JIT = {
    "id": "jit", "name": "SERVO JIT", "bpm": 156.0, "rail_growl": 0.35,
    "character": "Ghetto tech. The same machine pitched up and run hot: a hard short kick on every beat, "
                 "claps, rims and offbeat hats squared off against the rail and gone before the next sixteenth.",
    "pads": [
        ("A01", "Hard Kick", "KICK", _m(.7, .3, .35, .55, .3, .8), "KICK"),
        ("A02", "Crack Snare", "SNARE", _m(.7, .3, .5, .5, .3, .7), "SNARE"),
        ("A03", "Tight Hat", "HAT", _m(.65, .08, .3, .5, .3, .6), "HAT_CLOSED"),
        ("A04", "Off Hat", "HAT", _m(.65, .5, .3, .5, .3, .65), "HAT_OPEN"),
        ("A05", "Driven Boom", "KICK", _m(.5, .6, .3, .7, .35, .85), "KICK"),
        ("A06", "Hot Clap", "CLAP", _m(.7, .35, .4, .5, .3, .7), "CLAP"),
        ("A07", "Shaker Tick", "HAT", _m(.9, .03, .2, .4, .3, .5), "HAT_CLOSED"),
        ("A08", "Ride", "HAT", _m(.8, .85, .2, .45, .35, .55), "HAT_OPEN"),
        ("A09", "Low Tom", "TOM", _m(.5, .3, .5, .55, .3, .7), "TOM"),
        ("A10", "High Tom", "TOM", _m(.8, .25, .5, .55, .3, .7), "TOM"),
        ("A11", "High Bell", "BELL", _m(.8, .3, .3, .5, .3, .6), "PERC"),
        ("A12", "Rim Shot", "RIM", _m(.6, .45, .4, .5, .3, .75), "PERC"),
        ("A13", "Short Laser", "LASER", _m(.7, .25, .5, .5, .3, .5), "PERC"),
        ("A14", "Jack Bass", "STAB", _m(.75, .25, .4, .6, .35, .8), "PERC"),
        ("A15", "Clave", "RIM", _m(.95, .2, .1, .4, .3, .5), "PERC"),
        ("A16", "Short Crash", "CRASH", _m(.6, .35, .3, .5, .3, .6), "PERC"),
    ],
    "groove": (
        _bars([("A01", s, 1.0) for s in (0, 4, 8, 12)]
              + [("A06", 4, 1.0), ("A06", 12, 1.0), ("A02", 12, .8)]
              + [("A04", s, .8) for s in (2, 6, 10, 14)]
              + [("A03", s, .5) for s in (1, 3, 5, 7, 9, 11, 13, 15)]
              + [("A12", 3, .7), ("A12", 7, .55), ("A12", 10, .75), ("A12", 13, .6),
                 ("A14", 0, .9), ("A14", 3, .7), ("A14", 6, .85), ("A14", 11, .7), ("A11", 6, .6), ("A11", 9, .55)])
        + [("A05", 0, 1.0), ("A01", 14, .8), ("A10", 26, .85), ("A10", 27, .7), ("A09", 29, .9), ("A09", 30, .8),
           ("A15", 15, .7), ("A15", 23, .6), ("A13", 31, .7)]
    ),
    "roll": [("A01", 0, 1.0)] + [("A06", .25 * i, .5 + .06 * i) for i in range(4)]
            + [("A12", 1 + .125 * i, .55 + .055 * i) for i in range(8)],
    "riser": [("A03", .25 * i, .35 + .03 * i) for i in range(8)] + [("A02", 2 + .25 * i, .5 + .06 * i) for i in range(4)]
             + [("A02", 3 + .125 * i, .65 + .045 * i) for i in range(8)] + [("A05", 4, 1.0), ("A16", 4, 1.0)],
}

NEURO = {
    "id": "neuro", "name": "SERVO NEURO", "bpm": 174.0, "rail_growl": 0.85,
    "character": "Neurofunk. A punch kick and a snare with its body near 200 Hz, on a rail that rings: tails "
                 "growl, hats dive when the kick lands, and the bass stabs snarl through a moving filter.",
    "pads": [
        ("A01", "Punch Kick", "KICK", _m(.62, .22, .55, .6, .5, .55), "KICK"),
        ("A02", "Steel Snare", "SNARE", _m(.5, .5, .45, .65, .85, .55), "SNARE"),
        ("A03", "Tech Hat", "HAT", _m(.8, .1, .4, .6, .8, .45), "HAT_CLOSED"),
        ("A04", "Open Tech Hat", "HAT", _m(.8, .45, .4, .6, .9, .45), "HAT_OPEN"),
        ("A05", "Sub Kick", "KICK", _m(.3, .45, .3, .55, .7, .45), "KICK"),
        ("A06", "Ghost Snare", "SNARE", _m(.65, .2, .2, .5, .6, .3), "SNARE"),
        ("A07", "Tick", "HAT", _m(1.0, .02, .7, .5, .7, .4), "HAT_CLOSED"),
        ("A08", "Dark Ride", "HAT", _m(.35, .9, .3, .55, .6, .45), "HAT_OPEN"),
        ("A09", "Growl Tom", "TOM", _m(.25, .5, .5, .8, .95, .55), "TOM"),
        ("A10", "High Growl Tom", "TOM", _m(.55, .4, .5, .8, .95, .55), "TOM"),
        ("A11", "Metal", "BELL", _m(.3, .6, .4, .8, .9, .6), "PERC"),
        ("A12", "Low Rim", "RIM", _m(.35, .6, .4, .65, .8, .5), "PERC"),
        ("A13", "Scanner", "LASER", _m(.3, .6, .8, .8, 1.0, .5), "PERC"),
        ("A14", "Reese Stab", "STAB", _m(.5, .6, .3, .8, .9, .6), "PERC"),
        ("A15", "Snarl Stab", "STAB", _m(.9, .35, .6, 1.0, 1.0, .75), "PERC"),
        ("A16", "Crash", "CRASH", _m(.45, .7, .4, .6, .7, .45), "PERC"),
    ],
    "groove": (
        _bars([("A01", 0, 1.0), ("A01", 10, .95), ("A02", 4, 1.0), ("A02", 12, 1.0),
               ("A06", 7, .4), ("A06", 9, .35), ("A06", 15, .45)]
              + [("A03", s, .8 if s % 4 == 0 else .55) for s in range(0, 16, 2)]
              + [("A07", s, .4) for s in (1, 3, 5, 11, 13)]
              + [("A08", s, .5) for s in (0, 4, 8, 12)]
              + [("A14", 0, .95), ("A14", 6, .7), ("A14", 11, .8)])
        + [("A01", 22, .85), ("A05", 19, .8), ("A15", 30, .9), ("A13", 24, .5), ("A04", 14, .75), ("A04", 30, .75),
           ("A12", 3, .6), ("A12", 27, .65), ("A11", 13, .5)]
    ),
    "roll": [("A01", 0, 1.0)] + [("A06", .25 * i, .5 + .06 * i) for i in range(4)]
            + [("A02", 1 + .125 * i, .5 + .06 * i) for i in range(8)],
    "riser": [("A13", 0, .6)] + [("A07", .25 * i, .35 + .03 * i) for i in range(8)]
             + [("A06", 2 + .25 * i, .5 + .06 * i) for i in range(4)] + [("A10", 3 + .125 * i, .6 + .05 * i) for i in range(8)]
             + [("A01", 4, 1.0), ("A16", 4, 1.0), ("A15", 4, .9)],
}

KITS = (ELECTRO, JIT, NEURO)

# A pad's share of a phrase mix, by class and voice. "shape"
MIX = {"KICK": 1.0, "SNARE": .85, "CLAP": .75, "HAT_CLOSED": .4, "HAT_OPEN": .4, "TOM": .7, "PERC": .55}
MIX_VOICE = {"STAB": .8, "CRASH": .45, "BELL": .45, "LASER": .45}
