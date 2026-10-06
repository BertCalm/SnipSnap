# THAW

THAW ports the supplied [engine specification](superpowers/specs/2026-10-03-thaw-engine-design.md)
into SnipSnap. It is an imaginary instrument with deliberately compressed material
timings, not a validated simulation of ice.

## Playing and saving

The SYNTH picker offers BRITTLE, RUNNER, MELT, CHANNEL, FROST and SHEET.
Each voice has two initial factory presets. The engine is dry: the plate and
contact model supplies the transformation before any optional rack effects.

| Control | Behavior |
| --- | --- |
| TUNE | Requested root, snapped in semitones from C3 to C5 |
| CONTACT | Runner pressure, articulation, footprint and loading |
| HEAT | Contact heating and the powered runner heater |
| FREEZE | Cooling, cold contact and constrained restoration |
| CHANNELS | Resting acoustic linkage, capacity and delayed liquid loading |
| THICKNESS | Modal spacing, mass, contact compliance and thermal response |
| HOLD | Longer finite gestures; its top step returns settled sustain material |

Velocity scales gesture energy without changing the saved CONTACT setting.
Every independently rendered pad begins from its own frozen state; pads do not
share thermal history. Patches round-trip through `Patches` and `PadRecipe`,
including the existing optional `FxChain`. `SynthKits.thaw()` and FRESH TAPE's
THAW starter supply sixteen dry pads in C minor pentatonic, with every voice
represented and two held sounds.

SnipSnap's drum-pad sample format has no attack-plus-loop-region field. A held
THAW buffer contains settled material without the initial cold attack. The
drum program plays that complete buffer once; the audition page's Repeat
option lets the wrap be heard repeatedly.

All finite notes exceed the classifier's 1.5-second length threshold, so
SnipSnap files them as LOOP too. That existing duration classification is
separate from HOLD's sustained, repeating buffer.

## Reduced material model

Each plate carries four modal coordinates. Voice shapes select bending-mode
ratios, contact sites, engagement impulses, runner profiles, losses, surface
roughness, channel capacity and constraint strength. Responding fundamentals
are usually 2, 3 and 4 times the requested root; SHEET uses 3/2, 2 and 3.
Three enclosure modes receive mounting reaction. Exact damped rotations and
reciprocal exchanges keep the passive network's energy from growing when the
runners stop. Frozen channels add reactive coupling; liquid weakens that bridge
and increases dissipative exchange and modal loading. Near-frequency links
carry bending-mode energy into answering plates, while weaker off-resonant
mounting links allow the cold upper-mode ring to survive.

The implicit contact law uses runner speed relative to the plate surface.
Temperature and local liquid alter its grip and surface texture continuously;
excess liquid reduces traction. Band-limited microscopic surface slopes change
relative contact velocity while the runner is powered. A short engagement
pulse has a broader modal projection than sustained friction; both drive the
same plate states. The quieter linked runner changes loading independently
of cruise speed, retaining the contact law's singing region. CHANNEL includes
a small register-dependent root compensation for strong contact loading.
There is no separate oscillator supplying the sustain, and the seeded surface
remains the same across macro comparisons.

Enthalpy is bounded from 0 to 1.35 normalized units. The latent interval runs
from 0.35 to 1.05; liquid fraction and temperature are separate derived states.
Contact dissipation and an explicitly powered heater supply heat. Cooling has
a nonzero baseline even at FREEZE zero. Transport queues delay liquid transfer,
accounting for source subtraction and destination capacity; the diagnostic
ledger tracks explicit melting, freezing and drainage. Departing hot liquid
carries sensible heat as well as latent quantity. Only actual phase loss
accumulates stress, and releases spend it with a refractory interval. Fine
releases favor bending modes. Their acoustic energy increment, including
existing modal velocity, is capped by the stress removed for the release.

Finite renders update material state near 1 kHz while smoothing acoustic
coefficients on the 4× path. The output is band-limited, decimated to 44.1 kHz
mono and leveled with `MELODIC_LOUDNESS_TARGET`. Finite gesture and tail lengths
stay within two to six seconds; longer diagnostic tails have explicit bounds.

V1 HOLD uses balanced contact with extra powered refrigeration that keeps the
thin film in a useful singing region. It does not promise a repeated warm/cold
material cycle. Its slow clock follows plate phase, and preroll compares modal,
contact, thermal, liquid, channel, stress, transport and coefficient states in
separate groups. Export requires both compatible state and
`Keys.seamError < 1e-3`; a small seam alone cannot certify a loop.
Held contact narrows its footprint, and the responding held contact stays
below independent pitch capture. Upper modes settle into the dominant plate's
forced response, with bounded extra preroll for slowly equilibrating wet states.
The held support relaxes steady freezing stress below the release threshold;
its rate is bounded from channel capacity and cooling, while transient phase
changes can still release stored stress. Modal coordinates are interpolated
to the same root crossing for the state comparison, removing
sub-sample timing differences. Candidate cuts also match actual sample phase;
the exported seam is measured on the actual audio samples. A loop must contain
nonzero raw audio; rendering rejects a loop that fails certification.

## Audition and verification

```sh
./gradlew --no-daemon test
./gradlew :synth:generateThawAudition
./gradlew :synth:generateThawAudition -PthawFirstListen
./gradlew :synth:generateThawKit
./gradlew :cli:run --args='synth THAW RUNNER --all --out /tmp/thaw-runner'
```

Open `testkit/thaw-audition/index.html`. It includes raw and matched-loudness
pairs, every voice at C3/C4/C5 and three gesture energies, five-step macro
sweeps with companions at neutral, interaction grids, extreme states, causal
ablations, passive tails and held loops. The manifest includes levels,
render costs and loop evidence; diagnostic CSVs expose contact work,
temperature, local liquid, channels, stress and modal energy.
The optional first-listen task writes `testkit/thaw-first-listen/index.html`
with six neutral C4 voices and the twelve factory presets.

The numerical checks establish repeatable, bounded, pitched behavior and
material causality. Phase-independent upper-band and gesture-envelope checks
also reject a voice roster that collapses into the same fundamental after
leveling. They cannot establish whether the proposed instrument
sounds convincing. The owner must judge the cold attack, dry-to-singing
transition, answering plates, refreezing tail and loop behavior. All voice
shapes, preset settings and names remain provisional until that listen.

## Deferred scope

There is no full fluid solver, fracture mesh, external sample asset, stereo
propagation or thermal memory across independently exported pads. The optional
phrase harness is deferred. The reduced model uses normalized quantities and
musical time scales; none of its constants are measured thermodynamic values.
