package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class HydroformTest {
    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble().pow(2) } / a.size)

    @Test fun `all macro corners are finite bounded and produce an arrested ending`() {
        val names = listOf("TUNE", "CHARGE", "STANDOFF", "YIELD", "ARREST")
        for (voice in HydroformVoice.entries) for (mask in 0..31) {
            val m = names.mapIndexed { i, key -> key to ((mask shr i) and 1).toFloat() }.toMap()
            val raw = Hydroform.raw(voice, m)
            assertTrue(raw.all { it.isFinite() && abs(it) <= .940001f })
            assertTrue(raw.takeLast(Hydroform.INTERNAL_RATE / 4).all { it == 0f }, "ending must come from arrest")
            val out = Hydroform.render(voice, m)
            assertEquals(44100, out.frameCount)
            assertEquals(1, out.channels)
            assertTrue(out.samples.all { it.isFinite() })
            assertTrue(out.peak() in .01f..1f)
            assertTrue(abs(out.samples.sumOf { it.toDouble() } / out.frameCount) < .005)
            assertTrue(Loudness.of(out) >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || out.peak() >= .95f)
        }
    }

    @Test fun `every macro and source touch changes the leveled output`() {
        for (voice in HydroformVoice.entries) {
            for (macro in Hydroform.macrosFor(voice).filterNot { it.name == "HOLD" }) {
                val a = Hydroform.render(voice, mapOf(macro.name to 0f)).samples
                val b = Hydroform.render(voice, mapOf(macro.name to 1f)).samples
                assertTrue(rms(FloatArray(a.size) { a[it] - b[it] }) > .001, "$voice ${macro.name}")
            }
            val a = Hydroform.render(voice, velocity = .25f).samples
            val b = Hydroform.render(voice, velocity = 1f).samples
            assertTrue(rms(FloatArray(a.size) { a[it] - b[it] }) > .01)
            assertTrue(Hydroform.render(voice, velocity = 0f).samples.all { it == 0f })
        }
    }

    @Test fun `arrest keeps the accepted maximum and shortens active energy at intermediate steps`() {
        fun duration(arrest: Float): Double {
            val a = Hydroform.raw(HydroformVoice.DOME, mapOf("ARREST" to arrest))
            val target = a.sumOf { it.toDouble().pow(2) } * .9
            var e = 0.0
            for (i in a.indices) { e += a[i].toDouble().pow(2); if (e >= target) return i.toDouble() / Hydroform.INTERNAL_RATE }
            error("no energy")
        }
        val durations = listOf(0f, .25f, .5f, .75f, 1f).map(::duration)
        assertTrue(durations.zipWithNext().all { (a,b) -> a > b })
        val raw = Hydroform.raw(HydroformVoice.DOME, mapOf("ARREST" to 1f))
        val last = raw.indexOfLast { abs(it) > 1e-8 }.toDouble() / Hydroform.INTERNAL_RATE
        assertTrue(last in .087.. .089, "R2 maximum changed: $last")
    }

    @Test fun `settling ignores unknown keys and repairs nonfinite macros while recipes preserve touch`() {
        for (voice in HydroformVoice.entries) {
            val reference = Hydroform.render(voice).samples
            assertContentEquals(reference, Hydroform.render(voice, mapOf("TUNE" to Float.NaN, "UNKNOWN" to 1f)).samples)
            val p = HydroformPatch("Saved", voice, Hydroform.defaults(voice), .6f)
            assertEquals(p, Patches.fromJsonText(p.toJsonText()))
            assertContentEquals(p.render().samples, Patches.fromJsonText(p.toJsonText()).render().samples)
            val scrambled = Hydroform.scramble(voice, Random(42), 1f)
            assertTrue(scrambled.values.all { it.isFinite() && it in 0f..1f })
            assertFailsWith<IllegalArgumentException> { Hydroform.render(voice, velocity = Float.NaN) }
        }
        assertFalse(Hydroform.macrosFor(HydroformVoice.BULKHEAD).any { it.name == "HOLD" })
    }

    @Test fun `DOME HOLD closes against actual preroll and an independent continuation`() {
        val evidence = Hydroform.loopEvidence(emptyMap())
        assertTrue(evidence.seam < Keys.MAX_SEAM_ERROR)
        assertTrue(evidence.continuation < Keys.MAX_SEAM_ERROR)
        val m = mapOf("HOLD" to 1f)
        val snip = Hydroform.render(HydroformVoice.DOME, m)
        assertEquals(88200, snip.frameCount)
        assertEquals(DrumClass.LOOP, Hydroform.drumClassFor(HydroformVoice.DOME, m))
        assertEquals(DrumClass.KICK, Hydroform.drumClassFor(HydroformVoice.DOME))
        assertEquals(DrumClass.PERC, Hydroform.drumClassFor(HydroformVoice.BULKHEAD))
        assertContentEquals(snip.samples, Hydroform.render(HydroformVoice.DOME, m).samples)
    }

    @Test fun `open lightly driven bodies retain the requested root in the detector register`() {
        // The house detector starts at 40 Hz. Low DOME notes below it are not failed
        // tuning assertions, and distorted short corners are diagnosed rather than retuned.
        for (voice in HydroformVoice.entries) for (tune in listOf(.5f, .75f, 1f)) {
            val m = mapOf("TUNE" to tune, "CHARGE" to 0f, "YIELD" to 1f, "ARREST" to 0f)
            val estimate = assertNotNull(Pitch.detect(Hydroform.render(voice, m), .2f, .25f))
            val cents = 1200 * ln(estimate.hz / Hydroform.frequencyFor(voice, tune)) / ln(2.0)
            assertTrue(abs(cents) < 50, "$voice $tune root error $cents cents")
            assertTrue(estimate.confidence >= .3f)
        }
    }
}
