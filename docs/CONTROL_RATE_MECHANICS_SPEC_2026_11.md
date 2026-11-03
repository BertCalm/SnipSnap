# Control-rate mechanics for the slow engines, 2026-11

Status: proposal. Nothing in this document is built. Nobody has heard any of it.
Written against commit `e18d9237` ("Shared FastMath.tanh for COROLLA and TREFOIL"),
2026-11-02. Follows item 5 of "Render speed, 2026-11-01" in
`docs/ENGINE_STANDARD_SCAN_2026_11.md`: "Mechanics at audio rate (design work, the
largest gains)".

## 1. Summary

Item 5 says CIRCUIT, UNDERTOW, NIMBUS, TESSERA, PITCHWHEEL and REVEL step their whole
physical simulation at 176.4 kHz. Reading the code shows that this is true of only
one of them. **UNDERTOW, NIMBUS, TESSERA, REVEL and CIRCUIT already run their slow
mechanics on a control clock.** In each of them the time goes to work that has to
happen every sample: resonator banks, propagation paths and per-sample couplings.

| Engine | Slow mechanics today | Where the time actually goes |
|---|---|---|
| UNDERTOW | pressure, flaps, seals, contacts at 1 kHz (`Undertow.kt:241`, `CONTROL`, `controlRateHz = 1000`) | 15 to 19 `Mode.step` calls per internal sample in the acoustic network (`Undertow.kt:332-390`) |
| CIRCUIT | walker geometry every 64 samples (`Circuit.kt:555`) | the observer: 7 sources x 3 delay paths per sample, each with a division (`Circuit.kt:569-579`), then the tube `tanh` (`Circuit.kt:322`) |
| NIMBUS | suspension and field every 128 samples (`Nimbus.kt:44`, `control()` at `:656`) | 6 plates x 16 modes rotated per sample (`Nimbus.kt:297`), wave scattering, and 6 or 18 HOLD preroll cycles |
| TESSERA | wall geometry every 64 samples (`Tessera.kt:49`, `:501`) | 43 modes, six chamber paths, collector detectors, all per sample |
| REVEL | moving microphones every 64 samples (`Revel.kt:62`, `:681`) | head and floor modes, per-mode loss bookkeeping, moving taps |
| PITCHWHEEL | **none: `mechanics()` runs every internal sample** (`Pitchwheel.kt:493`, `solverRate = INTERNAL_RATE`) | the wheel solver plus `Wood.tick` |

So the honest reading of item 5 is narrower than it was written:

1. **CIRCUIT** has the largest concrete control-rate win: its observer computes three
   distance gains per source per sample with a division, while the distances themselves
   are already ramped linearly across 64-sample blocks. Ramping the gains the same way
   removes 21 divisions per internal sample from the hottest loop in the profile.
2. **UNDERTOW** has almost nothing left to move to a control rate. Two exact,
   byte-identical simplifications are available, plus one real control-rate move for the
   FLUTTER voice (leather flexure modes at 12 to 26 Hz stepped at 176.4 kHz but read only
   at 1 kHz).
3. **PITCHWHEEL** is the only engine that literally runs its mechanics at audio rate,
   and it already has a half-rate solver switch with a convergence test. It is the
   riskiest (tooth contacts would quantize) and has the best versioning machinery.
4. NIMBUS, TESSERA and REVEL gain little from a control rate. Their costs are resonator
   banks and HOLD prerolls, which this document is not allowed to touch (resonators stay
   at 176.4 kHz).

**Recommendation: build CIRCUIT first**, as a new CIRCUIT model 2 whose only audio
change is block-ramped observer gains and block-rate breath envelopes. Section 9 has the
ranking.

## 2. Ground rules this spec works inside

- Resonators stay at the 4x internal rate, `Dsp.RATE * Dsp.OVERSAMPLE` = 176,400 Hz.
  Nothing here changes a resonator's rate, tuning or coupling law.
- The render budget rule: a knob turn re-renders the patch after a 100 ms debounce;
  one-shots should render in tens of ms (`docs/SYNTH_ROADMAP.md`, "The instant loop").
  None of these engines will meet that from this change alone. The target is a measured
  drop, not compliance.
- Published sound does not change. A change that alters output bytes ships as a new model
  version. Saved recipes without a model field keep rendering the old model, which is the
  PITCHWHEEL and FILAMENT convention (`PitchwheelPatch.kt:7-12`, `:55`;
  `FilamentPatch.kt:58-59`).
- A change that keeps output bytes identical needs no model version. It needs a byte
  comparison against the old code to prove it.

## 3. Measurements taken for this spec

`./gradlew :synth:scanRenderTimes -Pengines=UNDERTOW,CIRCUIT -Pjfr=...` on the cloud JVM,
2026-11-02, with `-Pkotlin.compiler.execution.strategy=in-process
-Dorg.gradle.jvmargs=-Xmx4g`. Other agents and test jobs were running on the same machine,
so treat every number as plus or minus 15 percent or worse. The scan renders each voice's
first factory preset; none of those is a HOLD preset.

| Voice | Median knob-turn render | Clip | Cost per second of audio |
|---|---|---|---|
| CIRCUIT ROOT | 1,763 ms | 5.31 s | 332 ms/s |
| CIRCUIT PROCESSION | 1,818 ms | 5.40 s | 336 ms/s |
| CIRCUIT ANSWER | 1,973 ms | 5.58 s | 353 ms/s |
| CIRCUIT VOICED | 1,648 ms | 5.36 s | 308 ms/s |
| CIRCUIT EXPANSE | 1,682 ms | 5.77 s | 292 ms/s |
| CIRCUIT CONFLUENCE | 1,924 ms | 5.54 s | 347 ms/s |
| UNDERTOW KNOCK | 367 ms | 2.44 s | 150 ms/s |
| UNDERTOW BREATH | 262 ms | 3.35 s | 78 ms/s |
| UNDERTOW FLUTTER | 926 ms | 3.65 s | 254 ms/s |
| UNDERTOW SEAL | 558 ms | 4.15 s | 134 ms/s |
| UNDERTOW HOLLOW | 419 ms | 3.29 s | 127 ms/s |
| UNDERTOW SURGE | 1,028 ms | 4.43 s | 232 ms/s |

Profile (JFR execution samples, attributed to the innermost `com.snipsnap` frame and line;
the JIT inlines aggressively, so a line is "the code around this line", not one
instruction):

| Engine | Samples | Line | Share | What is there |
|---|---|---|---|---|
| CIRCUIT | 4,049 | `Circuit.kt:574` | 45.4% | observer path gain, a division per path per sample |
| | | `Circuit.kt:570` | 26.9% | observer path loop: interpolated `read`, one-pole |
| | | `Circuit.kt:322` | 14.7% | tube lip `tanh` |
| | | `CircuitInstruments.modes:131` | 2.6% | gesture instruments |
| | | `Circuit.kt:480`, `:453`, `CircuitPhrase.profile:140` | 3.7% together | tube loop envelopes, accent scan, breath effort |
| | | `Circuit.kt:551` | 1.1% | observer outer loop |
| UNDERTOW | 1,388 | `Undertow.kt:387` | 76.5% | the body loop line; the JIT folds the whole per-sample network here |
| | | `Undertow.kt:332` | 16.0% | the per-chamber loop |
| | | `Undertow.kt:360` | 3.2% | flexure `Mode.step` (FLUTTER only) |
| | | `control()`, all lines | under 0.5% | the 1 kHz gross mechanics |

Two things this changes:

- The scan doc's "`observe`, the tube simulation loop (77%)" is right about the function
  and wrong about the loop. `observe` has two loops. About 72% of CIRCUIT's time is the
  second one, the observer that carries seven source buffers to the listener
  (`Circuit.kt:538-582`). The tube loop is about 20%, most of it `tanh`.
- UNDERTOW's control clock costs under half a percent. There is no slow mechanics left at
  audio rate to speak of. The 96% is the acoustic network itself.

Also measured, not explained: UNDERTOW's cost per second of audio varies threefold between
voices (BREATH 78 ms/s, FLUTTER 254 ms/s) although every voice steps the same 15 modes
(FLUTTER 19). The four flexure modes do not explain a factor of three. A denormal slow path
is one candidate (FILAMENT's lesson; `pulseTarget` and `contactPulse` decay geometrically
at `Undertow.kt:334-335` with no floor). Section 5.6 makes the ten-minute test step one.

## 4. CIRCUIT

### 4.1 What moves slowly and what must stay at audio rate

The tube loop (`Circuit.kt:437-490`), per internal sample:

| Quantity | Line | Moves | Verdict |
|---|---|---|---|
| `release`, `envelope` (attack `smooth(t/.18)`, end `smooth(.../.55)`) | 443-448 | 180 ms and 550 ms ramps | control rate |
| `phrase.effort(t)`: `require`, a division, `floor`, two `sin`, the profile search | 449, `CircuitPhrase.kt:71-80` | a breath arch over cells of about 0.7 s | control rate |
| `CircuitCeremony.gain(t)` | 451 | a piecewise smooth curve over a minute | control rate |
| `pressure` | 450 | product of the above | control rate, ramped |
| `accent`: up to 8 active accents, each `smooth(age/.050) * exp(-age/.14) * smooth(...)` | 456-471 | 50 ms rise, 140 ms decay | control rate, ramped |
| `gesture` per role | 473-477 | follows `accent` and `effort` | control rate, ramped |
| `local` coupling from `prior` radiation | 478 | audio | stays per sample |
| `returning` echo `read(sources[role], i - returnDelay)` | 479 | audio | stays per sample |
| `Tube.tick`: lip phase rotation, `tanh`, four modes | 307-332 | audio, nonlinear | stays per sample |

The observer loop (`Circuit.kt:538-582`), per source, per internal sample:

| Quantity | Line | Moves | Verdict |
|---|---|---|---|
| future path lengths, `facing`, filter `coefficients` | 555-563 | already every 64 samples (`CONTROL_BLOCK`) | already control rate |
| `delays[path]`, `distances[path]`, `facing` | 576-580 | already ramped linearly inside the block | unchanged |
| `gain` = `(.70 + .30*facing) / (1 + .18*d)` or `(.035 + .43*canyon) / (1 + .035*d)` | 573-574 | a smooth function of `d` and `facing`, which only ramp | **control rate, ramped** |
| `read(...)`, `low[path]` one-pole, `sample` sum | 571-575 | audio | stays per sample |

The gain is the clearest case in the whole roster: its inputs are themselves linear ramps
inside the block, so the per-sample division buys a curvature correction on a hyperbola
over 64 samples (0.36 ms), during which a walker at the fastest ORBIT moves well under a
millimetre.

### 4.2 The proposed split

Control block: keep `CONTROL_BLOCK = 64` (2,756 Hz). It already exists, the geometry
already lands on it, and HOLD cycle lengths already have to respect it.

**Observer (the main win).** At each block boundary, where the code already computes
`future`, compute the gain at the block start and at the block end for each path, and ramp
it linearly, exactly as `delays` and `distances` ramp now:

```
gainNow[path]  = g(distances[path], facing)           // at block start
gainEnd[path]  = g(future[path], facingAtBlockEnd)     // at block end, from values already computed
gainStep[path] = (gainEnd[path] - gainNow[path]) / CONTROL_BLOCK
// per sample: sample += low[path] * gainNow[path]; gainNow[path] += gainStep[path]
```

Divisions per internal sample in the observer fall from 21 to 0. Divisions per block rise by
42, spread over 64 samples.

**Tube loop (a small win, done in the same model).** Evaluate `pressure` and the three role
`gesture` values at block boundaries, and ramp linearly between them. Keep `prior`, `local`,
`returning` and `Tube.tick` per sample. This removes, per sample, the effort evaluation (two
`sin`, a `floor`, a search), two `smooth` calls with divisions, and up to eight `exp` calls
in the accent scan.

The accent onset needs care. An accent is a scheduled event at a precise time, and its rise
is `smooth(age / .050)`, 50 ms. A linear ramp across a 0.36 ms block that straddles the onset
starts the rise up to 0.36 ms early with a value of order `(0.36/50)^2`, about 5e-5 of full
scale. That is far below anything a listener can place in time. If the owner hears a softened
attack, the fallback is to split the block at each accent onset (the accent times are known
before the loop starts), which keeps onsets sample-exact at the cost of a few extra block
evaluations per accent.

**Not proposed.** Running the observer at the 44.1 kHz output rate after band-limiting each
source would cut the hottest loop by four. It changes the fractional-delay interpolation's
high-frequency loss and the Doppler character of moving walkers, it is a larger model change
than a gain ramp, and it moves a resonator-adjacent stage off the 4x clock. It is the next
step only if model 2 is heard as identical and is still too slow.

### 4.3 Interpolation and smoothing at the boundary

- Linear ramps, not held steps, for every block-rate value that multiplies audio. A held
  gain would step every 0.36 ms (a 2,756 Hz zipper with sidebands around every partial).
  A linear ramp has no step, only a slope change at block edges; its error against the
  exact per-sample curve is second order in the block length.
- For HOLD the block grid must close on the loop. `Plan.frames` comes from whole pitch cycles
  (`Circuit.kt:107-108`), so `n = frames * 4` is a multiple of 4 but not always of 64. The
  observer loop already runs its 64-sample grid on `i`, which wraps through `at = i % n`. Use
  the NIMBUS pattern (`Nimbus.kt:825-831`): measure the block from the start of each cycle
  and shorten the last block to `n - within`, so the grid restarts on every cycle and the held
  seam stays exact.
- Ramps are computed from the start and end values of the block, never by accumulating a
  slope across blocks, so rounding cannot drift across a 16-second held cycle.

### 4.4 What could audibly change, and why

| Risk | Mechanism | Expected size | How it is caught |
|---|---|---|---|
| Zipper noise | block-rate gain or pressure with no interpolation | none with ramps; a 2.76 kHz sideband family without them | spectral difference between model 1 and model 2, section 4.7 |
| Softened accent onsets | the ramp spans an accent start | about 5e-5 of full scale, 0.36 ms | onset-time test on tube accents; owner A/B; fallback is splitting blocks at onsets |
| Tube timbre drift | the lip `tanh` is nonlinear, so a tiny pressure difference yields a tiny harmonic difference | a residual far below the signal, not a new partial | model-1 vs model-2 residual RMS in dB |
| Chaos divergence | the tubes are coupled through `prior` and through the returning echo, a feedback network that could amplify small differences | small: the tubes are forced at a fixed lip phase (`Circuit.kt:308-310`); "loading changes stiffness/partials, never the phase clock", so phase cannot wander | residual must not grow over the clip: compare residual RMS in the first and last second |
| Reply schedule change | replies are planned before rendering by `events(...)`, from geometry, not from audio | none: `events` runs before `observe` and is untouched | event lists identical between models (assert equality) |
| HOLD seam | block grid not closing on the loop | a click at the wrap | the existing seam and convergence check (`Circuit.kt:403`) |

### 4.5 Expected gain (estimate, not measured)

The divisions in the observer sit on the line holding 45% of CIRCUIT's samples. Removing them
should cut the observer's cost by roughly a third to a half. Block-rate envelopes remove most
of the tube loop's non-`tanh` work, about 3 to 4 percent of the total. **Estimate: finite
CIRCUIT renders go from about 1.65 to 1.97 s to about 1.1 to 1.35 s, a 25 to 40 percent
cut.** Held renders scale the same way; the doc's EXPANSE HOLD fixture took 11.2 s on a
desktop (`docs/CIRCUIT.md`, completed checks), so it would land near 7 to 8 s. That is still
far from the instant loop; this is a first step, not a fix.

The shared `FastMath.tanh` landed in the commit this spec was written against
(`FastMath.kt:18`). Using it in `Tube.tick` is a separate byte change (scan item 3) with its
own A/B. If both ship, they should ship as the same model 2, so the owner judges one change.

### 4.6 Frozen models and versioning

CIRCUIT has **no model version today**. `CircuitPatch` carries name, voice and macros only
(`CircuitPatch.kt:7-11`). There are no committed CIRCUIT WAVs or SHA-256 fixtures; the
determinism checks compare a render with a second render
(`DeterminismTest.kt:296-303`, `CircuitTest` "fresh renders reproduce audio paths and event
order exactly"), so they would pass after a sound change and protect nothing. What is
published:

- the approved one-minute ROOT ceremony ("The owner heard the minute and replied, 'Sounds
  good.'", `docs/CIRCUIT.md:11-13`), rendered by `CircuitCeremonyGenerator`;
- 15 factory presets and the 16-pad kit (`CircuitPresets.kt`, `SynthKits.kt:872`);
- whatever pads users saved, which store macros and re-render on load.

Plan:

1. **Freeze first, in its own commit, before any DSP change.** Add a
   `CircuitFrozenModelTest` in the `FilamentCharacterTest.kt:71-95` pattern: SHA-256 of the
   float PCM (or PCM24 bytes) for each voice's default finite render, the two HOLD presets
   EMBER PULSE and HELD CIRCLE, and the ceremony (`Circuit.inspectCeremony()`). It must pass
   on today's code. That commit is the evidence that model 1 is what was published.
2. Add `model: Int = Circuit.MODEL_VERSION` to `CircuitPatch` with
   `ORIGINAL_MODEL = 1`, `MODEL_VERSION = 2`; JSON without `model` decodes as 1; JSON with an
   unknown model throws (`PitchwheelPatch.kt:55-57`); `toJsonValue` writes it.
3. Thread `model` into `Circuit.render` and `observe` as a parameter. Model 1 runs today's
   per-sample code exactly. Model 2 takes the ramped branch. Do not fork the file
   (`PitchwheelV1.kt` is a fork because its mechanics changed wholesale; here two branches in
   one function are smaller and keep the shared code shared).
4. The ceremony is pinned to model 1. It is an approved artifact.
5. Arms that must pass the model: `Velocity.kt:416` (`touchedRender`), `SynthKits.kt:872`,
   `CircuitPresets.kt:11`. The app arms at `SynthScreen.kt:5655` and `:5816` render the
   current model by default, which is the intent for new work; a saved pad's model must
   survive editing (`withMacros` uses `copy`, which keeps it). The app arms are left to a
   session with an Android SDK, per the house rule.
6. Factory presets and the kit move to model 2 only after the owner's A/B. Until then they
   stay explicitly on model 1, so the kit does not change under the owner.

### 4.7 Acceptance plan

Measured before and after:

- `./gradlew :synth:scanRenderTimes -Pengines=CIRCUIT` on the same machine, same session,
  model 1 and model 2 back to back, three runs each; report medians and the spread. A JFR
  profile of model 2 (`-Pjfr=`) to show the division line is gone.
- The EXPANSE HOLD fixture timing from `docs/CIRCUIT.md`, before and after.

Tests (all must pass, judged by Gradle's exit code):

- `CircuitFrozenModelTest`: model 1 byte-identical to the fixtures, including the ceremony.
- A new model-2 closeness test: for each voice default and both HOLD presets, the residual
  `model2 - model1` is at least 60 dB below the model-1 RMS over the whole clip (a proposed
  threshold; set it from the first measurement and record it), and its RMS in the last second
  is no larger than in the first (no divergence).
- No new spectral line: the model-2 minus model-1 spectrum has no peak near 2,756 Hz or its
  sidebands above the residual floor.
- Event lists identical between models (`inspect(...).events`).
- The existing `CircuitTest`, `CircuitIntegrationTest`, `CircuitRootScoreTest`,
  `CircuitCeremonyTest` and the HOLD convergence tests pass on model 2.
- A model JSON round trip: no field means 1; 2 survives; 3 throws.

Owner listen: an A/B page from `CircuitAuditionGenerator` with matched-level model-1 and
model-2 pairs for each voice default, both HOLD presets, and one ORBIT-fast case. The page
says which is which only after the listener picks. If the owner hears a difference, the spec
fails as written, and the next step is the onset-split fallback, then a re-listen.

## 5. UNDERTOW

### 5.1 What moves slowly and what must stay at audio rate

The gross mechanics already run on a 1 kHz clock: `control()` (`Undertow.kt:241-322`) updates
the reservoir, local pressures, flap motion, rim and seal contacts, apertures and flows every
`controlFrames` = 176 internal samples (`:326`, `:431-432`). The class KDoc says so
(`:23-24`) and `docs/UNDERTOW.md:115` does too. A test already proves the clock is converged:
"doubling the mechanical clock preserves pressure catch timing and pitch"
(`UndertowTest.kt:273`), with catch timing within 3 ms and reservoir pressure within 5%.

Inside `step()` (`Undertow.kt:324-399`), per internal sample:

| Work | Line | Moves | Verdict |
|---|---|---|---|
| noise draw and `noiseLow` | 327-328 | audio | stays |
| `contactPulse`, `pulseTarget` | 334-335 | a ~1 ms force pulse | stays (it is the click) |
| 2 rim `Mode.step` per chamber, 8 total | 337-342 | audio | stays |
| `audibleFlow` one-pole of the 1 kHz flow | 345 | smooths a 1 kHz step, 0.7 ms | stays (it is the de-zipper) |
| tone `Mode.step` with flow feedback, 4 total | 349-352 | audio, nonlinear | stays |
| **flexure `Mode.step`, 4 total, FLUTTER only** | 357-361 | **12 to 26 Hz modes; `flexure[j].re` is read only in `control()` (`:308`)** | **control rate** |
| `engagement`, `amount` per chamber | 364, 368 | change only when `extraction` changes, which is in `control()` | hoist to `control()` (exact) |
| shell exchanges | 369-385 | audio | stays |
| 3 body `Mode.step` | 387-390 | audio | stays |
| three DC blockers | 394-396 | audio | stays |

### 5.2 Byte-identical preliminaries (no model version)

These are not control-rate changes, but they are the cheapest UNDERTOW work and they do not
change a byte. Each must be proved by comparing full renders against the old code.

1. **`Mode.step` fast path for zero feedback.** Rim and body modes call `step()` with the
   default `feedback = 0.0` (`:340`, `:388`): 11 of 15 calls per sample. Today each computes
   `radius` and `feedback / INTERNAL_RATE / (1 + 100 * radius)`, a division, then
   `attenuation * (1 + 0.0)`. With `feedback == 0.0` that quotient is exactly `0.0` for every
   finite or infinite `radius` (state is reset to finite at the end of every step, `:164`),
   so `gain` is exactly `attenuation`. A separate `stepFree()` that uses `attenuation`
   directly computes the same doubles. Saves 11 divisions and 11 `radius` products per sample.
2. **Hoist `engagement` and `amount`.** Both depend on `extraction`, `held`, `spiral`,
   `sh.mounting`, `voice` and `j`, all constant between `control()` calls. Computing them in
   `control()` into a four-element array, with the same expression in the same order, gives
   the same doubles.

Estimate: 10 to 25 percent off every UNDERTOW voice. Not measured.

### 5.3 The proposed split (model 2)

Move the four flexure modes to the 1 kHz control clock. `flexOpening` reads only
`flexure[j].re`, and only in `control()` (`:308`), so the audio path sees the flexure through
the aperture, which is already a 1 kHz quantity. Per block:

- integrate the per-sample drive over the block: the sum of
  `noiseLow * flow * .000015 + impulse * .005` across the block's samples (`:359`), applied
  once as a velocity increment at the block start;
- rotate with coefficients for the control rate: a `Mode` built for `controlFrames /
  INTERNAL_RATE` steps, with the same `hz` and `loss`;
- apply the powered feedback `480 * flow * (.4 + flap)` with `dt = controlFrames /
  INTERNAL_RATE` in the radial law in place of `1 / INTERNAL_RATE`.

A 26 Hz mode at 1 kHz has 38 steps per period; a rotation is exact for a linear mode at any
step, so only the injected noise and the saturating feedback see the coarser clock.

### 5.4 What could audibly change, and why

- **Flutter rate and depth.** The flexure mode is a powered oscillator whose amplitude is
  set by the saturating feedback (`Mode.step` radial law, `:160`). Updating it 176 times
  less often changes how fast it saturates. The flutter rate is fixed by `hz`, so it should
  not move; the depth might. Measured by `UndertowIdentityTest`'s "flutter repeatedly
  throttles the shared flow instead of staying open" and its sideband share.
- **Texture.** The flexure's noise input is summed across each block, a 1 kHz low-pass on
  noise that drives a 26 Hz resonator; the resonator already rejects everything above a few
  tens of Hz, so the loss should be negligible. Not measured.
- **Contact timing.** Unchanged in kind: contacts are already detected on the 1 kHz clock.
  The aperture trajectory changes slightly, so catch times may shift by a fraction of a
  millisecond. The existing 1k/2k test bounds this at 3 ms.
- **Chaos divergence.** UNDERTOW is a feedback network (flow feeds the reed, the shell
  loads the flaps). A small aperture change can move the next contact by a control step,
  and a moved contact moves everything after it. The residual between models may therefore
  grow over the clip even if each part is close. That is not an error in itself; it means a
  sample-wise residual is the wrong acceptance metric for FLUTTER, and the comparison must be
  statistical (contact count, catch times within a tolerance, sideband share, spectral
  envelope) plus the owner's ear.

### 5.5 Expected gain (estimate, not measured)

Four of nineteen `Mode.step` calls in FLUTTER, each with a division (the flexure always has
nonzero feedback). Roughly 15 to 20 percent of FLUTTER, nothing for the other five voices.
Combined with section 5.2, FLUTTER might reach about 600 ms from 926 ms. If the unexplained
threefold spread in section 3 turns out to be denormals, a state floor could matter more
than everything above.

### 5.6 Frozen models and versioning

UNDERTOW has **no model version** (`UndertowPatch.kt:11-15`), no committed WAV or hash
fixtures, and its presets "await the owner's audition" (`UndertowPresets.kt:6`,
`docs/UNDERTOW.md:203-205`). Order of work:

1. **Denormal test** (ten minutes, FILAMENT's method): floor `pulseTarget`, `contactPulse`,
   `noiseLow`, `audibleFlow` and the DC blocker states at 1e-100, re-run the scan for
   FLUTTER and BREATH, then revert. It changes bytes, so a kept floor needs model 2.
2. Sections 5.2's two changes, with a byte-comparison test against SHA-256 fixtures captured
   from today's code in a commit before the change (the same freezing step as CIRCUIT 4.6.1).
   No model version needed if the hashes hold.
3. Only if 1 or the flexure move is kept: `model` on `UndertowPatch` (omitted means 1), as
   in CIRCUIT 4.6.2-5, with arms at `Velocity.kt:467`, `SynthKits.kt:1583`,
   `UndertowPresets.kt:11`, and the app arms `SynthScreen.kt:5661` and `:5822` left for an
   SDK session.

### 5.7 Acceptance plan

- `./gradlew :synth:scanRenderTimes -Pengines=UNDERTOW`, before and after each step, with a
  JFR profile.
- Section 5.2: every voice's default finite render and the HELD SUCTION preset byte-identical
  to the frozen hashes. If one byte differs, the change is wrong, not "close enough".
- Flexure move: `UndertowTest` and `UndertowIdentityTest` pass on model 2, including the 1k/2k
  clock test and the HOLD convergence tests at 500 and 1,500 Hz (`UndertowTest.kt:346`);
  FLUTTER's contact count within one and first catch within 3 ms of model 1; sideband share
  within 10 percent of model 1 (proposed threshold).
- Owner A/B of FLUTTER model 1 against model 2 at three notes, matched level, on the
  UNDERTOW audition page.

## 6. PITCHWHEEL (brief)

The one engine whose mechanics truly run at audio rate: `tick()` calls `mechanics()` every
`stride = INTERNAL_RATE / solverRate` samples (`Pitchwheel.kt:315`, `:493`), and
`solverRate` defaults to `INTERNAL_RATE` (`:559`). `renderProbe` already accepts
`INTERNAL_RATE / 2` (`:564`), and `PitchwheelTest.kt:574` ("mechanical solver changes
preserve encounters recoil and thermal trajectories") compares 2x with 4x: event counts within
one, same tooth order and direction, encounter times within 15 ms, final angle and temperature
close.

- Slow: wheel angle and speed, temperature, resin attachment, finger and filament anchors.
- Must stay fast: tooth crossings. `crossing(...)` (`:344-352`) finds the exact crossing angle,
  but the excitation it triggers (`wood.excite`) lands on the solver step. At a 44.1 kHz solver
  a contact can land up to 23 microseconds late. Tooth contacts repeat at the pitch, so that
  jitter is pitch-period jitter, heard as roughness or noise. A control-rate solver therefore
  needs sub-step event placement: compute the fraction of the step at which the crossing
  happened and deliver the excitation to `Wood` at that fractional time.
- Gain: the solver family (`mechanics`, `geometry`, the energy sums) is about 44% of
  PITCHWHEEL's profile (section 10). A half-rate solver would save roughly 20%, a quarter-rate
  one roughly 30%, both estimates. Versioning is ready
  (`MODEL_VERSION = 3`, V1 and V2 frozen, `PitchwheelPatch.kt:33-38`): this would be model 4.
- Another session was working on a PITCHWHEEL denormal floor (`pending[j]` in `Wood.tick`,
  `Pitchwheel.kt:262`) while this spec was written. Measure after that lands.

## 7. NIMBUS (brief)

The suspension, field controller and modal loading already run every 128 samples
(`DEFAULT_CONTROL_STRIDE`, `Nimbus.kt:44`; `control()` at `:656-677`), and the HOLD grid is
already aligned to the cycle (`:825-831`). Per sample it rotates 96 plate modes
(`Bank.step`, `:297-304`), runs the chamber, scatters neighbor and body waves with 16-wide dot
products (`scatter`, `:691-695`), and resolves rim contacts (`contacts()`, `:781-811`), which
must stay at 4x.

- The existing control clock is itself expensive: `control()` is 12.8% of NIMBUS's profile
  (section 10). Each call runs `depleteModal` once per cymbal, which sums all 96 modal
  energies and then each plate's again, and `load()` (`:596-638`), which retunes all 96
  modes with `exp`, `cos` and `sin` even when the suspension has not moved. Making the slow
  path cheaper (one energy sum per call, retune only plates whose `z` moved) is the
  control-rate work here. Estimate 5 to 10 percent. Byte changes are likely (summation
  order), so it needs a model version.
- The SUSPEND voice's first preset is HOLD (HELD METAL), which runs 6 or 18 preroll cycles
  before the captured pair (`:238-242`). That is a preroll budget question, not a
  control-rate one: fewer cycles, or caching the converged state keyed by the macros, is the
  render-budget rule's precompute-and-cache story.
- The real NIMBUS lever would be running the linear plate banks below 4x, which this spec
  rules out. NIMBUS has no model version.

## 8. TESSERA and REVEL (brief)

**TESSERA.** Wall geometry is already at a 64-sample control rate (`Tessera.kt:49`, `:501`).
The every-sample energy sum was moved to every 64th sample on 2026-11-02 and gave nothing
(scan item 4). What remains per sample and moves slowly: `gestureEnvelope` (a 75 ms one-pole
fed by a 43-mode energy sum, `:499-500`), the six path `stretch` factors (computed from `size`
and `movingFold`, which change only in `updateGeometry`, `:505`), and the collector detectors
(`envelopes`, `baseline`, arming and release decisions, `:542-564`). Item 4 says the profiler
spread inlined work across these lines; expect a small gain at most. The collector release
decision is contact planning; moving it to the 64-sample grid changes release times by up to
0.36 ms, which then reshapes a whole answer. Low priority.

**REVEL.** Moving microphones are already on a 64-sample grid with ramped gain, delay and
filter coefficients (`Revel.kt:681-708`): the pattern CIRCUIT model 2 copies. Per sample it
rotates head and floor modes with per-mode `passiveLoss` bookkeeping (`:543-549`), sums energy
for a `check` (`:555-572`), smooths `pressure` with an `exp` per sample (`:540-542`), and reads
moving taps. The `pressureTarget` exponential and the `exp(-65 * DT)` coefficient are
control-rate candidates; the loss and energy bookkeeping is TESSERA's case again. Small gain,
low risk, not worth a model version on its own.

## 9. Ranking by gain per risk

| Rank | Change | Gain (estimate) | Risk | Version |
|---|---|---|---|---|
| 1 | CIRCUIT observer gain ramps and block-rate breath envelopes | 25 to 40% of every CIRCUIT render, including 11 s HOLD fixtures | low: linear gain ramps on already-ramped inputs; no contacts; tube phase fixed | model 2 |
| 2 | UNDERTOW zero-feedback fast path and hoists | 10 to 25% of every UNDERTOW voice | none if the byte comparison holds | none |
| 3 | UNDERTOW denormal test | unknown; possibly the explanation for FLUTTER and SURGE | low | model 2 if kept |
| 4 | PITCHWHEEL solver at half or quarter rate with sub-step contact placement | 20 to 30% (solver family is 44% of the profile) | high: contact jitter at pitch rate | model 4 |
| 5 | UNDERTOW flexure at 1 kHz | 15 to 20% of FLUTTER only | medium: powered oscillator depth | model 2 |
| 6 | NIMBUS cheaper `control()` (one energy sum, retune only moved plates); HOLD preroll cache | 5 to 10% for finite voices; the preroll is SUSPEND's real cost | low to medium | model 2 |
| 7 | TESSERA, REVEL envelope and detector work | small | low to medium | new model each |

Rank 2 is cheaper than rank 1 but is not a control-rate change and saves less absolute time
(UNDERTOW's voices are 0.26 to 1.03 s; CIRCUIT's are 1.65 to 1.97 s). It is a good
companion task for the same session.

**Build CIRCUIT model 2 first.** It is the slowest engine of the six by median voice, the
profile points at one line, the change is a linear ramp of a gain whose inputs are already
ramped, it has no contacts or chaos to disturb, and it forces the freeze-then-version step
that CIRCUIT needs anyway: today nothing would notice if CIRCUIT's published sound changed.

## 10. Profile of the lighter engines

Second scan, same machine and session, `-Pengines=PITCHWHEEL,NIMBUS,REVEL,TESSERA` with
`-Pjfr`. Same noise caveats as section 3.

| Engine | Voices, median knob-turn render | Cost per second of audio |
|---|---|---|
| NIMBUS | 1,041 to 1,346 ms over 5.8 to 6.1 s; SUSPEND (HOLD preset) 1,829 ms for 1.35 s | 181 to 223 ms/s; SUSPEND 1,356 ms/s |
| PITCHWHEEL | 595 to 1,201 ms over 4.7 to 8.0 s | 98 to 159 ms/s |
| TESSERA | 1,034 to 1,114 ms over 6.0 s | 172 to 186 ms/s |
| REVEL | 529 to 992 ms over 4.96 s | 107 to 200 ms/s |

Profile by method (share of each engine's samples):

| Engine | Samples | Method | Share | Reading |
|---|---|---|---|---|
| PITCHWHEEL | 2,170 | `Wood.tick` | 40.3% | the resonator; stays |
| | | `Engine.geometry` + `Engine.mechanics` + energy sums | about 44% | **the solver, every internal sample: the control-rate candidate** |
| | | `Dsp.TptSvf.lowPassInPlace` | 6.9% | output band-limit |
| NIMBUS | 2,863 | `Bank.velocity` + `Bank.addVelocity` + `Bank.displacement` | 48.2% | wave scattering and rim contacts, per sample; stays |
| | | `Simulation.run` | 25.6% | the per-sample loop body, bank rotation inlined |
| | | `Simulation.control` | 12.8% | the existing 128-sample slow path |
| TESSERA | 2,566 | `Engine.step` | 64.1% | modes, pulses, detectors |
| | | `Engine.passiveEnergy` | 16.0% | the every-sample guard item 4 tried to thin |
| | | `Path.read` | 14.4% | chamber paths |
| REVEL | 1,589 | `perform` + `Dsp.tap` | 66.2% | the moving microphones: two taps and two one-poles per head per microphone per sample |
| | | `Ensemble.tick` | 25.9% | head and floor modes |

REVEL's microphone stage is already in the shape CIRCUIT model 2 proposes (gain, delay and
cutoff ramped across 64-sample blocks, no per-sample division), and it costs 107 to 200 ms per
second of audio against CIRCUIT's 292 to 353. That comparison is the best evidence available
that the CIRCUIT change is worth doing; it is not a measurement of CIRCUIT model 2.

## 11. Why it is built this way

- **Ramp, never step.** A value updated every 64 samples and held would put a buzz at 2.76 kHz
  on everything it touches. A straight line between two correct values has no buzz, and the
  error from straightening a 0.36 ms piece of a smooth curve is far too small to hear.
  CIRCUIT already does this for walker delays and distances; model 2 only does the same for
  the walker's loudness.
- **Leave the resonators alone.** Every engine here gets its character from resonators and
  nonlinear contacts at 4x. Slowing those changes the instrument. Slowing the things that
  steer them (how hard the player blows, how far away the walker is) changes only the bill.
- **Freeze before you touch.** CIRCUIT and UNDERTOW have no stored fingerprints of their
  published sound. The first commit writes those fingerprints down from today's code, so the
  second commit can prove the old sound is still there for every saved pad and the approved
  ceremony.
- **The risky decision is the accent onset.** It is the one place a block-rate value meets a
  scheduled event. The estimate says it moves the start of a 50 ms swell by under half a
  millisecond at a level of 5e-5. If the owner hears the attacks soften, split the blocks at
  accent times.
- **A residual is the wrong test for a chaotic engine.** CIRCUIT's tubes are phase-locked, so
  comparing samples works. UNDERTOW's flaps and contacts can move a whole event by a control
  step, so its test compares events and spectra, not samples.

## 12. Ledger

| Claim | Status | Evidence |
|---|---|---|
| UNDERTOW runs its gross mechanics at 1 kHz | verified | `Undertow.kt:23-24`, `:135`, `:241`, `:326`; `UndertowTest.kt:273` |
| UNDERTOW's 1 kHz `control()` is under 0.5% of its time | measured | JFR, scan of 2026-11-02, 1,388 samples |
| CIRCUIT's observer is about 72% of its time; the tube `tanh` about 15% | measured | JFR, scan of 2026-11-02, 4,049 samples; line attribution is approximate under inlining |
| CIRCUIT finite renders take 1.65 to 1.97 s; UNDERTOW 0.26 to 1.03 s | measured | `scanRenderTimes`, noisy shared machine |
| UNDERTOW's per-second cost varies threefold between voices | measured; cause not known | same scan |
| NIMBUS, TESSERA, REVEL already have control clocks | verified | `Nimbus.kt:44`, `Tessera.kt:49`, `Revel.kt:62` |
| PITCHWHEEL's solver family is about 44% of its time; NIMBUS's `control()` 12.8% | measured | JFR, second scan of 2026-11-02 |
| PITCHWHEEL's mechanics run every internal sample by default | verified | `Pitchwheel.kt:315`, `:493`, `:559` |
| CIRCUIT and UNDERTOW have no model version and no byte fixtures | verified | `CircuitPatch.kt`, `UndertowPatch.kt`; `git ls-files` shows no CIRCUIT or UNDERTOW WAVs |
| `Mode.step` with zero feedback can skip the division with identical bytes | proposed, argued | section 5.2; needs a byte-comparison test |
| CIRCUIT model 2 saves 25 to 40% | proposed | estimate from the profile, not measured |
| UNDERTOW fast path saves 10 to 25% | proposed | estimate, not measured |
| Model 2 sounds the same as model 1 | not heard | only the owner hears; section 4.7 A/B |
| Any of this sounds good | not heard | nothing was rendered for listening |
