package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtisPrototypeV4Test {
    private fun rms(x: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt()
        val end = (until * Dsp.RATE).toInt().coerceAtMost(x.size)
        return sqrt((start until end).sumOf { x[it].toDouble() * x[it] } / (end - start))
    }

    @Test
    fun `causal conditioning preserves leading silence and settles DC before the end`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = FloatArray(rate * 2)
        for (i in rate / 2 until rate) raw[i] = (.25 + .5 * sin(2 * Math.PI * 220 * i / rate)).toFloat()
        val out = OtisPrototypeV4.condition(raw)
        assertEquals(1, out.channels)
        assertEquals(Dsp.RATE, out.sampleRate)
        assertTrue(out.samples.take((.45 * Dsp.RATE).toInt()).all { it == 0f }, "mean subtraction lifted silence")
        assertTrue(rms(out.samples, .8, .95) > .2, "DC removal erased the musical signal")
        assertTrue(rms(out.samples, 1.5, 1.9) < 1e-6, "DC blocker did not settle")
        assertTrue(out.samples.all { it.isFinite() })
    }

    @Test
    fun `delayed front pew no longer gains DC in its silent lead and tail`() {
        val front = OtisPresetStudy.cases().first { it.presetId == "organ_front_pew" && it.group == "Note" }.request
            .copy(notes = listOf(OtisPrototypeNote(startSeconds = .5)))
        val old = front.copy(model = 3).render()
        val current = front.copy(model = 4).render()
        assertTrue(rms(old.samples, .05, .45) > .01, "the diagnostic no longer exercises legacy offset")
        assertTrue(rms(current.samples, .05, .45) < 1e-7, "silence before a delayed note is not silent")
        val end = current.durationSeconds.toDouble()
        assertTrue(rms(current.samples, end - .036, end - .004) < 2e-6, "DC remained before the final fade")
    }

    @Test
    fun `new model recipe replays and keeps velocity timbre through the actual four times path`() {
        for (voice in OtisPrototypeVoice.entries) {
            val request = OtisPrototypeRequest(voice, listOf(OtisPrototypeNote(gateSeconds = .4)), model = 4)
            val source = request.render()
            val restored = OtisPrototypeRecipe.fromJsonText(OtisPrototypeRecipe.toJsonText(request)).render()
            assertContentEquals(source.samples, restored.samples, "$voice did not replay model4")
            val soft = request.copy(notes = listOf(request.notes.single().copy(velocity = .25f))).render()
            assertTrue(rms(source.samples, .1, .35) > rms(soft.samples, .1, .35) * 1.5, "$voice lost dynamics")
            assertTrue(abs(source.samples.map { it.toDouble() }.average()) < 1e-4)
            val muted = request.copy(notes = listOf(request.notes.single().copy(velocity = 0f))).render()
            assertTrue(muted.samples.all { it == 0f }, "$voice voiced a zero-velocity note")
        }
    }

    @Test
    fun `complete output cannot bloom after a released short key with long attack`() {
        val isolated = OtisOrganSettings(drawbars = List(9) { if (it == 2) 1f else 0f },
            click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f,
            attackSeconds = 2.0, releaseSeconds = 3.0, cabinetHz = 20_000.0)
        val request = OtisPrototypeRequest(OtisPrototypeVoice.ORGAN,
            listOf(OtisPrototypeNote(gateSeconds = .04)), organ = isolated, model = 4)
        val x = request.render().samples
        assertTrue(rms(x, .95, 1.05) < rms(x, .025, .04) * 1.2, "attack continued rising after release")
    }
}
