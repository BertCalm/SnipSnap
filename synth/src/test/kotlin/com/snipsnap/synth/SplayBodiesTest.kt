package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPLAY model 3's WIRE and RUB bodies. TONGUE's accepted sound is pinned by [SplayAcceptedBankTest]
 * (all five kept populations, raw and matched PCM) and replayed unchanged there; these tests prove the
 * new bodies only read TONGUE's mechanics and never change them.
 */
class SplayBodiesTest {
    private val accepted = SplayAcceptedBank.entries.map { it.patch }

    private fun sha(x: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in x) bytes.putFloat(v)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun rms(x: FloatArray, from: Int, until: Int): Double {
        var s = 0.0
        for (i in from until until) s += x[i].toDouble() * x[i]
        return sqrt(s / (until - from))
    }

    /** Magnitude-weighted mean frequency of a Hann-windowed block. */
    private fun centroid(x: FloatArray, from: Int, size: Int = 32768): Double {
        val re = FloatArray(size) { (x[from + it] * (.5 - .5 * cos(2 * PI * it / (size - 1)))).toFloat() }
        val im = FloatArray(size)
        Fft.forward(re, im)
        var num = 0.0; var den = 0.0
        for (k in 1 until size / 2) {
            val m = hypot(re[k].toDouble(), im[k].toDouble())
            num += m * k * Dsp.RATE.toDouble() / size; den += m
        }
        return num / den
    }

    /** The quietest tenth of the active gesture's 50 ms windows against their median: how much sound stays between catches. */
    private fun sustainFloor(x: FloatArray): Double {
        val window = Dsp.RATE / 20
        val levels = (20 until 110).map { rms(x, it * window, (it + 1) * window) }.sorted()
        return levels[9] / levels[45]
    }

    @Test fun `the bodies strike exactly TONGUE's model-2 catches`() {
        val fractional = accepted.first().macros + ("POPULATION" to 5.5f / 99f)
        for (macros in accepted.map { it.macros } + listOf(fractional)) {
            val tongue = Splay.renderDetailed(SplayVoice.TONGUE, macros, 60, .8f, model = 2)
            val prepared = Splay.prepareModel2Excitation(macros)
            assertEquals(tongue.events, prepared.events)
            assertEquals(Splay.populationWeights(macros.getValue("POPULATION")).toList(), prepared.weights.toList())
            for (body in SplayBody.entries) {
                // Note, velocity and seed reach the body only, never the catches.
                assertEquals(tongue.events, SplayBodies.renderDetailed(body, macros, 72, .3f, seed = 7).events, "$body")
            }
        }
    }

    @Test fun `TONGUE renders the same bytes before and after the bodies, at both of its models`() {
        val cases = accepted.map { it.macros to 2 } + listOf(Splay.defaults(SplayVoice.TONGUE, 1) to 1)
        val before = cases.map { (m, model) -> sha(Splay.renderDetailed(SplayVoice.TONGUE, m, model = model).raw) }
        for ((m, _) in cases) for (body in SplayBody.entries) SplayBodies.render(body, m)
        val after = cases.map { (m, model) -> sha(Splay.renderDetailed(SplayVoice.TONGUE, m, model = model).raw) }
        assertEquals(before, after)
        // TONGUE stays on models 1 and 2; model 3 is the bodies' alone.
        assertFalse(Splay.supportsModel(SplayBodies.MODEL))
        assertEquals(2, Splay.MODEL_VERSION)
        assertEquals(listOf("TONGUE"), SplayVoice.entries.map { it.name })
    }

    @Test fun `both bodies ring at the requested pitch across SPLAY's range`() {
        for (body in SplayBody.entries) for (midi in listOf(36, 48, 60, 72, 84)) {
            val r = SplayBodies.renderDetailed(body, midi = midi)
            val hz = Keys.midiHz(midi).toDouble()
            val (measured, confidence) = BowMeter.pitch(r.raw, Dsp.RATE / 2, Dsp.RATE / 2, hz.toFloat())
            val cents = 1200 * ln(measured / hz) / ln(2.0)
            assertTrue(abs(cents) <= 12.0, "$body MIDI $midi: %.1f cents".format(cents))
            assertTrue(confidence > .4, "$body MIDI $midi: pitch confidence %.2f".format(confidence))
        }
    }

    @Test fun `a softer touch is a duller hit after the levels are matched`() {
        for (body in SplayBody.entries) {
            val soft = SplayBodies.render(body, velocity = .3f).samples
            val hard = SplayBodies.render(body, velocity = 1f).samples
            val ratio = centroid(soft, 2 * Dsp.RATE) / centroid(hard, 2 * Dsp.RATE)
            assertTrue(ratio < .85, "$body soft/hard brightness %.2f".format(ratio))
            val loudness = Loudness.of(soft, 1, Dsp.RATE) / Loudness.of(hard, 1, Dsp.RATE)
            assertEquals(.3.pow(.65), loudness.toDouble(), .02, "$body loudness still follows velocity")
        }
        // The contact law shortens strictly as touch or CONTACT rises, at every pitch, and never passes .38 of a period.
        for (midi in listOf(36, 60, 84)) {
            val hz = Keys.midiHz(midi).toDouble()
            var last = Double.MAX_VALUE
            for (v in listOf(0.0, .3, .6, 1.0)) {
                val w = SplayBodies.contactSeconds(.7, v, hz)
                assertTrue(w < last && w <= .38 / hz && w < .004)
                last = w
            }
            assertTrue(SplayBodies.contactSeconds(.2, .6, hz) > SplayBodies.contactSeconds(.8, .6, hz))
        }
    }

    @Test fun `WIRE and RUB are new bodies, not TONGUE settings`() {
        for (population in listOf(0f, 15f / 99f)) {
            val m = accepted.first().macros + ("POPULATION" to population)
            val tongue = Splay.render(SplayVoice.TONGUE, m, model = 2).samples
            val wire = SplayBodies.render(SplayBody.WIRE, m).samples
            val rub = SplayBodies.render(SplayBody.RUB, m).samples
            val t = centroid(tongue, 2 * Dsp.RATE)
            assertTrue(centroid(wire, 2 * Dsp.RATE) > 1.6 * t, "WIRE is a brighter, harmonic string")
            assertTrue(centroid(rub, 2 * Dsp.RATE) > 1.6 * t, "RUB is a brighter, harmonic resonator")
            // RUB keeps singing between catches; WIRE decays between them like a plucked string.
            assertTrue(sustainFloor(rub) > sustainFloor(wire) + .08,
                "population $population: RUB floor %.2f, WIRE %.2f".format(sustainFloor(rub), sustainFloor(wire)))
        }
    }

    @Test fun `RUB's held loop closes and is the real motion until the window begins`() {
        for (midi in listOf(36, 60, 84)) {
            val held = SplayBodies.renderDetailed(SplayBody.RUB, mapOf("HOLD" to 1f), midi, .6f)
            val start = assertNotNull(held.loopStart)
            assertEquals(((SplayBodies.LOOP_FROM_SECONDS + SplayBodies.HELD_CYCLES * SplayBodies.CYCLE_SECONDS) * Dsp.RATE).toInt(), held.raw.size)
            assertEquals((SplayBodies.CYCLE_SECONDS * Dsp.RATE).toInt(), held.raw.size - start, "the loop is exactly one cycle")
            val seam = Keys.seamError(held.raw, start)
            assertTrue(seam < 1e-6, "MIDI $midi seam %.2e".format(seam))
            assertTrue(SplayBodies.isLoop(SplayBody.RUB, mapOf("HOLD" to 1f)))
            // Before the loop window the held note is the finite note, sample for sample.
            val finite = SplayBodies.renderDetailed(SplayBody.RUB, emptyMap(), midi, .6f)
            assertNull(finite.loopStart)
            val early = (2.5 * Dsp.RATE).toInt()
            assertContentEquals(finite.raw.copyOf(early), held.raw.copyOf(early))
        }
    }

    @Test fun `renders are deterministic, bounded and refuse what they cannot play`() {
        for (body in SplayBody.entries) {
            val a = SplayBodies.renderDetailed(body)
            val b = SplayBodies.renderDetailed(body)
            assertContentEquals(a.snip.samples, b.snip.samples)
            assertEquals(8 * Dsp.RATE, a.snip.frameCount)
            assertTrue(a.snip.peak() <= .94f + 1e-6f)
            val started = System.nanoTime()
            SplayBodies.render(body, midi = 50)
            assertTrue((System.nanoTime() - started) / 1e6 < 3000, "$body warm render under 3 s")
            assertFailsWith<IllegalArgumentException> { SplayBodies.render(body, midi = 85) }
            assertFailsWith<IllegalArgumentException> { SplayBodies.render(body, velocity = 1.5f) }
        }
        // RUB's rosin noise follows the acoustic seed; WIRE has none.
        assertFalse(SplayBodies.render(SplayBody.RUB, seed = 1).samples.contentEquals(SplayBodies.render(SplayBody.RUB, seed = 2).samples))
        assertContentEquals(SplayBodies.render(SplayBody.WIRE, seed = 1).samples, SplayBodies.render(SplayBody.WIRE, seed = 2).samples)
        assertFailsWith<IllegalArgumentException> { SplayBodies.render(SplayBody.WIRE, mapOf("HOLD" to 1f)) }
        assertFalse(SplayBodies.supportsHold(SplayBody.WIRE))
    }
}
