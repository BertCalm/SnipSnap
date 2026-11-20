# FACSIMILE

Four drum kits replicated, not sampled, from nine reference loops the owner supplied (2026-11-18). Every drum is a
numeric model fitted to a reference loop; the kits render strikes from those numbers and never read, ship or slice
the reference audio. FACSIMILE is a working name.

The characteristic gesture: **a drum is struck → its pitch falls (a kick, an 808) or holds (a tom's ring) and its
noise decays band by band as the reference's did → the groove lands each hit where the reference did, on the
groove's own average timing per place in the bar, printed through the bus compressor fitted to the reference.**

## Status

**Kotlin built and heard.** The owner listened to the Python prototype through rounds 0 to 0m
(2026-11-18 to 2026-11-20) and closed them with "Ok, sounds good now". The four kits are now on FRESH TAPE's SKINS
shelf (`FacsimileKits` version 1, `StarterKits`), rendered by a Kotlin port of the prototype's synth, with their
velocity and round-robin grid proven in software through save, reload, playback and MPC 3, SFZ and DecentSampler
export. The owner heard the Kotlin kits as the app plays them (read back from the MPC 3 export), rolls included, and
answered "Everything sounds good" (2026-11-20). Nothing has been played on a phone or an MPC.

Listening page (private to the owner's account): [FACSIMILE](https://claude.ai/artifact/LvtMyNDXUr9jjvVvw8uPMa),
every round, newest first, with the Kotlin kits at the top.

Repository truth: APIs grepped at `b5079df9` (2026-11-20): `TerraFitted.bandpass` (the band-pass construction,
generalised here to any order), `Tide.bandLimit`, `Dsp.decimate`, `Dsp.seedFor`, `KitStrikes.finish`,
`KitStrikes.peakOf`, `ArrangedPad`, `StarterKits.Starter`, `StarterShelf.GROUPS`, `TriadKitProof`, `PadHit.resolve`.

## The landing: a kit family, not an engine

As LOOSE CRATE: sixteen heard pads plus a roll on pad 17 (bank B's first), three velocity zones of four takes
per pad, fixed recipes on the shelf. No Patch,
no macros, no app picker entry: the drums are frozen fits, not knob-driven voices.

| Kit | Tempo | From the references | Pads |
|---|---|---|---|
| SHUFFLE | 90 | heavy swing (90 "alley"), swing (103) and thin percussion (102) | 14 hits, the Alley and Swing grooves |
| KNOCK | 113 | the hard knocker (113) and fusion (106), a thin-perc tambourine | 14 hits, the Knocker and Fusion grooves |
| HALF-TIME | 127 | "almost" half time (127, here Doves) and smooth mover (154), a thin-perc zap | 14 hits, the Doves and Mover grooves |
| TEXTURE | 134 | big tom beat (134) and new world (163) | 13 hits, the Tom, World and Thin Perc grooves |

The layout (names, classes, layers, grooves) is the prototype's `kits.json`, as heard, with one change: the five pads
named after a drum machine's model number became "Sub" (Doves Sub, Doves Sub Low, Mover Sub, World Sub, Tom Beat
Sub). `PresetTestSupport.trademarkBlocklist` bars that number in any name.

## How a drum is made (the prototype, `prototype/facsimile/`)

The prototype's own README records every round with its measurements. In short:

1. **Decompose** each loop by NMFD into a few parts with fixed shapes; merge parts that fire together.
2. **Inventory**: keep only the hits the loop itself shows an attack at; rebuild a kick sliced by pitch as one kick
   (`kick_first`); split a part that holds two instruments by how its hits decay (`split_by_decay`); drop low parts
   struck again on their own tails (`drop_doubles`); set every hit to its groove's average timing per place in the bar
   (`tighten`).
3. **Fit** each drum's model in closed loop against its own hits: glide partials (glidefit), steady partials, measured
   tail tones for a rebuilt kick (`tail_modes`, phase-locked), 26 third-octave noise envelopes, a tone-only low end
   for kicks, a late peak for layers that build (a snare's room).
4. **Glue**: per loop, a bus compressor fitted to the reference's crest factor, with makeup to its level.

## The Kotlin port

`Facsimile.kt` renders a strike from a model (the port of `synth.py`); `FacsimileKits.kt` lays out the kits and
performs the grooves (the port of `groove.py`). The models and layout ship under `synth/src/main/resources/facsimile/v1/`
(about 420 KB of numbers).

What changed against the prototype, on purpose:

- **The noise runs at the output rate** (the prototype filtered it at 4x). It is band-limited by its own filters;
  only the falling partials can alias, and they still render at 4x through the house band-limit and decimation. A
  strike renders in about 32 ms instead of 175.
- **A take's velocities render together** (`renderMany`): a take's noise is one stream at every velocity, so a band
  is filtered once for every velocity a groove asks of that drum, about twenty times less work.
- **Random draws** come from Kotlin's seeded generators (`Dsp.seedFor`), so the noise is not the prototype's sample
  for sample; the models, laws and filters are.

Measured parity (Python harness on Kotlin renders, `FacsimileTest`'s dump):

| Check | Kotlin vs prototype | Prototype vs itself (other noise) |
|---|---|---|
| Band-envelope distance, 8 drums x 2 velocities x 2 takes (median) | 4.30 dB | 4.32 dB |
| Level | within 1 dB on most strikes, 1.2 to 3.1 dB on four | the prototype's own seed spread is 1.9 to 2.3 dB on those drums |
| Pitch-fall partials alone (HALF-TIME Punch Kick) | identical to the dB at both velocities | |

The grooves, as the kits render them (hard zone, first take, level-matched, measured as `measure.py`):

| Groove | Band distance, Kotlin | Prototype as heard | Balance, Kotlin | Prototype |
|---|---|---|---|---|
| Alley | 7.64 | 7.69 | 1.13 | 1.18 |
| Swing | 7.06 | 6.88 | 2.21 | 2.35 |
| Knocker | 6.77 | 6.75 | 2.66 | 2.42 |
| Fusion | 7.99 | 8.22 | 2.17 | 1.90 |
| Doves | 6.59 | 6.60 | 2.16 | 2.53 |
| Mover | 7.13 | 7.66 | 2.35 | 3.16 |
| Tom | 6.37 | 6.39 | 1.07 | 1.11 |
| World | 7.64 | 7.64 | 2.14 | 1.99 |
| Thin Perc | 6.27 | 6.23 | 4.93 | 5.46 |

## Velocity and round robin

- **A zone is its own strike.** A softer strike's noise tilts darker (-2.5 dB per octave from 1 kHz at velocity 0),
  its envelopes run 15% shorter at velocity 0, and a falling pitch falls 30% less far; level follows velocity squared
  with the tilt's energy given back (house laws, not fitted to the references' own soft hits). A pad's zones are .30,
  .65 and 1 of the pad's own velocity (ghost pads strike at .45 or .5 of it).
- **A groove's soft zone** plays every note at sqrt(zone) of its velocity (.55 at the soft zone), so its hits change
  character, not only level.
- **A take is a fresh strike**: new noise, new phases for the upper partials (a kick's fundamental and a rebuilt
  kick's tail tones keep theirs), a few cents of pitch and per-band level scatter sized from the reference's own
  repeats. A groove's take rotates which strike each note deals.
- Every zone peaks at `PAD_PEAK` (0.92) from its first take, as LOOSE CRATE's.

## Gates

## The roll (pad 17)

Authored, not measured: none of the nine references holds a roll. Each kit's main snare (its fullest snare pad at
full velocity: Alley Snare, Knock Snare, Mover Snare, World Snare) is struck over two bars at the kit's tempo, the gap
between strokes tightening geometrically from an eighth to a thirty-second over the first seven beats while the strokes
swell from .35 to full, then lands on beat nine with the kit's main kick and snare together. Each stroke is a fresh
strike (the takes cycle along the roll); a zone softens every stroke as a groove's zone does, and a take rotates
them. It sits on pad 17 so that none of the sixteen heard pads is given up; it chokes with the grooves.
`FacsimileKitsTest` holds its shape (every gap tighter than the last, from .5 beat to under .15, swelling, landing with
the kick). Heard by the owner with the kits ("Everything sounds good", 2026-11-20).

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Built: four kits under SKINS, each from different reference loops (boom-bap swing, knock, half time, toms and texture). The prototype's kits were heard through round 0m ("Ok, sounds good now"), and the Kotlin kits as exported ("Everything sounds good", 2026-11-20) |
| Percussion: roll/riser | Built and heard: an accelerating snare roll landing on kick and snare, pad 17 of every kit (`FacsimileKitsTest`, `FacsimileKitTest`, the generator) |
| Velocity and round robin: rendering | Pass in software: in every hit pad the soft zone is a different strike from the hard one and, over its four takes, never brighter; every zone holds four different strikes; a groove's soft zone is a different performance (`FacsimileKitsTest`) |
| Velocity and round robin: saving, playback, export | Pass in software: `FacsimileKitTest` (a single drum, a stack, a ghost, a hat, a groove) and `:shell:generateFacsimileKits` (all four kits, 816 slices: saved PCM, two playback cycles per zone through the pad resolver, MPC 3 track, SFZ, DecentSampler). MPC 2 is not written (the owner's format limit). Not played on a phone or an MPC |

## Disclosed, not fixed

- **A single take can be brighter when softer.** Some models carry noise in the same band as a partial; the two add
  or cancel with each take's noise. The HALF-TIME Punch Kick's first soft take measured an onset centroid of 934 Hz
  against 892 Hz hard (the prototype's own first take, other noise: 687 against 998). Over a zone's four takes the
  soft zone is never brighter, which is what the test holds. The knocker kick, rebuilt tone-only under 400 Hz, does
  not have this.
- **Build time**: SHUFFLE 43 s, KNOCK 52 s, HALF-TIME 63 s, TEXTURE 161 s on this machine, the rolls included (LOOSE CRATE: 45 to 107 s).
  TEXTURE's three grooves, the World groove's 287 hits above all, cost the most. Not measured on a phone.
- **Highs**: the knocker groove's highs measured 5 to 8 dB under its reference in the prototype; the port keeps that.
- **The World groove's mids** fall more than 6 dB under the reference in about 38% of its sounding frames; the owner
  accepted the groove with it.
- **Three grooves were left untreated** (Fusion, Doves, Tom): the round-0m fixes measured worse there, and the owner
  heard no fault (`prototype/facsimile/README.md`, round 0m).

## Ledger

- **Verified**: the APIs above by grep at `b5079df9`; `FacsimileTest` (5), `FacsimileKitsTest` (7),
  `FacsimileKitTest` (3), `StarterShelfTest`, `StarterKitsTest`'s id list and `ConventionTest` pass;
  `:shell:generateFacsimileKits` verifies all four kits.
- **Measured**: the parity and groove tables (Python harness on Kotlin renders), the build times.
- **Proposed**: the velocity and groove-zone laws (house laws), the zone levels.
- **Heard**: the prototype's rounds 0 to 0m, and the Kotlin kits with their rolls as read back from the MPC 3 export,
  by the owner ("Everything sounds good", 2026-11-20). Not played: on a phone or an MPC.
