package com.snipsnap.synth

/** Native listening references, kept out of the factory roster until audition. */
object HalobarPresets {
    fun forVoice(voice: HalobarVoice): List<HalobarPatch> {
        fun study(name: String,vararg values: Pair<String,Float>) =
            HalobarPatch(name,voice,Halobar.defaults(voice)+values.toMap())
        return listOf(
            study("Close Strike","SPACE" to 0f,"HOLD" to .15f),
            study("Dub Phrase","SPACE" to .68f,"RETURN" to .62f,"MOTION" to .45f),
            study("Dark Answers","RETURN" to .8f,"SPACE" to .55f,"RATE" to .22f),
            study("Bright Steps","TOUCH" to .8f,"RATE" to .58f,"RETURN" to .45f,"SPACE" to .5f),
        )
    }
    fun all(): List<HalobarPatch> = HalobarVoice.entries.flatMap(::forVoice)
}
