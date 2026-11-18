# FACSIMILE round 0: drum kits replicated from reference loops

Four kits synthesized from nine reference drum loops the owner supplied (2026-11-18). The owner
asked for the references to be **replicated, not sampled**: no reference audio is in any kit.
This round is a Python listening round, as the house does before any Kotlin. Nothing here is on
the shelf, and nothing has been heard yet.

## The idea in one paragraph

A loop is several drums overlapping. Stage 1 pulls the loop apart into drums and a timeline with
NMFD. Stage 2 merges the parts of one drum, lists every hit (time to the millisecond, loudness),
and lines the hits up. Stage 3 fits a small synth to each drum: falling-pitch partials (a kick's
or an 808's body, a zap's sweep), ringing partials (a snare's shell, a tom's ring) and 26 bands of
shaped noise, then corrects it in a closed loop until its band envelopes match the reference. The
fit writes **numbers only** (`models/*.json`); the synth (`synth.py`) renders from those numbers
and never reads audio. The grooves re-perform each reference pattern with the replica drums.

## Files

| File | Reads | Writes | What it does |
|---|---|---|---|
| `decompose.py` | reference WAVs | work folder | NMFD with two constraints: a drum fires only at a detected onset, and a template only decays after 40 ms (else NMFD learns patterns, not drums). Circular, because the loop repeats. |
| `drums.py` | work folder | `inventory.json`, aligned hits | Merges parts that co-fire, refines each hit's time on the separated audio, aligns the strong hits. |
| `glidefit.py` | (a hit) | (numbers) | Fits a falling pitch by analysis by synthesis: demodulate the hit by a candidate glide and keep the one that leaves the most energy standing still. Two shapes: falling in Hz (kick body) and falling in octaves (zap). |
| `fit.py` | work folder | `models/<loop>.json`, `fit-report.json` | The band target, the partials, the closed loop (best pass kept). |
| `synth.py` | a model | audio | The instrument. Velocity and round robin live here. |
| `groove.py` | `models/`, `kits.json` | groove WAVs | Re-performs each loop: measured timing and loudness, four takes dealt in turn, 808 choke. |
| `measure.py` | references, work folder, models, grooves | `measure-report.json` | Groove and drum distances, velocity character change, take distinctness. |
| `build_audition.py` | all of the above | audition folder | The listening page's audio and `data.json`. |
| `kits.json` | | | The four kits, hand-named, and each loop's choke group. |

## Run it

The reference loops are licensed audio and are **not** in the repository. Put them in a folder of
their own, keep the work folder outside the repository, and run with `python3 -I` (the scripts
add their own folder to the import path):

```sh
python3 -I decompose.py REFS WORK        # about 3 minutes
python3 -I drums.py REFS WORK
python3 -I fit.py WORK models            # about 11 minutes; add a loop's tempo to fit one loop
python3 -I groove.py models GROOVES
python3 -I measure.py REFS WORK models GROOVES
python3 -I build_audition.py REFS WORK models AUDITION
```

Requires NumPy, SciPy, soundfile and matplotlib; `build_audition.py` also calls ffmpeg.

## What is committed and what is not

Committed: the code, `kits.json`, and `models/`: per drum, glide pitches and times, partial
frequencies, envelope breakpoints in dB, and the reference's hit times and loudness (the groove's
notation). Not committed: the reference loops, the separated audio, the aligned hits and the
templates (all derived from reference PCM), and every rendered WAV or MP3.

## Ledger (round 0, 2026-11-18)

- **Verified:** the render path reads only `models/*.json` (`synth.py`, `groove.py` import no
  audio reader). The glide fitter recovers five known synthetic glides, including a 3 kHz
  sweep and a log-frequency zap, within 5% in start pitch, end pitch and speed, and within 4%
  in level, and picks the right shape each time (`python3 -I test_glidefit.py`).
- **Measured (Python harness, not Kotlin):** see `models/fit-report.json` and
  `models/measure-report.json`. The listening page shows each number beside its pad.
- **Proposed:** the pad roles and names in `kits.json`, read from templates and patterns, not
  heard. Velocity is a house law this round (softer strikes tilt about 2.5 dB per octave darker
  at full softness, decay up to 15% faster, and fall 30% less far in pitch); the references'
  own soft hits have not been fitted yet.
- **Heard:** nothing. Only the owner hears.

## Known limits, disclosed

- The band distance cannot tell a tone from noise with the same energy: a noise-only fit of the
  zap scored better than the tonal sweep that is actually there. The ear decides that.
- The replicas are mono, as the house renders. The references' stereo width is not replicated.
- 808 tails: the references choke each 808 note with the next, so the full tail was never heard.
  The fit continues the decay it measured before the cut; the grooves choke as a sampler does.
  The rule only fires for a low-heavy drum (over half its energy under 150 Hz) that was falling
  slower than 150 dB/s before a 25 dB drop inside 35 ms. Applied to every drum, it gave hats
  1.4 s washes and made two grooves 8 to 20 dB too loud; that bug was caught by the groove
  level check and fixed before anything was published.
- Tails under the template's floor: once a drum is 40 dB under its peak it must keep falling at
  60 dB/s or faster. NMFD leaves a flat plateau 45 to 60 dB down where no hit shows a late tail.
  For a drum that is not low-heavy, the floor can sit only 30 to 50 dB down, so a second rule
  applies: once it has fallen 20 dB, if it then falls slower than 30 dB/s over 100 ms, it keeps
  falling at 60 dB/s from there. Low-heavy drums (808s, boom toms) are left to ring as measured,
  which is why a few of them are 1.4 s long.
- Some NMFD components are artifacts (a kick's sub re-fired on its own). The grooves use every
  component, so nothing in the reference goes missing; the pads use only the drums named in
  `kits.json`.
- Two fit bugs were caught by the groove level check (grooves 113 and 134 measured 8 to 20 dB
  louder than their references). First, a grid point at the render's very end can never be
  measured, so the closed loop raised it every pass and the drum swelled into its last sample;
  such points are now frozen at silence and no band may be set more than 6 dB over the drum's
  loudest target. Second, the loop fitted one noise recording's random wobble (narrow-band
  noise wanders 4 to 6 dB at any instant), which left other takes and the 4x render 2 dB
  further off than the fit reported; the fit now averages its replica over three noise seeds,
  and `measure.py` reports the mean distance over the four takes.
- Round robin follows the reference's own hit-to-hit spread, clamped to 0.3 to 1.2 dB per band,
  plus fresh noise and phases. Whether that reads as one drum hit again is for the ear.
- Engine gates: this is a prototype. Velocity and round robin are checked in rendering only;
  saving, playback and export are not built (see `docs/ENGINE_ACCEPTANCE_GATES.md`).
