# Skirl: finite rolling-disk prototype

The characteristic gesture is **knock → flutter → whirr → shudder → ringing stillness**.
Model 1 provides two finite voices, ROLL and WHIRR, from solved rigid-disk motion into
note-tuned disk and support modes. The owner responded positively to both Python auditions.
The Kotlin render awaits its own listening review.

ROLL opens at a larger inclination with coarser contacts. WHIRR starts shallower and reaches
a denser valid regime. TUNE sets the acoustic note; contact rate changes articulation while
the mode frequencies stay fixed. Output is dry mono, 44.1 kHz, with standard 4× internal
synthesis, antialiasing, decimation and melodic leveling.

## Controls and scope

Controls are normalized to 0..1. Angles below are degrees; `a` is the initial angle.
Physical and musical seconds have the same scale.

| Voice | Initial angle | Resistance | Terminal angle | Default LEAN / BRAKE / COLLAPSE |
|---|---|---|---|---|
| ROLL | `18+8*LEAN` | `.006+.004*BRAKE` | `.42*a−1.1+2*(1−COLLAPSE)` | `.55 / .40 / .65` |
| WHIRR | `8+4*LEAN` | `.004+.002*BRAKE` | `.42*a−1.4+1.2*(1−COLLAPSE)` | `0 / .20 / 1` |

EDGE changes contact width and the share spent on fine surface features. PLATE changes
support spacing, decay and transmission together while preserving its root. Their defaults
are `.40` and `.50`. TUNE defaults to `.50`; MIDI 36–84 and excitation velocity are separate
saved patch fields. Velocity changes acoustic energy and hardness, reusing the motion.

Contact-width endpoints are note-aware: `soft=min(.006,.75/hz)` and
`hard=min(.0007,.20/hz)` seconds, with width
`soft*(hard/soft)^EDGE*(1.6−.6*velocity)`. This keeps the root within the pulse's main
spectral lobe at high notes while retaining EDGE and touch response. Mechanical motion
and measured contact-event timing are unchanged.

HOLD must be zero; any nonzero value is refused. The two `SkirlPresets` recipes remain
outside the factory roster. The phone picker, starter kit, held instrument, four additional
handoff voices and 36-preset library are deferred.

The older independent 8–35° launch / 7–1° exit ranges contain rolling failures. Revised
terminal bands explicitly depend on launch. Production rendering refuses any incomplete
trajectory. Research fixtures can audition friction, normal-force, rate, solver-work or
horizon exits with excitation withdrawal and no successful shudder.

## Render and audition

From the repository root, render one provisional recipe:

```sh
./gradlew --no-daemon :cli:run --args="synth SKIRL WHIRR --preset 1 --midi 36 --velocity .25 --out /tmp/skirl-whirr"
```

The native audition generator accepts an optional folder of earlier Python WAVs. To
regenerate those references first, install the Python requirements under `prototype/skirl`
and run its portable helper; its output is `prototype/skirl/reference/audio/`:

```sh
python3 prototype/skirl/reference/render_audition.py
./gradlew --no-daemon :synth:generateSkirlAudition -PskirlPythonReference="$PWD/prototype/skirl/reference/audio"
```

Without `-PskirlPythonReference`, the generator omits unavailable Python pairs. Its full
matrix requests 106 native cases under `testkit/skirl-audition/`, with production-level
and common-gain WAVs, recipes, diagnostics, manifest and benchmark. The selected
`skirl-listening-pack.zip` contains eight native cases plus two Python/Kotlin comparison
pairs when those references are supplied. These compare the revised signed acoustic
readout against the heard positive-texture Python version; they are not waveform-identity
tests. Extract the ZIP and open `index.html` directly in a browser: its manifest is
embedded, so it needs no local server.

## Mechanics and sound

`SkirlDynamics` eliminates contact height and no-slip velocity from Newton–Euler dynamics.
It integrates body-to-world orientation, world angular velocity, planar center position
and resistance work. A regularized torque opposes horizontal angular velocity, doing
nonpositive work. The selected rim point has a geometric derivative; it is not one material
point permanently attached to the floor. Acoustics never feeds forces back.

Contact azimuth schedules coarse and quarter-cycle fine features. A separate body-frame
rim coordinate changes transmission and samples a periodic 1024-value `Dsp.Noise` map for
signed acoustic polarity at those actual events. This decorrelates the pressure comb so
the acoustic root can ring without following a contact harmonic. It adds no random event
clock or continuous noise floor and does not change contact forces. Face-marker phase and
axial angular velocity remain separate diagnostics. Inclination exposes fine detail.

One shared disk/support source budget comes from integrated resistance work. Its signed
quadratic pulse accumulator includes reinforcing and cancelling overlap cross terms.
The five-input shudder receives at most 5% of the **assigned** rolling budget, also bounded
by remaining mechanical energy. Cancellation can reduce measured rolling-source energy;
the 5% rule does not compare the shudder against that smaller measured value. REST supplies
no contact excitation; six disk and five support modes decay freely. Source units are a
proxy, not radiated joules.

## Evidence and remaining work

Python calibration checked 84 complete trajectories and 420 terminal exits across five-step
grids, seeded points between grid coordinates and defaults. Rolling duration was
1.873–5.250 s; required static friction was at most `.67513` against assumed available `.8`,
with normal reaction at least `2.80064 N`. Maximum relative energy/work residual was
`7.83e−9`. Two tighter checks changed terminal time by at most `3.20e−8 s`. Finite sampling
supports this limited domain; it does not prove a continuum or describe a measured disk.

All 31 focused native Skirl tests passed. The default C2–C6 soft/firm matrix covered 20
renders plus eight later/middle low-WHIRR pitch windows: worst root error 19.157 cents,
minimum loudness `.18339999`, maximum absolute DC `1.693e−6`. Twenty-four EDGE/PLATE/register
corner renders had worst root error 19.753 cents.

On a shared short trace at MIDI 84 and stress MIDI 108, EDGE 0/1, the 4×/8× comparison's
maximum normalized spectral band-mass L1 difference was `.000284405`; maximum stress-band
mass from 15 kHz to Nyquist was `2.232e−7`. These are spectral comparisons, not a direct
measurement of alias energy. MIDI 108 is outside the production note range.

The complete nine-module JVM gate passed: **5,994 tests, zero failures or skips**.
The final audition covered 106 renders and 212 early/tail pitch windows, with worst
root error 18.342 cents and minimum confidence .7974. All 106 production WAVs matched
a separate JVM preflight byte-for-byte. The selected offline listening ZIP contains
eight native cases and two matched Python/Kotlin comparisons (9.18 MB).

Standalone default C3, full-velocity render timings in this Linux JVM:

| Voice | Cleared mechanics cache, JVM warm | Warm median, seven renders | Warm range |
|---|---:|---:|---:|
| ROLL | 195.81 ms | 148.85 ms | 136.51–156.17 ms |
| WHIRR | 181.96 ms | 160.73 ms | 152.94–176.69 ms |

Both voices miss the proposed 50 ms warm target. The twelve-entry mechanics cache
excludes pitch, EDGE, PLATE and acoustic velocity; synthesis and output conditioning
run every time. Phone latency and Kotlin owner listening remain open. Exact benchmark,
source hashes, gate counts and artifact checks are under
[`prototype/skirl/native`](../prototype/skirl/native/README.md).

Portable sources, reports and exact feedback are under
[`prototype/skirl`](../prototype/skirl/README.md). The
[engineering design](superpowers/specs/2026-10-23-skirl-engine-design.md) records equations,
limits, integration and the source handoff.

## Why it is built this way

- Separate motion and pitched modes let agitation rise while the requested note survives.
- A shared input budget preserves a restrained finish when contacts overlap and accelerate.
- Two voices and refused HOLD keep the first Kotlin review focused on the finite gesture.
