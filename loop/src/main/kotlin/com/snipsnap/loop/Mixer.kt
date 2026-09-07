package com.snipsnap.loop

import com.snipsnap.audio.Snip
import kotlin.math.min

/**
 * Sums the engaged tracks into one output block.
 *
 * This is the only code that runs on the audio thread, so it does exactly one
 * thing: reads floats and adds them. No decoding, no allocation, no branching
 * on block type — the baker already made every buffer the same shape.
 *
 * Constant-power panning would need two sqrt calls per track per block; a
 * linear law costs two multiplies and is inaudibly different at the small pan
 * amounts a six-track grid actually uses.
 */
object Mixer {

    fun mix(buffers: List<Snip>, tracks: List<Track>, out: FloatArray) {
        require(buffers.size == tracks.size) {
            "got ${buffers.size} buffers for ${tracks.size} tracks"
        }
        out.fill(0f)

        for (t in tracks.indices) {
            val track = tracks[t]
            if (!track.engaged || track.level == 0f) continue

            val src = buffers[t].samples
            val n = min(src.size, out.size)
            val leftGain = track.level * min(1f, 1f - track.pan)
            val rightGain = track.level * min(1f, 1f + track.pan)

            var i = 0
            while (i + 1 < n) {
                out[i] += src[i] * leftGain
                out[i + 1] += src[i + 1] * rightGain
                i += 2
            }
        }
    }
}
