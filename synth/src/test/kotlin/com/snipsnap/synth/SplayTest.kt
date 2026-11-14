package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Model-1 behavior stays replayable while the revised model has its own listening gates. */
class SplayTest {
    private val voice = SplayVoice.TONGUE
    private fun macros(vararg edits: Pair<String, Float>): Map<String, Float> = Splay.defaults(voice, model = 1) + edits.toMap()
    private fun population(count: Int) = (count - 1) / 99f
    private fun render(
        m: Map<String, Float> = macros(), midi: Int = 60, velocity: Float = .8f,
        seed: Int = 41001, mode: SplayMode = SplayMode.FULL,
    ) = Splay.renderDetailed(voice, m, midi = midi, velocity = velocity, seed = seed, model = 1, mode = mode)

    private fun rms(samples: FloatArray, from: Double = 0.0, to: Double = samples.size.toDouble() / Dsp.RATE): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val end = (to * Dsp.RATE).toInt().coerceIn(start, samples.size)
        var sum = 0.0
        for (i in start until end) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (end - start).coerceAtLeast(1))
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray, from: Double = 0.0, to: Double = 6.0): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, minOf(a.size, b.size))
        val end = (to * Dsp.RATE).toInt().coerceIn(start, minOf(a.size, b.size))
        var difference = 0.0
        var energy = 0.0
        for (i in start until end) {
            val d = a[i].toDouble() - b[i]
            difference += d * d
            energy += a[i].toDouble() * a[i] + b[i].toDouble() * b[i]
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    @Test
    fun `saved controls reproduce samples regardless of macro insertion order`() {
        val m = macros("POPULATION" to population(4))
        val a = render(m)
        val b = render(m.entries.reversed().associate { it.key to it.value })
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.trace, b.trace)
        assertEquals(a.events, b.events)
        assertEquals(1, a.snip.channels)
        assertEquals(44_100, a.snip.sampleRate)
        assertTrue(a.raw.all { it.isFinite() })
        assertTrue(a.snip.samples.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(rms(a.raw) > 1e-5, "raw render is silent")
        assertTrue(abs(a.raw.average()) < rms(a.raw) * .02, "raw render retains substantial DC")
        assertEquals(setOf("TRACE", "SEED GAP", "ENERGY", "CONTACT", "POPULATION", "HOLD"), m.keys)
    }

    @Test
    fun `three keyboard registers retain the requested pitched root`() {
        for (midi in listOf(36, 60, 84)) {
            val p = render(macros("POPULATION" to 0f), midi = midi)
            val estimate = assertNotNull(Pitch.detect(p.snip, fromSec = .04f, windowSec = .20f), "MIDI $midi has no pitch")
            val cents = 1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15, "MIDI $midi root is $cents cents out")
            assertTrue(estimate.confidence > .45f, "MIDI $midi root confidence ${estimate.confidence}")
            assertTrue(rms(p.raw, 6.8, 7.8) < rms(p.raw, 5.0, 6.0) * .15,
                "MIDI $midi has not decayed before the terminal fade")
        }
    }

    @Test
    fun `zero gap remains acoustically coherent as population grows`() {
        val single = render(macros("SEED GAP" to 0f, "POPULATION" to 0f))
        val many = render(macros("SEED GAP" to 0f, "POPULATION" to population(16)))
        assertTrue(relativeDifference(single.raw, many.raw) < 1e-5,
            "identical histories gain artificial acoustic differences")
        assertTrue(relativeDifference(single.snip.samples, many.snip.samples) < 1e-5)
    }

    @Test
    fun `all five behavior macros change a six second divergent gesture`() {
        val baseline = render()
        for ((name, value) in listOf("TRACE" to 1f, "SEED GAP" to 1f, "ENERGY" to 1f, "CONTACT" to 1f, "POPULATION" to 0f)) {
            val changed = render(macros(name to value))
            assertTrue(relativeDifference(baseline.raw, changed.raw, .25, 6.0) > .005,
                "$name has no measurable authority in the finite gesture")
        }
        val frozen = render(mode = SplayMode.FROZEN)
        assertTrue(relativeDifference(baseline.raw, frozen.raw, .25, 6.0) > .01,
            "freezing the motion leaves the same excitation")
    }

    @Test
    fun `acoustic seed and keyboard edits preserve the mechanical history`() {
        val m = macros("POPULATION" to population(4))
        val original = render(m)
        val reseeded = render(m, seed = 41002)
        val transposed = render(m, midi = 48)
        val soft = render(m, velocity = .20f)
        assertEquals(original.trace, reseeded.trace, "the acoustic seed changed mechanical histories")
        assertEquals(original.trace, transposed.trace, "the note changed mechanical histories")
        assertEquals(original.trace, soft.trace, "acoustic playing effort changed mechanical histories")
        assertEquals(original.events, reseeded.events)
        assertEquals(original.events, transposed.events)
        assertEquals(original.events, soft.events)
        assertTrue(relativeDifference(original.raw, reseeded.raw) > 1e-4,
            "the saved acoustic seed has no effect")
        assertTrue(rms(original.raw) > rms(soft.raw) * 1.3, "velocity did not increase raw excitation effort")
        val silent = render(m, velocity = 0f)
        assertTrue(silent.raw.all { it == 0f })
        assertTrue(silent.snip.samples.all { it == 0f })
    }

    @Test
    fun `adding participants preserves existing contacts and crossings are geometric`() {
        val four = render(macros("POPULATION" to population(4), "SEED GAP" to .7f))
        val sixteen = render(macros("POPULATION" to population(16), "SEED GAP" to .7f))
        assertTrue(four.events.isNotEmpty(), "the active trajectory produced no contacts")
        assertEquals(four.events, sixteen.events.filter { it.participantId < 4 },
            "admitting participants changed established contact histories")
        for (event in sixteen.events) {
            assertTrue(event.zoneErrorRadians.isFinite() && abs(event.zoneErrorRadians) < .002,
                "event is not at a contact-zone crossing: $event")
            assertTrue(event.seconds in 0.0..6.0)
            assertTrue(event.strength.isFinite() && event.strength >= 0.0)
            assertTrue(event.direction == -1 || event.direction == 1)
        }
    }

    @Test
    fun `summed modal forces match the explicit participant resonator reference`() {
        val m = macros("POPULATION" to population(4), "CONTACT" to .8f, "SEED GAP" to .8f)
        val efficient = render(m)
        val reference = Splay.renderDetailed(voice, m, midi = 60, velocity = .8f, seed = 41001, model = 1,
            referenceBank = true)
        assertEquals(efficient.trace, reference.trace)
        assertEquals(efficient.events, reference.events)
        assertTrue(relativeDifference(efficient.raw, reference.raw) < 1e-5,
            "force summation changed independently articulated resonators")
    }

    @Test
    fun `finite prototype rejects unsupported sustain and invalid inputs`() {
        assertFailsWith<IllegalArgumentException> { render(macros("HOLD" to .001f)) }
        assertFailsWith<IllegalArgumentException> { render(macros("TRACE" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { render(macros("TRACE" to Float.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { render(macros("MISSING" to .5f)) }
        assertFailsWith<IllegalArgumentException> { render(midi = 35) }
        assertFailsWith<IllegalArgumentException> { render(midi = 85) }
        assertFailsWith<IllegalArgumentException> { render(velocity = -.1f) }
        assertFailsWith<IllegalArgumentException> { render(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Splay.render(voice, model = 3) }
    }
}
