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
 * The held keys of BOWL, SCATTER and TONGUE ([CausticCandidateHeld], 21 Nov 2026, kept in round held-cl-1 as KA1-KA3): the
 * one-shot's own strike, repeated in a varied hand. Pinned here: the key starts as the kept one-shot does, the loop
 * closes on real history, the repeats hold the first strike's level instead of building on it, the hand varies every
 * strike, MOTION moves the strikes and not the body, the soft layer is the kept soft touch at the hard layer's level.
 */
class CausticCandidateHeldTest {
    private val voices = listOf(CausticVoice.BOWL, CausticVoice.SCATTER, CausticVoice.TONGUE)
    private val rate = Dsp.RATE

    private fun rms(x: FloatArray, from: Int, n: Int): Double {
        var e = 0.0
        val to = minOf(x.size, from + n)
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / max(1, to - from))
    }
    private fun db(x: Double) = 20 * log10(max(x, 1e-12))
    private fun held(voice: CausticVoice, extra: Map<String, Float> = emptyMap()) =
        CausticInstrument.defaults(voice) + extra + ("HOLD" to 1f)

    @Test
    fun `each held key starts as the kept one-shot, then loops closed on real history at bass middle and treble`() {
        for (voice in voices) for (midi in listOf(36, 60, 84)) {
            val probe = CausticInstrument.heldProbe(voice, held(voice), midi)
            val note = probe.note
            val x = note.snip.samples
            val start = note.loopStartFrame.toInt()
            val one = CausticInstrument.render(voice, emptyMap(), midi).samples
            // The first 80 ms are the one-shot's strike: the same shape once both are levelled on them.
            val a = (.08 * rate).toInt()
            val gk = rms(x, 0, a); val go = rms(one, 0, a)
            var d = 0.0; var e = 0.0
            for (i in 0 until a) { val u = x[i] / gk - one[i] / go; d += u * u; e += (one[i] / go).toDouble().pow(2) }
            val onset = 10 * log10(d / e)
            val loop = rms(x, start, x.size - start)
            val ring = rms(x, (.3 * rate).toInt(), (.5 * rate).toInt())
            println("CAUSTIC held $voice MIDI$midi key %.2f s, loop from %.2f s (%d settle loops), onset against the one-shot %.1f dB, ring at .3 s %+.1f dB re loop, seam %.1e"
                .format(x.size.toDouble() / rate, start.toDouble() / rate, probe.diagnostics.prefixCycles, onset, db(ring / loop), probe.seamError))
            assertTrue(onset < -40, "$voice MIDI$midi held key does not start as the one-shot: $onset dB")
            assertTrue(probe.seamError < Keys.MAX_SEAM_ERROR && probe.continuationError < CausticHeld.MAX_CONTINUATION_ERROR)
            assertTrue(probe.diagnostics.stateResidual < 1e-5 && probe.diagnostics.continuationError < 1e-7)
            assertTrue(x.all { it.isFinite() && abs(it) <= .991f })
            // The repeats hold the level the first strike left, not a swell on top of it.
            assertTrue(abs(db(ring / loop)) < 6.0, "$voice MIDI$midi loop is ${db(loop / ring)} dB from the first ring")
            assertTrue(x.size.toDouble() / rate < 30.0, "$voice MIDI$midi key is ${x.size.toDouble() / rate} s long")
            // The loop is the LOOP pad's loop.
            assertContentEquals(probe.loop.samples, x.copyOfRange(start, x.size))
            val pitch = CandidateVoiceProbes.rootCents(probe.loop.samples, 440.0 * 2.0.pow((midi - 69) / 12.0), 0.0,
                probe.loop.samples.size.toDouble() / rate)
            assertTrue(abs(pitch) < 15.0, "$voice MIDI$midi held root is $pitch cents off")
        }
    }

    @Test
    fun `the hand varies every strike, and the loop is four periods of the still hold`() {
        for (voice in voices) {
            val m = held(voice)
            val loop = CausticInstrument.renderLoop(voice, m).samples
            val frames = CausticSustain.loopFrameCount(60, m.getValue("LENGTH"))
            assertEquals(4 * frames, loop.size)
            // A still hold repeats every period; the varied one does not: no quarter of the loop is the next.
            val quarters = (0 until 4).map { loop.copyOfRange(it * frames, (it + 1) * frames) }
            for (i in 0 until 3) {
                var d = 0.0; var e = 0.0
                for (k in 0 until frames) { val u = quarters[i][k] - quarters[i + 1][k]; d += u * u; e += quarters[i][k].toDouble().pow(2) }
                assertTrue(d / e > 1e-3, "$voice periods $i and ${i + 1} are the same (${d / e})")
            }
            val still = CausticCandidateHeld.held(voice, m, 60, .75, 4, { false }, null)
            val varied = CausticCandidateHeld.held(voice, m, 60, .75, 4, { false }, CausticInstrument.HELD_HUMAN)
            assertFalse(still.internal.contentEquals(varied.internal), "$voice hand changes nothing")
            assertContentEquals(varied.internal, CausticCandidateHeld.held(voice, m, 60, .75, 4, { false }, CausticInstrument.HELD_HUMAN).internal,
                "$voice held key is not deterministic")
            assertEquals(CausticInstrument.HELD_HUMAN, CausticSustain.HUMAN_STRONGER)
        }
    }

    @Test
    fun `MOTION moves the strikes round the lit arc and leaves the body where it is`() {
        for (voice in voices) {
            val m = held(voice, mapOf("LENGTH" to .2f, "MOTION" to .6f))
            val moving = CausticSustain.held(voice, m, 60, .75, human = CausticInstrument.HELD_HUMAN)
            val still = CausticSustain.held(voice, m + ("MOTION" to 0f), 60, .75, human = CausticInstrument.HELD_HUMAN)
            val d = moving.diagnostics
            assertTrue(d.sourceMax - d.sourceMin > .2 && d.focusMax - d.focusMin > .15, "$voice MOTION did not move the arc")
            assertTrue(d.continuationError < 1e-7 && d.stateResidual < 1e-5)
            assertContentEquals(still.frequencies, moving.frequencies, "$voice MOTION moved the body")
            val a = CausticHeld.probe(voice, m, 60, .75f, 2, 4, true, { false }).loop.samples
            val b = CausticHeld.probe(voice, m + ("MOTION" to 0f), 60, .75f, 2, 4, true, { false }).loop.samples
            val shapes = CandidateVoiceProbes.distance(CandidateVoiceProbes.bands(a), CandidateVoiceProbes.bands(b))
            println("CAUSTIC held $voice MOTION .6 against 0: %.1f dB third-octave".format(shapes))
            assertTrue(shapes > .3, "$voice MOTION is not heard in the loop: $shapes dB")
        }
    }

    @Test
    fun `the soft layer is the kept soft touch, darker, at the hard layer's level`() {
        assertEquals(.3f, .75f * CausticInstrument.SOFT_TOUCH_SHARE, 1e-6f)
        for (voice in voices) {
            val (soft, hard) = CausticInstrument.heldLayers(voice, CausticInstrument.defaults(voice))
            assertContentEquals(CausticInstrument.keyNote(voice, held(voice)).snip.samples, hard.snip.samples)
            fun loop(n: KeyNote) = n.snip.samples.copyOfRange(n.loopStartFrame.toInt(), n.snip.samples.size)
            val level = db(rms(loop(soft), 0, loop(soft).size) / rms(loop(hard), 0, loop(hard).size))
            val shapes = CandidateVoiceProbes.distance(CandidateVoiceProbes.bands(loop(soft)), CandidateVoiceProbes.bands(loop(hard)))
            println("CAUSTIC held $voice soft against hard: %.1f dB third-octave, level %+.2f dB".format(shapes, level))
            assertTrue(abs(level) < .5, "$voice soft loop sits $level dB from the hard one")
            assertTrue(shapes > 2.0, "$voice soft and hard keys are only $shapes dB apart")
            assertTrue(Keys.seamError(soft.snip.samples, soft.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
        }
    }

    @Test
    fun `silent at zero touch, and the kept one-shots are what they were`() {
        for (voice in voices) {
            assertTrue(CausticSustain.held(voice, held(voice), 60, 0.0).internal.all { it == 0f })
            // Below the top step HOLD still lengthens the finite note.
            assertFalse(CausticInstrument.isLoop(voice, mapOf("HOLD" to .5f)))
            assertEquals(Presets.forVoice("CAUSTIC", voice.name).single().render().samples.size,
                CausticInstrument.render(voice).samples.size)
        }
    }

    /**
     * Kept by ear in round held-cl-1 (21 Nov 2026, KA1 BOWL, KA2 SCATTER, KA3 TONGUE): the zone the keys instrument
     * ships at middle C from each voice's preset, soft and hard layer (what CausticPadMaker.renderLayers renders and the
     * clip played), and the LOOP at HOLD 1 from the preset. Any change to these is a sound change and must be heard.
     */
    @Test
    fun `the heard held keys and LOOPs render exactly what round held-cl-1 heard`() {
        val lines = ArrayList<String>()
        for (voice in voices) {
            val preset = CausticPresets.forVoice(voice).single()
            CausticInstrument.heldLayers(voice, preset.macros, 60, preset.velocity, preset.model).forEachIndexed { i, n ->
                lines += "CAUSTIC $voice held C4 layer $i loop ${n.loopStartFrame} ${CandidateVoiceProbes.hash(n.snip.samples)}"
            }
            lines += "CAUSTIC $voice LOOP " + CandidateVoiceProbes.hash(
                CausticInstrument.render(voice, preset.macros + ("HOLD" to 1f), preset.midi, preset.velocity).samples)
        }
        assertEquals(9, lines.size)
        assertEquals(HEARD, CandidateVoiceProbes.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /** SHA-256 over the nine lines above, taken in the run whose re-rendered clips KA1-KA3 matched the heard clips' SHA-256. */
        const val HEARD = "702d665a082392edd00145a55c302d92079d00e87b2133fd863e3754c97c1a43"
    }
}
