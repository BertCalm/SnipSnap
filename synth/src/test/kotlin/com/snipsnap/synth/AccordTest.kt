package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Native behavioral probes. Listening approval and phone latency remain separate evidence. */
class AccordTest {
    private val drumFamilies = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    private fun rms(x: FloatArray, from: Int = 0, end: Int = x.size): Double {
        var energy = 0.0
        for (i in from until end) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (end - from))
    }

    private fun distance(a: FloatArray, b: FloatArray, frames: Int = minOf(a.size, b.size)): Double {
        // Capture length follows actual mode loss, so note/velocity/RAFT edits
        // can change the tail. Compare one declared shared active interval.
        require(frames in 1..minOf(a.size, b.size))
        var energy = 0.0
        for (i in 0 until frames) {
            val d = a[i].toDouble() - b[i]
            energy += d * d
        }
        return sqrt(energy / frames)
    }

    /** Integrated Hann-windowed FFT energy; compares spectral balance independently of output gain. */
    private fun upperShare(x: FloatArray, hz: Double, seconds: Double): Double {
        val frames = minOf(x.size, (seconds * Dsp.RATE).toInt())
        val n = 8192
        var all = 0.0
        var high = 0.0
        var start = 0
        while (start + n <= frames) {
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (j in 0 until n) re[j] = (x[start + j] * (.5 - .5 * cos(2 * PI * j / (n - 1)))).toFloat()
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val energy = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                all += energy
                if (bin * Dsp.RATE.toDouble() / n > 1.7 * hz) high += energy
            }
            start += n / 2
        }
        return high / max(all, 1e-30)
    }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun cents(actual: Double, expected: Double) = 1200 * ln(actual / expected) / ln(2.0)

    @Test
    fun `the accepted roster retains five timbral controls with finite playback at default`() {
        assertEquals(listOf(AccordVoice.BRUSH, AccordVoice.WOOD, AccordVoice.TINE), AccordVoice.entries)
        for (voice in AccordVoice.entries) {
            val specs = Accord.macrosFor(voice)
            assertEquals(listOf("PACE", "COUPLING", "MISMATCH", "CONTACT", "RAFT", "HOLD"), specs.map { it.name })
            assertEquals(listOf(.5f, 1f, .25f, .5f, .5f, 0f), specs.map { it.default })
            assertEquals(if (voice == AccordVoice.TINE) AccordAcousticProfile.REFERENCE else AccordAcousticProfile.BASE,
                Accord.defaultProfile(voice))
        }
    }

    @Test
    fun `direct rendering settles convenience macros but never invents sustained playback`() {
        for (voice in AccordVoice.entries) {
            val baseline = Accord.render(voice, sourceSeconds = .75).samples
            assertContentEquals(baseline, Accord.render(voice,
                mapOf("PACE" to Float.NaN, "CONTACT" to Float.POSITIVE_INFINITY, "SPARKLE" to .9f),
                sourceSeconds = .75).samples)
            assertContentEquals(baseline, Accord.render(voice, mapOf("HOLD" to -.1f), sourceSeconds = .75).samples)
            assertFailsWith<IllegalArgumentException> {
                Accord.render(voice, mapOf("HOLD" to .001f), sourceSeconds = .75)
            }
            assertFailsWith<IllegalArgumentException> { Accord.render(voice, velocity = Float.NaN, sourceSeconds = .75) }
            assertFailsWith<IllegalArgumentException> { Accord.render(voice, midi = 35, sourceSeconds = .75) }
            assertFailsWith<IllegalArgumentException> { Accord.render(voice, sourceStartCycles = 8.01, sourceSeconds = .75) }
            assertFailsWith<IllegalArgumentException> { Accord.render(voice, model = 99, sourceSeconds = .75) }
            assertFailsWith<IllegalArgumentException> { Accord.render(voice, sourceSeconds = .25) }
        }
        assertContentEquals(Accord.render(AccordVoice.WOOD, mapOf("CONTACT" to 1f), sourceSeconds = .75).samples,
            Accord.render(AccordVoice.WOOD, mapOf("CONTACT" to 2f), sourceSeconds = .75).samples)
    }

    @Test
    fun `every register and voice remains finite with controlled peaks and DC`() {
        for (voice in AccordVoice.entries) for (midi in listOf(36, 48, 60, 72)) {
            val snip = Accord.render(voice, midi = midi, sourceSeconds = 2.0)
            val label = "$voice MIDI$midi"
            val configuration = AccordAcoustics.configuration(voice, Accord.defaultProfile(voice), midi, .75f)
            assertEquals(1, snip.channels)
            assertEquals(44_100, snip.sampleRate)
            assertEquals(Math.round((2.0 + configuration.releaseSeconds) * Dsp.RATE).toInt(), snip.frameCount,
                "$label length did not follow the resolved acoustic release")
            assertTrue(snip.samples.all { it.isFinite() }, "$label has nonfinite output")
            assertTrue(snip.peak() in .001f..0.94001f, "$label peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < .005, "$label has DC ${snip.samples.average()}")
            assertEquals(0f, snip.samples.first(), 1e-7f, "$label does not start from zero")
            assertEquals(0f, snip.samples.last(), 1e-7f, "$label does not end at zero")
            assertEquals(DrumClass.LOOP, Classifier.classify(snip).drumClass, "$label is a finite long performance")
        }
        for (profile in listOf(AccordAcousticProfile.SOFT_ATTACK,
            AccordAcousticProfile.BRIGHT_EDGE, AccordAcousticProfile.LONG_RING)) {
            val snip = Accord.render(AccordVoice.TINE, acousticProfile = profile, sourceSeconds = 2.0)
            val configuration = AccordAcoustics.configuration(AccordVoice.TINE, profile, 48, .75f)
            assertEquals(Math.round((2.0 + configuration.releaseSeconds) * Dsp.RATE).toInt(), snip.frameCount)
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(snip.peak() <= .94001f, "$profile peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < .005)
        }
    }

    @Test
    fun `cold warm cleared and caller-mutated recalls preserve deterministic owned audio`() {
        for (voice in AccordVoice.entries) {
            Accord.clearCaches()
            val cold = Accord.render(voice, sourceSeconds = .75).samples
            val before = Accord.cacheSnapshot()
            val warm = Accord.render(voice, sourceSeconds = .75).samples
            assertContentEquals(cold, warm, "$voice warm recall changed samples")
            assertTrue(Accord.cacheSnapshot().audioHits > before.audioHits, "$voice audio cache was not exercised")
            warm.fill(0f)
            assertContentEquals(cold, Accord.render(voice, sourceSeconds = .75).samples,
                "$voice exposed its retained audio array")
            Accord.clearCaches()
            assertContentEquals(cold, Accord.render(voice, sourceSeconds = .75).samples,
                "$voice cache removal changed the physical or acoustic result")
        }
    }

    @Test
    fun `note touch and contact edits reuse mechanics while reciprocal support changes prepare anew`() {
        Accord.clearCaches()
        Accord.render(AccordVoice.BRUSH, sourceSeconds = .75)
        val prepared = Accord.cacheSnapshot().mechanicalPreparations
        Accord.render(AccordVoice.BRUSH, midi = 60, sourceSeconds = .75)
        Accord.render(AccordVoice.BRUSH, velocity = .3f, sourceSeconds = .75)
        Accord.render(AccordVoice.TINE, acousticProfile = AccordAcousticProfile.BRIGHT_EDGE, sourceSeconds = .75)
        Accord.render(AccordVoice.BRUSH, mapOf("CONTACT" to .2f), sourceSeconds = .75)
        assertEquals(prepared, Accord.cacheSnapshot().mechanicalPreparations,
            "an acoustic edit rebuilt or reseeded the prepared pendulums")
        Accord.render(AccordVoice.BRUSH, mapOf("COUPLING" to 0f), sourceSeconds = .75)
        assertTrue(Accord.cacheSnapshot().mechanicalPreparations > prepared,
            "removing reciprocal coupling reused stale mechanics")
    }

    @Test
    fun `bounded audio and mechanical eviction cannot change a previously rendered performance`() {
        Accord.clearCaches()
        val original = Accord.render(AccordVoice.WOOD, sourceSeconds = 2.0).samples
        // Each probe owns conditioned and output arrays. Ten distinct two-
        // second sources exceed the declared eight-MiB retained-audio budget.
        for (i in 1..10) Accord.render(AccordVoice.WOOD,
            mapOf("CONTACT" to i / 23f), sourceSeconds = 2.0)
        val afterEdits = Accord.cacheSnapshot()
        assertTrue(afterEdits.audioBytes <= 8L * 1024 * 1024)
        val recalled = Accord.probe(AccordVoice.WOOD, sourceSeconds = 2.0)
        assertTrue(!recalled.diagnostics.audioCacheHit, "the eviction setup did not evict the original audio")
        assertContentEquals(original, recalled.samples.samples, "audio eviction changed the recalled performance")

        for (i in 1..7) Accord.render(AccordVoice.WOOD,
            mapOf("COUPLING" to i / 10f), sourceSeconds = .75)
        assertTrue(Accord.cacheSnapshot().mechanicalEntries <= 6)
        Accord.clearAudioCache()
        val physicalRecall = Accord.probe(AccordVoice.WOOD, sourceSeconds = 2.0)
        assertTrue(!physicalRecall.diagnostics.preparationCacheHit,
            "the eviction setup did not evict the original mechanics")
        assertContentEquals(original, physicalRecall.samples.samples, "mechanical eviction changed the recalled performance")
    }

    @Test
    fun `zero touch is exactly silent without preparing a trajectory`() {
        Accord.clearCaches()
        val before = Accord.cacheSnapshot().mechanicalPreparations
        for (voice in AccordVoice.entries) {
            val silent = Accord.render(voice, velocity = 0f, sourceSeconds = .75)
            val configuration = AccordAcoustics.configuration(voice, Accord.defaultProfile(voice), 48, 0f)
            assertTrue(silent.samples.all { it == 0f })
            assertEquals(Math.round((.75 + configuration.releaseSeconds) * Dsp.RATE).toInt(), silent.frameCount,
                "$voice silent output lost its declared release calculation")
        }
        assertEquals(before, Accord.cacheSnapshot().mechanicalPreparations)
    }

    @Test
    fun `acoustic edits preserve individual events and zero coupling leaves only the raft sound change`() {
        val original = Accord.probe(AccordVoice.BRUSH, sourceSeconds = 2.0)
        for (edited in listOf(
            Accord.probe(AccordVoice.WOOD, sourceSeconds = 2.0),
            Accord.probe(AccordVoice.TINE, acousticProfile = AccordAcousticProfile.LONG_RING, sourceSeconds = 2.0),
            Accord.probe(AccordVoice.BRUSH, midi = 72, velocity = .3f,
                macros = mapOf("CONTACT" to .1f), sourceSeconds = 2.0),
        )) assertEquals(original.events, edited.events, "an acoustic edit moved or reseeded contacts")

        val heavy = Accord.probe(AccordVoice.WOOD, mapOf("COUPLING" to 0f, "RAFT" to .1f), sourceSeconds = 2.0)
        val light = Accord.probe(AccordVoice.WOOD, mapOf("COUPLING" to 0f, "RAFT" to .9f), sourceSeconds = 2.0)
        assertEquals(heavy.events, light.events, "RAFT reconnected mechanisms at exactly zero coupling")
        assertTrue(distance(heavy.samples.samples, light.samples.samples, 2 * Dsp.RATE) /
            rms(heavy.samples.samples, 0, 2 * Dsp.RATE) > .01,
            "RAFT lost its acoustic body behavior when mechanics were independent")
    }

    @Test
    fun `one performance gain preserves velocity energy and hardness rather than leveling each touch`() {
        for (voice in AccordVoice.entries) for (midi in listOf(36, 48, 60, 72)) {
            val probes = listOf(.3f, .75f, 1f).map { velocity ->
                Accord.probe(voice, midi = midi, velocity = velocity, sourceSeconds = 2.0)
            }
            val renders = probes.map { it.samples }
            val gains = probes.map { it.diagnostics.calibrationGain }
            for (gain in gains) assertEquals(gains.first(), gain,
                "$voice MIDI$midi changed its scalar performance gain with velocity")
            val energies = renders.map { rms(it.samples, 0, 2 * Dsp.RATE) }
            val highs = renders.map { upperShare(it.samples, hz(midi), 2.0) }
            assertTrue(energies[0] < energies[1] * .9 && energies[1] < energies[2],
                "$voice MIDI$midi flattened or reversed touch RMS: $energies")
            assertTrue(highs[0] < highs[1] && highs[1] < highs[2],
                "$voice MIDI$midi hardness did not brighten the excitation: $highs")
            assertTrue(renders.all { it.peak() <= .94001f }, "$voice MIDI$midi overloads at hard touch")
        }
    }

    @Test
    fun `the measured coherent resonance regression stays bounded with one gain for every touch`() {
        // The first native full matrix measured peak 4.824982 at this actual
        // period-derived setting. Retain it as a real regression fixture,
        // rather than weakening the output ceiling or testing only defaults.
        val controls = mapOf("PACE" to .9522643f, "CONTACT" to 1f)
        // .5 and .9 are deliberately absent from the gain's normal/hard
        // reference pair, so these bounds test intermediate touches too.
        val probes = listOf(.3f, .5f, .75f, .9f, 1f).map { velocity ->
            Accord.probe(AccordVoice.TINE, controls, midi = 36, velocity = velocity,
                acousticProfile = AccordAcousticProfile.LONG_RING, sourceSeconds = 8.0)
        }
        val gains = probes.map { it.diagnostics.calibrationGain }
        for (gain in gains) assertEquals(gains.first(), gain,
            "the coherent fixture independently normalized a touch")
        val energy = probes.map { rms(it.samples.samples, 0, 8 * Dsp.RATE) }
        assertTrue(energy[0] < energy[2] * .9 && energy.zipWithNext().all { (a, b) -> a < b },
            "shared resonance protection flattened the five-touch energy law: $energy")
        for (probe in probes) {
            assertTrue(probe.samples.samples.all { it.isFinite() })
            assertTrue(probe.samples.peak() <= .94001f, "coherent fixture overloads: ${probe.samples.peak()}")
        }
    }

    @Test
    fun `accepted TINE options change spectral attack or free release without replacing the reference`() {
        fun tine(profile: AccordAcousticProfile) = Accord.render(AccordVoice.TINE,
            acousticProfile = profile, sourceSeconds = 8.0).samples
        val reference = tine(AccordAcousticProfile.REFERENCE)
        val soft = tine(AccordAcousticProfile.SOFT_ATTACK)
        val bright = tine(AccordAcousticProfile.BRIGHT_EDGE)
        val long = tine(AccordAcousticProfile.LONG_RING)
        val referenceHigh = upperShare(reference, hz(48), 8.0)
        assertTrue(upperShare(soft, hz(48), 8.0) < referenceHigh * .6,
            "SOFT_ATTACK did not soften the ringing attack spectrum")
        assertTrue(upperShare(bright, hz(48), 8.0) > referenceHigh * 1.8,
            "BRIGHT_EDGE did not retain its distinct upper-mode emphasis")
        val tailFrom = (8.2 * Dsp.RATE).toInt()
        val tailTo = (8.8 * Dsp.RATE).toInt()
        assertTrue(rms(long, tailFrom, tailTo) > 2 * rms(reference, tailFrom, tailTo),
            "LONG_RING did not preserve its longer unforced response")
        assertTrue(long.size > reference.size, "the retained longer captured release disappeared")
    }

    @Test
    fun `each timbral macro has a low to middle effect while controls interact acoustically`() {
        for (voice in AccordVoice.entries) for (control in listOf("PACE", "COUPLING", "MISMATCH", "CONTACT", "RAFT")) {
            val low = Accord.render(voice, mapOf(control to .1f), sourceSeconds = 2.0).samples
            val middle = Accord.render(voice, mapOf(control to .5f), sourceSeconds = 2.0).samples
            val relative = distance(low, middle, 2 * Dsp.RATE) / max(rms(middle, 0, 2 * Dsp.RATE), 1e-12)
            assertTrue(relative > .01, "$voice $control low/middle relative difference $relative")
        }
        // The effect of harder contacts must depend on articulation density.
        val corners = listOf(.1f, .9f).flatMap { pace -> listOf(.1f, .9f).map { contact ->
            Accord.render(AccordVoice.WOOD, mapOf("PACE" to pace, "CONTACT" to contact), sourceSeconds = 2.0).samples
        } }
        val atSlow = distance(corners[0], corners[1], 2 * Dsp.RATE)
        val atFast = distance(corners[2], corners[3], 2 * Dsp.RATE)
        assertTrue(abs(atFast - atSlow) > .001, "PACE x CONTACT did not change contact articulation")
    }

    @Test
    fun `finite class helpers agree with real output including short supported performances`() {
        for (voice in AccordVoice.entries) for (source in listOf(Accord.MIN_SOURCE_SECONDS, 2.0)) {
            val patch = AccordPatch("Class Probe", voice, Accord.defaults(voice), sourceSeconds = source)
            val snip = patch.render()
            val actual = Classifier.classify(snip).drumClass
            assertEquals(actual, Accord.drumClassFor(patch), "$voice source$source class/PCM disagreement")
            assertTrue(actual !in drumFamilies, "$voice source$source was classified $actual")
        }
    }

    @Test
    fun `unforced rendered tails retain their root after the complete house output path`() {
        for (voice in AccordVoice.entries) for (midi in listOf(36, 48, 60, 72)) {
            val snip = Accord.render(voice, midi = midi, sourceSeconds = 2.0)
            val estimate = assertNotNull(Pitch.detect(snip, 2.3f, .35f), "$voice MIDI$midi has no unforced root")
            val error = abs(cents(estimate.hz.toDouble(), hz(midi)))
            println("ACCORD $voice MIDI$midi unforced cents=$error confidence=${estimate.confidence}")
            assertTrue(error < 15, "$voice MIDI$midi rendered free root drifted $error cents")
            assertTrue(estimate.confidence >= .3f)
        }
    }

    @Test
    fun `active pitch remains rooted over declared windows and reports full confidence`() {
        for (voice in AccordVoice.entries) for (midi in listOf(36, 48, 60, 72)) {
            val snip = Accord.render(voice, midi = midi, sourceSeconds = 2.0)
            // Keep the original .4/1.1/1.6-second probes in this fixed union.
            // In particular, BRUSH C2's known .4-second detector blind spot
            // remains visible instead of moving the grid around that failure.
            val windows = listOf(.25f, .4f, .5f, .75f, 1f, 1.1f, 1.25f, 1.5f, 1.6f)
            val estimates = windows.map { from -> Pitch.detect(snip, from, .3f) }
            println("ACCORD $voice MIDI$midi active=" + windows.zip(estimates).map { (from, estimate) ->
                if (estimate == null) "$from:null" else
                    "$from:${cents(estimate.hz.toDouble(), hz(midi))}c/conf=${estimate.confidence}"
            })
            // Geometry-gated BRUSH can have weak/no monophonic estimates in
            // individual forced windows. Rejecting those by proximity to the
            // expected note would conceal errors; select only by confidence,
            // retain every null/wrong-register result in the printed report,
            // and require useful coverage over one fixed grid for all voices.
            val confident = estimates.filterNotNull().filter { it.confidence >= .3f }
            assertTrue(confident.size >= 3, "$voice MIDI$midi has only ${confident.size}/${windows.size} confident active windows")
            val errors = confident.map { abs(cents(it.hz.toDouble(), hz(midi))) }.sorted()
            // Forced windows can move the apparent period. This asserts the
            // declared root survives, not an unsupported universal five cents.
            val median = if (errors.size % 2 == 0) (errors[errors.size / 2 - 1] + errors[errors.size / 2]) / 2
                         else errors[errors.size / 2]
            assertTrue(median <= 50, "$voice MIDI$midi active median $median cents; all confident errors $errors")
        }
    }
}
