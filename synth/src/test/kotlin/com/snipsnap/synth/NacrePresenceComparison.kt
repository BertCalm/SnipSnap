package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * REVIEW-led listening prototype: the preferred original duet -> distinct returning paths -> rest.
 * Coral is a compact, soft three-answer cluster; seaweed is a later set of gated transmission
 * windows. These are invented venue gestures, not measurements or a water simulation.
 *
 * This testkit operation starts AFTER frozen model 1's 4x render and output conditioning. It
 * neither changes its excitation/strings/bodies/sympathetics nor defines a production model.
 * Only its conditioned venue stem is replaced. Static integer delays and fixed one-poles are
 * feed-forward; smooth 0.1..1 apertures act only on delayed returns. No feedback, moving delay,
 * nonlinear excitation, added pitch or bright resonator is involved.
 *
 * Why this is built this way: later paths can answer after the foreground falls, instead of
 * remaining masked inside its attack. Low-passes retain the preferred soft source. One common
 * return gain sets the larger return RMS to at most .45 of the unchanged foreground, then is
 * reduced only if either candidate needs headroom. It does not level the three clips separately.
 * These constants are listening hypotheses; guards prove the route, not musical acceptance.
 */
internal object NacrePresenceComparison {
    const val RATE = 44_100
    const val REFERENCE_RESOURCE = "/nacre-model1-reference.json"
    const val REFERENCE_LISTENING_GAIN = 3.2143452167510986
    const val LISTENING_PEAK_CEILING = .92
    const val TARGET_MAX_RETURN_FOREGROUND_RMS = .45

    data class Path(
        val delaySeconds: Double,
        val weight: Double,
        val lowPassHz: Double,
        val apertureHz: Double = 0.0,
        val apertureDepth: Double = 0.0,
        val aperturePhaseRadians: Double = 0.0,
    ) {
        val delayFrames: Int get() = (delaySeconds * RATE).roundToInt()
    }

    /** [native] is the exact original model-1 source render, never variant playback. */
    data class Clip(
        val id: String,
        val title: String,
        val patch: NacrePatch,
        val raw: FloatArray,
        val native: Snip,
        val stems: Map<String, FloatArray>,
        val events: List<Nacre.Event>,
        val diagnostics: Map<String, Double>,
        val venueGeometry: Float,
        val renderMilliseconds: Double,
        val returnSamples: FloatArray,
        val originalReturn: FloatArray,
        val returnGain: Double,
        val operation: String,
        val pathDetails: List<Path>,
    )

    private fun seaweedPaths(moving: Boolean): List<Path> = listOf(.22, .41, .64).mapIndexed { i, delay ->
        val peakSeconds = doubleArrayOf(.40, .65, .85)[i]
        Path(delay, doubleArrayOf(.52, .31, .17)[i], doubleArrayOf(1200.0, 900.0, 650.0)[i],
            apertureHz = if (moving) 2.0 else 0.0, apertureDepth = .90,
            // At 0 Hz and pi/2 the same aperture is frozen at its mean gain, .55.
            aperturePhaseRadians = if (moving) -2.0 * PI * 2.0 * peakSeconds else PI / 2.0)
    }

    /** The false case is a diagnostic control, not a fourth listening candidate or patch macro. */
    fun render(seaweedMotion: Boolean = true): List<Clip> {
        val started = System.nanoTime()
        val reference = Json.parse(checkNotNull(javaClass.getResourceAsStream(REFERENCE_RESOURCE)) {
            "Missing frozen model-1 reference"
        }.bufferedReader().use { it.readText() }).obj()
        val patch = NacrePatch.fromJsonValue(reference.getValue("recipe")) as NacrePatch
        check(patch.model == 1 && patch.midi == 48 && patch.seed == 7L && patch.velocity == 1f)
        val original = NacreModels.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, model = 1)
        check(samplesHash(original.raw) == reference.getValue("prelevelSamplesSha256").str()) {
            "The presence study changed the published original prelevel source"
        }
        check(samplesHash(original.snip.samples) == reference.getValue("nativeSamplesSha256").str()) {
            "The presence study changed the published original native source"
        }
        val originalMilliseconds = (System.nanoTime() - started) / 1e6
        val originalReturn = original.stems.getValue("venue")
        val foreground = DoubleArray(original.raw.size) { original.raw[it].toDouble() - originalReturn[it] }
        val foregroundEnergy = foreground.sumOf { it * it }
        check(foregroundEnergy > 0.0)
        val bass = original.stems.getValue("bass")
        val upper = original.stems.getValue("upper")
        val source = FloatArray(original.raw.size) { bass[it] + upper[it] }
        val coralPaths = listOf(Path(.10, .52, 1600.0), Path(.21, .31, 1200.0), Path(.34, .17, 900.0))
        val movingPaths = seaweedPaths(true)
        val stillPaths = seaweedPaths(false)
        val pathStart = System.nanoTime()
        val rawCoral = paths(source, coralPaths)
        val coralMilliseconds = (System.nanoTime() - pathStart) / 1e6
        val weedStart = System.nanoTime()
        val rawMoving = paths(source, movingPaths)
        val rawStill = paths(source, stillPaths)
        val weedMilliseconds = (System.nanoTime() - weedStart) / 1e6
        val maxReturnEnergy = maxOf(energy(rawCoral), energy(rawMoving))
        check(maxReturnEnergy > 0.0)
        val rmsGain = TARGET_MAX_RETURN_FOREGROUND_RMS * sqrt(foregroundEnergy / maxReturnEnergy)

        // Solve |foreground + g*return| < ceiling at every frame. This is a single scalar
        // attenuation shared by the family, not limiting, clipping or per-clip normalization.
        val rawCeiling = LISTENING_PEAK_CEILING / REFERENCE_LISTENING_GAIN
        check(original.raw.all { abs(it) < rawCeiling }) { "The anchored original itself lacks peak headroom" }
        var peakGain = Double.POSITIVE_INFINITY
        // Include the still control so toggling motion never changes the calibration or coral.
        for (candidate in listOf(rawCoral, rawMoving, rawStill)) for (i in candidate.indices) {
            val r = candidate[i].toDouble()
            if (r > 0.0) peakGain = minOf(peakGain, (rawCeiling - foreground[i]) / r)
            else if (r < 0.0) peakGain = minOf(peakGain, (-rawCeiling - foreground[i]) / r)
        }
        check(peakGain > 0.0)
        val commonGain = minOf(rmsGain, peakGain * .99999)
        val commonDiagnostics = mapOf(
            "testkitPostmixOnly" to 1.0,
            "testkitSourceModel" to 1.0,
            "testkitForegroundRms" to sqrt(foregroundEnergy / foreground.size),
            "testkitTargetMaximumReturnForegroundRms" to TARGET_MAX_RETURN_FOREGROUND_RMS,
            "testkitRmsCalibratedReturnGain" to rmsGain,
            "testkitPeakBoundReturnGain" to peakGain,
            "testkitCommonReturnGain" to commonGain,
            "testkitReferenceListeningGain" to REFERENCE_LISTENING_GAIN,
            "testkitMaximumNonVenueStemDifference" to 0.0,
            "testkitMaximumEventDifference" to 0.0,
        )

        fun candidate(id: String, title: String, geometry: Float, rawReturn: FloatArray,
                      details: List<Path>, milliseconds: Double): Clip {
            val returns = FloatArray(rawReturn.size) { (commonGain * rawReturn[it]).toFloat() }
            val samples = FloatArray(original.raw.size) { i ->
                (original.raw[i].toDouble() - originalReturn[i].toDouble() + returns[i].toDouble()).toFloat()
            }
            check(samples.all { it.isFinite() && abs(it) * REFERENCE_LISTENING_GAIN < LISTENING_PEAK_CEILING }) {
                "$id violates the anchored family peak bound"
            }
            val deltaEnergy = returns.indices.sumOf { (returns[it].toDouble() - originalReturn[it]).let { d -> d * d } }
            val diagnostics = original.diagnostics + commonDiagnostics + mapOf(
                "testkitReturnForegroundRmsRatio" to sqrt(energy(returns) / foregroundEnergy),
                "testkitDeltaForegroundRmsRatio" to sqrt(deltaEnergy / foregroundEnergy),
                "testkitReturnEnergyAfterPointEightSeconds" to energy(returns, (.8 * RATE).toInt()),
                "testkitReturnEnergyAfterPointEightFraction" to energy(returns, (.8 * RATE).toInt()) / energy(returns),
                "testkitFullEnergyAfterPointEightFraction" to energy(samples, (.8 * RATE).toInt()) / energy(samples),
                "testkitEnergy99Seconds" to energyTime(samples, .99),
                "testkitReturnEnergy99Seconds" to energyTime(returns, .99),
                "testkitMaxPathDelaySeconds" to details.maxOf { it.delayFrames.toDouble() / RATE },
                "testkitRawPeak" to samples.maxOf { abs(it).toDouble() },
                "testkitSeaweedMotion" to if (id == "seaweed" && seaweedMotion) 1.0 else 0.0,
            )
            return Clip(id, title, patch.copy(name = title), samples, original.snip,
                original.stems + ("venue" to returns), original.events, diagnostics, geometry,
                milliseconds, returns, originalReturn, commonGain,
                "Float(original.raw.double - originalReturn.double + Float(commonReturnGain * rawPathReturn).double)", details)
        }

        return listOf(
            Clip("original", "Original / model 1", patch, original.raw, original.snip, original.stems,
                original.events, original.diagnostics + commonDiagnostics + mapOf(
                    "testkitPostmixOnly" to 0.0,
                    "testkitReturnForegroundRmsRatio" to sqrt(energy(originalReturn) / foregroundEnergy),
                    "testkitDeltaForegroundRmsRatio" to 0.0,
                    "testkitEnergy99Seconds" to energyTime(original.raw, .99)),
                patch.macros.getValue("VENUE"), originalMilliseconds, originalReturn, originalReturn,
                1.0, "Identity: published original.raw; no return replacement", emptyList()),
            candidate("coral", "Coral / close soft answers", 0f, rawCoral, coralPaths, coralMilliseconds),
            candidate("seaweed", "Seaweed / later opening paths", 1f,
                if (seaweedMotion) rawMoving else rawStill,
                if (seaweedMotion) movingPaths else stillPaths, weedMilliseconds),
        )
    }

    private fun paths(source: FloatArray, paths: List<Path>): FloatArray {
        val out = DoubleArray(source.size)
        for (path in paths) {
            check(path.delayFrames > 0 && path.weight >= 0.0 && path.lowPassHz in 1.0..(RATE / 2.0))
            val coefficient = 1.0 - exp(-2.0 * PI * path.lowPassHz / RATE)
            var filtered = 0.0
            for (i in out.indices) {
                val input = if (i >= path.delayFrames) source[i - path.delayFrames].toDouble() else 0.0
                filtered += coefficient * (input - filtered)
                // Applied at the outlet: each delayed copy encounters a different opening.
                val aperture = 1.0 - path.apertureDepth + path.apertureDepth *
                    (.5 + .5 * cos(2.0 * PI * path.apertureHz * i / RATE + path.aperturePhaseRadians))
                out[i] += path.weight * filtered * aperture
            }
        }
        return FloatArray(out.size) { out[it].toFloat() }
    }

    private fun energy(samples: FloatArray, from: Int = 0): Double =
        (from until samples.size).sumOf { samples[it].toDouble() * samples[it] }

    private fun energyTime(samples: FloatArray, fraction: Double): Double {
        val target = energy(samples) * fraction
        var sum = 0.0
        for (i in samples.indices) { sum += samples[i].toDouble() * samples[i]; if (sum >= target) return i.toDouble() / RATE }
        return samples.size.toDouble() / RATE
    }

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
