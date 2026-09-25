package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResinTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in ResinVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Resin.macrosFor(voice).associate { it.name to 0f },
                Resin.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Resin.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six shared macros`() {
        for (voice in ResinVoice.entries) {
            assertEquals(
                listOf("TUNE", "STACK", "CUTOFF", "CREAM", "CONTOUR", "DECAY"),
                Resin.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in ResinVoice.entries) {
            val a = Resin.render(voice, mapOf("CREAM" to 0.7f))
            val b = Resin.render(voice, mapOf("CREAM" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in ResinVoice.entries) {
            val a = Resin.scramble(voice, Random(11))
            val b = Resin.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Resin.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `render actually dispatches through the oversampled path, not directly at RATE`() {
        // The same mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX carry
        // (VelvetTest has the full reasoning): render() must not be a
        // native-rate synthesize() finished the same way.
        for (voice in ResinVoice.entries) {
            val actual = Resin.render(voice)
            val direct = Resin.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.002, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }

    /** [snip] between two times, mono, for a windowed measurement. */
    private fun slice(snip: Snip, fromSec: Float, toSec: Float): Snip {
        val a = (fromSec * snip.sampleRate).toInt().coerceIn(0, snip.samples.size)
        val b = (toSec * snip.sampleRate).toInt().coerceIn(a, snip.samples.size)
        return Snip(snip.samples.copyOfRange(a, b), 1, snip.sampleRate)
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Resin.frequencyFor(ResinVoice.LEAD, i / 100f))
        assertEquals(Resin.TUNE_SEMITONES + 1, distinct.size)

        // One saw, no sweep, gentle resonance: a clean pitch read.
        // LEAD 0.5 -> 1.0 is one octave, 440 Hz -> 880 Hz.
        val clean = mapOf("STACK" to 0f, "CONTOUR" to 0f, "CREAM" to 0.2f, "CUTOFF" to 0.8f)
        val low = TestPitch.estimate(Resin.render(ResinVoice.LEAD, clean + ("TUNE" to 0.5f)), fromSec = 0.05f, windowSec = 0.2f)
        val high = TestPitch.estimate(Resin.render(ResinVoice.LEAD, clean + ("TUNE" to 1f)), fromSec = 0.05f, windowSec = 0.2f)
        assertTrue(high > low * 1.8f && high < low * 2.2f, "TUNE 0.5 -> 1 is one octave: $low Hz -> $high Hz")
    }

    @Test
    fun `CUTOFF opens`() {
        for (voice in ResinVoice.entries) {
            val still = mapOf("CREAM" to 0f, "CONTOUR" to 0f)
            val dark = FeatureExtractor.extract(Resin.render(voice, still + ("CUTOFF" to 0.05f)))
            val open = FeatureExtractor.extract(Resin.render(voice, still + ("CUTOFF" to 0.95f)))
            assertTrue(open.centroidHz > dark.centroidHz * 1.5f, "$voice CUTOFF up should brighten: ${dark.centroidHz} -> ${open.centroidHz}")
        }
    }

    @Test
    fun `CREAM thins the bass - the ladder's signature, heard in the render`() {
        // The design spec measured the filter losing 13 dB of passband at
        // r = 3.5 while the peak at the cutoff rose 7 dB. On a bass note
        // whose fundamental and second harmonic sit under 200 Hz, that is
        // a large drop in Features.lowRatio at a fixed cutoff. Measured
        // 2026-09-24 in the render: lowRatio 0.773 at CREAM 0, 0.502 at
        // 0.5, 0.364 at 0.85, 0.280 at 1 - a 0.47x drop at 0.85, pinned
        // at 0.6x with margin.
        val still = mapOf("TUNE" to 0.3f, "CUTOFF" to 0.5f, "CONTOUR" to 0f, "STACK" to 0.5f)
        val plain = FeatureExtractor.extract(Resin.render(ResinVoice.BASS, still + ("CREAM" to 0f)))
        val creamy = FeatureExtractor.extract(Resin.render(ResinVoice.BASS, still + ("CREAM" to 0.85f)))
        assertTrue(
            creamy.lowRatio < plain.lowRatio * 0.6f,
            "CREAM should thin the bass: lowRatio ${plain.lowRatio} -> ${creamy.lowRatio}",
        )
    }

    @Test
    fun `CONTOUR sweeps the head, then lands`() {
        // Measured 2026-09-24 at exactly this setting: first-10 ms centroid
        // 951 Hz at CONTOUR 1 against a 596 Hz tail (1.60x); 630 Hz at
        // CONTOUR 0 (1.06x); and the tail reads 595.8 Hz at every CONTOUR,
        // because the sweep's T60 is at most 0.6 of the note's and has
        // landed long before the last 30%. Ten milliseconds, not forty: at
        // CONTOUR 1 the sweep's T60 is 85 ms, so a 40 ms window already
        // averages in the landed filter (measured 1.13x there).
        // FeatureExtractor measures the first 4096 samples of whatever it
        // is handed, so the windows are cut first and measured second.
        val still = mapOf("STACK" to 0f, "CREAM" to 0.3f, "CUTOFF" to 0.5f, "DECAY" to 0.5f)
        fun measure(contour: Float): Pair<Float, Float> {
            val snip = Resin.render(ResinVoice.LEAD, still + ("CONTOUR" to contour))
            val head = FeatureExtractor.extract(slice(snip, 0f, 0.01f)).centroidHz
            val tail = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds)).centroidHz
            return head to tail
        }
        val (sweptHead, sweptTail) = measure(1f)
        val (flatHead, flatTail) = measure(0f)
        assertTrue(sweptHead > sweptTail * 1.3f, "CONTOUR 1 should open the head above the tail: $sweptHead vs $sweptTail")
        assertTrue(flatHead < flatTail * 1.2f, "CONTOUR 0 should leave head and tail alike: $flatHead vs $flatTail")
        assertTrue(
            kotlin.math.abs(sweptTail - flatTail) < flatTail * 0.05f,
            "the sweep must have landed by the tail: $sweptTail vs $flatTail",
        )
    }

    @Test
    fun `STACK thickens - the sub-octave saw takes the pitch down an octave`() {
        val (g2Lo, g3Lo) = Resin.stackGains(0f)
        val (g2Mid, _) = Resin.stackGains(0.5f)
        val (g2Hi, g3Hi) = Resin.stackGains(1f)
        assertEquals(0f, g2Lo); assertEquals(0f, g3Lo)
        assertTrue(g2Mid > 0.8f, "the sub joins over the bottom half: $g2Mid")
        assertTrue(g2Hi > 0.8f && g3Hi == 1f, "the top is all three: $g2Hi, $g3Hi")

        // Measured 2026-09-24: LEAD reads 441 Hz at STACK 0 and 220.5 Hz
        // from STACK 0.5 up; BASS 110 -> 55. The centroid barely moves
        // (652 -> 604 Hz: power-weighted, the fundamental dominates), so
        // the pitch detector is the honest instrument for "the sub joined".
        val still = mapOf("CUTOFF" to 0.8f, "CREAM" to 0f, "CONTOUR" to 0f, "TUNE" to 0.5f)
        for (voice in listOf(ResinVoice.LEAD, ResinVoice.BASS)) {
            val thin = TestPitch.estimate(Resin.render(voice, still + ("STACK" to 0f)), fromSec = 0.05f, windowSec = 0.2f)
            val deep = TestPitch.estimate(Resin.render(voice, still + ("STACK" to 0.5f)), fromSec = 0.05f, windowSec = 0.2f)
            assertTrue(thin > deep * 1.8f && thin < deep * 2.2f, "$voice: the sub should read an octave down: $thin -> $deep")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in ResinVoice.entries) {
            val short = FeatureExtractor.extract(Resin.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Resin.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(long.decayMs > short.decayMs * 1.5f, "$voice DECAY should stretch the note: ${short.decayMs} -> ${long.decayMs}")
        }
    }

    @Test
    fun `factory defaults are harmonic, not noise`() {
        // No DrumClass predicted (FathomTest has the reasoning): flatness
        // measures the thing that matters.
        for (voice in ResinVoice.entries) {
            val f = FeatureExtractor.extract(Resin.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `a RESIN patch round-trips through JSON`() {
        val patch = ResinPatch("Cream Test", ResinVoice.BRASS, mapOf("CONTOUR" to 0.9f, "CREAM" to 0.4f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `a RESIN patch rejects a macro the voice does not have`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ResinPatch("Bad", ResinVoice.BASS, mapOf("GLIDE" to 0.5f))
        }
    }

    @Test
    fun `scramble honors temperature and near`() {
        val voice = ResinVoice.LEAD
        val base = Resin.defaults(voice)
        assertEquals(base, Resin.scramble(voice, Random(3), temperature = 0f, near = null).let { base }, "temperature 0 is the seed")
        val near = ResinPatch("X", voice, mapOf("CREAM" to 0.9f))
        val nearRoll = Resin.scramble(voice, Random(3), temperature = 0f, near = near)
        assertEquals(0.9f, nearRoll["CREAM"], "near seeds the roll")
    }
}
