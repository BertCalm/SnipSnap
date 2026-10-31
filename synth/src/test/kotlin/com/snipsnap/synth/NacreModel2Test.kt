package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Behavioral checks for the finite listening model, before factory/preset acceptance. */
class NacreModel2Test {
    private val base = NacreModel2.defaults(NacreVoice.PEARL)

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from).coerceAtLeast(1))

    private fun distance(a: FloatArray, b: FloatArray): Double =
        sqrt(a.indices.sumOf { (a[it].toDouble() - b[it]).let { d -> d * d } } / a.size) /
            rms(a).coerceAtLeast(1e-12)

    /** Broad-band spectral evidence ignores sample phase and scalar loudness differences. */
    private fun spectrum(x: FloatArray, fromSeconds: Double = .20): DoubleArray {
        val n = 32768
        val start = (fromSeconds * 44_100).toInt()
        val re = FloatArray(n) { i -> (x[start + i] * (.5 - .5 * cos(2.0 * PI * i / (n - 1)))).toFloat() }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun highFraction(x: FloatArray, boundaryHz: Double = 500.0): Double {
        val power = spectrum(x)
        val first = (boundaryHz * 32768 / 44_100).toInt()
        return power.drop(first).sum() / power.sum().coerceAtLeast(1e-20)
    }

    private fun spectralShapeDistance(a: FloatArray, b: FloatArray): Double {
        val pa = spectrum(a); val pb = spectrum(b)
        val ea = pa.sum().coerceAtLeast(1e-20); val eb = pb.sum().coerceAtLeast(1e-20)
        // Compare broad bands, so a tiny detuning cannot manufacture spectral contrast.
        val boundaries = listOf(0, 200, 350, 500, 800, 1200, 2000, 4000, 8000, 16000)
        return boundaries.zipWithNext().sumOf { (lo, hi) ->
            val first = lo * 32768 / 44_100
            val last = minOf(pa.size, hi * 32768 / 44_100)
            abs((first until last).sumOf { pa[it] } / ea - (first until last).sumOf { pb[it] } / eb)
        }
    }

    private fun direct(probe: NacreModel2.Probe): FloatArray {
        val bass = probe.stems.getValue("bass"); val upper = probe.stems.getValue("upper")
        return FloatArray(bass.size) { bass[it] + upper[it] }
    }

    @Test
    fun `root seed and macro map ordering reproduce every event and sample`() {
        val a = NacreModel2.inspect(macros = base, seed = Long.MIN_VALUE)
        val b = NacreModel2.inspect(macros = base.entries.reversed().associate { it.key to it.value }, seed = Long.MIN_VALUE)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        for (stem in a.stems.keys) assertContentEquals(a.stems.getValue(stem), b.stems.getValue(stem))
        assertTrue(distance(a.raw, NacreModel2.inspect(seed = Long.MAX_VALUE).raw) > .01, "seed has no effect")
    }

    @Test
    fun `each complete gesture has four strings per performer with corresponding octave roots`() {
        for (duet in listOf(0f, .5f, 1f)) {
            val events = NacreModel2.inspect(macros = base + ("DUET" to duet)).events
            assertEquals(8, events.size)
            assertEquals(events.sortedBy { it.timeSeconds }, events)
            assertTrue(events.all { it.timeSeconds >= 0 && it.timeSeconds < 1.0 && it.strength > 0 })
            val bass = events.filter { it.performer == "bass" }.associateBy { it.stringId }
            val upper = events.filter { it.performer == "upper" }.associateBy { it.stringId }
            assertEquals(setOf(0, 1, 2, 3), bass.keys)
            assertEquals(bass.keys, upper.keys)
            for (id in bass.keys) assertEquals(2.0, upper.getValue(id).frequencyHz / bass.getValue(id).frequencyHz, 1e-5)
            if (duet == 1f) assertTrue(events.filter { it.performer == "bass" }.map { it.stringId } !=
                events.filter { it.performer == "upper" }.map { it.stringId }, "crossing directions are identical")
        }
    }

    @Test
    fun `lossy finite networks remain finite and decay at register and macro corners`() {
        val cases = listOf(28 to base, 48 to base.mapValues { if (it.key == "HOLD") 0f else 1f },
            72 to (base + mapOf("TOUCH" to 1f, "CURRENT" to 1f, "VENUE" to 1f, "BLOOM" to 1f)))
        for ((midi, macros) in cases) {
            val probe = NacreModel2.inspect(macros = macros, midi = midi)
            assertEquals(44_100, probe.snip.sampleRate)
            assertEquals(1, probe.snip.channels)
            assertTrue(probe.raw.size in 2 * 44_100..8 * 44_100)
            assertTrue(probe.raw.all { it.isFinite() })
            assertTrue(probe.snip.samples.all { it.isFinite() && abs(it) <= 1.00001f })
            assertTrue(probe.raw.maxOf { abs(it) } < 4f, "uncontrolled raw peak at MIDI $midi")
            assertTrue(abs(probe.raw.sumOf { it.toDouble() } / probe.raw.size) < .005, "DC buildup")
            for ((name, stem) in probe.stems) {
                assertTrue(stem.all { it.isFinite() }, "$name states diverged")
                assertTrue(rms(stem, stem.size - 44_100) < rms(stem, 0, 44_100) * .2 + 1e-9,
                    "$name did not decay at MIDI $midi")
            }
        }
    }

    @Test
    fun `each timbral control changes low and middle settings after leveling`() {
        for (macro in listOf("TOUCH", "DUET", "CURRENT", "VENUE", "BLOOM")) {
            val low = NacreModel2.render(macros = base + (macro to 0f)).samples
            val quarter = NacreModel2.render(macros = base + (macro to .25f)).samples
            val middle = NacreModel2.render(macros = base + (macro to .5f)).samples
            assertTrue(distance(low, quarter) > .002, "$macro has no low-range authority")
            assertTrue(distance(quarter, middle) > .002, "$macro has no middle-range authority")
        }
    }

    @Test
    fun `touch changes harmonic balance while diagnostic strum timing stays fixed`() {
        val options = NacreModel2.Options(lockStrumTiming = true)
        val soft = NacreModel2.inspect(macros = base + ("TOUCH" to 0f), options = options)
        val hard = NacreModel2.inspect(macros = base + ("TOUCH" to 1f), options = options)
        assertEquals(soft.events.map { Triple(it.performer, it.stringId, it.timeSeconds) },
            hard.events.map { Triple(it.performer, it.stringId, it.timeSeconds) })
        val softHigh = highFraction(soft.raw)
        val hardHigh = highFraction(hard.raw)
        assertTrue(hardHigh > softHigh * 2.0 + .04,
            "TOUCH only changes level: above500Hz soft=$softHigh hard=$hardHigh")
        assertTrue(spectralShapeDistance(soft.raw, hard.raw) > .15, "touch spectral shapes remain too similar")
    }

    @Test
    fun `baseline rings long enough for water and exposes sympathetic and venue contributions`() {
        val probe = NacreModel2.inspect(options = NacreModel2.Options(lockStrumTiming = true))
        val directRms = rms(direct(probe))
        val symRatio = rms(probe.stems.getValue("sympathetic")) / directRms
        val venueRatio = rms(probe.stems.getValue("venue")) / directRms
        val energy99 = probe.diagnostics.getValue("energy99Seconds")
        val high = highFraction(probe.raw)
        println("NACRE2 baseline: E99=$energy99 s, above500Hz=$high, sympathetic/direct=$symRatio, venue/direct=$venueRatio")
        assertTrue(energy99 in 1.3..4.5, "energy is concentrated in $energy99 seconds")
        assertTrue(rms(probe.raw, 44_100, 2 * 44_100) > rms(probe.raw, 8820, 44_100) * .08,
            "the water acts after the audible note ends")
        assertTrue(symRatio > .10, "sympathetic RMS/direct=$symRatio remains buried")
        assertTrue(venueRatio > .08, "venue RMS/direct=$venueRatio remains buried")
        assertTrue(high > .025, "baseline remains almost entirely the two lowest tones: above500Hz=$high")
    }

    @Test
    fun `venues have different material spectra while current changes a fixed gesture`() {
        val options = NacreModel2.Options(lockStrumTiming = true)
        val coral = NacreModel2.inspect(macros = base + mapOf("VENUE" to 0f, "BLOOM" to .65f), options = options)
        val weed = NacreModel2.inspect(macros = base + mapOf("VENUE" to 1f, "BLOOM" to .65f), options = options)
        assertContentEquals(coral.stems.getValue("bass"), weed.stems.getValue("bass"))
        assertContentEquals(coral.stems.getValue("upper"), weed.stems.getValue("upper"))
        assertTrue(spectralShapeDistance(coral.stems.getValue("venue"), weed.stems.getValue("venue")) > .18,
            "venue endpoints only change timing or level")
        assertTrue(spectralShapeDistance(coral.raw, weed.raw) > .025, "venue color disappears in the full mix")
        val still = NacreModel2.inspect(macros = base + ("CURRENT" to 0f), options = options)
        val moving = NacreModel2.inspect(macros = base + ("CURRENT" to .8f), options = options)
        assertEquals(still.events.map { it.timeSeconds }, moving.events.map { it.timeSeconds })
        assertTrue(spectralShapeDistance(still.raw, moving.raw) > .06, "water only changes overall energy")
    }

    @Test
    fun `cross harp excitation reaches unplucked resonators without changing direct strings`() {
        val macros = base + ("BLOOM" to .85f)
        val on = NacreModel2.inspect(macros = macros, options = NacreModel2.Options(muteUpperPlucks = true))
        val off = NacreModel2.inspect(macros = macros, options = NacreModel2.Options(muteUpperPlucks = true, disableCrossHarp = true))
        assertContentEquals(on.stems.getValue("bass"), off.stems.getValue("bass"))
        assertContentEquals(on.stems.getValue("upper"), off.stems.getValue("upper"))
        assertTrue(distance(on.stems.getValue("sympathetic"), off.stems.getValue("sympathetic")) > .01)
        assertTrue(rms(on.stems.getValue("sympathetic")) > 1e-7)
    }

    @Test
    fun `current changes active string states even with venue bypassed`() {
        val moving = base + ("CURRENT" to .7f)
        val internal = NacreModel2.inspect(macros = moving, options = NacreModel2.Options(bypassVenue = true))
        val post = NacreModel2.inspect(macros = moving, options = NacreModel2.Options(bypassVenue = true, postCurrentOnly = true))
        assertTrue(distance(internal.stems.getValue("bass"), post.stems.getValue("bass")) > .005,
            "current only changes final pickup")
    }

    @Test
    fun `moderate gestures retain the requested fundamental and avoid drum families`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED,
            DrumClass.HAT_OPEN, DrumClass.TOM)
        for (midi in listOf(36, 48, 60, 72)) {
            val snip = NacreModel2.render(midi = midi)
            val pitch = requireNotNull(Pitch.detect(snip, fromSec = .45f, windowSec = .4f)) { "no pitch at MIDI $midi" }
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15.0, "MIDI $midi: $cents cents (including octave ambiguity)")
            assertTrue(Classifier.classify(snip).drumClass !in forbidden, "MIDI $midi classified as a drum")
        }
    }

    @Test
    fun `hard touch retains the bass root across the audition register`() {
        for (midi in listOf(36, 60, 72)) {
            val snip = NacreModel2.inspect(macros = base + ("TOUCH" to 1f), midi = midi,
                seed = 7L, options = NacreModel2.Options(lockStrumTiming = true)).snip
            val pitch = requireNotNull(Pitch.detect(snip, fromSec = .45f, windowSec = .4f)) { "hard touch has no pitch at MIDI $midi" }
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15.0, "hard TOUCH MIDI $midi: $cents cents (including octave ambiguity)")
        }
    }

    @Test
    fun `separated crossing gestures begin as pitched plucks without a noisy drum attack`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED,
            DrumClass.HAT_OPEN, DrumClass.TOM)
        for (duet in listOf(.85f, 1f)) for (touch in listOf(.3f, .7f)) {
            val snip = NacreModel2.render(macros = base + mapOf("DUET" to duet, "TOUCH" to touch))
            val onset = Snip(snip.samples.copyOfRange(0, (44_100 * .75).toInt()), 1, 44_100)
            val classified = Classifier.classify(onset)
            assertTrue(classified.drumClass !in forbidden,
                "DUET $duet TOUCH $touch onset: ${classified.drumClass}, flatness ${classified.features.flatness}")
        }
    }

    @Test
    fun `zero velocity injects no energy and unsupported HOLD is refused`() {
        val silent = NacreModel2.inspect(velocity = 0f)
        assertTrue(silent.raw.all { it == 0f })
        assertTrue(silent.snip.samples.all { it == 0f })
        assertTrue(silent.stems.values.all { x -> x.all { it == 0f } })
        assertFailsWith<IllegalArgumentException> { NacreModel2.render(macros = base + ("HOLD" to 1f)) }
    }
}
