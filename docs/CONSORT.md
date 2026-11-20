# CONSORT

A woodwind section in one pad: a lead and up to three harmony players, each a separate blown
pipe. Prototype engine (`PatchTypes.PROTOTYPES`). The design, the three listening rounds on the
Python prototype and the "As built" record are in the
[design brief](superpowers/specs/2026-11-19-consort-woodwind-ensemble-design-brief.md).

**What has been heard.** The owner heard the Python prototype over three rounds and approved its
overall sound, the room at its default and the four takes, then heard the Kotlin K1 audition and
approved it (2026-11-20: "Sounds good to me"; asked directly, "Yes, I listened; it sounds good").
That render is the accepted baseline. The current engine is within -50 dB of it on every one of
the 100 audition clips (median -88 dB), which nobody has listened to as a difference.

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
- `SynthKits.consort()` and `ConsortKitGenerator`: `./gradlew :synth:generateConsortKit` writes the
  first kit to `testkit/SnipSnap Consort Kit/` (A01 to A08 FLUTE up the minor pentatonic, A09 to A16
  eight recipes).
- `docs/prototypes/consort/consort_proto.py`: the Python prototype the owner heard.

## Measured on the Kotlin render (2026-11-19, the audition's `metrics.csv` and `ConsortTest`)

| | |
|---|---|
| Pitch, solo notes at the defaults (room and humanization on) | within 7.6 cents across 36 clips; confidence 0.92 or better |
| Pitch, dry and stiff (the test's condition) | within 8 cents at three notes a voice |
| Hard FLUTE | 1201 cents: the octave |
| Cylinders | CLARINET's and DUDUK's second harmonic over 20 dB under the first |
| Classifier | none of the 100 audition clips, and none of the test's 180 short renders, files as a drum |
| Render time (after the K2 speed pass, 4 cores) | solo 40 to 150 ms (median 78); section 63 to 270 ms (median 116). Before: solo median 109, section median 324 |

## Disclosed, not fixed

- **Render time is still over the house rule** ("tens of ms") for long notes and sections. The K2
  pass keeps the room's setup per ROOM value (about 12 ms a render), blows a section's players
  side by side, and works the slow parts of the plan out every 64 samples. What is left on a
  default 1.3 s note, measured: the body's filters 18 ms, the house band limit before the
  decimator 16 to 20, the pipe 8 to 9, the plan 6 to 7, the reed's bend 3 to 4, and the room
  about 24. A section's time now depends on the cores it has.
- **The body's 18 ms is kept on purpose.** Moving its filters to the output rate saved 13 ms a
  note and darkened every voice (2 to 5 kHz down 0.2 to 1.9 dB against 100 to 500 Hz). The owner
  had approved the sound with the filters at the internal rate, so the move was undone.
- **The room makes short notes long.** A render is the breath, 0.35 s of release and the wash's
  tail (0.74 s at the default ROOM). At the default ROOM any HOLD above about 0.11 passes the
  classifier's 1.5 s line and files LOOP by length alone, so the kit's note pads use HOLD 0.1.
- **HOLD does not loop.** A loop with a room in it needs a periodic wash, or the room applied
  after the loop. `Consort.isLoop` is false at every setting and a test says so.
- **Eight controls**, HOLD included, where the house asks for three to six plus HOLD. TREMOR has
  the same count. The alternative is to land the pad with the rack's ROOM, but the rack sees one
  finished mix and cannot seat each chair.
- **The low BASSOON is not thin (K3 correction).** K2 reported it thin from the whole-file
  centroid (1466 Hz at 58 Hz against 211 Hz an octave up). Measured on the held part of the note
  (0.4 to 1.1 s, room off), the lowest note is the darkest: centroid 327 Hz at MIDI 34 against
  465 Hz at MIDI 52, the fundamental is its strongest partial (-7 dB against the RMS), and the band
  above 2 kHz is -12.9 dB against -9.1. The whole-file reading is pulled up by the quiet edges of
  the clip. Nothing was changed; the listening round plays the low notes for the owner to judge.
- **DUDUK's velocity is not monotonic by the classifier's whole-file centroid** at the defaults
  (soft 778, medium 542, hard 704 Hz); dry and stiff it rises, which is what the test checks.
- **The Kotlin differs from the heard prototype** in three places: `Strings.Loop` (allpass-tuned,
  retuned every 64 samples) for a linearly interpolated delay, biquads for a zero-phase spectrum
  gain, and `Room.bloom` for a noise convolution. Each can change the sound.
- **Reeds do not overblow**; they growl. Only the flute's crack is a regime change.

## Acceptance gates

All open. See [the gates' CONSORT entry](ENGINE_ACCEPTANCE_GATES.md#consort-kotlin-prototype-2026-11-19).

## Round K3 (2026-11-20)

Held keys that sustain while a key is down (the owner's choice), the chord tool, round robin on pads
(ROBIN and CONSORT TAKES), CONSORT and CONSORT TAKES on the shelf, the SYNTH picker with a section
editor, and fourteen character presets. The owner heard all 29 clips of the K3 round and kept them
("All sounds good"); the 27 recipes are the factory bank. The one-shot sound is unchanged (156
renders pinned in `ConsortPinsTest`); the K3 renders are pinned in `ConsortK3PinsTest`. Gate status:
[the gates' CONSORT entry](ENGINE_ACCEPTANCE_GATES.md).
