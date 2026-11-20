"""BOROUGH round 0: the collection layout. Python harness, not Kotlin.

Mix-and-match contract (proposed): every DRUM KIT fills pads A01..A12; every PERC STACK fills
A13..A16. Any kit plus any stack is a complete sixteen-pad bank, so swapping a stack never moves
the drums under the player's fingers. Six kits x four stacks = 24 banks.

Drum kit sources are fitted parameter sets (fitted.json, from fit.py). Perc stacks are authored
from the peak and decay measurements of the three perc loops and the tops loops (numbers quoted
beside each value); they are not optimiser fits yet.
"""
from __future__ import annotations

# --------------------------------------------------------------------------- drum kits
# Each kit: name, groove tempo, primary loop (A01 kick, A02 snare, hats), secondary loop (A05 kick 2,
# A06 snare 2), measured 16th-offbeat delay used for the audition groove, and the reason it exists
# beside the other five. Cluster membership is decided from fitted.json (see DESIGN.md).
KITS = [
    dict(id='borough-stoop', name='STOOP DUST', bpm=90, primary='drums_full_driggs', secondary='drums_full_kingston',
         swing_ms=32, role='the reference middle: round kick, papery crack, swung hats'),
    dict(id='borough-cellar', name='CELLAR BOOM', bpm=90, primary='drums_full_hudson', secondary='drums_full_decatur',
         swing_ms=31, role='sub kick with a long tail, tight dry snare'),
    dict(id='borough-cranked', name='CRANKED', bpm=90, primary='drums_mercer_cranked', secondary='drums_lafayette',
         swing_ms=0, role='driven and squashed: high-knock kick, dark mono, hats buried'),
    dict(id='borough-wax', name='WAX', bpm=80, primary='drums_baxter', secondary='drums_full_halsey',
         swing_ms=5, role='slow and mono, the darkest top end, a fat low snare'),
    dict(id='borough-dry', name='DRY ROOM', bpm=90, primary='drums_keap', secondary='drums_howard',
         swing_ms=20, role='gated and clean: silence between hits, short snare'),
    dict(id='borough-rims', name='RIDE & RIMS', bpm=90, primary='drums_ride_moffat_rims', secondary='drums_full_ditmas',
         swing_ms=45, ride=True, role='rim-led snare and a ride instead of hats, the most swing'),
]

# How far apart the kits are pushed (build.spread): every fitted setting moved this many times further
# from the six kits' average, inside the model's bounds. 2.0 puts kick, snare and hat 13-18% further
# apart than the loops' own drums (1.0: 84-95%), at 0.4-1.8 dB of fit. Owner, round 1.4: the kits
# "all kind of sound pretty similar", and the loops are similar too; push past them.
SPREAD = 2.0

DRUM_PADS = ['KICK', 'SNARE', 'HAT', 'OPEN HAT', 'KICK 2', 'SNARE 2', 'RIM', 'PEDAL HAT',
             'GHOST', 'TOM LO', 'TOM HI', 'SNARE ROLL']

# --------------------------------------------------------------------------- perc stacks
# Values are shape constants read off the measured spectral peaks (percscan, Python harness).
STACKS = [
    dict(id='hands', name='HANDS', source='perc_stack_hudson',
         pads=[('SHAKER', 'shaker', dict(shk_hz=5600, density=9000, attack_ms=6, shk_ms=55, lp=14000)),    # peaks 5.3-5.8 kHz, 8ths
               ('BONGO LO', 'hand', dict(hd_hz=400, hd_ms=90, hd_modes=.35, lp=9000)),                    # 398-409 Hz recurring
               ('BONGO HI', 'hand_slap', dict(hd_hz=750, hd_ms=70, hd_modes=.3, lp=11000)),               # 746-753 Hz
               ('BLOCK', 'block', dict(blk_hz=950, blk_ms=28, lp=12000))]),                                # 947-958 Hz
    dict(id='tamb', name='TAMBOURINE', source='perc_stack_tamb_simpson',
         pads=[('TAMB SHAKE', 'tamb', dict(j1=2600, j2r=2.04, j3r=2.7, jingle_rate=1800, tmb_ms=70, frame_hz=220, lp=15000)),  # 2.6/5.3/7.0 kHz
               ('TAMB HIT', 'tamb_hit', dict(j1=2600, j2r=2.04, j3r=2.7, jingle_rate=2500, tmb_ms=140, frame_hz=1070, lp=15000)),  # accent 920-1590 Hz
               ('THUD', 'hand', dict(hd_hz=88, hd_ms=160, hd_modes=.5, lp=4000)),                                   # 88/117 Hz
               ('TAMB ROLL', 'tamb_roll', dict(j1=2600, j2r=2.04, j3r=2.7, jingle_rate=1800, tmb_ms=60, frame_hz=220, lp=15000))]),
    dict(id='bells', name='BELLS', source='perc_stack_bells_rockaway',
         pads=[('BELL HI', 'bell', dict(bell_hz=2520, pair=1.37, br2=2.84, br3=4.1, bell_ms=900, lp=16000)),   # 2.52/7.16/10.3 kHz
               ('BELL LO', 'bell_lo', dict(bell_hz=2520, pair=1.37, br2=2.84, br3=4.1, bell_ms=900, lp=16000)),  # 3.45 kHz partner
               ('SLEIGH', 'tamb', dict(j1=6100, j2r=1.7, j3r=2.85, jingle_rate=3000, tmb_ms=90, frame_hz=400, lp=18000)),  # 6-17 kHz accents
               ('LOG', 'hand', dict(hd_hz=395, hd_ms=45, hd_modes=.9, lp=6000))]),                                  # 395/484/571/658 cluster
    dict(id='tops', name='TOPS', source='drum_tops_claps_rogers + drum_tops_surf',
         pads=[('CLAP', 'clap', dict(hands=4, spread_ms=9, cup_hz=1250, tail=.35, tail_ms=90, lp=12000)),    # backbeat peaks 1.0-2.2 kHz
               ('RIDE', 'ride', dict(ride_hz=310, ride_ms=900, wash=.6, lp=13000, drive=.1)),                  # sustained lines across surf
               ('RIDE BELL', 'ride_bell', dict(ride_hz=310, ride_ms=900, wash=.3, lp=13000, drive=.1)),
               ('TAP', 'hand', dict(hd_hz=110, hd_ms=60, hd_modes=.6, lp=3000))]),                               # 102-118 Hz taps
]
