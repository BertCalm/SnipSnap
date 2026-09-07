package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentEngineTest {

    private val rate = 44_100

    /** A 220 Hz tone, half a second, looping from a tenth of a second (a whole number of periods in). */
    private fun looped(): InstrumentEngine.Loaded {
        val periods = 22 // 22 periods of 220 Hz = 0.1 s exactly at 44.1k (4410 frames)
        val loopStart = periods * rate / 220
        val n = rate / 2
        val sample = FloatArray(n) { (0.8 * Math.sin(2 * Math.PI * 220.0 * it / rate)).toFloat() }
        val zone = InstrumentStore.Zone(0, 127, 57, "a.wav", n.toLong(), loopStart.toLong())
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("Loop", 0.2f, listOf(zone)), rate, listOf(sample))
    }

    /** A decaying 220 Hz pluck, no loop, a quarter second. */
    private fun oneShot(): InstrumentEngine.Loaded {
        val n = rate / 4
        val sample = FloatArray(n) { (0.8 * Math.sin(2 * Math.PI * 220.0 * it / rate) * Math.exp(-it / (0.05 * rate))).toFloat() }
        val zone = InstrumentStore.Zone(40, 80, 57, "p.wav", n.toLong(), 0)
        return InstrumentEngine.Loaded(InstrumentStore.Instrument("Pluck", 0.1f, listOf(zone)), rate, listOf(sample))
    }

    private fun render(engine: InstrumentEngine, seconds: Float, outRate: Int = rate): Snip {
        val frames = (seconds * outRate).toInt()
        val out = FloatArray(frames * 2)
        var at = 0
        val block = FloatArray(512)
        while (at < frames) {
            val n = minOf(256, frames - at)
            engine.render(block, n)
            System.arraycopy(block, 0, out, at * 2, n * 2)
            at += n
        }
        return Snip(out, 2, outRate)
    }

    private fun rms(s: Snip, from: Float, to: Float): Double {
        val a = (from * s.frameCount).toInt()
        val b = (to * s.frameCount).toInt()
        var acc = 0.0
        for (i in a until b) acc += s.samples[i * 2].toDouble() * s.samples[i * 2]
        return Math.sqrt(acc / (b - a))
    }

    @Test
    fun `a held note sustains through the loop, and lets go over the release`() {
        val engine = InstrumentEngine(looped(), rate)
        assertTrue(engine.noteOn(57))
        val held = render(engine, 2f)
        // Well past the half-second sample, the loop is still sounding at the same level.
        val early = rms(held, 0.05f, 0.15f)
        val late = rms(held, 0.9f, 1.0f)
        assertTrue(late > 0.9 * early, "the loop sustains: $early -> $late")
        assertEquals(listOf(57), engine.activeNotes)

        engine.noteOff(57)
        val gone = render(engine, 0.5f)
        assertTrue(rms(gone, 0f, 0.1f) > 0.1, "still sounding at the start of the release")
        assertTrue(rms(gone, 0.45f, 0.5f) < 1e-4, "silent after the 0.2 s release")
        assertTrue(engine.activeNotes.isEmpty(), "the voice is reaped")
    }

    @Test
    fun `a note above the root plays higher by the right ratio, at the device's rate`() {
        for (outRate in intArrayOf(44_100, 48_000)) {
            val engine = InstrumentEngine(looped(), outRate)
            engine.noteOn(57 + 7) // a fifth up: 220 * 1.498 = 329.6 Hz
            val out = render(engine, 1f, outRate)
            val mono = Snip(FloatArray(out.frameCount) { out.samples[it * 2] }, 1, outRate)
            val est = Pitch.detect(mono)!!
            assertTrue(abs(est.hz - 329.6f) < 4f, "at $outRate Hz: ${est.hz}")
            // And the root itself lands where the sample is, whatever the device rate.
            val root = InstrumentEngine(looped(), outRate).also { it.noteOn(57) }
            val r = render(root, 1f, outRate)
            val rm = Snip(FloatArray(r.frameCount) { r.samples[it * 2] }, 1, outRate)
            assertTrue(abs(Pitch.detect(rm)!!.hz - 220f) < 3f, "root at $outRate: ${Pitch.detect(rm)!!.hz}")
        }
    }

    @Test
    fun `an unlooped sample plays to its end and stops, and notes outside every zone are silent`() {
        val engine = InstrumentEngine(oneShot(), rate)
        assertTrue(engine.noteOn(57))
        val out = render(engine, 0.5f)
        assertTrue(rms(out, 0f, 0.05f) > 0.1)
        // Fractions of the half-second render: 0.6..1.0 is 0.3..0.5 s, past the sample's quarter second.
        assertTrue(rms(out, 0.6f, 1.0f) < 1e-6, "nothing after the sample's quarter second")
        assertTrue(engine.activeNotes.isEmpty())
        assertFalse(engine.noteOn(30), "below the zone")
        assertFalse(engine.noteOn(90), "above the zone")
        assertTrue(engine.activeNotes.isEmpty())
    }

    @Test
    fun `polyphony is capped by stealing the oldest, and eight notes never crack`() {
        val engine = InstrumentEngine(looped(), rate, maxVoices = 4)
        for (n in 0 until 6) engine.noteOn(50 + n)
        assertEquals(listOf(52, 53, 54, 55), engine.activeNotes, "the two oldest were stolen")
        val loud = InstrumentEngine(looped(), rate)
        for (n in 0 until 8) loud.noteOn(45 + n, 1f)
        val out = render(loud, 0.2f)
        assertTrue(out.samples.all { it <= 1f && it >= -1f }, "soft clipped inside full scale")
        assertTrue(out.peak() > 0.5f, "and still loud")
    }
}
