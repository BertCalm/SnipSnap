package com.snipsnap.synth

/**
 * Eight listening proposals developed from the approved 81b7bfd audition.
 * These are audition recipes, not entries in the accepted factory preset roster.
 * Every proposal is one dry C3 note with the same touch, seed and 5.2-second press.
 * Each pair has its own color, layer balance and articulation. All still share
 * one note-on and a fixed root; revised Reed sources live in the engine itself.
 */
internal object OsteriaPresetCandidates {
    const val GATE_SECONDS = 5.2

    data class Candidate(val id: String, val description: String, val patch: OsteriaPatch, val revision: Int = 3)

    private fun candidate(
        id: String,
        name: String,
        voice: OsteriaVoice,
        description: String,
        shore: Float,
        tension: Float,
        wander: Float,
        memory: Float,
        shores: List<Double>,
        levels: List<Double>,
        attack: Double,
        release: Double,
        revision: Int = 3,
    ): Candidate {
        val family = Osteria.configurationFor(voice)
        return Candidate(id, description, OsteriaPatch(
            name = name,
            voice = voice,
            macros = Osteria.defaults(voice) + mapOf(
                "SHORE" to shore, "TENSION" to tension, "WANDER" to wander,
                "MEMORY" to memory, "ROOM" to 0f, "HOLD" to 0f,
            ),
            midi = 48,
            velocity = .8f,
            config = family.copy(
                initialShores = shores,
                roleLevels = levels,
                seed = 173,
                seconds = GATE_SECONDS + release,
                attackSeconds = attack,
                releaseSeconds = release,
            ),
        ), revision)
    }

    private val proposals = listOf(
        candidate("anchor-bass", "Anchor Bass", OsteriaVoice.BASS,
            "An immediate, dark, root-led bass with a short release. Its colors stay together and memory is off; compare its compact body with Roaming Bass's slower, more textured onset.",
            shore = 1f, tension = 1f, wander = 0f, memory = 0f,
            shores = List(4) { 4.0 }, levels = listOf(1.0, .08, .04, .04), attack = .008, release = .12),
        candidate("roaming-bass", "Roaming Bass", OsteriaVoice.BASS,
            "A slower opening from a hollow edge into a fuller bass body. Stronger memory retains the earlier color as the upper layers develop; the fundamental stays fixed.",
            shore = .50f, tension = .22f, wander = .75f, memory = .60f,
            shores = List(4) { 2.8 }, levels = listOf(.12, 1.0, .80, .70), attack = .18, release = .30, revision = 4),
        candidate("rounded-pluck", "Rounded Pluck", OsteriaVoice.PLUCK,
            "A rounded, dark pluck with a softened edge and a short damped release. Root-led layers keep the single decaying vibration compact.",
            shore = 1f, tension = .95f, wander = 0f, memory = 0f,
            shores = List(4) { 4.0 }, levels = listOf(1.0, .10, .08, .04), attack = .025, release = .12),
        candidate("bright-pluck", "Bright Pluck", OsteriaVoice.PLUCK,
            "A crisp, bright pluck with a fast edge, stronger upper body and a more open release. One excitation decays naturally; memory is off.",
            shore = .50f, tension = .75f, wander = .30f, memory = 0f,
            shores = List(4) { 2.0 }, levels = listOf(.08, 1.0, .80, .60), attack = .005, release = .35),
        candidate("still-reed", "Still Reed", OsteriaVoice.REED,
            "A compact, hollow reed body with a prompt onset and short release. Breath settles into the held tone, with small pressure-led changes in body and air. Compare Remembered Reed's slower, fuller opening.",
            shore = .25f, tension = .95f, wander = .02f, memory = 0f,
            shores = List(4) { 1.0 }, levels = listOf(1.0, .15, .35, .08), attack = .045, release = .20, revision = 4),
        candidate("remembered-reed", "Remembered Reed", OsteriaVoice.REED,
            "A slower opening from a restrained start into a fuller, rounded reed body. Pressure gently changes body damping and breath color, while wide travel and strong memory carry earlier colors through the held note.",
            shore = .80f, tension = .25f, wander = .90f, memory = .95f,
            shores = listOf(.10, .10, .10, .10), levels = listOf(.68, .52, .66, .18), attack = .18, release = .50, revision = 4),
        candidate("warm-swell", "Warm Swell", OsteriaVoice.PAD,
            "A rounded, close pad that settles sooner into a dark root-led body, then releases compactly. Small excursions and no memory contrast with Memory Drift's slower opening and fuller moving color.",
            shore = 1f, tension = .95f, wander = .02f, memory = 0f,
            shores = List(4) { 4.0 }, levels = listOf(1.0, .18, .10, .04), attack = .28, release = .55),
        candidate("memory-drift", "Memory Drift", OsteriaVoice.PAD,
            "A slower opening into a fuller moving pad, with a long release. Its broad color journey retains earlier color in memory; compare its held body with Warm Swell's darker, compact sustain.",
            shore = .48f, tension = .15f, wander = .75f, memory = .85f,
            shores = listOf(3.90, 3.90, 3.90, 3.90), levels = listOf(.72, .78, .42, .30), attack = .95, release = 1.20),
    )

    fun candidates(): List<Candidate> = proposals

    fun all(): List<OsteriaPatch> = proposals.map { it.patch }
}
