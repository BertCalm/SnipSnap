package com.snipsnap.synth

/**
 * Twelve original dry proposals for the first listen, covering each musical role.
 * These settings and names await the owner's sonic review before roster expansion.
 */
object MurmurPresets {
    private fun p(voice: MurmurVoice, name: String, vararg macros: Pair<String, Float>) =
        MurmurPatch(name, voice, Murmur.defaults(voice) + macros.toMap())

    private val presets = listOf(
        p(MurmurVoice.BASS, "CLOSED CIRCUIT").copy(midi = 48),
        p(MurmurVoice.BASS, "ROUND HUM", "MOUTH" to .18f, "EDGE" to .19f,
            "BREATH" to .16f, "MOTION" to .44f, "BODY" to .88f).copy(midi = 48),
        p(MurmurVoice.KEY, "TINE WHISPER"),
        p(MurmurVoice.KEY, "DUST VOWEL", "MOUTH" to .67f, "EDGE" to .28f,
            "BREATH" to .34f, "MOTION" to .48f, "BODY" to .39f),
        p(MurmurVoice.PLUCK, "SHORT ANSWER"),
        p(MurmurVoice.PLUCK, "POCKET VOICE", "MOUTH" to .62f, "EDGE" to .32f,
            "BREATH" to .25f, "MOTION" to .61f, "BODY" to .61f),
        p(MurmurVoice.LEAD, "OPEN THROAT"),
        p(MurmurVoice.LEAD, "NARROW CALL", "MOUTH" to .85f, "EDGE" to .63f,
            "BREATH" to .28f, "MOTION" to .26f, "BODY" to .35f),
        p(MurmurVoice.CHOIR, "SMALL CHOIR"),
        p(MurmurVoice.CHOIR, "HELD HUM", "MOUTH" to .29f, "EDGE" to .25f,
            "BREATH" to .37f, "MOTION" to .43f, "BODY" to .72f, "HOLD" to 1f),
        p(MurmurVoice.METAL, "COPPER HUM"),
        p(MurmurVoice.METAL, "GLASS MOUTH", "MOUTH" to .73f, "EDGE" to .92f,
            "BREATH" to .26f, "MOTION" to .63f, "BODY" to .24f),
    )

    fun forVoice(voice: MurmurVoice): List<MurmurPatch> = presets.filter { it.voice == voice }

    fun all(): List<MurmurPatch> = presets
}
