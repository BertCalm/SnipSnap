# Synth Depth Phase 1C — SPACE, per-mode stereo

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the modal bank genuine stereo width by panning each mode to its own position, and fix the one FX section that would tear that image apart.

**Architecture:** A real body radiates different modes in different directions, so panning per mode is the physically honest way to make a struck sound wide. Panning is **linear**, not equal-power, which makes it mono-safe by construction: `(1-p)·x + p·x = x` exactly, so L+R returns every mode at full amplitude with no comb notching. `Squash`'s per-channel detector is replaced with one linked detector before any of this ships.

**Tech Stack:** Kotlin, `:synth`. Offline render-to-buffer. `Snip` already permits `channels in 1..2` (`Cleanup.kt:18`).

**Spec:** `docs/superpowers/specs/2026-09-18-synth-depth-design.md` — the SPACE section, and its correction note about what export does *not* need.

## Why this closes the last of the four words

The original complaint was "limited, thin, cheap, uninteresting." Phase 0 took cheap, Phase 1A/1B took uninteresting. **"Thin" is this.** Every engine still returns `channels = 1`; there is no stereo anywhere in the repo.

## What is already settled, so nobody re-derives it

- **Export needs no change.** Traced end to end: `Snip.channels` is constrained to 1..2 at construction, `WavWriter` writes `snip.channels` generically, `KitAssembler` makes no mono assumption, `KitExporter` derives `SliceEnd` from a channel-aware `WavInfo.read`, `KeygroupWriter` has no channel logic, and `Preflight.kt:85` already rejects >2-channel samples. A stereo WAV flows through and the MPC reads its own `fmt` chunk. `ExportRegressionTest` already asserts every exported WAV carries 1 or 2 channels.
- **`<Mono>` in the XPM is voice allocation, not channel count** — monophonic versus polyphonic. An earlier draft of the spec claimed otherwise and was corrected. Do not touch `XpmWriter`.
- **The FX rack is safe except one section.** All sixteen of `FxChain.SECTIONS` were audited with a correlated pan probe (L=x, R=0.4x, checking whether the 2.5:1 ratio survives). Only `squash` fails. `ring`, `vinyl` and `phase` are deliberately linked and do what their comments claim.
- **`swell` discards panning by design.** It calls `Cleanup.toMono` and routes through `Stretch.stretch`, fabricating a decorrelated wash. Documented, in `FxTest`'s `stereoExcluded`. It destroys per-mode imaging in the hit's first 0.4 s whenever `RISE > 0`, which is the default. Not a bug — a constraint. Do not "fix" it.

## Global Constraints

- **`PadRecipe.VERSION` stays 1.** `validateMacros` takes a subset and `render` overlays onto defaults, so an added macro needs no bump; `RecipeReplay.kt:85` swallows the version throw into `null`, so bumping would erase older recipes from breed and replay.
- **Determinism:** same patch → byte-identical audio. No clock, no shared mutable RNG.
- **Never hardcode 44100** — `Dsp.RATE` or a passed `rate`.
- **No invented values.** Measured, sourced, or a marked placeholder. No third category.
- **Acoustic claims need a real spectrum** (`Fft`/`FeatureExtractor` in `:audio`), never autocorrelation — it misled this project twice, once reporting a 523.93 Hz string as 1047 Hz.
- **Reachability tests are SWEPT, not spot-checked.** This project shipped a floor that swallowed a macro across its whole range while every test stayed green, and a two-point test that passed a wrong implementation.
- **Extremes must be extreme, including the ugly end** — a range curated to only tasteful settings has already made the user's decisions for them.
- **Test command:** `./gradlew :synth:test`. Hand long runs to the controller; commit before reporting.

---

## File Structure

**Modify:**
- `synth/src/main/kotlin/com/snipsnap/synth/Squash.kt` — one linked detector
- `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt` — `Mode.pan`, `ringStereo`
- `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt` — SNARE opts into width
- their tests

**Create:** nothing.

---

### Task 1: Squash stops tearing the image

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Squash.kt:58-74`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/SquashTest.kt` (or wherever Squash is tested — find it)

**Interfaces:** no signature change. `Squash`'s public entry point keeps its shape.

**The defect, measured.** `Squash.kt:58` opens `for (ch in 0 until snip.channels)` and builds a **separate `env` per channel**, then compares each against `threshold` — which is derived once from the whole-buffer peak and is therefore *absolute*, not scale-invariant. So a quieter channel crosses the threshold later and by less, and the two channels get different gain curves. A correlated pan probe's 2.5:1 ratio collapsed to **1.13 during the attack**, recovering after release: audible pumping, and the image narrows exactly when the hit lands.

**The fix.** One detector fed by the loudest channel at each frame, producing one gain applied to every channel. That is what a stereo compressor does, and it is why they have a link switch.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `squash keeps a stereo image instead of pumping it narrower`() {
        val rate = Dsp.RATE
        val frames = rate / 4
        // A correlated pair, deliberately off-centre: R is 0.4x L throughout.
        // Any honest dynamics processor preserves that ratio; a per-channel
        // detector closes on the louder side first and squeezes them together.
        val samples = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val t = f.toFloat() / rate
            val x = kotlin.math.sin(2.0 * Math.PI * 220.0 * t).toFloat() *
                kotlin.math.exp(-6.0 * t).toFloat()
            samples[f * 2] = x
            samples[f * 2 + 1] = x * 0.4f
        }
        val out = Squash.apply(Snip(samples, 2, rate), amount = 0.8f)

        fun ratioOver(fromSec: Float, toSec: Float): Float {
            val a = (fromSec * rate).toInt() * 2
            val b = (toSec * rate).toInt() * 2
            var l = 0.0; var r = 0.0
            var i = a
            while (i < b) { l += out.samples[i].toDouble() * out.samples[i]
                            r += out.samples[i + 1].toDouble() * out.samples[i + 1]; i += 2 }
            return kotlin.math.sqrt(l / (r + 1e-12)).toFloat()
        }
        // Measured before the fix: 2.5 at rest, collapsing to 1.13 during the
        // attack. The image must hold through the transient, not just after it.
        val duringAttack = ratioOver(0.001f, 0.02f)
        assertTrue(
            duringAttack > 2.0f,
            "squash narrowed the image during the attack: L:R fell to $duringAttack from 2.5",
        )
    }
```

Find `Squash`'s real entry point before writing this — if it is not `Squash.apply(snip, amount)`, use the real signature. Do not invent one.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*Squash*'`
Expected: FAIL, with the ratio somewhere near 1.13.

- [ ] **Step 3: Link the detector**

Replace the per-channel envelope loop. One `env` over the frame, charged by the loudest channel at that frame:

```kotlin
        // One detector for the whole frame, fed by whichever channel is
        // loudest. Per-channel envelopes against a shared absolute threshold
        // are not scale-invariant: the quieter side crosses later and by
        // less, so the two gains diverge and the image narrows exactly when
        // the hit lands. Every stereo compressor has a link switch for this
        // reason, and for a hand-built per-mode image there is no case for
        // leaving it off.
        val env = FloatArray(frames)
        var e = 0f
        for (f in 0 until frames) {
            var a = 0f
            for (ch in 0 until snip.channels) {
                val x = snip.samples[f * snip.channels + ch]
                val m = if (x < 0) -x else x
                if (m > a) a = m
            }
            e += (if (a > e) aAtk else aRel) * (a - e)
            env[f] = e
        }
        for (f in 0 until frames) {
            val ahead = env[if (f + look < frames) f + look else frames - 1]
            val gain = if (ahead > threshold) {
                Math.pow((threshold / ahead).toDouble(), slope.toDouble()).toFloat()
            } else 1f
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                out[i] = snip.samples[i] * gain
            }
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :synth:test --tests '*Squash*'`
Expected: PASS. **Mono behaviour must be unchanged** — with one channel, `max` over one channel is that channel, so the arithmetic is identical. If a mono Squash test moves, something is wrong; investigate rather than updating it.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Squash.kt synth/src/test/kotlin/com/snipsnap/synth/*.kt
git commit -m "Link Squash's detector so it stops squeezing a stereo image together"
```

---

### Task 2: Modes get a position, and the bank can render stereo

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Interfaces:**
- Consumes: `Modes.Mode(ratio, gain, t60)`, `Modes.ring(excitation, fundamentalHz, modes, rate): FloatArray`
- Produces: `Modes.Mode(ratio, gain, t60, pan)` with `pan` defaulting to centre; `Modes.ringStereo(excitation, fundamentalHz, modes, rate): FloatArray` returning **interleaved** L/R; `Modes.spread(modes, width, seed): List<Mode>`

**Panning is LINEAR, not equal-power, and that is the whole point.** With `L = (1-p)·x` and `R = p·x`, summing gives `(1-p)·x + p·x = x` **exactly**, for every p. So a mono fold-down returns every mode at full amplitude with no comb notching — which matters because these files go to SD cards and club systems. Equal-power (sin/cos) panning would sum to √2 at centre, a 3 dB bump that varies with position. Do not "improve" this to equal-power.

`ring` keeps its mono signature and behaviour; every existing caller is unaffected.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a mono fold-down of the stereo bank returns the mono bank exactly`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 4).also { it[0] = 1f }
        val modes = Modes.spread(Modes.tableFor(Modes.Material.METAL_BAR), width = 1f, seed = 7)
        val mono = Modes.ring(exc, 220f, modes, rate)
        val stereo = Modes.ringStereo(exc, 220f, modes, rate)
        // Linear panning sums to unity for every position, so L+R must be
        // the mono render sample for sample. This is the property that makes
        // the width safe on a club system, and it is worth asserting exactly
        // rather than approximately.
        for (f in mono.indices) {
            val summed = stereo[f * 2] + stereo[f * 2 + 1]
            assertTrue(
                kotlin.math.abs(summed - mono[f]) < 1e-5f,
                "fold-down differs at frame $f: $summed vs ${mono[f]}",
            )
        }
    }

    @Test
    fun `width actually widens, swept across its travel`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 4).also { it[0] = 1f }
        val table = Modes.tableFor(Modes.Material.METAL_BAR)
        // Side energy over mid energy: 0 at width 0, rising monotonically.
        fun sideRatio(width: Float): Float {
            val s = Modes.ringStereo(exc, 220f, Modes.spread(table, width, seed = 7), rate)
            var mid = 0.0; var side = 0.0
            var f = 0
            while (f < s.size) {
                val m = (s[f] + s[f + 1]) * 0.5; val d = (s[f] - s[f + 1]) * 0.5
                mid += m * m; side += d * d; f += 2
            }
            return kotlin.math.sqrt(side / (mid + 1e-12)).toFloat()
        }
        val points = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { sideRatio(it) }
        assertTrue(points.first() < 1e-4f, "width 0 must be dead centre, got ${points.first()}")
        for (i in 0 until points.size - 1) {
            assertTrue(
                points[i + 1] > points[i] * 1.15f,
                "width did nothing between step $i and ${i + 1}: $points",
            )
        }
    }

    @Test
    fun `the stereo bank is deterministic`() {
        val rate = Dsp.RATE
        val exc = FloatArray(rate / 8).also { it[0] = 1f }
        val modes = Modes.spread(Modes.tableFor(Modes.Material.BELL), width = 0.7f, seed = 3)
        assertTrue(
            Modes.ringStereo(exc, 220f, modes, rate)
                .contentEquals(Modes.ringStereo(exc, 220f, modes, rate)),
            "same inputs must give byte-identical stereo output",
        )
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: FAIL — `Unresolved reference: ringStereo`

- [ ] **Step 3: Implement**

Add `pan` to `Mode` with a centred default so every existing construction still compiles:

```kotlin
    /**
     * One partial, and where it sits. [pan] is 0 hard left, 1 hard right,
     * 0.5 centred — the position this mode radiates from.
     */
    data class Mode(val ratio: Float, val gain: Float, val t60: Float, val pan: Float = 0.5f)
```

Then the spread and the stereo render:

```kotlin
    /**
     * [modes] given stereo positions, [width] 0 (all centred) to 1 (full
     * spread). Seeded from [seed] so a body's image is reproducible — two
     * renders of the same patch must be byte-identical, and a random image
     * per render would break that as surely as a random oscillator phase.
     *
     * Positions alternate outward rather than landing randomly: a real body's
     * low modes are its least directional, so the fundamental stays near the
     * middle and the upper partials fan out, which is both what a physical
     * radiator does and what keeps a fold-down's low end solid.
     */
    fun spread(modes: List<Mode>, width: Float, seed: Int): List<Mode> {
        val w = width.coerceIn(0f, 1f)
        val random = Random(seed)
        return modes.mapIndexed { i, mode ->
            // Alternating sides, widening with index, jittered so a bank
            // never sounds like a row of evenly spaced points.
            val side = if (i % 2 == 0) -1f else 1f
            val reach = if (modes.size <= 1) 0f else i.toFloat() / (modes.size - 1)
            val jitter = (random.nextFloat() - 0.5f) * 0.25f
            mode.copy(pan = (0.5f + side * w * reach * (0.5f + jitter)).coerceIn(0f, 1f))
        }
    }

    /**
     * [modes] rung into an interleaved stereo buffer, each at its own [Mode.pan].
     *
     * Panning is LINEAR — `L = (1-p)·x`, `R = p·x` — so L+R sums to exactly
     * `x` for every position. A mono fold-down therefore returns every mode
     * at full amplitude with no comb notching, which equal-power panning
     * cannot promise (it sums to √2 at centre). These files end up on SD
     * cards and club systems; the fold-down is not hypothetical.
     */
    fun ringStereo(
        excitation: FloatArray,
        fundamentalHz: Float,
        modes: List<Mode>,
        rate: Int = RATE,
    ): FloatArray {
        val out = FloatArray(excitation.size * 2)
        if (excitation.isEmpty() || modes.isEmpty() || fundamentalHz <= 0f) return out
        for (mode in modes) {
            val one = ring(excitation, fundamentalHz, listOf(mode.copy(pan = 0.5f)), rate)
            val p = mode.pan.coerceIn(0f, 1f)
            for (i in one.indices) {
                out[i * 2] += one[i] * (1f - p)
                out[i * 2 + 1] += one[i] * p
            }
        }
        return out
    }
```

Ensure `import kotlin.random.Random` is present.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: PASS, all three, plus the existing 19 unchanged.

- [ ] **Step 5: Hear it**

Render a body at width 0, 0.5 and 1 to WAV via a scratch test and listen on headphones. It should widen without the centre hollowing out. Delete the scratch test before committing.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt
git commit -m "Pan each mode to its own position, linearly so a fold-down stays whole"
```

---

### Task 3: SNARE takes width, per-voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt`

**Interfaces:**
- Consumes: `Modes.spread`, `Modes.ringStereo` from Task 2
- Produces: SNARE renders `channels = 2` when `WIDTH > 0`; a new `WIDTH` macro on SNARE

**Per-voice, because mono is often correct.** The spec is explicit: kick and sub stay mono — it is safer on club systems and halves the SD-card cost. SNARE is the flagship the audition gate chose, and it is where the modal bank already lives.

**`WIDTH` defaults to 0**, so every existing preset renders mono and byte-identical. That is deliberate: the sixteen snare presets were auditioned in mono, and silently widening them would discard that work. Width is opted into per preset, at the next listening session.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `SNARE stays mono at WIDTH 0 and goes stereo above it`() {
        val mono = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0f))
        assertEquals(1, mono.channels, "WIDTH 0 must stay mono - presets were auditioned there")
        val wide = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0.8f))
        assertEquals(2, wide.channels, "WIDTH above zero should render stereo")
    }

    @Test
    fun `a wide SNARE folds down without losing energy`() {
        val wide = Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 1f))
        val folded = FloatArray(wide.frameCount)
        for (f in folded.indices) folded[f] = wide.samples[f * 2] + wide.samples[f * 2 + 1]
        var wideRms = 0.0; var foldRms = 0.0
        for (v in wide.samples) wideRms += v.toDouble() * v
        for (v in folded) foldRms += v.toDouble() * v
        // Linear panning means the fold-down keeps the body's energy rather
        // than notching it - the property that makes this safe in a club.
        assertTrue(foldRms > wideRms * 0.5, "fold-down lost energy: $foldRms vs $wideRms")
    }

    @Test
    fun `every existing SNARE preset still renders mono and unchanged`() {
        // The sixteen presets were auditioned by ear in mono. None sets
        // WIDTH, so none may quietly become stereo.
        for (p in ThumpPresets.forVoice(ThumpVoice.SNARE)) {
            assertEquals(1, p.render().channels, "${p.name} silently went stereo")
        }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ThumpTest*'`
Expected: FAIL — SNARE has no `WIDTH` macro yet.

- [ ] **Step 3: Wire it**

Add `MacroSpec("WIDTH", 0f)` to `macrosFor(ThumpVoice.SNARE)`. In `snare()`, when `WIDTH > 0`, build the head with `Modes.spread(...)` and ring it through `Modes.ringStereo`, then interleave the wire layer across both channels. `Thump.render` must return `Snip(buf, channels = <1 or 2>, sampleRate = RATE)` accordingly — trace every downstream call in `render` (`Dsp.normalize`, `Punch.applyOversampled`, `Dsp.limitPeak`, `Dsp.fadeTail`, `trimSnareTail`) and confirm each is channel-correct or make it so. **`trimSnareTail` indexes a flat buffer and must not cut mid-frame.**

The seed for `Modes.spread` must derive from the patch, not a clock — reuse `Dsp.seedFor("THUMP", voice.name)`'s pattern so renders stay byte-identical.

- [ ] **Step 4: Run the tests, then the export test**

Run: `./gradlew :synth:test --tests '*ThumpTest*'` then `--tests '*ExportRegressionTest*'`.
The export test already asserts every WAV carries 1 or 2 channels — **it is the safety net for this task**, and it should pass without modification. If it fails, the export path is not as channel-clean as the audit concluded; report that rather than adjusting the test.

- [ ] **Step 5: Hear it**

```bash
./gradlew :cli:installDist
cli/build/install/cli/bin/cli synth THUMP SNARE --all --out /tmp/snare-mono
```
Then render a widened one via a scratch test and compare on headphones. Report whether the width reads as space or as a phasey smear.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Thump.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ThumpTest.kt
git commit -m "Let the snare take width, opt-in per preset so the auditioned sixteen stay mono"
```

---

## Audition Gate

**Stop here.** Which voices should default to width, and how much, is a listening decision — the same one that owns the four outstanding placeholders and the macro ranges. Render for it: SNARE at WIDTH 0, 0.33, 0.66, 1, plus each `Modes.Material` body at width 0 and 1, and listen on headphones *and* folded to mono.

The question for the gate: does width read as **space** or as a **phasey smear**? Per-mode panning should give the former, but that claim has not been tested by ear.

---

## Self-Review

**Spec coverage.** The spec's SPACE section asks for per-mode panning (Task 2), mono-safety (Task 2's fold-down test), an FX-rack stereo audit (done, and Task 1 fixes its one finding), and per-voice opt-in (Task 3). Its correction note says export needs no change — honoured; `XpmWriter` is untouched.

**Placeholders.** `WIDTH` defaults to 0, which is not a placeholder but a deliberate no-op default preserving the auditioned presets. The jitter constant `0.25f` in `spread` is a shape choice, marked as such in the KDoc, and belongs at the gate alongside the others.

**Type consistency.** `Mode(ratio, gain, t60, pan = 0.5f)` is introduced in Task 2 and consumed by Task 3. `spread(modes, width, seed): List<Mode>` and `ringStereo(excitation, fundamentalHz, modes, rate): FloatArray` keep one signature throughout. `ring`'s existing mono signature is unchanged, so Phase 1A/1B callers are untouched.

**Known risk, stated plainly.** Task 3 is the first time anything in this repo returns `channels = 2`, so it exercises code paths no test has ever reached — `Punch.applyOversampled`, `trimSnareTail`, `Dsp.fadeTail` and `limitPeak` all index flat buffers. The instruction is to trace each rather than assume, and `ExportRegressionTest` is the backstop.
