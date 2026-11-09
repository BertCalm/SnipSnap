# HALOBAR

A mallet strike opens into rhythmic dub answers, then a moving halo. VIBRAPHONE,
MARIMBA and XYLOPHONE share the processing, while their contact length, mode
weights and decay remain distinct. The vibraphone also has an amplitude motor.
These are synthesized tuned bars, not recordings or measured instrument models.

GLOCKENSPIEL is the new fourth voice, with four inharmonic steel-bar modes and
a C4–C7 register centered on C5. Its [separate audition and study kit](HALOBAR_GLOCKENSPIEL.md)
await listening; the twelve accepted factory recipes and original starter stay
fixed. [CROTALES](HALOBAR_CROTALES.md) now adds a fifth voice: a longer bronze-disc
inspired ring, sounding C6–C8, with its own audition and study kit. Tubular bells
remain planned.

Select HALOBAR in the synth picker, or open its sixteen-pad FRESH TAPE kit in
the keys shelf. The first eight pads play a C minor pentatonic vibraphone walk;
four marimba and four xylophone references follow. Every pad retains an editable
recipe. Its dub is inside the engine; the external effects rack starts dry.

| Control | Default | What moves |
|---|---:|---|
| TUNE | .50 | Note, snapped to semitones; C4 at the middle |
| TOUCH | .40 | Mallet contact duration and attack noise |
| RATE | .45 | Six rhythmic delay divisions at a fixed 72 BPM reference |
| RETURN | .45 | Feedback amount, saturation and progressively darker repeats |
| SPACE | .35 | Echo and halo level; zero gives the dry instrument |
| MOTION | .25 | Return phase motion; also the vibraphone motor |
| HOLD | .30 | Finite bar decay, up to a long note |

Vibraphone and marimba span C3–C6. Xylophone spans A3–C6 to keep its short
attack in a useful pitched register. The note and octave steppers use the exact
inverse of each voice's TUNE curve. RATE runs from dotted quarter to sixteenth;
the renderer has no host-tempo input, so its reference remains 72 BPM.

HOLD .98–1 is the same finite maximum, labelled LONG NOTE. Files are at most
twelve seconds, including their release. A file longer than 1.5 seconds receives
the sampler's LOOP category; it has no seamless loop region or sampler markers.
The source fades over its final 100 ms; capped returns also receive a 100 ms
fade before the house output fade.

## Render and audition

```sh
./gradlew :cli:run --args='synth HALOBAR XYLOPHONE --preset 2 --midi 65 --velocity .7 --macros SPACE=.6,RETURN=.7 --out /tmp/halobar'
./gradlew :synth:generateHalobarKit :synth:generateHalobarAudition
```

The CLI writes a WAV and its complete timbre recipe. Velocity is a render flag;
repeat it when replaying a recipe to reproduce the strike. The house output
level is matched; the sampler supplies the live playing gain.
The kit lands at `testkit/SnipSnap Halobar Kit/`. Open
`testkit/halobar-audition/index.html`, starting with the dry/dub xylophone phrase,
then the full family. The page contains three-register comparisons, all seven
control sweeps, five interacting control pairs, and twelve listening references.
Register references also have fixed-gain, unlevelled 24-bit versions.

The twelve unchanged recipes now join the factory preset roster, following the
owner's approval of the published native audition on 2026-11-08: “Sound great,
continue.” This establishes an overall listening verdict, not phone timing or
MPC hardware acceptance. The [hosted audition](https://halobar-native-audition.bertcalm.chatgpt.site)
retains the same native audio.

## Implementation and limits

The three modes use the existing tuned-bar ratio tables: 1:4:10 for vibraphone
and marimba, 1:3:6 for xylophone. A unit-area contact impulse excites normalized
Double recurrences. The root is reduced only in the return send, preserving a
clear mono fundamental while the upper modes answer through three delay heads.
Low marimba bars retain a real, longer fundamental decay to avoid a tom-like
attack at minimum HOLD.

The nonlinear feedback core runs at 4x, followed by the existing band limit and
two-stage decimation. Fixed allpasses and a finite, partitioned halo convolution
run at the delivery rate, outside the feedback loop. Loop gain stays at or below
.84; the Butterworth filter and saturator have no gain greater than one. The
halo cannot feed itself. House mean removal, 20 Hz high-pass, loudness matching,
peak ceiling and tail fade finish the mono 44.1 kHz output.

The completed-render cache is bounded to 6 MiB and owns copies of its arrays.
An additional 8 MiB cache retains private, immutable dry/echo/halo stems. After a
wet render, changing positive SPACE only remixes those stems and applies the
house output stage. Voice, every other control and velocity invalidate that
preparation. SPACE zero retains the shorter dry-only duration. The immutable
halo spectra use less than 1 MiB.

Fresh diagnostics bypass both audio caches. The audition reports uncached
calculation and changing-SPACE timing separately, with thirty measurements per
voice after eight warm-ups. SPACE edits meet the 50 ms desktop target; full
fresh calculations remain above it. These are JVM timings, not phone timings.
Device playback and MPC hardware loading remain unverified. The owner approved
the native audition on 2026-11-08.

## Accepted-sound verification

| Voice | Fresh median / p95 ms | SPACE edit median / p95 ms |
|---|---:|---:|
| Vibraphone | 310.1 / 341.1 | 14.5 / 16.9 |
| Marimba | 175.9 / 181.0 | 9.2 / 10.5 |
| Xylophone | 145.9 / 156.0 | 8.1 / 10.1 |

Thirty measurements after eight warm-ups, on Temurin 21 with four visible
processors. Every SPACE measurement uses a different positive value; completed
output hits cannot satisfy it. The first wet calculation still pays the full
preparation cost. Other controls invalidate preparation.

All 179 audition WAVs, 153 recipes and sixteen MPC samples remain byte-identical
to the approved version. A 195-case Float32 replay also matches the earlier
renderer exactly: 192 normal 4x cases plus three 8x diagnostics. The complete
JVM run found one missing preset-authoring contract. Its 4,915 synth
checks passed. After the metadata correction, all 4,219 other-module JVM
checks and 53 affected synth/recipe checks passed. The full synth
suite was not repeated after that source-only correction; the DSP and twelve
accepted recipe settings remain identical.
The APK build and device-test compilation also passed. The accepted-foundation
[acceptance ledger](../reference/halobar/native-acceptance.json) records the
source tree, hashes and measurement samples.

The later development integration, based on `2e572bf4c`, preserves both SKIN
and HALOBAR preset restoration. Its 273 focused JVM checks passed
(135 synth, 137 shelf/integration, 1 CLI), as did all six
native C++ tests, Android assembly and device-test compilation. The 195-case
Float32 replay and twelve approved factory parameter comparisons passed again.
The merged downbeat regression test was corrected to pass its required lap
argument. These are focused checks on the later base; the complete nine-module
suite was not repeated there. The earlier complete-run and correction scopes above remain
separate. HALOBAR DSP and approved audio remain unchanged.

See the [engineering spec](superpowers/specs/2026-11-07-halobar-cosmic-dub-design.md)
for the model contract, regression probes and final build evidence. The frozen
[Python reference](../reference/halobar/README.md) records the approved sketch;
it is not the production renderer.

## Why it is built this way

- Separate contact and body paths keep the mallet readable when SPACE rises.
- Less fundamental in the send protects the note against coherent mono echoes.
- Darkening and saturation make repeated answers evolve inside a bounded loop.
- Phase motion and the halo sit after feedback so they cannot destabilize it.
- Finite releases make every exported pad usable without pretending it sustains
  seamlessly. Prepared stems make SPACE editing inexpensive while preserving
  the approved audio; full fresh rendering remains the main engineering cost.
