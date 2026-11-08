# BACKWASH drum engine: engineering specification

Version 1, 2026-11-07. Status: first build and listening candidate.
Repository baseline: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.
The conversational name UNDERTOW is retired for this work: `Undertow.kt` already
implements a pressure-reservoir instrument. Its patches must remain compatible.

The characteristic gesture is **a dry, papery drum hit → a wavering double →
a compressed swell → a dark, gritty finish**.

## 1. Scope and landing

A selectable drum engine, explicitly requested by the owner, reusing SKIN's
acoustic sources with a private chorus → compressor → amp path. SKIN and ONSET
are the nearest siblings. They cover the source; neither source alone provides
this ordered processing gesture. `FxChain` orders COMPRESSOR → VALVE → ENSEMBLE,
and `PedalBoard` retains rack order within each side. Neither is rearranged.

Five first-build voices: KICK, SNARE, TOM, HAT_CLOSED and HAT_OPEN. The owner
requested a drum set, so this build extends the usual two-voice first round to
five existing source models. No new drum physics is introduced. Factory presets
and a promoted starter kit wait for listening; the engine is explicitly a prototype.

The input document supplies a sonic brief: round kick, tight high snare with
short wire chatter, muted woody toms and restrained cymbals. Its historical
hardware assertions are not validated evidence. No original recordings are used.

Independent hits only. No shared bus pumping, human groove simulation, pattern
sequencer, real-time effect state, or HOLD loop. TUNE is voice-specific drum
pitch, not a melodic MIDI mapping. Drum classes remain drum classes.

## 2. Reference mechanisms and alternatives

Research names only, never engine/preset branding:

- EarthQuaker Devices Sea Machine V3: dry plus modulated delay, extended pitch
  movement and resonant regeneration. Keep movement and afterimage; omit its
  self-oscillation and triangle-to-square waveform extremes.
- Walrus Audio Deep Six V3: attack-aware compression and parallel blend. Keep
  transient/body interaction; omit circuit emulation and independently exposed
  bass-cut, level, attack and sustain knobs.
- Unspecified amp: assume a mildly driven combo and speaker coloration. Use
  the existing VALVE behavior, not a named amplifier or a measured cabinet IR.

Sources read during the concept pass:
<https://www.earthquakerdevices.com/sea-machine/>
<https://www.earthquakerdevices.com/s/EQD-EU-Sea-Machine-R2.pdf>
<https://www.walrusaudio.com/products/deep-six-compressor-v3>

Selected STACK: clear hit/double/swell gesture, moderate novelty, low–medium
risk, two build rounds. MUTATE (strike-dependent cloth damping): clear gesture,
moderate novelty, medium risk, three rounds. COUPLE (kit envelope drives chorus
movement): stronger breathing, greater novelty, medium risk, three rounds.
The selected direction follows the owner's exact processing order.

## 3. Signal flow and model boundaries

```
voice + TUNE + DAMP + velocity
  → SKIN source [4x synthesis, existing finish to mono 44.1 kHz]
  → damping tone [44.1 kHz]
  → dry + modulated delay with filtered feedback [44.1 kHz]
  → parallel COMPRESSOR [44.1 kHz]
  → VALVE [4x upsample/nonlinearity/band-limit/decimate]
  → subtract mean → 20 Hz DC blocker → house level target → 4 ms fade
```

Drums are modal/noise approximations; DAMP maps to decay, overtone balance,
wire/ring duration and low-pass loss. It does not simulate cloth contact or
microphone geometry. The chorus runs an actual time-varying delay and feedback
filter. Compression runs the existing detector/gain law, blended with the
aligned input. Amp nonlinearity runs the existing VALVE model. These are
behavioral adaptations, not measured pedal circuits.

`Backwash.source`, `chorus`, `press`, and `inspect` expose internal stage taps
for auditions and independent tests. Only the patch and six controls are public
saved state. Per-voice modulation phase is derived through `Dsp.seedFor` and
does not change when a macro changes. SKIN retains its established seeded noise.
No module-global mutable state or cross-pad history.

## 4. Shape laws and stability

All new constants below are proposed shape values pending owner listening.

- SKIN DECAY: .45 → .04 as DAMP rises; PUNCH .35; CLICK .18 where available.
- TONE .45 → .12; RATTLE .48 → .12; SIZZLE .12; RING .65 → .30.
- Additional source low-pass: 12 kHz → 4.8 kHz.
- Delay seconds: `.018 + .006*TRAIL + .006*SWAY*sin(2π*.8*t + phase)`.
- Wet gain: `.12 + .48*TRAIL`; feedback: `.58*TRAIL`; feedback low-pass 5.5 kHz.
- Delay interpolation: existing `Dsp.tap`, linear with nonnegative weights.
- PRESS: compressor AMOUNT `.15+.45*PRESS`, ATTACK .7, RECOVER .6;
  output is aligned dry/processed blend with processed fraction `.75*PRESS`.
  PRESS zero bypasses compression exactly.
- VALVE DRIVE .32 → .65; SAG .2; TONE .48; CAB .55.

Minimum delay is positive and exceeds one frame. Delay interpolation and the
feedback one-pole are convex combinations. Feedback gain never exceeds .58,
therefore repeated unforced circulation contracts peak norm. The maximum
forced line amplitude is bounded by input peak / (1-.58). No limiter sits in
this loop. A .65-second zero-input tail permits regeneration to decay; tests
check the unforced tail independently of final leveling.

The source, COMPRESSOR and VALVE retain their existing normalization semantics.
This is acceptable for independent hits; it is explicitly not a model of an
absolute-level live bus. A future bus mode must revisit those gain laws.

## 5. Macro contract

All controls are normalized 0..1; nonfinite render values use defaults, finite
values clamp, and unknown render keys are ignored. Saved unknown keys are refused.

| Control | Default | Quantity | Interaction/proof |
|---|---:|---|---|
| TUNE | kick/tom .35, snare .65, hats .50 | source frequency | DAMP corners retain voice identity |
| DAMP | .75 | decay, wire/ring loss, source tone | dry duration shrinks; no new tail energy |
| SWAY | .30 | delay excursion, fixed .8 Hz motion | TRAIL carries the bent return |
| TRAIL | .25 | wet contribution and regeneration | PRESS changes its relative envelope |
| PRESS | .45 | compression and parallel blend | reversed order produces a different envelope |
| DRIVE | .25 | gain into oversampled amp | PRESS determines body reaching saturation |

Every macro is compared at .1 and .5, not only endpoints. All 64 binary corners
are rendered for every voice. Velocity changes SKIN's strike spectrum for drums;
hats instead reduce source TONE. The existing Velocity pipeline supplies layer
leveling. No generic brightness macro is declared without a monotonic sweep.

## 6. Output and render budget

Mono 44.1 kHz, source and nonlinear stages at 4x with their existing filters.
Final output uses `Dsp.levelTo(..., Dsp.MELODIC_LOUDNESS_TARGET)` and `Dsp.fadeTail`.
Must remain finite, within PCM range, mean magnitude < .005 and under 1.5 seconds
at every macro corner. Do not truncate to meet the classifier limit: shorten
source/tail ranges if the proof fails. Nominal pad class follows the selected
voice; actual default classifications are measured and disclosed in the audition.

Target warm one-shot render <= 80 ms on a representative phone. Desktop timing
is measured separately and is not a phone guarantee. Complexity is O(frames)
with one delay line, bounded source duration, and existing bounded-cost DSP.
No solver or geometry cache is needed. First round has no cache. If measured
latency misses target, cache dry source by voice/TUNE/DAMP/velocity/model version
before considering algorithm changes. Never key on an incomplete macro subset.

## 7. Integration and build rounds

Round 1: `Backwash.kt`, `BackwashPatch.kt`, Patches dispatch, both Velocity arms,
SynthRoot drum exclusion, prototype roster, patch/PadRecipe registry fixtures,
determinism canary, corner/stage probes and WAV audition generator.

Picker target: append BACKWASH to `SynthScreen.Engine`; add voices, macros,
defaults, scramble, render, class and patch arms. Compile with Android SDK before
claiming picker integration verified. Existing UNDERTOW entry is untouched.

Round 2 after listening: refine gain/macro laws, approve factory presets, add a
16-pad kit and starter menu registration, check actual phone render latency and
MPC loading. HOLD and shared-bus phrase rendering require separate designs.
No factory-preset claims are made for first-round audition recipes.

## 8. Validation and sonic risks

- Kick comb cancellation: compare dry and processed low-band energy at SWAY ×
  TRAIL corners; owner judges loss of weight in the audition.
- Compression resurrects too much damped tail: compare DAMP × PRESS corners.
- Amp erases drum identity: stage-isolated clips and actual classifier output.
- Feedback instability: impulse followed by silence, maximum feedback and movement,
  unconditioned tail bound; a final limiter cannot rescue this test.
- Aliasing: source and amp retain their oversampled paths; new modulated-delay
  spectral error versus a higher-rate reference remains a listening-round probe.
- End click: final sample zero, last-window RMS reported, 4 ms tail fade.
- Determinism and saved sound: render twice and across JSON/PadRecipe round trips.
- Regression gate: `./gradlew --no-daemon test` for JVM; SDK compile for picker.

## 9. Verified / proposed / heard

Verified at baseline: SKIN voices/render API, Dsp tap/seed/filter/level APIs,
COMPRESSOR/VALVE process APIs, fixed rack and pedal order, existing UNDERTOW.
Proposed: physical analogy, shape constants, new voice balance, phone budget.
Measured: to be appended in As built from actual Kotlin probes.
Heard: no owner audition yet. No reference-pedal matching claim.

## Why it is built this way

- Existing drum models keep effort focused on the processing gesture.
- The moving double follows a short source, leaving the first hit readable.
- Compression after chorus makes the original and return share an envelope.
- The amp runs last so its coloration belongs to the complete sound.
- The risky choice is the wet kick: dry/delayed interference can remove weight.
  Its audition is a release gate, not something numerical safety proves away.

## As built

First implementation and measurements are recorded in `docs/BACKWASH.md`.
All five source voices, six controls, ordered processing, saved recipes, velocity
integration, prototype registration and picker/catalogue integration are built.
84 focused JVM tests pass; Android app and instrumentation-test Kotlin compile.
The audition has 195 WAVs. Full JVM regression is running at the initial handoff;
owner listening and device execution remain open. No factory preset promotion.

## Revision 2, after owner audition

Owner feedback: the dry source presents too much like a basic drum machine.
Replace the SKIN adapter with the dedicated `BackwashDrums` source described in
`docs/BACKWASH.md`; keep effects and macros fixed for the source A/B. Model
version is now 2; existing recipes adopt its sound. Original audition WAVs are
preserved. Constants remain voicing hypotheses. Numerical tests and rendered
comparisons cannot establish acoustic realism; owner listening is the gate.

## Revision 3, after reference supplied

Revision 2 was rejected by the owner. The supplied Miami Beach performance now
anchors the listening brief. Separate initial strike from body decay, preserve
short resonances, lengthen the snare-wire envelope, and raise the hats' register.
See `docs/BACKWASH.md` for source changes, comparison methodology and limits.
The four-bar groove is a balance audition, not a reference transcription.
Constants remain design hypotheses; no reference samples or claimed isolated
acoustic measurements enter the source. No factory preset approval is implied.

## Owner-preferred model-3 direction

Owner heard revision 3 and said “Much better, proceed in this direction.” Keep
its source and default values fixed. The next listening pack explores only
nearby DAMP settings and a stronger SWAY/TRAIL pair. Candidate recipes remain
outside the factory roster. Re-rendered reference WAVs must match the published
revision-3 one-shots and grooves byte for byte; see the neighborhood generator
and page builder. No new synthesis revision is introduced.

## MPC playing trial

The owner authorized the sixteen-pad kit, useful velocity layers and hat
choking. `generateBackwashKit` exports MPC 3 and MPC 2 formats with 48 source
strikes, complete velocity wrappers, read-back checks and actual-sample
preview. Source model 3 remains unchanged. See `docs/BACKWASH.md` for layout,
gain policy, layer serialization, evidence and hardware acceptance limits.

## Factory promotion

After positive feedback on the MPC-kit audition, the owner authorized the
factory-preset and app-validation step. `BackwashPresets` contains exactly
those sixteen model-3 recipes. Factory voice order starts at each heard
default; `kitOrder()` retains the shipped bank-A layout. The ordinary Presets
dispatcher exposes the bank to the app and CLI, and the exporter shares the
same table. No DSP values change. The 48 shipped-strike wrappers and PCM24
hashes are frozen as test evidence. Device and MPC hardware execution remain
unverified; this is not a claim of a deployed app release.

## Stronger processing audition

Owner requested four stronger treatments around the accepted source:
Seasick (chorus), Clamped (compression), Overloaded (amp), and Combined.
`BackwashProcessingStudy` is test-source-only; model 3 remains unchanged.
`docs/BACKWASH.md` records exact parameters, feedback bounds, level matching,
preserved reference evidence, measurements and replay limits. Adoption into
factory settings or phone/MPC exports awaits a listening verdict.

### Processing promotion, 2026-11-08

The owner approved continuing the stronger processing study. Processing model 4
promotes the four treatments into 20 editable factory presets, alongside all
16 originals. SWAY/TRAIL/PRESS/DRIVE extend above midpoint to the study settings;
source model 3 remains unchanged. Recipe JSON now stores model 3 or 4, with
missing model selecting the frozen original renderer. Existing factory presets
retain model 3. See docs/BACKWASH.md for mappings, version behavior and the new
16-pad processing kit arrangement. The original study is heard; pad feel and
Android performance are pending device testing.
