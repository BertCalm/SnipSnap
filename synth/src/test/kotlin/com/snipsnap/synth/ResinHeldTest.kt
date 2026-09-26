package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RESIN, held (docs/superpowers/specs/2026-09-25-resin-held-pad-design.md).
 * The envelope tests run at STACK 0.3, where the detuned square is silent:
 * its beat against the saws would swing a window's RMS and hide what is
 * being measured here. The beat has its own tests, in the loop cut.
 */
class ResinHeldTest {

    private val still = mapOf("STACK" to 0.3f, "CONTOUR" to 0f)

    private fun rms(s: FloatArray, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * RATE).toInt()
        val b = (toSec * RATE).toInt()
        var sum = 0.0
        for (i in a until b) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (b - a))
    }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    @Test
    fun `a held note rises over its ATTACK then stays flat`() {
        val s = Resin.renderHeld(ResinVoice.BRASS, still, Resin.Held(attackSeconds = 1f, seconds = 4f)).samples
        val early = rms(s, 0.20f, 0.30f)
        val full = rms(s, 2.5f, 3.5f)
        assertTrue(db(early / full) < -6.0, "a 1 s attack must still be quiet at a quarter second: ${db(early / full)} dB")
        val landed = rms(s, 1.2f, 1.7f)
        val late = rms(s, 3.0f, 3.5f)
        assertTrue(kotlin.math.abs(db(landed / late)) < 1.0, "past the attack the level must be flat: ${db(landed / late)} dB")
    }

    @Test
    fun `a held note does not decay`() {
        // DECAY 0 is the one-shot's shortest tail (0.15 s): held, it must not matter.
        val s = Resin.renderHeld(ResinVoice.BRASS, still + ("DECAY" to 0f), Resin.Held(0.01f, 6f)).samples
        val a = rms(s, 2.0f, 2.5f)
        val b = rms(s, 5.0f, 5.5f)
        assertTrue(kotlin.math.abs(b / a - 1.0) < 0.01, "held level drifted ${b / a} between 2 s and 5 s")
    }

    @Test
    fun `CREAM tops out at the threshold when held`() {
        assertEquals(4f, Resin.resonanceFor(1f, held = true))
        assertEquals(Dsp.Ladder.MAX_RESONANCE, Resin.resonanceFor(1f, held = false))
        assertEquals(0f, Resin.resonanceFor(0f, held = true))
    }

    @Test
    fun `held renders are deterministic`() {
        val held = Resin.Held(0.3f, 3f, squareRatio = 1.0 + 1.0 / 216.0)
        val a = Resin.renderHeld(ResinVoice.LEAD, emptyMap(), held).samples
        val b = Resin.renderHeld(ResinVoice.LEAD, emptyMap(), held).samples
        assertTrue(a.contentEquals(b))
    }

    // ---------- the loop cut: Keys.resinPad ----------

    private companion object {
        /** Every voice's nine zones at defaults, rendered once for the tests that read them. */
        val zones: Map<ResinVoice, List<Pair<Int, KeyNote>>> by lazy {
            ResinVoice.entries.associateWith { v ->
                Keys.resinPadMidis(v).map { it to Keys.resinPad(v, emptyMap(), it, attackSeconds = 0.3f) }
            }
        }
    }

    private fun loopOf(note: KeyNote): FloatArray =
        note.snip.samples.copyOfRange(note.loopStartFrame.toInt(), note.snip.samples.size)

    @Test
    fun `nine zones every minor third across each voice's range`() {
        assertEquals(listOf(33, 36, 39, 42, 45, 48, 51, 54, 57), Keys.resinPadMidis(ResinVoice.BASS))
        assertEquals(45, Keys.resinPadMidis(ResinVoice.BRASS).first())
        assertEquals(81, Keys.resinPadMidis(ResinVoice.LEAD).last())
    }

    @Test
    fun `every zone lands its pitch`() {
        // ResinTest's clean read: one saw, no sweep, gentle resonance. At
        // defaults BASS's sub-octave saw puts the waveform's true period at
        // 27.5 Hz for A1, under Pitch.detect's 40 Hz floor; the zone's pitch
        // does not depend on STACK, so the clean render proves the same thing.
        val clean = mapOf("STACK" to 0f, "CONTOUR" to 0f, "CREAM" to 0.2f, "CUTOFF" to 0.8f)
        for (voice in ResinVoice.entries) {
            val midis = Keys.resinPadMidis(voice)
            for (midi in listOf(midis.first(), midis.last())) {
                val note = Keys.resinPad(voice, clean, midi, attackSeconds = 0.01f)
                val expected = Keys.midiHz(midi)
                // What a held key plays: the loop, over and over. High zones'
                // loops are shorter than the detector's 0.25 s window.
                val loop = loopOf(note)
                val held = FloatArray(loop.size * (RATE / loop.size + 1)) { loop[it % loop.size] }
                val got = com.snipsnap.audio.Pitch.detect(com.snipsnap.audio.Snip(held, 1, RATE))
                requireNotNull(got) { "$voice midi $midi: no pitch detected" }
                val ratio = got.hz / expected
                val folded = listOf(ratio, ratio * 2, ratio / 2).minBy { kotlin.math.abs(it - 1f) }
                assertTrue(kotlin.math.abs(folded - 1f) < 0.02f, "$voice midi $midi: expected ${expected}Hz, detected ${got.hz}Hz")
            }
        }
    }

    @Test
    fun `every zone's seam closes`() {
        for ((voice, notes) in zones) for ((midi, note) in notes) {
            val e = Keys.seamError(note.snip.samples, note.loopStartFrame.toInt())
            assertTrue(e < Keys.MAX_SEAM_ERROR, "$voice midi $midi seam $e")
        }
    }

    @Test
    fun `the measured worst corner closes`() {
        // Spec, "Measured, not guessed": BASS A1, lowest cutoff, most resonance
        // a held note allows, slowest sweep - worst probe seam 7.1e-5.
        val bottom = Keys.resinPadMidis(ResinVoice.BASS).first()
        val cases = listOf(0.3f, 0.6f, 1.0f).map { it to 0.01f } + (0.6f to 2.5f)
        for ((stack, attack) in cases) {
            val macros = mapOf("CUTOFF" to 0f, "CREAM" to 1f, "CONTOUR" to 1f, "DECAY" to 1f, "STACK" to stack)
            val note = Keys.resinPad(ResinVoice.BASS, macros, bottom, attack)
            val e = Keys.seamError(note.snip.samples, note.loopStartFrame.toInt())
            assertTrue(e < Keys.MAX_SEAM_ERROR, "STACK $stack attack $attack seam $e")
        }
    }

    @Test
    fun `the square is snapped to one whole beat per loop`() {
        val base = 110f
        val plan = Keys.planLoop(base, stack = 0.8f)
        val ratio = requireNotNull(plan.squareRatio) { "STACK 0.8 sounds the square" }
        assertEquals(1.0 + 1.0 / (2.0 * plan.k), ratio)
        assertEquals(Math.round(plan.k * 2.0 * RATE / base).toInt(), plan.loopFrames)
        // K comes straight from the asked detune: only K's rounding moves it.
        val cents = 1200.0 * kotlin.math.ln(ratio) / kotlin.math.ln(2.0)
        val asked = Dsp.lin(0.8f, 3f, 14f)
        assertTrue(kotlin.math.abs(cents - asked) < 0.25, "snapped $cents cents, asked $asked")
    }

    @Test
    fun `the loop is whole frames because the pitch moves by a hair to fit it`() {
        for (voice in ResinVoice.entries) for (midi in Keys.resinPadMidis(voice)) for (stack in listOf(0.3f, 0.6f, 1.0f)) {
            val note = Keys.midiHz(midi)
            val plan = Keys.planLoop(note, stack)
            // K sub-octave periods fill the loop exactly: 2K cycles of the note.
            val cycles = plan.baseHz * plan.loopFrames / RATE
            assertTrue(kotlin.math.abs(cycles - 2.0 * plan.k) < 1e-9, "$voice $midi: $cycles cycles, want ${2 * plan.k}")
            val nudge = 1200.0 * kotlin.math.ln(plan.baseHz / note) / kotlin.math.ln(2.0)
            assertTrue(kotlin.math.abs(nudge) < 0.2, "$voice $midi STACK $stack: pitch moved $nudge cents")
        }
    }

    @Test
    fun `a bright zone's seam closes as cleanly as a dark one`() {
        // The case that exposed the fractional-frame residue: before the
        // pitch fit, LEAD at CUTOFF 1 seamed at up to 6.5e-4.
        val bright = mapOf("CUTOFF" to 1f, "CREAM" to 0f, "CONTOUR" to 0f)
        var worst = 0.0
        for (stack in listOf(0.6f, 1.0f)) for (midi in Keys.resinPadMidis(ResinVoice.LEAD)) {
            val n = Keys.resinPad(ResinVoice.LEAD, bright + ("STACK" to stack), midi, 0.01f)
            worst = maxOf(worst, Keys.seamError(n.snip.samples, n.loopStartFrame.toInt()))
        }
        // Measured after the fit: 1.8e-15, floating-point noise. The residue it
        // fixed produced 2e-4 and up, so this bound catches its return with
        // seven orders to spare either side.
        assertTrue(worst < 1e-8, "bright LEAD worst seam $worst - has a fractional-frame residue crept back?")
    }

    @Test
    fun `no square no snap`() {
        val plan = Keys.planLoop(110f, stack = 0.3f)
        assertEquals(null, plan.squareRatio)
        assertTrue(kotlin.math.abs(plan.loopFrames.toFloat() / RATE - 0.5f) < 0.1f, "organ-style loop near 0.5 s, got ${plan.loopFrames}")
    }

    @Test
    fun `the loop starts after the attack has settled`() {
        val midi = Keys.resinPadMidis(ResinVoice.LEAD)[4]
        for (attack in listOf(0.01f, 2.0f)) {
            val note = Keys.resinPad(ResinVoice.LEAD, emptyMap(), midi, attack)
            assertEquals(((attack + Keys.RESIN_PAD_SETTLE_SECONDS) * RATE).roundToLong(), note.loopStartFrame, "attack $attack")
        }
    }

    @Test
    fun `a zone's loop is as loud as its neighbours`() {
        for ((voice, notes) in zones) {
            val loud = notes.map { (_, n) -> com.snipsnap.audio.Loudness.of(com.snipsnap.audio.Snip(loopOf(n), 1, RATE)).toDouble() }
            val median = loud.sorted()[loud.size / 2]
            loud.forEachIndexed { i, l -> assertTrue(kotlin.math.abs(db(l / median)) < 1.0, "$voice zone $i is ${db(l / median)} dB off") }
        }
    }

    @Test
    fun `a seam that does not close is refused by name`() {
        val period = 100
        val loopStart = 2_000
        val s = FloatArray(loopStart + period * 10 + period / 2) { kotlin.math.sin(2.0 * Math.PI * it / period).toFloat() }
        val e = assertFailsWith<IllegalArgumentException> { Keys.requireSeam("BRASS A2", s, loopStart) }
        assertTrue("BRASS A2" in e.message.orEmpty(), "the refusal names the zone: ${e.message}")
    }

    @Test
    fun `resinPad refuses a note outside the voice`() {
        val midis = Keys.resinPadMidis(ResinVoice.BRASS)
        assertFailsWith<IllegalArgumentException> { Keys.resinPad(ResinVoice.BRASS, emptyMap(), midis.first() - 1, 0.3f) }
        assertFailsWith<IllegalArgumentException> { Keys.resinPad(ResinVoice.BRASS, emptyMap(), midis.first() + 25, 0.3f) }
    }

    @Test
    fun `Held refuses an attack outside its range`() {
        assertFailsWith<IllegalArgumentException> { Resin.Held(0f, 4f) }
        assertFailsWith<IllegalArgumentException> { Resin.Held(3f, 4f) }
        assertFailsWith<IllegalArgumentException> { Resin.Held(1f, 0.5f) }
    }

    @Test
    fun `a held zone nobody wants any more stops, and the retry lets the stop through`() {
        var asked = 0
        val t0 = System.nanoTime()
        assertFailsWith<java.util.concurrent.CancellationException> {
            // The slowest zone there is: the longest attack, the lowest note.
            Keys.resinPad(ResinVoice.BASS, emptyMap(), Keys.resinPadMidis(ResinVoice.BASS).first(), Resin.ATTACK_MAX_SECONDS) { ++asked > 2 }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(3, asked, "asked once too often: the retry caught the stop and rendered again")
        assertTrue(ms < 1_000, "a cancelled zone ran on for ${ms}ms")
    }

    @Test
    fun `asking changes nothing about a held zone that isn't cancelled`() {
        val midi = Keys.resinPadMidis(ResinVoice.LEAD)[4]
        val plain = Keys.resinPad(ResinVoice.LEAD, emptyMap(), midi, 0.3f)
        val asked = Keys.resinPad(ResinVoice.LEAD, emptyMap(), midi, 0.3f) { false }
        assertEquals(plain.loopStartFrame, asked.loopStartFrame)
        assertTrue(plain.snip.samples.contentEquals(asked.snip.samples))
    }
}
