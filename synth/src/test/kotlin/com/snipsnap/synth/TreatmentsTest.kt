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

    @Test
    fun `driven ends clean at the pad sheet's AMT and at full, on a short pad and a long one`() {
        val sources = mapOf("snare" to Thump.render(ThumpVoice.SNARE), "choir" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in sources) {
            for (amount in listOf(0.7f, 1f)) {
                val out = Treatments.apply("driven", dry, amount).snip
                val end = endDb(out)
                assertTrue(end <= -53.0, "$name at AMT $amount ends $end dB under its peak, past the -50 bar with 3 dB of margin")
                assertTrue(abs(out.samples.average()) < 1e-4, "$name at AMT $amount leaves DC")
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
