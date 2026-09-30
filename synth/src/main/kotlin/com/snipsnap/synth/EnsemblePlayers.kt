package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ENSEMBLE's SECTION voicing: six players where the chorus has three locked taps.
 *
 * The chorus reads as one swirled instrument because its three taps ride the *same* two sines 120° apart: every
 * pair of pitch wobbles correlates at exactly −0.5, they always sum to zero, and the swell comes round every
 * 1.72 s. A real section is the opposite: each player drifts in pitch on their own, has their own vibrato, comes
 * in a little before or after the others, and plays with their own tone and their own small swells of level. So
 * each of six players here has a seeded, non-repeating pitch drift (smoothed noise, 0.4–1.45 Hz corners), their
 * own vibrato (5.1–6.5 Hz, their own phase, entering after their own delay as a bowed note's vibrato does), a
 * base delay of their own across about 25 ms, a tone corner, a tilt and a slow level flutter. Pairwise, their
 * pitch deviations correlate at 0.02 (the chorus: 0.50) and nothing in the output repeats.
 *
 * Stereo is the chorus's rule, for six: a ramp family of weights at unit power whose opposite pairs sum to a
 * constant, so the phone's mono fold weights every player the same, at L/R correlation 0.51 (the chorus's own
 * row). The same WIDTH semantics apply: [Ensemble.WIDTH_OFF], a mono input widened, a stereo input keeping its image.
 *
 * **The bass anchor.** Six equal-weight players spread over 25 ms fold to a deep static comb: on their own a
 * 65 Hz tone folds 13 dB down on average (20 dB in its worst window) and a 130 Hz one 16 dB down in its worst, where
 * the chorus, spread over 2.8 ms, does not. The drift only randomises that comb above about 150 Hz, and no choice
 * of six delays, weights or a stronger lead player closes it, so the fold's low band is taken from one steady copy of
 * the input instead: the output gains `LP290(x delayed 17.7 ms) − foldWeight · LP145(Σ players)`, added identically
 * to both channels, which swaps the low band of the players' fold for that copy and leaves the side signal (L − R)
 * exactly the players'. Measured, each of the ten 40–330 Hz test tones then folds within 4 dB of the dry sine even
 * in its worst 250 ms window, and so does every tone on a 5 Hz grid over 30–1000 Hz. The cost is heard, and owned:
 * the image is narrower on low and mid notes (the L/R correlation of eight saws 0.48 → 0.74), steady partials
 * under about 500 Hz ripple less, and the envelope's beating lines stand out a little more at the extremes of RATE.
 *
 * Deterministic: every table and every seed is a literal, all state is built inside [render], and nothing
 * depends on the input except the signal itself, so the first seconds of a render do not depend on how long the
 * snip is. Cost: six taps, twelve one-poles (coefficients hoisted) and six sines per frame, plus twelve noise
 * steps per 5 ms. Like the chorus it is for one-shots: the drift's slow corners make a LOOP wrap a larger pitch
 * tick than 0.58 Hz does. The last 33 ms of a render's input never emerges (the chorus loses its last 7.5 ms).
 *
 * At DEPTH 0 nothing moves and the players are six static taps with no dry signal, a comb the chorus's DEPTH 0
 * does not have; the anchor keeps the low end but the mid-range is a comb.
 */
internal object EnsemblePlayers {

    const val PLAYERS = 6

    /** The drift and flutter noise is generated at this rate and read by linear interpolation: smooth enough to be exact to the ear. */
    private const val CONTROL_HZ = 200.0

    /** Each player's own vibrato, rate in hertz, phase in turns, and RMS depth in cents. */
    private val VIBRATO_HZ = doubleArrayOf(5.34, 6.45, 5.76, 5.08, 6.19, 5.50)
    private val VIBRATO_TURNS = doubleArrayOf(0.110, 0.865, 0.620, 0.375, 0.130, 0.884)
    private val VIBRATO_CENTS = doubleArrayOf(5.0, 4.0, 5.5, 4.5, 6.0, 3.5)

    /** Each player's pitch drift: smoothed-noise corner in hertz and RMS depth in cents. */
    private val DRIFT_HZ = doubleArrayOf(0.55, 1.10, 0.80, 0.40, 1.45, 0.70)
    private val DRIFT_CENTS = doubleArrayOf(4.0, 3.0, 4.5, 5.0, 3.5, 4.0)

    /** Where each player sits in time, in milliseconds: the earliest at the chorus's own 7.5, the last 25.5 later. */
    private val DELAY_MS = doubleArrayOf(7.5, 11.8, 18.0, 21.8, 27.0, 33.0)

    /** Seconds after the note starts before each player's vibrato is fully in. */
    private val VIBRATO_ENTRY_S = doubleArrayOf(0.10, 0.35, 0.20, 0.45, 0.15, 0.30)

    /** Each player's tone corner in hertz, tilt in decibels above 900 Hz, and level flutter (RMS decibels, corner hertz). */
    private val TONE_HZ = doubleArrayOf(6_500.0, 5_200.0, 7_800.0, 4_600.0, 6_000.0, 8_800.0)
    private val TILT_DB = doubleArrayOf(1.0, -1.5, 0.0, 2.0, -2.5, -0.5)
    private val FLUTTER_DB = doubleArrayOf(0.8, 1.2, 0.6, 1.0, 1.4, 0.9)
    private val FLUTTER_HZ = doubleArrayOf(0.40, 0.90, 0.60, 1.20, 0.50, 0.75)

    /** Which place in the ramp family of pair weights each player takes; chosen so the two channels' power-weighted mean delay differ by under 0.01 ms. */
    private val SLOT = intArrayOf(1, 4, 5, 0, 2, 3)

    private const val TILT_CORNER_HZ = 900.0

    /** The bass anchor: one steady copy at this delay, low-passed at [ANCHOR_HZ], against [SUB_HZ] of the players' sum taken out. */
    private const val ANCHOR_DELAY_S = 0.0177
    private const val ANCHOR_HZ = 290.0
    private const val SUB_HZ = 145.0

    /** The L/R correlation the pair weights are built for: the chorus's own row. */
    private const val PAIR_RHO = 0.51

    /** The delay line: room for the last player's 33 ms, its drift at DEPTH 1, and the anchor. */
    private const val LINE_SECONDS = 0.06

    /** The noise is clamped to this many standard deviations, which bounds every player's delay. */
    private const val NOISE_CLAMP = 4.0

    /** The fold weight of each player at WIDTH 1, and the pair weights, for [PLAYERS] at [PAIR_RHO]. */
    private val FOLD_WEIGHT: Double = sqrt((1.0 + PAIR_RHO) / (2.0 * PLAYERS))

    internal val PAIR_WEIGHTS: DoubleArray = run {
        val ramp = DoubleArray(PLAYERS) { i -> 1.0 - 2.0 * i / (PLAYERS - 1) }
        val power = ramp.sumOf { it * it }
        val b = sqrt((1.0 - PAIR_RHO) / 2.0 / power)
        DoubleArray(PLAYERS) { i -> FOLD_WEIGHT + b * ramp[i] }
    }

    private fun ratio(cents: Double): Double = 2.0.pow(cents / 1200.0) - 1.0

    /** Unit-RMS smoothed noise: two cascaded one-poles on seeded uniform noise at [CONTROL_HZ], three seconds of pre-roll, read by linear interpolation. */
    private class Wander(seed: Int, cornerHz: Double) {
        private val noise = Dsp.Noise(seed)
        private val a = 1.0 - exp(-2.0 * PI * cornerHz / CONTROL_HZ)
        private val gain: Double
        private var s1 = 0.0
        private var s2 = 0.0
        private var w0 = 0.0
        private var w1 = 0.0
        private var k = 0L

        init {
            // The exact output variance of two equal one-poles on noise of variance 1/3.
            val q = (1.0 - a) * (1.0 - a)
            gain = 1.0 / sqrt((1.0 / 3.0) * a.pow(4) * (1.0 + q) / (1.0 - q).pow(3))
            repeat(600) { step() }
            w0 = w1
            step()
        }

        private fun step() {
            val x = noise.next().toDouble()
            s1 += a * (x - s1)
            s2 += a * (s1 - s2)
            w1 = s2 * gain
        }

        /** The value [seconds] in; calls must not go backwards. */
        fun at(seconds: Double): Double {
            val u = seconds * CONTROL_HZ
            val target = floor(u).toLong()
            while (k < target) {
                w0 = w1
                step()
                k++
            }
            return w0 + (w1 - w0) * (u - k)
        }
    }

    /**
     * The players' delays (seconds) and level gains at a time: the one copy of the law, read by [render] and by the
     * tests alike, so what is measured is what is heard. [scale] is 2·DEPTH (1 at the default DEPTH 0.5, the depth the
     * tables are written for) and [rateMul] is [Ensemble.rateMultiplier]. Time must not go backwards.
     */
    internal class Law(private val scale: Double, private val rateMul: Double) {
        private val driftSigma = DoubleArray(PLAYERS) { i -> ratio(DRIFT_CENTS[i]) / (2.0 * PI * DRIFT_HZ[i] * rateMul) }
        private val vibratoAmp = DoubleArray(PLAYERS) { i -> sqrt(2.0) * ratio(VIBRATO_CENTS[i]) / (2.0 * PI * VIBRATO_HZ[i] * rateMul) }
        private val drift = Array(PLAYERS) { i -> Wander(7001 + 977 * i, DRIFT_HZ[i] * rateMul) }
        private val flutter = Array(PLAYERS) { i -> Wander(9001 + 1013 * i, FLUTTER_HZ[i] * rateMul) }

        fun at(seconds: Double, delays: DoubleArray, gains: DoubleArray) {
            for (i in 0 until PLAYERS) {
                val wander = drift[i].at(seconds).coerceIn(-NOISE_CLAMP, NOISE_CLAMP)
                val x = ((seconds - VIBRATO_ENTRY_S[i]) / 0.35).coerceIn(0.0, 1.0)
                val entry = x * x * (3.0 - 2.0 * x)
                val vibrato = vibratoAmp[i] * scale * entry * sin(2.0 * PI * (VIBRATO_HZ[i] * rateMul * seconds + VIBRATO_TURNS[i]))
                delays[i] = DELAY_MS[i] / 1000.0 + driftSigma[i] * scale * wander + vibrato
                gains[i] = 10.0.pow(scale * FLUTTER_DB[i] * flutter[i].at(seconds) / 20.0)
            }
        }
    }

    /** The players' delay tracks in seconds, [fs] samples a second for [seconds]: what the tests measure the independence of. */
    internal fun delayTracks(depth: Float, rateMul: Float, seconds: Int, fs: Int): Array<DoubleArray> {
        val law = Law(2.0 * depth, rateMul.toDouble())
        val tracks = Array(PLAYERS) { DoubleArray(seconds * fs) }
        val delays = DoubleArray(PLAYERS)
        val gains = DoubleArray(PLAYERS)
        for (n in 0 until seconds * fs) {
            law.at(n.toDouble() / fs, delays, gains)
            for (i in 0 until PLAYERS) tracks[i][n] = delays[i]
        }
        return tracks
    }

    /**
     * The players' output, before any peak match or fade: the same layout as the chorus's (a mono input widened above
     * [Ensemble.WIDTH_OFF] comes out as a pair, a stereo input keeps its two channels), the same frame count.
     */
    internal fun render(snip: Snip, depth: Float, rateMul: Float, width: Float): FloatArray {
        val rate = snip.sampleRate
        val frames = snip.frameCount
        val inChannels = snip.channels
        val wide = width > Ensemble.WIDTH_OFF
        val widen = wide && inChannels == 1
        val outChannels = if (widen) 2 else inChannels
        val lineLen = (LINE_SECONDS * rate).toInt()
        val out = FloatArray(frames * outChannels)
        val lines = Array(inChannels) { FloatArray(lineLen) }
        val law = Law(2.0 * depth, rateMul.toDouble())

        val tiltCoef = coefficient(TILT_CORNER_HZ, rate)
        val toneCoef = FloatArray(PLAYERS) { coefficient(TONE_HZ[it], rate) }
        val tiltGain = FloatArray(PLAYERS) { 10.0.pow(TILT_DB[it] / 20.0).toFloat() }
        val anchorCoef = coefficient(ANCHOR_HZ, rate)
        val subCoef = coefficient(SUB_HZ, rate)
        val anchorDelay = (ANCHOR_DELAY_S * rate).toFloat()
        val invRoot = (1.0 / sqrt(PLAYERS.toDouble())).toFloat()
        // What the fold of the widened pair weights each player: the equal sum at WIDTH 0, the ramp family's constant at 1.
        val foldWeight = ((1.0 - width) / sqrt(PLAYERS.toDouble()) + width * FOLD_WEIGHT).toFloat()
        val left = FloatArray(PLAYERS) { PAIR_WEIGHTS[SLOT[it]].toFloat() }
        val right = FloatArray(PLAYERS) { PAIR_WEIGHTS[PLAYERS - 1 - SLOT[it]].toFloat() }

        val lowTilt = Array(inChannels) { FloatArray(PLAYERS) }
        val tone = Array(inChannels) { FloatArray(PLAYERS) }
        val anchor = FloatArray(inChannels)
        val sub = FloatArray(inChannels)
        val delays = DoubleArray(PLAYERS)
        val gains = DoubleArray(PLAYERS)
        val voice = FloatArray(PLAYERS)
        var w = 0
        for (f in 0 until frames) {
            // One clock across the frame, so a stereo pair's players move together.
            law.at(f.toDouble() / rate, delays, gains)
            for (c in 0 until inChannels) {
                val line = lines[c]
                line[w] = snip.samples[f * inChannels + c]
                var sum = 0f
                for (i in 0 until PLAYERS) {
                    val read = (delays[i] * rate).toFloat().coerceIn(2f, (lineLen - 2).toFloat())
                    val x = Dsp.tap(line, w, read)
                    lowTilt[c][i] += tiltCoef * (x - lowTilt[c][i])
                    val tilted = lowTilt[c][i] + tiltGain[i] * (x - lowTilt[c][i])
                    tone[c][i] += toneCoef[i] * (tilted - tone[c][i])
                    voice[i] = tone[c][i] * gains[i].toFloat()
                    sum += voice[i]
                }
                anchor[c] += anchorCoef * (Dsp.tap(line, w, anchorDelay) - anchor[c])
                sub[c] += subCoef * (sum - sub[c])
                val steady = anchor[c] - foldWeight * sub[c]
                val mono = sum * invRoot
                if (!wide) {
                    out[f * outChannels + c] = mono + steady
                } else {
                    var l = 0f
                    var r = 0f
                    for (i in 0 until PLAYERS) {
                        l += left[i] * voice[i]
                        r += right[i] * voice[i]
                    }
                    if (widen) {
                        out[f * 2] = mono + (l - mono) * width + steady
                        out[f * 2 + 1] = mono + (r - mono) * width + steady
                    } else {
                        val own = if (c == 0) l else r
                        out[f * outChannels + c] = mono + (own - mono) * width + steady
                    }
                }
            }
            w = (w + 1) % lineLen
        }
        return out
    }

    /** A one-pole's smoothing coefficient for [cornerHz] at [rate]: hoisted out of the loop, where [Dsp.OnePole] recomputes its exponential per sample. */
    private fun coefficient(cornerHz: Double, rate: Int): Float = (1.0 - exp(-2.0 * PI * cornerHz / rate)).toFloat()
}
