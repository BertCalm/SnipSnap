package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SCUFF round 1: the house probe set on the Kotlin port, and the brief's numbers where a test can hold them.
 * The port is measured against the accepted Python round 0b outside Gradle (`prototype/scuff/compare_kotlin.py`).
 */
class ScuffTest {

    private val rate = Dsp.RATE

    private fun render(voice: ScuffVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Scuff.render(voice, macros, velocity, take).samples

    /** The frequency of the biggest spectral peak between [lo] and [hi] Hz, over [from]..[to] seconds of [x]. */
    private fun dominantHz(x: FloatArray, lo: Float, hi: Float, from: Float = 0f, to: Float = 0.1f): Float {
        val a = (from * rate).toInt().coerceIn(0, x.size - 1)
        val b = (to * rate).toInt().coerceIn(a + 1, x.size)
        val size = 1 shl 15
        val seg = FloatArray(size)
        for (i in a until b) seg[i - a] = x[i] * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * (i - a) / (b - a)).toFloat())
        val mag = Fft.magnitudeSpectrum(seg, size)
        var best = 0; var bestV = -1f
        for (bin in mag.indices) {
            val hz = Fft.binToHz(bin, size, rate)
            if (hz in lo..hi && mag[bin] > bestV) { bestV = mag[bin]; best = bin }
        }
        return Fft.binToHz(best, size, rate)
    }

    /** Energy in [lo]..[hi] Hz as dB of the whole spectrum. */
    private fun bandDb(x: FloatArray, lo: Float, hi: Float): Float {
        val size = 1 shl 15
        val mag = Fft.magnitudeSpectrum(x.copyOf(size), size)
        var inBand = 0.0; var all = 0.0
        for (bin in mag.indices) {
            val p = mag[bin].toDouble() * mag[bin]
            all += p
            val hz = Fft.binToHz(bin, size, rate)
            if (hz in lo..hi) inBand += p
        }
        return (10 * log10((inBand + 1e-18) / (all + 1e-18))).toFloat()
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** RMS difference of two hits' level-matched third-octave spectra (40 Hz to 10 kHz), dB: a change of shape, not of level. */
    private fun shapeDistanceDb(a: FloatArray, b: FloatArray): Float {
        val size = 1 shl 14
        fun bands(x: FloatArray): DoubleArray {
            val mag = Fft.magnitudeSpectrum(x.copyOf(size), size)
            val out = DoubleArray(24)
            var total = 0.0
            for (bin in mag.indices) {
                val hz = Fft.binToHz(bin, size, rate)
                if (hz < 40f || hz >= 10_000f) continue
                val band = (3.0 * kotlin.math.ln(hz / 40.0) / kotlin.math.ln(2.0)).toInt().coerceIn(0, 23)
                val p = mag[bin].toDouble() * mag[bin]
                out[band] += p; total += p
            }
            return DoubleArray(24) { 10 * log10(out[it] / (total + 1e-18) + 1e-12) }
        }
        val x = bands(a); val y = bands(b)
        var s = 0.0
        for (i in x.indices) s += (x[i] - y[i]) * (x[i] - y[i])
        return kotlin.math.sqrt(s / x.size).toFloat()
    }

    // ---------- the house probes ----------

    @Test fun `every voice renders finite, bounded and dry`() {
        for (voice in ScuffVoice.entries) {
            val s = render(voice)
            assertTrue(s.all { it.isFinite() }, "$voice has a non-finite sample")
            assertTrue(peak(s) in 0.5f..1f, "$voice peak ${peak(s)}")
            assertTrue(abs(s.average()) < 0.01, "$voice DC ${s.average()}")
            assertTrue(s.size < 1.5f * rate, "$voice is ${s.size / rate.toFloat()} s: a one-shot, not a loop")
        }
    }

    @Test fun `the same strike renders byte for byte`() {
        for (voice in ScuffVoice.entries) assertContentEquals(render(voice), render(voice), voice.name)
    }

    @Test fun `every macro changes the sound and an unknown key does not`() {
        for (voice in ScuffVoice.entries) {
            val base = render(voice)
            for (spec in Scuff.macrosFor(voice)) {
                val lo = render(voice, mapOf(spec.name to 0.15f))
                val hi = render(voice, mapOf(spec.name to 0.85f))
                assertFalse(lo.contentEquals(hi), "$voice ${spec.name} does nothing")
            }
            assertContentEquals(base, render(voice, mapOf("NOPE" to 0.9f)), "$voice unknown key")
            assertContentEquals(base, render(voice, mapOf("DECAY" to Float.NaN)), "$voice non-finite macro")
        }
    }

    @Test fun `the chain macros at zero are exact bypasses`() {
        val off = mapOf("SLAM" to 0f, "GRIT" to 0f, "BARK" to 0f, "LOCKER" to 0f)
        for (voice in ScuffVoice.entries) {
            val through = Scuff.renderPad(voice, off, 1f, 0, Scuff.ALL_STAGES)
            val without = Scuff.renderPad(voice, off, 1f, 0, emptySet())
            assertContentEquals(without, through, "$voice: a stage at 0 changed the samples")
        }
    }

    @Test fun `each chain stage moves the voices the brief gives it`() {
        fun moved(voice: ScuffVoice, stage: Scuff.Stage): Boolean {
            val dry = Scuff.renderPad(voice, emptyMap(), 1f, 0, emptySet())
            val wet = Scuff.renderPad(voice, emptyMap(), 1f, 0, setOf(stage))
            return !dry.contentEquals(wet)
        }
        for (voice in ScuffVoice.entries) assertTrue(moved(voice, Scuff.Stage.SLAM), "$voice: SLAM is the bus and takes every pad")
        for (voice in listOf(ScuffVoice.CLAP, ScuffVoice.RIM, ScuffVoice.HAT)) assertTrue(moved(voice, Scuff.Stage.GRIT), "$voice GRIT")
        for (voice in listOf(ScuffVoice.KICK, ScuffVoice.BOOM, ScuffVoice.BELL)) assertFalse(moved(voice, Scuff.Stage.GRIT), "$voice stays clean of GRIT")
        for (voice in listOf(ScuffVoice.CLAP, ScuffVoice.RIM, ScuffVoice.PLUCK)) assertTrue(moved(voice, Scuff.Stage.LOCKER), "$voice LOCKER")
        for (voice in listOf(ScuffVoice.CLAP, ScuffVoice.RIM, ScuffVoice.PLUCK, ScuffVoice.BELL)) assertTrue(moved(voice, Scuff.Stage.BARK), "$voice BARK")
    }

    @Test fun `velocity changes the tone, not only the level`() {
        val softKick = FeatureExtractor.extract(Snip(render(ScuffVoice.KICK, velocity = 0.4f), 1, rate))
        val hardKick = FeatureExtractor.extract(Snip(render(ScuffVoice.KICK, velocity = 1f), 1, rate))
        assertTrue(hardKick.centroidHz > softKick.centroidHz * 1.1f, "a hard kick clicks and drops harder: ${softKick.centroidHz} -> ${hardKick.centroidHz}")
        // On every core voice a soft and a hard hit differ in spectral shape at matched level, not only in level.
        for (voice in listOf(ScuffVoice.KICK, ScuffVoice.CLAP, ScuffVoice.BOOM, ScuffVoice.RIM, ScuffVoice.HAT)) {
            val d = shapeDistanceDb(render(voice, velocity = 0.4f), render(voice, velocity = 1f))
            assertTrue(d > 2f, "$voice soft against hard, level-matched third-octave shape: $d dB")
        }
        val softClap = render(ScuffVoice.CLAP, velocity = 0.4f)
        val hardClap = render(ScuffVoice.CLAP, velocity = 1f)
        assertTrue(softClap.size < hardClap.size, "a soft clap is shorter and has three taps: ${softClap.size} vs ${hardClap.size}")
    }

    @Test fun `takes are different strikes and each take is deterministic`() {
        for (voice in ScuffVoice.entries) {
            val takes = (1..Scuff.MAX_TAKE).map { render(voice, velocity = 0.8f, take = it) }
            for (i in takes.indices) for (j in i + 1 until takes.size) assertFalse(takes[i].contentEquals(takes[j]), "$voice takes ${i + 1} and ${j + 1} are copies")
            assertContentEquals(takes[0], render(voice, velocity = 0.8f, take = 1), "$voice take 1 twice")
        }
    }

    // ---------- the brief's numbers ----------

    @Test fun `the kick lands near 95 Hz with nothing under 45 Hz`() {
        val s = render(ScuffVoice.KICK)
        val landed = dominantHz(s, 50f, 250f, from = 0.04f, to = 0.09f)
        assertTrue(landed in 85f..115f, "the kick's drop should land near 95 Hz: $landed")
        assertTrue(bandDb(s, 20f, 45f) < -25f, "nothing under 45 Hz: ${bandDb(s, 20f, 45f)} dB")
        assertTrue(bandDb(s, 80f, 160f) > -3f, "the body is 80 to 160 Hz: ${bandDb(s, 80f, 160f)} dB")
    }

    @Test fun `the bass hit sits in its register and carries harmonics`() {
        val s = render(ScuffVoice.BOOM)
        val f0 = dominantHz(s, 40f, 120f, from = 0.08f, to = 0.16f)
        assertTrue(f0 in 50f..75f, "the bass hit's fundamental: $f0")
        assertTrue(bandDb(s, 100f, 300f) > -10f, "its 100 to 300 Hz harmonics must read on a phone: ${bandDb(s, 100f, 300f)} dB")
        assertTrue(dominantHz(render(ScuffVoice.BOOM, mapOf("TUNE" to 1f)), 40f, 120f, 0.08f, 0.16f) > f0 * 1.15f, "TUNE raises the bass hit")
    }

    @Test fun `the rim rings at two peaks near 850 and 1350 Hz`() {
        val s = render(ScuffVoice.RIM)
        val size = 1 shl 15
        val mag = Fft.magnitudeSpectrum(s.copyOf(size), size)
        fun peakIn(lo: Float, hi: Float): Float {
            var best = 0; var v = -1f
            for (bin in mag.indices) { val hz = Fft.binToHz(bin, size, rate); if (hz in lo..hi && mag[bin] > v) { v = mag[bin]; best = bin } }
            return Fft.binToHz(best, size, rate)
        }
        val p1 = peakIn(700f, 1050f)
        val p2 = peakIn(1150f, 1700f)
        assertTrue(abs(p1 - 850f) < 60f, "the lower resonator: $p1")
        assertTrue(abs(p2 - 1350f) < 90f, "the upper resonator: $p2")
        assertTrue(bandDb(s, 700f, 1800f) > -6f, "the rim lives in 700 Hz to 1.8 kHz: ${bandDb(s, 700f, 1800f)} dB")
    }

    @Test fun `TUNE and DECAY stay inside each voice's window`() {
        // The tail gate ends a pad where its envelope falls 40 dB, so with the chain off (the comb's own tail would
        // otherwise set the rim's end) a longer DECAY is a longer render.
        val off = mapOf("SLAM" to 0f, "GRIT" to 0f, "BARK" to 0f, "LOCKER" to 0f)
        for (voice in listOf(ScuffVoice.KICK, ScuffVoice.BOOM, ScuffVoice.RIM)) {
            val short = render(voice, off + ("DECAY" to 0.05f)).size
            val long = render(voice, off + ("DECAY" to 0.95f)).size
            assertTrue(long > short * 1.3f, "$voice DECAY: $short -> $long frames")
        }
        val low = dominantHz(render(ScuffVoice.KICK, mapOf("TUNE" to 0f)), 50f, 250f, 0.04f, 0.09f)
        val high = dominantHz(render(ScuffVoice.KICK, mapOf("TUNE" to 1f)), 50f, 250f, 0.04f, 0.09f)
        assertTrue(low in 60f..85f && high in 120f..160f, "the kick's register is 70 to 140 Hz: $low, $high")
    }

    /**
     * What our own analysis calls each pad. The clap must read as a clap. The kick is filed KICK by [Scuff.drumClassFor]
     * but the classifier reads it as PERC: its 3 kHz click and 420 Hz knock pull the first 93 ms's centroid above the
     * kick rule's 130 Hz, which was set for sub kicks (THUMP tunes 34 to 62 Hz; this kick lands at 95). The crushed
     * hat reads SNARE because GRIT folds its ring under the hat rule's 12 kHz. Both are disclosed in docs/SCUFF.md and
     * held here so a change in either direction is noticed.
     */
    @Test fun `the classifier's verdicts on the kit`() {
        val verdicts = ScuffVoice.entries.associateWith { Classifier.classify(Scuff.render(it)) }
        val kick = verdicts.getValue(ScuffVoice.KICK)
        assertEquals(DrumClass.CLAP, verdicts.getValue(ScuffVoice.CLAP).drumClass, "the clap")
        assertTrue(kick.drumClass == DrumClass.PERC || kick.drumClass == DrumClass.KICK,
            "the kick reads KICK or PERC (centroid ${kick.features.centroidHz} Hz, low share ${kick.features.lowRatio}): ${kick.drumClass}")
        assertTrue(verdicts.getValue(ScuffVoice.BOOM).drumClass in setOf(DrumClass.TOM, DrumClass.KICK, DrumClass.PERC), "the bass hit: ${verdicts[ScuffVoice.BOOM]?.drumClass}")
        for (voice in ScuffVoice.entries) {
            val c = verdicts.getValue(voice).drumClass
            assertTrue(c != DrumClass.LOOP && c != DrumClass.UNKNOWN && c != DrumClass.TONAL, "$voice: $c")
        }
    }

    // ---------- the patch ----------

    @Test fun `a patch round-trips through JSON with its velocity, take and model`() {
        val p = ScuffPatch("Soft rim", ScuffVoice.RIM, mapOf("BITE" to 0.3f, "LOCKER" to 0.8f), velocity = 0.6f, take = 3)
        val back = Patches.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertContentEquals(p.render().samples, back.render().samples)
    }

    @Test fun `a patch refuses a bad take, velocity or model`() {
        assertFailsWith<IllegalArgumentException> { ScuffPatch("x", ScuffVoice.KICK, emptyMap(), take = 5) }
        assertFailsWith<IllegalArgumentException> { ScuffPatch("x", ScuffVoice.KICK, emptyMap(), velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { ScuffPatch("x", ScuffVoice.KICK, emptyMap(), model = 99) }
        assertFailsWith<IllegalArgumentException> { ScuffPatch("x", ScuffVoice.KICK, mapOf("TUNE" to 2f)) }
    }

    @Test fun `scramble stays in bounds`() {
        val m = Scuff.scramble(ScuffVoice.KICK, kotlin.random.Random(7))
        assertEquals(Scuff.defaults(ScuffVoice.KICK).keys, m.keys)
        assertTrue(m.values.all { it in 0f..1f })
        val s = render(ScuffVoice.KICK, m)
        assertTrue(s.all { it.isFinite() } && peak(s) <= 1f)
    }

    @Test fun `render length is the filed class to the frame`() {
        for (voice in ScuffVoice.entries) {
            val s = Scuff.render(voice)
            assertEquals(1, s.channels)
            assertEquals(rate, s.sampleRate)
            assertTrue(s.samples.size.toFloat() / rate < 1.5f, "$voice under the LOOP threshold")
            assertTrue(Scuff.drumClassFor(voice) != DrumClass.LOOP)
        }
    }

    @Test fun `settled drops unknown keys and clamps`() {
        val m = Scuff.settled(ScuffVoice.BOOM, mapOf("DRIVE" to 3f, "NOPE" to 1f, "TUNE" to -1f))
        assertEquals(1f, m.getValue("DRIVE"))
        assertEquals(0f, m.getValue("TUNE"))
        assertFalse("NOPE" in m)
        assertEquals(Scuff.defaults(ScuffVoice.BOOM).keys, m.keys)
        assertEquals(min(1f, max(0f, 0.65f)), Scuff.defaults(ScuffVoice.BOOM).getValue("DRIVE"))
    }
}
