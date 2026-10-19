package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenSkyTest {
    private fun rms(s: FloatArray, from: Int = 0, until: Int = s.size): Double {
        var energy = 0.0
        for (i in from until until) energy += s[i].toDouble() * s[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    private fun mono(s: Snip) = FloatArray(s.frameCount) { (s.samples[it * 2] + s.samples[it * 2 + 1]) * .5f }

    private fun windowRms(s: Snip, fromSeconds: Double, untilSeconds: Double): Double {
        val folded = mono(s)
        val from = (fromSeconds * OpenSky.RATE).toInt()
        val until = (untilSeconds * OpenSky.RATE).toInt()
        var energy = 0.0
        for (i in from until until) if (i < folded.size) energy += folded[i].toDouble() * folded[i]
        return sqrt(energy / (until - from))
    }

    private fun bandFractions(s: Snip): DoubleArray {
        val folded = mono(s)
        val from = (OpenSky.RATE * .3).toInt()
        val count = (OpenSky.RATE * .8).toInt()
        val real = FloatArray(65_536)
        val imaginary = FloatArray(real.size)
        for (i in 0 until count) {
            real[i] = folded[from + i] * (.5 - .5 * cos(2.0 * PI * i / (count - 1))).toFloat()
        }
        Fft.forward(real, imaginary)
        val bands = DoubleArray(6)
        val edges = doubleArrayOf(500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        for (bin in 1 until real.size / 2) {
            val hz = bin.toDouble() * OpenSky.RATE / real.size
            val band = edges.indexOfFirst { hz < it }.let { if (it < 0) 5 else it }
            bands[band] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
        }
        val total = bands.sum()
        return DoubleArray(bands.size) { bands[it] / total }
    }

    @Test
    fun `neutral timbre preserves the R2 finite and held dry source exactly`() {
        // Captured from the compiled R2 dry engine before the R3 room revision.
        val hashes = mapOf(
            OpenSkyVoice.ANTHEM to 1897328628,
            OpenSkyVoice.SOAR to -951915867,
            OpenSkyVoice.HALO to 1919191686,
            OpenSkyVoice.CRYSTAL to -92041345,
        )
        for ((voice, hash) in hashes) {
            val defaults = OpenSky.render(voice, gateSeconds = .4f, wet = false)
            val explicit = OpenSky.render(voice, gateSeconds = .4f, wet = false, timbre = OpenSkyTimbre())
            assertEquals(hash, defaults.samples.contentHashCode(), "$voice neutral dry timbre changed R2")
            assertContentEquals(defaults.samples, explicit.samples)
        }
        val held = OpenSky.renderHeld(OpenSkyVoice.HALO, loopSeconds = 1.5f, wet = false)
        assertEquals(500074414, held.samples.contentHashCode(), "neutral held dry HALO changed R2")
    }

    @Test
    fun `source recipes reject malformed and unbounded values`() {
        assertFailsWith<IllegalArgumentException> { OpenSkyTimbre(pulseMix = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { OpenSkyTimbre(toneScale = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { OpenSkyTimbre(pulseWidth = .01f) }
        assertFailsWith<IllegalArgumentException> { OpenSkyTimbre(releaseScale = 10f) }
    }

    @Test
    fun `asymmetric pulse and octave sources retain headroom and remove duty-cycle DC`() {
        for (midi in listOf(60, 96, 108)) {
            val timbre = OpenSkyTimbre(pulseMix = .9f, pulseWidth = .15f, octaveMix = .6f, toneScale = 2f)
            val s = OpenSky.render(OpenSkyVoice.SOAR, mapOf("WIDTH" to 0f, "AIR" to 1f),
                midi = midi, gateSeconds = .4f, wet = false, timbre = timbre)
            assertTrue(s.samples.all { it.isFinite() && abs(it) < 1f }, "pulse/octave broke headroom at MIDI $midi")
            assertTrue(abs(mono(s).average()) < .002, "pulse duty cycle introduced DC at MIDI $midi")
            assertTrue(rms(s.samples) > .002, "band limiting erased the MIDI $midi source")
        }
    }

    @Test
    fun `each authored preset pair changes dry spectral balance and release at the same pitch`() {
        for ((voice, first, second) in OpenSkyAuditionGenerator.sourcePairsForTest()) {
            // Shared note/macros/gate isolate the new source recipes from room,
            // transposition and differences in the four front-panel macros.
            val a = OpenSky.render(voice, midi = 60, gateSeconds = 1.5f, wet = false, timbre = first)
            val b = OpenSky.render(voice, midi = 60, gateSeconds = 1.5f, wet = false, timbre = second)
            val bandsA = bandFractions(a); val bandsB = bandFractions(b)
            val variation = bandsA.indices.sumOf { abs(bandsA[it] - bandsB[it]) } * .5
            // Soft Horizon's slow onset is still rising at .5..1s. Compare
            // release with the settled body immediately before the shared gate.
            val releaseA = windowRms(a, 1.6, 1.8) / windowRms(a, 1.3, 1.45)
            val releaseB = windowRms(b, 1.6, 1.8) / windowRms(b, 1.3, 1.45)
            assertTrue(variation > .12, "$voice dry pair spectral variation $variation is too small")
            assertTrue(abs(releaseA - releaseB) > .05,
                "$voice articulation pair stayed too similar: relative release $releaseA / $releaseB")
        }
    }

    @Test
    fun `configured pulse octave and sine halo retains independently validated wet recurrence`() {
        val timbre = OpenSkyTimbre(pulseMix = .6f, pulseWidth = .25f, sineMix = .2f,
            octaveMix = .4f, detuneScale = 1.5f, toneScale = 1.3f, sustainLevel = .6f)
        val held = OpenSky.heldProbe(OpenSkyVoice.HALO, loopSeconds = 1.5f, timbre = timbre)
        assertTrue(held.loopError < 1e-10, "configured source broke complete wet recurrence")
        assertTrue(held.rawPeak < 1f)
        var delta = 0.0; var energy = 0.0
        for (i in held.previousCycle.indices) {
            val d = held.previousCycle[i] - held.snip.samples[i].toDouble()
            delta += d * d; energy += held.snip.samples[i].toDouble() * held.snip.samples[i]
        }
        assertTrue(delta / energy < 1e-10)
        assertTrue(held.shimmerPeak > .0001f)
        assertFailsWith<CancellationException> {
            OpenSky.renderHeld(OpenSkyVoice.HALO, cancelled = { true }, timbre = timbre)
        }
    }

    @Test
    fun `finite voices and high notes are deterministic stereo raw audio`() {
        for (voice in OpenSkyVoice.entries) {
            val a = OpenSky.render(voice, midi = 84, gateSeconds = .25f)
            val b = OpenSky.render(voice, midi = 84, gateSeconds = .25f)
            assertEquals(2, a.channels)
            assertEquals(44_100, a.sampleRate)
            assertContentEquals(a.samples, b.samples, "$voice changed across independent renders")
            assertTrue(a.samples.all { it.isFinite() && abs(it) < 1f }, "$voice raw output exhausted headroom")
            assertTrue(rms(a.samples) > .002, "$voice produced no audible material")
            assertTrue(abs(mono(a).average()) < .002, "$voice has excessive DC")
        }
    }

    @Test
    fun `velocity keeps event amplitude and zero remains silent`() {
        val loud = OpenSky.render(OpenSkyVoice.SOAR, velocity = 1f, gateSeconds = .3f, wet = false)
        val soft = OpenSky.render(OpenSkyVoice.SOAR, velocity = .4f, gateSeconds = .3f, wet = false)
        assertTrue(abs(rms(soft.samples) / rms(loud.samples) - .4) < 1e-5, "per-clip normalization erased velocity")
        val silent = OpenSky.render(OpenSkyVoice.CRYSTAL, velocity = 0f, gateSeconds = .1f)
        assertTrue(silent.samples.all { it == 0f })
    }

    @Test
    fun `width produces distinct channels while the fold retains the source`() {
        val narrow = OpenSky.renderHeld(OpenSkyVoice.ANTHEM, mapOf("WIDTH" to 0f), wet = false, loopSeconds = 1.5f)
        val wide = OpenSky.renderHeld(OpenSkyVoice.ANTHEM, mapOf("WIDTH" to 1f), wet = false, loopSeconds = 1.5f)
        val difference = FloatArray(wide.frameCount) { wide.samples[it * 2] - wide.samples[it * 2 + 1] }
        assertTrue((0 until narrow.frameCount).all { narrow.samples[it * 2] == narrow.samples[it * 2 + 1] })
        assertTrue(rms(difference) > .02, "wide unison did not produce stereo width")
        assertTrue(rms(mono(wide)) / rms(wide.samples) > .55, "mono fold lost too much of the root")
    }

    @Test
    fun `octave heads place sinusoidal energy near twice the input frequency`() {
        // A noncommensurate tone catches the real granular sidebands instead of
        // testing only the easy case of whole cycles in a grain window.
        val shift = OpenSky.OctaveShift()
        val samples = FloatArray(65_536)
        for (i in 0 until 85_536) {
            val input = sin(2.0 * PI * 440.0 * i / OpenSky.RATE).toFloat()
            val out = shift.next(input)
            if (i >= 20_000) samples[i - 20_000] = out
        }
        val imaginary = FloatArray(samples.size)
        for (i in samples.indices) samples[i] *= (.5 - .5 * cos(2.0 * PI * i / (samples.size - 1))).toFloat()
        Fft.forward(samples, imaginary)
        var octave = 0.0; var original = 0.0; var total = 0.0
        for (bin in 1 until samples.size / 2) {
            val power = samples[bin].toDouble() * samples[bin] + imaginary[bin].toDouble() * imaginary[bin]
            val hz = bin.toDouble() * OpenSky.RATE / samples.size
            total += power
            if (hz in 835.0..925.0) octave += power
            if (hz in 395.0..485.0) original += power
        }
        assertTrue(octave > total * .80, "octave read heads failed to shift most energy")
        assertTrue(octave > original * 50, "the original octave dominated the supposed shimmer")
    }

    @Test
    fun `wet path contains actual returning octave audio and glow extends persistence`() {
        val macros = mapOf("RISE" to 1f, "GLOW" to .8f, "AIR" to .7f)
        val shifted = OpenSky.probe(OpenSkyVoice.CRYSTAL, macros, gateSeconds = .25f)
        val plain = OpenSky.probe(OpenSkyVoice.CRYSTAL, macros, gateSeconds = .25f, shimmer = false)
        val delta = FloatArray(shifted.snip.samples.size) { shifted.snip.samples[it] - plain.snip.samples[it] }
        // A fixed passive-tail window excludes both the source and the several
        // seconds of near-silence appended to permit natural room decay.
        val tailStart = (1.0 * OpenSky.RATE).toInt() * 2
        val tailEnd = (1.5 * OpenSky.RATE).toInt() * 2
        assertTrue(shifted.shimmerPeak > .0001f, "no room audio returned through the octave send")
        assertEquals(0f, plain.shimmerPeak)
        val deltaRms = rms(delta, tailStart, tailEnd)
        val roomRms = rms(plain.snip.samples, tailStart, tailEnd)
        assertTrue(deltaRms > .00001 && deltaRms / roomRms > .10,
            "shimmer did not affect the passive tail: delta $deltaRms, plain room $roomRms")
        val low = OpenSky.probe(OpenSkyVoice.ANTHEM, macros + ("GLOW" to 0f), gateSeconds = .25f)
        val high = OpenSky.probe(OpenSkyVoice.ANTHEM, macros + ("GLOW" to 1f), gateSeconds = .25f)
        val from = (1.2 * OpenSky.RATE).toInt() * 2
        val until = (1.8 * OpenSky.RATE).toInt() * 2
        assertTrue(rms(high.snip.samples, from, until) > rms(low.snip.samples, from, until) * 1.3,
            "GLOW did not extend the decaying room's lifetime")
    }

    @Test
    fun `short excitation leaves an audible diffuse room and distinct post-source octave bloom`() {
        val timbre = OpenSkyAuditionGenerator.sourcePairsForTest().first { it.first == OpenSkyVoice.CRYSTAL }.second
        val macros = mapOf("RISE" to 1f, "GLOW" to 1f, "AIR" to .95f)
        val dry = OpenSky.probe(OpenSkyVoice.CRYSTAL, macros, gateSeconds = .12f, wet = false, timbre = timbre)
        val room = OpenSky.probe(OpenSkyVoice.CRYSTAL, macros, gateSeconds = .12f, shimmer = false, timbre = timbre)
        val shimmer = OpenSky.probe(OpenSkyVoice.CRYSTAL, macros, gateSeconds = .12f, timbre = timbre)
        assertEquals(dry.snip.frameCount, room.sourceFrames)
        assertEquals(room.sourceFrames, shimmer.sourceFrames)
        val from = room.sourceFrames * 2
        val until = from + OpenSky.RATE
        val sourceRms = rms(dry.snip.samples, 0, (.12 * OpenSky.RATE).toInt() * 2)
        val roomRms = rms(room.wetSamples, from, until)
        val octave = FloatArray(room.wetSamples.size) { shimmer.wetSamples[it] - room.wetSamples[it] }
        val octaveRms = rms(octave, from, until)
        assertTrue(roomRms / sourceRms > .08,
            "short source ended without a substantial room return: $roomRms / $sourceRms")
        assertTrue(octaveRms / roomRms > .20,
            "post-source octave bloom is still masked by the room: $octaveRms / $roomRms")
        assertTrue(room.snip.samples.copyOfRange(from, room.snip.samples.size)
            .contentEquals(room.wetSamples.copyOfRange(from, room.wetSamples.size)),
            "pure-return samples contain a dry source after sourceFrames")
        val lateFrom = from + 3 * OpenSky.RATE * 2
        val lateUntil = lateFrom + OpenSky.RATE
        assertTrue(rms(room.wetSamples, lateFrom, lateUntil) / sourceRms > .002,
            "high GLOW room disappeared before a useful long tail formed")
        assertTrue(shimmer.rawPeak < 1f && shimmer.roomPeak < 1f)
    }

    @Test
    fun `raw full controls remain bounded and decay before final normalization`() {
        val macros = OpenSky.defaults(OpenSkyVoice.ANTHEM).mapValues { 1f }
        val probe = OpenSky.probe(OpenSkyVoice.ANTHEM, macros, midi = 48, gateSeconds = 2f)
        assertTrue(probe.rawPeak < 1f, "raw peak ${probe.rawPeak} requires a hidden gain rescue")
        assertTrue(probe.roomPeak < 1f, "room state ${probe.roomPeak} grew beyond ordinary signal levels")
        val start = (4.5 * OpenSky.RATE).toInt() * 2
        val end = (5.5 * OpenSky.RATE).toInt() * 2
        assertTrue(rms(probe.snip.samples, end, probe.snip.samples.size) < rms(probe.snip.samples, start, end),
            "unfaded passive wet tail grew instead of decaying")
    }

    @Test
    fun `held halo keeps width and wet recurrence and validates independent adjacent cycles`() {
        val macros = mapOf("RISE" to .8f, "WIDTH" to .7f, "GLOW" to .75f, "AIR" to .5f)
        val held = OpenSky.heldProbe(OpenSkyVoice.HALO, macros, loopSeconds = 1.5f)
        val dry = OpenSky.renderHeld(OpenSkyVoice.HALO, macros, loopSeconds = 1.5f, wet = false)
        assertTrue(held.settleCycles >= 4)
        assertTrue(held.loopError < 1e-10, "complete wet state did not repeat: ${held.loopError}")
        assertTrue(held.shimmerPeak > .0001f)
        assertEquals(held.snip.samples.size, held.previousCycle.size)
        var delta = 0.0; var energy = 0.0
        for (i in held.previousCycle.indices) {
            val d = held.previousCycle[i] - held.snip.samples[i].toDouble()
            delta += d * d; energy += held.snip.samples[i].toDouble() * held.snip.samples[i]
        }
        assertTrue(delta / energy < 1e-10, "independently rendered wet cycles disagree")
        val wetDifference = FloatArray(dry.samples.size) { held.snip.samples[it] - dry.samples[it] }
        val stereo = FloatArray(held.snip.frameCount) { held.snip.samples[it * 2] - held.snip.samples[it * 2 + 1] }
        assertTrue(rms(wetDifference) > .005, "held HALO lost its room identity")
        assertTrue(rms(stereo) > .01, "held HALO lost its width")
    }

    @Test
    fun `finite and held rendering both honor cancellation`() {
        assertFailsWith<CancellationException> { OpenSky.render(OpenSkyVoice.ANTHEM, cancelled = { true }) }
        assertFailsWith<CancellationException> { OpenSky.renderHeld(OpenSkyVoice.HALO, cancelled = { true }) }
    }
}
