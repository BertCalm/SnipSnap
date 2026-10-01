package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.audio.Transients
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesVoice
import java.io.File
import kotlin.math.ceil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BECOME (docs/superpowers/specs/2026-09-30-become-strung-say-design.md,
 * "BECOME, the design" and "Testing"): MORPH's amount as a ramp in time.
 * The first claim is the one every other rests on - BECOME 0 is today's
 * MORPH, byte for byte - and it is held against [LegacyMorph], a frozen
 * copy, not against the new code.
 */
class BecomeTest {

    private val rate = 44_100

    /** A decaying sine - the construction of CliTest's morph test (`CliTest.kt:3027-3030`). */
    private fun tone(hz: Double, sampleRate: Int, seconds: Float = 1f): Snip =
        Snip(
            FloatArray((seconds * sampleRate).toInt()) { i ->
                val t = i.toDouble() / sampleRate
                (0.6 * Math.sin(2.0 * Math.PI * hz * t) * Math.exp(-5.0 * t)).toFloat()
            },
            1, sampleRate,
        )

    /**
     * Three pairs, binding by the spec. They reach `toStereo`'s mono and
     * stereo branches (a mono kick into a mono bell; a stereo snare, WIDTH
     * above 0, into a kick) and `resampled`'s two branches (a 48 kHz tone
     * into a 44.1 kHz one, so `morph` and PGHI also run off 44.1 kHz). They
     * do NOT reach `alignToOnset` past its first exit: `Transients.detect`
     * finds no onset in any of these sounds, so each case returns at the
     * `?: return snip` and the trim is never run. The trim is held by
     * [BECOME 0 trims leading room exactly as MORPH does].
     */
    private val pairs: List<Triple<String, Snip, Snip>> by lazy {
        listOf(
            Triple("kick into bell", Thump.render(ThumpVoice.KICK), Tines.render(TinesVoice.BELL)),
            Triple("stereo snare into kick", Thump.render(ThumpVoice.SNARE, mapOf("WIDTH" to 0.5f)), Thump.render(ThumpVoice.KICK)),
            Triple("48 kHz tone into 44.1 kHz tone", tone(300.0, 48_000), tone(1200.0, rate)),
        )
    }

    @Test
    fun `BECOME 0 is today's MORPH, bit for bit, against the frozen copy`() {
        assertEquals(2, pairs[1].second.channels, "the snare must be stereo or the stereo branch is never reached")
        var cases = 0
        for ((name, base, parent) in pairs) {
            for (amount in listOf(0f, 0.25f, 0.5f, 1f)) {
                val now = Mutate.render(base, listOf(Mutate.Source("parent", parent)), Mutate.Mode.MORPH, morphAmount = amount).snip
                val then = LegacyMorph.render(base, parent, amount)
                assertEquals(then.channels, now.channels, "$name at $amount: channels")
                assertEquals(then.sampleRate, now.sampleRate, "$name at $amount: rate")
                assertContentEquals(then.samples, now.samples, "$name at $amount: MORPH moved")
                cases++
            }
        }
        println("BECOME 0 against the frozen MORPH: $cases cases")
        assertEquals(12, cases)
    }

    /**
     * `alignToOnset`'s trim, the branch the three pairs above never reach: a
     * pad and a parent each led by 150 ms of silence (the construction of
     * the leading-room test) have their room cut, so MORPH renders them
     * within half the lead of the same sounds without the room (the onset
     * backoff leaves a few frames), not 150 ms longer. The precondition is
     * about the input: if it fails, the detector or its backoff moved and
     * the construction no longer reaches the trim - fix the input, not
     * `Mutate.kt`.
     */
    @Test
    fun `BECOME 0 trims leading room exactly as MORPH does`() {
        val lead = FloatArray(150 * rate / 1000)
        val base = Snip(lead + tone(300.0, rate).samples, 1, rate)
        val parent = Snip(lead + tone(1200.0, rate).samples, 1, rate)
        var cases = 0
        for (amount in listOf(0f, 0.25f, 0.5f, 1f)) {
            val untrimmed = LegacyMorph.render(tone(300.0, rate), tone(1200.0, rate), amount)
            val then = LegacyMorph.render(base, parent, amount)
            assertTrue(
                Math.abs(then.frameCount - untrimmed.frameCount) <= lead.size / 2,
                "alignToOnset did not trim the room on this construction at $amount " +
                    "(${then.frameCount} frames against ${untrimmed.frameCount} unleaded): fix the input",
            )
            val now = Mutate.render(base, listOf(Mutate.Source("parent", parent)), Mutate.Mode.MORPH, morphAmount = amount).snip
            assertEquals(then.channels, now.channels, "leaded at $amount: channels")
            assertEquals(then.sampleRate, now.sampleRate, "leaded at $amount: rate")
            assertContentEquals(then.samples, now.samples, "leaded at $amount: the trim moved")
            cases++
        }
        println("BECOME 0 trim against the frozen MORPH: $cases cases")
    }

    @Test
    fun `the ramp is 0 up to the onset, never falls, is linear between, and MIX from BECOME on`() {
        val amount = 0.8f
        var checked = 0
        for (sr in listOf(44_100, 48_000, 96_000)) {
            // 1 ms is the CLI's floor and shorter than one analysis window: it reads as a step, and must not divide by zero.
            for (ms in listOf(1, 50, 400, 2000)) {
                val ramp = ms * sr / 1000.0 // samples
                var prev = 0f
                var f = 0
                while (true) {
                    val centre = f.toLong() * Spectral.HOP - Spectral.FRAME / 2
                    val a = Mutate.becomeAmount(f, amount, ms, sr)
                    if (centre <= 0) assertEquals(0f, a, "$sr Hz, $ms ms, frame $f: at or before the onset is the pad alone")
                    assertTrue(a >= prev, "$sr Hz, $ms ms: the ramp fell at frame $f ($prev -> $a)")
                    if (centre >= ramp) {
                        assertTrue(a == amount, "$sr Hz, $ms ms, frame $f: from BECOME on it must be MIX itself, got $a")
                        break
                    }
                    prev = a
                    f++
                }
                assertTrue(Mutate.becomeAmount(f + 1000, amount, ms, sr) == amount, "$sr Hz, $ms ms: long after BECOME it is MIX")
                for (p in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    // The first frame centred at or after p of the ramp.
                    val at = ceil((p * ramp + Spectral.FRAME / 2) / Spectral.HOP).toInt()
                    val centre = at.toLong() * Spectral.HOP - Spectral.FRAME / 2
                    val expected = (amount * (centre / ramp).coerceIn(0.0, 1.0)).toFloat()
                    val got = Mutate.becomeAmount(at, amount, ms, sr)
                    assertEquals(expected, got, 1e-6f, "$sr Hz, $ms ms at ${(p * 100).toInt()}%: not linear in amount")
                    if (p < 1.0) {
                        // ...and within one hop of p itself, so "linear" means the spec's points, not only the formula.
                        assertTrue(got >= amount * p - 1e-6 && got <= amount * (p + Spectral.HOP / ramp) + 1e-6, "$sr Hz, $ms ms: $got is not near ${amount * p}")
                    }
                    checked++
                }
            }
        }
        assertEquals(60, checked)
        // BECOME 0 is the amount itself at every frame, the ones before the onset included: today's loop.
        for (f in listOf(0, 1, 2, 3, 100)) assertTrue(Mutate.becomeAmount(f, 0.37f, 0, 44_100) == 0.37f, "frame $f at BECOME 0")
    }

    // ---------- through render and apply ----------

    private val temp: File = java.nio.file.Files.createTempDirectory("become").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        m.assign(1, tone(300.0, rate, 0.5f), DrumClass.KICK)
        m.assign(2, tone(1200.0, rate, 0.8f), DrumClass.SNARE)
        m.save()
        return m
    }

    private fun parentOf(m: KitBuilderModel): Mutate.Source =
        Mutate.Source("${m.kit.name}:A02", WavReader.read(File(m.kitDir, m.pad(2)!!.sampleFile)))

    private fun mutateKeys(pad: KitPad): Map<String, JsonValue> =
        (pad.recipe!!.entries["mutate"] as JsonValue.Obj).entries

    private fun becomeRender(base: Snip, parent: Snip, mix: Float, becomeMs: Int): Snip =
        Mutate.render(base, listOf(Mutate.Source("parent", parent)), Mutate.Mode.MORPH, morphAmount = mix, becomeMs = becomeMs).snip

    private fun left(s: Snip): FloatArray = FloatArray(s.frameCount) { s.samples[it * s.channels] }

    /** The amplitude of [hz] in [fromMs]..[toMs) of [x] - the probe CliTest's morph test reads (`CliTest.kt:2382`). */
    private fun line(x: FloatArray, hz: Double, sr: Int, fromMs: Int, toMs: Int): Double {
        val from = fromMs * sr / 1000
        val to = minOf(x.size, toMs * sr / 1000)
        var c = 0.0
        var s = 0.0
        for (i in from until to) {
            val w = 2.0 * Math.PI * hz * i / sr
            c += x[i] * Math.cos(w)
            s += x[i] * Math.sin(w)
        }
        return Math.hypot(c, s) / (to - from).coerceAtLeast(1)
    }

    @Test
    fun `early frames are the pad, late frames the parent, and it is still one hit`() {
        // CliTest.kt:3019-3074's construction: a 300 Hz pad, a 1200 Hz parent, BECOME 400 at MIX 1.
        // The bounds are the spec's ("Testing"), from CliTest.kt:3054 (5x), :3062 (0.2) and :3064 (one onset).
        // Measured (44.1 kHz): head ratio 24.3 against the 5x bound; at 200 ms the smaller line is 0.993 of the larger
        // against the 0.2 bound; late ratio 1514 against 5x; Transients.detect finds no onset (frames []), within "one".
        val x = left(becomeRender(tone(300.0, rate), tone(1200.0, rate), 1f, 400))
        val head = line(x, 300.0, rate, 0, 30) to line(x, 1200.0, rate, 0, 30)
        val mid = line(x, 300.0, rate, 180, 220) to line(x, 1200.0, rate, 180, 220)
        val late = line(x, 300.0, rate, 600, 900) to line(x, 1200.0, rate, 600, 900)
        val onsets = Transients.detect(Snip(x, 1, rate))
        println(
            "BECOME 400 ms at MIX 1, 300/1200 Hz lines: first 30 ms $head (ratio ${head.first / head.second}), " +
                "at 200 ms $mid (smaller/larger ${minOf(mid.first, mid.second) / maxOf(mid.first, mid.second)}), " +
                "from 600 ms $late (ratio ${late.second / late.first}), onsets at frames ${onsets.map { it.frame }}",
        )
        assertTrue(head.first >= 5 * head.second, "the first 30 ms are not the pad: $head")
        assertTrue(late.second >= 5 * late.first, "from 600 ms it is not the parent: $late")
        assertTrue(minOf(mid.first, mid.second) > 0.2 * maxOf(mid.first, mid.second), "at 200 ms both should be audible: $mid")
        assertTrue(onsets.size <= 1, "one hit, not a seam: ${onsets.size} onsets")
    }

    @Test
    fun `length and level follow the end amount - the blend's, not the ramp's`() {
        val base = tone(300.0, rate, 0.5f)
        val parent = tone(1200.0, rate, 1.2f)
        for (mix in listOf(0.6f, 1f)) {
            val flat = becomeRender(base, parent, mix, 0)
            val ramped = becomeRender(base, parent, mix, 400)
            assertEquals(flat.frameCount, ramped.frameCount, "MIX $mix: the length is the end blend's")
            assertEquals(flat.peak(), ramped.peak(), 1e-6f, "MIX $mix: the level is the end blend's peak target")
        }
    }

    @Test
    fun `an explicit BECOME 0, and a ramp toward MIX 0, are the frozen MORPH`() {
        for ((name, base, parent) in pairs) {
            assertContentEquals(LegacyMorph.render(base, parent, 0.5f).samples, becomeRender(base, parent, 0.5f, 0).samples, "$name: BECOME 0 at MIX .5")
            // A ramp toward nothing is nothing: every frame's amount is 0, so this is the frozen MIX 0 exactly.
            assertContentEquals(LegacyMorph.render(base, parent, 0f).samples, becomeRender(base, parent, 0f, 400).samples, "$name: BECOME 400 at MIX 0")
        }
    }

    @Test
    fun `BECOME refuses in words - out of range, or on a move that is not MORPH`() {
        assertEquals(2000, Mutate.MAX_BECOME_MS)
        val base = tone(300.0, rate, 0.3f)
        val p = listOf(Mutate.Source("p", tone(1200.0, rate, 0.3f)))
        for (bad in listOf(-1, 2001, Int.MAX_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException> { Mutate.render(base, p, Mutate.Mode.MORPH, becomeMs = bad) }
            assertEquals("--become wants 0..2000 ms, got $bad", e.message)
        }
        for (mode in Mutate.Mode.values().filter { it != Mutate.Mode.MORPH }) {
            val e = assertFailsWith<IllegalArgumentException> { Mutate.render(base, p, mode, becomeMs = 400) }
            assertEquals("--become rides on --morph - add it", e.message, "$mode")
            Mutate.render(base, p, mode, becomeMs = 0) // 0 is no BECOME at all: every move takes it
        }
        Mutate.render(base, p, Mutate.Mode.MORPH, becomeMs = Mutate.MAX_BECOME_MS)
        Mutate.render(base, p, Mutate.Mode.MORPH, becomeMs = 1)
    }

    @Test
    fun `the recipe says become only for a MORPH with a ramp, after amount, and the file is the ramp`() {
        val m = model("Recipe")
        val base = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        val heard = Mutate.render(base, listOf(parentOf(m)), Mutate.Mode.MORPH, morphAmount = 0.5f, becomeMs = 400).snip

        val ramped = Mutate.apply(m, 1, listOf(parentOf(m)), Mutate.Mode.MORPH, morphAmount = 0.5f, becomeMs = 400)
        val keys = mutateKeys(ramped.pad)
        assertEquals(listOf("mode", "with", "amount", "become"), keys.keys.toList())
        assertEquals(400.0, (keys["become"] as JsonValue.Num).value)
        assertEquals(0.5, (keys["amount"] as JsonValue.Num).value, "amount is still written - the end of the blend")
        val kept = WavReader.read(File(m.kitDir, m.pad(1)!!.sampleFile))
        assertEquals(heard.samples.size, kept.samples.size, "apply wrote another length than render's ramp")
        var worst = 0f
        for (i in heard.samples.indices) worst = maxOf(worst, Math.abs(heard.samples[i] - kept.samples[i]))
        // One 24-bit step, the file's own quantisation (MutateSheetTest.kt:109-119).
        assertTrue(worst <= 2f / 8_388_607f, "apply wrote something other than the ramp render makes: $worst")
        Mutate.undo(m, 1)

        val flat = Mutate.apply(m, 1, listOf(parentOf(m)), Mutate.Mode.MORPH, morphAmount = 0.5f)
        assertEquals(listOf("mode", "with", "amount"), mutateKeys(flat.pad).keys.toList(), "BECOME 0 leaves today's recipe, key for key")
        Mutate.undo(m, 1)
    }

    @Test
    fun `an extra recipe field named become is refused before anything moves, and DRIFT writes none`() {
        val m = model("Guard")
        model("Guard2")
        val padFile = File(m.kitDir, m.pad(1)!!.sampleFile)
        val before = padFile.readBytes()
        val e = assertFailsWith<IllegalArgumentException> {
            Mutate.apply(m, 1, listOf(parentOf(m)), Mutate.Mode.MORPH, becomeMs = 400, extraRecipe = mapOf("become" to JsonValue.Num(0.0)))
        }
        assertTrue(e.message!!.contains("become"), e.message)
        assertTrue(before.contentEquals(padFile.readBytes()), "the refusal touched the pad")
        assertNull(m.pad(1)!!.recipe, "the refusal left a recipe")

        // DRIFT is a flat morph: it never passes a ramp, so its recipe has no become.
        val drifted = Mutate.drift(m, 1, root = temp, seed = 1, amount = 0.5f)
        assertEquals(listOf("mode", "with", "amount", "roulette", "drift"), mutateKeys(drifted.outcome.pad).keys.toList())
    }

    // ---------- the inputs a person will hand it (Review Focus) ----------

    @Test
    fun `the ramp runs in the pad's own time at 96 kHz, with a parent at 48 kHz`() {
        val out = becomeRender(tone(300.0, 96_000), tone(1200.0, 48_000), 1f, 400)
        assertEquals(96_000, out.sampleRate)
        val x = left(out)
        val head = line(x, 300.0, 96_000, 0, 30) to line(x, 1200.0, 96_000, 0, 30)
        val late = line(x, 300.0, 96_000, 600, 900) to line(x, 1200.0, 96_000, 600, 900)
        println("BECOME 400 ms at 96 kHz, 300/1200 Hz lines: first 30 ms $head (ratio ${head.first / head.second}), from 600 ms $late (ratio ${late.second / late.first})")
        // The spec's 5x (CliTest.kt:3054), at the pad's own rate. Measured: head ratio 25.8, late ratio 683.6.
        assertTrue(head.first >= 5 * head.second, "at 96 kHz the first 30 ms are not the pad: $head")
        assertTrue(late.second >= 5 * late.first, "at 96 kHz from 600 ms it is not the parent: $late")
    }

    @Test
    fun `a stereo pad comes through a ramp as two channels at BECOME 0's length`() {
        val (_, snare, kick) = pairs[1]
        assertEquals(2, snare.channels, "the snare must be stereo or this does not reach the stereo path")
        val out = becomeRender(snare, kick, 1f, 250)
        assertTrue(out.samples.all { it.isFinite() }, "a stereo pad under a ramp went non-finite")
        assertEquals(2, out.channels)
        assertEquals(becomeRender(snare, kick, 1f, 0).frameCount, out.frameCount, "the length is BECOME 0's")
    }

    @Test
    fun `a ramp longer than the sound is legal - the tail is still turning, and the length is the end blend's`() {
        val base = tone(300.0, rate, 0.3f)
        val parent = tone(1200.0, rate, 1.5f)
        val flat = becomeRender(base, parent, 1f, 0)
        val long = becomeRender(base, parent, 1f, 2000)
        assertEquals(flat.frameCount, long.frameCount, "the length is the end blend's, whatever the ramp")
        assertTrue(long.samples.all { it.isFinite() }, "a 2000 ms ramp over a 300 ms pad went non-finite")
        val flatTail = line(left(flat), 1200.0, rate, 1000, 1200)
        val longTail = line(left(long), 1200.0, rate, 1000, 1200)
        println("BECOME 2000 ms over a 300 ms pad: the parent's line at 1.0-1.2 s is $longTail against $flatTail flat (ratio ${longTail / flatTail})")
        // This plan's own bound, not the spec's: at 1.0-1.2 s a 2000 ms ramp is about 0.55 of the way, so the parent
        // should sit near half its flat level. Measured longTail / flatTail = 0.565 (6.59e-4 against 1.167e-3), so the
        // bound is that plus about 20 %: 0.68 (the plan's first-draft 0.8 is replaced).
        assertTrue(longTail < 0.68 * flatTail, "at 1.0-1.2 s a 2000 ms ramp should still be short of MIX: $longTail against $flatTail")
    }

    @Test
    fun `a parent shorter than the pad is silence past its end, and the ramp keeps turning toward it`() {
        // Spec, "A parent longer or shorter than the pad": a missing frame is silence and the ramp is on absolute
        // time. MIX .5, not 1: at MIX 1 the result is exactly the parent's 0.2 s, so nothing past its end is rendered.
        val base = tone(300.0, rate, 1.2f)
        val parent = tone(1200.0, rate, 0.2f)
        val flat = becomeRender(base, parent, 0.5f, 0)
        val ramped = becomeRender(base, parent, 0.5f, 1000)
        assertEquals(flat.frameCount, ramped.frameCount, "the length is the end blend's, round(1.2 s x .5 + 0.2 s x .5)")
        assertTrue(ramped.samples.all { it.isFinite() }, "a short parent under a ramp went non-finite")
        // Past the parent's end its share is silence. At a flat MIX the pad's 300 Hz line falls by the pad's own decay
        // alone; under the ramp the silence's share grows from 0.175 at 350 ms to 0.3 at 600 ms, so the line falls
        // faster (about 0.85 of the flat fall, predicted). A ratio inside one render, so each file's normalisation cancels.
        // Measured: the line falls to 0.243 under BECOME 1000 against 0.286 flat (ratio 0.85, as predicted).
        fun fall(s: Snip): Double = line(left(s), 300.0, rate, 550, 650) / line(left(s), 300.0, rate, 300, 400)
        println("a 0.2 s parent under a 1.2 s pad at MIX .5: the pad's line falls to ${fall(ramped)} from 350 to 600 ms under BECOME 1000, ${fall(flat)} flat")
        assertTrue(fall(ramped) < fall(flat), "past the parent's end the ramp should still be turning toward its silence: ${fall(ramped)} against ${fall(flat)} flat")
    }

    @Test
    fun `a silent pad and a one-sample pad come through BECOME finite`() {
        val parent = tone(1200.0, rate, 0.5f)
        for ((name, base) in listOf("silent" to Snip(FloatArray(4_410), 1, rate), "one sample" to Snip(floatArrayOf(0.5f), 1, rate))) {
            for (mix in listOf(0.5f, 1f)) {
                val out = becomeRender(base, parent, mix, 400)
                assertTrue(out.samples.all { it.isFinite() }, "$name at MIX $mix went non-finite")
                assertEquals(becomeRender(base, parent, mix, 0).frameCount, out.frameCount, "$name at MIX $mix: the length moved")
            }
        }
    }

    @Test
    fun `a pad with leading room ramps from its hit, not from the file's start`() {
        // Spec, "From the aligned onset": alignToOnset trims the quiet head of both parents first, so the ramp's zero
        // is the pad's hit. A ramp counted from the file's first sample would already be 0.375 of the way at the hit.
        val lead = FloatArray(150 * rate / 1000) // 150 ms of room before each hit
        val base = Snip(lead + tone(300.0, rate).samples, 1, rate)
        val parent = Snip(lead + tone(1200.0, rate).samples, 1, rate)
        // A precondition on the input, not on BECOME: alignToOnset must trim this room. Transients.detect backs its
        // onset off 128 samples before the attack (Transients.kt:49, refineAttack), so the head before it is silence
        // and is cut. If the leaded BECOME 0 render is not within half the lead of the unleaded one, the room was kept.
        val unleaded = becomeRender(tone(300.0, rate), tone(1200.0, rate), 1f, 0).frameCount
        val leaded = becomeRender(base, parent, 1f, 0).frameCount
        assertTrue(
            Math.abs(leaded - unleaded) <= lead.size / 2,
            "alignToOnset kept the room on this construction ($leaded against $unleaded frames): fix the input, not the DSP",
        )
        val ramped = becomeRender(base, parent, 1f, 400)
        assertEquals(leaded, ramped.frameCount, "the length is BECOME 0's")
        val x = left(ramped)
        var peak = 0f
        for (v in x) peak = maxOf(peak, Math.abs(v))
        // Measured: the hit lands at 2 ms, and the 30 ms after it read head ratio 26.6 against the 5x bound.
        // Measured from the hit wherever it lands, so a render that kept the room still reads its first 30 ms of sound.
        val hit = x.indexOfFirst { Math.abs(it) >= 0.1f * peak }
        assertTrue(hit >= 0, "the render is silent")
        val hitMs = hit * 1000 / rate
        val head = line(x, 300.0, rate, hitMs, hitMs + 30) to line(x, 1200.0, rate, hitMs, hitMs + 30)
        println("BECOME 400 ms over a pad with 150 ms of room: hit at $hitMs ms, 300/1200 Hz lines in the 30 ms after it $head (ratio ${head.first / head.second})")
        // The spec's 5x (CliTest.kt:3054), read from the hit.
        assertTrue(head.first >= 5 * head.second, "the first 30 ms after the hit are not the pad, so the ramp did not start at the hit: $head")
    }
}
