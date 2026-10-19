package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Common controls and pre-release windows keep presets, level, phase and tail length out of identity. */
class SkitterCharacterTest {
    @Test
    fun `common controls leave audible character beyond same voice seed variation`() {
        val bank = SkitterCharacterMeasure.bank
        SkitterCharacterMeasure.printBank(bank)
        for ((i, a) in SkitterVoice.entries.withIndex()) for (b in SkitterVoice.entries.drop(i + 1)) {
            val joint = SkitterCharacterMeasure.distance(bank.centers.getValue(a), bank.centers.getValue(b))
            val distance = SkitterCharacterMeasure.distance(bank.shapeCenters.getValue(a), bank.shapeCenters.getValue(b))
            val seedRadius = maxOf(bank.shapeSeedRadius.getValue(a), bank.shapeSeedRadius.getValue(b))
            // Five percent of normalized energy must move, as in CisternVoiceContrastTest.
            // Equal window weights keep human phrase loudness out of the spectral seed balls.
            // Total time/frequency contrast must also remain audible on the joint ruler.
            assertTrue(joint >= .05, "$a / $b share their total character at common controls: distance $joint")
            assertTrue(distance >= .05, "$a / $b share their character at common controls: distance $distance")
            assertTrue(distance > 2.0 * seedRadius, "$a / $b contrast $distance is explained by seed spread $seedRadius")
        }
    }

    @Test
    fun `quiet core retains bass lead and hollow spectral roles without motion or hiss`() {
        val core = SkitterCharacterMeasure.bank.core
        val bass = core.getValue(SkitterVoice.BASS)
        val lead = core.getValue(SkitterVoice.LEAD)
        val hollow = core.getValue(SkitterVoice.HOLLOW)
        val fray = core.getValue(SkitterVoice.FRAY)
        assertTrue(bass.lowShare > lead.lowShare + .05,
            "BASS does not retain a more compact low-harmonic body than LEAD: ${bass.lowShare} / ${lead.lowShare}")
        assertTrue(hollow.evenOddRatio < lead.evenOddRatio * .80,
            "HOLLOW lacks its odd-rich nasal contrast with LEAD: ${hollow.evenOddRatio} / ${lead.evenOddRatio}")
        assertTrue(fray.upperShare > bass.upperShare + .02,
            "FRAY does not leave a denser upper body than BASS: ${fray.upperShare} / ${bass.upperShare}")
    }

    @Test
    fun `reactions reach changing audio spectra and the ruler ignores gain and carrier phase`() {
        val hz = Keys.midiHz(48).toDouble()
        fun reference(gain: Double, phase: Double, third: Double): FloatArray = FloatArray((Dsp.RATE * 1.25).toInt()) { i ->
            val t = 2.0 * PI * hz * i / Dsp.RATE
            (gain * (sin(t + phase) + third * sin(3.0 * t - phase * .37))).toFloat()
        }
        val original = SkitterCharacterMeasure.signature(reference(.2, .0, .35), hz)
        val gainAndPhase = SkitterCharacterMeasure.signature(reference(-.8, 1.7, .35), hz)
        val newColor = SkitterCharacterMeasure.signature(reference(.2, .0, .80), hz)
        assertTrue(SkitterCharacterMeasure.distance(original.joint, gainAndPhase.joint) < 1e-4, "identity ruler confuses gain or phase with color")
        assertTrue(SkitterCharacterMeasure.distance(original.joint, newColor.joint) > .10, "identity ruler misses an audible harmonic redistribution")

        val reactions = SkitterCharacterMeasure.reactions
        SkitterCharacterMeasure.printReactions(reactions)
        for ((voice, reaction) in reactions) {
            assertTrue(reaction.spectralChange > .005, "$voice agitation never changes its audible spectral envelope: ${reaction.spectralChange}")
            assertTrue(reaction.centroidCurve.all { it.isFinite() }, "$voice reaction contains nonfinite audio measurements")
            assertTrue(reaction.centroidRange > 1e-4, "$voice reaction is a fixed color change without a temporal contour")
        }
        val flutter = reactions.getValue(SkitterVoice.FLUTTER)
        val chirp = reactions.getValue(SkitterVoice.CHIRP)
        val contrast = SkitterCharacterMeasure.reactionDistance(flutter, chirp)
        assertTrue(contrast > .01, "FLUTTER and CHIRP have the same audible reaction: $contrast")
    }
}

/** The diagnostic main and JUnit use the same phase-insensitive audio measurements. */
internal object SkitterCharacterMeasure {
    data class Signature(
        val joint: DoubleArray,
        val shapes: List<DoubleArray>,
        val lowShare: Double,
        val upperShare: Double,
        val evenOddRatio: Double,
        val centroidOverRoot: Double,
    )
    data class Bank(
        val core: Map<SkitterVoice, Signature>,
        val active: Map<SkitterVoice, List<Signature>>,
        val centers: Map<SkitterVoice, DoubleArray>,
        val seedRadius: Map<SkitterVoice, Double>,
        val raw: Map<SkitterVoice, FloatArray>,
        val shapeCenters: Map<SkitterVoice, DoubleArray>,
        val shapeSeedRadius: Map<SkitterVoice, Double>,
    )
    data class Reaction(val spectralChange: Double, val delta: List<DoubleArray>, val centroidCurve: List<Double>) {
        val centroidRange get() = centroidCurve.max() - centroidCurve.min()
    }

    private val common = mapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val hz = Keys.midiHz(48).toDouble()
    private val seeds = listOf(0, 17, 91)
    private val starts = doubleArrayOf(.0, .20, .45, .70, .95)
    private val edges = doubleArrayOf(.0, .85, 1.15, 1.75, 2.5, 3.5, 5.0, 7.0, 10.0, 14.0, 20.0, 32.0, 64.0, Double.POSITIVE_INFINITY)
    private const val FFT_SIZE = 8192

    val bank: Bank by lazy {
        val core = SkitterVoice.entries.associateWith { voice ->
            signature(Skitter.renderProbe(voice, common + mapOf("SKITTER" to 0f, "AGITATE" to 0f, "STATIC" to 0f),
                midi = 48, velocity = 1f, seed = 0, seconds = 1.25f).raw, hz)
        }
        val raw = mutableMapOf<SkitterVoice, FloatArray>()
        val active = SkitterVoice.entries.associateWith { voice ->
            seeds.map { seed ->
                val audio = Skitter.renderProbe(voice, common, midi = 48, velocity = 1f, seed = seed, seconds = 1.25f).raw
                if (seed == 0) raw[voice] = audio
                signature(audio, hz)
            }
        }
        val centers = active.mapValues { (_, signatures) ->
            DoubleArray(signatures.first().joint.size) { i -> signatures.sumOf { it.joint[i] } / signatures.size }
        }
        val radius = active.mapValues { (voice, signatures) -> signatures.maxOf { distance(it.joint, centers.getValue(voice)) } }
        val shapes = active.mapValues { (_, signatures) -> signatures.map(::shapeVector) }
        val shapeCenters = shapes.mapValues { (_, signatures) ->
            DoubleArray(signatures.first().size) { i -> signatures.sumOf { it[i] } / signatures.size }
        }
        val shapeRadius = shapes.mapValues { (voice, signatures) ->
            signatures.maxOf { distance(it, shapeCenters.getValue(voice)) }
        }
        Bank(core, active, centers, radius, raw, shapeCenters, shapeRadius)
    }

    val reactions: Map<SkitterVoice, Reaction> by lazy {
        SkitterVoice.entries.associateWith { voice ->
            val full = bank.active.getValue(voice).first()
            val offRaw = Skitter.renderProbe(voice, common, midi = 48, velocity = 1f, seed = 0,
                seconds = 1.25f, probe = SkitterProbe(agitationDisabled = true)).raw
            val off = signature(offRaw, hz)
            val delta = full.shapes.zip(off.shapes) { a, b -> DoubleArray(a.size) { a[it] - b[it] } }
            val fullCurve = centroidCurve(bank.raw.getValue(voice))
            val offCurve = centroidCurve(offRaw)
            Reaction(delta.sumOf { it.sumOf { value -> abs(value) } } / (2.0 * delta.size), delta,
                fullCurve.zip(offCurve) { a, b -> a - b })
        }
    }

    fun signature(samples: FloatArray, root: Double): Signature {
        val powers = starts.map { start -> bandPowers(samples, (start * Dsp.RATE).roundToInt(), FFT_SIZE, root) }
        val sum = powers.sumOf { it.sum() }
        require(sum > 1e-20 && sum.isFinite()) { "character signature is silent or nonfinite" }
        val joint = powers.flatMap { row -> row.map { it / sum } }.toDoubleArray()
        val shapes = powers.map { row -> val total = row.sum(); DoubleArray(row.size) { row[it] / total } }
        val body = DoubleArray(edges.size - 1) { band -> powers.drop(1).sumOf { it[band] } }
        val bodyTotal = body.sum()
        val low = body.indices.filter { edges[it + 1] <= 3.5 }.sumOf { body[it] } / bodyTotal
        val upper = body.indices.filter { edges[it] >= 5.0 }.sumOf { body[it] } / bodyTotal
        var even = 0.0
        var odd = 0.0
        var weighted = 0.0
        var total = 0.0
        for (start in starts.drop(1)) {
            val power = spectrum(samples, (start * Dsp.RATE).roundToInt(), FFT_SIZE)
            for (bin in 1 until power.size) {
                val ratio = bin.toDouble() * Dsp.RATE / FFT_SIZE / root
                weighted += ratio * power[bin]
                total += power[bin]
                val harmonic = ratio.roundToInt()
                if (harmonic in 2..9 && abs(ratio - harmonic) <= .12) {
                    if (harmonic % 2 == 0) even += power[bin] else odd += power[bin]
                }
            }
        }
        return Signature(joint, shapes, low, upper, even / odd.coerceAtLeast(1e-30), weighted / total)
    }

    private fun bandPowers(samples: FloatArray, from: Int, size: Int, root: Double): DoubleArray {
        val power = spectrum(samples, from, size)
        val bands = DoubleArray(edges.size - 1)
        for (bin in 1 until power.size) {
            val ratio = bin.toDouble() * Dsp.RATE / size / root
            val band = edges.indexOfLast { ratio >= it }
            if (band in bands.indices) bands[band] += power[bin]
        }
        return bands
    }

    private fun spectrum(samples: FloatArray, from: Int, size: Int): DoubleArray {
        require(from >= 0 && from + size <= samples.size) { "identity window runs past the active note" }
        val re = FloatArray(size) { i -> samples[from + i] * (.5 - .5 * cos(2.0 * PI * i / (size - 1))).toFloat() }
        val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    private fun centroidCurve(samples: FloatArray): List<Double> {
        val size = 2048
        return (0..100).map { step ->
            val from = (step * .010 * Dsp.RATE).roundToInt()
            val power = spectrum(samples, from, size)
            val total = power.drop(1).sum()
            power.indices.drop(1).sumOf { bin -> bin.toDouble() * Dsp.RATE / size / hz * power[bin] } / total
        }
    }

    /** Total variation: fraction of normalized power redistributed, independent of phase and gain. */
    fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } * .5

    fun reactionDistance(a: Reaction, b: Reaction): Double = a.delta.zip(b.delta).sumOf { (x, y) -> distance(x, y) } / a.delta.size

    /** Equal window weight isolates spectral color from seeded changes in phrase loudness. */
    private fun shapeVector(signature: Signature): DoubleArray = signature.shapes.flatMap { row ->
        row.map { it / signature.shapes.size }
    }.toDoubleArray()

    fun printBank(value: Bank) {
        for (voice in SkitterVoice.entries) {
            val core = value.core.getValue(voice)
            println("CHARACTER_VOICE,$voice,low=${core.lowShare},upper=${core.upperShare},evenOdd=${core.evenOddRatio},centroidRoot=${core.centroidOverRoot},seedRadius=${value.seedRadius.getValue(voice)}")
            println("CHARACTER_SHAPE_VOICE,$voice,seedRadius=${value.shapeSeedRadius.getValue(voice)}")
        }
        for ((i, a) in SkitterVoice.entries.withIndex()) for (b in SkitterVoice.entries.drop(i + 1)) {
            println("CHARACTER_PAIR,$a,$b,core=${distance(value.core.getValue(a).joint, value.core.getValue(b).joint)},active=${distance(value.centers.getValue(a), value.centers.getValue(b))},seedRadius=${maxOf(value.seedRadius.getValue(a), value.seedRadius.getValue(b))}")
            println("CHARACTER_SHAPE_PAIR,$a,$b,core=${distance(shapeVector(value.core.getValue(a)), shapeVector(value.core.getValue(b)))},active=${distance(value.shapeCenters.getValue(a), value.shapeCenters.getValue(b))},seedRadius=${maxOf(value.shapeSeedRadius.getValue(a), value.shapeSeedRadius.getValue(b))}")
        }
    }

    fun printReactions(value: Map<SkitterVoice, Reaction>) {
        for ((voice, reaction) in value) {
            val early = reaction.centroidCurve.take(16)
            val late = reaction.centroidCurve.drop(65)
            val lateMean = late.average()
            val lateRms = sqrt(late.sumOf { (it - lateMean) * (it - lateMean) } / late.size)
            println("CHARACTER_REACTION,$voice,spectral=${reaction.spectralChange},centroidRange=${reaction.centroidRange},earlyExcess=${early.max() - lateMean},lateMotion=$lateRms")
        }
        println("CHARACTER_REACTION_PAIR,FLUTTER,CHIRP,distance=${reactionDistance(value.getValue(SkitterVoice.FLUTTER), value.getValue(SkitterVoice.CHIRP))}")
    }

    @JvmStatic fun main(args: Array<String>) {
        printBank(bank)
        printReactions(reactions)
    }
}
