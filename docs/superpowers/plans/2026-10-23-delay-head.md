# DELAY: ECHO's HEAD Knob, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL, if available: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task; otherwise run the tasks in order. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give ECHO a fifth macro, HEAD, that feeds the repeats only the note's first 120 ms, so a held note's attack comes back as a repeat. Add the `delayed` character, an ungated DELAY entry in the pedal library, and the owner's listening gate. At HEAD 0, ECHO renders every existing recipe and saved kit bit for bit. The DELAY chip is NOT drawn by this plan: it appears on the pad sheet only if the gate passes (see "After the gate").

**Architecture:**
- **`synth`:**
  - `Echo` gains one macro (HEAD), three helpers (`reach`, `headFrames`, `window`) and one multiply in its loop. The multiply is by exactly 1f when HEAD is off.
  - `Treatments.EXTRA` gains `delayed`.
  - `Pedals.entries` gains DELAY.
- **Test source:**
  - `EchoTest`, new.
  - Edits to `TreatmentsTest`, `PedalsTest` and `PedalBoardTest`.
  - `PedalListensGenerator` and its page trade the long-note sheet for the gate's sheet.
- **Unchanged:** no rack section, no `FxChain` field, no sheet code and no app code changes.

**Tech Stack:** Kotlin/JVM (`:synth`, `:shell`, `:cli`), Gradle, kotlin.test. The gate page is plain HTML and JS in test resources, published as a claude.ai artifact.

**Spec:** `docs/superpowers/specs/2026-10-22-delay-head-design.md`. Read it whole before starting. It covers the macro, the recipe, decisions 1 to 7, the tests, the gate and the hazards. Every number this plan pins comes from it. The pedal library it plugs into is `docs/superpowers/specs/2026-10-21-pedal-library-and-board-design.md` (built in PR #623).

## Global Constraints

- **Branch.** The controller names the branch: the session's designated branch, restarted from the default branch if PR #619 (this spec and plan) has merged. If the default branch has moved, merge it in before Task 1. Use a merge commit; never rebase or force-push a branch that has an open PR. "The base" below means the default branch's tip as merged before Task 1 (`origin/claude/mobile-mpc-drum-sampler-t58x74` at that moment).
- **Saved keys.** `FxChain.VERSION` stays 1, and `echo` keeps its saved key. HEAD is appended AFTER MIX in `Echo.MACROS`. A recipe that does not set HEAD writes no HEAD key.
- **`Shuffle.TREATMENTS` is never touched.** `Treatments.EXTRA` is appended to at its END. Re-read it first: it ends with `rockwahed` at the time of writing. `TreatmentsTest`'s names list moves with it.
- **Neutrals and fade laws.** A recipe never sets a macro exactly at its neutral. All five of ECHO's neutrals are 0, and `delayed` sets none of its macros to 0. HEAD takes `MacroSpec`'s default fade law, `FadeLaw.LINEAR`, like every ECHO macro (only VALVE's DRIVE fades by `LOG`), so AMT scales each of `delayed`'s macros by AMT exactly, and EchoTest's `mapValues { it.value * amount }` is `Treatments.fade`'s own result for ECHO.
- **The `:shell` source laws** apply to main source, and they run only when forced:
  - never hardcode a sample rate (no literal `44100`);
  - no `.format` with digit conversions without `Locale.ROOT`;
  - no stacked KDoc;
  - no double-asterisk-slash. This last law (`ConventionTest`'s comment-closer law) also scans `synth/src/test`, so `EchoTest` and the generator must not contain one either.
- **Verification.**
  - Each task runs its own targeted tests.
  - The whole suite runs once, in Task 7: `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew --no-daemon test`, which takes about two hours. Run it detached with its exit code written to a file, and wait on that file; a background shell with a 30-minute limit would kill it.
  - Judge green by gradle's exit code, never by grepping output. The steward skill (`.claude/skills/steward/SKILL.md`) is the authority.
- **Commits.**
  - One commit per task.
  - Messages are plain declarative prose with no `feat:` or `fix:` prefix. The title says what the commit does; the body says why.
  - Messages end with the attribution trailers the session's instructions give, and never name a model in the body.
- **Pins.** Never re-record a hash pin (Task 1) to make a test pass. A mismatch means HEAD 0 is no longer ECHO, and that is the bug.

## Review Focus

1. **HEAD 0 is ECHO, byte for byte.** Task 1 records 18 SHA-256 hashes on the base, before `Echo.kt` changes. Task 2 must keep every one of them at HEAD absent, 0, 0.0005 and `HEAD_OFF`. The only change to the loop at HEAD 0 is `dry * 1f`.
2. **The first tap is the head alone.** `delayed` at the pad sheet's AMT 70 has HEAD 0.7, and `reach` is 1 there (pinned).
3. **The onset is untouched.** The first repeat matches HEAD off to 1e-5, up to one gain, over `[0, delay + headFrames − headFrames/3)`. HEAD acts only after the window's fully open part.
4. **The MIX numbers are pinned on both sides:** the jangle's pick at 3.5 dB and the snare's repeat at −7.8 dB, at AMT 70, each ± 0.5 dB. The owner chose MIX 0.75 from these figures.
5. **Nothing on the pad sheet changes.** DELAY is ungated, so row seven, `CHARACTER_FOR` and every `PadSheetTest` pin hold.
6. **A recorded verdict never points at a new sound.**
   - The gate's clips get new ids (`gate_*`) and new question ids (`dly_*`).
   - The 48 clips already on the published page are never re-uploaded.
   - The retired long-note clips stay on the artifact as they are.

---

### Task 1: Pin ECHO as it ships, before any change

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/EchoTest.kt`
- Create, then delete in Step 4: `synth/src/test/kotlin/com/snipsnap/synth/EchoPinsPrinter.kt`

**Interfaces:**
- Consumes: `Echo.process(snip, macros)` as it is today, `Thump.render(ThumpVoice)`, `Vox.render(VoxVoice.CHOIR)`.
- Produces:
  - `EchoTest.settings`: six named settings, all literals.
  - `EchoTest.sources`: kick, snare, choir.
  - `EchoTest.PINS`: 18 hashes.
  - The test `HEAD 0 is ECHO as it shipped`.

- [ ] **Step 1: Confirm `Echo.kt` is unchanged from the base.** Run `git diff origin/claude/mobile-mpc-drum-sampler-t58x74 -- synth/src/main/kotlin/com/snipsnap/synth/Echo.kt` (the base, merged in before this task). The diff must be empty. If it is not, stop: the pins must be recorded on the shipped ECHO.

- [ ] **Step 2: Write the one-off printer and run it.** Create `EchoPinsPrinter.kt`:

```kotlin
package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test

/** One-off: prints the literal settings and the hashes EchoTest pins. Deleted once they are pasted. */
class EchoPinsPrinter {
    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun print() {
        val drawn = (0..2).associate { seed ->
            val r = Random(seed)
            "random$seed" to listOf("TIME", "REPEAT", "TONE", "MIX").associateWith { r.nextFloat() }
        }
        for ((name, m) in drawn) println("ECHO-PIN \"$name\" to mapOf(" + m.entries.joinToString(", ") { "\"${it.key}\" to ${it.value}f" } + "),")
        val settings = linkedMapOf(
            "slapback" to mapOf("TIME" to 0.25f, "REPEAT" to 0.3f, "TONE" to 0.4f, "MIX" to 0.5f),
            "landing" to mapOf("TIME" to 0.45f, "REPEAT" to 0.55f, "TONE" to 0.5f, "MIX" to 0.45f),
            "defaults" to emptyMap<String, Float>(),
        ) + drawn
        val sources = linkedMapOf("kick" to Thump.render(ThumpVoice.KICK), "snare" to Thump.render(ThumpVoice.SNARE), "choir" to Vox.render(VoxVoice.CHOIR))
        for ((src, snip) in sources) for ((set, m) in settings) println("ECHO-PIN \"$src/$set\" to \"${hash(Echo.process(snip, m).samples)}\",")
    }
}
```

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.EchoPinsPrinter' -i 2>&1 | grep 'ECHO-PIN'`. It prints three map lines and 18 hash lines.

- [ ] **Step 3: Write `EchoTest` with the pins.** Paste the printed lines into the two marked places below. The random maps go in `settings` and the hashes go in `PINS`. Drop the `ECHO-PIN ` prefix and keep the printed float literals exactly.

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ECHO's HEAD macro (docs/superpowers/specs/2026-10-22-delay-head-design.md).
 *
 * The first test pins ECHO as it shipped before HEAD existed: SHA-256 hashes of its output, recorded on the base and
 * never re-recorded. Every setting is a literal, so a later change to a recipe, to Siren, or to the macro list (whose
 * scramble gains a fifth draw) cannot move what the pin compares.
 */
class EchoTest {

    private val kick = Thump.render(ThumpVoice.KICK)
    private val snare = Thump.render(ThumpVoice.SNARE)
    private val choir = Vox.render(VoxVoice.CHOIR)

    private val sources = linkedMapOf("kick" to kick, "snare" to snare, "choir" to choir)

    /** Shipped settings, as literals: the bank's `slapback`, SIREN's `LANDING_ECHO`, ECHO's defaults, and three draws made once with Random(0..2). */
    private val settings: Map<String, Map<String, Float>> = linkedMapOf(
        "slapback" to mapOf("TIME" to 0.25f, "REPEAT" to 0.3f, "TONE" to 0.4f, "MIX" to 0.5f),
        "landing" to mapOf("TIME" to 0.45f, "REPEAT" to 0.55f, "TONE" to 0.5f, "MIX" to 0.45f),
        "defaults" to emptyMap(),
        // PASTE the three "randomN" lines from the printer here.
    )

    /** Recorded on the base before HEAD existed (plan Task 1). Never re-record: a mismatch means HEAD 0 is no longer ECHO. */
    private val PINS: Map<String, String> = mapOf(
        // PASTE the 18 "source/setting" lines from the printer here.
    )

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `HEAD 0 is ECHO as it shipped, byte for byte - absent, zero, under the off point and at it`() {
        assertEquals(18, PINS.size, "18 pins: three sources by six settings")
        for ((src, snip) in sources) for ((set, m) in settings) {
            val want = PINS.getValue("$src/$set")
            assertEquals(want, hash(Echo.process(snip, m).samples), "$src/$set: HEAD absent is no longer the shipped ECHO")
            // 0.001f is Echo.HEAD_OFF; Task 2 swaps the constant in once it exists.
            for (off in listOf(0f, 0.0005f, 0.001f)) {
                assertEquals(want, hash(Echo.process(snip, m + ("HEAD" to off)).samples), "$src/$set at HEAD $off")
            }
        }
    }
}
```

The test passes on today's ECHO, because the shipped `Echo.process` ignores a key it does not know.

- [ ] **Step 4: Run it green, delete the printer, commit.**
  - Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.EchoTest'`. Expect exit 0.
  - Delete `EchoPinsPrinter.kt`.
  - Commit: "Pin ECHO's output before it gains a HEAD knob". In the body, name the 18 renders and say the hashes were recorded on the base and are never re-recorded.

---

### Task 2: HEAD on ECHO

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Echo.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/EchoTest.kt`

**Interfaces:**
- Consumes:
  - `MacroSpec(name, default, neutral = 0f, law = FadeLaw.LINEAR)` and `Echo.delayFrames(time, rate)`.
  - `FxChain.fromJsonText` / `toJsonText` and `Treatments.chain("slapback")`.
  - `Magnet.render(MagnetVoice.JANGLE)` and `Magnet.landingChain(MagnetVoice.JANGLE)`.
- Produces:
  - `Echo.MACROS`, whose fifth entry is `HEAD` (default 0, neutral 0).
  - `Echo.HEAD_OFF = 0.001f` and `Echo.HEAD_SEC = 0.12f`.
  - `internal fun Echo.reach(macros): Float`.
  - `internal fun Echo.headFrames(delay, sampleRate): Int`.
  - `internal fun Echo.window(f, head): Float`.

- [ ] **Step 1: Write the failing tests.** Add these imports to `EchoTest`: `kotlin.math.PI`, `kotlin.math.abs`, `kotlin.math.log10`, `kotlin.math.sin`, `kotlin.math.sqrt`, `kotlin.random.Random`, `java.util.Locale`, `kotlin.test.assertContentEquals`, `kotlin.test.assertNotEquals` and `kotlin.test.assertTrue`. Then add the helpers and tests below. Also change the `0.001f` in Task 1's test to `Echo.HEAD_OFF`, and delete its comment.

```kotlin
    /** `delayed` at full, spelled out so this file does not lean on Treatments: the values the spec measured. */
    private val recipe = mapOf("TIME" to 0.75f, "REPEAT" to 0.85f, "TONE" to 0.35f, "MIX" to 0.75f, "HEAD" to 1f)

    private fun channel(s: Snip, ch: Int): FloatArray = FloatArray(s.frameCount) { s.samples[it * s.channels + ch] }
    private fun doubled(s: Snip): Snip { val m = channel(s, 0); return Snip(FloatArray(m.size * 2) { m[it / 2] }, 2, s.sampleRate) }
    private fun hardLeft(s: Snip): Snip { val m = channel(s, 0); return Snip(FloatArray(m.size * 2) { if (it % 2 == 0) m[it / 2] else 0f }, 2, s.sampleRate) }
    private fun mono(s: Snip): Snip = Snip(channel(s, 0), 1, s.sampleRate)
    private fun ms(rate: Int, x: Float): Int = (x * rate / 1000f).toInt()

    /** The rms of every channel over frames [from, to). */
    private fun rms(s: Snip, from: Int, to: Int): Double {
        var acc = 0.0; var n = 0
        for (f in from until minOf(to, s.frameCount)) for (ch in 0 until s.channels) { val v = s.samples[f * s.channels + ch].toDouble(); acc += v * v; n++ }
        return sqrt(acc / maxOf(1, n))
    }

    /** The peak of every channel over frames [from, to). */
    private fun peak(s: Snip, from: Int, to: Int): Double {
        var p = 0.0
        for (f in from until minOf(to, s.frameCount)) for (ch in 0 until s.channels) p = maxOf(p, abs(s.samples[f * s.channels + ch].toDouble()))
        return p
    }

    private fun db(a: Double, b: Double): Double = 20 * log10(maxOf(a, 1e-12) / maxOf(b, 1e-12))

    /**
     * The spec's gap level: the whole output's rms, dry note included, from 10 ms after the head's first repeat ends
     * to 5 ms before the second repeat lands, against the rms of the first repeat's first 20 ms. Lower is more discrete.
     */
    private fun gap(out: Snip, macros: Map<String, Float>): Double {
        val r = out.sampleRate
        val delay = Echo.delayFrames(macros.getValue("TIME"), r)
        val head = Echo.headFrames(delay, r)
        return db(rms(out, delay + head + ms(r, 10f), 2 * delay - ms(r, 5f)), rms(out, delay, delay + ms(r, 20f)))
    }

    /**
     * [gap]'s two windows on a mono pad's wet alone, `(out / k - dry) / mix`, with k read off the loudest dry frame
     * before the first repeat (where the output is the dry times the peak match's one gain).
     */
    private fun wetGap(dry: Snip, macros: Map<String, Float>): Double {
        val r = dry.sampleRate
        val out = Echo.process(dry, macros)
        val delay = Echo.delayFrames(macros.getValue("TIME"), r)
        val head = Echo.headFrames(delay, r)
        var f0 = 0
        for (f in 0 until delay) if (abs(dry.samples[f]) > abs(dry.samples[f0])) f0 = f
        val k = out.samples[f0] / dry.samples[f0]
        val mix = macros.getValue("MIX")
        val wet = Snip(FloatArray(out.frameCount) { f -> (out.samples[f] / k - (if (f < dry.frameCount) dry.samples[f] else 0f)) / mix }, 1, r)
        return db(rms(wet, delay + head + ms(r, 10f), 2 * delay - ms(r, 5f)), rms(wet, delay, delay + ms(r, 20f)))
    }

    /** A held 220 Hz tone with a 2 ms click at its start: a picked note with nothing else in it. */
    private fun heldTone(seconds: Float = 2f, rate: Int = 44_100): Snip {
        val n = (seconds * rate).toInt()
        val click = ms(rate, 2f)
        return Snip(FloatArray(n) { i -> (0.3 * sin(2 * PI * 220.0 * i / rate)).toFloat() + if (i < click) 0.6f else 0f }, 1, rate)
    }

    // ---------------------------------------------------------------- the precedent's pins (EnsembleSectionTest's three)

    @Test
    fun `HEAD off renders the shipped samples on a mono, a doubled and a hard-left pad`() {
        for (signal in listOf(mono(snare), doubled(snare), hardLeft(snare))) for ((set, m) in settings) {
            val shipped = Echo.process(signal, m)
            for (off in listOf(0f, 0.0005f, Echo.HEAD_OFF)) {
                assertContentEquals(shipped.samples, Echo.process(signal, m + ("HEAD" to off)).samples, "$set at HEAD $off on ${signal.channels} channels")
            }
        }
    }

    @Test
    fun `a recipe written before HEAD existed reads back without it, writes back without it, and renders as slapback`() {
        val text = """{"fx":1,"reverse":false,"echo":{"TIME":0.25,"REPEAT":0.3,"TONE":0.4,"MIX":0.5}}"""
        val chain = FxChain.fromJsonText(text)
        assertEquals(null, chain.section("echo")?.get("HEAD"))
        assertTrue("HEAD" !in chain.toJsonText(), "reading an old recipe and writing it back added the key")
        assertContentEquals(Treatments.chain("slapback").process(snare).samples, chain.process(snare).samples)
    }

    @Test
    fun `HEAD is the last macro, off by default with neutral 0, and the first four random draws stay where they were`() {
        assertEquals(listOf("TIME", "REPEAT", "TONE", "MIX", "HEAD"), Echo.MACROS.map { it.name })
        assertEquals(0f, Echo.defaults().getValue("HEAD"))
        assertEquals(0f, Echo.MACROS.last().neutral)
        val rolled = Echo.scramble(Random(0))
        val r = Random(0)
        for (name in listOf("TIME", "REPEAT", "TONE", "MIX")) assertEquals(r.nextFloat(), rolled.getValue(name), "the $name draw moved")
    }

    // ---------------------------------------------------------------- HEAD on

    @Test
    fun `HEAD on keeps the section contract - deterministic, stereo-identical, no widening, mono stays mono`() {
        assertContentEquals(Echo.process(snare, recipe).samples, Echo.process(snare, recipe).samples, "not deterministic")
        for (s in listOf(kick, choir)) {
            val out = Echo.process(doubled(s), recipe)
            assertContentEquals(channel(out, 0), channel(out, 1), "a doubled pad's channels diverged")
        }
        val left = Echo.process(hardLeft(snare), recipe)
        assertTrue(channel(left, 1).all { it == 0f }, "a hard-left pad gained a right channel")
        assertEquals(1, Echo.process(mono(snare), recipe).channels, "a mono pad was widened")
    }

    @Test
    fun `the first repeat's onset is the same with HEAD on and off, up to the peak match's one gain`() {
        val on = Echo.process(choir, recipe)
        val off = Echo.process(choir, recipe + ("HEAD" to 0f))
        val delay = Echo.delayFrames(recipe.getValue("TIME"), choir.sampleRate)
        val head = Echo.headFrames(delay, choir.sampleRate)
        val end = (delay + head - head / 3) * choir.channels
        var p = 0
        for (i in 0 until end) if (abs(off.samples[i]) > abs(off.samples[p])) p = i
        val k = on.samples[p] / off.samples[p]
        for (i in 0 until end) assertEquals(k * off.samples[i], on.samples[i], 1e-5f, "sample $i of the onset")
    }

    @Test
    fun `HEAD makes the repeats discrete - the gap after the first repeat falls`() {
        val off = recipe + ("HEAD" to 0f)
        val choirOn = gap(Echo.process(choir, recipe), recipe)
        val choirOff = gap(Echo.process(choir, off), off)
        println(String.format(Locale.ROOT, "DELAY gap, choir: HEAD 0 %.1f dB, HEAD 1 %.1f dB", choirOff, choirOn))
        assertTrue(choirOff - choirOn >= 7.0, "HEAD opened only ${choirOff - choirOn} dB on the choir (measured 9.8)")
        // A held tone never decays, so with the dry note included its gap is set by the delay's phase against the tone,
        // not by HEAD (at 220 Hz the plain repeat lands near antiphase: HEAD 0 -5.0 dB, HEAD 1 +3.2). Its wet alone shows HEAD.
        val tone = heldTone()
        val toneOn = wetGap(tone, recipe)
        val toneOff = wetGap(tone, off)
        println(String.format(Locale.ROOT, "DELAY wet gap, held tone: HEAD 0 %.1f dB, HEAD 1 %.1f dB", toneOff, toneOn))
        assertTrue(toneOn < -60.0, "with HEAD on, a held tone's wet between repeats is only $toneOn dB under its first repeat")
        assertTrue(toneOff >= -6.7, "with HEAD off, a held tone's body should keep feeding the repeats (measured -3.7 dB; the bar is 3 dB under), got $toneOff")
    }

    @Test
    fun `the head window shrinks with a short TIME so a repeat of the head ends before the next lands`() {
        val r = 44_100
        assertEquals(2116, Echo.headFrames(Echo.delayFrames(0f, r), r))
        assertEquals(4100, Echo.headFrames(Echo.delayFrames(0.375f, r), r))
        assertEquals(5292, Echo.headFrames(Echo.delayFrames(0.75f, r), r))
        val tone = heldTone()
        val m = recipe + ("TIME" to 0f)
        val out = Echo.process(tone, m)
        val delay = Echo.delayFrames(0f, r)
        val head = Echo.headFrames(delay, r)
        // Before the first repeat the output is the dry times the peak match's gain: read the gain off the loudest dry frame there.
        var f0 = 0
        for (f in 0 until delay) if (abs(tone.samples[f]) > abs(tone.samples[f0])) f0 = f
        val k = out.samples[f0] / tone.samples[f0]
        val mix = m.getValue("MIX")
        val wet = FloatArray(out.frameCount) { f -> (out.samples[f] / k - (if (f < tone.frameCount) tone.samples[f] else 0f)) / mix }
        val wetPeak = wet.maxOf { abs(it) }.toDouble()
        val worst = (delay + head + ms(r, 2f)..2 * delay - ms(r, 1f)).maxOf { abs(wet[it]) }.toDouble()
        assertTrue(db(worst, wetPeak) < -60.0, "the wet between repeats at TIME 0 is ${db(worst, wetPeak)} dB under its peak")
    }

    @Test
    fun `HEAD is stretched by 1_5 so the first tap still feeds the head alone`() {
        assertEquals(1f, Echo.reach(mapOf("HEAD" to 2f / 3f)))
        assertEquals(1f, Echo.reach(mapOf("HEAD" to 0.7f)), "the recipe at AMT 70")
        assertEquals(0.9f, Echo.reach(mapOf("HEAD" to 0.6f)), 1e-6f)
        assertEquals(0f, Echo.reach(mapOf("HEAD" to Echo.HEAD_OFF)))
        assertEquals(0f, Echo.reach(emptyMap()))
        assertContentEquals(Echo.process(choir, recipe).samples, Echo.process(choir, recipe + ("HEAD" to 0.7f)).samples)
        assertNotEquals(Echo.process(choir, recipe).samples.toList(), Echo.process(choir, recipe + ("HEAD" to 0.6f)).samples.toList())
    }

    // ---------------------------------------------------------------- the numbers the recipe's MIX rests on

    @Test
    fun `the jangle's pick and the snare's repeat at the first tap - the figures MIX 0_75 was chosen from`() {
        val jangle = Magnet.landingChain(MagnetVoice.JANGLE).process(Magnet.render(MagnetVoice.JANGLE))
        val chug = Magnet.landingChain(MagnetVoice.CHUG).process(Magnet.render(MagnetVoice.CHUG))
        fun at(mix: Float, amount: Float) = (recipe + ("MIX" to mix)).mapValues { it.value * amount }
        // The pick over the body: the first repeat's first 25 ms over the 20 ms ending 5 ms before it, peak.
        fun pick(out: Snip, delay: Int) = db(peak(out, delay, delay + ms(out.sampleRate, 25f)), peak(out, delay - ms(out.sampleRate, 25f), delay - ms(out.sampleRate, 5f)))
        // The repeat against the hit: the first repeat's first 20 ms over the hit's first 20 ms, rms.
        fun repeat(out: Snip, delay: Int) = db(rms(out, delay, delay + ms(out.sampleRate, 20f)), rms(out, 0, ms(out.sampleRate, 20f)))
        for (amount in listOf(1f, 0.7f)) for (mix in listOf(0.5f, 0.75f, 1f)) {
            val m = at(mix, amount)
            val delay = Echo.delayFrames(m.getValue("TIME"), 44_100)
            println(String.format(Locale.ROOT, "DELAY probe AMT %.0f, recipe MIX %.2f: jangle pick %.1f dB, snare repeat %.1f dB, choir gap HEAD 0 %.1f / HEAD 1 %.1f dB",
                amount * 100, mix, pick(Echo.process(jangle, m), delay), repeat(Echo.process(snare, m), delay),
                gap(Echo.process(choir, m + ("HEAD" to 0f)), m), gap(Echo.process(choir, m), m)))
            for ((name, s) in linkedMapOf("choir" to choir, "chug" to chug, "jangle" to jangle, "snare" to snare)) {
                println(String.format(Locale.ROOT, "DELAY gap AMT %.0f, recipe MIX %.2f, %s: HEAD 0 %.1f dB, HEAD 1 %.1f dB",
                    amount * 100, mix, name, gap(Echo.process(s, m + ("HEAD" to 0f)), m), gap(Echo.process(s, m), m)))
            }
        }
        val first = at(0.75f, 0.7f)
        val delay = Echo.delayFrames(first.getValue("TIME"), 44_100)
        assertEquals(3.5, pick(Echo.process(jangle, first), delay), 0.5, "the jangle's pick at AMT 70 (spec: 3.5 dB)")
        assertEquals(-7.8, repeat(Echo.process(snare, first), delay), 0.5, "the snare's repeat at AMT 70 (spec: -7.8 dB)")
    }
```

Run `./gradlew :synth:test --tests 'com.snipsnap.synth.EchoTest'`. Expect a compile failure, because `Echo.HEAD_OFF`, `reach` and `headFrames` do not exist yet. That is the failing state.

- [ ] **Step 2: Implement HEAD in `Echo.kt`.**
  - Add the fifth `MacroSpec`, the two constants and the three helpers.
  - Add the one factor in the loop. Nothing else in `process` changes.
  - Add `import kotlin.math.PI` and `import kotlin.math.cos`.

```kotlin
    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TIME", 0.4f),    // 60..350 ms between repeats
        MacroSpec("REPEAT", 0.4f),  // feedback: one slapback up to a long trail
        MacroSpec("TONE", 0.5f),    // how dark each repeat gets
        MacroSpec("MIX", 0.4f),     // wet level against the untouched dry
        MacroSpec("HEAD", 0f),      // what feeds the repeats: 0 the whole note, 1 only its first HEAD_SEC
    )

    /** The most tail any setting may add, seconds. The one-shot promise. */
    const val MAX_TAIL_SECONDS = 1.1f

    /** At or below this HEAD is off: the whole note feeds the repeats, through a factor of exactly 1f. */
    const val HEAD_OFF = 0.001f

    /** The head a full HEAD feeds the repeats: the pick and a little of the body, its last third faded. */
    const val HEAD_SEC = 0.12f
```

```kotlin
    /**
     * How far HEAD has moved the feed toward the head alone: 0 at or below [HEAD_OFF], then HEAD stretched by 1.5
     * and capped at 1, as WAH's weights are, so the pad sheet's first tap (HEAD 0.7) still feeds the head alone.
     */
    internal fun reach(macros: Map<String, Float>): Float {
        val head = macros["HEAD"] ?: 0f
        return if (head <= HEAD_OFF) 0f else (1.5f * head).coerceIn(0f, 1f)
    }

    /** The head's length in frames: [HEAD_SEC], never past 0.8 of [delay], so a repeat of the head ends before the next lands. */
    internal fun headFrames(delay: Int, sampleRate: Int): Int =
        minOf((HEAD_SEC * sampleRate).toInt(), (0.8f * delay).toInt())

    /** The head window at frame [f]: 1, then a raised-cosine fade over the last third of [head] frames, then 0. */
    internal fun window(f: Int, head: Int): Float {
        val fade = head / 3
        return when {
            f >= head -> 0f
            f < head - fade -> 1f
            else -> (0.5 + 0.5 * cos(PI * (f - (head - fade)) / fade)).toFloat()
        }
    }
```

In `process`, after `toneHz`:

```kotlin
        val reach = reach(m)
        val head = headFrames(delay, snip.sampleRate)
```

In the loop, replace `d[f] = lp.lp(dry + feedback * fed, toneHz)` with:

```kotlin
                // HEAD's feed: the whole note at reach 0 (a factor of exactly 1f), the head alone at reach 1.
                val feed = 1f - reach * (1f - window(f, head))
                d[f] = lp.lp(dry * feed + feedback * fed, toneHz)
```

`out[...] = dry + mix * wet` stays as it is. The dry note is added back untouched at full level.

- [ ] **Step 3: Read the measurements.**
  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.EchoTest' -i 2>&1 | grep 'DELAY '`.
  - The held tone's wet gap reads about HEAD 0 −3.7 dB and HEAD 1 under −140 dB (a dry run measured −230.2). Its bars are fixed in the test at −6.7 dB (the HEAD 0 measurement less 3 dB, the spec's rule) and −60 dB. The spec's Tests section already says the held tone is read on the wet alone, and why.
  - The choir's gap line must read about HEAD 0 −0.5 dB and HEAD 1 −10.3 dB.
  - The probe lines must reproduce the spec's MIX table, each to ± 0.1 dB:
    - the jangle 1.8, 2.4 / 3.1, 3.5 / 4.3, 4.6;
    - the snare −8.1, −11.3 / −4.6, −7.8 / −2.1, −5.3.
  - The MIX 0.75 gap lines must reproduce the spec's discreteness table, each to ± 0.1 dB (AMT 100, then AMT 70):
    - the choir −0.5 / −10.3 and −4.6 / −9.7;
    - the chug −0.7 / −2.4 and −0.2 / −0.9;
    - the jangle 0.0 / −0.7 and −0.5 / −1.9;
    - the snare −39.9 / silence and −36.8 / −48.1.
  - Record any larger difference in the commit body.
  - While `PedalListensGenerator.attackEcho` still exists (Task 6 deletes it), print once, in a scratch test you then delete, the liked clip's figures by the same metrics: `PedalListensGenerator.attackEcho(dry, PedalListensGenerator.Feed.HEAD, PedalListensGenerator.DELAYED.echo!!)` on the jangle (the spec: 5.3 dB) and on the choir's mono fold (the spec: 0.5 dB peak, 3.0 dB rms). It lifts each channel on its own, which the spec's 2026-10-23 probe did not. Record them in the commit body.

- [ ] **Step 4: Run green, then mutation-check.**
  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.EchoTest' --tests 'com.snipsnap.synth.FxTest'`. Expect exit 0.
  - FxTest's random rolls now draw HEAD too. Their contract (finite, within −1..1, peak within 0.05) holds because ECHO's own peak match runs on any feed. No exemption may be added to `contractExcluded` or `stereoExcluded`.
  - Then make each mutation below. Run `EchoTest` after each one and see the named test fail (others may fail too). Revert each before the next. The JSON precedent pin is structural (the key is absent from the text), so it has no mutation in `Echo.kt`:

| Mutation in `Echo.kt` | Test that must fail |
|---|---|
| `val feed = 1f - reach * (1f - window(f, head)) + 1e-7f` | HEAD 0 is ECHO as it shipped |
| `window(f, head + ch)` (needs `ch` in scope: it is) | HEAD on keeps the section contract |
| `f < head - fade - 200 -> 1f` | the first repeat's onset |
| `return 0f` as `reach`'s body | HEAD makes the repeats discrete |
| drop `0.8f * delay` from `headFrames` (use `HEAD_SEC` alone) | the head window shrinks |
| `1f * head` for `1.5f * head` | HEAD is stretched by 1_5 |
| `out[f * snip.channels + ch] = dry + wet` (MIX dropped) | the jangle's pick and the snare's repeat |
| `head < HEAD_OFF` for `head <= HEAD_OFF` in `reach` | HEAD off renders the shipped samples on a mono, a doubled and a hard-left pad |
| `MacroSpec("HEAD", 0f)` moved to the front of `MACROS` | HEAD is the last macro, off by default |

- [ ] **Step 5: Commit.** Commit message: "Give ECHO a HEAD knob that feeds its repeats only the note's head". The body says:
  - what HEAD does;
  - why HEAD 0 is bit for bit (a factor of exactly 1f, proved by the Task 1 pins);
  - the measured choir gap, the held tone's wet gaps, both probe tables and the liked clip's figures;
  - that FxTest's random rolls now reach HEAD with no exemption.

---

### Task 3: The `delayed` character

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt` (`EXTRA`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/TreatmentsTest.kt`

**Interfaces:**
- Consumes: `FxChain(echo = …)`.
- Produces: `Treatments.chain("delayed")` = echo {TIME 0.75, REPEAT 0.85, TONE 0.35, MIX 0.75, HEAD 1.0}.

- [ ] **Step 1: Write the failing test edits.**
  - In TreatmentsTest's names test (`the named characters are the bank's five and then the extras…`), append `"delayed"` after `"rockwahed"`.
  - In `the pedal recipes set only their own section, and none sits on a neutral`, add `"delayed" to "echo"`, and add after its loop `assertTrue(Treatments.chain("delayed", 0f).isBypass, "delayed at AMT 0 is not a bypass")`.
  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.TreatmentsTest'`. Expect a failure: `delayed` is unknown.

- [ ] **Step 2: Append the character.** Re-read `EXTRA`, then add this at its end, after `rockwahed`:

```kotlin
        // A delay pedal: ECHO's long, dark repeats (225 ms apart, feedback 0.66, darkening) fed only the note's
        // first 120 ms, so a held note's attack comes back as a repeat. MIX 0.75 is the owner's pick
        // (docs/superpowers/specs/2026-10-22-delay-head-design.md). The DELAY chip is this character once its gate passes.
        "delayed" to FxChain(echo = mapOf("TIME" to 0.75f, "REPEAT" to 0.85f, "TONE" to 0.35f, "MIX" to 0.75f, "HEAD" to 1.0f)),
```

- [ ] **Step 3: Run green.**
  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.TreatmentsTest' --tests 'com.snipsnap.synth.FxTest'`. Expect exit 0.
  - FxTest's `AMT scales every section a treatment sets` now covers `delayed`. So does TreatmentsTest's door-equivalence loop over `Treatments.names`.

- [ ] **Step 4: Commit.** Commit message: "Add the delayed character: ECHO's long repeats fed by the note's head". Cite the spec and the owner's MIX 0.75.

---

### Task 4: DELAY in the pedal library, ungated

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pedals.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PedalsTest.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PedalBoardTest.kt`

**Interfaces:**
- Consumes: `Pedals.Entry(word, key, chain, side, movable, gated)` and `PedalBoard(picks)`. Also PedalBoardTest's `pick(key, amount, side?)` and its `snare`.
- Produces: `Pedals.byKey("delayed")`, the word DELAY, side POST, not movable, ungated.

- [ ] **Step 1: Write the failing test edits.**
  - In PedalsTest, rename `day one - DRIVE and COMP draw chips, WAH and RIG wait for their gates` to `DRIVE and COMP draw chips, WAH, RIG and DELAY wait for their gates`.
  - In that test, change the entries list to `listOf("DRIVE", "COMP", "WAH", "RIG", "DELAY")`. The gated list stays `listOf("DRIVE", "COMP")`.
  - In PedalBoardTest, add:

```kotlin
    @Test
    fun `RIG and DELAY on one board run two echoes, one per pass, inside the board's one tail budget`() {
        val board = PedalBoard(listOf(pick("rig", 1f), pick("delayed", 1f)))
        assertEquals("rig", board.owner(PedalSide.AMP, "echo")!!.key, "RIG's slapback is the AMP pass's echo")
        assertEquals("delayed", board.owner(PedalSide.POST, "echo")!!.key, "DELAY is the POST pass's echo")
        val out = board.process(snare)
        assertTrue(
            out.frameCount <= snare.frameCount + (FxChain.MAX_CHAIN_TAIL_SECONDS * snare.sampleRate).toInt(),
            "two tail-making picks ran past the board's budget",
        )
    }
```

  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.PedalsTest' --tests 'com.snipsnap.synth.PedalBoardTest'`. Expect failures: `delayed` is not a pedal.

- [ ] **Step 2: Append the entry.** In `Pedals.entries`, after RIG:

```kotlin
        // A delay pedal: the `delayed` character, ECHO's repeats fed by the note's head. After the amp, where a rig
        // puts a delay; not movable. Gate pending (docs/superpowers/specs/2026-10-22-delay-head-design.md).
        Entry("DELAY", "delayed", character("delayed"), PedalSide.POST, movable = false, gated = false),
```

Then update the object's KDoc sentence that lists the day-one entries: add "and DELAY, added after its own spec, awaiting its gate".

- [ ] **Step 3: Run green.**
  - Run `./gradlew :synth:test --tests 'com.snipsnap.synth.PedalsTest' --tests 'com.snipsnap.synth.PedalBoardTest'`, then `./gradlew :shell:cleanTest :shell:test --tests 'com.snipsnap.shell.PadSheetTest'`. Expect exit 0 from both.
  - PedalBoardTest's `a one-pick board is exactly the chip` now covers DELAY at 0.7 and 1.
  - PadSheetTest is unchanged and passes: DELAY is ungated, so row seven is still DRIVE and COMP, and the sheet still draws 33 chips.

- [ ] **Step 4: Commit.** Commit message: "Add DELAY to the pedal library, ungated until its gate". The body says:
  - its side and why;
  - that it draws no chip;
  - that `treat --pedals delayed` and saved kits reach it;
  - the RIG + DELAY budget test, a two-pick form of the three-tail-pick budget test the library's R1 deferred while RIG was its only tail-making entry.

---

### Task 5: The documents

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Echo.kt` (KDoc only)
- Modify: `README.md`
- Modify: `docs/CLI.md`
- Modify: `docs/superpowers/specs/2026-10-21-pedal-library-and-board-design.md`
- Modify: `docs/SYNTH_ROADMAP.md`

- [ ] **Step 1: `Echo.kt`'s object KDoc.** Add a paragraph after the existing one:

```kotlin
 *
 * HEAD chooses what feeds the repeats: 0 the whole note (ECHO as it always was, bit for bit), 1 only the note's
 * first [HEAD_SEC], its last third faded, so a held note's attack comes back as a repeat instead of the whole
 * note smearing into itself. It is stretched by 1.5 as WAH's weights are, so the pad sheet's first tap still feeds
 * the head alone, and the window shrinks with a short TIME so a repeat of the head ends before the next lands
 * (docs/superpowers/specs/2026-10-22-delay-head-design.md).
```

Keep one KDoc block. The shell law forbids stacked KDoc.

- [ ] **Step 2: `README.md`.** In the rack list, the ECHO entry wraps after "and" (`README.md` around line 1018). Change "ECHO (one delay line and⏎one filter, repeats darkening as they fade)" to "ECHO (one delay line and⏎one filter, repeats darkening as they fade; HEAD feeds the repeats only the note's first 120 ms, so a held note's attack comes back as a repeat)", where ⏎ is the existing line break. Rewrap the paragraph to its neighbours' width if you like.

- [ ] **Step 3: `docs/CLI.md`.** Three edits; the quoted text wraps where ⏎ shows.
  - In `treat`'s character list, change "as the note dies) and `rockwahed` (one heel-to-toe-to-heel⏎gesture, then the wah waits at the heel)." to "as the note dies), `rockwahed` (one heel-to-toe-to-heel⏎gesture, then the wah waits at the heel) and `delayed` (a delay pedal: ECHO's long, dark repeats 225 ms apart, fed only the note's first 120 ms so a held note's attack comes back as a repeat)."
  - In the `--pedals` paragraph, change "`wahed`, and `rig` (a board: compressor, drive, slapback)" to "`wahed`, `delayed` (after the amp; no chip until its gate), and `rig` (a board: compressor, drive, slapback)".
  - In "On the phone the same characters, less …", change "`amped` (its chip is a later layout decision) and the three wah⏎characters (the WAH chip is not on the pad sheet yet; `touchwahed` and⏎`rockwahed` are CLI and saved kits only), sit" to "`amped` (its chip is a later layout decision), the three wah⏎characters (the WAH chip is not on the pad sheet yet; `touchwahed` and⏎`rockwahed` are CLI and saved kits only) and `delayed` (the DELAY chip waits for its listening gate), sit".

- [ ] **Step 4: The library design.** After its "As built (R1, …)" section, add a dated section. Leave the R1 record as it is.

```markdown
## After R1

- **2026-10-23, DELAY** (`2026-10-22-delay-head-design.md`): one entry appended after RIG, `Entry("DELAY", "delayed", …, POST, movable = false, gated = false)`. The library now holds two tail-making entries, RIG and DELAY; `PedalBoardTest` holds their shared tail budget (a two-pick form of the three-tail-pick test R1 deferred). `PedalsTest`'s entries list gained DELAY; the gated list, row seven and `PadSheetTest`'s pins move only when its gate passes.
```

Use the date the task is done, not necessarily 2026-10-23.

- [ ] **Step 5: `docs/SYNTH_ROADMAP.md`, the S30 row.** Before the row's "Specs:" sentence, add one sentence:

> DELAY (`docs/superpowers/specs/2026-10-22-delay-head-design.md`): on the long-note sheet (2026-10-22) the owner named the plain DELAY at 100 and the head-fed echo at 120 ms the most like a delay, kept ROLL where it is as a secondary option, and asked for HEAD on ECHO. ECHO gains HEAD, a macro and not a section. At 0 it is ECHO bit for bit, pinned by 18 hashes recorded before the change; at 1 the repeats are fed only the note's first 120 ms. It ships with the `delayed` character (MIX 0.75, the owner's pick) and an ungated DELAY entry in the pedal library. The long-note sheet and its prototypes are retired, and the DELAY gate is on the Stage 1b page.

Add the spec to the row's "Specs:" list.

- [ ] **Step 6: Commit.** Commit message: "Document ECHO's HEAD knob and the DELAY pedal". No test run is needed beyond a compile: `./gradlew :synth:compileKotlin`.

---

### Task 6: The gate's clips and page

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PedalListensGenerator.kt`
- Modify: `synth/src/test/resources/audition/pedal-listens.html`

**Interfaces:**
- Consumes: `Treatments.apply("delayed", dry, amount)`, `Treatments.fade(chain, amount)`, `Magnet.landingChain(MagnetVoice.CHUG)` and `FxChain.MAX_CHAIN_TAIL_SECONDS`.
- Produces: 28 clips under the ids `gate_pad`, `gate_delay_70`, `gate_delay_100`, `gate_head0_70`, `gate_head0_100`, `gate_plain_70` (all four sources) and `gate_mix100_70`, `gate_mix50_70` (snare and jangle). It also produces the page's `delayGateSheet()` with question ids `dly_*`.

- [ ] **Step 1: Retire the long-note sheet from the generator.**
  - Delete sheet (5)'s loop.
  - Delete `attackEcho`, `Feed`, `HEAD_SEC`, `HEAD_FADE_SEC`, `DUCK_DB`, `MAX_FEED_GAIN` and `ROLL_BPM`. `ROLL_BPM` is used only by that sheet.
  - Delete any import left unused.
  - Rewrite item (5) of the object KDoc to describe the gate (Step 2). Change its opening "Four sheets." to "Five sheets." (it already lists five), and change "the 48 clips as its files" (or whatever the KDoc says of the files) to say the page carries the clips of sheets (1) to (4), the gate's 28, and, on the published artifact only, the ten retired long-note clips.
  - Do not touch sheets (1) to (4): their 48 clips are already published, and their verdicts point at those bytes.

- [ ] **Step 2: Add the gate's sheet.** After sheet (4), as the new (5):

```kotlin
        // (5) The DELAY gate: `delayed` as built (ECHO with HEAD), HEAD off at the same level (the recipe with HEAD 0, so
        // HEAD alone is heard), the plain DELAY the owner passed, and the owner's MIX 0.75 between its two neighbours.
        val chug = Magnet.landingChain(MagnetVoice.CHUG).process(Magnet.render(MagnetVoice.CHUG))
        val built = Treatments.chain("delayed").echo!!
        fun variant(change: Pair<String, Float>) = FxChain(echo = built + change)
        val capFrames = (FxChain.MAX_CHAIN_TAIL_SECONDS * snare.sampleRate).toInt()
        fun gate(source: String, dry: Snip, id: String, label: String, out: Snip) {
            write(source, id, label, out)
            // The spec's tail-cap hazard, re-measured on the clips the owner hears: where the rack's cut lands, if it does.
            if (out.frameCount >= dry.frameCount + capFrames) {
                val r = out.sampleRate
                val end = out.frameCount - (0.01f * r).toInt()
                var cut = 0f
                for (i in (end - (0.02f * r).toInt()) * out.channels until end * out.channels) cut = maxOf(cut, kotlin.math.abs(out.samples[i]))
                println("gate cap: $source/$id cut ${"%.1f".format(Locale.ROOT, 20 * kotlin.math.log10(out.peak() / maxOf(cut, 1e-12f)))} dB under the peak")
            } else println("gate cap: $source/$id ends before the cap")
        }
        for ((name, dry) in linkedMapOf("SNARE" to snare, "CHOIR" to choir, "JANGLE" to jangle, "CHUG" to chug)) {
            gate(name, dry, "gate_pad", "$name AS THE KIT HAS IT", dry)
            for (amount in AMOUNTS) gate(name, dry, "gate_delay_${pct(amount)}", "$name DELAY AT ${pct(amount)}, AS BUILT", Treatments.apply("delayed", dry, amount).snip)
            for (amount in AMOUNTS) gate(name, dry, "gate_head0_${pct(amount)}", "$name DELAY AT ${pct(amount)} WITH HEAD OFF, THE SAME LEVEL", Treatments.fade(variant("HEAD" to 0f), amount).process(dry))
            gate(name, dry, "gate_plain_70", "$name THE PLAIN DELAY FROM YOUR FIRST LISTEN (MIX 50, HEAD OFF), AT 70", Treatments.fade(DELAYED, 0.7f).process(dry))
            if (name == "SNARE" || name == "JANGLE") for (mix in listOf(1f, 0.5f)) {
                gate(name, dry, "gate_mix${pct(mix)}_70", "$name DELAY AT 70 WITH MIX ${pct(mix)}", Treatments.fade(variant("MIX" to mix), 0.7f).process(dry))
            }
        }
```

Change the count check to:

```kotlin
        check(entries.size == 76) { "expected 76 clips (four short pads x 5, FADE IN 10, VIBRATO 6, DELAY 12, the DELAY gate 28), wrote ${entries.size}" }
```

Notes on the snippet:
- `DELAYED` is sheet (4)'s plain recipe, MIX 0.5 with no HEAD. It stays, because sheet (4) still uses it.
- The cut is the peak of the 20 ms before the rack's 10 ms fade, against the clip's peak: the review's probe's window on the capped clip. The spec's 2026-10-23 probe read the 20 ms before the cut on the uncapped render; the two agree (the spec's figures were reproduced both ways).
- `"%.1f".format(Locale.ROOT, …)` keeps the format law's habit, even in test source.

- [ ] **Step 3: The page.** In `pedal-listens.html`:

  **3a. Remove the long-note sheet and its leftovers.**
  - Delete `longNoteSheet()`, and `LONG4` if nothing else uses it.
  - In `verdictSheet()`, delete the `s1b_attack_section` question, and `SECTION4` if it is then unused: its subject (ROLL and the attack-fed echo as a section) leaves the page with the long-note sheet, and the owner's request for a HEAD knob settled it (spec decision 1).
  - Keep `s1b_slate`, `SLATE4` and `s1b_floor`: the spec does not retire them.

  **3b. Add the option sets** next to the others:

```js
const HEAD3 = [['head', 'Head on is better'], ['same', 'About the same'], ['off', 'Head off is better']];
const LOUD3 = [['loud', 'Too loud'], ['right', 'Right'], ['quiet', 'Too quiet']];
const MIX3 = [['100', 'MIX 100'], ['75', 'MIX 75, as built'], ['50', 'MIX 50']];
const SPACE3 = [['closer', 'Closer together'], ['right', 'Right'], ['apart', 'Further apart']];
const JOIN3 = [['yes', 'Yes'], ['not_yet', 'Not yet'], ['no', 'No']];
```

  **3c. Add the gate's sheet** where `longNoteSheet()` was:

```js
function delayGateSheet() {
  const one = (src, id, name, held, mix) => {
    const row = [
      [src + '/gate_pad', 'Pad', name + ', as the kit has it'],
      [src + '/gate_delay_70', 'Delay 70', name + ', DELAY at 70, as built'],
      [src + '/gate_delay_100', 'Delay 100', name + ', DELAY at 100, as built'],
      [src + '/gate_head0_70', 'Head off 70', name + ', DELAY at 70 with HEAD off, at the same level'],
      [src + '/gate_head0_100', 'Head off 100', name + ', DELAY at 100 with HEAD off, at the same level'],
      [src + '/gate_plain_70', 'Plain 70', name + ', the plain DELAY from your first listen (MIX 50, HEAD off), at 70'],
    ];
    if (mix) row.push([src + '/gate_mix100_70', 'Mix 100', name + ', DELAY at 70 with MIX 100'], [src + '/gate_mix50_70', 'Mix 50', name + ', DELAY at 70 with MIX 50']);
    const parts = [setRow(name, row), chipGroup('dly_' + id + '_delay', 'Is it a delay, on ' + name + '?', YES4, name + ' delay')];
    if (held) parts.push(chipGroup('dly_' + id + '_head', 'With HEAD off beside it at the same level, is the head-fed one still the better delay, on ' + name + '?', HEAD3, name + ' head'));
    if (id === 'snare') parts.push(
      chipGroup('dly_snare_loud70', 'On the snare at 70, are the repeats too loud?', LOUD3, 'snare loud 70'),
      chipGroup('dly_snare_loud100', 'On the snare at 100, are the repeats too loud?', LOUD3, 'snare loud 100'));
    if (mix) parts.push(chipGroup('dly_' + id + '_mix', 'Which MIX is the DELAY, on ' + name + '?', MIX3, name + ' mix'));
    return parts;
  };
  return setSheet('The DELAY gate', [
    'ECHO now has the HEAD knob you asked for. DELAY is the long, dark ECHO with HEAD on: its repeats are fed only the first 120 ms of the note, so a held note\'s attack comes back as a repeat instead of the whole note smearing into itself. It plays at your MIX 75.',
    'Head off is the same recipe with HEAD off at the same level, so you hear what HEAD alone does. The head clip you liked before was also 8 to 10 dB louder than the plain one, so this is the fair test. Plain 70 is the plain DELAY from the DELAY sheet of your first listen (MIX 50, HEAD off): you passed it on the snare; on the choir and the jangle it did not repeat the attack; the chug did not have it. On the snare and the jangle, MIX 100 and MIX 50 sit beside your 75. At 70 the repeats are 151 ms apart; at 100, 225 ms.',
  ], ...one('SNARE', 'snare', 'the snare', false, true), ...one('CHOIR', 'choir', 'the choir', true, false),
     ...one('JANGLE', 'jangle', 'the jangle', true, true), ...one('CHUG', 'chug', 'the chug', true, false),
     chipGroup('dly_spacing', 'At 70 the repeats are 151 ms apart and at 100 225 ms. Is that spacing right?', SPACE3, 'spacing'),
     chipGroup('dly_join', 'Should DELAY join the pedals?', JOIN3, 'join'));
}
```

  **3d. Wire it in.** Change the `sheet.append(…)` line so that `delayGateSheet()` replaces `longNoteSheet()`.

  **3e. Update the lede's second paragraph.** "A fifth sheet, DELAY on long notes, was added after your first listen." becomes "The DELAY on long notes sheet you heard has made way for the DELAY gate: ECHO with the HEAD knob, as built."

- [ ] **Step 4: Render and check.**
  - Run `./gradlew :synth:generatePedalListens`. Expect `pedal listens: 76 clips …` and 28 `gate cap:` lines, one per clip: the four `gate_pad` lines end before the cap, and the 24 DELAY clips each print a cut (ECHO's own tail is 1.1 s, past the rack's one second). The HEAD-on snare and choir cuts land on silence and print a figure near 239 dB, because the cut is floored at 1e-12. The hottest cut is the chug's HEAD 0 clip at 100, about 35 dB under (a dry run measured 34.7); every cut at AMT 70 is about 66 dB or more under.
  - Check the clips the page needs exist. The page builds its keys at runtime (`src + '/gate_pad'`), so check the rendered table and the files:

```bash
for s in SNARE CHOIR JANGLE CHUG; do for id in gate_pad gate_delay_70 gate_delay_100 gate_head0_70 gate_head0_100 gate_plain_70; do grep -q "\"$s/$id\":" testkit/pedal-listens/index.html || echo "missing $s/$id"; done; done
for s in SNARE JANGLE; do for id in gate_mix100_70 gate_mix50_70; do grep -q "\"$s/$id\":" testkit/pedal-listens/index.html || echo "missing $s/$id"; done; done
grep -o '"[A-Z]*/gate_[a-z0-9_]*":' testkit/pedal-listens/index.html | sort -u | wc -l
ls testkit/pedal-listens/*/gate_*.wav | wc -l
```

    Expect no `missing` lines, then 28, then 28.
  - Open `testkit/pedal-listens/index.html` in the pre-installed Chromium (Playwright; `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers`). Confirm the gate sheet draws its 28 clip buttons, each with its length badge (`clipBtn` draws one only for a key in CLIPS), and 13 questions (four "is it a delay", three HEAD, two snare loudness, two MIX, spacing, join), and that the console shows no errors other than the Google Fonts stylesheet failing to load offline.

- [ ] **Step 5: Commit.** Commit message: "Trade the long-note delay sheet for the DELAY gate". The body says:
  - the 28 clips and why each is there;
  - the retired prototypes;
  - the one dropped verdict question and why;
  - the count of 58 → 76;
  - the `gate cap:` figures.

---

### Task 7: Verify, push, and the pull request

- [ ] **Step 1: The whole suite.**
  - Run `./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew --no-daemon test`, in the background (about two hours).
  - Expect exit 0. If any test fails, root-cause it; never skip, disable or quarantine a test.
  - Run the native suite too, as the steward skill gives it. It is unaffected, and its green state is part of what CI runs.
- [ ] **Step 2: Record the build in the spec.** Change the spec's Status line from "not built" to built (with the date), and append an "As built" section: the 18 pins, both probe tables as measured, the held tone's wet gaps, the liked clip's figures, and the `gate cap:` figures. This is the repo's habit (an As built record in the same branch as the code).
- [ ] **Step 3: Re-read the diff adversarially.** `git diff origin/claude/mobile-mpc-drum-sampler-t58x74...HEAD`. Check:
  - Is `Echo.kt`'s loop change the one multiply?
  - Is `delayed` at the end of `EXTRA`?
  - Is DELAY ungated?
  - Does Task 8 upload no path that is already published? (Sheets (1) to (4) re-render locally, and since DRIVE's LOG fade law, `CHOIR/bloom_70.wav` and `bloom_100` may differ from the published bytes: they must never be uploaded.)
  - Are the shell laws respected?
- [ ] **Step 4: Push, and open or update the PR.** Follow the steward skill and the session's rules. The PR body names:
  - the hash pins;
  - the probe table as measured;
  - the held-tone bar;
  - the gate's 28 clips;
  - that the chip waits for the gate.

---

### Task 8: The owner's gate (last)

- [ ] **Step 1: Publish the gate.** Update the Stage 1b artifact in place (https://claude.ai/artifact/Lnj9v78sJA4u1BaHm1aAvh):
  - Read it first, with the Artifact tool's `read`. Then list its files (`action: "list"`, `scope: "files"`): expect 59 (58 WAVs and the page) and no `*/gate_*` path.
  - Publish with `url` set to the artifact, `file_path` set to `testkit/pedal-listens/index.html`, `root` set to `testkit/pedal-listens`, and `files` set to exactly the 28 gate paths as a list (e.g. `{"path": "SNARE/gate_pad.wav"}`).
  - Leave `capabilities` out: omitting it keeps the page's `db`, and `{}` would clear it.
  - Never publish the whole folder, never re-upload the 48 clips already there, and never remove the ten retired long-note clips: recorded verdicts point at those bytes.
  - Answers land under `verdicts/dly_*`.
- [ ] **Step 2: Send the owner the link,** with one plain paragraph:
  - what HEAD does;
  - what each clip is;
  - the questions.

  Say that nothing changes on the pad sheet until they say so. Then wait. Do not chase.
- [ ] **Step 3: Read the verdicts** with `ArtifactData` (list `verdicts`), plus anything the owner says in chat. Decide by the spec's rules, and record the decision only: this plan changes no recipe, no entry and no pin. Every change lands in the follow-up under "After the gate".
  - **MIX:** a MIX picked by ear becomes `delayed`'s MIX, and every bar measured at MIX 0.75 moves with it: `EchoTest`'s `recipe` literal, the two pins in Task 2's last test (re-measured), and the discreteness bar (re-set at the measurement less 3 dB; the choir's separation is 6.8 dB at MIX 0.5, under today's 7 dB bar).
  - **HEAD:** if HEAD off wins on the held notes, `delayed` drops HEAD and keeps its MIX, and HEAD stays a macro for kits and the CLI.
  - **Spacing:** a spacing answer moves `delayed`'s TIME. TIME 1.0 gives 206 ms at 70 and 350 ms at 100.
  - **Not a delay:** if no setting is a delay to the owner, the chip does not ship, and DELAY stays ungated (the registry's standing rule).
  - **Delay, but not yet:** "Should DELAY join the pedals: not yet" leaves it ungated, and the answer is recorded.
- [ ] **Step 4: Record the verdict.** Write the answers into the spec as a dated "The gate (date)" section, quoting the owner verbatim. Add a note to the S30 roadmap row. Commit, push, and add the verdict to the pull request's body.

---

## After the gate (not this plan)

If the gate passes, a short follow-up flips `gated` to true on DELAY's entry. That flip moves exactly these pins and lines (the spec's pinned-lists hazard, checked against a scratch run):

- **Tests:**
  - `PedalsTest`'s gated list becomes `listOf("DRIVE", "COMP", "DELAY")`.
  - `PadSheetTest`'s row-seven list gains DELAY (or follows WAH, if WAH's gate passed first).
  - `PadSheetTest`'s inventory set gains DELAY, and its two `33`s, with the inventory test's name, become `34`. The six-wide ceiling holds.
- **Docs:**
  - `docs/CLI.md`: row seven's list ("the pedals (DRIVE · COMP …)") gains DELAY, and the not-drawn list drops `delayed`.
  - The library design: a dated line.
  - The S30 roadmap row.

Row seven and `CHARACTER_FOR` follow from the registry with no sheet code change. Any change the gate's answers make to the recipe (MIX, HEAD, TIME) lands in that same follow-up, with its pins re-measured.
