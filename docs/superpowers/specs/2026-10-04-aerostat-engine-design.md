# Aerostat — engine engineering specification

The specification below is the engineering proposal this round implemented.
The decisions that diverged from it, and the constants that are still
listening guesses, are in **R1, as built** at the end. Roadmap row S24.
The audition page is `./gradlew :synth:generateAerostatAudition`, then
`testkit/aerostat-audition/index.html`.

Version: 0.1 • 3 October 2026 • Working name, not cleared for release

## 1. Product intent

Aerostat is a pitched percussion and airflow instrument imagined as a floating balloon vessel containing a pressure engine and two banks of struck tubes. A strike creates a hollow tube note and an air pulse. That pulse spins a turbine, whose linkage opens a valve. The valve feeds a tuned airflow resonator at the same pitch as the tube. Exhaust changes the vessel’s thermal state; the vessel rises and settles, changing the sound’s loading and radiation.

The signature gesture is **knock → mechanical catch → blooming whistle → pressure relaxation → floating tail**. These events must be causally connected. A tube hit followed by an unrelated synth pad does not meet the brief.

This document specifies an offline DSP instrument for SnipSnap. It is an engineering proposal, not a validated implementation or a plan for a passenger balloon, boiler, or pressure vessel. The imaginary mechanism is deliberately exaggerated where that improves musical expression.

## 2. Scope and platform assumptions

Use the supplied SnipSnap stack summary as the integration baseline. Confirm the actual repository interfaces before implementation: no source checkout was inspected for this specification.

V1 produces deterministic, pitched mono samples through the existing voice and patch interfaces. Final output is 44.1 kHz; use the existing four-times internal rendering and decimation path where appropriate. Reuse project tuning, seed, loudness, envelope, and loop utilities instead of adding parallel infrastructure.

Each render starts with a fresh, explicitly initialized reservoir and vessel state. It does not inherit pressure or altitude from other independently rendered pads. Shared pressure operates between the two banks within one render. A separate phrase audition can retain state between diagnostic strikes; that is a development harness, not a promised change to the public rendering API.

Both banks play the requested note in V1. Their mechanical response and overtone spectra differ. Do not introduce random pitches, chords, or an independent bass voice. Multinote persistent performance is a future extension requiring an explicit event API.

## 3. Audible requirements

At the default patch, the listener should hear:

1. A brief hollow, pitched strike with a soft paddle-like contact and displaced air.
2. A short mechanical delay before the tuned airflow speaks.
3. A sustained tone with breath, an edge, and a clear relationship to the struck note.
4. A subtle secondary arrival from the heavier bank.
5. A tail that changes as pressure falls and the vessel responds.

The direct tube must remain musically useful when a gentle strike does not open a valve. Turbine noise is a supporting texture, not a continuous engine drone. Steam-like hiss must follow flow. Balloon motion should be heard through timbre and decay, with small bounded pitch movement at most.

Do not use a prerecorded whistle or steam loop as the primary engine. Do not make pressure merely a filter knob, inertia merely a delay knob, or lift merely an LFO depth.

## 4. Causal architecture

| Stage | Input | State or mechanism | Audible consequence |
|---|---|---|---|
| Tube strike | Contact and strike strength | Tuned tube modes plus finite air displacement | Hollow attack and pressure pulse |
| Turbine | Displaced air pulse | Rotor speed, inertia, drag | Catch delay and optional quiet flutter |
| Linkage | Rotor speed and angular travel | Opening threshold, valve position, return spring | Flow onset, duration, and closure |
| Reservoir | Engine replenishment and valve demand | Shared normalized pressure | Competition between banks and changing tone strength |
| Airflow resonator | Valve flow | Jet/edge excitation and tuned feedback | Pitched whistle bloom |
| Vessel | Integrated hot exhaust | Temperature proxy, altitude, vertical speed | Slowly changing loading and radiation |

Render the direct tube and airflow branches separately until their final sum. Retain diagnostic taps for every state variable. Sharing pressure must happen before the resonators, not through a compressor on the final mix.

The pressure engine replenishes the reservoir. The strike pulse supplies the turbine’s initial energy. Rotor motion opens access to reservoir energy; it does not power the entire sustained whistle. This distinction is essential to the intended delayed bloom.

## 5. Tube banks and strike excitation

Use two parallel tube banks, provisionally called Quick and Heavy. A single trigger excites both through a fixed split; do not add a bank selector in V1.

Quick has lower inertia, a lighter return spring, and a brighter tube spectrum. Heavy has approximately 2.0–3.5 times Quick’s inertia and a darker, more hollow spectrum. These ratios are starting points for audition, not measured properties.

Represent each struck tube with a short waveguide or damped modal model. Start with 4–8 modes per bank; tune the dominant perceived pitch to the requested note. Use a finite contact impulse, a short band-limited contact texture, and a separate low-frequency air displacement envelope. The contact source should not sound like a snare transient.

The turbine-driving air pulse is derived from that strike’s displacement envelope. It must have finite energy and expire. Audio-frequency tube oscillation may modulate its texture, but must not drive a rotor indefinitely or cause alternating reverse rotation at the fundamental frequency.

Higher strike strength increases excitation and turbine impulse. It should change whether and how rapidly a valve catches, not simply multiply the output. Preserve reproducibility with the existing seeded noise mechanism.

## 6. Turbine and valve model

Use a bounded mechanical model updated at a suitable control rate, interpolated into the audio path. Start near 1 kHz, then validate convergence against a faster update. Acoustic resonators remain on the internal audio clock.

For each bank, maintain rotor angle, angular speed, valve position, and valve velocity. A conceptual rotor equation is:

`I × dω/dt = strikeTorque − linearDrag × ω − quadraticDrag × ω × abs(ω) − linkageLoad`

Use a stable integration method and bounded coefficients. Rotor speed cannot become negative in the V1 one-way mechanism. Limit state excursions independently of the final audio limiter.

The valve target depends on rotor speed crossing a catch threshold and on sufficient angular travel. Smooth the transition with hysteresis to avoid chatter. A spring and damping term return the valve toward closed as turbine energy is lost. Valve position is bounded between 0 and 1.

A useful opening response has a low-energy region where the valve stays closed, an audible catch region, and a saturated region where stronger strikes cannot create unlimited flow. Do not implement the entire mechanism as a fixed delayed envelope. Different strike strengths must produce different catch times and trajectories.

Release modifies return behavior and the lifetime of the open state. Inertia changes acceleration and retained motion. Keep these controls distinguishable: a slow rotor can catch late and then close quickly; a light rotor can catch quickly and release slowly.

Quiet bearing flutter may use rotor angle to modulate flow or a small filtered noise source. Its frequency follows rotor speed, and it dies when the rotor stops. Avoid a separate constant motor oscillator.

## 7. Shared pressure and tuned airflow

Maintain one normalized pressure state `p` for both banks:

`dp/dt = refill(targetPressure, p) − flowQuick − flowHeavy − reservoirLeak`

The expression is a musical reservoir model, not a dimensionally complete boiler calculation. Define its normalization and units in code. Clamp pressure to the supported range and use smooth bounded refill behavior.

Each bank’s flow is a monotonic bounded function of valve opening and available pressure. A square-root pressure relationship is a reasonable initial model. Both valves drawing simultaneously must reduce available pressure more than either alone. Refill may support a long tail, but must not erase audible demand from a strong attack.

An opened pipe requires a tone-producing mechanism. Use a whistle-like jet/edge resonator with a tuned delay or acoustic cavity, nonlinear excitation, and damping. Opening a valve alone only gives airflow noise. The resonator must develop a stable periodic tone from its flow-dependent feedback; filtered noise alone is insufficient.

Calibrate delay and losses across pitch so the resonator speaks reliably in the supported range. Pressure controls onset margin, harmonic richness, breath, and tone strength. Keep ordinary pressure changes within the intended register; octave jumps and unstable squeals are optional extreme behavior, not defaults.

Quick and Heavy share the same nominal pitch. Different onset times and spectra provide the two-bank identity. Limit any intentional detuning to a small, documented amount and include it in pitch tests.

Expose diagnostic branches for tube-only, flow-only, Quick-only, Heavy-only, and full mix. These are engineering tools, not additional product controls.

## 8. Thermal vessel and lift

Treat the balloon as an imaginary thermal vessel. Heating changes a buoyancy proxy; an optional small exhaust impulse may add upward force. Keep those terms separate. Do not describe every release of steam as directly creating sustained buoyancy.

Track a normalized thermal state, vertical speed, and height. Heat input follows hot exhaust flow; cooling relaxes toward the starting state. Height follows integrated vertical speed with drag and a weak restoring term. The restoring term represents a tether or bounded imaginary environment and prevents indefinite ascent.

Use bounded states and define a stable equilibrium. A suggested thermal response is 0.2–2 seconds, with motion slower than the valve opening. Scale the response for short samples so the effect remains audible without making motion instantaneous.

Map motion primarily to acoustic loading, high-frequency radiation, and vessel/envelope resonances. For example, rise can lighten the low-mid loading while increasing the open-air whistle edge; settling restores a warmer cavity tail. Choose the mapping through listening rather than assuming height has one universal acoustic effect.

At default settings, keep motion-induced pitch change below roughly 10 cents. At the maximum Lift setting, permit up to 25 cents if the requested note remains identifiable. Do not use a free-running sine wave to substitute for thermal motion.

This model does not simulate propagation to a stationary listener or stereo position. It changes the instrument’s timbre and loading inside the mono source.

## 9. User controls

All values use the project’s existing normalized macro convention. Defaults below are proposals. Resolve MacroSpec neutral values against actual host semantics rather than assuming neutral means midpoint.

| Control | Proposed default | Low setting | High setting | Primary implementation |
|---|---:|---|---|---|
| STRIKE | 0.60 | Soft hollow contact, smaller air impulse | Harder contact, stronger turbine impulse | Contact hardness and finite displacement energy |
| PRESSURE | 0.55 | Breathier bloom; weaker strikes may not catch | Firmer whistle and stronger shared demand | Reservoir target, bounded refill, excitation margin |
| INERTIA | 0.45 | Quick valve response | Late catch and retained rotor motion | Rotor inertia and related drag calibration |
| RELEASE | 0.50 | Short valve opening and clipped breath tail | Long opening and settling tail | Valve spring/damping and release timing |
| LIFT | 0.40 | Minimal motion coloration | Audible rise/settle loading changes | Thermal-to-motion coupling and acoustic mapping |
| HOLD | 0 | Finite gesture | Sustaining, loopable gesture | Explicit sustain mode described below |

Keep STRIKE distinct from any host velocity parameter. If velocity exists, use it for event energy and scale STRIKE’s contact character within that event. Do not create a second undocumented velocity API.

Pressure’s minimum can represent a low operating pressure rather than an empty boiler. Diagnostic zero pressure must silence airflow while leaving the direct tube attack. Lift at zero may genuinely remove motion coloration. Inertia at minimum still uses a causal linkage, not immediate unrelated tone triggering.

## 10. One-shot, sustain, and loop behavior

Normal mode uses one finite strike. A quiet strike may produce only the direct tube; a stronger strike catches one or both valves. Once excitation ends, all states must settle or reach a quiet equilibrium. Terminate the rendered tail by the project’s normal bounded duration rules.

HOLD begins with the same strike-driven catch. After catch, an explicit sustaining linkage maintains the valve using reservoir energy. This is an imaginary powered latch, not energy-free rotor motion. It must not retrigger the contact sound repeatedly. Releasing sustain returns the linkage to its normal spring behavior if the host supports a release stage.

For the static HOLD=1 sample contract, render the attack separately from the steady loop if the existing format permits it. If the format supplies only a loop buffer, return the settled sustain region and document the absence of the initial strike in that buffer. Confirm existing semantics before choosing the integration.

Do not assume an integer number of pitch periods makes the loop seamless. Pressure, rotor, valve, thermal, height, resonator, and noise states also affect the seam. Settle slow states, constrain modulation to a repeatable trajectory, and use the existing loop construction or a short phase-aware crossfade where needed. Verify the project seam metric, targeting `Keys.seamError < 1e-3` if that remains the current contract. Listen for clicks and audible cyclic pumping as separate checks.

## 11. Numerical and rendering requirements

- Bound pressure, rotor speed, valve position, thermal state, height, and resonator energy. Reject non-finite states at their source with a deterministic recovery path.
- Smooth parameter mappings and valve transitions. Do not hide unstable mechanics with output clipping.
- Keep nonlinear jet excitation on the oversampled path. Measure aliasing at upper pitches before lowering its render rate.
- Avoid denormals in long damped tails. Use existing utilities where available.
- Seed noise and every initial perturbation from the voice/patch render seed. Identical inputs must reproduce identical samples.
- Apply project loudness targeting after synthesis. Keep reasonable peak headroom before normalization so a tiny late whistle is not promoted into the main event.
- Compare raw and normalized audition clips. Normalization must not conceal pressure collapse, a missing bank, or a control with little effect.

Start with two tube models and two whistle resonators. Optimize only after the acoustic identity passes listening review. Record render time and memory over the supported pitch range.

## 12. Integration work

Confirm and extend the existing voice registry, macro registration, patch serialization, renderer dispatch, presets, and audition generator. Proposed identifiers are `AEROSTAT` and `AerostatPatch`; naming clearance may change them before release.

Round-trip all six controls through JSON using the established patch conventions. Add deterministic presets with short names and documented macro values. Suggested starting presets: Soft Catch, Twin Pipes, Heavy Rotor, Drifting Steam, and High Envelope. Do not present these as final sonic results before audition.

Keep the voice in the pitched instrument route. Review SynthKits classification, PadRecipe, and effects defaults against the existing pitched guards. It must not acquire SNARE, CLAP, KICK, HAT, or TOM behavior solely because its excitation is percussive. Existing effects may enhance the engine but should not supply its core whistle or lift identity.

Add the specification to the repository’s current spec location and update its synth roadmap when implementation begins. The historical branch name in the supplied summary is context, not an instruction to switch branches.

## 13. Build rounds and acceptance

### Round A — mechanical proof

Implement a single tube, finite displacement pulse, rotor, and valve with silent diagnostic flow. Plot rotor speed, valve opening, and pressure for low, medium, and high strike strengths. Verify finite impulse energy, threshold behavior, and late catch with increased inertia. A zero-pressure diagnostic must still produce the tube note.

### Round B — musical airflow

Add one tuned resonator, then the second bank and shared reservoir. Verify stable pitch, audible catch, and pressure competition. Compare the same render with isolated reservoirs: the shared version should show measurable demand coupling. Do not approve solely from the waveform.

### Round C — vessel behavior

Add heat and motion, then loading/radiation mapping. Verify that no exhaust produces no new heating, that height remains bounded, and that motion lags flow. Compare Lift 0 and 1 at matched loudness; both should remain pitched and the difference should be audible in the tail.

### Round D — host and loop completion

Add patches, presets, HOLD behavior, kit routing, audition assets, and documentation. Run the repository’s required checks; the supplied summary identifies `./gradlew --no-daemon test`, but verify current instructions. Use the existing engine audition tooling and obtain the owner’s sonic verdict before calling the engine complete.

Acceptance requires:

| Area | Evidence |
|---|---|
| Identity | Blind comparison clearly separates direct tube attack from delayed, matching airflow bloom |
| Tuning | Sustained fundamental within an initial ±10-cent target at ordinary settings; document calibrated range and extreme exceptions |
| Determinism | Identical input yields identical output under the existing render contract |
| Stability | No NaN, runaway feedback, unbounded height, or non-decaying accidental tail in boundary sweeps |
| Controls | Matched-level sweeps at 0, 0.25, 0.5, 0.75, 1 demonstrate each macro’s intended change |
| Interactions | Pressure × Inertia and Strike × Release grids preserve a useful playable region |
| Shared demand | Two open banks draw more reservoir energy than one and change the subsequent flow trajectory |
| Sustain | HOLD maintains a pitched sound without repeated attack clicks; loop seam passes metric and listening checks |
| Integration | Patch round-trip, voice dispatch, preset rendering, pitched routing, and required repository checks pass |

Use low, middle, and high notes across the actual supported range, including its boundaries. Test quiet and strong excitation, minimum and maximum macro combinations, and long sustained renders. An onset that intentionally fails at low event energy is acceptable when the tube remains audible; widespread failure at the default patch is not.

## 14. Deferred work and engineer decisions

Defer separate playable bank pitches, persistent cross-pad pressure, stereo movement, actual flight dynamics, literal steam thermodynamics, and sampled mechanical recordings. None is necessary for the first engine’s identity.

Before implementation, confirm the host’s HOLD and velocity contracts, supported pitch range, maximum sample duration, and exact patch/voice interfaces. During the first audition, choose the tube/whistle level balance, catch threshold, shared refill strength, and Lift mapping. These are calibration decisions; the proposed numeric targets are not proof that the model works.

The finished engine should feel like one instrument whose struck air opens a second voice and whose own breath moves its body.

## R1, as built

`Aerostat.kt` is the player. One voice, `FLOAT`. Both banks always play. There is no bank selector. The identifiers are `AEROSTAT` and `AerostatPatch`.

**TUNE is a seventh macro.** A patch has no separate MIDI field, and a kit pad needs a note. TUNE snaps C3–C5, 24 semitones, default 0.5 (C4). The six controls in the table above still round-trip through JSON at the proposed defaults.

**Velocity is a render argument**, wired through `Velocity.touchedVelocity` the way Mercury's rubbed voices take it. It is event energy. It does not scale STRIKE. Diagnostic taps, a forced reservoir pressure, and `AerostatCarry` are render arguments too, not macros. `Aerostat.phrase` keeps pressure and altitude across strikes. It is a harness. `AerostatPatch.render` starts fresh.

**HOLD at 0.99 and above returns the settled loop only.** A `Snip` has no loop-start field, so the opening strike is not in that buffer. Below that step, HOLD is a powered latch after the catch and does not restrike. Heavy's one-shot whistle is 5 cents sharp (`HEAVY_DETUNE_CENTS`). That detune is not a whole number of the loop period — the nearest odd harmonic is the fundamental — so the held Heavy whistle sits on the same cycle as Quick. The loop is whole output frames, so four-times oversampling decimates onto the same cycle. Half a frame of pitch is the cost. The seam is `Keys.seamError`, and a render that does not close under 1e-3 throws.

**The whistle is a delay of one period and a memoryless jet**, on the 4× internal rate, then `Dsp.decimate`. Darkening is feed-forward, so it cannot walk the pitch. A reflection coefficient of 0.99 at 176400 Hz is a few milliseconds, so the ring is an exponential of the sample rate. The tube is scaled under the whistle in the full mix (`tubeScale`) so the knock does not file the note as a tom. Control steps are 1050 Hz, because 1000 Hz does not divide 176400. A 2100 Hz step is the convergence check.

**The kit is dry.** `SynthKits.aerostat()` is sixteen pads: a pentatonic from C3, the five presets, two more notes, and the held loop. The phone picker is not in this round. There is no Android SDK here, and a missed `when` in `SynthScreen` would fail a build this checkout cannot run.

**Listening guesses, not measurements.** Torque scale, catch speeds, the thermal map (`HEAT`, `THERMAL_TAU`, `BUOY`, `RESTORE`), the jet gain, the tube scale, and the ring times are calibration for the audition. The page is where a person says which of them is wrong, and whether the integration is the instrument. Nothing in this round has been heard.
