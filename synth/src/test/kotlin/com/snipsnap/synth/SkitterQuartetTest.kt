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

/** R7 is judged at the published HOLD0 patch, beyond its first contact. */
class SkitterQuartetTest {
    @Test
    fun `quartet bodies persist beyond contact and all six voices exceed their seed variation`() {
        SkitterQuartetMeasure.printBodies()
        for (take in SkitterQuartetMeasure.bodies) {
            // Retain the R6 admission scale: five percent of normalized energy moves,
            // with change distributed through the powered body rather than one strike.
            assertTrue(take.bodyDistance >= .05, "${take.label} retains the R6 body (${take.bodyDistance})")
            assertTrue(take.bodyWindows.count { it >= .025 } >= 6,
                "${take.label} differs from R6 only at scattered contacts: ${take.bodyWindows}")
        }
        val bank = SkitterQuartetMeasure.identity
        SkitterQuartetMeasure.printIdentity()
        for ((i, a) in SkitterVoice.entries.withIndex()) for (b in SkitterVoice.entries.drop(i + 1)) {
            val distance = SkitterQuartetMeasure.tv(bank.centers.getValue(a), bank.centers.getValue(b))
            val radius = maxOf(bank.radius.getValue(a), bank.radius.getValue(b))
            assertTrue(distance >= .05, "$a / $b share a sustained body ($distance)")
            assertTrue(distance > 2.0 * radius, "$a / $b body contrast $distance is explained by seed radius $radius")
        }
    }

    @Test
    fun `quartet touch changes normalized body color while roots silence and determinism remain healthy`() {
        SkitterQuartetMeasure.printTouch()
        for (voice in SkitterQuartetMeasure.voices) {
            val takes = SkitterQuartetMeasure.bodies.filter { it.voice == voice }
            for ((soft, hard) in takes.zipWithNext()) {
                assertTrue(hard.full.diagnostics.rawRms > soft.full.diagnostics.rawRms,
                    "$voice stronger touch loses raw energy at ${hard.velocity}")
            }
            val touch = SkitterQuartetMeasure.touch(voice)
            // Separate power normalization prevents gain passing as color. Five percent,
            // as for sustained identity, records meaningful touch rather than mere activity.
            assertTrue(touch.colorDistance >= .05, "$voice touch barely changes body color (${touch.colorDistance})")
            assertTrue(touch.responseDistance > .01,
                "$voice new body adds no new touch response beyond R6 (${touch.responseDistance})")
            val original = takes.single { it.velocity == .6f }.full
            val repeat = SkitterQuartetMeasure.render(voice, .6f, 0)
            assertTrue(original.raw.contentEquals(repeat.raw), "$voice raw body is not deterministic")
            assertTrue(original.snip.samples.contentEquals(repeat.snip.samples), "$voice exported body is not deterministic")
            for (held in listOf(false, true)) {
                val zero = Skitter.renderProbe(voice, SkitterQuartetMeasure.common + ("HOLD" to if (held) 1f else 0f),
                    velocity = 0f, seed = 17, seconds = .3f)
                assertTrue(zero.raw.all { it == 0f } && zero.snip.samples.all { it == 0f }, "$voice held=$held leaves sound at zero velocity")
            }
        }
        for (take in SkitterQuartetMeasure.roots) {
            val pitch = assertNotNull(Pitch.detect(take.render.snip, .55f, .4f), "${take.label} loses the root")
            val cents = SkitterQuartetMeasure.cents(pitch.hz.toDouble(), Keys.midiHz(take.midi).toDouble())
            assertTrue(pitch.confidence >= .75f, "${take.label} pitch confidence ${pitch.confidence}")
            assertTrue(abs(cents) < 5.0, "${take.label} root error $cents cents")
            assertEquals(0, take.render.diagnostics.boundHits, "${take.label} uses emergency bounds")
            assertTrue(take.render.raw.all { it.isFinite() && abs(it) < .8f }, "${take.label} exceeds raw headroom")
        }
    }

    @Test
    fun `quartet held bodies converge and four times rendering agrees with sixteen times reference`() {
        for ((voice, render) in SkitterQuartetMeasure.held) {
            SkitterQuartetMeasure.printHeld(voice, render)
            assertTrue(render.diagnostics.loopConverged, "$voice body never reaches its periodic state")
            assertEquals(0, render.diagnostics.boundHits, "$voice held body hits a bound")
            assertTrue(render.diagnostics.loopStateError < Skitter.LOOP_STATE_TOLERANCE)
            assertTrue(Keys.seamError(render.raw, render.loopStart) < Keys.MAX_SEAM_ERROR, "$voice raw body seam does not close")
        }
        for (take in SkitterQuartetMeasure.references) {
            SkitterQuartetMeasure.printReference(take)
            // Same admission bars and stationary C6 reference as R6. Musical sidebands
            // are excluded here by removing gestures/noise, never by subtracting waveforms.
            assertTrue(take.normal.unwanted <= take.reference.unwanted + 1e-6,
                "${take.voice} adds folded/nonharmonic power above -60dB (${take.excessDb}dB)")
            assertTrue(take.harmonicDistance < .03,
                "${take.voice} 4x harmonic envelope differs from 16x (${take.harmonicDistance})")
        }
    }
}

/** Prints real output evidence before a root-owned build applies numerical acceptance. */
internal object SkitterQuartetMeasure {
    val voices = listOf(SkitterVoice.BASS, SkitterVoice.FLUTTER, SkitterVoice.CHIRP, SkitterVoice.FRAY)
    val velocities = listOf(.25f, .6f, 1f)
    val common = mapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val seeds = listOf(0, 17, 91)
    private val root = Keys.midiHz(48).toDouble()
    private const val fftSize = 2048
    // Every window ends before BASS's 1.30s gate; these are actual HOLD0 bodies.
    private val bodyStarts = List(8) { .5 + .1 * it }
    private val onsetStarts = List(10) { .005 * it }
    private val edges = doubleArrayOf(0.0, .85, 1.15, 1.75, 2.5, 3.5, 5.0, 7.0, 10.0, 14.0, 20.0, 32.0, 64.0, Double.POSITIVE_INFINITY)

    data class Shape(val windows: List<DoubleArray>) {
        val vector = windows.flatMap { row -> row.map { it / windows.size } }.toDoubleArray()
    }
    data class Body(val voice: SkitterVoice, val velocity: Float, val full: SkitterRender, val old: SkitterRender,
                    val shape: Shape, val oldShape: Shape, val onset: Shape, val oldOnset: Shape) {
        val label get() = "$voice velocity $velocity"
        val bodyDistance get() = SkitterQuartetMeasure.tv(shape.vector, oldShape.vector)
        val bodyWindows get() = shape.windows.zip(oldShape.windows) { a, b -> SkitterQuartetMeasure.tv(a, b) }
    }
    data class Identity(val centers: Map<SkitterVoice, DoubleArray>, val radius: Map<SkitterVoice, Double>)
    data class Touch(val colorDistance: Double, val oldColorDistance: Double,
                     val responseDistance: Double, val onsetColorDistance: Double)
    data class RootTake(val voice: SkitterVoice, val midi: Int, val velocity: Float, val render: SkitterRender) {
        val label get() = "$voice MIDI$midi velocity $velocity"
    }
    data class ReferenceSpectrum(val unwanted: Double, val harmonics: DoubleArray)
    data class Reference(val voice: SkitterVoice, val full: SkitterRender, val oversampled: SkitterRender,
                         val normal: ReferenceSpectrum, val reference: ReferenceSpectrum) {
        val harmonicDistance get() = SkitterQuartetMeasure.tv(normal.harmonics, reference.harmonics)
        val excessDb get() = SkitterQuartetMeasure.db((normal.unwanted - reference.unwanted).coerceAtLeast(1e-30))
    }

    fun render(voice: SkitterVoice, velocity: Float, seed: Int, midi: Int = 48, old: Boolean = false) =
        Skitter.renderProbe(voice, common, midi = midi, velocity = velocity, seed = seed, seconds = 1.3f,
            probe = SkitterProbe(quartetDisabled = old))

    val bodies: List<Body> by lazy {
        voices.flatMap { voice -> velocities.map { velocity ->
            val full = render(voice, velocity, 0)
            val old = render(voice, velocity, 0, old = true)
            Body(voice, velocity, full, old, shape(full.raw, bodyStarts), shape(old.raw, bodyStarts),
                shape(full.raw, onsetStarts), shape(old.raw, onsetStarts))
        } }
    }

    val identity: Identity by lazy {
        val signatures = SkitterVoice.entries.associateWith { voice -> seeds.map { seed ->
            if (seed == 0 && voice in voices) bodies.single { it.voice == voice && it.velocity == .6f }.shape.vector
            else shape(render(voice, .6f, seed).raw, bodyStarts).vector
        } }
        val centers = signatures.mapValues { (_, values) -> DoubleArray(values.first().size) { i -> values.sumOf { it[i] } / values.size } }
        val radius = signatures.mapValues { (voice, values) -> values.maxOf { tv(it, centers.getValue(voice)) } }
        Identity(centers, radius)
    }

    // Low-velocity formant ambiguity is checked first by the diagnostic, before full banks.
    val roots: List<RootTake> by lazy {
        listOf(36, 48, 60, 72, 84).flatMap { midi -> velocities.flatMap { velocity -> voices.map { voice ->
            val result = if (midi == 48) bodies.single { it.voice == voice && it.velocity == velocity }.full
                else render(voice, velocity, 0, midi)
            RootTake(voice, midi, velocity, result)
        } } }
    }
    val held: Map<SkitterVoice, SkitterRender> by lazy {
        voices.associateWith { voice -> Skitter.renderProbe(voice, common + ("HOLD" to 1f), seed = 17) }
    }
    val references: List<Reference> by lazy {
        voices.map { voice ->
            val macros = common + mapOf("BUZZ" to 1f, "SKITTER" to 0f, "STATIC" to 0f, "HOLD" to 1f)
            val normal = Skitter.renderProbe(voice, macros, midi = 84, seed = 0, oversample = 4)
            val ref = Skitter.renderProbe(voice, macros, midi = 84, seed = 0, oversample = 16)
            check(normal.loopStart == ref.loopStart) { "$voice reference has a different period" }
            val hz = Keys.midiHz(84).toDouble()
            val fitted = (hz * normal.loopStart / Dsp.RATE).roundToInt() * Dsp.RATE.toDouble() / normal.loopStart
            Reference(voice, normal, ref, referenceSpectrum(normal, fitted), referenceSpectrum(ref, fitted))
        }
    }

    fun tv(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } * .5
    fun cents(hz: Double, expected: Double) = 1200.0 * ln(hz / expected) / ln(2.0)
    private fun db(power: Double) = 10.0 * log10(power.coerceAtLeast(1e-30))

    fun touch(voice: SkitterVoice): Touch {
        val soft = bodies.single { it.voice == voice && it.velocity == .25f }
        val hard = bodies.single { it.voice == voice && it.velocity == 1f }
        val newResponse = DoubleArray(soft.shape.vector.size) { hard.shape.vector[it] - soft.shape.vector[it] }
        val oldResponse = DoubleArray(soft.shape.vector.size) { hard.oldShape.vector[it] - soft.oldShape.vector[it] }
        return Touch(tv(soft.shape.vector, hard.shape.vector), tv(soft.oldShape.vector, hard.oldShape.vector),
            tv(newResponse, oldResponse), tv(soft.onset.vector, hard.onset.vector))
    }

    private fun spectrum(audio: FloatArray, from: Int, size: Int, bh: Boolean = false): DoubleArray {
        require(from >= 0 && from + size <= audio.size)
        val re = FloatArray(size) { i ->
            val angle = 2.0 * PI * i / (size - 1)
            val window = if (bh) .35875 - .48829 * cos(angle) + .14128 * cos(2.0 * angle) - .01168 * cos(3.0 * angle)
                else .5 - .5 * cos(angle)
            (audio[from + i] * window).toFloat()
        }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun shape(audio: FloatArray, starts: List<Double>): Shape = Shape(starts.map { start ->
        val power = spectrum(audio, (start * Dsp.RATE).roundToInt(), fftSize)
        val bands = DoubleArray(edges.size - 1)
        for (bin in 1 until power.size) {
            val ratio = bin.toDouble() * Dsp.RATE / fftSize / root
            val band = edges.indexOfLast { ratio >= it }
            if (band in bands.indices) bands[band] += power[bin]
        }
        val total = bands.sum()
        require(total > 1e-20 && total.isFinite()) { "quartet spectrum is silent or nonfinite" }
        DoubleArray(bands.size) { bands[it] / total }
    })

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

    fun printBodies() {
        for (take in bodies) println("QUARTET_BODY,${take.voice},velocity=${take.velocity},distance=${take.bodyDistance},windowMin=${take.bodyWindows.min()},windowsAbove025=${take.bodyWindows.count { it >= .025 }},rawPeak=${take.full.diagnostics.rawPeak},rawRms=${take.full.diagnostics.rawRms},oldRawRms=${take.old.diagnostics.rawRms},boundHits=${take.full.diagnostics.boundHits}")
    }
    fun printTouch() {
        for (voice in voices) {
            val t = touch(voice)
            println("QUARTET_TOUCH,$voice,bodyColor=${t.colorDistance},r6BodyColor=${t.oldColorDistance},newResponse=${t.responseDistance},onsetColor=${t.onsetColorDistance}")
        }
    }
    fun printIdentity() {
        for (voice in SkitterVoice.entries) println("QUARTET_IDENTITY_VOICE,$voice,seedRadius=${identity.radius.getValue(voice)}")
        for ((i, a) in SkitterVoice.entries.withIndex()) for (b in SkitterVoice.entries.drop(i + 1)) {
            println("QUARTET_IDENTITY_PAIR,$a,$b,distance=${tv(identity.centers.getValue(a), identity.centers.getValue(b))},seedRadius=${maxOf(identity.radius.getValue(a), identity.radius.getValue(b))}")
        }
    }
    private fun printRoot(take: RootTake) {
        val expected = Keys.midiHz(take.midi).toDouble()
        val pitch = Pitch.detect(take.render.snip, .55f, .4f)
        val power = spectrum(take.render.raw, (.55 * Dsp.RATE).roundToInt(), 8192)
        var rootPower = 0.0
        for (bin in 1 until power.size) if (bin.toDouble() * Dsp.RATE / 8192 / expected in .85..1.15) rootPower += power[bin]
        val strongest = power.indices.drop(1).maxBy { power[it] }.toDouble() * Dsp.RATE / 8192 / expected
        println("QUARTET_ROOT,${take.voice},midi=${take.midi},velocity=${take.velocity},cents=${pitch?.let { cents(it.hz.toDouble(), expected) }},confidence=${pitch?.confidence},rootPowerShare=${rootPower / power.sum()},strongestMultiple=$strongest,rawPeak=${take.render.diagnostics.rawPeak},boundHits=${take.render.diagnostics.boundHits}")
    }
    fun printHeld(voice: SkitterVoice, render: SkitterRender) {
        println("QUARTET_HELD,$voice,stateError=${render.diagnostics.loopStateError},preroll=${render.diagnostics.loopPrerollPeriods},rawSeam=${Keys.seamError(render.raw, render.loopStart)},boundHits=${render.diagnostics.boundHits}")
    }
    fun printReference(take: Reference) {
        println("QUARTET_REFERENCE,${take.voice},unwanted4Db=${db(take.normal.unwanted)},unwanted16Db=${db(take.reference.unwanted)},excessDb=${take.excessDb},harmonicDistance=${take.harmonicDistance},peak4=${take.full.diagnostics.rawPeak},peak16=${take.oversampled.diagnostics.rawPeak}")
    }

    @JvmStatic fun main(args: Array<String>) {
        when {
            "--soft-roots" in args -> for (midi in listOf(36, 48)) for (voice in voices) {
                printRoot(RootTake(voice, midi, .25f, render(voice, .25f, 0, midi)))
            }
            "--roots" in args -> roots.forEach(::printRoot)
            else -> {
                printBodies()
                printTouch()
                printIdentity()
                held.forEach { (voice, render) -> printHeld(voice, render) }
                references.forEach(::printReference)
            }
        }
    }
}
