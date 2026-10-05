# COROLLA — coupled mechanical flower

Built from the supplied 3 October 2026 Corolla engineering specification. This is a reduced musical DSP object; constants are proposed sound-design choices, not measurements from hardware. The provisional name and factory names remain subject to the owner's release review.

## Host decisions

- Offline, deterministic, mono, 44.1 kHz; nonlinear interactions run on the existing 4× clock. No external samples or persistent state between pads.
- All voices use TUNE over C3–C5 (MIDI 48–72), snapped to semitones, default C4. Five timbral macros and HOLD retain the proposed voice defaults and neutral values. TUNE is the existing host's requested-note interface.
- Velocity is the existing `Velocity.render` argument. It scales physical pull displacement and available powered input, rather than introducing another product control. Macro PULL selects release character within that event.
- Finite renders last 2.2–6 seconds. FIELD drive stops after a bounded envelope; FIELD zero has no powered input. HOLD below .99 lengthens the finite powered envelope. HOLD at .99 or above returns settled sustain material only. The current `Snip` sample API has no attack-plus-loop marker, so the initial blossom is absent from that buffer.
- Engine metadata routes finite notes as TONAL so pitched pads retain IN KEY and tonal retuning; HOLD routes as LOOP. The generic audio classifier independently labels these long recordings LOOP by its 1.5-second duration rule. This explicit engine metadata takes precedence when sending an authored Corolla patch to a pad. Only HOLD's top step closes as a loop.
- Patches, twelve complete factory presets, a sixteen-pad dry factory kit, generic CLI/shell replay, and the Android synth picker use existing interfaces. Dry synthesis contains the instrument's identity before rack processing.

## Passive object

Six petals have three flexural modes each. Fundamentals follow 1, 2, 3, 4, 5, 6 times the requested note; upper modes begin near 2.7 and 5.4, with small fixed/seeded differences. Their ratios describe the imaginary instrument, not validated metal tongues. Modes approaching the final 19 kHz ceiling are attenuated before band-limiting and decimation.

Each mode has mass-normalised displacement q, velocity v, and x = omega q. Free motion is an exact damped rotation of (x,v). The performer prescribes a short raised-cosine displacement ramp on the root petal and releases it with a consistent derivative. Neighbors receive no duplicate note-on impulse.

Six links form a ring. Each interaction is computed once with equal-and-opposite spring forces and relative-velocity damping. A smooth tanh travel bound limits the force gradient. These links combine structural transfer and passive magnetic loading: FIELD zero still rings as a coupled object. They connect fundamental coordinates; contact alone projects into all three modes.

The chamber has four fixed-scale modes and reciprocal links to the root mounting. CHAMBER changes scale (about 620–145 Hz for its lowest mode), damping, pickup and loading. BLOOM changes aperture losses and enclosure radiation. The chamber is a small modal load rather than a reverb or independent oscillator.

Spring self-load is removed from the free modal stiffness, leaving the static matrix's diagonal at its target value. The full reciprocal stiffness matrix is eigensolved once per note, and the root eigenmode is compensated. Moving geometry can make bounded transient pitch shifts. This compensation includes chamber and neighbor links.

The split integration and geometry changes are not exactly conservative. After each passive step, modal energy plus the positive neighbor/chamber spring potentials is compared with the previous total. Any increase is removed by a uniform state contraction, before powered input or pickup. This documented passive formulation allows losses but forbids a passive energy gain. Contact uses the same energy bound rather than independently storing an additional contact state. The source resets non-finite states deterministically; the output limiter is not the stability mechanism.

## Opening, power, contact

Opening has one shared position and velocity. Smoothed measured vibration energy pushes against a return spring and damping; BLOOM sets resting opening and energy-response strength. Opening lags the pull and folds as the energy falls. It changes ring-link strength, collision gaps, chamber losses and radiation. It is never driven by a free-running opening oscillator.

FIELD's core rate follows log-interpolated anchors 0, .3, 2, 10, 40 Hz at macro 0, .25, .5, .75, 1. Its rotating spatial preference acts on amplitude-bounded velocity-feedback forces **inside** the petal modes. A finite smooth field-induced release starts the root modes even when diagnostic pull is disabled. Forces propose work; their contributions are scaled to an explicit input budget before application. The budget is at most `.025 + .45 * FIELD` mass-normalised energy units per second times event strength, further scaled by FIELD in finite mode and by its finite envelope.

HOLD at FIELD zero uses gentle powered velocity feedback to offset losses of the same network. This is an explicit maintenance source, not passive endless sustain or a substituted oscillator. With no pull and powered input disabled, the zero-state object is silent. Diagnostics separate input work from stored passive energy.

Adjacent petal contact uses projected displacement and velocity, a smooth compliant repulsive force, and closing-only damping. CONTACT zero removes the entire collision path. Clearance depends on CONTACT and opening, so strong events and folding promote chatter. No contact noise is pasted onto the final mix.

## Held loops and output

Held root cycles snap to integer output frames, with less than about 0.1 cent of frame quantization. Loop periods are 1.8–4 seconds and contain an integer number of core orbits. Very slow cores use the nearest compatible **nonzero** rate; their exact one-shot rate is not promised in HOLD.

The entire network runs through bounded preroll and three candidate periods. Two successive candidates are compared by full-buffer waveform change. The less-changing region is selected, then Tremor's unity-sum smoothstep wrap blend closes the final 1,280 frames against the preceding region; the last 256 are identical. This closes output despite remaining chamber, contact and hinge-state differences; it is not a claim of an exactly periodic physical state. The full buffer passes `Keys.seamError < 1e-3`. Tests also compare the actual last-to-first step and slope to the signal's interior changes, and check settled pitch.

Shared band-limiting and cascaded decimation precede DC removal and `MELODIC_LOUDNESS_TARGET`. Finite tails are faded; held buffers are not. Diagnostic `finish(normalize=false)` and `renderLoop(normalize=false)` support raw versus loudness-matched audition. Recorded internal taps are root, responding petals, chamber, contact activity, opening, stored energy, and powered work, all at 176.4 kHz.

## Review and validation

`./gradlew --no-daemon test` is the JVM gate when no Android SDK is installed. The new engine tests cover passive decay, source isolation, neighbor causality, opening lag/folding, input work, contact removal, macro activity, velocity, root dominance, deterministic renders, serialization, factory names/routing, finite extremes and held seams. Android assembly remains a separate SDK-dependent check.

Run `./gradlew :synth:generateCorollaAudition` for the full listening matrix, or add `-PcorollaQuick` for defaults and held versions of each voice. Open `testkit/corolla-audition/index.html` to compare raw and matched clips and save listening notes. The full matrix includes pitches/velocities, five-step sweeps, the five required interaction grids, corners, all factory presets and coupling isolation. Automated checks establish behavior and stability; the owner's listening verdict is still needed for sonic acceptance.

### Initial measured calibration (5 October 2026)

The corrected focused matrix passed its twenty Corolla/preset checks. Eighteen moderate voice/pitch cases measured −0.043 to +0.116 cents. Nine default or maintenance held cases measured −0.066 to +2.221 cents, with root energy 5.65–9,071 times the summed selected upper-partial energy. Eleven held cases passed the actual wrap step/slope guards; their conditioned `Keys.seamError` was zero after the documented blend, which alone does not prove raw state convergence.

Passive six-voice raw peaks were .195–.302. Mean stored energy at .12–.17 seconds was .00540–.0143, falling to 3.07e−9–2.20e−7 in the last 50 ms of a 1.8-second probe, with no sustained growth and exactly zero powered work. Six-second CHATTER/ORBIT/HUSK powered/contact extremes had raw peaks .761–.793, RMS .0163–.0176 and peak stored energy 1.213–1.243. Internal simulation with diagnostic taps cost .405–.436 wall seconds for six seconds of audio on this executor; that excludes final filtering, decimation and loudness. These are observations from this test matrix, not universal bounds or listening acceptance.

Deferred: independently played petals, phrase/pad memory, stereo rotation, hardware, full field simulation, thermal behavior and recorded surface materials. Dedicated held keygroup instruments are not part of this V1; the loop buffer can be exported through the existing sample route.
