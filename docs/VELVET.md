# Velvet

VELVET is the subtractive engine: one-shot stabs from naive oscillators through
a resonant low-pass. The characteristic gesture is the filter falling faster than
the note, so a stab opens bright and closes while it still sounds. Every voice
runs one path:

```
saw/pulse pair (SHAPE, FAT detune) + sub square an octave down (FAT level)
  ──> resonant low-pass (CUTOFF, key-tracked; SQUEEZE = resonance + sweep)
  ──> amp envelope (DECAY) ──> 4x decimate ──> Snip
      └─ filter envelope: peak to floor in half the amp's T60
```

The oscillators are naive saws and pulses, rendered at 4x and decimated
(`Dsp.OVERSAMPLE`, then `Dsp.decimate`). The filter is `Dsp.TptSvf`, the
trapezoidal state-variable filter, with its saturating mode on so the top of
SQUEEZE self-limits. The roadmap row is S3.6. The design entry under "The key
engines" in `SYNTH_ROADMAP.md` lists GLIDE as the fifth macro; the build has DECAY
there instead and no GLIDE.

## Voices

| Voice | Where its knobs start | Bottom of TUNE |
|---|---|---|
| BASS | saw, dark filter, moderate SQUEEZE | A1 (55 Hz) |
| BRASS | saw, widest FAT, half-open filter | A2 (110 Hz) |
| SQUELCH | saw, low CUTOFF, SQUEEZE 0.85 | E2 (82.4 Hz) |
| CHIP | narrow pulse (SHAPE 0.9), filter open, little SQUEEZE | A3 (220 Hz) |

All four voices have the same six knobs; only their defaults differ
(`Velvet.macrosFor`).

## Knobs

- **TUNE** snaps to whole semitones over two octaves above the voice's bottom note.
- **SHAPE** is all saw over its lower half. From 0.5 to 0.75 it crossfades the
  pair to a square; above 0.75 the pulse narrows from 50% to 12%.
- **FAT** spreads the unison pair from a 1.0005 to a 1.012 frequency ratio and
  raises the sub square from 0.15 to 0.45. `Velvet.detuneFor` lifts a too-slow
  beat toward audibility but is clamped so FAT always keeps
  `MIN_AUTHORITY_RATIO` (1.002) of room above that floor.
- **CUTOFF** sets the filter's resting point, 180 Hz to 12 kHz, before key
  tracking. Tracking is 0.6 about the middle of TUNE, a placeholder awaiting a
  listen (`CUTOFF_KEY_TRACK_AMOUNT`).
- **SQUEEZE** moves resonance and the filter envelope's amount together: the
  damping goes from 1.8 to 0.3 and the sweep's peak from about 2.9 to 6 times the
  resting cutoff (capped at 16 kHz).
- **DECAY** sets the amp's T60, 0.15 s to 0.9 s. The render lasts 1.4 times that.

## Kit, audition, presets

- **Presets:** twelve per voice in `VelvetPresets.kt`, plus two on BRASS that
  land with a string-machine chain (STRING MACHINE and THIN STRINGS,
  `VelvetPresets.landingFor`): fifty in all. Authored from the DSP, not by ear,
  per that file's KDoc.
- **Starter kit:** `SynthKits.velvet()`, a sixteen-pad starter kit: A01-A08 walk
  the first voice up the minor pentatonic, A09-A16 are factory presets. Render it
  with `./gradlew :synth:generateVelvetKit`. VELVET also plays in
  `SynthKits.chip()` (CHIP squares through CRUNCH) and is one GRAINS source in
  `SynthKits.cloud()`.
- **Audition page:** `./gradlew :synth:generateVelvetAudition` runs
  `VelvetRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/velvet-audition/` with each voice's default, every knob at both ends,
  TUNE at three notes, and every preset, at one loudness. The two string-machine
  presets play dry there, without their chain. The starter kit
  opens the page.

## Ledger

- **Verified:** `VelvetTest` and `VelvetPresetsTest` cover the engine and the
  roster. `FirstGenAuditionTest` checks the audition spec has a note for every
  voice and every knob but TUNE.
- **Not heard:** no VELVET preset, knob end or audition clip has had the owner's
  listen on record.
- **Open:** the per-voice loudness offsets are zero awaiting that listen, and so
  is CUTOFF's key-tracking amount. The starter kit is checked by
  `FirstGenKitsTest`.
