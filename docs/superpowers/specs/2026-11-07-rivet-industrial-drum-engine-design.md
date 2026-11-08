# RIVET: industrial drums in a building

Version 1.0 · 2026-11-07

Status: engineering specification and first Kotlin implementation. All voicing constants
marked **shape** remain listening hypotheses. See As built for executed validation.
Repository truth: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.

## 1. Summary

The characteristic gesture is **impact → metal rattle → walls answer → dark decay**.

RIVET is its own drum engine with shared controls across voices. SIZE takes a close,
cramped workshop into a warehouse while a parallel close path keeps the beat legible.
The physical ingredients are damped modal resonators, a finite noise burst and geometric
room reflections. Industrial materials are a sound-design analogy; no real sheet, machine
or building has been measured. No rigid-body solver or structural building simulation runs.

## 2. Goals, landing and sibling check

First round: KICK and SNARE, offline mono one-shots, editable saved patches, native
strike velocity, control probes and replayable auditions. TOM, METAL and HAT are future
voice roles, not entries to add before hearing the first two.

Nearest siblings are SKIN (modal drums, wires and impact controls) and CHAMBER (a modal
quartet with material boundaries). SKIN has no integrated room-size gesture; CHAMBER's
quartet, controls and tonal filing do not provide this two-role industrial drum interface.
ROOM already supplies the spatial DSP, so no new reverb or room pedal is commissioned.

The three directions considered:

| Direction | Operator | Gesture / roster novelty / risk / cost |
|---|---|---|
| Strong: struck body, metal, drive and parallel room | STACK | Clear; new shared drum interface over existing DSP; low solver risk; one prototype round then listening and product integration |
| Building as the played resonator | INVERT | Clear; closer to OVERLAP and CHAMBER; medium bass-focus risk; roughly three rounds |
| Impacts wake loose machinery | COUPLE | Clear; closer to TREMOR and CHAMBER; higher stability and rhythm-smear risk; roughly four rounds |

Strong wins because the requested change of scale is explicit and independently testable.
Its novelty is the playable contract across drum roles, not a new resonance algorithm.

## 3. SnipSnap contract

- Mono 44.1 kHz output. Excitation, resonators and nonlinearity run at 176.4 kHz.
- `Tide.bandLimit` precedes `Dsp.decimate`. ROOM's acoustic network runs at output rate;
  its normal processor also peak-matches the result to its input.
- `Dsp.seedFor` and `Dsp.Noise` provide deterministic noise, common across knob sweeps.
- Six plain macros plus a refused HOLD. Model 1 cannot loop.
- This is a **drum** engine: KICK and SNARE are intentional classifications. The pitched
  engine prohibition on those classes does not apply.
- Sources are 0.45 s (kick) and 0.36 s (snare), each followed by at most ROOM's existing
  1 s allowance. Outputs must stay below the 1.5 s classifier boundary.
- Use the drum peak convention: remove DC, high-pass at 20 Hz, fade, normalize to .95.
  Do not impose the melodic loudness target on a drum prototype.
- Full immutable macro snapshots and an explicit `modelVersion` travel through JSON.

## 4. Signal flow

```text
voice, macros, velocity
          |
          v
[finite contact + seeded noise @176.4 kHz]
          |
          +--> [membrane modes, 4] --+
          +--> [metal modes, 4] ----+--> [drive @176.4 kHz]
          +--> [wire/edge noise] --+              |
                                                 v
                                [band limit -> decimate -> DC/HP/fade]
                                                 |
                   +-----------------------------+------------------+
                   |                                                |
             [close, weight .60]                   [kick send: 2 x 150 Hz HP]
                   |                                                |
                   |                               [ROOM -> kick: 2 x 150 Hz HP]
                   |                                  weight .40 @44.1 kHz
                   |                                SIZE / WALLS, fixed mic
                   +----------------------+-------------------------+
                                          v
                              [DC/HP/fade -> peak .95 -> Snip]
```

The weights are **shape**. ROOM contains its own direct sound; this branch is a parallel
room blend, not an extracted wet return. A later crushed/gated return requires explicit
stem support and its own test. No audio returns from ROOM to the drum or drive.

## 5. Architecture

`Rivet` owns defaults, settling, source construction, macro laws, drive, room mapping and
output. `RivetPatch` owns the immutable saved controls and renderer version.
`Modes.Bank` owns stable double-precision damped resonators. `Room.process` owns the existing space.
RIVET does not alter SKIN, ONSET, CHAMBER, ROOM or their saved recipes.

`inspect` exposes close and room branch samples plus pre-drive peak for tests and audition
diagnostics. They are observations, never extra feedback paths.

## 6. Contact, membrane, metal and root

One raised-cosine force pulse has a unit discrete integral. STRIKE and velocity change its
width between 3.5 ms and 0.25 ms (**shape**). Harder contact retains more upper spectrum.

```text
p[n] = Hann(n, width) / sum(Hann)
root = exponentialMap(TUNE, kick:42..78 Hz, snare:140..260 Hz)
membrane ratios = [1, 1.59, 2.14, 2.65]
metal ratio[i] = [2.76, 4.82, 7.13, 9.61][i] * (1 + .12*METAL*(i+1))
metal T60 = .08 + .22*METAL seconds
```

Ratios and decay times are **shape**, not sourced material tables. The fundamental stays
fixed when METAL changes. METAL stretches only the upper bank, increases its participation
and lengthens its decay. Snare wire-noise lifetime also increases with METAL.

Each mode uses the double-precision exact damped rotation in `Modes.Bank`, with no coupling
springs. A unit-integral pulse drives velocity; displacement multiplied by angular frequency
is the pressure readout. This gives fixed radiating weights without normalizing individual
events or late responses. `StrikeVelocity.modeFactor` darkens higher modes on soft strikes.
There are four membrane and four metal modes; neither count depends on macros.

## 7. Noise and drive

Noise is high-passed, low-passed and exponentially enveloped. KICK uses a short beater edge;
SNARE uses a longer wire burst. Noise has no independent clock or secondary strike schedule.
The seed includes RIVET, model 1 and voice, never the macro map, so a knob does not reroll noise.

```text
g = exponentialMap(DRIVE, 1, 12) * settledVelocity
y = tanh(g*x) / tanh(g)
```

This is a smooth, memoryless nonlinear shape, not an amplifier model. Velocity changes
contact width, partial weights, noise and the drive's admitted level, not just output gain.
One finite strike supplies the whole response. Fixed bank weights and decays bound the
source; the drive has a finite bound. No limiter establishes feedback stability.

## 8. Space and scale

Pass SIZE and WALLS directly to `Room.process`; use fixed DISTANCE .75 (**shape**).
ROOM geometrically scales from a 2.4 × 2 × 2.2 m booth to a 28 × 20 × 11 m hall.
Its image paths move with size; WALLS changes absorption and high-frequency losses.
The late network is orthonormal with lossy filtered lines and its existing stability clamp.

SIZE does not retune the drum or increase drive. The close branch must remain sample-identical
across SIZE and WALLS changes. The output's room response must still change measurably.
The kick's room send and return each have two 150 Hz high-pass stages (**shape**), leaving
its sub on the close path. Without that separation, a warehouse reflection comb displaced the measured
low spectral maximum even though the membrane's own root stayed correct. The snare sends
its full spectrum. This is a designed microphone/send response, not architectural physics.
This is a static room per render; turning a knob triggers a new render rather than resizing
a ringing network in real time.

## 9. Macro contract

Defaults are **shape**. Tests establish signal change; listening decides usefulness.

| Control | Kick / snare default | Model quantity | Required interaction | Proof |
|---|---|---|---|---|
| TUNE | .35 / .45 | Membrane root and metal frequencies | TUNE × METAL | Low-band kick root retained at metal endpoints |
| STRIKE | .60 / .60 | Contact width and noise bandwidth | STRIKE × DRIVE | Low/middle step residual; pair cross-difference |
| METAL | .40 / .40 | Upper ratios, weights, T60 and wire life | METAL × DRIVE | Midpoint differs from endpoints; root stays anchored |
| DRIVE | .40 / .40 | Oversampled saturation gain | STRIKE × DRIVE | Low/middle steps; higher-rate distortion reference |
| SIZE | .30 / .30 | Room dimensions and travel times | SIZE × WALLS | Later bloom, changed tail; byte-identical close branch |
| WALLS | .70 / .70 | Absorption and spectral loss | SIZE × WALLS | Changed decay and tail spectrum |
| HOLD | 0 / 0 | Unsupported in model 1 | None | Nonzero finite values explicitly refused |

All direct render controls settle non-finite values to defaults, clamp finite values and
drop unknown keys. A saved patch validates a complete finite map instead of repairing it.
Scramble always returns HOLD 0.

## 10. Voices and HOLD

KICK is a low membrane with a restrained metal bank and short beater edge. SNARE is a higher
membrane with stronger metal and a finite noise-wire response. Each is one hit, not a phrase.
HOLD is refused, following the house's explicit-refusal strategy. There is no seam claim.
No factory presets are authored before the first listening verdict. Workshop, factory and
warehouse are labelled audition cases, not promoted presets.

## 11. Render budget and preparation

Target: a normal knob rerender in tens of ms; initial target under 100 ms warm on a phone
is **proposed**, not a measured device claim. Host timings must identify the JVM and machine.
ROOM already computes taps and coefficients once per render and calibrates its late gain
with a finite click render. No solver, mesh or unbounded event loop exists.

R1 has no persistent cache. First measure cold/warm calls and room cost. If the phone misses
budget, cache immutable room plans and late-gain calibration by SIZE, WALLS, DISTANCE,
sample rate and room model version, with a bounded LRU. Changing TUNE/STRIKE/METAL/DRIVE must
not invalidate those plans. Never share mutable delay buffers or source arrays between renders.

## 12. Numerical probes and sonic risks

Required: all 64 macro corners per voice finite, raw peak bounded, output peak .95, DC under
.005, deterministic replay, correct mono/rate/duration, unknown/non-finite controls, bounded
scramble, saved JSON/recipe replay, model/HOLD refusal, native velocity and macro pairs.

| Sonic risk | Proof and listening question |
|---|---|
| Warehouse washes out the beat | Close-stem independence and matched-level space grooves; is the hit still immediate? |
| Metal steals the kick root | Low-band pitch at TUNE/METAL corners; does the kick retain weight? |
| Drive aliases | 4x distortion against a higher-rate reference, with a deliberately naive 1x comparison |
| Every voice is a bell | Distinct membrane/noise proportions; listen to kick/snare side by side |
| Long room cuts away | Check decaying tail and fade; disclose the existing 1 s room cap |
| Loudness sells size | Native and common-loudness audition WAVs; report both levels |

## 13. Integration and rounds

Verified integration surfaces: `Patches.kt` decoder; `Velocity.kt` macro and touched-render
arms; `PatchTypes.PROTOTYPES`; `PatchesTest`; `PadRecipeTest`; `DeterminismTest`; a new
`RivetTest`; a `generateRivetAudition` JavaExec task in `synth/build.gradle.kts`.
`shell/SynthRoot.kt` explicitly treats continuous RIVET drum tuning like SKIN/ONSET,
without a semitone root editor; the sealed patch dispatcher must name the new subtype.
Add docs here, `docs/RIVET.md`, a roadmap entry and README prototype notice.

R1 ends with two registered voices and native auditions. Run `./gradlew --no-daemon test`;
judge by exit code. Record independent pre-existing failures separately if encountered.
R2 follows the owner's sound verdict: revise attack/metal/room balance, then add TOM/METAL/HAT
only where each earns a pad. R3 builds factory presets, a velocity-layered sixteen-pad kit,
both MPC export formats and hardware checks. HOLD sustain is a separate optional design.

Android picker integration waits for an Android SDK and a tested adapter. No SDK is present
in this session. No FRESH TAPE entry is added before an actual kit exists.

## 14. References and evidence ledger

Only existing SnipSnap DSP is reused. No external product was benchmarked or sampled.
Source inspection: `Modes.kt`, `StrikeVelocity.kt`, `Dsp.kt`, `Room.kt`, `FxChain.kt`,
`OnsetPatch.kt`, `Patches.kt`, `Velocity.kt` and the registry tests at the named commit.

| Claim | Status | Evidence |
|---|---|---|
| ROOM already provides geometric size and wall controls | Verified | `Room.kt` macro list, plan and process |
| RIVET can use a drum class | Verified design fit | Existing SKIN/ONSET drum engines; pitched-only rule is inapplicable |
| Shared industrial macros are useful across kick/snare | Proposed | Requires the native audition |
| Shape constants create convincing industrial metal | Proposed | No measured material and no listening verdict |
| Phone render budget | Proposed | Host timing cannot substitute for phone timing |
| Sonic character | Not heard | Owner audition outstanding |

## Why it is built this way

- The close path makes the attack survive as the building grows around it.
- A separate metal bank changes the shell's response while preserving the kick's root.
- Shared modal and room DSP puts the work into the instrument's controls and voicing.
- Two voices expose the important trade early. The riskiest decision is the fixed close/room
  balance: measurements can prove separation, but only listening can choose the balance.

## As built

R1 contains KICK and SNARE, the six shared controls, a refused HOLD, `RivetPatch` with
complete immutable controls and renderer version 1, six provisional space recipes in
`RivetPresets`, decoder/velocity integration and prototype-registry coverage. No factory
roster, kit or Android picker was promoted. `SynthRoot` names RIVET explicitly as a drum
without a semitone editor; the broader compiler check found that additional integration row.

The first low-root probe found a 109-cent displacement of the warehouse output's spectral
maximum. A separate close/output study localized the remaining displacement to the room:
the close branch's maximum remained 42.06 Hz for the 42 Hz case. Double-precision modal
rotations are retained, and the kick now filters both room send and return. Applying the
send filter alone still left a 76-cent upper-end displacement; the paired return filter
was necessary. No shared ROOM or older engine renderer was changed.

Native Kotlin measurements on OpenJDK 17.0.21:

- `RivetTest`: **9 tests, zero failures**, Gradle exit 0. The class took 8.611 s in that run.
- All 64 macro corners per voice: largest pre-drive peak **1.6869881**, largest absolute
  output mean **0.00000591197**, every render finite, peak .95 and shorter than 1.5 s.
- TUNE 0/.5/1 × METAL 0/1 with warehouse SIZE 1: largest low-band root displacement
  **8.68 cents**. The spectral search steps by 0.5% in frequency; this is not a sub-cent
  pitch measurement. The root assertion remains 50 cents.
- 3 kHz sine at the maximum drive gain: 17.1 kHz folded component amplitude **0.1132371**
  at 1x, **0.000129677** at 4x and **0.0000217985** at 16x. The 4x path reduces that
  component by about 58.8 dB against the deliberately naive 1x control. This probes the
  nonlinear stage; it is not a complete spectral audit of every noise-based voice.
- Unknown/non-finite controls, low/middle control movement, macro pairs, native velocity,
  immutability, JSON version refusal, HOLD refusal, close-stem independence, settling
  warehouse tails and actual default KICK/SNARE classifications all passed.

The native audition contains **98 native/matched pairs**, two close-only diagnostic clips
and three two-bar grooves. Its manifests save the full patch and strike velocity; the
grooves share one mix gain across room sizes. Independent Python checks on the **196**
normal exported PCM24 files found maximum peak **0.98999989** (matched files may use the
.99 ceiling), maximum absolute DC **0.00000242606**, maximum duration **1.155805 s**, mono
44.1 kHz throughout and complete version-1 recipes. `export-checks.json` records the check.

Python house probes on the six space cases report finite output and settling tails.
Their near-Nyquist partial counts on noise are not an aliasing measurement, and snare
pitch estimates are descriptive, not a tuning contract; those limits are in
`house-probes.json` beside the measurements.

The audition's warm host measurements were recorded while the broader Gradle regression
was also active: **71.82 ms median, 140.09 ms maximum** across 98 calls, on a four-processor
OpenJDK 17.0.21 host. These are contended host timings, not phone measurements. Phone cost
and the proposed under-100-ms target remain unverified.

Regression status: the initial broad build caught the missing `SynthRoot` sealed arm;
that arm and its regression assertion are now present. The unfiltered command
`./gradlew --no-daemon --max-workers=4 test --fail-fast` is running after that repair.
No full-suite pass is claimed until its exit code is recorded.

Heard: **nothing**. The first listening verdict, phone latency and hardware checks remain
open. The next sound round follows the owner's attack/metal/space verdict.
