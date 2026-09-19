# Synth Depth Phase 0 + 0b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the audition path and every cheap fix that makes the engines sound less limited, thin and cheap — without building the modal primitive, which is Phase 1.

**Architecture:** Generalize techniques already implemented once in the tree (saturation, loudness matching, velocity→timbre, wow/flutter) so they apply fleet-wide, fix three outright bugs, and widen macro ranges. No new synthesis model. Every change is to `:synth`, plus one new `:cli` command.

**Tech Stack:** Kotlin, Gradle multi-module (`:synth`, `:audio`, `:cli`), `kotlin.test` with backtick test names. Offline render-to-buffer — no realtime constraints.

**Spec:** `docs/superpowers/specs/2026-09-18-synth-depth-design.md`

## Global Constraints

- **Do not bump `PadRecipe.VERSION`.** It stays at `1` for this entire plan. Phase 0 changes rendering, not schema. Bumping early turns old recipes into `null` at `RecipeReplay.kt:85` and silently breaks breed/replay. See the spec's "When the version bumps".
- **Renders must stay deterministic.** Same patch in → byte-identical audio out. Randomization is seeded *from the patch*, never from wall-clock or a shared mutable RNG. `--undo` byte-identity and golden-file tests depend on this.
- **Never hardcode `44100`.** Use `Dsp.RATE` or a passed `rate` param. New code that touches absolute Hz takes a rate parameter.
- **Naming rule (`SYNTH_ROADMAP.md:27`):** no trademarked names or model numbers, and no obvious near-misses, in any code, preset name, or CLI output string.
- **No new engines in this plan.** STRIKE is Phase 3.
- **`Snip` already permits stereo** (`Cleanup.kt:18` requires `channels in 1..2`), but **this plan ships no stereo.** SPACE is Phase 1.
- **Test command:** `./gradlew :synth:test` and `./gradlew :cli:test`. Full check: `./gradlew check`.

---

## File Structure

**Create:**
- `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt` — the audition verb
- `cli/src/test/kotlin/com/snipsnap/cli/SynthCommandTest.kt`
- `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt` — guards the seeding contract
- `synth/src/test/kotlin/com/snipsnap/synth/MacroReachTest.kt` — guards defect 1
- `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt` — guards the PLUCK bug

**Modify:**
- `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt` — `seedFor`, `startPhase`, `minBeatDetune`, `levelTo`
- `cli/src/main/kotlin/com/snipsnap/cli/Main.kt` — dispatch + usage text
- `synth/.../Velvet.kt`, `Fathom.kt`, `Vox.kt`, `Tonewheel.kt`, `Tines.kt` — phase seeding, ranges
- `synth/.../Velocity.kt` — velocity→timbre
- `synth/.../Pluck.kt` — fractional delay + oversampling
- `synth/src/test/kotlin/com/snipsnap/synth/*PresetsTest.kt` — delete spread assertions

---

### Task 1: The `synth` CLI render verb

Ships first. Every later task in this plan is verified by ear through this command, and the spec's audition gates are meaningless without it.

**Files:**
- Create: `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt`
- Create: `cli/src/test/kotlin/com/snipsnap/cli/SynthCommandTest.kt`
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/Main.kt` (dispatch table ~line 352, and the usage text)

**Interfaces:**
- Consumes: `Presets.forVoice(engine: String, voice: String): List<Patch>`; `Patch.render(): Snip`; `WavWriter.write(out: OutputStream, snip: Snip, depth: BitDepth = BitDepth.PCM_24, smpl: SmplChunk? = null)`; `Options.parse(args, valued, boolean)`; `CliError`
- Produces: `SynthCommand.run(args: List<String>, out: PrintStream): Int`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthCommandTest {

    @Test
    fun `renders every preset of a voice to wav`() {
        val dir = File.createTempFile("synthcmd", "").let { it.delete(); it.mkdirs(); it }
        val out = PrintStream(ByteArrayOutputStream())
        val code = SynthCommand.run(listOf("TINES", "BELL", "--all", "--out", dir.path), out)
        assertEquals(0, code)
        val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }!!
        assertTrue(wavs.size >= 12, "expected a wav per BELL preset, got ${wavs.size}")
        assertTrue(wavs.all { it.length() > 1000 }, "every wav should carry audio")
    }

    @Test
    fun `an unknown engine is refused by name`() {
        val out = PrintStream(ByteArrayOutputStream())
        val e = runCatching {
            SynthCommand.run(listOf("THEREMIN", "AIR", "--out", "/tmp"), out)
        }.exceptionOrNull()
        assertTrue(e is CliError, "unknown engine should raise CliError, got $e")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :cli:test --tests '*SynthCommandTest*'`
Expected: FAIL — `Unresolved reference: SynthCommand`

- [ ] **Step 3: Implement the command**

```kotlin
package com.snipsnap.cli

import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * `snipsnap synth <ENGINE> <VOICE> [--preset N | --all] --out <dir>` — the
 * audition path. Renders factory presets straight to WAV so a sound can be
 * *heard* before it is judged; the engines' presets were authored without
 * one, which is the failure `docs/superpowers/specs/2026-09-18-synth-depth-design.md`
 * exists to correct.
 */
object SynthCommand {

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(
            args,
            valued = setOf("--preset", "--out"),
            boolean = setOf("--all"),
        )
        val engine = opts.positional.getOrNull(0)?.uppercase()
            ?: throw CliError("synth wants an engine and a voice: snipsnap synth TINES BELL --all --out <dir>")
        val voice = opts.positional.getOrNull(1)?.uppercase()
            ?: throw CliError("which voice? e.g. snipsnap synth TINES BELL --all --out <dir>")
        if (opts.positional.size > 2) throw CliError("synth takes an engine and a voice, nothing more")

        val presets = runCatching { Presets.forVoice(engine, voice) }.getOrNull()
        if (presets.isNullOrEmpty()) throw CliError("no such engine/voice: $engine $voice")

        val dirArg = opts["--out"] ?: throw CliError("--out wants a folder to write the wavs into")
        val dir = File(dirArg)
        if (!dir.isDirectory && !dir.mkdirs()) throw CliError("could not make the output folder: $dirArg")

        val chosen: List<Patch> = when {
            opts.has("--all") -> presets
            opts["--preset"] != null -> {
                val n = opts["--preset"]!!.toIntOrNull()
                    ?: throw CliError("--preset wants a number, got '${opts["--preset"]}'")
                if (n !in 1..presets.size) throw CliError("--preset is 1..${presets.size} for $engine $voice, got $n")
                listOf(presets[n - 1])
            }
            else -> listOf(presets.first())
        }

        for ((i, patch) in chosen.withIndex()) {
            val snip = patch.render()
            val safe = patch.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "PRESET" }
            val file = File(dir, "%s_%s_%02d_%s.wav".format(engine, voice, i + 1, safe))
            FileOutputStream(file).use { WavWriter.write(it, snip) }
            out.println("${file.name}  ${"%.2f".format(snip.durationSeconds)}s")
        }
        out.println("${chosen.size} rendered into ${dir.path}")
        return 0
    }
}
```

- [ ] **Step 4: Wire it into the dispatch table**

In `cli/src/main/kotlin/com/snipsnap/cli/Main.kt`, beside the other entries near line 352:

```kotlin
                "synth" -> SynthCommand.run(args.drop(1), out)
```

Add a matching line to the usage/help text block in the same file, following the wording style of the neighbouring entries:

```
        |  synth <ENGINE> <VOICE> [--preset N | --all] --out <dir>
        |                        render factory presets to wav, to hear them
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew :cli:test --tests '*SynthCommandTest*'`
Expected: PASS, both tests.

- [ ] **Step 6: Hear it**

```bash
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli synth TINES BELL --all --out /tmp/audition
```
Expected: twelve WAVs. **Listen to them.** This is the baseline the rest of the plan is measured against — keep the folder.

- [ ] **Step 7: Commit**

```bash
git add cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt \
        cli/src/test/kotlin/com/snipsnap/cli/SynthCommandTest.kt \
        cli/src/main/kotlin/com/snipsnap/cli/Main.kt
git commit -m "Add the synth verb, so a preset can be heard before it is judged"
```

---

### Task 2: Seeded start phase — kill the phase lock

Every oscillator in every engine starts at phase exactly `0.0`, so detuned pairs begin locked and every attack is identical. The fix must keep renders **deterministic**: the seed comes from the patch, not the clock.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt`
- Modify: `synth/.../Velvet.kt:124`, `Fathom.kt:153`, `Vox.kt:108`, `Tonewheel.kt:93`

**Interfaces:**
- Consumes: `Dsp.RATE`
- Produces: `Dsp.seedFor(vararg parts: Any): Int`, `Dsp.phases(count: Int, seed: Int): DoubleArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The seeding contract: a render is decorrelated from its neighbours but
 * reproducible from its own patch. Golden files and `--undo` byte-identity
 * both depend on the second half.
 */
class DeterminismTest {

    @Test
    fun `the same seed gives the same phases`() {
        assertContentEquals(Dsp.phases(4, Dsp.seedFor("TINES", "BELL")), Dsp.phases(4, Dsp.seedFor("TINES", "BELL")))
    }

    @Test
    fun `different voices get different phases`() {
        val a = Dsp.phases(4, Dsp.seedFor("TINES", "BELL"))
        val b = Dsp.phases(4, Dsp.seedFor("TINES", "CHIME"))
        assertTrue(a.indices.any { a[it] != b[it] }, "two voices should not share a phase set")
    }

    @Test
    fun `phases are spread, not all zero`() {
        val p = Dsp.phases(8, Dsp.seedFor("VELVET", "BASS"))
        assertTrue(p.any { it > 0.01 }, "phases must not all start at 0.0")
        assertTrue(p.all { it >= 0.0 && it < 1.0 }, "phases are a fraction of a cycle")
    }

    @Test
    fun `a rendered patch is byte-identical across renders`() {
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        assertContentEquals(patch.render().samples, patch.render().samples)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*DeterminismTest*'`
Expected: FAIL — `Unresolved reference: seedFor`

- [ ] **Step 3: Add the primitives to `Dsp.kt`**

Place beside `scrambleNear`:

```kotlin
    /**
     * A stable seed for a render. Derived from the patch's own identity, so
     * two different voices decorrelate while one voice stays reproducible —
     * golden files and `--undo` byte-identity need the second half.
     */
    fun seedFor(vararg parts: Any): Int {
        var h = 17
        for (p in parts) h = h * 31 + p.toString().hashCode()
        return h
    }

    /**
     * [count] start phases in [0, 1), spread from [seed]. Every oscillator in
     * every engine used to start at exactly 0.0, so detuned pairs began locked
     * and each attack was the same coherent transient.
     */
    fun phases(count: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { random.nextDouble() }
    }
```

- [ ] **Step 4: Run to verify the first three pass**

Run: `./gradlew :synth:test --tests '*DeterminismTest*'`
Expected: the three `phases`/`seed` tests PASS; the byte-identity test also passes (nothing has changed yet).

- [ ] **Step 5: Wire the phases into Tonewheel**

`Tonewheel.kt:93` currently reads `val phases = DoubleArray(8)`. Replace with:

```kotlin
        // Seeded per voice so the eight wheels no longer start coherent —
        // a real generator has no moment where every partial peaks together.
        val phases = Dsp.phases(8, Dsp.seedFor("TONEWHEEL", voice.name))
```

- [ ] **Step 6: Wire the phases into Velvet, Fathom and Vox**

In each engine, the oscillator phase accumulators (`Velvet.kt:124-126`, `Fathom.kt:153-156`, `Vox.kt:108-109`) initialise to `0.0`. Seed them instead. Velvet, for example:

```kotlin
        val ph = Dsp.phases(3, Dsp.seedFor("VELVET", voice.name))
        var p1 = ph[0]
        var p2 = ph[1]
        var pSub = ph[2]
```

Apply the same shape to `Fathom` (its saw pair and FM operators) and `Vox` (its saw pair), using that engine's own name in `seedFor`.

- [ ] **Step 7: Run the full synth suite**

Run: `./gradlew :synth:test`
Expected: PASS. Golden/preset tests still pass because each patch remains reproducible. **If a golden-file test fails, that is expected — audio changed.** Regenerate those fixtures and note it in the commit.

- [ ] **Step 8: Hear it**

```bash
./cli/build/install/cli/bin/cli synth VELVET BASS --all --out /tmp/audition-phases
```
Compare against `/tmp/audition`. Attacks should differ between presets rather than sharing one transient.

- [ ] **Step 9: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/{Dsp,Velvet,Fathom,Vox,Tonewheel}.kt \
        synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt
git commit -m "Start oscillators off zero, seeded from the patch so renders stay reproducible"
```

---

### Task 3: Make FAT actually beat

`Velvet.kt:105` sets detune to 1.0005–1.012. At BASS defaults the beat period is ≈3.07 s against a ≈0.47 s buffer — the pair never completes a quarter cycle, so FAT is a static comb tint. Scale the detune floor to the note's own length.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velvet.kt:105`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/VelvetTest.kt`

**Interfaces:**
- Produces: `Dsp.minBeatDetune(baseHz: Float, seconds: Float, cycles: Float = 1.5f): Float`

- [ ] **Step 1: Write the failing test**

Add to `VelvetTest.kt`:

```kotlin
    @Test
    fun `the detuned pair completes at least one beat cycle in the buffer`() {
        // 82.4 Hz over a 0.47 s note: the old 1.00395 gave a 3.07 s beat period.
        val d = Dsp.minBeatDetune(baseHz = 82.4f, seconds = 0.47f)
        val beatHz = 82.4f * (d - 1f)
        assertTrue(beatHz * 0.47f >= 1f, "expected >=1 beat cycle in the note, got ${beatHz * 0.47f}")
    }

    @Test
    fun `a long note does not get forced wider than asked`() {
        val d = Dsp.minBeatDetune(baseHz = 82.4f, seconds = 8f)
        assertTrue(d < 1.005f, "a long note needs no detune floor, got $d")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*VelvetTest*'`
Expected: FAIL — `Unresolved reference: minBeatDetune`

- [ ] **Step 3: Add the primitive to `Dsp.kt`**

```kotlin
    /**
     * The smallest detune ratio whose beating completes [cycles] cycles inside
     * a note of [seconds]. Two oscillators a ratio r apart beat at
     * baseHz*(r-1); below this floor a "FAT" macro is a static comb tint
     * rather than movement, which is what it was.
     */
    fun minBeatDetune(baseHz: Float, seconds: Float, cycles: Float = 1.5f): Float {
        if (baseHz <= 0f || seconds <= 0f) return 1f
        return 1f + (cycles / seconds) / baseHz
    }
```

- [ ] **Step 4: Apply it in Velvet**

At `Velvet.kt:105`, where `detune` is computed, raise it to the floor. The buffer length is already known from `t60` (`Velvet.kt:96,118`):

```kotlin
        // FAT is width, not tint: force the pair to complete a beat cycle
        // inside this note's own buffer, however short the note is.
        val asked = Dsp.lin(fat, 1.0005f, 1.012f)
        val detune = maxOf(asked, Dsp.minBeatDetune(baseHz = base, seconds = t60 * 1.4f))
```

Use whatever the local names for the base frequency and buffer seconds are in that function; do not introduce new ones.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :synth:test --tests '*VelvetTest*'`
Expected: PASS.

- [ ] **Step 6: Hear it**

```bash
./cli/build/install/cli/bin/cli synth VELVET BASS --all --out /tmp/audition-fat
```
FAT should now audibly move rather than colour.

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/{Dsp,Velvet}.kt \
        synth/src/test/kotlin/com/snipsnap/synth/VelvetTest.kt
git commit -m "Scale FAT's detune to the note's length so the pair actually beats"
```

---

### Task 4: Loudness-match the melodic engines

`Dsp.normalize(target = 0.95f)` is the last stage for Velvet, Fathom, Vox, Tonewheel and Pluck — peak only. THUMP alone is loudness-matched, via `Punch.rescaleToLoudness` (`Punch.kt:147`). A sine-heavy patch at equal peak sits noticeably quieter, and `SynthKits.melodic()` puts these on the same grid as the drums.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Modify: `synth/.../Velvet.kt`, `Fathom.kt`, `Vox.kt`, `Tonewheel.kt`, `Pluck.kt` (their final `Dsp.normalize` calls)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/DspTest.kt`

**Interfaces:**
- Consumes: `com.snipsnap.audio.Loudness.of(snip: Snip): Float`, `Dsp.limitPeak(buf, ceiling)`
- Produces: `Dsp.levelTo(buf: FloatArray, rate: Int, target: Float, ceiling: Float = 0.99f)`

- [ ] **Step 1: Write the failing test**

Add to `DspTest.kt`:

```kotlin
    @Test
    fun `a sine and a saw at the same target land at the same loudness`() {
        val rate = Dsp.RATE
        val sine = FloatArray(rate / 2) { kotlin.math.sin(2.0 * Math.PI * 220.0 * it / rate).toFloat() * 0.2f }
        val saw = FloatArray(rate / 2) { (2f * ((220f * it / rate) % 1f) - 1f) * 0.2f }
        Dsp.levelTo(sine, rate, target = 0.12f)
        Dsp.levelTo(saw, rate, target = 0.12f)
        val ls = Loudness.of(Snip(sine, 1, rate))
        val lw = Loudness.of(Snip(saw, 1, rate))
        assertTrue(kotlin.math.abs(ls - lw) < 0.02f, "loudness should match within tolerance: sine=$ls saw=$lw")
    }

    @Test
    fun `levelTo never exceeds the ceiling`() {
        val rate = Dsp.RATE
        val hot = FloatArray(1000) { if (it % 2 == 0) 0.9f else -0.9f }
        Dsp.levelTo(hot, rate, target = 0.9f, ceiling = 0.99f)
        assertTrue(hot.all { kotlin.math.abs(it) <= 0.99f + 1e-6f }, "ceiling must hold")
    }
```

Add the imports `com.snipsnap.audio.Loudness` and `com.snipsnap.audio.Snip` at the top of the file.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*DspTest*'`
Expected: FAIL — `Unresolved reference: levelTo`

- [ ] **Step 3: Implement `levelTo`**

In `Dsp.kt`, beside `normalize`:

```kotlin
    /**
     * Scale [buf] so its measured loudness hits [target], then hold a true
     * peak [ceiling]. Peak normalisation makes a sine-heavy patch sit quieter
     * than a saw at the same number, which is why every melodic pad used to
     * sink under the drums — THUMP was the only engine measured this way.
     */
    fun levelTo(buf: FloatArray, rate: Int, target: Float, ceiling: Float = 0.99f) {
        if (buf.isEmpty()) return
        val measured = Loudness.of(Snip(buf.copyOf(), 1, rate))
        if (measured <= 1e-6f) return
        val gain = target / measured
        for (i in buf.indices) buf[i] *= gain
        limitPeak(buf, ceiling)
    }
```

Add `import com.snipsnap.audio.Loudness` to `Dsp.kt`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests '*DspTest*'`
Expected: PASS.

- [ ] **Step 5: Swap the melodic engines over**

In each of `Velvet.kt`, `Fathom.kt`, `Vox.kt`, `Tonewheel.kt` and `Pluck.kt`, replace the final `Dsp.normalize(buf)` with a loudness target. Start every voice at `0.12f` and record the per-voice values you settle on by ear in step 6:

```kotlin
        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter.
        Dsp.levelTo(buf, RATE, target = 0.12f)
```

- [ ] **Step 6: Hear it and tune the targets**

```bash
./cli/build/install/cli/bin/cli synth VELVET BASS --preset 1 --out /tmp/lvl
./cli/build/install/cli/bin/cli synth THUMP KICK --preset 1 --out /tmp/lvl
./cli/build/install/cli/bin/cli synth TONEWHEEL SOUL --preset 1 --out /tmp/lvl
```
Play them back to back. Melodic pads should now sit *with* the kick rather than under it. Adjust the per-voice `target` values until they do, and leave the chosen numbers in the code.

- [ ] **Step 7: Run the full suite and commit**

```bash
./gradlew :synth:test
git add synth/src/main/kotlin/com/snipsnap/synth/{Dsp,Velvet,Fathom,Vox,Tonewheel,Pluck}.kt \
        synth/src/test/kotlin/com/snipsnap/synth/DspTest.kt
git commit -m "Level the melodic engines by loudness, the way Thump already was"
```

---

### Task 5: Velocity that changes timbre, not just brightness

`Velocity.soften()` low-passes the *same* frozen waveform, so every dynamic layer shares one attack, one phase, one noise burst. `Keys.ep` (`Keys.kt:48`) is the only place velocity drives a synthesis parameter — FM index. Generalize that.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt`

**Interfaces:**
- Consumes: `Patch.render(): Snip`, `Patch.macros: Map<String, Float>`
- Produces: `Velocity.atVelocity(patch: Patch, velocity: Float): Snip`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `velocity re-renders rather than filtering the same waveform`() {
        val patch = TinesPresets.forVoice(TinesVoice.BELL).first()
        val soft = Velocity.atVelocity(patch, 0.25f)
        val hard = Velocity.atVelocity(patch, 1.0f)
        // A low-pass on one render leaves the onset sample-aligned; a real
        // re-render at a different index does not.
        val n = minOf(soft.samples.size, hard.samples.size)
        val differing = (0 until minOf(n, 400)).count {
            kotlin.math.abs(soft.samples[it] - hard.samples[it]) > 1e-4f
        }
        assertTrue(differing > 50, "onset should differ between velocities, only $differing samples did")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*VelocityGrooveShuffleTest*'`
Expected: FAIL — `Unresolved reference: atVelocity`

- [ ] **Step 3: Implement `atVelocity`**

In `Velocity.kt`, beside `soften`:

```kotlin
    /**
     * [patch] rendered *as struck at* [velocity] — the timbre macro moves and
     * the voice is synthesized again, rather than one render being low-passed.
     * A quiet strike on a real instrument excites fewer partials; it is not a
     * loud strike with a blanket over it. Falls back to [soften] for voices
     * that expose no brightness macro.
     */
    fun atVelocity(patch: Patch, velocity: Float): Snip {
        val v = velocity.coerceIn(0f, 1f)
        val key = BRIGHTNESS_MACROS.firstOrNull { it in patch.macros }
            ?: return soften(patch.render(), 1f - v)
        val asked = patch.macros.getValue(key)
        // Velocity scales the macro toward its floor, never above what the
        // preset asked for: a preset's brightest is still its own ceiling.
        val scaled = asked * Dsp.lin(v, 0.35f, 1f)
        return patch.withMacros(patch.macros + (key to scaled)).render()
    }

    /** Macros that mean "how hard was this struck", in preference order. */
    private val BRIGHTNESS_MACROS = listOf("BRIGHT", "CUTOFF", "DRIVE", "DIRT", "GRIND")
```

If `Patch` has no `withMacros`, add it to the sealed interface in `Patches.kt` and implement it on each subtype by copying with a new macro map — `TinesPatch(name, voice, macros)` and its siblings are all data classes, so `copy(macros = m)` is the body.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests '*VelocityGrooveShuffleTest*'`
Expected: PASS.

- [ ] **Step 5: Hear it**

Render one preset at three velocities by hand in a scratch test or via `--preset`, and confirm the soft layer is *duller in character*, not merely quieter and filtered.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/{Velocity,Patches}.kt \
        synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt
git commit -m "Re-render at velocity instead of low-passing one frozen take"
```

---

### Task 6: Key-track the filter cutoff

`Velvet.kt:115` and `Fathom.kt:120` map cutoff to an absolute Hz independent of note pitch, so brightness drifts across a kit's pentatonic run instead of staying proportional.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Modify: `synth/.../Velvet.kt:115`, `Fathom.kt:120`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/DspTest.kt`

**Interfaces:**
- Produces: `Dsp.keyTrack(cutoffHz: Float, baseHz: Float, referenceHz: Float, amount: Float): Float`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `full key tracking keeps cutoff proportional to pitch`() {
        val low = Dsp.keyTrack(cutoffHz = 1000f, baseHz = 110f, referenceHz = 110f, amount = 1f)
        val high = Dsp.keyTrack(cutoffHz = 1000f, baseHz = 220f, referenceHz = 110f, amount = 1f)
        assertEquals(2f, high / low, 0.01f)
    }

    @Test
    fun `zero tracking leaves cutoff where it was`() {
        assertEquals(1000f, Dsp.keyTrack(1000f, 440f, 110f, 0f), 0.01f)
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*DspTest*'`
Expected: FAIL — `Unresolved reference: keyTrack`

- [ ] **Step 3: Implement**

```kotlin
    /**
     * [cutoffHz] scaled toward the note's own pitch. At [amount] 1 the filter
     * sits a fixed interval above the fundamental, so a run up the pads keeps
     * one brightness instead of getting duller as it climbs.
     */
    fun keyTrack(cutoffHz: Float, baseHz: Float, referenceHz: Float, amount: Float): Float {
        if (referenceHz <= 0f || baseHz <= 0f) return cutoffHz
        val ratio = baseHz / referenceHz
        return cutoffHz * ratio.pow(amount.coerceIn(0f, 1f))
    }
```

Ensure `import kotlin.math.pow` is present in `Dsp.kt`.

- [ ] **Step 4: Apply in Velvet and Fathom**

At each cutoff site, wrap the mapped value. Velvet:

```kotlin
        // Track the note so brightness is an interval, not a fixed Hz.
        val floorHz = Dsp.keyTrack(Dsp.expMap(cutoff, 180f, 12_000f), base, VELVET_REFERENCE_HZ, amount = 0.6f)
```

Declare `private const val VELVET_REFERENCE_HZ = 110f` near the engine's other constants, and the equivalent in `Fathom`. `0.6f` is a starting point — tune it by ear in step 5.

- [ ] **Step 5: Hear it and tune the amount**

```bash
./cli/build/install/cli/bin/cli synth VELVET BASS --all --out /tmp/keytrack
```
Presets across the TUNE range should hold a consistent brightness. Settle `amount` by ear and leave the value in the code.

- [ ] **Step 6: Run the suite and commit**

```bash
./gradlew :synth:test
git add synth/src/main/kotlin/com/snipsnap/synth/{Dsp,Velvet,Fathom}.kt \
        synth/src/test/kotlin/com/snipsnap/synth/DspTest.kt
git commit -m "Track the filter to the note so brightness is an interval, not a fixed Hz"
```

---

### Task 7: Saturate the filters that are actually driven

`TptSvf.process(..., saturate: Boolean = false)` exists at `Dsp.kt:118`, is tested, and is passed `true` in exactly one place — `Velvet.kt:144`. This is judgement, not a blanket flag: Tonewheel already ends in a post-sum `tanh` (`Tonewheel.kt:121`) and would double-saturate, and Vox has no resonance to self-limit.

**Files:**
- Modify: `synth/.../Fathom.kt:121`, `Thump.kt` (the cowbell/rim `Svf` sites)
- Do **not** modify: `Tonewheel.kt`, `Vox.kt`

- [ ] **Step 1: Find every driven-filter call site**

Run: `grep -rn "\.process(" synth/src/main/kotlin/com/snipsnap/synth/*.kt`

For each hit, decide: does this filter get pushed with resonance, and is there no `tanh` already downstream? Write the decision as a one-line comment at each site you change.

- [ ] **Step 2: Turn it on in Fathom**

`Fathom.kt:121` runs `TptSvf` with a fixed `damp = 1.2f` and a `tanh` drive *before* it. Its filter self-limiting is still meaningful on resonant peaks:

```kotlin
            // The filter self-limits instead of ringing clean into the
            // normaliser; 4x oversampling upstream keeps the folded harmonics out.
            svf.process(x, fc, damp, saturate = true)
```

- [ ] **Step 3: Run the suite**

Run: `./gradlew :synth:test`
Expected: PASS. Golden fixtures that change are expected — regenerate and say so in the commit.

- [ ] **Step 4: Hear it**

```bash
./cli/build/install/cli/bin/cli synth FATHOM GRIND --all --out /tmp/sat
```
Resonant peaks should thicken rather than spike.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt
git commit -m "Let the driven filters self-limit; leave the ones already ending in tanh alone"
```

---

### Task 8: Default TAPE's wow and flutter on for melodic voices

`Tape.kt:18` already implements a ~1.3 Hz wow plus ~7.4 Hz flutter as a modulated fractional delay. It is a working pitch LFO that no melodic voice uses — the cheapest motion available before Phase 1 builds the real MOTION stage.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/SynthKitTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a melodic pad carries a little wow by default`() {
        val kit = SynthKits.melodic()
        val pad = kit.pads.first { it.recipe != null }
        val fx = PadRecipe.fromJsonValue(pad.recipe!!).fx
        assertTrue(fx != null && fx.toJsonValue().toString().contains("WOBBLE", ignoreCase = true),
            "melodic pads should default to a touch of tape motion")
    }
```

Adjust the assertion to however `FxChain` names its sections in JSON — check `FxChain.toJsonValue` first and match the real key.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*SynthKitTest*'`
Expected: FAIL.

- [ ] **Step 3: Add a small default**

In `SynthKits.kt:15`, where the `PadRecipe(patch, fx)` is built for melodic pads, give the chain a low WOBBLE amount. Keep it subtle — this is a stopgap for motion, not an effect:

```kotlin
        // Until Phase 1's MOTION stage lands, TAPE's wow is the only thing in
        // the tree that moves. A little goes a long way; drums stay dry.
        val fx = baseFx.withWobble(amount = 0.12f)
```

Use whatever builder `FxChain` actually exposes; if there is none, construct the chain with the WOBBLE section included and its amount set.

- [ ] **Step 4: Run, hear, tune**

```bash
./gradlew :synth:test --tests '*SynthKitTest*'
```
Then render a melodic kit and listen. If it sounds seasick, lower the amount. Drums must stay untouched.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/SynthKits.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SynthKitTest.kt
git commit -m "Give melodic pads a touch of tape wow, the only motion in the tree so far"
```

---

### Task 9: The macro range audit

Defect 1. Every TINES TUNE macro spans under two octaves (BELL 220–740 Hz is 1.75); DECAY spans under 6× everywhere. This is the cheapest item in the spec and it is done **by ear**, with the CLI verb from Task 1.

**Files:**
- Modify: `synth/.../Tines.kt`, `Velvet.kt`, `Fathom.kt`, `Vox.kt`, `Tonewheel.kt`, `Pluck.kt`, `Thump.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/MacroReachTest.kt`

**Interfaces:**
- Consumes: `Dsp.expMap`, `Dsp.lin`

- [ ] **Step 1: Inventory every range**

Run: `grep -rn "Dsp.expMap(m\|Dsp.lin(m" synth/src/main/kotlin/com/snipsnap/synth/*.kt`

Write the current lo/hi for each macro into a scratch table. This is the "before" column.

- [ ] **Step 2: Widen, one engine at a time, listening as you go**

Starting targets — treat these as a floor to argue with, not gospel:
- **TUNE**: at least 3 octaves (×8) where the voice's character survives it
- **DECAY**: at least 15× between shortest and longest
- **BRIGHT / index**: the top should be obviously over-driven, not merely bright

For each engine: widen, render `--all`, listen, and pull back anything whose extreme stops sounding like the voice. **A range is wide enough when the extremes are useful and different, not when they hit a number.**

- [ ] **Step 3: Write the reach test last**

Only once the ranges are settled — the thresholds are an output of this task, not an input (see the spec's Testing section):

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Defect 1 of the depth spec: the knobs used to barely travel. These bounds
 * were set by ear during the range audit and exist so the ranges cannot
 * quietly narrow again.
 */
class MacroReachTest {

    @Test
    fun `TUNE spans at least three octaves on every Tines voice`() {
        for (voice in TinesVoice.entries) {
            val lo = Tines.render(voice, mapOf("TUNE" to 0f))
            val hi = Tines.render(voice, mapOf("TUNE" to 1f))
            val loHz = Pitch.detect(lo)?.hz ?: continue
            val hiHz = Pitch.detect(hi)?.hz ?: continue
            assertTrue(hiHz / loHz >= 7.5f, "$voice TUNE spans only ${hiHz / loHz}x")
        }
    }

    @Test
    fun `DECAY spans at least fifteen times on every Tines voice`() {
        for (voice in TinesVoice.entries) {
            val short = Tines.render(voice, mapOf("DECAY" to 0f)).durationSeconds
            val long = Tines.render(voice, mapOf("DECAY" to 1f)).durationSeconds
            assertTrue(long / short >= 15f, "$voice DECAY spans only ${long / short}x")
        }
    }
}
```

Replace `7.5f` and `15f` with whatever you actually settled on, and extend the test across the other engines the same way.

- [ ] **Step 4: Run and commit**

```bash
./gradlew :synth:test
git add synth/src/main/kotlin/com/snipsnap/synth/*.kt \
        synth/src/test/kotlin/com/snipsnap/synth/MacroReachTest.kt
git commit -m "Widen the macro ranges until the extremes are worth reaching"
```

---

### Task 10: Delete the spread tests

`presets within a voice do not cluster` (`TinesPresetsTest.kt:76` and siblings) fails the build when two presets are too similar, forcing presets out of the narrow regions that sound good. Even distribution across macro space is not a quality; it is the absence of curation.

**Files:**
- Modify: every `synth/src/test/kotlin/com/snipsnap/synth/*PresetsTest.kt` with a spread assertion

- [ ] **Step 1: Find them**

Run: `grep -rn "do not cluster\|spreadThreshold\|rmsDistance" synth/src/test/kotlin/com/snipsnap/synth/`

- [ ] **Step 2: Delete the spread tests and their threshold tables**

Remove each `presets within a voice do not cluster` test and the `spreadThreshold` map that feeds it. Leave the sanity and round-trip tests alone — those guard real invariants.

- [ ] **Step 3: Run the suite**

Run: `./gradlew :synth:test`
Expected: PASS, with fewer tests.

- [ ] **Step 4: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/
git commit -m "Drop the spread tests: even distribution is the absence of curation"
```

---

### Task 11 (Phase 0b): PLUCK's fractional delay and oversampling

`Pluck.kt:127` uses `n = (RATE / freq).toInt()` with no fractional-delay correction. HARP at top TUNE renders ≈+21 cents; at its root ≈+1.8. The error is non-monotonic across semitones, so adjacent pads in `SynthKits.melodic()` are out of tune *with each other*. PLUCK is also the only engine that never oversamples.

This is real DSP on a feedback loop whose delay length, loop-filter cutoff and feedback gain are all rate-dependent — it is split out so it cannot hold up Tasks 1–10.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt:96,127,143-147`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt`

**Interfaces:**
- Consumes: `Dsp.OVERSAMPLE`, `Dsp.decimate`, `com.snipsnap.audio.Pitch.detect`
- Produces: no new public surface; `Pluck.render` keeps its signature

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every pitched voice lands within five cents across its whole TUNE range.
 * PLUCK's integer delay line used to render ~21 cents sharp at the top of
 * HARP and ~2 cents at its root, non-monotonically — so a pentatonic run of
 * pads was out of tune with itself, not merely transposed.
 */
class TuningAccuracyTest {

    private fun cents(a: Float, b: Float) = 1200f * (ln(a / b) / ln(2f))

    @Test
    fun `every Pluck semitone lands within five cents`() {
        for (voice in PluckVoice.entries) {
            for (semi in 0..Pluck.TUNE_SEMITONES) {
                val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
                val snip = Pluck.render(voice, mapOf("TUNE" to macro))
                val detected = Pitch.detect(snip) ?: continue
                val want = Pluck.frequencyFor(voice, semi)
                val err = abs(cents(detected.hz, want))
                assertTrue(err <= 5f, "$voice semitone $semi is $err cents off (want $want, got ${detected.hz})")
            }
        }
    }
}
```

If `Pluck` exposes no `frequencyFor`, add it as an `internal fun frequencyFor(voice: PluckVoice, semitone: Int): Float` returning the root times `2^(semitone/12)`, so the test asserts against the engine's own intent rather than a duplicated formula.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*TuningAccuracyTest*'`
Expected: FAIL — several HARP semitones off by well over 5 cents.

- [ ] **Step 3: Add the fractional delay**

In `ks()`, replace the truncated delay length with an integer part plus a first-order allpass for the fraction:

```kotlin
        // The loop length is rarely a whole number of samples. Truncating it
        // detunes the string by up to ~21 cents, differently at each semitone,
        // so a run of pads went out of tune with itself. The allpass carries
        // the leftover fraction.
        val exact = rate / freq
        val n = kotlin.math.floor(exact).toInt().coerceAtLeast(2)
        val frac = exact - n
        val a = ((1f - frac) / (1f + frac)).toFloat()
        var apX1 = 0f
        var apY1 = 0f
```

and inside the loop, after reading the delayed sample `d`:

```kotlin
            // First-order allpass: y[i] = a*(x[i] - y[i-1]) + x[i-1]
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
```

Feed `tuned` — not `d` — into the loop filter and the output.

- [ ] **Step 4: Run to verify the tuning test passes**

Run: `./gradlew :synth:test --tests '*TuningAccuracyTest*'`
Expected: PASS. If some semitones are still out, the allpass is likely applied after the loop filter rather than before — order matters in a feedback path.

- [ ] **Step 5: Add oversampling**

`render()` at `Pluck.kt:96` calls `ks()` at bare `RATE`. Bring it in line with the other eight engines:

```kotlin
        val renderRate = RATE * Dsp.OVERSAMPLE
        // ... every ks() call now takes rate = renderRate
        val buf = Dsp.decimate(raw, RATE)
```

**The loop is rate-dependent in three places** — delay length (`rate / freq`), loop-filter cutoff (`loopLp`, `Pluck.kt:143-147`) and feedback gain derived from decay. All three already take `rate`; make sure every one receives `renderRate` and none keeps a bare `RATE`.

- [ ] **Step 6: Re-run the tuning test and the suite**

Run: `./gradlew :synth:test`
Expected: PASS. The tuning test is the real check — oversampling must not have shifted the pitch.

- [ ] **Step 7: Hear it**

```bash
./cli/build/install/cli/bin/cli synth PLUCK HARP --all --out /tmp/pluck
```
Play the presets in TUNE order. The run should now be in tune with itself.

- [ ] **Step 8: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -m "Give Pluck a fractional delay and oversampling, so a run is in tune with itself"
```

---

## Audition Gate

**Stop here.** Phase 1 does not begin until these have been heard back to back against the `/tmp/audition` baseline from Task 1:

```bash
for e in "TINES BELL" "VELVET BASS" "FATHOM GRIND" "PLUCK HARP" "TONEWHEEL SOUL" "VOX CHOIR"; do
  ./cli/build/install/cli/bin/cli synth $e --all --out /tmp/audition-phase0
done
```

The questions the gate exists to answer, which shape Phase 1's mode tables and macro layout:

1. Do the engines still sound thin, cheap or uninteresting — and which word survives?
2. Did the widened ranges help, or do the extremes sound broken rather than bold?
3. Is the loudness match right across engines, or do melodic pads still sit under the drums?
4. Which engine should Phase 1 rebuild on the spine? The spec assumes TINES; the audition may disagree.

---

## Self-Review

**Spec coverage.** Phase 0's bullets each map to a task: CLI verb (1), phase randomization (2), detune scaled to note length (3), loudness normalize (4), velocity→timbre (5), key tracking (6), `saturate=true` (7), TAPE WOBBLE default (8), macro range audit (9). Phase 0b is Task 11. The spread-test deletion (Task 10) comes from the spec's Testing section. **Deliberately not covered here**, all Phase 1: `Dsp.Modes`, mode tables, MATERIAL, STRIKE position, the MOTION stage, per-mode stereo, the FX-rack stereo audit, the `VERSION` 1→2 migration, and the TINES rebuild.

**Placeholders.** Tasks 5, 8 and 11 each contain a conditional ("if `Patch` has no `withMacros`", "use whatever builder `FxChain` exposes", "if `Pluck` exposes no `frequencyFor`"). These are deliberate: the exact helper names could not be confirmed without reading files beyond this plan's scope, and each instruction says precisely what to write if the helper is absent. No step says "handle edge cases" or "add tests for the above".

**Type consistency.** `Dsp.seedFor(vararg Any): Int` feeds `Dsp.phases(count, seed)` in Task 2 and is reused unchanged in later tasks. `Dsp.levelTo(buf, rate, target, ceiling)` keeps one signature across Task 4's five call sites. `Dsp.minBeatDetune(baseHz, seconds, cycles)` and `Dsp.keyTrack(cutoffHz, baseHz, referenceHz, amount)` each appear once. `Velocity.atVelocity(patch, velocity)` is the only new public name in Task 5.

**Known ordering constraint.** Task 1 must land before Tasks 4, 6 and 9, which are tuned by ear and have no other listening path.
