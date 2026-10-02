# TERRA Hook R1b (HIT's Floor, the Rattle Pinned, the Follow-up Page) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Act on the owner's three answers on R1's page (2026-10-01). Give HIT's gain a floor, so that at full HIT no mode rings below a fraction φ of today's level whatever hits the drum (default 0.25, provisional), while φ = 0 stays R1's HIT bit for bit. Pin test 6 to "BUZZ follows the striker". Render R1b's ten-clip page (HIT's ladder on one voice, and the floor's four choices on the bar), then stop for the owner.

**Architecture:** A frozen copy of R1's colouring (`LegacyTerraHit`) lands with the floor's tests, so φ = 0 is compared against the colouring the owner heard, not against itself. `Terra.hitLevel` gains a `hitFloor` parameter, defaulting to `Terra.HIT_FLOOR` (0.25). It keeps R1's expression verbatim wherever `s · |P_k(n)|` is at or above the floor, and gives `(1 − c) + c · φ` below it. `s` is still computed on the unfloored `|P_k|`. `renderStruck`, `renderStruckAt` and `bankStruckAt` pass the floor through as the "internal overload taking φ". `TerraPatch`, its JSON and the null path do not change. Test 6 is rewritten from printed to pinned. A new generator branch renders R1b's page.

**Tech Stack:** Kotlin 2.0 / JVM 17, Gradle 8.14 (`./gradlew --no-daemon`), `kotlin.test`. From `:synth`: `Terra`, `TerraPatch`, `Modes`, `Dsp`, `AuditionLevel`. From `:audio`: `Snip`, `WavWriter`. Test sources from R1: `LegacyTerraBank`, `TerraCases`, `TerraStrikers`, `TerraMeasure`, `TerraAuditionGenerator`.

**Spec:** [`docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md`](../specs/2026-09-30-terra-hit-bend-talk-design.md), R1b only: HIT's floor ("HIT, the design", the floor bullet; decision 22), test 6 pinned (decision 2, taken 2026-10-01), and R1b's page ("Phasing and gates"). It builds on [`2026-09-30-terra-hook-r1.md`](2026-09-30-terra-hook-r1.md), which is committed on this branch: HEAD `58757f10` holds Tasks 1–8 of R1.

## Global Constraints

- No driver renders today's TERRA byte for byte. The frozen `LegacyTerraBank` and the 40-case guard (`TerraFrozenTest`; 16 `TerraKits.classic()` pads, 4 voices at defaults, 20 macro probes) are not touched and must pass unchanged.
- The hook stays one change in `Terra.strikeAndModalBank` (R1's). R1b changes only the level curve HIT hands it; `strikeAndModalBank`, `drivenBank` and the pitch curve are not touched.
- HIT, with R1b's floor: `G_k(n) = (1 − c) + c · max(φ, s · |P_k(n)|)`, with `c` = HIT and `φ` = `Terra.HIT_FLOOR`. `P_k(n) = Σ_{m=0..n} x[m] · r_k^(−m) · e^(−j·θ_k·m)` in double; `θ_k = 2π · f0 · ratio_k / RR` (nominal pitch, droop ignored); `r_k = exp(−6.9078 / (t60_k · RR))`; `|P_k(n)|` is constant for `n ≥ M` (the last non-zero sample of `x`, plus 1).
- Level match, unchanged from R1: `s = refPeak / peak(body_s)`. `refPeak` is the peak of today's `0.65 · modalSum` over the first `3·RR/f0 + 16` samples. `body_s` is TERRA's bank at gains `g_k·|P_k(n)|`, unfloored, with no exciter, over the first `max(3·RR/f0, M) + 64` samples.
- In code, the floored value is R1's double expression, `((1.0 - cd) + cd * s * run[k][n]).toFloat()`, written verbatim wherever `s * run[k][n] >= floor`, and `((1.0 - cd) + cd * floor).toFloat()` below it. At a floor of 0 the first branch is always taken, because `s > 0` and `|P| ≥ 0`.
- `c = 0` is the null path outright at every floor and never computes `s`; a non-finite `s` renders the striker as absent (today's body). An impulse is untouched at any floor up to 1 (`s · |P| = 1`).
- HIT 0 = today, 0.5 = subtle, 1 = strong = COLOURED 100 % (decision 1, taken 2026-09-30). HIT's amount lives inside the striker (decision 3).
- The floor is a code constant (`Terra.HIT_FLOOR`), not recipe data. `TerraPatch`, `TerraPatch.Striker`, the version rule and every JSON byte stay as R1 left them.
- The bank renders at `RATE · Dsp.OVERSAMPLE` = 176.4 kHz. The striker head is stored at 44.1 kHz, exactly `Fork.STRIKER_SAMPLES` (882) long, and resampled once at render (3528 samples).
- Decision 2 is taken (2026-10-01, R1's page question 3, "Follow the hit (default)"): BUZZ follows the striker. `s` stays a peak match, and the cavity's 75 Hz stage stays fixed.
- The controller's rulings for R1b, verbatim:
  - HIT FLOOR: per-mode gain G_k(n) = (1 - c) + c * max(phi, s * |P_k(n)|), where phi (HIT_FLOOR) is a fraction of today's per-mode level, so at full HIT no mode rings below phi x today's level whatever hits the drum. The level match s is computed exactly as R1 computes it (on the unfloored |P_k|), then the floor is applied. phi = 0 must reproduce R1's HIT bit for bit (a test pins it). c = 0 stays the null path (the frozen 40-case guard unchanged). phi applies to all four voices (it only bites where a mode would fall below it). phi's value is a listening value chosen at the follow-up page from {0 (none), 0.125 (-18 dB), 0.25 (-12 dB), 0.5 (-6 dB)}; the code default until that answer is 0.25 (-12 dB), labelled provisional; the generator renders the choices through an internal overload taking phi.
  - BUZZ: decision 2 is taken: BUZZ follows the striker. Pin test 6 (the BUZZ/cavity drive test that R1 left print-only) to that behaviour: bounds = R1's printed measurements +/- about 20 %, recomputed with the floor default in place.
  - FOLLOW-UP PAGE (at most 10 clips, at most 3 questions), a new generator page under testkit/terra-audition/R1B/ with its own manifest in the same shape as R1's: (a) the HIT ladder — COMPOUND_MEMBRANE struck by the THUMP SNARE head at HIT 0, .25, .5, .75, 1 (5 clips; floor at the default); (b) the floor — TUNED_BAR today (1 clip) and TUNED_BAR at HIT 1 struck by the factory kick sample (A01_Kick_01.wav, a dark head) with phi = 0, 0.125, 0.25, 0.5 (4 clips). Two questions: "Can you hear the steps from 0 to 1 on the ladder?" and "Which floor keeps the bar sounding right with a dark hit: none, -18, -12 or -6 dB?". Print the per-mode levels of the four floor clips.
  - The claims tests must still hold with the floor default: the coupling claim (overtone-balance spread across strikers >= the spec's bar on the membrane at HIT .5) — if the floor default breaks it, report the measurement, do not loosen.
- The floor's reach, from the formula: at HIT 0.5 the unfloored gain is already at least 0.5, so a floor lifts a mode by at most a factor of 1 + φ (+1.9 dB at 0.25), and one striker's overtone balance moves by no more than that. At HIT 1 the lift is unbounded in dB. Measured by plan review with this plan's code (scratch copy of `58757f10`), the coupling gaps at HIT 0.5 at the default are membrane 5.83, cavity 12.65, bell 3.76 and bar 4.38 dB (Phase 0, floor 0: 5.84 / 15.17 / 3.87 / 4.54), so the coupling claim holds at 0.25 and the ruling's "report the measurement" trigger is not hit there.
- The floor removes decision 4's soft attack at HIT 1; it does not merely shorten it. It lifts every mode while `|P_k|` is still rising through the head. At the default the first-5-ms peak mean over the ten strikers at HIT 1 is 1.000 on the membrane (Phase 0: 0.815 / −2.22 dB), which test 8 prints before it throws. Plan review's probe read the rest, which test 8 does not reach before the ruling (see the notice below): 1.000 on the bell (0.970), 0.833 on the cavity (0.832) and 1.000 on the bar (1.000), and 4 class changes in 40 at HIT 1 (Phase 0: 5). Decision 4's 14.0 ms and test 8's attack and class prints at HIT 1 are φ = 0 figures. At the default they differ from Phase 0's; that is expected, and it is not a failure (they are printed, not bounded). The top rung of R1b's ladder (clip 5) is therefore not what the owner heard as R1's clip 3, though both are the membrane struck by THUMP SNARE at HIT 1.
- Which tests read φ = 0, and which read the default:
  - The two Phase-0 reproductions (`the ten strikers reproduce Phase 0's overtone spread at subtle - recipe provenance` and `HIT reproduces the overtone balance Phase 0 measured`) pin the algorithm Phase 0 measured. They are read at `hitFloor = 0f`.
  - Every other HIT claim stays at the default: coupling, test 8 (monotone, attack and class at subtle), CLACK, hostile, the 200-case sweep and the forty cases at HIT 1. Moving any of those to φ = 0 would be loosening.
- Gates: at most 10 clips and 3 questions; clips are mono 44.1 kHz, levelled by `AuditionLevel.level`, written by `TerraAuditionGenerator`.
- Not in R1b: the 14th `Engine` entry, presets, scramble and starter kit (R2); `bend` and `talk` (R3); the chooser, the pad-sheet group and any `:app` change (R4); any change to `TerraPatch` or its JSON; a velocity override for HIT.
- Tests use `kotlin.test`, never Jupiter. CI sweeps stay small, because CI minutes are metered; the ten-striker drive table stays opt-in behind `TERRA_FULL=1`.
- Always `./gradlew --no-daemon`.
- Judge green by Gradle's exit code, never by grepping output.
- Never skip, disable or loosen a test without printing the measurement and recording it in the test's comment.
- Thresholds are measured numbers with about 20 % margin. Where the built code disagrees with one, print both numbers, record the measurement in the test's comment and set the threshold from it, never silently. A claims test (coupling, test 8, the tanh cap, the striker order in test 6) is never re-thresholded here: its failure is reported.
- Commit titles are plain declarative prose, with no `feat:` prefixes.
- No maker or product names in code, comments, commits or PR text.
- Never put a model identifier in a commit message, PR title, PR body or code comment. Every commit message ends with the single line `Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY`, with no `Co-Authored-By` line.
- Never push, open a PR or merge from this plan. The orchestrator publishes the page and owns the branch.

## Review Focus

- **φ = 0 must be R1's HIT bit for bit**, against a frozen copy of R1's colouring and not against the new code. Pinned in Task 1 (`a floor of 0 is R1's HIT bit for bit`: 160 level curves on four voices × ten strikers × four strengths, and 80 renders on the forty cases).
- **`s` from the unfloored `|P_k|`, and the floor applied after it.** At HIT 1 every floored value must equal the floor-0 value wherever that value is at least φ, and equal φ exactly elsewhere, which is stronger than "≥ φ". Pinned in Task 1 (`at full HIT no bar mode rings below the floor, struck by the factory kick`), and shown to fail when the floor is compared with the unscaled `|P_k|`.
- **`c = 0` is the null path at every floor.** Pinned in Task 1 (`HIT 0 at every floor, and an impulse under the highest floor, render the frozen TERRA`), beside the unchanged 40-case guard.
- **The coupling claim at the floor default.** Pinned by R1's `HIT couples - a dull and a bright head move the overtone balance after 20 ms, not the tuning or the length`, which is left at the default and unchanged. Plan review measured it passing at 0.25 (5.83 / 12.65 / 3.76 / 4.38 dB; the bell's 3.76 is the closest to the 3 dB bar). Task 1 Step 6 stops and reports if it fails.
- **Test 6 pinned to "BUZZ follows the striker".** Each figure is held within ±20 % of its measurement: at φ = 0 against R1's printed figures, and at the default against Step 1's. The tanh cap of 0.36 is kept, and at HIT 1 a dark head must rattle longer than a bright one. Pinned in Task 2 (`BUZZ follows the striker - the cavity's and the bar's drive under HIT, pinned`).
- **Test 8's monotone claim at the floor default fails, and needs a controller ruling before execution.** See the notice below.

## Before execution: a controller ruling is needed

**This plan is blocked at Task 1 Step 6 until the controller rules.** Plan review implemented Task 1 verbatim in a scratch copy of `58757f10` and ran `:synth:test` and `:shell:test`. The only failure is test 8 (`HIT is monotone in its amount and keeps today's attack and class at subtle`), read at the floor default as this plan prescribes:

```
RESONANT_CAVITY BEATBOX RIM: OB is not monotone in HIT: [-37.82512283325195, -37.797203063964844, -37.81308364868164, -37.772193908691406, -37.67024230957031]
```

At c = 0, .25, .5, .75 and 1 the cavity's OB under BEATBOX RIM (`bbrim`) reads −37.825 / −37.797 / −37.813 / −37.772 / −37.670 dB. The step from .25 to .5 is −0.016 dB, against the test's 0.01 dB tolerance. The floor flattens a near-flat curve until it moves less than the tolerance. Plan review's probe found no other voice or striker non-monotone. Test 8 throws at that striker, so its later checks and prints never run in the test: the subtle class and first-5-ms checks on BEATBOX RIM itself, on the cavity's eight later strikers, and on the bell and the bar, and the HIT 1 attack and class prints for the cavity, the bell and the bar. Those first run inside the test, at the default, only after the ruling. The cavity, bell and bar figures quoted under Global Constraints, and the 4-in-40 class changes, come from plan review's probe, not from test output.

The monotone claim is a spec claim (test 8), and this plan never re-thresholds one (Global Constraints). It does not loosen the 0.01 dB tolerance, and it does not move test 8 to φ = 0.

The four floor choices against the three claims at risk, measured by plan review with this plan's code:

| φ | test 8, cavity × BEATBOX RIM: OB at c = 0 / .25 / .5 / .75 / 1 (dB) | bell coupling at HIT .5 (bar: ≥ 3 dB) | cavity tanh input, THUMP KICK at HIT 1 (cap: ≤ 0.36) |
|---|---|---|---|
| 0 (none) | −37.825 / −37.745 / −37.539 / −36.983 / −33.213: **passes** | 3.87: **passes** | 0.3571: **passes** |
| 0.125 (−18 dB) | −37.825 / −37.820 / −37.837 / −37.762 / −37.412 (steps +0.005, −0.017): **fails** | 3.85: passes | 0.3569: passes |
| 0.25 (−12 dB, the provisional default) | −37.825 / −37.797 / −37.813 / −37.772 / −37.670 (step −0.016): **fails** | 3.76: passes | 0.3598: passes, 0.0002 under the cap |
| 0.5 (−6 dB) | −37.825 / −37.821 / −37.806 / −37.808 / −37.795 (worst step −0.002): passes | 2.50: **fails** | 0.3661: **fails** |

On these figures only φ = 0 passes all three claims as the spec writes them. Choices 0.125 and 0.25 both fail test 8, so moving the default between them cannot dodge the failure. 0 and 0.5 pass test 8, but 0 is no floor and 0.5 fails the bell's bar and the tanh cap. Choice 0.5 fails the bell's coupling bar and the tanh cap. The other coupling claims hold at every choice: membrane 5.84 / 5.83 / 5.83 / 5.64 dB, cavity 15.17 / 14.44 / 12.65 / 9.50 dB and bar 4.54 / 4.38 / 4.38 / 3.03 dB at φ = 0 / .125 / .25 / .5 (the bar's 3.03 is marginal). This plan recommends none of the choices. The controller, and through the controller the owner, should see this table before R1b's page ships, because the page offers all four.

Plan review named two options for the controller. The plan writes code for neither until the controller rules:
- **(a)** A recorded, measured exemption in test 8 for the cavity striker whose OB total move is under 0.2 dB, with the measurements above written in the test's comment. As worded, (a) covers φ = 0.25 (total move 0.155 dB). It does **not** cover φ = 0.125 (total move 0.425 dB, from −37.837 to −37.412). If the owner then picks −18 dB, test 8 fails again.
- **(b)** A tolerance scaled to each curve's total span. To pass, the fraction must be at least 0.103 of the span at φ = 0.25 (0.016 of 0.155 dB) and at least 0.040 at φ = 0.125 (0.017 of 0.425 dB). Choosing that fraction is a re-threshold, so the controller sets it.

Two further notes for the controller, from plan review:
- The page auditions the floor at HIT 1 on the bar, where it bites hard. The owner's complaint arose at subtle (R1's question 2), and at 0.25 the floor lifts a mode there by at most +1.9 dB.
- At any non-zero floor the bar's modes 2 to 4 collapse to exactly φ under the factory kick (held gains with no floor: 1.354 / 0.010 / 0.005 / 0.001). So clips 8 to 10 differ only in one flat overtone level: −18.1, −12.0 or −6.0 dB on modes 2 to 4, with mode 1 at +2.6 dB in every floor clip.

Once the controller rules, Task 1 Step 6's stop rule for test 8 says where the ruling is applied. The rest of the plan stands as written.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraHit.kt` | R1's HIT colouring, frozen at `58757f10`: `hitLevel`, `runningMagnitudes` and `peakOf` verbatim (Task 1) |
| Modify `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt:844-861` | `HIT_FLOOR`, `HIT_FLOOR_CHOICES`, and the `hitFloor` parameter on `renderStruck`, `renderStruckAt`, `bankStruckAt` and `hitInputs` (Task 1) |
| Modify `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt:863-909` | `hitLevel`'s floor and its KDoc (Task 1) |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt` | `modeLevels`, `MODE_FROM_FRAMES` and `MODE_WINDOW`: each mode's amplitude in a bank (Task 1) |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt` | imports; the two Phase-0 reproductions read at φ = 0 (`:455-457`, `:479`, `:497`, `:527`); four floor tests (Task 1); test 6 rewritten from printed to pinned (`:773-816`, Task 2) |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt` | imports; KDoc (`:20-21`); the `r1b` branch (after `:75`); `renderR1B` (before the closing brace at `:227`) (Task 3) |
| Modify `synth/build.gradle.kts` (after `generateTerraR1Audition`, ending at `:343`) | the `generateTerraR1BAudition` task (Task 3) |

Line numbers are the head's, `58757f10`. Each step that edits a file quotes the text it replaces, so a step is still exact after an earlier step has moved the lines.

---

### Task 1: HIT's floor, pinned to R1 at 0 and to φ on the bar

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraHit.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt:844-861`, `:863-909`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt` (imports; append before the object's closing brace)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt` (imports; `:455-457`, `:479`, `:497`, `:527`; insert after `a head with nothing in it renders today's body at HIT 1`, which ends at `:436`)

**Interfaces:**
- Consumes:
  - `Terra.Body`, `Terra.BankInputs`, `Terra.renderWith`, `Terra.bankWith` and `Terra.strikeAndModalBank(…, level = …)` (R1 Task 2).
  - `Terra.upsample` and `Terra.hitLevel(body, x, c)` (R1 Task 4).
  - `TerraStrikers.TEN` and `TerraStrikers.head(id)`; `"kick01"` is `A01_Kick_01.wav` (R1 Task 3).
  - `TerraCases.all` and `LegacyTerraBank.render` (R1 Task 1).
  - `TerraMeasure.bodyOf` and `TerraMeasure.BODY_FROM_FRAMES` (R1 Task 4); `Dsp.OVERSAMPLE` (`const val`, 4).
- Produces, in `object Terra`:
  - `internal const val HIT_FLOOR = 0.25f`
  - `internal val HIT_FLOOR_CHOICES: List<Float>`, which is `[0, 0.125, 0.25, 0.5]`
  - `internal fun hitLevel(body: Body, x: FloatArray, c: Float, hitFloor: Float = HIT_FLOOR): Array<FloatArray>?`
  - `internal fun renderStruck(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): Snip`
  - `internal fun renderStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): Snip`
  - `internal fun bankStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): FloatArray`
  - Every existing caller compiles unchanged and now renders at the default floor. That includes `Terra.render(voice, macros, drivers)`, so `TerraPatch.render()` does too.
- Produces, in test sources:
  - `internal object LegacyTerraHit` with `fun level(body: Terra.Body, x: FloatArray, c: Float): Array<FloatArray>?`.
  - In `TerraMeasure`: `const val MODE_FROM_FRAMES`, `const val MODE_WINDOW` and `fun modeLevels(bank: FloatArray, body: Terra.Body, from: Int, length: Int): DoubleArray`.
  - Task 2 uses `bankStruckAt(…, hitFloor)`. Task 3 uses `renderStruck(…, hitFloor)`, `HIT_FLOOR_CHOICES`, `modeLevels`, `MODE_FROM_FRAMES` and `MODE_WINDOW`.

- [ ] **Step 1: Freeze R1's colouring before anything changes**

Create `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraHit.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R1's HIT colouring, frozen at 58757f10 before R1b's floor touched it:
 * `Terra.hitLevel`, `runningMagnitudes` and `peakOf` as R1 committed them,
 * copied verbatim. `a floor of 0 is R1's HIT bit for bit` compares the
 * floored code against this, the colouring the owner heard on R1's page,
 * and not against itself. It calls the live `Terra.strikeAndModalBank`,
 * which TerraFrozenTest's forty-case guard already freezes. Never edit it
 * to follow Terra.kt.
 */
internal object LegacyTerraHit {

    private const val T60_NEPERS_DOUBLE = 6.9078

    fun level(body: Terra.Body, x: FloatArray, c: Float): Array<FloatArray>? {
        if (!(c > 0f) || x.isEmpty() || body.modes.isEmpty()) return null
        val run = runningMagnitudes(body, x)
        val m = run[0].size
        val ringFrames = body.frames - body.onsetSamples
        val periods = (3f * body.rate / body.fundamentalHz).toInt()
        val reference = Terra.strikeAndModalBank(body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, periods + 16), body.rate, { 0f })
        val magnitudes = Array(run.size) { k -> FloatArray(m) { n -> run[k][n].toFloat() } }
        val coloured = Terra.strikeAndModalBank(
            body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, maxOf(periods, m) + 64), body.rate, { 0f },
            level = magnitudes,
        )
        val colouredPeak = peakOf(coloured)
        if (!(colouredPeak > 0f)) return null
        val s = (peakOf(reference) / colouredPeak).toDouble()
        if (!s.isFinite()) return null
        val cd = c.coerceAtMost(1f).toDouble()
        return Array(run.size) { k -> FloatArray(m) { n -> ((1.0 - cd) + cd * s * run[k][n]).toFloat() } }
    }

    private fun runningMagnitudes(body: Terra.Body, x: FloatArray): Array<DoubleArray> {
        var last = 0
        for (i in x.indices) if (x[i] != 0f) last = i
        val m = last + 1
        return Array(body.modes.size) { k ->
            val mode = body.modes[k]
            val hz = body.fundamentalHz * mode.ratio
            val out = DoubleArray(m)
            if (hz <= 0f || hz >= body.rate / 2f || mode.t60 <= 0f) return@Array out
            val invR = 1.0 / exp(-T60_NEPERS_DOUBLE / (mode.t60.toDouble() * body.rate))
            val theta = 2.0 * Math.PI * hz / body.rate
            var re = 0.0
            var im = 0.0
            var w = 1.0
            for (n in 0 until m) {
                if (x[n] != 0f) {
                    val phi = theta * n.toDouble()
                    re += x[n] * w * cos(phi)
                    im -= x[n] * w * sin(phi)
                }
                w *= invR
                out[n] = sqrt(re * re + im * im)
            }
            out
        }
    }

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }
}
```

Then confirm the copy is verbatim:

Run: `diff <(sed -n '891,947p' synth/src/main/kotlin/com/snipsnap/synth/Terra.kt | sed 's/^ *//') <(sed -n '/fun level/,/^}/p' synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraHit.kt | sed 's/^ *//')`
Expected: the only differences are:
- the function's name and visibility line (`internal fun hitLevel(body: Body, …)` against `fun level(body: Terra.Body, …)`);
- `Terra.` in front of the two `strikeAndModalBank` calls;
- `Body` against `Terra.Body` in `runningMagnitudes`' signature;
- the blank lines and KDoc between the functions, which `Terra.kt` holds and the copy does not;
- the copy's closing brace of `object LegacyTerraHit`.

No arithmetic line may differ.

- [ ] **Step 2: Write the per-mode measure**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt`, add to the imports, after `import kotlin.math.log10`:

```kotlin
import kotlin.math.sin
```

and insert before the object's closing brace, after `msAbove`:

```kotlin

    /** Where the per-mode reading starts on a bank at the render rate: 20 ms, the head's own length (3528 frames at 176.4 kHz). */
    const val MODE_FROM_FRAMES = BODY_FROM_FRAMES * Dsp.OVERSAMPLE

    /** The per-mode reading's length on a bank at the render rate: 8192 frames, about 46 ms, a 21.5 Hz bin. */
    const val MODE_WINDOW = 8192

    /**
     * Each mode's amplitude in [bank] (the bank alone, at [Terra.Body.rate])
     * over [length] frames from [from]: the Hann-windowed projection onto the
     * mode's nominal frequency, f0 x ratio. On a rigid body (droop 0: the bell
     * and the bar) the bank's modes ring exactly there, and a struck bank and
     * today's share their phases, their decays and (after the 1.8 ms stick)
     * a silent exciter. So a struck bank's reading over today's is that mode's
     * level against today's, the held HIT gain: the bar's closest modes are
     * more than 50 bins apart, so no mode leaks into another's reading. A mode
     * the bank skips (at or past
     * Nyquist, no t60, or no table gain) reads 0.
     */
    fun modeLevels(bank: FloatArray, body: Terra.Body, from: Int, length: Int): DoubleArray {
        val n = minOf(length, bank.size - from)
        require(n >= 64) { "a per-mode reading needs at least 64 frames, got $n" }
        return DoubleArray(body.modes.size) { k ->
            val mode = body.modes[k]
            val hz = body.fundamentalHz * mode.ratio
            if (hz <= 0f || hz >= body.rate / 2f || mode.t60 <= 0f || mode.gain == 0f) return@DoubleArray 0.0
            val w = 2.0 * PI * hz / body.rate
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val v = bank[from + i] * (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))
                re += v * cos(w * i)
                im -= v * sin(w * i)
            }
            4.0 * sqrt(re * re + im * im) / n
        }
    }
```

(The factor 4/n reads a steady sine of amplitude 1 as about 1: 2/n for the one-sided sum, times 2 for the Hann window's mean of 0.5. Only ratios are asserted.)

- [ ] **Step 3: Write the floor's tests**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt`, add to the imports, after `import kotlin.test.assertEquals`:

```kotlin
import kotlin.test.assertFailsWith
```

Insert after the test `a head with nothing in it renders today's body at HIT 1` (its closing brace is line 436), before the KDoc that begins `Recipe provenance, read before any claim below is blamed on HIT.`:

```kotlin

    // ---------- HIT's floor (R1b; spec "HIT, the design", decision 22) ----------

    /** The provisional default until R1b's page answers, among the four choices the page offers; a floor outside 0..1 is refused. */
    @Test
    fun `HIT's floor defaults to a quarter of today's level, provisionally, among R1b's four choices`() {
        assertEquals(0.25f, Terra.HIT_FLOOR, "the provisional default is -12 dB until R1b's page answers")
        assertEquals(listOf(0f, 0.125f, 0.25f, 0.5f), Terra.HIT_FLOOR_CHOICES)
        assertTrue(Terra.HIT_FLOOR in Terra.HIT_FLOOR_CHOICES)
        val body = TerraMeasure.bodyOf(TerraVoice.TUNED_BAR)
        for (bad in listOf(-0.01f, 1.01f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException>("floor $bad") { Terra.hitLevel(body, impulse, 1f, hitFloor = bad) }
        }
    }

    /**
     * The controller's ruling for R1b: phi = 0 must reproduce R1's HIT bit for
     * bit. Compared with LegacyTerraHit, R1's colouring frozen at 58757f10,
     * not with the floored code itself: every level curve on the four voices,
     * the ten strikers and four strengths, then the forty cases rendered at
     * HIT 1 with a dark and a bright head.
     */
    @Test
    fun `a floor of 0 is R1's HIT bit for bit`() {
        var curves = 0
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            for (s in TerraStrikers.TEN) {
                val x = Terra.upsample(TerraStrikers.head(s.id))
                for (c in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                    val r1 = assertNotNull(LegacyTerraHit.level(body, x, c), "$voice ${s.id} c=$c: R1's colouring")
                    val now = assertNotNull(Terra.hitLevel(body, x, c, hitFloor = 0f), "$voice ${s.id} c=$c: the floored colouring at 0")
                    assertEquals(r1.size, now.size, "$voice ${s.id} c=$c: mode count")
                    for (k in r1.indices) assertContentEquals(r1[k], now[k], "$voice ${s.id} c=$c mode ${k + 1}")
                    curves++
                }
            }
        }
        assertEquals(160, curves)
        var renders = 0
        for (id in listOf("kick01", "tsnare")) {
            val x = Terra.upsample(TerraStrikers.head(id))
            for (c in TerraCases.all) {
                val r1 = Terra.renderWith(c.voice, c.macros) { body -> LegacyTerraHit.level(body, x, 1f)?.let { Terra.BankInputs(level = it) } }
                assertContentEquals(r1.samples, Terra.renderStruckAt(c.voice, c.macros, x, 1f, hitFloor = 0f).samples, "${c.label}, $id at HIT 1")
                renders++
            }
        }
        assertEquals(80, renders)
    }

    /**
     * The floor's promise, on the voice a dark head thins most (R1's page,
     * question 2): at HIT 1, struck by the factory kick (A01_Kick_01.wav), no
     * bar mode rings below phi x today's level, for every choice R1b's page
     * offers.
     *
     * Three readings:
     * - exactly, on the level curves: every floored value equals the floor-0
     *   value wherever that value is at least phi, and equals phi exactly
     *   elsewhere. That pins `s` taken from the unfloored |P_k| and the floor
     *   applied after it.
     * - on the bank: each mode's amplitude against today's (TerraMeasure.
     *   modeLevels, from 20 ms) is at least phi less 0.5 dB, Phase 0's
     *   materiality rule;
     * - the same reading matches the held gain within 0.5 dB, so the measure
     *   reads what the floor did.
     *
     * At phi = 0 at least one bar mode's held gain must fall more than
     * 0.5 dB below the smallest non-zero choice (0.125 x 10^(-0.5/20),
     * about 0.118), the same 0.5 dB gap the page generator checks between
     * floors. Otherwise at least two of the four floor clips on R1b's page
     * hold the same tone after 20 ms, and the plan stops. Measured by plan
     * review: the held gains are 1.354 / 0.010 / 0.005 / 0.001.
     */
    @Test
    fun `at full HIT no bar mode rings below the floor, struck by the factory kick`() {
        val voice = TerraVoice.TUNED_BAR
        val body = TerraMeasure.bodyOf(voice)
        val x = Terra.upsample(TerraStrikers.head("kick01"))
        val from = TerraMeasure.MODE_FROM_FRAMES
        val window = TerraMeasure.MODE_WINDOW
        val today = TerraMeasure.modeLevels(Terra.bankWith(voice, emptyMap(), null), body, from, window)
        for ((k, level) in today.withIndex()) assertTrue(level > 0.0, "bar mode ${k + 1} is silent today")
        val unfloored = assertNotNull(Terra.hitLevel(body, x, 1f, hitFloor = 0f))
        println("TERRA HIT floor: the factory kick at HIT 1 with no floor holds the bar's modes at ${unfloored.joinToString(" / ") { "%.3f".format(it.last()) }} of today's")
        val tolerance = Math.pow(10.0, -0.5 / 20.0)
        val lowest = unfloored.minOf { it.last() }
        val smallest = Terra.HIT_FLOOR_CHOICES.filter { it > 0f }.min()
        assertTrue(lowest < smallest * tolerance, "no bar mode holds 0.5 dB below $smallest of today's under the factory kick at HIT 1 (lowest $lowest), so some of R1b's floor clips would hold one tone")
        for (phi in Terra.HIT_FLOOR_CHOICES) {
            val gains = assertNotNull(Terra.hitLevel(body, x, 1f, hitFloor = phi), "floor $phi")
            for (k in gains.indices) {
                for (n in gains[k].indices) {
                    val u = unfloored[k][n]
                    if (u >= phi) {
                        assertEquals(u, gains[k][n], "floor $phi mode ${k + 1} at $n: not R1's gain where the floor does not bite")
                    } else {
                        assertEquals(phi, gains[k][n], "floor $phi mode ${k + 1} at $n: not the floor where it bites")
                    }
                }
            }
            val struck = TerraMeasure.modeLevels(Terra.bankStruckAt(voice, emptyMap(), x, 1f, hitFloor = phi), body, from, window)
            val ratios = DoubleArray(today.size) { k -> struck[k] / today[k] }
            println(
                "TERRA HIT floor $phi: bar mode levels against today's " +
                    ratios.joinToString(" / ") { "%.3f (%+.1f dB)".format(it, 20 * log10(maxOf(it, 1e-9))) } +
                    ", held gains " + gains.joinToString(" / ") { "%.3f".format(it.last()) },
            )
            for (k in ratios.indices) {
                val held = gains[k].last().toDouble()
                assertTrue(ratios[k] >= phi * tolerance, "floor $phi: bar mode ${k + 1} rings at ${ratios[k]} of today's, under the floor")
                assertTrue(abs(ratios[k] - held) <= 0.06 * maxOf(held, 0.01), "floor $phi: bar mode ${k + 1} reads ${ratios[k]} on the bank against a held gain of $held")
            }
        }
    }

    /** `c = 0` is the null path at every floor (the controller's ruling), and an impulse (`s · |P| = 1`) is untouched even under the highest choice. */
    @Test
    fun `HIT 0 at every floor, and an impulse under the highest floor, render the frozen TERRA`() {
        val head = TerraStrikers.head("kick01")
        var renders = 0
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros).samples
            for (phi in Terra.HIT_FLOOR_CHOICES) {
                assertContentEquals(frozen, Terra.renderStruck(c.voice, c.macros, head, 0f, hitFloor = phi).samples, "${c.label}, HIT 0 at floor $phi")
                renders++
            }
            assertContentEquals(frozen, Terra.renderStruckAt(c.voice, c.macros, impulse, 1f, hitFloor = 0.5f).samples, "${c.label}, an impulse at HIT 1 under floor 0.5")
            renders++
        }
        assertEquals(200, renders)
    }
```

Then point the two Phase-0 reproductions at φ = 0, the algorithm Phase 0 measured.

In the KDoc of `the ten strikers reproduce Phase 0's overtone spread at subtle - recipe provenance`, change

```kotlin
     * Each striker's source format, peak and onset are printed first, so a
     * failure names which source changed.
     */
```

to

```kotlin
     * Each striker's source format, peak and onset are printed first, so a
     * failure names which source changed.
     *
     * Read at a floor of 0 (`hitFloor = 0f`): this pins the algorithm Phase 0
     * measured, which R1b's floor leaves bit for bit at 0 (`a floor of 0 is
     * R1's HIT bit for bit`). The floor default's own claims are the coupling
     * and monotone tests below, which read the default.
     */
```

and change line 479 from

```kotlin
                    TerraMeasure.ob(Terra.renderStruck(row.voice, emptyMap(), TerraStrikers.head(s.id), hit), nominal).toDouble() - today
```

to

```kotlin
                    TerraMeasure.ob(Terra.renderStruck(row.voice, emptyMap(), TerraStrikers.head(s.id), hit, hitFloor = 0f), nominal).toDouble() - today
```

In the KDoc of `HIT reproduces the overtone balance Phase 0 measured`, change

```kotlin
     * The build is the algorithm Phase 0 measured. OB is read from 20 ms
```

to

```kotlin
     * The build is the algorithm Phase 0 measured, read at a floor of 0
     * (`hitFloor = 0f`, R1's HIT bit for bit). OB is read from 20 ms
```

and change line 527 from

```kotlin
            val snip = if (r.striker == null) Terra.render(r.voice) else Terra.renderStruck(r.voice, emptyMap(), TerraStrikers.head(r.striker), r.hit)
```

to

```kotlin
            val snip = if (r.striker == null) Terra.render(r.voice) else Terra.renderStruck(r.voice, emptyMap(), TerraStrikers.head(r.striker), r.hit, hitFloor = 0f)
```

- [ ] **Step 4: Run the tests to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest" -i`
Expected: FAIL in `:synth:compileTestKotlin`, with unresolved references to `HIT_FLOOR` and `HIT_FLOOR_CHOICES`, and "No parameter with name 'hitFloor' found" at the new call sites. `LegacyTerraHit.kt` and `TerraMeasure.kt` compile on their own.

- [ ] **Step 5: Write the floor**

In `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`, replace lines 844-861, from

```kotlin
    /** [voice] struck by a stored [head] at HIT [hit], 0..1. HIT 0 is today's render, byte for byte, and computes nothing. */
    internal fun renderStruck(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray, hit: Float): Snip =
```

through the end of `hitInputs`

```kotlin
        val inputs: (Body) -> BankInputs? = { body -> hitLevel(body, x, hit)?.let { BankInputs(level = it) } }
        return inputs
    }
```

with:

```kotlin
    /**
     * HIT's floor, phi (spec, "HIT, the design"; decision 22): a fraction of
     * today's per-mode level that no mode falls below at HIT 1, whatever
     * strikes the drum. 0.25 is -12 dB and **provisional**: R1b's page picks
     * the value from [HIT_FLOOR_CHOICES], and the answer replaces it here. It
     * is a code constant, not recipe data, so a saved struck pad renders with
     * the build's floor.
     */
    internal const val HIT_FLOOR = 0.25f

    /** The floors R1b's page offers: none, -18, -12 and -6 dB. Removed once the owner picks one. */
    internal val HIT_FLOOR_CHOICES = listOf(0f, 0.125f, 0.25f, 0.5f)

    /** [voice] struck by a stored [head] at HIT [hit], 0..1, under the floor [hitFloor]. HIT 0 is today's render, byte for byte, and computes nothing, at any floor. */
    internal fun renderStruck(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): Snip =
        if (!(hit > 0f)) render(voice, macros) else renderStruckAt(voice, macros, upsample(head), hit, hitFloor)

    /** [renderStruck] with the striker [x] already at the render rate; an impulse there is `floatArrayOf(1f)`. */
    internal fun renderStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): Snip =
        renderWith(voice, macros, hitInputs(x, hit, hitFloor))

    /** The bank alone ([bankWith]) struck by [x], already at the render rate, at HIT [hit] under the floor [hitFloor]. */
    internal fun bankStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float, hitFloor: Float = HIT_FLOOR): FloatArray =
        bankWith(voice, macros, hitInputs(x, hit, hitFloor))

    /** HIT as the bank's input builder: null at HIT 0, so [renderWith] takes today's path without computing anything. */
    private fun hitInputs(x: FloatArray, hit: Float, hitFloor: Float): ((Body) -> BankInputs?)? {
        if (!(hit > 0f)) return null
        val inputs: (Body) -> BankInputs? = { body -> hitLevel(body, x, hit, hitFloor)?.let { BankInputs(level = it) } }
        return inputs
    }
```

Then replace `hitLevel`, its KDoc and its body, from the `/**` above `HIT's level curve on [body]` (line 863) to the function's closing brace (line 909, just before the KDoc that begins `` `|P_k(n)|` for n below M``), with:

```kotlin
    /**
     * HIT's level curve on [body]: round two's COLOURED candidate, in the
     * gain domain, on TERRA's own bank (spec, "HIT, the design"), with R1b's
     * floor.
     *
     * Each mode's gain is multiplied by
     * `G_k(n) = (1 - c) + c * max(phi, s * |P_k(n)|)`:
     * - `|P_k(n)|` is the running magnitude of the striker [x] projected onto
     *   mode k at its nominal pitch, droop ignored. It grows while the
     *   striker plays and holds after its last non-zero sample, so a
     *   short-lived mode is never inflated during the head.
     * - `s` is the level match: the peak of today's body over the first three
     *   periods, divided by the peak of the coloured body. It is taken from
     *   the unfloored `|P_k|`, exactly as R1 took it.
     * - `phi` is [hitFloor], a fraction of today's per-mode level: at HIT 1
     *   no mode rings below phi x today's. It is applied after `s`. Where
     *   `s * |P_k(n)|` is at or above it, the value is R1's expression,
     *   verbatim. At a floor of 0 that is every value, because `s > 0` and
     *   `|P| >= 0`, so a floor of 0 is R1's HIT bit for bit (TerraTest's `a
     *   floor of 0 is R1's HIT bit for bit`, against LegacyTerraHit).
     *
     * The receiver's modes come from [body] at render, so a retuned pad is
     * recoloured. Returns null - today's body - when [c] is not above 0, or
     * when `s` is not finite because the coloured body has no peak (spec,
     * "Failure handling"). A floor outside 0..1, or NaN, is refused.
     *
     * The arithmetic follows Phase 0's operation for operation, as the
     * Phase-0 record's §8.1 quotes it (`TerraStruckR2.running` and
     * `levelS`), which is what makes it the measured algorithm:
     * - the projection in double precision;
     * - a zero striker sample skipped, but its decay weight still advanced;
     * - `s` divided in float and then widened.
     * One deliberate difference: where the coloured body has no peak,
     * Phase 0's `levelS` returned `s = 0.0` (every gain `1 - c`); this
     * returns null, today's body, as the spec's failure rule asks. No
     * Phase-0 case reached that branch.
     */
    internal fun hitLevel(body: Body, x: FloatArray, c: Float, hitFloor: Float = HIT_FLOOR): Array<FloatArray>? {
        require(hitFloor >= 0f && hitFloor <= 1f) { "HIT's floor is a fraction of today's level, 0..1, got $hitFloor" }
        if (!(c > 0f) || x.isEmpty() || body.modes.isEmpty()) return null
        val run = runningMagnitudes(body, x)
        val m = run[0].size
        val ringFrames = body.frames - body.onsetSamples
        val periods = (3f * body.rate / body.fundamentalHz).toInt()
        val reference = strikeAndModalBank(body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, periods + 16), body.rate, { 0f })
        val magnitudes = Array(run.size) { k -> FloatArray(m) { n -> run[k][n].toFloat() } }
        val coloured = strikeAndModalBank(
            body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, maxOf(periods, m) + 64), body.rate, { 0f },
            level = magnitudes,
        )
        val colouredPeak = peakOf(coloured)
        if (!(colouredPeak > 0f)) return null
        val s = (peakOf(reference) / colouredPeak).toDouble()
        if (!s.isFinite()) return null
        val cd = c.coerceAtMost(1f).toDouble()
        val floor = hitFloor.toDouble()
        return Array(run.size) { k ->
            FloatArray(m) { n ->
                if (s * run[k][n] < floor) ((1.0 - cd) + cd * floor).toFloat() else ((1.0 - cd) + cd * s * run[k][n]).toFloat()
            }
        }
    }
```

- [ ] **Step 6: Run the tests, and the claims at the default (expected: STOP on test 8)**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest" --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.TerraPatchTest" --tests "com.snipsnap.synth.TerraCaptureTest" --tests "com.snipsnap.synth.DeterminismTest" -i`
Expected, until the controller rules (see "Before execution: a controller ruling is needed"): a non-zero exit code, with exactly one failing test, `HIT is monotone in its amount and keeps today's attack and class at subtle`, and this message:

```
RESONANT_CAVITY BEATBOX RIM: OB is not monotone in HIT: [-37.82512283325195, -37.797203063964844, -37.81308364868164, -37.772193908691406, -37.67024230957031]
```

That is the expected STOP. Do not loosen the 0.01 dB tolerance, do not move test 8 to φ = 0, and do not change `Terra.HIT_FLOOR` to dodge it (0.125 fails the same way; 0.5 fails the bell's coupling bar and the tanh cap instead). Report the message and the `TERRA HIT sweep RESONANT_CAVITY BEATBOX RIM` line to the orchestrator, then STOP. Do not run Steps 7 and 8, or Tasks 2 and 3, until the controller's ruling has been applied to test 8 in `TerraTest.kt` and recorded in the test's comment with the figures above. After that, re-run this step and expect exit code 0.

Every other test in the run is expected to pass. Read and keep these for the commit message:
- the `TERRA HIT floor` lines: the bar's held gains under the kick with no floor, then each floor's per-mode levels against today's. Plan review measured 1.354 / 0.010 / 0.005 / 0.001 with no floor, then modes 1 to 4 at +2.6 / −39.7 / −46.3 / −57.3 dB at φ = 0, +2.6 / −18.1 / −18.1 / −18.1 at 0.125, +2.6 / −12.0 / −12.0 / −12.0 at 0.25 and +2.6 / −6.0 / −6.0 / −6.0 at 0.5;
- the `TERRA HIT coupling` lines at the default. Plan review measured membrane 5.83, cavity 12.65, bell 3.76 and bar 4.38 dB apart (Phase 0, floor 0: 5.84, 15.17, 3.87, 4.54);
- the `TERRA HIT sweep` and `TERRA HIT 1` lines. Before the ruling, test 8 prints only the membrane's ten strikers, its attack line, the cavity's two BEATBOX KICK lines and its BEATBOX RIM sweep line, then throws. The membrane's first-5-ms peak mean at HIT 1 reads 1.000 (Phase 0: 0.815 / −2.22 dB): the floor removes decision 4's soft attack at HIT 1, because it lifts every mode while `|P_k|` rises. After the ruling, expect what plan review's probe read: 1.000 on the bell (Phase 0: 0.970), 0.833 on the cavity (0.832), 1.000 on the bar (1.000), and 4 class changes in 40 (Phase 0: 5). They are printed, not bounded;
- the `TERRA HIT CLACK` ratios;
- the `TERRA drive` lines. They are R1's test 6 still printing, now at the default floor, and Task 2 Step 1 reads them again.

The `TERRA HIT OB` and `TERRA HIT T2` lines read φ = 0 and must still match Phase 0's as they did in R1.

If a test fails, act by which one:
- **`a floor of 0 is R1's HIT bit for bit`:** the floored branch is not R1's expression where it should be, or the null and `s` conditions moved. Compare `hitLevel` with `LegacyTerraHit.level` line by line. The only additions allowed are the `require`, `floor` and the `if`. Never edit `LegacyTerraHit`.
- **`HIT couples…` on any voice** (plan review measured it passing at the default: 5.83 / 12.65 / 3.76 / 4.38 dB, the bell closest to the 3 dB bar): this is the controller's named claim. Do not loosen the 3 dB bar, and do not move the test to φ = 0. Print and report the measured gap per voice at the default. Then STOP: the default floor is the owner's to reconsider, not this plan's.
- **`HIT is monotone…`** (test 8): a spec claim at the default. The cavity × BEATBOX RIM failure above is expected until the controller rules, and its handling is the controller's ruling. Any other failure of test 8, before or after the ruling (another striker's monotone check, or the class or first-5-ms check at subtle), is a new finding. Report the printed values and STOP. Do not re-threshold it.
- **`the CLACK pre-roll stays quiet under HIT`, the hostile renders, the 200-case sweep or the forty cases at HIT 1:** the floor broke a safety property. Report and STOP.
- **`at full HIT no bar mode rings below the floor…`, on its `lowest < smallest * tolerance` premise (about 0.118, 0.5 dB under 0.125):** under this head the floor does not bite at every choice on the bar's held tone, so R1b's page cannot ask its question 2 with four distinct clips. Report the printed held gains and STOP.
- **The same test, on the 0.5 dB bank readings, while the exact level-curve checks pass:** print both readings. The measure, not the floor, is off: check the window (`MODE_FROM_FRAMES` must be at or past the head's last sample). Record the measured difference in the KDoc and set the tolerance from it plus about 20 %.

- [ ] **Step 7: Show the exact check catches a floor taken before the level match**

In `hitLevel`, change `if (s * run[k][n] < floor)` to `if (run[k][n] < floor)`, so the floor is compared with the unscaled magnitude.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.at full HIT no bar mode rings below the floor, struck by the factory kick" --tests "com.snipsnap.synth.TerraTest.a floor of 0 is R1's HIT bit for bit" -i`
Expected while the change is in place:
- the floor test FAILS on an exact check ("not R1's gain where the floor does not bite" or "not the floor where it bites") at a floor above 0, because the kick's `s` on the bar is not 1;
- the floor-0 test still PASSES, because at 0 neither condition ever bites.

Revert, re-run, and expect exit code 0. Then run `git diff synth/src/main/kotlin/com/snipsnap/synth/Terra.kt | grep -c 's \* run\[k\]\[n\] < floor'` and expect `1`: the reverted line, with `s *`.

- [ ] **Step 8: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Terra.kt synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraHit.kt synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt
git commit -m "HIT has a floor: at full strength no TERRA mode rings below a quarter of today's level" -m "R1's page asked for one. Each mode's gain is (1 - c) + c * max(phi, s * |P|),
with the level match taken from the unfloored projection as before and phi a
fraction of today's per-mode level. The default, 0.25 (-12 dB), is provisional
until R1b's page picks among none, -18, -12 and -6 dB. A floor of 0 is R1's
HIT bit for bit against a frozen copy of R1's colouring, HIT 0 is today's
TERRA at every floor, and struck by the factory kick at HIT 1 every bar mode
holds at least the floor. The Phase-0 reproductions read a floor of 0; every
other HIT claim reads the default." -m "Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

Before running it, add one more `-m "..."` argument, before the `Claude-Session` one, that holds the lines Step 6 printed after the controller's ruling was applied, copied from the test output: the bar's held gains and per-mode levels at each floor, the four coupling gaps at the default, HIT 1's class changes and attack means, and the CLACK ratios. Say in that paragraph that the floor removes decision 4's soft attack at HIT 1 (the first-5-ms peak means against Phase 0's 0.815 / 0.832 / 0.970 / 1.000), and name the controller's ruling on test 8 in one sentence. Copy only figures the test printed; plan review's probe figures are not test output.

---

### Task 2: Test 6 pinned: BUZZ follows the striker

**Files:**
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt:773-816` (replace the test `BUZZ and the cavity's drive under HIT are printed until R1's page answers decision 2`, its KDoc and body)

**Interfaces:**
- Consumes:
  - `Terra.bankWith`, `Terra.bankStruckAt(…, hitFloor)`, `Terra.upsample`, `Terra.HIT_FLOOR` and `Terra.defaults`.
  - `TerraMeasure.cavityStage`, `TerraMeasure.msAbove` and `TerraMeasure.tanhInput`.
  - `TerraStrikers.TEN` and `TerraStrikers.head`.
  - The test class's own `full` (`TERRA_FULL=1`).
- Produces: no production code. Spec test 6, pinned to decision 2 as taken.

Both tables are written in full. The floor-0 table is R1's printed figures (commit `19ff3762`), which equal Phase 0's to the printed digit. The floored table is plan review's measurement at the default, 0.25, with this plan's Task 1 code in a scratch copy of `58757f10`: the six `TERRA drive` lines R1's test 6 printed there. Step 1 confirms them on the built code before the test is rewritten.

- [ ] **Step 1: Confirm the drive figures with the floor in place**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.BUZZ and the cavity's drive under HIT are printed until R1's page answers decision 2" --rerun -i`
Expected: exit code 0, then seven lines:
- `TERRA drive today: cavity 55.6 ms and bar 53.2 ms above 0.12 at BUZZ 1, cavity tanh input 0.3075`, unchanged from R1, because HIT 0 is the null path;
- one `TERRA drive <id> HIT <hit>` line for each of `tkick`, `tsnare` and `wraith` at 0.5 and 1, now at the floor default, 0.25. They should read as plan review measured them, beside R1's floor-0 figures:

| striker | HIT | at the default, 0.25: cavity ms / bar ms / tanh input | R1, floor 0: cavity ms / bar ms / tanh input |
|---|---|---|---|
| tkick | 0.5 | 63.9 / 55.2 / 0.3277 | 63.7 / 55.2 / 0.3260 |
| tkick | 1 | 69.8 / 55.3 / 0.3598 | 69.3 / 54.2 / 0.3571 |
| tsnare | 0.5 | 40.1 / 47.8 / 0.2471 | 40.0 / 47.8 / 0.2468 |
| tsnare | 1 | 24.8 / 41.5 / 0.2053 | 24.7 / 41.3 / 0.2049 |
| wraith | 0.5 | 35.4 / 35.8 / 0.2413 | 32.2 / 35.7 / 0.2381 |
| wraith | 1 | 16.6 / 13.7 / 0.1936 | 14.2 / 13.3 / 0.1867 |

The widest move from R1's figure is wraith at HIT 1 on the cavity, 16.6 ms against 14.2 (+17 %). That is why the floored table is pinned separately and does not reuse R1's.

The tanh cap's margin at the default is thin: THUMP KICK at HIT 1 reads 0.3598 against the cap of 0.36, a margin of 0.0002. At φ = 0.5 the same figure is 0.3661, past the cap (see "Before execution: a controller ruling is needed").

If a printed figure differs from the table, print both and check Task 1's `hitLevel` against Step 5's code before going on. If a printed tanh input is above 0.36, the floor broke the spec's cap. Report it and STOP: the cap is not re-thresholded here.

- [ ] **Step 2: Write the pinned test**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt`, replace the test `BUZZ and the cavity's drive under HIT are printed until R1's page answers decision 2`, from its KDoc's `/**` (line 773, beginning `Spec "Testing", test 6, and decision 2.`) to its closing brace (line 816), with the code below.

```kotlin
    /**
     * Spec "Testing", test 6, pinned to decision 2 as R1's page took it on
     * 2026-10-01 (question 3, "Follow the hit"): BUZZ follows the striker. The
     * level match `s` matches the body's peak, not the 75 Hz band-passed level
     * the cavity's tanh sees nor the 0.12 threshold BUZZ gates on. So a dark
     * head keeps the rattle about as long as today's, and a bright head
     * shortens it.
     *
     * Each figure is pinned within ±20 % of its measurement (the controller's
     * ruling for R1b, which replaces the spec's ±0.05 ratio bands), in two
     * tables:
     * - at a floor of 0, R1's HIT, against R1's printed figures (commit
     *   19ff3762), which equal Phase 0's to the printed digit;
     * - at the floor default ([Terra.HIT_FLOOR], 0.25, provisional), against
     *   the figures measured with the floor in place (R1b's plan review,
     *   confirmed by its Task 2 Step 1).
     *
     * Measured at the default, against R1's floor-0 figures (cavity ms / bar
     * ms / tanh input). Each row reads "floored, against R1's":
     * - THUMP KICK HIT 0.5: 63.9 / 55.2 / 0.3277, against 63.7 / 55.2 / 0.3260;
     * - THUMP KICK HIT 1: 69.8 / 55.3 / 0.3598, against 69.3 / 54.2 / 0.3571;
     * - THUMP SNARE HIT 0.5: 40.1 / 47.8 / 0.2471, against 40.0 / 47.8 / 0.2468;
     * - THUMP SNARE HIT 1: 24.8 / 41.5 / 0.2053, against 24.7 / 41.3 / 0.2049;
     * - WRAITH WORD HIT 0.5: 35.4 / 35.8 / 0.2413, against 32.2 / 35.7 / 0.2381;
     * - WRAITH WORD HIT 1: 16.6 / 13.7 / 0.1936, against 14.2 / 13.3 / 0.1867.
     *
     * THUMP KICK at HIT 1 holds the tanh input 0.0002 under the cap at the
     * default (0.3598); at a floor of 0.5 it reads 0.3661, past it.
     *
     * Two claims sit beside the bands and are never re-thresholded:
     * - the cavity's tanh input stays at or under 0.36 (within 4 % of linear,
     *   the spec's cap) in both tables;
     * - at HIT 1, at the default, THUMP KICK keeps BUZZ above 0.12 longer
     *   than WRAITH WORD, on the cavity and on the bar. That is "follows the
     *   striker": a band-passed level match would hold the two near each
     *   other and near today's.
     *
     * The other seven strikers are printed, not pinned, when TERRA_FULL=1.
     * When R1b's page picks a floor other than 0.25, the floored table is
     * measured again at the new value by the same step.
     */
    @Test
    fun `BUZZ follows the striker - the cavity's and the bar's drive under HIT, pinned`() {
        class Drive(val id: String, val hit: Float, val cavityMs: Double, val barMs: Double, val tanhIn: Double)
        val r1 = listOf(
            Drive("tkick", 0.5f, 63.7, 55.2, 0.3260),
            Drive("tkick", 1f, 69.3, 54.2, 0.3571),
            Drive("tsnare", 0.5f, 40.0, 47.8, 0.2468),
            Drive("tsnare", 1f, 24.7, 41.3, 0.2049),
            Drive("wraith", 0.5f, 32.2, 35.7, 0.2381),
            Drive("wraith", 1f, 14.2, 13.3, 0.1867),
        )
        // Measured at Terra.HIT_FLOOR = 0.25 (R1b's plan review; confirmed by R1b Task 2 Step 1).
        val floored = listOf(
            Drive("tkick", 0.5f, 63.9, 55.2, 0.3277),
            Drive("tkick", 1f, 69.8, 55.3, 0.3598),
            Drive("tsnare", 0.5f, 40.1, 47.8, 0.2471),
            Drive("tsnare", 1f, 24.8, 41.5, 0.2053),
            Drive("wraith", 0.5f, 35.4, 35.8, 0.2413),
            Drive("wraith", 1f, 16.6, 13.7, 0.1936),
        )
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val buzz = mapOf("BUZZ" to 1f)
        val mix = Terra.defaults(TerraVoice.RESONANT_CAVITY).getValue("CAVITY")
        fun near(expected: Double, got: Double, what: String) =
            assertTrue(abs(got - expected) <= 0.2 * abs(expected), "$what: $got, pinned at $expected ± 20 %")
        fun measure(id: String, hit: Float, floor: Float): DoubleArray {
            val x = Terra.upsample(TerraStrikers.head(id))
            val cavity = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, buzz, x, hit, floor), mix, rate), 0.12f, rate)
            val bar = TerraMeasure.msAbove(Terra.bankStruckAt(TerraVoice.TUNED_BAR, buzz, x, hit, floor), 0.12f, rate)
            val tanhIn = TerraMeasure.tanhInput(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, emptyMap(), x, hit, floor), rate).toDouble()
            return doubleArrayOf(cavity, bar, tanhIn)
        }

        val cavityToday = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankWith(TerraVoice.RESONANT_CAVITY, buzz, null), mix, rate), 0.12f, rate)
        val barToday = TerraMeasure.msAbove(Terra.bankWith(TerraVoice.TUNED_BAR, buzz, null), 0.12f, rate)
        val tanhToday = TerraMeasure.tanhInput(Terra.bankWith(TerraVoice.RESONANT_CAVITY, emptyMap(), null), rate).toDouble()
        println("TERRA drive today: cavity ${"%.1f".format(cavityToday)} ms and bar ${"%.1f".format(barToday)} ms above 0.12 at BUZZ 1, cavity tanh input ${"%.4f".format(tanhToday)} (R1 and Phase 0: 55.6, 53.2, 0.3075)")
        near(55.6, cavityToday, "today: cavity ms above 0.12")
        near(53.2, barToday, "today: bar ms above 0.12")
        near(0.3075, tanhToday, "today: cavity tanh input")

        val got = mutableMapOf<String, DoubleArray>()
        for ((floor, table) in listOf(0f to r1, Terra.HIT_FLOOR to floored)) {
            for (row in table) {
                val (cavity, bar, tanhIn) = measure(row.id, row.hit, floor).also { got["$floor ${row.id} ${row.hit}"] = it }
                println(
                    "TERRA drive floor $floor ${row.id} HIT ${row.hit}: cavity ${"%.1f".format(cavity)} ms (x${"%.2f".format(cavity / cavityToday)}), " +
                        "bar ${"%.1f".format(bar)} ms (x${"%.2f".format(bar / barToday)}), tanh input ${"%.4f".format(tanhIn)} (x${"%.2f".format(tanhIn / tanhToday)})",
                )
                val what = "floor $floor ${row.id} HIT ${row.hit}"
                near(row.cavityMs, cavity, "$what: cavity ms above 0.12")
                near(row.barMs, bar, "$what: bar ms above 0.12")
                near(row.tanhIn, tanhIn, "$what: cavity tanh input")
                assertTrue(tanhIn <= 0.36, "$what: the cavity's tanh input $tanhIn is past 0.36, more than 4 % from linear")
            }
        }

        val kick = got.getValue("${Terra.HIT_FLOOR} tkick 1.0")
        val wraith = got.getValue("${Terra.HIT_FLOOR} wraith 1.0")
        assertTrue(kick[0] > wraith[0], "HIT 1: the cavity rattles ${kick[0]} ms under THUMP KICK and ${wraith[0]} ms under WRAITH WORD; BUZZ does not follow the striker")
        assertTrue(kick[1] > wraith[1], "HIT 1: the bar rattles ${kick[1]} ms under THUMP KICK and ${wraith[1]} ms under WRAITH WORD; BUZZ does not follow the striker")

        if (full) {
            for (s in TerraStrikers.TEN.filter { it.id !in setOf("tkick", "tsnare", "wraith") }) {
                for (hit in listOf(0.5f, 1f)) {
                    val (cavity, bar, tanhIn) = measure(s.id, hit, Terra.HIT_FLOOR)
                    println(
                        "TERRA drive floor ${Terra.HIT_FLOOR} ${s.id} HIT $hit (printed, not pinned): cavity x${"%.2f".format(cavity / cavityToday)}, " +
                            "bar x${"%.2f".format(bar / barToday)}, tanh input x${"%.2f".format(tanhIn / tanhToday)}",
                    )
                    assertTrue(cavity.isFinite() && bar.isFinite() && tanhIn.isFinite(), "${s.id} HIT $hit: a drive figure is not finite")
                }
            }
        }
    }
```

A `Float` printed in a string template reads `1.0` and `0.5`, so the map keys are `"0.25 tkick 1.0"` and `"0.25 wraith 1.0"`. They are built the same way the loop builds them, so the lookup cannot drift.

- [ ] **Step 3: Run it**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.BUZZ follows the striker - the cavity's and the bar's drive under HIT, pinned" --rerun -i`
Expected: exit code 0. You should see the `TERRA drive today` line, then twelve `TERRA drive floor` lines: six at floor 0.0 matching R1's figures, and six at floor 0.25 matching the floored table (Step 1's).

If it fails, act by which assertion:
- **A floor-0 row is outside ±20 %:** R1's HIT moved, but Task 1 pinned it bit for bit. Check that the row reads `hitFloor = 0f` through `measure`, then report and STOP.
- **A floored row is outside ±20 %:** the built floor is not the one plan review measured (Step 1 should already have caught it). Check `hitLevel` against Task 1 Step 5's code. If the code is as written, print both figures, record the measurement in the KDoc's `Measured` list, and set the row from it (Global Constraints). Never widen the 20 %.
- **The tanh cap, or kick-over-wraith on the cavity or the bar:** the floor default breaks decision 2's claim. Report both printed figures and STOP. Do not re-threshold.

- [ ] **Step 4: Print the ten-striker table once**

Run: `TERRA_FULL=1 ./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.BUZZ follows the striker - the cavity's and the bar's drive under HIT, pinned" --rerun -i`
Expected: exit code 0, and fourteen more `(printed, not pinned)` lines, for the other seven strikers at 0.5 and 1. Keep the extremes for the commit message beside R1's floor-0 ranges:
- cavity BUZZ x0.47–1.14 at HIT 0.5 and x0.05–1.25 at HIT 1;
- bar BUZZ x0.50–1.14 and x0.05–1.24;
- tanh input x0.56–1.06 and x0.19–1.16.

- [ ] **Step 5: Show the pin can fail**

In `measure`, change `0.12f` on the bar line to `0.06f`, half the BUZZ threshold. The bar's fundamental decays with a time constant of about 51 ms (t60 0.35 s at the default DECAY), so halving the threshold adds about ln 2 × 51 ≈ 35 ms above it, far past 20 %. A smaller change, 0.10, would add only about 9 ms, inside the band.

Run the Step 3 command.
Expected while the change is in place: FAIL, with a "bar ms above 0.12" assertion on the first floor-0 row (`tkick` HIT 0.5); plan review measured 87.8 ms there against 55.2. Revert, re-run, and expect exit code 0.

- [ ] **Step 6: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt
git commit -m "Pin that TERRA's rattle follows what hits it" -m "R1's page took decision 2: BUZZ follows the striker. Test 6 now pins each
named striker's BUZZ time on the cavity and the bar, and the cavity's tanh
input, within 20 % of its measurement: at a floor of 0 against R1's printed
figures, and at the floor default against figures measured with it in place.
The tanh input stays within 4 % of linear, and at HIT 1 a kick head rattles
longer than a vocal one on both voices." -m "Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

Before running it, add one more `-m "..."` argument, before the `Claude-Session` one, that holds the figures Steps 1 and 4 printed, copied from the test output: the six floored rows beside R1's, and the ten-striker extremes at the default.

---

### Task 3: R1b's page, then stop for the owner

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt`:
  - imports (`:3-6`);
  - KDoc (`:20-21`);
  - after the `r1` branch (`:72-75`): the `r1b` branch;
  - before the object's closing brace (`:227`, after `renderR1`): `renderR1B`.
- Modify: `synth/build.gradle.kts` (after the `generateTerraR1Audition` block, which ends at `:343`)

**Interfaces:**
- Consumes:
  - `TerraStrikers.ten(dir)` (`"tsnare"` is THUMP SNARE; `"kick01"` is FACTORY KICK, `A01_Kick_01.wav`), `Terra.captureStriker` and `TerraPatch(…).copy(striker = …)` / `TerraPatch.Striker`.
  - `Terra.render`, `Terra.renderStruck(…, hitFloor)`, `Terra.bankWith`, `Terra.bankStruckAt(…, hitFloor)`, `Terra.upsample` and `Terra.HIT_FLOOR_CHOICES` (Task 1).
  - `TerraMeasure.bodyOf`, `modeLevels`, `MODE_FROM_FRAMES` and `MODE_WINDOW` (Task 1).
  - `AuditionLevel.level(snip)` and `WavWriter.write(file, snip, WavWriter.BitDepth.PCM_16)`.
  - The generator's own `DOT` and `q`.
- Produces:
  - `testkit/terra-audition/R1B/<id>.wav`: ten clips, mono 44.1 kHz, levelled, 16-bit.
  - `testkit/terra-audition/R1B/manifest.json`: the clips and two questions, in R1's shape. The orchestrator turns it into R1b's page.
  - The Gradle task `generateTerraR1BAudition`.

- [ ] **Step 1: Confirm the output folder is ignored**

Run: `git check-ignore -v testkit/terra-audition/R1B/x.wav`
Expected: `.gitignore:65:testkit/terra-audition/	testkit/terra-audition/R1B/x.wav`.

- [ ] **Step 2: Write R1b's page generator**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt`, add to the imports, after `import java.io.File`:

```kotlin
import kotlin.math.abs
import kotlin.math.log10
```

Change the object's KDoc lines

```kotlin
 * listening artifact. With a second argument `r1` it renders R1's page
 * instead ([renderR1]): HIT in the engine, ten clips and three questions.
 */
```

to

```kotlin
 * listening artifact. With a second argument `r1` it renders R1's page
 * instead ([renderR1]): HIT in the engine, ten clips and three questions.
 * With `r1b` it renders R1b's page ([renderR1B]): HIT's ladder and its
 * floor, ten clips and two questions.
 */
```

After the `r1` branch

```kotlin
        if (args.getOrNull(1) == "r1") {
            renderR1(root)
            return
        }
```

insert:

```kotlin
        if (args.getOrNull(1) == "r1b") {
            renderR1B(root)
            return
        }
```

Before the object's closing brace, after `renderR1`, insert:

```kotlin

    /**
     * R1b's page (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
     * "Phasing and gates", "R1b's page: HIT's ladder and its floor"): ten clips
     * and two questions under [root]/R1B, with a manifest in R1's shape.
     * - The ladder: COMPOUND_MEMBRANE struck by THUMP SNARE at HIT 0, .25, .5,
     *   .75 and 1. Each clip is the render its recipe gives
     *   (`TerraPatch.render`), so at the floor default. HIT 0 is a striker at
     *   strength 0, today's drum.
     * - The floor: TUNED_BAR today, then at HIT 1 struck by the factory kick
     *   (A01_Kick_01.wav, a dark head) at each of [Terra.HIT_FLOOR_CHOICES].
     *   These render through `Terra.renderStruck`'s floor overload, because a
     *   recipe carries no floor.
     * Each floor clip's per-mode levels against today's are measured on the
     * bank, before the output chain (which renormalises). They are printed
     * and written into the clip's line. Each floor's held tone must differ
     * from the next one's by at least 0.5 dB on some mode, or the second
     * question has one answer. (Their first moments always differ, because
     * `|P_k|` rises through the head under every floor, so a sample check
     * would prove nothing.)
     */
    private fun renderR1B(root: File) {
        val dir = File(root, "R1B").apply { mkdirs() }
        val sources = TerraStrikers.ten(File(root.parentFile, "Expansions/SnipSnap Factory/Samples")).associateBy { it.id }
        fun head(id: String): FloatArray {
            val s = sources.getValue(id)
            return Terra.captureStriker(s.snip) ?: error("${s.name} captured as silence")
        }
        class R1BClip(val id: String, val label: String, val why: String, val snip: Snip)

        class Step(val hit: Float, val shown: String, val tag: String, val why: String)
        val snare = head("tsnare")
        val snareName = sources.getValue("tsnare").name
        val membrane = TerraPatch("Membrane", TerraVoice.COMPOUND_MEMBRANE, emptyMap())
        val ladder = listOf(
            Step(0f, "0", "hit0", "the ladder's foot: today's drum"),
            Step(0.25f, ".25", "hit025", "a quarter"),
            Step(0.5f, ".5", "hit05", "subtle"),
            Step(0.75f, ".75", "hit075", "three quarters"),
            Step(1f, "1", "hit1", "strong"),
        ).mapIndexed { i, st ->
            R1BClip(
                "%02d_membrane_%s_thump_snare".format(i + 1, st.tag),
                "MEMBRANE $DOT HIT ${st.shown} $DOT THUMP SNARE",
                st.why,
                membrane.copy(striker = TerraPatch.Striker(snare, st.hit, snareName)).render(),
            )
        }

        class Floor(val phi: Float, val label: String, val tag: String, val why: String)
        val floors = listOf(
            Floor(0f, "NO FLOOR", "no_floor", "R1's HIT under a dark head"),
            Floor(0.125f, "FLOOR -18 DB", "floor_18db", "a floor at -18 dB"),
            Floor(0.25f, "FLOOR -12 DB", "floor_12db", "a floor at -12 dB, the provisional default"),
            Floor(0.5f, "FLOOR -6 DB", "floor_6db", "a floor at -6 dB"),
        )
        check(floors.map { it.phi } == Terra.HIT_FLOOR_CHOICES) { "the page's floors are not Terra.HIT_FLOOR_CHOICES" }
        val bar = TerraVoice.TUNED_BAR
        val kick = head("kick01")
        val body = TerraMeasure.bodyOf(bar)
        val x = Terra.upsample(kick)
        val today = TerraMeasure.modeLevels(Terra.bankWith(bar, emptyMap(), null), body, TerraMeasure.MODE_FROM_FRAMES, TerraMeasure.MODE_WINDOW)
        val held = floors.map { f -> TerraMeasure.modeLevels(Terra.bankStruckAt(bar, emptyMap(), x, 1f, f.phi), body, TerraMeasure.MODE_FROM_FRAMES, TerraMeasure.MODE_WINDOW) }
        val floorClips = floors.mapIndexed { i, f ->
            val struck = held[i]
            val levels = today.indices.joinToString(" / ") { k -> "%+.1f".format(20.0 * log10(maxOf(struck[k] / today[k], 1e-9))) }
            println("terra R1B floor ${f.phi} (${f.label}): bar modes 1-${today.size} at $levels dB against today's, on the bank before the output chain")
            R1BClip(
                "%02d_bar_hit1_factory_kick_%s".format(7 + i, f.tag),
                "BAR $DOT HIT 1 $DOT FACTORY KICK $DOT ${f.label}",
                "${f.why}; modes 1-${today.size} at $levels dB against today's (the bank, before the output chain)",
                Terra.renderStruck(bar, emptyMap(), kick, 1f, f.phi),
            )
        }
        // Two floors always differ in the first moments, where |P_k| is still rising
        // under every floor, so the samples prove nothing. The held tone must differ:
        // at least one mode 0.5 dB apart between each floor and the next.
        for (i in 0 until floors.size - 1) {
            val apart = today.indices.maxOf { k -> abs(20.0 * log10(maxOf(held[i + 1][k], 1e-12) / maxOf(held[i][k], 1e-12))) }
            check(apart >= 0.5) {
                "${floors[i].label} and ${floors[i + 1].label} hold every bar mode within ${"%.2f".format(apart)} dB of each other: the floor never bites on the held tone under the factory kick"
            }
        }

        val clips = ladder + R1BClip("06_bar_today", "BAR $DOT TODAY", "the floor's anchor", Terra.render(bar)) + floorClips
        val questions = listOf(
            "Can you hear the steps from 0 to 1 on the ladder?",
            "Which floor keeps the bar sounding right with a dark hit: none, -18, -12 or -6 dB?",
        )
        check(clips.size <= 10 && questions.size <= 3) { "a gate page leads with at most ten clips and three questions" }
        val entries = clips.map { c ->
            WavWriter.write(File(dir, "${c.id}.wav"), AuditionLevel.level(c.snip), WavWriter.BitDepth.PCM_16)
            "{\"id\":${q(c.id)},\"file\":${q("${c.id}.wav")},\"label\":${q(c.label)},\"why\":${q(c.why)}}"
        }
        File(dir, "manifest.json").writeText(
            "{\"page\":\"R1B\",\"title\":\"HIT'S STEPS AND ITS FLOOR\",\"clips\":[\n" + entries.joinToString(",\n") +
                "\n],\"questions\":[\n" + questions.joinToString(",\n") { q(it) } + "\n]}\n",
        )
        println("terra R1B: ${clips.size} clips and ${questions.size} questions under ${dir.absolutePath}")
    }
```

- [ ] **Step 3: Register the Gradle task**

In `synth/build.gradle.kts`, after the `tasks.register<JavaExec>("generateTerraR1Audition") { … }` block (ending at line 343), add:

```kotlin

/** Render TERRA R1b's listening clips (HIT's ladder and its floor) and manifest under testkit/terra-audition/R1B/. See TerraAuditionGenerator.renderR1B. */
tasks.register<JavaExec>("generateTerraR1BAudition") {
    group = "distribution"
    description = "Render the TERRA R1b (HIT's ladder and floor) listening clips and manifest under testkit/terra-audition/R1B/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/terra-audition", "r1b")
}
```

- [ ] **Step 4: Run it**

Run: `./gradlew --no-daemon :synth:generateTerraR1BAudition`, then `echo "exit=$?"; ls testkit/terra-audition/R1B/; grep -c '"id"' testkit/terra-audition/R1B/manifest.json`
Expected:
- `exit=0`;
- four `terra R1B floor` lines, the per-mode levels of the four floor clips, then `terra R1B: 10 clips and 2 questions`;
- ten `.wav` files and `manifest.json` in the listing;
- the count `10`.

Keep the four `terra R1B floor` lines for the commit message and the page. They should agree with Task 1's `TERRA HIT floor` lines to the printed digit: the same bank, head and window. Plan review read +2.6 / −39.7 / −46.3 / −57.3, then +2.6 / −18.1 / −18.1 / −18.1, then +2.6 / −12.0 / −12.0 / −12.0, then +2.6 / −6.0 / −6.0 / −6.0 dB. At any non-zero floor, modes 2 to 4 sit exactly at φ, so clips 8 to 10 differ in one flat overtone level.

Clip 5 (the membrane struck by THUMP SNARE at HIT 1) is not R1's clip 3, though it has the same voice, striker and HIT. It renders under the floor default, which removes the soft attack the owner heard at HIT 1 on R1's page (the membrane's first-5-ms peak mean over the ten strikers: 1.000 at the default, 0.815 on R1's). The orchestrator should not present the ladder's top rung as R1's clip.

Do not re-run `:synth:generateTerraR1Audition`. Its struck clips now render under the floor, and they would overwrite the page the owner answered.

- [ ] **Step 5: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt synth/build.gradle.kts
git commit -m "The TERRA R1b listening page: HIT's steps on one drum, and four floors for the bar" -m "Renders under testkit/terra-audition/R1B/ with a manifest of its own: the
membrane struck by a snare at HIT 0, .25, .5, .75 and 1, then the bar today
and struck at HIT 1 by the factory kick with no floor and with floors at -18,
-12 and -6 dB. Each floor clip's per-mode levels against today's are printed
and written into its line. Two questions: whether the steps can be heard, and
which floor keeps the bar right under a dark hit." -m "Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

Before running it, add one more `-m "..."` argument, before the `Claude-Session` one, that holds the four `terra R1B floor` lines Step 4 printed.

- [ ] **Step 6: Run the whole JVM suite, and stop for R1b's gate**

Run: `./gradlew --no-daemon test`, then `echo "exit=$?"`
Expected: `exit=0`. In a session with an Android SDK, run `./gradlew --no-daemon test -x :app:test`.

Do not push, open a PR or merge. Report to the orchestrator:
- the three commit hashes;
- Task 1's printed floor and coupling lines, and the controller's ruling on test 8 as applied;
- Task 2's floored drive table beside R1's;
- the path `testkit/terra-audition/R1B/manifest.json`;
- the table under "Before execution: a controller ruling is needed", re-read from this run where the tests print it: −6 dB fails the bell's coupling bar (2.50 dB against 3) and the tanh cap (0.3661 against 0.36), and −18 dB fails test 8 unless the ruling covers it. The controller decides whether the page ships with all four choices;
- that clip 5 is not R1's clip 3 (the floor removes the soft attack at HIT 1), and that the page auditions the floor at HIT 1, not at subtle, where the owner's complaint arose.

**Stop here for R1b's gate.** The orchestrator publishes the page from `testkit/terra-audition/R1B/`. The owner's two answers decide what happens next:
1. **The ladder.** "Yes": HIT's knob stands as built. "No": the knob's spacing becomes an owner question before R3, on a page of its own, not a guess.
2. **The floor.** The chosen φ replaces 0.25 in `Terra.HIT_FLOOR`, and `HIT_FLOOR_CHOICES` leaves the code. Change the floor-default test's pin. Measure Task 2's floored table again at the new value by Task 2 Step 1 (if the answer is "none", that table becomes R1's). Re-run Task 1 Step 6's claims. "None" sets the floor to 0, which is R1's HIT bit for bit. Measured by plan review, two answers reopen spec claims, and neither is re-thresholded here:
   - **−6 dB (0.5)** fails the bell's coupling bar (2.50 dB against 3 dB) and the cavity's tanh cap (0.3661 against 0.36, THUMP KICK at HIT 1). Picking it reopens those two claims, and test 6's tanh assertion fails at the new default. Report and STOP for the controller; do not loosen either.
   - **−18 dB (0.125)** fails test 8 on the cavity × BEATBOX RIM (steps +0.005, −0.017 dB; total move 0.425 dB). Option (a) as worded (a total move under 0.2 dB) does not cover it, so it needs the controller's ruling again unless the ruling already covers it.

R2 may run alongside. R3 (BEND and TALK) and R4 (the group and the chooser) do not start before this verdict.

---

## Self-review

- **Spec and ruling coverage.**

  | Spec / ruling | Where |
  |---|---|
  | The floor formula, `s` from the unfloored `|P_k|`, the floor after it | Task 1 Step 5; pinned by the exact level-curve check in Step 3 and shown to fail in Step 7 |
  | φ = 0 is R1's HIT bit for bit (a test pins it) | Task 1, `a floor of 0 is R1's HIT bit for bit`, against the frozen `LegacyTerraHit` |
  | `c = 0` stays the null path; the 40-case guard unchanged | Task 1, `HIT 0 at every floor…`; `TerraFrozenTest` runs untouched in Step 6 |
  | φ on all four voices, biting only below it | `hitLevel` serves every voice; the floor-0 test covers all four, and the floor test reads the bar, where it bites |
  | Default 0.25, provisional; the four choices | `Terra.HIT_FLOOR`, `Terra.HIT_FLOOR_CHOICES`, pinned in Task 1 |
  | The internal overload taking φ | `renderStruck`, `renderStruckAt` and `bankStruckAt`'s `hitFloor` (Task 1); used by Task 3 |
  | Every bar mode at HIT 1 under the dark kick head ≥ φ × today, within a stated tolerance | Task 1: exact on the level curves, and 0.5 dB on the bank |
  | The coupling claim at the floor default | R1's coupling test, unchanged, run in Task 1 Step 6 with a stop rule |
  | Test 6 pinned to "BUZZ follows the striker", ±20 %, recomputed with the floor | Task 2 |
  | R1b's page: five ladder clips, the bar today, four floors, two questions, the per-mode levels printed | Task 3 |

- **Placeholders.** Every code block is complete and compiles as written. Task 2's `floored` table and its KDoc list hold the figures plan review measured at the default, and Task 2 Step 1 confirms them on the built code; nothing is filled in by hand. The one open item is not a placeholder but a gate: test 8's handling waits on the controller's ruling ("Before execution: a controller ruling is needed"), and the plan writes no code for it until then. Each commit that should carry printed figures says so in a sentence after its command.
- **Type consistency.** `HIT_FLOOR`, `HIT_FLOOR_CHOICES`, `hitLevel`'s `hitFloor`, and the overloads of `renderStruck`, `renderStruckAt` and `bankStruckAt` come from Task 1, as do `LegacyTerraHit.level`, `TerraMeasure.modeLevels`, `MODE_FROM_FRAMES` and `MODE_WINDOW`. Every other symbol was read at `58757f10`:
  - `Terra.Body` and `Terra.BankInputs(level = …)` (internal classes with public constructors);
  - `Terra.renderWith(voice, macros, inputsFor)` and `Terra.strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, exciterAt, onsetSamples = 0, level = null, pitch = null)`;
  - `Terra.upsample`, `Terra.captureStriker`, `Terra.defaults`, `Terra.render(voice, macros)`;
  - `TerraPatch(name, voice, macros, striker)`, `.copy(striker = …)` and `TerraPatch.Striker(head, hit, from)`;
  - `TerraStrikers.ten(dir)`, `TEN`, `head(id)`; `TerraCases.all` with `label`, `voice` and `macros`; `LegacyTerraBank.render`;
  - `TerraMeasure.bodyOf`, `BODY_FROM_FRAMES`, `cavityStage`, `msAbove`, `tanhInput`;
  - `Dsp.RATE` and `Dsp.OVERSAMPLE` (both `const`);
  - `TerraTest`'s `impulse` and `full`;
  - the generator's `DOT`, `q`, `AuditionLevel.level` and `WavWriter.write`.
  
  `kotlin.math.floor` is not imported in `Terra.kt`, so the local `val floor` shadows nothing.
- **Review Focus.** Each line's pinning test is in the task that owns the code: φ = 0, `s` before the floor, and `c = 0` in Task 1; coupling in Task 1 Step 6; test 6 in Task 2.
- **Line numbers** are the head's (`58757f10`). Every edit also quotes the text it replaces.
- **What R1b does not decide.** The floor's value is the owner's, on R1b's page. How test 8 treats the cavity × BEATBOX RIM curve at a non-zero floor is the controller's. Plan review measured the bell's coupling gap at the default as 3.76 dB (3.87 at a floor of 0), so it survives 0.25; at 0.5 it is 2.50 dB and fails. If a claim fails, the plan stops rather than loosening it.
- **Plan review (2026-10-01), applied.**
  - The blocker (test 8 fails at the default on RESONANT_CAVITY × BEATBOX RIM): a notice before Task 1, "Before execution: a controller ruling is needed", with the measured table over the four choices. Task 1 Step 6 now expects that failure as the STOP, with its message and figures, instead of exit code 0.
  - −6 dB breaks the bell's coupling bar and the tanh cap: in the notice's table, in Task 2 Step 1 (with the 0.3598 against 0.36 margin at the default), in test 6's KDoc, in Task 3's report to the orchestrator, and in Task 3's post-gate step 2.
  - The `floored` table: the six measured rows are written into the code and the KDoc's `Measured` list. Task 2 Step 1 is now a confirm step, and the hand-fill instruction and the illustrative example are gone. Task 2 Step 5 records the measured 87.8 ms.
  - The floor removes, not shortens, decision 4's soft attack at HIT 1: in Global Constraints, Task 1 Step 6's expected prints, Task 1 Step 8's commit instruction, and Task 3 (clip 5 is not R1's clip 3; the floor is auditioned at HIT 1, not at subtle). The probe figures for the cavity, bell and bar, and the 4-in-40 class changes, are labelled as plan review's probe, because test 8 throws before printing them.
  - The floor test's premise is now `lowest < smallest * tolerance` (about 0.118), which matches the generator's 0.5 dB check. The test's KDoc and Step 6's stop rule say so.
  - The T60 bullet is gone from Task 1 Step 1's expected diff. `T60_NEPERS_DOUBLE` sits at Terra.kt line 838, outside the diffed range 891–947, and before `fun level` in the copy, so it is in neither side.
  - Stale forecasts of the coupling gaps (Global Constraints, Review Focus, Step 6's coupling stop rule, this list) are replaced with the measured 5.83 / 12.65 / 3.76 / 4.38 dB.
- **Plan review, skipped, and why.**
  - Choosing option (a) or (b) for test 8, and any code for either: the fix asks for a controller ruling before execution. Code for (b) would pick a span fraction, which is a re-threshold this plan forbids. Code for (a) would pre-empt the ruling, so (a) is described, not written. The notice records that (a) as worded covers 0.25 but not 0.125.
  - Dropping 0.5 from `HIT_FLOOR_CHOICES` and from the page: that changes the controller's ruling, which lists all four choices. The plan keeps 0.5 and records that picking it reopens the two claims.
  - The spec's own wording that the floor "shortens" the soft attack ("HIT, the design", the floor bullet): this fix edits only the plan, so the spec's wording is left for the controller.
  - Writing the flat-overtone shape into the floor clips' `why` lines (finding 5's optional part): the page text is the controller's ruling, and each line already prints the four per-mode levels, which show it. It is noted in Task 3 Step 4 and in the notice instead.
  - The review notes on what is sound (finding 7): no action was asked. The worktree code was not touched.
