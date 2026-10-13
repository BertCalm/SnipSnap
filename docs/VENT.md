# VENT

VENT is a first-listening prototype for SnipSnap: a finite sample drawn directly
from an evolving Rössler trajectory. The idea comes from
[OUROBOROS](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/ouroboros_guide.md).
VENT starts with one topology, three intended characters, and twelve dry
comparisons. Its recipes remain outside the factory preset roster while the
owner listens. No musical acceptance is implied by the implementation or its
numerical checks.

## The sound source

The classical Rössler equations are:

```text
dx/dt = -y - z
dy/dt =  x + a*y
dz/dt =  b + z*(x - c)
```

Model 1 uses `b = .2`, `a = .12 + .13*CHAOS`, and
`c = 3 + 5.8*CHAOS`. These are prototype mappings through different orbit
behaviors; CHAOS is not a measured mathematical chaos index. Audio comes from
a projection of X/Y and a compressed Z contribution. The engine adds no audio
oscillator, recorded sample or wavetable underneath the trajectory.

Fourth-order Runge–Kutta integration runs on the standard 4× internal audio
path, with extra model-time substeps keeping each RK4 step at or below `.02`.
A seeded preroll of at least 300 model-time units measures positive-X
crossing intervals. Their mean sets nominal traversal speed for the requested
MIDI note. That calibration gives free motion a pitch reference, not an exact
repeating period.

LEASH periodically corrects X toward zero, with a cosine transition spread
over 8% of the requested note cycle. Correction strength is `LEASH²`; Y and Z
retain their history. This differs from OUROBOROS's described blend of free
and X-reset paths: VENT uses one trajectory with variable periodic forcing.
The forcing encourages a note but does not prove exact pitch or recurrence of
the full state. This audition records a local pitch estimate for each clip.

Projection saturation shapes the audible readout without feeding back into
the differential equations. Two-pole damping, a DC blocker, antialias
decimation, the shared melodic level convention and a short tail fade
condition the output. A nonfinite state, a state beyond the 256-unit bound,
or a negative Z excursion outside the safety tolerance triggers an emergency
reset. The audition refuses every render that needs such a reset; normal
LEASH corrections are counted separately.

## Controls and recipes

ROUND, WIRE and TEAR use the same equation family with different projection,
drive, envelope and default control settings. Their names describe intended
starting characters for listening, rather than verified musical outcomes.

| Control | Behavior |
| --- | --- |
| CHAOS | Moves `a` and `c` through the prototype's regular and irregular orbit regions |
| LEASH | Increases smooth, periodic X correction toward the requested note cycle |
| VIEW | Rotates the X/Y readout and changes the compressed Z contribution |
| DAMP | Increases lowpass damping of fast trajectory detail |
| PUSH | Displaces the actual starting trajectory; velocity changes the displacement and readout drive |
| LENGTH | Selects a finite duration from .18 to 5.4 seconds; `.7` is about 1.95 seconds |

`VentPatch` saves the voice, six macros, MIDI note, velocity, seed and
integration model. Supported notes are MIDI 36–84 (C2–C6); default MIDI 60 is
C4. Velocity is 0–1. Model 1 is the only currently supported model; unknown
models are refused. Patch JSON uses the shared patch version and embeds in
the standard `PadRecipe`, so the dry clip can be reconstructed from its recipe.

The seed selects the preroll's starting history. Note is absent from that seed,
so changing MIDI begins with the same seeded orbit before its traversal speed
changes. With the same implementation, model, voice, seed, macros, note and
velocity, rerenders reproduce floating-point samples exactly. Model 1 records
the integration design; cross-version or cross-runtime bit identity is not
promised without preserving and checking that implementation.

Every sound is finite, including LENGTH at 1. This version has no HOLD mode,
continuous live voice, seamless loop, stereo projection or captured-audio
forcing. Repeating a WAV repeats a captured passage. A future loop needs an
explicit recurrence design, and a future snip-injection experiment needs
separate evidence for its effect on the trajectory.

## Audition

Generate the complete twelve-clip matrix:

```text
./gradlew :synth:generateVentAudition
```

All twelve clips use C4, MIDI 60, velocity .8, seed 17, model 1 and LENGTH .7
(about 1.95 seconds).
VIEW, DAMP and PUSH retain each voice's defaults. Each voice has these four
conditions, with an empty effects chain:

| Condition | CHAOS | LEASH |
| --- | ---: | ---: |
| Anchored | .15 | .95 |
| Chaotic | .8 | .95 |
| Loose | .8 | .4 |
| Free | .8 | 0 |

`testkit/vent-audition/` contains `audio/<id>-native.wav`,
`audio/<id>-matched.wav`, `recipes/<id>.json`, `manifest.json`,
`diagnostics.json` and `audition.zip`. Both audio variants are mono 44.1 kHz
PCM24. Native files are the engine's normal conditioned and leveled output;
they are not the unlevelled internal readout. Matched files use the shared
`AuditionLevel.level`: loudest 200 ms RMS target .03 with a .99 peak guard.
Matching uses one scalar gain per clip and preserves the waveform shape.

The manifest's main peak/RMS/DC/loudness fields describe matched playback;
native metrics and internal raw/state measurements are in the diagnostics.
Pitch uses SnipSnap's autocorrelation estimator with spectral refinement on
a central window up to 250 ms. Its frequency, confidence and cents from C4
are estimates; null means no confident local period was found. A detected
pitch at one moment does not establish pitch lock across the clip, especially
at zero LEASH.

Generation fails on empty/nonfinite/clipped audio, emergency integration
resets, invalid model-time steps, or a deterministic replay mismatch. Every
clip is rerendered directly and through a JSON-roundtripped `PadRecipe`, and
the generator compares the raw floating-point bits. SHA-256 hashes record
native and matched sample buffers, WAV files and recipe files. Git HEAD and
a working-tree dirty flag identify the source context; an uncommitted
prototype must not be treated as the contents of that HEAD commit.

The archive includes only the generated files and any supplied standalone
page assets, never an earlier archive. Publishing the page is a separate
delivery step after generation.

The CLI also renders the three provisional voice defaults without adding
them to the factory preset roster:

```text
./gradlew :cli:run --args="synth VENT ROUND --midi 60 --velocity 0.8 --seed 17 --out /tmp/vent-round"
./gradlew :cli:run --args="synth VENT WIRE --all --out /tmp/vent-wire"
./gradlew :cli:run --args="synth VENT TEAR --midi 48 --seed 42 --out /tmp/vent-tear"
```

These CLI examples use the voice's default macros, so their sounds differ
from the controlled audition matrix. The useful first listening questions
are whether the three characters remain distinct, whether CHAOS changes the
trajectory audibly, and whether reducing LEASH opens useful movement without
losing the sound's body. Pitch measurements and numerical stability support
that listening decision; they do not make it.
