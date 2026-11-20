package com.snipsnap.synth

/**
 * CONSORT's provisional recipes: for each voice, the lead alone and one section it leads. They are the
 * settings of the Python prototype's audition, which the owner heard and approved as a whole
 * (2026-11-19), and most of them are clips of the Kotlin audition the owner approved (2026-11-20);
 * none was chosen one by one. K3 added two to four character recipes a voice, written from the owner's
 * reference recordings (shakuhachi, fujara, bawu, pku) and the techniques the brief asked for (trill,
 * flutter, scoop, fall, overblow); none of those has been heard yet. They stay
 * outside the factory roster ([Presets]) until a listening verdict on the Kotlin render: recipes
 * written without listening are disposable.
 */
object ConsortPresets {

    private fun chair(voice: ConsortVoice, interval: ConsortInterval) = ConsortChair(voice, interval)

    private fun alone(voice: ConsortVoice, name: String, macros: Map<String, Float> = emptyMap()) =
        ConsortPatch(name, voice, Consort.defaults(voice) + macros)

    private fun g(gesture: ConsortGesture) = "GESTURE" to Consort.gestureValue(gesture)

    private fun section(voice: ConsortVoice, name: String, chairs: List<ConsortChair>, macros: Map<String, Float> = emptyMap()) =
        ConsortPatch(name, voice, Consort.defaults(voice) + mapOf("HOLD" to 0.75f) + macros, chairs)

    private val recipes: Map<ConsortVoice, List<ConsortPatch>> = mapOf(
        ConsortVoice.FLUTE to listOf(
            alone(ConsortVoice.FLUTE, "PLAIN FLUTE"),
            section(ConsortVoice.FLUTE, "FLUTE OCTAVES",
                listOf(chair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), chair(ConsortVoice.CLARINET, ConsortInterval.OCT_DOWN))),
            // K3 character recipes (2026-11-20), written from the owner's references and not yet heard
            alone(ConsortVoice.FLUTE, "SHAKUHACHI", mapOf("BREATH" to 0.45f, "MOTION" to 0.75f, "LOOSE" to 0.7f, g(ConsortGesture.SCOOP), "ROOM" to 0.55f, "HOLD" to 0.6f)),
            alone(ConsortVoice.FLUTE, "FUJARA", mapOf("TUNE" to 0f, "BREATH" to 1f, "MOTION" to 0.3f, "LOOSE" to 0.4f, "ROOM" to 0.75f, "HOLD" to 0.8f)),
            alone(ConsortVoice.FLUTE, "FLUTE TRILL", mapOf(g(ConsortGesture.TRILL), "MOTION" to 0.5f, "HOLD" to 0.6f)),
            alone(ConsortVoice.FLUTE, "FLUTTER FLUTE", mapOf(g(ConsortGesture.FLUTTER), "MOTION" to 0.6f, "BREATH" to 0.7f)),
        ),
        ConsortVoice.SAX to listOf(
            alone(ConsortVoice.SAX, "PLAIN SAX"),
            section(ConsortVoice.SAX, "SAX FIFTHS",
                listOf(chair(ConsortVoice.SAX, ConsortInterval.FIFTH), chair(ConsortVoice.SAX, ConsortInterval.OCT_DOWN))),
            section(ConsortVoice.SAX, "MAJOR SEVENTH STACK",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.MAJ7), chair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN), chair(ConsortVoice.FLUTE, ConsortInterval.FIFTH))),
            alone(ConsortVoice.SAX, "SAX FALL", mapOf(g(ConsortGesture.FALL), "MOTION" to 0.6f, "BREATH" to 0.75f)),
            alone(ConsortVoice.SAX, "SAX GROWL", mapOf("BREATH" to 1f, "ROOM" to 0.45f, "LOOSE" to 0.6f)),
        ),
        ConsortVoice.CLARINET to listOf(
            alone(ConsortVoice.CLARINET, "PLAIN CLARINET"),
            section(ConsortVoice.CLARINET, "FULL QUARTET",
                listOf(chair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), chair(ConsortVoice.OBOE, ConsortInterval.FIFTH), chair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN))),
            alone(ConsortVoice.CLARINET, "CLARINET SCOOP", mapOf(g(ConsortGesture.SCOOP), "MOTION" to 0.8f)),
            alone(ConsortVoice.CLARINET, "BAWU", mapOf("BREATH" to 0.4f, "MOTION" to 0.85f, "LOOSE" to 0.65f, "ROOM" to 0.5f)),
        ),
        ConsortVoice.OBOE to listOf(
            alone(ConsortVoice.OBOE, "PLAIN OBOE"),
            section(ConsortVoice.OBOE, "REED TRIO",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.FIFTH), chair(ConsortVoice.BASSOON, ConsortInterval.MIN7))),
            alone(ConsortVoice.OBOE, "OBOE TRILL", mapOf(g(ConsortGesture.TRILL), "MOTION" to 0.45f, "HOLD" to 0.6f)),
            alone(ConsortVoice.OBOE, "PKU", mapOf("BREATH" to 0.9f, "MOTION" to 0.6f, "LOOSE" to 0.7f, "ROOM" to 0.35f)),
        ),
        ConsortVoice.BASSOON to listOf(
            alone(ConsortVoice.BASSOON, "PLAIN BASSOON"),
            section(ConsortVoice.BASSOON, "LOW REEDS",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.OCT_UP), chair(ConsortVoice.BASSOON, ConsortInterval.FIFTH))),
            alone(ConsortVoice.BASSOON, "BASSOON STABS", mapOf("HOLD" to 0.1f, "BREATH" to 0.7f, "LOOSE" to 0.4f)),
            alone(ConsortVoice.BASSOON, "BASSOON FALL", mapOf(g(ConsortGesture.FALL), "MOTION" to 0.5f)),
        ),
        ConsortVoice.DUDUK to listOf(
            alone(ConsortVoice.DUDUK, "PLAIN DUDUK"),
            section(ConsortVoice.DUDUK, "DRONE AND FIFTH",
                listOf(chair(ConsortVoice.DUDUK, ConsortInterval.OCT_DOWN), chair(ConsortVoice.DUDUK, ConsortInterval.FIFTH))),
            alone(ConsortVoice.DUDUK, "DUDUK LAMENT", mapOf("BREATH" to 0.5f, "MOTION" to 0.9f, "LOOSE" to 0.7f, "ROOM" to 0.65f, "HOLD" to 0.8f)),
            alone(ConsortVoice.DUDUK, "DUDUK SCOOP", mapOf(g(ConsortGesture.SCOOP), "MOTION" to 0.5f)),
        ),
    )

    fun forVoice(voice: ConsortVoice): List<ConsortPatch> = recipes.getValue(voice)

    fun all(): List<ConsortPatch> = ConsortVoice.entries.flatMap { forVoice(it) }
}
