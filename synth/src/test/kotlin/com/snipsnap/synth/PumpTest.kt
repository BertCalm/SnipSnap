package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PUMP's own claims (the doc on [Pump]): AMOUNT 0 is a copy; a pad under two divisions is refused in words; the first
 * division is untouched; each later beat dips to the depth AMOUNT sets and breathes back, steady in decibels, before
 * the next; the fall does not click; one gain for every channel; the same pad renders the same samples.
 */
class PumpTest {

    private val rate = 44_100
    private val bpm = 92f
    private val quarter = (Wobble.periodSec(bpm, "1/4") * rate).toInt()

    private fun sine(hz: Float, seconds: Float, channels: Int = 1): Snip {
        val n = (seconds * rate).toInt()
        return Snip(FloatArray(n * channels) { 0.5f * sin(2.0 * PI * hz * (it / channels) / rate).toFloat() }, channels, rate)
    }

    private fun db(x: Float) = 20f * log10(x)

    @Test
    fun `AMOUNT 0 is a copy, the length is kept, and a short pad is refused in words`() {
        val tone = sine(220f, 3f)
        assertContentEquals(tone.samples, Pump.pump(tone, bpm, "1/4", 0f).samples, "PUMP at 0 is not a copy")
        assertEquals(tone.frameCount, Pump.pump(tone, bpm).frameCount, "PUMP changed the length")

        // Two quarters at 92 BPM is 1.30 s: the choir (1.44 s) is in, a snare is out.
        val snare = Onset.render(OnsetVoice.SNARE)
        val why = assertNotNull(Pump.refusal(snare, bpm, "1/4"), "a snare was not refused")
        assertTrue("1/4" in why && "92 BPM" in why, "the refusal does not say why: $why")
        assertFailsWith<IllegalArgumentException> { Pump.pump(snare, bpm) }
        assertNull(Pump.refusal(Vox.render(VoxVoice.CHOIR), bpm, "1/4"), "the choir was refused")
        // AMOUNT 0 is never refused: it does nothing.
        assertContentEquals(snare.samples, Pump.pump(snare, bpm, "1/4", 0f).samples)
    }

    @Test
    fun `the first division is untouched, so the hit the pump answers keeps its attack`() {
        for (pad in listOf(sine(220f, 3f), Vox.render(VoxVoice.CHOIR))) {
            val out = Pump.pump(pad, bpm, "1/4", 1f)
            for (i in 0 until quarter * pad.channels) assertEquals(pad.samples[i], out.samples[i], "frame ${i / pad.channels} moved")
        }
    }

    @Test
    fun `each beat dips to the depth AMOUNT sets, and the floor holds at AMOUNT 1`() {
        assertEquals(1f, Pump.dipGain(0f))
        assertEquals(-24f, db(Pump.dipGain(1f)), 1e-3f, "AMOUNT 1 is not the floor")
        val tapDb = db(Pump.dipGain(0.7f))
        assertTrue(tapDb in -10f..-8.5f, "a tap (AMOUNT 0.7) dips $tapDb dB, not about 9")

        for (amount in listOf(0.7f, 1f)) {
            val env = Pump.envelope(5 * quarter, rate, bpm, "1/4", amount)
            for (beat in 1 until 5) {
                val at = beat * quarter
                var lo = 1f
                var where = at
                for (f in at until at + quarter) if (env[f] < lo) { lo = env[f]; where = f }
                assertEquals(Pump.dipGain(amount), lo, 1e-3f, "beat $beat at AMOUNT $amount dips to $lo")
                val late = (where - at).toFloat() / rate
                assertTrue(late <= Pump.ATTACK_SEC + 1e-3f, "beat $beat's dip lands ${late * 1000} ms after the beat")
            }
        }
    }

    @Test
    fun `it breathes back steady in decibels, and holds at full level before the next beat`() {
        val env = Pump.envelope(3 * quarter, rate, bpm, "1/4", 1f)
        val attack = (Pump.ATTACK_SEC * rate).toInt()
        val releaseEnd = (Pump.RELEASE_FRACTION * quarter).toInt()
        val start = quarter + attack
        val end = quarter + releaseEnd
        // Halfway up the rise it is halfway up in decibels: a compressor's release, not a fade in the gain.
        val mid = (start + end) / 2
        assertEquals(Pump.FLOOR_DB / 2f, db(env[mid]), 0.3f, "halfway up the rise is ${db(env[mid])} dB")
        // A quarter of the way it is still three quarters down: the swell is slow at first, where a gain fade is fast.
        val early = start + (end - start) / 4
        assertEquals(Pump.FLOOR_DB * 0.75f, db(env[early]), 0.3f, "a quarter of the way up is ${db(env[early])} dB")
        for (f in end until 2 * quarter) assertEquals(1f, env[f], "frame $f before the next beat is not at full level")
        // The rise never turns back.
        for (f in start + 1 until end) assertTrue(env[f] >= env[f - 1], "the rise turned back at frame $f")
    }

    @Test
    fun `the fall into the dip does not click`() {
        // A steady level: a step in the output is a step in the gain.
        val steady = Snip(FloatArray(3 * rate) { 0.5f }, 1, rate)
        val out = Pump.pump(steady, bpm, "1/4", 1f)
        var worst = 0f
        for (i in 1 until out.frameCount) worst = maxOf(worst, abs(out.samples[i] - out.samples[i - 1]))
        // A 5 ms raised cosine over 0.47 of full scale moves at most about 0.0034 a frame; GATE's limit is 0.1.
        assertTrue(worst < 0.005f, "a step of $worst is a click")
    }

    @Test
    fun `one gain for every channel, and the same pad renders the same samples`() {
        val stereo = sine(330f, 3f, channels = 2)
        val out = Pump.pump(stereo, bpm, "1/8", 0.8f)
        for (f in 0 until out.frameCount) assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "the channels parted at $f")
        assertContentEquals(out.samples, Pump.pump(stereo, bpm, "1/8", 0.8f).samples, "PUMP is not deterministic")
        // Peak matched: a pad whose loudest moment sits in a dip comes back to its own peak.
        val late = Snip(FloatArray(3 * rate) { f -> if (f == quarter + 10) 0.9f else 0.1f }, 1, rate)
        assertEquals(late.peak(), Pump.pump(late, bpm, "1/4", 1f).peak(), 1e-6f, "the peak was not matched")
    }
}
