package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Loudness
import kotlin.math.*
import kotlin.test.*

class PatioTreatmentsTest {
    @Test fun `treatments replay preserve inputs and stay healthy`() {
        val input = PatioScrape.render(PatioScrape.Direction.FORWARD)
        val saved = input.samples.copyOf()
        val outputs = PatioTreatments.ALL.map { recipe ->
            val s = PatioTreatments.process(input, recipe)
            assertContentEquals(s.samples, PatioTreatments.process(input, recipe).samples)
            assertContentEquals(saved, input.samples)
            assertTrue(s.samples.all { it.isFinite() }); assertTrue(s.peak() in .01f..1f)
            assertTrue(abs(s.samples.average()) < .001); assertTrue(abs(s.samples.last()) < .001)
            val matched = AuditionLevel.level(s)
            assertTrue(abs(Loudness.of(matched) - .03f) < .0001f)
            matched.samples
        }
        for (i in outputs.indices) for (j in i+1 until outputs.size) {
            val difference = sqrt(outputs[i].indices.sumOf { (outputs[i][it] - outputs[j][it]).toDouble().pow(2) } / outputs[i].size)
            assertTrue(difference > .003, "treatments collapse after matching")
        }
    }
    @Test fun `silence stays silent and chewed loss affects the long take`() {
        val silent = Snip(FloatArray(Dsp.RATE * 5), 1, Dsp.RATE)
        val probe = silent.copy(samples = FloatArray(silent.samples.size) { (.25 * sin(2 * PI * 231 * it / Dsp.RATE)).toFloat() })
        val recipe = PatioTreatments.ALL.single { it.id == "chewed" }
        assertTrue(PatioTreatments.process(silent, recipe).samples.all { it == 0f })
        val a = PatioTreatments.process(probe, recipe).samples
        val b = PatioTreatments.process(probe, recipe.copy(dropout = 0f)).samples
        assertTrue((Dsp.RATE until Dsp.RATE * 2).any { abs(a[it] - b[it]) > .001f })
    }
}
