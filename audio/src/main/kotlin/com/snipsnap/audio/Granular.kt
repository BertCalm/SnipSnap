package com.snipsnap.audio

/**
 * The grain engine — the Sculptor's one honest primitive. A seeded
 * scheduler scatters short Hann-windowed grains read from anywhere in
 * a source sound: where they read from ([Params.positionStart], fixed
 * or crawling to [Params.positionEnd]), how big and how many
 * ([Params.sizeSec], [Params.density]), how far each may wander
 * ([Params.jitter]), how far each may detune
 * ([Params.pitchSpreadSemis], a resampled read per grain), and how
 * wide they sit in stereo ([Params.spray], equal-power).
 *
 * Everything random is drawn from one seed in schedule order, so the
 * same seed renders the same bytes — a texture is a recipe, not an
 * accident. The output is normalized to the source's own peak: a
 * cloud of a quiet sound is a quiet cloud.
 */
object Granular {

    const val MIN_GRAIN_SEC = 0.01f
    const val MAX_GRAIN_SEC = 0.5f
    const val MAX_DENSITY = 200f
    const val MAX_SECONDS = 120f

    data class Params(
        /** Grain length in seconds. */
        val sizeSec: Float = 0.08f,
        /** Grains per second (mean — onsets are jittered ±50%). */
        val density: Float = 30f,
        /** Where grains read from, 0..1 through the source. */
        val positionStart: Float = 0.35f,
        /** Crawl target; null holds [positionStart] — a cloud, not a scrub. */
        val positionEnd: Float? = null,
        /** Each grain wanders up to this fraction of the source from its position. */
        val jitter: Float = 0.05f,
        /** Each grain detunes uniformly within ± this many semitones. */
        val pitchSpreadSemis: Float = 0f,
        /** Stereo width, 0 (mono center) .. 1 (full field), equal-power. */
        val spray: Float = 0.5f,
    )

    /** [seconds] of stereo texture grown from [source], deterministic per [seed]. */
    fun render(source: Snip, seconds: Float, seed: Long, params: Params = Params()): Snip {
        require(seconds > 0f && seconds <= MAX_SECONDS) { "seconds wants (0, $MAX_SECONDS], got $seconds" }
        require(params.sizeSec in MIN_GRAIN_SEC..MAX_GRAIN_SEC) { "grain size wants $MIN_GRAIN_SEC..$MAX_GRAIN_SEC s, got ${params.sizeSec}" }
        require(params.density > 0f && params.density <= MAX_DENSITY) { "density wants (0, $MAX_DENSITY] grains/s, got ${params.density}" }
        require(params.positionStart in 0f..1f) { "position wants 0..1, got ${params.positionStart}" }
        params.positionEnd?.let { require(it in 0f..1f) { "position end wants 0..1, got $it" } }
        require(params.jitter in 0f..1f) { "jitter wants 0..1, got ${params.jitter}" }
        require(params.spray in 0f..1f) { "spray wants 0..1, got ${params.spray}" }
        require(source.frameCount > 0) { "the source is empty" }

        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        val rate = mono.sampleRate
        val src = mono.samples
        val outFrames = (seconds * rate).toInt()
        val out = FloatArray(outFrames * 2)
        val rng = java.util.Random(seed)
        val grainFrames = (params.sizeSec * rate).toInt().coerceAtLeast(2)
        val endPos = params.positionEnd ?: params.positionStart

        var t = 0.0
        while (t < seconds) {
            val onset = (t * rate).toInt()
            val u = (t / seconds).toFloat()
            val center = params.positionStart + (endPos - params.positionStart) * u
            val pos = (center + (rng.nextFloat() * 2f - 1f) * params.jitter).coerceIn(0f, 1f) *
                (src.size - 1).toFloat()
            val ratio = Math.pow(2.0, (rng.nextFloat() * 2.0 - 1.0) * params.pitchSpreadSemis / 12.0)
            val pan = 0.5f + (rng.nextFloat() - 0.5f) * params.spray
            val gl = Math.cos(pan * Math.PI / 2.0).toFloat()
            val gr = Math.sin(pan * Math.PI / 2.0).toFloat()
            for (i in 0 until grainFrames) {
                val f = onset + i
                if (f >= outFrames) break
                val at = pos + i * ratio
                val i0 = at.toInt()
                if (i0 >= src.size - 1) break
                val frac = (at - i0).toFloat()
                val s = src[i0] + (src[i0 + 1] - src[i0]) * frac
                val w = (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainFrames)).toFloat()
                out[f * 2] += s * w * gl
                out[f * 2 + 1] += s * w * gr
            }
            t += (1.0 / params.density) * (0.5 + rng.nextFloat())
        }

        // The texture sits at the source's own level — grains stack, so
        // the raw sum can be anything; the peak is brought home.
        var srcPeak = 0f
        for (v in src) {
            val a = Math.abs(v)
            if (a > srcPeak) srcPeak = a
        }
        var outPeak = 0f
        for (v in out) {
            val a = Math.abs(v)
            if (a > outPeak) outPeak = a
        }
        if (outPeak > 0f && srcPeak > 0f) {
            val g = srcPeak / outPeak
            for (i in out.indices) out[i] *= g
        }
        return Snip(out, 2, rate)
    }
}
