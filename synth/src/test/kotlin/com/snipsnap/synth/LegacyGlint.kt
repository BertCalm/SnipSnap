package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * GLINT's two render loops exactly as they stood before DEPTH existed (the
 * default branch at b5b8f5ae, 2026-10-01), frozen here so that the edits DEPTH
 * makes to those loops cannot change what they produce at DEPTH 0 unseen.
 * [GlintFrozenReferenceTest] holds the production code to them, sample for
 * sample.
 *
 * What the guard covers: the two render loops, the window they multiply by,
 * and the arithmetic copied around them (frame counts, the held loop's
 * placement and levelling, the order `render` decimates, levels and fades).
 * The window is this file's own `1f - phase`, not [Glint.windowAt], so an edit
 * to `windowAt` is caught as well.
 *
 * What it does not cover: whatever the copy still calls in production, so an
 * edit to any of it moves both sides together and the test stays green.
 * That is [GlintPath.of] and the members of the path the loops read
 * ([GlintPath.ratios], [GlintPath.breathRatios], `level2`, `ladder`,
 * `onsetSeconds`, `bloom`); [Glint.defaults] and [Glint.frequencyFor]; [Dsp]
 * (`Env`, `expMap`, `envAt`, `decimate`, `levelTo`, `fadeTail` and its rate and
 * loudness constants); [GlintHeld.breathPlan]; and the constants
 * [Glint.BODY_DECAY_RATIO], [Glint.BLOOM_T60], [Glint.STEP_SECONDS],
 * [GlintHeld.BRASS_REST] and [GlintHeld.BREATHE_SHARE]. Cancellation is left
 * out: it never changes a sample.
 */
internal object LegacyGlint {
    private const val ATTACK_SECONDS = 0.002f

    private fun window(phase: Float): Float = 1f - phase.coerceIn(0f, 1f)

    /** `Glint.synthesize` as it was. */
    fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = Glint.defaults(voice) + macros
        val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)
        val path = GlintPath.of(voice, m, f0)
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val env2 = if (voice == GlintVoice.VOWEL) {
            amp
        } else {
            Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60 * Glint.BODY_DECAY_RATIO)
        }
        fun x(t: Float): Float = if (voice == GlintVoice.BRASS) amp.at(t) else Dsp.envAt(t, Glint.BLOOM_T60)
        fun rung(t: Float): Int = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
        val k = FloatArray(2)
        path.ratios(1f, rung(0f), k)
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = window(phase.toFloat())
            val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
            val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            out[i] = amp.at(t) * burst + env2.at(t) * second
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                path.ratios(x(t), rung(t), k)
            }
        }
        return out
    }

    /** `Glint.render` as it was: the one-shot at four times oversampling, decimated, levelled, faded. */
    fun render(voice: GlintVoice, macros: Map<String, Float>): FloatArray {
        val raw = synthesize(voice, macros, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    /** `GlintHeld.render` as it was (without its cancellation polling). */
    fun held(voice: GlintVoice, macros: Map<String, Float>): GlintHeld.Held {
        val m = Glint.defaults(voice) + macros
        val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
        val os = Dsp.OVERSAMPLE
        val rate = Dsp.RATE * os
        val loopOs = plan.loopFrames.toLong() * os
        val n = plan.cycles.toLong()
        val path = GlintPath.of(voice, m, plan.f0.toFloat())
        val depth = abs(path.bloom)

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
            val attack = (i.toFloat() / rate / ATTACK_SECONDS).coerceAtMost(1f)
            val breathing = i >= w0
            if (breathing) {
                val b = sin(2.0 * PI * Math.floorMod(i - i0, loopOs) / loopOs).toFloat()
                if (voice == GlintVoice.BRASS) {
                    amp = GlintHeld.BRASS_REST * (1f + GlintHeld.BREATHE_SHARE * depth * b)
                    swing = (amp - GlintHeld.BRASS_REST) / (1f - GlintHeld.BRASS_REST)
                } else {
                    amp = 1f
                    swing = GlintHeld.BREATHE_SHARE * b
                }
            } else {
                val t = i.toFloat() / rate
                val fall = Dsp.envAt(t, Glint.BLOOM_T60)
                x = fall
                amp = attack * (if (voice == GlintVoice.BRASS) GlintHeld.BRASS_REST + (1f - GlintHeld.BRASS_REST) * fall else 1f)
                rung = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
            }
            val phase = ((i.toLong() * n) % loopOs).toDouble() / loopOs
            if (i == 0 || cycleOf(i.toLong()) != cycleOf(i.toLong() - 1)) {
                if (breathing) path.breathRatios(swing, k) else path.ratios(x, rung, k)
            }
            val w = window(phase.toFloat())
            val second = if (voice == GlintVoice.VOWEL) amp else attack
            out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
        }

        val down = Dsp.decimate(out, Dsp.RATE)
        val loopStart = (i0 / os).toInt() + plan.loopFrames
        val audio = down.copyOfRange(0, loopStart + plan.loopFrames)
        level(audio, loopStart)
        return GlintHeld.Held(audio, loopStart)
    }

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
