# TEMPEST: SnipSnap review and adapted engineering handoff
Version 1.1 | 26 October 2026 | Proposed, not implemented
The characteristic gesture is a pitched digital strike → a driven resonant bite → darkening short repeats → a finite landing.

## 1. Review verdict
Keep the digital-source / drive / filter / lossy-delay core. It is small enough for an offline percussion instrument and gives a useful contrast between a definite attack and a degrading tail. The original proposal is not implementation-ready: its source algorithms, envelopes, filter mode selection and sustained excitation are unspecified.
This is a REVIEW followed by an adapted design. The target is two industrial percussion voices for MPC pads. Kotlin implementation is outside this handoff: no SnipSnap checkout or engine source was supplied. The ZIP contains a repaired luthier skill, references and Python tools, not SnipSnap code.

## 2. Fix now
[A/B] Four algorithms have no definitions (original §§2, 6, 9). ALGO currently names a promise rather than a synthesizer. Define bounded sources, phase initialization, crossfade law and source envelopes before implementation. Otherwise two implementers will build different instruments.
[A/D] No source decay or filter envelope is defined (original §§6–10). A saturated oscillator plus a fixed cutoff does not establish a percussion gesture. Add voice-specific decay and a falling cutoff contour, shared across all source algorithms.
[C] Feedback ≤0.95 alone does not guarantee stability (original §6). It only suffices if the complete loop has gain ≤1 before the feedback multiplier. Keep the resonant filter and drive outside the delay loop. Use a convex one-pole lowpass and a fixed integer delay inside it; the resulting loop is contractive.
[A/C] A decaying delay cannot sustain a drone (original §11). With excitation off and feedback below unity, its contents disappear. HOLD must supply a continuous deterministic source and bypass the strike decay. A loop cut is a separate operation after this change.
[D/E] Always filing PERC contradicts long renders and HOLD (original §§3, 13). Use the repository's actual duration/class rules, checked against rendered audio. The supplied conventions report a 1.5-second LOOP threshold; it is not verified against current code here.
[B/E] The spec's commit and API claims are inherited evidence, not verified facts in this session (original opening and §19). In particular, the asymmetric soft-clip helper and exact SVF interface are unresolved. Use the explicit curve below and inspect the repository's equivalent filter before naming a callable API.

## 3. Try next / Big idea / Disclosed, not fixed
Try next [A/D]: replace ALGO with plain-word SOURCE, retain FILTER / DRIVE / SPACE, and make SPACE jointly increase repeat audibility and memory. Demonstrate each control at low and middle values after output leveling. DRIVE need not produce monotonic THD on complex inharmonic signals; test a sine through the isolated stage for that claim.
Big idea [A/B]: if the tail proves distinctive, later let a bounded attack-envelope descriptor set delay loss. This is control-rate interaction, not audio feedback through the resonant filter. It changes how the strike hands over to memory without weakening the stability proof.
Disclosed, not fixed: the inventory lists FATHOM as source → drive → resonant lowpass, and THUMP as swept sine / square-cluster / noise percussion. Neither inventory entry lists a source-morphing strike with an integrated degrading short-memory tail. That is a candidate distinction, not proof they cannot do it. Audit both implementations before adding a new engine. If their existing patch and recipe path carries this gesture, land there instead.
Disclosed, not fixed: the original research names a commercial instrument but cites no manual or mechanism evidence. This adaptation does not claim to reproduce its controls, sound or circuitry. All voices and future presets must use generic names.

## 4. Landing and alternatives
Strong: STACK, two-voice TEMPEST prototype with the memory tail inside the render. Gesture clarity 4/5; roster novelty 2/5 pending sibling audit; numerical risk low with the loop below; cost R1 plus a separately gated HOLD round. It wins as the smallest test of the supplied idea. Promotion to an engine is conditional on the sibling audit.
Alternate A: DISTIL, a FATHOM/THUMP patch recipe with an existing delay section. Gesture clarity 3/5; novelty 1/5; risk low; cost one recipe round after repository inspection. Choose this if the delay is merely decoration.
Alternate B: INVERT, play the lossy delay as the pitched body and inject digital impulses. Gesture clarity 4/5; novelty 3/5; risk medium because delay-filter phase changes pitch; cost at least three rounds. Defer: it changes the supplied concept and requires fractional-delay tuning.

## 5. Goals, contract and scope
V1: original synthesized low strikes and metallic hits; mono offline 44.1 kHz output; 176.4 kHz internal processing; five macros plus HOLD. No samples, stereo, sequencer, tempo synchronization, analog clock model, or commercial-product emulation. Delay is part of the instrument; external rack FX remain optional.
Use the house order: band-limit at the internal rate → proper decimation → subtract mean → 20 Hz highpass → house loudness target → 4 ms tail fade for one-shots. These are conventions supplied in the bundle; confirm helpers and signatures in the actual checkout. Do not use a tail fade on a finished seamless loop.
Use the repository's stable seeded noise generator and parameter sanitization. Non-finite controls fall back to defaults, finite controls clamp to 0..1, unknown controls do not affect audio or the seed. Resolve macro values once and pass an immutable parameter record to the renderer.
All numerical ranges below are proposed shape constants, except sample rates and the cited house conventions. None is listening-calibrated.

## 6. Signal flow and modules
Parameters → source/contour plan [cacheable]
Source × strike envelope @176.4 kHz → asymmetric drive → DC blocker → resonant LP or BP
Filter output → dry path
Filter output × send → delay write sum ← feedback × one-pole lowpass(delay read)
Delay read × wet gain + dry → house output stage
TempestPlan: settled parameters, source weights, pitch, envelopes and filter coefficients. TempestSource: oscillators and optional seeded noise. TempestTone: drive, DC blocker and filter state. TempestMemory: delay and loss state. TempestFinish: one-shot output conditioning. These are proposed internal units, not repository API names.
No cross-note mutable state. Delay buffers and filter histories belong to one render. Do not cache live DSP state. Avoid a shared toolkit extraction until a second actual engine needs this memory stage.

## 7. Source and excitation model
Let u=3*SOURCE. Select adjacent anchors j=floor(u), k=min(j+1,3), with linear weights 1-frac(u), frac(u). At SOURCE=1 select anchor 3 exactly. Compute at most two sources. Linear blending preserves the amplitude bound; equal-power blending does not preserve it. No hard switching along the knob.
With carrier phase phi integrated from instantaneous f(t), define anchors:
0: sin(phi), a clear root.
1: sin(phi + I(t)*sin(2*phi)), harmonic phase modulation, I(t)=0.3+2.7*exp(-t/0.035).
2: 0.55*sin(phi)+0.30*sin(1.41421356*phi)+0.15*sin(2.71828183*phi), an inharmonic cluster with a retained root.
3: 0.35*sin(phi)+0.65*n(t), bounded seeded noise n in [-1,1]. Its pitched component is intentional; no root-confidence promise at this anchor.
Every anchor has absolute amplitude ≤1 before its envelope. Reset phases deterministically. Generate noise in a fixed order from the host's stable generator. Oversampling is not an anti-alias proof for the PM source; high-register comparisons below decide its range.
One-shot envelope: e(t)=min(t/0.0007,1)*exp(-t/tau), with KICK tau=0.055 s and METAL tau=0.12 s. Both reach near-silence before the render end. Fixed amplitude calibration only; never normalize each oscillator or late repeat separately.
KICK uses f(t)=f0*(1+1.5*exp(-t/0.012)); METAL uses f(t)=f0. TUNE names the settled root, not the first few milliseconds of KICK. Later velocity adaptation may shorten attack and increase PM index within its stated bound; do not add a public velocity argument without checking the host contract.

## 8. Drive, DC and filter
Drive gain g=10^DRIVE. Define c(x)=tanh(g*x+0.2)-tanh(0.2). This explicit asymmetric curve is bounded by 1+tanh(0.2), approximately 1.198; no unverified helper is required. It can introduce DC, so use a proposed 10 Hz highpass before the resonant filter, plus the normal final DC stage.
Prefer the repository's equivalent of a topology-preserving state-variable filter, inspected before use. Set resonance Q=0.8 for KICK LP and Q=1.2 for METAL BP. No self-oscillation and no nonlinear resonant feedback in V1. Confirm how its BP output is normalized; calibrate per voice by a fixed coefficient, not per render.
FILTER maps to fc_base=80*(10000/80)^FILTER Hz. Strike contour: fc(t)=min(15000,fc_base*(1+2*exp(-t/0.025))). The cutoff falls after the strike, exposing source harmonics early and leaving a darker ring. There is no claim that the filter peak tracks the note. At extreme low FILTER the requested pitch can become weak; disclose and test this rather than compensating TUNE silently.
A finite-output test is not a filter stability proof. Inspect the selected filter's domain and damping law; keep Q positive and cutoff inside its documented range. If no suitable implementation is found, stop that integration step and specify a verified stable filter rather than guessing an API.

## 9. Memory, energy and tail
This is a BBD-inspired loss analogy: no clock leakage, charge transfer, compander or clock modulation is simulated. Fixed delay D=12 ms for KICK and 35 ms for METAL, rounded to an integer number of internal frames. Delay time does not vary during a render.
send=0.25*SPACE; feedback b=0.2+0.55*SPACE; wet=0.6*SPACE. Read d from the delay, update l=(1-a)*d+a*l_previous, and write send*x+b*l. Here a=exp(-2*pi*3500/internal_rate), strictly between 0 and 1. The dry output is x and the mixed output is x+wet*d. There is no path from the delay output back through drive or the resonant filter.
A fixed delay has sup-norm gain 1. The one-pole is a convex average with nonnegative coefficients summing to 1. Thus the loop gain is at most b≤0.75, and a bounded input x gives delay bound ≤send*max|x|/(1-b). At SPACE=1 this is ≤max|x|; mixed peak ≤1.6*max|x|. This is a bound conditional on a bounded filter output, not a global passive-energy claim.
For a unity-gain loss filter the scalar delay T60=D*ln(0.001)/ln(b). At b=0.75, 12 ms gives 0.288 s and 35 ms gives 0.840 s. The original b=0.95 with 35 ms gives 4.713 s, illustrating why a feedback cap is also a duration decision. These are analytic values, not rendered or heard decay measurements; the lowpass changes frequency-dependent decay.
Proposed one-shot length: exactly 1.20 seconds / 52,920 output frames, after which the house tail fade applies. Measure pre-fade residual: if not below -60 dB relative to the attack reference, reduce loss time or revise the duration/class contract. Never hide a truncated loud tail with the last 4 ms fade.

## 10. Root and macro contract
TUNE .50: MIDI round(24+36*TUNE), C1 through C4; midpoint F#2. Both voices share this mapping for pad consistency. Test settled-pitch error within 30 cents and confidence ≥0.3 for sine and harmonic-PM anchors at non-obscuring filter settings; measure after KICK's pitch fall. Metal/noise corners have no guaranteed perceived root.
SOURCE .20: adjacent blend across the four explicit anchors. Test leveled spectral distance at 0/.25/.5/.75/1 and finer steps near anchor boundaries. Pair SOURCE × DRIVE tests that the same drive behaves differently with harmonic and noisy input.
FILTER .70: base cutoff and the falling strike contour. Test low/mid settings as well as endpoints; measure spectral centroid or band-energy change. FILTER × DRIVE must change spectral balance after leveling, not only peak.
DRIVE .50: gain 1..10 before the fixed asymmetric curve. Test isolated sine THD trend with DC removed, and full-render spectral change. Do not require every distorted complex source to have monotonic THD.
SPACE .30: send, feedback and wet gain with fixed delay and loss. Test isolated memory impulse: later-window energy rises with SPACE, using a fixed reference level. Also test leveled full-render wet-tail/attack ratio. SPACE × FILTER checks how brighter strikes feed darkening repeats.
HOLD: R1 top step explicitly reserved with a test and documented UI behavior. Intermediate HOLD values map to the same finite one-shot in R1; reserve rather than imply an implemented drone.

## 11. Voices and HOLD extension
KICK: LP, 12 ms memory, pitched fall; proposed defaults TUNE .22, SOURCE .10, FILTER .45, DRIVE .55, SPACE .15. It is a timbre name, not a promise of KICK classifier output.
METAL: BP, 35 ms memory, steady source root; defaults TUNE .70, SOURCE .65, FILTER .75, DRIVE .65, SPACE .45. Preset names and collections wait for listening.
R2 HOLD is a driven adaptation: after a smooth onset, sustain bounded source amplitude, settle the pitch fall and filter contour, and retain fixed memory parameters. The delay carries response, not the energy source. Do not loop the onset.
Use the repository's equivalent of cut-from-held only after reading its real implementation. Generate warmup, retained region and genuine preceding/continuing material. Include loss-filter state and source history. Fit any blend before the seam test. Require the actual house threshold, reported in the bundle as 1e-3. If no admissible cut exists, refuse HOLD for that patch with a diagnostic; do not claim periodicity by playing the cut twice. Noise and inharmonic sources make this a genuine open risk. Loop processing must preserve periodic filter/DC state and omit endpoint fading.

## 12. Render cost and caching
Budget proposal: warmed one-shot median <20 ms and p95 <40 ms on a named target phone, including finishing, measured over at least 100 renders. These are acceptance targets, not timings obtained here. R1 executes 211,680 internal frames per 1.2-second note with at most two source anchors, one filter, and one short delay. Worst memory delay is 6,174 floats, about 24.1 KiB. Trigonometric evaluation and finishing may dominate.
Precompute weights, g, envelope coefficients, cutoff schedule and filter coefficients once per render or block. No solver or cross-note simulation cache is needed. If caching immutable plans, key by model revision, rate, voice, settled note and all settled macro values. Bound the cache size. Record warm and cold times separately. HOLD's warmup/cut search needs its own bounded work budget and may not inherit the one-shot timing claim.

## 13. Verification and audition gates
Run the bundle's minimum repository searches in the actual checkout: sibling patch helper names; seam threshold; prototype/rosterless entries; classifier duration threshold; velocity dispatch functions; model-version example; current commit. None could be run against SnipSnap here because those files are absent. Do not substitute searches of this skill bundle as API verification.
Audio gates: byte-identical repeated renders, finite internal states, raw peak telemetry by stage, output peak ≤1, |mean|<0.01, house loudness acceptance, unknown-key invariance, non-finite-control fallback, JSON roundtrip, scramble bounds and duration/class agreement using the real classifier. Check low/high notes and all 32 binary corners of the five controls for each voice, plus low/middle control steps.
Memory gates: isolated impulse and zero-input state decay, with fixed attack reference and separate late windows. A deliberately unity or >unity feedback mutation must fail a decay test. No tautological whole-render peak test. The analytic proof above does not replace testing implementation signs and routing.
Aliasing gates: compare 4× rendering to an independently lowpassed/downsampled 16× reference for high notes and maximum drive/source corners. Time-align and compare in-band error and unexpected folded components. A spectrum merely having no bins above 22.05 kHz proves nothing about folded aliases. Propose -50 dB relative in-band error as an initial investigation threshold; report failures before choosing a looser musical tradeoff.
Audition: two voices at three notes; each control at 0/.25/.5/.75/1; interaction corners; raw and matched-level files; eight repeats only after HOLD exists. Josh judges attack definition, root ambiguity, whether memory is part of the instrument, and whether it adds value beyond FATHOM/THUMP.

## 14. Integration and build rounds
All identifiers below must be located in the checkout first; filenames for TEMPEST are proposed. The attached conventions list these integration surfaces, not verified current APIs: engine and patch type; JSON patch registry; both velocity dispatches; presets registry or explicit prototype exemption; patch/recipe roster fixtures; determinism canary; engine/probe tests; preset contracts and maker-name blocklist; 16-pad kit with class derived from rendered behavior; kit generation task; audition generator/task/HTML and diagnostics; HOLD/reserved-step tests; roadmap, engine docs, README count and as-built spec. App picker remains deferred until an Android SDK session can verify it.
R0: sibling audit and current API/classifier inspection, then choose recipe versus engine. R1: two voices, fixed one-shots, reserved HOLD, safety and activity probes, measured timing and audition files. Register a prototype explicitly if presets are absent; do not leave roster tests half-integrated.
R2: sustained source, bounded HOLD cut search and genuine seam gate. R3: owner audition, adjust shape constants, author presets. R4: kit, generators, docs and final integration. Run the checkout's full required JVM test command, reported in the bundle as ./gradlew --no-daemon test, and judge by exit status. No Kotlin tests were run here.

## 15. Sonic risks
A generic distorted oscillator: compare against sibling recipes before promotion. Pitch lost under BP/noise: report pitch only where promised and provide matched-level auditions at corners. PM/drive aliasing: high-rate-reference test, then reduce index/drive or improve antialiasing. Low FILTER gives near-silence: activity/level checks at corners. Tail truncation: pre-fade residual test. HOLD discontinuities: genuine continuation seam test and explicit refusal. Loudness leveling erases control intent: evaluate spectral and tail/attack changes after leveling.

## 16. Evidence ledger and references
Read: original Tempest Engine Spec.docx, extracted paragraphs; supplied luthier-repaired.zip, including SKILL, conventions, rubric, inventory, template, combination grammar and DSP catalog. The inventory and conventions cite SnipSnap commit 565551e on 21 October; this session did not inspect that commit.
Calculated: scalar-delay T60 values in delay-checks.json, reproducible by build_handoff.py. This is an analytic model check, not a Python engine render or Kotlin measurement.
Proposed: all source formulas, contour mappings, filter settings, memory law, macro defaults, frame length and performance targets in this revision. Their bounds follow the stated equations, conditional on a bounded upstream filter.
Not verified: current APIs, registry state, classifier behavior, prototype status, Kotlin compilation, audio measurements, phone timings and HOLD seams. Not heard: all audio. No audio render accompanies this handoff.
Research provenance: the original names Erica Synths Perkons HD-01 without cited documentation. No product behavior was researched or attributed in this adaptation. No external DSP implementation was copied. The conceptual lowpass/delay and bounded-nonlinearity equations are specified directly here.

## 17. As built
To be completed by the implementing session with the actual commit, changed equations/constants, probe results, timings, audition paths, heard feedback and remaining limitations.

## Why it is built this way
The falling source and filter contours make a strike evolve into a tail. Without them, this would mostly be an oscillator held behind a filter.
The delay receives the filtered strike but feeds back only through a loss filter. That keeps the tail darkening and makes its stability understandable without a limiter.
Linear source crossfades retain a simple amplitude bound. Final leveling can address loudness; it must not conceal unstable internal gain.
HOLD is deferred because sustaining energy and making a seamless cut are different problems. Treating a dying delay as a drone would hide both.
The risky decision is whether the short-memory tail earns a new engine. The sibling audition decides that before the larger registration cost is paid.

## As built: first Kotlin R1, 2026-10-28

Implemented against SnipSnap base `df675d0`: two finite voices, adjacent four-source morph,
explicit asymmetric drive, inspected TPT filter, contractive memory and house finishing.
The patch JSON registry, velocity fallback, provisional default recipes, prototype roster
and replay fixtures, determinism canary, audio probes and production audition generator are
included. HOLD is deliberately reserved; factory presets, kit and Android picker remain
later rounds. There is no shared simulation cache or external DSP code dependency.

The first build corrects the earlier “not verified” status for the actual helper signatures:
`Dsp.seedFor`, `Dsp.Noise`, `Dsp.TptSvf`, `Dsp.FixedOnePole`, `Tide.bandLimit`,
`Dsp.decimate`, `Dsp.levelTo` and the patch/velocity dispatch were inspected and compiled.
The short-kick raw-confidence promise is narrowed: decay-compensated body frequency is
tested separately from raw pitch estimates. Two provisional default recipes are required by
the current prototype registry; these are not owner-approved factory presets.

See [TEMPEST.md](../../TEMPEST.md) for measured audio ranges, the 42 focused passing tests,
and the open performance, alias-investigation and capture-classification gates. Raw
synthesis pad metadata remains PERC; the independent classifier often identifies drum
classes. No audio has been heard. Numbers and their scope were recorded in the R1 evidence,
which stayed in the recovery backup. No claim of production acceptance or phone/MPC verification.

## As built: macro revision 2, 2026-11-01

Owner feedback replaces the first voicing maps. See [TEMPEST_R2.md](../../TEMPEST_R2.md) for revised source/drive/filter/memory equations, model-1 replay, affected tests, measurements and remaining acceptance limits. HOLD remains reserved.

## As built: accepted finite integration, 2026-11-01

The owner accepted model 2 and authorized the preset/kit step. Twelve names retain the
already auditioned single-control settings. The dry sixteen-pad E minor pentatonic kit,
shell starter, model-aware CLI replay and dual MPC export are implemented. Percussion filing
uses the actual rendered classifier result, explicitly replacing the pitched-engine PERC
proposal for this engine. Ten published model-2 PCM hashes protect the accepted sound;
eight model-1 hashes protect recalled recipes. See [TEMPEST_R2.md](../../TEMPEST_R2.md)
and its linked release evidence. HOLD, phone timing, alias investigation, Android picker
and hardware loading remain disclosed limits.
