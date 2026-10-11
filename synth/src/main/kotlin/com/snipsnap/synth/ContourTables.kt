package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Original harmonic recipes; these names are serialized patch properties. */
enum class ContourTable {
    ROUND_PULSE, SAW_HOLLOW, ODD_EVEN, DIGITAL_BELL, CLUSTER, FORMANT, SOFT_SAW, COPPER,
}

/**
 * Contour's generated, phase-aligned wavetable library. No sampled or factory assets.
 *
 * Each family has 24 frames and 2048 phase samples. Its 16 pitch levels contain at most
 * 1..256 harmonics. Both levels participating in a pitch crossfade stay below 18 kHz;
 * merely interpolating toward the next, unsafe level would bring aliases back in.
 * The highest safe level fades in over the following pitch interval, making transitions
 * continuous while leaving room for scan sidebands and the final output low-pass.
 *
 * Normalization uses ONE gain for an entire family, including all frames and pitch levels:
 * the largest sampled absolute peak becomes 0.90. There is no per-frame RMS or peak
 * correction. Intended energy changes survive a scan, the fundamental stays constant,
 * and interpolating frames cannot create sudden gain changes or exceed that peak.
 *
 * The arrays stay private and are never changed after publication. Only this immutable
 * bank is shared between renders; oscillator phase, scan smoothing and envelopes belong
 * to the caller. Initialization is synchronized, and [prewarm] can move it before a render.
 */
object ContourTables {
    const val TABLE_SIZE = 2048
    const val FRAME_COUNT = 24
    const val BANDWIDTH_HZ = 18_000.0

    private val harmonicLimits = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256)
    private val bank: Array<Family> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildBank() }

    private class Family(val levels: Array<Array<FloatArray>>)

    fun prewarm() { bank.size }

    /** Compile immutable pitch selection once for an oscillator with a constant frequency. */
    fun lookup(table: ContourTable, hz: Double): Lookup = Lookup(table, hz)

    class Lookup internal constructor(table: ContourTable, hz: Double) {
        private val valid = hz.isFinite() && hz > 0.0 && hz < BANDWIDTH_HZ
        private val upper = if (valid) upperLevel(hz) else 0
        private val bright = bank[table.ordinal].levels[upper]
        private val dark = bank[table.ordinal].levels[maxOf(0, upper - 1)]
        private val rootFrames = bank[table.ordinal].levels[0]
        private val mix = if (valid) levelMix(upper, hz) else 0.0
        private val gain = if (valid) rootFade(hz) else 0.0

        fun sample(phase: Double, scan: Double): Double {
            if (gain == 0.0 || !phase.isFinite() || scan.isNaN()) return 0.0
            return readAt(dark, bright, mix, phase, scan) * gain
        }

        /** The family's constant fundamental, with identical phase interpolation and pitch fade. */
        fun root(phase: Double): Double {
            if (gain == 0.0 || !phase.isFinite()) return 0.0
            return readAt(rootFrames, rootFrames, 0.0, phase, 0.0) * gain
        }
    }

    /**
     * [phase] is in cycles and wraps in either direction. [scan] uses a smoothstep clamp
     * onto 0..1, so a trajectory reaching either end has no reflected or clipped corner.
     * Phase and adjacent frames are linearly interpolated; pitch levels use a smoothstep
     * crossfade. [hz] is this oscillator's frequency, including any patch interval.
     */
    fun sample(table: ContourTable, phase: Double, scan: Double, hz: Double): Double {
        if (!phase.isFinite() || scan.isNaN() || !hz.isFinite() || hz <= 0.0 || hz >= BANDWIDTH_HZ) return 0.0

        val upper = upperLevel(hz)
        val family = bank[table.ordinal]
        return readAt(family.levels[maxOf(0, upper - 1)], family.levels[upper], levelMix(upper, hz), phase, scan) * rootFade(hz)
    }

    private fun readAt(dark: Array<FloatArray>, bright: Array<FloatArray>, pitchMix: Double, phase: Double, scan: Double): Double {
        val wrapped = phase - floor(phase)
        val phasePosition = wrapped * TABLE_SIZE
        val phaseIndex = phasePosition.toInt().coerceIn(0, TABLE_SIZE - 1)
        val phaseFraction = phasePosition - phaseIndex
        val framePosition = smoothstep(scan.coerceIn(0.0, 1.0)) * (FRAME_COUNT - 1)
        val frameIndex = framePosition.toInt().coerceIn(0, FRAME_COUNT - 1)
        val frameFraction = framePosition - frameIndex

        val atDark = read(dark, phaseIndex, phaseFraction, frameIndex, frameFraction)
        if (pitchMix == 0.0 || dark === bright) return atDark
        val atBright = read(bright, phaseIndex, phaseFraction, frameIndex, frameFraction)
        return atDark + (atBright - atDark) * pitchMix
    }

    private fun upperLevel(hz: Double): Int {
        val allowed = BANDWIDTH_HZ / hz
        var upper = 0
        while (upper + 1 < harmonicLimits.size && harmonicLimits[upper + 1] <= allowed) upper++
        return upper
    }

    private fun levelMix(upper: Int, hz: Double): Double {
        if (upper == 0) return 0.0
        val nextLimit = if (upper + 1 < harmonicLimits.size) harmonicLimits[upper + 1].toDouble()
            else harmonicLimits[upper] * 1.5
        return smoothstep(((BANDWIDTH_HZ / hz - harmonicLimits[upper]) / (nextLimit - harmonicLimits[upper])).coerceIn(0.0, 1.0))
    }

    // Fade the root beforehand rather than creating a discontinuity at 18 kHz.
    private fun rootFade(hz: Double): Double = smoothstep(((BANDWIDTH_HZ - hz) / 3_000.0).coerceIn(0.0, 1.0))

    private fun read(frames: Array<FloatArray>, phase: Int, phaseMix: Double, frame: Int, frameMix: Double): Double {
        val nextPhase = (phase + 1) and (TABLE_SIZE - 1)
        val a = frames[frame]
        val atA = a[phase] + (a[nextPhase] - a[phase]) * phaseMix
        if (frameMix == 0.0 || frame == FRAME_COUNT - 1) return atA
        val b = frames[frame + 1]
        val atB = b[phase] + (b[nextPhase] - b[phase]) * phaseMix
        return atA + (atB - atA) * frameMix
    }

    private fun smoothstep(x: Double): Double = x * x * (3.0 - 2.0 * x)

    private fun buildBank(): Array<Family> {
        val maximum = harmonicLimits.last()
        // Shared bases make generation additions rather than millions of trig calls.
        val sine = Array(maximum) { h -> DoubleArray(TABLE_SIZE) { i -> sin(2.0 * PI * (h + 1) * i / TABLE_SIZE) } }
        val cosine = Array(maximum) { h -> DoubleArray(TABLE_SIZE) { i -> cos(2.0 * PI * (h + 1) * i / TABLE_SIZE) } }
        return Array(ContourTable.entries.size) { familyIndex ->
            val table = ContourTable.entries[familyIndex]
            val levels = Array(harmonicLimits.size) { Array(FRAME_COUNT) { FloatArray(TABLE_SIZE) } }
            var peak = 0.0
            for (frame in 0 until FRAME_COUNT) {
                val scan = frame.toDouble() / (FRAME_COUNT - 1)
                val waveform = DoubleArray(TABLE_SIZE)
                var level = 0
                for (harmonic in 1..maximum) {
                    val amplitude = amplitude(table, harmonic, scan)
                    // Fixed harmonic phases across every frame avoid destructive scan fades.
                    val angle = when (table) {
                        ContourTable.DIGITAL_BELL -> if (harmonic == 1) 0.0 else 0.27 * harmonic
                        ContourTable.COPPER -> if (harmonic == 1) 0.0 else 0.13 * harmonic
                        else -> 0.0
                    }
                    val s = amplitude * cos(angle)
                    val c = amplitude * sin(angle)
                    val sineBasis = sine[harmonic - 1]
                    val cosineBasis = cosine[harmonic - 1]
                    for (i in waveform.indices) waveform[i] += s * sineBasis[i] + c * cosineBasis[i]
                    if (harmonic == harmonicLimits[level]) {
                        val destination = levels[level][frame]
                        for (i in waveform.indices) {
                            destination[i] = waveform[i].toFloat()
                            peak = maxOf(peak, abs(waveform[i]))
                        }
                        level++
                    }
                }
            }
            val gain = 0.90 / peak
            for (level in levels) for (frame in level) for (i in frame.indices) frame[i] = (frame[i] * gain).toFloat()
            Family(levels)
        }
    }

    /** Fundamental is always present; every other coefficient moves continuously. */
    private fun amplitude(table: ContourTable, harmonic: Int, scan: Double): Double {
        if (harmonic == 1) return 1.0
        val n = harmonic.toDouble()
        val odd = harmonic and 1 == 1
        return when (table) {
            ContourTable.ROUND_PULSE -> {
                val duty = 0.50 - 0.20 * scan
                val pulse = sin(PI * n * duty) / (n * sin(PI * duty))
                pulse * (0.30 + 0.95 * scan) * exp(-n / (5.0 + 38.0 * scan))
            }
            ContourTable.SAW_HOLLOW -> {
                val hollow = if (odd) 1.0 + 0.25 * scan else 1.0 - 0.93 * scan
                hollow * exp(-n / (10.0 + 54.0 * scan)) / n.pow(1.15)
            }
            ContourTable.ODD_EVEN -> {
                val parity = if (odd) 1.10 - 0.83 * scan else 0.06 + 1.10 * scan
                parity * exp(-n / (12.0 + 42.0 * scan)) / n.pow(1.05)
            }
            ContourTable.DIGITAL_BELL -> {
                0.75 * bell(n, 3.0 + 3.0 * scan, 0.72) +
                    0.46 * bell(n, 7.0 + 7.0 * scan, 0.95) +
                    0.25 * bell(n, 12.0 + 13.0 * scan, 1.2) + 0.06 * exp(-n / 22.0) / n
            }
            ContourTable.CLUSTER -> {
                0.78 * bell(n, 3.0 + 12.0 * scan, 0.9 + 1.2 * scan) +
                    0.32 * bell(n, 8.0 + 19.0 * scan, 1.4) + 0.09 * exp(-n / 15.0) / n
            }
            ContourTable.FORMANT -> {
                val vowel = 0.14 + 1.8 * bell(n, 3.0 + 4.0 * scan, 1.1) +
                    1.3 * bell(n, 8.0 + 8.0 * scan, 2.0)
                vowel * exp(-n / 55.0) / n.pow(0.95)
            }
            ContourTable.SOFT_SAW -> {
                exp(-n / (3.0 + 34.0 * scan)) / n.pow(1.30 - 0.24 * scan)
            }
            ContourTable.COPPER -> {
                val brass = 0.65 + 1.55 * bell(n, 3.0 + 6.0 * scan, 1.6 + scan)
                val parity = if (odd) 1.0 else 0.5 + 0.45 * scan
                brass * parity * exp(-n / (14.0 + 50.0 * scan)) / n.pow(1.05)
            }
        }
    }

    private fun bell(n: Double, center: Double, width: Double): Double {
        val distance = (n - center) / width
        return exp(-0.5 * distance * distance)
    }
}
