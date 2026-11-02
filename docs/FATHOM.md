# Fathom

FATHOM is the bass engine, and the only engine whose identity is the bass slot.
The characteristic gesture is a note that slides up into pitch, saturates, and is
then shaped by a low resonant filter as it decays. Every voice runs one path:

```
source ──> DRIVE ──> low-pass (CUTOFF, key-tracked) ──> amp envelope (DECAY) ──> Snip
  └─ pitch ← GLIDE (every voice) and SWEEP (DEEP only)
```

DRIVE sits before the filter on purpose. Saturation makes harmonics, and the
filter has to be downstream to shape them. Putting distortion after the filter,
as an FX rack would, is why that combination sounds like a blanket.

The design is in the [engineering spec](superpowers/specs/2026-08-22-fathom-bass-engine-design.md).
The roadmap row is S3.8.

## Voices

| Voice | Source | Own knob | Bottom of TUNE |
|---|---|---|---|
| DEEP | sine, with a fast pitch blip at the attack | SWEEP: the blip's size | E1 (41.2 Hz) |
| GRIND | two detuned saws, left to beat over a long tail | SPREAD: the detune, heard as a beat rate | A1 (55 Hz) |
| GLASS | two-operator FM tuned for the bottom | RATIO: snapped to 0.5, 1, 1.5, 2 and 3 | A1 (55 Hz) |

## Knobs

All voices share TUNE, GLIDE, DRIVE, CUTOFF and DECAY.

- **TUNE** snaps to whole semitones over two octaves above the voice's bottom note.
  The SYNTH screen's ROOT and OCT steppers move it a semitone or an octave at a time.
- **GLIDE** starts up to an octave below the note and slides up into it. The slide
  is clamped inside DECAY, so it always lands before the note ends.
- **DRIVE** adds harmonics, each voice in its own way: DEEP folds toward a shaped
  square, GRIND clips the saw pair, GLASS deepens the FM.
- **CUTOFF** maps 90 Hz to 4 kHz and tracks the note, so a run up the pads stays
  equally bright. The tracking amount (0.6) is a placeholder awaiting a listen.
- **DECAY** sets the amp envelope's length.

## Kit, audition, presets

- **Presets:** twelve per voice in `FathomPresets.kt`, authored from the DSP, not
  by ear. GRIND's presets stay inside the region the classifier files as KICK;
  GLASS's stay where it files PERC.
- **Starter kit:** `SynthKits.fathom()`. A01 to A08 walk DEEP up the minor
  pentatonic from E1 at default knobs. A09 to A12 are four GRIND presets and
  A13 to A16 four GLASS presets. Every pad is dry and filed by
  `Fathom.drumClassFor`: KICK for DEEP and GRIND, PERC for GLASS.
  Render it with `./gradlew :synth:generateFathomKit`.
- **Audition page:** `./gradlew :synth:generateFathomAudition` writes
  `testkit/fathom-audition/`. It holds the kit, then each voice's default, every
  knob at both ends, TUNE at three notes, and every preset, at one loudness. It is
  the shared `RosterAudition` page, so it looks and saves like the others.

## Ledger

- **Verified:** `FathomTest`, `FathomPresetsTest` and `FathomKitTest` pass. The kit
  test checks the walk's pitches and every pad's class and recipe. The audition
  test checks every clip the manifest lists was written.
- **Not heard:** no FATHOM preset, kit pad or knob end has had the owner's listen.
- **Open:** the per-voice loudness offsets are zero awaiting that listen, and so
  is a decision on CUTOFF's key-tracking amount.
