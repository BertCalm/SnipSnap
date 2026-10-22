package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sustained differences must reach the real audition patch, after the contact transient. */
class SkitterBodyTest {
    @Test
    fun `actual common controls produce persistent new bodies beyond R5 and R4 and separate the voices`() {
        SkitterBodyMeasure.printBodies()
        for (take in SkitterBodyMeasure.bank) {
            for ((revision, distance, windows) in listOf(
                Triple("R5", take.r5Distance, take.r5Windows), Triple("R4", take.r4Distance, take.r4Windows),
            )) {
                assertTrue(distance >= .05, "${take.label} retains the $revision body after gain removal ($distance)")
                assertTrue(windows.count { it >= .025 } >= 6,
                    "${take.label} differs from $revision only in scattered contacts: $windows")
            }
        }
        for (velocity in SkitterBodyMeasure.velocities) {
            val pair = SkitterBodyMeasure.bank.filter { it.velocity == velocity }
            val distance = SkitterBodyMeasure.tv(pair[0].fullShape.mean, pair[1].fullShape.mean)
            assertTrue(distance >= .08, "R6 LEAD/HOLLOW share a sustained body at velocity $velocity ($distance)")
        }
    }

    @Test
    fun `new bodies retain pitched deterministic finite notes and healthy held states including silence`() {
        for (take in SkitterBodyMeasure.bank) {
            val render = take.full
            assertTrue(render.raw.all { it.isFinite() && abs(it) < .8f }, "${take.label} exceeds raw headroom")
            assertEquals(0, render.diagnostics.boundHits, "${take.label} uses an emergency bound")
            val pitch = assertNotNull(Pitch.detect(render.snip, .55f, .4f), "${take.label} loses the root")
            assertTrue(pitch.confidence >= .75f, "${take.label} pitch confidence ${pitch.confidence}")
            assertTrue(abs(SkitterBodyMeasure.cents(pitch.hz.toDouble(), SkitterBodyMeasure.root)) < 5.0,
                "${take.label} moves away from the requested root")
        }
        for (voice in SkitterBodyMeasure.voices) {
            val original = SkitterBodyMeasure.bank.single { it.voice == voice && it.velocity == .6f }.full
            val repeat = Skitter.renderProbe(voice, SkitterBodyMeasure.common, velocity = .6f, seed = 0, seconds = 3f)
            assertTrue(original.raw.contentEquals(repeat.raw), "$voice body is not deterministic")
            assertTrue(original.snip.samples.contentEquals(repeat.snip.samples), "$voice export is not deterministic")
            for (held in listOf(false, true)) {
                val macros = SkitterBodyMeasure.common + ("HOLD" to if (held) 1f else .75f)
                val zero = Skitter.renderProbe(voice, macros, velocity = 0f, seconds = .3f)
                assertTrue(zero.raw.all { it == 0f } && zero.snip.samples.all { it == 0f }, "$voice held=$held leaves audio at zero touch")
            }
        }
        for ((voice, render) in SkitterBodyMeasure.held) {
            assertTrue(render.diagnostics.loopConverged, "$voice body does not reach a periodic state")
            assertEquals(0, render.diagnostics.boundHits)
            assertTrue(render.diagnostics.loopStateError < Skitter.LOOP_STATE_TOLERANCE)
            assertTrue(Keys.seamError(render.raw, render.loopStart) < Keys.MAX_SEAM_ERROR, "$voice raw body loop does not close")
        }
    }

    @Test
    fun `four times rendering agrees with a sixteen times reference without excess folded tones`() {
        for (take in SkitterBodyMeasure.references) {
            SkitterBodyMeasure.printReference(take)
            // A phase difference between numerical integrators is not an alias. Guard the
            // wanted harmonics, then compare unwanted power against the cleaner reference.
            assertTrue(take.normal.unwantedPower <= take.reference.unwantedPower + 1e-6,
                "${take.voice} adds folded/nonharmonic power above -60dB: ${take.extraUnwantedDb}dB")
            assertTrue(take.harmonicDistance < .03,
                "${take.voice} changes its harmonic envelope at 4x (${take.harmonicDistance})")
        }
    }
}

/** Diagnostic main runs before JUnit so a weak body or folded spectrum can reject a prototype early. */
internal object SkitterBodyMeasure {
    val voices = listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW)
    val velocities = listOf(.25f, .6f, 1f)
    val root = Keys.midiHz(48).toDouble()
    val common = mapOf("BUZZ" to .55f, "SKITTER" to .5f, "AGITATE" to .6f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to .75f)
    private val starts = listOf(.5, .8, 1.1, 1.4, 1.7, 2.0, 2.3, 2.6)
    private val edges = doubleArrayOf(0.0, .85, 1.15, 1.75, 2.5, 3.5, 5.0, 7.0, 10.0, 14.0, 20.0, 32.0, 64.0, Double.POSITIVE_INFINITY)
    private const val bodyFftSize = 8192

    data class Shape(val windows: List<DoubleArray>, val centroidHz: Double) {
        val mean = DoubleArray(windows.first().size) { i -> windows.sumOf { it[i] } / windows.size }
    }
    data class BodyTake(val voice: SkitterVoice, val velocity: Float, val full: SkitterRender,
                        val r5: SkitterRender, val r4: SkitterRender,
                        val fullShape: Shape, val r5Shape: Shape, val r4Shape: Shape) {
        val label get() = "$voice velocity $velocity"
        val r5Distance get() = SkitterBodyMeasure.tv(fullShape.mean, r5Shape.mean)
        val r4Distance get() = SkitterBodyMeasure.tv(fullShape.mean, r4Shape.mean)
        val r5Windows get() = fullShape.windows.zip(r5Shape.windows) { a, b -> SkitterBodyMeasure.tv(a, b) }
        val r4Windows get() = fullShape.windows.zip(r4Shape.windows) { a, b -> SkitterBodyMeasure.tv(a, b) }
    }
    data class ReferenceSpectrum(val unwantedPower: Double, val harmonics: DoubleArray)
    data class ReferenceTake(val voice: SkitterVoice, val normal: ReferenceSpectrum, val reference: ReferenceSpectrum,
                             val render4: SkitterRender, val render16: SkitterRender) {
        val harmonicDistance get() = SkitterBodyMeasure.tv(normal.harmonics, reference.harmonics)
        val extraUnwantedDb get() = SkitterBodyMeasure.db((normal.unwantedPower - reference.unwantedPower).coerceAtLeast(1e-30))
    }

    val bank: List<BodyTake> by lazy {
        voices.flatMap { voice -> velocities.map { velocity ->
            val full = Skitter.renderProbe(voice, common, velocity = velocity, seed = 0, seconds = 3f)
            val r5 = Skitter.renderProbe(voice, common, velocity = velocity, seed = 0, seconds = 3f,
                probe = SkitterProbe(bodyDisabled = true))
            val r4 = Skitter.renderProbe(voice, common, velocity = velocity, seed = 0, seconds = 3f,
                probe = SkitterProbe(bodyDisabled = true, articulationDisabled = true))
            BodyTake(voice, velocity, full, r5, r4, shape(full.raw), shape(r5.raw), shape(r4.raw))
        } }
    }

    val held: Map<SkitterVoice, SkitterRender> by lazy {
        voices.associateWith { Skitter.renderProbe(it, common + ("HOLD" to 1f), seed = 17) }
    }

    val references: List<ReferenceTake> by lazy {
        voices.map { voice ->
            // A stationary, clean high note exposes foldback. Gesture/noise sidebands would
            // make a nonharmonic mask count intended musical motion as an alias.
            val macros = common + mapOf("BUZZ" to 1f, "SKITTER" to 0f, "STATIC" to 0f, "HOLD" to 1f)
            val r4 = Skitter.renderProbe(voice, macros, midi = 84, seed = 0, oversample = 4)
            val r16 = Skitter.renderProbe(voice, macros, midi = 84, seed = 0, oversample = 16)
            check(r4.loopStart == r16.loopStart) { "$voice reference has a different period" }
            val expected = Keys.midiHz(84).toDouble()
            val fittedRoot = (expected * r4.loopStart / Dsp.RATE).roundToInt() * Dsp.RATE.toDouble() / r4.loopStart
            ReferenceTake(voice, referenceSpectrum(r4, fittedRoot), referenceSpectrum(r16, fittedRoot), r4, r16)
        }
    }

    fun tv(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } * .5
    fun cents(hz: Double, expected: Double): Double = 1200.0 * ln(hz / expected) / ln(2.0)
    private fun db(power: Double): Double = 10.0 * log10(power.coerceAtLeast(1e-30))

    private fun spectrum(audio: FloatArray, from: Int, size: Int, blackmanHarris: Boolean = false): DoubleArray {
        require(from >= 0 && from + size <= audio.size)
        val re = FloatArray(size) { i ->
            val angle = 2.0 * PI * i / (size - 1)
            val window = if (blackmanHarris) .35875 - .48829 * cos(angle) + .14128 * cos(2.0 * angle) - .01168 * cos(3.0 * angle)
                else .5 - .5 * cos(angle)
            (audio[from + i] * window).toFloat()
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun shape(audio: FloatArray): Shape {
        var weighted = 0.0
        var total = 0.0
        val windows = starts.map { start ->
            val power = spectrum(audio, (start * Dsp.RATE).roundToInt(), bodyFftSize)
            val bands = DoubleArray(edges.size - 1)
            for (bin in 1 until power.size) {
                val hz = bin.toDouble() * Dsp.RATE / bodyFftSize
                val band = edges.indexOfLast { hz / root >= it }
                if (band in bands.indices) bands[band] += power[bin]
                weighted += hz * power[bin]
                total += power[bin]
            }
            val sum = bands.sum()
            require(sum > 1e-20 && sum.isFinite()) { "body spectrum is silent or nonfinite" }
            DoubleArray(bands.size) { bands[it] / sum }
        }
        return Shape(windows, weighted / total)
    }

    private fun referenceSpectrum(render: SkitterRender, hz: Double): ReferenceSpectrum {
        val size = 32768
        val power = spectrum(render.raw, render.loopStart, size, blackmanHarris = true)
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
        val harmonicTotal = harmonics.sum()
        require(harmonicTotal > 1e-20 && total.isFinite())
        return ReferenceSpectrum(unwanted / total, DoubleArray(harmonics.size) { harmonics[it] / harmonicTotal })
    }

    fun printBodies() {
        for (take in bank) {
            val pitch = Pitch.detect(take.full.snip, .55f, .4f)
            println("BODY_CONTRAST,${take.voice},velocity=${take.velocity},r5Mean=${take.r5Distance},r4Mean=${take.r4Distance},r5WindowMin=${take.r5Windows.min()},r4WindowMin=${take.r4Windows.min()},r5WindowsAbove025=${take.r5Windows.count { it >= .025 }},r4WindowsAbove025=${take.r4Windows.count { it >= .025 }},centroidHz=${take.fullShape.centroidHz},r5CentroidHz=${take.r5Shape.centroidHz},r4CentroidHz=${take.r4Shape.centroidHz},rawPeak=${take.full.diagnostics.rawPeak},rawRms=${take.full.diagnostics.rawRms},pitchCents=${pitch?.let { cents(it.hz.toDouble(), root) }},pitchConfidence=${pitch?.confidence},renderMillis=${take.full.diagnostics.renderMillis}")
        }
        for (velocity in velocities) {
            val pair = bank.filter { it.velocity == velocity }
            println("BODY_VOICES,velocity=$velocity,distance=${tv(pair[0].fullShape.mean, pair[1].fullShape.mean)}")
        }
    }

    fun printReference(take: ReferenceTake) {
        println("BODY_REFERENCE,${take.voice},unwanted4Db=${db(take.normal.unwantedPower)},unwanted16Db=${db(take.reference.unwantedPower)},extraUnwantedDb=${take.extraUnwantedDb},harmonicDistance=${take.harmonicDistance},peak4=${take.render4.diagnostics.rawPeak},peak16=${take.render16.diagnostics.rawPeak},render4Millis=${take.render4.diagnostics.renderMillis},render16Millis=${take.render16.diagnostics.renderMillis}")
    }

    @JvmStatic fun main(args: Array<String>) {
        printBodies()
        for ((voice, render) in held) println("BODY_HELD,$voice,stateError=${render.diagnostics.loopStateError},preroll=${render.diagnostics.loopPrerollPeriods},rawSeam=${Keys.seamError(render.raw, render.loopStart)},boundHits=${render.diagnostics.boundHits}")
        references.forEach(::printReference)
    }
}
