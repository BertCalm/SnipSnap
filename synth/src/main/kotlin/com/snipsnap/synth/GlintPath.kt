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
     * `out[1]` (second burst). STEP reads `ladder[rung]` when [rung] ≥ 0 and
     * otherwise rounds the continuous path to a whole harmonic — the held
     * breath's case, which has no rung clock. VOWEL ignores [rung]: [x] walks
     * the vowel line from BLOOM's start to PEAK's vowel, and both bursts are
     * that vowel's F1 and F2 in Hz over the note.
     */
    fun ratios(x: Float, rung: Int, out: FloatArray) {
        if (voice == GlintVoice.VOWEL) {
            // Formants are Hz, not harmonics: no snap, and a formant below the
            // note pins to the fundamental instead of vanishing.
            Glint.vowelAt(vowelPos + (vowelStart - vowelPos) * x, out)
            out[0] = (out[0] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
            out[1] = (out[1] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
            return
        }
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
