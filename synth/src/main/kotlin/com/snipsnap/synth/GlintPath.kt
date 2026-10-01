package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where GLINT's two bursts sit at one point along a voice's path
 * (docs/superpowers/specs/2026-09-29-glint-paths-design.md §2).
 *
 * Every path is one number, `x`: 1 at the path's start, 0 on PEAK. The
 * one-shot runs `x` down on a clock (SWEEP, VOWEL), on the note's own level
 * (BRASS) or rung by rung (STEP), and a held note's onset does the same
 * ([ratios]), so the two can never disagree about where a voice's formant
 * goes on its way to PEAK. The held note's breath then swings either side of
 * PEAK by BLOOM's own depth ([breathRatios], [GlintHeld]): a quarter of the
 * travel BLOOM asks for, not of what the one-shot's clamped start leaves.
 *
 * Every ratio this returns is read by its callers only at a phase wrap,
 * where the window is zero and a change is free.
 */
internal class GlintPath private constructor(
    val voice: GlintVoice,
    private val kBase: Float,
    private val kStart: Float,
    private val k2: Float,
    /** The second burst's level: BODY × [Glint.BODY_MIX] on the path voices, [Glint.VOWEL_LEVEL2] on VOWEL. */
    val level2: Float,
    /** STEP's rungs, start first, PEAK last; null on every other voice. */
    val ladder: FloatArray?,
    /** The sign value s = 2·BLOOM − 1. */
    val bloom: Float,
    /** VOWEL: the note actually rendered, which turns a formant in Hz into a ratio. 0 on every other voice. */
    private val f0: Float,
    /** VOWEL: PEAK's position on the vowel line, where the path lands. 0 on every other voice. */
    private val vowelPos: Float,
    /** VOWEL: where BLOOM starts the path on the vowel line. 0 on every other voice. */
    private val vowelStart: Float,
    /** VOWEL: BODY's vocal-tract size, multiplying both formants. 1 on every other voice. */
    private val scale: Float,
) {
    /** How long the one-shot path takes to arrive: the whole ladder on STEP, [Glint.BLOOM_T60] otherwise. */
    val onsetSeconds: Float = ladder?.let { it.size * Glint.STEP_SECONDS } ?: Glint.BLOOM_T60

    /**
     * Writes the burst ratios at path position [x] into `out[0]` (main) and
     * `out[1]` (second burst): the one-shot's read, and the held note's
     * onset. STEP reads `ladder[rung]` and needs a [rung] ≥ 0. VOWEL ignores
     * [rung]: [x] walks the vowel line from BLOOM's start to PEAK's vowel,
     * and both bursts are that vowel's F1 and F2 in Hz over the note.
     */
    fun ratios(x: Float, rung: Int, out: FloatArray) {
        if (voice == GlintVoice.VOWEL) {
            vowelRatios(vowelPos + (vowelStart - vowelPos) * x, out)
            return
        }
        val continuous = (kBase * (kStart / kBase).pow(x)).coerceIn(Glint.K_MIN, Glint.K_MAX)
        out[0] = if (ladder != null) {
            require(rung >= 0) { "STEP reads its ladder by rung; the held breath goes through breathRatios" }
            ladder[rung.coerceAtMost(ladder.lastIndex)]
        } else continuous
        out[1] = k2
    }

    /**
     * The held breath's ratios, [swing] being the breath already scaled and
     * signed: `BREATHE_SHARE` times a sine, and on BRASS times |s| as well,
     * because its k follows its level (spec §2.2). This is spec §3, not
     * [ratios] at a small x: the breath swings around PEAK by BLOOM's own
     * depth, where [ratios] runs between PEAK and the one-shot's start, which
     * the clamp to `K_MIN..K_MAX` (and on VOWEL, to the ends of the line)
     * cuts short wherever PEAK sits near an end of its range.
     *
     * Path voices: `k = kBase · (1 + a)^swing` with `a = |s| · BLOOM_MAX`, so
     * BLOOM 0.5 (a = 0) rests on PEAK exactly. A negative s swings the other
     * way round, as the one-shot's path does (a louder BRASS is darker).
     * STEP takes the whole harmonic ([heldHarmonic]). VOWEL: the position on
     * the vowel line is `PEAK's vowel + swing · 4 · s`, which [Glint.vowelAt]
     * clamps to the line.
     */
    fun breathRatios(swing: Float, out: FloatArray) {
        if (voice == GlintVoice.VOWEL) {
            vowelRatios(vowelPos + swing * Glint.VOWEL_LAST * bloom, out)
            return
        }
        val a = abs(bloom) * Glint.BLOOM_MAX
        val exponent = if (bloom < 0f) -swing else swing
        val continuous = (kBase * (1f + a).pow(exponent)).coerceIn(Glint.K_MIN, Glint.K_MAX)
        out[0] = if (ladder != null) heldHarmonic(continuous) else continuous
        out[1] = k2
    }

    /**
     * The amplitude of the sine DEPTH mixes in, chosen so that sine and burst carry equal power
     * (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §2.2). It is `√2 · rB`, `rB`
     * being the RMS over one cycle of the fully rounded burst pair,
     * `w₁(p) · (sin(2π·k0·p) + level2 · sin(2π·k1·p))`, at this path's resting ratios (the breath's
     * centre: [breathRatios] at 0), by numerical integration. It is one number for a note whose ratios
     * move along their path: right at rest, an approximation elsewhere. The spec expects it within
     * about a dB there, which has been measured only at the SWEEP and VOWEL probe setups (defaults,
     * BLOOM 0.85).
     */
    fun sineGain(): Float {
        val k = FloatArray(2)
        breathRatios(0f, k)
        var sum = 0.0
        for (i in 0 until SINE_GAIN_STEPS) {
            val p = (i + 0.5) / SINE_GAIN_STEPS
            val v = GlintShape.analyticWindow(p, 1.0) *
                (sin(2.0 * PI * k[0] * p) + level2 * sin(2.0 * PI * k[1] * p))
            sum += v * v
        }
        return sqrt(2.0 * sum / SINE_GAIN_STEPS).toFloat()
    }

    /**
     * VOWEL's two bursts at [position] on the vowel line, BODY's vocal-tract
     * size applied. Formants are Hz, not harmonics: no snap, and a formant
     * below the note pins to the fundamental instead of vanishing.
     */
    private fun vowelRatios(position: Float, out: FloatArray) {
        Glint.vowelAt(position, out)
        out[0] = (out[0] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
        out[1] = (out[1] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
    }

    /**
     * STEP's held breath, for [breathRatios] only: with no rung clock the
     * landing replaces its nearest whole harmonic, and every other value
     * rounds to one. So a breath that swings across PEAK steps from the
     * harmonic just below straight to the landing (15 to 16.28, skipping 16),
     * and a still pad rests on PEAK, not on the whole harmonic beside it. The
     * landing replaces its nearest whole harmonic, not only an exact hit, so
     * that a small non-zero swing still rests on PEAK: an exact-landing test
     * would round such a swing onto the harmonic beside PEAK.
     */
    private fun heldHarmonic(continuous: Float): Float {
        val whole = Math.round(continuous)
        return if (whole == Math.round(kBase)) kBase else whole.toFloat().coerceIn(Glint.K_MIN, Glint.K_MAX)
    }

    companion object {
        /** Midpoints per cycle in [sineGain]'s integral: 51 per cycle even at k = 40. */
        private const val SINE_GAIN_STEPS = 2048

        /** [macros] already merged over [Glint.defaults]; [f0] the note actually rendered. */
        fun of(voice: GlintVoice, macros: Map<String, Float>, f0: Float): GlintPath {
            val m = Glint.defaults(voice) + macros
            val s = Glint.bloomSign(m.getValue("BLOOM"))
            // VOWEL has no FOLLOW and no ratio until it knows the note: its path
            // is a position on the vowel line, and f0 turns it into ratios at
            // each read.
            if (voice == GlintVoice.VOWEL) {
                val pos = Glint.vowelPosition(m.getValue("PEAK"))
                return GlintPath(
                    voice = voice, kBase = 0f, kStart = 0f, k2 = 0f,
                    level2 = Glint.VOWEL_LEVEL2, ladder = null, bloom = s,
                    f0 = f0, vowelPos = pos,
                    vowelStart = (pos + s * Glint.VOWEL_LAST).coerceIn(0f, Glint.VOWEL_LAST),
                    scale = 2f.pow(0.5f * (m.getValue("BODY") - 0.5f)),
                )
            }
            val kBase = Glint.ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
            val kStart = Glint.startRatio(kBase, s)
            return GlintPath(
                voice = voice,
                kBase = kBase,
                kStart = kStart,
                k2 = Glint.bodyRatio(kBase),
                level2 = m.getValue("BODY") * Glint.BODY_MIX,
                ladder = if (voice == GlintVoice.STEP) Glint.stepLadder(kBase, kStart) else null,
                bloom = s,
                f0 = 0f, vowelPos = 0f, vowelStart = 0f, scale = 1f,
            )
        }
    }
}
