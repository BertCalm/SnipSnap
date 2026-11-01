package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtisPrototypeTest {
    @Test
    fun `every source reaches the native mono format without hidden leveling and replays exactly`() {
        for (voice in OtisPrototypeVoice.entries) {
            val request = OtisPrototypeRequest(voice, listOf(OtisPrototypeNote(gateSeconds = .4)))
            val first = request.render()
            val second = request.render()
            assertEquals(44_100, first.sampleRate)
            assertEquals(1, first.channels)
            assertTrue(first.durationSeconds in .4f..3f, "$voice lost its finite gate")
            assertTrue(first.samples.all { it.isFinite() }, "$voice produced nonfinite output")
            assertTrue(first.peak() in .01f..1.5f, "$voice source escaped its audition budget")
            assertTrue(abs(first.samples.map { it.toDouble() }.average()) < 1e-4, "$voice retained DC")
            assertTrue(first.samples.first() == 0f, "$voice onset guard is not silent")
            assertTrue(first.samples.last() == 0f, "$voice tail guard is not silent")
            assertContentEquals(first.samples, second.samples, "$voice recipe did not replay")
        }
    }

    @Test
    fun `velocity zero remains silent through the complete conditioning path`() {
        for (voice in OtisPrototypeVoice.entries) {
            val muted = OtisPrototypeRequest(voice,
                listOf(OtisPrototypeNote(velocity = 0f, gateSeconds = .1))).render()
            assertTrue(muted.samples.all { it == 0f }, "$voice generated sound without input work")
        }
    }

    @Test
    fun `source renders pass through decimation rather than a native rate shortcut`() {
        val note = OtisPrototypeNote(midi = 72, gateSeconds = .25, velocity = .9f)
        val request = OtisPrototypeRequest(OtisPrototypeVoice.HARP, listOf(note), model = 3)
        val actual = request.render()
        val direct = OtisAirPrototype.synthesize(request.voice, request.notes, request.air,
            request.seed, Dsp.RATE)
        val conditioned = Cleanup.removeDcOffset(Snip(direct, 1, Dsp.RATE)).samples
        for (i in 0 until 6) conditioned[i] *= i.toFloat() / 6
        Dsp.fadeTail(conditioned)
        val difference = actual.samples.indices.sumOf { abs(actual.samples[it] - conditioned[it]).toDouble() } /
            actual.frameCount
        assertTrue(difference > 1e-4, "the 4x source path is indistinguishable from the native-rate shortcut: $difference")
    }
}
