package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * BREATHE - GLINT held (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3).
 *
 * The onset plays the voice's own path onto PEAK; then the formant breathes,
 * one breath per loop. The swing handed to [GlintPath.breathRatios] is
 * `BREATHE_SHARE · sin(2π t / loop)` on SWEEP, STEP and VOWEL, and on BRASS it
 * is e(t) of its breathing level (spec §2.2: `BREATHE_SHARE · |s| · sin`). It
 * swings the formant around PEAK by BLOOM's own depth (spec §3) and not between
 * PEAK and the one-shot's start, whose clamp would cut the breath short. Phase
 * distortion is exactly periodic, so N whole cycles in L whole frames close
 * with no seam search: from the first wrap of the loop on, phase, k and level
 * are all functions of the oversampled index modulo `OVERSAMPLE·L`.
 *
 * The render runs at `Dsp.OVERSAMPLE`x and decimates through a filter with
 * memory, so it synthesises three breaths and returns the onset, the first
 * breath and the second: by the second, the filter has forgotten the onset,
 * and the third exists only so the second's tail is filtered by real audio.
 *
 * `GlintBreatheTest`'s `every voice's breath closes` asserts it over four
 * voices × three BLOOMs × both ends of TUNE: the second breath equals the
 * first, sample for sample, from its 64th frame on (`assertEquals(0f, maxDiff)`,
 * so the largest sample difference is 0 and the worst seam it prints is 0,
 * against [Keys.MAX_SEAM_ERROR]'s 1e-3). The loop is a whole number of output
 * frames, each 2:1 decimation stage reads whole source samples, so its taps
 * are the same at every output frame, and a signal that repeats before the
 * filter repeats after it.
 *
 * The loop carries its voice's steady DC, which a one-shot's decay hides, and
 * leaves it as [Glint.synthesize] does: a subtracted constant would step the
 * file's first sample. Measured |mean| / peak over the loop at BLOOM 0.5 and
 * default macros: 0.024 to 0.056 everywhere but VOWEL at TUNE 1, which is
 * 0.108 (0.19 at TUNE 1, PEAK 0, BODY 0).
 *
 * DEPTH (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md) is one value for
 * the whole render, so the loop stays periodic: the rounded window is a pure function of phase, and
 * the sine is a function of phase times the main level, which repeats with the loop (BRASS's
 * breathes). At DEPTH 0 the loop runs its own literal saw lines and is today's, sample for sample.
 */
internal object GlintHeld {
    const val BREATHE_SECONDS = 3f
    const val BREATHE_SHARE = 0.25f
    const val BRASS_REST = 0.5f
    private const val ATTACK_SECONDS = 0.002f
    private const val CANCEL_CHECK_SAMPLES = 4096

    data class BreathPlan(val cycles: Int, val loopFrames: Int, val f0: Double)

    /** N whole cycles in L whole frames at [Dsp.RATE], L as close to [BREATHE_SECONDS] as the note allows. */
    fun breathPlan(f0: Float): BreathPlan {
        val cycles = max(1, (BREATHE_SECONDS * f0).roundToInt())
        val loopFrames = (cycles * Dsp.RATE / f0.toDouble()).roundToInt()
        return BreathPlan(cycles, loopFrames, cycles.toDouble() * Dsp.RATE / loopFrames)
    }

    /** `audio[loopStart until audio.size]` is exactly one breath and repeats seamlessly. */
    class Held(val audio: FloatArray, val loopStart: Int)

    fun render(voice: GlintVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): Held {
        val m = Glint.defaults(voice) + macros
        val plan = breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
        val os = Dsp.OVERSAMPLE
        val rate = Dsp.RATE * os
        val loopOs = plan.loopFrames.toLong() * os
        val n = plan.cycles.toLong()
        val path = GlintPath.of(voice, m, plan.f0.toFloat())
        val depth = abs(path.bloom)
        // `depth` above is BLOOM's own travel. DEPTH the macro is the shape below: null at DEPTH 0, and the
        // `shape == null` branch below is then today's loop body, line for line (see GlintShape).
        val shape = GlintShape.of(m.getValue("DEPTH")) { path.sineGain() }

        // w0: the first phase wrap at or after the onset's end - from here on
        // everything is periodic. i0: the next frame boundary at the output
        // rate, where the file's own loop marker can sit.
        fun cycleOf(i: Long) = i * n / loopOs
        var w0 = ceil(path.onsetSeconds * rate).toLong().coerceAtLeast(1)
        while (cycleOf(w0) == cycleOf(w0 - 1)) w0++
        val i0 = (w0 + os - 1) / os * os
        val total = (i0 + 3 * loopOs).toInt()

        val out = FloatArray(total)
        val k = FloatArray(2)
        var amp = 0f
        var x = 1f
        var rung = -1
        var swing = 0f
        for (i in 0 until total) {
            if (i % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("GLINT held render no longer wanted")
            }
            val attack = (i.toFloat() / rate / ATTACK_SECONDS).coerceAtMost(1f)
            val breathing = i >= w0
            if (breathing) {
                // The breath: a function of (i - i0) mod loop only.
                val b = sin(2.0 * PI * Math.floorMod(i - i0, loopOs) / loopOs).toFloat()
                // BRASS's k follows its own level (spec §2.2): e = (amp - rest) / (1 - rest),
                // which breathRatios turns brighter for s > 0 and darker for s < 0.
                if (voice == GlintVoice.BRASS) {
                    amp = BRASS_REST * (1f + BREATHE_SHARE * depth * b)
                    swing = (amp - BRASS_REST) / (1f - BRASS_REST)   // spec §2.2's e(t), rest = BRASS_REST
                } else {
                    amp = 1f
                    swing = BREATHE_SHARE * b
                }
            } else {
                val t = i.toFloat() / rate
                val fall = Dsp.envAt(t, Glint.BLOOM_T60)
                x = fall
                amp = attack * (if (voice == GlintVoice.BRASS) BRASS_REST + (1f - BRASS_REST) * fall else 1f)
                rung = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
            }
            val phase = ((i.toLong() * n) % loopOs).toDouble() / loopOs
            if (i == 0 || cycleOf(i.toLong()) != cycleOf(i.toLong() - 1)) {
                if (breathing) path.breathRatios(swing, k) else path.ratios(x, rung, k)
            }
            if (shape == null) {
                val w = Glint.windowAt(phase.toFloat())
                val second = if (voice == GlintVoice.VOWEL) amp else attack
                out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                    second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            } else {
                val w = shape.window(phase.toFloat())
                val second = if (voice == GlintVoice.VOWEL) amp else attack
                var v = shape.burstWeight * (
                    amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                        second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
                    )
                // The sine rides the main level: a function of phase times a level that repeats with the
                // loop, so the loop still closes.
                if (shape.sineWeight != 0f) v += shape.sineWeight * amp * sin(2.0 * PI * phase).toFloat()
                out[i] = v
            }
        }

        val down = Dsp.decimate(out, Dsp.RATE)
        val loopStart = (i0 / os).toInt() + plan.loopFrames
        val audio = down.copyOfRange(0, loopStart + plan.loopFrames)
        level(audio, loopStart)
        return Held(audio, loopStart)
    }

    /**
     * One scalar gain for the whole file, fitted so the loop - the sustained
     * sound - sits at the melodic loudness target, as `Keys.resinPad` levels
     * its pads. Fitting the whole file instead lets BRASS's fortepiano accent
     * (twice its loop's level, and the loudest 200 ms in the file) pull its
     * loop about 2 dB under every other voice's. `Dsp.levelTo` applies one gain
     * to what it is given (its ceiling, `limitPeak`, is a uniform rescale), so
     * the least-squares scalar fitted to what it did to the loop recovers
     * exactly the gain it chose: the loudness target, applied alike to every
     * pass of the loop, which keeps the seam. The peak guard runs over the
     * whole file, where the onset can be the loudest part.
     */
    private fun level(audio: FloatArray, loopStart: Int) {
        val loop = audio.copyOfRange(loopStart, audio.size)
        val probe = loop.copyOf()
        Dsp.levelTo(probe, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        var num = 0.0
        var den = 0.0
        for (i in loop.indices) { num += probe[i].toDouble() * loop[i]; den += loop[i].toDouble() * loop[i] }
        if (den <= 0.0) return
        var gain = (num / den).toFloat()
        val peak = audio.maxOf { abs(it) }
        if (peak * gain > 0.99f) gain = 0.99f / peak
        for (i in audio.indices) audio[i] *= gain
    }
}
