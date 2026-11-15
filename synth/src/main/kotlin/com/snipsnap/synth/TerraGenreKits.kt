package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.kit.ArrangedPad

/**
 * Three genre kits from TERRA's voices, beside the kept classic kit ([TerraKits.classic]): SAHEL (West
 * African, djembe-led, a pentatonic balafon), MAQAM (darbuka-led) and PUNJAB (dholak- and tabla-led). Every pad is a
 * new recipe on an existing voice, with every macro written out ([Terra.defaults] plus the pad's own), so no engine
 * file moves and no kept render changes. No recipe is shared between two kits or with the classic kit.
 *
 * Each pad carries two softer strikes, as the classic kit's do, and its class is what the [Classifier] hears.
 * The owner kept all three, pads and grooves, in listening round terra-presets-1 (X1–X3, Y1–Y3, 2026-11-15);
 * `TerraGenreKitsTest` pins every pad and softer strike (docs/TERRA_PROMOTION.md).
 */
object TerraGenreKits {
    enum class Kit(val id: String, val displayName: String) {
        SAHEL("terra-sahel", "TERRA SAHEL"),
        MAQAM("terra-maqam", "TERRA MAQAM"),
        PUNJAB("terra-punjab", "TERRA PUNJAB"),
    }

    private fun pad(name: String, voice: TerraVoice, vararg overrides: Pair<String, Float>) =
        TerraPatch(name, voice, Terra.defaults(voice) + overrides.toMap())

    private val CAVITY = TerraVoice.RESONANT_CAVITY
    private val BELL = TerraVoice.CONICAL_BELL
    private val BAR = TerraVoice.TUNED_BAR
    private val BOX = TerraVoice.BOX
    private val SLAP = TerraVoice.SLAP
    private val DAYAN = TerraVoice.DAYAN
    private val DARBUKA = TerraVoice.DARBUKA
    private val DOUM = TerraVoice.DOUM
    private val DJEMBE = TerraVoice.DJEMBE
    private val DHOLAK = TerraVoice.DHOLAK
    private val BAYAN = TerraVoice.BAYAN
    private val TUN = TerraVoice.TUN

    /**
     * A balafon key at [hz]: TUNED_BAR's TUNE spans 180 to 400 Hz exponentially (`Terra.tunedBar`), so the key's
     * name is its fundamental, measured in `TerraGenreKitsTest`.
     */
    private fun key(name: String, hz: Double, vararg overrides: Pair<String, Float>) =
        pad(name, BAR, "TUNE" to (kotlin.math.ln(hz / 180.0) / kotlin.math.ln(400.0 / 180.0)).toFloat(), *overrides)

    /** West African: a deep clay pot, djembe bass, tones and slaps, two bells, and a G major pentatonic balafon. */
    private fun sahel(): List<TerraPatch> {
        val buzz = arrayOf("DECAY" to .6f, "FORCE" to .75f, "POS" to .25f, "BUZZ" to .7f)
        return listOf(
            pad("Udu Deep", CAVITY, "TUNE" to .07f, "DECAY" to .6f, "FORCE" to .15f, "DROOP" to .1f, "CAVITY" to .9f),
            pad("Djembe Boom", DJEMBE, "TUNE" to .45f, "DECAY" to .55f, "THUMP" to .8f),
            pad("Djembe Low", DJEMBE, "TUNE" to .25f, "THUMP" to .6f),
            pad("Djembe Tone", DJEMBE, "TUNE" to .55f, "DECAY" to .45f, "STROKE" to 1f),
            pad("Djembe Tone Hi", DJEMBE, "TUNE" to .75f, "DECAY" to .45f, "STROKE" to 1f),
            pad("Djembe Crack", SLAP, "TUNE" to .58f, "DECAY" to .35f, "FORCE" to 1f, "SMACK" to .8f),
            pad("Slap Light", SLAP, "TUNE" to .55f, "FORCE" to .55f, "SMACK" to .35f),
            pad("Djembe Muffle", DJEMBE, "TUNE" to .55f, "DECAY" to .2f, "FORCE" to .4f, "STROKE" to 1f),
            pad("Bell Low", BELL, "TUNE" to .2508f, "DECAY" to .4f, "FORCE" to .9f),
            pad("Bell High", BELL, "TUNE" to .4308f, "DECAY" to .4f, "FORCE" to .9f),
            pad("Bell Clack", BELL, "TUNE" to .3f, "DECAY" to .3f, "FORCE" to .95f, "CLACK" to .5f),
            key("Balafon G3", 196.00, *buzz),
            key("Balafon A3", 220.00, *buzz),
            key("Balafon B3", 246.94, *buzz),
            key("Balafon D4", 293.66, *buzz),
            key("Balafon E4", 329.63, *buzz),
        )
    }

    /** Darbuka-led: doums, teks and kas across three heads, a clay pot's drop, and two bell accents. */
    private fun maqam(): List<TerraPatch> = listOf(
        pad("Doum Low", DOUM, "TUNE" to .35f, "DECAY" to .55f, "FORCE" to .5f, "AIR" to .8f, "SHELL" to .4f),
        pad("Doum Open", DOUM, "TUNE" to .45f, "DECAY" to .7f, "FORCE" to .45f, "AIR" to .6f, "SHELL" to .6f),
        pad("Doum Muted", DOUM, "TUNE" to .45f, "DECAY" to .15f, "FORCE" to .5f, "AIR" to .4f, "SHELL" to .3f),
        pad("Doum Heavy", DOUM, "TUNE" to .28f, "DECAY" to .4f, "FORCE" to .8f, "AIR" to .7f, "SHELL" to .5f),
        pad("Tek", DARBUKA, "TUNE" to .5f, "DECAY" to .45f, "FORCE" to .95f, "POS" to .9f, "SHELL" to .4f, "SNAP" to .6f),
        pad("Tek Hard", DARBUKA, "TUNE" to .55f, "DECAY" to .35f, "FORCE" to 1f, "POS" to .9f, "SHELL" to .4f, "SNAP" to .8f),
        pad("Ka", DARBUKA, "TUNE" to .5f, "DECAY" to .45f, "FORCE" to .55f, "POS" to .8f, "SHELL" to .4f, "SNAP" to .3f),
        pad("Ka Soft", DARBUKA, "TUNE" to .5f, "DECAY" to .45f, "FORCE" to .35f, "POS" to .8f, "SHELL" to .4f, "SNAP" to .2f),
        pad("Tek Ghost", DARBUKA, "TUNE" to .5f, "DECAY" to .4f, "FORCE" to .25f, "POS" to .875f, "SHELL" to .4f, "SNAP" to .25f),
        pad("Tek Ring", DARBUKA, "TUNE" to .45f, "DECAY" to .8f, "FORCE" to .85f, "POS" to .875f, "SHELL" to .9f, "SNAP" to .5f),
        pad("Darbuka Pop", DARBUKA, "TUNE" to .52f, "DECAY" to .25f, "FORCE" to .9f, "POS" to .6f, "SHELL" to .3f, "SNAP" to 1f),
        pad("Darbuka High", DARBUKA, "TUNE" to .8f, "DECAY" to .4f, "FORCE" to .9f, "POS" to .875f, "SHELL" to .5f, "SNAP" to .5f),
        pad("Darbuka Low", DARBUKA, "TUNE" to .2f, "DECAY" to .55f, "FORCE" to .9f, "POS" to .875f, "SHELL" to .5f, "SNAP" to .4f),
        pad("Udu Drop", CAVITY, "TUNE" to .2f, "DECAY" to .5f, "FORCE" to .3f, "DROOP" to .3f, "CAVITY" to .8f),
        pad("Bell Ring", BELL, "TUNE" to .8807f, "DECAY" to .8f, "FORCE" to .95f),
        pad("Bell Tick", BELL, "TUNE" to .95f, "DECAY" to .1f, "FORCE" to .9f, "CLACK" to .9f),
    )

    /** Dholak- and tabla-led: dholak booms and rings, bayan bends, dayan strokes, tuns, a cajón backbeat and bells. */
    private fun punjab(): List<TerraPatch> = listOf(
        pad("Dholak Boom", DHOLAK, "TUNE" to .35f, "DECAY" to .65f, "FORCE" to .7f, "DROP" to .8f, "TREBLE" to .3f),
        pad("Dholak Hi", DHOLAK, "TUNE" to .65f, "FORCE" to .6f),
        pad("Dholak Thud", DHOLAK, "TUNE" to .4f, "DECAY" to .25f, "FORCE" to .7f, "DROP" to 0f, "TREBLE" to .2f),
        pad("Dholak Ring", DHOLAK, "DECAY" to .6f, "DROP" to .3f, "TREBLE" to 1f),
        pad("Bayan Ghe Low", BAYAN, "TUNE" to .2f, "DECAY" to .6f, "FORCE" to .6f, "BEND" to 0f),
        pad("Bayan Swoop", BAYAN, "TUNE" to .6f, "DECAY" to .7f, "FORCE" to .7f, "BEND" to 1f),
        pad("Bayan Ka", BAYAN, "DECAY" to .1f, "FORCE" to .8f, "BEND" to .2f),
        pad("Tabla Na Hi", DAYAN, "TUNE" to .6429f, "CLICK" to .7f, "OPEN" to 0f),
        pad("Tabla Tin Lo", DAYAN, "TUNE" to .3571f, "CLICK" to .55f),
        pad("Tabla Te", DAYAN, "DECAY" to .15f, "FORCE" to .7f, "OPEN" to 0f, "CLICK" to .9f),
        pad("Tun Long", TUN, "DECAY" to .8f, "KNOCK" to .4f),
        pad("Tun Knock", TUN, "FORCE" to .6f, "KNOCK" to 1f, "LAG" to .2f),
        pad("Cajon Snap", BOX, "TUNE" to .55f, "FORCE" to 1f, "POS" to 1f, "BUZZ" to .8f),
        pad("Cajon Thump", BOX, "TUNE" to .3f, "FORCE" to .7f, "POS" to 0f, "CAVITY" to .9f, "BUZZ" to .1f),
        pad("Bell Chime", BELL, "TUNE" to .6f, "DECAY" to .9f, "FORCE" to .7f),
        pad("Bell Clink", BELL, "TUNE" to .9f, "DECAY" to .3f, "FORCE" to .85f, "CLACK" to .4f),
    )

    /** The sixteen recipes of [kit], in MPC pad order A01–A16. */
    fun patches(kit: Kit): List<TerraPatch> = when (kit) {
        Kit.SAHEL -> sahel()
        Kit.MAQAM -> maqam()
        Kit.PUNJAB -> punjab()
    }

    /** The listening round in which the owner kept these kits. */
    const val ROUND = "terra-presets-1"

    fun build(kit: Kit): List<ArrangedPad?> = patches(kit).map { patch ->
        val snip = patch.render()
        ArrangedPad(snip, Classifier.classify(snip).drumClass, PadRecipe(patch).toJsonValue(), displayName = patch.name,
            source = mapOf("voice" to patch.voice.name, "status" to "kept", "round" to ROUND, "kit" to kit.displayName),
            softVariants = Velocity.variantsAt(snip, patch, count = 2))
    }
}
