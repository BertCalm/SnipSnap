"""CALABASH kits: Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz.

Each kit is sixteen one-shot pads (A01 bottom left), its own tempo, roles, era and room, and two bars of groove to
hear it in. The owner's gate asks for genre kits that are "authored musical choices with distinct roles, balance and
character", so the four differ in which instruments fill the roles (a live kit with shekere and congas; a programmed
kick, rim and shaker with a talking drum; a sabar ensemble with a tama over a kit; a jazz kit under congas, bongos and
timbales), in how they are struck, in tuning, in the room and the era's chain, and in tempo and feel. Every setting
here is "shape": a first authored choice for the owner's listen. The grooves are authored in each style, not
transcribed from any record.

Grid: sixteenths, 32 steps for two bars. Step 0 is beat 1, 4 is beat 2. SWING delays every odd sixteenth by that
fraction of a sixteenth. Pad tuple: (pad, label, voice, macros). Groove tuple: (pad, step, velocity).
FEEL: per-pad timing in ms, how the player sits (early is negative).
"""
from __future__ import annotations

import calabash_proto as c

O, M, S = 1 / 3, 2 / 3, 1.0          # hand-drum STROKE anchors: open, muffled, slap (0 is bass)


def cls(voice):
    return c.VOICES[voice][0]


def era(room, age):
    return {"ROOM": room, "AGE": age}


# ------------------------------------------------------------------------------ Afrobeat, Lagos, the 1970s

E1 = era(.3, .55)
AFROBEAT = {
    "id": "afrobeat", "name": "CALABASH AFROBEAT", "bpm": 112.0, "swing": 0.12,
    "character": "Lagos in the 1970s, the big band's rhythm section live in one room: a drummer opening and closing "
                 "the hats around a tight, high snare and a round kick, two congas and a tumba, a shekere on the "
                 "sixteenths, the sticks' line and a bell. A dry studio, sixteen-track tape pushed warm.",
    "pads": [
        ("A01", "Kick", "KICK", dict(E1, TUNE=.45, DAMP=.55, BEATER=.45)),
        ("A02", "Snare", "SNARE", dict(E1, TUNE=.7, DAMP=.35, WIRES=.45)),
        ("A03", "Rimshot", "RIMSHOT", dict(E1, TUNE=.7, DAMP=.4, WIRES=.4)),
        ("A04", "Hat Closed", "HAT_CLOSED", dict(E1, TUNE=.55, DAMP=.35)),
        ("A05", "Hat Half Open", "HAT_OPEN", dict(E1, TUNE=.55, DAMP=.55)),
        ("A06", "Hat Foot", "HAT_FOOT", dict(E1, TUNE=.55, DAMP=.3)),
        ("A07", "Rack Tom", "TOM", dict(E1, TUNE=.62, DAMP=.45)),
        ("A08", "Floor Tom", "TOM", dict(E1, TUNE=.3, DAMP=.45)),
        ("A09", "Shekere", "SHEKERE", dict(E1, STROKE=0.0, DAMP=.3)),
        ("A10", "Shekere Slap", "SHEKERE", dict(E1, STROKE=1.0, DAMP=.3)),
        ("A11", "Conga Open", "CONGA", dict(E1, STROKE=O)),
        ("A12", "Conga Slap", "CONGA", dict(E1, STROKE=S)),
        ("A13", "Tumba Open", "TUMBA", dict(E1, STROKE=O, TUNE=.45)),
        ("A14", "Sticks", "STICKS", dict(E1, TUNE=.45, DAMP=.45)),
        ("A15", "Bell", "BIG_BELL", dict(E1, STROKE=0.0, TUNE=.45)),
        ("A16", "Shekere Rush", "SHEKERE_RUSH", dict(E1, SPEED=.5)),
    ],
    "feel": {"A02": 6, "A04": -3, "A09": -4, "A14": -2},
    "groove": (
        # the hats: closed sixteenths, opened on the a of 2 and 4, the foot closing them on the next beat
        [("A04", s + b, v) for b in (0, 16) for s, v in ((0, .7), (1, .35), (2, .6), (4, .7), (5, .35), (8, .7), (9, .35),
                                                          (10, .6), (12, .7), (13, .35))]
        + [("A05", s, .75) for s in (6, 14, 22, 30)] + [("A06", s, .55) for s in (8, 16, 24)]
        + [("A01", s, v) for s, v in ((0, .95), (3, .55), (10, .8), (16, .9), (19, .5), (26, .85), (27, .5))]
        + [("A02", s, .9) for s in (4, 12, 20, 28)] + [("A02", s, .28) for s in (7, 9, 15, 23, 25, 31)]
        + [("A09", s, .75 if s % 4 == 2 else .45) for s in range(0, 32, 2)]
        + [("A11", s, .7) for s in (6, 7, 22, 23)] + [("A12", s, .85) for s in (4, 20)] + [("A13", s, .75) for s in (14, 30)]
        + [("A14", s, .7) for s in (0, 3, 6, 10, 12, 16, 19, 22, 26, 28)]
        + [("A15", s, .6) for s in (0, 8, 16, 24)]
    ),
}

# ------------------------------------------------------------------------------ Afrobeats, Lagos and Accra, now

E2 = era(.05, .0)
AFROBEATS = {
    "id": "afrobeats", "name": "CALABASH AFROBEATS", "bpm": 104.0, "swing": 0.16,
    "character": "Today's Lagos and Accra pop, programmed: a deep, round kick, a crisp rim on the three-three-two, "
                 "a clap and snaps on the backbeat, a swung shaker, congas and a talking drum answering, a high bell. "
                 "Close and dry, a clean modern chain.",
    "pads": [
        ("A01", "Deep Kick", "DEEP_KICK", dict(E2, TUNE=.45, DAMP=.45, CLICK=.4)),
        ("A02", "Rim", "RIM", dict(E2, TUNE=.6, DAMP=.4)),
        ("A03", "Clap", "CLAP", dict(E2, CROWD=.35, TUNE=.5)),
        ("A04", "Snaps", "SNAP", dict(E2, CROWD=.4, TUNE=.55)),
        ("A05", "Shaker", "SHAKER", dict(E2, STROKE=0.0, TUNE=.55)),
        ("A06", "Shaker Back", "SHAKER", dict(E2, STROKE=.5, TUNE=.55)),
        ("A07", "Hat", "HAT_CLOSED", dict(E2, TUNE=.65, DAMP=.5)),
        ("A08", "Open Hat", "HAT_OPEN", dict(E2, TUNE=.65, DAMP=.65)),
        ("A09", "Conga Slap", "CONGA", dict(E2, STROKE=S, TUNE=.6)),
        ("A10", "Quinto", "QUINTO", dict(E2, STROKE=O, TUNE=.55)),
        ("A11", "Tumba", "TUMBA", dict(E2, STROKE=O, TUNE=.4)),
        ("A12", "Talking Drum", "DUNDUN", dict(E2, SQUEEZE=.4, CONTOUR=0.0)),
        ("A13", "Talking Drum Fall", "DUNDUN", dict(E2, SQUEEZE=.6, CONTOUR=.5, TUNE=.55)),
        ("A14", "Bell", "AGOGO_HIGH", dict(E2, TUNE=.55, DAMP=.4)),
        ("A15", "Shekere", "SHEKERE", dict(E2, STROKE=.5, TUNE=.6, DAMP=.45)),
        ("A16", "Talking Call", "TALKING_CALL", dict(E2, SPEED=.5, SQUEEZE=.6)),
    ],
    "feel": {"A03": 4, "A05": -3},
    "groove": (
        [("A01", s, v) for s, v in ((0, 1.0), (7, .7), (8, .9), (16, 1.0), (23, .7), (24, .9), (30, .6))]
        + [("A02", s, .8) for s in (3, 6, 10, 19, 22, 26)]
        + [("A03", s, .9) for s in (8, 24)] + [("A04", s, .75) for s in (8, 24)]
        + [("A05", s, .7 if s % 4 == 2 else .4) for s in range(0, 32, 2)] + [("A06", s, .35) for s in range(1, 32, 2)]
        + [("A07", s, .5) for s in (2, 6, 10, 18, 22, 26)] + [("A08", 14, .6), ("A08", 30, .6)]
        + [("A09", s, .75) for s in (4, 12, 20)] + [("A10", s, .6) for s in (10, 11, 26, 27)] + [("A11", s, .7) for s in (15, 31)]
        + [("A12", 13, .7), ("A12", 21, .6), ("A13", 29, .8)]
        + [("A14", s, .45) for s in (0, 3, 6, 10, 12, 16, 19, 22, 26, 28)]
    ),
}

# ------------------------------------------------------------------------------ Mbalax, Dakar, the 1980s and 90s

E3 = era(.35, .25)
MBALAX = {
    "id": "mbalax", "name": "CALABASH MBALAX", "bpm": 126.0, "swing": 0.0,
    "character": "Dakar's dance bands of the 1980s and 90s: a sabar ensemble on top of a kit. The nder leads with "
                 "the stick and the hand, the mbeng-mbeng plays the mbalax underneath, the col holds the bass, the "
                 "tama talks over it, and the drummer drives straight eighths with a high, cracking snare. A bright, "
                 "live room.",
    "pads": [
        ("A01", "Kick", "KICK", dict(E3, TUNE=.55, DAMP=.6, BEATER=.8)),
        ("A02", "Snare", "SNARE", dict(E3, TUNE=.85, DAMP=.4, WIRES=.6)),
        ("A03", "Hat", "HAT_CLOSED", dict(E3, TUNE=.6, DAMP=.4)),
        ("A04", "Open Hat", "HAT_OPEN", dict(E3, TUNE=.6, DAMP=.5)),
        ("A05", "Nder Stick", "NDER", dict(E3, STROKE=M)),
        ("A06", "Nder Slap", "NDER", dict(E3, STROKE=O)),
        ("A07", "Nder Pressed", "NDER", dict(E3, STROKE=S)),
        ("A08", "Mbeng Open", "MBENG", dict(E3, STROKE=0.0)),
        ("A09", "Mbeng Stick", "MBENG", dict(E3, STROKE=M)),
        ("A10", "Col Bass", "COL", dict(E3, STROKE=0.0)),
        ("A11", "Col Stick", "COL", dict(E3, STROKE=M)),
        ("A12", "Tama", "TAMA", dict(E3, SQUEEZE=.3, CONTOUR=0.0)),
        ("A13", "Tama Talk", "TAMA", dict(E3, SQUEEZE=.75, CONTOUR=1.0, TUNE=.45)),
        ("A14", "High Tom", "TOM", dict(E3, TUNE=.85, DAMP=.5)),
        ("A15", "Crash", "CRASH", dict(E3, TUNE=.55, DAMP=.35)),
        ("A16", "Sabar Roll", "SABAR_ROLL", dict(E3, SPEED=.6)),
    ],
    "feel": {"A05": -4, "A09": -2, "A02": 2},
    "groove": (
        [("A01", s, .9) for s in (0, 4, 8, 12, 16, 20, 24, 28)] + [("A01", s, .6) for s in (10, 26)]
        + [("A02", s, .9) for s in (4, 12, 20, 28)] + [("A02", s, .35) for s in (14, 15, 31)]
        + [("A03", s, .6 if s % 4 else .75) for s in range(0, 32, 2)] + [("A04", s, .65) for s in (6, 22)]
        + [("A08", s, .8) for s in (0, 3, 8, 11, 16, 19, 24, 27)] + [("A09", s, .7) for s in (2, 6, 7, 10, 14, 18, 22, 23, 26, 30)]
        + [("A10", s, .85) for s in (0, 6, 16, 22)] + [("A11", s, .6) for s in (10, 26)]
        # the nder's bakk in bar two
        + [("A05", 16, .8), ("A05", 17, .55), ("A05", 18, .8), ("A06", 20, .9), ("A05", 22, .8), ("A05", 23, .6),
           ("A07", 24, 1.0), ("A05", 26, .75), ("A05", 27, .6), ("A06", 28, .85), ("A07", 30, 1.0)]
        + [("A12", 13, .7), ("A13", 29, .85)] + [("A14", 15, .7)] + [("A15", 0, .7)]
    ),
}

# ------------------------------------------------------------------------------ Afro-Cuban jazz, New York, the 1950s

E4 = era(.6, .7)
AFROCUBAN = {
    "id": "afro-cuban-jazz", "name": "CALABASH AFRO-CUBAN JAZZ", "bpm": 176.0, "swing": 0.0,
    "character": "New York in the 1950s, the big band's Latin section beside a jazz drummer: congas on the tumbao, "
                 "bongos on the martillo, a mambo bell, the timbalero's shell and abanico, the clave on two-three, and "
                 "a ride and an open jazz kick under it. A big studio heard from across the floor, a valve and tape "
                 "chain.",
    "pads": [
        ("A01", "Kick", "KICK", dict(E4, TUNE=.65, DAMP=.2, BEATER=.15)),
        ("A02", "Snare", "SNARE", dict(E4, TUNE=.5, DAMP=.3, WIRES=.55)),
        ("A03", "Ride", "RIDE", dict(E4, TUNE=.5, DAMP=.3)),
        ("A04", "Hat Foot", "HAT_FOOT", dict(E4, TUNE=.45, DAMP=.3)),
        ("A05", "Conga Open", "CONGA", dict(E4, STROKE=O)),
        ("A06", "Conga Slap", "CONGA", dict(E4, STROKE=S)),
        ("A07", "Tumba Open", "TUMBA", dict(E4, STROKE=O)),
        ("A08", "Quinto Open", "QUINTO", dict(E4, STROKE=O)),
        ("A09", "Conga Heel", "CONGA", dict(E4, STROKE=0.0)),
        ("A10", "Bongo High", "BONGO_HIGH", dict(E4, STROKE=O)),
        ("A11", "Bongo Low", "BONGO_LOW", dict(E4, STROKE=O)),
        ("A12", "Timbale", "TIMBALE", dict(E4, STROKE=0.0, TUNE=.55)),
        ("A13", "Paila", "TIMBALE", dict(E4, STROKE=1.0, TUNE=.55)),
        ("A14", "Mambo Bell", "COWBELL", dict(E4, STROKE=0.0, TUNE=.45)),
        ("A15", "Clave", "CLAVE", dict(E4, TUNE=.5, DAMP=.3)),
        ("A16", "Abanico", "ABANICO", dict(E4, SPEED=.55, TUNE=.55)),
    ],
    "feel": {"A03": -5, "A14": -2, "A02": 6},
    "groove": (
        # two-three son clave
        [("A15", s, .8) for s in (4, 8, 16, 22, 28)]
        # the tumbao: heel on 1 and 3, slap on 2 and 4, open tones on the and of 4 and 4
        + [("A09", s, .55) for s in (0, 8, 16, 24)] + [("A06", s, .85) for s in (4, 12, 20, 28)]
        + [("A05", s, .8) for s in (14, 15, 30)] + [("A07", 31, .8)]
        # the martillo
        + [("A10", s, .7 if s % 4 == 0 else .45) for s in range(0, 32, 2) if s % 8 != 6] + [("A11", s, .75) for s in (6, 14, 22, 30)]
        # the paila on the cascara, the bell on the beat
        + [("A13", s, .55 if s % 4 else .75) for s in (0, 2, 3, 5, 6, 8, 10, 11, 13, 14, 16, 18, 19, 21, 22, 24, 26, 27, 29, 30)]
        + [("A14", s, .7) for s in (0, 4, 8, 12, 16, 20, 24, 28)]
        # the jazz drummer
        + [("A03", s, .7) for s in (0, 4, 6, 8, 12, 14, 16, 20, 22, 24, 28, 30)] + [("A04", s, .6) for s in (4, 12, 20, 28)]
        + [("A01", s, .7) for s in (6, 14, 22, 30)] + [("A02", 27, .5), ("A12", 31, .85), ("A08", 26, .7)]
    ),
}

GENRE = [AFROBEAT, AFROBEATS, MBALAX, AFROCUBAN]
ALL = GENRE

# Chokes: hats close hats, and strokes on one drum cut that drum's ring (a slap after an open tone on the same conga).
def mutual(*pads):
    return {p: [q for q in pads if q != p] for p in pads}


CHOKES = {
    "afrobeat": {"A04": ["A05"], "A06": ["A05"], **mutual("A11", "A12")},
    "afrobeats": {"A07": ["A08"], **mutual("A12", "A13")},
    "mbalax": {"A03": ["A04"], **mutual("A05", "A06", "A07"), **mutual("A08", "A09"), **mutual("A10", "A11"),
               **mutual("A12", "A13")},
    "afro-cuban-jazz": {**mutual("A05", "A06", "A09")},
}

# Groove balance by drum class (the sampler's pad levels in the audition grooves only).
MIX = {"KICK": 1.0, "SNARE": .8, "CLAP": .6, "HAT_CLOSED": .4, "HAT_OPEN": .4, "TOM": .7, "PERC": .55}
MIX_VOICE = {"SHEKERE": .45, "SHAKER": .38, "RIDE": .4, "CRASH": .45, "STICKS": .45, "CLAVE": .5, "BIG_BELL": .38,
             "COWBELL": .42, "AGOGO_HIGH": .32, "SNAP": .45, "RIM": .7, "TIMBALE": .6, "NDER": .7, "MBENG": .6,
             "COL": .8, "TAMA": .6, "DUNDUN": .7, "CONGA": .65, "TUMBA": .7, "QUINTO": .6, "BONGO_HIGH": .5,
             "BONGO_LOW": .55}


def mix_for(voice):
    return MIX_VOICE.get(voice, MIX[cls(voice)])


for k in ALL:
    assert [p[0] for p in k["pads"]] == [f"A{i:02d}" for i in range(1, 17)], k["id"]
    for _, _, voice, macros in k["pads"]:
        assert voice in c.VOICES and set(macros) <= set(c.macros_for(voice)), (k["id"], voice, macros)
    for pad, step, vel in k["groove"]:
        assert pad in {p[0] for p in k["pads"]} and 0 <= step < 32 and 0 < vel <= 1, (k["id"], pad, step)
