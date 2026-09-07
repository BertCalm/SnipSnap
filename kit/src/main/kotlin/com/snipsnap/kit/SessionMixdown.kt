package com.snipsnap.kit

import com.snipsnap.audio.Snip
import java.io.File

/**
 * The whole session as one WAV — every kit's groove rendered by
 * [KitPreview] at the session tempo and summed: the demo beside every
 * `.xpj`, and the "share the beat, not just the kit" story. Deterministic
 * like everything else in the render path.
 */
object SessionMixdown {

    /** The shared ceiling with [KitPreview] — a mix must never clip harshly. */
    const val PEAK = 0.95f

    fun render(kitDirs: List<File>, tempoBpm: Float? = null): Snip {
        require(kitDirs.isNotEmpty()) { "a mixdown needs at least one kit" }
        val renders = kitDirs.map { dir ->
            KitPreview.render(KitStore.load(dir), dir, tempoBpm = tempoBpm)
        }
        if (renders.size == 1) return renders[0]

        // Every render is stereo at KitPreview.RATE; the mix runs as long
        // as the longest one.
        val frames = renders.maxOf { it.frameCount }
        val out = FloatArray(frames * 2)
        for (r in renders) {
            for (i in r.samples.indices) out[i] += r.samples[i]
        }
        var peak = 0f
        for (s in out) {
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        if (peak > PEAK) {
            val k = PEAK / peak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, 2, KitPreview.RATE)
    }
}
