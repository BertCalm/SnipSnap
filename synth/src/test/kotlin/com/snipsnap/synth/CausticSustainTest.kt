package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** State/continuation proofs are made before cropping, fading or level makeup.
 * Spectral/envelope comparisons remove gain and cannot pass from phase alone.
 */
class CausticSustainTest {
    private val rate = Dsp.RATE
    private val voices = CausticVoice.entries
    private fun macros(voice: CausticVoice, vararg overrides: Pair<String, Float>) =
        CausticInstrument.defaults(voice, model = 1) + mapOf("HOLD" to 1f, "MOTION" to 0f) + overrides.toMap()

    @Test
    fun `whole period plans retain every supported note without a large tuning concession`() {
        for (midi in 36..84) for (length in listOf(0f, .5f, 1f)) {
            val frames = CausticSustain.loopFrameCount(midi, length)
            val requested = 440 * 2.0.pow((midi - 69) / 12.0)
            val periods = (frames * requested / rate).roundToInt()
            val fitted = periods * rate.toDouble() / frames
            val cents = 1200 * ln(fitted / requested) / ln(2.0)
            assertTrue(frames > 256 && frames / rate.toDouble() in .55..1.1, "MIDI$midi LENGTH$length frames=$frames")
            assertTrue(abs(cents) < .05, "MIDI$midi plan changes pitch by $cents cents")
            assertEquals(0, periods % 4, "four source contacts must share the carrier phase")
        }
    }

    @Test
    fun `periodic state solution agrees with independent complex recurrence and free decay`() {
        val internalRate = rate * 4
        val drive = DoubleArray(4093) { n ->
            .7 * sin(2 * PI * n / 4093) + .23 * cos(2 * PI * 17 * n / 4093) + if (n == 83) .5 else 0.0
        }
        val frequency = 333.7; val t60 = .27
        val probe = CausticSustain.periodicProbe(drive, frequency, t60, internalRate)
        val radius = exp(-ln(1000.0) / (t60 * internalRate))
        val cr = radius * cos(2 * PI * frequency / internalRate)
        val ci = radius * sin(2 * PI * frequency / internalRate)
        var real = probe.beforeRe; var imag = probe.beforeIm
        for (cycle in 0..1) for (n in drive.indices) {
            val previous = real
            real = previous * cr - imag * ci
            imag = previous * ci + imag * cr - drive[n].toFloat().toDouble() / internalRate
            val expected = if (cycle == 0) probe.first[n] else probe.second[n]
            assertTrue(abs(-imag - expected) < 1e-13, "independent recurrence disagrees at $cycle/$n")
        }
        val scale = hypot(probe.beforeRe, probe.beforeIm).coerceAtLeast(1e-20)
        assertTrue(hypot(real - probe.beforeRe, imag - probe.beforeIm) / scale < 1e-9)
        assertTrue(relative(probe.first, probe.second) < 1e-9, "freshly stepped second cycle did not close")
        val initialEnergy = real * real + imag * imag
        repeat(1024) { val previous = real; real = previous * cr - imag * ci; imag = previous * ci + imag * cr }
        assertTrue(real * real + imag * imag < initialEnergy * .8, "unforced modal state did not lose energy")
    }

    @Test
    fun `paired complex convolution agrees with old scalar house FFT channels`() {
        val source = DoubleArray(19001) { i -> .4 * sin(i * .017) + .19 * cos(i * .071) }
        val kernels = List(3) { channel -> DoubleArray(257) { i ->
            exp(-i / (23.0 + channel * 17)) * sin((i + 1) * (.13 + channel * .07)) / 20
        } }
        val (paired, scalar) = CausticSustain.convolutionProbe(source, kernels)
        for (channel in kernels.indices) {
            val difference = relative(scalar[channel], paired[channel])
            println("CAUSTIC paired/scalar FFT channel$channel relative=$difference")
            assertTrue(difference < 2e-6, "paired reception changes channel$channel: $difference")
        }
    }

    @Test
    fun `analytic metal reception matches actual full source Float FFT routing`() {
        for (voice in listOf(CausticVoice.ALLOY, CausticVoice.TRAVELLING_CATCHES))
            for (held in listOf(false, true)) {
                val m = macros(voice, "HOLD" to if (held) 1f else 0f, "MOTION" to .7f)
                val proof = CausticSustain.metalForceProbe(voice, m, 60, .75, held)
                for (i in 0..1) {
                    val difference = relative(proof.fft[i], proof.analytic[i])
                    println("CAUSTIC analytic route $voice held=$held port=$i relative=$difference")
                    assertTrue(difference < 2e-5, "$voice held=$held port$i source-time routing differs: $difference")
                    if (held) assertTrue(relative(proof.fftOnset[i], proof.analyticOnset[i]) < 2e-5,
                        "$voice actual onset differs from full FFT reference")
                }
            }
    }

    @Test
    fun `all four held voices have real onset prior and continuation at bass middle and treble`() {
        for (voice in voices) for (midi in listOf(36, 60, 84)) {
            val held = CausticSustain.held(voice, macros(voice), midi, .75)
            val d = held.diagnostics
            val n = held.loopFrames * 4; val start = held.loopStartFrame * 4
            assertTrue(start >= n && held.playedEndFrame == held.loopStartFrame + held.loopFrames)
            assertTrue(held.internal.size >= (held.playedEndFrame + 2 * held.loopFrames + 128) * 4,
                "$voice MIDI$midi lacks real filter/continuation context")
            assertTrue(held.internal.all { it.isFinite() && abs(it) < 8f })
            // Float FFT convolution has a numerical floor before causal support.
            // This bound is below a 24-bit step; the state and arrival checks
            // below independently distinguish a real onset from copied sustain.
            assertTrue(abs(held.internal.first()) < 1e-9f, "$voice has pre-onset excitation")
            assertTrue(d.stateResidual < 1e-4 && d.periodicStateResidual < 1e-8,
                "$voice MIDI$midi modal closure ${d.stateResidual}/${d.periodicStateResidual}")
            assertTrue(d.rawSeamError < 1e-5 && d.continuationError < 1e-5,
                "$voice MIDI$midi real cycle mismatch ${d.rawSeamError}/${d.continuationError}")
            val onsetDifference = relative(held.internal.copyOfRange(0, n), held.internal.copyOfRange(start, start + n))
            assertTrue(onsetDifference > 1e-6, "$voice onset is a copied sustain cycle")
            val finished = decimate(held.internal)
            val body = finished.copyOfRange(held.loopStartFrame, held.playedEndFrame)
            val pitch = assertNotNull(Pitch.detect(Snip(body, 1, rate), 0f, minOf(.5f, body.size.toFloat() / rate)),
                "$voice MIDI$midi held pitch missing")
            val requested = 440 * 2.0.pow((midi - 69) / 12.0)
            val cents = 1200 * ln(pitch.hz / requested) / ln(2.0)
            println("CAUSTIC HOLD $voice MIDI$midi pitch=$cents confidence=${pitch.confidence} prefix=${d.prefixCycles} raw=${d.rawPeak} state=${d.stateResidual}")
            assertTrue(abs(cents) < 20 && pitch.confidence >= .3, "$voice MIDI$midi pitch=$cents confidence=${pitch.confidence}")
            assertTrue(d.firL1Bound < 2, "$voice reception FIR lost its fixed force budget: ${d.firL1Bound}")
            assertTrue(CausticSustain.receptionCacheBytes() <= 8L * 1024 * 1024)
        }
    }

    @Test
    fun `held moving geometry is deterministic and moves path delay and collection before the body`() {
        for (voice in voices) {
            val m = macros(voice, "MOTION" to .5f)
            val a = CausticSustain.held(voice, m, 60, .75)
            val b = CausticSustain.held(voice, m, 60, .75)
            assertContentEquals(a.internal, b.internal, "$voice moving held determinism")
            assertTrue(a.diagnostics.sourceMax - a.diagnostics.sourceMin > .05)
            assertTrue(a.diagnostics.focusMax - a.diagnostics.focusMin > .1)
            assertTrue(a.diagnostics.collectionMax - a.diagnostics.collectionMin > 1e-7)
            assertTrue(a.diagnostics.maxDelayR in 0.0..4.0 && a.diagnostics.geometrySnapshots > 1)
            assertTrue(a.diagnostics.continuationError < 1e-5 && a.diagnostics.stateResidual < 1e-4)
        }
    }

    @Test
    fun `finite motion zero is byte preserving and epsilon does not replace the finite voice`() {
        for (voice in voices) {
            val m = macros(voice, "HOLD" to 0f)
            val old = CausticInstrument.probe(voice, m, model = 1, seconds = .6, normalize = false).internalRaw
            val zero = old.copyOf()
            CausticSustain.applyMotion(voice, m, 60, .75, zero)
            assertContentEquals(old, zero, "$voice MOTION0 changed legacy arithmetic")
            val near = old.copyOf()
            CausticSustain.applyMotion(voice, m + ("MOTION" to 1e-4f), 60, .75, near)
            val distance = relative(old, near)
            println("CAUSTIC finite $voice MOTIONepsilon relative=$distance")
            assertTrue(distance < .005, "$voice discontinuous finite renderer switch at MOTIONepsilon: $distance")
        }
    }

    @Test
    fun `low and middle finite motion change matched main note shapes and interact with body`() {
        for (voice in voices) {
            val m = macros(voice, "HOLD" to 0f)
            val old = CausticInstrument.probe(voice, m, model = 1, seconds = .6, normalize = false).internalRaw
            val audio = listOf(0f, .2f, .5f, 1f).map { amount ->
                val x = old.copyOf()
                CausticSustain.applyMotion(voice, m + ("MOTION" to amount), 60, .75, x)
                decimate(x)
            }
            for (i in 0 until audio.lastIndex) {
                val spectral = spectralDistance(audio[i], audio[i + 1])
                val temporal = envelopeResidual(audio[i], audio[i + 1])
                println("CAUSTIC finite $voice MOTION ${listOf(0f,.2f,.5f,1f)[i]}..${listOf(0f,.2f,.5f,1f)[i+1]} spectralL1=$spectral envelope=$temporal")
                assertTrue(spectral >= .03 || temporal >= .03, "$voice has a dead finite MOTION interval: $spectral/$temporal")
            }
            val profiles = listOf(0f, 1f).map { body -> listOf(0f, .8f).map { amount ->
                val map = m + mapOf("BODY" to body, "MOTION" to amount)
                val x = CausticInstrument.probe(voice, map, model = 1, seconds = .6, normalize = false).internalRaw
                CausticSustain.applyMotion(voice, map, 60, .75, x)
                spectrum(decimate(x))
            } }
            val interaction = profiles[0][0].indices.sumOf { i ->
                abs(profiles[1][1][i] - profiles[1][0][i] - profiles[0][1][i] + profiles[0][0][i])
            }
            println("CAUSTIC finite $voice MOTION x BODY spectral interaction=$interaction")
            assertTrue(interaction > .01, "$voice BODY does not affect moving geometry reception")
        }
    }

    @Test
    fun `zero effort stays silent while geometry cache is bounded and cancellation is prompt`() {
        CausticSustain.clearGeometryCache()
        for (voice in voices) {
            val p = CausticSustain.held(voice, macros(voice, "MOTION" to 1f), 60, 0.0)
            assertTrue(p.internal.all { it == 0f }, "$voice zero effort injected source or modal energy")
            val input = FloatArray(4410)
            CausticSustain.applyMotion(voice, macros(voice, "HOLD" to 0f, "MOTION" to 1f), 60, 0.0, input)
            assertTrue(input.all { it == 0f })
        }
        for (i in 0..9) CausticSustain.held(CausticVoice.ALLOY,
            macros(CausticVoice.ALLOY, "SOURCE" to i / 10f, "MOTION" to .5f), 60, 0.0)
        assertTrue(CausticSustain.geometryCacheSize() <= 6)
        var checks = 0
        assertFailsWith<CancellationException> {
            CausticSustain.held(CausticVoice.RUBBED_RING, macros(CausticVoice.RUBBED_RING), 36, .75,
                cancelled = { ++checks >= 3 })
        }
        assertEquals(3, checks)
        Thread.currentThread().interrupt()
        try { assertFailsWith<CancellationException> { CausticSustain.held(CausticVoice.ALLOY, macros(CausticVoice.ALLOY), 60, .75) } }
        finally { Thread.interrupted() }
    }

    private fun decimate(raw: FloatArray): FloatArray {
        val copy = raw.copyOf(); Tide.bandLimit(copy, rate * 4)
        return Dsp.decimate(copy, rate)
    }
    private fun relative(a: FloatArray, b: FloatArray): Double = sqrt(a.indices.sumOf {
        val d = a[it] - b[it].toDouble(); d * d
    } / a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
    private fun relative(a: DoubleArray, b: DoubleArray): Double = sqrt(a.indices.sumOf {
        (a[it] - b[it]).pow(2)
    } / a.sumOf { it * it }.coerceAtLeast(1e-30))
    private fun spectrum(x: FloatArray): DoubleArray {
        val n = 4096; val out = DoubleArray(56)
        for (start in (.015 * rate).roundToInt()..minOf(x.size - n, (.55 * rate).roundToInt() - n) step 1024) {
            val re = FloatArray(n) { i -> (x[start + i] * (.5 - .5 * cos(2 * PI * i / n))).toFloat() }
            val im = FloatArray(n); Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val hz = bin * rate.toDouble() / n
                if (hz < 40) continue
                val band = (ln(hz / 40) / ln(2.0) * 6).toInt().coerceIn(out.indices)
                out[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
        }
        val total = out.sum().coerceAtLeast(1e-30)
        return DoubleArray(out.size) { out[it] / total }
    }
    private fun spectralDistance(a: FloatArray, b: FloatArray): Double {
        val x = spectrum(a); val y = spectrum(b)
        return x.indices.sumOf { abs(x[it] - y[it]) }
    }
    private fun envelopeResidual(a: FloatArray, b: FloatArray): Double {
        fun envelope(x: FloatArray) = DoubleArray(22) { i ->
            val start = ((.015 + i * .025) * rate).roundToInt(); val end = minOf(x.size, start + (rate * .025).roundToInt())
            sqrt((start until end).sumOf { x[it].toDouble() * x[it] } / (end - start).coerceAtLeast(1))
        }
        val x = envelope(a); val y = envelope(b)
        val gain = x.indices.sumOf { x[it] * y[it] } / x.sumOf { it * it }.coerceAtLeast(1e-30)
        return sqrt(x.indices.sumOf { (y[it] - gain * x[it]).pow(2) } / y.sumOf { it * it }.coerceAtLeast(1e-30))
    }
}
