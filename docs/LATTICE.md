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

The [published audition](https://snipsnap-lattice-audition.bertcalm.chatgpt.site)
retains the source choices and comparison studies. R3 adds native instrument and
HOLD examples, a native patch download and a dry sixteen-pad MPC starter. Earlier
R1 and R2 listening evidence remains preserved.

## Native patch and controls

`LatticeVoice.WARM`, `PLUCK` and `HOLLOW` select the three source profiles from the
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
verification. Long source motion and changing gate geometry remain a separate
milestone; this HOLD implementation supports static settings.

## Render and replay

Generate R3 into a fresh directory so older assets cannot silently enter its ZIP:

```sh
./gradlew --no-daemon :synth:generateLatticeAudition \
  -PlatticeOutput=/absolute/fresh/lattice-audition-r3
```

The generator writes real Kotlin WAVs, recipes, waveform peaks, measurements,
source provenance and downloadable packs. New native and held examples lead the
page; the accepted short source phrases, 9.6-second restoration proof and repeated
A/B studies remain available. The proof always retains common gain. Only the
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
  clearer comparisons. New native voicings and held loops are awaiting hearing.
- **Verified in source:** R1 grouped harmonics 1–3 under COLOR's unchanged
  geometry; model 2 leaves only the fundamental there. Native patch routing,
  three voice identities, the factory roster, starter and settled-HOLD algorithm
  have now been implemented. Build and numerical validation are separate checks.
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
  58.94–104.13 ms in the review pack and 65.92–146.81 ms in the final regeneration.
  These measurements describe those three exported loops on this
  host, not every supported note or a phone render-time guarantee.
- **JVM status, 2026-10-24:** the full Gradle JVM gate and final audition generator
  completed with exit code 0. All 6,130 tests across nine modules passed, including
  3,228 synth and 1,275 shell tests, with no failures, errors or skips. The test
  runtime was OpenJDK 21.0.12.1; Java fixtures used the installed Temurin 21.0.13
  compiler. Seventy-six focused native checks had also passed before the upstream
  merge. Final regeneration retained all twenty-one published R3 WAVs byte for
  byte. Android compilation remains separate because this host has no Android SDK.
  Physical phone editing/playback and MPC import/playback
  have not been run; the new factory bank and HOLD voicings await hearing.

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
- Start with a small dry bank and playable pad row so the next listening verdict
  can judge useful instrument positions before adding moving geometry.
