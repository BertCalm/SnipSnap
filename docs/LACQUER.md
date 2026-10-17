# Lacquer — layered tone, separated articulation

Lacquer is a deterministic, original layered-tone engine. Body anchors the
requested note, edge supplies contact and upper detail, trail develops more
softly, and quiet reinforcement adds weight where the selected voice needs it.
Each layer has its own source and envelope; RELIEF changes their arrival and
development rather than adding an echo to a finished sound.

The factory bank contains 36 recipes: six each for KEY, GLASS, BRASS, BASS,
PLUCK and SILK. The original eight recipes remain unchanged as references;
28 additions vary layer sources, filtering, envelopes, timing and macros.
The sources are generated in code, without external PCM assets or a hidden
fixed-chord recipe. The expanded bank is available for owner listening review.

The [published audition](https://snipsnap-lacquer-audition.bertcalm.chatgpt.site)
is private to the owner's account. It includes native and level-matched clips
for all 36 recipes, layer solos, macro and interaction comparisons, and MPC
downloads. Voice and reference filters keep the larger bank easy to browse.
The commands below reproduce the pack locally.

## Controls and host behavior

| Control | Behavior |
| --- | --- |
| RELIEF | Separates independently articulated layer arrivals and development. |
| SHEEN | Opens upper-layer detail and edge projection. |
| BODY | Changes low/mid support and body source character. |
| CONTOUR | Moves the voice's envelopes from clipped toward blooming sustain. |
| GLUE | Applies bounded shared nonlinear coloration on the oversampled path. |
| TUNE | The host's pitch offset, separate from the five timbral controls. |
| HOLD | Finite at ordinary settings; the top step supplies settled loop material. |

Render state is fresh for every call. Output is mono at 44.1 kHz, with sources
and nonlinear processing running at 4× before band-limited decimation. Shared
melodic loudness follows the existing `Dsp.MELODIC_LOUDNESS_TARGET` contract.
Velocity affects excitation and articulation; it is an event parameter rather
than a saved macro. Finite sounds route as TONAL and held sounds as LOOP.

Patches store a MIDI root from 24 to 96 (default 48) and velocity from 0 to 1.
TUNE offsets that stored root by ±12 semitones, giving an effective range of
12 to 108. Zero velocity is silent. A normal finite schedule is capped at
eight seconds by proportionally compressing the entire layer timeline, retaining
its relative arrivals and release regions.

A finite render retains all scheduled layers and their tails. A loop-only
render cannot keep an initial onset delay audible indefinitely: held RELIEF
separates low-depth periodic layer articulation and spectral development instead.
The host currently receives the settled region, without an attack-plus-loop
sample format. Loop pitch uses whole cycles over an integer frame period, with
only the small correction needed to fit the frame clock.

## Saving and integration

`LacquerPatch` saves the macro map and explicit layer model, including sources,
ratios, reference gains, timing, envelopes, processing and loop profile. Editing
the common name or macros preserves that model. `Patches`, `Presets`,
`SynthKits`, velocity rendering, the host engine picker and the CLI dispatch
Lacquer through the existing recipe and FX chain paths.

The starter kit and generic export pipeline use the same rendered engine.
MPC hardware listening is a separate acceptance step from JVM export validation.

## Reproducing the audition

```sh
./gradlew --no-daemon test
./gradlew :synth:generateLacquerAudition
./gradlew :cli:run --args="synth LACQUER KEY --all --out /tmp/lacquer"
```

`testkit/lacquer-audition/` contains the listening page, real engine-rendered WAVs,
the audition manifest and numerical evidence. The generator records native and
matched samples separately. Loudness matching follows `AuditionLevel` so a
gain change does not decide a macro comparison; solo layers are independently
matched for audibility, with their native role levels available as evidence.

The kit generator exports the original sixteen dry starter pads as a native
MPC 3 `.xtd` and an MPC 2 `.xpm` twin in `SnipSnap-Lacquer-Kit.zip`. Its eight
pentatonic pads and eight reference recipes retain their original ordering.

`SnipSnap-Lacquer-Bank.zip` contains three twelve-preset programs, each with an
MPC 3 track and MPC 2 twin. Together they include every complete factory recipe:

| Program | Voices | Presets |
| --- | --- | --- |
| Keys / Glass | KEY, GLASS | 12 |
| Brass / Bass | BRASS, BASS | 12 |
| Pluck / Silk | PLUCK, SILK | 12 |

These drum programs play each whole sample once, including samples of held
material; they do not encode sustained keygroups or attack-plus-loop regions.

The listening matrix covers notes and velocities, all five timbral controls,
independent layer solos, RELIEF × CONTOUR, RELIEF × SHEEN, BODY × GLUE,
SHEEN × GLUE, all-high combinations and difficult held cases. Automated checks
cover reproducibility, pitch, finite-state and raw-mix bounds, macro activity,
classification, model serialization, routing and measured loop seams. Listening
still decides whether these numerical properties add up to a playable instrument.

Frozen complete-recipe fixtures protect the eight references. Within each voice,
the expanded recipes are compared at a common root, velocity, neutral TUNE and
finite HOLD after matching levels, so transposition, gain and loop mode alone
cannot establish variety.

## Physical device bench

The following listening checks require actual devices and remain unverified:

1. On Android, open LACQUER and confirm six presets under each voice. Compare
   original and new recipes, edit TUNE, RELIEF and GLUE, switch finite/HOLD, and
   check rapid edits settle to the latest requested tone.
2. Save and reload a new custom-profile preset, send it to a pad, regenerate it,
   and relaunch. Check its source character, layer arrivals and complete tail.
3. On MPC, import all three bank tracks and MPC 2 twins. Confirm twelve named
   pads per program and trigger every held recipe; held samples should play once.
4. Import the original starter and confirm sixteen pads: eight pentatonic notes
   followed by the eight references. Listen for clicks, missing late layers,
   clipping and stuck notes. Record device models and firmware with the verdict.

The supplied version 0.1 engineering spec is retained at
[`superpowers/specs/2026-10-11-lacquer-engine-engineering-spec.md`](superpowers/specs/2026-10-11-lacquer-engine-engineering-spec.md).
