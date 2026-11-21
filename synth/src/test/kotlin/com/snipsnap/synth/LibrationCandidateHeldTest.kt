package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HOLLOW's held key ([LibrationCandidateHeld], 21 Nov 2026, kept in round held-cl-1 as KA5; ROTOR's awaits a verdict), read as the
 * cloud voices' held keys are: the trajectories traversed out and back over about four seconds, the root fitted to whole
 * turns, every oscillator running across the wrap. Pinned here: the key starts as the kept one-shot does, the loop closes
 * on real neighbouring loops with every phase whole, the root is in tune, and the soft layer is the kept soft touch.
 */
class LibrationCandidateHeldTest {
    // ROTOR's held key is written but not offered: it was not rated in held-cl-1 (KA4) and awaits a verdict.
    private val voices = listOf(LibrationVoice.HOLLOW)
    private val rate = Dsp.RATE

    private fun rms(x: FloatArray, from: Int, n: Int): Double {
        var e = 0.0
        val to = minOf(x.size, from + n)
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / max(1, to - from))
    }
    private fun db(x: Double) = 20 * log10(max(x, 1e-12))
    private fun patch(voice: LibrationVoice, midi: Int = 60) = LibrationPatch("Held", voice,
        Libration.defaults(voice) + ("HOLD" to 1f), midi = midi, heldModel = LibrationKeys.MODEL_VERSION)

    @Test
    fun `each held key starts as the kept one-shot and loops closed, in tune, at bass middle and treble`() {
        assertEquals(Libration.CLOUD_VOICES + voices, LibrationKeys.HELD_VOICES)
        assertFalse(LibrationKeys.supports(LibrationPatch("Held", LibrationVoice.ROTOR, Libration.defaults(LibrationVoice.ROTOR))))
        for (voice in voices) for (midi in listOf(36, 60, 84)) {
            val p = LibrationKeys.Session().probe(patch(voice, midi))
            val x = p.note.snip.samples
            val start = p.note.loopStartFrame.toInt()
            val one = Libration.render(voice, emptyMap(), midi, .8f).samples
            val a = (.08 * rate).toInt()
            val gk = rms(x, 0, a); val go = rms(one, 0, a)
            var d = 0.0; var e = 0.0
            for (i in 0 until a) { val u = x[i] / gk - one[i] / go; d += u * u; e += (one[i] / go).toDouble().pow(2) }
            val onset = 10 * log10(d / e)
            val loop = rms(x, start, x.size - start)
            val early = rms(x, (.3 * rate).toInt(), (.5 * rate).toInt())
            println("LIBRATION held $voice MIDI$midi key %.2f s, loop %.2f s, onset against the one-shot %.1f dB, early %+.1f dB re loop, seam %.1e cycle %.1e phase %.1e root %+.3f cents"
                .format(x.size.toDouble() / rate, p.loop.size.toDouble() / rate, onset, db(early / loop), p.seamError, p.cycleError, p.phaseError, p.rootCents))
            assertTrue(onset < -20, "$voice MIDI$midi held key does not start as the one-shot: $onset dB")
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR && p.cycleError < 1e-3 && p.phaseError < 1e-6)
            assertTrue(abs(p.rootCents) < .1)
            assertTrue(abs(db(early / loop)) < 4.0, "$voice MIDI$midi swells into its loop: ${db(early / loop)} dB")
            assertTrue(x.all { it.isFinite() && abs(it) <= .94f })
            assertContentEquals(p.loop, x.copyOfRange(start, x.size))
            assertTrue(p.loop.size.toDouble() / rate in 3.8..4.2)
            val pitch = CandidateVoiceProbes.rootCents(p.loop, Keys.midiHz(midi).toDouble(), 0.0, 1.0)
            assertTrue(abs(pitch) < 15.0, "$voice MIDI$midi held root is $pitch cents off")
            // The LOOP pad is that loop, turned to a quiet start.
            assertEquals(p.loop.size, LibrationKeys.loop(patch(voice, midi)).frameCount)
        }
    }

    @Test
    fun `the soft layer is the kept soft touch, at the hard layer's level`() {
        for (voice in voices) {
            val base = LibrationPatch("Touch", voice, Libration.defaults(voice))
            val layers = LibrationTouchKeys.capture(base)
            assertEquals(listOf(.35f, .8f), layers.map { it.sourcePatch.velocity }.map { (it * 1e4f).toInt() / 1e4f })
            val (soft, hard) = layers.map { it.note }
            // The hard layer is the held key itself, under the pair's common peak guard.
            val key = LibrationKeys.note(patch(voice))
            assertEquals(key.loopStartFrame, hard.loopStartFrame)
            assertTrue(key.snip.samples.indices.all { abs(key.snip.samples[it] * layers[1].sampleGain - hard.snip.samples[it]) < 1e-6 })
            fun loop(n: KeyNote) = n.snip.samples.copyOfRange(n.loopStartFrame.toInt(), n.snip.samples.size)
            val level = db(rms(loop(soft), 0, loop(soft).size) / rms(loop(hard), 0, loop(hard).size))
            val shapes = CandidateVoiceProbes.distance(CandidateVoiceProbes.bands(loop(soft)), CandidateVoiceProbes.bands(loop(hard)))
            println("LIBRATION held $voice soft against hard: %.1f dB third-octave, level %+.2f dB".format(shapes, level))
            assertTrue(abs(level) < 1.0, "$voice soft loop sits $level dB from the hard one")
            assertTrue(shapes > 1.0, "$voice soft and hard keys are only $shapes dB apart")
            assertFalse(soft.snip.samples.contentEquals(hard.snip.samples))
        }
    }

    /**
     * Kept by ear in round held-cl-1 (21 Nov 2026, KA5 HOLLOW): the touch-key layers at middle C from the preset (what
     * the keys instrument ships and the clip played), and the LOOP at HOLD 1 from the preset. A change here must be heard.
     */
    @Test
    fun `the heard HOLLOW held key and LOOP render exactly what round held-cl-1 heard`() {
        val preset = LibrationPresets.forVoice(LibrationVoice.HOLLOW).single()
        val lines = LibrationTouchKeys.capture(preset.copy(midi = 60)).mapIndexed { i, l ->
            "LIBRATION HOLLOW held C4 layer $i loop ${l.note.loopStartFrame} ${CandidateVoiceProbes.hash(l.note.snip.samples)}"
        } + ("LIBRATION HOLLOW LOOP " + CandidateVoiceProbes.hash(
            preset.copy(macros = preset.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION).render().samples))
        assertEquals(3, lines.size)
        assertEquals(HEARD, CandidateVoiceProbes.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the three lines above, taken in the run whose re-rendered clip KA5 matched the heard clips' SHA-256. */
        const val HEARD = "594b21e78fa97953545837409f30dfd2be292bd65735a308a7119a861624aaa8"
    }
}
