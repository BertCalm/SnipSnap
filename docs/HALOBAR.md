# HALOBAR

A mallet strike opens into rhythmic dub answers, then a moving halo. VIBRAPHONE,
MARIMBA and XYLOPHONE share the processing, while their contact length, mode
weights and decay remain distinct. The vibraphone also has an amplitude motor.
These are synthesized tuned bars, not recordings or measured instrument models.

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

Those references are available in the picker and CLI, and explicitly remain
outside the global factory preset roster until the native sound has been heard.
The earlier Python audition received owner approval; that does not establish a
listening verdict for this Kotlin renderer.

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

The completed-render cache is bounded to 6 MiB and owns copies of its arrays;
immutable halo spectra use less than 1 MiB. Warm fresh-control medians were 291 ms for vibraphone, 164 ms for marimba and
136 ms for xylophone on the development JVM, exceeding the 50 ms target.
The native audition records thirty uncached measurements per voice after eight
warm-ups. These are desktop timings, not phone measurements. Device playback,
MPC hardware loading and the native listening verdict remain unverified.

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
  seamlessly. The main remaining engineering cost is fresh-control render time.
