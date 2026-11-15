# KINDRED

A one-voice, eight-step analog-style percussion synth, and four genre kits made from that one voice.

Status: round K1 (2026-11-15). The Kotlin engine, patch and recipes are built and tested; the kits are not on
the shelf yet (round K2). The sound direction was accepted by ear on the Python round-2 renders; no Kotlin render
has been heard.

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
| Percussion: roll or riser | Not yet built in Kotlin. Each kit's A16 riser and A15 phrase were heard in Python (round 2) |
| Drums: multiple distinct genre kits on the shelf | Not yet: four kits accepted in Python; round K2 puts them on the shelf |
| Velocity: rendering | Pass in Kotlin, not heard |
| Round robin: rendering | Pass in Kotlin, not heard |
| Velocity and round robin: saving | Partly: a patch round-trips with its velocity, take, recipe and model; no saved kit yet |
| Velocity and round robin: playback, export | Not yet verified |
| Hardware | Not verified |

## Ledger

- Verified: the APIs used (`Tide.bandLimit`, `Dsp.decimate`, `Dsp.Noise`, `Dsp.seedFor`, `Dsp.levelTo`,
  `Patches.decode`, `Restrike`) were read in the repository before use.
- Measured: every number above, by the named script or test.
- Proposed: every voice and recipe setting is a "shape" value chosen from the clips and by the owner's ear.
- Heard: rounds 0, 1 and 2 in Python, by the owner. Nothing in Kotlin.

## Next: round K2

The four kits on the shelf (`KindredKits`, `StarterKits`, `StarterShelf`), each with its A15 phrase and A16 riser
rendered by the engine's trigger list, three velocity zones of four takes per pad, and the grid proved through
saving, playback and MPC 3 export (`KindredKitTest`), then the owner's listen to the Kotlin kits.
