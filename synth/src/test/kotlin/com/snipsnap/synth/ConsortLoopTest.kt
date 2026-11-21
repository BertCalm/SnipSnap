package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HOLD's top step: one seamless loop of the held note, for a gated pad that sustains while held. */
class ConsortLoopTest {
    private val rate = Dsp.RATE
    private val top = mapOf("HOLD" to 1f)

    private fun wrapStep(x: FloatArray): Float = abs(x[0] - x[x.size - 1])

    private fun steepest(x: FloatArray): Float {
        val steps = FloatArray(x.size - 1) { abs(x[it + 1] - x[it]) }
        steps.sort()
        return steps[((steps.size - 1) * 0.999).toInt()]
    }

    @Test
    fun `every voice's top step is one two-second loop whose wrap is no steeper than its own audio`() {
        for (voice in ConsortVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val m = Consort.settled(top + ("TUNE" to tune), voice)
            val r = Consort.renderLoop(voice, m, emptyList(), Consort.DEFAULT_VELOCITY, 0)
            val label = "$voice TUNE $tune"
            assertTrue(abs(r.lengthFrames - Consort.HELD_PERIOD_SECONDS * rate) <= rate / 40, "$label loops at ${r.lengthFrames}")
            assertEquals(r.lengthFrames, r.loop.size, label)
            assertTrue(r.correlation >= Bore.LOOP_MIN_CORRELATION, "$label correlates ${r.correlation}")
            assertTrue(r.blendDipDb >= -Bore.LOOP_MAX_DIP_DB, "$label dips ${r.blendDipDb}")
            assertTrue(wrapStep(r.loop) <= steepest(r.loop), "$label steps ${wrapStep(r.loop)} at its wrap")
            // levelled as every render is: to the loudness target, unless the peak reaches the ceiling first
            val loud = Loudness.of(r.loop, 1, rate)
            val peak = r.loop.maxOf { abs(it) }
            assertTrue(abs(loud - Dsp.MELODIC_LOUDNESS_TARGET) <= 0.02f || peak >= 0.98f, "$label reads $loud at peak $peak")
            assertTrue(r.loop.all { it.isFinite() && it in -1f..1f }, label)
            assertEquals(DrumClass.LOOP, Consort.drumClassFor(voice, m))
        }
    }

    @Test
    fun `a section, every gesture, the touches and the takes sustain too, and render is the loop`() {
        for (preset in ConsortPresets.all().filter { it.chairs.isNotEmpty() }) {
            val p = preset.withMacros(preset.macros + top)
            val s = p.render()
            assertTrue(abs(s.frameCount - Consort.HELD_PERIOD_SECONDS * rate) <= rate / 40, "${preset.name} renders ${s.frameCount}")
            assertTrue(wrapStep(s.samples) <= steepest(s.samples), "${preset.name} steps at its wrap")
        }
        val flute = ConsortPresets.forVoice(ConsortVoice.FLUTE).first()
        for (g in ConsortGesture.entries) {
            val p = flute.withMacros(flute.macros + top + ("GESTURE" to Consort.gestureValue(g)) + ("MOTION" to 1f))
            assertTrue(p.render().samples.all { it.isFinite() }, "$g")
        }
        val loops = listOf(0.3f, 1f).map { flute.copy(macros = flute.macros + top, velocity = it).render().samples } +
            listOf(1, 2).map { flute.copy(macros = flute.macros + top, take = it).render().samples }
        for (i in loops.indices) for (j in i + 1 until loops.size) assertTrue(!loops[i].contentEquals(loops[j]), "loops $i and $j are one render")
        val p = flute.withMacros(flute.macros + top)
        assertContentEquals(p.render().samples, p.render().samples, "the loop is deterministic")
    }

    @Test
    fun `SCRAMBLE never rolls HOLD onto the sustaining step`() {
        val random = Random(7)
        repeat(400) {
            val m = Consort.scramble(ConsortVoice.SAX, random, temperature = 1f, near = ConsortPatch("x", ConsortVoice.SAX, Consort.defaults(ConsortVoice.SAX) + top))
            assertTrue(!Consort.isLoop(m.getValue("HOLD")), "SCRAMBLE rolled HOLD ${m["HOLD"]}")
        }
    }
}
