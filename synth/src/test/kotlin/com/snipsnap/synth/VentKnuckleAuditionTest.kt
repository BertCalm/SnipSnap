package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VentKnuckleAuditionTest {
    @Test
    fun `experiment retains the exact factory source and recipes replay both finite variants`() {
        val patch = VentKnuckleExperiment.patch
        val source = VentKnuckleExperiment.source(patch)
        val sourceHash = VentKnuckleExperiment.samplesHash(source.raw)
        val traceHash = VentKnuckleExperiment.traceHash(source.trace)
        val original = VentKnuckleExperiment.render(source, VentKnuckleExperiment.Variant.ORIGINAL)
        assertContentEquals(PadRecipe(patch).render().samples, original.samples)
        for (variant in VentKnuckleExperiment.Variant.entries) {
            val rendered = VentKnuckleExperiment.render(source, variant)
            val recipe = VentKnuckleExperiment.recipe(patch, source, variant)
            assertContentEquals(rendered.samples, VentKnuckleExperiment.replay(recipe).samples)
            assertEquals(source.snip.frameCount, rendered.frameCount)
            assertTrue(rendered.samples.all { it.isFinite() && abs(it) < 1f })
            assertTrue(abs(rendered.samples.last()) < 1e-8f)
            assertEquals(sourceHash, VentKnuckleExperiment.samplesHash(source.raw), "Processing mutated source raw audio")
            assertEquals(traceHash, VentKnuckleExperiment.traceHash(source.trace), "Processing mutated trajectory evidence")
            assertEquals(0, source.diagnostics.integrationFailures)
        }
    }

    @Test
    fun `optional decay lowers body energy without changing the orbit or event schedule`() {
        val source = VentKnuckleExperiment.source()
        val original = source.snip
        val candidate = VentKnuckleExperiment.render(source, VentKnuckleExperiment.Variant.TIGHT_DECAY)
        fun bodyChange(samples: FloatArray): Double {
            fun rms(from: Double, to: Double): Double {
                val a = (from * samples.size).toInt(); val b = (to * samples.size).toInt()
                return sqrt((a until b).sumOf { samples[it].toDouble() * samples[it] } / (b - a))
            }
            return 20.0 * log10(rms(.35, .65) / rms(.05, .25))
        }
        assertTrue(bodyChange(original.samples) > 0.0, "Reference no longer reproduces the reviewed rising body")
        assertTrue(bodyChange(candidate.samples) < -12.0, "Candidate needs a clear decay for this controlled comparison")
        assertTrue(bodyChange(original.samples) - bodyChange(candidate.samples) > 15.0)
        val a = VentKnuckleExperiment.rhythm(original)
        val b = VentKnuckleExperiment.rhythm(candidate)
        assertEquals(a.frameCount, b.frameCount)
        assertEquals(8, VentKnuckleExperiment.events.size)
        for (event in VentKnuckleExperiment.events) {
            val start = kotlin.math.round(event.beat * 60.0 / VentKnuckleExperiment.BPM * a.sampleRate).toInt()
            // The exact original/candidate PCM and the same event gain occupy the same frames.
            for (i in original.samples.indices) {
                assertTrue(original.samples[i] * event.gain == a.samples[start + i])
                assertTrue(candidate.samples[i] * event.gain == b.samples[start + i])
            }
        }
    }

    @Test
    fun `experimental recipe cannot silently ignore an edited processing contract`() {
        val source = VentKnuckleExperiment.source()
        val o = Json.parse(VentKnuckleExperiment.recipe(VentKnuckleExperiment.patch, source,
            VentKnuckleExperiment.Variant.TIGHT_DECAY)).obj().toMutableMap()
        val processing = o.getValue("processing").obj().toMutableMap()
        processing["t60Seconds"] = JsonValue.Num(.28)
        o["processing"] = JsonValue.Obj(processing)
        assertFailsWith<IllegalArgumentException> { VentKnuckleExperiment.replay(Json.write(JsonValue.Obj(o))) }
    }
}
