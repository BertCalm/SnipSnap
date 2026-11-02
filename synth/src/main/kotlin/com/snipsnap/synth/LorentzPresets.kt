package com.snipsnap.synth

/** Audition-three anchors and control positions. Kotlin output/preset combinations await listening. */
object LorentzPresets {
    /** Paste complete JSON roster lines here; a saved voice stays in its own bank. */
    private val promoted: List<LorentzPatch> = listOf()

    fun forVoice(voice: LorentzVoice): List<LorentzPatch> {
        fun p(name: String, vararg controls: Pair<String, Float>) = LorentzPatch(name, voice, Lorentz.defaults(voice) + controls.toMap())
        val prefix = if (voice == LorentzVoice.FUSE) "Fuse" else "Tear"
        return listOf(
            p("$prefix Contact"),
            p("$prefix Clean", "IMPACT" to 0f),
            p("$prefix Hard", "IMPACT" to 1f),
            p("$prefix Close", "GAP" to 0f),
            p("$prefix Wide", "GAP" to 1f),
            p("$prefix Long", "HOLD" to 1f),
        ) + promoted.filter { it.voice == voice }
    }
    fun all() = LorentzVoice.entries.flatMap(::forVoice)
}
