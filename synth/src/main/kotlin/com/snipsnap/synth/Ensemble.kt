package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ENSEMBLE — the 1970s string machine's back half, on any pad.
 *
 * Three fractional taps off one delay line, read 120° apart on two slow
 * sines (one the swell, one the shimmer), each tap through its own one-pole
 * tone, the three summed with no dry signal: what sat after the divide-down
 * oscillators in every string synthesizer of that decade, and what makes
 * one saw read as a section. Not a bucket-brigade emulation — no clock, no
 * compander, no clock noise; a three-tap chorus with a tone per tap, and
 * that is all the chorus claims.
 *
 * Sits after TAPE and before PHASE. The copies are made of the finished
 * tone, and the sweep and the repeats then carry every copy — PHASE's own
 * argument for its slot, one section earlier; before PHASE rather than
 * after because a phaser on a chorus notches the moving copies at once,
 * and the fixed order picks one.
 *
 * **SECTION.** Three taps on the same two sines read as one instrument
 * being swirled, not as several playing: their pitch wobbles are locked
 * together (every pair correlates at exactly −0.5) and the swell repeats
 * every 1.72 s. SECTION crossfades, equal power, from that chorus (0, bit
 * for bit what this section was before the macro existed, so a saved recipe
 * without the key keeps its bytes) to [EnsemblePlayers] (1): six players, each
 * with their own drift, vibrato, onset, tone and level flutter. DEPTH and RATE
 * then scale the players as they scale the chorus, and WIDTH is shared. Between
 * the two the bass is kept as one steady voice: the chorus bed gives up its own
 * bass and the players' anchor is made up to full weight, because the bed's bass
 * (a copy at 7.5 ms) and the anchor's (a copy at 17.7 ms) would otherwise cancel
 * at 49 Hz and its odd multiples. The paragraphs below on the dry signal, DEPTH 0
 * and the stereo rows describe the chorus; [EnsemblePlayers] has the players'.
 *
 * **100 % wet, no dry mix** — TAPE's rule. A dry-plus-delayed sum is a
 * comb with notches every 133 Hz at 7.5 ms, 11–14 dB deep at half mix with
 * these weights, and that comb's low end survives the modulation; with no dry there is no
 * comb, and what is left is the three taps beating against each other,
 * which *is* the ensemble — a slow level ripple of a few dB on a steady
 * tone, printed by the test, not hidden. DEPTH 0 is therefore three
 * identical copies: a 7.5 ms delay through the 6.5 kHz tone, the only
 * trace, as TAPE's all-zeros is.
 *
 * **Stereo.** WIDTH crossfades from the equal sum of the three taps (one
 * channel — the WAV stays mono) to a pair assigned so the phone's mono
 * fold — `KitPreview` averages the channels, `Loudness.of` folds before it
 * meters, the classifier folds before it files — weights all three taps
 * the same: `L = 0.852·t0 + 0.501·t1 + 0.150·t2`, R the mirror. Both
 * weight sets are at unit power, so the crossfade holds level. What the
 * fold loses against either channel is set by how alike the two channels
 * are, and the source decides that. For equal-window RMS the loss is
 * exactly `√((1 + ρ)/2)` for an L/R correlation ρ (−0.97 dB at ρ 0.60);
 * by the house meter it is larger, because `Loudness.of` cuts below
 * 120 Hz — removing the coherent fundamental and leaving the less coherent
 * harmonics — and meters the loudest 200 ms window. By that meter, at
 * DEPTH 0.5: three copies of a kick a few milliseconds apart stay
 * coherent where a kick lives (−0.2 dB, ρ 0.96), a brass preset loses
 * about a decibel (VELVET's FANFARE −0.95 dB), and a bare sawtooth
 * loses two to three (−2.1 dB at C3 with ρ 0.60, −3.2 dB at C4 with
 * ρ 0.19, where the harmonics fall near half the tap spacing and the
 * copies cancel). `FxTest` pins those rows; the gate page plays every stereo clip
 * beside its fold so the trade is heard, not assumed, and `WIDE_Z` is the
 * knob that moves it (a larger z is narrower and folds better, up to
 * `1/√3` where L = R). A mono input above [WIDTH_OFF] comes out with two
 * channels — the one section that
 * widens; see [FxChain]'s contract — and a stereo input keeps its image,
 * each channel through its own line and taps. WIDTH's neutral is 0, so
 * AMT narrows the image toward the mono sum as it falls; the WAV stays a
 * pair until the section is bypassed altogether at AMT 0.
 *
 * **Keep a LOOP dry.** At 0.58 Hz a two-to-four-second loop holds one or
 * two slow cycles, so the delay jumps at the wrap — a pitch tick. The rack
 * cannot tell a LOOP from a one-shot (a `Snip` carries no loop metadata),
 * so nothing here guards it: SIREN lands its own LOOPs without ECHO for
 * the same reason, and a recipe that ensembles a LOOP is asking for the
 * tick.
 *
 * Every constant below — the three delays, the two rates, the 6.5 kHz tone,
 * the stereo weights — is a listening value with no source: the starting
 * point for the section's own gate, claimed as no instrument's.
 *
 * Peak-matched, deterministic, no tail; runs at the snip's own rate. The
 * chorus has no seed (both sines start at phase zero); the players' noise
 * is seeded from literals. The chorus is six sines per frame and three
 * one-poles per channel: TAPE's load three times over, once; the players
 * cost about twice that, and a SECTION between 0 and 1 runs both.
 */
object Ensemble {

    val MACROS: List<MacroSpec> = listOf(
        // Both sines' swings together; 0 is three identical copies. At SECTION 1 it
        // scales the players' drift, vibrato and level flutter instead (twice DEPTH is
        // their scale, so the default 0.5 is the depth they are written for).
        MacroSpec("DEPTH", 0.5f),
        // One multiplier on both rates, ×0.5 to ×2; the centre is exactly
        // the base rates, and the neutral, so AMT leaves the speed alone. The
        // players' drift and vibrato rates scale with it too.
        MacroSpec("RATE", 0.5f, neutral = 0.5f),
        // The mono sum at 0 (the WAV stays mono) to the stereo pair at 1;
        // neutral 0, so AMT narrows the image toward the mono sum.
        MacroSpec("WIDTH", 1f, neutral = 0f),
        // The three-tap chorus at 0, six independent players at 1, an equal-power
        // crossfade between. Last in the list, so the macros before it keep their
        // places in every positional read; default and neutral 0, so a recipe
        // without the key is the chorus and AMT fades the players away.
        MacroSpec("SECTION", 0f, neutral = 0f),
    )

    /** The centre delay every tap swings around, seconds. */
    const val BASE_DELAY_S = 0.0075f

    /** The slow sine's swing at DEPTH 1 — the swell. */
    const val SLOW_DEPTH_S = 0.0028f

    /** The fast sine's swing at DEPTH 1 — the shimmer. */
    const val FAST_DEPTH_S = 0.00045f

    const val SLOW_HZ = 0.58f
    const val FAST_HZ = 5.85f

    /** The per-tap tone, fixed: TAPE's AGE already spans 3.2–16 kHz for anyone who wants it darker. */
    const val TONE_HZ = 6_500f

    /** RATE's travel, as one multiplier on both sines. */
    const val RATE_MIN = 0.5f
    const val RATE_MAX = 2f

    /** Below this WIDTH is off and the output keeps the input's channel count. */
    const val WIDTH_OFF = 0.001f

    /** At or below this SECTION is off: the chorus runs alone, by the very code that ran before the macro existed. */
    const val SECTION_OFF = 0.001f

    /**
     * The stereo pair's weights over the three taps: `L = (x, y, z)`, `R` the
     * mirror, at unit power (`x² + y² + z² = 1`) with `y = (x + z) / 2` so the
     * average fold weights every tap the same. `z = 0.15` is one row of that
     * family: the width and the fold loss of the classic shared-centre pair
     * to two decimals, without that pair's centre tap folded at double
     * weight. A gate choice among the rows, not a derivation.
     */
    const val WIDE_Z = 0.150f

    /** The mono sum's weight per tap — unit power for three uncorrelated taps, so WIDTH's crossfade holds level. */
    internal val MONO_WEIGHT: Float = (1.0 / sqrt(3.0)).toFloat()

    /** The far-tap weight at which L = R — the same `1/√3` as [MONO_WEIGHT], one number. The family is defined up to here. */
    internal val WIDE_Z_MAX: Float = MONO_WEIGHT
    val WIDE_X: Float
    val WIDE_Y: Float

    init {
        val w = wideWeights(WIDE_Z)
        WIDE_X = w[0]
        WIDE_Y = w[1]
    }

    /**
     * The family's row for a given far-tap weight [z]: `y = (x + z) / 2` for an
     * equal-weight fold, `x² + y² + z² = 1` for unit power, solved for `x`. The
     * family is monotonic — a larger z narrower — only up to `1/√3 ≈ 0.577`,
     * where x = y = z and L = R; past it the sides swap and the pair widens
     * again, so the domain stops there.
     */
    internal fun wideWeights(z: Float): FloatArray {
        require(z in 0f..WIDE_Z_MAX) { "z is 0..$WIDE_Z_MAX (1/sqrt 3, where L = R); got $z" }
        val x = ((-z / 2.0 + sqrt(5.0 - 6.0 * z * z)) / 2.5).toFloat()
        return floatArrayOf(x, (x + z) / 2f, z)
    }

    /** The delay line: room for the deepest swing (10.75 ms) with margin. */
    private const val LINE_SECONDS = 0.025f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** RATE's multiplier on both sines: exactly 1 at the knob's centre. */
    fun rateMultiplier(rate: Float): Float = Dsp.expMap(rate.coerceIn(0f, 1f), RATE_MIN, RATE_MAX)

    /**
     * The three taps' delays at [t] seconds, in seconds: the centre plus each
     * sine's swing, the three taps read 120° apart on both sines so their
     * swings sum to zero at every instant while each one moves.
     */
    fun delaysAt(t: Float, depth: Float, rateMul: Float): FloatArray =
        FloatArray(3).also { delaysInto(it, t, depth, rateMul) }

    private fun delaysInto(dst: FloatArray, t: Float, depth: Float, rateMul: Float) {
        val slow = SLOW_DEPTH_S * depth
        val fast = FAST_DEPTH_S * depth
        val slowArg = 2.0 * PI * SLOW_HZ * rateMul * t
        val fastArg = 2.0 * PI * FAST_HZ * rateMul * t
        for (k in 0 until 3) {
            val phase = 2.0 * PI * k / 3.0
            dst[k] = BASE_DELAY_S + slow * sin(slowArg + phase).toFloat() + fast * sin(fastArg + phase).toFloat()
        }
    }

    /**
     * How far one tap's pitch swings at the peak of each sine, in cents, from
     * the constants alone: a delay changing at `d/dt` plays back at pitch
     * ratio `1 + d/dt`, and a sine's steepest slope is `2π·f·A`. Slow and
     * fast, in that order — 17.6 and 28.4 cents at DEPTH 1 with RATE at its
     * centre. The two peaks coincide only now and then (the rates are not a
     * ratio of small integers), so the sum is a ceiling, not the usual swing.
     */
    fun peakCents(depth: Float, rateMul: Float): Pair<Float, Float> {
        fun cents(hz: Float, swing: Float): Float =
            (1200.0 * ln(1.0 + 2.0 * PI * hz * rateMul * swing * depth) / ln(2.0)).toFloat()
        return cents(SLOW_HZ, SLOW_DEPTH_S) to cents(FAST_HZ, FAST_DEPTH_S)
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = process(snip, macros, WIDE_Z)

    /** [process] with another row of the stereo family — the test's way of measuring the rows against each other. */
    internal fun process(snip: Snip, macros: Map<String, Float>, wideZ: Float): Snip {
        wideWeights(wideZ)
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        val rateMul = rateMultiplier(m.getValue("RATE"))
        val width = m.getValue("WIDTH")
        val section = m.getValue("SECTION")

        val inPeak = snip.peak()
        // The one place a section changes the channel count: a mono pad at
        // WIDTH above WIDTH_OFF comes out as the pair. A stereo pad keeps its two.
        val outChannels = if (width > WIDTH_OFF && snip.channels == 1) 2 else snip.channels

        if (section <= SECTION_OFF) {
            val out = chorus(snip, depth, rateMul, width, wideZ)
            matchPeak(out, inPeak)
            return Snip(out, outChannels, snip.sampleRate)
        }

        val weights = sectionWeights(section)
        val out = if (weights[0] == 0f) {
            EnsemblePlayers.render(snip, depth, rateMul, width)
        } else {
            // The low band is one steady voice at every point of the crossfade: the anchor's weight is made up to one, and the bed
            // brings only what is above its bass (two coherent copies of a bass note at different delays would cancel).
            val players = EnsemblePlayers.render(snip, depth, rateMul, width, anchorExtra = 1f / weights[1] - 1f, anchorDelayS = EnsemblePlayers.anchorDelayAt(section))
            underAnchor(chorus(snip, depth, rateMul, width, wideZ), players, weights, outChannels, snip.sampleRate)
        }
        // The players read the line behind the input, so the last of a render would otherwise cut mid-sound.
        Dsp.fadeTail(out, rate = snip.sampleRate, channels = outChannels)
        matchPeak(out, inPeak)
        return Snip(out, outChannels, snip.sampleRate)
    }

    /** The crossfade of the chorus [bed] with the [players]: each channel's bed less its own bass, weighted by [weights], plus the players weighted likewise. */
    private fun underAnchor(bed: FloatArray, players: FloatArray, weights: FloatArray, channels: Int, rate: Int): FloatArray {
        val coefficient = EnsemblePlayers.bedLowCoefficient(rate)
        val low = FloatArray(channels)
        val out = FloatArray(players.size)
        for (i in out.indices) {
            val c = i % channels
            low[c] += coefficient * (bed[i] - low[c])
            out[i] = weights[0] * (bed[i] - low[c]) + weights[1] * players[i]
        }
        return out
    }

    /**
     * The chorus's equal-power weights at [section] between 0 and 1: `(chorus, players)`, unit power, exactly
     * `(0, 1)` at 1 so the chorus is not rendered at all there. Below [SECTION_OFF] the chorus runs alone and
     * this is not consulted.
     */
    internal fun sectionWeights(section: Float): FloatArray {
        require(section in 0f..1f) { "SECTION is 0..1; got $section" }
        if (section >= 1f) return floatArrayOf(0f, 1f)
        val angle = section * PI / 2.0
        return floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
    }

    /** The chorus's output before the peak match: three taps off one line, unchanged since before SECTION existed. */
    private fun chorus(snip: Snip, depth: Float, rateMul: Float, width: Float, wideZ: Float): FloatArray {
        val weights = wideWeights(wideZ)
        val wx = weights[0]; val wy = weights[1]; val wz = weights[2]

        val rate = snip.sampleRate
        val frames = snip.frameCount
        val inChannels = snip.channels
        val wide = width > WIDTH_OFF
        val widen = wide && inChannels == 1
        val outChannels = if (widen) 2 else inChannels
        val lineLen = (LINE_SECONDS * rate).toInt()

        val out = FloatArray(frames * outChannels)
        val lines = Array(inChannels) { FloatArray(lineLen) }
        val tones = Array(inChannels) { Array(3) { Dsp.OnePole(rate) } }
        val delays = FloatArray(3)
        val taps = FloatArray(3)
        var w = 0
        for (f in 0 until frames) {
            // One clock across the frame, so a stereo pair swims together.
            delaysInto(delays, f.toFloat() / rate, depth, rateMul)
            for (c in 0 until inChannels) {
                val line = lines[c]
                line[w] = snip.samples[f * inChannels + c]
                for (k in 0 until 3) taps[k] = tones[c][k].lp(Dsp.tap(line, w, delays[k] * rate), TONE_HZ)
                val mono = (taps[0] + taps[1] + taps[2]) * MONO_WEIGHT
                if (!wide) {
                    out[f * outChannels + c] = mono
                } else if (widen) {
                    val l = wx * taps[0] + wy * taps[1] + wz * taps[2]
                    val r = wz * taps[0] + wy * taps[1] + wx * taps[2]
                    out[f * 2] = mono + (l - mono) * width
                    out[f * 2 + 1] = mono + (r - mono) * width
                } else {
                    // A stereo input keeps its image: the left channel's own
                    // taps take the left weights, the right's the right.
                    val own = if (c == 0) {
                        wx * taps[0] + wy * taps[1] + wz * taps[2]
                    } else {
                        wz * taps[0] + wy * taps[1] + wx * taps[2]
                    }
                    out[f * outChannels + c] = mono + (own - mono) * width
                }
            }
            w = (w + 1) % lineLen
        }
        return out
    }

    /**
     * TAPE's peak match: the input's peak over every sample, the output
     * scaled to it, coerced so a match can never clip.
     */
    private fun matchPeak(out: FloatArray, inPeak: Float) {
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        if (inPeak > 1e-9f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
    }
}
