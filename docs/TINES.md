# Tines

TINES is the FM engine, deliberately small: two operators, phase modulation, a
snapped RATIO and one BRIGHT knob on the modulation index. The characteristic
gesture is the bite: the index dies faster than the note, so a hit opens bright
and clangy and settles toward a pure tone, which is what reads as struck metal
rather than a drone. Every voice is the same core with different numbers:

```
Tines.strike: carrier sine, phase-modulated by a modulator at RATIO x carrier
  index = BRIGHT, decaying BITE x faster than the amp ──> amp envelope (DECAY)
  BELL:    + a quieter partner strike at the CLANG interval
  CHIME:   + a second strike detuned by SHIMMER, the two beating
  ZAP:     carrier and modulator ride one pitch drop (DROP, BEND)
  TOY:     an LFO on the carrier (WOBBLE, SHAPE)
  KALIMBA: tongue strike + two bar overtones + nail tick (TICK) + buzzer rattle (BUZZ)
  ──> 4x decimate ──> peak normalize ──> Snip
```

`Tines.strike` is internal so `Keys` can strike at exact MIDI pitch for the EP
and Music Box instruments without a second FM core. The roadmap row is S3; the
sound-design round (BITE, CLANG, RATIO on four voices, BEND, SHAPE, TICK) is
the table under "TINES got the same round" in `SYNTH_ROADMAP.md`.

## Voices

| Voice | What it is | Own knobs | TUNE |
|---|---|---|---|
| BELL | a struck bell with a partner strike above it | RATIO, CLANG | 220-740 Hz, continuous |
| CHIME | two near-identical glass bells beating | SHIMMER, RATIO | 520-1500 Hz, continuous |
| BLOCK | a woodblock, gone in a blink | RATIO | 380-950 Hz, continuous |
| ZAP | a laser tom: the pitch falls onto its note | DROP, BEND, RATIO | 55-120 Hz landing, continuous |
| TOY | a cheap keyboard's wobbling hit | WOBBLE, RATIO, SHAPE | 300-900 Hz, continuous |
| KALIMBA | a plucked tine with buzzers | BUZZ, TICK | A3 up two octaves, snapped to semitones |

Every voice has TUNE, BRIGHT, DECAY and BITE. KALIMBA is the one melodic voice,
so it alone snaps TUNE; it took PLUCK's kalimba's root and span so the melodic
kit's notes did not move.

## Knobs

- **TUNE** maps exponentially across the voice's carrier range; on KALIMBA it
  snaps to semitones.
- **RATIO** snaps the modulator to 1, 1.4, 2, 2.7, 3.5, 4.2 or 5.8 times the
  carrier. Each voice whose ratio used to be fixed defaults inside that entry's
  band (CHIME 3.5, BLOCK 1.4, ZAP 2.7, TOY 2).
- **BRIGHT** is the modulation index's starting depth, per voice (BELL 0.8 to 5).
  On KALIMBA it also raises the bar overtones and the tick.
- **DECAY** is the amp's T60, per voice (BLOCK 45-160 ms up to KALIMBA 0.5-1.1 s).
- **BITE** multiplies how much faster the index dies than the note, 0.3x to 3x,
  exactly 1 at its default.
- **CLANG** moves BELL's partner from a fifth (1.5) through the stretched octave
  (2.01) to 2.76, off every harmonic, and raises its level above the middle.
- **SHIMMER** detunes CHIME's pair, 1.001 to 1.02.
- **DROP** sets how far above its landing ZAP starts, 4x to 16x. **BEND** is how
  fast it lands, 6/s (a long laser) to 90/s (a pitched thud).
- **WOBBLE** is TOY's LFO, 6 to 34 Hz and deeper as it rises. **SHAPE** squares
  the LFO off, so the pitch steps between two notes instead of sliding.
- **BUZZ** is KALIMBA's buzzers: noise added where the tine's swing passes a
  threshold BUZZ lowers. 0 is a clean thumb piano.
- **TICK** is KALIMBA's nail, from no click to six times the old one.

## Kit, audition, presets

- **Presets:** sixteen per voice in `TinesPresets.kt`, ninety-six in all: twelve,
  then four BOLD that push the sound-design macros. Reasoned from the DSP and
  never heard, per that file's KDoc.
- **Kits:** TINES has no kit of its own. ZAP, BLOCK, CHIME and BELL fill A13-A16
  of THUMP's factory kit (`ThumpKits.classic()`, `./gradlew :synth:generateThumpKit`);
  KALIMBA plays A07-A11 of `SynthKits.melodic()` (`./gradlew :synth:generateMelodicKit`);
  TOY and ZAP sit in `SynthKits.chip()` (`./gradlew :synth:generateChipKit`); BELL
  and CHIME are GRAINS sources in `SynthKits.cloud()`.
- **Audition page:** `./gradlew :synth:generateTinesAudition` runs
  `TinesAuditionGenerator`, TINES's own hand-made page (not `RosterAudition`). It
  writes `testkit/tines-audition/`. Every clip is a five-note phrase (root, third,
  fifth, octave, fifth, 0.32 s apart) because a bell or a kalimba is judged by how
  it plays a line. Per voice it holds today's sound, the sound-design round's
  macros at both ends (BITE everywhere, CLANG, RATIO on CHIME, BLOCK, ZAP and TOY,
  BEND, SHAPE, TICK) and the four BOLD presets, at one loudness.

## Ledger

- **Verified:** `TinesTest`, `TinesPresetsTest` and `TinesSoundDesignTest` cover
  the engine, the roster and the sound-design macros' exact defaults.
- **Not heard:** no TINES preset or audition clip has had the owner's listen on
  record. `Tines.rattle`'s KDoc names BUZZ's level as one to revisit once the
  presets are authored by ear.
- **Open:** the TINES audition does not cover BRIGHT, DECAY, SHIMMER, DROP,
  WOBBLE or BUZZ, BELL's RATIO, or the first twelve presets per voice. A
  `RosterAudition` spec would cover all of them; whether TINES gets one next to
  its hand-made page is undecided.
