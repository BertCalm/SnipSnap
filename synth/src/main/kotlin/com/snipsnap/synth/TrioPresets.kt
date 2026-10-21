package com.snipsnap.synth

enum class TrioPresetGroup { ROOTS, LOW_DRAMA, MID_DRAMA, HIGH_DRAMA }

/** Sixteen dry, finite model-5 recipes in C minor pentatonic, from C3 to C5. */
object TrioPresets {
    private fun c(seed: Int, tempoBpm: Float = 104f, beats: Int = 2, press: Float = .4f,
        tremoloHz: Float = 10f, strumMs: Float = 8f) =
        TrioConfig(seed, tempoBpm, beats, press, tremoloHz, strumMs, model = 5)

    private fun p(voice: TrioVoice, name: String, midi: Int, velocity: Float, config: TrioConfig,
        vararg macros: Pair<String, Float>) = TrioPatch(name, voice,
        Trio.defaults(voice) + mapOf("TOUCH" to .55f, "DRAMA" to 0f, "SIDES" to (1f / 6f),
            "MOTION" to 0f, "SPACE" to .35f, "DAMP" to .48f) + macros.toMap(), midi, velocity, config)

    private val roots = listOf(
        p(TrioVoice.TRES, "Tres Study", 60, .90f, c(47011),
            "SIDES" to 0f, "SPACE" to .25f, "DAMP" to .50f),
        p(TrioVoice.BERIMBAU, "Wire Study", 60, .90f, c(47012, tempoBpm = 96f, press = .60f),
            "TOUCH" to .50f, "DAMP" to .42f),
        p(TrioVoice.CHARANGO, "Charango Study", 72, .85f,
            c(47013, tempoBpm = 112f, tremoloHz = 14f, strumMs = 6f),
            "TOUCH" to .58f, "SPACE" to .25f, "DAMP" to .55f),
        // The accepted wife-only listening anchor, including its original seed and root.
        p(TrioVoice.ENSEMBLE, "Family Root", 60, 1f, c(47010)),
    )

    private val low = listOf(
        p(TrioVoice.ENSEMBLE, "Late Reply", 48, .82f,
            c(47015, tempoBpm = 88f, press = .30f, tremoloHz = 8f, strumMs = 12f),
            "TOUCH" to .42f, "DRAMA" to .25f, "SIDES" to 0f, "SPACE" to .30f, "DAMP" to .40f),
        p(TrioVoice.ENSEMBLE, "Patient Circle", 51, .80f,
            c(47016, tempoBpm = 80f, press = .45f, tremoloHz = 8f, strumMs = 16f),
            "TOUCH" to .35f, "DRAMA" to .18f, "MOTION" to .10f, "SPACE" to .45f, "DAMP" to .35f),
        p(TrioVoice.ENSEMBLE, "Wire Apart", 53, .88f,
            c(47017, tempoBpm = 100f, press = .75f),
            "DRAMA" to .30f, "SIDES" to (1f / 3f), "SPACE" to .28f, "DAMP" to .50f),
        p(TrioVoice.ENSEMBLE, "Slow Turn", 55, .86f,
            c(47018, tempoBpm = 92f, tremoloHz = 9f, strumMs = 11f),
            "TOUCH" to .48f, "DRAMA" to .32f, "SIDES" to (2f / 3f), "MOTION" to .60f,
            "SPACE" to .55f, "DAMP" to .40f),
    )

    private val mid = listOf(
        p(TrioVoice.ENSEMBLE, "Cross Talk", 58, .90f, c(47019),
            "TOUCH" to .58f, "DRAMA" to .50f, "SIDES" to 0f, "DAMP" to .50f),
        p(TrioVoice.ENSEMBLE, "Inner Answer", 60, .87f,
            c(47020, tempoBpm = 120f, beats = 3, press = .55f, tremoloHz = 12f, strumMs = 6f),
            "TOUCH" to .50f, "DRAMA" to .42f, "SIDES" to (1f / 3f), "MOTION" to .15f,
            "SPACE" to .25f, "DAMP" to .55f),
        p(TrioVoice.ENSEMBLE, "Moving Pair", 63, .92f,
            c(47021, tempoBpm = 112f, beats = 3, press = .35f, tremoloHz = 14f),
            "TOUCH" to .62f, "DRAMA" to .58f, "SIDES" to (2f / 3f), "MOTION" to .75f, "SPACE" to .48f),
        p(TrioVoice.ENSEMBLE, "Bright Exchange", 65, .95f,
            c(47022, tempoBpm = 128f, press = .50f, tremoloHz = 16f, strumMs = 4f),
            "TOUCH" to .70f, "DRAMA" to .65f, "MOTION" to .35f, "SPACE" to .30f, "DAMP" to .60f),
    )

    private val high = listOf(
        p(TrioVoice.ENSEMBLE, "Early Guests", 55, .92f,
            c(47023, tempoBpm = 100f, press = .45f, tremoloHz = 11f, strumMs = 7f),
            "TOUCH" to .66f, "DRAMA" to .75f, "SIDES" to 0f, "SPACE" to .40f, "DAMP" to .45f),
        p(TrioVoice.ENSEMBLE, "Close Quarters", 58, .95f,
            c(47024, tempoBpm = 132f, beats = 3, press = .65f, tremoloHz = 15f, strumMs = 5f),
            "TOUCH" to .72f, "DRAMA" to .82f, "SIDES" to (1f / 3f), "MOTION" to .25f,
            "SPACE" to .25f, "DAMP" to .56f),
        p(TrioVoice.ENSEMBLE, "Gathering Heat", 60, 1f,
            c(47025, tempoBpm = 144f, beats = 4, press = .60f, tremoloHz = 18f, strumMs = 4f),
            "TOUCH" to .80f, "DRAMA" to .90f, "SIDES" to (2f / 3f), "MOTION" to .80f,
            "SPACE" to .50f, "DAMP" to .50f),
        // The accepted full entrance; other controls match Family Root exactly.
        p(TrioVoice.ENSEMBLE, "Full Circle", 60, 1f, c(47010), "DRAMA" to 1f),
    )

    fun forGroup(group: TrioPresetGroup): List<TrioPatch> = when (group) {
        TrioPresetGroup.ROOTS -> roots
        TrioPresetGroup.LOW_DRAMA -> low
        TrioPresetGroup.MID_DRAMA -> mid
        TrioPresetGroup.HIGH_DRAMA -> high
    }

    fun all(): List<TrioPatch> = roots + low + mid + high
    fun forVoice(voice: TrioVoice): List<TrioPatch> = all().filter { it.voice == voice }

    fun groupFor(patch: TrioPatch): TrioPresetGroup {
        val drama = patch.macros["DRAMA"] ?: 0f
        return when {
            patch.voice != TrioVoice.ENSEMBLE || drama == 0f -> TrioPresetGroup.ROOTS
            drama <= 1f / 3f -> TrioPresetGroup.LOW_DRAMA
            drama <= 2f / 3f -> TrioPresetGroup.MID_DRAMA
            else -> TrioPresetGroup.HIGH_DRAMA
        }
    }
}
