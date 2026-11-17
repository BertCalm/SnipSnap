# KINDRED

A one-voice, eight-step analog-style percussion synth, and four genre kits made from that one voice.

Status: round K2 (2026-11-16). The Kotlin engine, patch and recipes are built and tested (K1, merged in #1156),
and the four kits are on the shelf with velocity and round robin proved through saving, playback and MPC 3 export
in software (K2). The sound direction was accepted by ear on the Python round-2 renders; no Kotlin render has been
heard yet.

The characteristic gesture: **a strike → the pitch dives from a zap into a body → the ladder closes over the
harmonics → the next step re-strikes the same voice at its own height and force.**

## Research (names allowed here, never in the engine)

The owner sent eleven phone screen recordings (twelve files, two byte-identical) of five YouTube demos of the Moog
DFAM: Limbic Bits' sound demo (techno, "Ambient / Electro sequence", "Industrial sequence"), Moog's "Drummer From
Another Mother" promo, timlfk's presets ("QUICK HATS", "FM TOYS", "KICK / SNARE / FM OSC 2"), "TECHNO JAM ON DFAM
ONLY" and dalecutlemusic586's demo. The clips are the owner's and are not in the repository.

The reference's signal path, from the manual (ManualsNet's copy of the DFAM user's manual): two VCOs, each
triangle or square, sharing one decay envelope on pitch with a bipolar amount per VCO; FM from VCO 1 to VCO 2 and
hard sync; a mixer with noise; a four-pole ladder, low-pass or high-pass, with its own decay envelope and noise
modulation; a VCA with a fast (about 1 ms) or slow (about 100 ms) attack and its own decay; an eight-step
sequencer with pitch and velocity per step, velocity setting "each step's loudness and envelope strength"; a tempo
from about 0.7 Hz to 700 Hz.

Read from the clips (Python, `prototype/kindred/`; YouTube AAC captured by a phone, so approximate): five clips
repeat every 8 or 16 steps at 100 to 240 ms a step; the kick clip's fundamental falls from about 400 Hz at 3 ms to
about 100 Hz by 80 ms and settles near 70 Hz; the industrial clip's harmonics fall from above 10 kHz to about
1 kHz over 150 ms.

What is not simulated: the analog circuit's component values, the patch bay, external audio in, and stereo.

## Sibling check

THUMP (analog-style one-shot drums) and ETCH (a sixteen-step bass line through a ladder) are the nearest engines.
Neither makes a kit and its pattern from one re-struck voice, where each new step cuts the last one's tail.

## Signal flow

```
triggers ─► pitch EG ─┬─ AMT1 ─► VCO1 tri/square ─┬──────────────┐
(time, pitch,         └─ AMT2 ─► VCO2 tri/square ◄─┘ FM 1→2, sync │
 velocity)   noise ──────────────────────────────────────────────► MIX ─► 4-pole ladder (tanh stages), LP or HP ─► VCA
             filter EG × velocity, noise → cutoff ─────────────────────────────┘       VCA EG × velocity ──┘
  4x internal ─► Tide.bandLimit ─► Dsp.decimate ─► DC block ─► Dsp.levelTo (house loudness) ─► 4 ms fade
```

## Controls

| Macro | 0.5 is | What it moves | Not listed on |
|---|---|---|---|
| TUNE | the voice as written | both VCOs, an octave either way; on a voice with silent oscillators, the filter by the same ratio | |
| DIVE | as written | the pitch envelope's depth (0 to 1.8x) and time (0.6 to 2.2x) together | HAT, CLAP, COWBELL, SWEEP, PING, SHAKER (no pitch envelope) |
| CLANG | as written | FM from VCO 1 into VCO 2; hard sync from 0.8 | CLAP, SWEEP, SHAKER (silent oscillators) |
| NOISE | as written | noise level and noise → cutoff | |
| TONE | as written | cutoff (±2.5 octaves) and the filter envelope; resonance rises near the top | |
| LENGTH | as written | the VCA and filter decays together | |

Velocity sets the VCA, the filter envelope's depth, part of the pitch dive and how hard the mixer drives the
ladder. A take is a fresh strike: free-running oscillator phases, new noise, up to 4 cents of drift and 5% of the
decays, and for a clap the spacing of its three strikes.

## Voices and recipes

Fifteen voices: KICK, ZAP, SNARE, FMTOM, METAL, HAT, CLAP, RIM, COWBELL, SUB, BELL, SWEEP, LASER, PING, SHAKER.
A clap is three quick re-strikes of filtered noise, a laser the pitch envelope run upward, a steam sweep the
filter envelope run backward, a bell FM, a pluck a click ringing the ladder near self-oscillation, the breath and
swell the slow attack.

`KindredRecipes` holds the 56 one-shots of the four kits (A01 to A14 of each), generated from the prototype's kit
table by `prototype/kindred/gen_recipes_kt.py`, so every number is the one the owner heard. A `KindredPatch` names
its recipe and replays it byte for byte.

| Kit | BPM | Character |
|---|---|---|
| TECHNO | 132 | punchy kick, tight hats, rumble, ride, cowbell, sub hit; clap riser |
| INDUSTRIAL | 100 | driven squares, anvil, pipe, hammer, steam, grind; zap riser to audio rate |
| ELECTRO | 124 | long round boom, snare, clap, three toms, lasers up and down, conga; tom roll rising |
| AMBIENT | 84 | soft kick, brush, FM bells, wood, drop, glass, breath, swell; pluck riser to a tone |

## Rounds

- **Round 0 (Python, 2026-11-15):** six voices, three eight-step loops, a riser, six controls swept. The owner:
  "Decent start. I want all the genres, need to fill out the kits."
- **Round 1 (Python):** four sixteen-pad kits. The owner: "Sounds like it's coming out of a small radio speaker."
  Measured against the reference clips' median, the grooves put 5.8 dB too much energy under 60 Hz and 5.5 dB too
  little between 400 Hz and 2.5 kHz; the kicks were nearly pure low tones (the techno kick 33 dB short of the
  reference kick at 1 to 2.5 kHz).
- **Round 2 (Python), model 2:** the fix at the source, not an EQ. Kicks settle near 58 Hz; a kick's ladder stays
  open with an octave square, fitted to the reference kick's band balance (2.4 dB rms over 60 Hz to 16 kHz); the
  body sounds' low-pass cutoffs an octave higher with less resonance; the mixer drives the ladder 1.4x harder.
  Hats, bright cutoffs and resonant pings are left as they were, because opening them moved their velocity sweep
  above where the noise changes. Grooves against the references: under 60 Hz +0.2 dB (was +5.8), 400 Hz to 1 kHz
  −1.9 (was −5.5), 1 to 2.5 kHz −2.9 (was −5.2). The owner: "It's better. I think it was something with my
  headphones. Let's proceed with ring 2 sounds", then "Round" (the owner's correction: round 2).
- **Round K1 (Kotlin):** `Kindred.kt`, `KindredPatch.kt`, `KindredRecipes.kt`, `KindredPresets.kt` (provisional,
  `PatchTypes.PROTOTYPES`), the Patches, Velocity, Restrike and SynthRoot arms, `KindredTest`, a determinism
  canary over every voice and every recipe, and `generateKindredAudition`.

## Round K1, as built

Measured against the accepted Python with `prototype/kindred/compare_kotlin.py` (every recipe, full velocity,
take 0, aligned by cross-correlation, RMS matched):

- The 29 recipes without noise are the same sound: 0.01 dB of third-octave shape, waveform correlation 0.972 to
  1.000, residual −12.5 to −46.5 dB, after a constant 23-sample (0.5 ms) lag from the house resampler.
- The 27 recipes with noise draw `Dsp.Noise` in place of numpy's generator, so they are another realisation:
  0.02 to 2.54 dB of shape (AMBIENT A03 Tick the widest), inside the spread of the round-2 takes (up to 3.5 dB).
  They wait for the ear.
- Velocity: every recipe's soft (0.3) and hard hits differ by at least 1.96 dB of level-matched shape (ELECTRO
  A04), `KindredTest` floors it at 1.5. Takes: no two of four are copies, each replays byte for byte.
- Model 2's kicks put −18.4 to −20.6 dB of their energy at 1 to 2.5 kHz and under −55 dB below 50 Hz.
- Render time: 40.7 ms a pad on average, warm, on the session's machine (`generateKindredAudition` prints it);
  the slowest is AMBIENT A13 Pluck.
- Changed against the prototype, with no accepted recipe affected: DIVE is not listed where there is no pitch
  envelope, CLANG not where the oscillators are silent, and TUNE moves the filter on a voice with silent
  oscillators. The A/B is identical before and after these changes.
- Classifier: it reads every kick as PERC and some hats, the metal, the anvil and the wood as SNARE (`KindredTest`
  prints the table). `drumClassFor` files each recipe by its role, as SCUFF does; no render reads LOOP or UNKNOWN.

## Acceptance gates

| Gate | Status |
|---|---|
| Melodic voices, keygroups, chord tools | Not applicable: a drum engine |
| Articulation: HOLD, STRUM | Not applicable: finite hits. The eight-step phrase is a kit pad (A15), not HOLD |
| Percussion: roll or riser | Pass in software, not heard in Kotlin: every kit has its A16 riser and A15 phrase, rendered by the engine's trigger list |
| Drums: multiple distinct genre kits on the shelf | Pass in software: four kits on the shelf under SKINS (`kindred-techno`, `-industrial`, `-electro`, `-ambient`), accepted by ear in Python; the Kotlin kits wait for the ear |
| Velocity: rendering | Pass in Kotlin, not heard |
| Round robin: rendering | Pass in Kotlin, not heard |
| Velocity and round robin: saving | Pass in software: each kit reloads equal and every saved slice matches its render (`KindredKitTest`, `:shell:generateKindredKits`) |
| Velocity and round robin: playback, export | Pass in software: `PadHit.resolve` picks the zone by velocity and walks four takes, then repeats; the MPC 3 export reads back. MPC 2 not applicable (format limit). No phone or MPC has played them |
| Hardware | Not verified |

## Ledger

- Verified: the APIs used (`Tide.bandLimit`, `Dsp.decimate`, `Dsp.Noise`, `Dsp.seedFor`, `Dsp.levelTo`,
  `Patches.decode`, `Restrike`) were read in the repository before use.
- Measured: every number above, by the named script or test.
- Proposed: every voice and recipe setting is a "shape" value chosen from the clips and by the owner's ear.
- Heard: rounds 0, 1 and 2 in Python, by the owner. Nothing in Kotlin.

## Round K2, as built (2026-11-16)

`KindredKits` builds the four kits (`kindred-techno` 132 BPM, `kindred-industrial` 100, `kindred-electro` 124,
`kindred-ambient` 84), registered in `StarterKits`, the SKINS group of `StarterShelf`, and `StarterKitsTest`.

- **A01 to A14:** each recipe as three velocity zones (.3, .6, 1) of four takes, rendered by the engine
  (`Kindred.padRaw`); take 0 is the reference strike the owner heard. Every zone's first take peaks at 0.92 and its
  takes share that gain, so a soft zone is a different strike, not a quieter copy.
- **A15 Phrase:** two bars of the kit's eight-step pattern on one pad's voice (the prototype's phrase data, generated
  into `KindredRecipes.KITS`). Changed against the prototype: the audition rendered a seamless loop with the
  oscillators reset each pass; a one-shot pad needs no seam, so they run free across the steps, the reference's
  own behaviour.
- **A16 Riser:** the prototype's riser line for line (start and end rates, curve, glide, decays capped).
- **Levels:** each pad's mixer level is the round-2 groove's balance: the prototype levelled every pad to an RMS of
  0.1 under a 0.97 peak ceiling and scaled it by its mix, so the level is mix x min(0.1 x crest, 0.97), the loudest
  one-shot at the house default (-3 dB). A first version without the ceiling put the techno clap over its kick; the
  ceiling is what the owner heard.
- **Mute groups:** the two hats choke each other; the phrase and riser choke each other.

Measured (`KindredKitsTest`, `KindredKitTest`, `:shell:generateKindredKits`, `prototype/kindred/build_k2_page.py`):

- Every one of the 768 strikes is finite and peaks between 0.05 and 0.98.
- A soft zone differs from the hard one by at least 1.74 dB of level-matched third-octave shape (ELECTRO A04,
  TECHNO A11); the test floors it at 1.25.
- The closest two takes in any zone are 12.4 dB apart (TECHNO A12 at .6); a copy reads over 40.
- Every pair of kits differs on all fourteen one-shot slots by over 2 dB (least 2.90 dB, ELECTRO against AMBIENT).
- Every kit reloads equal, every saved slice matches its render, the pad resolver deals the zones and takes, and the
  MPC 3 track reads back: 4 kits, 64 pads, 768 slices. A kit builds in 14 to 20 s on the session's machine.
- Each kit's groove, played from the MPC 3 export's slices at the pads' levels and the touch law, is within 1 dB of
  the round-2 Python groove in every band from under 60 Hz to over 6 kHz.

Open: the owner's listen to the Kotlin kits ([K2 page](https://claude.ai/artifact/7ZrWVzzezn5uktr2fBm2Yf)), presets
into the factory roster after that listen, and phone and MPC hardware.

## Round K3: the picker (2026-11-17)

KINDRED joins SYNTH's engine picker after SERVO, before CAIRN, under DRUMS in `EngineCatalog` ("One analog voice
re-struck: kicks that dive, zaps, FM toms, metal"), wired as SERVO's entry was: every arm of the `Engine` enum, the
saved-draft restore, a frozen draft before a macro bag, the live-edit list, and the native-factory path, because a
KINDRED patch carries its strike velocity, take, kit recipe and model outside the macro map. Its presets come from
`KindredPresets` (as CHITIN's come from its own, outside `Presets`): the 56 kit pads, named in capitals after their
kit ("TECHNO KICK", "INDUSTRIAL HAMMER"), provisional until the owner hears the Kotlin kits. The FMTOM chip reads
FM TOM. `UserPresets.rosterLine` promotes a KINDRED patch through its complete JSON so the recipe survives. Both
READMEs count 96 picker engines.

`NewEngineSynthScreenTest` gains an on-device test: the kick opens with its six controls; TECHNO KICK lands with the
pad's LENGTH; a TONE edit survives a rebuild; the saved draft keeps the kit recipe, take 0, full velocity and model.
This session has no Android SDK: CI's `android-build` compiles the entry and `emulator-tests` runs the test.
`ConventionTest` (picker against catalogue, README counts), `UserPresetsTest` and `SynthRootTest` pass.
