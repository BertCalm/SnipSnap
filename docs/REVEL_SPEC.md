# Revel — engine engineering specification

Version 0.1 • 5 October 2026 • Working name; release naming checks pending

## 1. Product intent

Revel is an imaginary circular ensemble combining cuíca-inspired friction gestures, tabla-inspired articulated and pressure-bent strokes, dholak-inspired alternating drum rolls, and surdo-inspired deep pulses. One to three virtual microphones travel inside the circle, following circular, eccentric, or spirograph-like paths. Each hears a different balance of the same ongoing performance.

The signature is **interlocking skin and friction gestures → shared-floor response → moving close encounters → shifting ensemble perspective**. Microphone motion changes which acoustic events arrive, their delay, directness, and spectral detail. It must remain audible in mono.

These are generated design proposals rather than measured models or historical performance instructions. The source instruments inspire different mechanisms; Revel does not claim to reproduce their construction or traditions. No repository implementation was inspected. Numerical settings require prototype calibration.

## 2. SnipSnap contract and musical role

Follow the supplied Gyre architecture: deterministic offline mono rendering, 44.1 kHz output through the standard 4× internal path, seeded variation, five timbral macros plus HOLD, patch serialization, presets, MPC-oriented export, auditions, and shared loudness processing.

V1 is a pitched ensemble engine at one requested root. The ensemble consists of differently articulated tonal bodies, rather than a melody sequencer. Short expressive pitch bends return toward the requested root. Microphone travel and performance tempo do not transpose that root.

Each render initializes a fresh ensemble, phrase, floor, and microphone system. Independently exported pads share no performance or acoustic state. No external samples are required. Core identity must work with an empty FX chain.

## 3. Component budget

| System | Initial budget | Function |
|---|---:|---|
| Friction station | 1 head, 4–6 modes, 1 friction/contact state | Vocal elastic gestures |
| Articulated station | 2 heads, 4–6 modes each | Clear taps, ringing strokes, bass pressure bends |
| Rolling station | 2 heads, 3–5 modes each | Alternating low/high articulation |
| Pulse station | 1 head, 4–6 modes | Broad resonant anchor |
| Shared floor | 3–4 modes | Weak transfer between stations |
| Gesture scheduler | 4 linked parts | Interlocking ensemble timing |
| Virtual microphones | 1–3 | Independently phased moving pickup |
| Acoustic paths | One direct path per head/mic, sparse early returns | Position-dependent arrival and detail |

Six heads occupy four fixed stations around the circle. Two-head stations use small local offsets. V1 does not require full drum meshes, room simulation, or microphone hardware modeling.

## 4. Four source mechanisms

### Friction station

Use a bounded moving rod/contact exciter that supplies frictional force to a resonant membrane. Relative contact velocity and pressure determine smooth tone, noisy slip, or brief squeak. Reuse a regularized stable friction model where practical. A generic saw oscillator with an envelope is insufficient.

Finite gestures have defined contact onset, pressure trajectory, speed, and release. Envelope-controlled loading can create a rising or falling vocal contour. Moderate gestures retain a root-bearing region; extreme friction remains bounded and band-limited.

### Articulated station

Use two differently voiced membranes with contact-position-dependent modal excitation. Include at least three stroke projections: ringing center/near-center, brighter edge, and damped touch. A slower pressure/load state changes the bass head's tension and damping during a gesture, then relaxes. Smooth retuning avoids delay or coefficient discontinuities.

### Rolling station

Use two heads with contrasting spectral balance and loss. Alternate palm/finger-like finite impulses with occasional simultaneous emphasis. The distinction comes from contact and resonator projection, not merely alternating output gains. Avoid assigning these heads different melody notes in V1.

### Pulse station

Use a broad finite mallet/contact force with a longer root-bearing tail. Controlled damping can shorten an answer without turning it into a synthesized kick envelope. Maintain enough mid-frequency resonance to remain identifiable when the microphone is close.

Suggested modal ratios and contact coefficients must be established through prototypes. Source labels alone are not evidence that the intended character has been achieved.

## 5. Tuning and SKIN

Calibrate each source's dominant perceived pitch to the requested note after floor coupling and normal loading. Inharmonic secondary modes distinguish membrane characters. Default tuning uses a shared root; octave offsets are a deferred patch option rather than an automatic bass assignment.

SKIN changes stiffness, mode spacing, loss, pressure sensitivity, contact compliance, and friction capture. Low settings favor loose, rounded, imperfect responses. High settings favor taut articulation, shorter contact, and brighter resonance. Compensate principal tuning so SKIN primarily changes physical character. Expressive bends are separately bounded around that compensated root.

Define a tested note range from probes. Thin high-register or muddy low-register results may require excitation and mode-weight scaling with pitch. Do not promise the whole MIDI range before auditioning it.

## 6. Ensemble performance

Use a small deterministic gesture scheduler. Establish an anchor pulse, place alternating rolling strokes between anchors, add articulated responses, and place friction gestures across selected gaps or accents. The parts share phrase phase and accents but retain different event shapes.

PLAY changes event density, participation, syncopation, stroke energy distribution, and overlap. Low settings still contain all four families over the phrase, with quieter sparse responses. High settings become lively without filling every gap with maximum-level hits.

Keep performance tempo separate from microphone orbit speed. Store `phraseTempo` and `phraseBeats` in the patch, subject to host conventions; suggested starting values are 104 BPM and 4 beats. These are musical defaults, not measured requirements. Tempo need not occupy a timbral macro.

Generate deterministic timing offsets and articulation variation once from the render seed. Bound timing displacement so intended interlocks remain perceptible. In HOLD, use a repeating variation sequence. Do not introduce fresh nonperiodic randomness on each loop cycle.

A one-shot is a finite ensemble gesture, initially around 1–2 phrase cycles, followed by decay. HOLD continues the patterned performance with explicit new performer energy. It does not sustain by raising acoustic feedback to unity.

## 7. Shared floor coupling

Every head feeds the same weak resonant floor. Floor motion returns small forces to receiving heads. A deep pulse may produce a faint sympathetic answer; a friction gesture can leave another head softly ringing. This physical response is distinct from a newly scheduled performer stroke.

Use reciprocal passive coupling or another energy-bounded formulation. Raising source intensity can increase audible sympathetic activity through greater input energy, without increasing coupling gain without limit. Avoid routing floor amplitude into unbounded stroke triggers.

If floor loading modifies friction or pressure, subtract the corresponding work from the supplying subsystem and bound the modulation. V1 may begin with linear modal coupling and add this interaction only if auditions justify it.

Microphones are passive observers. They do not change the ensemble, head loading, or event schedule. No microphone-to-floor feedback is required.

## 8. Microphone geometry

Let station positions be `s[j]` on a ring of radius `R`. Mic `m` follows a bounded position `p[m,t]` inside that ring, with an optional fixed height above the floor. Distances use both horizontal displacement and that height.

REACH sets path extent: central blended listening through progressively closer passes to the players. Maintain a minimum source clearance and valid radius for every trajectory. A mic cannot pass through a head or create zero-distance gain.

ORBIT sets travel speed. At zero, hold each mic at its deterministic initial position. Low nonzero values must produce an audible change within normal render lengths; use useful initial phase and modest path movement rather than waiting for a full slow revolution.

WEAVE smoothly interpolates from circular travel through eccentric paths to looping spirograph-like travel. One proposed implementation uses normalized coordinates:

`x = (1-w)*cos(theta) + w*(a*cos(theta) + b*cos(k*theta + phi))`

`y = (1-w)*sin(theta) + w*(a*sin(theta) - b*sin(k*theta + phi))`

Choose `a,b >= 0`, `a+b <= 1`, then apply REACH's bounded radius. Use fixed integer or rational `k` from a small patch roster, not a continuously changing irrational ratio. This produces spirograph-like paths without claiming literal gear geometry. The trajectory roster, phase offsets, direction, and speed ratios belong to the patch.

Use distinct initial phases and, where appropriate, opposite directions for multiple mics. Mic count is a voice/patch property, not an extra timbral macro. Every mic must sample the same source states, not its own independent ensemble render.

## 9. Moving acoustic pickup

For each source/mic pair, calculate distance, relative direction, bounded gain, propagation delay, and spectral absorption. Use a softened inverse-distance or other calibrated near-field law with explicit gain limits. Include a small diffuse/floor contribution so close travel does not erase the rest of the ensemble.

Read the source's shared audio history through fractional delays. Do not recompute its modal evolution separately for each mic. Use consistent timing: document whether geometry is evaluated at reception or approximates emission/retarded position. V1 may use the reception-time approximation at bounded speed.

Changing path length can produce restrained Doppler variation. Bound path velocity and resulting pitch shift so moderate ORBIT stays musically pitched. If imaginary acceleration exceeds that range, smoothly compress the delay-rate effect while preserving amplitude and spectral motion. Document this musical approximation.

For a near-center microphone, direct balances are similar but source orientation and head projection may still differ. Close passes reveal sharper contact details and a stronger direct-to-return ratio. Add only sparse early reflections initially; external reverb must not supply the core orbit behavior.

Fixed omnidirectional pickup is sufficient for V1. Directional microphones or automatic aiming can follow later; WEAVE must not secretly become a directivity control.

## 10. Multiple microphones and mono summation

Sum one to three separately delayed pickup signals into mono. Changing perspectives remains audible through event emphasis, directness, arrival timing, and spectral coloration. Stereo panning is not required to demonstrate orbit.

Calibrate count-dependent gain between correlated and decorrelated cases. A fixed `1/sqrt(micCount)` factor alone cannot protect against perfectly correlated microphones; coincident paths need a bounded coherent gain policy. Avoid automatic gain pumping that removes intentional close-pass accents.

Identical coincident mic paths should reproduce the single-mic perspective at matched level. Distinct paths should yield meaningful combined texture. Allow moderate reinforcement/cancellation, but prevent normal presets from losing their root or collapsing into thin comb filtering. Use restrained wet-path mixing, bounded timing spread, or a calibrated direct anchor as needed, without phase-locking all mics together.

Crossing trajectories must remain continuous. A crossing changes pickup geometry, not microphone identity, scheduler phase, or delay-buffer ownership.

## 11. Macro set

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| PLAY | Sparse conversation → lively overlapping ensemble | .60 | .50 |
| SKIN | Loose rounded response → taut bright articulation | .50 | .50 |
| ORBIT | Stationary listening → faster microphone travel | .40 | .30 |
| WEAVE | Circle → intricate looping paths | .35 | .25 |
| REACH | Central blended pickup → close player encounters | .65 | .60 |
| HOLD | Finite phrase → continuing seamless performance | 0 | 0 |

Defaults and neutral values are hypotheses. Confirm current MacroSpec semantics. Keep a small audible path extent at minimum REACH so ORBIT and WEAVE retain useful variation; describe the center end as near-center rather than exactly motionless. At ORBIT=0, WEAVE still changes the deterministic initial pickup position where meaningful.

Required interactions: ORBIT × REACH, WEAVE × REACH, PLAY × ORBIT, and SKIN × REACH. Motion must alter which actual gestures are prominent, rather than drive an unrelated global tremolo.

## 12. Voices and presets

Proposed enum: `RevelVoice { CIRCLE, CLOSE, CROSSING, SPIRO, FRICTION, PROCESSION }`.

| Voice | Mics | PLAY | SKIN | ORBIT | WEAVE | REACH | Identity |
|---|---:|---:|---:|---:|---:|---:|---|
| CIRCLE | 1 | .55 | .50 | .35 | .10 | .55 | Clear rotating conversation |
| CLOSE | 1 | .50 | .55 | .40 | .25 | .85 | Detailed individual encounters |
| CROSSING | 2 | .60 | .50 | .50 | .45 | .70 | Counter-rotating perspectives |
| SPIRO | 3 | .70 | .60 | .55 | .85 | .75 | Interlocking intricate paths |
| FRICTION | 2 | .45 | .40 | .35 | .55 | .70 | Elastic vocal gestures above drums |
| PROCESSION | 1 | .75 | .35 | .25 | .30 | .60 | Broad pulses and rolling answers |

Voices bias articulation, source balance, and trajectory roster within the same ensemble. All retain the four families. FRICTION does not mute the drums; PROCESSION does not become a single bass drum. Mic count remains independently serializable and overridable within 1–3.

Suggested presets: Inner Circle, Skin Conversation, Close Pass, Two Directions, Three Listeners, Flower Path, Elastic Answer, Rolling Floor, Deep Gathering, and Held Revel.

## 13. Duration and velocity

Finite mode schedules a bounded number of phrase cycles, ends all active friction gestures, then renders the decaying heads/floor/paths. Start around 3–7 seconds subject to host duration limits. Never truncate a finite phrase in the middle of a stroke simply to meet a fixed tail target.

If the host limit cannot fit the chosen phrase, select a documented shorter compatible phrase before rendering. End when source excitation has stopped and the propagated acoustic tail reaches the existing silence threshold, or the host maximum.

Velocity changes source work and contact character, including pressure and articulation intensity, rather than only output gain. Bound friction and floor energy at maximum velocity. Shared loudness leveling follows generation; compare raw and normalized renders so normalization does not hide dead controls or unstable growth.

## 14. HOLD and loop closure

Make gesture phase, variation pattern, mic trajectories, pressure states, friction roughness, acoustic modes, floor modes, and delay histories compatible with one loop period.

Choose a loop containing an integer number of phrase cycles. Quantize each mic's base travel frequency and its component frequencies to integer cycles over that period. Rational trajectory ratios require a period long enough for the complete path to close. Bound the permitted denominator and total duration.

At slow requested speeds, do not blindly round every nonzero orbit to zero. Choose a longer permitted loop or the nearest useful compatible speed and document the adjustment. Source note pitch remains independent of orbit quantization.

Render bounded hidden preroll and compare complete candidate periods. Geometric closure alone does not guarantee identical acoustic state. Use periodic seeded variation/roughness and stable bounded friction; constrain loop-mode chaotic behavior if necessary.

After selecting a settled region, use the project-standard wrap handling. Check for doubled strokes, broken friction contours, changes in propagation delay, and gain jumps. Representative loops must meet `Keys.seamError < 1e-3` and repeated-loop listening. Confirm host support for attack-plus-loop; loop-only export represents the settled ensemble.

## 15. Numerical and CPU requirements

Bound contact forces, friction slopes, pressure bends, modal energy, coupling, microphone gain, delay length, path velocity, and event density. No final limiter may conceal unstable internal growth. Detect non-finite state at its source and prevent DC, denormals, aliasing, and coefficient zippering.

Use the oversampled path for nonlinear excitation and acoustic synthesis, then band-limit before decimation. Slow trajectory/scheduler updates may run at a lower rate with smooth interpolation. Validate event timing and path continuity when changing control rate. Share source synthesis and circular history buffers across microphones.

Budget approximately 18 direct head/mic paths at maximum count, plus sparse floor/early-return paths. Allocate delay storage from maximum geometry once per render. Avoid per-sample allocation and full fluid/room solvers. Profile actual render cost before choosing stricter budgets.

## 16. Determinism, patches, and integration

Identical voice, note, macros, velocity, mic configuration, phrase configuration, patch, and seed context must produce identical samples and event traces. Use repository seed infrastructure such as `Dsp.seedFor`. No wall-clock time or external live input participates.

Implement `RevelPatch` with voice, macros, mic count, trajectory roster/phase/direction/speed ratios, phrase tempo/length, and deterministic configuration as required. Derived transient DSP state need not be serialized. JSON round-trip must preserve the audible configuration. Follow `BorePatch` and sibling conventions and register with `Patches`.

Expected surfaces: `Revel.kt`, `RevelVoice`, `RevelPatch`, `RevelPresets`, `Patches`, `SynthKits`, `drumClassFor`, audition generation, tests, and roadmap. Reuse current `PadRecipe`, `FxChain`, and `MELODIC_LOUDNESS_TARGET` conventions after verifying actual interfaces.

Revel's drum-derived sources create a specific classifier risk. The supplied pitched-engine contract requires standard melodic cases to avoid KICK/SNARE/CLAP/HAT/TOM classification. Preserve tonal tails and limit attack/noise dominance, then measure actual results. Do not bypass the classifier or relabel failed cases. If the ensemble cannot retain its identity under those guards, resolve a separate percussion-ensemble integration route with the owner; this spec does not silently invent an exception.

## 17. Build rounds and acceptance

1. Build four source mechanisms, fixed pickup, tuning, and shared-floor coupling. Probe raw pitch, energy, DC, decay, and classifier behavior before setting numeric test bounds.
2. Add finite interlocking performance. Approve dry ensemble identity and useful PLAY/SKIN behavior before spatial complexity.
3. Add one moving mic, then multiple mics and spirograph paths. Demonstrate mono perspective, gain safety, and independent performance/orbit clocks.
4. Complete HOLD, voices, presets, patch round-trip, kit integration, auditions, and current repository checks. The supplied baseline is `./gradlew --no-daemon test`; confirm the current implementation requirements.

Audition every voice at low/middle/high supported notes and velocities, 1/2/3 mics, stationary/circular/spiro paths, macro values 0/.25/.5/.75/1, interaction grids, all-high settings, and difficult HOLD cases. Probe pitch confidence, peak/RMS, spectral centroid, DC, event count, travel speed, mic gain, aliasing, render cost, determinism, routing, and seam error.

Diagnostic comparisons should freeze the same source performance while changing microphones, mute the floor while retaining direct pickup, and compare coincident versus independent mic paths at matched level.

Acceptance requires:

- All four source families contribute perceptibly, with interlocking rather than merely simultaneous unrelated patterns.
- Friction, pressure-bent articulation, alternating rolls, and broad pulses remain distinct in dry auditions.
- Passive floor coupling creates sympathetic response and decays when performers stop.
- Moving mics change local gesture emphasis, directness, and arrival timing in mono; stationary pickup removes that travel.
- REACH changes listening proximity; WEAVE changes path shape; ORBIT changes travel without changing performance tempo.
- Mic crossings remain continuous; coincident paths are level-safe; normal multiple-mic presets preserve root and body.
- Finite gestures decay; all render extremes are bounded and deterministic; patch round-trip and current pitched routing guards pass.
- HOLD passes the seam metric and repeated-loop listening without duplicate or missing strokes.

The owner's listening verdict is the sonic gate. Automated metrics alone cannot establish a lively or convincing ensemble.

## 18. Open decisions and deferred scope

Confirm note range, host duration, phrase/tempo fields, velocity support, loop format, APIs, and working name. Calibrate membrane spectra, friction law, pressure-bend limits, source balance, floor transfer, trajectory speeds, geometry scale, delay-rate compression, and mic-count compensation.

Defer literal cultural pattern libraries, sampled instrument recordings, live microphones, directional pickup, stereo, external MIDI sequencing, arbitrary mic counts, and persistent cross-pad ensemble state. Revel succeeds when one coherent circle of performers becomes several shifting perspectives while retaining its own tactile acoustic rules.
