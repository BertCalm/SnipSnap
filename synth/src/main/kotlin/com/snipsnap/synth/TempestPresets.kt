package com.snipsnap.synth

/** Owner-accepted settings for all six voices, with model-2 and model-3 audio preserved. */
object TempestPresets {
    private fun patch(name: String, voice: TempestVoice, control: String? = null, value: Float = 0f) =
        TempestPatch(name, voice, Tempest.defaults(voice) +
            (control?.let { mapOf(it to value) } ?: emptyMap()), model = 2)

    private fun drum(name: String, voice: TempestVoice, vararg overrides: Pair<String, Float>) =
        TempestPatch(name, voice, Tempest.defaults(voice) + overrides.toMap(), model = 3)

    private val presets = listOf(
        patch("GROUND", TempestVoice.KICK),
        patch("RUST", TempestVoice.KICK, "SOURCE", .5f),
        patch("GRIT", TempestVoice.KICK, "SOURCE", 1f),
        patch("DULL", TempestVoice.KICK, "FILTER", 0f),
        patch("BREAK", TempestVoice.KICK, "DRIVE", 1f),
        patch("AFTER", TempestVoice.KICK, "SPACE", .75f),
        patch("SHARD", TempestVoice.METAL),
        patch("WIRE", TempestVoice.METAL, "SOURCE", 0f),
        patch("GRAIN", TempestVoice.METAL, "SOURCE", 1f),
        patch("LOW", TempestVoice.METAL, "FILTER", 0f),
        patch("BURR", TempestVoice.METAL, "DRIVE", 1f),
        patch("TRAIL", TempestVoice.METAL, "SPACE", .75f),
        drum("SNARE START", TempestVoice.SNARE),
        drum("SNARE CRACK", TempestVoice.SNARE, "SOURCE" to .85f, "DRIVE" to .65f),
        drum("CLAP START", TempestVoice.CLAP),
        drum("CLAP SPREAD", TempestVoice.CLAP, "SOURCE" to .85f, "SPACE" to .50f),
        drum("TOM LOW", TempestVoice.TOM, "TUNE" to .25f),
        drum("TOM MID", TempestVoice.TOM, "TUNE" to .50f),
        drum("TOM HIGH", TempestVoice.TOM, "TUNE" to .75f),
        drum("HAT CLOSED", TempestVoice.HAT, "DECAY" to 0f, "SPACE" to .03f),
        drum("HAT OPEN", TempestVoice.HAT, "DECAY" to .70f, "SPACE" to .08f),
        drum("HAT NOISE", TempestVoice.HAT, "SOURCE" to 1f, "DECAY" to .16f),
    )

    fun forVoice(voice: TempestVoice): List<TempestPatch> = presets.filter { it.voice == voice }
    fun all(): List<TempestPatch> = presets

    /** The sixteen exact positions accepted on the full-kit page, in MPC pad order. */
    fun fullKitPatches(): List<TempestPatch> {
        fun pick(voice: TempestVoice, name: String) = forVoice(voice).single { it.name == name }
        return listOf(pick(TempestVoice.KICK, "GROUND"), pick(TempestVoice.KICK, "BREAK")) +
            forVoice(TempestVoice.SNARE) + forVoice(TempestVoice.CLAP) + forVoice(TempestVoice.TOM) + forVoice(TempestVoice.HAT) +
            listOf("SHARD", "GRAIN", "BURR", "TRAIL").map { pick(TempestVoice.METAL, it) }
    }
}
