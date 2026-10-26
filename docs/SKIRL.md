# Skirl: rolling-disk prototype

The characteristic gesture is **knock → flutter → whirr → shudder → ringing stillness**.
ROLL and WHIRR turn solved rigid-disk motion into note-tuned disk and support modes.
Model 1 provides the finite gesture; model 2 adds an authored acoustic HOLD.
Current model 3 exports several real captures so the resulting sample files as a loop.
The owner responded positively to both Python auditions.
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

HOLD defaults to zero. Below `.99`, it adds zero through six captured cycles, selected
by `round(6*HOLD/.99)`, before the original shudder and ringing tail resume. A cycle
is approximately `.56–.61 s` in the calibrated domain, aligned to a whole number of
the requested note's periods. The small interval near zero still selects zero extra
cycles. At `.99` and above, `render` returns one repeating acoustic cycle and declares
LOOP. Current exports retain at least three captures and exceed the generic classifier's
1.5-second loop boundary, typically about `1.79 s` at the defaults. The primitive texture
still repeats about every `.6 s`. `renderHeld` returns the original attack plus the settled
multi-capture span with a nonzero
`KeyNote.loopStartFrame`, ready for the existing sampler-loop export shape. It does not
add a new keygroup/export command or phone picker entry.

The held gesture is **knock → rolling texture → captured sustain → repeat**. It is an
authored, externally replenished acoustic capture. The dissipative mechanical trajectory
still ends, and its finite work ledger funds only the original gesture. It does not
fund repeated captured sound. Intermediate HOLD returns to that original finite runout;
the loop's top step has no terminal shudder or tail fade.

Saved patches now default to model 3. Model 2 retains the original short capture and its
exact audio and held markers. Its short bass files can be guessed as KICK/TOM by the
generic classifier, which ignores sampler-loop metadata; the current longer export
addresses that filing issue without changing the texture or the shared classifier.
Explicit model-1 patches remain supported, retain
their finite seed and sound, and still refuse every nonzero HOLD, including tiny positive
JSON numbers before Float conversion. Model, MIDI and velocity remain required replay
fields. The two `SkirlPresets` recipes remain outside the factory roster. The phone
picker, starter kit, reviewed held presets, four additional handoff voices and 36-preset
library are deferred.

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

Generate the current HOLD pack without overwriting the earlier finite audition:

```sh
./gradlew --no-daemon :synth:generateSkirlHoldAudition
```

It writes `testkit/skirl-hold-audition/`, with finite/extended/loop comparisons, attack-plus-
loop exports, eight-pass listening montages, exact recipes, sampler-loop metadata,
independent preceding-cycle diagnostics, common-gain WAVs and an offline listening ZIP.
CLI replay of a top-step HOLD patch also embeds its tuned MIDI root and full-cycle
forward loop in the WAV's existing RIFF `smpl` metadata. Finite CLI WAVs keep their
earlier metadata-free encoding.

For example, replay the saved repeating ROLL recipe after generating that pack:

```sh
./gradlew --no-daemon :cli:installDist
cli/build/install/cli/bin/cli synth SKIRL ROLL --patch testkit/skirl-hold-audition/recipes/roll-loop.patch.json --out /tmp/skirl-held-roll
```

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

`SkirlHold` captures the already-rendered modal signal at 4×, anchored near a real coarse
contact in the middle of the solved runout. A smooth unity-sum overlap blends the actual
continuation back into the excerpt's start. It neither adds a new event clock nor retunes
the physical solver. Boundaries are planned in output frames before multiplying by four,
so every cycle keeps the same decimator phase. The normal house output chain conditions
consecutive captures continuously. Current exports condition two settling captures,
a genuine preceding span and the exported span, then a true following capture for sinc
lookahead. The two independent spans must pass both the 256-frame seam metric and a
whole-span difference bound of `1e-3`. Lookahead is discarded after conditioning. No closing
samples are copied to manufacture a zero seam. Silent velocity-zero loops explicitly
report zero difference rather than divide by zero.

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

Historical model-1 default C3, full-velocity render timings in the earlier Linux JVM:

| Voice | Cleared mechanics cache, JVM warm | Warm median, seven renders | Warm range |
|---|---:|---:|---:|
| ROLL | 195.81 ms | 148.85 ms | 136.51–156.17 ms |
| WHIRR | 181.96 ms | 160.73 ms | 152.94–176.69 ms |

Those measurements preceded the performance changes. The twelve-entry mechanics cache
excludes pitch, EDGE, PLATE and acoustic velocity; synthesis and output conditioning
run every time. The current optimization skips filtering samples already defined as
silent REST and avoids repeated normalization/bounds work inside the exact power-of-two
resampler. The measured comparison and portable reproduction drivers are under
[`native/performance`](../prototype/skirl/native/performance/README.md). See that report
for current JVM costs against the proposed 50 ms warm target. HOLD still renders the
finite modal signal before capturing it, so it does not turn synthesis into a cached
sample lookup. Phone latency and Kotlin owner listening remain open. An opt-in device
diagnostic and exact procedure are in [`SKIRL_ANDROID_BENCHMARK.md`](SKIRL_ANDROID_BENCHMARK.md).
The harness has not been Android-built or run here because this environment has no SDK
or device. Exact historical benchmark,
source hashes, gate counts and artifact checks are under
[`prototype/skirl/native`](../prototype/skirl/native/README.md).

In three paired, warmed runs on the same current container, the median of run medians
improved from `135.54` to `105.33 ms` for ROLL and from `153.73` to `127.54 ms` for WHIRR
(22.3% and 17.0% less time). Each run discarded five warmups and measured fifteen renders
per voice. Unrelated host scheduling is uncontrolled, and both still miss 50 ms. These
are finite default C3 renders, not HOLD or phone timings. All 106 frozen finite fixtures
retain identical production Float arrays, conditioned prelevel arrays and acoustic
diagnostics; model-2 HOLD-zero also retains the old finite seed.

The initial HOLD gate checked 36 register/material/touch cases at a fixed `.10–.40 s`
window: worst root error `19.114 cents`, minimum confidence `.8387`, maximum absolute
DC `7.1e-9`. This is a windowed measurement, not proof of an unvarying perceived pitch.
Both actual preceding-cycle seam and whole-cycle difference settled to zero in Float;
a deliberate disturbance fails both unchanged thresholds. Loop joins also pass separate
local step, slope and RMS-dip guards. The pack records additional fixed-window and full-
cycle estimates, including refused estimates, rather than treating one passed window as
universal detection. Native listening remains necessary to judge the captured modulation.

In the final 18-clip pack, 35 of 36 fixed-window estimates and all 12 whole-loop estimates
returned a root. A 24-window phase sweep of soft C2 captures returned 18 estimates;
the other six are explicitly recorded as absent. Returned phase estimates stayed within
`24.054 cents`; primitive-cycle estimates stayed within `15.204 cents`; estimates over the full exported
span stayed within `22.229 cents`. WHIRR's refused late
windows retain a substantial near-root autocorrelation peak, but it falls below the
detector's relative threshold. This is a measurement limitation and an audible texture
to judge, not grounds to claim an invariant pitch at every instant. Bounded `.9 s` and
`1.2 s` capture experiments improved some notes and worsened others, so the original
capture remains. Detailed alternatives and waveform hashes are in
[`native/hold`](../prototype/skirl/native/hold/README.md).

Portable sources, reports and exact feedback are under
[`prototype/skirl`](../prototype/skirl/README.md). The
[engineering design](superpowers/specs/2026-10-23-skirl-engine-design.md) records equations,
limits, integration and the source handoff.

## Why it is built this way

- Separate motion and pitched modes let agitation rise while the requested note survives.
- A shared input budget preserves a restrained finish when contacts overlap and accelerate.
- HOLD captures the rolling timbre and lets the finite ending return at intermediate settings.
- Separate model and capture ledgers keep repeated sound from masquerading as passive mechanics.
- Consecutive filtered cycles and real lookahead protect the sampler join without copied seam samples.
- Exact resampling and silent-tail work removal speed rendering while preserving older finite audio.
