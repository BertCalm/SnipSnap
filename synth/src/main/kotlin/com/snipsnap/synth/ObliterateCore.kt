package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The approved OBLITERATE destruction core: octave fuzz -> captured fragments -> stereo chop.
 *
 * Numerical port of experiments/obliterate-core/prototype.py at 309825ac0615163db541d0a200fd72ba5990ed97.
 * This is a standalone offline input effect, not a board/engine registration. It has no shimmer,
 * feedback, whole-file reverse or seamless-loop claim. Its 8x FIR, Double SOS state and NumPy-compatible
 * seeded schedule preserve that listening reference instead of substituting the house Float rack.
 * Kotlin starts at AMT .35; the historical Python reference keeps its original .7 default.
 *
 * Zero is an exact same-Snip bypass. Nonzero renders preserve channels independently, duplicate mono
 * to stereo, append exactly .75 s, and apply one linked attenuation-only peak guard. The rectifier's
 * absolute drive intentionally changes with source level; no input-relative or per-grain makeup is
 * applied. Captured spans precede their event onset. Compensated resampling FIR lookahead and the
 * whole-file guard mean the complete processor is offline, not streaming causal.
 *
 * Why it is built this way:
 * - Full-wave rectification supplies actual octave content; 8x filtering reduces nonlinear aliases.
 * - DC removal precedes capture so an offset cannot become repeating grain energy.
 * - Linked recent-history reads and an overlap divisor retain stereo and linear-stage dynamics.
 * - Three reverse grains and a forward scatter fragment share a four-step pitch pattern.
 * - A sample-index musical clock and 3 ms cosine edges give a stereo chop without hard gate clicks.
 *
 * Shape constants are retained from the approved proof, not pedal-model measurements. Linear grain
 * interpolation has no ratio-specific anti-alias resampler; pitched residual highs can still alias.
 * FIR/SOS coefficients are generated values from SciPy 1.17.0 (BSD-3-Clause); the convolution and
 * transposed-direct-form-II sections below independently implement their standard equations.
 */
object ObliterateCore {
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    const val OVERSAMPLE = 8
    const val MAX_TAIL_SECONDS = 0.75
    const val TAIL_FRAMES = 33_075
    const val MAX_INPUT_SECONDS = 12.0
    const val MAX_GRAINS = 1024
    const val PEAK_CEILING = 0.98
    const val MODEL = "obliterate-core-kotlin-r1"
    const val REFERENCE_MODEL = "obliterate-core-python-r1"
    private const val HISTORY_FRAMES = 14_112
    private const val FADE_FRAMES = 176
    private const val FIR_CENTER = 80

    data class Settings(
        val amount: Double = 0.35,
        val bpm: Double = 120.0,
        val division: Int = 16,
        val seed: Long = 20261023L,
    ) {
        init {
            require(amount.isFinite() && amount in 0.0..1.0) { "amount must be finite and in 0..1" }
            require(bpm.isFinite() && bpm in 40.0..240.0) { "BPM must be finite and in 40..240" }
            require(division in setOf(4, 8, 16, 32)) { "division must be 4, 8, 16 or 32" }
            require(seed in 0L..0xffff_ffffL) { "seed must be an unsigned 32-bit integer" }
        }
    }

    data class GrainEvent(
        val onsetFrame: Int,
        val lengthFrames: Int,
        val sourceFirstFrame: Double,
        val sourceLastFrame: Double,
        val pitchRatio: Double,
        val semitones: Double,
        val reverse: Boolean,
        val balance: Double,
    )

    data class Metrics(val peak: Double, val rms: Double, val channelMean: List<Double>)
    data class StageMetrics(val name: String, val metrics: Metrics)
    data class Diagnostics(
        val settings: Settings,
        val stages: Int,
        val inputFrames: Int,
        val inputChannels: Int,
        val outputFrames: Int,
        val outputChannels: Int,
        val tailFrames: Int,
        val bypass: Boolean,
        val guardGain: Double,
        val preGuardPeak: Double,
        val grainEvents: List<GrainEvent>,
        val inputMetrics: Metrics,
        /** Metrics of the Double render before the final Snip Float conversion. */
        val outputMetrics: Metrics,
        val stageMetrics: List<StageMetrics>,
        val clockStepSeconds: Double,
        val model: String = MODEL,
        val referenceModel: String = REFERENCE_MODEL,
        val clockOriginFrame: Int = 0,
        val compensatedFirLatencyFrames: Int = 0,
        val streamingCausal: Boolean = false,
        val grainReadsBeforeEventOnset: Boolean = true,
        val shimmer: Boolean = false,
        val feedback: Boolean = false,
        val seamlessLoopClaim: Boolean = false,
    )

    data class RenderResult(val audio: Snip, val diagnostics: Diagnostics)

    /**
     * Print one mono/stereo 44.1 kHz hit. Stages 1/2/3 mean fuzz / +fragments / +stereo chop.
     * Input is finite normalized PCM, nonempty and at most 12 s; every call starts fresh state.
     */
    fun render(snip: Snip, settings: Settings = Settings(), stages: Int = 3): RenderResult {
        require(stages in 1..3) { "stages must be 1, 2 or 3" }
        require(snip.sampleRate == RATE) { "OBLITERATE core needs 44.1 kHz input" }
        require(snip.frameCount in 1..(RATE * MAX_INPUT_SECONDS).toInt()) { "input must be nonempty and at most 12 s" }
        require(snip.samples.all { it.isFinite() && abs(it) <= 1f }) { "input must be finite PCM in -1..1" }
        val input = Array(snip.channels) { channel ->
            DoubleArray(snip.frameCount) { frame -> snip.samples[frame * snip.channels + channel].toDouble() }
        }
        val inputMetrics = metrics(input)
        if (settings.amount == 0.0) {
            return RenderResult(snip, Diagnostics(
                settings, stages, snip.frameCount, snip.channels, snip.frameCount, snip.channels,
                0, true, 1.0, inputMetrics.peak, emptyList(), inputMetrics, inputMetrics,
                emptyList(), stepSeconds(settings),
            ))
        }
        val frames = snip.frameCount + TAIL_FRAMES
        var audio = Array(2) { channel ->
            fuzz(input[minOf(channel, snip.channels - 1)].copyOf(frames), settings.amount)
        }
        val stageMetrics = mutableListOf(StageMetrics("octave_fuzz", metrics(audio)))
        val events = if (stages >= 2) schedule(snip.frameCount, settings) else emptyList()
        if (stages >= 2) {
            audio = fragments(audio, settings, events)
            stageMetrics += StageMetrics("captured_fragments", metrics(audio))
        }
        if (stages >= 3) {
            val gains = chopGains(frames, settings)
            for (channel in 0..1) for (frame in 0 until frames) audio[channel][frame] *= gains[channel][frame]
            stageMetrics += StageMetrics("stereo_chop", metrics(audio))
        }
        // Match np.linspace(1,0,176), including its explicit last endpoint.
        val fadeStep = -1.0 / (FADE_FRAMES - 1)
        for (i in 0 until FADE_FRAMES) {
            val gain = if (i == FADE_FRAMES - 1) 0.0 else i * fadeStep + 1.0
            for (channel in 0..1) audio[channel][frames - FADE_FRAMES + i] *= gain
        }
        val preGuardPeak = metrics(audio).peak
        val guardGain = if (preGuardPeak > 0.0) minOf(1.0, PEAK_CEILING / preGuardPeak) else 1.0
        val output = FloatArray(frames * 2)
        for (frame in 0 until frames) for (channel in 0..1) {
            audio[channel][frame] *= guardGain
            check(audio[channel][frame].isFinite()) { "non-finite OBLITERATE output" }
            output[frame * 2 + channel] = audio[channel][frame].toFloat()
        }
        return RenderResult(Snip(output, 2, RATE), Diagnostics(
            settings, stages, snip.frameCount, snip.channels, frames, 2, TAIL_FRAMES, false,
            guardGain, preGuardPeak, events.toList(), inputMetrics, metrics(audio),
            stageMetrics.toList(), stepSeconds(settings),
        ))
    }

    /** Raw mono stage for reference/signal probes: no tail pad, guard or level target. */
    internal fun fuzz(samples: DoubleArray, amount: Double): DoubleArray {
        val high = interpolate8(samples)
        val drive = 1.0 + 11.0 * amount
        val mix = 0.9 * amount
        for (i in high.indices) {
            val x = high[i]
            high[i] = (1.0 - mix) * x + mix * (0.5 * tanh(drive * abs(x)))
        }
        filterInPlace(high, FUZZ_HIGH_PASS)
        filterInPlace(high, FUZZ_LOW_PASS)
        return decimate8(high)
    }

    /** Fresh NumPy-compatible PCG64 schedule, computed before any grain audio reads. */
    internal fun schedule(inputFrames: Int, settings: Settings): List<GrainEvent> {
        require(inputFrames in 1..(RATE * MAX_INPUT_SECONDS).toInt()) { "input exceeds schedule budget" }
        val rng = ObliteratePcg64(settings.seed)
        val amount = settings.amount
        val step = stepSeconds(settings) * RATE
        val hop = step * 0.5
        val count = maxOf(0, ceil(inputFrames / hop).toInt() - 1)
        require(count <= MAX_GRAINS) { "grain schedule exceeds fixed event budget" }
        val preferredLength = Math.rint((0.075 + 0.060 * amount) * RATE).toInt()
        val pitchPattern = intArrayOf(0, 7, -12, 12)
        val events = ArrayList<GrainEvent>(count)
        repeat(count) { index ->
            val onset = Math.rint((index + 1) * hop + rng.uniform(-1.0, 1.0) * 0.10 * amount * step)
                .toInt().coerceIn(1, inputFrames - 1)
            val lag = (0.012 + rng.uniform(0.0, 0.055 * amount)) * RATE
            val latest = onset - maxOf(1.0, lag)
            val earliest = maxOf(0.0, (onset - HISTORY_FRAMES).toDouble())
            val semitones = pitchPattern[index % 4] * amount
            val ratio = 2.0.pow(semitones / 12.0)
            val length = minOf(preferredLength, floor((latest - earliest) / ratio).toInt() + 1)
            val balance = rng.uniform(-0.35, 0.35) * amount
            if (length >= 32) {
                val reverse = index % 4 != 3
                val low = latest - (length - 1) * ratio
                events += GrainEvent(onset, length, if (reverse) latest else low,
                    if (reverse) low else latest, ratio, semitones, reverse, balance)
            }
        }
        return events.toList()
    }

    /** Planar stereo stage; one capture inventory and overlap divisor serve both channels. */
    internal fun fragments(audio: Array<DoubleArray>, settings: Settings, events: List<GrainEvent>): Array<DoubleArray> {
        require(audio.size == 2 && audio[0].size == audio[1].size) { "fragments need planar stereo" }
        val frames = audio[0].size
        val wet = Array(2) { DoubleArray(frames) }
        val overlap = DoubleArray(frames)
        for (event in events) {
            val sourceStep = (event.sourceLastFrame - event.sourceFirstFrame) / (event.lengthFrames - 1)
            val leftGain = 1.0 - maxOf(event.balance, 0.0)
            val rightGain = 1.0 + minOf(event.balance, 0.0)
            for (i in 0 until event.lengthFrames) {
                // Match linspace's explicit endpoint instead of accumulating read position.
                val position = if (i == event.lengthFrames - 1) event.sourceLastFrame
                    else i * sourceStep + event.sourceFirstFrame
                val low = floor(position).toInt()
                val fraction = position - low
                // np.hanning uses a centered integer sequence; retain that arithmetic.
                val window = 0.5 + 0.5 * cos(PI * (1 - event.lengthFrames + 2 * i) / (event.lengthFrames - 1))
                val target = event.onsetFrame + i
                wet[0][target] += (audio[0][low] * (1.0 - fraction) + audio[0][low + 1] * fraction) * window * leftGain
                wet[1][target] += (audio[1][low] * (1.0 - fraction) + audio[1][low + 1] * fraction) * window * rightGain
                overlap[target] += window
            }
        }
        val output = Array(2) { DoubleArray(frames) }
        for (frame in 0 until frames) {
            val divisor = maxOf(1.0, overlap[frame])
            val mix = 0.82 * settings.amount * engagement(frame)
            for (channel in 0..1) {
                val fragment = wet[channel][frame] / divisor
                output[channel][frame] = audio[channel][frame] * (1.0 - mix) + fragment * mix
            }
        }
        for (channel in 0..1) filterInPlace(output[channel], FRAGMENT_HIGH_PASS)
        return output
    }

    /** Planar L/R gains from absolute sample time, never an accumulated rounded period. */
    internal fun chopGains(frames: Int, settings: Settings): Array<DoubleArray> {
        val step = stepSeconds(settings)
        val duty = 0.58 - 0.13 * settings.amount
        val edge = minOf(0.003, step * duty * 0.25)
        return Array(2) { channel ->
            val offset = if (channel == 0) 0.0 else 0.5 * settings.amount
            DoubleArray(frames) { frame ->
                val time = frame.toDouble() / RATE
                val phase = ((time / step + offset) % 1.0) * step
                val attack = (phase / edge).coerceIn(0.0, 1.0)
                val release = ((duty * step - phase) / edge).coerceIn(0.0, 1.0)
                val rise = sin(PI * 0.5 * attack)
                val fall = sin(PI * 0.5 * release)
                val gate = rise * rise * (fall * fall)
                1.0 - settings.amount * engagement(frame) * (1.0 - gate)
            }
        }
    }

    private fun stepSeconds(settings: Settings) = 60.0 / settings.bpm * 4.0 / settings.division

    private fun engagement(frame: Int): Double {
        val ramp = ((frame.toDouble() / RATE - 0.018) / 0.030).coerceIn(0.0, 1.0)
        return 0.5 - 0.5 * cos(PI * ramp)
    }

    private fun metrics(audio: Array<DoubleArray>): Metrics {
        var peak = 0.0
        var squares = 0.0
        val means = audio.map { channel ->
            var sum = 0.0
            for (sample in channel) {
                peak = maxOf(peak, abs(sample))
                squares += sample * sample
                sum += sample
            }
            sum / channel.size
        }
        return Metrics(peak, sqrt(squares / (audio.size.toDouble() * audio[0].size)), means)
    }

    /** Centered 8x polyphase FIR with zero extension, compensated like resample_poly. */
    private fun interpolate8(input: DoubleArray): DoubleArray {
        val output = DoubleArray(input.size * OVERSAMPLE)
        for (frame in output.indices) {
            val first = maxOf(0, ceil((frame - FIR_CENTER).toDouble() / OVERSAMPLE).toInt())
            val last = minOf(input.lastIndex, (frame + FIR_CENTER) / OVERSAMPLE)
            var sum = 0.0
            for (source in first..last) sum += input[source] * (FIR[frame - source * OVERSAMPLE + FIR_CENTER] * OVERSAMPLE)
            output[frame] = sum
        }
        return output
    }

    /** The same unscaled 161 taps and compensated center, now taking each eighth sample. */
    private fun decimate8(input: DoubleArray): DoubleArray {
        val output = DoubleArray(input.size / OVERSAMPLE)
        for (frame in output.indices) {
            val center = frame * OVERSAMPLE
            val first = maxOf(0, center - FIR_CENTER)
            val last = minOf(input.lastIndex, center + FIR_CENTER)
            var sum = 0.0
            for (source in first..last) sum += input[source] * FIR[center - source + FIR_CENTER]
            output[frame] = sum
        }
        return output
    }

    private fun filterInPlace(audio: DoubleArray, sections: Array<DoubleArray>) {
        for (section in sections) {
            var z1 = 0.0
            var z2 = 0.0
            for (i in audio.indices) {
                val x = audio[i]
                val y = section[0] * x + z1
                z1 = section[1] * x - section[4] * y + z2
                z2 = section[2] * x - section[5] * y
                audio[i] = y
            }
        }
    }

    // SciPy 1.17.0: firwin(161, 1/8, window=("kaiser",10)), normalized sum=1.
    // Float64 little-endian taps SHA-256: adcb7585f715007637d642dbf3e332d48408a8940defe596d6005c9d1a1aa978
    private val FIR = doubleArrayOf(
        -1.7305402930470035e-21, -9.443323351679234e-07, -2.7381128262576943e-06, -5.2635262476957195e-06,
        -8.021640800978723e-06, -1.0102076456098408e-05, -1.0277485931311831e-05, -7.244981923534309e-06,
        2.6726138722904175e-20, 1.1716726747390287e-05, 2.7001082972968266e-05, 4.352626016149042e-05,
        5.757301516023548e-05, 6.444595854346086e-05, 5.9301587469161276e-05, 3.83147010805368e-05,
        -1.1635185035191548e-19, -5.3589981831253816e-05, -0.00011612309610819722, -0.00017702153952088993,
        -0.00022249738168380503, -0.00023764255118410784, -0.00020939507763949748, -0.00012995389993950175,
        3.3259758366565944e-19, 0.000169040503523223, 0.00035443605623169467, 0.0005238474156845291,
        0.0006394830702540811, 0.0006644315956684495, 0.0005703625632147476, 0.00034531628252908034,
        -7.399169301175948e-19, -0.0004290272983260366, -0.0008806065741275108, -0.0012753673055770141,
        -0.0015270614128019108, -0.00155762370652591, -0.0013137606138810657, -0.0007821433215774968,
        1.3743950394239434e-18, 0.0009418018477681213, 0.0019051764922752954, 0.002721278006636196,
        0.003215716656453321, 0.0032393826409001588, 0.0027001413063415937, 0.0015897120305456798,
        -2.2115051623803498e-18, -0.0018758395830664634, -0.0037602942785029876, -0.005326208624208556,
        -0.006245969298363503, -0.006248734366026578, -0.005176913232832671, -0.00303196201735421,
        3.149672202418144e-18, 0.0035501038833690808, 0.007099846707611789, 0.01004432130388647,
        0.01177932105376698, 0.011801369207314396, 0.00980628864103144, 0.005770475161849025,
        -4.02330642910542e-18, -0.006863885405422722, -0.013888673654324217, -0.01994276028642707,
        -0.023827194253535994, -0.024431654345877272, -0.020894400382407294, -0.012743787145647605,
        4.646470915383698e-18, 0.01678000250159524, 0.03652425950106775, 0.05773529426389977,
        0.07863884331641602, 0.09737472625995218, 0.11220628962108421, 0.12172158317113957,
        0.12499990479419411, 0.12172158317113957, 0.11220628962108421, 0.09737472625995218,
        0.07863884331641602, 0.05773529426389977, 0.03652425950106775, 0.01678000250159524,
        4.646470915383698e-18, -0.012743787145647605, -0.020894400382407294, -0.024431654345877272,
        -0.023827194253535994, -0.01994276028642707, -0.013888673654324217, -0.006863885405422722,
        -4.02330642910542e-18, 0.005770475161849025, 0.00980628864103144, 0.011801369207314396,
        0.01177932105376698, 0.01004432130388647, 0.007099846707611789, 0.0035501038833690808,
        3.149672202418144e-18, -0.00303196201735421, -0.005176913232832671, -0.006248734366026578,
        -0.006245969298363503, -0.005326208624208556, -0.0037602942785029876, -0.0018758395830664634,
        -2.2115051623803498e-18, 0.0015897120305456798, 0.0027001413063415937, 0.0032393826409001588,
        0.003215716656453321, 0.002721278006636196, 0.0019051764922752954, 0.0009418018477681213,
        1.3743950394239434e-18, -0.0007821433215774968, -0.0013137606138810657, -0.00155762370652591,
        -0.0015270614128019108, -0.0012753673055770141, -0.0008806065741275108, -0.0004290272983260366,
        -7.399169301175948e-19, 0.00034531628252908034, 0.0005703625632147476, 0.0006644315956684495,
        0.0006394830702540811, 0.0005238474156845291, 0.00035443605623169467, 0.000169040503523223,
        3.3259758366565944e-19, -0.00012995389993950175, -0.00020939507763949748, -0.00023764255118410784,
        -0.00022249738168380503, -0.00017702153952088993, -0.00011612309610819722, -5.3589981831253816e-05,
        -1.1635185035191548e-19, 3.83147010805368e-05, 5.9301587469161276e-05, 6.444595854346086e-05,
        5.757301516023548e-05, 4.352626016149042e-05, 2.7001082972968266e-05, 1.1716726747390287e-05,
        2.6726138722904175e-20, -7.244981923534309e-06, -1.0277485931311831e-05, -1.0102076456098408e-05,
        -8.021640800978723e-06, -5.2635262476957195e-06, -2.7381128262576943e-06, -9.443323351679234e-07,
        -1.7305402930470035e-21,
    )
    private val FUZZ_HIGH_PASS = arrayOf(
        doubleArrayOf(0.9996222751877729, -1.9992445503755458, 0.9996222751877729, 1.0, -1.999244407699507, 0.9992446930515847),
    )
    private val FUZZ_LOW_PASS = arrayOf(
        doubleArrayOf(3.3737620901862815e-05, 6.747524180372563e-05, 3.3737620901862815e-05, 1.0, -1.720651631616488, 0.742993702401115),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.8607179206540119, 0.8848787041209245),
    )
    private val FRAGMENT_HIGH_PASS = arrayOf(
        doubleArrayOf(0.997987115675119, -1.995974231350238, 0.997987115675119, 1.0, -1.9959701796428286, 0.9959782830576475),
    )
}
