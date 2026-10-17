package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Finite dry-voice contracts; listening acceptance remains separate from these causal checks. */
class OhmTest {
    private fun rms(x: FloatArray, from: Int = 0, until: Int = x.size): Double {
        var energy = 0.0
        for (i in from until until) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    private fun distance(a: FloatArray, b: FloatArray, from: Int = 0, until: Int = minOf(a.size, b.size)): Double {
        var change = 0.0
        var energy = 0.0
        for (i in from until until) {
            val d = a[i].toDouble() - b[i]
            change += d * d
            energy += a[i].toDouble() * a[i]
        }
        return sqrt(change / energy.coerceAtLeast(1e-30))
    }

    @Test
    fun `recipes are deterministic and the explicit seed changes excitation`() {
        val macros = Ohm.defaults() + ("LENGTH" to .2f)
        val a = Ohm.renderRaw(macros, seed = 731).samples
        val reversed = macros.entries.reversed().associate { it.key to it.value }
        val b = Ohm.renderRaw(reversed, seed = 731).samples
        assertContentEquals(a, b)
        assertTrue(distance(a, Ohm.renderRaw(macros, seed = 732).samples) > .1, "seed did not alter the instrument")
    }

    @Test
    fun `meddling zero is exactly the same solo for every commune value and gesture`() {
        val solo = Ohm.defaults() + mapOf("LENGTH" to .15f, "MEDDLING" to 0f, "COMMUNE" to 0f)
        val expected = Ohm.renderRaw(solo, seed = 17).samples
        for (gesture in OhmGesture.entries) {
            val actual = Ohm.renderRaw(solo + ("COMMUNE" to 1f), seed = 17, gesture = gesture).samples
            assertContentEquals(expected, actual, "$gesture changed an absent relationship")
        }
    }

    @Test
    fun `zero velocity is silence and level matching preserves playing dynamics`() {
        val macros = Ohm.defaults() + ("LENGTH" to .2f)
        for (gesture in OhmGesture.entries) {
            assertTrue(Ohm.renderRaw(macros, velocity = 0f, gesture = gesture).samples.all { it == 0f })
            assertTrue(Ohm.render(macros, velocity = 0f, gesture = gesture).samples.all { it == 0f })
        }
        val soft = Ohm.render(macros, velocity = .3f)
        val loud = Ohm.render(macros, velocity = 1f)
        assertTrue(Loudness.of(soft) < .6f * Loudness.of(loud), "level matching erased velocity")
    }

    @Test
    fun `warm foreground retains the requested pitch across the initial supported register`() {
        for (midi in listOf(48, 60, 72)) {
            val snip = Ohm.renderRaw(Ohm.defaults() + mapOf("LENGTH" to .25f, "MEDDLING" to .65f, "COMMUNE" to .7f), midi, seed = 731)
            val pitch = Pitch.detect(snip, fromSec = .08f, windowSec = .28f)
            assertTrue(pitch != null, "MIDI $midi has no measurable foreground pitch")
            val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
            println("OHM MIDI $midi pitch error $cents cents, confidence ${pitch.confidence}")
            assertTrue(abs(cents) < 12.0, "MIDI $midi is $cents cents from the requested note")
            assertTrue(pitch.confidence > .55f, "MIDI $midi lost a clear foreground")
        }
    }

    @Test
    fun `all five controls remain useful after matching the whole instrument`() {
        val base = Ohm.defaults() + mapOf("LENGTH" to .35f, "MEDDLING" to .75f, "COMMUNE" to .5f)
        for (macro in listOf("TOUCH", "BODY", "MEDDLING", "COMMUNE")) {
            val low = Ohm.render(base + (macro to 0f), seed = 731).samples
            val high = Ohm.render(base + (macro to 1f), seed = 731).samples
            val difference = distance(low, high)
            println("OHM matched $macro difference $difference")
            assertTrue(difference > .04, "$macro disappeared after matching: $difference")
        }
        // Companion gestures use fractions of the complete file. Isolate the string and compare
        // the same absolute-time window, so a later breath cannot impersonate a longer decay.
        val solo = base + ("MEDDLING" to 0f)
        val short = Ohm.renderRaw(solo + ("LENGTH" to 0f)).samples
        val long = Ohm.renderRaw(solo + ("LENGTH" to 1f)).samples
        assertTrue(long.size > 3 * short.size, "LENGTH did not produce a longer finite instrument")
        val from = Dsp.RATE / 2
        val until = 3 * Dsp.RATE / 4
        assertTrue(rms(long, from, until) > 3 * rms(short, from, until), "LENGTH did not extend the solo string's natural decay")
    }

    @Test
    fun `companion excitation changes the foreground through commune and cannot do so when uncoupled`() {
        val base = Ohm.defaults() + mapOf("LENGTH" to .25f, "MEDDLING" to 1f)
        val isolated = Ohm.probe(base + ("COMMUNE" to 0f), seed = 731)
        val isolatedMuted = Ohm.probe(base + ("COMMUNE" to 0f), seed = 731, companionExcitation = false)
        assertContentEquals(isolated.foreground, isolatedMuted.foreground, "independent companions leaked into the foreground tap")
        assertTrue(distance(isolated.snip.samples, isolatedMuted.snip.samples) > .05, "companions were never audible")

        val coupled = Ohm.probe(base + ("COMMUNE" to 1f), seed = 731)
        val coupledMuted = Ohm.probe(base + ("COMMUNE" to 1f), seed = 731, companionExcitation = false)
        val foregroundChange = distance(coupled.foreground, coupledMuted.foreground, Dsp.RATE / 3)
        println("OHM foreground response to companion excitation $foregroundChange")
        assertTrue(foregroundChange > .005, "COMMUNE is only an output mix: foreground response $foregroundChange")
    }

    @Test
    fun `gather begins solo and its late foreground hears the arriving companions`() {
        val macros = Ohm.defaults() + mapOf("LENGTH" to .5f, "MEDDLING" to 1f, "COMMUNE" to 1f)
        val gather = Ohm.probe(macros, seed = 731, gesture = OhmGesture.GATHER)
        val muted = Ohm.probe(macros, seed = 731, gesture = OhmGesture.GATHER, companionExcitation = false)
        val solo = Ohm.renderRaw(macros + ("MEDDLING" to 0f), seed = 731)
        val intro = (gather.snip.samples.size * .08).toInt()
        assertContentEquals(solo.samples.copyOf(intro), gather.snip.samples.copyOf(intro), "GATHER's solo introduction is not solo")
        val late = (gather.snip.samples.size * .6).toInt()
        assertTrue(distance(gather.foreground, muted.foreground, late) > .01, "late GATHER merely mixed companions over an unaffected string")
    }

    @Test
    fun `extreme finite gestures retain raw headroom and decay before the endpoint safeguard`() {
        for (midi in listOf(48, 72)) for (gesture in OhmGesture.entries) {
            val allHigh = Ohm.MACROS.associate { it.name to 1f }
            val result = Ohm.probe(allHigh, midi, seed = -73, gesture = gesture, recordPorts = false)
            val x = result.snip.samples
            val peak = x.maxOf { abs(it) }
            println("OHM $midi $gesture raw peak $peak, max state ${result.maxState}, tail ${rms(x, x.size - Dsp.RATE / 3)}")
            assertEquals(1, result.snip.channels)
            assertEquals(44_100, result.snip.sampleRate)
            assertTrue(x.size in (1.8 * Dsp.RATE).toInt()..6 * Dsp.RATE)
            assertTrue(x.all { it.isFinite() })
            assertTrue(peak in .001f.. .8f, "raw reference gain lost headroom or fell silent: $peak")
            assertTrue(result.maxState < .9f, "waveguide core lost measured headroom: ${result.maxState}")
            assertTrue(abs(x.average()) < .003, "DC accumulated")
            // This window starts well before the 120 ms endpoint fade: the fade cannot hide a cut-off tail.
            val beforeFade = rms(x, x.size - Dsp.RATE / 3, x.size - Dsp.RATE / 6)
            assertTrue(beforeFade < .02 * rms(x, 0, Dsp.RATE / 4), "tail was still loud before the endpoint safeguard")
            assertTrue(x.last() == 0f, "finite note did not end at zero")
        }
    }

    @Test
    fun `invalid register velocity and nonfinite controls fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(midi = 47) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(midi = 73) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(velocity = -1f) }
        assertFailsWith<IllegalArgumentException> { Ohm.renderRaw(mapOf("COMMUNE" to Float.POSITIVE_INFINITY)) }
    }
}
