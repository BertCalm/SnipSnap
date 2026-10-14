package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Raw bounds and gain-independent spectra verify the dry engine before shared leveling. */
class PrismTest {
    private val timbral = listOf("GRAIN", "DRIFT", "SHAPE", "STRIKE", "BODY")
    private val controls = listOf("TUNE") + timbral + "HOLD"
    private val internalRate = Dsp.RATE * Dsp.OVERSAMPLE

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        val voice = PrismVoice.KEY
        for ((label, render) in listOf<Pair<String, () -> Unit>>(
            "finite" to { Prism.render(voice) },
            "held" to { Prism.render(voice, Prism.defaults(voice) + ("HOLD" to 1f)) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException>(
                    "PRISM $label render did not stop on the interrupt at its first look in the synthesis loop",
                ) { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertFailsWith<java.util.concurrent.CancellationException>(
            "PRISM held render did not stop on the export cancel callback at its first look in the synthesis loop",
        ) {
            Prism.renderLoop(voice, cancelled = { true })
        }
        assertTrue(Prism.render(voice).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0

    private data class Spectrum(val power: DoubleArray, val binHz: Double)

    private fun spectrum(samples: FloatArray, rate: Int = Dsp.RATE,
        fromSeconds: Double = .08, seconds: Double = .25): Spectrum {
        val first = (fromSeconds * rate).roundToInt().coerceIn(0, samples.size)
        val count = minOf((seconds * rate).roundToInt(), samples.size - first)
        require(count > 256) { "a spectral probe needs a real body window" }
        var n = 65536
        while (n < count) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = samples[first + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        return Spectrum(DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] },
            rate.toDouble() / n)
    }

    /** Nearest-harmonic power buckets follow register and include inharmonic glass modes. */
    private fun spectralMass(samples: FloatArray, rootHz: Double,
        fromSeconds: Double = .08, seconds: Double = .25): DoubleArray {
        val s = spectrum(samples, fromSeconds = fromSeconds, seconds = seconds)
        val mass = DoubleArray(32)
        for (k in 1 until s.power.size) {
            val relative = k * s.binHz / rootHz
            val bucket = floor(relative + .5).toInt() - 1
            if (bucket in mass.indices) mass[bucket] += s.power[k]
        }
        val total = mass.sum()
        require(total > 1e-20) { "spectral comparison needs audible partials" }
        return DoubleArray(mass.size) { mass[it] / total }
    }

    /** Normalised time-bin power detects contour changes independently of final level. */
    private fun temporalMass(samples: FloatArray): DoubleArray {
        val count = minOf(samples.size, (Dsp.RATE * .7).roundToInt())
        val mass = DoubleArray(35)
        for (i in 0 until count) mass[minOf(34, i * 35 / count)] += samples[i].toDouble() * samples[i]
        val total = mass.sum()
        require(total > 1e-20) { "temporal comparison needs audible excitation" }
        return DoubleArray(mass.size) { mass[it] / total }
    }

    /** L1 distance .04 requires at least 2% of power to move between audible bins. */
    private fun massDistance(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) }

    private fun inharmonicFraction(spectrum: Spectrum, rootHz: Double): Double {
        var total = 0.0
        var inharmonic = 0.0
        val harmonicWidth = maxOf(12.0, rootHz * .065)
        for (k in 1 until spectrum.power.size) {
            val hz = k * spectrum.binHz
            if (hz < rootHz * .5) continue
            total += spectrum.power[k]
            val nearest = (hz / rootHz).roundToInt().coerceAtLeast(1) * rootHz
            if (abs(hz - nearest) > harmonicWidth) inharmonic += spectrum.power[k]
        }
        return inharmonic / total
    }

    private fun highFrequencyFraction(spectrum: Spectrum, aboveHz: Double): Double {
        val total = spectrum.power.drop(1).sum()
        return spectrum.power.indices.filter { it * spectrum.binHz > aboveHz }.sumOf { spectrum.power[it] } / total
    }

    @Test
    fun `factory glass lowers dissonant energy and high frequency bite across register and velocity`() {
        // This is the exact R1 factory model: its unchanged empty BELL recipe preserves its seed,
        // ratios, amplitudes and contours. R2 is compared through actual conditioned, unlevelled audio.
        val firstAudition = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING)
        val softerGlass = Prism.templateFor(PrismVoice.GLASS)
        for (tune in listOf(0f, .5f, 1f)) for (velocity in listOf(.35f, .9f)) {
            val macros = mapOf("TUNE" to tune)
            val before = Prism.probe(PrismVoice.GLASS, macros, velocity, firstAudition, seconds = .8f)
            val after = Prism.probe(PrismVoice.GLASS, macros, velocity, softerGlass, seconds = .8f)
            val beforeSpectrum = spectrum(before.samples, fromSeconds = .12, seconds = .4)
            val afterSpectrum = spectrum(after.samples, fromSeconds = .12, seconds = .4)
            val dissonanceBefore = inharmonicFraction(beforeSpectrum, before.effectiveHz)
            val dissonanceAfter = inharmonicFraction(afterSpectrum, after.effectiveHz)
            val biteBefore = highFrequencyFraction(beforeSpectrum, 4_000.0)
            val biteAfter = highFrequencyFraction(afterSpectrum, 4_000.0)
            val attackBiteBefore = highFrequencyFraction(spectrum(before.samples, fromSeconds = .01, seconds = .12), 4_000.0)
            val attackBiteAfter = highFrequencyFraction(spectrum(after.samples, fromSeconds = .01, seconds = .12), 4_000.0)
            // Fractions of measured power remove gain, so a quieter render alone cannot pass.
            assertTrue(dissonanceAfter < dissonanceBefore * .35,
                "GLASS tune $tune velocity $velocity retains too much inharmonic power: $dissonanceBefore -> $dissonanceAfter")
            assertTrue(biteAfter < biteBefore * .80,
                "GLASS tune $tune velocity $velocity retains high-frequency bite: $biteBefore -> $biteAfter")
            assertTrue(attackBiteAfter < attackBiteBefore * .85,
                "GLASS tune $tune velocity $velocity retains a harsh attack: $attackBiteBefore -> $attackBiteAfter")
            fun partialDissonance(p: Prism.Probe): Double {
                val energy = p.partialWeights.sumOf { it.toDouble() * it }
                return p.partialHz.indices.filter { abs(p.partialHz[it] / p.effectiveHz -
                    (p.partialHz[it] / p.effectiveHz).roundToInt()) > .065 }
                    .sumOf { p.partialWeights[it].toDouble() * p.partialWeights[it] } / energy
            }
            assertTrue(partialDissonance(after) < partialDissonance(before) * .35,
                "GLASS audio measurement is not backed by a lower inharmonic partial-energy budget")
            assertHealthy("softer GLASS tune $tune velocity $velocity", after)
            println("PRISM GLASS tune $tune velocity $velocity: inharmonic fraction $dissonanceBefore -> $dissonanceAfter; >4kHz fraction $biteBefore -> $biteAfter; attack >4kHz fraction $attackBiteBefore -> $attackBiteAfter")
        }
    }

    private fun relativeCycleError(previous: FloatArray, current: FloatArray): Double {
        assertEquals(previous.size, current.size, "the preceding cycle must be complete")
        var difference = 0.0
        var energy = 0.0
        for (i in current.indices) {
            val d = current[i] - previous[i].toDouble()
            difference += d * d
            energy += previous[i].toDouble() * previous[i]
        }
        return difference / energy.coerceAtLeast(1e-30)
    }

    private fun assertHealthy(label: String, p: Prism.Probe, normalized: Boolean = false) {
        assertTrue(p.samples.isNotEmpty() && p.raw.isNotEmpty(), "$label empty render")
        assertTrue(p.samples.all { it.isFinite() }, "$label non-finite output")
        assertTrue(p.raw.all { it.isFinite() }, "$label non-finite additive sum")
        assertTrue(peak(p.raw) <= 1.0, "$label raw partial sum exceeds headroom: ${peak(p.raw)}")
        assertTrue(rms(p.samples) > 1e-6, "$label silent pitched body")
        assertTrue(abs(p.samples.average()) < maxOf(.001, rms(p.samples) * .03), "$label DC offset")
        assertTrue(p.partialHz.size in 1..32, "$label exceeds initial partial budget")
        assertEquals(p.partialHz.size, p.partialWeights.size)
        assertEquals(p.partialHz.size, p.groupIndices.size)
        assertTrue(p.partialHz.all { it.isFinite() && it > 0 && it < Dsp.RATE / 2.0 }, "$label out-of-band partial")
        assertTrue(p.partialWeights.all { it.isFinite() && it >= 0f }, "$label invalid spectral weights")
        // A group outside 0 until GROUPS cannot reach this check (synthesis indexes its five group
        // envelopes with it and would fail first), so a range check here could never fail. Check the
        // partition instead: Prism files partial indices 0-1, 2-4, 5-9, 10-17 and 18-31 under groups
        // 0..4 and the bank keeps the partials in index order (the factory ratios rise with the index),
        // dropping only those above its bandwidth. So the n partials kept are the first n, and each
        // group holds exactly its share of them: [2, 3, 5, 8, 14] when all 32 are kept.
        val bands = intArrayOf(2, 3, 5, 8, 14)
        assertEquals(Prism.PARTIALS, bands.sum())
        assertEquals(Prism.GROUPS, bands.size)
        var first = 0
        val expected = IntArray(Prism.GROUPS) { g -> (p.groupIndices.size - first).coerceIn(0, bands[g]).also { first += bands[g] } }
        val counts = IntArray(Prism.GROUPS) { g -> p.groupIndices.count { it == g } }
        assertContentEquals(expected, counts, "$label spectral groups ${counts.toList()} for ${p.groupIndices.size} partials, expected ${expected.toList()}")
        assertEquals(p.groupIndices.sorted(), p.groupIndices.toList(), "$label spectral groups out of partial order")
        if (normalized) {
            assertTrue(peak(p.samples) <= .99001, "$label export clips")
            val loudness = Loudness.of(Snip(p.samples, channels = 1, sampleRate = Dsp.RATE))
            assertTrue(loudness >= Dsp.MELODIC_LOUDNESS_TARGET * .9f || peak(p.samples) >= .95,
                "$label fails shared loudness: $loudness")
        }
    }

    @Test
    fun `six voices expose the five timbral macros pitch and finite hold defaults`() {
        assertEquals(listOf("KEY", "PLUCK", "ORGAN", "BASS", "GLASS", "VEIL"), PrismVoice.entries.map { it.name })
        assertEquals(4, Dsp.OVERSAMPLE)
        assertEquals(44100, Dsp.RATE)
        for (voice in PrismVoice.entries) {
            assertEquals(controls, Prism.macrosFor(voice).map { it.name }, "$voice macro contract")
            assertEquals(0f, Prism.defaults(voice).getValue("HOLD"))
            assertEquals(DrumClass.TONAL, Prism.drumClassFor(voice))
            assertEquals(DrumClass.LOOP, Prism.drumClassFor(voice, mapOf("HOLD" to 1f)))
            val pitches = (0..100).map { Prism.midiFor(voice, it / 100f) }.toSet()
            assertEquals(Prism.TUNE_SEMITONES + 1, pitches.size, "$voice tune must snap to semitones")
            assertEquals(Prism.rootMidi(voice), pitches.min())
            assertEquals(Prism.rootMidi(voice) + Prism.TUNE_SEMITONES, pitches.max())
        }
    }

    @Test
    fun `fresh renders reproduce the same partial phases and samples`() {
        for (voice in PrismVoice.entries) {
            val macros = mapOf("GRAIN" to .73f, "DRIFT" to .62f, "STRIKE" to .57f)
            val a = Prism.probe(voice, macros, velocity = .7f, seconds = .75f)
            val b = Prism.probe(voice, macros, velocity = .7f, seconds = .75f)
            assertContentEquals(a.raw, b.raw, "$voice raw deterministic replay")
            assertContentEquals(a.samples, b.samples, "$voice conditioned deterministic replay")
        }
        val macros = mapOf("HOLD" to 1f, "GRAIN" to 1f, "SHAPE" to .86f, "TUNE" to 1f)
        assertContentEquals(Prism.renderLoop(PrismVoice.GLASS, macros),
            Prism.renderLoop(PrismVoice.GLASS, macros), "sparse held glass deterministic replay")
    }

    @Test
    fun `finite notes settle quietly within the render budget before the final fade`() {
        for (voice in PrismVoice.entries) {
            val p = Prism.probe(voice)
            assertHealthy("$voice finite", p)
            val seconds = p.samples.size.toDouble() / Dsp.RATE
            assertTrue(seconds in .5..Prism.MAX_FINITE_SECONDS.toDouble() + .01, "$voice duration $seconds")
            assertEquals(0, p.loopFrames, "$voice default unexpectedly held")
            val lastRaw = rms(p.raw, (p.raw.size - internalRate / 20).coerceAtLeast(0))
            assertTrue(lastRaw <= peak(p.raw) * .001,
                "$voice natural tail is cut while audible: raw last-50ms RMS $lastRaw, peak ${peak(p.raw)}")
            val out = Prism.render(voice)
            assertEquals(1, out.channels)
            assertEquals(Dsp.RATE, out.sampleRate)
            assertTrue(out.samples.last() == 0f, "$voice finite output has no final fade")
            println("PRISM FINITE $voice: ${seconds}s, raw peak ${peak(p.raw)}, raw RMS ${rms(p.raw)}, tail RMS $lastRaw")
        }
    }

    @Test
    fun `all low all high and sparse spectral corners remain bounded before leveling`() {
        for (voice in PrismVoice.entries) for (macros in listOf(
            timbral.associateWith { 0f } + ("TUNE" to 0f),
            timbral.associateWith { 1f } + ("TUNE" to 1f),
            mapOf("GRAIN" to 1f, "BODY" to 0f, "SHAPE" to .5f, "DRIFT" to 1f, "TUNE" to 1f),
        )) {
            val p = Prism.probe(voice, macros, normalize = true, seconds = .75f)
            assertHealthy("$voice $macros", p, normalized = true)
            val root = spectralMass(p.samples, p.effectiveHz)[0]
            assertTrue(root > .005, "$voice $macros erases the root ($root share)")
        }
    }

    @Test
    fun `notes remain on their requested root across registers and spectral extremes`() {
        for (voice in PrismVoice.entries) for (tune in listOf(0f, .5f, 1f)) {
            val macros = mapOf("TUNE" to tune, "GRAIN" to .8f, "SHAPE" to .78f)
            val p = Prism.probe(voice, macros, seconds = .75f)
            val wanted = Keys.midiHz(Prism.midiFor(voice, tune))
            val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, fromSec = .08f, bodySeconds = .5f)
            val cents = FineTuning.cents(measured, wanted.toDouble())
            assertTrue(abs(cents) < 1.0, "$voice TUNE $tune root detuned $cents cents")
            assertTrue(spectralMass(p.samples, wanted.toDouble())[0] > .02,
                "$voice TUNE $tune preserves only an inaudible tuning reference")
        }
    }

    @Test
    fun `high register partials are omitted rather than aliasing during decimation`() {
        for (voice in PrismVoice.entries) {
            val p = Prism.probe(voice, mapOf("TUNE" to 1f, "GRAIN" to 1f, "SHAPE" to 1f,
                "STRIKE" to 1f, "BODY" to 1f), seconds = .75f)
            assertTrue(p.partialHz.all { it < Dsp.RATE / 2.0 })
            val s = spectrum(p.raw, rate = internalRate, fromSeconds = .25, seconds = .25)
            val total = s.power.sum()
            val ultrasonic = s.power.indices.filter { it * s.binHz >= Dsp.RATE / 2.0 }.sumOf { s.power[it] }
            assertTrue(ultrasonic / total < 2e-5,
                "$voice raw out-of-band fraction ${ultrasonic / total} would fold during export")
        }
    }

    @Test
    fun `every timbral macro moves audible spectrum or timing independently of level`() {
        for (voice in PrismVoice.entries) for (macro in timbral) {
            val low = Prism.probe(voice, mapOf(macro to 0f), seconds = .75f)
            val high = Prism.probe(voice, mapOf(macro to 1f), seconds = .75f)
            val spectrumDistance = massDistance(spectralMass(low.samples, low.effectiveHz),
                spectralMass(high.samples, high.effectiveHz))
            val timingDistance = massDistance(temporalMass(low.samples), temporalMass(high.samples))
            assertTrue(maxOf(spectrumDistance, timingDistance) > .04,
                "$voice $macro has no useful change after removing gain: spectrum $spectrumDistance, timing $timingDistance")
            if (macro in listOf("GRAIN", "SHAPE", "BODY")) assertTrue(spectrumDistance > .04,
                "$voice $macro changes timing but not harmonic organization: $spectrumDistance")
        }
    }

    @Test
    fun `grain sweeps bound actual template energy before shared output leveling`() {
        val fixed = mapOf("TUNE" to .5f, "STRIKE" to .7f, "BODY" to .45f, "SHAPE" to .55f, "DRIFT" to 0f)
        for (voice in PrismVoice.entries) {
            val baseline = Prism.probe(voice, fixed + ("GRAIN" to 0f), seconds = .75f)
            val referenceEnergy = baseline.partialWeights.sumOf { it.toDouble() * it }
            val referenceRms = rms(baseline.raw)
            for (grain in listOf(.25f, .5f, .75f, 1f)) {
                val p = Prism.probe(voice, fixed + ("GRAIN" to grain), seconds = .75f)
                val weightEnergy = p.partialWeights.sumOf { it.toDouble() * it }
                val rawRms = rms(p.raw)
                // The mask uses the template's real weights, including its protected root.
                // Raw measurements prevent the shared loudness stage from hiding a gain bug.
                assertTrue(weightEnergy <= referenceEnergy * 1.001,
                    "$voice GRAIN $grain adds aggregate partial energy: $referenceEnergy -> $weightEnergy")
                assertTrue(rawRms <= referenceRms * 1.05,
                    "$voice GRAIN $grain unintentionally adds raw gain: $referenceRms -> $rawRms")
                assertTrue(rawRms >= referenceRms * .4,
                    "$voice GRAIN $grain creates an almost silent region: $referenceRms -> $rawRms")
                assertTrue(peak(p.raw) <= Prism.RAW_PEAK_CEILING + 1e-6,
                    "$voice GRAIN $grain violates coherent-phase headroom")
            }
        }
    }

    @Test
    fun `small drift changes early development and five point shape sweeps stay continuous`() {
        for (voice in PrismVoice.entries) {
            val together = Prism.probe(voice, mapOf("DRIFT" to 0f), seconds = .75f)
            val separating = Prism.probe(voice, mapOf("DRIFT" to .25f), seconds = .75f)
            val temporal = massDistance(temporalMass(together.samples), temporalMass(separating.samples))
            val spectral = massDistance(spectralMass(together.samples, together.effectiveHz, .03, .18),
                spectralMass(separating.samples, separating.effectiveHz, .03, .18))
            assertTrue(maxOf(temporal, spectral) > .005,
                "$voice low DRIFT has no detectable development inside the first note: $temporal, $spectral")
        }
        val positions = listOf(0f, .25f, .5f, .75f, 1f)
        val masses = positions.map { x ->
            val p = Prism.probe(PrismVoice.KEY, mapOf("SHAPE" to x), seconds = .75f)
            spectralMass(p.samples, p.effectiveHz)
        }
        for (i in 1 until masses.size) assertTrue(massDistance(masses[i - 1], masses[i]) > .005,
            "SHAPE has a silent/dead sweep interval ${positions[i - 1]}..${positions[i]}")
        val nearA = Prism.probe(PrismVoice.KEY, mapOf("GRAIN" to .499f), seconds = .75f)
        val nearB = Prism.probe(PrismVoice.KEY, mapOf("GRAIN" to .501f), seconds = .75f)
        assertTrue(massDistance(spectralMass(nearA.samples, nearA.effectiveHz), spectralMass(nearB.samples, nearB.effectiveHz)) < .02,
            "GRAIN activates a partial group abruptly near its centre")
    }

    @Test
    fun `drift separates partial development rather than simply delaying the whole note`() {
        for (voice in listOf(PrismVoice.KEY, PrismVoice.PLUCK, PrismVoice.VEIL)) {
            fun evolution(drift: Float): Double {
                val p = Prism.probe(voice, mapOf("DRIFT" to drift, "STRIKE" to .4f), seconds = 1.4f)
                val early = spectralMass(p.samples, p.effectiveHz, .08, .25)
                val late = spectralMass(p.samples, p.effectiveHz, .8, .25)
                return massDistance(early, late)
            }
            val together = evolution(0f)
            val separated = evolution(1f)
            assertTrue(separated > together + .02,
                "$voice DRIFT fails to separate audible partial lifetimes: $together -> $separated")
        }
    }

    @Test
    fun `velocity scales raw energy and attack brightness and zero velocity is silent`() {
        for (voice in PrismVoice.entries) {
            val soft = Prism.probe(voice, velocity = .25f, seconds = .75f)
            val firm = Prism.probe(voice, velocity = .9f, seconds = .75f)
            assertTrue(rms(firm.raw) > rms(soft.raw) * 1.5, "$voice velocity fails to scale excitation energy")
            val softMass = spectralMass(soft.samples, soft.effectiveHz, .005, .12)
            val firmMass = spectralMass(firm.samples, firm.effectiveHz, .005, .12)
            val softUpper = softMass.drop(3).sum()
            val firmUpper = firmMass.drop(3).sum()
            assertTrue(firmUpper > softUpper * 1.01 + .0001,
                "$voice velocity changes only gain, not early brightness: $softUpper -> $firmUpper")
            for (hold in listOf(0f, 1f)) {
                val silent = Prism.probe(voice, mapOf("HOLD" to hold), velocity = 0f)
                assertTrue(silent.raw.all { it == 0f } && silent.samples.all { it == 0f }, "$voice HOLD $hold zero-velocity note sounds")
            }
        }
    }

    @Test
    fun `held carrier envelope and processing cycles genuinely repeat including sparse high glass`() {
        val cases = PrismVoice.entries.map { it to emptyMap<String, Float>() } + listOf(
            PrismVoice.BASS to mapOf("TUNE" to 0f, "DRIFT" to 0f),
            PrismVoice.GLASS to mapOf("TUNE" to 1f, "GRAIN" to 1f, "SHAPE" to 1f, "BODY" to 0f, "DRIFT" to 1f),
            PrismVoice.VEIL to mapOf("TUNE" to 1f, "GRAIN" to 1f, "SHAPE" to .6f, "DRIFT" to 1f),
        )
        for ((voice, extra) in cases) {
            val macros = extra + ("HOLD" to 1f)
            val p = Prism.probe(voice, macros)
            val previous = requireNotNull(p.previousCycle) { "$voice does not expose a truly preceding held cycle" }
            assertHealthy("$voice HOLD $extra", p)
            assertEquals(p.samples.size, p.loopFrames)
            assertTrue(p.loopFrames > 1280, "$voice held region too short for measured continuity")
            val repeatError = relativeCycleError(previous, p.samples)
            // This observes the complete independently synthesized cycles, not a copied seam window.
            assertTrue(repeatError < 1e-5, "$voice HOLD $extra complete cycle differs: $repeatError")
            val seam = Keys.seamError(previous + p.samples, previous.size)
            assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "$voice HOLD $extra true seam $seam")
            assertEquals(seam, p.seamError, 1e-12, "$voice reported seam is not measured from actual cycles")
            val wanted = Keys.midiHz(Prism.midiFor(voice, extra["TUNE"] ?: Prism.defaults(voice).getValue("TUNE")))
            assertTrue(abs(FineTuning.cents(p.effectiveHz, wanted.toDouble())) < .1,
                "$voice pitch was coarsely quantized to fit HOLD")
            val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, .05f, .5f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 1.0, "$voice audible held root detuned")
            var largestStep = 0.0
            var largestKink = 0.0
            for (i in 1 until p.samples.size) largestStep = maxOf(largestStep, abs(p.samples[i] - p.samples[i - 1]).toDouble())
            for (i in 2 until p.samples.size) largestKink = maxOf(largestKink,
                abs((p.samples[i] - p.samples[i - 1]) - (p.samples[i - 1] - p.samples[i - 2])).toDouble())
            val wrap = (p.samples.first() - p.samples.last()).toDouble()
            val before = (p.samples.last() - p.samples[p.samples.lastIndex - 1]).toDouble()
            val after = (p.samples[1] - p.samples.first()).toDouble()
            assertTrue(abs(wrap) <= largestStep * 1.01 + 1e-7, "$voice held wrap has an exceptional jump")
            assertTrue(maxOf(abs(wrap - before), abs(after - wrap)) <= largestKink * 1.02 + 1e-7,
                "$voice held wrap has an exceptional slope change")
            println("PRISM HOLD $voice $extra: ${p.loopFrames} frames, whole-cycle error $repeatError, seam $seam")
        }
    }

    @Test
    fun `low bass strikes and sparse noisy glass attacks avoid drum choke classes`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_OPEN, DrumClass.HAT_CLOSED, DrumClass.TOM)
        val cases = listOf(
            PrismVoice.BASS to mapOf("TUNE" to 0f, "STRIKE" to 1f, "BODY" to .9f),
            PrismVoice.PLUCK to mapOf("TUNE" to 0f, "STRIKE" to 1f, "BODY" to 0f),
            PrismVoice.GLASS to mapOf("TUNE" to 1f, "GRAIN" to 1f, "STRIKE" to 1f, "BODY" to 0f),
        )
        for ((voice, macros) in cases) {
            assertEquals(DrumClass.TONAL, Prism.drumClassFor(voice, macros))
            val heard = Classifier.classify(Prism.render(voice, macros)).drumClass
            assertTrue(heard !in forbidden, "$voice $macros is heard as a choking drum: $heard")
        }
    }
}
