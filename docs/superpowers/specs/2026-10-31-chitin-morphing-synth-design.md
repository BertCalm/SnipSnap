# CHITIN engineering specification

Version 1.0 · 2026-10-31 · CONCEIVE handoff

The characteristic gesture is **solid bass attack → hollow phase sweep → accelerating harmonic chatter → a slower, rounded release**.

**Status:** R1 finite-note Kotlin prototype implemented on `claude/chitin-engine`. The design below is retained as the proposal; section 20 records built behavior and validation. Owner listening, target-phone timing, picker integration and seamless sustain remain outstanding.

**Repository baseline:** `BertCalm/SnipSnap`, default branch `claude/mobile-mpc-drum-sampler-t58x74`, commit [`2b3e16882d0fa2657c47058cfb3551b439e7cf47`](https://github.com/BertCalm/SnipSnap/commit/2b3e16882d0fa2657c47058cfb3551b439e7cf47). Selected files were fetched at this immutable commit and searched locally; this was not a full checkout or a Gradle build. The companion research manifest lists the files. Reconcile with the implementation branch before editing.

## 1. Instrument and landing decision

CHITIN is a mono, offline-rendered synth for bass notes and animated leads. A master oscillator fixes the root. A slave saw resets to that master, so its rising frequency moves the harmonic structure while the musical note stays anchored. A single excursion envelope also opens the tone filter and accelerates/deepens a six-stage phaser. Releasing the note returns these quantities toward their starting values while the amplitude fades.

The shared excursion is a control signal, not audio feedback between the synth and the phaser. Hard sync and phase filtering are literal DSP mechanisms. The shell/insect language is a naming analogy; no biological or mechanical simulation runs.

Implement a small **CHITIN engine with two voices**, SHELL and WING. Keep its phaser primitive internal to the synth module initially. A rack extraction requires a second consumer and its own compatibility tests.

This is the lightest landing that preserves the selected gesture:

| Existing code | What it already does | Why this design does not fit as a preset |
|---|---|---|
| `Velvet.kt`, especially `synthesize` | Subtractive saw/pulse stack, detune, sub, envelope-controlled SVF | No slave reset oscillator or shared source/phaser excursion. A voice would add a separate synthesis architecture behind the existing macro contract. |
| `Skitter.kt` | Two edged oscillators, history-dependent filter, articulated gestures and spectral feedback | Its core response depends on recent audio and internal interaction. CHITIN uses an explicit, repeatable A-to-B-to-A control trajectory and allpass phase cancellation. |
| `Phase.kt:21–89` | Whole-buffer rack effect, four `Dsp.Biquad` allpasses, fixed-rate sine, delayed feedback, final peak matching | It neither accepts a per-sample control trajectory nor shares oscillator state. Its four biquad sections are second-order sections; do not infer a six-stage first-order design by changing its count. |

The three brainstorm directions remain recorded for scope: CROSS/shared morph is selected (clear gesture, medium-high novelty, moderate risk/cost); STACK/synth plus ramped rack phaser is the cheaper comparison (clear, lower novelty, low risk/cost); INVERT/phaser clock driving sample-and-hold is deferred (clear, high novelty, higher risk/cost). The audition must establish that the shared morph earns the extra integration cost over STACK.

## 2. Reference mechanisms and scope

The ARP Odyssey reference contributes two oscillators, hard-sync harmonic movement and a resonant subtractive path. Its ring modulation, pulse-width modulation, sample-and-hold, duophonic keyboard assignment and extensive patch controls are documented capabilities, not requirements to reproduce in R1. [Korg features](https://www.korg.com/us/products/synthesizers/arpodyssey/features.php), [owner's manual](https://cdn.korg.com/us/support/download/files/2da5c4341ec598574d4af552ecb88b6e.pdf).

The LARVA reference contributes a six-stage phaser, preamp before modulation, and ramping between two rate/depth settings. Its wet-only Vibee mode and live footswitch interface are omitted. In CHITIN, the ramp becomes a baked note gesture and also controls the source. The proposed coefficients, feedback normalization and bass protection below are our design, not claims about the pedal's circuit. [Beetronics product description](https://www.beetronicsfx.com/products/larva-morphing-phaser), [manual](https://cdn.shopify.com/s/files/1/2124/2959/files/Larva_User_s_Manual.pdf?v=1760717529).

R1 includes two voices, six macros plus HOLD, finite notes, velocity-dependent articulation, corrected hard sync, a resonant low-pass, preamp saturation and the protected six-stage phaser. It excludes ring modulation, random modulation, duophony, stereo, tempo sync, wet-only mode, freely editable A/B presets, additional FX and seamless sustain. These exclusions keep the source and motion audible enough to judge the central idea.

Use generated waveforms only. Public engine, voice and preset names contain no maker or model names. CHITIN, SHELL and WING are working names subject to the repository naming tests; they are not a trademark-clearance claim.

## 3. House contract and constants

Output is mono PCM at `Dsp.RATE` (44.1 kHz), with audio processing at `Dsp.RATE * Dsp.OVERSAMPLE` (176.4 kHz). One render is a pure function of voice, settled macros and velocity. Parameters are immutable during a render. A knob change starts a fresh render.

Use the verified output sequence: `Tide.bandLimit(raw, internalRate)` → `Dsp.decimate(raw, Dsp.RATE)` → subtract mean → 20 Hz high-pass implemented as `x - Dsp.OnePole(Dsp.RATE).lp(x, 20f)` → `Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)` → `Dsp.fadeTail(out)`. Construct `Snip(out, channels = 1, sampleRate = Dsp.RATE)`. This names existing methods, not a shared `finish` API.

`Dsp.MELODIC_LOUDNESS_TARGET` is currently `0.1834f`; `levelTo`'s default ceiling is `0.99f`. Do not substitute the Python harness's RMS target for this measure. Retain the house pipeline even if a separate pre-house diagnostic is already DC-clean.

All numerical choices below, except verified house values and mathematical identities, are **proposed shape constants**. Put them in one documented shape table in the implementation. An audition or measurement may justify a revision; record that revision in As built. No automatic normalization inside the synth, phaser or individual gesture stages is permitted.

## 4. Signal flow and ownership

```text
voice + settled macros + velocity
        |
        +--> ChitinPlan: pitch, frame counts, bounded control nodes
        |       [cache: voice, macros, velocity, rate, model version]
        |       |
        |       +-- control --> ratio / cutoff / drive / phaser rate and depth
        |
        +--> master pulse or saw --> slave reset events
                   |                       |
                   +----- corrected source mix -----+
                                                   |
                                      low-pass -> amp -> preamp
                                                   | x
              +------------------------------------+----------------+
              |                                                     |
              |            HP2(root protection) -> six AP1 stages   |
              |                         | h                 | wet   |
              |                         +------ subtract <--+       |
              |                                    | wet - h        |
              |                         HP2(same cutoff)             |
              |                                    |                |
              +---------------- sum x + w * correction -------------+
                                                   |
                                        house output processing

All audio arrows: 176.4 kHz. Control nodes: every 64 internal frames.
Feedback: previous wet sample returns only to the AP1 cascade input.
```

Proposed components, not existing APIs: `ChitinPlan`, `ChitinSource`, `ChitinPhaser`, `ChitinDiagnostics` and `ChitinRender`. Keep these internal except the engine entry points needed by registration. The plan contains no evolving audio state. Every render owns its oscillator phases, filter integrators, six lattice states and feedback delay. No mutable singleton DSP state.

The phaser receives already driven audio. It does not reset the oscillator, modify the root or alter the envelope schedule. The two high-pass processors have identical fixed coefficients and independent states. The unprocessed `x` path has no delay added by the phaser.

## 5. Pitch, timing and the shared excursion

Settle each known macro independently: missing or non-finite → voice default; finite → clamp to `[0,1]`; unknown keys → ignore. Normalize negative zero to positive zero for cache keys. Validate serialized patch maps through the repository patch mechanism.

Let `b, c, p, M, P, H` denote BITE, COLOR, PHASE, MORPH, PACE and HOLD. Let `v` be finite velocity clamped to `[0,1]`, default 1. A zero-velocity render returns silence of the planned length and is excluded from non-silence/classifier assertions.

Pitch is semitone-quantized with an explicit nonnegative rounding rule:

```text
midi = lowMidi(voice) + floor(24 * TUNE + 0.5)
f0   = 440 * 2^((midi - 69) / 12)
lowMidi(SHELL) = 24       // C1 through C3, C2 at Tune .5
lowMidi(WING)  = 48       // C3 through C5, C4 at Tune .5
```

Do not introduce detune or a sub-octave oscillator in R1. The sine support described below uses this same root.

HOLD's top step is reserved for future looping. For R1, settle its effective duration as `h = min(H, .98) / .98`; `.98`, `.999` and `1` must render identically. This is an explicitly finite maximum note, not a hidden loop. The presentation must say “long note” until a seamless mode exists.

```text
gateSeconds = .65 * (2.5 / .65)^h
releaseSeconds = releaseBase(voice) + .08*h
releaseBase(SHELL) = .18; releaseBase(WING) = .28
tailSeconds = .08
riseSeconds = 1.8 * (.12 / 1.8)^P     // higher Pace is faster
attackSeconds = .012 - .007*v
```

Compute `gateFrames = floor(gateSeconds*44100 + .5)` and the corresponding release frames the same way; tail is 3528 frames. `outputFrames = gateFrames + releaseFrames + tailFrames`. Use the rounded times for all gates and envelopes; do not recompute a second duration for classification. Internal synthesis consumes exactly four times this count after removing oscillator correction guards.

Define the clamped quintic ramp `S(u)=6u^5−15u^4+10u^3` for `0<u<1`, with endpoint values 0 and 1. It has zero first and second derivatives at both ends.

```text
t0 = .08 seconds // pitched attack guard added in the candidate-bank round
mv = M * (.7 + .3*v)
before note-off: m(t) = mv * S((t-t0)/riseSeconds)
mOff = mv * S((roundedGateSeconds-t0)/riseSeconds)
after note-off:  m(t) = mOff * [1-S((t-roundedGateSeconds)/roundedReleaseSeconds)]
after release:  m(t) = 0
amp(t) = S(t/attackSeconds) before note-off
amp(t) = 1-S((t-roundedGateSeconds)/roundedReleaseSeconds) during release
amp(t) = 0 after release
```

If a slow rise outlasts a short gate, release starts from the attained value `mOff`. Never accelerate the rise automatically to force it to finish. PACE therefore retains its meaning at every HOLD setting. MORPH zero is a steady timbre with the voice's starting phaser motion; PACE is intentionally inactive at MORPH zero.

Evaluate continuous targets at 64-internal-frame control nodes and at exact note-off/release boundaries. Interpolate between adjacent nodes. Integrate oscillator and LFO phase sample by sample; a new rate must never replace `phase` with `rate * absoluteTime`. Cacheable control curves depend on pitch where key tracking is used.

## 6. Corrected hard-sync source

Master phase advances at `f0/Fs`. SHELL uses a fixed 50% pulse; WING uses a saw. Slave phase advances at `r(t)*f0/Fs` and resets to zero at every master wrap. Its output is a saw. BITE controls the amount of harmonic sweep:

```text
r(t) = 1 + b*(rA-1) + b*m(t)*(rB-rA)
SHELL: rA=1.5, rB=4.5
WING:  rA=2.0, rB=6.0
```

At BITE zero the slave locks to the master period at ratio 1. At other settings, the period still resets at the root, while the slave's within-period motion changes. This is actual hard sync, not frequency modulation of the master or a wavetable proxy.

Mix corrected sources with fixed positive weights:

| Voice | Master | Slave | Sine of master phase |
|---|---:|---:|---:|
| SHELL | .60 | .32 | .08 |
| WING | .35 | .60 | .05 |

The sine uses the master phase; it is not a separate tuning state. It helps the root remain identifiable when the synced source emphasizes upper harmonics. No per-note or per-oscillator peak normalization.

### 6.1 Discontinuity events

Use Double phase and event time. Sample the interpolated ratio at each internal interval's midpoint, and hold that increment constant within the interval, including its fractional event segments. Enumerate events inside each interval in temporal order. Under the prescribed frequency ceiling, each oscillator can wrap at most once per interval; still handle a natural slave wrap and a later master reset in that same interval. Carry the remaining fractional interval after each event. Initialize the pre-guard master phase from its negative-time position modulo one and the slave from `frac(startingRatio * masterPhase)`, consistent with the preceding master reset under constant starting controls.

For a rising saw, a natural wrap has jump `Δ=-2`. At master sync, evaluate the slave's actual phase immediately before reset; its jump is `Δ=-2*slavePhaseBeforeReset`. Master pulse transitions have jumps ±2. A master saw wrap has jump −2. If a natural slave wrap and sync coincide within numerical tolerance, process one effective jump from the pre-event waveform to the final post-event waveform; do not add the same discontinuity twice. At an exact integer slave ratio this must reduce to ordinary coincident wraps.

Apply a band-limited step residual to **every** jump. A regular saw PolyBLEP alone does not correct the independent sync reset. Do not call the naive `Dsp.square` as the finished source.

### 6.2 Proposed correction kernel

Use a symmetric windowed-sinc BLEP residual with support `[-16,+16]` internal samples, cutoff `.45` cycles/internal sample, Kaiser beta `8.6`, and 256 fractional subdivisions per sample. Build the continuous sampled impulse table, normalize its numerical integral to 1, integrate to a step `B(u)`, and form `R(u)=B(u)-step(u)`. Outside the support, `R=0`. Use the same right-continuous step convention for the raw oscillator and the residual at an exact event sample. Linear table interpolation is sufficient for the first prototype; alias probes decide whether support or resolution must increase.

For an event at fractional sample coordinate `e`, add `Δ*R(n-e)` to the naive waveform. The render is offline: corrections may write earlier and later samples in the source buffer. Generate 16 guard samples at both ends, with starting/ending controls held constant outside the requested span, and trim guards before the tone filter. Simulate the pre-guard source so master phase is exactly zero at requested time zero. The amp envelope starts after trimming. This supplies the correction's look-ahead without shifting the dry or wet path.

Bound work by the frequency and ratio limits, not by data-dependent searches. No solver or geometry is involved. The kernel is deterministic immutable precomputation keyed by support, beta, cutoff, subdivision count and model version. A full alias probe is a mandatory R0 gate; this document does not claim this proposed kernel has passed it.

## 7. Tone filter, articulation and preamp

Use `Dsp.TptSvf(Fs)` with `saturate=false` for one 12 dB/octave low-pass. This is a chosen subtractive character, not a component model of a particular vintage revision. Its damping parameter is fixed at `k=1.35` for SHELL and `k=1.05` for WING; both avoid self-oscillation.

```text
attackColor(t) = exp(-t/.045) * (.25 + .75*v)
cutoff(t) = clamp(f0 * (2 + 18*c) * 2^(1.4*m(t) + .6*attackColor(t)), 60, 14000)
filterInput(t) = master/slave/sine mix
z(t) = .65 * lowPass(filterInput(t)) * amp(t)
d(t) = 1 + 2.5*b + 1.5*m(t)
x(t) = tanh(d(t)*z(t)) / tanh(d(t))
```

Interpolate cutoff in log-Hz, drive linearly and ratio linearly between control nodes. The filter processes at the internal rate. The preamp produces harmonics before the phaser; it is a memoryless saturation approximation. It does not compensate its output against measured RMS or peaks. Its scalar amplitude is bounded by `1/tanh(1)`, independently of filter-state spikes, but that bound does not excuse a broken filter. Record and test pre-saturator filter states separately.

Velocity changes attack duration, the initial filter opening and attained morph depth. Production pad leveling occurs once at the end. Register the engine's explicit velocity render through the private `Velocity.touchedRender` dispatch arm; do not claim BITE, COLOR or MORPH is a monotonically brightening velocity axis without a measured sweep. The current `Velocity.layerAt` peak-matches variants, and sampler/hardware velocity owns playback level. CHITIN therefore applies no additional positive-velocity amplitude multiplier; the zero-velocity silence rule is explicit. Do not add a second velocity gain in this integration.

## 8. Phaser control laws

The phaser contains six **first-order** allpass stages. Each stage has a slightly different corner, giving three primary cancellation notches in a dry/wet feedforward mix when feedback is zero. Feedback modifies that response. Six biquad allpasses would be a different order and must not be substituted.

```text
rate(t) = rateA * (rateB/rateA)^m(t)
SHELL: rateA=.20 Hz, rateB=8 Hz
WING:  rateA=.35 Hz, rateB=12 Hz
theta[n+1] = frac(theta[n] + rate[n]/Fs); theta[0]=0
excursion(t) = (.15 + 1.85*p) * (.35 + .65*m(t))  // octaves
center = 650 * (f0 / 65.4063913)^.25              // Hz
corner[j,t] = clamp(center * 2^(excursion(t)*sin(2*pi*theta(t)) + offset[j]), 100, 10000)
offset = [-.30, -.18, -.06, .06, .18, .30]        // octaves
wetAmount = .5 * sqrt(p)
feedback = .35                                  // fixed R1 value, both voices
```

MORPH is a maximum excursion amount. With MORPH below 1, the second state's rate/depth is approached partially. PHASE controls both movement depth and the strength of cancellation, with a transparent endpoint at zero. It is not a generic effects mix hidden behind a physical label: both quantities are declared here. The feedback parameter is not exposed in R1.

Advance theta at audio rate. Compute stage coefficient pairs at control nodes from the corners. Do not reset theta when morph turns around. The LFO returns smoothly to its initial speed but need not return to its initial phase during a finite note.

## 9. A passive time-varying phaser

Direct-form coefficient interpolation is not the chosen stability argument. Use a normalized lattice state `s` per first-order stage:

```text
g = tan(pi * corner/Fs)
a = (g-1)/(g+1)
b = sqrt(1-a*a)
y = a*x + b*s
sNext = b*x - a*s
```

For constant coefficients this has transfer `(a+z^-1)/(1+a*z^-1)`. For every sample with `a*a+b*b=1`, the scattering matrix is orthogonal, so `y*y+sNext*sNext = x*x+s*s`. This holds even when the coefficient pair changes between samples.

Linearly interpolate **both** `a` and `b` between control nodes using the same interpolation weight. This is a convex combination of two unit-norm scattering matrices, with norm at most 1. Consequently the intermediate samples are weakly dissipative rather than energy-generating. Do not independently smooth the two values, or linearly interpolate `b` against a differently timed `a`. Keep states and coefficient calculations in Double; use Float only for the audio buffer. Measure any extra attenuation from coefficient interpolation in the sweep audition.

Let `h[n]` be the phaser input, `z[n]` the one-sample delayed wet output, and `q=.35`:

```text
u[n] = (1-q)*h[n] + q*z[n]
wet[n] = sixStageCascade(u[n])
z[n+1] = wet[n]
```

The `(1-q)` factor is essential. It prevents feedback resonance from automatically becoming a large energy gain. Write `E` for the sum of squared lattice states plus `q*z*z`. Convexity and the stage identity give:

```text
E[n+1] - E[n] <= (1-q) * (h[n]^2 - wet[n]^2)
```

With zero input, this total storage cannot grow. The cascade's energy gain is bounded without a limiter. The proof concerns the isolated phaser; it does not make the active preamp passive. The stability test must include the feedback delay in its storage quantity and all six lattice states. A final peak cap is not this test.

## 10. Preserving the bass root

Use a second-order Butterworth high-pass `HP2` at `protectHz=max(180,3*f0)` to restrict the phaser's correction. Keep this cutoff fixed during the note. Implement with `Dsp.TptSvf(Fs)`, `k=sqrt(2)`, reading `.high`, with independent instances before and after the wet-minus-input subtraction.

```text
h = HP2a(x)
wet = normalizedFeedbackPhaser(h)
correction = HP2b(wet - h)
output = x + wetAmount*correction
```

At PHASE zero, bypass the phaser/readout branch and return `x` exactly. At high frequencies, where both high-passes approach unity, this becomes the familiar dry/wet phaser mixture. Near the fundamental, the correction is attenuated twice; no separate sub oscillator has to mask a missing root. The second high-pass also rejects low-frequency products generated by the moving phase response.

For the frozen continuous-frequency approximation, `|HP2(f0)|² <= 1/82` when `protectHz >= 3*f0`. Since the normalized feedback allpass has magnitude at most 1, the magnitude of the additive correction relative to the root is bounded by `2*wetAmount/82`, at most approximately `.0122`. This motivates the design; time-varying sidebands and discrete filters require measurement. The interpolation and feedback proof establishes an energy bound, not a guarantee of zero root modulation.

The protected readout is deliberately not full-range phase cancellation. Its sonic risk is that a dark bass may have too few upper harmonics for PHASE to be useful. COLOR zero therefore retains a cutoff above the root, and the macro tests include low and middle COLOR settings. If listening shows insufficient movement, change the source/filter or protection ratio with a new root probe; do not quietly mix an unprotected path back in.

## 11. Macro and voice contracts

All defaults are audition hypotheses. Six controls plus HOLD fit the house contract. No control shares the engine name.

| Macro | SHELL | WING | Quantity | Required interaction and proposed proof |
|---|---:|---:|---|---|
| TUNE | .50 | .50 | 24-semitone root range | With BITE/PHASE corners, root error <=10 cents in held-body windows. |
| BITE | .35 | .60 | Slave ratio excursion and preamp drive | BITE × MORPH: ratio trajectory and windowed harmonic centroid change; master pitch stays fixed. |
| COLOR | .45 | .55 | Root-relative low-pass cutoff | COLOR × PHASE: moving spectral cancellations remain detectable at COLOR .25 and .5. |
| PHASE | .55 | .65 | Phaser octave excursion and cancellation amount | PHASE × MORPH: notch motion changes; isolated root-level change <=.5 dB. PHASE 0 branch is exact bypass. |
| MORPH | .60 | .75 | Maximum shared excursion | MORPH × BITE/PHASE: source and effect both change. MORPH 0 is an explicit control ablation. |
| PACE | .40 | .60 | Rise time, 1.8 to .12 s | PACE × HOLD: measured excursion age follows the same absolute-time law; slow rise may be interrupted. |
| HOLD | .35 | .25 | Gate length, .65 to 2.5 s, plus release | All controls: frame predictor matches render. Top step equals .98 and stays finite. |

For each non-pitch timbre macro, compare .1/.3 and .4/.6 at other defaults with loudness-matched audition pairs. Use time-windowed spectra and traces, not only whole-file spectral averages. Provisional activity floor: at least 1 dB RMS log-spectral distance in one non-silent body window for timbre macros. PACE is instead proven by changed trajectory timing plus an audible matched-level comparison; HOLD by duration. These are engineering floors, not listening approval.

SHELL favors a stable pulse root and compact sync movement. WING favors the slave's higher harmonics and faster motion. They must differ before adding the phaser; same-macro voice comparisons with PHASE zero are mandatory. No third voice or factory preset count is promised before these two pass listening.

## 12. HOLD, duration and classification

R1 exports finite notes only. Proposed `Chitin.isLoop(hold)` returns false for all settled HOLD values. The engine must not offer a seamless SURFACE/keygroup sustain path. `.98`, `.999` and `1` are documented aliases of the finite maximum duration, reserving the top step without throwing from an ordinary knob endpoint. A future seamless mode must be explicitly versioned if it changes stored recipes' meaning.

`Classifier.LOOP_MIN_SECONDS` is private and currently equals `1.5f`; its comparison is strictly greater than. `drumClassFor` must use the shared `outputFrames` plan: if `outputFrames / 44100f > 1.5f`, return `DrumClass.LOOP`. **A duration-classified LOOP is not a seamless loop.** Do not infer `isLoop` from this label or invent a loop region in the exported sample.

Below that duration, the current classifier has no general pitched-note detector: `classifyBass` can return TONAL when low-band energy and decay qualify, while a bright harmonic note can correctly fall through `classifyBright` to PERC. Consequently, do not hardcode TONAL for WING or invent an unmeasured macro threshold. The initial implementation computes `Classifier.classify(renderedSnip).drumClass` through a shared internal render-result path at nominal velocity 1. Cache up to four immutable completed results so `drumClassFor` followed by `render` does not synthesize the same note twice. Include feature extraction in the performance budget. Never cache a partial/cancelled result; return a defensive sample copy to callers that might mutate a Snip. A later cheap classifier predictor requires separate evidence of equivalence across the supported domain.

The minimum .65 s gate deliberately avoids making SHELL a short bass drum. TONAL and PERC are both acceptable for finite notes; duration-based LOOP is acceptable for long notes. If actual audio files as KICK/SNARE/CLAP/HAT/TOM, R1 fails: revise the envelope or spectrum rather than relabel the sound. The no-drum assertion is an independent test over real rendered features, not a check that a hand-written label is in an allowed set. Velocity variants use nominal-patch classification for kit identity; separately inspect their actual features for drum-class leakage.

Deferred R2 sustain design must independently close oscillator forcing, integrated LFO phase, morph schedule, all recursive states, DC filtering and output filtering. Whole-cycle oscillator counts alone are insufficient for this accelerating phaser. Warm-up, a retained period and an independently evolved preceding/continuing period are required. Use verified `Keys.seamError` and `Keys.MAX_SEAM_ERROR = 1e-3`; never certify a loop by concatenating the same rendered buffer twice. No R2 algorithm is represented as already implemented or validated.

## 13. Render budget and caching

No iterative solver, audio-rate geometry or mutable shared simulation is required. Work scales linearly with output frames plus a bounded number of oscillator discontinuities. Counts remain fixed: two oscillators, a sine readout of the master, one low-pass, two protection high-passes, six AP1 stages, one feedback delay and the house filters. Do not add oversampled buffer copies for each stage.

Target on a named midrange Android ARM64 device, release build, after warm-up: P95 <=50 ms per rendered second, default patches <=75 ms, maximum finite note <=150 ms. These are proposed acceptance budgets, not measurements. Record device, ABI, build, sample length and cold/warm timings. Python execution time is not a phone estimate.

Generate the BLEP table once. Cache at most a small bounded number of control plans, keyed by voice, settled macros, velocity, internal rate and model version. Include TUNE because filter/protection corners track it. Reuse the pitch-independent scalar morph schedule when only pitch changes, but recompute pitch-dependent coefficients. Exact settled Float bits are valid keys; no audible parameter quantization for cache hits. Canonicalize HOLD's three top aliases to the same effective duration key. Rendering an identical plan always starts with fresh audio state. The four-entry completed-result cache from section 12 is separate from mutable DSP state; synchronize access, avoid retaining diagnostic traces in ordinary cached results, and cap combined cache memory at 8 MiB.

Compute phaser tan/sqrt at 64-frame nodes and interpolate pairs. `Dsp.TptSvf.process` currently computes tan per sample; measure it before adding a new coefficient API. Any optimization must preserve the equations and pass the probes; do not change shared Dsp behavior for older engines. Poll cancellation between bounded blocks following the existing render integration's cancellation convention. A stale render must be stoppable.

## 14. Numerical requirements and meaningful tests

Run the existing `kotlin.test` conventions and `./gradlew --no-daemon test` in the implementation checkout. Treat exit status as the test result. The following named tests are proposed, not files that already exist.

| Test | Required evidence |
|---|---|
| `ChitinDeterminismTest` | Byte-identical repeated renders for both voices and macro corners; same result with/without cache hits. No seed from wall clock. R1 has no RNG; future noise must use `Dsp.seedFor`. |
| `ChitinPlanTest` | Correct semitone rounding, one frame-count source, exact HOLD aliases, non-finite default handling, unknown-key invariance and finite velocity handling. |
| `ChitinSyncTest` | Fractional natural/reset events, coincident-event case, integer and noninteger ratios, changing ratio, no double discontinuity, root remains anchored. Guard trimming does not offset the attack. |
| `ChitinAliasTest` | Compare 4× and independent 16× reference source renders at low/mid/high pitch and BITE/MORPH corners, including fractional sync ratios. Equalize known latency and apply the same final comparison low-pass. Source target: residual below −55 dB RMS relative to reference over 40 Hz–18 kHz. Report separately full-chain residual and static off-harmonic energy; filter-discretization error is not automatically aliasing. No “energy above output Nyquist” proxy alone. |
| `ChitinPhaserEnergyTest` | Per-sample scattering energy inequality, fixed and rapidly changing coefficients, full feedback-storage inequality, input-off decay. Include a mutation removing `(1-q)` and show the normalized energy assertion fails. Use Double error tolerance scaled to accumulated input energy. |
| `ChitinRootTest` | Windowed root error <=10 cents, autocorrelation confidence >=.7 for default and ordinary corners; no octave slips. The existing `Pitch.detect` has a 40 Hz floor, so C1 requires a test-only detector extending to 25 Hz plus direct expected-frequency projection. Do not claim a C1 test passed via the stock detector. |
| `ChitinBassProtectionTest` | Isolated phaser root change <=.5 dB before gain matching, at MIDI 24/36/48 and PHASE/MORPH/PACE corners. Full-chain tests compare the same synth before/after phaser; filter and preamp changes are not charged to phaser loss. |
| `ChitinMacroTest` | Low/mid activity, declared zero-depth exceptions, required pairs, two-voice dry identity, shared morph vs source-only and phaser-only ablations. |
| `ChitinOutputTest` | Every stage finite; proposed raw final peak <=4, filter-state abs peak <=8, output peak <=.991, output abs mean <.005. Detect violations before gain matching. Record headroom before setting final shipping thresholds. |
| `ChitinTailTest` | Inject zero after note release; passive storage decays. Raw last 20 ms RMS below −70 dBFS before final fade, or increase the declared tail and update frame planning. No measured whole-buffer maximum used as its own energy limit. |
| `ChitinClassTest` | Public duration shortcut/shared-result classification agrees with independent `Classifier` calls over defaults, corners and every HOLD class transition. Independently reject drum-class leakage and verify classification/render reuse avoids a second synthesis. Exact boundary and adjacent output frame tests. |
| `ChitinPatchTest` | Recipe round trip and rendering equality, invalid values rejected/fallback as appropriate, settled behavior and scramble bounds. |
| `ChitinVelocityTest` | Low velocity changes the attack and filter/morph traces, not just level. Verify the timbre-only variant path is peak-matched as expected and playback gain is not duplicated; brightness registration remains absent until monotonicity is established. |
| `ChitinRenderBudgetTest` | Record cold/warm target-device timings and cancellation latency; no desktop/Python result substituted for Android. |

For pathological numerical states, fail the probe with stage/parameter diagnostics. Do not install undocumented clamps on filter or allpass state. Silence is handled explicitly; final leveling never promotes invalid audio into an apparent pass.

## 15. Measured Python spike

Companion script: `prototype/chitin_phaser_probe.py`, adapted from the repository luthier prototype template. Its fixed additive saw isolates the phaser, normalized feedback and bass-protection readout. It does **not** implement CHITIN's hard-sync source, tone filter, preamp, full voice defaults or velocity. Test constants: PHASE 1, MORPH .9, PACE .6, gate 1.4 s, release .4 s, feedback .35, six normalized lattice sections, internal rate 176.4 kHz, 64-frame coefficient nodes.

Root amplitude was measured before normalization with a Hann-windowed complex projection in 200 ms windows starting every 100 ms from .25 to 1.15 s. These are measured amplitude changes at the requested root, not pitch-detection results:

| Note | Protected root change | Unprotected root change |
|---|---|---|
| C1 / MIDI 24 | −.0053 to −.0025 dB | −5.13 to −1.40 dB |
| C2 / MIDI 36 | −.0446 to −.0377 dB | −7.56 to −2.49 dB |
| C3 / MIDI 48 | −.0461 to −.0259 dB | −4.68 to −3.82 dB |

All six musical probe renders were finite. Largest local scattering-energy excess was `1.42e-14`, consistent with Double rounding. The normalized feedback energy margins were nonnegative. An additional 12,000-sample test changed stage coefficients randomly every sample, used feedback .45 and removed input halfway through; it remained finite, met the energy inequality and ended with zero recorded storage. This does not establish full-engine numerical health.

The companion `report.json` is authoritative for full-precision results. Nine WAVs supply dry, protected and unprotected comparisons at the three notes. Each note's three files use one common gain, so cancellation differences survive. The Python house approximation uses different decimation taps and an RMS proxy; it is not a Kotlin loudness compliance test. No listening approval is recorded. The result supports retaining the protection branch and lattice form for R0; it does not justify declaring CHITIN finished.

## 16. Integration against the verified baseline

Paths in this table are relative to the repository root. “New” identifies proposed files/APIs. Existing method names were found in fetched source; existence does not authorize calling private methods from another file.

| Surface | Implementation action | Gate |
|---|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Chitin.kt` (new) | `ChitinVoice { SHELL, WING }`; public engine macros/defaults/scramble/pitch/class/render entry points; internal planning and diagnostics. | R1 |
| `ChitinSource.kt`, `ChitinPhaser.kt` (new, same package) | Corrected source and passive phaser primitives with internal visibility and isolated tests. | R0/R1 |
| `BorePatch.kt` → `ChitinPatch.kt` (new) | Use the verified wrapper shape: `Patch`, `withMacros`, validation and `Patches.decode`; `ENGINE="CHITIN"`. No `siblingPatches` API is assumed. | R1 |
| `Patches.kt:43` | Add `Patches.fromJsonValue` dispatch arm. Retain current shared JSON version unless schema actually changes. | R1 |
| `Velocity.kt:196,384` | Add exhaustive arms to private `macroSpecsFor` and `touchedRender`; the latter calls proposed `Chitin.render(..., velocity=v)`. No new brightness override at this stage. | R1 |
| `synth/src/test/.../PatchTypes.kt:67` | Put the unauditioned engine in the existing `PROTOTYPES` set while presetless, then remove at shipping-preset gate. Do not silently bypass registry tests. | R1/R3 |
| `PatchesTest.kt`, `PadRecipeTest.kt` | Add hand-built round-trip/recipe representative while presetless. | R1 |
| `DeterminismTest.kt` | Add canary for each voice and finite maximum HOLD. | R1 |
| `ChitinTest.kt` and focused tests (new) | Implement section 14 with diagnostics retained in failure messages. | R0/R1 |
| `ChitinAuditionGenerator.kt`, `synth/src/test/resources/audition/chitin-audition.html` (new) | Generate WAVs, recipe JSON, metrics, traces and comparison page under gitignored `testkit/chitin-audition/`. | R1 |
| `synth/build.gradle.kts` | Add `generateChitinAudition`; add kit task only when the kit exists. Existing Bore generator tasks establish the task pattern. | R1/R3 |
| `ChitinPresets.kt` (new), `Presets.kt:18,310` | After listening, author six presets per voice and add `forVoice` and `all` entries. Six is an authoring target, not an instruction to invent unheard factory settings now. | R3 |
| `ChitinPresetsTest.kt` (new), `PresetTestSupport.kt`, `ThumpPresetsTest.kt` | Apply house preset contracts and naming checks. Add research-name terms carefully; existing bounded ARP rule must continue permitting HARP/SHARP/WARP. Verify near-miss and clean-name tests. | R3 |
| `SynthKits.kt`, `ChitinKitGenerator.kt` (new) | 16-pad dry kit: first eight a SHELL minor-pentatonic walk, remaining eight auditioned contrasting settings. Duration-classified long notes last; label them finite. No extra rack phase on landing. | R3 |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | Add picker/render/recipe/classification wiring only with an Android SDK and current source inspection. Explicitly distinguish finite long notes from seamless sustain. The exact current arms were not inspected in this spec session. | SDK gate |
| `Keys.kt`, instrument suite and shell instrument makers | No R1 seamless keygroup integration. Reinspect and add only with a tested sustain mode or an explicitly finite instrument design. | Deferred |
| `docs/SYNTH_ROADMAP.md`, `docs/CHITIN.md` (new), `README.md`, this spec | Document actual scope/count; append As built with measurements, listening status and deviations in the same implementation commit. | Each round |

R0 and R1 do not change `Phase.kt` or its preset behavior. Before integration, search all exhaustive `when (patch)` consumers in the complete checkout; the fetched-file audit cannot guarantee it found every current consumer. Likewise re-read app and CLI instructions rather than copying an old count of integration arms.

## 17. Build rounds and audition gates

**R0: source and phaser proof.** Port the isolation spike's phaser math; implement corrected hard sync separately. Pass energy and sync/alias tests before coupling them. Include dry/protected/unprotected bass comparisons and naive/corrected/reference oscillator comparisons. Do not add factory presets. Deliver a reproducible audition and raw metrics.

**R1: two finite voices.** Connect the shared plan, filter, amp, preamp and phaser. Integrate patch dispatch, velocity, prototype roster entry, diagnostics and audition task. Pass frame-count, root, macro, classifier, output and cancellation tests. Run the full authorized Gradle test task. Listening gate: compare shared morph with source-only, phaser-only and ordinary STACK alternatives at matched levels. Keep CHITIN only if its coherent transformation remains recognizable on low notes and both voices remain distinct.

**R2: sustain decision, optional later work.** Finite HOLD already meets R1. Only add seamless sustain after defining and proving periodic controls and state convergence. A failed loop proof leaves the finite engine usable; it does not justify shipping a crossfaded click or claiming sustain exists.

**R3: voiced presets and kit.** After listening, author presets, remove prototype status, generate the 16-pad kit and document it. Complete app wiring in an SDK-equipped session and test phone playback/export. Do not block the finite-note kit on the optional R2 research.

Minimum audition sections: both voices at low/center/high TUNE; every macro at 0/.25/.5/.75/1; low/mid activity pairs; BITE×MORPH, COLOR×PHASE, PACE×HOLD, TUNE×PHASE and velocity corners; shared/source-only/phaser-only morph; protected/unprotected phase; PHASE-zero dry voice comparison; long-note endings; raw and matched levels. Seam-loop repetition belongs only to a future loop gate.

## 18. Sonic risks and decision rules

| Risk | What the owner should judge | Engineering evidence and response |
|---|---|---|
| Protected bass loses too much phaser character | Does the note still become hollow and animated at COLOR .25? | Compare protection ratios and low/mid macro activity. Preserve the root criterion; revise spectrum before allowing bass cancellation. |
| Shared morph sounds like unrelated knobs moving | Is there one recognizable transformation? | Compare shared/source-only/phaser-only at equal level; reduce the filter/drive excursions if they mask phase motion. |
| Hard sync becomes a bright hiss | Is the upper note's edge pitched and intentional? | Fractional reset alias tests against higher-rate reference; fix correction coverage before darkening with EQ. |
| Preamp fills the notches or dominates the motion | Can the phaser still be followed at high BITE? | Drive ablation, windowed spectra and raw headroom. Retain preamp before phase. |
| PHASE zero and high PHASE differ mostly in volume | Is there spectral motion after matching levels? | Raw/common-gain and matched-level pairs. Do not peak-normalize the phaser itself. |
| Coefficient interpolation damps too much | Does fast motion lose body without a musical reason? | Compare 64-frame nodes with sample-rate coefficient reference; shorten node spacing if attenuation is material. |
| Short notes lose the morph | Is the interrupted excursion useful? | PACE×HOLD audition and mOff traces. Do not redefine PACE depending on duration. |
| Duration LOOP label is mistaken for seamless sustain | Does export/UI accurately describe the result? | Explicit finite metadata/readout and no seamless route; R2 is required before enabling sustain affordances. |

## 19. Verification ledger

| Claim | Status | Evidence |
|---|---|---|
| Current repository baseline and named DSP methods | Verified source | Pinned commit, local searches of fetched files, companion manifest. |
| Existing PHASE cannot accept CHITIN's shared control trajectory | Verified source / architectural inference | `Phase.process(snip, macros)` computes its own fixed-rate LFO and owns no oscillator. |
| Lattice energy inequality and normalized feedback budget | Derived and numerically checked | Section 9 and the Python adversarial/music probes. |
| Bass-protection improvement on fixed additive notes | Measured, Python harness | Section 15, exact report and comparison WAVs. |
| Proposed hard-sync correction passes alias targets | Unverified | R0 source implementation and reference comparison required. |
| Two voices earn a new engine | Proposed | Sibling analysis plus R1 comparison audition required. |
| Full Kotlin numerical, pitch, macro and classifier contracts | Unverified | No Kotlin implementation or Gradle run in this spec session. |
| Target-device render budget | Proposed | No Android benchmark in this session. |
| Seamless sustain | Deferred | Finite R1 HOLD is specified; no loop implementation claimed. |
| Sound character and presets | Not heard | Owner audition gates outstanding. |

## 20. As built

Implemented against `2b3e168` on the local `claude/chitin-engine` branch. The source, phaser, two voices, recipe dispatch, native velocity dispatch, provisional CLI starting points, prototype roster entry and audition generator are built. The implementation uses the specified symmetric 32-sample-span Kaiser BLEP and six normalized first-order lattice sections. No existing PHASE processing was changed.

Measured validation:

- Nine focused Kotlin tests pass (exit 0), including fresh replay/JSON/cache isolation, 192 short-note parameter combinations, raw headroom/energy checks, direct C1 root projection, ordinary root pitch/confidence, bass protection, controls, HOLD aliases and velocity behavior.
- The actual Kotlin source at 4x was compared with 16x reference renders at MIDI 24/48/72 and ratios 1/2.3/5.7. All nine static saw-source cases pass the proposed -55 dB comparison-error gate; measured errors range from -99.09 to -69.54 dB. Naive-source comparisons range from -40.71 to -24.81 dB. This is a source-only static comparison, not a complete time-varying/preamp alias certificate.
- The repository luthier resource checker passes. Full repository test status is recorded in `docs/CHITIN.md` after the running gate finishes.

One measured design correction: the original constant-sustain contour let the lowest SHELL maximum-motion corner acquire its largest peak late in the sweep. The real classifier consequently filed it as KICK. A post-preamp onset multiplier `1 + .75*exp(-t/.08)` now provides the solid initial crest. It is intentionally after saturation so high BITE cannot flatten that articulation. The 192-corner test passes without class-label overrides or changing the classifier. All output still receives only the one house leveling pass.

Other disclosed implementation choices: scalar morph/filter/drive targets are evaluated directly at audio rate rather than cached as control-node plans; the expensive phaser coefficient pairs retain 64-frame chord interpolation. The completed-result cache is capped at four immutable PCM results and returns defensive copies. A separate plan cache was unnecessary because there is no solver or costly precomputation beyond the immutable BLEP table. Cache hits and misses are covered by replay tests. The independent phaser test also checks an adversarial scattering model and a deliberately unnormalized feedback counterexample.

The generator produces 108 normal clips with recipes, traces and diagnostics, ten comparison clips and 36 high-rate source-reference files. The audition has been generated from Kotlin. Nothing has been heard or approved here. Six factory presets per voice, a kit, seamless sustain, Android picker work and phone/MPC acceptance remain at their declared later gates. The maximum HOLD is a finite note with no loop chunk; a duration LOOP label is not represented as seamless sustain.

### Candidate bank round 2026-11-05

Merged default-branch commit `482c55a` into the implementation branch and reconciled the newer velocity and shared root-editor dispatchers. The owner approved the first published core audition. This round adds six **candidate** recipes per voice, kept outside `Presets.all()` until selected by ear. `ChitinPresets` is available through the CLI and picker without changing prototype status.

`generateChitinBank` reconstructs a twelve-preset audition, recipe ZIP and a sixteen-pad compatibility MPC program folder with sixteen mono 44.1 kHz WAVs. A01-A08 climb C minor pentatonic from MIDI 24 through 41; the final eight compare eight other colors, with duration-classified long notes last. Every pad is dry, carries its saved recipe and plays once. Export tests inspect real sample headers, sample references and disabled loop flags. Actual MPC hardware loading is still pending.

The broader 192-point short-note grid found an additional maximum-speed WING corner classified SNARE on the current baseline. Moving the shared morph onset from 15 ms to 80 ms preserves a pitched initial FFT window. All fourteen focused Kotlin tests then passed, including the corner grid, C1 projection, ordinary pitch, preset levels and classes, JSON replay, kit replay, waveform bounds and export checks. The updated bank is available at [the private listening site](https://chitin-audition.bertcalm.chatgpt.site), with the full control lab retained under `/lab/`.

Measured over the twelve candidates: raw peak ceiling 2.201, low-pass output peak 1.154, maximum positive feedback energy excess 0. Desktop median render time was 74.34 ms in the generator run; this is not a phone benchmark. The program ZIP is approximately 2.65 MB. The listening selection for these twelve recipes is outstanding.

Picker changes cover voices, macros, defaults, scramble, rendering, classification, saved patches, note-root control and candidate-preset loading. Maximum HOLD displays LONG NOTE; seamless instrument support remains disabled. A Compose device test covers loading FOUNDATION, editing HOLD and retaining state after recreation. SDK-backed CI must compile and run the Android paths. Full JVM status and the PR are recorded in the implementation notes when their gates complete.

## 21. Why it is built this way

- The master fixes the note while the slave moves its harmonics. This lets a bass line become aggressive without turning the pitch itself into a random effect.
- One excursion controls the source, filter and phaser. That common timing is the instrument's identity; separate unrelated sweeps would weaken it.
- The phaser changes the upper part of the sound while its correction is suppressed around the root. The Python probe shows why this matters: an ordinary full-range mix removed several decibels of the fundamental.
- The lattice and normalized feedback have an explicit energy budget. A final limiter cannot substitute for knowing that the phaser stops accumulating energy when its input stops.
- Finite HOLD keeps the first implementation small enough to judge. The main creative risk is moving too many timbre parameters together; the ablation audition will show whether the filter and drive help the phaser or obscure it.
