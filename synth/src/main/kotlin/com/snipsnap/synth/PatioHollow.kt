package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** One-pad revision: lower hollow bell with a finite, damped resonant tail. */
object PatioHollow {
    const val VERSION = 1
    const val NAME = "SnipSnap Patio Hollow"
    fun dry(r: PatioStudy.Recipe): Snip = if (r.id == "agogo-low")
        PatioArticulation.source(r.id, r.seed, pitchHz = 490f, hollowRevision = 2)
        else PatioArticulation.dry(r)
    fun render(r: PatioStudy.Recipe): Snip = if (r.id == "agogo-low")
        PatioCleanup.process(dry(r), r.seed) else PatioArticulation.render(r)
}
