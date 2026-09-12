package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderTest {

    private val rate = 44_100

    /** 120 BPM: a beat is half a second, a sixteenth an eighth of one. */
    private val beat = rate / 2
    private val sixteenth = beat / 4

    private val kick = DrumSynth.kick()
    private val snare = DrumSynth.snare()
    private val hat = DrumSynth.closedHat()

    /** A little silence before the first sound: a hit on the very first frame has no rise for the detector to hear. */
    private val lead = rate / 10

    /**
     * Bars as sixteen-character strings: K a loud kick, k a soft one, s a
     * snare, h a hat, . a rest — after [pickupBeats] of hats, after [lead].
     */
    private fun tape(bars: List<String>, pickupBeats: Int = 1): Snip {
        val total = FloatArray(lead + pickupBeats * beat + bars.size * 4 * beat)
        fun put(at: Int, s: Snip, gain: Float) {
            for (i in s.samples.indices) if (at + i < total.size) total[at + i] += s.samples[i] * gain
        }
        for (b in 0 until pickupBeats) put(lead + b * beat, hat, 0.4f)
        for ((bi, bar) in bars.withIndex()) {
            require(bar.length == 16)
            for ((i, c) in bar.withIndex()) {
                val at = lead + pickupBeats * beat + bi * 4 * beat + i * sixteenth
                when (c) {
                    'K' -> put(at, kick, 1.0f)
                    'k' -> put(at, kick, 0.5f)
                    's' -> put(at, snare, 0.7f)
                    'h' -> put(at, hat, 0.4f)
                }
            }
        }
        return Snip(total, 1, rate)
    }

    private val barA = "K.h.s.h.k.h.s.h."
    private val barB = "K.h.s.h.h.h.s.s."
    private val barC = "K.h.h.s.k.h.s.h."
    private val barD = "K.h.s.h.k.s.s.h."

    private fun hear(source: Snip): Ladder.Pulse {
        val tempo = assertNotNull(ChopReviewModel.chop(source).tempo, "the break has a pulse")
        assertTrue(abs(tempo.bpm - 120f) < 3f || abs(tempo.bpm - 60f) < 2f || abs(tempo.bpm - 240f) < 5f, "a 120 tempo or its octave, got ${tempo.bpm}")
        return assertNotNull(Ladder.hear(source, tempo))
    }

    @Test
    fun `the one is the loud kick after the pickup, the beat is fitted, and a nudge moves the one`() {
        val source = tape(List(8) { barA })
        val pulse = hear(source)
        assertTrue(abs(pulse.beatFrames - beat) < beat * 0.02, "the beat is half a second, got ${pulse.beatFrames}")
        assertEquals(1, pulse.one, "the pickup hat is the anchor; the one is a beat later")
        assertTrue(abs(pulse.downbeat() - (lead + beat)) < 600, "the downbeat sits on the first loud kick, got ${pulse.downbeat()}")
        assertTrue(abs(pulse.downbeat(1) - (lead + 2 * beat)) < 600, "nudged a beat later")
        assertTrue(abs(pulse.downbeat(-1) - lead) < 600, "nudged a beat earlier: the pickup is the one")
        assertTrue(abs(pulse.downbeat(4) - pulse.downbeat()) < 2, "four beats round is the same one")
        assertEquals(8, pulse.wholeBars(source.frameCount))

        // Every hit, not a chop's strongest sixty-four: the pickup and the
        // first bars are quiet next to what follows, and must still anchor.
        assertTrue(Ladder.hits(source).size > ChopReviewModel.MAX_HITS, "eight bars of hats and kicks are more than 64 hits")
        assertTrue(abs(Ladder.hits(source).first() - lead) < 600, "the pickup hat is the first hit")

        // A stereo source is heard as its mono mix, on the same frames.
        val stereo = Snip(FloatArray(source.frameCount * 2) { source.samples[it / 2] }, 2, rate)
        val tempo = assertNotNull(ChopReviewModel.chop(source).tempo)
        val wide = assertNotNull(Ladder.hear(stereo, tempo))
        assertEquals(pulse.one, wide.one)
        assertEquals(pulse.phraseBars, wide.phraseBars)
        assertTrue(abs(wide.beatFrames - pulse.beatFrames) < 50.0)
    }

    @Test
    fun `the rungs cut every sixteenth, beat, bar or phrase from the one, the sixteenths capped`() {
        val source = tape(List(8) { barA })
        val pulse = hear(source)
        val bars = Ladder.cuts(source, pulse, Ladder.Rung.BAR)
        assertEquals(8, bars.size)
        for (i in 1 until bars.size) assertTrue(abs((bars[i] - bars[i - 1]) - 4 * beat) < 600, "bars are four beats apart")
        assertTrue(abs(bars[0] - (lead + beat)) < 600)
        assertEquals(32, Ladder.cuts(source, pulse, Ladder.Rung.BEAT).size)
        val sixteenths = Ladder.cuts(source, pulse, Ladder.Rung.SIXTEENTH)
        assertEquals(ChopReviewModel.MAX_HITS, sixteenths.size, "128 sixteenths, the first 64 kept")
        assertTrue(abs((sixteenths[1] - sixteenths[0]) - sixteenth) < 200)
        val phrases = Ladder.cuts(source, pulse, Ladder.Rung.PHRASE)
        assertEquals(8 / pulse.phraseBars, phrases.size)
        assertEquals(8, Ladder.cuts(source, pulse, Ladder.Rung.BAR, nudge = 1).size, "a beat later: seven whole bars and a three-beat tail, which is a pad")
        val wrapped = Ladder.cuts(source, pulse, Ladder.Rung.BAR, nudge = 3)
        assertEquals(8, wrapped.size, "three beats later the one wraps onto the pickup: eight bars from there")
        assertTrue(abs(wrapped.last() - (lead + 28 * beat)) < 600, "and the one-beat tail after them is no pad")
    }

    @Test
    fun `bars that rhyme every two are a two-bar phrase, every four a four-bar one`() {
        val abab = hear(tape(listOf(barA, barB, barA, barB, barA, barB, barA, barB)))
        assertEquals(2, abab.phraseBars)
        val abcd = hear(tape(listOf(barA, barB, barC, barD, barA, barB, barC, barD)))
        assertEquals(4, abcd.phraseBars)
        val three = hear(tape(listOf(barA, barB, barC)))
        assertEquals(3, three.phraseBars, "too few bars to compare: the whole thing is one phrase")
    }

    @Test
    fun `the ladder mode cuts named LOOP pads, and with no tempo cuts nothing and says so`() {
        val source = tape(List(4) { barA })
        val model = ChopReviewModel.chop(source, ChopReviewModel.ChopMode.Ladder(Ladder.Rung.BAR))
        assertNotNull(model.pulse)
        assertEquals(4, model.sliceCount)
        assertEquals(listOf("BAR 1", "BAR 2", "BAR 3", "BAR 4"), model.rows.map { it.label })
        assertTrue(model.rows.all { it.effectiveClass == DrumClass.LOOP && !it.unsure })
        assertEquals("BAR ×4", model.modeLabel())
        assertNull(ChopReviewModel.hitsOf(model.mode))
        val send = model.sendToGrid()
        assertEquals(setOf("BAR 1", "BAR 2", "BAR 3", "BAR 4"), send.arranged.filterNotNull().map { it.displayName }.toSet(), "the pads carry the names")
        assertTrue(send.arranged.filterNotNull().all { it.oneShot }, "a bar is a one-shot, not a gate")
        val beats = model.rechop(ChopReviewModel.ChopMode.Ladder(Ladder.Rung.BEAT))
        assertEquals(16, beats.sliceCount)
        assertEquals("BEAT 5", beats.rows[4].label)
        val merged = assertNotNull(model.merged(0))
        assertEquals(listOf("BAR 1", "BAR 3", "BAR 4"), merged.rows.map { it.label }, "a merge keeps the names it keeps")
        assertEquals("BAR ×3 · EDITED", merged.modeLabel())

        val short = Snip(FloatArray(rate) { i -> if (i < kick.frameCount) kick.samples[i] else 0f }, 1, rate)
        val none = ChopReviewModel.chop(short, ChopReviewModel.ChopMode.Ladder(Ladder.Rung.BAR))
        assertNull(none.pulse)
        assertEquals(0, none.sliceCount)
        assertEquals("BAR (NO TEMPO)", none.modeLabel())
    }
}
