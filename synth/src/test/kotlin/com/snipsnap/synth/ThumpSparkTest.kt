package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Finite-hit sound, numerical and replay contracts for the provisional SPARK voice. */
class ThumpSparkTest {
    private val voice = ThumpVoice.SPARK

    private fun macros(vararg changes: Pair<String, Float>) = Thump.defaults(voice) + changes.toMap()
    private fun render(vararg changes: Pair<String, Float>) = Thump.render(voice, macros(*changes))

    @Test
    fun `the first voice exposes five useful controls and no deferred motion knob`() {
        assertEquals(mapOf("TUNE" to .5f, "DECAY" to .35f, "PUNCH" to .5f,
            "METAL" to .25f, "COLOR" to .5f), Thump.defaults(voice))
        assertContentEquals(render().samples, Thump.render(voice).samples)
    }

    @Test
    fun `all thirty two corners are finite bounded mono hits with decaying raw energy`() {
        val names = Thump.macrosFor(voice).map { it.name }
        var largestRaw = 0f
        var largestDc = 0.0
        for (bits in 0 until (1 shl names.size)) {
            val m = names.mapIndexed { index, name ->
                name to if (bits and (1 shl index) == 0) 0f else 1f
            }.toMap()
            val raw = Thump.spark(m, Dsp.RATE * Dsp.OVERSAMPLE)
            val rawPeak = raw.maxOf { abs(it) }
            largestRaw = maxOf(largestRaw, rawPeak)
            assertTrue(raw.all { it.isFinite() } && rawPeak <= 1.5f, "$m raw peak=$rawPeak")
            // The comparison windows are independent. A whole-render maximum
            // would make an energy-growth check incapable of detecting a fault.
            val attack = rms(raw, 0, raw.size / 4)
            val tail = rms(raw, raw.size * 3 / 4, raw.size)
            assertTrue(tail < attack * .05, "$m tail stopped decaying: attack=$attack tail=$tail")

            val snip = Thump.render(voice, m)
            assertEquals(1, snip.channels)
            assertEquals(44_100, snip.sampleRate)
            assertTrue(snip.durationSeconds in .05f..1.45f, "$m length=${snip.durationSeconds}")
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$m unsafe output")
            assertTrue(snip.peak() > .1f, "$m lost its hit")
            val dc = abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size)
            largestDc = maxOf(largestDc, dc)
            assertTrue(dc < .005, "$m DC=$dc")
            assertEquals(0f, abs(snip.samples.last()), "delivery tail did not reach zero")
        }
        println("SPARK corners: rawPeak=$largestRaw maxAbsDc=$largestDc")
    }

    @Test
    fun `bad direct macro values fall back or clamp without changing unrelated controls`() {
        val default = Thump.render(voice).samples
        assertContentEquals(default, Thump.render(voice, mapOf("MOVE" to 1f)).samples,
            "the deferred motion control must not sneak in as an unsaved parameter")
        for (name in Thump.defaults(voice).keys) {
            for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                assertContentEquals(default, Thump.render(voice, mapOf(name to invalid)).samples,
                    "$name=$invalid did not use its default")
            }
            assertContentEquals(Thump.render(voice, mapOf(name to 0f)).samples,
                Thump.render(voice, mapOf(name to -2f)).samples, "$name low clamp")
            assertContentEquals(Thump.render(voice, mapOf(name to 1f)).samples,
                Thump.render(voice, mapOf(name to 2f)).samples, "$name high clamp")
        }
    }

    @Test
    fun `decay lengthens delivered energy while tune lands on the requested root`() {
        val short = render("DECAY" to 0f)
        val long = render("DECAY" to 1f)
        assertTrue(long.frameCount > short.frameCount * 5, "DECAY did not change the hit duration")
        val shortDecay = FeatureExtractor.extract(short).decayMs
        val longDecay = FeatureExtractor.extract(long).decayMs
        assertTrue(longDecay > shortDecay * 3, "DECAY changed padding instead of energy: $shortDecay -> $longDecay")
        for (tune in listOf(0f, .5f, 1f)) for (metal in listOf(0f, .5f, 1f)) {
            val note = render("TUNE" to tune, "DECAY" to 1f, "METAL" to metal, "PUNCH" to 0f)
            val detected = assertNotNull(Pitch.detect(note, fromSec = .20f, windowSec = .25f),
                "TUNE=$tune METAL=$metal lost a measurable body root")
            val expected = 110.0 * 4.0.pow(tune.toDouble())
            val cents = 1200.0 * ln(detected.hz / expected) / ln(2.0)
            println("SPARK root TUNE=$tune METAL=$metal: ${detected.hz}Hz cents=$cents confidence=${detected.confidence}")
            assertTrue(abs(cents) < 25.0, "TUNE=$tune METAL=$metal root moved by $cents cents")
            assertTrue(detected.confidence >= .3f, "root is too ambiguous: $detected")
        }
    }

    @Test
    fun `metal adds post attack sidebands across low and middle settings`() {
        for (tune in listOf(.25f, .75f)) {
            val root = (110.0 * 4.0.pow(tune.toDouble())).toFloat()
            val fractions = listOf(0f, .25f, .5f, .75f).map { metal ->
                highFraction(render("TUNE" to tune, "METAL" to metal,
                    "COLOR" to .65f, "PUNCH" to 0f), root * 1.8f)
            }
            println("SPARK METAL TUNE=$tune postattack high fractions=$fractions")
            assertTrue(fractions[1] > fractions[0] + .002, "low METAL is spectrally inert: $fractions")
            assertTrue(fractions[2] > fractions[1] + .002, "middle METAL is spectrally inert: $fractions")
            assertTrue(fractions[3] > fractions[2] + .002, "upper-middle METAL is spectrally inert: $fractions")
        }
    }

    @Test
    fun `color moves the spectrum and changes the authority of metallic sidebands`() {
        val colors = listOf(.25f, .5f, .75f)
        val sounds = colors.map { color ->
            render("METAL" to .65f, "COLOR" to color, "PUNCH" to 0f)
        }
        val centroids = sounds.map { FeatureExtractor.extract(it).centroidHz }
        val spectra = sounds.map { postAttackSpectrum(it) }
        val upperFractions = spectra.map { upperPower(it, 396f) }
        val upperGrowthDb = upperFractions.zipWithNext().map { (a, b) -> 10.0 * ln(b / a) / ln(10.0) }
        val spectralDistances = spectra.zipWithNext().map { (a, b) ->
            sqrt(a.indices.sumOf { i ->
                val delta = sqrt(a[i]) - sqrt(b[i])
                delta * delta
            })
        }
        println("SPARK COLOR centroids=$centroids")
        println("SPARK COLOR upperPower=$upperFractions growthDb=$upperGrowthDb normalizedSpectrumDistance=$spectralDistances")
        // The root-bearing body dominates whole-hit centroid. Judge COLOR's
        // filter/edge behavior by the post-attack spectrum too: these are
        // numerical shape checks, not a substitute for the owner's listen.
        assertTrue(centroids.zipWithNext().all { (a, b) -> b > a + 1f },
            "COLOR lost its ordered spectral movement: $centroids")
        assertTrue(upperGrowthDb.all { it >= 3.0 },
            "COLOR did not change upper-partial authority between quarter steps: $upperGrowthDb dB")
        assertTrue(spectralDistances.all { it > .15 },
            "COLOR produced too little normalized spectral change: $spectralDistances")
        fun growth(color: Float): Double {
            val low = highFraction(render("METAL" to .2f, "COLOR" to color, "PUNCH" to 0f), 396f)
            val high = highFraction(render("METAL" to .8f, "COLOR" to color, "PUNCH" to 0f), 396f)
            return high - low
        }
        val darkGrowth = growth(.2f)
        val brightGrowth = growth(.8f)
        println("SPARK METAL x COLOR sideband growth: dark=$darkGrowth bright=$brightGrowth")
        assertTrue(abs(brightGrowth - darkGrowth) > .005,
            "METAL and COLOR did not interact in the delivered spectrum")
    }

    @Test
    fun `the four times delivery stays close to a separately downsampled eight times reference`() {
        // This is a rate-convergence residual, not a pure alias-power bound:
        // it also includes integration, filter phase and resampling differences.
        for (tune in listOf(0f, .5f, 1f)) {
            val m = macros("TUNE" to tune, "METAL" to 1f, "COLOR" to 1f, "PUNCH" to 0f)
            val actual = Thump.render(voice, m).samples
            val referenceRate = Dsp.RATE * 8
            val raw = Thump.spark(m, referenceRate)
            // Match the delivery's physical bandwidth and DC treatment;
            // leaving out its high-pass would measure filter phase instead
            // of convergence with a finer synthesis time step.
            Tide.bandLimit(raw, referenceRate)
            val mean = raw.sumOf { it.toDouble() } / raw.size
            val dc = Dsp.FixedOnePole(20f, referenceRate)
            for (i in raw.indices) {
                val centered = (raw[i] - mean).toFloat()
                raw[i] = centered - dc.lp(centered)
            }
            Dsp.normalize(raw)
            var reference = Snip(raw, 1, referenceRate)
            while (reference.sampleRate > Dsp.RATE) {
                reference = Resampler.resample(reference, reference.sampleRate / 2)
            }
            val expected = reference.samples
            // PUNCH 0 skips nonlinear shaping and loudness matching, but its
            // shared finish still restores the post-decimation peak target.
            Dsp.normalize(expected)
            Dsp.limitPeak(expected)
            Dsp.fadeTail(expected)
            val count = minOf(actual.size, expected.size)
            val from = (Dsp.RATE * .01f).toInt()
            val until = count - (Dsp.RATE * .01f).toInt()
            var error = 0.0
            var energy = 0.0
            for (i in from until until) {
                val delta = actual[i] - expected[i]
                error += delta.toDouble() * delta
                energy += expected[i].toDouble() * expected[i]
            }
            val residual = sqrt(error / energy)
            println("SPARK 4x/8x residual TUNE=$tune: $residual")
            assertTrue(residual < .04, "TUNE=$tune render-rate residual grew to $residual")
        }
    }

    private fun rms(samples: FloatArray, from: Int, until: Int): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun highFraction(snip: Snip, boundaryHz: Float): Double =
        upperPower(postAttackSpectrum(snip), boundaryHz)

    private fun upperPower(spectrum: DoubleArray, boundaryHz: Float): Double =
        spectrum.indices.filter { it.toDouble() * Dsp.RATE / 8192 > boundaryHz }.sumOf { spectrum[it] }

    /** Window after the attack so an onset-only change cannot pass as body color. */
    private fun postAttackSpectrum(snip: Snip): DoubleArray {
        val from = (snip.sampleRate * .006f).toInt()
        val count = minOf((snip.sampleRate * .09f).toInt(), snip.samples.size - from)
        val re = FloatArray(8192)
        val im = FloatArray(re.size)
        for (i in 0 until count) {
            re[i] = (snip.samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        }
        Fft.forward(re, im)
        val spectrum = DoubleArray(re.size / 2)
        var total = 0.0
        for (i in 1 until re.size / 2) {
            val power = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            total += power
            spectrum[i] = power
        }
        assertTrue(total > 1e-12, "a silent window cannot establish spectral movement")
        for (i in spectrum.indices) spectrum[i] /= total
        return spectrum
    }
}
