package com.snipsnap.synth

import java.util.Locale

/** The selected six-family bank; the original Note roster remains a separate saved reference. */
internal object OsteriaSixVoiceCandidates {
    private fun note(id: String, name: String, description: String): OsteriaPresetCandidates.Candidate {
        val original = OsteriaPresets.original().single { it.name == name }
        return OsteriaPresetCandidates.Candidate(
            id = id,
            description = description,
            patch = original.copy(
                midi = 48,
                velocity = .8f,
                config = original.config.copy(
                    seed = 173,
                    seconds = OsteriaPresetCandidates.GATE_SECONDS + original.config.releaseSeconds,
                ),
            ),
            revision = 5,
        )
    }

    private fun bowed(
        id: String,
        name: String,
        description: String,
        shore: Float,
        tension: Float,
        wander: Float,
        memory: Float,
        shores: List<Double>,
        levels: List<Double>,
        attack: Double,
        release: Double,
    ) = OsteriaPresetCandidates.Candidate(
        id = id,
        description = description,
        patch = OsteriaPatch(
            name = name,
            voice = OsteriaVoice.BOWED,
            macros = Osteria.defaults(OsteriaVoice.BOWED) + mapOf(
                "SHORE" to shore, "TENSION" to tension, "WANDER" to wander,
                "MEMORY" to memory, "ROOM" to 0f, "HOLD" to 0f,
            ),
            midi = 48,
            velocity = .8f,
            config = Osteria.configurationFor(OsteriaVoice.BOWED).copy(
                initialShores = shores,
                roleLevels = levels,
                seed = 173,
                seconds = OsteriaPresetCandidates.GATE_SECONDS + release,
                attackSeconds = attack,
                releaseSeconds = release,
            ),
        ),
        revision = 5,
    )

    private val proposals = OsteriaPresetCandidates.candidates() + listOf(
        note("anchored-note", "Anchored Note",
            "The original Note voice held close to its starting colors. Its fixed root and prompt shared onset make a clear reference for Lingering Color's broader remembered movement."),
        note("lingering-color", "Lingering Color",
            "The original Note voice travelling through its four colors, with earlier color retained in memory. One fixed-root press develops and releases without another note-on."),
        bowed("warm-bow", "Warm Bow",
            "A soft catch opens into a dark, root-led string draw. Restrained travel and no memory keep the warm held body close; compare Rosin Bow's firmer, more open texture.",
            shore = 1f, tension = .95f, wander = .02f, memory = 0f,
            shores = List(4) { 4.0 }, levels = listOf(1.0, .30, .20, .08), attack = .20, release = .55),
        bowed("rosin-bow", "Rosin Bow",
            "A firmer catch opens a brighter string draw with more upper body and quiet friction. Travel and memory carry earlier color through the single sustained note before its damped release.",
            shore = .75f, tension = .30f, wander = .72f, memory = .65f,
            shores = List(4) { 3.0 }, levels = listOf(.48, .78, .64, .28), attack = .14, release = .80),
    )

    fun candidates(): List<OsteriaPresetCandidates.Candidate> = proposals

    fun all(): List<OsteriaPatch> = proposals.map { it.patch }

    fun userPresetName(candidate: OsteriaPresetCandidates.Candidate): String = when (candidate.id) {
        "anchored-note" -> "ANCHOR NOTE"
        "remembered-reed" -> "REMEMBER REED"
        "lingering-color" -> "LINGER COLOR"
        else -> candidate.patch.name.uppercase(Locale.ROOT)
    }.also { require(it.length <= 14) }
}
