package com.snipsnap.synth

/** The twelve unchanged native recipes promoted after the owner's audition. */
object HalobarPresets {
    val acceptedVoices = listOf(HalobarVoice.VIBRAPHONE,HalobarVoice.MARIMBA,HalobarVoice.XYLOPHONE)
    /** Paste complete UserPresets roster lines here; the accepted bank stays fixed. */
    private val promoted: List<HalobarPatch> = listOf()

    private fun studies(voice: HalobarVoice): List<HalobarPatch> {
        fun study(name: String,vararg values: Pair<String,Float>) =
            HalobarPatch(name,voice,Halobar.defaults(voice)+values.toMap())
        return listOf(
            study("Close Strike","SPACE" to 0f,"HOLD" to .15f),
            study("Dub Phrase","SPACE" to .68f,"RETURN" to .62f,"MOTION" to .45f),
            study("Dark Answers","RETURN" to .8f,"SPACE" to .55f,"RATE" to .22f),
            study("Bright Steps","TOUCH" to .8f,"RATE" to .58f,"RETURN" to .45f,"SPACE" to .5f),
        )
    }
    fun forVoice(voice: HalobarVoice): List<HalobarPatch> =
        (if(voice in acceptedVoices) studies(voice) else emptyList()) + promoted.filter { it.voice == voice }
    /** Listening references for an unapproved voice are not factory presets. */
    fun auditionFor(voice: HalobarVoice): List<HalobarPatch> = forVoice(voice).ifEmpty { studies(voice) }
    fun all(): List<HalobarPatch> = HalobarVoice.entries.flatMap(::forVoice)
}
