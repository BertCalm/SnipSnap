package com.snipsnap.synth

/**
 * The seam of a held loop whose wrap rewrites the loop's closing frames.
 *
 * RELAY, LACQUER and CONTOUR cross-fade the end of the exported cycle into the cycle that
 * genuinely preceded it, and over the last 256 frames copy that cycle verbatim. Keys.seamError
 * reads exactly those 256 frames, so after the wrap it is zero whether or not the sound repeats;
 * so is Keys.seamError over two copies of one exported loop. A seam that can fail compares the
 * preceding cycle of the same continuous render with the exported cycle one period later, on
 * frames the wrap did not write.
 */
internal object HeldSeams {
    /**
     * Keys.seamError over the 256 frames that end at [untouched]: [previous] there against
     * [loop] there, one period apart in the render. [untouched] is the first frame the wrap
     * writes, so neither window holds a copied or cross-faded frame.
     */
    fun beforeWrap(previous: FloatArray, loop: FloatArray, untouched: Int): Double {
        require(untouched in 256..minOf(previous.size, loop.size)) {
            "no 256-frame window before the wrap: it starts at $untouched of ${previous.size}/${loop.size}"
        }
        return Keys.seamError(previous.copyOfRange(untouched - 256, untouched) +
            loop.copyOfRange(untouched - 256, untouched), 256)
    }
}
