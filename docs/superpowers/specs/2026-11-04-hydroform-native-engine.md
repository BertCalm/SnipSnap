# HYDROFORM Engine: native implementation specification
Version 2.0, 2026-11-04. Accepted Python R5 translated to Kotlin; native listening outstanding.
Repository truth: 482c55a146b1569ba9ccad27e4ebd274ad398df4.

## 1. Summary and scope
The characteristic gesture is **pitched strike → dispersed pressure return → slope-dependent
tearing → deliberate arrest**. Literal DSP implements this; hydroforming is an analogy.
Ship DOME and BULKHEAD plus DOME HOLD, saved recipes, registry/velocity wiring and native
comparison audio. Final factory presets, kits and Android picker work await native listening.
Nearest existing roles are THUMP and FATHOM; HYDROFORM keeps its derivative/arrest gesture.
The second voice is a different source in the same engine, not a second architecture.

## 2. Contract and signal flow
Offline mono 44.1 kHz, 4x source/shaping. No randomness, external samples or time dependence.

```
voice + velocity + settled macros
    -> analytic source @176400
    -> direct + three delayed all-passes + front delay @176400
    -> root-scaled derivative + 1800 Hz one-pole bilinear detector
    -> smooth activation + dynamic clip/fold blend
    -> raised-cosine arrest + .94 internal clip
    -> Tide.bandLimit -> Dsp.decimate -> subtract mean -> 20 Hz high-pass
    -> Dsp.levelTo(MELODIC_LOUDNESS_TARGET) -> Dsp.fadeTail (one-shots only)
```

All state is per render. Shared Dsp/Keys/Tide calls were read in the named commit, as were
Patches.decode, Velocity.macroSpecsFor/touchedRender and the rosterless test registry.
No `siblingPatches` API exists. Keys.MAX_SEAM_ERROR is 1e-3. Classifier's 1.5-second threshold
is private. No seed is invented: without noise, identical input is deterministic by construction.

## 3. Model and energy
The exact source contract is frozen in reference/hydroform/prototype.py. DOME: f0 =
49 * 2^(2*(TUNE-.5)); phase integral t + 2.5*.018*(1-exp(-t/.018)). BULKHEAD uses root
147 Hz with ratios 1, 1.593, 2.317, 3.711, 5.167 and decays .18/.13/.095/.07/.045 s.
Those ratios are auditioned shape values, not measured steel modes.

STANDOFF sets delay .0005*32^s seconds, stages 1/1.41/1.89 times that delay, with ties-to-even
integer sample rounding. Each stage obeys y[n]=.5*x[n]+x[n-D]-.5*y[n-D]; poles have radius
.5^(1/D)<1. A 0–24 ms front delay precedes addition to the direct source with gain
.95*exp(-t/.085). This is interference, not guaranteed impulse concentration.

Derivative magnitude is multiplied by Fs/(2*pi*f0); a 1800 Hz bilinear first-order lowpass
produces d. Threshold T=.15+1.65*YIELD; activation q=d²/(d²+T²). Drive k=1+2*CHARGE.
Output before arrest: (1-.9*q)*tanh(1.5*k*x)+.9*q*sin(k*x*(1+4*q)).
ARREST uses a=.75*macro, stop=.65*(.045/.65)^a and ramp=.0015+.020*(1-a)^2.
The raised-cosine stop ends the source; a clamp alone cannot end a decay. The model has
bounded analytic excitation, stable all-pass state and bounded transfer functions; the
internal clip is a sound stage, not the feedback stability argument.

## 4. Controls, voice and root
See docs/HYDROFORM.md for defaults and ranges. TUNE remains continuous to preserve R5,
with approximate MIDI labels, not silently snapped semitones. Velocity is saved and alters
contact and excitation. Non-finite engine macros fall back to defaults; unknown engine keys
are dropped. Saved-patch validation remains strict. Model 1 is explicitly serialized and
future model numbers are refused. Fixed-slope shaping is an audition reference only.

## 5. HOLD
DOME exposes a reserved top step: exactly HOLD=1 selects a two-second loop of impacts every
.25 seconds, assuming 120 BPM. Render three cycles of actual warm-up, retain a cycle, and
render another independent continuation. Keys.seamError compares the retained boundary with
the actual preceding cycle; a separate energy-normalized continuation error must also pass
1e-3. Level the retained cycle; do not fade its edge. BULKHEAD does not expose HOLD.

## 6. Cost and integration
Linear bounded work: one source or five analytic partials plus three delay lines and a
one-pole detector per sample. No geometry/ODE cache is needed. Target: one-shot knob turns
in tens of milliseconds after JVM warm-up; phone timings remain unverified. HOLD reuses one
rendered impact before filtering its train. Native timing is collected, not inferred from Python.

Registered surfaces: Hydroform.kt, HydroformPatch.kt, Patches dispatcher, both exhaustive
Velocity arms, PatchTypes.ROSTERLESS, hand-built PatchesTest/PadRecipeTest entries,
DeterminismTest canary, HydroformTest, generateHydroformAudition and this documentation.
No production preset roster or kit is fabricated before the listening gate. App picker is
deferred because no Android SDK is installed. README engine count and roadmap reflect a
registered prototype rather than a completed factory instrument.

## 7. Validation and sonic risks
Validate raw Kotlin/Python parity before assessing output-stage differences. Test finite,
peak/DC, leveled macro activity, source touch, strict JSON replay, settling, arrest timing,
HOLD preceding-cycle and continuation error. Collect real pitch diagnostics; do not force an
unreliable full-buffer autocorrelation into a false tuning guarantee for inharmonic impacts.
Run ./gradlew --no-daemon test and record its actual exit code. Native audio remains unheard.

Risks: strongest BULKHEAD folding can alias; extreme shaping may obscure the root; the native
leveling stage can change relative pad weight; warm-up/retained-loop level must not be confused.
The Python hard-corner residual (~-18.4 dB at 4x vs16x) includes discretization/filter effects,
so no alias-free claim is made and no unapproved high-rate mode silently replaces accepted sound.

## 8. Evidence ledger
- Heard by Josh: R1 controls became audible; R2 ARREST range preferred; R3 other controls liked;
  R4 dynamic slope adds value, followed by authorization to promote and port.
- Verified: APIs and registration surfaces read at 482c55a; exact equations frozen from R5.
- Measured: native results belong in the As built section after execution.
- Proposed/unverified: native listening acceptance, Android latency, final presets and hardware export.

## Why it is built this way
Sources and transfer laws preserve the accepted voice rather than redesigning it during the
port. Stable local filters make repeat renders pure. Native output reuse avoids a second house
chain. Separate raw comparisons catch DSP errors that level matching would conceal. Explicit
prototype status prevents unfinished presets or kits from masquerading as a shipped instrument.


## As built: native model 1

Implemented engine, versioned/velocity-preserving patch, registry, both Velocity arms,
rosterless hand-built registry fixtures, DeterminismTest canary, dedicated probes,
SynthRoot continuous-TUNE metadata arm, generator and frozen Python references. The shell
arm was required by its exhaustive sealed-Patch dispatch; no Android picker work was guessed.

Targeted Gradle run completed with exit 0, including six HydroformTest probes, its canary,
PatchesTest and PadRecipeTest, plus native audio generation. Tests cover both voices at all
32 corners, output finite/peak/DC/loudness, all five macro endpoints, velocity, exact zero-touch
silence, strict recipe replay, NaN fallback and unknown-key settling, scramble bounds, approved
~88 ms maximum ARREST, lightly driven open-body pitch and real DOME HOLD seam/continuation.
The pitch detector starts at 40 Hz, so DOME notes below it are not given fabricated pitch
assertions. No all-corner perceived-pitch guarantee is claimed.

Six raw parity comparisons (both defaults plus hard corners at 4x and 16x) passed:
maximum sample error 2.981e-8, RMS below 5.7e-9, after matching Float macro inputs.
After fitting output gain, default native/Python residuals were -42.14 dB DOME and -37.06 dB
BULKHEAD at zero-frame alignment. Those residuals include expected output-stage differences
and are not alias bounds. Native Tide cutoff is 19.5 kHz versus Python's 20 kHz; Kotlin
uses the actual two-stage house decimator and perceived-loudness stage.

95 native/Python pairs, including six native CHARGE-1/YIELD-0 register corners, were generated. Observed one-shot median 36.70 ms, p95 58.26 ms;
DOME HOLD 108.62 ms, on Temurin 21.0.13 with 3 reported processors on this cloud host.
These are generator observations, not an Android or isolated warmed-JVM latency benchmark.
Native default pitch diagnostics: DOME 49.21 Hz / confidence .839; BULKHEAD 149.73 Hz /
confidence .615. Maximum absolute DC across generated native files was 2.85e-05.
The retained HOLD was 88200 frames, mean -4.90e-9 and house loudness .1834.

Native audio has not been heard by the author or accepted by Josh yet. Python R5 remains
the heard reference. Factory presets, kit integration and Android picker remain deferred.
Full JVM suite completed with exit 0 in 1h 35m 52s. All nine JVM modules passed; no Android SDK was present. Module totals and production source hashes are recorded in reference/hydroform/validation.json.


Actual native classifier diagnostic (PCM WAVs, current Classifier): default DOME is PERC
at confidence .40; default BULKHEAD is SNARE at .662; HOLD is LOOP at .90. Distribution
across the initial 89 non-hot studies: PERC 56, SNARE 30, KICK 2, LOOP 1. The declared KICK/PERC classes are
intended musical slots, not a passed classifier-agreement claim. This is disclosed and
not corrected by re-voicing; factory kit labeling remains a listening/integration decision.

The final expanded 95-file batch includes six native hot-register corners. Classifier counts: KICK 2, LOOP 1, PERC 57, SNARE 35. The completed full JVM run reported 8,437 tests, zero failures/errors/skips. The updated audition generator was then compiled and executed successfully; raw parity was rechecked.
