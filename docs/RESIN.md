# Resin

RESIN is the ladder engine: a stack of oscillators through a four-pole
transistor-ladder low-pass with its saturation inside the feedback loop. The
characteristic gesture is the ladder's own: resonance thins the bass as it rises
and sings at the top, while an envelope sweeps the cutoff down through the note
(the wah, then the snap). Every voice runs one path:

```
saw at the note + saw an octave down + detuned square   (STACK mixes, scaled to unity)
  ──> Dsp.Ladder: four one-poles, last fed back to first, tanh in the loop
        cutoff: CUTOFF (key-tracked) swept up by CONTOUR, falling back
        feedback: CREAM, up to 4.3 (self-oscillation at 4)
  ──> amp envelope (DECAY) ──> 4x decimate ──> loudness level ──> Snip
```

The drive is the ladder's own input stage; there is no separate saturator. STACK
is scaled to unity so how hard the stack hits the ladder's tanh does not change
with the mix. The ladder's passband loss as feedback rises is restored as
loudness after the render, never inside the filter. The design is in the
[engineering spec](superpowers/specs/2026-09-24-resin-ladder-engine-design.md).
The roadmap rows are S8 (the one-shots), S8.1 (held) and S8.2 (drone).

## Voices

| Voice | Where its knobs start | CUTOFF range before tracking | Bottom of TUNE |
|---|---|---|---|
| BASS | half STACK, low CUTOFF | 60 Hz to 5 kHz | A1 (55 Hz) |
| LEAD | full STACK, more CREAM | 200 Hz to 14 kHz | A3 (220 Hz) |
| BRASS | the biggest CONTOUR (0.75) | 120 Hz to 9 kHz | A2 (110 Hz) |

All three voices have the same six knobs (`Resin.macrosFor`).

## Knobs

- **TUNE** snaps to whole semitones over two octaves above the voice's bottom note.
- **STACK** is the mixer: the sub-octave saw rises over the bottom half of the
  knob (to 0.9), the detuned square from 0.4 to the top, and the square's detune
  widens from 3 to 14 cents. The saw at the note is always on. `stackGains` is
  marked a placeholder awaiting the audition gate.
- **CUTOFF** sets the ladder's resting point in the voice's range above, then
  tracks the note at 0.6 about the middle of TUNE (`CUTOFF_KEY_TRACK_AMOUNT`,
  inherited from VELVET and marked a placeholder). Capped at 16 kHz.
- **CREAM** is the feedback, 0 to 4.3. The linear ladder self-oscillates at 4, so
  the top of the knob rings on its own.
- **CONTOUR** is the filter sweep's amount and speed together: up to four octaves
  above the resting point, falling with a T60 from 0.6 of the note's down to 0.2
  as the knob rises, so a bigger sweep snaps faster. The 0.6 to 0.2 shape is
  marked a placeholder.
- **DECAY** sets the amp's T60, 0.15 s to 1.2 s.

## Kit, audition, presets

- **Presets:** twelve per voice in `ResinPresets.kt`, plus two on BRASS that land
  with a string-machine chain (WIDE STRINGS and DARK STRINGS,
  `ResinPresets.landingFor`): thirty-eight in all. Authored from the DSP, not by
  ear, per that file's KDoc.
- **Starter kit:** `SynthKits.resin()`, a sixteen-pad starter kit: A01-A08 walk
  the first voice up the minor pentatonic, A09-A16 are factory presets. Render it
  with `./gradlew :synth:generateResinKit`.
- **Held and drone:** a RESIN patch becomes a nine-zone keys instrument
  (`snipsnap synth RESIN <VOICE> --instrument`, or SYNTH's MAKE INSTRUMENT) with
  CREAM capped at the self-oscillation threshold (`HELD_MAX_RESONANCE`, 4) so its
  loops close, and a loop-grid drone (`--drone`, or DRONE TO LOOP). Their designs
  are in the [held](superpowers/specs/2026-09-25-resin-held-pad-design.md) and
  [drone](superpowers/specs/2026-09-25-resin-drone-design.md) specs.
- **Audition page:** `./gradlew :synth:generateResinAudition` runs
  `ResinRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/resin-audition/` with each voice's default, every knob at both ends,
  TUNE at three notes, and every preset, at one loudness, as one-shots. The two
  string-machine presets play dry there. The starter kit
  opens the page.

## Ledger

- **Verified:** `ResinTest` and `ResinPresetsTest` cover the one-shots and the
  roster; `ResinHeldTest`, `ResinHeldFuzzTest`, `ResinDroneTest` and
  `ResinDroneFuzzTest` cover the held and drone paths. `FirstGenAuditionTest`
  checks the audition spec has a note for every voice and every knob but TUNE.
- **Not heard:** no RESIN preset, knob end or audition clip has had the owner's
  listen on record; `Resin.kt` marks STACK's curve "authored from the DSP, never
  listened to".
- **Open:** the per-voice loudness offsets are zero, and the key-tracking amount,
  STACK's curve and CONTOUR's shape are placeholders, all awaiting that listen.
  The starter kit is checked by `FirstGenKitsTest`.
