# Skirl: acoustic HOLD and exact render optimizations

Ground truth: SnipSnap commit `4ecd6fe1a14ac24a1ee446d87c81b35d5c8b8778`.
This extends the [finite design](2026-10-23-skirl-engine-design.md). Luthier's REVIEW
identified render cost as the playability limitation; CONCEIVE supplies the new held
gesture. The owner authorized performance work and HOLD on 26 October 2026.

The characteristic held gesture is **knock → rolling texture → captured sustain → repeat**.
Intermediate HOLD instead returns to **shudder → ringing stillness**.

## Mechanism and landing

The finite Newton–Euler mechanics remain literal and unchanged. HOLD is an acoustic
capture analogy: external replenishment sustains a contact-derived excerpt. It does not
simulate a motor, a steady driven mechanical orbit or infinite passive rolling. No
excitation-budget claim for the original finite trajectory extends to captured repeats.

Nearest siblings: PITCHWHEEL makes a driven wheel settle to a repeating mechanical state;
MERCURY plans periodic modal excitation. Skirl instead retains the irregular acoustic
texture of its solved disk runout. This is a HOLD extension of its two existing voices,
not a new engine, preset roster or export format.

Three directions were considered:

| Direction | Gesture and novelty | Risk | Cost | Decision |
|---|---|---|---|---|
| Zero resistance in the solver | Continuous rolling, but loses the finite energy story | No calibrated steady-state domain or guaranteed closure | New mechanics and solver validation | Defer |
| Periodic source plus solved modal steady state | Retains contact excitation with a genuinely repeating modal response | New cyclic-state solver and changed sustain timbre | Highest development and render cost | Possible later refinement |
| Capture modal output at 4×, then condition consecutive cycles | Retains the actual irregular texture and a designed finite return | Capture repetition and overlap are audible design choices | One finite render plus bounded overlap/output work | Selected |

```text
unchanged finite mechanics → budgeted contacts → disk/support modes at 4×
                                                │
                 HOLD 0 ────────────────────────┤
                 HOLD < .99 → insert captured cycles → resume original runout
                 HOLD ≥ .99 → attack + captured cycles + true lookahead
                                                │
                 house band limit → decimate → mean/DC removal
                                                │
                 independently measured prior/current cycles → level
                                                │
                 finite: fade tail / loop: no tail fade
```

## Control and replay contract

All six existing macros retain their finite mapping. Their acoustic effects also alter
the captured signal. LEAN/BRAKE/COLLAPSE select a different solved motion; EDGE/PLATE and
velocity change its readout; TUNE preserves the body root. The physical cache stays keyed
only by mechanical inputs, independent of MIDI, material and touch.

| HOLD | Result | Class | Proof |
|---|---|---|---|
| 0 | Original finite path | TONAL | Independent 106-case model-1 bit parity |
| 0..`.99`, exclusive | `round(6*HOLD/.99)` extra captured cycles, then original ending | TONAL | Stepped frame counts and restored conditioned tail |
| `.99`..1 | A closed multi-capture span in current `render`; original attack plus a nonzero loop marker in `renderHeld` | LOOP | Real preceding filtered span, whole-span convergence, pitch, wrap step/slope/dip |

A capture spans about `.56–.61 s` in the calibrated domain, rounded to output frames near
a whole number of root periods. Its approximately `.12 s` unity-sum overlap closes the
actual continuation into its beginning. Maximum finite extension is six such cycles,
about `3.6 s`; tiny HOLD settings select zero extra cycles. This is intentionally stepped.
The overlap and capture lengths are authored design constants, not measured disk properties.

Model 3 is the default saved model. Its export retains at least three real captures,
strictly longer than the classifier's private 1.5-second LOOP threshold. The generic
classifier filed 14 of the 36 short model-2 cores as KICK/TOM because of their bass decay,
despite their pitched root and closed seam. The shared classifier is unchanged. Published
model-2 audio and held markers remain byte-exact; model 2 keeps its original preparation
and short cut. The current span has independent preceding/current blocks and real
lookahead, not repeated normalized output or silent padding.

Explicit model 1 remains supported and refuses nonzero
HOLD before JSON Float conversion. Its seed stays pinned to 1, including model-2 HOLD-zero
renders. `SkirlPatch.render` forwards its saved model. Required model/MIDI/velocity fields
remain strict. Velocity dispatch, recipe edits and CLI replay retain them. `renderHeld`
uses the existing `KeyNote` type; this milestone adds no app picker or keygroup command.

## Numerical and render constraints

The capture stays at the existing 4× internal rate. Planning boundaries in output frames
keeps the decimator phase fixed. Model 3 conditions two settling captures, a genuine
preceding span, the exported span and a complete following
cycle beyond the exported cut, so sinc lookahead never substitutes silence at a loop end.
Neither the exported closing samples nor the preceding diagnostic span are copied after
conditioning. Both 256-frame seam error and normalized whole-span difference must be
below `Keys.MAX_SEAM_ERROR` (`1e-3`); a silent loop reports zero by explicit convention.

Level comes from the steady cycle for a held key note and is applied to the whole note;
the house sample-peak ceiling may reduce that note uniformly. Loop outputs skip tail fade.
Finite outputs keep the standard tail fade. Thread interruption cancels capture work.

Performance changes preserve operation order and signal values: input filters stop at
REST, where their remaining output was already discarded, and the shared power-of-two
resampler reuses interior normalization and unrolls consecutive mono taps in the same
accumulation order. Edge kernels retain their existing normalization. No mode, precision,
sample rate, solver tolerance or audible tail is removed. Full render costs and standalone
reproduction commands are under `prototype/skirl/native/performance/`.

## Evidence ledger and sonic risk

- Verified: actual `Skirl`, `SkirlPatch`, `KeyNote`, `Keys.seamError`, `Dsp`, `Modes`,
  `Tide`, `Resampler`, CLI and velocity APIs were read before implementation.
- Measured: 106 finite production/prelevel arrays and acoustic diagnostic ledgers match
  frozen model-1 jars bit-for-bit after the optimizations. The final report records hashes.
- Measured: 36 held register/material/touch cases stay within `19.114 cents`, confidence
  at least `.8387`, absolute DC below `7.1e-9`, and pass independent cycle and wrap guards.
  A disturbed preceding cycle fails both unchanged `1e-3` error limits.
- Measured: the isolated model-3 proof passes all 15 changed test methods, including
  genuine span convergence, 36 actual LOOP classifications and 12 frozen model-2 Float
  hashes/held markers. Normal Gradle validation is recorded separately; a LOOP duration
  classification does not substitute for the independent pitch and sound checks.
- Measured: the final pack retains one refused estimate among 36 fixed windows and six
  refused estimates among 24 soft-C2 phase windows. All 12 primitive-cycle estimates return
  a root within `15.204 cents`; full-export span estimates stay within `22.229 cents`; returned phase estimates stay within `24.054 cents`.
  The refused WHIRR windows retain a root-local peak below the detector's relative gate.
  These results qualify the first-window matrix rather than promise invariant pitch.
- Verified: the audition independently replays saved render methods and checks WAV root/
  loop metadata. It includes common gain and eight consecutive wraps for listening.
- Proposed: the capture location, overlap and six extension steps are useful musical
  choices. Josh's listening must judge their repeat character and transition shape.
- Heard: earlier Python auditions received positive feedback. Native HOLD listening has
  not been specifically accepted; this session claims no assistant listening.
- Disclosed: Android SDK/device absent. The opt-in instrumentation diagnostic is prepared
  and syntax/API-checked with stubs, not Android-built or phone-measured. App picker,
  factory presets and keygroup UI remain outside this round.

The new risk is repetition: HOLD freezes an excerpt, so it no longer accelerates as the
original runout did. Eight-wrap montages expose that trade. Whole-period planning protects
the root but does not make every inharmonic mode periodic independently; overlap shapes
those components. The register/material tests and common-gain files bound the numerical
risk, while only owner listening can decide whether that capture is musically successful.
Bounded `.9 s` and `1.2 s` alternatives traded improvements at C2 for weaker behavior at
other notes. They were rejected after 30 transition renders; the original capture remains.
Its ROLL soft-C2 strongest periodic spectral line sits about 45 cents below the target,
although unconstrained whole-cycle correlation estimates are closer. This is inside the
initial 50-cent root bound and is retained as a disclosed capture risk for listening.

## Why it is built this way

- Capturing the real modal output keeps the rolling texture already established by the finite model.
- Keeping its energy ledger separate makes the added sustain honest about external replenishment.
- Returning to the original runout gives intermediate HOLD a definite mechanical ending.
- Independent filtered cycles and real lookahead test the exported join before sampler repetition.
- Exact work removal improves responsiveness without changing the earlier finite audition.
