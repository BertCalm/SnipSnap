package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Shuffle.TREATMENTS` is picked by index from a seeded `Random` in
 * `withRemixBank` — reordering, adding, or removing an entry changes what
 * every previously-saved seed produces. This is not covered by the pad-sheet
 * task brief; it is added because the launching agent flagged
 * `Shuffle.TREATMENTS` reproducibility as the single most important
 * constraint on this task, and no existing test pinned the list.
 */
class TreatmentsTest {

    @Test
    fun `bank B's five treatments, in the order the seeded shuffle depends on`() {
        assertEquals(
            listOf("reversed", "crushed", "slapback", "washed", "punched"),
            Shuffle.TREATMENTS.map { it.first },
        )
    }

    @Test
    fun `the named characters are the bank's five and then the extras, smeared among them`() {
        assertEquals(
            listOf(
                "reversed", "crushed", "slapback", "washed", "punched",
                "smeared", "ghosted", "stopped", "started", "skimmed", "dubbed", "swelled",
                "spiked", "ringed", "vinyl", "phased", "pitched", "contoured", "ensembled", "amped", "sectioned",
                "driven", "compressed",
            ),
            Treatments.names,
        )
        assertTrue(Treatments.EXTRA.none { it.first in Shuffle.TREATMENTS.map { t -> t.first } }, "an extra never shadows a bank name")
    }

    @Test
    fun `smeared takes the attack out of a snare and records a smear-only recipe`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        fun headShare(s: Snip): Double {
            val head = (0.02f * s.sampleRate).toInt() * s.channels
            var h = 0.0
            var all = 0.0
            for (i in s.samples.indices) {
                val e = s.samples[i] * s.samples[i].toDouble()
                if (i < head) h += e
                all += e
            }
            return h / all
        }
        val treated = Treatments.apply("smeared", snare, 1f)
        assertTrue(headShare(treated.snip) < headShare(snare), "less of the energy sits in the first 20 ms")
        val recipe = PadRecipe.fromJsonValue(treated.recipe)
        assertEquals("smeared", recipe.treatment)
        assertEquals(1f, recipe.amount)
        assertEquals(mapOf("AMOUNT" to 0.85f), recipe.fx!!.smear)
        assertTrue(recipe.fx.eq == null && recipe.fx.spring == null, "a smear-only chain")
    }

    @Test
    fun `AMT scales the smear like every other section`() {
        assertEquals(0.85f * 0.5f, Treatments.chain("smeared", 0.5f).smear!!.getValue("AMOUNT"), 1e-6f)
    }

    @Test
    fun `AMT zero bypasses the chain entirely`() {
        assertTrue(Treatments.chain("crushed", 0f).isBypass)
    }

    @Test
    fun `AMT zero leaves the audio alone`() {
        val src = Snip(FloatArray(2205) { i -> if (i % 2 == 0) 0.5f else -0.5f }, 1, 44_100)
        val treated = Treatments.apply("crushed", src, 0f)
        assertEquals(src.samples.toList(), treated.snip.samples.toList())
    }

    @Test
    fun `an unknown treatment is refused even at AMT zero`() {
        assertFailsWith<IllegalArgumentException> { Treatments.chain("wobbel", 0f) }
    }

    @Test
    fun `ghosted, stopped and started each record a one-section recipe`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val ghosted = PadRecipe.fromJsonValue(Treatments.apply("ghosted", snare, 1f).recipe)
        assertEquals(mapOf("AMOUNT" to 0.9f), ghosted.fx!!.ghost)
        val stopped = PadRecipe.fromJsonValue(Treatments.apply("stopped", snare, 0.5f).recipe)
        assertEquals(0.3f, stopped.fx!!.motion!!.getValue("STOP"), 1e-6f)
        val started = PadRecipe.fromJsonValue(Treatments.apply("started", snare, 1f).recipe)
        assertEquals(mapOf("START" to 0.5f), started.fx!!.motion)
    }

    @Test
    fun `the pedal recipes set only their own section, and none sits on a neutral`() {
        val expected = mapOf("driven" to "valve", "compressed" to "squash")
        for ((name, section) in expected) {
            val chain = Treatments.chain(name)
            assertEquals(
                setOf(section),
                FxChain.SECTION_NAMES.filter { chain.section(it) != null }.toSet(),
                "$name sets only $section",
            )
            val neutrals = FxChain.macrosOf(section).associate { it.name to it.neutral }
            for ((macro, v) in chain.section(section)!!) {
                assertTrue(v != neutrals.getValue(macro), "$name.$macro sits on its neutral $v: AMT could never move it")
            }
        }
    }

    @Test
    fun `each pedal recipe changes a snare and leaves it finite and audible`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        for (name in listOf("driven", "compressed")) {
            val out = Treatments.apply(name, snare, 1f).snip
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "$name stays finite and in range")
            assertTrue(out.samples.any { abs(it) > 0.05f }, "$name is audible")
            assertFalse(out.samples.contentEquals(snare.samples), "$name changes the hit")
        }
    }

    /**
     * Total harmonic distortion of a steady 220 Hz sine: the root of the energy at harmonics 2 to 8
     * over the energy at the fundamental, by a DFT over half a second (a whole 110 periods, so every
     * bin is exact with no window leakage) that starts 100 ms in, after the supply and the DC blocker
     * have settled. Percent.
     */
    private fun thdPercent(out: Snip): Double {
        val rate = out.sampleRate
        val start = rate / 10
        val n = rate / 2
        fun magnitude(harmonic: Int): Double {
            val w = 2.0 * PI * 220.0 * harmonic / rate
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val x = out.samples[start + i].toDouble()
                re += x * cos(w * i)
                im += x * sin(w * i)
            }
            return sqrt(re * re + im * im)
        }
        var harmonics = 0.0
        for (h in 2..8) magnitude(h).let { harmonics += it * it }
        return 100.0 * sqrt(harmonics) / magnitude(1)
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /**
     * What makes DRIVE a drive pedal and not a speaker in front of a clean tube: the recipe's own
     * VALVE, run on a steady 220 Hz sine at peak 0.5 (VALVE normalises its input, so the level does
     * not matter), makes real harmonics. Measured: 35.99 percent at the pad sheet's default AMT 0.7
     * and 38.05 at AMT 1 for the owner's recipe (DRIVE 1.0, SAG 0.15, CAB 0.08; at AMT 0.7 the DRIVE
     * macro fades to 0.7, which is VALVE's own default gain). The earlier recipes made far less: DRIVE
     * 0.6 made 4.81 and 19.49, and the first, DRIVE 0.45 with CAB 0.3, made 1.24 and 5.66, a tube that
     * was nearly linear. The bounds are the bars the recipe was chosen against, 15 and 30.
     */
    @Test
    fun `DRIVE makes harmonics on a steady sine at the pad sheet's AMT and at full`() {
        val rate = WavWriter.MPC_SAMPLE_RATE
        val sine = Snip(FloatArray(rate) { i -> (0.5 * sin(2.0 * PI * 220.0 * i / rate)).toFloat() }, 1, rate)
        val seventy = thdPercent(Treatments.chain("driven", 0.7f).process(sine))
        val full = thdPercent(Treatments.chain("driven", 1f).process(sine))
        assertTrue(seventy >= 15.0, "DRIVE at AMT 0.7 makes ${pct(seventy)} percent THD on a 220 Hz sine, under the 15 percent bar (a drive pedal, not a speaker; 35.99 measured)")
        assertTrue(full >= 30.0, "DRIVE at AMT 1 makes ${pct(full)} percent THD on a 220 Hz sine, under the 30 percent bar (38.05 measured)")
    }

    private fun rmsDb(s: Snip, fromMs: Int, toMs: Int): Double {
        val a = (fromMs * s.sampleRate / 1000) * s.channels
        val b = minOf((toMs * s.sampleRate / 1000) * s.channels, s.samples.size)
        var sq = 0.0
        for (i in a until b) sq += s.samples[i].toDouble() * s.samples[i]
        return 10.0 * log10(sq / (b - a))
    }

    /** Peak over RMS, both over the whole snip and every channel. */
    private fun crestFactor(s: Snip): Double {
        var peak = 0.0
        var sq = 0.0
        for (v in s.samples) {
            val a = abs(v.toDouble())
            if (a > peak) peak = a
            sq += a * a
        }
        return peak / sqrt(sq / s.samples.size)
    }

    /**
     * What makes COMP a compressor pedal: it leans on the body and lets the tail stand up. Two
     * measures, both at AMT 1, for the owner's recipe (SQUASH AMOUNT 1.0, ATTACK 0.05). On the snare,
     * the tail (100 to 200 ms) against the body (5 to 50 ms) rises 7.78 dB (-23.42 dry, -15.64
     * compressed); the bar is 6 (the first recipe, AMOUNT 0.7 with ATTACK 0.55, rose 4.42). On the
     * choir, whose body is held, the peak-to-RMS ratio falls from 10.64 to 7.28 (3.36); the bar is
     * 2.35, about 70 percent of the drop (the first recipe fell to 9.46, a drop of 1.19). The
     * snare's own peak-to-RMS ratio does NOT fall: it goes from 11.58 to 11.80 (14.58 for the first
     * recipe), because a fast one-shot's 2 ms attack lets the hit through and SQUASH takes the body,
     * so COMP on a drum is a punchier hit with a longer tail, not a flatter one. That is why the
     * snare is measured by its tail and not by its crest.
     */
    @Test
    fun `COMP lifts a snare's tail against its body and flattens a choir`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val squashed = Treatments.apply("compressed", snare, 1f).snip
        val dryTail = rmsDb(snare, 100, 200) - rmsDb(snare, 5, 50)
        val tail = rmsDb(squashed, 100, 200) - rmsDb(squashed, 5, 50)
        assertTrue(
            tail - dryTail >= 6.0,
            "COMP lifts the snare's tail against its body by ${pct(tail - dryTail)} dB (${pct(dryTail)} dry, ${pct(tail)} compressed), under the 6 dB bar (7.78 measured)",
        )
        val choir = Vox.render(VoxVoice.CHOIR)
        val dryCrest = crestFactor(choir)
        val crest = crestFactor(Treatments.apply("compressed", choir, 1f).snip)
        assertTrue(
            dryCrest - crest >= 2.35,
            "COMP lowers the choir's peak-to-RMS by ${pct(dryCrest - crest)} (${pct(dryCrest)} dry, ${pct(crest)} compressed), under the 2.35 bar (3.36 measured)",
        )
    }

    private fun endDb(s: Snip): Double {
        val n = (0.02f * s.sampleRate).toInt() * s.channels
        var peak = 0f
        var end = 0f
        for (i in s.samples.indices) {
            val a = abs(s.samples[i])
            if (a > peak) peak = a
            if (i >= s.samples.size - n && a > end) end = a
        }
        return 20.0 * log10(maxOf(end / peak, 1e-12f).toDouble())
    }

    /**
     * A snare (333 ms, mono) and a choir (1.44 s, stereo) end at least 53 dB under their peaks, the
     * last 20 ms against the whole pad, at the pad sheet's AMT 0.7, at full, and between (the fade's
     * length follows the amp's gain, so the in-between amounts are the law's own cells). Measured at
     * the shipped fade (95 ms at power 5 at full, the first cell of the declared grid in
     * `Treatments.PRE_AMP_FADE`'s KDoc that passes this test's two bars): the snare ends 69.81, 74.44, 63.43 and 54.10 dB under its peak at AMT 0.7, 0.8, 0.9
     * and 1 (44.36, 32.05, 19.92 and 9.14 with no fade), the choir at least 83 dB; the snare at AMT 1
     * leaves a DC mean of 9.6e-5 (2.5e-4 with no fade), the largest of the eight cells.
     */
    @Test
    fun `driven ends clean at the pad sheet's AMT, at full and between, on a snare and a choir`() {
        val sources = mapOf("snare" to Thump.render(ThumpVoice.SNARE), "choir" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in sources) {
            for (amount in listOf(0.7f, 0.8f, 0.9f, 1f)) {
                val out = Treatments.apply("driven", dry, amount).snip
                val end = endDb(out)
                assertTrue(end <= -53.0, "$name at AMT $amount ends $end dB under its peak, past the -50 bar with 3 dB of margin")
                assertTrue(abs(out.samples.average()) < 1e-4, "$name at AMT $amount leaves DC")
            }
        }
    }

    private fun ones(frames: Int, rate: Int) = Snip(FloatArray(frames) { 1f }, 1, rate)

    /** The milliseconds from the first frame of [gain] that is not 1 (the fade's start) to its end. */
    private fun fadeMs(gain: Snip): Double {
        val first = gain.samples.indexOfFirst { it != 1f }
        return if (first < 0) 0.0 else (gain.samples.size - first) * 1000.0 / gain.sampleRate
    }

    /**
     * The fade is the tail's alone. Read off a pad of ones, so the samples are the gain itself: it is
     * a fade of tens of milliseconds, at most 110 at full (95 shipped) and 40 at the pad sheet's AMT
     * 0.7 (33 shipped), shorter at the lower amount; it ends at silence and falls all the way; and it
     * never reaches the first half of a pad, however short the pad is (a 20 ms pad fades its last 10).
     */
    @Test
    fun `the end fade is the tail's alone - tens of milliseconds, shorter at a lower AMT, never the first half of a pad`() {
        val rate = WavWriter.MPC_SAMPLE_RATE
        val long = ones(rate * 1500 / 1000, rate)
        val seventy = fadeMs(Treatments.endFaded(long, "driven", 0.7f))
        val full = fadeMs(Treatments.endFaded(long, "driven", 1f))
        assertTrue(full in 50.0..110.0, "the fade at full is $full ms of a long pad, outside 50 to 110 (95 shipped)")
        assertTrue(seventy in 10.0..40.0, "the fade at AMT 0.7 is $seventy ms of a long pad, outside 10 to 40 (33 shipped)")
        assertTrue(seventy < full, "the fade at AMT 0.7 ($seventy ms) is shorter than at full ($full ms)")
        for (ms in listOf(20, 60, 120, 333, 1500)) {
            for (amount in listOf(0.7f, 1f)) {
                val pad = ones(rate * ms / 1000, rate)
                val out = Treatments.endFaded(pad, "driven", amount).samples
                val half = pad.frameCount / 2
                assertTrue((0 until half).all { out[it] == 1f }, "a $ms ms pad at AMT $amount keeps its first half untouched")
                assertEquals(0f, out[out.size - 1], "a $ms ms pad at AMT $amount ends at silence")
                val start = out.indexOfFirst { it != 1f }
                assertTrue(start >= half, "a $ms ms pad at AMT $amount starts its fade at frame $start, before the half at $half")
                assertTrue((start until out.size - 1).all { out[it] >= out[it + 1] }, "a $ms ms pad at AMT $amount falls from its fade's start to its end")
            }
        }
    }

    /**
     * Where the amp does not lift (its gain at or under 1, DRIVE up to about 0.46, so AMT up to about
     * 0.46) there is no fade: the door's output is the chain's, byte for byte. There is no click to
     * hide there, and a fade ahead of a nearly clean amp would only shorten the pad's tail.
     */
    @Test
    fun `no end fade where the amp does not lift`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        for (amount in listOf(0.05f, 0.3f)) {
            val applied = Treatments.apply("driven", snare, amount).snip
            val direct = Treatments.chain("driven", amount).process(snare)
            assertTrue(applied.samples.contentEquals(direct.samples), "driven at AMT $amount is the chain and nothing else")
        }
    }

    /**
     * The body is the amp's own: before the fade starts, the output is the unfaded chain's output (the
     * measured difference is exactly 0; the bar is a millionth of the peak, and the last 2 ms before
     * the fade are left out for the amp's filters). On the snare's 333 ms and on an 80 ms slice of it,
     * which is shorter than the 95 ms fade, at AMT 0.7 and 1: the slice fades its last 40 ms at full
     * (the half of the pad) and 33 at AMT 0.7, and what comes before is the unfaded chain's.
     */
    @Test
    fun `the end fade leaves a pad's body to the amp, a pad shorter than the fade included`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val pads = mapOf("the snare" to snare, "an 80 ms slice of the snare" to Snip(snare.samples.copyOf(snare.sampleRate * 80 / 1000), 1, snare.sampleRate))
        for ((name, pad) in pads) {
            for (amount in listOf(0.7f, 1f)) {
                val faded = Treatments.apply("driven", pad, amount).snip
                val unfaded = Treatments.chain("driven", amount).process(pad)
                val gain = Treatments.endFaded(ones(pad.frameCount, pad.sampleRate), "driven", amount)
                val first = gain.samples.indexOfFirst { it != 1f }
                val fadeFrames = if (first < 0) 0 else gain.samples.size - first
                val body = pad.frameCount - fadeFrames - 2 * pad.sampleRate / 1000
                assertTrue(body > 0, "$name at AMT $amount has a body before its fade")
                var peak = 0f
                for (v in unfaded.samples) if (abs(v) > peak) peak = abs(v)
                var worst = 0.0
                for (i in 0 until body) worst = maxOf(worst, abs((faded.samples[i] - unfaded.samples[i]).toDouble()))
                assertTrue(worst <= 1e-6 * peak, "$name at AMT $amount: the first $body frames differ from the unfaded chain by up to ${worst / peak} of the peak")
                assertFalse(faded.samples.contentEquals(unfaded.samples), "$name at AMT $amount: the fade does something to the tail")
            }
        }
    }

    @Test
    fun `the end fade is driven's alone`() {
        val snare = Thump.render(ThumpVoice.SNARE)
        val applied = Treatments.apply("compressed", snare, 1f).snip
        val direct = Treatments.chain("compressed", 1f).process(snare)
        assertTrue(applied.samples.contentEquals(direct.samples), "compressed is not faded: the door applies the chain and nothing else")
    }
}
