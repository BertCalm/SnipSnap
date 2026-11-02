package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class LorentzTest {
    @Test
    fun `TUNE retains reference precision at a semitone boundary`() {
        // Float multiplication would round this control up onto the midpoint.
        // The approved reference promotes the saved Float before multiplying.
        val below = .14583333f
        val above = Math.nextUp(below)
        for (voice in LorentzVoice.entries) {
            assertEquals(27, Lorentz.midiFor(voice, below))
            assertEquals(28, Lorentz.midiFor(voice, above))
            assertEquals(36, Lorentz.midiFor(voice, Float.NaN))
        }
    }

    @Test
    fun `each control changes the finished sound between low and middle settings after gain matching`() {
        for (voice in LorentzVoice.entries) for (control in Lorentz.defaults(voice).keys) {
            val a = Lorentz.render(voice, mapOf(control to .25f)).samples
            val b = Lorentz.render(voice, mapOf(control to .75f)).samples
            val n = minOf(a.size, b.size, Dsp.RATE / 5)
            val aa = sqrt((0 until n).sumOf { a[it].toDouble().pow(2) } / n)
            val bb = sqrt((0 until n).sumOf { b[it].toDouble().pow(2) } / n)
            val difference = sqrt((0 until n).sumOf { (a[it] / aa - b[it] / bb).pow(2) } / n)
            assertTrue(difference > .001, "$voice $control must change more than overall gain")
        }
    }

    @Test
    fun `raw model agrees with the independent approved Python equations`() {
        val lines = javaClass.getResourceAsStream("/lorentz/audition03.tsv")!!.bufferedReader().readLines()
        val cases = lines.filterNot { it.startsWith("#") || it.isBlank() }.map { it.split('\t') }
            .groupBy { it.take(4) }
        assertEquals(54, cases.size)
        for ((key, rows) in cases) {
            val voice = LorentzVoice.valueOf(key[0])
            val macros = mapOf("TUNE" to key[1].toFloat(), "GAP" to key[2].toFloat(), "IMPACT" to key[3].toFloat())
            val raw = Lorentz.synthesize(voice, macros)
            for (row in rows) assertEquals(row[5].toDouble(), raw[row[4].toInt()].toDouble(), 2e-6,
                "approved reference $key frame ${row[4]}")
        }
    }

    @Test
    fun `all macro corners remain bounded before leveling and finite after finishing`() {
        for (voice in LorentzVoice.entries) for (bits in 0 until 64) {
            val macros = Lorentz.defaults(voice).keys.mapIndexed { i, k -> k to ((bits shr i) and 1).toFloat() }.toMap()
            val raw = Lorentz.synthesize(voice, macros)
            assertTrue(raw.all { it.isFinite() && abs(it) <= 1.000001f }, "$voice corner $bits raw")
            val out = Lorentz.render(voice, macros)
            assertEquals(Dsp.RATE, out.sampleRate)
            assertEquals(1, out.channels)
            assertTrue(out.samples.all { it.isFinite() })
            assertTrue(out.peak() in .01f.. .990001f, "$voice corner $bits peak ${out.peak()}")
            assertTrue(abs(out.samples.average()) < .005, "$voice corner $bits DC")
            assertTrue(out.frameCount <= (1.5 * Dsp.RATE).toInt())
            assertNotEquals(DrumClass.LOOP, Classifier.classify(out).drumClass)
        }
    }

    @Test
    fun `GAP and CAPACITOR control collision time in opposite directions without moving root`() {
        for (voice in LorentzVoice.entries) {
            val gaps = listOf(0f, .5f, 1f).map { Lorentz.plan(voice, mapOf("GAP" to it)) }
            assertTrue(gaps.zipWithNext().all { (a, b) -> a.collisionSeconds < b.collisionSeconds })
            assertTrue(gaps.last().collisionSeconds - gaps.first().collisionSeconds > .04)
            val rates = listOf(0f, .5f, 1f).map { Lorentz.plan(voice, mapOf("CAPACITOR" to it)) }
            assertTrue(rates.zipWithNext().all { (a, b) -> a.collisionSeconds > b.collisionSeconds })
            assertEquals(1, (gaps + rates).map { it.hz }.distinct().size)
        }
    }

    @Test
    fun `IMPACT changes contact but retires completely from the raw settled tail`() {
        for (voice in LorentzVoice.entries) {
            val a = Lorentz.synthesize(voice, mapOf("IMPACT" to 0f))
            val b = Lorentz.synthesize(voice, mapOf("IMPACT" to 1f))
            val plan = Lorentz.plan(voice, mapOf("IMPACT" to 1f))
            val start = ((plan.collisionSeconds + .002) * Lorentz.INTERNAL_RATE).toInt()
            val end = ((plan.collisionSeconds + plan.contactSeconds + .001) * Lorentz.INTERNAL_RATE).toInt()
            val rms = sqrt((start until end).sumOf { (a[it] - b[it]).toDouble().pow(2) } / (end - start))
            assertTrue(rms > .01, "$voice IMPACT branch must change the actual contact waveform")
            assertContentEquals(a.copyOfRange(end, a.size), b.copyOfRange(end, b.size))
        }
        val fuse = Lorentz.synthesize(LorentzVoice.FUSE)
        val tear = Lorentz.synthesize(LorentzVoice.LORENTZ)
        val tc = Lorentz.plan(LorentzVoice.FUSE, emptyMap()).collisionSeconds
        val from = ((tc + .002) * Lorentz.INTERNAL_RATE).toInt()
        val to = ((tc + .035) * Lorentz.INTERNAL_RATE).toInt()
        assertTrue((from until to).sumOf { abs(fuse[it] - tear[it]).toDouble() } / (to - from) > .01)
    }

    @Test
    fun `tail periods retain the requested note at registers and contact corners`() {
        for (voice in LorentzVoice.entries) for (tune in listOf(0f, .5f, 1f))
            for (gap in listOf(0f, 1f)) for (impact in listOf(0f, 1f)) {
                val m = mapOf("TUNE" to tune, "GAP" to gap, "IMPACT" to impact)
                val p = Lorentz.plan(voice, m)
                val out = Lorentz.render(voice, m).samples
                val from = ((p.collisionSeconds + p.contactSeconds + .035) * Dsp.RATE).toInt()
                val to = min(out.size - 1, from + (Dsp.RATE * .12).toInt())
                val crossings = (from until to).filter { out[it] <= 0 && out[it + 1] > 0 }
                    .map { it - out[it].toDouble() / (out[it + 1] - out[it]) }
                assertTrue(crossings.size >= 3)
                val periods = crossings.zipWithNext { a, b -> b - a }.sorted()
                val hz = Dsp.RATE / periods[periods.size / 2]
                assertTrue(abs(1200 * ln(hz / p.hz) / ln(2.0)) < 5, "$voice $m measured $hz")
            }
    }

    @Test
    fun `raw high rate reference agrees after common decimation`() {
        for (voice in LorentzVoice.entries) {
            val m = mapOf("TUNE" to 1f, "GAP" to 1f, "IMPACT" to 1f)
            val native = Lorentz.synthesize(voice, m)
            val reference = Lorentz.synthesize(voice, m, Lorentz.INTERNAL_RATE * 4)
            val down = Dsp.decimate(reference, Lorentz.INTERNAL_RATE)
            // Shared FIR introduces its own start/boundary behavior. Compare the
            // full note at the same sample positions, not a Nyquist-bin claim.
            val n = min(native.size, down.size)
            val error = (0 until n).sumOf { (native[it] - down[it]).toDouble().pow(2) }
            val energy = (0 until n).sumOf { native[it].toDouble().pow(2) }
            assertTrue(sqrt(error / energy) < .03, "$voice relative RMS ${sqrt(error / energy)}")
        }
    }

    @Test
    fun `recall sanitation touch and finite HOLD retain their contracts`() {
        for (voice in LorentzVoice.entries) {
            val defaults = Lorentz.render(voice).samples
            assertContentEquals(defaults, Lorentz.render(voice, mapOf("IMPACT" to Float.NaN, "UNKNOWN" to .3f)).samples)
            val patch = LorentzPatch("Saved collision", voice, velocity = .55f)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val recipe = PadRecipe(patch)
            assertContentEquals(recipe.render().samples, PadRecipe.fromJsonText(recipe.toJsonText()).render().samples)
            assertTrue(Lorentz.render(voice, velocity = 0f).samples.all { it == 0f })
            assertTrue(patch.render().peak() < Lorentz.render(voice).peak())
            assertTrue(Lorentz.render(voice, mapOf("HOLD" to 1f)).frameCount > defaults.size)
            assertEquals(Lorentz.scramble(voice, Random(7)), Lorentz.scramble(voice, Random(7)))
            assertTrue(Lorentz.scramble(voice, Random(7), 1f).values.all { it in 0f..1f })
            assertFailsWith<IllegalArgumentException> { patch.copy(model = 2) }
            assertFailsWith<IllegalArgumentException> { Lorentz.render(voice, velocity = Float.NaN) }
        }
    }
}
