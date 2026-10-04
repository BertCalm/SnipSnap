package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MERCURY, held (docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md, R2b): [Keys.mercuryPad]'s
 * doubling trick, not [Keys.seamError]. As in `SirenHeldTest`, the seam metric is vacuous on a doubled loop (copy
 * one's tail against copy two's tail are the same samples by construction). The loop's own closure is held by
 * `MercuryLoopTest`; this file tests what the pad adds on top: the right zones, the right pitch, a level that matches
 * across the keyboard, and a marker that plays the loop's wrap back without a step.
 */
class MercuryHeldTest {

    private val rate = Dsp.RATE

    private val zones get() = ZONES

    private companion object {
        /**
         * Every zone of every voice at the voice's own defaults, rendered once for the whole class (JUnit makes a
         * new instance per test, so an instance `lazy` would render the 27 zones again in every test).
         */
        val ZONES: Map<Pair<MercuryVoice, Int>, KeyNote> by lazy {
            MercuryVoice.entries.flatMap { v -> Keys.mercuryPadMidis(v).map { midi -> (v to midi) to Keys.mercuryPad(v, emptyMap(), midi) } }.toMap()
        }
    }

    private fun halves(note: KeyNote): Pair<FloatArray, FloatArray> {
        val s = note.snip.samples
        return s.copyOfRange(0, s.size / 2) to s.copyOfRange(s.size / 2, s.size)
    }

    @Test
    fun `nine zones every minor third, from each voice's own root`() {
        assertEquals((0..8).map { 60 + 3 * it }, Keys.mercuryPadMidis(MercuryVoice.PING))
        assertEquals((0..8).map { 55 + 3 * it }, Keys.mercuryPadMidis(MercuryVoice.SING))
        assertEquals((0..8).map { 55 + 3 * it }, Keys.mercuryPadMidis(MercuryVoice.BLADE))
        for (v in MercuryVoice.entries) {
            assertEquals(Mercury.rootMidi(v), Keys.mercuryPadMidis(v).first())
            assertEquals(Mercury.rootMidi(v) + Mercury.TUNE_SEMITONES, Keys.mercuryPadMidis(v).last())
        }
    }

    @Test
    fun `a midi outside the nine zones' two octaves is refused`() {
        for (v in MercuryVoice.entries) {
            val low = Mercury.rootMidi(v)
            assertFailsWith<IllegalArgumentException> { Keys.mercuryPad(v, emptyMap(), low - 1) }
            assertFailsWith<IllegalArgumentException> { Keys.mercuryPad(v, emptyMap(), low + Mercury.TUNE_SEMITONES + 1) }
        }
    }

    /**
     * The render is two bit-identical copies with the marker at the second, and the copy is exactly
     * [Mercury.renderLoop]'s own output for the same recipe: the pad adds the marker, not new audio. HOLD is forced
     * to its top step, so a patch's own HOLD (here the bottom) plays no part.
     */
    @Test
    fun `the render is two bit-identical copies, the marker at the second, and the loop itself`() {
        for (v in MercuryVoice.entries) {
            val midi = Keys.mercuryPadMidis(v)[4]
            val note = zones.getValue(v to midi)
            val n = note.snip.samples.size
            assertTrue(n % 2 == 0, "$v: an odd length can't be two equal copies")
            assertEquals((n / 2).toLong(), note.loopStartFrame, "$v: the marker sits at the second copy")
            val (one, two) = halves(note)
            assertContentEquals(one, two, "$v: the two copies must be bit-identical")
            val tune = (midi - Keys.mercuryPadMidis(v).first()) / Mercury.TUNE_SEMITONES.toFloat()
            val direct = Mercury.renderLoop(v, Mercury.defaults(v) + mapOf("TUNE" to tune, "HOLD" to 1f))
            assertContentEquals(direct, two, "$v: copy two must be Mercury.renderLoop's own render, unchanged")
            val lowHold = Keys.mercuryPad(v, mapOf("HOLD" to 0f), midi)
            assertContentEquals(note.snip.samples, lowHold.snip.samples, "$v: a patch's own HOLD must not change a held key")
        }
    }

    /**
     * What a held key plays is the loop's wrap, copy two's last sample into its first. No step across it may be
     * larger than the loop's own steepest step, and none may differ from the steps either side of it by more than a
     * sample-to-sample step ever does within the loop: a click shows as a jump, or a kink, the signal itself never
     * makes. The check is on the played wrap, which `Keys.seamError` cannot see on a doubled file.
     */
    @Test
    fun `the wrap of every zone steps as the loop itself steps`() {
        var worst = 0.0
        var worstKink = 0.0
        for ((key, note) in zones) {
            val (_, loop) = halves(note)
            val n = loop.size
            val jump = abs(loop[0] - loop[n - 1]).toDouble()
            var steepest = 0.0
            var kinkBar = 0.0
            for (i in 1 until n) steepest = maxOf(steepest, abs(loop[i] - loop[i - 1]).toDouble())
            // The loop's own largest change of step from one sample to the next: the most a kink may be.
            for (i in 2 until n) kinkBar = maxOf(kinkBar, abs((loop[i] - loop[i - 1]) - (loop[i - 1] - loop[i - 2])).toDouble())
            // The same change of step across the wrap: the step before it, then the step across it, then the one after.
            val before = (loop[n - 1] - loop[n - 2]).toDouble()
            val across = (loop[0] - loop[n - 1]).toDouble()
            val after = (loop[1] - loop[0]).toDouble()
            val kink = maxOf(abs(across - before), abs(after - across))
            worst = maxOf(worst, jump / steepest)
            worstKink = maxOf(worstKink, kink / kinkBar)
            assertTrue(jump <= steepest, "${key.first} MIDI ${key.second}: the wrap steps ${"%.2f".format(jump / steepest)} of the steepest step in the loop")
            assertTrue(kink <= kinkBar, "${key.first} MIDI ${key.second}: the wrap kinks ${"%.2f".format(kink / kinkBar)} of the loop's own largest kink")
        }
        println("MERCURY HELD: worst wrap step ${"%.3f".format(worst)} of the loop's steepest, worst kink ${"%.3f".format(worstKink)} of its largest, over ${zones.size} zones")
    }

    /**
     * Decision 10, the loop sets the level: every zone is levelled where it is held, so a keyboard run is even. All
     * 27 zones land within 1 dB of the melodic target.
     */
    @Test
    fun `every zone is levelled where it is held`() {
        val target = Dsp.MELODIC_LOUDNESS_TARGET
        var spread = 0.0
        for ((key, note) in zones) {
            val loud = Loudness.of(Snip(halves(note).second, channels = 1, sampleRate = rate))
            val db = 20 * log10((loud / target).toDouble())
            spread = maxOf(spread, abs(db))
            assertTrue(abs(db) < 1.0, "${key.first} MIDI ${key.second}: ${"%.2f".format(db)} dB from the melodic target")
            assertTrue(note.snip.samples.all { it.isFinite() && abs(it) <= 1f }, "${key.first} MIDI ${key.second}: not finite or clips")
        }
        println("MERCURY HELD: worst zone ${"%.2f".format(spread)} dB from the melodic target")
    }

    /**
     * Each zone plays its MIDI pitch: with WATER still, read over the loop, within 3 cents. (Under WATER the pitch
     * moves by design, `MercuryLoopTest`'s drift test.) The pad fits the loop's whole periods to the note, not the
     * note to the loop, so this is the same 0.3 cent claim the loop makes, kept per key.
     */
    @Test
    fun `every zone plays its midi pitch`() {
        var worst = 0.0
        for (v in MercuryVoice.entries) for (midi in Keys.mercuryPadMidis(v)) {
            val note = Keys.mercuryPad(v, mapOf("WATER" to 0f), midi)
            val hz = Keys.midiHz(midi)
            val cents = FineTuning.cents(FineTuning.measuredHz(note.snip, hz, 0.1f, 1.4f), hz.toDouble())
            worst = maxOf(worst, abs(cents))
            assertTrue(abs(cents) < 3.0, "$v MIDI $midi: plays $cents cents off")
        }
        println("MERCURY HELD: worst pitch ${"%.2f".format(worst)} cents over 27 zones")
    }

    @Test
    fun `a held render is deterministic and the macros reach it`() {
        val v = MercuryVoice.SING
        val midi = Keys.mercuryPadMidis(v)[2]
        assertContentEquals(Keys.mercuryPad(v, emptyMap(), midi).snip.samples, zones.getValue(v to midi).snip.samples, "two renders of one zone differ")
        val bright = Keys.mercuryPad(v, mapOf("GLASS" to 0.95f), midi).snip.samples
        val dull = Keys.mercuryPad(v, mapOf("GLASS" to 0.55f), midi).snip.samples
        assertTrue(!bright.contentEquals(dull), "GLASS did not reach the held render")
        // An unknown key is dropped, as in every other held render.
        assertContentEquals(zones.getValue(v to midi).snip.samples, Keys.mercuryPad(v, mapOf("SPARKLE" to 1f), midi).snip.samples)
    }

    @Test
    fun `a cancelled held render stops with a CancellationException`() {
        for (v in MercuryVoice.entries) {
            assertFailsWith<CancellationException> { Keys.mercuryPad(v, emptyMap(), Keys.mercuryPadMidis(v)[4]) { true } }
        }
    }
}
