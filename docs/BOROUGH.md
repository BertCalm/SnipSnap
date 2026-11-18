# BOROUGH: fitted boom-bap kits and perc stacks (round 1)

Status: CONCEIVE plus a Python sound proof. Round 0 heard by the owner ("Sounds quite drum
machine like"); round 1 rebuilds every voice on acoustic models and is waiting for a listen.
Not integrated, not on the shelf.
Repository read: `afb0fa98` (2026-11-18).

The characteristic gesture is **heavy swept kick → papery snare crack with late wires → dark, driven recording chain → a perc stack riding on top**.

## What the owner asked for

Twenty reference loops (80 to 95 BPM, NYC boom-bap: thirteen full kits, three "tops"
loops, three perc stacks, one texture loop) turned into a drum kit collection whose kits
can be mixed and matched with percussion. Replicated, not sampled.

"Replicated" is held to this rule here: every sound starts from oscillators and freshly seeded
noise. The reference loops are reduced to numbers (`extract.py`): hit maps on the 16th grid,
microtiming, and a third-octave band-envelope descriptor per drum role averaged over every clean
occurrence. The fitter (`fit.py`) only compares those numbers. No reference PCM is stored,
mixed, resynthesised with its phases, or put on the listening page. The reference files are not
in the repository; their SHA-256 hashes are in `targets.json` so a later round can prove which
files were measured.

## Round 1: from drum machine to drums in a room

**What the owner heard.** Round 0: "Sounds quite drum machine like". That is an accurate
description of what round 0 built. Its kick was a falling sine with a click, its snare two sines
plus white noise, its hats six square waves through filters: the classic analog drum-machine
recipes. Fitting moved those recipes' numbers toward the loops' band envelopes, but no setting of
a swept sine is a felt beater hitting a calf head in a room. The grid-perfect demo grooves added
to the impression.

**What changed.** `prototype/borough/acoustic.py` replaces every voice with the repository's
acoustic drum models (EMBER, `prototype/ember/ember_proto.py`, owner-approved) and adds the
record that the loops were sampled from:

```
kick : felt beater + air push -> membrane (Bessel modes, high modes die first, pitch sag on hard
       hits, second head) -> the kit hums along (snare wires, a floor tom answering) + beater click
snare: hickory stick -> membrane -> threshold wires driven by the head's own motion (a soft stroke
       is clean, a hard one sizzles) + the stick heard directly
hats, rim, toms, roll, perc stacks: EMBER's voices (cymbals and Latin voices fitted to CC0
       recordings), tuned per kit or per measured perc peak
  -> EMBER room by DISTANCE -> 44.1 kHz
  -> the record, per hit: compressor (lifts room and tail) -> drive (scaled by strike)
     -> high-shelf tilt -> 2-pole low-pass -> trim / release -> zone level 0.89 * v^0.8
```

Fitted per role (kick, snare, hat) against the same round 0 descriptors: the head's pitch,
decay and loss law, strike radius, beater or stick contact width, wire looseness and level, and
the record (room distance, squash, drive, tilt, low-pass). Derived pads follow their kit: RIM is
tuned from the snare head, TOM LO and TOM HI sit at 2x and 3.2x the kick head, GHOST is the
fitted snare at 0.4 strength with the wires relatively louder, SNARE ROLL is EMBER's press roll.
Perc stacks are EMBER voices tuned to the measured stack peaks (shaker 5.6 kHz, bongos 400 and
750 Hz, clave 950 Hz, tambourine 5.3 kHz, conga 88 and 395 Hz, ride bells 3.45 and 2.52 kHz).

The demo grooves are now played, not sequenced: each hit drifts with sd 3 ms (kick), 4 ms (snare,
ghost, rim) or 5 ms (hats), the snare sits 6 ms late, strength varies with sd 0.06 and picks the
nearest velocity zone, and quarter-note hats are accented. Seeded, so every build is identical.

**Results (Python harness).** Refit: 36 fits at 28 generations. Mean residual against the
reference descriptors, round 0 to round 1: kick 6.2 to 7.4 dB, snare 8.3 to 8.8 dB, hat 7.1 to
7.7 dB. The acoustic models score slightly worse on this descriptor, and that is expected: the
descriptor is a band envelope, which a free recipe can match by shape without sounding like a
drum, while a membrane can only make the spectra a membrane makes. The number cannot measure
"drum machine"; the listen decides.

| Loop | kick r0 → r1 | snare r0 → r1 | hat r0 → r1 |
|---|---|---|---|
| drum_tops_surf | 7.8 → 9.5 | 10.1 → 9.1 | 10.9 → 13.7 |
| drums_baxter | 7.7 → 8.3 | 10.1 → 10.6 | 6.8 → 7.3 |
| drums_full_decatur | 4.5 → 4.9 | 9.8 → 9.9 | – |
| drums_full_ditmas | 5.3 → 5.7 | 7.8 → 7.4 | 5.6 → 5.0 |
| drums_full_driggs | 7.5 → 8.1 | 5.7 → 7.4 | 7.6 → 7.9 |
| drums_full_halsey | 8.1 → 8.0 | 7.5 → 7.7 | 6.2 → 6.1 |
| drums_full_hudson | 5.8 → 7.2 | 7.4 → 8.3 | 5.3 → 5.3 |
| drums_full_kingston | 5.8 → 7.4 | 8.5 → 8.4 | – |
| drums_howard | 5.7 → 7.1 | 9.7 → 8.9 | 11.3 → 13.1 |
| drums_keap | 5.8 → 7.4 | 10.3 → 11.1 | 7.1 → 6.6 |
| drums_lafayette | 4.5 → 6.5 | 6.6 → 8.2 | – |
| drums_mercer_cranked | 6.6 → 7.9 | 6.6 → 8.2 | 6.4 → 6.3 |
| drums_ride_moffat_rims | 6.1 → 7.5 | 7.5 → 9.2 | 3.3 → 5.7 |

Verification (`verify.py` on the round 1 build): 792 WAVs, 0 failures; worst peak 0.890,
worst DC 0.0006, worst final-10 ms RMS 0.0008. Velocity is timbral on 88 of 88 pads
(gain-matched soft-to-hard residual above -20 dB; the closest, the DRY ROOM ghost, is -11.1 dB
with its spectral centroid moving from 324 Hz soft to 1118 Hz hard). Takes are distinct strikes:
the most alike pair of takes still differs by -19.6 dB after gain matching. The first build
failed 17 open- and pedal-hat files on the silent-ending check: the trim judged the cut point
with a half-empty averaging window, read the ringing cymbal as quiet, and gave it a 10 ms fade.
`end()` now judges the last full 10 ms against the sample peak and fades a still-ringing cut
over up to 250 ms.

Spectrograms of four grooves against their reference loops (a private visual check, not
published): the round 1 kicks show several low bands where round 0 showed one sine line; the
room and compression fill the gaps that round 0 left silent; the tops arrive in short bright
bursts like the loops'. Two suspects for the listen: the open hat at the end of the bar is
brighter and longer than anything in the loops, and DRY ROOM carries more room between hits
than keap, which is gated.

Listening page, version 2 (same link), with a round 0 before player on each kit sheet.

Round 1 build (from `prototype/borough/`):

```sh
BOROUGH_MODEL=acoustic BOROUGH_ITER=28 python3 fit.py targets.json fitted_r1.json [loop ...]  # ~80 min on 4 cores
BOROUGH_MODEL=acoustic python3 build.py fitted_r1.json OUT     # ~4 min
python3 verify.py OUT targets.json fitted_r1.json
python3 build_page.py OUT [ROUND0_OUT]                         # optional before/after players
```

## What the loops measured (Python harness)

Measured by `prototype/borough/extract.py` at mono 44.1 kHz. Whole-loop statistics include every
instrument; they are design evidence, not isolated drum parameters.

- **Darkness.** On the full-kit loops 99% of the energy sits below 1.2 to 4.7 kHz; the tops and
  perc loops reach 7.5 to 18 kHz. Two loops (80 baxter, 90 lafayette) are exactly mono; the
  full kits sit 22 to 56 dB below mid in the side channel; perc stacks are wide (side 4 to 18 dB
  below mid).
- **Patterns.** Every full kit puts the snare on 16ths 4 and 12. Kick placements vary per loop
  (`targets.json` `pattern`).
- **Swing.** Median delay of the 16th offbeat against the straight grid: 20 to 45 ms on eight
  loops at 90 BPM (a 16th is 167 ms, so 56 to 64% swing), near zero on mercer, lafayette, baxter.
- **Perc stacks.** Hudson: a shaker on every 8th (peaks 5.3 to 5.8 kHz) with 400 Hz and 750 Hz
  hand-drum tones and a 950 Hz block. Simpson: tambourine 16ths (jingle peaks 2.6, 5.3, 7.0 kHz),
  an accent with 920 to 1590 Hz peaks on beat 2, an 88/117 Hz thud. Rockaway: a bell pair at
  2.52 and 3.45 kHz with partials at 7.1 and 10.3 kHz, bright sleigh-type accents on 4/8/12, a
  395 to 908 Hz multi-mode knock.

## Landing and nearest siblings

A **kit and stack collection**, delivered later as frozen synthesis like SOUL ROCK, not a new
engine. The shelf is already deep in boom bap (KNEE BREAK, CYPHER BACKROOM and DUST, CLUTTER
BOOMBAP, TIMBER BOOM BAP, CUTTINGS POCKET, HICCUP HOP, the BOOM_BAP SKIN study). What none of
them do, and what earns this collection a place:

1. **Fitted to a measured reference set.** Each kit's kick, snare and hat parameters are an
   optimiser's answer to a named reference's numbers, with the residual reported. The siblings
   are authored by ear.
2. **Mix and match as a contract.** A kit fills A01 to A12; a stack fills A13 to A16. Any of six
   kits plus any of four stacks is a complete bank (24 banks) without moving a drum. `KitMerge`
   already lands a second kit on bank B (CLI `merge`); a stack-onto-A13 combine and its phone
   picker are new work for the integration round.

## Signal flow (round 0, superseded by round 1 above)

```
macro-free recipe (fitted numbers) + zone velocity + take seed
  kick : swept sine body (two-stage decay) + shell mode + beater click + skin noise
  snare: two head modes with strike bend + wire noise (late, own band and decay) + stick click
  hat  : six square partials + noise -> band-pass -> high-pass -> one decay
  perc : shaker grains | jingle resonators | hand-drum membrane | bell modes | block | clap | ride
    -> recording chain per hit: tanh drive (scaled by strike) -> high-shelf tilt -> 2-pole low-pass
    -> 88.2 kHz render -> polyphase 2:1 -> DC high-pass -> trim at -60 dB / release -> mono 44.1 kHz
    -> zone level 0.89 * v^0.8 (the SOUL ROCK law)
```

The Python harness renders at 2x for fitting speed; Kotlin's house stage is 4x. A frozen bank
would be re-rendered at 4x and compared before freezing.

## Velocity and round robin

Three zones (0.35, 0.65, 1.0) and three takes per pad. Velocity changes the strike itself before
the level law: the kick's pitch sweep narrows and its click darkens; the snare follows the
accepted BOOM_BAP wire law (wire level `sqrt(v)`, wire band top `14 kHz * v^0.8`, wire decay
`0.55 + 0.45 sqrt(v)`); hats shorten and darken; drive scales with the strike. Takes re-seed the
noise and move the physics a little (pitch sd 0.4%, decays sd 4%, click and wire level sd 10%).
Shape constants, not measured mechanics.

## The collection (round 0 layout, proposed)

| Kit | Tempo | Fitted to (A01-A03, then A05-A06) | Role beside the others |
|---|---:|---|---|
| STOOP DUST | 90 | driggs, kingston | the middle: round 40 Hz kick, papery 180 Hz snare, swung hats |
| CELLAR BOOM | 90 | hudson, decatur | sub kicks (42-43 Hz) with longer bodies, tight bright snare |
| CRANKED | 90 | mercer cranked, lafayette | the highest, longest kick (62 Hz, little sweep) against a 39 Hz partner |
| WAX | 80 | baxter, halsey | slow and darkest: snare low-passed at 3.2 kHz, heavy drive |
| DRY ROOM | 90 | keap, howard | gated: a 47 ms kick body, silence between hits |
| RIDE & RIMS | 90 | ride moffat rims, ditmas | short rim-led snare, a ride-like cymbal in the hat slot, the most swing |

Drum pads A01-A12: KICK, SNARE, HAT, OPEN HAT, KICK 2, SNARE 2, RIM, PEDAL HAT, GHOST, TOM LO,
TOM HI, SNARE ROLL. Fitted: A01, A02, A03, A05, A06. Derived from a fitted parent (proposed,
not fitted): the rest. Kits whose primary loop had no clean hat (none here) take the secondary
loop's, then the surf tops loop's.

Perc pads A13-A16, authored from the measured peaks, not optimiser fits:
HANDS (shaker, bongo lo, bongo hi slap, block), TAMBOURINE (shake, hit, thud, a finite
two-beat roll), BELLS (bell hi, bell lo, sleigh, log), TOPS (clap, ride, ride bell, tap).

Unused this round: berry (85 BPM, the darkest kick low-pass, 1.4 kHz), the goner texture loop,
and the wilkins tops loop (too sparse to fit). Each is a candidate for a seventh kit or a dust bed.

## Results, round 0 (Python harness, not Kotlin)

Fit: `fit.py`, differential evolution over 10 to 15 named parameters per role, scored as the RMS
dB difference of the third-octave band envelope (29 bands, up to 21 times, -50 dB floor) over the
window before the next hit. 44 fits, residual 3.3 to 11.3 dB. Re-scored from the shipped
hardest-zone first take, against the same score for the unfitted mid-range guess:

| Kit | Pad | Role | Fitted to | Unfitted guess | Fitted |
|---|---|---|---|---:|---:|
| STOOP DUST | A01 | kick | drums full driggs | 11.13 dB | 7.75 dB |
| STOOP DUST | A02 | snare | drums full driggs | 14.12 dB | 5.95 dB |
| STOOP DUST | A03 | hat | drums full driggs | 11.21 dB | 7.98 dB |
| STOOP DUST | A05 | kick | drums full kingston | 7.93 dB | 6.27 dB |
| STOOP DUST | A06 | snare | drums full kingston | 14.97 dB | 8.36 dB |
| CELLAR BOOM | A01 | kick | drums full hudson | 9.63 dB | 6.30 dB |
| CELLAR BOOM | A02 | snare | drums full hudson | 15.82 dB | 7.31 dB |
| CELLAR BOOM | A03 | hat | drums full hudson | 8.51 dB | 5.84 dB |
| CELLAR BOOM | A05 | kick | drums full decatur | 8.52 dB | 4.79 dB |
| CELLAR BOOM | A06 | snare | drums full decatur | 17.31 dB | 10.02 dB |
| CRANKED | A01 | kick | drums mercer cranked | 8.49 dB | 7.00 dB |
| CRANKED | A02 | snare | drums mercer cranked | 17.56 dB | 6.57 dB |
| CRANKED | A03 | hat | drums mercer cranked | 11.24 dB | 8.71 dB |
| CRANKED | A05 | kick | drums lafayette | 7.34 dB | 4.74 dB |
| CRANKED | A06 | snare | drums lafayette | 16.95 dB | 6.69 dB |
| WAX | A01 | kick | drums baxter | 11.29 dB | 7.97 dB |
| WAX | A02 | snare | drums baxter | 17.94 dB | 10.14 dB |
| WAX | A03 | hat | drums baxter | 10.14 dB | 7.04 dB |
| WAX | A05 | kick | drums full halsey | 10.72 dB | 8.78 dB |
| WAX | A06 | snare | drums full halsey | 14.42 dB | 7.43 dB |
| DRY ROOM | A01 | kick | drums keap | 11.43 dB | 6.03 dB |
| DRY ROOM | A02 | snare | drums keap | 19.47 dB | 10.52 dB |
| DRY ROOM | A03 | hat | drums keap | 10.59 dB | 8.69 dB |
| DRY ROOM | A05 | kick | drums howard | 7.54 dB | 5.88 dB |
| DRY ROOM | A06 | snare | drums howard | 13.14 dB | 9.96 dB |
| RIDE & RIMS | A01 | kick | drums ride moffat rims | 8.66 dB | 6.32 dB |
| RIDE & RIMS | A02 | snare | drums ride moffat rims | 16.00 dB | 7.71 dB |
| RIDE & RIMS | A03 | hat | drums ride moffat rims | 13.04 dB | 3.96 dB |
| RIDE & RIMS | A05 | kick | drums full ditmas | 8.31 dB | 5.96 dB |
| RIDE & RIMS | A06 | snare | drums full ditmas | 14.40 dB | 7.91 dB |

Median: kick 8.6 to 6.2 dB, snare 15.9 to 7.8 dB, hat 10.9 to 7.5 dB. The descriptor includes
bleed from whatever else plays in the loop, so zero is not reachable and a number is not a
listening verdict.

Checks (`verify.py`, 792 WAVs: 6 kits x 12 pads + 4 stacks x 4 pads, 3 zones x 3 takes):
peak at most 0.890, |DC| at most 0.00063, final 10 ms RMS at most 0.0022, all finite. Velocity is
timbral on 88 of 88 pads (gain-matched soft-against-hard residual above -20 dB; the closest are
the tamb THUD at -17.1 dB, the tops TAP at -16.2 dB and CRANKED's GHOST at -14.2 dB). Takes in a
zone differ by -21.3 dB gain-matched residual at the closest pair. The whole collection renders in
18 s on this container (4 cores); not a phone measurement.

Fixed during the round: one-shots first ended on a step (442 files failed the tail check), now
trimmed at -60 dB or given a raised-cosine release of at most 300 ms; the wood block barely
changed with velocity (-30.6 dB), now its upper mode and knock follow the strike; fitted kicks
rang past a second because the fit never sees beyond the next hit and drive lifts the tail, now
0.75 s with the release.

Listening page (private until shared): https://claude.ai/artifact/6y32yHPUszWK58Bgezbhg6

## Gates (owner's acceptance gates)

| Gate | Status |
|---|---|
| Drums: multiple distinct genre kits on the shelf | not yet: six kits rendered in Python; round 0 heard and rejected as drum-machine-like, round 1 not heard; not on the shelf |
| Percussion: authored roll/riser where appropriate | partial: SNARE ROLL per kit and TAMB ROLL, finite, not heard |
| Velocity levels through render, save, playback, export | render only (Python): three timbral zones; save, playback and export not started |
| Round robin through render, save, playback, export | render only (Python): three takes; save, playback and export not started |
| Melodic, keygroups, chords, HOLD, STRUM | not applicable: finite drum and percussion one-shots |

## Next round

1. Owner listens to round 1 against round 0 (each kit sheet has a before player). Kick and
   snare per kit first; then the derived pads; then the stacks.
2. Fit the stacks properly (shaker, tambourine, bells, clap) with role-specific descriptors.
3. Optional dust bed and a seventh kit from berry, if the listen asks for them.
4. Re-render at 4x, freeze accepted audio as a versioned resource bank (the SOUL ROCK path),
   register the kits, add a kit-plus-stack combine (A13-A16) with a phone picker, and prove
   velocity and round robin through save, playback and MPC 3 export.

## Ledger

- **Verified:** `KitMerge` lands a kit on bank B (`kit/src/main/kotlin/com/snipsnap/kit/KitMerge.kt`),
  exposed in the CLI (`cli/.../MergeCommand.kt`) and not in the app; no repo file uses the name BOROUGH; branch
  `ccr-eda5e44d-r9ge5f` from `afb0fa98`.
- **Measured (Python harness):** everything in "What the loops measured" and "Results".
- **Proposed:** the kit groupings, the A01-A12 / A13-A16 contract, every derived pad, the perc
  parameters, the velocity laws, the take jitter, the kick length.
- **Heard:** round 0 by the owner: "Sounds quite drum machine like". Round 1: nothing yet. This
  sandbox cannot play audio.

## Why it is built this way

- **Numbers in, oscillators out.** The fitter only ever compares band-envelope numbers, so the
  kits are new performances of the same recipe, not copies of the loops. That is what
  "replicated, not sampled" means here, and why each kit can still have velocity and takes that
  the loops never recorded.
- **The recording chain is part of each hit.** Most of the loops' character is drive and a low
  top end (99% of energy below 1.2 to 4.7 kHz). Fitting the low-pass and drive per voice keeps
  that colour on every pad.
- **Velocity changes the strike, not just the volume.** A soft kick has a smaller pitch drop and a
  duller click; a soft snare has fewer, duller wires. Remove that and the ghost notes sound like
  turned-down backbeats.
- **Kits and stacks share fixed pads.** Drums on A01-A12 and percussion on A13-A16 is what makes
  "mix and match" a one-tap swap that never moves the kick.
- **Round 1 trades a better score for a real drum.** A drum-machine recipe can bend into almost
  any band envelope; a membrane can only make what a membrane makes. Round 1's residuals are
  about 1 dB worse on average, and it is still the right trade: the owner's ear rejected the
  better-scoring recipes, and the physics is what makes a soft hit, a hard hit and the next hit
  differ the way a played drum does.
- **The risky decision** is fitting each role against a mix that has other drums in it. The
  backbeat prior and averaging over every occurrence reduce the bleed, but the residuals stay at
  4 to 11 dB, and only your ears can say whether that is close enough.
