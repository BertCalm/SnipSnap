# SUTURE

SUTURE ports the supplied [engineering specification](SUTURE_SPEC.md) into
SnipSnap as a dry pitched instrument. Its proposed gesture is a bronze bloom,
stretching cords and wooden eyelets, resisted closure, and a murmuring seam.
The material constants describe an imaginary reduced instrument and require
listening; they are not measurements of bronze or a physical prototype.

## Playing and saving

Six voices share one vessel model: BLOOM, THREAD, CLOSE, MURMUR, STRAIN and
SHELL. Each has two initial factory presets. Every exported pad starts with
its own vessel state. Independently played pads do not share cord tension,
opening position or stitch travel.

| Control | Behavior |
| --- | --- |
| TUNE | Requested root, snapped in semitones from C3 to C5 |
| GAP | Opening distance, release energy and changing geometry |
| STITCH | Bounded closure force, speed and linkage compliance |
| CORD | Elastic links, stretched harmonics and sliding eyelet texture |
| SEAM | Edge clearance, contact roughness and dissipative loading; zero disables contact |
| CAVITY | Enclosure scale, resonances, absorption and aperture response |
| HOLD | Finite tail/drive allowance; the top step supplies a powered repeating cycle |

Velocity supplies finite release energy for a one-shot and changes actual
travel and force in a powered HOLD cycle. It is passed through `Velocity`
and is not an extra saved macro. Patches save the voice and macro map with the
existing JSON version. `PadRecipe` retains the optional `FxChain`.
`SynthKits.suture()` supplies sixteen dry pads, and FRESH TAPE includes a
SUTURE starter.

SnipSnap's pad sample API has no attack-plus-loop-region field. A held
SUTURE buffer contains settled powered material, omitting the initial spread
and release. A long one-shot can also receive the host's duration-based LOOP
classification; that routing is separate from a certified repeating HOLD
buffer.

## Reduced vessel model

The acoustic network has 36 modes: four bronze plates with four modes each,
four cords with three modes each, four short wooden eyelet modes and four
cavity modes. The dominant plate bears the requested root; answering plate
fundamentals use ratios 2, 3 and 4. Upper plate ratios and mounting weights
vary deterministically by voice and requested note.

Modes use normalized displacement and velocity coordinates. Damped rotations
contract their energy norm, reciprocal bridges redistribute it, and implicit
relative-velocity exchanges dissipate it. Geometry changes smoothly alter
the modal coefficients. Changing frequency in these normalized coordinates
does not add a stiffness impulse. The raw network has no feedback limiter or
state-recovery reset.

Two linked gap coordinates run near 2 kHz. A finite note-on spread and release
opens them; a force-limited stitcher closes them. Ringing increases a bounded
dashpot that opposes closing travel, allowing stronger notes to close later.
Cord strain follows gap geometry and preload and reacts on the linkage.
The slow linkage receives the positive contact-force envelope through a
20 ms projection, retaining actual catch resistance across mechanical
update rates.
An internal take-up coordinate follows the actual powered carriage; its
material displacement and gap-driven sliding traverse the same seeded
surface grain. Mechanical geometry, material position and tension interpolate
on the audio clock. A reduced regularized Stribeck contact supplies friction;
there is no separate elastic bristle state. Sliding drives the same cord and
eyelet modes, while closure changes the cavity's resonances, losses and
reciprocal acoustic loading. Aperture also changes plate radiation, making
the measured closing trajectory audible. Near the
closed position, compliant edge coupling and dissipative relative motion
create seam activity. SEAM zero removes those contacts.

The audio and nonlinear contacts run at 176.4 kHz, then use the shared
band-limited decimator to 44.1 kHz mono. Public renders use
`MELODIC_LOUDNESS_TARGET`; diagnostics retain raw branch levels and separate
stored acoustic energy, passive loss, friction input and actuator work.
These are normalized model quantities: the acoustic energy balance is not
a claim of complete physical energy conservation for the slow mechanism.
Friction debits bounded work reserves supplied by measured opening or stitch
work. Measured work is delivered across each control interval at the audio
rate, and material motion interpolates actual mechanism endpoints. Pending
delivery belongs to the complete state and recorded work reserves. Separate
acoustic-input ledgers identify the finite note gesture and powered closure;
unused reserves relax passively. Stationary cords retain
their passive damping while producing no new motion-driven excitation.
Finite gestures end within the host's six-second ceiling. HOLD supplies
periodic mechanical work to the same vessel and uses bounded preroll with
separate acoustic, gap, cord, coefficient and work-store state comparisons.
Held diagnostics also report mechanical work and acoustic input per cycle.
The loop is captured after its complete state settles, with periodic guards
on both sides of the shared decimator; the renderer does not crossfade or
reset the mechanism at the cut.
Held voices begin at a shared late-opening point in the settled cycle.
Carriage and take-up use the same phase offset, giving the host pitch
detector a clearer starting window while retaining the later cord chirps
and the same repeating material. The root-bearing cavity link has a bounded,
pitch-scaled footprint; upper cavity modes retain their stronger loading.

## Numerical evidence

The 53 SUTURE and integration checks pass on the frozen implementation
(`Suture.kt` SHA-256
`e4a6923758cb6b345a551353987765a124dd3ff86ec6fa7581a5aa9a6466bd03`).
The following measurements come from the dry renderer before loudness
matching. They establish numerical behavior; the listening and device
checks described below still require the owner's verdict.

| Check | Measured result |
| --- | --- |
| Requested one-shot roots, six voices at C3/C4/C5 | Worst error 9.34 cents; minimum root power 92.67% |
| Neutral upper modes, BLOOM/THREAD/STRAIN | 8.74% / 4.48% / 6.12% above 1.5 times the root |
| Acoustic resistance | Strong notes close 29.93 ms later; fixed-velocity stored-energy probe adds 95.28 ms; resistance-off difference is zero |
| Passive acoustic balance | Energy falls from 0.02 to 1.15e-25; maximum balance error 4.47e-10 |
| Default raw tails | Worst final 50 ms / early RMS ratio 9.80e-6 (−100.17 dB) |
| One-shot stability probes | Peak 0.1943; maximum normalized acoustic energy 0.03573; zero recovery resets |
| Seven held cases, including both factory loops | Settle in 5–12 cycles; worst complete-state error 7.82e-4; seam 1.40e-5; root error at most 5.15 cents |
| Quiet C5 BLOOM, all timbral controls zero and HOLD one | State error 3.25e-4; seam 7.48e-11; dry and public RMS both 1.20e-7, with exact sample equality |
| Mechanical 2 kHz versus 8 kHz | Vessel band difference 0.504 dB / envelope L1 0.0320; eyelets 0.137 dB / 0.00851 |
| Eyelet power at mechanical-clock harmonics | 0.3765% at 2 kHz; 0.00128% at 4 kHz; 0.0000295% at 8 kHz |
| Nonlinear contact at 4× versus 8× audio rate | Band difference 0.00414 dB / envelope L1 0.00236; identical closure; work, loss and energy deviations below 0.016% |

The original approximately 1 kHz mechanical rate did not meet the sound
convergence limits. Production therefore uses 88 oversampled ticks per
mechanical update (about 2 kHz), checked against 4 kHz and 8 kHz with the
same acceptance thresholds. These diagnostics cover normalized acoustic
energy, not the total physical energy of the slow mechanism.

Physical convergence uses the complete-state and seam limits independently
of signal level. A smooth, low-motion HOLD can be very quiet while still
forming a valid cycle; shared loudness processing leaves signals below its
threshold unamplified. Normal and factory acceptance checks separately
retain their audibility, pitch and powered-work requirements.

## Audition

```sh
./gradlew --no-daemon test
./gradlew :synth:generateSutureAudition
./gradlew :synth:generateSutureAudition -PsutureFirstListen
./gradlew :cli:run --args='synth SUTURE BLOOM --all --out /tmp/suture-bloom'
```

Open `testkit/suture-audition/index.html`, or the smaller first-listen page
at `testkit/suture-first-listen/index.html`. The generator supplies raw and
matched-loudness clips, voice/register/velocity comparisons, five-step macro
sweeps with neutral companions, the five specified interaction grids,
isolated branches, disabled mechanisms, passive tails and difficult held
cases. Generated audio and pages are ignored by Git and can be regenerated.
The manifest identifies the DSP source by SHA-256 and records JVM render
cost and memory observations. Those measurements describe the generator,
not Android device performance.

The full export produces 325 listening cases from 320 engine renders,
650 PCM-24 mono 44.1 kHz WAVs, and 112 finite mechanical CSVs. All 24 held
captures converge. All 36 first-listen clips and 44 held preflight clips
match their full-export counterparts byte for byte. The largest raw peak
across this larger pack is 0.2640.

The recorded full export took 267.0 seconds on Java 17 with a 512 MiB
heap and four visible CPUs, alongside the repository tests. Engine and
raw finishing account for 255.3 seconds across 1,240.5 seconds of returned
audio, with powered-loop preroll included in render time. Per-case costs
exclude file export; these observations are not device benchmarks.

The first-listen task produces 18 cases / 36 clips: all twelve factory
presets and six neutral C4 voices, each with raw and matched versions.
The held-case preflight settles all 22 captures. Quiet BLOOM and SHELL
have raw RMS below the report's optional 1e-5 audibility flag; their
matched clips reach the shared 0.03 window-RMS target. That flag describes
low model output level and is retained in the report.

The owner's sonic verdict is still required. Listen for one connected object:
the bronze pulls the cords, eyelets answer actual travel, vibration delays
the stitchers, and closing changes the cavity and seam. Numerical evidence
cannot establish that identity or exclude an audible change in motion at
the wrap. Names and preset settings remain provisional.

## Deferred scope

V1 does not model full plate deformation, literal knots, tearing, physical
robotics, stereo propagation, shared phrase state or external recordings.
The diagnostic surface stays outside the product macros.
