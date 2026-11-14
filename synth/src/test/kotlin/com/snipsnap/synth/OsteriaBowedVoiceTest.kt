package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BOWED is a continuous spectral string draw, with one onset and no pitch modulation. */
class OsteriaBowedVoiceTest {
    private val voice = OsteriaVoice.BOWED
    private val still = mapOf("SHORE" to .5f, "WANDER" to 0f, "MEMORY" to 0f, "ROOM" to 0f)
    private val stationary = Osteria.ProbeOptions(motionEnabled = false)

    @Test
    fun `bowed has its own normalized body after the common attack`() {
        val config = Osteria.configurationFor(voice).copy(
            initialShores = List(4) { 2.0 }, seconds = 2.0, releaseSeconds = .3)
        val bowed = spectrum(Osteria.probe(voice, still, 48, .8f, config, stationary).samples, 48, .35, 1.25)
        for (other in listOf(OsteriaVoice.REED, OsteriaVoice.PAD, OsteriaVoice.PLUCK)) {
            val reference = spectrum(Osteria.probe(other, still, 48, .8f, config, stationary).samples,
                48, .35, 1.25)
            val difference = distance(bowed, reference)
            println("BOWED/$other normalized held-body distance: $difference")
            assertTrue(difference > .035, "BOWED differs from $other only in level: $difference")
        }
        val even = bowed.indices.filter { (it + 1) % 2 == 0 }.sumOf { bowed[it] }
        val oddAboveRoot = bowed.indices.filter { it > 0 && (it + 1) % 2 == 1 }.sumOf { bowed[it] }
        assertTrue(even > .04 && oddAboveRoot > .008,
            "bowed string lost its mixed even/odd body: even $even, odd above root $oddAboveRoot")
        assertTrue(bowed[0] > .40, "bowed body obscures its common root: ${bowed[0]}")
    }

    @Test
    fun `one soft catch becomes a continuous draw rather than a pluck or repeated strokes`() {
        val config = Osteria.configurationFor(voice).copy(initialShores = List(4) { 2.0 }, seconds = 5.0)
        val drawn = Osteria.probe(voice, still, velocity = .8f, config = config, options = stationary)
        val early = rms(drawn.samples, .8, 1.0)
        val sustained = rms(drawn.samples, 3.2, 3.5)
        assertTrue(sustained > early * .80, "bow draw lost its continuous energy: $early -> $sustained")
        val pluck = Osteria.probe(OsteriaVoice.PLUCK, still, velocity = .8f,
            config = config, options = stationary)
        assertTrue(rms(pluck.samples, 3.2, 3.5) < rms(pluck.samples, .8, 1.0) * .25,
            "the sustained comparison no longer distinguishes an energy-losing pluck")
        assertTrue(rms(drawn.samples, 0.0, .01) < early * .02, "bow catch starts with a click")
        for ((role, stem) in drawn.stems.withIndex()) {
            var previous = rms(stem, .4, .5)
            var t = .5
            while (t + .1 < config.seconds - config.releaseSeconds - .1) {
                val next = rms(stem, t, t + .1)
                assertTrue(next < previous * 1.13,
                    "bowed role $role starts a new stroke at $t: $previous -> $next")
                previous = next
                t += .1
            }
        }
        assertTrue(drawn.samples.takeLast(2).all { abs(it) < .0001f }, "the bow never lifts")
    }

    @Test
    fun `friction changes upper body ratios without changing root or travel`() {
        for (midi in intArrayOf(36, 48, 72)) {
            val config = Osteria.configurationFor(voice).copy(
                initialShores = List(4) { 2.0 }, seconds = 3.4, releaseSeconds = .3)
            val on = Osteria.probe(voice, still, midi, .8f, config, stationary)
            val off = Osteria.probe(voice, still, midi, .8f, config,
                stationary.copy(bowedFrictionEnabled = false))
            assertEquals(on.shoreTrace, off.shoreTrace, "friction changed the saved journey")
            val differences = listOf(.75, 1.55, 2.35).map { start ->
                val sounding = spectrum(on.samples, midi, start, start + .40)
                val neutral = spectrum(off.samples, midi, start, start + .40)
                // Remove the root and normalize again. A shared gain on all
                // upper partials cannot pass this causal texture comparison.
                distance(upperBody(sounding), upperBody(neutral))
            }
            println("BOWED friction MIDI $midi normalized upper-body distances: $differences")
            assertTrue(differences.max() > .0005 && differences.average() > .0002,
                "bow friction only changes level at MIDI $midi: $differences")
            assertRoot(on.samples, midi, .85f)
            assertRoot(on.samples, midi, 2.35f)
        }
    }

    @Test
    fun `moving draw retains pitch and mono body at normal and soft touch`() {
        val config = Osteria.configurationFor(voice).copy(seconds = 2.5, releaseSeconds = .3)
        for (midi in intArrayOf(36, 48, 72)) for (velocity in floatArrayOf(.25f, .8f)) {
            val result = Osteria.probe(voice, midi = midi, velocity = velocity, config = config)
            assertTrue(result.samples.all { it.isFinite() && abs(it) < 1f }, "invalid MIDI $midi PCM")
            assertTrue(abs(mono(result.samples).average()) < .001, "bowed MIDI $midi has a DC offset")
            assertTrue(energy(mono(result.samples)) / energy(result.samples) > .80,
                "MIDI $midi touch $velocity loses its body in mono")
            val treble = trebleShare(result.samples, .5, 1.8)
            println("BOWED MIDI $midi touch $velocity energy above 4kHz: $treble")
            assertTrue(treble < .02, "MIDI $midi touch $velocity regained a hard upper buzz: $treble")
            assertRoot(result.samples, midi, .75f)
            assertRoot(result.samples, midi, 1.65f)
            val sum = FloatArray(result.samples.size) { index ->
                var sample = 0f
                for (stem in result.stems) sample += stem[index]
                sample
            }
            assertContentEquals(result.samples, sum, "bowed stems do not reconstruct the draw")
            assertTrue(result.samples.takeLast(2).all { abs(it) < .0001f })
        }
    }

    @Test
    fun `room and finite hold keep the same note clock and saved journey`() {
        val config = Osteria.configurationFor(voice).copy(seconds = 2.0)
        val dry = Osteria.probe(voice, config = config)
        val room = Osteria.probe(voice, mapOf("ROOM" to 1f), config = config)
        assertEquals(dry.shoreTrace, room.shoreTrace)
        assertTrue(!dry.samples.contentEquals(room.samples), "ROOM does not color the bowed draw")
        val original = Osteria.probe(OsteriaVoice.QUARTET, config = config)
        assertEquals(original.shoreTrace, dry.shoreTrace, "bowed changes the original travel mechanics")
        val held = Osteria.probe(voice, mapOf("HOLD" to 1f), config = config)
        assertEquals(8 * Dsp.RATE * 2, held.samples.size)
        val prefix = (1.2 * Dsp.RATE).toInt() * 2
        assertContentEquals(dry.samples.copyOfRange(0, prefix), held.samples.copyOfRange(0, prefix),
            "the future release changed the bow's note-on clock")
        assertTrue(held.samples.takeLast(2).all { abs(it) < .0001f })
        assertEquals(DrumClass.TONAL, Osteria.drumClassFor(voice, mapOf("HOLD" to 1f)))
    }

    @Test
    fun `fresh friction and filter state replay identically with warm and evicted source tables`() {
        val config = Osteria.configurationFor(voice).copy(seconds = 1.0, releaseSeconds = .3, seed = 914)
        val first = Osteria.probe(voice, midi = 48, config = config)
        val warm = Osteria.probe(voice, midi = 48, config = config)
        assertContentEquals(first.samples, warm.samples)
        assertEquals(first.shoreTrace, warm.shoreTrace)
        // Eight other roots fill the existing eight-bank LRU and evict MIDI 48.
        // Rebuilding immutable spectra must not change local friction or filters.
        for (midi in 36..43) Osteria.probe(voice, midi = midi, config = config)
        val rebuilt = Osteria.probe(voice, midi = 48, config = config)
        assertContentEquals(first.samples, rebuilt.samples)
        assertEquals(first.shoreTrace, rebuilt.shoreTrace)
    }

    @Test
    fun `production bow output has headroom silence and prompt cancellation`() {
        val config = Osteria.configurationFor(voice).copy(seconds = 1.5, releaseSeconds = .3)
        assertEquals(0.0, Osteria.balanceTrimDbFor(voice))
        for (midi in intArrayOf(36, 84)) for (corner in listOf(0f, 1f)) {
            val macros = Osteria.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else corner }
            val rendered = Osteria.render(voice, macros, midi = midi, config = config)
            assertTrue(rendered.samples.all { it.isFinite() && abs(it) <= .990001f },
                "bowed MIDI $midi corner $corner exceeds output headroom")
            assertTrue(rendered.peak() > .01f, "bowed MIDI $midi corner $corner is silent")
            assertTrue(rendered.samples.takeLast(2).all { abs(it) < .0001f })
        }
        assertTrue(Osteria.probe(voice, velocity = 0f, config = config).samples.all { it == 0f })
        var checks = 0
        assertFailsWith<CancellationException> {
            Osteria.render(voice, config = config, cancelled = { ++checks >= 3 })
        }
        assertEquals(3, checks, "bowed render did not check cancellation during source generation")
    }

    private fun assertRoot(samples: FloatArray, midi: Int, time: Float) {
        val pitch = Pitch.detect(Snip(samples, channels = 2, sampleRate = Dsp.RATE), time, .30f)
        assertNotNull(pitch, "bowed MIDI $midi lost its root at $time")
        val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
        assertTrue(abs(cents) < 20 && pitch.confidence >= .3f,
            "bowed MIDI $midi at $time: $cents cents, confidence ${pitch.confidence}")
    }

    private fun mono(stereo: FloatArray) = FloatArray(stereo.size / 2) {
        (stereo[it * 2] + stereo[it * 2 + 1]) * .5f
    }

    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it } / samples.size

    private fun rms(stereo: FloatArray, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt() * 2
        val end = minOf(stereo.size, (until * Dsp.RATE).toInt() * 2)
        return sqrt((start until end).sumOf { stereo[it].toDouble() * stereo[it] } / (end - start))
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]) } / 2.0

    private fun upperBody(bands: DoubleArray): DoubleArray {
        val total = bands.drop(1).sum().coerceAtLeast(1e-24)
        return DoubleArray(bands.size - 1) { bands[it + 1] / total }
    }

    /** Harmonic powers, normalized per frame so a level change cannot pass. */
    private fun spectrum(stereo: FloatArray, midi: Int, from: Double, until: Double): DoubleArray {
        val root = Keys.midiHz(midi).toDouble()
        val averaged = DoubleArray(16)
        var count = 0
        forEachSpectrum(stereo, from, until) { real, imaginary ->
            val bands = DoubleArray(averaged.size)
            for (bin in 1..real.size / 2) {
                val harmonic = (bin * Dsp.RATE / (real.size * root) + .5).toInt()
                if (harmonic in 1..bands.size) {
                    bands[harmonic - 1] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                }
            }
            val total = bands.sum()
            if (total > 1e-24) {
                for (band in bands.indices) averaged[band] += bands[band] / total
                count++
            }
        }
        check(count > 0)
        return DoubleArray(averaged.size) { averaged[it] / count }
    }

    private fun trebleShare(stereo: FloatArray, from: Double, until: Double): Double {
        var treble = 0.0
        var total = 0.0
        forEachSpectrum(stereo, from, until) { real, imaginary ->
            for (bin in 1..real.size / 2) {
                val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                total += power
                if (bin * Dsp.RATE.toDouble() / real.size >= 4000.0) treble += power
            }
        }
        return treble / total.coerceAtLeast(1e-24)
    }

    private fun forEachSpectrum(stereo: FloatArray, from: Double, until: Double,
        consume: (FloatArray, FloatArray) -> Unit) {
        val size = 8192
        val first = (from * Dsp.RATE).toInt()
        val end = minOf(stereo.size / 2, (until * Dsp.RATE).toInt())
        for (start in first..end - size step size / 2) {
            val real = FloatArray(size) { i ->
                ((stereo[(start + i) * 2] + stereo[(start + i) * 2 + 1]) * .5 *
                    (.5 - .5 * cos(2 * PI * i / (size - 1)))).toFloat()
            }
            val imaginary = FloatArray(size)
            Fft.forward(real, imaginary)
            consume(real, imaginary)
        }
    }
}
