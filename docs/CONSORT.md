# CONSORT

A woodwind section in one pad: a lead and up to three harmony players, each a separate blown
pipe. Prototype engine (`PatchTypes.PROTOTYPES`). The design, the three listening rounds on the
Python prototype and the "As built" record are in the
[design brief](superpowers/specs/2026-11-19-consort-woodwind-ensemble-design-brief.md).

**What has been heard.** The owner heard the Python prototype over three rounds and approved its
overall sound, the room at its default and the four takes. Nobody has heard the Kotlin render.

## What it plays

| | |
|---|---|
| Voices | FLUTE, SAX, CLARINET, OBOE, BASSOON, DUDUK. Any can lead; any can sit in a chair |
| Section | up to three chairs, each an instrument at OCT_DOWN, UNISON, FIFTH, MIN7, MAJ7 or OCT_UP from the lead |
| Velocity | breath effort: `0.5 BREATH + 0.6 velocity`. Above 0.85 the flute cracks to its octave and the reeds growl |
| Takes | four ways of playing the note: centred, soft, bitten, leaning. Chairs are staggered |

| Macro | Default | What it changes |
|---|---|---|
| TUNE | 0.5 | the lead's note, two octaves in semitones; every chair moves with it |
| BREATH | 0.6 | mouth pressure, breath noise, bell opening, reed edge |
| GESTURE | 0 | stepped: PLAIN, SCOOP, TRILL, FLUTTER, FALL |
| MOTION | 0.5 | how much of the gesture (vibrato depth on a plain note) |
| BLEND | 0.6 | the harmony chairs' level, 14 dB under the lead up to level with it |
| LOOSE | 0.5 | how human the players are; also how far a take strays |
| ROOM | 0.4 | each chair's wall bounces and distance, and the shared wash |
| HOLD | 0.45 | breath length, 0.3 to 4 s. The top step is a long note, not a loop |

The section, the velocity and the take are fields on `ConsortPatch`, not macros.

## Files

- `synth/.../Consort.kt`: the engine. `plan` (the player), `blow` (the pipe), `rasp` and `body`
  (the tone), `seat` and `wash` (the room), `render`.
- `synth/.../ConsortPatch.kt`: the saved recipe and its JSON.
- `synth/.../ConsortPresets.kt`: thirteen provisional recipes, outside the factory roster until heard.
- `synth/src/test/.../ConsortTest.kt`: seventeen probes. `DeterminismTest` has the canary.
- `synth/src/test/.../ConsortAuditionGenerator.kt`: `./gradlew :synth:generateConsortAudition`
  writes 100 clips, their recipes, `metrics.csv` and a page under `testkit/consort-audition/`.
- `docs/prototypes/consort/consort_proto.py`: the Python prototype the owner heard.

## Measured on the Kotlin render (2026-11-19, the audition's `metrics.csv` and `ConsortTest`)

| | |
|---|---|
| Pitch, solo notes at the defaults (room and humanization on) | within 7.6 cents across 36 clips; confidence 0.92 or better |
| Pitch, dry and stiff (the test's condition) | within 8 cents at three notes a voice |
| Hard FLUTE | 1201 cents: the octave |
| Cylinders | CLARINET's and DUDUK's second harmonic over 20 dB under the first |
| Classifier | none of the 100 audition clips, and none of the test's 180 short renders, files as a drum |
| Render time (after the K2 speed pass, 4 cores) | solo 34 to 169 ms (median 73); section 57 to 214 ms (median 100). Before: solo median 109, section median 324 |

## Disclosed, not fixed

- **Render time is still over the house rule** ("tens of ms") for long notes and sections. The K2
  pass moved the tone filters to the output rate (18 ms to 5 ms a note), keeps the room's setup
  per ROOM value (about 12 ms a render), blows a section's players side by side, and works the
  slow parts of the plan out every 64 samples. What is left on a default 1.3 s note, measured:
  the house band limit before the decimator 16 to 20 ms, the pipe 8 to 9, the plan 6 to 7, the
  body 5, the reed's bend 3 to 4, and the room about 24. A section's time now depends on the
  cores it has.
- **HOLD does not loop.** A loop with a room in it needs a periodic wash, or the room applied
  after the loop. `Consort.isLoop` is false at every setting and a test says so.
- **Eight controls**, HOLD included, where the house asks for three to six plus HOLD. TREMOR has
  the same count. The alternative is to land the pad with the rack's ROOM, but the rack sees one
  finished mix and cannot seat each chair.
- **The low BASSOON is thin.** At its lowest note (58 Hz) the audition reads a 1466 Hz centroid
  against 211 Hz an octave up: the body's 150 Hz high-pass and tilt remove most of the fundamental.
- **DUDUK's velocity is not monotonic by the classifier's whole-file centroid** at the defaults
  (soft 778, medium 542, hard 704 Hz); dry and stiff it rises, which is what the test checks.
- **The Kotlin differs from the heard prototype** in three places: `Strings.Loop` (allpass-tuned,
  retuned every 64 samples) for a linearly interpolated delay, biquads for a zero-phase spectrum
  gain, and `Room.bloom` for a noise convolution. Each can change the sound.
- **Reeds do not overblow**; they growl. Only the flute's crack is a regime change.

## Acceptance gates

All open. See [the gates' CONSORT entry](ENGINE_ACCEPTANCE_GATES.md#consort-kotlin-prototype-2026-11-19).
