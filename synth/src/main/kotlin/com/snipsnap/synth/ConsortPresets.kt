package com.snipsnap.synth

/**
 * CONSORT's provisional recipes: for each voice, the lead alone and one section it leads. They are the
 * settings of the Python prototype's audition, which the owner heard and approved as a whole
 * (2026-11-19); nobody has heard them from this engine, and none was chosen one by one. They stay
 * outside the factory roster ([Presets]) until a listening verdict on the Kotlin render: recipes
 * written without listening are disposable.
 */
object ConsortPresets {

    private fun chair(voice: ConsortVoice, interval: ConsortInterval) = ConsortChair(voice, interval)

    private fun alone(voice: ConsortVoice, name: String, macros: Map<String, Float> = emptyMap()) =
        ConsortPatch(name, voice, Consort.defaults(voice) + macros)

    private fun section(voice: ConsortVoice, name: String, chairs: List<ConsortChair>, macros: Map<String, Float> = emptyMap()) =
        ConsortPatch(name, voice, Consort.defaults(voice) + mapOf("HOLD" to 0.75f) + macros, chairs)

    private val recipes: Map<ConsortVoice, List<ConsortPatch>> = mapOf(
        ConsortVoice.FLUTE to listOf(
            alone(ConsortVoice.FLUTE, "PLAIN FLUTE"),
            section(ConsortVoice.FLUTE, "FLUTE OCTAVES",
                listOf(chair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), chair(ConsortVoice.CLARINET, ConsortInterval.OCT_DOWN))),
        ),
        ConsortVoice.SAX to listOf(
            alone(ConsortVoice.SAX, "PLAIN SAX"),
            section(ConsortVoice.SAX, "SAX FIFTHS",
                listOf(chair(ConsortVoice.SAX, ConsortInterval.FIFTH), chair(ConsortVoice.SAX, ConsortInterval.OCT_DOWN))),
            section(ConsortVoice.SAX, "MAJOR SEVENTH STACK",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.MAJ7), chair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN), chair(ConsortVoice.FLUTE, ConsortInterval.FIFTH))),
        ),
        ConsortVoice.CLARINET to listOf(
            alone(ConsortVoice.CLARINET, "PLAIN CLARINET"),
            section(ConsortVoice.CLARINET, "FULL QUARTET",
                listOf(chair(ConsortVoice.FLUTE, ConsortInterval.OCT_UP), chair(ConsortVoice.OBOE, ConsortInterval.FIFTH), chair(ConsortVoice.BASSOON, ConsortInterval.OCT_DOWN))),
        ),
        ConsortVoice.OBOE to listOf(
            alone(ConsortVoice.OBOE, "PLAIN OBOE"),
            section(ConsortVoice.OBOE, "REED TRIO",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.FIFTH), chair(ConsortVoice.BASSOON, ConsortInterval.MIN7))),
        ),
        ConsortVoice.BASSOON to listOf(
            alone(ConsortVoice.BASSOON, "PLAIN BASSOON"),
            section(ConsortVoice.BASSOON, "LOW REEDS",
                listOf(chair(ConsortVoice.CLARINET, ConsortInterval.OCT_UP), chair(ConsortVoice.BASSOON, ConsortInterval.FIFTH))),
        ),
        ConsortVoice.DUDUK to listOf(
            alone(ConsortVoice.DUDUK, "PLAIN DUDUK"),
            section(ConsortVoice.DUDUK, "DRONE AND FIFTH",
                listOf(chair(ConsortVoice.DUDUK, ConsortInterval.OCT_DOWN), chair(ConsortVoice.DUDUK, ConsortInterval.FIFTH))),
        ),
    )

    fun forVoice(voice: ConsortVoice): List<ConsortPatch> = recipes.getValue(voice)

    fun all(): List<ConsortPatch> = ConsortVoice.entries.flatMap { forVoice(it) }
}
