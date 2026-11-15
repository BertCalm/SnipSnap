"""EMBER kits: the club kit and three genre kits from one night in 1962.

Each kit is sixteen one-shot pads (A01 bottom left), its own tempo and pad roles, and two bars of
groove to hear it in. The owner's gate asks for genre kits that are "authored musical choices with
distinct roles, balance and character", so the four kits differ in implements (sticks, brushes,
mallets, hands, a bow), in which drums fill each role, in tuning, damping and looseness, in where
the microphone is, in how much of the 1962 chain is on them, and in tempo and feel. Every setting
here is "shape": a first authored choice for the owner's listen.

Jazz is counted in triplets, so the grooves sit on a triplet-eighth grid: twelve steps a bar, 24 in
two bars. Step 0 is beat 1, 3 is beat 2, 5 is the "let" before beat 3 (the ride's skip note).

Pad tuple: (pad, label, voice, macros). Groove tuple: (pad, step, velocity).
FEEL: per-pad timing in ms, how the player sits: the ride a hair ahead, the snare comping behind.
"""
from __future__ import annotations

import ember_proto as e


def cls(voice):
    return e.VOICES[voice][0]


CLUB = {
    "id": "ember", "name": "EMBER", "bpm": 138.0,
    "character": "The club kit, 1962, hard bop on sticks: an open 18-inch kick feathered under the band, a high crisp "
                 "snare, a riveted ride carrying the time, the hi-hat foot on 2 and 4. Close mics, a little of the room, "
                 "a warm chain.",
    "pads": [
        ("A01", "Kick", "KICK", {"TUNE": .55, "DAMP": .25}),
        ("A02", "Snare", "SNARE", {}),
        ("A03", "Hat Foot", "HAT_FOOT", {}),
        ("A04", "Hat Open", "HAT_OPEN", {}),
        ("A05", "Ride", "RIDE", {}),
        ("A06", "Ride Bell", "RIDE_BELL", {}),
        ("A07", "Cross Stick", "CROSS_STICK", {}),
        ("A08", "Rimshot", "RIMSHOT", {}),
        ("A09", "Rack Tom", "TOM_RACK", {}),
        ("A10", "Floor Tom", "TOM_FLOOR", {}),
        ("A11", "Hat Stick", "HAT_CLOSED", {}),
        ("A12", "Snaps", "SNAPS", {"CROWD": .5}),
        ("A13", "Sticks", "STICKS", {}),
        ("A14", "Elbow Tom", "ELBOW_TOM", {}),
        ("A15", "Press Roll", "PRESS_ROLL", {}),
        ("A16", "Cymbal Swell", "CYMBAL_SWELL", {}),
    ],
    "feel": {"A05": -6, "A02": 8, "A01": 0, "A03": 2},
    "groove": (
        # the ride: ding, ding-ga ding, ding-ga
        [("A05", s + b, v) for b in (0, 12) for s, v in ((0, .75), (3, .9), (5, .55), (6, .78), (9, .9), (11, .55))]
        + [("A03", s, .7) for s in (3, 9, 15, 21)]
        + [("A01", s, .22) for s in (0, 3, 6, 9, 12, 15, 18)]          # feathered
        + [("A01", 20, .9)]                                               # a bomb
        + [("A02", 8, .35), ("A02", 11, .6), ("A02", 17, .4), ("A02", 22, .5), ("A02", 23, .85)]
    ),
}

AFTER_HOURS = {
    "id": "after-hours", "name": "EMBER AFTER HOURS", "bpm": 66.0,
    "character": "Three in the morning, a ballad on brushes: sweeps in slow circles, taps on 2 and 4, a riveted sizzle far "
                 "off, a damped kick barely there, two people snapping at the back. More room, a little chain.",
    "pads": [
        ("A01", "Soft Kick", "KICK", {"TUNE": .45, "DAMP": .5, "BUZZ": .3, "DISTANCE": .4, "WARMTH": .35}),
        ("A02", "Brush Tap", "BRUSH_TAP", {"BUZZ": .6, "DISTANCE": .4, "WARMTH": .35}),
        ("A03", "Hat Foot", "HAT_FOOT", {"DAMP": .55, "TUNE": .45, "DISTANCE": .45, "WARMTH": .35}),
        ("A04", "Slow Sweep", "BRUSH_SWEEP", {"SPEED": .1, "DISTANCE": .4, "WARMTH": .35}),
        ("A05", "Brush Ride", "BRUSH_RIDE", {"DISTANCE": .45, "WARMTH": .35}),
        ("A06", "Far Sizzle", "SIZZLE", {"TUNE": .3, "DAMP": .3, "DISTANCE": .6, "WARMTH": .35}),
        ("A07", "Quick Sweep", "BRUSH_SWEEP", {"SPEED": .8, "TUNE": .4, "BUZZ": .7, "DISTANCE": .4, "WARMTH": .35}),
        ("A08", "Soft Cross Stick", "CROSS_STICK", {"TUNE": .35, "DAMP": .55, "DISTANCE": .5, "WARMTH": .35}),
        ("A09", "Brushed Tom", "BRUSH_TOM", {"TUNE": .65, "DISTANCE": .45, "WARMTH": .35}),
        ("A10", "Brushed Floor", "BRUSH_TOM", {"TUNE": .3, "DAMP": .2, "DISTANCE": .45, "WARMTH": .35}),
        ("A11", "Mallet Tom", "MALLET_TOM", {"TUNE": .45, "DAMP": .2, "DISTANCE": .5, "WARMTH": .35}),
        ("A12", "Two Snapping", "SNAPS", {"CROWD": .2, "TUNE": .45, "DISTANCE": .6, "WARMTH": .35}),
        ("A13", "Hat Open", "HAT_OPEN", {"DAMP": .55, "TUNE": .45, "DISTANCE": .5, "WARMTH": .35}),
        ("A14", "Loose Brush", "BRUSH_TAP", {"TUNE": .35, "BUZZ": .95, "DAMP": .5, "DISTANCE": .45, "WARMTH": .35}),
        ("A15", "Brush Roll", "BRUSH_ROLL", {"SPEED": .3, "DISTANCE": .45, "WARMTH": .35}),
        ("A16", "Far Swell", "CYMBAL_SWELL", {"SPEED": .3, "TUNE": .4, "DISTANCE": .65, "WARMTH": .35}),
    ],
    "feel": {"A02": 12, "A04": -10, "A03": 6},
    "groove": (
        [("A04", s, .6) for s in (0, 6, 12, 18)]
        + [("A02", s, .7) for s in (3, 9, 15, 21)]
        + [("A02", 8, .3), ("A02", 20, .35)]
        + [("A03", s, .45) for s in (3, 9, 15, 21)]
        + [("A01", s, .2) for s in (0, 12)]
        + [("A12", s, .55) for s in (15, 21)]
    ),
}

SOUL = {
    "id": "soul-jazz", "name": "EMBER SOUL JAZZ", "bpm": 104.0,
    "character": "The organ-trio set: a shuffle with a fat, loose backbeat, tambourine and a table clapping on 2 and 4, "
                 "a sizzle crash, two congas, a tighter kick. The chain pushed warmer, the mics close.",
    "pads": [
        ("A01", "Kick", "KICK", {"TUNE": .4, "DAMP": .6, "BUZZ": .3, "DISTANCE": .15, "WARMTH": .6}),
        ("A02", "Fat Snare", "SNARE", {"TUNE": .3, "DAMP": .55, "BUZZ": .75, "DISTANCE": .2, "WARMTH": .6}),
        ("A03", "Hat Tight", "HAT_CLOSED", {"DAMP": .65, "TUNE": .55, "DISTANCE": .2, "WARMTH": .6}),
        ("A04", "Hat Open", "HAT_OPEN", {"DAMP": .35, "TUNE": .55, "DISTANCE": .25, "WARMTH": .6}),
        ("A05", "Sizzle Crash", "SIZZLE", {"TUNE": .6, "DAMP": .55, "DISTANCE": .3, "WARMTH": .6}),
        ("A06", "Tambourine", "TAMBOURINE", {"DISTANCE": .3, "WARMTH": .6}),
        ("A07", "Hat Foot", "HAT_FOOT", {"TUNE": .55, "DISTANCE": .25, "WARMTH": .6}),
        ("A08", "Claps", "CLAPS", {"CROWD": .6, "DISTANCE": .5, "WARMTH": .6}),
        ("A09", "Conga High", "CONGA", {"TUNE": .65, "SLAP": .2, "DISTANCE": .3, "WARMTH": .6}),
        ("A10", "Conga Low", "CONGA", {"TUNE": .3, "SLAP": .1, "DISTANCE": .3, "WARMTH": .6}),
        ("A11", "Rimshot", "RIMSHOT", {"TUNE": .4, "BUZZ": .65, "DAMP": .45, "DISTANCE": .2, "WARMTH": .6}),
        ("A12", "Cross Stick", "CROSS_STICK", {"TUNE": .45, "DISTANCE": .2, "WARMTH": .6}),
        ("A13", "Conga Slap", "CONGA", {"TUNE": .55, "SLAP": .9, "DISTANCE": .3, "WARMTH": .6}),
        ("A14", "Floor Tom", "TOM_FLOOR", {"TUNE": .35, "DAMP": .45, "DISTANCE": .25, "WARMTH": .6}),
        ("A15", "Snare Roll", "PRESS_ROLL", {"TUNE": .35, "SPEED": .7, "BUZZ": .75, "DISTANCE": .25, "WARMTH": .6}),
        ("A16", "The Room Claps", "CLAPS", {"CROWD": 1.0, "TUNE": .45, "DISTANCE": .85, "WARMTH": .6}),
    ],
    "feel": {"A02": 10, "A06": 4, "A08": 14},
    "groove": (
        [("A03", s + b, v) for b in (0, 12) for s, v in ((0, .75), (2, .45), (3, .75), (5, .45), (6, .75), (8, .45), (9, .75), (11, .45))]
        + [("A01", s, v) for s, v in ((0, .9), (8, .65), (12, .9), (17, .6), (18, .8))]
        + [("A02", s, .95) for s in (3, 9, 15, 21)]
        + [("A02", 20, .3)]
        + [("A06", s, .75) for s in (3, 9, 15, 21)]
        + [("A08", s, .8) for s in (15, 21)]
        + [("A10", 11, .7), ("A09", 10, .6), ("A10", 23, .75), ("A09", 22, .65), ("A13", 16, .8)]
    ),
}

FREE = {
    "id": "free", "name": "EMBER FREE", "bpm": 80.0,
    "character": "The late set, after the paying crowd has gone: mallets on toms tuned wide, a bowed cymbal, snares "
                 "off, an elbow in the floor tom, sticks clicked together, loose rattling hats, one person snapping. "
                 "Open, loose and further back in the room.",
    "pads": [
        ("A01", "Open Kick", "KICK", {"TUNE": .7, "DAMP": 0.0, "BUZZ": .8, "DISTANCE": .5, "WARMTH": .25}),
        ("A02", "Snares Off", "SNARE", {"BUZZ": 0.0, "TUNE": .6, "DAMP": .1, "DISTANCE": .45, "WARMTH": .25}),
        ("A03", "Mallet Tom High", "MALLET_TOM", {"TUNE": .8, "DAMP": .1, "BUZZ": .3, "DISTANCE": .5, "WARMTH": .25}),
        ("A04", "Mallet Tom Low", "MALLET_TOM", {"TUNE": .2, "DAMP": .1, "BUZZ": .3, "DISTANCE": .5, "WARMTH": .25}),
        ("A05", "Bowed Cymbal", "BOWED_CYMBAL", {"DISTANCE": .5, "WARMTH": .25}),
        ("A06", "Bowed, Biting", "BOWED_CYMBAL", {"TUNE": .3, "PRESSURE": .9, "DISTANCE": .5, "WARMTH": .25}),
        ("A07", "Elbow Tom", "ELBOW_TOM", {"BEND": .9, "TUNE": .45, "DISTANCE": .5, "WARMTH": .25}),
        ("A08", "Hard Rimshot", "RIMSHOT", {"TUNE": .65, "DAMP": .1, "BUZZ": .9, "DISTANCE": .45, "WARMTH": .25}),
        ("A09", "Sticks", "STICKS", {"TUNE": .65, "DAMP": .1, "DISTANCE": .5, "WARMTH": .25}),
        ("A10", "Bell, Open", "RIDE_BELL", {"TUNE": .7, "DAMP": .1, "DISTANCE": .5, "WARMTH": .25}),
        ("A11", "Fast Sweep", "BRUSH_SWEEP", {"SPEED": 1.0, "BUZZ": .9, "DISTANCE": .45, "WARMTH": .25}),
        ("A12", "Loose Hats", "HAT_OPEN", {"BUZZ": .6, "DAMP": .1, "TUNE": .4, "DISTANCE": .5, "WARMTH": .25}),
        ("A13", "Hat Foot", "HAT_FOOT", {"DAMP": .1, "BUZZ": .4, "TUNE": .4, "DISTANCE": .5, "WARMTH": .25}),
        ("A14", "One Snapping", "SNAPS", {"CROWD": 0.0, "TUNE": .6, "DISTANCE": .35, "WARMTH": .25}),
        ("A15", "Swell", "CYMBAL_SWELL", {"SPEED": .8, "TUNE": .3, "DISTANCE": .55, "WARMTH": .25}),
        ("A16", "Floor Tom, Snares On", "TOM_FLOOR", {"TUNE": .2, "DAMP": 0.0, "BUZZ": 1.0, "DISTANCE": .5, "WARMTH": .25}),
    ],
    "feel": {},
    # Not a pattern: a phrase. Fractional steps are allowed.
    "groove": [("A05", 0, .7), ("A04", 1.6, .8), ("A03", 2.4, .6), ("A03", 2.9, .5), ("A07", 5, .9), ("A09", 7.2, .6),
               ("A09", 7.5, .55), ("A02", 9, .7), ("A02", 9.4, .4), ("A08", 10.7, .95), ("A12", 12, .7), ("A01", 12, .9),
               ("A14", 14, .6), ("A14", 17, .6), ("A04", 15.5, .7), ("A10", 18, .8), ("A11", 19, .7), ("A03", 21.3, .9),
               ("A04", 21.8, .95), ("A16", 22.5, 1.0)],
}

LATIN = {
    "id": "latin-jazz", "name": "EMBER LATIN JAZZ", "bpm": 118.0,
    "character": "Cha-cha-cha in the small club, 1962: the jazz drummer plays Latin on the kit (the clave on the snare "
                 "rim, the hat foot on 2 and 4, a light kick) while one percussionist adds the guiro, a cha-cha bell, "
                 "timbales, bongos and a conga. Close and bright through the club's warm chain. Made to differ from "
                 "CALABASH's Afro-Cuban jazz (a 176 BPM mambo, the big band's Latin section in a big dark studio): "
                 "another tempo, another dance, a drummer's kit at the centre and the guiro leading.",
    "pads": [
        ("A01", "Kick", "KICK", {"TUNE": .55, "DAMP": .45, "BUZZ": .25}),
        ("A02", "Rim Clave", "CROSS_STICK", {"TUNE": .55, "DAMP": .4}),
        ("A03", "Hat Foot", "HAT_FOOT", {"TUNE": .55}),
        ("A04", "Hat Open", "HAT_OPEN", {"TUNE": .55, "DAMP": .45}),
        ("A05", "Cha-cha Bell", "COWBELL", {"TUNE": .7, "DAMP": .5}),
        ("A06", "Guiro Long", "GUIRO", {"SPEED": .25}),
        ("A07", "Guiro Short", "GUIRO", {"SPEED": .95}),
        ("A08", "Timbale Macho", "TIMBALE", {"TUNE": .7}),
        ("A09", "Timbale Hembra", "TIMBALE", {"TUNE": .3}),
        ("A10", "Cascara", "PAILA", {}),
        ("A11", "Bongo Macho", "BONGO_HIGH", {}),
        ("A12", "Bongo Hembra", "BONGO_LOW", {}),
        ("A13", "Conga", "CONGA", {"TUNE": .5, "SLAP": .15}),
        ("A14", "Shaker", "SHAKER", {}),
        ("A15", "Clave", "CLAVE", {}),
        ("A16", "Cymbal Swell", "CYMBAL_SWELL", {"SPEED": .6, "TUNE": .55}),
    ],
    "feel": {"A06": -4, "A13": 6, "A02": 3},
    # Straight eighths on the triplet grid: eighth e is step 1.5 e. Two bars; the cha-cha-cha is "4 and 1".
    "groove": (
        [("A05", 1.5 * e, .75 if e % 4 == 0 else .55) for e in range(0, 16, 2)]                    # bell on the quarters
        + [("A06", 1.5 * e, .7) for e in (0, 4, 8, 12)] + [("A07", 1.5 * e, .5) for e in (2, 6, 7, 10, 14, 15)]  # guiro
        + [("A02", 1.5 * e, .7) for e in (2, 4, 8, 11, 14)]                                         # rim clave, 2-3
        + [("A03", 1.5 * e, .5) for e in (2, 6, 10, 14)]                                            # hat foot, 2 and 4
        + [("A01", 1.5 * e, v) for e, v in ((0, .55), (4, .4), (8, .55), (12, .4))]                  # a light kick
        + [("A11", 1.5 * (b + e), v) for b in (0, 8) for e, v in ((0, .6), (1, .35), (2, .45), (3, .35), (4, .6), (5, .35))]
        + [("A12", 1.5 * (b + e), .6) for b in (0, 8) for e in (6, 7)]                              # bongos
        + [("A13", 1.5 * e, .75) for e in (6, 7, 14, 15)]                                            # conga on the cha-cha-cha
        + [("A08", 1.5 * 13, .7), ("A09", 1.5 * 14, .75), ("A09", 1.5 * 15, .8)]                    # timbales: cha-cha-cha
    ),
}

GENRE = [AFTER_HOURS, SOUL, FREE, LATIN]
ALL = [CLUB] + GENRE

# Hats choke like hats: the foot (and on EMBER the stick on the closed hat) closes the open hat.
CHOKES = {"ember": {"A03": ["A04"], "A11": ["A04"]}, "after-hours": {"A03": ["A13"]},
          "soul-jazz": {"A03": ["A04"], "A07": ["A04"]}, "free": {"A13": ["A12"]},
          "latin-jazz": {"A03": ["A04"], "A06": ["A07"], "A07": ["A06"]}}   # one hi-hat; one guiro (a new stroke stops the last)

# Groove balance by drum class (the sampler's pad levels in the audition grooves only).
MIX = {"KICK": 1.0, "SNARE": .8, "CLAP": .5, "HAT_CLOSED": .4, "HAT_OPEN": .4, "TOM": .7, "PERC": .45}
MIX_VOICE = {"RIDE": .42, "RIDE_BELL": .35, "BRUSH_RIDE": .4, "BRUSH_SWEEP": .39,   # the owner, 2026-11-14: "Bring down the sweep a bit in the groove" (-3 dB)
             "TAMBOURINE": .3, "CONGA": .6,
             "SNAPS": .35, "CLAPS": .45, "STICKS": .4, "BOWED_CYMBAL": .5, "SIZZLE": .38, "CYMBAL_SWELL": .5, "PRESS_ROLL": .6,
             "BRUSH_ROLL": .6, "CROSS_STICK": .5,
             "TIMBALE": .55, "PAILA": .4, "COWBELL": .45, "CLAVE": .45, "BONGO_HIGH": .5, "BONGO_LOW": .55,
             "GUIRO": .35, "SHAKER": .3}


def mix_for(voice):
    return MIX_VOICE.get(voice, MIX[cls(voice)])


for k in ALL:
    assert [p[0] for p in k["pads"]] == [f"A{i:02d}" for i in range(1, 17)], k["id"]
    for _, _, voice, macros in k["pads"]:
        assert voice in e.VOICES and set(macros) <= set(e.macros_for(voice)), (k["id"], voice, macros)
