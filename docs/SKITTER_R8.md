# Skitter R8: coupled spectral memory

The characteristic gesture is **touch → source and filter load two spectral memories → their replies reshape the upper body → the phrase breathes and settles, or repeats under HOLD**.

Status: REVIEW → CONCEIVE design and measured implementation, 2026-10-26. Focused engineering admission passes; the owner's R8 listening decision remains pending. Repository base: `bd3f1bdc4ed6480c0bbbea479a6232ec3e396b9c`, whose Skitter files match merged R7 head `385b87a431d4084540fb8d762418bdfe3a51c546`. R7's merge is not a listening acceptance; the owner's earlier positive acceptance applies to R6 LEAD/HOLLOW. Frozen `Skitter.kt` SHA-256: `03425e88f13033df0e00832b0a72dbc354bb0620d8cb3a4606057b52540e71de`.

## Review: what needs to develop

**Try next [A, B].** R7 gives each voice a distinct persistent source body, but another static wave shape or one shared recovery envelope would not establish the requested depth. R8 should let the source and its actual filter response leave different memories, then demonstrate that both memories change later upper-spectrum behavior. Four state variables alone prove neither musical complexity nor four independent audible dimensions.

**Measured motivation, with limits.** A new Python analysis of retained raw PCM24 R7 audition recordings found mean sustained harmonic-spectrum spread of `0.002220` for FLUTTER and `0.005559` for FRAY. Their first centered PCA axes accounted for `98.80%` and `97.28%` of variance. These are measurements of one excerpt, not a listening verdict or a state-dimension estimate. Settings: MIDI 48, velocity `.6`, seed `0`, BUZZ `.55`, SKITTER `.50`, AGITATE `.60`, VENT `.35`, STATIC `.15`, HOLD `0`. Method: note-relative `.5–1.25 s`, 57 wholly contained 8192-sample symmetric Hann windows at 441-sample hops; power within ±`.20 × f0` of harmonics 1–12, normalized by their sum. Spread is mean total-variation distance from the mean harmonic vector. The 185.76 ms window suppresses rapid flutter; these results motivate causal and temporal probes, not an acceptance threshold.

**Disclosed, not fixed [D].** The owner's request concerns sustained depth and complexity. A level change, wider noise bed, extra scoops, or a successful numerical test cannot answer that request by itself. The dry audition must make complete-note, synchronized R7/R8 comparison available at fixed common gain and optional level matching.

## Three directions and the landing

| Direction | Gesture, roster difference, risk and cost |
|---|---|
| **Strong: COUPLE** a four-state spectral bridge | Source upper energy and the actual filter band load separate rotating memories which exchange state and return to the upper source. Clear load/reply/recovery gesture; distinct from reciprocal oscillator FM and pressure-driven phase maps. Moderate spectral/loop risk; fixed work per sample, one engine revision. |
| **Alternate A: MUTATE** two slow charge resources | Let recent source/filter activity deplete and recover two color resources. Cheap, clear recovery; too close to another pair of envelope followers to establish depth alone. Low numerical risk, small implementation. |
| **Alternate B: INVERT** a one-pole band return | Feed a smoothed filter-band measurement back into source color. Cheapest causal return; one dominant response direction and excessive roughness or a moving wah remain likely limits. Small implementation, narrower payoff. |

Strong wins because the two inputs can produce a delayed reply and redistribution rather than merely opening one shared brightness control. This remains a **revision of Skitter's six voices**: the note, faster primary/reply/breath schedule, filter, six controls and pad role stay in place. No new engine or preset family is needed to test the gesture.

The nearest verified oscillator/memory siblings are [TANGLE](../synth/src/main/kotlin/com/snipsnap/synth/Tangle.kt), whose `Tangle.render(voice, macros, midi, velocity)` uses reciprocal previous-output FM and encounter-driven phase attraction, and [CREASE](../synth/src/main/kotlin/com/snipsnap/synth/Crease.kt), whose `Crease.render(voice, macros, midi, velocity, model)` moves two analytic phase maps through pressure, recovery and output-load states. Skitter's proposed distinction is a source/filter spectral return which leaves the oscillator root phases unchanged; this is an architectural distinction, not a claim that those engines cannot make complex tones.

## Literal model and analogy

The four real state variables, bounded drive, phase-plane rotations and orthogonal exchange are **literal DSP**. “Load,” “bridge” and “reply” are musical analogies. This is not a transistor circuit, material simulation, mechanical energy model or calibrated physical instrument. Its norm bound is a numerical signal bound, not a conservation-of-acoustic-energy claim.

```text
note, velocity, existing macros
          |
          v
existing phases + phrase schedule --> [voice source] --> [SVF] --> [VENT mix] --> output
                                       ^   |              |
                                       |   | source tap*  | actual band before VENT
                                       |   v              v
                                       | [input A]     [input B]
                                       |      \           /
                                       | [four-state bridge: A <-> B, dynamic Cayley]
                                       +------- prior upper-color state
                                         [cache: carrier rotations/decay/exchange scale]
```

Port A uses the actual loaded upper source for BASS, LEAD and HOLLOW. For FLUTTER, CHIRP and FRAY, its tap precedes the bridge's changes: the R7-shaped upper excitation includes its original normalizer, CHIRP's reference center/sweep and FRAY's reference fold/aperture/J0 mean correction. This reference is computed from the current note and circuit history, position and signed state, not a detached R7 recording. Port B always uses the actual R8 SVF band before VENT's output mix. The three reference taps remove their immediate loaded-source return to port A; indirect source/filter feedback remains.

Prior bridge states reshape the next upper branch; oscillator/root phase increments retain their R7 path. Source normalization can still change carrier amplitude, and an “upper branch” need not contain only upper partials. The one-sample order avoids an algebraic solver. Existing filtering, anti-alias conditioning, DC removal and uniform output leveling keep their jobs.

## Implemented architecture

`SpectralBridge` implements two complex states, `z1` and `z2`, stored as four real scalars. For requested root `hz`, internal `rate`, `g = sqrt(AGITATE)` and the shape coefficients below:

```text
tau = clamp(cycles/hz, .008, .06) seconds; rho = exp(-1/(rate*tau))
R = diag(rot(2*pi*hz*h1/rate), rot(2*pi*hz*h2/rate))
pressure = g*(position - .85*tanh(.30*g*history))
strain = pressure/(.08+abs(pressure)); contact = gesture/(.10+abs(gesture))
t0 = tan(pi*hz*exchange*g/rate); t = t0*(1+1.75*strain+.75*contact)
c = (1-t*t)/(1+t*t); s = 2*t/(1+t*t); U = [[c,-s],[s,c]]
b = [tanh(18*portA), 0, tanh(filterGain*SVFband), 0] / sqrt(2)
q[n+1] = rho U R q[n] + (1-rho) b[n]; depth = depthScale*g
||q[n+1]||2 <= rho ||q[n]||2 + (1-rho)
```

`filterGain` is `12` for LEAD/HOLLOW and `24` for the other voices. Port A includes the actual excitation/velocity scale. The Cayley exchange reads prior circuit pressure and actual gesture, with no direct bridge-energy or coherence term in its angle. It acts on both real and imaginary components and stays orthogonal for every resulting angle. Rotations preserve norm, and the bounded drive has norm at most one. Starting at zero keeps the bridge in the unit ball even when forcing depends on the surrounding circuit; its unforced norm decays by `rho`. This **does not prove global contraction of the nonlinear instrument or held convergence**.

Readout projects the prior states into oscillator A's matching harmonic frames. With those complex vectors `w1`, `w2` and norms `n1`, `n2`, the in-phase first return and quadrature second return are divided by `.06 + n`. A separate relative-lag readout is:

```text
lag = cross(w1,w2) / sqrt((.0004+n1*n1)*(.0004+n2*n2))
```

Each branch is regularized independently with `.02`; a quiet branch cannot divide by its own vanishing norm. The three readouts stay below unit magnitude and change voice geometry. Lag adds no state and is not directly fed into the exchange angle. All constants here are musical **shape choices**, not physical measurements.

| Voice | `h1/h2` | Cycles | Exchange | Depth scale | Two return routes |
|---|---:|---:|---:|---:|---|
| BASS | `2/3` | `8` | `.18` | `.55` | Charge aperture and centered cubic weight. |
| LEAD | `3/5` | `5` | `.27` | `.65` | Broad packet groups plus alternating partial teeth; harmonic 3 is exempt from the lag teeth. |
| HOLLOW | `3/5` | `11` | `.14` | `.60` | Octave-PM index, bounded to `1.25–3.0`. |
| FLUTTER | `2/4` | `7` | `.36` | `.80` | Aperture, balance and relative phases of the 2B/3B gate holes. |
| CHIRP | `4/6` | `9` | `.24` | `.70` | Windowed packet center and sweep; original level/normalizer/velocity law retained. |
| FRAY | `2/3` | `6` | `.31` | `.85` | Bounded fold and folded-grain aperture, including J0 mean correction. |

`SkitterProbe(depthDisabled = true)` selects the retained R7 path; AGITATE zero also omits the bridge. `filterReturnDisabled` removes only this bridge's band injection, and `depthExchangeDisabled` removes only its orthogonal exchange. All four states join `Circuit.state()` for HOLD convergence; traces and diagnostics expose state/return/exchange observations. These source facts are verified; the frozen-run parity, causal and convergence results are recorded below.

HOLD retains the 16-period preroll cap and `2e-5` engine state tolerance. The unchanged [legacy test](../synth/src/test/kotlin/com/snipsnap/synth/SkitterTest.kt) separately requires exactly three periods and state error below `1e-7` for its 18 default/all-high cases: six voices at MIDI 48 defaults and MIDI 24/84 all-high, seed 917. Keep that tighter margin for those inputs. It is not a universal three-period promise: other common-control cases may need additional warm-up periods and must still meet full-state and independently evolved audio-seam checks within the existing cap.

## Existing macro contract

Factory defaults below retain the order **BASS / LEAD / HOLLOW / FLUTTER / CHIRP / FRAY**, verified through `Skitter.macrosFor(voice)` and its shape table. R8 adds no controls. The interaction and probe columns are design obligations, not completed results.

| Control | Defaults | Quantity and required interaction | Proof |
|---|---|---|---|
| BUZZ | `.40/.70/.35/.55/.50/.85` | Existing source geometry and upper energy; BUZZ × AGITATE must change how the bridge is loaded and heard. | Level-normalized low/middle sweeps plus bridge bypass. |
| SKITTER | `.20/.45/.30/.55/.25/.65` | Existing compact pitch departures and phrase density; SKITTER × AGITATE must retain primary/reply/breath timing. | Compare event times and bridge response; preserve the eight-Hz wrap cap. |
| AGITATE | `.30/.45/.25/.70/.65/.85` | Existing forcing and reaction depth; controls the new return without a second independent motion clock. | Zero/middle sweeps, filter-return ablation and exchange ablation. |
| VENT | `.10/.25/.50/.40/.65/.45` | Selects the heard low/band/high filter balance. The bridge's band tap is independent of this output mix; VENT × AGITATE changes which source/filter response is heard. | Band-input causal probe, output-balance interaction and retained high-VENT pitch checks. |
| STATIC | `.05/.10/.10/.20/.35/.60` | Existing seeded noise excitation; STATIC × AGITATE can provoke memory without becoming the sole source of depth. | Noise-off temporal/causal probes; unchanged noise-ablation semantics. |
| HOLD | `0` for all | Existing finite length below `.999`, periodic-input loop at/above `.999`. Include all four new states in recurrence. | Real consecutive periods, complete-state error and raw/export seam. |

`Skitter.render(voice, macros, midi, velocity, seed)` and `Skitter.renderProbe(..., seconds, oversample, probe)` are verified APIs. MIDI remains 24–84, default 48; production output remains mono 44.1 kHz with 4× internal processing. `SkitterPatch`, serialization, six voice IDs and 36 presets retain their existing integration surface.

## Cost, risks and cheapest proof

The implementation precomputes carrier rotations, decay and base exchange scale once per render from voice, AGITATE, root Hz, internal rate and the exchange-ablation flag. Per frame it computes a rational Cayley rotation, two bounded drives, harmonic projection/readout and the voice's fixed-size source/reference evaluation. No new inner-loop allocation, matrix construction or iterative solve is used; the exchange needs no per-frame trigonometry. The finite desktop timing below measures this cost. Phone interaction remains unmeasured.

Risks are a single dominant spectral direction despite four states, return-induced roughness/aliasing, masking of the root, quieter notes being mistaken for richer notes, and longer HOLD convergence. Oscillator-phase preservation alone does not establish detected pitch. Retain all existing admission bars rather than lowering them to accept a new circuit.

The minimum proof compares the six C3 voices against a retained R7 path, with noise present and absent, then removes the bridge's filter drive and exchange separately. The measured methods below establish those particular causal and temporal changes; the complete-note audition lets the owner judge their musical value at three velocities.

Before publication, require deterministic repeated renders, finite internal bounds, root/confidence and raw headroom, preserved timing/cancellation, higher-rate alias references, and held complete-state plus independently synthesized audio recurrence. Verify the R7 bypass sample bits against retained R7 classes on the same runtime. Keep full raw PCM24 downloads and source/recipe provenance; freeze historical recordings rather than rerendering an archive. The full `./gradlew --no-daemon test` task must finish successfully before the implementation is reported green.

## Measured validation

Measurements below use the frozen source SHA-256 above, `skitter-r8-focused-10.log` and `skitter-r8-final-probes.json`. The focused runner exited zero: **45/45 methods passed**, comprising 42 existing methods with retained admission bars and the three new [SkitterDepthTest](../synth/src/test/kotlin/com/snipsnap/synth/SkitterDepthTest.kt) methods for sustained trajectories, separate causal returns, and held/reference health. These are source-specific engineering results; owner listening remains pending.

### Sustained sound change

The sustained-trajectory method compares raw R8 against `depthDisabled` R7 at MIDI 48, velocity `.6`, seed `0`, 4×, a 1.3 s finite probe, and the common controls above with STATIC `.15` and `0`. It analyzes `.35–1.25 s` using symmetric Hann windows of 2048/8192 samples (46.44/185.76 ms) at 441-sample hops. Each power frame is normalized into 13 root-relative bands with edges `0,.85,1.15,1.75,2.5,3.5,5,7,10,14,20,32,64,∞`. Distances are dimensionless half-L1 distances. “Centered” subtracts each take's mean profile before comparing trajectories; “spread” measures motion around R8's own mean. Each reported value is the **declared maximum over the two resolutions**.

| Voice | Mean shift, STATIC 0 | Centered difference, STATIC 0 | Centered difference, STATIC .15 | R8 spread, STATIC 0 |
|---|---:|---:|---:|---:|
| BASS | 0.18219 | 0.01005 | 0.01015 | 0.02352 |
| LEAD | 0.03577 | 0.07613 | 0.07627 | 0.07612 |
| HOLLOW | 0.14018 | 0.01223 | 0.01219 | 0.06051 |
| FLUTTER | 0.03407 | 0.01141 | 0.01139 | 0.01127 |
| CHIRP | 0.03663 | 0.01565 | 0.01548 | 0.01049 |
| FRAY | 0.02965 | 0.01867 | 0.01842 | 0.01846 |

All 12 voice/noise cases clear the declared centered-distance and spread bars of `.01`. These changes survive zero STATIC; they establish evolving normalized spectra under this ruler, not a universal depth score. The ruler reports only `2.56e-5` centered distance for gain/carrier-phase changes; a static color change gives mean distance `.2811` but centered distance `2.70e-5`. Uniform gain cancels in these features. One constant complete-1.3 s RMS match, when used, ranges `.9578–1.0480`; no separate attack/body gain is used. The earlier R7 harmonic/PCA excerpt uses different bands/windows and cannot be divided into these values to claim an improvement factor.

### Causal filter return and exchange

The causal method uses the same common controls at MIDI 48, velocity `.6`, seed `17`, STATIC `0`, HOLD `1`. Full and one-flag-off renders retain identical external pitch-gesture traces and planned periods. Features cover the independently evolved second held period; the same two-resolution maximum rule applies.

| Voice | Filter return: total / centered | Exchange: total / centered |
|---|---:|---:|
| BASS | 0.18181 / 0.01146 | 0.15477 / 0.00772 |
| LEAD | 0.08736 / 0.06983 | 0.09115 / 0.07116 |
| HOLLOW | 0.02691 / 0.02118 | 0.01620 / 0.01073 |
| FLUTTER | 0.01046 / 0.00630 | 0.03519 / 0.00780 |
| CHIRP | 0.13549 / 0.01045 | 0.11333 / 0.00861 |
| FRAY | 0.04540 / 0.01318 | 0.04452 / 0.01382 |

Every voice clears total distance `>.01` and centered distance `>.005` for **each** ablation. Removing the actual SVF-band injection and removing orthogonal exchange both reach time-varying normalized audio, beyond a fixed spectral offset. This does not establish that every setting, velocity or register has the same effect.

### Complete-state HOLD and stationary reference

At the causal/common-clean settings, all four bridge states remain in the measured full vector, no internal bounds are hit, and actual successive raw periods close:

| Voice | Warm-up periods | Full-state error | Raw seam |
|---|---:|---:|---:|
| BASS | 3 | 5.95e-12 | 1.5e-19 |
| LEAD | 3 | 4.4e-12 | 0 |
| HOLLOW | 3 | 7.58e-12 | 0 |
| FLUTTER | 4 | 4.27e-06 | 2.55e-17 |
| CHIRP | 3 | 4.7e-12 | 0 |
| FRAY | 3 | 4.62e-08 | 0 |

FLUTTER needs four periods here; the other five need three. The separate 18 legacy default/all-high cases still pass their unchanged exactly-three-period, state-error `<1e-7` and exported-seam `<1e-9` checks. Neither result promises three periods for all macros. The full-state engine limit remains `2e-5`, cap 16, with real raw/export seam checks.

The held/reference method also compares clean stationary C6/MIDI 84, velocity `1`, seed `0`, BUZZ `1`, SKITTER `0`, AGITATE `.6`, VENT `.35`, STATIC `0`, HOLD `1`, at 4× versus 16×. A 32768-sample four-term Blackman-Harris window protects wanted harmonics within six bins:

| Voice | Unwanted power, 4× / 16× (dB) | Positive excess (dB) | Harmonic distance |
|---|---:|---:|---:|
| BASS | -89.40 / -89.44 | -109.32 | 0.00157 |
| LEAD | -87.20 / -88.97 | -91.95 | 0.00801 |
| HOLLOW | -88.51 / -88.50 | None | 0.00435 |
| FLUTTER | -87.71 / -87.72 | -114.07 | 0.00086 |
| CHIRP | -88.10 / -88.38 | -100.14 | 0.00696 |
| FRAY | -87.94 / -87.98 | -108.45 | 0.00409 |

All six meet the retained excess-fractional-power bar `≤1e-6` and harmonic-envelope distance `<.03`. “None” means no positive excess, not a measured −300 dB floor. This single stationary setting cannot expose shared artifacts or harmonic-coincident folding and is not a universal alias-floor claim.

### Preserved reference, pitch and render cost

An independent isolated-loader check compares retained original R7 classes/runtime against the depth-disabled final source on the same JVM: **108 comparisons / 10,986,660 raw floats are bit-identical**. The matrix is six voices × three velocities (`.25/.6/1`) × finite/held × three profiles: C3 defaults/seed 917; C5 all five timbral controls high/seed 917; and C3 common proof controls/seed 0. This proves that compatibility path, not unchanged normal R8 output.

An additional LEAD/HOLLOW grid passes **60/60 roots**, MIDI `36/48/60/72/84`, the same three velocities, seed `0`, 1.3 s finite probes at 4×. Profiles are common controls and voice defaults with SKITTER capped at `.35`, AGITATE at `.55`, STATIC at `.20`, HOLD `0`. Maximum absolute error is **2.34678 cents**, minimum confidence **.959417**. This supplements the existing register, velocity, determinism, bounds, classification and silence guards; it does not cover every macro corner.

A separate warmed desktop JVM cost probe exited zero. At C3, velocity `.6`, seed `0`, common controls, HOLD `0`, a 1.3 s finite probe and 4×, each voice/path receives three warm-ups then seven timed renders. Medians:

| Voice | R7 compatibility path (ms) | R8 (ms) | Added time (ms) |
|---|---:|---:|---:|
| BASS | 83.29 | 87.58 | 4.29 |
| LEAD | 96.34 | 103.31 | 6.97 |
| HOLLOW | 79.56 | 89.99 | 10.44 |
| FLUTTER | 75.32 | 92.05 | 16.72 |
| CHIRP | 80.30 | 100.88 | 20.58 |
| FRAY | 89.43 | 104.81 | 15.38 |

The baseline here is the depth-disabled compatibility path on the **current runtime**, not an isolated original-R7 performance measurement. These 87.6–104.8 ms finite-render medians disclose cost; HOLD preroll and phone latency were not timed. The complete unfiltered `./gradlew --no-daemon test` remains a release check; the PR records its final exit status and test count against the published source commit.

## Evidence ledger and implementation accounting

| Claim | Status | Evidence or remaining work |
|---|---|---|
| R7 files are unchanged at the chosen upstream base. | Verified | Full-tree comparison of `385b87a…` and `bd3f1bdc…`; all 28 Skitter paths match. |
| Existing render/default APIs and the sibling mechanisms above exist. | Verified by source reading | Named Kotlin files at the stated base; no new sibling API assumed. |
| The retained excerpt has the spread/PCA values above. | Measured | Python analysis of existing PCM24 recordings, method and limitations above; not a new Kotlin render. |
| Four-state recurrence, per-voice routes, null R7 path and HOLD state inclusion. | Verified | Frozen source hash, equations and shape table above; retained-path samples and held state measured above. |
| More sustained depth, suitable defaults and a useful audible distinction from R7. | Proposed; not heard | Await owner comparison on the published dry audition. |
| Focused numerical, causal, temporal, held/reference, parity and finite desktop cost checks. | Measured | Frozen-run tables above; 45 focused methods passed. Scope limits remain explicit. |
| Full unfiltered JVM release gate. | Required release check | Final result and source commit are reported in the PR, separately from focused admission. |

Method: **REVIEW → CONCEIVE**, using [Luthier](../.claude/skills/luthier/SKILL.md). The cloud provider's six reference reads failed earlier; the repository fallback supplied conventions, review rubric, DSP catalog, engine inventory, combination grammar and spec template. All six were read locally and `scripts/check_resources.py` passed. The inventory is dated; actual Kotlin source takes precedence for sibling mechanisms and API names.

## Why it is built this way

- Two different audio measurements give the instrument two things to remember. Replacing them with one envelope would make the extra state count a poor argument for depth.
- Rotations and exchange move a bounded memory vector before loss. This permits delayed color replies while making the unforced state budget inspectable without a final limiter.
- The upper branch carries the new response while the root phase stays put. Pitch tests still have to prove that carrier-level changes and extra partials do not mask the note.
- Cached coefficients and four scalar states keep the experiment small enough to rerender. A large physical solver would add cost before proving the owner's musical benefit.
- A bit-exact R7 bypass and synchronized dry comparison make the risky decision reviewable: whether this extra response is useful character rather than roughness or a level trick.
