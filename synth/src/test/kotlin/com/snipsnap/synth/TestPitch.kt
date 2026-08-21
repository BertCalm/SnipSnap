package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip

/**
 * Thin shim over the promoted [Pitch] detector, keeping the tests' original
 * float-returning shape (0 = nothing detected). The algorithm itself now
 * lives in `:audio` because in-key sampling ships it as product code.
 */
internal object TestPitch {
    fun estimate(snip: Snip, fromSec: Float = 0.08f, windowSec: Float = 0.25f): Float =
        Pitch.detect(snip, fromSec, windowSec)?.hz ?: 0f
}
