package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Raw, gain-independent gates; the audition is the sonic approval gate. */
class OvenTest {
    private fun rms(a: FloatArray, start: Double = 0.0, end: Double = a.size.toDouble() / Dsp.RATE): Double {
        val lo = (start * Dsp.RATE).roundToInt().coerceIn(0, a.size)
        val hi = (end * Dsp.RATE).roundToInt().coerceIn(lo, a.size)
        var sum = 0.0
        for (i in lo until hi) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (hi - lo).coerceAtLeast(1))
    }

    private fun centroid(a: FloatArray, start: Double = .008, seconds: Double = .10): Double {
        val n = 16384
        val re = FloatArray(n)
        val im = FloatArray(n)
        val offset = (start * Dsp.RATE).roundToInt()
        val count = minOf((seconds * Dsp.RATE).roundToInt(), a.size - offset)
        for (i in 0 until count) re[i] = a[offset + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        var total = 0.0
        var weighted = 0.0
        for (i in 1 until n / 2) {
            val power = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            total += power
            weighted += power * i * Dsp.RATE / n
        }
        return weighted / total.coerceAtLeast(1e-30)
    }

    @Test fun `renders reproduce inputs independently of macro insertion order`() {
        for (voice in OvenVoice.entries) {
            val m = Oven.defaults(voice)
            val a = Oven.probe(voice, m, seconds = .30f)
            val b = Oven.probe(voice, m.entries.reversed().associate { it.key to it.value }, seconds = .30f)
            assertContentEquals(a.samples, b.samples)
            assertTrue(rms(a.samples) > .001, "$voice is silent")
            assertEquals(setOf("TUNE", "HAMMER", "BODY", "RING", "BLOOM", "HALO"), m.keys)
        }
    }

    @Test fun `stiffness preserves the played root and explicit MIDI uses a neutral offset`() {
        for (b in listOf(0.0, .000035, .00016, .00065)) {
            assertEquals(1.0, Oven.ratioFor(1, b), 1e-12)
            assertTrue(Oven.ratioFor(12, b) >= 12.0)
        }
        assertEquals(60, Oven.midiFor(OvenVoice.GRAND, .5f))
        assertEquals(48, Oven.midiFor(OvenVoice.GRAND, 0f))
        assertEquals(72, Oven.midiFor(OvenVoice.GRAND, 1f))
        assertEquals(36, Oven.midiFor(OvenVoice.GRAND, .5f, 36))
        val p = Oven.probe(OvenVoice.GRAND, midi = 60, seconds = .30f)
        assertEquals(Keys.midiHz(60).toDouble(), p.rootHz, 1e-9)
        // The strongest late-note sinusoid remains close to the requested root, not a body mode.
        val re = FloatArray(65536)
        val im = FloatArray(re.size)
        val first = (Dsp.RATE * .12).roundToInt()
        val count = (Dsp.RATE * .16).roundToInt()
        for (i in 0 until count) re[i] = p.samples[first + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val strongest = (1 until 5000).maxBy { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        assertTrue(abs(strongest * Dsp.RATE.toDouble() / re.size - p.rootHz) < 2.0)
    }

    @Test fun `raw states stay finite and bounded across registers and corners`() {
        for (midi in listOf(24, 48, 72, 96)) for (value in listOf(0f, 1f)) {
            val m = Oven.defaults(OvenVoice.GRAND).mapValues { if (it.key == "TUNE") .5f else value }
            val p = Oven.probe(OvenVoice.GRAND, m, midi, seconds = .25f)
            assertTrue(p.samples.all { it.isFinite() })
            assertTrue(p.rawPeak in .001..1.0, "MIDI $midi corner $value peak ${p.rawPeak}")
            assertTrue(p.partials in 3..48)
        }
        val silence = Oven.render(OvenVoice.GRAND, velocity = 0f)
        assertTrue(silence.samples.all { it == 0f })
        assertEquals(1, silence.channels)
        assertEquals(44100, silence.sampleRate)
    }

    @Test fun `velocity changes contact energy and brightness before output leveling`() {
        val soft = Oven.probe(OvenVoice.GRAND, velocity = .20f, seconds = .30f)
        val hard = Oven.probe(OvenVoice.GRAND, velocity = 1f, seconds = .30f)
        assertTrue(soft.contactSeconds > hard.contactSeconds)
        assertTrue(rms(hard.samples) > 1.8 * rms(soft.samples))
        assertTrue(centroid(hard.samples) > 1.20 * centroid(soft.samples),
            "velocity centroids ${centroid(soft.samples)} -> ${centroid(hard.samples)}")
    }

    @Test fun `hammer and body change dry spectral character while bloom changes arrival`() {
        val low = Oven.probe(OvenVoice.GRAND, mapOf("HAMMER" to 0f), seconds = .40f)
        val high = Oven.probe(OvenVoice.GRAND, mapOf("HAMMER" to 1f), seconds = .40f)
        assertTrue(centroid(high.samples) > centroid(low.samples) * 1.15,
            "HAMMER centroids ${centroid(low.samples)} -> ${centroid(high.samples)}")
        val light = Oven.probe(OvenVoice.FORGED, mapOf("BODY" to 0f), seconds = .40f)
        val dark = Oven.probe(OvenVoice.FORGED, mapOf("BODY" to 1f), seconds = .40f)
        assertTrue(centroid(light.samples) > centroid(dark.samples) * 1.10,
            "BODY light/dark centroids ${centroid(light.samples)} -> ${centroid(dark.samples)}")
        val immediate = Oven.probe(OvenVoice.GRAND, bloomOverride = 0f, seconds = .60f)
        val blooming = Oven.probe(OvenVoice.GRAND, bloomOverride = 1f, seconds = .60f)
        assertTrue(rms(blooming.samples, 0.0, .03) < .12 * rms(immediate.samples, 0.0, .03))
        assertTrue(rms(blooming.samples, .20, .35) > rms(blooming.samples, 0.0, .03) * 3)
    }

    @Test fun `ring retains a natural tail and halo remains a causal per-note layer`() {
        val short = Oven.probe(OvenVoice.GRAND, mapOf("RING" to 0f, "HALO" to 0f), seconds = 2f)
        val long = Oven.probe(OvenVoice.GRAND, mapOf("RING" to 1f, "HALO" to 0f), seconds = 2f)
        val shortFraction = rms(short.samples, 1.4, 1.8) / rms(short.samples, .02, .12)
        val longFraction = rms(long.samples, 1.4, 1.8) / rms(long.samples, .02, .12)
        assertTrue(longFraction > shortFraction * 10, "RING did not extend decay")
        val withHalo = Oven.probe(OvenVoice.GRAND, mapOf("HALO" to 1f), seconds = 1f)
        val withoutHalo = Oven.probe(OvenVoice.GRAND, mapOf("HALO" to 1f), haloEnabled = false, seconds = 1f)
        assertTrue(withHalo.diagnostics.haloPeak > .001)
        assertEquals(0.0, withoutHalo.diagnostics.haloPeak)
        assertTrue(withHalo.samples.indices.any { abs(withHalo.samples[it] - withoutHalo.samples[it]) > .001f })
    }

    @Test fun `cancelled renders stop and finite controls do not request loops`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Oven.render(OvenVoice.GRAND) }
        } finally { Thread.interrupted() }
        assertTrue(!Oven.isLoop(1f))
        assertFailsWith<IllegalArgumentException> { Oven.render(OvenVoice.GRAND, velocity = Float.NaN) }
    }

    @Test fun `a short struck ring still exports the enabled halos full natural tail`() {
        val macros = mapOf("RING" to 0f, "HALO" to 1f, "BLOOM" to 0f)
        val full = Oven.probe(OvenVoice.GRAND, macros)
        val d = full.diagnostics
        assertTrue(d.haloT60 > d.rootT60 * 5)
        assertTrue(d.seconds >= d.haloT60 + .29, "halo T60 ${d.haloT60} cut at ${d.seconds}")
        // This window ends before the protective terminal fade starts. The tail itself must
        // already be quiet; measuring the faded last samples would hide a prematurely cut halo.
        val beforeFade = rms(full.samples, d.seconds - .40, d.seconds - .15)
        val onset = rms(full.samples, .01, .20)
        assertTrue(beforeFade < onset * .01, "the natural halo was still loud before its end fade")
        val dry = Oven.probe(OvenVoice.GRAND, macros, haloEnabled = false)
        assertTrue(dry.diagnostics.seconds < d.seconds * .30, "disabled halo still reserves a long tail")
        val cut = Oven.probe(OvenVoice.GRAND, macros, seconds = .30f)
        assertEquals((.30 * Dsp.RATE).roundToInt(), cut.samples.size)
    }
}
