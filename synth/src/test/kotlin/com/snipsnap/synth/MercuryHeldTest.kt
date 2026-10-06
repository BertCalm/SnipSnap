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

    /** Every zone of every voice at the voice's own defaults, shared across the test classes that read them ([MercuryPadZones]). */
    private val zones get() = MercuryPadZones.all()

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
     * [Mercury.renderLoop]'s own output for the same recipe: the pad adds the marker, not new audio.
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
        }
    }

    /**
     * The key and the top step win over a patch's own: TUNE is fixed by the key (a patch's TUNE plays no part in a
     * held render) and HOLD is forced to its top step. The loop path never reads HOLD, so the HOLD half of this can
     * only guard a future change that makes it; the TUNE half is the one that bites today.
     */
    @Test
    fun `the key and the top step win over a patch's own TUNE and HOLD`() {
        for (v in MercuryVoice.entries) {
            val midi = Keys.mercuryPadMidis(v)[4]
            val other = Keys.mercuryPad(v, mapOf("TUNE" to 0.9f, "HOLD" to 0f), midi)
            assertContentEquals(MercuryPadZones.note(v, midi).snip.samples, other.snip.samples, "$v: a patch's own TUNE or HOLD changed a held key")
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
     * The loop sets the level, as the RESIN held pad's decision 10 has it (`Keys.resinPad`): every zone is levelled
     * where it is held, so a keyboard run is even. All 54 zones land within 1 dB of the melodic target.
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
     * Each zone plays its MIDI pitch: with WATER still, read over the loop, within 3 cents (the loop's own bar,
     * `MercuryLoopTest`; 0.3 measured). The pad fits the loop's whole periods to the note, not the note to the loop.
     * The two ends and the middle of each voice's keyboard; `MercuryLoopTest` holds the rest of TUNE. (Under WATER
     * the pitch moves by design: the next test.)
     */
    @Test
    fun `the zones play their midi pitch`() {
        var worst = 0.0
        for (v in MercuryVoice.entries) {
            val midis = Keys.mercuryPadMidis(v)
            for (midi in listOf(midis.first(), midis[4], midis.last())) {
                val note = Keys.mercuryPad(v, mapOf("WATER" to 0f), midi)
                val hz = Keys.midiHz(midi)
                val cents = FineTuning.cents(FineTuning.measuredHz(note.snip, hz, 0.1f, 1.4f), hz.toDouble())
                worst = maxOf(worst, abs(cents))
                assertTrue(abs(cents) < 3.0, "$v MIDI $midi: plays $cents cents off")
            }
        }
        println("MERCURY HELD: worst pitch ${"%.2f".format(worst)} cents at WATER 0, ends and middle of each voice")
    }

    /**
     * The factory instruments are the voices at their defaults, WATER included, and under WATER the pitch moves by
     * design (about 8 cents of swing at the defaults) while the retune locks the loop to the whole cycles nearest the
     * model's own mean, which can sit a few cents from the plan (BLADE's top zone, measured about 3 cents sharp, as
     * the one-shot is). So the claim at the defaults is the mean pitch over the whole loop within 6 cents of the key:
     * wide enough for that, narrow enough to catch a zone a quarter tone off. The mean is of windows hopped across
     * one loop of the doubled file, so the WATER orbit is covered evenly.
     */
    @Test
    fun `every zone at its defaults is near its midi pitch on average`() {
        var worst = 0.0
        for ((key, note) in zones) {
            val hz = Keys.midiHz(key.second)
            val loopSeconds = (note.snip.samples.size / 2).toDouble() / rate
            val reads = ArrayList<Double>()
            var t = 0.0
            while (t < loopSeconds) {
                reads += FineTuning.cents(FineTuning.measuredHz(note.snip, hz, t.toFloat(), 0.5f), hz.toDouble())
                t += 0.1
            }
            val mean = reads.average()
            worst = maxOf(worst, abs(mean))
            assertTrue(abs(mean) < 6.0, "${key.first} MIDI ${key.second}: averages ${"%.2f".format(mean)} cents off over the loop at its defaults")
        }
        println("MERCURY HELD: worst mean pitch ${"%.2f".format(worst)} cents at the defaults, over ${zones.size} zones")
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
