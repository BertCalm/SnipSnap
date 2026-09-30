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
 * one breath per loop, `x = BREATHE_SHARE · sin(2π t / loop)` on the same
 * [GlintPath] the one-shot uses. Phase distortion is exactly periodic, so N
 * whole cycles in L whole frames close with no seam search: from the first
 * wrap of the loop on, phase, k and level are all functions of `i mod L`.
 *
 * The render runs at `Dsp.OVERSAMPLE`x and decimates through a filter with
 * memory, so it synthesises three breaths and returns the onset, the first
 * breath and the second: by the second, the filter has forgotten the onset,
 * and the third exists only so the second's tail is filtered by real audio.
 *
 * Measured (2026-09-30, `GlintBreatheTest`'s grid): worst seam 0 across four
 * voices × three BLOOMs × both ends of TUNE, against [Keys.MAX_SEAM_ERROR]'s
 * 1e-3. The second breath matches the first bit for bit from its 64th frame
 * on (largest sample difference 0): the loop is a whole number of output
 * frames, each 2:1 decimation stage reads whole source samples, so its taps
 * are the same at every output frame, and a signal that repeats before the
 * filter repeats after it.
 *
 * The loop carries its voice's steady DC, which a one-shot's decay hides, and
 * leaves it as [Glint.synthesize] does: a subtracted constant would step the
 * file's first sample. Measured |mean| / peak over the loop at BLOOM 0.5 and
 * default macros: 0.024 to 0.056 everywhere but VOWEL at TUNE 1, which is
 * 0.108 (0.19 at TUNE 1, PEAK 0, BODY 0).
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

        // w0: the first phase wrap at or after the onset's end - from here on
        // everything is periodic. i0: the next frame boundary at the output
        // rate, where the file's own loop marker can sit.
        fun cycleOf(i: Long) = i * n / loopOs
        var w0 = ceil(path.onsetSeconds * rate).toLong().coerceAtLeast(1)
        while (cycleOf(w0) == cycleOf(w0 - 1)) w0++
        val i0 = (w0 + os - 1) / os * os
        val total = (i0 + 3 * loopOs).toInt()
        val loopSeconds = loopOs.toDouble() / rate

        val out = FloatArray(total)
        val k = FloatArray(2)
        var amp = 0f
        var x = 1f
        var rung = -1
        for (i in 0 until total) {
            if (i % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("GLINT held render no longer wanted")
            }
            val attack = (i.toFloat() / rate / ATTACK_SECONDS).coerceAtMost(1f)
            if (i >= w0) {
                // The breath: a function of (i - i0) mod loop only.
                val b = sin(2.0 * PI * (i - i0) / rate / loopSeconds).toFloat()
                // BRASS's k follows its own level (spec §2.2): e = (amp - rest) / (1 - rest).
                x = if (voice == GlintVoice.BRASS) BREATHE_SHARE * depth * b else BREATHE_SHARE * b
                amp = if (voice == GlintVoice.BRASS) BRASS_REST * (1f + BREATHE_SHARE * depth * b) else 1f
                rung = -1
            } else {
                val t = i.toFloat() / rate
                val fall = Dsp.envAt(t, Glint.BLOOM_T60)
                x = fall
                amp = attack * (if (voice == GlintVoice.BRASS) BRASS_REST + (1f - BRASS_REST) * fall else 1f)
                rung = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
            }
            val phase = ((i.toLong() * n) % loopOs).toDouble() / loopOs
            if (i == 0 || cycleOf(i.toLong()) != cycleOf(i.toLong() - 1)) path.ratios(x, rung, k)
            val w = Glint.windowAt(phase.toFloat())
            val second = if (voice == GlintVoice.VOWEL) amp else attack
            out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
        }

        val down = Dsp.decimate(out, Dsp.RATE)
        val loopStart = (i0 / os).toInt() + plan.loopFrames
        val audio = down.copyOfRange(0, loopStart + plan.loopFrames)
        level(audio)
        return Held(audio, loopStart)
    }

    /**
     * One scalar gain for the whole file. `Dsp.levelTo` ends in a peak
     * limiter, and a limiter with memory would make the loop's first pass
     * differ from its repeats; a least-squares scalar fitted to what
     * `levelTo` would have done keeps the loudness target and the seam.
     */
    private fun level(audio: FloatArray) {
        val probe = audio.copyOf()
        Dsp.levelTo(probe, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        var num = 0.0
        var den = 0.0
        for (i in audio.indices) { num += probe[i].toDouble() * audio[i]; den += audio[i].toDouble() * audio[i] }
        if (den <= 0.0) return
        var gain = (num / den).toFloat()
        val peak = audio.maxOf { abs(it) }
        if (peak * gain > 0.99f) gain = 0.99f / peak
        for (i in audio.indices) audio[i] *= gain
    }
}
