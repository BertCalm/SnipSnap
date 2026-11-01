# Accord Engine Engineering Specification for SnipSnap

Design version 1.0 · 31 October 2026

Native R1 implementation record · 1 November 2026

Status: engineering handoff preserved alongside the finite native R1 candidate. Owner approval applies to the Python sound direction; native validation, phone performance and production listening remain separate gates.

Repository baseline: [`d7c2379c16520df2679457679844b56d32777712`](https://github.com/BertCalm/SnipSnap/tree/d7c2379c16520df2679457679844b56d32777712). The original source audit is in `reference/accord/evidence/repository-audit.md`. Sections 1–20 preserve the design and its prior Python evidence; section 21 records the native implementation, repository updates and validation revisions. Read [ACCORD.md](../../ACCORD.md) for the current usage contract.

The complete frozen handoff is in [`reference/accord/`](../../../reference/accord/README.md), including six approved Python WAVs, exact tables, source scripts and unchanged manifests. Source/evidence paths below are repository-root paths; run archived Python experiments in a scratch copy to preserve their hashes.

## 1. Summary

The characteristic gesture is **scattered contacts → mutual adjustment through the raft → concentrated pulses or a stable dispersed pattern → unforced ringing**.

Accord is a pitched, offline instrument whose articulation comes from fifteen autonomous, self-sustaining pendulum-like oscillators on one horizontally moving support. Their inertial coupling is reciprocal. Audio reads their contacts and near-center pressure without feeding back into their motion. Requested note tunes the acoustic resonators independently of the mechanical pace.

Carry the approved sound direction forward: **BRUSH is the default; WOOD and TINE remain; TINE retains Reference, Soft Attack, Bright Edge and Long Ring profiles**. The original TINE upper-mode ratio is **5.96**. The later 6.00 experiment is diagnostic history, not a new default. No TINE winner or combination was selected.

The listening reference is the [private audition page](https://accord-listening-room.bertcalm.chatgpt.site#tineOptions). The bundled WAVs are the durable reference if that account-private page is unavailable.

## 2. Goals and scope

The instrument should produce playable pitched pulses and brushed textures whose timing develops without a master clock. It should retain the accepted root hierarchy and soft/hard articulation across C2–C5. Gathering must be a possible dynamical outcome, not a promised result at every knob setting.

The first native build includes three voices, four TINE profiles, a finite performance, deterministic replay, mechanics/audio diagnostics and cache reuse. All voices use the same mechanical population at equivalent settings. It includes no source recordings, external samples, dry noise layer, reverb, independent click scheduler or imposed crescendo.

The first build refuses sustained HOLD explicitly. Seamless or reconstructed sustain is a later, separately versioned capability. The approved 8-second excitation studies are finite performances; their length alone does not make their motion periodic.

## 3. Why this earns an engine

Current source supports the distinction from these nearest siblings:

| Sibling | Existing behavior | Accord's additional behavior |
|---|---|---|
| FLOCK | Twenty-four particles gather in pitch and energy coordinates. | Fifteen sustained oscillators change the timing of their contacts through a common accelerating support. |
| BALLAST | An electronic bass drives a frame and passive strings/tiles; those settle when the bass stops. | Every mechanism sustains itself and can alter the others' articulation phases without an external audio drive. |

PITCHWHEEL supplies useful event/trace/diagnostic patterns. Current LIBRATION, TIDAL and APSIS supply preparation/cache precedents. LIBRATION models restricted three-body orbital motion, not pendulums. Putting the accepted modal tables into those engines would preserve a timbre while losing Accord's defining gesture.

Three directions were assessed. Scores are design judgments, on a 1–5 scale where 5 means stronger clarity/novelty and greater risk/cost:

| Direction | Operator and behavior | Clarity / novelty / risk / cost |
|---|---|---|
| Selected | PORT + COUPLE: literal autonomous oscillators exchange inertial reaction through the raft. | 5 / 4 / 3 / 4; wins because the contact timing has a distinct cause and an existing numerical proof. |
| Alternative | SPLICE: put the accepted acoustic profiles behind an existing phrase engine. | 4 / 1 / 1 / 1; economical if only the timbres matter, but loses emergent timing. |
| Alternative | INVERT: use resonator energy to drive the support and thereby change subsequent contacts. | 3 / 5 / 5 / 5; would require a new feedback-energy bound and invalidate the present cache separation. |

No additional direction selection is needed for this handoff.

## 4. SnipSnap contract and saved performance

Output is mono 44.1 kHz; internal audio is 176.4 kHz. Use the house band-limit, decimation, DC removal and tail fade. Use the house loudness target to establish reference calibration, preserving the velocity law rather than leveling every velocity independently.

Expose five controls, PACE, COUPLING, MISMATCH, CONTACT and RAFT, plus HOLD. Pitch is an explicit saved MIDI note, following the current FLOCK/LIBRATION performance pattern. This is the reason for omitting a second stored TUNE macro: note must have one authority. A pitch control labeled TUNE can edit that MIDI field directly when the app integration is implemented.

Proposed `AccordPatch` fields:

| Field | Contract |
|---|---|
| name, voice, macros | Common Patch identity; voice is BRUSH, WOOD or TINE. |
| midi | Integer 36–72, default 48. Frequency is `440 * 2^((midi-69)/12)`. |
| velocity | Finite 0–1, default .75; zero produces silence without rebuilding mechanics. |
| seed | Exact signed 64-bit value, default 20261022; JSON canonical decimal string. |
| model | Independent render-model version; reject unsupported explicit versions. |
| acousticProfile | BASE for BRUSH/WOOD; REFERENCE, SOFT_ATTACK, BRIGHT_EDGE or LONG_RING for TINE. Reject invalid combinations. |
| initializer | Default REFERENCE_SINE_20261022, requiring seed 20261022 and the saved vectors; PROJECT_SINE_V1 derives vectors for any seed. These are distinct saved identities. |
| sourceStartCycles | Finite 0–8 canonical reference cycles, default 8; zero is useful for diagnostic starts. |
| sourceSeconds | Finite .25–8 seconds, default 8. |

Acoustic tables, roughness law, filter/gain decisions and solver rules are part of the rendering model. The common `Patches.VERSION` is a JSON schema version and must not stand in for those choices. A future held reconstruction needs its own versioned playback state.

At the public direct-render boundary, fill missing macro values from defaults; discard unknown macro keys; substitute defaults for non-finite macro values; clamp finite macro values to 0–1. Validate the non-macro performance fields against the table above. Programmatic construction of a saved Patch follows strict patch validation instead of this convenience policy.

Strict saved JSON rejects unknown macro keys, invalid profiles, unsupported models and invalid note/velocity/seed/start/duration values. Reject every raw non-finite macro number or value outside 0–1 at JSON Double precision before Float conversion, so rounding cannot make an invalid stored value valid. Test these policies separately. HOLD is validated after settling: only zero is supported in the finite build. For example, direct-render HOLD=-.1 settles to zero; saved HOLD=-.1 is invalid, and a positive settled HOLD is unsupported.

## 5. Signal flow and separation

```text
seed, initializer, COUPLING, MISMATCH, RAFT
                    |
                    v
 [AccordDynamics: 32-state reciprocal ODE]
 [cache: model, initializer, seed, physical quantities, solver grid; extent]
                    |
     states + individual directional center passages, canonical time
                    |
           PACE, source start/duration
                    v
 [AccordContacts: event traversal + geometry pressure]
                    |
       note, velocity, CONTACT, voice/profile, RAFT acoustic law
                    v
 [AccordExciter: fractional Hann contacts + pressure-gated roughness]
                    | audio @176.4 kHz, 10 kHz excitation low-pass
                    v
 [AccordAcoustics: common 4-mode tongue + common 4-mode body]
                    | audio @176.4 kHz
                    v
 [AccordOutput: bandLimit -> decimate -> mean/20 Hz DC removal
                -> shared reference calibration -> one-shot fades]
                    |
                    v
             mono 44.1 kHz Snip
```

Mechanisms exchange reaction forces only through the common mechanical acceleration. Resonator output, contact strength, requested note, velocity and acoustic profile never enter the mechanical RHS. The approved body is driven by contacts/pressure. Adding a platform-acceleration audio send would be a new sound-design experiment, so omit it from the compatibility build.

## 6. Reciprocal mechanical model

The following is the tested research model, in normalized mechanical units. It is a literal integration of these equations, not a metronome-clock simulation. It is not a calibrated model of a particular physical instrument.

Let normalized time be tau. For mechanism i, theta is angle, omega = dtheta/dtau, J is rotational inertia, h is the horizontal inertial coefficient and k is the gravity coefficient. The support state is displacement x and velocity V. There are 15 mechanisms and 32 state variables.

```text
q_i = kappa * h_i * cos(theta_i)
Q_i = J_i * mu * (1 - (theta_i/theta0)^2) * omega_i
b_i = Q_i - k_i * sin(theta_i)
B   = sum_i(kappa*h_i*sin(theta_i)*omega_i^2) - cb*V
D   = H - sum_i(q_i^2/J_i)
A   = (B - sum_i(q_i*b_i/J_i)) / D

dtheta_i/dtau = omega_i
domega_i/dtau = (b_i - q_i*A)/J_i
dx/dtau = V
dV/dtau = A
```

The Van der Pol term Q supplies energy at small amplitude and removes it at large amplitude. It models an autonomous sustaining action, not audio feedback. The sign change at theta0 is not the final swing amplitude.

Reference constants are h_i = k_i = .02, J_ref = .024, mu = .1, theta0 = .38. Let W = sqrt(.02/.024), T_ref = 2*pi/W = 6.882884651454571, and `J_i = .02 / [W*(1+s*eta_i)]^2`. The rate-offset vector eta has zero mean and max absolute value 1. Total support translation inertia is H = Mbase + .3. Reference Mbase = .6, cb = .05, kappa = 1, s = .01.

For the proposed macro domain, Mbase >= .05, kappa <= 1 and s <= .04 imply:

```text
J_i >= .024/(1.04^2)
D >= .35 - 15*(.02^2)/(.024/(1.04^2)) = .0796 > 0
```

This conservative bound holds for every angle and every permitted offset, without using its zero mean. Check D in every RHS evaluation. A violated bound is a model/input failure, never a reason to divide by an arbitrary epsilon or clip the resulting acceleration.

The matching energy/work identity is:

```text
E = .5*H*V^2 + sum_i[.5*J_i*omega_i^2
                     + kappa*h_i*V*cos(theta_i)*omega_i
                     + k_i*(1-cos(theta_i))]
dE/dtau = sum_i(Q_i*omega_i) - cb*V^2
```

With drive and support damping disabled, energy and horizontal momentum are conserved. With drive present, test the work identity instead of claiming energy must decrease. The support has no translation spring; displacement may acquire an offset. Its absolute displacement is not an audio source.

## 7. Initialization, time and contacts

The accepted fixture uses NumPy seed 20261022. It draws the 15 phases first, then the 15 centered/scaled rate offsets from the same generator. Initial conditions are `theta_i=.76*cos(phi_i)`, `omega_i=-.76*W*(1+s*eta_i)*sin(phi_i)`, x = V = 0. Preserve the stored phase/rate arrays in `reference/accord/reference/mechanics/gathered.npz` as a reference initializer. This avoids silently changing the approved gathering history when porting RNGs.

Production random initialization uses stable phase/rate/roughness substreams derived through `Dsp.seedFor`, with `Dsp.Noise` for noise generation. PROJECT_SINE_V1 uses the same sinusoidal state formula with project-generated phase/rate vectors. A seed edit from the reference initializer must select that saved initializer explicitly; never special-case seed 20261022 behind an otherwise identical identifier. Center the rate draws and divide by their maximum absolute value, with all-zero offsets if that maximum is negligible. Use fixed mechanism order and a versioned threshold for that degenerate case.

For PROJECT_SINE_V1, construct separate `Dsp.Noise(Dsp.seedFor("ACCORD_MECHANICS", 1, seed.toString(), stream))` generators with literal stream names `phase` and `rate`. Draw exactly fifteen values from each in mechanism order. Convert each phase draw n to `2*pi*clamp((n.toDouble()+1)/2,0,1-2^(-24))`. Clamp each rate draw to −1..1, subtract the Double mean, then divide by the largest absolute centered value; use all zeros if that value is <= 1e-12. These proposed rules must acquire native goldens before model 1 is frozen.

Changing note, velocity, CONTACT, voice or acoustic profile must not reseed mechanics. Use a stable mechanical seed-contract identifier and initializer in the substream namespace, rather than a global acoustic model version that would reseed it after a sound-only revision. Project-RNG initialization and any measured-limit-cycle initializer require their own versioned comparison; the same integer seed does not reproduce NumPy draws.

Canonical cycle coordinate is c = tau/T_ref. PACE traverses it as `c(t) = sourceStartCycles + f_ref*t`. Default f_ref = 2 cycles per musical second. Thus the approved four-second mechanics preroll is stored as **8 cycles**, not as four seconds at every PACE. Acoustic states start at zero at the beginning of the excerpt.

Retain each mechanism's two directional center passages per full swing. Locate theta_i = 0 within the integration interval, interpolate its time and speed, and assign direction from omega. Preserve mechanism index and local ordinal. Deterministic ordering is by time, then mechanism index, then direction/ordinal. Do not snap, merge, cluster or reset events. Specify endpoint ownership so a zero on a step boundary appears once; use positive/negative brackets and reject stationary zero tangencies.

Stored speed is abs(dtheta/dtau). Use that canonical speed in the acoustic strength law. Multiplying it again by PACE would introduce an unintended loudness change.

Compute R1 and R2 from phase interpolated between consecutive same-direction passages, excluding incomplete edge cycles. R1 measures full-cycle phase gathering; R2 helps distinguish two-cluster structure. Neither substitutes for the measured spread of actual contact times.

## 8. Solver and preparation strategy

Python used SciPy DOP853 with dense event roots. That is the reference solver, not a ready-made Kotlin API.

The first Kotlin candidate is Double-state RK4 on a fixed canonical grid of 256 steps/reference cycle, with Hermite interpolation using endpoint states/derivatives for contact refinement. This is a proposed starting resolution. Increase to 512/1024 or choose a bounded adaptive scheme if the convergence gates fail. The integrator must land on the same global grid when extending a cached prefix. A cache hit and a fresh solve must give the same prefix.

Initially retain the approved 40 descriptor samples/cycle as a compatibility readout. Preserve the operation order: interpolate **theta/omega states** from that grid to a 2 kHz musical-time grid, evaluate nonlinear pressure there, then interpolate pressure to internal audio rate. Use grid origin t=0 at the source excerpt, `j=0..ties-to-even-round(sourceSeconds*2000)-1`, t_j=j/2000, and canonical position `sourceStartCycles+PACE_in_cycles_per_second*t_j`. Linear audio interpolation uses the first pressure at the left endpoint and zero beyond the final pressure knot, matching the reference before the shutdown gate. Evaluating pressure on the coarse grid before interpolation is a different sound mapping. Assess a denser readout separately; interpolation at 2 kHz does not mean the original trajectory was solved at 2 kHz.

Ordinary rendering prepares only the required horizon: `sourceStartCycles + PACE*sourceSeconds`. With the proposed domain, the maximum is 40 cycles. Round coverage to 16-cycle blocks, so the largest ordinary preparation is 48 cycles; do not solve the 140-cycle research horizon on every edit. Long-horizon synchronization probes remain test-only.

Candidate acceptance gates, not existing measurements: match per-mechanism/direction/ordinal event counts; event-time error below 2 microseconds at the slowest supported traversal on curated short fixtures; pressure normalized RMS error <= 1e-3 and maximum absolute error <= 1e-2 versus DOP853 on the same 2 kHz compatibility grid, over the complete eight-second excitation. Normalize RMS error by max(reference pressure RMS,1e-8), and compare before the common shutdown. Grid halving must reduce the trajectory/event errors rather than merely leave the final audio level unchanged.

For conservation/work, use the reference initializer and stated physical fixture over tau=0..30*T_ref. Evaluate residuals at least at every solver knot. With drive and damping off, require `max_t(abs(E(t)-E(0)))/max(abs(E(0)),1e-12) <= 1e-6`. With drive/damping on, define W(t) as the integral of `sum(Q_i*omega_i)-cb*V^2` from zero and require `max_t(abs(E(t)-E(0)-W(t)))/max(abs(E(0)),1e-12) <= 1e-6`. Integrate an auxiliary work state using the same RK stages, then independently check its quadrature against refined/dense power evaluation until that quadrature changes the normalized residual by <= 1e-8. A sign-flipped power term must fail the driven test. Long-horizon phase-sensitive differences must also be reported, rather than hidden by aggregate R1 alone.

Cap solver work and check cancellation at least every cycle and before cache publication. Non-finite state, exhausted work cap, invalid D or failed event refinement produces an explicit diagnostic failure. Never replace a failed solve with a master clock or partially prepared trajectory.

## 9. Contact excitation and available energy proxy

At C3/velocity .75 and default CONTACT, positive-direction widths are BRUSH 4.5 ms, WOOD 2.1 ms and TINE .72 ms. Negative passages are 1.12 times wider. A pulse starts at the geometric event time; its force centroid consequently occurs later for wider contacts.

```text
N = max(4, ties-to-even-round(width_ms * direction_scale * .001 * 176400))
w[n] = .5 - .5*cos(2*pi*n/(N-1)), n = 0..N-1
kernel = w / sum(w)
amplitude = sqrt(min(canonical_speed/.8, 2)) / 15
```

Place the kernel using linear fractional-sample weights at floor(position) and the next sample. Both directions have the same pulse polarity in the reference. Normalize each fixed kernel once; do not normalize each event's speed-dependent response or each mechanism's output. With a fixed population of fifteen, individual pulse area is bounded by sqrt(2)/15. This is an acoustic excitation proxy, not a claim that audio joules equal the pendulums' mechanical energy.

Multiply direct force by the voice's pulse gain. Shut all excitation down with a 60 ms half-cosine at the end of sourceSeconds; for sources shorter than 60 ms use their full duration. The allowed minimum source duration is .25 seconds. Ringing after that gate is unforced.

BRUSH also reads:

```text
pressure = sum_i(max(0,1-(theta_i/.17)^2)^2
                 * min(abs(omega_i)/.8,2)) / 15
brush_force = brush_coefficient * pressure * bandpassed_roughness
```

The reference roughness is tanh of a seeded Gaussian sequence, then a second-order Butterworth bandpass with edges 80/2600 Hz at the internal rate. Gate it with the same shutdown. There is no bypass hiss. Low-pass direct and brush force at 10 kHz before acoustic resonance. `Dsp.Noise` supplies Float uniform draws, not a Gaussian API. Selecting and freezing the native roughness transform, endpoint rules and draw order is an explicit R0/R1 comparison/listening gate: do not assume that tanh(uniform noise) reproduces tanh(Gaussian noise), or claim native parity while that decision is unresolved. Source WAVs are test references only.

## 10. Approved resonators and profiles

Each tuple is `(note frequency ratio, input gain, t60 seconds)`, at C3/velocity .75/default CONTACT/RAFT. These are approved sound-design anchors, not measured material constants.

| Voice | Four tongue modes | Four body modes |
|---|---|---|
| BRUSH | (1,1,1.05), (2,.13,.52), (3,.025,.29), (4,.009,.17) | (1,.26,1.35), (1.5,.06,.55), (2.25,.018,.31), (3.25,.009,.19) |
| WOOD | (1,.85,.48), (2,.50,.29), (3,.24,.17), (4.04,.10,.10) | (1,.22,.72), (1.51,.07,.22), (2.29,.035,.16), (3.41,.018,.10) |
| TINE | (1,1,.83), (2,.38,.40), (3,.20,.24), (5.96,.60,.26) | (1,.14,.62), (1.5,.035,.28), (2.42,.025,.17), (3.24,.012,.11) |

| Voice | Width | Body output gain | Brush coefficient | Pulse gain |
|---|---:|---:|---:|---:|
| BRUSH | 4.5 ms | .58 | .0024 | .55 |
| WOOD | 2.1 ms | .50 | 0 | 1 |
| TINE | .72 ms | .32 | 0 | 1 |

All fifteen event histories feed **one common four-mode tongue and one common four-mode body**. The accepted prototypes do not use fifteen independently detuned acoustic banks. Because contact and brush paths have identical linear coefficients, their inputs can be summed before these eight modes; verify the floating-point reduction against the separate paths.

For frequency F, internal rate R and decay T:

```text
rho = exp(-6.9078/(T*R))
a = 2*pi*F/R
input_gain = table_gain * sin(a)
y[n] = input_gain*x[n] + 2*rho*cos(a)*y[n-1] - rho^2*y[n-2]
raw = sum(tongue_modes) + body_gain*sum(body_modes)
```

Positive t60 gives strictly stable poles. Use Double states/coefficients initially and cast only at the appropriate output boundary; compare an optimized Float path before adopting it. Existing `Modes.ring` has plain modal gain, so passing these table gains unchanged would lose the reference's **sin(a) compensation** and alter pitch/rate-dependent balance. Calibrate the caller's mode gains or add a verified internal helper. Do not add a non-existent compensate argument to `Modes.ring`.

The root ratio remains 1 throughout. CONTACT, velocity and RAFT may change excitation, gain and decay; they do not retune the tongue's root. Drop/taper modes that would enter an unsafe output band. Every approved mode through C5 is below output Nyquist.

TINE profiles are overrides on the same 5.96 reference, before continuous note/velocity laws:

| Profile | Override | Captured release |
|---|---|---:|
| REFERENCE | None | .9 s |
| SOFT_ATTACK | Width .72 → 3.2 ms only | 2.2 s |
| BRIGHT_EDGE | Tongue gains at ratios 2/3/5.96 become .85/.50/1.50 | 2.2 s |
| LONG_RING | All tongue/body t60 multiplied by 3 | 2.2 s |

BRUSH/WOOD use BASE and .9-second release. Profile identity is saved patch state, not another mechanical population or a fourth knob family. Invalid voice/profile combinations must fail decoding. Keep the variants separate; combining them is a new, unauditioned profile.

## 11. Note, velocity and level calibration

The exact continuous laws are in `reference/accord/reference/refinement/controls.py` and explained in `reference/accord/evidence/acoustic-audit.md`. They are implementation starting points with prior Python measurements, not a full native-engine acceptance.

Let r = (midi-48)/12 and d = (v-.75)/.75 for v <= .75, otherwise (v-.75)/.25. Let u = clamp(log2(mode_ratio)/2,0,1).

```text
width *= min(1,2^(-r)) * exp(-ln(1.5)*d)
all_t60 *= 2^(-.08*r) * exp(.12*d)
tongue_gain *= exp((.42*d - .10*max(r,0))*u)
body_mode_gain *= exp((.20*d - .10*max(r,0))*u)
body_output_gain *= exp(.10*d)
pulse_gain *= exp(.13*d)
brush_coefficient *= 10^(brush_register_db(midi)/20) * exp(-.18*d)
```

Width is now final: do not apply note scaling a second time in the renderer. Brush register correction is a fixed smoothstep curve through C2/C3/C4/C5 offsets +6.662590/0/−.495396/−.75 dB. C2/C4 came from measured imbalance; C5 is an extrapolated shape. Preserve the exact curve in controls.py.

Amplitude is `(v/.75)^.9` up to .75, with zero at v=0. Above .75, let t=(v-.75)/.25 and use `1+.3*t-.1*t^3`. The gains at .3/.75/1 are approximately .4384/1/1.2. Harder touches also shorten contacts and raise upper-mode input, so output RMS need not follow those ratios exactly.

Generate a frozen native calibration table keyed by acoustic/output model version, calibration version, voice/profile and integer MIDI note. Its calibration performance always uses REFERENCE_SINE_20261022, seed 20261022, default macros, velocity .75, start cycle 8 and eight seconds of excitation, with the profile's nominal release. These calibration inputs are fixed; a user's seed, macros, duration or current output cannot change the table entry.

Measure the conditioned active interval 0–8 seconds before head/tail fades, using the house loudness function through `Dsp.levelTo(activeCopy, 44100, Dsp.MELODIC_LOUDNESS_TARGET, ceiling=.94f)`. That helper applies scalar loudness and peak rescaling. Extract/verify its single gain against the unscaled active interval. Then reduce the table gain, if required, by one shared static headroom factor from the control/velocity/seed validation sweep. Publish the resulting gain and headroom margin in diagnostics. A quiet or zero-energy calibration fixture is an error, not a valid infinite gain.

Apply the stored gain to every performance of that profile/note and every velocity. Never recompute it per user edit, normalize profiles with different tails over their whole lengths, or call full-render leveling independently on each velocity. This preserves the separate amplitude/timbre law and mechanical energy envelope. Rebuild/version the table only when the native acoustic/output model changes. Do not reuse Python's .105 RMS target as a house loudness target, and do not copy the archived 6.00 TINE gain table into 5.96.

Preserve the gathering envelope. No per-event AGC, bank normalization or envelope flattening is allowed. If the chosen static calibration fails headroom probes, revise the shared calibration before release; a final limiter must not hide unstable state or erase velocity differences.

## 12. Macro contract

All mappings in this section are **proposed shape laws**, except their default point reproduces the tested physical/acoustic quantities. Validate low and middle settings before freezing model 1.

| Control | Default | Quantity and law | Interaction and proof |
|---|---:|---|---|
| PACE | .5 | f_ref = 2^(2*PACE), 1–4 reference cycles/s. No exact BPM claim. | PACE × CONTACT changes pulse overlap; traversal preserves canonical event order and root centers. |
| COUPLING | 1 | kappa = COUPLING in every reciprocal inertial term. | COUPLING × MISMATCH/RAFT changes phase organization; removing kappa removes support-mediated interaction. |
| MISMATCH | .25 | s = .04*MISMATCH in natural-rate offsets; eta is unchanged. | COUPLING × MISMATCH may lock or disperse. No universal monotonic R1 promise. Verify rate spread at kappa=0. |
| CONTACT | .5 | Let z=2*CONTACT-1. Multiply profile width by 2^(-z), pulse gain by exp(.15*z), and brush coefficient by exp(-.15*z). | PACE × CONTACT and velocity × CONTACT affect transient overlap/brightness. Defaults multiply by 1; event times remain identical. |
| RAFT | .5 | Log-interpolate Mbase/cb through (0:.05/.005), (.5:.6/.05), (1:2.4/.2). Multiply body send by 2^(.5*z) and body t60 by 2^(.25*z), z=2*RAFT-1. | COUPLING × RAFT changes support response; CONTACT × RAFT changes resonant persistence. At kappa=0 the acoustic body law still moves. |
| HOLD | 0 | First build supports only finite playback; reject nonzero after settling. | Test explicit refusal, keep scramble at zero. Later sustain must be versioned separately. |

CONTACT is an acoustic/readout analogy for a stronger catch, not an extra mechanical collision force. RAFT's support mass/damping are literal model parameters; its correlated body gain/decay law is an acoustic design mapping. RAFT is not a reverb or filter amount. No modal-frequency law has been inferred from unmeasured material physics.

Apply mappings in this order: base voice → saved profile override → note/velocity laws → CONTACT acoustic law → RAFT acoustic law → static reference/output calibration. Mechanical quantities are computed independently. Every factor equals its reference anchor at defaults, C3 and velocity .75.

## 13. HOLD and instrument playback

In the finite build, nonzero HOLD fails with an explicit unsupported-sustain diagnostic. Do not silently relabel a cut-out passage as a loop. Keep sourceSeconds separately persisted so finite performances can be reproduced without changing HOLD's future meaning.

For a later build, first test whether the full mechanical state and active acoustic states close. A high R1, matching pendulum angles, a quiet tail or a repeated buffer is insufficient. The periods of all modes and the drift of the mechanical state need not coincide. Never retune the note or reset phase merely to force a seam.

The two passage directions use different contact widths, so half-cycle closure can exchange tick and tock even when an aggregate phase metric looks periodic. Test a complete collective period and preserve direction identity.

If state closure is impractical, a captured/reconstructed sustain, like the approach explicitly described by current `FlockHeld`, is the preferable separate experiment. Describe it as a driven playback adaptation; preserve the finite onset, use the current `KeyNote(snip, loopStartFrame)` path only after checking the chosen export route, and retain release behavior. Current SnipSnap already has attack-plus-loop examples, so do not claim that capability is universally absent.

Either approach must pass `Keys.MAX_SEAM_ERROR = 1e-3` after the complete output path, using a genuinely preceding cycle/context and a meaningful signal floor. Include modal, filter and decimator states. Test eight wraps for listening and propagation, but do not use the repeated loop itself as the seam reference.

## 14. Caching and render budget

Use current preparation-cache patterns rather than starting a universal simulation framework. `ApsisPreparationCache` is a verified internal generic bounded LRU, and `LibrationRenderSession` demonstrates separate preparation/audio caches, cancellation, stage timing and owned output arrays. Neither supplies Accord's solver.

The mechanical base key contains model, initializer identity, full seed, kappa, spread, Mbase, cb, fixed physical constants or their version, solver resolution and descriptor policy. Coverage extent is tracked alongside it. Include every quantity that can change a trajectory. Exclude pitch, velocity, voice/profile and CONTACT. RAFT is included through its physical values. PACE, source duration and source start affect required coverage/traversal, not the mechanical RHS: a sufficient cached prefix can be reused.

Use 16-cycle coverage blocks and retain immutable states/events. An extension must match a fresh prefix; selecting a different requested duration must not perturb earlier contacts. Use a bounded preparation cache with at most six entries, 32 MiB total and 12 MiB per entry initially. These are existing-cache defaults, not measured Accord memory use; account for the arrays actually retained. The audio cache has a separate byte cap, for example 8 MiB, and a complete key including every saved acoustic/performance field and output model. Copy mutable Snip arrays on retrieval.

Never round physical knob values just to obtain a cache hit. Settle/snapshot mutable macro maps before key construction. Prepare outside the shared lock, cancel obsolete work and publish only a fully completed immutable value. Cache eviction may change cost, never output.

Proposed phone targets at the default 8-second source: exact audio recall <= 5 ms, preparation-hit acoustic rerender <= 80 ms, cold preparation plus acoustic render <= 150 ms, measured at p95 on a named device. A normal warmed note/contact edit must stay in tens of milliseconds. Report physics, excitation, modes, output conditioning, total time and retained bytes separately. These are acceptance targets, not performance claims. If they fail, optimize the eight-mode/force implementation or preparation before widening scope; do not remove coupling or shorten the approved reference silently.

The only existing cost number is **.1804 seconds for 16 cycles of Python DOP853 mechanics alone**. It excludes acoustic rendering and the default excerpt's additional eight-cycle preroll. It establishes neither native latency nor a cache speedup.

## 15. Numerical and output requirements

Use the verified sequence `Tide.bandLimit` → `Dsp.decimate` → mean subtraction/20 Hz high-pass → reference-calibrated gain → 4 ms tail fade. The references also use a 3 ms head fade. Adapt the house sequence locally; do not call another engine's private finish function.

Python uses approximate Butterworth/FIR output filters, Double states, NumPy random draws and 16-bit WAV export. Native filters/RNG/precision will change PCM. Native acceptance therefore combines event/model agreement, measured acoustic behavior and an owner listening comparison. Do not promise cross-language byte identity. Native repeated/cache-hit/cache-miss renders must be byte-identical within the project's determinism contract.

Required bounds: finite mechanical/audio state; D above its analytic floor within tolerance; positive t60; output peak <= .94 for the released calibration; absolute mean < .005; no nonzero output for zero velocity. Establish a meaningful raw-peak ceiling from the corner sweep and expose unexpected internal growth instead of hiding it with output gain.

Root mode frequencies must remain at the requested note under all acoustic controls. Test free response separately from active contact pitch estimates. The working active-window goal is <= 50 cents in confident windows across C2–C5, while reporting the complete confidence/error distribution and octave errors. Do not discard windows based on proximity to the expected pitch. A universal 5-cent claim is not supported by the Python results.

Compare a higher-rate reference for sharp-contact/bright-profile aliasing; 4x rendering alone is not proof. Verify no folded component exceeds the agreed residual bound, initially −60 dB relative to the reference in protected bands. If it fails, revise excitation anti-aliasing and rerun the native listening comparison.

Use the profile's captured release at its reference point. Probe extreme notes/controls for material ringing at the capture boundary; if needed, extend the unforced native release under an explicit versioned tail policy rather than relying on a 4 ms fade to hide a large truncation. Record any length change in the native audition. Compute render frames once from sourceSeconds plus that resolved release, and use that same frame calculation for classification.

`drumClassFor` must agree with actual rendered classification and frame length. The classifier files nonsilent audio longer than 1.5 seconds as LOOP. Therefore the default 8.9/10.2-second finite studies receive the LOOP class without a seamless-playback claim. Test short finite renders against actual classification rather than assuming TONAL or PERC; if a short corner falls into a drum category, narrow the supported short domain or revise/audition that behavior before release. Never force a metadata label to hide that disagreement.

After validation and frame calculation, velocity zero returns an all-zero Snip without preparing mechanics. Test silent-classifier behavior separately from the nonsilent duration shortcut; it must not silently redefine a pitched patch's declared class based on a silence-only measurement.

## 16. Integration checklist

Paths below are relative to the pinned repository. Re-grep them in the implementing checkout if its commit has moved.

| Work | Existing location or pattern |
|---|---|
| New DSP units and patch | `synth/src/main/kotlin/com/snipsnap/synth/Accord*.kt`; new files implementing the units above. |
| Common patch JSON/edits | `Patches.kt` dispatch/validation and `Patches.edited`; rename/macro edits must retain every custom field. |
| Velocity | Both exhaustive `Velocity.kt` cases, `macroSpecsFor` and `touchedRender`; do not register brightnessOverride until a measured sweep proves it. |
| Presets | New `AccordPresets.kt`, plus `Presets.forVoice` and `Presets.all`. Preserve named profiles; no speculative six-voice roster or blind 36-preset quota. |
| Test coverage | New engine/profile/JSON tests, `DeterminismTest` canary, actual-render classifier tests and current exhaustive patch roster helpers. Use an explicit prototype exemption before release integration if appropriate. |
| Kit | `SynthKits.accord`, new generator and Gradle task, sixteen pads with class derived from actual duration/behavior. Dry identity; no unrequested FX chain. |
| Audition | New generator/task/HTML, reference comparisons, raw and matched levels, manifests, diagnostics and recipes. |
| Documentation | `docs/ACCORD.md`, roadmap row, README count if registration changes it, and append the As built ledger to the implemented spec. |
| Phone picker | Inspect current `app/.../SynthScreen.kt` mappings and implement only in an Android-capable session that can build it. |
| Later held/keygroup export | Verify the current KeyNote/export route, note/velocity layers, loop/release state and any hardware check. |

There is no `siblingPatches` API. `Keys.planLoop` is RESIN-specific and is not an Accord loop constructor. Current `Modes.Bank` sparse participation vectors are available if a later acoustic topology needs them; the compatibility eight-mode feedforward bank does not require a coupled-bank rewrite.

## 17. Probes and auditions

Mechanics tests must cover Schur versus direct matrix acceleration; unforced conservation and driven work; D lower bound over the full macro domain; grid convergence; stable event identity; fixed/gathered/dispersed fixtures; and causal ablations. With kappa=0 the support cannot alter oscillator phase. Removing support reaction while retaining the rest must break the reciprocal-mechanics test.

Acoustic tests cover deterministic replay after cache eviction; finite/DC/peak bounds; per-profile root centers and free decay; C2/C3/C4/C5 at velocities .3/.75/1; soft < normal < hard RMS and upper energy on matched trajectories; and paired controls at corners plus low/middle sweeps. Verify an unknown public macro key does not change output. Add schema round trips including Long.MIN_VALUE/Long.MAX_VALUE seeds and invalid-profile/model rejections.

Audition sections:

1. Accepted Python BRUSH/WOOD/TINE beside native versions, same contact history and active-window match.
2. The four TINE profiles, same C3/.75/history, preserving the original 5.96 reference.
3. Voice/register and soft/normal/hard runs with one shared calibration per profile/note.
4. Individual macros at low, middle and high settings; selected pairs, especially COUPLING × MISMATCH, COUPLING × RAFT and PACE × CONTACT.
5. Fixed, gathering and dispersed mechanics under an identical acoustic profile, including a no-coupling ablation. This proves whether the distinctive mechanism matters audibly.
6. Long-ring/high-density and edge-note cases; raw/matched levels and failure diagnostics.
7. Later: held output with preceding-context seam evidence, eight wraps, onset and release, and the exported kit as loaded.

Only the owner supplies listening judgments. A better detector score is not an audible improvement. In particular, the owner reported no audible change in the old 5.96/6.00 TINE comparison; retain that limitation rather than describing the 6.00 experiment as a demonstrated improvement.

## 18. Build rounds and gates

| Round | Deliverable | Gate |
|---|---|---|
| R0 | Portable references, exact initialization fixture, dynamics/events and bounded preparation cache. | Conservation/work, Schur, convergence, causal ablations and cache identity; cost recorded. |
| R1 | Finite native BRUSH/WOOD/TINE and TINE profiles; note/velocity, explicit persistence and diagnostics. | Approved-reference parity comparisons, register/velocity/alias probes, performance targets and owner listen. No new sound profiles before this gate. |
| R2 | Full proposed CONTACT/RAFT/COUPLING/MISMATCH/PACE domains and compact reviewed preset set. | Macro/pair sweeps, seed robustness, headroom and audible timing ablation; owner listen. Revise shape constants explicitly if needed. |
| R3 | Sustain investigation, then only the strategy that passes. | Genuine state/context seam or explicitly versioned reconstruction; actual export-path checks and owner listen. A refusal remains valid if no candidate passes. |
| R4 | Kit, audition tooling, release docs and supported app integration. | `./gradlew --no-daemon test` exits 0; Android checks where applicable; documented phone/MPC results. |

R0/R1 should use the fixed accepted default point before treating the complete control cube as validated. Kotlin implementation is the next engineering task; this handoff does not claim it has been built or tested.

## 19. Sonic risks and failure probes

| Risk | Why it matters | Probe or response |
|---|---|---|
| Timing gathers but audio cancels | A 4 ms contact span is already longer than a C5 cycle. | Event-span and acoustic-envelope comparison by register; never equate R1 with coherent audio amplitude. |
| Long Ring blurs contacts | More stored modal energy can hide articulation. | Matched profile/pace comparison; retain it as an option rather than flattening it with AGC. |
| BRUSH turns into independent hiss | Noise detached from geometry loses the instrument's cause. | Pressure ablation, gate/tail checks and no dry noise path. |
| Low-note modal gain dominates | Bare two-pole gain scales strongly with frequency/rate. | Preserve sin(a) compensation, free-response/register probes and fixed reference calibration. |
| Retuning follows a misleading pitch detector | Forced windows can shift an estimate without moving modal centers. | Modal-frequency fit plus unrestricted active-window reporting; no global cents correction. |
| New RNG or solver changes the gesture | Identical numeric seeds do not imply identical phases/noise. | Saved reference vectors, versioned initialization, event parity and native owner comparison. |

## 20. Evidence and acceptance ledger

The bundled evidence files expand the equations, source locations and measurements. Numerical values below are prior Python results audited for this handoff, not new Kotlin measurements.

| Claim | Status | Evidence and limit |
|---|---|---|
| Three voices, BRUSH default, TINE variations retained | Owner-approved direction | Conversation following published auditions; no ranking or native acceptance inferred. |
| Current registry, RNG, modal, classifier, cache and export APIs | Verified source | Repository audit at d7c2379, with paths/line locations and changes since the initial 4e36a2b review. |
| Reciprocal Schur acceleration agrees with direct solve | Measured in Python | Maximum error 2.22e-16 over the saved comparison cases. |
| Conservation/work consistency | Measured in Python | Conservative residual 6.79e-7 at loose tolerance, 2.56e-10 at tight; driven work residual 4.75e-9. |
| Event extraction convergence | Measured in Python | Same event counts, maximum 8.535e-9 reference-cycle timing difference by mechanism/direction/ordinal. |
| Distinct long-horizon patterns | Measured in Python | Late crossing R1 fixed .3922, gathered .99986, dispersed mobile .00243. These are curated fixtures, not all-seed guarantees. |
| Default gathering onset | Measured in Python | R1 > .95 persists 20 cycles starting around 19.175 reference cycles. The approved excerpt starts at cycle 8 and ends at 24, so it does not contain every late steady-state diagnostic window. |
| Oscillator swing | Measured in Python | Isolated amplitude .76243 rad for theta0=.38; theta0 is a drive-law scale. |
| TINE profile contrasts | Measured from playback WAVs | Upper energy share reference/soft/bright 12.84/3.06/44.93%; common .9-second release energy Long Ring about 19.5x reference. Fixed active RMS match, not perceived-loudness proof. |
| Note/velocity refinement | Measured/proposed | 36 finite audio cases and monotonic soft/normal/hard checks on one trajectory; TINE matrix used the archived 6.00 variant. Revalidate 5.96 and the options. |
| Entire macro domain, production RNG, native filters and latency | Proposed | Requires R0–R2 gates; shape laws and target ms are not measurements. |
| Sustained loop or complete engine accepted by owner | Outstanding | No native engine, closed-state loop, phone/MPC check or native listening acceptance in this work. |

Reference source: `reference/accord/reference/mechanics/{mechanics.py,validate.py,report.json}`, `reference/accord/reference/musicality/{render.py,report.json}`, `reference/accord/reference/refinement/{controls.py,render.py,report.json,pitch_study.json}` and `reference/accord/reference/tine-options/{render.py,report.json,qa_results.json}`. The portable bundle records original and bundled SHA-256 hashes in `reference/accord/SOURCE_MANIFEST.json`.

Research basis is the displayed pendulum/common-support mass matrix and Van der Pol sustaining law implemented in the local experiments. Modal synthesis and anti-aliasing follow the repository toolkit and its Luthier guidance. No external DSP library or original product recording is being vendored. Any later external reuse requires its own source/license audit; this handoff makes no license assertion about unseen code.

## 21. As built

### Finite R1 candidate, 1 November 2026

The candidate implements native render model 1, starting from `46a046e8dd24ed50149c0fc1cf96b2a694d59af3`, incorporating base `e838055ac4d907f7de936d32528f4f507c31b842` in merge `fa3bf0baf73756affd8d890758fb4c25b66ff010`, and then merging current upstream `7cb8f34507c664ad671b952f93934c0bc53265c5` with Otis and PRISM's held-preview changes. Those upstream changes leave Accord's shared DSP dependencies and calibration source unchanged. Sections 1–20 retain the proposal; the gain, tail and source-domain corrections recorded here take precedence for the native candidate. This record distinguishes compiled/tested stages from later corrections and listening still pending.

| Area | Implemented candidate |
|---|---|
| Voices / profiles | BRUSH default, WOOD, TINE; BASE for BRUSH/WOOD and REFERENCE, SOFT_ATTACK, BRIGHT_EDGE, LONG_RING for TINE. The compatibility upper ratio remains 5.96. Six named starting recipes are registered; no native profile winner is inferred. |
| Mechanics | Fifteen reciprocal pendulums, 32 state coordinates, Schur acceleration solve, normalized-time RK4 at 256 steps/reference cycle. Work is integrated alongside state; dense cubic Hermite interpolation supplies directional center-passage events. The fixed solver/grid and reference/project initializers are model identities. |
| Initializers / saved state | REFERENCE_SINE_20261022 requires the exact reference seed 20261022 and saved phase/rate vectors. PROJECT_SINE_V1 accepts canonical signed Long seeds. Patch JSON persists model, voice/profile, MIDI, velocity, initializer, source start/duration and controls; raw JSON values are checked before Float conversion. |
| Readout / acoustics | Compatibility state descriptors at 40/reference cycle, 2 kHz pressure readout, fractional audio contact timing and eight feedforward acoustic modes, rendered at 4×. Root and touch do not enter the mechanical equations. |
| Native roughness / filters | Project-seeded Dsp.Noise with a pinned Box–Muller roughness transform, project excitation/output filtering, decimation, DC removal, reference-calibrated gain and boundary fades. Excitation/filter processing uses engine-local Double precision and a tiny-state floor; the floor does not shorten meaningful resonator decay. Native processing intentionally differs from NumPy arithmetic; cross-language PCM identity is not claimed. |
| Gain correction | One constant gain per saved performance, shared across velocity. Its identity includes voice/profile, note, all five controls, full seed/initializer, passage start/duration and rendering model, excluding velocity. Reference .75 and hard 1 renders establish one gain applied throughout that performance. Gain can change after a macro/seed/start/duration edit: this explicitly revises section 11's gain independent of those edits. It avoids attenuating every factory default roughly 14.6 dB for a rare resonance. There is no per-hit normalization, gain follower or mechanical/timing change. Factory eight-second references use 222 frozen profile/note gains, regenerated as calibration version 2 after the tail correction; 24 spot checks matched exactly. Corrected sampled output bounds passed; one cold edited fixture is measured below, without a general latency bound. |
| Controls / duration correction | Defaults PACE .5, COUPLING 1, MISMATCH .25, CONTACT .5, RAFT .5, HOLD 0. Explicit MIDI 36–72; native source .75–8 seconds and start 0–8 reference cycles. The .25-second proposal above is historical: actual short native BRUSH at C2/C3 classified KICK and WOOD at C2/C3 classified KICK/TOM. The .75 minimum keeps even the shortest nominal nonsilent capture above the classifier's 1.5-second duration threshold; eight-second excitation references remain the anchors. Unforced release is max(profile minimum, 1.1 × longest actual modal t60): .9-second minima for BASE/REFERENCE and 2.2-second minima for TINE options. This captures roughly 66 dB of free modal decay without altering the decay law. Native capture length can exceed the original 8.9/10.2-second references. |
| Caches / diagnostics | Immutable mechanical histories keyed by physical parameters, seed, initializer and model; coverage rounded in 16-cycle blocks. Maximum six histories/32 MiB total/12 MiB each; separate 8 MiB completed-audio and 24 MiB roughness-prefix budgets, with owned sample copies. Edited-performance calibration retains at most 64 gains. Probes expose contacts, order estimates, work error, matrix margin, pole/peak/level measurements and stage timing. |
| Integration | Patch/JSON registry, native velocity dispatch, six recipes, preset registry, sixty-one-starter FRESH TAPE registry including Accord's sixteen dry one-shots, kit/audition generators and tests. The 70-engine picker includes BRUSH default and ROOT/TOUCH/LENGTH/TINE profile controls; drafts, YOURS, navigation and reopening copy the complete typed patch. SEND and SPREAD retain authored note authority. Generic CLI factory voice/preset auditions use Presets; Accord-specific CLI patch replay and performance overrides are not implemented. Long files use duration-derived LOOP filing while every Accord starter pad explicitly plays once. |
| Playback scope | Finite only. Any positive settled HOLD is rejected and hidden in the editor. There is no Accord sustained/keygroup adaptation, claimed wrap closure or physical phone/MPC acceptance. Android compilation is recorded separately from emulator execution and performance. |

### Verification record

- Frozen handoff copy: 38 files, including six unchanged approved Python WAVs; 7,485,083 bytes. All 37 BUNDLE_MANIFEST file hashes and the manifest's own byte identity were checked on copying. The original archive under `reference/accord` is not edited to report native work.
- Luthier resource checker: passed in the implementation checkout.
- Corrected standard Android debug assembly and instrumentation compilation at `fac839f098f5d9095e82df0ee3df42b7892941d8`: **exit 0**, 28 seconds. Earlier SDK assembly compiled all four native ABIs. Compilation is distinct from instrumentation execution; the functional result below used the explicit non-debuggable validation build.
- Opt-in non-debuggable validation assembly: **exit 0**, 4m25s, including vital lint. The optional `scripts/android-aot-validation.init.gradle` keeps the debug variant's source/dependencies/signing and changes debuggability only when explicitly supplied with `--init-script`. No test is disabled. This Android 15 AOSP ATD environment previously returned shell compilation success for the debug package while retaining `verify`. The target-package `status=speed` check is necessary but insufficient for instrumentation: artifact compilation does not prove runtime acceptance. Initial setup commands and their limits are in [ACCORD.md](../../ACCORD.md#software-emulator-functional-validation); this is not a physical-phone latency benchmark.
- Initial instrumented ART attempt: **2/3 methods passed; save/send timed out at 180 seconds**, 241.562 seconds total, with unchanged assertions and no refusal/error toast. Native solver code was present and the 961/371/79 DEX code units were below ART's default 10,000-unit huge-method threshold. The artifact's `PCL[]` context mismatched the instrumentation prefix, motivating the exact-context setup used below.
- Actual unchanged Accord instrumentation gate: **3/3 passed, zero failures/skips, `OK (3 tests)`**, runner 163.048 seconds / wall 172.537 seconds, at source `fac839f098f5d9095e82df0ee3df42b7892941d8`. API 35 AOSP ATD under software TCG; non-debuggable debug variant with the same source/debug dependencies/signing and tests. Official ART full-speed compilation used the exact observed initial flat instrumentation context, took 580.888 seconds and retained an enabled finite 1,200,000 ms compiler watchdog. Backup, ownership, permissions and SELinux labels were preserved; no context wildcard or guard bypass was used. Native integrate/derivative/energy code was 6,472/2,044/632 bytes. Discovery ran no functional methods. The initial flat loader matched and an executable OAT mapping was observed during the real run, while later child/image-context warnings remained; no precise executing solver program counter was captured. The functional pass is the gate, not an assertion that every solver call used AOT. The existing 180-second SEND bound and all typed-recipe assertions remained unchanged. This does not establish a default-debug instrumentation pass or physical-phone latency.
- Functional APK provenance: target SHA-256 `687e8f41fbc23eb61d880f02da14aa1b922e8cc8b35bd8fd0468f16cd5d5e252`; unchanged test APK `350be9e79c6e814706a43b38007d0d814c39d8aa7de54dff385b7f059f965c7a`. The test APK defines no synth classes, ruling out a duplicate solver loaded from it. Setup and limits are documented in [ACCORD.md](../../ACCORD.md#software-emulator-functional-validation).
- Subsequent integration fixes at `c57f6161`/`578d9119` retain complete Accord recipes during preset promotion, bound Meridian's sampled keyboard and preserve legacy Crease descriptor bytes without changing standalone NoteOn metadata semantics. Accord DSP, calibration and generated PCM are unchanged. The Android functional result above remains scoped to `fac839f0`. The source comparison confirms unchanged listed Accord DSP/preset/patch sources, SynthScreen, C++ and frozen references; it does not establish complete binary identity or execution of the final rebuilt APK.
- Meridian distribution generator repair at combined commit `00a1f9ff`: explicit `spanKeyboard=false` keeps the exported first low note at 36 and the declared C2–C5 range at 36–72, matching its manifest and original assertions. Only the distribution-tool call changed; sample rendering, loops, layers and assertions/goldens are unchanged. This target includes the Keel fixture correction below.
- Inherited Keel fixture correction, test-only commit `986b789b`: default BELLY/MIDI 30 measures +5.28 cents with the unchanged independent NSDF helper. The house reader's biased-ACF ratio .804 was below .85, and the default .575-second capture truncated its .35–.60-second analysis window. All nine voice/note pairs now have an added default HOLD=0 NSDF ±50-cent guard and retain the existing house ±50-cent/confidence ≥.3 checks at uniform finite HOLD=.65 in the same window. Source audit and independent review confirm that this HOLD range changes only envelope/capture; no production code or tolerances changed. This is a fixture correction, not a Keel or Accord retuning.
- Latest C++ host harness on the unchanged merged C++ tree: **exit 0, 6/6 passed**, 8.98 seconds. These cover the existing native playback/processing engines; Accord DSP itself is Kotlin/JVM.
- Corrected native pipeline: **exit 0**, 3m36s. Tasks were `:synth:test` filtered to Accord tests, `:synth:generateAccordValidation`, `:synth:generateAccordRateReference`, `:synth:generateAccordCalibration` with `-PaccordCalibrationArgs="--verify spot"`, `:synth:generateAccordAudition` and `:synth:generateAccordKit`.
- Focused native tests after correction: **43 passed, zero failures/errors** (13 mechanics, 6 acoustics, 6 patch, 3 preset, 15 engine tests).
- Initial wider headroom run: **failed** across 360 full eight-second cases, with 89 output-peak violations and maximum 4.824982 against .94001. All 360 outputs were finite. The worst measured tail retained last-100-ms RMS at −33.88 dB relative to its active interval before the final fade. This remains the evidence for the gain/release correction, not a claimed all-domain failure of the corrected candidate.
- Corrected output matrix: **429/429 passed**, eight-second excitation in every case. Maximum actual output peak .850000024 against .94001; maximum absolute mean 2.514e-5 against .005; all samples finite. Worst last-100-ms pre-fade RMS was −57.3523 dB relative to active excitation, passing the −50 dB target. Forty-eight repeated contexts shared exactly one calibration gain across positive velocities; 48 intermediate .5/.9 cases passed. The former worst LONG RING resonance also passed a monotonic 21-step 0–1 peak/RMS sweep. Zero touch uses an exact-silence bypass and reports zero calibration work.
- Higher-rate study: six deterministic-contact cases at PACE 2/4 cycles per second. Independent 4× parity maximum 2.91e-10 absolute. Native-support-matched 8× comparisons had aligned 20 Hz–10 kHz residuals −89.32 to −69.78 dB, below the −60 dB target. Independently rounded nominal widths gave −51.45 to −40.84 dB; this includes changed pulse support and is not alias energy. The separately isolated sampled-Hann folding probe reached at worst −78.59 dB relative to unit DC over 20 Hz–10 kHz. See methods below for scope and fitting limits.
- Calibration / exports: all 222 native model-1 calibration-version-2 gains regenerated; 24 profile/register spot checks matched exactly. Generated 71 native audition performances, four common-gain mechanical diagnostic sidecars and six unchanged Python prototypes, plus the sixteen-pad MPC 3/MPC 2 kit. Independent file inspection confirmed audition sample/archive hashes, all six prototype copies against the frozen originals and maximum PCM24 encoding error 5.96e-8. Kit staging/export preflight passed; sixteen sample hashes/frame counts, mono 44.1 kHz PCM24 format, complete model-1/HOLD=0 recipes, one-shot playback metadata and ZIP contents were independently verified. Native listening and physical MPC loading are separate pending gates.
- Desktop wall timing for the default BRUSH patch: 537.8 ms cold, 272.1 ms with prepared mechanics and 2.10 ms exact completed-audio recall; PCM was byte-identical across those paths. Linux amd64, OpenJDK 21.0.13, four visible processors, one sample per path. A separate cold host render of the edited A01 test fixture took 376.15 ms (mechanics 50.89, shared calibration 231.97, actual-touch audio 85.87): TINE Bright Edge, MIDI 63, velocity .37, CONTACT .61, PROJECT_SINE_V1/Long.MIN_VALUE, start .75 cycles, source 1.04 seconds, 142,884 output frames. The different source lengths prevent treating these as a controlled speed comparison; neither measurement supplies a phone p95 or an all-edit latency bound.
- Independent pre-tail-correction Float-force versus Double-force comparison: six C3 profiles, reference seed/initializer and velocity .75. Output difference RMS was −129.81 to −132.66 dB relative, maximum absolute difference 4.47e-7, correlation at least .999999999999969. PCM16 changed 114–237 frames/profile by at most one LSB. An isolated sparse-filter benchmark improved from 611 ms to 20 ms; the engine-local tiny-state-floor difference was at most 9.53e-101. These measurements establish neither perceived equivalence nor final corrected-candidate latency; no listening claim is made.
- Final unfiltered nine-module JVM gate at clean `00a1f9ff67cecf44076fb2472db2d7dab921ec1f`: **actual process exit 0**, 1h30m58s, **8,010 tests, zero failures/errors/skips**. Expanded command: `env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew --no-daemon --max-workers=4 -Dkotlin.daemon.options=autoshutdownIdleSeconds=30 test --continue`, with no local.properties, test filters, exclusions or skip switches. All nine test tasks were reached and have completed XML; synth reran all 4,403 tests, while the other eight unchanged module tasks were validly up to date. The repaired Keel and Meridian cases passed. Earlier failed attempts were archived separately and are not substituted for this result.
- Final Android builds at `00a1f9ff67cecf44076fb2472db2d7dab921ec1f`: standard `:app:assembleDebug :app:compileDebugAndroidTestKotlin` **exit 0**, 154.627 seconds; optional non-debuggable `:app:assembleDebug` **exit 0**, 157.730 seconds. Both APKs passed ZIP CRC and signature verification. The final selected `SnipSnap-Accord-development.apk` is debug-signed with `debuggable=false`, 46,461,883 bytes, SHA-256 `fd66d3dc1df63ba98401d05f5cfefb3cfe25e83edb14b98688afdc04de75fc82`. The standard debuggable APK is 56,046,124 bytes, SHA-256 `2488f52ee2b9a680aa921091ff93e0d919b4462c330bcfb7778249983da8020e`. The optional init script SHA-256 is `829159173f7869a6af25fe25caa2cd56bc9fdfcc090b32368880e2f28b9e40bd`; source/dependencies/debug signing remain unchanged. Final build provenance is published beside the selected APK as `Accord-APK-provenance.json`. This rebuilt artifact was **not device-executed**; the three-method result above belongs to the earlier artifact.
- Owner native listening and physical phone/MPC checks: **pending**. The emulator was stopped before the successful JVM retry. Broader render-cost acceptance and phone latency values in section 14 remain targets, not measurements.
- Owner listening: Python direction accepted before implementation; the native render, full controls, project RNG and production levels are **not yet accepted**. Phone/MPC performance and playback checks remain open.

The final JVM XML totals are:

| Module | Tests | Final task status |
|---|---:|---|
| json | 16 | Up to date |
| xpm | 48 | Up to date |
| audio | 457 | Up to date |
| kit | 463 | Up to date |
| mpc3 | 95 | Up to date |
| synth | 4,403 | Executed |
| loop | 426 | Up to date |
| cli | 262 | Up to date |
| shell | 1,840 | Up to date |
| **Total** | **8,010** | **Zero failures/errors/skips** |

The final APK build/source/variant/hash is recorded above. Preserve the distinction between rebuilt artifact provenance, the earlier scoped functional run and remaining listening/hardware gates.

### Native validation methods and limits

The full reports are generated at `testkit/accord-validation/report.json`, `testkit/accord-rate-reference/report.json` and `testkit/accord-audition/manifest.json`. Calibration source provenance is committed in `synth/src/test/resources/accord/calibration-model-1.json`. Generated manifests preserve source hashes and the dirty-tree state at generation; outputs do not gain release provenance merely by sharing a commit label.

The output matrix includes all six acoustic profiles, C2–C5 octave roots, soft/reference/hard touches, a three-value COUPLING/MISMATCH/RAFT grid, PACE/CONTACT endpoints and five-step pace, measured period-derived resonances including a strongly synchronized trajectory, selected signed project seeds and a scattered passage start. Intermediate touches are held out of the normal/hard calibration. The generator inspects production Float PCM before WAV encoding, without an extra normalization pass. It is a finite sampled matrix, not an analytic all-seed/control bound.

Tail RMS is measured on the conditioned unforced continuation before calibration and before the 4 ms fade, comparing the last 100 ms with active-source RMS over 0–8 seconds. The shared scalar cancels in that ratio. This test observes the capture boundary instead of allowing the fade to conceal an early cut.

The independent rate path reprojects exact public contact events into separately implemented 176.4/352.8 kHz force, modal and house-conditioning paths. It tests WOOD, TINE Reference and TINE Bright Edge at C5/hard touch/CONTACT=1 with PACE 2 and 4 cycles per second. One 8× comparison independently rounds the nominal kernel width; another preserves the native kernel's actual time support. Comparison uses 0.1–7.9 seconds before calibration, reports unfitted error, then fits one global cubic-interpolated delay and scalar gain for the aligned full-band/protected-band residuals. Rate-dependent filter/modal phase and fractional-event interpolation remain contributors, so waveform residual is not called alias energy.

The separate kernel-folding calculation subtracts the exact continuous, support-matched Hann transform from its sampled-kernel DTFT over 20 Hz–10 kHz, relative to unit DC. It precedes fractional-event interpolation and acoustic filters. High-band FFT power is reported as available out-of-band energy, not directly as folded energy. BRUSH's independent-rate noise is excluded because changing its random draw rate would compare different excitation histories. These limits prevent the six deterministic comparisons from becoming an unsupported whole-engine alias guarantee.

The audition distinguishes exact production `raw/`, optional active-interval `matched/`, and four `controlled-gain/` mechanical sidecars. Those sidecars apply one common scalar across the group, followed by identical production fades; they preserve cross-setting conditioned-amplitude differences for a causal listen and are explicitly not production engine levels. The copied Python references retain their original PCM and durations. No numerical comparison supplies the owner's listening judgment.

## 22. Why it is built this way

- The support really pushes back on every oscillator. Without that reciprocal term, the gathering would be a scheduled effect rather than the instrument's behavior.
- Timing and note are separate. The mechanism can reorganize its contacts while the resonators keep the requested pitch.
- Three accepted voices and one small TINE profile family preserve the listening work without multiplying the physical model or inventing more untested materials.
- A gain constant throughout one saved performance and shared across velocities preserves soft/hard touch and its evolving contact envelope. Recalibration after control or passage edits protects headroom, explicitly trading away the proposal's cross-setting level invariance; per-hit leveling would erase the gesture.
- The risky step is the native conversion: solver precision, RNG and house filters can change the accepted result. Saved fixtures and a matched native audition make that change reviewable before release.
