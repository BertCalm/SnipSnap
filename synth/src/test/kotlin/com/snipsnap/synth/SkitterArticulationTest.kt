package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Archival R5 touch proof; bodyDisabled keeps its steady-body contract separate from R6. */
class SkitterArticulationTest {
    @Test
    fun `soft medium and hard contacts change audio color while preserving a pitched body`() {
        val bank = SkitterArticulationMeasure.finite
        SkitterArticulationMeasure.printFinite(bank)
        for (voice in SkitterArticulationMeasure.voices) {
            val takes = bank.filter { it.voice == voice }
            for ((soft, hard) in takes.zipWithNext()) {
                assertTrue(hard.rawRms > soft.rawRms, "$voice loses touch energy at ${hard.velocity}")
                assertTrue(hard.contactPeak > soft.contactPeak, "$voice contact ignores excitation velocity")
            }
            for (take in takes) {
                val label = "$voice velocity ${take.velocity}"
                // First Kotlin probe: the softest LEAD contact moves .00536 of normalized power.
                assertTrue(take.peakColorChange > .004, "$label contact only changes gain (${take.peakColorChange})")
                assertTrue(take.full.raw.all { it.isFinite() && abs(it) < .8f }, "$label exceeds the raw headroom budget")
                assertEquals(0, take.full.diagnostics.boundHits, "$label uses an emergency state bound")
                val pitch = assertNotNull(Pitch.detect(take.full.snip, .55f, .4f), "$label loses its body root")
                assertTrue(pitch.confidence >= .75f, "$label weak body pitch confidence ${pitch.confidence}")
                assertTrue(abs(SkitterTestMeasure.cents(pitch.hz.toDouble(), SkitterArticulationMeasure.root)) < 5.0,
                    "$label moves its stable body away from the requested root")
            }
            assertTrue(takes.last().peakColorChange > takes.first().peakColorChange * 1.10,
                "$voice stronger touch does not deepen the audible contact")
            assertTrue(SkitterArticulationMeasure.touchColor(takes.first(), takes.last(), false) > .01,
                "$voice full soft and hard attacks have the same normalized audio color")
            val zero = Skitter.renderProbe(voice, SkitterArticulationMeasure.isolated, velocity = 0f, seconds = .3f,
                probe = SkitterProbe(bodyDisabled = true))
            assertTrue(zero.raw.all { it == 0f } && zero.snip.samples.all { it == 0f }, "$voice contact leaves audio at zero velocity")
        }
        // The proof is deliberately confined to two source families.
        for (voice in SkitterVoice.entries - SkitterArticulationMeasure.voices.toSet()) {
            val normal = Skitter.renderProbe(voice, SkitterArticulationMeasure.isolated, seconds = .4f,
                probe = SkitterProbe(bodyDisabled = true))
            val off = Skitter.renderProbe(voice, SkitterArticulationMeasure.isolated, seconds = .4f,
                probe = SkitterProbe(articulationDisabled = true, bodyDisabled = true))
            assertContentEquals(off.raw, normal.raw, "$voice changed outside the LEAD/HOLLOW proof")
            assertContentEquals(off.snip.samples, normal.snip.samples, "$voice changed its exported level")
        }
    }

    @Test
    fun `lead tears early hollow blooms later and both return to their steady bodies`() {
        val hard = SkitterArticulationMeasure.finite.filter { it.velocity == 1f }
        val lead = hard.single { it.voice == SkitterVoice.LEAD }
        val hollow = hard.single { it.voice == SkitterVoice.HOLLOW }
        val ruler = SkitterArticulationMeasure.ruler
        assertTrue(ruler.gainPhasePeak < .001, "contact ruler mistakes gain/carrier phase for color: ${ruler.gainPhasePeak}")
        assertTrue(ruler.colorPeak > .10, "contact ruler misses harmonic redistribution: ${ruler.colorPeak}")
        // A 46ms window can give both attacks the same peak frame. Integrated color timing
        // asks when the audible departure carries its weight, rather than reasserting a DSP tau.
        // First timing probe: HOLLOW carries its color 31..46ms later across touch levels.
        assertTrue(hollow.colorCentroidSeconds > lead.colorCentroidSeconds + .020,
            "contact timing does not reach audio: LEAD ${lead.colorCentroidSeconds}s / HOLLOW ${hollow.colorCentroidSeconds}s")
        assertTrue(hollow.colorAfter60msFraction > lead.colorAfter60msFraction + .08,
            "HOLLOW lacks a later audible answer (${hollow.colorAfter60msFraction} vs ${lead.colorAfter60msFraction})")
        for (take in hard) {
            assertTrue(take.lateColorChange < take.peakColorChange * .01,
                "${take.voice} contact persists as a replacement steady timbre (${take.lateColorChange} vs ${take.peakColorChange})")
            assertTrue(take.bodyRmsRatio in .98..1.02,
                "${take.voice} contact displaces the steady source body (${take.bodyRmsRatio})")
            assertTrue(take.full.trace.filter { it.seconds >= .6 }.maxOf { it.contact } < take.contactPeak * .01,
                "${take.voice} source contact fails to recover")
        }
    }

    @Test
    fun `loaded history changes real held replies without changing their pitch gestures or loop health`() {
        for (take in SkitterArticulationMeasure.held) {
            SkitterArticulationMeasure.printHeld(take)
            assertEquals(take.full.trace.map { it.pitchOffsetA to it.pitchOffsetB },
                take.unloaded.trace.map { it.pitchOffsetA to it.pitchOffsetB },
                "${take.voice} source-memory ablation changes the external pitch gesture")
            // Kotlin held probe: loaded peaks are .463 of the source-memory-disabled case.
            assertTrue(take.loadedContactPeak < take.unloadedContactPeak * .75,
                "${take.voice} source contact is not loaded by previous signal history")
            assertTrue(take.audioColorChange > .002,
                "${take.voice} source-memory loading never reaches audible color (${take.audioColorChange})")
            for (render in listOf(take.full, take.unloaded)) {
                assertTrue(render.diagnostics.loopConverged, "${take.voice} contact state does not converge")
                assertEquals(0, render.diagnostics.boundHits)
                assertTrue(render.diagnostics.loopStateError < Skitter.LOOP_STATE_TOLERANCE)
                assertTrue(Keys.seamError(render.raw, render.loopStart) < 1e-3,
                    "${take.voice} independently evolved raw periods do not close")
            }
        }
    }
}

/** Root-owned diagnostic main prints audio evidence before new numerical margins are finalized. */
internal object SkitterArticulationMeasure {
    val voices = listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW)
    private val velocities = listOf(.25f, .6f, 1f)
    val root = Keys.midiHz(48).toDouble()
    // Removing pitch departures and noise isolates source contact from pre-existing reaction color.
    val isolated = mapOf("BUZZ" to .55f, "SKITTER" to 0f, "AGITATE" to .6f,
        "VENT" to .35f, "STATIC" to 0f, "HOLD" to 0f)
    private const val fftSize = 2048
    private const val hop = 256
    val hopSeconds = hop.toDouble() / Dsp.RATE
    private val edges = doubleArrayOf(0.0, .85, 1.15, 1.75, 2.5, 3.5, 5.0, 7.0, 10.0, 14.0, 20.0, 32.0, 64.0, Double.POSITIVE_INFINITY)
    private val window = DoubleArray(fftSize) { .5 - .5 * cos(2.0 * PI * it / (fftSize - 1)) }

    data class AudioFrame(val seconds: Double, val shape: DoubleArray)
    data class Delta(val seconds: Double, val color: Double)
    data class FiniteTake(val voice: SkitterVoice, val velocity: Float, val full: SkitterRender,
                          val off: SkitterRender, val differences: List<Delta>) {
        private val onset get() = differences.filter { it.seconds <= .3 }
        val rawRms get() = SkitterTestMeasure.rms(full.raw)
        val peakColorChange get() = onset.maxOf { it.color }
        val audioPeakSeconds get() = onset.maxBy { it.color }.seconds
        val colorCentroidSeconds get() = onset.sumOf { it.seconds * it.color } / onset.sumOf { it.color }
        val colorHalfSeconds: Double get() {
            val half = onset.sumOf { it.color } * .5
            var area = 0.0
            return onset.first { area += it.color; area >= half }.seconds
        }
        val colorAfter60msFraction get() = onset.filter { it.seconds >= .06 }.sumOf { it.color } / onset.sumOf { it.color }
        val earlyColorMean get() = onset.filter { it.seconds < .06 }.map { it.color }.average()
        val recoveryColorMean get() = onset.filter { it.seconds in .06.. .2 }.map { it.color }.average()
        val lateColorChange get() = differences.filter { it.seconds in .6.. .95 }.map { it.color }.average()
        val contactPeak get() = full.trace.maxOf { it.contact }
        val contactPeakSeconds get() = full.trace.maxBy { it.contact }.seconds
        val bodyRmsRatio get() = SkitterArticulationMeasure.rms(full.raw, .6, .95) / SkitterArticulationMeasure.rms(off.raw, .6, .95)
    }
    data class HeldTake(val voice: SkitterVoice, val full: SkitterRender, val unloaded: SkitterRender,
                        val audioColorChange: Double) {
        val loadedContactPeak get() = full.trace.maxOf { it.contact }
        val unloadedContactPeak get() = unloaded.trace.maxOf { it.contact }
    }
    data class Ruler(val gainPhasePeak: Double, val colorPeak: Double)

    val ruler: Ruler by lazy {
        fun signal(gain: Double, phase: Double, third: Double) = FloatArray((Dsp.RATE * .4).toInt()) { i ->
            val t = 2.0 * PI * root * i / Dsp.RATE
            (gain * (sin(t + phase) + third * sin(3.0 * t - phase * .37))).toFloat()
        }
        val original = signal(.2, 0.0, .35)
        Ruler(differences(original, signal(-.8, 1.7, .35)).maxOf { it.color },
            differences(original, signal(.2, 0.0, .80)).maxOf { it.color })
    }

    val finite: List<FiniteTake> by lazy {
        voices.flatMap { voice -> velocities.map { velocity ->
            val full = Skitter.renderProbe(voice, isolated, midi = 48, velocity = velocity, seed = 0, seconds = 1.15f,
                probe = SkitterProbe(bodyDisabled = true))
            val off = Skitter.renderProbe(voice, isolated, midi = 48, velocity = velocity, seed = 0, seconds = 1.15f,
                probe = SkitterProbe(articulationDisabled = true, bodyDisabled = true))
            FiniteTake(voice, velocity, full, off, differences(full.raw, off.raw))
        } }
    }

    val held: List<HeldTake> by lazy {
        voices.map { voice ->
            val macros = isolated + mapOf("SKITTER" to .7f, "HOLD" to 1f)
            val full = Skitter.renderProbe(voice, macros, midi = 48, velocity = 1f, seed = 917,
                probe = SkitterProbe(bodyDisabled = true))
            val off = Skitter.renderProbe(voice, macros, midi = 48, velocity = 1f, seed = 917,
                probe = SkitterProbe(contactMemoryDisabled = true, bodyDisabled = true))
            val delta = differences(full.raw.copyOf(full.loopStart), off.raw.copyOf(off.loopStart))
            HeldTake(voice, full, off, delta.map { it.color }.average())
        }
    }

    private fun rms(audio: FloatArray, from: Double, to: Double): Double =
        SkitterTestMeasure.rms(audio, (from * Dsp.RATE).toInt(), (to * Dsp.RATE).toInt())

    /** Independent windows avoid FFT-of-subtraction phase artifacts; equal power removes gain. */
    private fun frames(audio: FloatArray): List<AudioFrame> = (0..audio.size - fftSize step hop).map { from ->
        val re = FloatArray(fftSize) { (audio[from + it] * window[it]).toFloat() }
        val im = FloatArray(fftSize)
        Fft.forward(re, im)
        val bands = DoubleArray(edges.size - 1)
        for (bin in 1..fftSize / 2) {
            val ratio = bin.toDouble() * Dsp.RATE / fftSize / root
            val band = edges.indexOfLast { ratio >= it }
            if (band in bands.indices) bands[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
        }
        val total = bands.sum()
        require(total > 1e-20 && total.isFinite()) { "contact audio window is silent or nonfinite" }
        AudioFrame((from + (fftSize - 1) * .5) / Dsp.RATE, DoubleArray(bands.size) { bands[it] / total })
    }

    private fun differences(a: FloatArray, b: FloatArray): List<Delta> = frames(a).zip(frames(b)) { x, y ->
        Delta(x.seconds, x.shape.indices.sumOf { abs(x.shape[it] - y.shape[it]) } * .5)
    }

    fun touchColor(soft: FiniteTake, hard: FiniteTake, disabled: Boolean): Double {
        val a = if (disabled) soft.off.raw else soft.full.raw
        val b = if (disabled) hard.off.raw else hard.full.raw
        return differences(a, b).filter { it.seconds <= .3 }.map { it.color }.average()
    }

    fun printFinite(value: List<FiniteTake>) {
        for (take in value) {
            val pitch = Pitch.detect(take.full.snip, .55f, .4f)
            println("ARTICULATION_TOUCH,${take.voice},velocity=${take.velocity},rawRms=${take.rawRms},rawPeak=${take.full.diagnostics.rawPeak},colorPeak=${take.peakColorChange},audioPeakSeconds=${take.audioPeakSeconds},colorCentroidSeconds=${take.colorCentroidSeconds},colorHalfSeconds=${take.colorHalfSeconds},colorAfter60msFraction=${take.colorAfter60msFraction},earlyColorMean=${take.earlyColorMean},recoveryColorMean=${take.recoveryColorMean},lateColor=${take.lateColorChange},bodyRmsRatio=${take.bodyRmsRatio},contactPeak=${take.contactPeak},contactPeakSeconds=${take.contactPeakSeconds},pitchCents=${pitch?.let { SkitterTestMeasure.cents(it.hz.toDouble(), root) }},pitchConfidence=${pitch?.confidence}")
        }
        for (voice in voices) {
            val takes = value.filter { it.voice == voice }
            println("ARTICULATION_TOUCH_PAIR,$voice,fullColorMean=${touchColor(takes.first(), takes.last(), false)},r4ColorMean=${touchColor(takes.first(), takes.last(), true)}")
        }
    }

    fun printHeld(take: HeldTake) {
        println("ARTICULATION_HELD,${take.voice},colorMean=${take.audioColorChange},loadedContactPeak=${take.loadedContactPeak},unloadedContactPeak=${take.unloadedContactPeak},contactRatio=${take.loadedContactPeak / take.unloadedContactPeak},stateError=${take.full.diagnostics.loopStateError},preroll=${take.full.diagnostics.loopPrerollPeriods},rawSeam=${Keys.seamError(take.full.raw, take.full.loopStart)}")
    }

    @JvmStatic fun main(args: Array<String>) {
        println("ARTICULATION_RULER,gainPhasePeak=${ruler.gainPhasePeak},colorPeak=${ruler.colorPeak}")
        printFinite(finite)
        held.forEach(::printHeld)
    }
}
