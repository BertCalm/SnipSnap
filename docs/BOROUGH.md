# BOROUGH: fitted boom-bap kits and perc stacks (round 0)

Status: CONCEIVE plus a Python sound proof. Not heard, not integrated, not on the shelf.
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

## Signal flow

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

## Results (Python harness, not Kotlin)

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
| Drums: multiple distinct genre kits on the shelf | not yet: six kits rendered in Python, not heard, not on the shelf |
| Percussion: authored roll/riser where appropriate | partial: SNARE ROLL per kit and TAMB ROLL, finite, not heard |
| Velocity levels through render, save, playback, export | render only (Python): three timbral zones; save, playback and export not started |
| Round robin through render, save, playback, export | render only (Python): three takes; save, playback and export not started |
| Melodic, keygroups, chords, HOLD, STRUM | not applicable: finite drum and percussion one-shots |

## Next round

1. Owner listens. Kick and snare per kit first; then the derived pads; then the stacks.
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
- **Heard:** nothing. This sandbox cannot play audio.

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
- **The risky decision** is fitting each role against a mix that has other drums in it. The
  backbeat prior and averaging over every occurrence reduce the bleed, but the residuals stay at
  4 to 11 dB, and only your ears can say whether that is close enough.
