# RESIN, Held — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Design:** [`docs/superpowers/specs/2026-09-25-resin-held-pad-design.md`](../specs/2026-09-25-resin-held-pad-design.md). Read it first. This plan does not repeat its reasoning, only its decisions and their numbers.

**Goal:** A RESIN patch becomes a keys instrument that sounds for as long as a key is held: nine zones, each rendered at exact pitch and sustaining, each looped at a seam that is a whole number of every waveform's cycles. It is reachable from the CLI (`synth --instrument`) and from the SYNTH screen (`MAKE INSTRUMENT ▸`).

**Architecture:** A held branch in `Resin.synthesize` (the one-shot path stays byte-identical). `Keys.resinPad` does the loop cut, the Organ's method generalized with a snapped detune. `ResinPadMaker` in `:shell` packages nine `KeyNote`s through `OneNote.writePackage`, the shop's one packaging door. The phone's player, the package writers and `InstrumentStore` do not change.

**Tech Stack:** Kotlin/JVM 2.0.21, Gradle wrapper, `kotlin.test`, Compose (the app task only). No new dependencies.

## How to hand this to a session

```
Implement docs/superpowers/plans/2026-09-25-resin-held-pad.md task by task,
on branch claude/sound-design-tools-mdmrbw (restart it from the default
branch, claude/mobile-mpc-drum-sampler-t58x74, if its PR has merged). Read
the design spec it links first, and .claude/skills/steward/SKILL.md before
your first push. Commit after every task with the message the plan gives.
Run ./gradlew --no-daemon test before every push. Open one PR when Task 9's
verification is green.
```

## Running tests here

- Fast loop: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.ResinHeldTest'` (and likewise `:shell:test`, `:cli:test`).
- Before every push: `./gradlew --no-daemon test`. It takes about 16 minutes in a cloud session; run it with the Bash tool's own `run_in_background`, **never** with an inline `&` as well (that orphans the Gradle process; see the RESIN session's notes).
- `:app` is not in this environment's Gradle graph (no Android SDK). Task 7 is proved only by CI's `android-build` and `emulator-tests`. Re-read that diff twice before pushing it.

## Global constraints

- **Measure, never guess; never loosen a threshold blind.** Every threshold below comes from the spec's probe. If a test misses, print the measured value, find out why, and re-derive with margin. Do not widen the bound until it passes.
- **The one-shot render must not move by a single bit.** Task 1 pins it before anything else is touched.
- **No silent crossfade.** A seam that doesn't close is an error naming the zone, never a baked fade.
- **Naming guardrail** (`docs/SYNTH_ROADMAP.md`, "sound yes, names never"): no trademarked names or model numbers in code, strings, presets or commit messages.
- Commit messages follow the house style (a sentence about the *why*, e.g. "RESIN's macros keep their promises: …"), ending with the session's attribution lines.

## File structure

| File | Change |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Resin.kt` | `Held`, `HELD_MAX_RESONANCE`, `resonanceFor`, a `held` parameter on `synthesize`, `renderHeld` |
| `synth/src/main/kotlin/com/snipsnap/synth/Keys.kt` | `resinPad`, `resinPadMidis`, `seamError`, and the internal loop planner |
| `synth/src/test/kotlin/com/snipsnap/synth/ResinHeldTest.kt` | new: the held render and the loop cut |
| `synth/src/test/kotlin/com/snipsnap/synth/ResinTest.kt` | + the one-shot pin (Task 1) |
| `kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt` | `writePackage`: `internal` → public |
| `shell/src/main/kotlin/com/snipsnap/shell/ResinPadMaker.kt` | new: zones → program → package; preview |
| `shell/src/test/kotlin/com/snipsnap/shell/ResinPadMakerTest.kt` | new |
| `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt` | `--instrument`, `--attack`, `--release` |
| `cli/src/test/kotlin/com/snipsnap/cli/SynthCommandTest.kt` | + two tests |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | `MAKE INSTRUMENT ▸` and its sheet (RESIN only) |
| `README.md`, `docs/SYNTH_ROADMAP.md`, `testkit/README.md` | Task 8 |

---

### Task 1: Pin the one-shot before touching it

**Why first:** every RESIN preset, every RESIN pad in a `kit.json` and every determinism test depends on `Resin.render` staying exactly as it is. `DeterminismTest` only proves two renders *agree with each other*, not that they agree with *yesterday*. This pin does.

- [ ] **Step 1: Capture the values.** Add a temporary test to `ResinTest.kt` that prints, for each voice, `Resin.render(voice).samples.contentHashCode()`, plus the same for the first factory preset of each voice (`ResinPresets.forVoice(v).first().macros`). Run it (`--info` shows stdout, or write the numbers to a file in the scratchpad) and record the six integers. `FloatArray.contentHashCode()` hashes `floatToIntBits`, so it is exact and stable across JVMs.
- [ ] **Step 2: Replace the printer with the pin:**

```kotlin
@Test
fun `the one-shot render is pinned - held work must not move it by a bit`() {
    // Captured on the default branch before the held branch existed
    // (docs/superpowers/plans/2026-09-25-resin-held-pad.md, Task 1).
    val expected = mapOf(
        ResinVoice.BASS to Pair(/* defaults */ 0, /* first preset */ 0),
        ResinVoice.LEAD to Pair(0, 0),
        ResinVoice.BRASS to Pair(0, 0),
    )
    for ((v, hashes) in expected) {
        assertEquals(hashes.first, Resin.render(v).samples.contentHashCode(), "$v defaults moved")
        val preset = ResinPresets.forVoice(v).first()
        assertEquals(hashes.second, Resin.render(v, preset.macros).samples.contentHashCode(), "$v '${preset.name}' moved")
    }
}
```

  Fill in the six captured integers, and delete the printer.
- [ ] **Step 3:** `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.ResinTest'` should pass.
- [ ] **Step 4: Commit:** "Pin RESIN's one-shot render before a held branch goes in beside it".

---

### Task 2: The held render

**Files:** `Resin.kt`, new `ResinHeldTest.kt`.

- [ ] **Step 1: Write the failing tests** in `ResinHeldTest.kt`:
  - `a held note rises over its ATTACK then stays flat`: `renderHeld(BRASS, defaults, Held(attackSeconds = 1f, seconds = 4f))`. The RMS over 0.20–0.30 s is at least 6 dB below the RMS over 2.5–3.5 s. The RMS over 1.2–1.7 s is within 1 dB of the RMS over 3.0–3.5 s. Measure RMS in 50 ms windows *after* the CONTOUR sweep has landed; use `CONTOUR 0` for this test so the filter is still.
  - `a held note does not decay`: `Held(0.01f, 6f)` at DECAY 0 (the shortest one-shot decay). The RMS over 5.0–5.5 s is within 1% of the RMS over 2.0–2.5 s.
  - `CREAM tops out at the threshold when held`: `Resin.resonanceFor(1f, held = true) == 4f` and `Resin.resonanceFor(1f, held = false) == Dsp.Ladder.MAX_RESONANCE`.
  - `held renders are deterministic`: two `renderHeld` calls, `contentEquals`.
  - `Held refuses an attack outside its range`: `Held(0f, 4f)` and `Held(3f, 4f)` throw `IllegalArgumentException`.
- [ ] **Step 2:** Run them and confirm they fail to compile (`Unresolved reference: renderHeld / Held / resonanceFor`).
- [ ] **Step 3: Implement in `Resin.kt`.** Everything below goes **inside `object Resin`**. Later tasks call `Resin.Held`, `Resin.renderHeld`, `Resin.ATTACK_MIN_SECONDS` and `Resin.HELD_MAX_RESONANCE`.

```kotlin
/**
 * A held render (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md):
 * the amp rises over [attackSeconds] and then holds flat for the whole
 * [seconds]; [squareRatio], when set, replaces osc 3's detune with an
 * exact ratio so a loop can close on a whole beat (Keys.resinPad). Null
 * keeps the one-shot's own detune.
 */
internal data class Held(val attackSeconds: Float, val seconds: Float, val squareRatio: Double? = null) {
    init {
        require(attackSeconds in ATTACK_MIN_SECONDS..ATTACK_MAX_SECONDS) {
            "attack wants $ATTACK_MIN_SECONDS..$ATTACK_MAX_SECONDS s, got $attackSeconds"
        }
        require(seconds > attackSeconds) { "a held note must outlast its attack: $seconds <= $attackSeconds" }
    }
}

const val ATTACK_MIN_SECONDS = 0.01f
const val ATTACK_MAX_SECONDS = 2.5f

/**
 * Held renders stop at the ladder's self-oscillation threshold, not past
 * it: a self-oscillating ladder rings at its own frequency, which no loop
 * of the note's period closes (measured: BASS A1 at r 4.3 seams at 7.9e-3,
 * eight times the Organ's bar). CONTOUR caps at 4 for its own reason.
 */
const val HELD_MAX_RESONANCE = 4f

internal fun resonanceFor(cream: Float, held: Boolean): Float =
    Dsp.lin(cream, 0f, if (held) HELD_MAX_RESONANCE else Dsp.Ladder.MAX_RESONANCE)
```

  In `synthesize`, add `held: Held? = null` as the last parameter. Change exactly these lines, and nothing else:

```kotlin
val resonance = resonanceFor(cream, held != null)                       // was Dsp.lin(cream, 0f, Dsp.Ladder.MAX_RESONANCE)
val out = FloatArray(
    if (held == null) (t60 * 1.4f * rate).toInt().coerceAtLeast(64)
    else (held.seconds * rate).toInt(),
)
val env = if (held == null) Dsp.Env(attackSeconds = 0.003f, decay2T60 = t60)
          else Dsp.Env(attackSeconds = held.attackSeconds, decay2T60 = t60, holdSeconds = held.seconds)
// The one-shot's increment is the same Float expression as before, widened
// once; a snapped ratio stays Double so the loop closes on the beat.
val inc3: Double = held?.squareRatio?.let { base * it / rate } ?: (base * detune / rate).toDouble()
// ... in the loop:
p3 += inc3                                                              // was p3 += base * detune / rate
```

  `resonanceFor(cream, false)` is the identical expression, and `inc3` on the one-shot path is the identical Float value. Task 1's pin proves both.

  Add `renderHeld`: decimate only, with no leveling and no `fadeTail`. `Keys.resinPad` levels on the loop after the cut (spec, decision 10).

```kotlin
internal fun renderHeld(voice: ResinVoice, macros: Map<String, Float>, held: Held): Snip {
    val out = Dsp.decimate(synthesize(voice, macros, RATE * Dsp.OVERSAMPLE, held), RATE)
    return Snip(out, channels = 1, sampleRate = RATE)
}
```

- [ ] **Step 4:** `ResinHeldTest` passes, and so does **Task 1's pin** (`ResinTest`). If the pin fails, the edit touched the one-shot path. Find which line; don't re-capture.
- [ ] **Step 5: Commit:** "RESIN can hold a note: a flat amp after a chosen attack, CREAM stopping at the threshold".

---

### Task 3: The loop cut — `Keys.resinPad`

**Files:** `Keys.kt`, `ResinHeldTest.kt`.

- [ ] **Step 1: Write the failing tests:**
  - `every zone lands its pitch`: for each voice, zones 0 and 8 (`Keys.resinPadMidis(voice).first()/last()`): `Pitch.detect(note.snip)` within 2% of `Keys.midiHz(midi)`, octave-folded. Copy `assertPitched` from `InstrumentSuiteTest`.
  - `every zone's seam closes`: 3 voices × 9 zones at defaults, then `Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < 1e-3`. The loop runs from the loop start to the end of the sample, so the length is implied.
  - `the measured worst corner closes`: BASS, lowest zone, `CUTOFF 0, CREAM 1, CONTOUR 1, DECAY 1`, at STACK 0.3, 0.6 and 1.0, and again with `attackSeconds = 2.5f`. Seam < 1e-3. (Probe: ≤ 7.1e-5 at loop start 1.5 s.)
  - `the square is snapped to one whole beat per loop`: at STACK 0.8, `Keys.planLoop(baseHz, stack)` returns `k`, `loopFrames == round(k × 2 × RATE / baseHz)`, `squareRatio == 1 + 1/(2k)` exactly, and the snapped cents are within 2 of `Dsp.lin(0.8f, 3f, 14f)`.
  - `no square, no snap`: at STACK 0.3, `planLoop` returns `squareRatio == null` and a loop within 0.1 s of 0.5 s.
  - `the loop starts after the attack has settled`: `loopStartFrame == round((attack + 1.5) × RATE)` for attack 0.01 and 2.0.
  - `a zone's loop is as loud as its neighbours`: the loop-region `Loudness.of` of every zone in a voice is within 1 dB of the median.
  - `a seam that does not close is refused by name`: `Keys.requireSeam("BRASS A2", samples, loopStart)` on a deliberately misaligned loop (loop length off by half a period) throws, with "BRASS A2" in the message.
  - `resinPad refuses a note outside the voice`: MIDI root − 1 and root + 25 throw.
- [ ] **Step 2:** Confirm they fail to compile.
- [ ] **Step 3: Implement in `Keys.kt`.** The planner's arithmetic is the probe's, as it ran (spec, appendix):

```kotlin
/** The nine RESIN pad zones: every minor third across the voice's TUNE range (A1–A3, A2–A4, A3–A5). */
fun resinPadMidis(voice: ResinVoice): List<Int> {
    val root = Scales.hzToMidi(Resin.frequencyFor(voice, 0f)).roundToInt()
    return (0..Resin.TUNE_SEMITONES step 3).map { root + it }
}

/** Seconds the ladder is given to settle after the attack before the loop may start (spec: measured worst 7.1e-5 here). */
const val RESIN_PAD_SETTLE_SECONDS = 1.5f

internal data class LoopPlan(val loopFrames: Int, val squareRatio: Double?, val k: Int)

/**
 * The loop length, and the square's ratio if it sounds. With the square
 * silent, the Organ's rule: whole sub-octave periods near half a second.
 * With it sounding: K sub-periods and a ratio of exactly 1 + 1/(2K), so
 * the square completes 2K + 1 cycles, one whole beat per loop. K is searched
 * ±10% around the asked detune for the length nearest a whole frame.
 */
internal fun planLoop(baseHz: Float, stack: Float): LoopPlan {
    val period = 2.0 * RATE / baseHz
    fun nearestWhole(range: IntRange): Int = range.filter { it >= 8 }.minBy { abs(it * period - Math.round(it * period)) }
    val g3 = Resin.stackGains(stack).second
    if (g3 <= 0f) {
        val n0 = (0.5 * RATE / period).roundToInt()
        val n = nearestWhole(n0 - 40..n0 + 40)
        return LoopPlan(Math.round(n * period).toInt(), null, n)
    }
    val cents = Dsp.lin(stack, 3f, 14f)
    val k0 = (1.0 / (2.0 * (2.0.pow(cents / 1200.0) - 1.0))).roundToInt()
    val k = nearestWhole((k0 * 0.9).toInt()..(k0 * 1.1).toInt())
    return LoopPlan(Math.round(k * period).toInt(), 1.0 + 1.0 / (2.0 * k), k)
}

/** InstrumentSuiteTest's seam metric: difference energy across the wrap over signal energy, the 256 frames before [loopStart]. */
fun seamError(s: FloatArray, loopStart: Int): Double {
    val loopLen = s.size - loopStart
    var diff = 0.0
    var level = 0.0
    for (i in loopStart - 256 until loopStart) {
        val d = s[i + loopLen] - s[i].toDouble()
        diff += d * d
        level += s[i].toDouble() * s[i]
    }
    return diff / level
}

const val MAX_SEAM_ERROR = 1e-3   // the Organ's bar

internal fun requireSeam(label: String, s: FloatArray, loopStart: Int) {
    val e = seamError(s, loopStart)
    require(e < MAX_SEAM_ERROR) { "$label: the loop does not close (seam %.2e, bar %.0e)".format(java.util.Locale.ROOT, e, MAX_SEAM_ERROR) }
}

/**
 * RESIN held down — one pad zone at exact MIDI pitch, cut at a seam that is
 * a whole number of every waveform's cycles
 * (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md).
 */
fun resinPad(voice: ResinVoice, macros: Map<String, Float>, midi: Int, attackSeconds: Float): KeyNote {
    val midis = resinPadMidis(voice)
    val semis = midi - midis.first()
    require(semis in 0..Resin.TUNE_SEMITONES) { "$voice pads are MIDI ${midis.first()}..${midis.first() + Resin.TUNE_SEMITONES}, got $midi" }
    val m = Resin.defaults(voice) + macros.filterKeys { it in Resin.defaults(voice) } + ("TUNE" to semis / Resin.TUNE_SEMITONES.toFloat())
    val base = Resin.frequencyFor(voice, m.getValue("TUNE"))
    val plan = planLoop(base, m.getValue("STACK"))
    val label = "$voice ${Scales.nameOf(midi)}"

    fun cut(settle: Float): KeyNote {
        val loopStart = ((attackSeconds + settle) * RATE).roundToInt()
        val end = loopStart + plan.loopFrames
        // A quarter second past the cut, so the decimator's edge never reaches it.
        val held = Resin.Held(attackSeconds, end.toFloat() / RATE + 0.25f, plan.squareRatio)
        val s = Resin.renderHeld(voice, m, held).samples.copyOf(end)
        requireSeam(label, s, loopStart)
        // Loud where it is held: the loop, not the attack, sets the level (spec decision 10).
        val loud = Loudness.of(Snip(s.copyOfRange(loopStart, end), 1, RATE))
        if (loud > 1e-6f) { val g = Dsp.MELODIC_LOUDNESS_TARGET / loud; for (i in s.indices) s[i] *= g }
        Dsp.limitPeak(s, 0.99f)
        return KeyNote(Snip(s, channels = 1, sampleRate = RATE), loopStartFrame = loopStart.toLong())
    }
    // One retry with twice the settle; the probe says it never fires.
    return runCatching { cut(RESIN_PAD_SETTLE_SECONDS) }.getOrElse { cut(RESIN_PAD_SETTLE_SECONDS * 2f) }
}
```

  Notes for the implementer:
  - `Held` is nested in `Resin` only if you declared it there; adjust `Resin.Held` to wherever Task 2 put it. It is `internal`, and `Keys` is in the same module.
  - `Scales`, `Loudness` and `Snip` come from `com.snipsnap.audio`. Add the imports.
  - If `LOUDNESS_OFFSET` ever becomes non-zero, the target here should add it. It is private and all-zero today; leave a one-line note rather than a speculative parameter.
  - The retry's second failure propagates, naming the zone (the spec's "no silent crossfade").
- [ ] **Step 4:** All of `ResinHeldTest` passes, and Task 1's pin still does. If a seam misses, print it with the zone, compare against the spec's probe table, and find the cause. The bar stays at 1e-3.
- [ ] **Step 5: Commit:** "RESIN held down: nine zones cut at seams that are whole beats of every oscillator".

---

### Task 4: Open the packaging door

**File:** `kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt`.

- [ ] **Step 1:** `internal fun writePackage(` → `fun writePackage(`. Add one KDoc line above it: the shop's one packaging door, shared by MAKE INSTRUMENT, MAKE PAD, `keys`, `pad`, and `:shell`'s RESIN pads. Nothing else changes; the existing tests (`FreshNameTest`, `OneNoteTest`) cover it.
- [ ] **Step 2:** `./gradlew --no-daemon :kit:test` passes.
- [ ] **Step 3:** Commit it together with Task 5 (a visibility change with no caller is dead weight on its own).

---

### Task 5: `ResinPadMaker` — nine zones to a package

**Files:** new `shell/src/main/kotlin/com/snipsnap/shell/ResinPadMaker.kt` and `ResinPadMakerTest.kt`.

- [ ] **Step 1: Write the failing tests** (`ResinPadMakerTest`), using a temp dir as the shelf's instruments folder:
  - `nine zones tile the voice and root at the minor thirds`: `assemble` over `renderZone` for BRASS. `keygroups.size == 9`, zones tile (`highNote + 1 == next.lowNote`), `rootNote`s == `Keys.resinPadMidis(BRASS)`, the first `lowNote == root − 9` and the last `highNote == root + 24 + 9` (`InstrumentSuite.build`'s layout).
  - `every zone's loop survives into both generations and the phone's sidecar`: after `export`: the `.xpm` has `<SliceLoop>1</SliceLoop>` and `<SliceLoopStart>` equal to each zone's `loopStartFrame`; the `.xty` has `"LoopMode": 1`; `InstrumentStore.list(dir)` returns one instrument whose zones all have `loopStartFrame > 0`, and whose `release` equals the spec's.
  - `the phone's own player holds it and lets it go`: load the exported instrument into `InstrumentEngine.Loaded` (read each zone's WAV with `WavReader`), `noteOn(root + 12)`, render 5 s: the RMS of the last 100 ms is at least 50% of the RMS of 1.5–2.0 s. Then `noteOff`, render `release + 0.1` s, and the last 10 ms is silent (< 1e-4).
  - `a second export does not clobber the first`: two `export`s with the same base name via `OneNote.freshName` leave two packages.
  - `preview is the head and two passes of the loop`: `preview(spec).samples.size == loopStart + 2 × loopLen` of the middle zone, and the second pass equals the first sample-for-sample.
  - `attack and release are refused out of range`: `Spec(attackSeconds = 3f)` and `Spec(releaseSeconds = 2f)` throw.
- [ ] **Step 2:** Confirm they fail to compile.
- [ ] **Step 3: Implement.**

```kotlin
/**
 * RESIN, held — a RESIN patch as a keys instrument that sounds while held
 * (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md). Zones render
 * one at a time through [renderZone] so a caller can spread them over
 * threads and report progress; [assemble] and [export] never render.
 */
object ResinPadMaker {
    const val RELEASE_MIN_SECONDS = 0.1f
    const val RELEASE_MAX_SECONDS = 1.5f   // modest until the MPC's reading of larger values is heard (spec, open question 4)

    data class Spec(
        val voice: ResinVoice,
        val macros: Map<String, Float>,
        val attackSeconds: Float = 0.3f,
        val releaseSeconds: Float = 0.6f,
    ) {
        init {
            require(attackSeconds in Resin.ATTACK_MIN_SECONDS..Resin.ATTACK_MAX_SECONDS) { "attack wants ${Resin.ATTACK_MIN_SECONDS}..${Resin.ATTACK_MAX_SECONDS} s, got $attackSeconds" }
            require(releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) { "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds" }
        }
    }

    fun zoneMidis(spec: Spec): List<Int> = Keys.resinPadMidis(spec.voice)

    fun renderZone(spec: Spec, midi: Int): KeyNote = Keys.resinPad(spec.voice, spec.macros, midi, spec.attackSeconds)

    /** The zones, in [zoneMidis] order, as a program plus the samples it names. */
    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> { /* InstrumentSuite.build's layout; stems "${name without spaces}_${Scales.nameOf(midi)}" via Names.sanitizeStem */ }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The middle zone, head plus two passes of its loop: what a three-second hold sounds like. */
    fun preview(spec: Spec): Snip { /* renderZone(spec, zoneMidis(spec)[4]), then append the loop once more */ }
}
```

  `assemble` mirrors `InstrumentSuite.build` (a test-source file, so copy its twelve lines of zone layout rather than depend on it) with `volumeRelease = spec.releaseSeconds`, one layer per zone at `velStart = 0, velEnd = 127`, carrying `loopStartFrame`. Put `ATTACK_MIN_SECONDS` and `ATTACK_MAX_SECONDS` wherever Task 2 did, and reference them from there.
- [ ] **Step 4:** `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.ResinPadMakerTest'` and `:kit:test` pass.
- [ ] **Step 5: Commit** (with Task 4): "A held RESIN patch packages like every instrument the shop makes, and the phone's player holds it".

---

### Task 6: The CLI door — `synth --instrument`

**Files:** `SynthCommand.kt`, `SynthCommandTest.kt`.

- [ ] **Step 1: Write the failing tests:**
  - `a RESIN preset becomes a held instrument`: `synth RESIN BRASS --preset 1 --instrument --attack 0.5 --release 0.8 --out <tmp>` returns 0; `<tmp>` holds one `.xty`, one `_[TrackData]/` with nine WAVs, and one `.instrument.json` whose zones all loop. Stdout names the instrument and says `9 zones, each holds`.
  - `--instrument is refused for an engine that cannot hold`: `synth TINES BELL --instrument --out <tmp>` raises `CliError` with "RESIN" in the message (it says which engine can).
- [ ] **Step 2: Implement.** Add `--instrument` to `boolean` and `--attack`/`--release` to `valued`. With `--instrument`: refuse `--all` (one instrument per call); require `engine == "RESIN"`; parse the two floats with `CliError` on garbage; build `ResinPadMaker.Spec(ResinVoice.valueOf(voice), patch.macros, attack ?: 0.3f, release ?: 0.6f)` (the `Spec`'s own `require`s become `CliError`s via `runCatching`); render the zones sequentially, printing `zone 3/9 C#3`; name with `OneNote.freshName(dir, Names.sanitizeStem(patch.name))`; `export`. Without `--instrument`, the command is byte-for-byte what it was.
- [ ] **Step 3:** `:cli:test` passes.
- [ ] **Step 4: Listen.** Render one per voice into the scratchpad and report the file paths to the user for listening. The spec's placeholders are taste; they cannot be heard in this environment.
- [ ] **Step 5: Commit:** "snipsnap synth RESIN … --instrument: a held pad from the desk, before the phone has a button".

---

### Task 7: The phone door — `MAKE INSTRUMENT ▸` on the SYNTH screen

**File:** `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt`. **Proved only by CI.**

- [ ] **Step 1: The button.** Under the SCRAMBLE / SAVE PRESET / SEND TO PAD `Row` (around `SynthScreen.kt:605–637`), add a full-width `LabButton("MAKE INSTRUMENT ▸", …, accessibilityLabel = "MAKE INSTRUMENT")`, shown only when `engine == Engine.RESIN` (the `DELETED PRESETS ▸` button, `:582`, is the full-width precedent). Tapping sets `makingInstrument = true`.
- [ ] **Step 2: The sheet**, an overlay shaped like the SAVE AS PRESET naming overlay (`:900–960`) with `BackHandler` cancel:
  - NAME: defaults to `currentPresetByVoice[engine to voice]?.name ?: "RESIN ${voice.name}"`, made unique at write time with `OneNote.freshName(File(shelfRoot, KitShelf.INSTRUMENTS_DIR), …)`.
  - ATTACK and RELEASE: two `MacroSlider`s over 0..1, mapped with `Dsp.expMap` onto the `Spec` ranges, with readouts in seconds (`"0.30 s"`).
  - PREVIEW: `appScope.launch` → `withContext(Dispatchers.Default) { ResinPadMaker.preview(spec) }` → the screen's existing `audition(snip)`. Disabled while busy.
  - MAKE: `appScope.launch` (a write in flight survives a tab switch, per the screen's own KDoc on `appScope`). Render zones with `coroutineScope { midis.map { async(Dispatchers.Default) { ResinPadMaker.renderZone(spec, it).also { done.incrementAndGet() } } }.awaitAll() }`, update a `"RENDERING $done/9"` label, then `withContext(Dispatchers.IO) { KitWrites.mutex.withLock { ResinPadMaker.export(name, spec, notes, destRoot) } }`, then `onToast("$name · ON KEYS · HOLDS")`. Wrap it in `try/catch` the way `sendToSlot` does, and on failure toast the exception's message: it names the zone.
  - CANCEL while rendering cancels the job; nothing is written, since the write is after `awaitAll`.
- [ ] **Step 3:** If `app/src/androidTest/.../SynthScreenTest.kt` exists, add one case: select RESIN and `MAKE INSTRUMENT` is shown; select THUMP and it is gone. If it doesn't exist, don't create a suite in this PR; note it in the PR body.
- [ ] **Step 4:** Re-read the diff twice: imports (`ResinPadMaker`, `OneNote`, `KitShelf`, `Dsp`, `async`, `awaitAll`, `coroutineScope`), the RESIN-only gate, that no existing button moved, and `sendToSlot` untouched.
- [ ] **Step 5: Commit:** "MAKE INSTRUMENT on the SYNTH screen: a RESIN sound onto KEYS, held".

---

### Task 8: Document it

- [ ] `README.md`: one paragraph after the RESIN paragraph. RESIN held: nine zones, whole-beat seams, `MAKE INSTRUMENT ▸` on SYNTH, `snipsnap synth RESIN <VOICE> --instrument` on the desk. Keep the README's existing voice.
- [ ] `docs/SYNTH_ROADMAP.md`: a phasing row after S8: `| S8.1 | **shipped** — RESIN, held: a RESIN patch as a keys instrument that sounds while held (…) — design in docs/superpowers/specs/2026-09-25-resin-held-pad-design.md | S5 + S8 |`.
- [ ] `testkit/README.md`: a short "RESIN held pads — made with the CLI" section. How to make one (`snipsnap synth RESIN BRASS --preset 1 --instrument --release 1.5 --out …`) and what to check on hardware: held pads sustain with **no audible seam**, and a 1.5 s RELEASE *sounds* like a second and a half (spec, open question 4).
- [ ] Commit: "Document RESIN, held".

---

### Task 9: Verification and the PR

- [ ] Naming sweep over the diff: no trademarked names or model numbers.
- [ ] `./gradlew --no-daemon test`, green across every JVM module.
- [ ] Push; open one PR against `claude/mobile-mpc-drum-sampler-t58x74`. The body carries the spec's probe tables, the render-time measurement from Task 6 (CLI wall-clock per zone), and a note that `android-build` is the only proof of Task 7. Subscribe to its activity.
- [ ] On the emulator run (`emulator-tests` runs because `app/**` changed), read the logs for a MAKE INSTRUMENT render time if the suite exercised it. Otherwise add phone render time to the PR's open questions (spec, open question 3).

## Notes for the implementer

- The probe code that produced the spec's numbers is summarized in the spec's appendix. `planLoop` above is the same arithmetic, split out so it can be tested without rendering.
- `Resin.stackGains` is `internal`, so `Keys` (same module) can call it.
- If `Keys.kt` grows awkward with RESIN-specific pieces, a `ResinPad.kt` beside `Keys.kt` is fine. Keep `KeyNote` as the return type, since `ResinPadMaker` and any future factory instrument read it.
- The spec's out-of-scope list is binding for this PR: no VELVET/FATHOM held modes, no velocity layers, no recipe sidecar, no factory testkit instrument.
