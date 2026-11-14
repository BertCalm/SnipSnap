package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Supporting-pad voicings over the approved Cleaner chain; existing models only. */
object PatioDetail {
    const val VERSION = 1
    const val NAME = "SnipSnap Patio Detail"
    val IDS = setOf("llamador", "repicador", "agogo-low", "agogo-high")

    fun patch(r: PatioStudy.Recipe): Patch {
        require(r.id in IDS)
        val changes = when (r.id) {
            // A round supporting stroke and a higher, firmer answering stroke.
            "llamador" -> mapOf("DECAY" to .38f, "FORCE" to .42f, "POS" to .24f)
            "repicador" -> mapOf("DECAY" to .46f, "FORCE" to .62f, "POS" to .48f)
            // Give the existing bell modes room to ring, without adding a sharp clack.
            "agogo-low" -> mapOf("DECAY" to .68f, "FORCE" to .58f, "POS" to .28f)
            "agogo-high" -> mapOf("DECAY" to .60f, "FORCE" to .66f, "POS" to .40f)
            else -> error("unsupported detail pad")
        }
        val base = requireNotNull(r.patch)
        return base.withMacros(base.macros + changes)
    }
    fun render(r: PatioStudy.Recipe): Snip = if (r.id in IDS)
        PatioCleanup.process(patch(r).render(), r.seed) else PatioCleanup.render(r)
}
