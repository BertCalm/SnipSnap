# Lattice native instrument

Lattice carries one sample-free pitched source through ordered two-component
projection gates. Crossing the entrance and exit removes direct transmission;
intermediate gates restore a route. Four harmonic groups can encounter different
geometry, and actual rejected components excite a separate, bounded resonant
outline. The characteristic gesture is aligned tone → crossed silence → restored
passage → a different balance of passing tone and rejected outline.

The R2 source and comparison direction passed the owner's listening gate on
2026-10-23: “New sounds are better”, then “Clearer, we can proceed to the next
steps”. That verdict authorizes this native instrument milestone. The new preset
bank and HOLD behavior still need their own listening verdict; the original
engineering specification is not declared complete.

## Focused musical gesture

R4 asks a narrower listening question: does a solid pluck becoming a hollow,
ringing trace give Lattice a useful musical character? The opening comparison
plays a plain phrase, then the same notes with moving gates and a changing blend
of passing tone and rejected resonance. Both use the same source, note velocities
and source gain. The ringing tail comes from the existing rejection collector.

The owner accepted B on 2026-10-30: “B sounds cool”, then authorized bringing
that movement into saved patches. TRACE now carries the accepted trajectory in
the native instrument. Its default SPEED .5 and HOLD zero reproduce B; the R4
recording remains the fixed listening reference. Faster, slower and held settings
are extensions of that direction and still need their own listening verdict.

## TRACE: the saved moving voice

Choose LATTICE → TRACE → Ringing Trace. The note begins with a solid pluck,
changes its harmonic balance, then closes the direct path and leaves the
rejected-signal resonators ringing. Its voice, speed, hold, MIDI root, velocity
and synthesis model are saved in an ordinary `LatticePatch` and travel with the
pad recipe through rendering and export.

| Control | Behavior |
| --- | --- |
| TUNE | Relative pitch around the saved root, as on the other Lattice voices. |
| SPEED | .5 is B's original timing. Zero takes twice as long after the first 35 ms; one takes half as long. Pitch and the resonators' natural damping stay fixed. |
| HOLD | Keeps feeding the ringing component longer, adding up to 3.24 seconds of finite effort. The top remains a finite note; it does not loop or strike again. |

TRACE deliberately has only pitch, motion speed and finite HOLD controls. The
accepted gate relationships move together rather than becoming more independent
knobs. SPEED also changes the source envelope's clock so slower gates still have
energy to reject. The first 35 ms remain fixed. Compiled gate and collector
coefficients are cached by SPEED in a bounded eight-entry cache; note, velocity
and HOLD do not create separate geometry plans.
Native TRACE rendering computes the mixed WAV without allocating or conditioning
unused diagnostic stems. Full probes retain both branches and their energy
evidence; tests require the native output to match those probes exactly.

The factory bank adds Ringing Trace, Slow Trace and Quick Trace. The earlier
twelve static patches and sixteen-pad starter remain available. R5 adds a
separate TRACE pad kit and native patch downloads; these finite samples use
ordinary one-shot playback. The native motion is now implemented; physical phone
and MPC listening still require device checks.

Why it is built this way:

- The plain phrase gives the ear a direct reference for what the gates change.
- Gate movement must reshape the passing tone before the branch blend, so the
  example demonstrates more than fading between two sounds.
- The source stops before the exposed tail. That makes the collector's stored
  ringing energy distinct from a separate sustained source or added reverb.
- The existing source gain stays fixed. A result-level boost would conceal the
  losses that connect this example to Lattice's mechanism.

The [published audition](https://snipsnap-lattice-audition.bertcalm.chatgpt.site)
retains the source choices, comparison studies and R4's accepted B. R5 adds the
saved TRACE voice and speed comparisons. Earlier listening archives remain
available alongside the current WAVs, patches and MPC kits.

## Static voices and controls

`LatticeVoice.WARM`, `PLUCK` and `HOLLOW` select the three static source profiles from the
accepted R2 direction. `LatticePatch` saves the voice, macro map, explicit MIDI
root, excitation velocity and model 2. Native roots are MIDI 36–84, default 48;
velocity is finite 0–1, default .75. Editing macros and generating velocity zones
retain the saved root, model and source identity. Unknown macros, invalid values
and unsupported models are refused.

| Control | Behavior |
| --- | --- |
| TUNE | Relative pitch, neutral .5; ±12 semitones around the saved root, rounded to a MIDI note and clamped to 36–84. |
| TURN | Crosses the entrance and final gate orientations. |
| STAGES | Two to sixteen effective gates, with quadratic travel around the first restoration. |
| TWIST | Redistributes the turn along the ordered chain. Two gates have no intermediate position to redistribute. |
| COLOR | Separates group geometry, changing lower harmonics as well as the upper spectrum. |
| REJECT | Convex mix between transmitted tone and the collected rejected outline. |
| HOLD | Below .99, extends finite source effort before the same pluck decay; at .99 or above, returns settled periodic sustain. |

Finite HOLD covers approximately .26–3.5 seconds of source effort, with a further
1.8-second tail budget. It extends one excitation rather than adding strikes.
Scramble remains below the loop threshold. Finite notes are TONAL; full HOLD is
LOOP. The twelve dry factory proposals contain four positions per voice,
including one held position. The starter has eight WARM notes in C minor
pentatonic from C3, five finite source choices and three held choices. Each pad
retains its complete recipe and its actual root-note metadata.

The standard drum-program exporter leaves sample looping disabled. On MPC pads
A14–A16, enable looping across the whole sample for indefinite HOLD; choose
note-on/gate playback if releasing the pad should stop the sound. The supplied
WAVs contain periodic sustain, but those device settings are not preconfigured.

Native patches are registered with common patch decoding, presets, velocity
rendering, the CLI, starter-kit selection and the Android synth picker. This does
not add a separate MAKE INSTRUMENT keygroup workflow.

## Model and level policy

- Model 2 uses twenty-four band-limited harmonics in four coherent groups:
  harmonic 1, harmonics 2–3, harmonics 4–6, and harmonics 7–24. Source phases do
  not change when gate controls or source profiles change within this model.
- WARM rolls off upper harmonics; PLUCK emphasizes a short low-harmonic stack;
  HOLLOW favors odd harmonics. Their spectra are authored choices rather than
  physical instrument models. The R2 source calibration remains fixed for a
  given note, velocity and profile.
- Every gate acts on the component surviving the preceding gates. Partial
  insertion partitions that state, and stable slot IDs retain rejection-tap
  identity across effective-count changes.
- Source gain stays fixed while geometry changes. Per-result normalization
  would restore level after extinction and hide the defining passage gesture.
  `Dsp.levelTo` is therefore not an engine output stage. Separately RMS-matched
  COLOR/TWIST listening files have explicit recipes and remain diagnostic copies.
- Halo excitation comes only from signed rejection. Its collector gain and mode
  stability are bounded separately from the projection invariant. It does not
  feed back to the source.
- Audio remains mono 44.1 kHz with four-times internal rendering, antialiasing
  before decimation and DC removal. External effects are absent.

## Settled HOLD

Static gates and the feed-forward resonant collector form a linear system. HOLD
uses the same source weights, phases and fixed gain, evaluates the actual
`Halo.response` at those harmonics, and forms the settled transmitted/halo mix.
This is the steady response of the existing source and collector, not another
source palette or an unconnected sustain layer.

The loop fits an integer number of root periods into whole output frames near
two seconds, exposing requested and fitted pitch in `HeldProbe`. Geometry and
harmonic transfer coefficients are compiled once per render, outside the audio
loop. The implementation then generates real oversampled audio through the same
band limit, decimator and DC blocker. A half-second conditioning guard precedes
two independently generated periods; postroll keeps the resampler edge outside
the measured periods. The returned sustain has no finite attack or tail fade.
No crossfade, waveform repair or loudness normalization forces closure.

The previous period, loop length and seam error are exposed for numerical
verification. These periodic HOLD loops belong to WARM, PLUCK and HOLLOW. TRACE
uses its finite moving gesture and refuses the periodic-loop renderer.

## Render and replay

Generate the current audition into a fresh directory so older assets cannot
silently enter its ZIP:

```sh
./gradlew --no-daemon :synth:generateLatticeAudition \
  -PlatticeOutput=/absolute/fresh/lattice-audition-r5
```

The generator writes real Kotlin WAVs, recipes, waveform peaks, measurements,
source provenance and downloadable packs. Native TRACE and its speed examples
lead the page; the accepted short source phrases, static native/HOLD examples,
9.6-second restoration proof and repeated A/B studies remain available. The proof always retains common gain. Only the
separate COLOR/TWIST diagnostic copies match each condition's RMS.

Replay an audition recipe with its saved kind and gains:

```sh
./gradlew --no-daemon :synth:generateLatticeAudition \
  -PlatticeRecipe=/absolute/path/recipes/example.json \
  -PlatticeReplayOutput=/absolute/path/replay.wav
```

Audition-harness recipes remain distinct from native `LatticePatch` JSON. Native
CLI rendering accepts factory positions, saved patches, performance overrides
and explicit macro edits, writing the complete `.patch` beside each WAV:

```sh
./gradlew --no-daemon :cli:run \
  --args='synth LATTICE WARM --preset 1 --midi 48 --out /tmp/lattice-warm'
./gradlew --no-daemon :cli:run \
  --args='synth LATTICE PLUCK --preset 1 --macros HOLD=1 --out /tmp/lattice-held'
./gradlew --no-daemon :cli:run \
  --args='synth LATTICE TRACE --preset 1 --midi 48 --out /tmp/lattice-trace'
./gradlew --no-daemon :cli:run \
  --args='synth LATTICE TRACE --preset 1 --macros SPEED=0.75 --out /tmp/lattice-quick-trace'
```

## Reference interpretation

For an aligned source, crossed equally spaced chains with 2, 3, 4 and 16 total
gates have amplitude ratios approximately 0, .5, .649519 and .920906. Their
squares are transmitted powers after entrance alignment. The optical
unpolarized fixture is a separate incoherent average of orthogonal inputs:
powers 0, .125, .2109375 and .424033798. The optical entrance factor is not
applied again to aligned audio.

At COLOR zero with common static geometry and no halo, all harmonics receive the
same scalar gain. Those examples deliberately demonstrate transmission level.
Timbral comparisons require group-dependent geometry; rejection-position
comparisons require the stage-sensitive collector.

## Evidence and validation

- **Heard:** the owner's R1 report rejected the source and found the takes hard
  to distinguish. On 2026-10-23 the owner accepted the improved R2 source and
  clearer comparisons. On 2026-10-30 the owner accepted R4 B: “B sounds cool”.
  That moving pluck is TRACE's default sound reference. New speed/HOLD positions
  and the other native voicings still need their own listening verdict.
- **Verified in source:** R1 grouped harmonics 1–3 under COLOR's unchanged
  geometry; model 2 leaves only the fundamental there. Native patch routing,
  the three static source identities, factory roster, starter and settled-HOLD
  algorithm have been implemented. TRACE adds a fourth voice with the accepted
  movement, a saved SPEED setting and finite HOLD. Build and numerical validation
  are separate checks.
- **R5 focused validation, 2026-10-31:** fifty-one Lattice tests across synth, shell
  and CLI passed with Gradle exit zero. Twenty-six listening recipes replayed
  sample-identically. All twenty-two R4 WAVs stayed byte-identical, and the new
  plain/native TRACE comparison is also identical to the accepted R4 comparison.
  The three TRACE patches and sixteen finite one-shot pad recipes round-tripped;
  MPC 3/XPM containers and PCM24 samples passed artifact checks. Two Android
  instrumentation regressions cover immediate SPEED/save/send and authored-root
  delivery; no Android SDK is available here, so those await SDK-backed CI.
- **Measured previously:** published R1 sustained C4 PCM16 had a power centroid
  of 815 Hz and 10.3% of power above 2 kHz over .25–1.25 seconds. The R2 Kotlin
  sustained-source test measured approximately .33% above 2 kHz. Exported R2
  short-phrase COLOR spectral residuals after gain removal were .451 WARM and
  .499 HOLLOW; matched repeated conditions were sample-identical. These are
  numerical findings, not an assessment of the new factory sounds.
- **Verified R3 review pack, 2026-10-24:** eighteen primary clips and three
  matched copies replayed from twenty-one saved audition recipes. All fifteen
  accepted R2 WAVs remain byte-identical. The twelve native patches, sixteen-pad
  starter, MPC 3 container and MPC 2 XPM twin passed pack checks; source hashes
  matched the bundled files. All twenty-one listening WAVs contained non-silent,
  non-clipping PCM. Desktop and mobile viewport browser checks decoded all of them,
  exercised cue seeking and downloads, and reported no page errors or overflow.
- **Measured R3 HOLD examples:** the three C3 listening examples each contain
  four exact PCM repeats of their native loop segment. Float seam and PCM preceding-cycle errors were
  zero, with fitted pitch offset 0.00462477 cents. Host held-render times were
  58.94–104.13 ms in the review pack and 62.90–98.90 ms in the integrated regeneration.
  These measurements describe those three exported loops on this
  host, not every supported note or a phone render-time guarantee.
- **JVM status before the latest base integration, 2026-10-24:** the full Gradle JVM gate and final audition generator
  completed with exit code 0. All 6,130 tests across nine modules passed, including
  3,228 synth and 1,275 shell tests, with no failures, errors or skips. The test
  runtime was OpenJDK 21.0.12.1; Java fixtures used the installed Temurin 21.0.13
  compiler. Seventy-six focused native checks had also passed before the upstream
  merge. Final regeneration retained all twenty-one published R3 WAVs byte for
  byte. Android compilation remains separate because this host has no Android SDK.
  Physical phone editing/playback and MPC import/playback
  have not been run; the new factory bank and HOLD voicings await hearing.
- **Latest integration:** the branch now includes upstream `7659ed77d`, retaining
  MERCURY's picker entry, ORIGAMI's fold gestures and WICK FLICKER. The full gate
  and generator for this combined state completed with exit code 0 at revision
  `95add83ec`: all 6,152 tests across nine modules passed, including 3,243 synth,
  209 CLI and 1,280 shell tests, with no failures, errors or skips. The synth suite
  used three isolated JVM forks with one common ForkJoin worker per JVM; every
  test and assertion remained enabled. All twenty-one published R3 WAVs remained
  byte-identical. The 6,130-test result above records the earlier tested state.

The accepted finite prototype and first native implementation were developed
against `c5112c507ccb75a8b95313c4ebbe26f8823700aa`. The branch then integrated
upstream `3ecbdb78de41e13f9603cb9cb6b54fb2a93dd934` through merge `f07669d15`,
retaining the newer engine registrations alongside Lattice. These commits
identify source history, not a completed validation result. Each generated
audition records its actual render commit and exact working-tree source hashes;
its source download must match that recorded state.
The published R3 review pack was rendered from
`86a8ffbd6c1f639061f257151d4dd9916b3d49db` and successfully deployed to the existing
audition URL on 2026-10-24. Its review-pack checks do not imply that the broader
JVM gate has completed.
The broad JVM gate remains `./gradlew --no-daemon test`; a passing targeted suite
does not establish that this entire repository gate passed.

## Why it is built this way

- Keep the accepted source and geometry so native integration does not restart
  the listening decision already made.
- Save pitch, velocity and model alongside macros so reopening and editing a
  sound preserves the instrument that produced it.
- Keep common gain so crossing the gates can truly extinguish the source;
  normalization would erase that behavior.
- Derive held sustain from the actual linear source/collector response so it
  can continue without another attack or a new synthetic layer at the wrap.
- Promote the owner's accepted moving example with an exact default comparison,
  then offer speed and finite HOLD as focused extensions of that reference.
