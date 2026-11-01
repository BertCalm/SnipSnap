# Tide

TIDE is the West Coast engine: it builds harmonics into a plain sine instead of
cutting them out of a rich wave. The characteristic gesture is the low-pass gate's
"bongo": level and brightness fall together, and the release slows as it closes,
so a struck note's tail hangs. Every voice runs one path:

```
sine carrier ──> phase modulation (WARP, RATIO; modulator sweeps onto its ratio)
  ──> triangle-core wavefolder (FOLD, per-voice bias, TILT, WOBBLE)
  ──> DC blocker ──> low-pass gate: SVF cutoff + VCA from one control (DECAY, GLOW)
  + BODY: a clean sine on the carrier's phase under the same VCA
  + CLICK noise after the VCA (BONGO, DRIP)
  ──> 8th-order band limit at 19.5 kHz ──> 4x decimate ──> Snip
```

Phase modulation, never frequency modulation, so the carrier is the note at every
WARP. The gate's cutoff is key-tracked from half the note (closed) to 64 times it
(open, held between 6 and 18 kHz). Several movements are built in rather than
knobs, chosen at the revision auditions recorded in the roadmap: SWEEP and CROSS
on the modulator, TILT and WOBBLE on the fold, a seven-semitone THUMP at the
strike, and BODY. The roadmap row is S9, and the S9 section of
`SYNTH_ROADMAP.md` has the design and its revisions.

## Voices

| Voice | What it is | Own knob | Bottom of TUNE |
|---|---|---|---|
| BONGO | a gently folded sine, the gate's small resonant pop | CLICK | C3 |
| DRIP | a 15 ms chirp up onto the note, closing fast | CLICK | C5 |
| GONG | phase modulation at an inharmonic ratio; DECAY's top half holds | RATIO (1.4, 2.7, 3.5, 4.2, 5.8) | C3 |
| FLARE | the most fold, blooming slower than the gate; DECAY's top half holds | RATIO (1, 2, 3, 4) | C2 |

Every voice has TUNE, FOLD, WARP, GLOW, DECAY and WANDER. A note stops at 2 s on
BONGO and DRIP and 4 s on GONG and FLARE, however far WANDER stretches it.

## Knobs

- **TUNE** snaps to whole semitones over two octaves above the voice's bottom note.
- **FOLD** is the folder's drive, up to 8.4 (`MAX_DRIVE`). At 0 the sine passes
  untouched and the built-in fold movements are silent.
- **WARP** is the phase-modulation index, squared, up to 4.2 (`MAX_INDEX`). At 0
  there is no modulation. High notes and high RATIOs get less of FOLD and WARP's
  reach so the brightest corner stays under 22 kHz (`Tide.reachAt`).
- **GLOW** holds the fold's depth (up to 60%) and the gate's filter open behind
  the level, so the tail keeps its harmonics.
- **CLICK** is a noise burst at the strike, after the VCA. 0 on every preset.
- **DECAY** sets the note's length to -60 dB: 0.12-1.5 s on BONGO, 0.06-0.8 s on
  DRIP, 0.4-4 s on GONG and 0.25-4 s on FLARE. On GONG and FLARE its top half also
  holds the gate open, up to 60% of the note.
- **WANDER** nudges fold, decay and WARP by up to 25% per note and drifts the
  fold through the note, from a seed made of the recipe and a take index. It
  never moves the pitch.
- **RATIO** (GONG, FLARE) snaps the modulator's ratio to the carrier.

## Kit, audition, presets

- **Presets:** ten per voice in `TidePresets.kt`, forty in all, authored from the
  DSP, not by ear, per that file's KDoc.
- **Starter kit:** `SynthKits.tide()`. A01-A08 walk WOOD BONGO up the minor
  pentatonic; A09-A12 are DRIP presets, A13-A14 GONG and A15-A16 FLARE. BONGO and
  DRIP pads are PERC, GONG and FLARE TONAL. Render it with
  `./gradlew :synth:generateTideKit`.
- **Audition page:** `./gradlew :synth:generateTideAudition` runs
  `TideRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/tide-audition/` with the kit, then each voice's default, every knob at
  both ends, TUNE at three notes, and every preset, at one loudness. Every clip is
  take 0, so the WANDER clips are one draw each.

## Ledger

- **Verified:** `TideTest` and `TidePresetsTest` cover the engine and the roster;
  the constants' KDocs quote the measurements behind them. `FirstGenAuditionTest`
  checks the audition spec has a note for every voice and every knob but TUNE.
- **Heard:** the GLOW, edge and character revisions were chosen at auditions
  (the S9 section records the verdicts); WOOD BONGO, LOW CONGA, RAIN DRIP, TEMPLE
  GONG, SNARL FLARE and FOLD BASS were the presets played there.
- **Not heard:** the other thirty-four presets, the kit and this audition page.
  The roadmap row still reads "built, awaiting the audition gate".
- **Open:** the per-voice loudness offsets are zero awaiting that gate. S9.1
  (FLARE and BONGO as keys instruments) waits on it.
