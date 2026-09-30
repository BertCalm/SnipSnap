package com.snipsnap.synth

import kotlin.math.pow

/**
 * Where GLINT's two bursts sit at one point along a voice's path
 * (docs/superpowers/specs/2026-09-29-glint-paths-design.md §2).
 *
 * Every path is one number, `x`: 1 at the path's start, 0 on PEAK. The
 * one-shot runs `x` down on a clock (SWEEP, VOWEL), on the note's own level
 * (BRASS) or rung by rung (STEP); a held note's breath swings `x` either
 * side of 0 ([GlintHeld]). Two clocks, one path, so the held note and the
 * one-shot can never disagree about where a voice's formant goes.
 *
 * Every ratio this returns is read by its callers only at a phase wrap,
 * where the window is zero and a change is free.
 */
internal class GlintPath private constructor(
    val voice: GlintVoice,
    private val kBase: Float,
    private val kStart: Float,
    private val k2: Float,
    /** The second burst's level: BODY × [Glint.BODY_MIX] on the path voices. */
    val level2: Float,
    /** STEP's rungs, start first, PEAK last; null on every other voice. */
    val ladder: FloatArray?,
    /** The sign value s = 2·BLOOM − 1. */
    val bloom: Float,
) {
    /** How long the one-shot path takes to arrive: the whole ladder on STEP, [Glint.BLOOM_T60] otherwise. */
    val onsetSeconds: Float = ladder?.let { it.size * Glint.STEP_SECONDS } ?: Glint.BLOOM_T60

    /**
     * Writes the burst ratios at path position [x] into `out[0]` (main) and
     * `out[1]` (second burst). STEP reads `ladder[rung]` when [rung] ≥ 0 and
     * otherwise rounds the continuous path to a whole harmonic — the held
     * breath's case, which has no rung clock.
     */
    fun ratios(x: Float, rung: Int, out: FloatArray) {
        val continuous = (kBase * (kStart / kBase).pow(x)).coerceIn(Glint.K_MIN, Glint.K_MAX)
        out[0] = when {
            ladder != null && rung >= 0 -> ladder[rung.coerceAtMost(ladder.lastIndex)]
            // The landing replaces its nearest whole harmonic, as the one-shot
            // ladder's last rung does. (An exact x == 0f test would leave a
            // still pad, where every x maps to kBase, rounded off PEAK.)
            ladder != null -> {
                val whole = Math.round(continuous)
                if (whole == Math.round(kBase)) kBase else whole.toFloat().coerceIn(Glint.K_MIN, Glint.K_MAX)
            }
            else -> continuous
        }
        out[1] = k2
    }

    companion object {
        /** [macros] already merged over [Glint.defaults]; [f0] the note actually rendered. */
        fun of(voice: GlintVoice, macros: Map<String, Float>, f0: Float): GlintPath {
            val m = Glint.defaults(voice) + macros
            val s = Glint.bloomSign(m.getValue("BLOOM"))
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
            )
        }
    }
}
