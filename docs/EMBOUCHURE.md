# EMBOUCHURE

EMBOUCHURE is a dry, deterministic mono prototype of a virtual player supplying
energy to one pitched resonator. Its first audition asks whether effort changes
how the note speaks: contact, articulation and spectrum as well as level.
It has not received the owner's sonic approval. Eight original presets are
available; the proposed larger roster waits for that listening decision.

The source brief is [Embouchure engine engineering specification](engineering/embouchure-engine-spec.md).
That brief refers to a supplied edited Gyre integration contract, but no such
separate contract accompanied the attachment. The port follows the interfaces
actually present in SnipSnap, especially TESSERA's relative TUNE and
PITCHWHEEL/CISTERN's explicit note, velocity and renderer-model recipes.

## Controls and source identity

Five timbral controls are EFFORT, REED, SHAPE, GRAIN and LENGTH. HOLD selects a
finite gesture or settled loop; TUNE is the host's note control. EFFORT selects
the intensity of the saved playing gesture. Velocity supplies input energy
within that character. HOLD requires continuing player energy and is separate
from a finite note's passive release.

The voices are REED, BRASS, BASS, WHISTLE, BOW and SOFT. Each voice uses one driver
calibration, fixed by voice and renderer model. REED, BASS, WHISTLE and SOFT use a
reed-like driver, BRASS a lip-like driver and BOW a friction driver. WHISTLE is
an air/reed-derived clear-tone approximation; it is not an accurate jet-edge
model. These are original reduced models inspired by pressure-driven physical
synthesis, with no VL1 algorithm or factory-data compatibility claim.

The saved gesture is one of SOFT_ENTRY, LEANING_SWELL, OVERPRESSURE,
PRESSURE_RELEASE or BOWED_CAPTURE. Named segment profiles are versioned with
the renderer. Model version 1 pins driver, resonator, body, envelope and loop
calibration; a change to those recipes needs an explicit model/version policy.

## Recipes and note semantics

`EmbouchurePatch` stores the engine/version, name, voice, macro map, renderer
model, MIDI base, velocity, fixed driver name and selected gesture. The macro map
contains TUNE and the six musical controls. Defaults fill omitted macros;
missing optional fields select the voice's initial configuration. Unknown
renderer models, gestures, conflicting drivers and out-of-range macro or note
values are rejected. Every render starts fresh and derives its seed from the
voice/note infrastructure. `withMacros`, `Patches.edited`, `PadRecipe` and the
user-preset shelf preserve the saved configuration.

TUNE snaps from 0 to 24 semitones above the saved MIDI base. At the default 0.5,
the played note is twelve semitones above that base. Each voice's supported
register is `Embouchure.midiRange(voice)`; both saved base and resulting played
note must fit. The phone's TUNE slider restricts its range for a loaded preset
whose base is above the voice's minimum. The CLI's `--midi` selects the played
note directly and resets the relative TUNE offset to zero. This prevents the
usual default offset from moving an explicit note into another octave.

The initial supported registers are BASS C2–C4 (MIDI 36–60), WHISTLE C4–C6
(60–84), and REED/BRASS/BOW/SOFT C3–C5 (48–72). These are prototype bounds,
subject to capture probes and owner listening. HOLD at or above 0.999 selects
the loop; lower positions use the finite gesture.

Native velocity rendering retains the selected gesture, contact macros and
model. Existing velocity-zone generation peak-matches the resulting take for
the MPC's own velocity curve. Finite notes route as TONAL and HOLD routes as
LOOP through `Embouchure.drumClassFor`, preserving the source's pitched identity.
The generic audio classifier remains a separate diagnostic.

Classifier guards use short onset/body spectral windows with the actual finite
note's full release measurement. An arbitrary crop of a still-rising BASS can
read as TOM when the crop invents an early release. Saved host recipes retain
their explicit TONAL/LOOP routing.

## First listening roster

| Preset | Voice | Saved gesture |
|---|---|---|
| Leaning Reed | REED | LEANING_SWELL |
| Muted Bell | BRASS | PRESSURE_RELEASE |
| Breath Pocket | BASS | Voice default |
| Soft Entry | SOFT | SOFT_ENTRY |
| Short Overblow | REED | OVERPRESSURE |
| Relaxed Bow | BOW | BOWED_CAPTURE |
| Narrow Air | WHISTLE | Voice default |
| Held Effort | SOFT | LEANING_SWELL, HOLD 1 |

`SynthKits.embouchure()` places eight REED notes from the minimum register in
C minor pentatonic on A01–A08 and these eight presets on A09–A16. It is dry,
fixed and independent per pad, with the complete recipe on every pad. Held
Effort is a loop; the remaining pads are tonal finite notes. The FRESH TAPE
starter has ID `embouchure` and the SYNTH picker includes all six voices,
factory presets, editing, SCRAMBLE, saving and SEND TO PAD.

## Audition and export

Generate the listening pack with:

```sh
./gradlew :synth:generateEmbouchureAudition
```

The pack lives under `testkit/embouchure-audition/`. Its manifest and patch JSON
identify the actual note, velocity, macros and player profile; audio comparisons
include raw and matched renders so loudness does not conceal excitation changes.
The [private audition page](https://embouchure-audition.bertcalm.chatgpt.site)
hosts the complete first matrix and kit downloads; [pack notes](EMBOUCHURE_AUDITION.md)
describe the loudness rules, measurements and reproduction task.

Render factory presets through the normal CLI path:

```sh
./gradlew :cli:run --args="synth EMBOUCHURE REED --all --out /tmp/embouchure-reed"
./gradlew :cli:run --args="synth EMBOUCHURE BASS --midi 48 --velocity .4 --out /tmp/embouchure-bass"
```

Save the FRESH TAPE starter and use SnipSnap's existing kit/export paths for
MPC WAV/program output. The patch is carried by `PadRecipe`; export is handled
by the existing host, including its velocity-layer conventions. The current
kit/drum-pad format has no attack-plus-sustain-loop field: Held Effort exports
as one settled loop-length WAV and a drum pad plays it once. Continuous repeat
on the audition page tests its wrap. A sustained keygroup instrument is a
separate host workflow and is not implied by a LOOP classification.

Numerical tests cover reproducibility, bounds, root capture, macro activity,
feedback dependence, source removal, serialization, routing and seam metrics.
They do not decide whether the voices have distinct musical identities or
whether a held transition sounds seamless. Listening, Android-device testing,
MPC-device playback and final naming remain acceptance work.
