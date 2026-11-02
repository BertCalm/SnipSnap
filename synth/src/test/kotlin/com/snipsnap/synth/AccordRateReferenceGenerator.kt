package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Independent deterministic-contact 8x comparison. Whole-waveform error includes
 * interpolation, kernel rounding and rate-dependent linear filter phase; it is
 * explicitly NOT reported as alias energy. The Hann transform comparison does
 * isolate sampled-kernel folding against its continuous, support-matched Hann.
 */
object AccordRateReferenceGenerator {
    private const val SOURCE = 8.0
    private const val NATIVE_RATE = 176400
    private val damping = floatArrayOf(1.9616f, 1.6629f, 1.1111f, .3902f)
    private data class Render(val samples: FloatArray, val aboveNyquistBeforeHouseDb: Double,
        val aboveNyquistAfterHouseDb: Double, val widths: List<Map<String, Any>>)

    private fun dbRatio(numerator: Double, denominator: Double, power: Boolean = false): Double =
        (if (power) 10.0 else 20.0) * log10(max(numerator, 1e-30) / max(denominator, 1e-30))

    /** Same physical analysis-window length at4x and8x, with no abrupt FFT cuts. */
    private fun highBandDb(samples: FloatArray, rate: Int): Double {
        val n = if (rate == NATIVE_RATE) 32768 else 65536
        var high = 0.0
        var total = 0.0
        var at = 0
        while (at + n <= min(samples.size, (SOURCE * rate).toInt())) {
            val re = FloatArray(n) { j -> (samples[at + j] * (.5 - .5 * cos(2 * PI * j / (n - 1)))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (j in 1 until n / 2) {
                val energy = re[j].toDouble() * re[j] + im[j].toDouble() * im[j]
                total += energy
                if (j.toDouble() * rate / n >= Dsp.RATE / 2.0) high += energy
            }
            at += n
        }
        return dbRatio(high, total, power = true)
    }

    private fun reference(config: AccordAcoustics.Config, events: List<Accord.ContactEvent>,
        rate: Int, matchedSupport: Boolean, paceHz: Double): Render {
        val outputFrames = AccordAcoustics.renderFrames(config, SOURCE)
        val force = DoubleArray(outputFrames * (rate / Dsp.RATE))
        val kernels = HashMap<Int, DoubleArray>()
        val widths = ArrayList<Map<String, Any>>()
        for (direction in listOf(-1, 1)) {
            val nominal = config.widthMs * if (direction > 0) 1.0 else 1.12
            val nativeFrames = max(4, Math.rint(nominal * .001 * NATIVE_RATE).toInt())
            val frames = if (matchedSupport && rate != NATIVE_RATE) 2 * (nativeFrames - 1) + 1
                         else max(4, Math.rint(nominal * .001 * rate).toInt())
            val kernel = DoubleArray(frames) { .5 - .5 * cos(2 * PI * it / (frames - 1)) }
            val sum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= sum
            kernels[direction] = kernel
            widths += linkedMapOf("direction" to direction, "nominalWidthMs" to nominal,
                "frames" to frames, "supportMs" to (frames - 1) * 1000.0 / rate,
                "nativeFrames" to nativeFrames, "nativeSupportMs" to (nativeFrames - 1) * 1000.0 / NATIVE_RATE)
        }
        for (event in events) {
            // Public events retain exact canonical roots. Reproject at the
            // independent rate rather than quantizing already rendered audio.
            val position = (event.cycle - 8.0) * rate / paceHz
            val start = floor(position).toInt()
            val fraction = position - start
            val kernel = kernels.getValue(event.direction)
            val amplitude = sqrt(min(event.canonicalSpeed / .8, 2.0)) / 15
            for (j in kernel.indices) {
                val index = start + j
                if (index < force.size) force[index] += kernel[j] * amplitude * (1 - fraction)
                if (index + 1 < force.size) force[index + 1] += kernel[j] * amplitude * fraction
            }
        }
        for (i in force.indices) {
            val time = i.toDouble() / rate
            val gate = when {
                time >= SOURCE -> 0.0
                time <= SOURCE - .060 -> 1.0
                else -> .5 + .5 * cos(PI * (time - SOURCE + .060) / .060)
            }
            force[i] *= config.pulseGain * gate
        }
        // Independent variable-rate form of the native Double TPT force filter.
        val g = tan(PI * 10_000.0 / rate)
        for (k in damping) {
            val a1 = 1 / (1 + g * (g + k.toDouble()))
            val a2 = g * a1
            val a3 = g * a2
            var ic1 = 0.0; var ic2 = 0.0
            for (i in force.indices) {
                val x = force[i]
                if (abs(x) < 1e-100 && abs(ic1) < 1e-100 && abs(ic2) < 1e-100) {
                    ic1 = 0.0; ic2 = 0.0; force[i] = 0.0
                } else {
                    val v3 = x - ic2
                    val v1 = a1 * ic1 + a2 * v3
                    val v2 = ic2 + a2 * ic1 + a3 * v3
                    ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; force[i] = v2
                }
            }
        }
        val modes = config.tongue + config.body.map { it.copy(gain = it.gain * config.bodyGain) }
        val a1 = DoubleArray(8); val a2 = DoubleArray(8); val gains = DoubleArray(8)
        val y1 = DoubleArray(8); val y2 = DoubleArray(8)
        for (j in modes.indices) {
            val rho = kotlin.math.exp(-6.9078 / (modes[j].t60 * rate))
            val angle = 2 * PI * config.rootHz * modes[j].ratio / rate
            a1[j] = 2 * rho * cos(angle); a2[j] = -rho * rho
            gains[j] = modes[j].gain * sin(angle)
        }
        val raw = FloatArray(force.size)
        for (i in raw.indices) {
            var sum = 0.0
            for (j in modes.indices) {
                val sample = gains[j] * force[i] + a1[j] * y1[j] + a2[j] * y2[j]
                y2[j] = y1[j]; y1[j] = sample; sum += sample
            }
            raw[i] = sum.toFloat()
        }
        val before = highBandDb(raw, rate)
        Tide.bandLimit(raw, rate)
        val after = highBandDb(raw, rate)
        var signal = Snip(raw, 1, rate)
        while (signal.sampleRate > Dsp.RATE) signal = Resampler.resample(signal, signal.sampleRate / 2)
        val samples = signal.samples
        var mean = 0.0
        for (sample in samples) mean += sample
        mean /= samples.size
        val hp = Dsp.FixedOnePole(20f)
        for (i in samples.indices) {
            val centered = (samples[i] - mean).toFloat()
            samples[i] = ((centered - hp.lp(centered)) * config.ampGain).toFloat()
        }
        return Render(samples, before, after, widths)
    }

    /** Continuous Hann transform: phase*sinc(x)/(1-x²), normalized to DC1. */
    private fun kernelFolding(widthMs: Double): Map<String, Any> {
        val frames = max(4, Math.rint(widthMs * .001 * NATIVE_RATE).toInt())
        val kernel = DoubleArray(frames) { .5 - .5 * cos(2 * PI * it / (frames - 1)) }
        val sum = kernel.sum()
        for (j in kernel.indices) kernel[j] /= sum
        val support = (frames - 1).toDouble() / NATIVE_RATE
        var worst = 0.0
        var worstHz = 0.0
        var worstModalBand = 0.0
        for (frequency in 20..10_000 step 2) {
            val x = frequency * support
            val magnitude = if (abs(x - 1.0) < 1e-10) .5 else sin(PI * x) / (PI * x * (1 - x * x))
            val exactRe = magnitude * cos(PI * x)
            val exactIm = -magnitude * sin(PI * x)
            var sampledRe = 0.0; var sampledIm = 0.0
            for (j in kernel.indices) {
                val phase = 2 * PI * frequency * j / NATIVE_RATE
                sampledRe += kernel[j] * cos(phase)
                sampledIm -= kernel[j] * sin(phase)
            }
            val difference = kotlin.math.hypot(sampledRe - exactRe, sampledIm - exactIm)
            if (difference > worst) { worst = difference; worstHz = frequency.toDouble() }
            if (frequency <= 3200) worstModalBand = max(worstModalBand, difference)
        }
        return linkedMapOf("frames" to frames, "continuousSupportMs" to support * 1000,
            "protected20To10000HzMaxFoldingAmplitudeDbRelativeToUnitDC" to dbRatio(worst, 1.0),
            "worstFrequencyHz" to worstHz,
            "protected20To3200HzMaxFoldingAmplitudeDbRelativeToUnitDC" to dbRatio(worstModalBand, 1.0),
            "interpretation" to "Discrete Hann DTFT minus exact continuous support-matched Hann transform; no fractional-delay filter included")
    }

    private fun comparison(native: FloatArray, reference: FloatArray): Map<String, Any> {
        require(native.size == reference.size)
        val from = (Dsp.RATE * .1).toInt()
        val until = (Dsp.RATE * 7.9).toInt()
        var energy = 0.0; var error = 0.0; var dot = 0.0; var referenceEnergy = 0.0
        for (i in from until until) {
            energy += native[i].toDouble() * native[i]
            val d = native[i] - reference[i].toDouble()
            error += d * d; dot += native[i] * reference[i].toDouble()
            referenceEnergy += reference[i].toDouble() * reference[i]
        }
        fun cubic(at: Double): Double {
            val i = floor(at).toInt(); val t = at - i
            val y0 = reference[i - 1].toDouble(); val y1 = reference[i].toDouble()
            val y2 = reference[i + 1].toDouble(); val y3 = reference[i + 2].toDouble()
            return y1 + .5 * t * (y2 - y0 + t * (2*y0 - 5*y1 + 4*y2 - y3 + t * (3*(y1-y2) + y3-y0)))
        }
        var bestDelay = 0.0; var bestGain = 1.0; var bestError = Double.POSITIVE_INFINITY
        for (step in -200..200) {
            val delay = step / 200.0
            var ab = 0.0; var bb = 0.0; var aa = 0.0
            var i = from
            while (i < until) {
                val b = cubic(i + delay)
                ab += native[i] * b; bb += b * b; aa += native[i].toDouble() * native[i]
                i += 8
            }
            val gain = ab / bb
            val residual = max(0.0, aa - ab * ab / bb)
            if (residual < bestError) { bestError = residual; bestDelay = delay; bestGain = gain }
        }
        val residual = FloatArray(native.size)
        var alignedEnergy = 0.0
        for (i in from until until) {
            val d = native[i] - bestGain * cubic(i + bestDelay)
            alignedEnergy += d * d; residual[i] = d.toFloat()
        }
        fun protectedPower(samples: FloatArray): Double {
            val n = 8192
            var power = 0.0
            var at = from
            while (at + n <= until) {
                val re = FloatArray(n) { j -> (samples[at + j] * (.5 - .5*cos(2*PI*j/(n-1)))).toFloat() }
                val im = FloatArray(n)
                Fft.forward(re, im)
                for (j in 1 until n/2) if (j * Dsp.RATE.toDouble()/n in 20.0..10_000.0) {
                    power += re[j].toDouble()*re[j] + im[j].toDouble()*im[j]
                }
                at += n
            }
            return power
        }
        return linkedMapOf("measurementIntervalSeconds" to listOf(.1,7.9),
            "rawWaveformResidualDb" to dbRatio(error, energy, power=true),
            "unshiftedScalarFit" to dot/referenceEnergy,
            "bestCubicDelaySamplesAt44100" to bestDelay,
            "bestCubicDelayMicroseconds" to bestDelay*1e6/Dsp.RATE,
            "bestSingleGain" to bestGain,
            "alignedWaveformResidualDb" to dbRatio(alignedEnergy,energy,power=true),
            "alignedProtected20To10000HzResidualDb" to dbRatio(protectedPower(residual),protectedPower(native),power=true),
            "interpretation" to "Total difference, not alias-only: includes rate-dependent filters, discrete modal response and fractional-event interpolation")
    }

    @JvmStatic fun main(args: Array<String>) {
        val out=File(args.firstOrNull() ?: "testkit/accord-rate-reference").canonicalFile
        out.mkdirs()
        val rows=ArrayList<Map<String,Any>>()
        val cases=listOf(AccordVoice.WOOD to AccordAcousticProfile.BASE,
            AccordVoice.TINE to AccordAcousticProfile.REFERENCE,
            AccordVoice.TINE to AccordAcousticProfile.BRIGHT_EDGE)
        for (paceMacro in listOf(.5f, 1f)) for ((voice,profile) in cases) {
            val paceHz = if (paceMacro == .5f) 2.0 else 4.0
            val patch=AccordPatch("Accord rate reference",voice,
                Accord.defaults(voice)+mapOf("CONTACT" to 1f,"PACE" to paceMacro),
                midi=72,velocity=1f,acousticProfile=profile)
            val probe=Accord.probe(patch)
            val config=AccordAcoustics.configuration(voice,profile,72,1f,1f,.5f)
            val native=reference(config,probe.events,NATIVE_RATE,false,paceHz)
            var parityMaximum=0.0
            for (i in native.samples.indices) parityMaximum=max(parityMaximum,abs(native.samples[i]-probe.raw.samples[i].toDouble()))
            check(parityMaximum<2e-5) { "Independent4x reference diverged: $voice/$profile $parityMaximum" }
            val rounded=reference(config,probe.events,NATIVE_RATE*2,false,paceHz)
            val matched=reference(config,probe.events,NATIVE_RATE*2,true,paceHz)
            rows+=linkedMapOf("voice" to voice.name,"profile" to profile.name,
                "referenceCyclesPerSecond" to paceHz,
                "patch" to patch.toJsonValue(),"reference4xParityMaximumAbsolute" to parityMaximum,
                "nativeWidths" to native.widths,"rounded8xWidths" to rounded.widths,"supportMatched8xWidths" to matched.widths,
                "positiveKernelFolding" to kernelFolding(config.widthMs),
                "negativeKernelFolding" to kernelFolding(config.widthMs*1.12),
                "nativeAboveOutputNyquistPowerBeforeHouseDb" to native.aboveNyquistBeforeHouseDb,
                "nativeAboveOutputNyquistPowerAfterHouseDb" to native.aboveNyquistAfterHouseDb,
                "rounded8xComparison" to comparison(probe.raw.samples,rounded.samples),
                "supportMatched8xComparison" to comparison(probe.raw.samples,matched.samples))
            println("Accord8x reference $voice/$profile PACE$paceHz:4x paritymax=$parityMaximum")
        }
        AccordAuditionFiles.writeJson(File(out,"report.json"),linkedMapOf(
            "status" to "Independent native deterministic-contact4x/8x reference; no listening judgment",
            "rates" to listOf(NATIVE_RATE,NATIVE_RATE*2),"outputRate" to Dsp.RATE,
            "cases" to rows,
            "normalization" to "Compare pre-calibration house-conditioned signals with velocity included; report unfitted error and one global delay/gain fit separately",
            "protectedBandHz" to listOf(20,10000),
            "limitations" to listOf("Whole-waveform differences are not labeled alias energy",
                "Hann alias isolation uses exact continuous support-matched transform, prior to event fractional interpolation and acoustic filters",
                "High-band FFT power is available out-of-band power, not a direct measurement of folded components",
                "Rate-dependent force/house filter phase and discrete modal phase remain in output residuals",
                "No8x independent-noise BRUSH comparison: changing random draw rate would compare different excitation")))
        println("Accord rate report: ${File(out,"report.json")}")
    }
}
