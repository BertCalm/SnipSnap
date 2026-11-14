package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/**
 * Three candidate genre kits authored from TEMPEST's six accepted voices: CIRCUIT
 * (electro), FOUNDRY (industrial) and STEP (UK garage). Every pad is a new recipe
 * on the existing model-2 (KICK, METAL) and model-3 (SNARE, CLAP, TOM, HAT) voices,
 * or an accepted factory preset unchanged, so no engine file and no accepted render
 * moves. Each kit keeps at most four accepted presets, has its own kick and snare
 * recipes, and chokes every hat in mute group 1, like the accepted [SynthKits.tempest].
 *
 * The settings are shape values for the owner's first listening round
 * (tempest-kits-1); nothing here is accepted audio yet.
 */
object TempestGenreKits {
    enum class Kit(val id: String, val displayName: String) {
        CIRCUIT("tempest-circuit", "TEMPEST CIRCUIT"),
        FOUNDRY("tempest-foundry", "TEMPEST FOUNDRY"),
        STEP("tempest-step", "TEMPEST STEP"),
    }

    private fun pad(name: String, voice: TempestVoice, vararg overrides: Pair<String, Float>) =
        TempestPatch(name, voice, Tempest.defaults(voice) + overrides.toMap(), model = Tempest.defaultModelFor(voice))

    /** An accepted factory preset, byte-identical to the one on the accepted kit. */
    private fun accepted(voice: TempestVoice, name: String) = TempestPresets.forVoice(voice).single { it.name == name }

    private val K = TempestVoice.KICK
    private val S = TempestVoice.SNARE
    private val C = TempestVoice.CLAP
    private val T = TempestVoice.TOM
    private val H = TempestVoice.HAT
    private val M = TempestVoice.METAL

    /** Dry, tight and clap-led, with tuned metal as cowbell- and clave-like blips. */
    private fun circuit(): List<TempestPatch> {
        val bell = arrayOf("SOURCE" to .05f, "FILTER" to .60f, "DRIVE" to .15f, "SPACE" to .05f)
        return listOf(
            pad("CIRCUIT KICK", K, "TUNE" to .19f, "SOURCE" to .05f, "FILTER" to .55f, "DRIVE" to .25f, "SPACE" to .05f),
            pad("CIRCUIT LOW", K, "TUNE" to .14f, "SOURCE" to .15f, "FILTER" to .45f, "DRIVE" to .40f, "SPACE" to .05f),
            pad("CIRCUIT SNAP", S, "TUNE" to .62f, "SOURCE" to .70f, "FILTER" to .80f, "DRIVE" to .20f, "SPACE" to .04f, "DECAY" to .25f),
            pad("CIRCUIT BODY", S, "TUNE" to .48f, "SOURCE" to .35f, "FILTER" to .60f, "DRIVE" to .30f, "SPACE" to .06f, "DECAY" to .45f),
            accepted(C, "CLAP START"),
            pad("CIRCUIT TIGHT", C, "SOURCE" to .15f, "FILTER" to .75f, "SPACE" to .04f, "DECAY" to .25f),
            pad("CIRCUIT WIDE", C, "SOURCE" to .95f, "FILTER" to .60f, "SPACE" to .30f, "DECAY" to .60f),
            pad("CIRCUIT ZAP HI", T, "TUNE" to .80f, "SOURCE" to .80f, "FILTER" to .75f, "DECAY" to .30f),
            pad("CIRCUIT ZAP LO", T, "TUNE" to .55f, "SOURCE" to .80f, "FILTER" to .70f, "DECAY" to .35f),
            accepted(H, "HAT CLOSED"),
            pad("CIRCUIT TICK", H, "TUNE" to .70f, "SOURCE" to .20f, "FILTER" to .85f, "DECAY" to 0f, "SPACE" to 0f),
            accepted(H, "HAT OPEN"),
            pad("CIRCUIT BELL", M, "TUNE" to .72f, *bell),
            pad("CIRCUIT BELL 5", M, "TUNE" to .92f, *bell),
            pad("CIRCUIT CLAVE", M, "TUNE" to .95f, "SOURCE" to .30f, "FILTER" to .90f, "DRIVE" to .05f, "SPACE" to 0f),
            accepted(M, "WIRE"),
        )
    }

    /** Heavy drive, metallic sources and long darkening repeats. */
    private fun foundry(): List<TempestPatch> {
        val pipe = arrayOf("SOURCE" to .90f, "FILTER" to .55f, "DRIVE" to .75f, "SPACE" to .45f, "DECAY" to .60f)
        return listOf(
            pad("FOUNDRY HAMMER", K, "TUNE" to .20f, "SOURCE" to .20f, "FILTER" to .60f, "DRIVE" to 1f, "SPACE" to .20f),
            pad("FOUNDRY SLAB", K, "TUNE" to .18f, "SOURCE" to .55f, "FILTER" to .50f, "DRIVE" to .85f, "SPACE" to .35f),
            pad("FOUNDRY ECHO", K, "TUNE" to .22f, "SOURCE" to .30f, "DRIVE" to .70f, "SPACE" to .85f),
            pad("FOUNDRY CRACK", S, "TUNE" to .50f, "SOURCE" to .80f, "FILTER" to .75f, "DRIVE" to .80f, "SPACE" to .30f, "DECAY" to .40f),
            pad("FOUNDRY PLATE", S, "TUNE" to .40f, "SOURCE" to .95f, "FILTER" to .55f, "DRIVE" to .90f, "SPACE" to .55f, "DECAY" to .55f),
            pad("FOUNDRY SLAM", C, "SOURCE" to .70f, "FILTER" to .50f, "DRIVE" to .85f, "SPACE" to .70f, "DECAY" to .50f),
            pad("FOUNDRY PIPE LO", T, "TUNE" to .20f, *pipe),
            pad("FOUNDRY PIPE MID", T, "TUNE" to .38f, *pipe),
            pad("FOUNDRY PIPE HI", T, "TUNE" to .58f, *pipe),
            accepted(H, "HAT NOISE"),
            pad("FOUNDRY HISS", H, "SOURCE" to 1f, "FILTER" to .55f, "DRIVE" to .60f, "DECAY" to .65f, "SPACE" to .25f),
            accepted(M, "BURR"),
            accepted(M, "TRAIL"),
            pad("FOUNDRY ANVIL", M, "TUNE" to .35f, "SOURCE" to .90f, "FILTER" to .45f, "DRIVE" to .80f, "SPACE" to .40f),
            pad("FOUNDRY CHAIN", M, "TUNE" to .78f, "SOURCE" to 1f, "FILTER" to .75f, "DRIVE" to .65f, "SPACE" to .90f),
            pad("FOUNDRY SPARK", M, "TUNE" to .97f, "SOURCE" to .80f, "FILTER" to 1f, "DRIVE" to .95f, "SPACE" to .15f),
        )
    }

    /** Short and hat-forward for swung patterns: rim-like snares, no toms, five choking hats. */
    private fun step(): List<TempestPatch> {
        val blip = arrayOf("SOURCE" to .10f, "FILTER" to .55f, "DRIVE" to .10f, "SPACE" to 0f)
        return listOf(
            pad("STEP KICK", K, "TUNE" to .25f, "SOURCE" to .08f, "FILTER" to .55f, "DRIVE" to .30f, "SPACE" to 0f),
            pad("STEP SUB", K, "TUNE" to .17f, "SOURCE" to 0f, "FILTER" to .35f, "DRIVE" to .15f, "SPACE" to 0f),
            pad("STEP RIM", S, "TUNE" to .85f, "SOURCE" to .15f, "FILTER" to .85f, "DRIVE" to .35f, "SPACE" to 0f, "DECAY" to 0f),
            pad("STEP SNARE", S, "TUNE" to .58f, "SOURCE" to .60f, "FILTER" to .70f, "DRIVE" to .25f, "SPACE" to .05f, "DECAY" to .30f),
            pad("STEP GHOST", S, "TUNE" to .58f, "SOURCE" to .45f, "FILTER" to .35f, "DRIVE" to 0f, "SPACE" to .03f, "DECAY" to .20f),
            pad("STEP CLAP", C, "SOURCE" to .35f, "FILTER" to .70f, "SPACE" to .06f, "DECAY" to .30f),
            accepted(C, "CLAP SPREAD"),
            pad("STEP CLOSED", H, "TUNE" to .60f, "SOURCE" to .35f, "FILTER" to .80f, "DECAY" to 0f, "SPACE" to 0f),
            pad("STEP PEDAL", H, "TUNE" to .45f, "SOURCE" to .50f, "FILTER" to .60f, "DECAY" to .08f, "SPACE" to 0f),
            pad("STEP SHAKE", H, "SOURCE" to 1f, "FILTER" to .75f, "DECAY" to .12f, "SPACE" to .02f),
            pad("STEP OPEN", H, "TUNE" to .55f, "SOURCE" to .40f, "FILTER" to .75f, "DECAY" to .45f, "SPACE" to .04f),
            accepted(H, "HAT OPEN"),
            pad("STEP BLIP", M, "TUNE" to .66f, *blip),
            pad("STEP BLIP 3", M, "TUNE" to .74f, *blip),
            pad("STEP TINK", M, "TUNE" to .92f, "SOURCE" to .55f, "FILTER" to .85f, "DRIVE" to .20f, "SPACE" to .05f),
            pad("STEP RIDE", M, "TUNE" to .80f, "SOURCE" to .75f, "FILTER" to .80f, "DRIVE" to .25f, "SPACE" to .45f),
        )
    }

    /** The sixteen recipes of [kit], in MPC pad order A01–A16. */
    fun patches(kit: Kit): List<TempestPatch> = when (kit) {
        Kit.CIRCUIT -> circuit()
        Kit.FOUNDRY -> foundry()
        Kit.STEP -> step()
    }

    /** Names of the accepted factory presets [kit] reuses unchanged. */
    fun acceptedNames(kit: Kit): List<String> {
        val factory = TempestPresets.all()
        return patches(kit).filter { it in factory }.map { it.name }
    }

    fun build(kit: Kit): List<ArrangedPad?> = patches(kit).map { patch ->
        val recipe = PadRecipe(patch)
        val snip = recipe.render()
        val status = if (patch in TempestPresets.all()) "accepted" else "candidate"
        ArrangedPad(snip, Tempest.drumClassFor(snip), recipe.toJsonValue(), displayName = patch.name,
            source = mapOf("role" to patch.voice.name, "model" to patch.model.toString(),
                "status" to status, "kit" to kit.displayName) +
                (if (status == "accepted") mapOf("factoryPreset" to patch.name) else emptyMap()),
            muteGroup = if (patch.voice == TempestVoice.HAT) 1 else 0)
    }
}
