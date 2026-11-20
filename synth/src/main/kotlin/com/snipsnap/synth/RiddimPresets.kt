package com.snipsnap.synth

/**
 * RIDDIM's factory bank: the RIDDIM pads of the six accepted kits as patches.
 *
 * The engine stayed rosterless until the owner accepted its kits. On 2026-11-19 they heard the six kits as the app
 * exports them and played them on the phone and the MPC ("Sounds great. Phone/MPC pass."), so the roster is those
 * pads and nothing reasoned from the knobs: each voice at the pad's own macros (completed by [Riddim.settled]), struck
 * full, as the pad's first take (engine take 0 is the kit grid's take 0). A preset renders through the engine's own
 * peak match; the kit's pad is the same strike scaled by the kit's level law.
 *
 * - **Only RIDDIM's pads.** A pad that strikes one of CALABASH's voices (the shaker, claves, the tambor, the sabar
 *   family) is a CALABASH sound and opens there; it is not filed here.
 * - **Each recipe once.** Where two kits share a pad at the same settings (Clave Dancehall's and Boom Riddim's open hat
 *   and clap, Clave Dancehall's kick and Dembow's punch kick, Clave Dancehall's and Wood and Bronze's hard-mallet wood
 *   A3), it is filed under the first kit only, so no two presets of a voice are one sound: sixty-five presets from
 *   sixty-nine RIDDIM pads.
 * - **Named "<tag> <pad>"**, the tag a short name for the kit ([tag]), so every name fits a preset list's 24 letters
 *   and says which kit it came from.
 */
object RiddimPresets {
    /** Paste complete UserPresets roster lines here; the accepted kits stay fixed. */
    private val promoted: List<RiddimPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    fun forVoice(voice: RiddimVoice): List<RiddimPatch> = all().filter { it.voice == voice }

    fun all(): List<RiddimPatch> = bank + promoted

    /** The kit's short name in a preset's name: a full title would pass a preset list's 24 letters. */
    fun tag(kit: RiddimKits.Kit): String = when (kit) {
        RiddimKits.Kit.CLAVE_DANCEHALL -> "CLAVE"
        RiddimKits.Kit.DEMBOW -> "DEMBOW"
        RiddimKits.Kit.BOOM_RIDDIM -> "BOOM"
        RiddimKits.Kit.BAILE_FUNK -> "BAILE"
        RiddimKits.Kit.SABAR_SWING -> "SABAR"
        RiddimKits.Kit.WOOD_BRONZE -> "WOOD"
    }

    /** Every RIDDIM pad of [kit] as a patch, in pad order, before a recipe an earlier kit files is dropped. */
    fun kitPatches(kit: RiddimKits.Kit): List<RiddimPatch> = RiddimKits.pads(kit).mapNotNull { pad ->
        val voice = pad.riddim ?: return@mapNotNull null
        RiddimPatch("${tag(kit)} ${pad.name}".uppercase(), voice, Riddim.settled(voice, pad.macros), velocity = 1f, take = TAKE)
    }

    private val bank: List<RiddimPatch> by lazy {
        RiddimKits.Kit.entries.flatMap(::kitPatches).distinctBy { it.voice to it.macros }
    }
}
