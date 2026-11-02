# Accord mechanics, numerical solver, and cache recommendations

Audit date: 31 October 2026. This is a mechanics contribution to the engineering handoff, not an implementation. The characteristic gesture remains scattered pitched contacts → partial organization → a collective pulse when the selected mechanical state permits it.

## Evidence and boundaries

Read: `/workspace/accord-probe/mechanics.py`, `validate.py`, `report.json`, and the saved event/trajectory assets; the musicality renderer's mechanical input selection; the local Luthier conventions; and the local `Dsp`, `Keys`, and `PitchwheelPatch` references. The repository reference snapshot was originally fetched at `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`. This audit does not assert that snapshot is the repository head on 31 October.

The Python study implements 15 literal reciprocally coupled nonlinear pendulums. It uses SciPy DOP853, double precision, `rtol=1e-9`, `atol=1e-11`, dense-output crossing roots, a NumPy RNG, and sinusoidal initialization. Its saved smooth states have 40 samples per reference cycle. It has no implemented production cache, no Kotlin solver, no measured fixed-step solver, and no HOLD construction. Later audio studies reuse that one mechanical trajectory; changing a tongue, attack, or decay in those studies does not constitute another synchronization probe.

The fixed-step solver, cache limits, macro domain, and acceptance targets below are production proposals. Existing numerical observations are labeled measured. They must not be quoted as Kotlin performance or evidence that every patch gathers.

## 1. Units and the state that is actually integrated

Use normalized mechanical time `tau`. The integrated 32-double state is:

```text
y = [theta[0..14], omega[0..14], x, v]
omega_i = d theta_i / d tau
v       = d x / d tau
```

Angles are radians. Set `m_i=.02`, `r_i=1`, `g=1`, so `h_i=m_i*r_i=.02` and `k_i=m_i*r_i*g=.02`. The reference inertia is `.024`; the reference small-angle angular frequency is `wRef=sqrt(.02/.024)=.9128709291752769` radians per normalized time unit. Define the reference period once:

```text
TRef = 2*pi/wRef = 6.882884651454571 normalized time units
c = tau/TRef     // canonical reference cycles, not observed oscillator cycles
```

`theta0=.38` is the angle at which the van der Pol drive changes sign. It is not the swing amplitude. The measured isolated oscillator with `mu=.1` settles near `.7624314263` radians, close to the weak-drive prediction `2*theta0=.76`. Its measured full period is `7.147375221` normalized units, about 3.84% longer than the small-angle reference period. Coupling changes observed frequency further.

Proposed PACE mapping from the primary design is `fRef=2^(2*PACE)` reference cycles per musical second, so the domain is 1..4 and the center is 2. PACE is a traversal speed, not a promise of exact tick BPM. Use one projection for every mechanical quantity:

```text
c(t) = sourceStartCycles + fRef*t
tau(t) = TRef*c(t)
alpha = TRef*fRef
```

If integrating directly against musical seconds, the entire state equation is `dy/dt=alpha*F(y)`. Do not scale only the pendulum velocities or only event times. Keeping the solver in canonical time avoids this ambiguity and makes PACE changes reusable.

Stored `omega`, `v`, and contact speed remain derivatives with respect to `tau`. Physical derivatives after the musical time projection would be multiplied by `alpha`, with acceleration multiplied by `alpha^2`. The approved acoustic study uses canonical crossing speed as its bounded excitation proxy. Preserve that convention so PACE does not secretly multiply every hit's strength in addition to changing contact density. Energy and work diagnostics below are evaluated in canonical units.

## 2. Exact model and Schur solve

For each solver evaluation, with platform-only mass `Mbase>0` and total translating mass `H=Mbase+sum(m_i)`:

```text
Q_i = J_i*mu_i*(1-(theta_i/theta0_i)^2)*omega_i
b_i = Q_i-k_i*sin(theta_i)
q_i = kappa*h_i*cos(theta_i)
bBase = sum(kappa*h_i*sin(theta_i)*omega_i^2)-cb*v
D = H-sum(q_i^2/J_i)
aBase = (bBase-sum(q_i*b_i/J_i))/D
a_i = (b_i-q_i*aBase)/J_i
F(y) = [omega[0..14], a[0..14], v, aBase]
```

This solves the symmetric mass matrix with diagonal pendulum entries `J_i`, platform entry `H`, and off-diagonal reciprocal entries `q_i`. The same `kappa` multiplies both acceleration coupling directions and the centrifugal term. A one-way phase correction or a scaled platform force alone is another model and is excluded.

Use `mu_i=.1` and `theta0_i=.38` for all mechanisms in the proposed initial domain, retaining the measured family's equations. Do not scale `mu_i` with each natural frequency without a model revision; that changes the tested drive law. There is no centering spring: `x` does not appear in `F`. Acoustic resonator output has no mechanical feedback in this version.

At `kappa=0`, with `x=v=0` initially, the platform stays stationary exactly and each oscillator is independent. RAFT may continue to change the acoustic body. CONTACT, note, velocity, and output processing must not reconnect the mechanics.

Evaluate sums in a fixed mechanism order, using primitive double arrays and reusable stage buffers. Do not allocate a matrix, collection, or lambda per RHS call. Retain a direct 16-by-16 mass-matrix solve in tests, not in the render path.

## 3. Proposed macro domain and global positive margin

The primary design proposes this compact domain, anchored to the measured gathered fixture:

| Quantity | Proposed law | Default |
|---|---|---:|
| PACE | `fRef=2^(2*p)`, 1..4 reference cycles/s | `p=.5`, 2 reference cycles/s |
| COUPLING | `kappa=c` | `c=1` |
| MISMATCH | `spread=.04*m` | `m=.25`, spread `.01` |
| RAFT | piecewise log interpolation through the table below | `.5` |
| CONTACT | acoustic excitation only | selected by voice |
| Source start | `sourceStartCycles=8` for the approved musical study, 0 for raw diagnostic starts | 8 |
| Source duration | explicit `.25..8` musical seconds | 8 |
| HOLD | 0 only in the first implementation; nonzero is explicitly refused | 0 |

| RAFT | Platform-only mass | Platform damping |
|---:|---:|---:|
| 0 | `.05` | `.005` |
| .5 | `.6` | `.05` |
| 1 | `2.4` | `.2` |

For example, below the center, `Mbase=.05*12^(2*r)` and `cb=.005*10^(2*r)`; above the center, `Mbase=.6*4^(2*r-1)` and `cb=.05*4^(2*r-1)`. These laws are continuous. Their derivative changes at the center; smoothing that derivative would be another explicit shape proposal. The low endpoint is related to the measured splay example, the center matches the gathered example, and the high endpoint is unmeasured. Increasing RAFT is not a verified monotonic synchronization control.

Generate stable bounded, zero-mean offsets `eta_i` once per mechanical seed context. Then:

```text
wNatural_i = wRef*(1+spread*eta_i)
J_i = k_i/wNatural_i^2
```

For `|eta_i|<=1` and the proposed maximum spread `.04`:

```text
Jmin = .024/(1.04^2) = .02218934911242603
Jmax = .024/(.96^2)  = .02604166666666667
Jmin > m*r^2 = .02
Hmin = .05+15*.02 = .35
D >= Hmin-15*(.02^2)/Jmin = .35-.2704 = .0796
```

This is a global conservative bound over every angle and `kappa in [0,1]`; it does not need zero-mean offsets. Verify the actual patch-specific bound `H-kappa^2*sum(h_i^2/J_i)` before integration. At runtime a failed positivity check is a fault. Never replace `D` by a small positive clamp and continue. Debug conservation tests may disable damping or drive explicitly; those test switches are not production macros.

## 4. Deterministic initialization and the approved fixture

The Python seed integer `20261022` is not portable by itself. NumPy and `Dsp.Noise` produce different sequences, and the Python harness draws phases and offsets successively from one generator. Switching generators or switching from the sinusoidal approximation to a limit-cycle table changes the gathering history.

Preserve the existing measured fixture as a versioned regression input. Its phase and offset vectors are given in Appendix A. Its initializer is:

```text
theta_i(0) = .76*cos(phi_i)
omega_i(0) = -.76*wNatural_i*sin(phi_i)
x(0)=v(0)=0
```

Those numbers originated in an actual seeded pseudorandom draw. They may be stored as generated model-fixture data; they are not external audio assets. Keep the fixture's `SINE_V1` initializer identity explicit. Do not describe it as a measured isolated limit-cycle initialization.

For additional production seed contexts, derive independent phase, rate, and acoustic-roughness substreams using verified `Dsp.seedFor` and the project's `Dsp.Noise`. Seed inputs include an engine namespace, a mechanical seed-version identifier, the persisted mechanical seed context, and a substream name. Exclude note, velocity, CONTACT, decay, brightness, RAFT's acoustic-only settings, duration, and display names. Acoustic-only voice changes must not select new mechanics unless explicitly designed to do so. A global acoustic model-version increment must not silently reseed the mechanical substreams; use a separate versioned seed contract.

One deterministic rate-offset algorithm is to draw 15 values in [-1,1], subtract their mean in double precision, and divide by the maximum absolute centered value. If that maximum is effectively zero, use all zeros. Verify boundedness and near-zero mean. MISMATCH then scales this fixed vector; turning MISMATCH must not draw a different vector. Draw phases once and keep them fixed. There is no ongoing phase noise.

A measured isolated limit-cycle initializer is a later calibration step. Its phase must be uniform in measured cycle time, not a uniform polar angle in `(theta,omega)`. Because the current model keeps `mu` fixed while varying `J`, its isolated cycle changes slightly with rate. Generate or interpolate a versioned rate-dependent table and validate its residual/transient behavior. Replacing `SINE_V1` in an approved fixture requires a new fixture and listening comparison; it cannot inherit the old fixture's gathering evidence automatically.

Random initial pendulum velocities with `v=0` generally imply nonzero horizontal momentum. Low platform damping can therefore produce a large displacement offset; zero damping can produce translation drift. This is compatible with a free platform. Do not add a hidden spring or recenter velocity to make `x` look tidy. Track displacement and velocity separately and distinguish translation drift from energy growth.

## 5. Research oracle and proposed production fixed-step solver

Keep DOP853 with the measured tolerances as the research reference. The Python files are an oracle for selected fixtures, not a callable JVM dependency.

Proposed Kotlin first candidate: classical fixed-step fourth-order Runge-Kutta in canonical time, with 256 steps per reference cycle. Thus `deltaTau=TRef/256`, approximately `.02688627`. Equivalently, advance `dy/dc=TRef*F(y)` at `deltaC=1/256`. Each step evaluates the full Schur system four times. Store a state at every fixed knot. All stages, sums, and event decisions use doubles.

The 256 value is a shape candidate, not a measured accuracy result. Compare it with 512 steps/cycle and with DOP853. If it fails the gates below, increase the fixed resolution or retain a validated deterministic adaptive implementation. Do not repair the discrepancy by phase correction, energy renormalization, resynchronizing at block boundaries, or hiding a different solver behind the same model version.

The benefit of fixed canonical steps is a duration-independent prefix and exact continuation checkpoints. A request for 24 cycles must produce the same first 24 cycles as a request for 48 cycles, including contact ordinals. Solver work cannot depend on wall-clock timing, cache eviction order, or the size of an audio block. With 256 steps/cycle, the maximum normal 40-cycle request costs 40,960 RHS evaluations; computing it to a 48-cycle chunk boundary costs 49,152. These are operation counts, not timing measurements.

Use stable math and fixed operation order. If byte identity must span different JVM/Android runtimes, evaluate the portability of the selected transcendental implementations and pin that contract with goldens. A fixed seed alone does not prove cross-runtime byte identity. Cancellation discards incomplete new chunks. Time limits must not produce machine-dependent truncated trajectories; use deterministic step/work caps and report cancellation separately.

## 6. Contacts, continuous pressure, and timing

An ordinary contact is a directional center crossing `theta_i=0` of that mechanism. Opposite directions remain distinct. Keep the event record in canonical time:

```text
Event(cycleTime: Double, mechanism: Int, direction: +/-1,
      canonicalSpeed: Double, perMechanismOrdinal: Long)
```

The Python event assets use the first four fields; their times are not an audio-grid raster. They were found from dense DOP853 output, even though the saved smooth state arrays are coarser.

For the fixed-step proposal, reconstruct `theta` within a step with cubic Hermite interpolation using the two endpoint angles and their endpoint `omega` derivatives. Find the zero in the appropriate subinterval. Inspect the Hermite derivative's extrema if needed so same-sign endpoints cannot hide multiple candidate roots. Derive direction and crossing speed from the same interpolant. Linear interpolation of event times is an alternative only if it passes the same timing comparison.

Use hysteresis only to rearm a direction; never move the emitted root from `theta=0` to a hysteresis edge. A proposed initial rearm angle is `.002` radians, much smaller than the measured swing. Keep a short per-mechanism interval guard in canonical time and validate it against all reference events. The guard's purpose is duplicate rejection; a rejection of a genuine passage is a diagnostic failure. Determine the final guard and minimum valid crossing speed from the production parameter sweep. Their numerical thresholds have not been measured by the current Python extraction, which has no explicit hysteresis or interval guard.

Specify the initial-root policy and segment ownership: a crossing at a chunk boundary belongs to exactly one chunk, and a root at source time zero is emitted at most once. A convenient policy is half-open chunks `[start,end)` and final inclusion handled only by the consumer. Initial rearm state must be derived from both angle and motion direction so a start inside the narrow zone does not lose its first passage.

Project event time to audio with `(event.cycleTime-sourceStartCycles)/fRef`. Preserve its fractional internal-sample position. Never snap to an MPC grid, merge mechanisms into one canned strike, or sort only by a rounded sample index. Use stable tie ordering `(exact time, mechanism, direction, ordinal)`.

For continuous pressure, the existing study reads a geometry-gated sum proportional to `max(0,1-(theta/.17)^2)^2 * min(abs(omega)/.8,2)/15`. It interpolates the coarse saved trajectory and then resamples the result at 2 kHz. That is an acoustic mapping, not a measured 2 kHz mechanical integration rate. The production version can evaluate a corresponding bounded descriptor from interpolated state at an initial 1 kHz musical control rate, with a 4 kHz comparison. Short contact kernels remain at the internal audio rate. CONTACT changes those kernels, brightness, and brushing, not `Q_i` or the mechanical trajectory.

For a platform-acceleration acoustic readout, reevaluate the Schur acceleration from the interpolated state, apply the separately documented bounded acoustic map, and make it exactly zero at `kappa=0`. Do not mistake low-frequency platform translation for an audio waveform. Keep the acoustic mapping outside the dynamics.

## 7. Source selection, horizon, and cache

The approved musical studies select an eight-second excitation segment beginning at musical second 4 of the two-reference-Hz trajectory. This is `sourceStartCycles=8`, with mechanical preroll only; their resonators start from zero at the selected audio segment. Save the canonical start, not a permanently fixed four-second preroll, if PACE is intended to change traversal speed without selecting a different gathering stage. At the proposed PACE extremes, this eight-cycle preroll corresponds to 8 or 2 hidden musical seconds.

For the proposed finite source duration:

```text
cEnd = sourceStartCycles + fRef*sourceSeconds
requiredCanonicalHorizon = cEnd plus the interpolation endpoint
```

The maximum normal request is `8+4*8=40` cycles. The default is `8+2*8=24` cycles. The zero-excitation acoustic release does not require further mechanical integration. Contact pulses extending beyond the source gate are governed by the documented excitation taper, not by starting new post-source contacts.

Use two cache layers; all class names here are proposed.

| Layer | Key content | Data |
|---|---|---|
| Mechanical trace | mechanics/model version, parameter-table version, solver/version/resolution, initializer version or fixture ID, persisted mechanical seed context, exact normalized COUPLING/MISMATCH/RAFT mechanical values | immutable canonical fixed-step state knots, exact contact ledger, continuation checkpoint, diagnostics |
| Acoustic descriptors/render | mechanical trace identity, source start/duration, PACE, CONTACT and acoustic settings, note, velocity, acoustic model version, output/HOLD mode | projected event kernels and pressure, acoustic states or rendered output as appropriate |

The mechanical key excludes PACE, note, velocity, CONTACT, timbral-only voice settings, source duration, acoustic release, and output gain. PACE and duration determine how far a trace must be extended. A longer request extends the same canonical prefix; it does not replace the trace with a fresh integration that changes earlier samples. Source start is a lookup selection unless a named preset explicitly changes initial conditions. Include any such initial-condition change in the mechanical identity.

Use exact canonicalized finite control bits or the exact derived parameter values. Do not silently round sliders to a hidden coarse grid to improve hit rate. If a future version exposes quantized mechanical controls, make their quantization part of the public parameter contract. Persist enough model identity in the patch JSON to select the same tables, seed algorithm, initializer, and solver. `Dsp.seedFor`, `Dsp.Noise`, and versioned fields patterned after `PitchwheelPatch` exist in the inspected reference; no general Accord cache API has been verified.

Proposed cache policy: extend in immutable 16-reference-cycle chunks, keep a byte-accounted 32 MiB process-local LRU, and support concurrent same-key requests through one in-flight computation. Normal source requests occupy at most 48 cycles. At 256 steps/cycle and 32 doubles per knot, that is approximately 3.15 MB of primitive state data, plus contacts and small metadata. The default 32-cycle trace is about 2.10 MB. Managed-object overhead must be measured; do not store one object per knot. Diagnostics may request up to a separately bounded 256 cycles, approximately 16.8 MB of primitive state data at this resolution. The cache is an optimization only: hit, miss, eviction, and request order must render identically.

Cache hits make note, velocity, and CONTACT rerenders cheap mechanically. COUPLING, MISMATCH, and RAFT changes generally miss and must be timed as cold simulations. A cache is not an adequate answer to those knobs' render latency by itself. Interpolating between unrelated cached trajectories is not the same physical model, particularly near changes of collective behavior, and must not be introduced silently.

Measured Python mechanics-only cost: 16 reference cycles at target DOP853 tolerances took `.180428351` seconds and 6,623 RHS calls. The 140-cycle gathered solve in the saved final report took `1.870362898` seconds and 66,002 RHS calls. These numbers exclude contacts extracted afterward, acoustic rendering, leveling, and Kotlin/JVM overhead. They are not a benchmark of RK4 or the proposed cache. Record cold mechanics, warm mechanics, and complete-render p50/p95 on a named host before committing a latency claim. The house rule remains a rerender in tens of milliseconds; failure of that target requires profiling and a disclosed limitation, not fewer hidden mechanisms or forced phase alignment.

## 8. Diagnostics and fault handling

For the canonical model, use:

```text
T = .5*H*v^2 + sum(.5*J_i*omega_i^2
                  + kappa*h_i*v*cos(theta_i)*omega_i)
V = sum(k_i*(1-cos(theta_i)))
E = T+V
dE/dtau = sum(Q_i*omega_i)-cb*v^2
P = H*v+sum(kappa*h_i*cos(theta_i)*omega_i)
```

Momentum is conserved only when platform damping is zero. The driven, damped model is not an energy-conservation test. Integrate work with the same solver, or use an independently converged quadrature. Do not claim conservation after normalizing velocities or subtracting a fitted drift from the answer.

Estimate phase from same-direction interpolated crossings. The saved report assigns phase linearly between consecutive positive-direction crossings and excludes incomplete edge cycles. Its R1/R2 values are therefore preferable to the scout's instantaneous `atan2(-omega/wNatural,theta)` proxy. Record mean frequencies and pairwise phase-range changes over declared windows as well as order parameters and a contact raster.

R2 describes two-passage phase concentration; it does not prove one full-cycle in-phase state. Two anti-phase pendulum groups can cross the center at the same audible tick times because each pendulum ticks twice per cycle. Report mechanical phase organization separately from the actual directional contact concentration. A frequency-locked splay can have very small R1 and R2 and no unified pulse.

Record at least: solver identity, step count/RHS work, minimum D and analytic bound, maximum angles/angular rates/base velocity/displacement, event counts and rejected-candidate reasons, energy/work residual, and any termination reason. Acceleration and energy state must remain finite before output limiting. Bound work by deterministic counters and finite horizon. Derive any amplitude/rate guard thresholds from the full-domain probes; the current one-fixture study does not establish safe production extrema for the proposed table. Runaway handling stops new excitation with a short deterministic fade and reports the failed mechanics result. It must not clamp a trajectory and relabel it valid physics.

## 9. What the existing numerical probes actually establish

Measured by `/workspace/accord-probe/validate.py`; final values are in `report.json`:

| Fixture, same initial phases/rate offsets | Final-window crossing R1 | R2 | Relative frequency spread | Max phase-difference swing versus oscillator 0 |
|---|---:|---:|---:|---:|
| Fixed: Mbase .6, cb .05, kappa 0 | `.3922147695` | `.2768519150` | `.01895379433` | `1.539719561` rad |
| Gathered: Mbase .6, cb .05, kappa 1 | `.9998570990` | `.9994284685` | `2.607373881e-12` | `1.382431947e-9` rad |
| Dispersed mobile: Mbase .05, cb .005, kappa 1 | `.002431676244` | `.01085935768` | `3.273650776e-5` | `.003314641758` rad |

All use `spread=.01`, `mu=.1`, `theta0=.38`, 140 reference cycles, and the final 20 reference cycles as the declared measurement window after excluding incomplete crossing intervals. Initial R1 is `.3863158370`; initial R2 is `.2884852611`. The dispersed case approaches a frequency-locked splay, not a gathered attack.

In the gathered fixture, R1 first stayed above `.95` for a following 20-reference-cycle interval at canonical cycle `19.175`. At two reference cycles/s this is musical time `9.5875` from raw initialization, or about `5.5875` seconds into the approved source beginning at cycle 8. This is a diagnostic observation, not a universal locking threshold. A .25-second source cannot be advertised as completing that same gesture.

The gathered trace's observed D minimum is `.6500227806`, above its patch-specific conservative bound `.6499914946`. Its observed maximum angle is `.9831771853` radians. A read of the saved knots finds maximum canonical angular speed about `.91233` and base speed `.25003`; these knot maxima are not certified continuous-time global bounds. The light/damped splay fixture's displacement reaches `11.42392743` normalized units; position magnitude alone does not diagnose instability on a translation-invariant free base.

Additional measured checks:

- Schur/direct acceleration discrepancy over 20 probe states: at most `2.220446049e-16`.
- Conservative relative energy residual over 30 reference cycles: `6.789541702e-7` at `rtol=1e-7`, improving to `2.555393531e-10` at `rtol=1e-10`.
- Conservative maximum momentum error: `2.506270723e-8`, improving to `4.929251451e-11` at the tighter setting.
- Driven energy/work relative residual at `rtol=1e-9`: `4.749989578e-9`. Momentum changes under its nonzero platform damping; that change is not a conservation error.
- Gathered event-time difference between `rtol=1e-9` and `1e-11`: at most `8.535266716e-9` reference cycles, with unchanged event counts and matching by mechanism, direction, and local ordinal. It is not a comparison of only globally sorted timestamps.

These probes establish feasibility for one curated seed and a contrasting splay state. They do not validate the whole continuous macro domain, phase/rate generation under the project's RNG, a fixed-step implementation, seed robustness, or a cache implementation.

## 10. Measurable implementation gates

Before promoting the proposed mechanical core:

1. Implement the exact Schur equations and verify the analytic `.0796` global bound across parameter corners and representative interior points. Verify `J>=.02`, and compare with a direct mass-matrix solver on independent states.
2. Reproduce the named fixed/mobile/splay reference fixtures from their exact stored vectors. Keep raw-start and eight-cycle-preroll fixtures separate. Compare event count and timing by mechanism/direction/ordinal, crossing-derived diagnostics, and the energy/work ledger.
3. Validate fixed RK4 at 256 against 512 steps/cycle and DOP853, including the fastest supported PACE projection. A proposed event-timing target is below .05 output samples at every supported PACE; at the slowest proposed one reference cycle/s, this is `1.133786848e-6` reference cycles. This target is a new acceptance proposal, not an observed RK4 result. A wrong event count fails regardless of average timing error.
4. Repeat driven-work and drive/damping-off conservation probes at successively finer steps and require convergence. Use independent work accumulation and a sign-flip negative control to show the test rejects injected energy growth. No velocity renormalization is permitted.
5. Prove coupling-zero independence by changing one oscillator's initial condition and showing no other trajectory or base state changes. Verify exact platform-motion acoustic silence at zero coupling while contact-to-body sends remain available.
6. Sweep the full proposed COUPLING/MISMATCH/RAFT domain, seed contexts, and supported source lengths for finiteness, positivity, event guards, realistic runtime, and flagged failure behavior. Do not require every patch to lock. Preserve contrasting loose, gathered, and splay/grouped fixtures after the sweep.
7. Prove cache transparency: cold/warm/evicted renders match, shorter and longer horizon prefixes match, PACE-only changes do not reseed or recompute an existing covered trace, and acoustic changes do not invalidate mechanics. Repeat concurrent same-key requests. Test that changing a true mechanical key does invalidate it.
8. Compare 1 kHz continuous descriptors with 4 kHz and compare the full high-PACE/hard-contact audio path with a denser reference. Exact event times must survive both tests.
9. Measure complete-render latency and output behavior separately; the mechanical result cannot certify root confidence, output gain, aliasing, decay, or classifier outcome. Run the applicable repository gate when Kotlin exists.

## 11. HOLD boundary and future construction

The first implementation specified by the primary design is finite and supports `HOLD=0`. A nonzero HOLD request is rejected explicitly; it must not silently render a nonseamless sample and label it held. No existing Accord export has passed the project's seam criterion. The inspected `Keys.MAX_SEAM_ERROR` is `1e-3`, and `Keys.seamError` requires an actual preceding cycle. A generic shared pitch-preserving loop method has not been verified.

If HOLD is added in a later model, keep these three adaptations separate:

- **Locked:** identify a measured full collective period, reconstruct periodic directional contacts and pressure from each mechanism, and preserve tick/tock parity. A half-cycle cut can swap different directional kernels. A high R1 alone is insufficient evidence that every contact and descriptor state closes.
- **Grouped/drifting:** construct an explicitly cyclic selected history; do not claim the continuing free simulation naturally repeats. Preserve each mechanism's interior event history and disclose any boundary reconstruction.
- **Repeated gathering:** repeat a selected gesture and reset mechanical hidden state only during a gated quiet recovery. Resonators must carry through or be prepared periodically; resetting them at an audible boundary creates a restart accent.

Positive-damped linear modal/filter banks permit a useful future closure method: for one complete discrete excitation period, write the state map as `zNext=Aperiod*z+bperiod` and solve `(I-Aperiod)*z0=bperiod` for its periodic initial state, or converge repeated periods with a declared residual. Periodic roughness, overlapping contact kernels, DC/filter states, decimation history/phase, and slow envelopes must also close. Keep acoustic modal frequencies at the requested root; do not detune them to make an arbitrary loop window fit. Choose integer output-frame loop lengths and validate any tiny descriptor-time adjustment used to fit them. Test the exported output after decimation and leveling, not just an internal force array. Each future adaptation still requires owner listening for click, dip, and restart behavior.

## Appendix A: exact reference initialization vectors

The generated phase vector, in radians, from the existing NumPy fixture:

```text
phi = [
 .8966527134072003, .01371931073871436, 1.032307611146228,
 1.6333219162822996, .8954696745107658, 1.7364706805937027,
 4.686724916365365, 2.881533644331152, .984617281081893,
 2.197295625035535, 4.92245680526012, .4100143347613006,
 1.7232504142699445, 4.663003291521934, 6.222979243758299
]
eta = [
 .2702849749993048, -.2162914734369595, .8923071623543083,
 -.8332611591935914, -.7394243316245537, -1.,
 .7656632647226754, .5644466614602919, .21773618789379703,
 -.3851930196340212, .38047645960622606, -.6053016041020003,
 -.11061557259250432, .4369388347714392, .36223361477558763
]
```

Use these with `spread=.01` and `SINE_V1` to reproduce the measured fixture. `m_i`, `h_i`, `k_i`, `mu_i`, and `theta0_i` are as stated above. Gathered and fixed comparisons differ only in `kappa=1` versus 0 with `Mbase=.6`, `cb=.05`. The splay comparison uses `Mbase=.05`, `cb=.005`, `kappa=1`.

## Why it is built this way

- The slow mechanical system determines who contacts when. Keeping it separate lets note and timbre change without inventing a different gathering history.
- Canonical cycle time preserves the same physical trajectory when PACE changes and makes cache continuation predictable.
- Exact crossings retain the heavier coherent attack when mechanisms agree. A control-rate tick grid would blur or manufacture that result.
- A positive mass-matrix margin and work balance expose numerical mistakes before an output limiter can conceal them.
- The risky decision is production solver accuracy and cold render cost across the full macro domain. The existing Python fixture proves the concept can gather; it does not settle that implementation choice.
