# Skirl Engine: finite prototype engineering design

Model 1 · 23 October 2026

Status: two finite Kotlin voices verified by 5,994 JVM tests and a 106-case native
audition. Warm defaults miss the 50 ms target; Kotlin owner listening and phone latency
remain open. Source handoff: **Skirl Engine Engineering Specification
v1.0, 20 October 2026**, supplied by the owner. This records the smaller authorized milestone.
The handoff's full voice/preset scope remains future work. Model-2 acoustic HOLD and
exact render optimizations are recorded in the [26 October extension](2026-10-26-skirl-hold-and-performance.md).

Repository context: initial review `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`; Kotlin baseline
`91384fa189a68c4993c8e359af6b748412bf95c3`. APIs below were checked in the working tree.
See also [SKIRL.md](../../SKIRL.md) and [portable reference](../../../prototype/skirl/README.md).

## 1. Gesture and landing

The characteristic gesture is **knock → flutter → whirr → shudder → ringing stillness**.

ROLL and WHIRR share a mechanics-to-acoustics engine. ROLL uses a larger launch angle and
coarser opening; WHIRR uses a shallow launch and denser runout. MIDI tunes acoustic modes,
independently of mechanical rotation. PITCHWHEEL supplies the finite-contact/model-version
precedent; MERCURY supplies the pitched modal-material precedent. Skirl adds traveling
rim/support contact organized by a losing-energy rolling trajectory and a finite exit.

No samples or FX are required. This milestone omits elastic deformation, air film, slip
continuation, prediction of real final impacts, HOLD, the remaining four handoff voices
and the full preset/kit/app rollout. Those sections of the handoff are deferred so the
next listening gate assesses two finite gestures.

## 2. Architecture and contract

```text
voice, LEAN/BRAKE/COLLAPSE → physical parameters
    → SkirlDynamics [12-entry cache: complete physical parameters/model]
    → 1 kHz inclination, contact/rim phase, load, resistance-work descriptors
    → SkirlAcoustics: phase crossings → bounded source → disk/support modes
    → 176.4 kHz mono → Tide.bandLimit → Dsp.decimate → mean / 20 Hz DC removal
    → Dsp.levelTo(MELODIC_LOUDNESS_TARGET) → Dsp.fadeTail → 44.1 kHz Snip
```

Mechanics is independent of pitch, EDGE, PLATE and acoustic velocity. Cache parameters
include geometry, launch, resistance, terminal threshold, time scale, model and solver
settings. Acoustic states never feed forces back. `Dsp.seedFor("SKIRL", model, voice)`
seeds a periodic body-coordinate map built from 1024 `Dsp.Noise` values. Its acoustic
polarity is sampled at actual contact events; transposing does not rotate the surface.

## 3. Literal reduced mechanics

World vectors use upward `k`; `[w,x,y,z]` rotates body vectors into world coordinates.
For cylinder mass `m`, radius `R`, thickness `h`, axis `n`, `s=sin(alpha)` and `c=n·k`:

```text
Iaxis = m R²/2; Itransverse = m(3R²+h²)/12
r = (R c/s − h/2)n − (R/s)k
nDot = omega × n; rDot = D r(n)[nDot]
vCOM = −omega × r
aCOM = −omegaDot × r − omega × rDot
[Iworld + m(|r|² Id − r rᵀ)] omegaDot =
    m r × (g k − omega × rDot) + torque
    − (Iaxis−Itransverse)(omega·n)nDot
Fcontact = m(aCOM + g k)
```

This eliminates no-slip velocity and contact height rather than correcting them after an
unconstrained step. A direct positive-definite 3×3 solve determines angular acceleration.
Quaternion readout uses its norm continuously; raw norm error remains diagnosed. No
velocity rescaling manufactures energy agreement. Planar COM position and resistance work
also integrate. Python uses DOP853; Kotlin uses error-controlled Dormand–Prince 5(4), with
steps ending at descriptor times. The selected rim point changes with orientation;
`rDot` is not `omega×r` for one permanently contacting material point.

The exact conservative stationary-COM launch family, with `a=h/2` and
`b=R cos(alpha)−a sin(alpha)`, is:

```text
Omega² = m g b / {sin(alpha)[Itransverse cos(alpha)−Iaxis(a/R)sin(alpha)]}
omega = Omega[k − (b/R)n]
torque = −mu m g R omegaHorizontal / sqrt(|omegaHorizontal|²+.1²)
```

Resistance does nonpositive work. Geometry `.04 m` radius, `.01 m` thickness, `.3 kg`
mass, gravity `9.81 m/s²`, and available static friction `.8` are design assumptions.
No particular real disk/support pair was measured.

Contact and axis azimuths are unwrapped separately. The body-frame rim coordinate uses
`rotationTranspose*r`; transformed face-marker phase and `omega·n` are separate diagnostics.
Every descriptor and event uses the same fixed physical/musical time scale of 1.

## 4. Safe control contract

All controls are 0..1. Angles below are degrees; `a` is the initial angle.

| Voice | Initial `a` | `mu` | Terminal angle | Default LEAN / BRAKE / COLLAPSE |
|---|---|---|---|---|
| ROLL | `18+8*LEAN` | `.006+.004*BRAKE` | `.42*a−1.1+2*(1−COLLAPSE)` | `.55 / .40 / .65` |
| WHIRR | `8+4*LEAN` | `.004+.002*BRAKE` | `.42*a−1.4+1.2*(1−COLLAPSE)` | `0 / .20 / 1` |

The original broad independent cube is narrowed because the original default launch at
22.85° with a 3.1° exit exceeds assumed friction at about 6.58°. Even the curated 7° exit
required about `.748`. The new launch-dependent bands are authored before rendering;
they never relabel a failed run as a successful finish. Old success/failure cases remain
independent fixtures.

| Control | Default | Mechanism and required probe |
|---|---:|---|
| TUNE | `.50` | Relative acoustic note offset; root/pitch across the register |
| LEAN | voice table | Initial inclination and compatible launch; opening rate/duration |
| BRAKE | voice table | Positive-floor resistance; whole-trajectory energy and duration |
| EDGE | `.40` | Pulse width and fine-feature share; raw/matched sweeps |
| PLATE | `.50` | Support spacing, damping and transmission; root/material sweeps |
| COLLAPSE | voice table | Chosen threshold and shudder character; include actual exit |
| HOLD | `0` | Nonzero refused; no loop or seam acceptance claim |

LEAN×BRAKE changes the trajectory; BRAKE×COLLAPSE changes useful evolution;
EDGE×COLLAPSE changes the finish; EDGE×PLATE changes ringing detail. MIDI 36–84 and
velocity are separate saved fields. TUNE spans 24 semitones, clamped to the supported
final note range. Velocity changes acoustic energy/hardness while reusing the launch.

## 5. Excitation, budgets and endings

One coarse feature and up to three quarter-cycle fine features follow measured contact
phase. A bounded inclination map exposes fine detail; EDGE redistributes their budget.
Body-frame rim and support-azimuth maps change routing weights. A periodic 1024-value
`Dsp.Noise` rim map is interpolated at the body-frame coordinate and reduced to signed
acoustic polarity at each measured event. This decorrelates the pressure comb so the
requested low root need not follow a contact harmonic. It is acoustic readout, not a
signed mechanical force. No independent accelerating clock, random event scheduler or
continuous noise floor is added.

For a coarse interval, assigned squared-input budget is
`B=13*max(deltaResistanceWork,0)*clamp(Fnormal/(1.3*m*g),0,1)*velocity²`.
The coefficient 13 is an acoustic shape choice. Fine contacts share this budget. For a
routed unit pulse with energy `q` and signed cross term `c` against the accumulated source,
incremental energy is `q*A²+2*c*A`. For `c>=0`, the positive root is evaluated as
`B/(sqrt(c²+q*B)+c)`; for `c<0`, as `(sqrt(c²+q*B)−c)/q`. Amplitude is also capped by
`sqrt(B)`. Both routes share this budget, including reinforcing/cancelling overlap and
partial pulses. Cancellation may reduce measured source energy without changing the
assigned budget ledger.

Pulse-width endpoints are note-aware: `soft=min(.006,.75/hz)` and
`hard=min(.0007,.20/hz)` seconds. Actual width is
`soft*(hard/soft)^EDGE*(1.6−.6*velocity)`. This keeps the acoustic root in the main pulse
spectral lobe at high notes and preserves the hardness/touch range. It changes excitation
shape, not the mechanical trajectory or measured rolling event times.

Chosen-threshold exit creates a 40–200 ms transition and five shudder inputs sharing
`min(13*remainingEnergy*(.10+.08*COLLAPSE)*velocity², .05*assignedRollingBudget)`.
The 5% bound refers to assigned rolling budget, not the smaller measured rolling energy
after signed cancellation. Production rejects incomplete trajectories; research fixtures
can render failure and horizon exits with 40 ms withdrawal and zero shudder budget. Source-filter
state is withdrawn before REST; REST input is exactly zero. These are source-energy proxy
units, not calibrated radiated joules or impulses fed back into mechanics.

Six disk modes include a root, second harmonic and metallic upper modes; five support
modes retain their root while PLATE changes upper spacing, damping and transmission.
Frequencies stay fixed within a render. `Modes.ring` uses per-mode
`sin(2*pi*f/internalRate)` gain compensation for its unnormalized recurrence. Excitation
passes sixth-order 8 kHz Butterworth filtering using `Dsp.TptSvf.lowPassInPlace` before
the house output stage. A limiter does not substitute for internal bounds.

## 6. Cost, validity and integration

The physical horizon is 12 s; solver cap 250,000 RHS evaluations; nominal maximum step
4 ms; descriptor rate 1 kHz; maximum contact rate 200 Hz; relative/absolute tolerances
`2e−9 / 2e−11`. Normal-force, friction, finite-state, rate and work-limit exits stay flagged.
Cancellation is checked during mechanics and synthesis.

The native rendering target is 50 ms, within the house's tens-of-milliseconds rule.
Standalone default C3/full-velocity measurements after the complete gate were:
ROLL 195.81 ms with cleared mechanics cache and 148.85 ms warm median;
WHIRR 181.96 ms with cleared mechanics cache and 160.73 ms warm median.
Each voice had three JVM warmup renders and seven warm repeats; cold means cleared
mechanics cache, not process startup. Both miss the target. Cache reuse removes repeated
mechanics for note/material/touch changes, but audio synthesis/conditioning still run.
Phone latency remains unmeasured. Exact samples and separate diagnostic stage timings
are in `prototype/skirl/native/benchmark.json`.

Checked integration surfaces: `Skirl.kt`, `SkirlDynamics.kt`, `SkirlAcoustics.kt`,
`SkirlPatch.kt`, `SkirlPresets.kt`, `Patches.fromJsonValue`, `Velocity.macroSpecsFor`,
`Velocity.touchedRender`, CLI `SynthCommand`, shell `Spread` and `UserPresets`.
`SkirlPatch` saves model, MIDI, velocity and macros; seed context is derived.
`PatchTypes.PROTOTYPES` keeps recipes outside the reviewed factory roster. There is no
`siblingPatches` API. `Keys.MAX_SEAM_ERROR` exists but cannot qualify unimplemented HOLD.
The generic classifier's private 1.5 s threshold can label long audio LOOP; SKIRL declares
finite pitched intent TONAL, with real-render drum-exclusion checks required.

## 7. Probes, listening and next gates

Mechanics probes cover launch, geometry/inertia, independent constraint differences,
work balance, normal/friction margin, phase separation, convergence, Python fixtures,
deterministic cache behavior and named exits. Audio probes cover shared overlap budgeting,
REST silence, terminal/failure distinction, determinism, root, peak/DC, macro activity and
interactions, velocity, aliasing and JSON/recipe replay. Source reports and scripts under
`prototype/skirl` regenerate the earlier comparison WAVs without tracking large audio.

Native listening should compare raw/matched output, coarse/fine contact, complete/truncated
runout, notes/touch and isolated terminal behavior. Root loss needs register/material
probes; terminal thumps need finish-only comparisons; insufficient density evolution needs
coarse/fine comparisons; hidden late energy needs pre-resonator budget checks.

The next gate is owner listening to the native two-voice audition. Render latency
still needs work against the 50 ms target. HOLD with a real exported seam, reviewed
voice/preset expansion, kit and SDK-verified phone picker remain future milestones.
The complete JVM repository gate passed with 5,994 tests and no failures or skips.
The original serial attempt was interrupted by an environment restart; the replacement
used three isolated synth test workers with unchanged assertions and heap limits.
The exact invocation and worker helper are in `prototype/skirl/native/README.md`.

## 8. Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| Reduced model, APIs and current integration | Verified by reading | Named repository context and files above |
| 84 complete trajectories / 420 exits; 1.873–5.250 s | Measured in Python | `prototype/skirl/safe_domain_report.json` |
| Friction ≤.67513; normal ≥2.80064 N | Measured in Python | Same finite sample; available friction .8 assumed |
| Relative work residual ≤7.83e−9; tighter time change ≤3.20e−8 s | Measured in Python | Same report; not Kotlin evidence |
| 31 focused native Skirl tests; complete 5,994-test JVM gate | Passed, exit 0 | `prototype/skirl/native/verification.json`; nine JVM modules, no failures or skips |
| 20 default C2–C6 soft/firm renders plus eight later/middle low-WHIRR windows; worst 19.157 cents | Measured in Kotlin | `SkirlTest` default register/velocity matrix |
| Minimum loudness .18339999; maximum absolute DC 1.693e−6 | Measured in Kotlin | Same default matrix |
| 24 EDGE/PLATE/register corner renders; worst root 19.753 cents | Measured in Kotlin | `SkirlTest` material/register corners |
| 4×/8× normalized spectral band-mass L1 ≤.000284405; stress 15 kHz–Nyquist mass ≤2.232e−7 | Measured in Kotlin | `SkirlAcousticsTest`, shared short trace, MIDI 84/108 and EDGE 0/1; not a direct alias-energy measurement |
| Final 106-case audition / 212 early-tail pitch windows; worst 18.342 cents, confidence ≥.7974 | Measured in Kotlin | `prototype/skirl/native/matrix_quality.json`; no quality outliers |
| 106 production WAVs identical across separate JVM runs | Verified by file hashes | `prototype/skirl/native/verification.json` |
| Warm defaults ROLL 148.85 ms / WHIRR 160.73 ms against 50 ms target | Measured in standalone JVM run | Target missed; exact cold/warm samples in `prototype/skirl/native/benchmark.json`, phone unmeasured |
| Geometry, resistance, texture, material tables and shudder | Authored / proposed | No physical-measurement claim |
| First Python audition | Heard, positive | “Sounds pretty cool”, 2026-10-22 |
| Second Python audition | Heard, positive | “Sounds good, what's next? Use Luthier”, 2026-10-23 |
| Kotlin audio and revised control ranges | Listening pending | No favorite clip or Kotlin approval inferred |

Research context: [Speeding to a stop](https://doi.org/10.1103/PhysRevE.66.045102),
[Rolling and slipping motion of Euler's disk](https://doi.org/10.1103/PhysRevE.69.056610),
[Imperfections, impacts, and the singularity of Euler's disk](https://arxiv.org/abs/1706.10205).
These motivate separated motion, dissipation and final contact; they do not validate this
resistance/readout law. Integrator reference: Dormand and Prince, *JCAM* 6 (1980), 19–26.

## Why it is built this way

- Contact phases organize density while fixed pitched modes preserve the requested note.
- A shared overlap-aware input budget keeps a sharper finish from becoming an uncontrolled hit.
- Narrowed launch/exit bands retain rolling margins; two voices and refused HOLD keep the next listen assessable.

## As built: first Kotlin milestone

Two finite voices, separated dynamics/acoustics/output units, versioned patch replay,
provisional recipes and CLI/shell paths are present. Six voices, 36 presets and HOLD remain
deferred. Revised safe controls and seeded signed acoustic polarity differ from the heard
Python curated settings; native output therefore needs a new listen. The shared signed
quadratic accumulator preserves the assigned input budget while accounting for cancellation.

Focused native Skirl tests: **31 passed**. Default C2–C6 soft/firm pitch and low-WHIRR
windows: **worst 19.157 cents**; EDGE/PLATE/register corners: **worst 19.753 cents**.
The short-trace 4×/8× spectral comparison passed; MIDI 108 is an out-of-range stress case,
and its band statistics do not measure alias energy directly.

Full JVM gate: **5,994 passed, exit 0**. Final audition: **106 cases / 212 pitch windows
passed**, with all 106 production WAVs identical to a separate JVM preflight. The
9.18 MB selected ZIP includes eight native clips and both matched Python/Kotlin pairs;
its HTML embeds the manifest for local opening. Warm default render medians were
**148.85 ms ROLL / 160.73 ms WHIRR**; the **50 ms target is not met**. Phone latency is
unmeasured. Owner Kotlin listening: **pending**.
