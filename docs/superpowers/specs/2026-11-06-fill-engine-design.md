# FILL: a drum fill on one pad (design, R1)

**Status:** R1 built, not yet heard. Listening page: https://claude.ai/artifact/8mBdGaFMw3XuGPdt9e4fLe
(answers in its `verdicts` collection, ids `f1-*`). Prototype: in `PatchTypes.PROTOTYPES`, with eight
provisional recipes in `FillPresets` (each voice at each LENGTH) outside the factory roster; no kit, not on
the picker until the owner's listen.

## Why

The 2026-11-06 gap scan ranked "drum fills cut to tempo" first. Of 86 registered engines, none plays a
fill. A fill is a gesture, not a timbre: a few sparse hits, then the run subdivides and gets louder, then it
lands on the downbeat. The owner chose a new engine (over presets on SKIN), so every fill stays editable.

## Sibling check

- **SKIN** already makes the snare, toms and kick. FILL adds no drum synthesis. It schedules SKIN's hits
  and mixes them, so a fill matches the SKIN kit beside it.
- **SCOUR** set the timing precedent: its build ends exactly BUILD seconds in, so the pad is hit that long
  before the downbeat. FILL's landing falls exactly LENGTH in, to the frame.

## Contract

- `render` receives no tempo, so TEMPO is a control: 60 to 180 BPM in whole steps (`Fill.bpm`,
  inverse `Fill.tempoFor`). A kit cuts its fills for a tempo, as SCOUR's builds are cut for 120 and 90 BPM.
- LENGTH has four steps: one beat, two beats, one bar, two bars of 4/4. A saved value sits mid-step
  (`Fill.lengthFor`).
- Every render is at least 1.6 s (`MIN_RENDER_SECONDS`) and ends 1 s after the landing, so every fill
  is filed as LOOP (over the classifier's 1.5 s). It plays once and whole, and is never choked as a lone
  snare. This is the SCOUR precedent.
- Deterministic: the schedule has no randomness. SKIN's two takes alternate by the hit's index.

## The two voices

| Voice | Schedule | Landing |
|---|---|---|
| SNARE | LENGTH in three stages, 1/2, 1/4 and 1/4, with the rate doubling at each stage. DENSITY 0 over a bar plays quarters, then eighths, then sixteenths. Each DENSITY step halves every note, so DENSITY 1 ends on sixty-fourths. | kick + snare |
| TOMS | One note value throughout: eighths, sixteenths or thirty-seconds by DENSITY. It walks high, mid, low and floor tom in equal quarters of LENGTH. | kick + floor tom |

Every stage boundary is a whole number of its notes, so every hit is on the finest grid (tested).

## Controls

- **TUNE.** Toms: the floor tom runs 90 to 130 Hz (log), and each tom above it sits three semitones
  higher. These are converted to SKIN TUNE through SKIN's `expMap(TUNE, 90, 220)`, so the high tom tops
  out at 218.6 Hz. Snare: SKIN TUNE = .4 + .6 × (TUNE − .5).
- **TEMPO, LENGTH.** As above.
- **DENSITY.** 0, 1 or 2 doublings (rounded).
- **RISE.** The crescendo. The first hit is at `1 − .8 × RISE` of the last, rising as `t^1.5`. RISE 0 is
  flat.
- **LAND.** The landing's strength. 0 means no landing.
- **No HOLD.** A fill always ends.

Velocity scales every hit. Toms and kick take SKIN's strike velocity, which changes the head's sound. The
snare takes `Velocity.soften` (the house's darker soft hit) and a level. The whole fill is then normalised
to 0.95 peak, so velocity changes tone and the MPC's velocity curve owns loudness, as for the other
engines. Hits are rendered on 12 velocity steps and cached per (drum, step, take).

## Shape constants: listening hypotheses, unverified

- The crescendo law and its floor (.2).
- The 1/2–1/4–1/4 stage split.
- The minor-third tom spacing.
- The 1 s tail after the landing.
- A rigid grid with no swing or human feel. This one is asked about directly.

## Tests (`FillTest`, plus the determinism canary)

- TEMPO and LENGTH round-trip.
- Every hit is on the grid, and the landing is exactly LENGTH in. This is checked for each voice across
  4 lengths × 3 densities × 3 tempos.
- The snare's stage counts are 4/4/8 over a bar at DENSITY .5. The toms play 16 hits in high-to-floor
  order, minor thirds apart, inside SKIN's range.
- RISE is monotone, with a floor of .2.
- In the rendered audio, nothing differs before the downbeat frame, and the landing is heard within
  1 ms of it.
- Every control and velocity changes the render.
- Renders are finite, at most 1.0 peak, and filed as LOOP.
- Bad input settles to defaults.

## Audition

`./gradlew :synth:generateFillAudition` writes `testkit/fill-audition/` with 42 WAVs:

- Four phrases. A beat stops for the fill, and the fill lands where the beat comes back: one bar at
  120 BPM and two beats at 90, for each voice. The phrase kick and the fill's landing kick stack on
  that downbeat.
- Each length alone.
- TUNE, DENSITY, RISE and LAND at 0, .5 and 1.
- Velocity at .3, .65 and 1.

## After the listen

Factory presets, a kit (fills cut for 90 and 120 BPM beside a SKIN kit), and the picker entry. Then, if asked:
mixed snare-and-tom fills, flams and drags, and a human-feel control.
