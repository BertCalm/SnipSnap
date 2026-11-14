package com.snipsnap.synth

/** First core-kit voicing pass: existing physical drum controls, no new DSP engine. */
object PatioCore {
    const val VERSION = 1
    val IDS = setOf("kick", "caixa", "closed", "open")
    fun patch(r: PatioStudy.Recipe): Patch {
        require(r.id in IDS)
        val original = requireNotNull(r.patch)
        val changes = when (r.id) {
            // Felt/beater definition and a shorter shell leave the surdo's low tail room.
            "kick" -> mapOf("TUNE" to .42f, "DECAY" to .24f, "TONE" to .65f,
                "CLICK" to .48f, "DROP" to .03f, "PUNCH" to .58f)
            // More head with a longer, papery wire response; avoid a thin hiss.
            "caixa" -> mapOf("TUNE" to .78f, "DECAY" to .35f, "SNAP" to .56f,
                "RATTLE" to .58f, "SIZZLE" to .30f, "PUNCH" to .57f)
            // Matched metal tuning, tighter resonances; controlled open sustain.
            "closed" -> mapOf("TUNE" to .22f, "DECAY" to .30f, "TONE" to .38f,
                "RING" to .82f, "PUNCH" to .58f)
            "open" -> mapOf("TUNE" to .22f, "DECAY" to .28f, "TONE" to .38f,
                "RING" to .82f, "PUNCH" to .54f)
            else -> error("unsupported core pad")
        }
        return original.withMacros(original.macros + changes)
    }
}
