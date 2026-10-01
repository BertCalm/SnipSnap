# GLINT DEPTH, and the factory roster

Design, 2026-10-01. Agreed in conversation one section at a time (the law by ear, the rest by a plain yes); this file is the written form, for review before any plan or code.

It does the two things `2026-09-29-glint-paths-design.md` §6 left out: **DEPTH**, "a sine endpoint for softer pads", and **the factory presets**, authored by ear after the audition and never blind. DEPTH ships first, as its own PR, so the presets can use it. The roster is a second PR.

## 1. What was decided by listening

DEPTH is a DSP idea, so under the standing rule (Josh auditions, Claude builds; never design DSP nobody has listened to) it was rendered and heard before it was specified. Two throwaway probes (one test class, never committed, deleted before the build) rendered held-pad clips of a SWEEP pad and a VOWEL pad (f0 220 Hz, BLOOM 0.85 so the pad breathes) through a mechanism with two ingredients, both of which keep the output exactly zero at every wrap:

1. **Edge rounding** replaces the saw window with a smooth one, so the slope jump at the wrap, the buzz, goes while the formant stays.
2. **A plain sine at f0** (also zero at the wrap) is faded in while the burst fades out. At full strength the output is that sine alone.

**Round 1** (14 clips, DEPTH law `e = min(1, 2·DEPTH)`, sine share `s = max(0, 2·DEPTH − 1)`, `out = (1 − s)·bursts + s·0.45·sine`, with 0.45 a guess). Josh's verdicts: DEPTH 0.5 (edge fully rounded, no sine) is not underwater; 0.75 and 1.0 are, on both voices. The two DEPTH 1.0 clips are byte-identical (md5 `7e46aa15465ef3f1ba22a5d0325387ea`): at full DEPTH the voice stops mattering.

**Why, measured** (RMS of the decimated 6 s renders; `rB` is the e = 1 burst pair's RMS, `rS` the unit sine's): SWEEP `rB` 0.139636, VOWEL 0.134822, `rS` 0.706908. Round 1's sine share of the power:

| DEPTH | 0.6 | 0.7 | 0.75 | 0.8 | 0.9 |
|---|---|---|---|---|---|
| SWEEP | 24.5% | 69.8% | 83.8% | 92.1% | 98.8% |
| VOWEL | 25.8% | 71.2% | 84.8% | 92.6% | 98.9% |

So the guessed gain made the sine take over between 0.6 and 0.75, and "halfway" on the knob was mostly sine.

**Round 2** (6 clips): the sine scaled to the burst's RMS (both at unit RMS, equal-power weights) and brought in late, as `u = 2·DEPTH − 1` (sine share `u²`: 25% at 0.75, 64% at 0.9). Josh's verdict: underwater only at the very end. **This is the law in §2.**

Two rulings, mine, Josh may overrule either:
- The bare sine at DEPTH 1 stays. The standing range rule is that a macro's extremes are extreme, including the ugly end. It is the last notch only.
- Round 1's "both at once" glide law (`e = s = DEPTH`) is dropped: it is half sine at DEPTH 0.5, which round 1 called underwater.

What the probe numbers are not. Round 1's spectral centroid and share-above-2-kHz readouts were taken at t = 2.5 s, the breath's trough. On SWEEP the share above 2 kHz swings with the breath (about 0.01% at the trough, 67 to 84% at the crest, where the formant itself sits above 2 kHz), so it is not a buzz measure and nothing here leans on it. The power shares above do not depend on breath phase. Clips were levelled flat (the repo's `AuditionLevel`), so a low sine is quieter to the ear than a voice near 1 kHz at the same level, and a phone speaker barely plays 220 Hz. Playback is therefore a possible confound for "underwater"; what Josh listens on has not been established. The ruling above does not depend on it.

## 2. DEPTH, the behaviour

### 2.1 The macro

`DEPTH`, last on every voice (path voices seven macros, VOWEL six), default 0, range 0 to 1, neutral 0. At default every sound is today's sound. SIREN and ENSEMBLE each have a macro called DEPTH of their own, and "depth" is also the name of the synth-depth programme; macro names are per engine and no code keys on the name outside SIREN and the FX sections, so there is no collision. The name stays because it is Josh's word for it since the paths spec.

### 2.2 The law

`e` is the window's edge rounding, `u` the sine's amplitude weight (its power share is `u²`):

| DEPTH | e | u | sine share |
|---|---|---|---|
| 0 | 0 | 0 | 0 |
| 0.25 | 0.5 | 0 | 0 |
| 0.5 | 1 | 0 | 0 |
| 0.75 | 1 | 0.5 | 25% |
| 0.9 | 1 | 0.8 | 64% |
| 1 | 1 | 1 | 100% |

In words: `e = 2·DEPTH` and `u = 0` up to 0.5; above it `e = 1` and `u = 2·DEPTH − 1`.

The rounded window, for `e > 0`:

```
w_e(p) = a(p) · (1 − p)^(1 + e)
a(p)   = ½ · (1 − cos(π · min(1, p / (½·e))))
```

`a` is a raised-cosine attack over the first `½·e` of the cycle. `w_e(0) = 0` and `w_e(1) = 0`, with no slope jump across the wrap for `e > 0`. At `e = 0` the window is today's saw, which DEPTH 0 never evaluates (§3.1).

Per sample, on the shaped path (`amp`, `env2`, `second` and `burst` as today's code names them):

```
burst  = w_e(p) · sin(2π·k0·p)
second = level2 · w_e(p) · sin(2π·k1·p)
out    = burstWeight · (amp·burst + env2·second) + sineWeight · amp · sin(2π·p)
burstWeight = sqrt(1 − u²)          (1 when u = 0)
sineWeight  = u · sineGain          (0 when u = 0; the term is then skipped, not zeroed)
```

`sineGain = rB / rS = √2 · rB`, where `rB` is the RMS over one cycle of `w_1(p) · (sin(2π·k0·p) + level2 · sin(2π·k1·p))` at the path's resting ratios, computed by numerical integration once per patch inside `GlintPath` (which owns `kBase`, `k2`, `f0` and `level2`). It matches the sine to the burst the way the round 2 probe did, by measured RMS and not by a guess. It is one scalar for a note whose `k` moves along its path, so it is right at rest; elsewhere it is expected to be within about a dB, which has been measured only at the two probe setups (SWEEP 0.0 dB and VOWEL 0.5 dB off the probe's breath-averaged figure). That is why the real engine is listened to before anything merges (§4).

Output is exactly zero at every wrap, so `k` still changes for free and only there. With `u = 1` the output is `amp · sineGain · sin(2π·p)`: a bare sine at f0.

### 2.3 One-shots and held pads

Both. The sine rides the main amplitude envelope (`amp`); the second burst keeps its own (`env2` on the path voices, `amp` on VOWEL, as today). DEPTH is one value for the whole render: held pads keep it steady through the loop, and the loop still closes exactly because every term is a pure function of phase.

### 2.4 Loudness

Nothing is built for it, deliberately. Rounding the edge costs the burst about 10 dB of RMS (the window's power at `e = 1` is 0.03617 against the saw's 0.33333, −9.64 dB; the probe's burst RMS fell 9.64 dB on SWEEP and 10.71 dB on VOWEL). The engine already levels every sound after it is rendered: `Glint.render` runs `Dsp.levelTo` to `MELODIC_LOUDNESS_TARGET` and `GlintHeld.level` fits the loop to the same target. A per-patch gain is cancelled by both, so none is added.

What can still move the final level is the 0.99 peak ceiling, which depends on crest factor, not scale. Estimated by a replica of the engine (validated against the repo's own recorded all-ones corner peak, 0.896 against 0.8965, so a replica estimate and not an engine measurement): about a 2.7 dB spread across DEPTH at default settings (DEPTH 0.5 about 1 dB under DEPTH 0, DEPTH 1 about 1.7 dB over), 4.8 dB at DECAY 0, flat at DECAY 1. The plan measures the engine's own figures, and the regression guard (§4.7) is a fixed bar of 5 dB against DEPTH 0 (the replica's worst case was 3.7 dB) that fails loudly if the engine exceeds it. A failure is a finding about the engine, and the bar is not raised to make it pass.

### 2.5 Velocity

Unchanged. PEAK is still GLINT's only velocity macro. DEPTH is not eligible: raising it softens the sound, the wrong polarity for a velocity macro. At DEPTH 1 PEAK has nothing to move, so soft and hard layers come out identical; at DEPTH 1 every voice is the same sine and PEAK, BLOOM, FOLLOW and BODY are inert. That is the ugly end, kept on purpose.

### 2.6 Saved sounds and SCRAMBLE

- A saved patch without a DEPTH key decodes with DEPTH 0 and renders bit for bit as before: the key is missing, `Glint.defaults` supplies 0, and `validateMacros` allows missing keys. No GLINT factory roster exists, so only a player's own kits hold GLINT patches.
- A patch saved by the new build carries DEPTH, and an older build refuses it ("unknown macro DEPTH"). `Patches.VERSION` is shared by every engine and a missing key is already legal, so there is no version bump.
- SCRAMBLE's code does not change. DEPTH is listed last, so the earlier macros' draws for a given seed are unchanged. DEPTH starts at 0, so about half of rolls leave it at 0 (negative draws clamp) and about 1 in 12 land above 0.5. Left alone by decision.

## 3. DEPTH, the code

### 3.1 Two sites, each with a literal DEPTH-0 branch

The waveform is computed in exactly two places: `Glint.synthesize` (`Glint.kt:367-378`, the one-shot) and `GlintHeld.render` (`GlintHeld.kt:117-120`, the held pad). BODY's second burst, both of VOWEL's bursts, STEP and BRASS all run through those lines and differ only in `k` and amplitude; `GlintPath` computes no samples.

At DEPTH 0 each site must produce today's bits, so each keeps its own literal expression behind a branch (`if (shape == null)`):

- The two sites associate their multiplies differently (one-shot `amp·(w·s0)`, held `(amp·w)·s0`; the second burst `env2·((level2·w)·s1)` against `((second·level2)·w)·s1`), so no shared helper can reproduce both. A shared helper serves the shaped path only.
- The window is a `Float` today (`windowAt`, `Glint.kt:233`). `2.0 * PI * k[0] * phase` stays exactly as written; a regrouping changes the argument's last bit.
- Never add zero terms. A `-0f` sample plus a `+0f` product becomes `+0f`, and `FloatArray.contentEquals`, which most GLINT tests use, tells them apart (verified on JDK 17: `Arrays.equals` of `0f` and `-0f` is false). DEPTH 0 and, inside the shaped path, `u = 0` are branches, not formulas that evaluate to about 1 or 0.
- `Glint.windowAt` stays as the DEPTH-0 window. It has two production callers and one test.

### 3.2 `GlintShape`

A new internal class in a new file, built once per render from `(depth, path)`. `GlintShape.of` returns null at DEPTH 0, so the sites test `shape == null`. For any DEPTH above 0, however small, the shaped path runs (a tiny DEPTH is a tiny change, and `e = 2·DEPTH` makes the attack span `DEPTH` of a cycle).

It holds `burstWeight`, `sineWeight` and the window. The window is a per-render table of the `(1 − p)^(1 + e)` fall, because the naive form puts a `pow` and a `cos` in every sample of a loop that costs 3 to 4 transcendentals per sample today (the naive window makes it 5 to 7), on held renders of up to about 2.4 million oversampled samples for each of nine zones:

- 4096 intervals, `table[4096] = 0f` exactly, `table[0]` the fall's value at 0. Linear interpolation.
- Indexed by phase, never by a sample counter, because held cycles differ by a sample and exact loop closure needs a pure function of phase.
- `phase.toFloat()` can round to 1.0f; the lookup clamps its cell so the answer is the last entry, 0.
- The attack `a(p)` is analytic and runs only while `p < ½·e`, because at small `e` it is narrower than a table cell.
- Built in `Double` and stored as `Float`. The probe's `Double` window is not lifted into production: a `Double` saw rounded to `Float` differs from today's `Float` window on about 42% of samples, harmless on the shaped path and fatal at DEPTH 0.
- Built per render, not shared: `Glint` and `GlintHeld` hold no mutable state and held zones render in parallel. The build is 4096 `pow` and `cos`, trivial next to the render, and a cancelled held render must still stop within a second.

### 3.3 Everything else that changes

- `Glint.macrosFor`: `MacroSpec("DEPTH", 0f)` appended in both branches (VOWEL's and the other). Everything downstream derives from it: `defaults`, `GlintPatch` validation, the app's sliders (one `MacroSlider` per spec; the label cell is wide enough, SIREN already shows `DEPTH` in it), `Keys.glintPad`'s `filterKeys { it in defaults }`, the breeder, the roster writer, `GlintPadMaker`.
- `GlintPath`: `sineGain` (§2.2).
- The class doc at `Glint.kt:16-20` says of the slope discontinuity "Do not smooth it". It becomes: at DEPTH 0 nothing is smoothed and that discontinuity is the buzz the engine is made of; DEPTH relaxes it on purpose, and the window still reaches zero at the wrap, which is the property that matters. `windowAt`'s doc and `GlintHeld`'s say "one window" and are updated to match.
- Not touched: `Dsp.*` (shared by every engine), `Velocity`, `Patches`, `GlintPatch`, anything in `:cli`, `:kit`, `:loop`, `:json`, `:xpm`, `:mpc3`, `:audio`.

## 4. DEPTH, how it is proved

Each gate is a test that fails with a message naming the guard, and where a guard can be reverted alone, it is shown to fail when it is.

1. **Today's sounds do not move.** No GLINT golden exists (every identity test compares two renders from one build), so the first task adds one before the engine is touched: a frozen copy of today's one-shot and held render code in the test sources (the repo's own pattern, `LegacyPluckLoop` with `StringsTest`), compared bit for bit (`contentEquals`) with production at DEPTH 0 and with the key omitted, across all four voices, macro corners, one-shots and held pads (onset and breathing). Raw buffers are compared where reachable; `Dsp.decimate` and `levelTo` are deterministic, so identical raw buffers mean identical audio. A frozen copy compared on the same JVM, not a stored hash, because Java does not guarantee `Math.sin` is bit-identical across CPUs, and a hash captured on one machine could fail on CI.
2. **Zero at every wrap** for DEPTH above 0, on both sites: the window and the sine are exactly 0 at phase 0 and 1 across a grid of `e`, and the sample at the wrap is 0 (extends `GlintTest`'s existing wrap tests).
3. **Held loops still close exactly** at DEPTH 0.5 and 1: seam 0 and the second breath bit-identical to the first (extends `GlintBreatheTest` and `GlintHeldTest`), on a path voice and on VOWEL, and through `GlintPadMaker`'s nine zones for one patch.
4. **DEPTH 1 is a bare sine** at the note's pitch: at least 99.9% of a held loop's energy within two FFT bins of f0, and the levelled loop is the same to within float rounding whatever PEAK, BLOOM, FOLLOW and BODY say (`sineGain` differs between those settings, and the levelling cancels it).
5. **The sine follows the law:** the sine-to-burst power ratio at DEPTH 0.75 and 0.9 is `u²/(1 − u²)` within a dB (1/3 and 16/9), measured by rendering the two components apart. And `sineGain` agrees with the probe's measured `rB·√2` within 1 dB at the probe's setups (SWEEP and VOWEL defaults with BLOOM 0.85: `rB` 0.139636 and 0.134822).
6. **No new clicks:** the step across each wrap is never larger than the largest step inside the cycles, on raw buffers at DEPTH 0.25, 0.5, 0.75 and 1, per voice. (This replaces a first wording, that the largest step falls as DEPTH rises: true of the listening probe's unlevelled output, but each real render is levelled on its own, and normalised by RMS the saw and the rounded window do not differ that way: SWEEP's step over RMS is about 0.21 at DEPTH 0, 0.22 at 0.25 and 0.20 at 0.5, from the probe's own readout.)
7. **Level stays within a band:** the one-shot's final loudness at DEPTH 0.25 to 1 stays within 5 dB of DEPTH 0, at DECAY 0, 0.5 and 1, on every voice. It is a regression guard, not a promise.
8. **Lists and the corner:** the macro lists are updated (`GlintTest` around `:67-79` and `GlintVowelTest` around `:25-31` pin the macro names and defaults), and the all-ones corner (`GlintTest.kt:52`) pins DEPTH 0, because with a new trailing macro all-ones becomes a bare sine and the corner silently loses its buzzy case.
9. **Edge cases:** DEPTH 1e-9 (shaped path, not saw); exactly 0.5; VOWEL at the top of TUNE, where F1 pins to k = 1 and the burst and the sine are in phase at f0 (the mix still reaches 0 at the wrap and stays bounded); STEP at its 880 Hz top; DECAY 0 and 1; PEAK at both ends (k up to 40, which the numerical `sineGain` must resolve).
10. **Builds:** `./gradlew --no-daemon test`, and `:app:compileDebugKotlin` with `ANDROID_HOME` set on the command line (the slider appears with no app code). No native code is touched.
11. **A listen before anything merges:** Josh hears the real engine, not the probe, at DEPTH 0.25, 0.5, 0.75 and 0.9 on a SWEEP and a VOWEL pad, to confirm it sounds like the round 2 clips. The probe's `sineGain` was measured over a breathing pad and the engine's is stationary, and that is the thing this listen checks.

Also measured in the plan, with nothing depending on it: render time of the nine-zone MAKE INSTRUMENT at DEPTH above 0 against DEPTH 0, and the held pads' DC. The rounded window has no slope jump and the sine has no DC, so DEPTH may shrink the DC the paths work flagged on VOWEL's top keys; that is an expectation, not a claim.

## 5. Housekeeping in the same PR

- `GlintDepthProbe.kt` (untracked, a live `@Test` that writes clips outside the repo) is deleted before the first full test run and never committed.
- `docs/SYNTH_ROADMAP.md`, S10 row: DEPTH built and awaiting its listen, and the roster as Phase 3 still to come (the row still lists the preset roster among its "Phases 2 and 3").
- The paths spec's §6 gets one line pointing here.
- Memory (`snipsnap-glint-engine`) is updated when the PR merges.

## 6. The roster

A second PR, after DEPTH has merged and Josh has heard it in the engine.

### 6.1 Shape

`GlintPresets` (new `GlintPresets.kt`) with `forVoice` and `all`, and the exact helper line `private fun p(voice: GlintVoice, name: String, vararg macros: Pair<String, Float>)` (the shell's `UserPresetsTest` checks that text). **Eight presets per voice, 32 in all**, one fixed count per voice as every other engine has. Every preset sets every macro (the newest engines do). Names are uppercase, at most 14 characters, one or two plain words, unique per voice, and clear of the trademark blocklist (a substring match: "acid", "emu", "serge", "korg", "linn", so PLACID would fail). Order is a contract (CLI `--preset N`, `.first()` pins), so the roster is final before it merges. A loaded preset's name becomes the MAKE INSTRUMENT name.

### 6.2 Authored by ear

No value in the roster comes from Claude's taste ("No agent invents a preset value", the THUMP SNARE precedent). The process:

1. **16 candidates per voice** (64 clips), rendered by the real engine as held pads on one zone near the middle of the keyboard, the sound MAKE INSTRUMENT gives, levelled by the repo's audition rule, 5 s each, named `<VOICE>-01` to `-16`.
2. **The values are a stratified spread, not a choice.** The varied macros are PEAK, FOLLOW, BODY, BLOOM and DEPTH (VOWEL: PEAK, BODY, BLOOM, DEPTH). Each takes 16 evenly spaced levels (`i/15`) exactly once across the 16 candidates, shuffled per macro by a recorded seed, so both ends of every range appear (the ugly end included). DEPTH's levels span 0 to 0.9, never 1.0, which is the same sine on every voice. TUNE and DECAY are not varied: TUNE is overridden per zone in a pad, and held pads ignore DECAY, so any value would be unheard.
3. **Sent to Josh's phone; he replies with the numbers to keep.** If a voice comes up short of eight, a second round of variants near his keeps (seeded jitter) is rendered for that voice.
4. **Names:** Claude proposes them from the settings, Josh vetoes.
5. **Before the PR:** Josh hears the final 32 once more, as pads and as one-shots (`snipsnap synth GLINT <VOICE> --all --out <dir>`).

TUNE and DECAY ship at their defaults unless Josh's review of the final listen says otherwise; those two are the only values in the roster not chosen by ear, and they are flagged.

### 6.3 Wiring and gates

- `Presets.forVoice` gains a GLINT branch and `Presets.all` gains `GlintPresets.all()`; the hand-listed engine lists in `PresetsTest` are updated; app chips and the CLI follow from `Presets.forVoice` with no app or CLI code.
- `Glint.scramble` becomes preset-seeded as SIREN's is: with no `near` patch and temperature below 1 it seeds from a random preset of the voice. Its existing comment says this is the plan.
- `GlintPresetsTest`: eight per voice, names unique, uppercase, at most 14 characters and blocklist-clean, every macro present and in range, no two presets of a voice identical, DEPTH at most 0.9, and every preset renders finite with a peak at or under 0.99, closes its held loop (`requireSeam`), and its pad renders. No spread test: this roster is picked, and good picks cluster (PLUCK, TINES and THUMP have none either).

## 7. Order of work

1. Branch `claude/glint-depth-macro`, from the default branch: this spec, then the plan.
2. PR 1, DEPTH. The frozen reference first, then the engine, tests, docs, the real-engine clips to Josh. Opened for review only when he has heard them.
3. PR 2, the roster, on a fresh branch from the default branch after PR 1 merges: candidates, picks, names, final listen, wiring, tests.

The implementation plan covers PR 1. The roster's plan is written after PR 1 merges, because its shape depends on what the listen finds and on Josh's picks.

Before each push: `./gradlew --no-daemon test` (with `ANDROID_HOME` set on the command line when `:app` should compile). PRs land as merge commits; the base is merged into the branch, never rebased.

## 8. Not doing

- No DEPTH modulation per cycle, no DEPTH on velocity, no envelope on DEPTH. Constant per render.
- No loudness-compensation gain (§2.4), no DC blocker, no new window family beyond the one rounded window. D2 measured window shape nearly inert on its own; here it works only together with the sine.
- No per-voice laws: one law for all four voices.
- No SCRAMBLE special case for DEPTH.
- No change to `Dsp`, `Velocity` or `Patches`.

## 9. Risks and what is not known

- `sineGain` is one stationary scalar: within about a dB of the probe, settled by the listen in §4.11. If the listen says a voice sits wrong, the cheapest fix is a per-voice constant on `sineGain`.
- The loudness spread in §2.4 is a replica estimate until the plan's level test has run against the engine.
- Whether "underwater" at full DEPTH is partly a playback artefact is untested; the ruling does not depend on it.
- Device-run items from the paths work are still open and are not made worse by DEPTH except in render time, which the plan measures: nine held zones in parallel on a phone, a VOWEL chord on the top keys, opening an old kit.
- The roster's quality is exactly the quality of Josh's picks from a blind spread: a voice may come up short of eight, which is what the second round is for.
