package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Depth must reach time-varying audio, rather than merely change gain, hiss or static color. */
class SkitterDepthTest {
    @Test
    fun `the sustained bridge changes centered spectral trajectories with and without STATIC`() {
        val ruler = SkitterDepthMeasure.ruler
        assertTrue(ruler.gainPhaseCentered < .001, "depth ruler mistakes gain/carrier phase for motion")
        assertTrue(ruler.staticColorMean > .10, "depth ruler misses a different static body")
        assertTrue(ruler.staticColorCentered < .001, "depth ruler credits a static body change as temporal depth")
        SkitterDepthMeasure.printBodies()
        for (take in SkitterDepthMeasure.bodies) {
            // The mean profile is deliberately reported separately. A permanent EQ change
            // must not satisfy this one-percent temporal-power redistribution requirement.
            assertTrue(take.comparisons.maxOf { it.centered } >= .01,
                "${take.label} changes static color without meaningful body evolution: ${take.comparisons}")
            assertTrue(take.fullShapes.maxOf { it.spread } >= .01,
                "${take.label} removes motion rather than supplying a developing body")
            SkitterDepthMeasure.requireHealthy(take.full, take.label)
            assertEquals(0.0, take.r7.diagnostics.depthStatePeak, "${take.label} bypass still evolves bridge states")
            assertEquals(0.0, take.r7.diagnostics.depthReturnPeak, "${take.label} bypass still returns bridge audio")
        }
    }

    @Test
    fun `filter return and exchange each alter coherent held audio under the same pitch gestures`() {
        SkitterDepthMeasure.printCausal()
        for (take in SkitterDepthMeasure.causal) {
            // Held forcing is predetermined. These ablations must change the circuit's
            // answer, not move the seed's external pitch events to different times.
            assertTrue(take.full.trace.map { it.pitchOffsetA to it.pitchOffsetB } ==
                take.off.trace.map { it.pitchOffsetA to it.pitchOffsetB }, "${take.label} reschedules pitch gestures")
            assertTrue(take.comparisons.maxOf { it.total } > .01,
                "${take.label} never reaches normalized audio color: ${take.comparisons}")
            assertTrue(take.comparisons.maxOf { it.centered } > .005,
                "${take.label} is only a fixed color offset: ${take.comparisons}")
            SkitterDepthMeasure.requireHealthy(take.off, take.label)
        }
    }

    @Test
    fun `all four bridge states remain bounded and close with real held audio and oversampled reference`() {
        for ((voice, render) in SkitterDepthMeasure.held) {
            SkitterDepthMeasure.printHeld(voice, render)
            SkitterDepthMeasure.requireHealthy(render, "$voice HOLD")
            assertTrue(render.diagnostics.loopConverged, "$voice bridge state does not converge")
            assertTrue(render.diagnostics.loopStateError < Skitter.LOOP_STATE_TOLERANCE,
                "$voice full held state vector fails to close")
            assertTrue(Keys.seamError(render.raw, render.loopStart) < Keys.MAX_SEAM_ERROR,
                "$voice independently evolved raw periods do not close")
        }
        for (take in SkitterDepthMeasure.references) {
            SkitterDepthMeasure.printReference(take)
            // Same R6/R7 reference bars. This is one clean stationary C6 condition;
            // intended gesture/noise sidebands cannot inflate its nonharmonic mask.
            assertTrue(take.normal.unwanted <= take.reference.unwanted + 1e-6,
                "${take.voice} adds nonharmonic/folded power above -60dB (${take.excessDb}dB)")
            assertTrue(take.harmonicDistance < .03,
                "${take.voice} changes its harmonic envelope at 4x (${take.harmonicDistance})")
        }
    }
}

/** Root-owned diagnostic main reports measurements before running the full admission suite. */
internal object SkitterDepthMeasure {
    val common = mapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val sizes = listOf(2048, 8192)
    private val root = Keys.midiHz(48).toDouble()
    private val edges = doubleArrayOf(0.0, .85, 1.15, 1.75, 2.5, 3.5, 5.0, 7.0, 10.0, 14.0, 20.0, 32.0, 64.0, Double.POSITIVE_INFINITY)
    private const val hop = 441

    data class Shape(val size: Int, val rows: List<DoubleArray>) {
        val mean = DoubleArray(rows.first().size) { i -> rows.sumOf { it[i] } / rows.size }
        val spread = rows.sumOf { SkitterDepthMeasure.tv(it, mean) } / rows.size
    }
    data class Comparison(val size: Int, val mean: Double, val total: Double, val centered: Double)
    data class Body(val voice: SkitterVoice, val clean: Boolean, val full: SkitterRender, val r7: SkitterRender,
                    val fullShapes: List<Shape>, val comparisons: List<Comparison>) {
        val label get() = "$voice STATIC ${if (clean) 0 else .15}"
    }
    data class Causal(val voice: SkitterVoice, val mechanism: String, val full: SkitterRender, val off: SkitterRender,
                      val comparisons: List<Comparison>) {
        val label get() = "$voice $mechanism"
    }
    data class Ruler(val gainPhaseCentered: Double, val staticColorMean: Double, val staticColorCentered: Double)
    data class ReferenceSpectrum(val unwanted: Double, val harmonics: DoubleArray)
    data class Reference(val voice: SkitterVoice, val full: SkitterRender, val oversampled: SkitterRender,
                         val normal: ReferenceSpectrum, val reference: ReferenceSpectrum) {
        val harmonicDistance get() = SkitterDepthMeasure.tv(normal.harmonics, reference.harmonics)
        val excessDb get() = SkitterDepthMeasure.db((normal.unwanted - reference.unwanted).coerceAtLeast(1e-30))
    }

    val bodies: List<Body> by lazy {
        SkitterVoice.entries.flatMap { voice -> listOf(false, true).map { clean ->
            val macros = if (clean) common + ("STATIC" to 0f) else common
            val full = Skitter.renderProbe(voice, macros, velocity = .6f, seed = 0, seconds = 1.3f)
            val r7 = Skitter.renderProbe(voice, macros, velocity = .6f, seed = 0, seconds = 1.3f,
                probe = SkitterProbe(depthDisabled = true))
            val a = shapes(full.raw, .35, 1.25)
            val b = shapes(r7.raw, .35, 1.25)
            Body(voice, clean, full, r7, a, a.zip(b, ::compare))
        } }
    }
    val held: Map<SkitterVoice, SkitterRender> by lazy {
        SkitterVoice.entries.associateWith { voice -> Skitter.renderProbe(voice,
            common + mapOf("STATIC" to 0f, "HOLD" to 1f), velocity = .6f, seed = 17) }
    }
    val causal: List<Causal> by lazy {
        SkitterVoice.entries.flatMap { voice ->
            val full = held.getValue(voice)
            val fullShapes = heldShapes(full)
            listOf("filterReturn" to SkitterProbe(filterReturnDisabled = true),
                "exchange" to SkitterProbe(depthExchangeDisabled = true)).map { (name, probe) ->
                val off = Skitter.renderProbe(voice, common + mapOf("STATIC" to 0f, "HOLD" to 1f),
                    velocity = .6f, seed = 17, probe = probe)
                check(off.loopStart == full.loopStart) { "$voice $name changes the planned period" }
                Causal(voice, name, full, off, fullShapes.zip(heldShapes(off), ::compare))
            }
        }
    }
    val ruler: Ruler by lazy {
        fun signal(gain: Double, phase: Double, third: Double) = FloatArray((Dsp.RATE * 1.3).toInt()) { i ->
            val t = 2.0 * PI * root * i / Dsp.RATE
            (gain * (sin(t + phase) + third * sin(3 * t - phase * .37))).toFloat()
        }
        val original = shapes(signal(.2, .0, .35), .35, 1.25)
        val phase = original.zip(shapes(signal(-.8, 1.7, .35), .35, 1.25), ::compare)
        val color = original.zip(shapes(signal(.2, .0, .80), .35, 1.25), ::compare)
        Ruler(phase.maxOf { it.centered }, color.minOf { it.mean }, color.maxOf { it.centered })
    }
    val references: List<Reference> by lazy {
        SkitterVoice.entries.map { voice ->
            val macros = common + mapOf("BUZZ" to 1f, "SKITTER" to 0f, "STATIC" to 0f, "HOLD" to 1f)
            val normal = Skitter.renderProbe(voice, macros, midi = 84, oversample = 4)
            val ref = Skitter.renderProbe(voice, macros, midi = 84, oversample = 16)
            check(normal.loopStart == ref.loopStart) { "$voice reference has a different period" }
            val hz = Keys.midiHz(84).toDouble()
            val fitted = (hz * normal.loopStart / Dsp.RATE).roundToInt() * Dsp.RATE.toDouble() / normal.loopStart
            Reference(voice, normal, ref, referenceSpectrum(normal, fitted), referenceSpectrum(ref, fitted))
        }
    }

    private fun tv(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } * .5
    private fun db(power: Double) = 10 * log10(power.coerceAtLeast(1e-30))
    private fun compare(a: Shape, b: Shape): Comparison {
        require(a.size == b.size && a.rows.size == b.rows.size)
        val centered = a.rows.indices.sumOf { t ->
            a.mean.indices.sumOf { i -> abs((a.rows[t][i] - a.mean[i]) - (b.rows[t][i] - b.mean[i])) } * .5
        } / a.rows.size
        return Comparison(a.size, tv(a.mean, b.mean), a.rows.zip(b.rows).sumOf { (x, y) -> tv(x, y) } / a.rows.size, centered)
    }
    private fun heldShapes(render: SkitterRender) = shapes(render.raw.copyOfRange(render.loopStart, render.raw.size),
        .0, (render.raw.size - render.loopStart).toDouble() / Dsp.RATE)

    private fun shapes(audio: FloatArray, fromSeconds: Double, toSeconds: Double): List<Shape> = sizes.map { size ->
        val from = (fromSeconds * Dsp.RATE).roundToInt()
        val end = minOf(audio.size, (toSeconds * Dsp.RATE).roundToInt())
        Shape(size, (from..end - size step hop).map { start ->
            val power = spectrum(audio, start, size)
            val bands = DoubleArray(edges.size - 1)
            for (bin in 1 until power.size) {
                val band = edges.indexOfLast { bin.toDouble() * Dsp.RATE / size / root >= it }
                if (band in bands.indices) bands[band] += power[bin]
            }
            val total = bands.sum()
            require(total > 1e-20 && total.isFinite()) { "depth spectrum is silent or nonfinite" }
            DoubleArray(bands.size) { bands[it] / total }
        })
    }
    private fun spectrum(audio: FloatArray, from: Int, size: Int, bh: Boolean = false): DoubleArray {
        require(from >= 0 && from + size <= audio.size)
        val re = FloatArray(size) { i ->
            val angle = 2 * PI * i / (size - 1)
            val window = if (bh) .35875 - .48829 * cos(angle) + .14128 * cos(2 * angle) - .01168 * cos(3 * angle)
                else .5 - .5 * cos(angle)
            (audio[from + i] * window).toFloat()
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }
    private fun referenceSpectrum(render: SkitterRender, hz: Double): ReferenceSpectrum {
        val size = 32768
        val power = spectrum(render.raw, render.loopStart, size, bh = true)
        val harmonics = DoubleArray((Dsp.RATE * .5 / hz).toInt())
        var unwanted = 0.0
        var total = 0.0
        for (bin in 1 until power.size) {
            val multiple = (bin.toDouble() * Dsp.RATE / size / hz).roundToInt()
            val harmonicBin = multiple * hz * size / Dsp.RATE
            if (multiple in 1..harmonics.size && abs(bin - harmonicBin) <= 6.0) harmonics[multiple - 1] += power[bin]
            else unwanted += power[bin]
            total += power[bin]
        }
        val wanted = harmonics.sum()
        require(wanted > 1e-20 && total.isFinite())
        return ReferenceSpectrum(unwanted / total, DoubleArray(harmonics.size) { harmonics[it] / wanted })
    }

    fun requireHealthy(render: SkitterRender, label: String) {
        assertTrue(render.raw.all { it.isFinite() && abs(it) < .8f }, "$label exceeds raw headroom")
        assertEquals(0, render.diagnostics.boundHits, "$label reaches emergency bounds")
        assertTrue(render.diagnostics.depthStatePeak > 1e-8 && render.diagnostics.depthStatePeak <= Skitter.DEPTH_STATE_LIMIT + 1e-8,
            "$label joint bridge norm ${render.diagnostics.depthStatePeak}")
        assertTrue(render.diagnostics.depthReturnPeak.isFinite() && render.diagnostics.depthReturnPeak > 0.0,
            "$label returns no bridge audio")
        for (trace in render.trace) {
            val norm = sqrt(trace.depth1Real * trace.depth1Real + trace.depth1Imag * trace.depth1Imag +
                trace.depth2Real * trace.depth2Real + trace.depth2Imag * trace.depth2Imag)
            assertTrue(norm.isFinite() && norm <= Skitter.DEPTH_STATE_LIMIT + 1e-8, "$label trace joint norm $norm")
        }
        if (render.loopStart < 0) {
            val pitch = assertNotNull(Pitch.detect(render.snip, .55f, .4f), "$label loses the root")
            assertTrue(pitch.confidence >= .75f, "$label root confidence ${pitch.confidence}")
            assertTrue(abs(SkitterTestMeasure.cents(pitch.hz.toDouble(), root)) < 5.0, "$label moves the requested root")
        }
    }

    fun printBodies() {
        println("DEPTH_RULER,gainPhaseCentered=${ruler.gainPhaseCentered},staticColorMean=${ruler.staticColorMean},staticColorCentered=${ruler.staticColorCentered}")
        for (take in bodies) for ((i, comparison) in take.comparisons.withIndex()) {
            val pitch = Pitch.detect(take.full.snip, .55f, .4f)
            val matchGain = take.r7.diagnostics.rawRms / take.full.diagnostics.rawRms
            println("DEPTH_BODY,${take.voice},static=${if (take.clean) 0 else .15},fft=${comparison.size},mean=${comparison.mean},total=${comparison.total},centered=${comparison.centered},spread=${take.fullShapes[i].spread},rawRms=${take.full.diagnostics.rawRms},r7RawRms=${take.r7.diagnostics.rawRms},matchGain=$matchGain,rawPeak=${take.full.diagnostics.rawPeak},statePeak=${take.full.diagnostics.depthStatePeak},returnPeak=${take.full.diagnostics.depthReturnPeak},exchangePeak=${take.full.diagnostics.depthExchangePeak},pitchCents=${pitch?.let { SkitterTestMeasure.cents(it.hz.toDouble(), root) }},pitchConfidence=${pitch?.confidence},renderMillis=${take.full.diagnostics.renderMillis}")
        }
    }
    fun printCausal() {
        for (take in causal) for (c in take.comparisons) println("DEPTH_CAUSAL,${take.voice},mechanism=${take.mechanism},fft=${c.size},mean=${c.mean},total=${c.total},centered=${c.centered},statePeak=${take.off.diagnostics.depthStatePeak}")
    }
    fun printHeld(voice: SkitterVoice, render: SkitterRender) {
        println("DEPTH_HELD,$voice,stateError=${render.diagnostics.loopStateError},preroll=${render.diagnostics.loopPrerollPeriods},rawSeam=${Keys.seamError(render.raw, render.loopStart)},statePeak=${render.diagnostics.depthStatePeak},returnPeak=${render.diagnostics.depthReturnPeak},exchangePeak=${render.diagnostics.depthExchangePeak},boundHits=${render.diagnostics.boundHits}")
    }
    fun printReference(take: Reference) {
        println("DEPTH_REFERENCE,${take.voice},unwanted4Db=${db(take.normal.unwanted)},unwanted16Db=${db(take.reference.unwanted)},excessDb=${take.excessDb},harmonicDistance=${take.harmonicDistance},peak4=${take.full.diagnostics.rawPeak},peak16=${take.oversampled.diagnostics.rawPeak}")
    }
    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty() || "--body" in args) printBodies()
        if (args.isEmpty() || "--causal" in args) printCausal()
        if (args.isEmpty() || "--held" in args) held.forEach { (voice, render) -> printHeld(voice, render) }
        if (args.isEmpty() || "--reference" in args) references.forEach(::printReference)
    }
}
