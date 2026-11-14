package com.snipsnap.synth

/**
 * SCUFF's factory bank: the twenty-eight hits of the two accepted kits as patches.
 *
 * The engine stayed rosterless until the owner heard it. On 2026-11-13 they heard both kits (SCUFF DETROIT and
 * SCUFF FLINT, the listening page's version 5, every pad cut from the exported MPC 3 tracks) and accepted them,
 * so the roster is those pads and nothing reasoned from the knobs: each hit at the kit's own chain setting under
 * the pad's own macros, struck full, as the first take of the pad (engine take 1, the kit grid's take 0). A
 * preset renders through the engine's own finish; the kit's pad is the same strike through the kit's level law.
 * Named "<kit> <hit>", so a player sees which kit a sound came from.
 */
object ScuffPresets {
    /** Paste complete UserPresets roster lines here; the accepted kits stay fixed. */
    private val promoted: List<ScuffPatch> = listOf()

    /** The take a preset is filed at: the first dealt take, which is the kit's first grid take. */
    const val TAKE = 1

    fun forVoice(voice: ScuffVoice): List<ScuffPatch> = all().filter { it.voice == voice }

    fun all(): List<ScuffPatch> = ScuffKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** [kit]'s fourteen hits as patches, in pad order, with the macros the kit itself passes to the engine. */
    fun kitPatches(kit: ScuffKits.Kit): List<ScuffPatch> = ScuffKits.parts(kit).map { part ->
        ScuffPatch(
            "${kit.title} ${part.name}".uppercase(), part.voice,
            Scuff.settled(part.voice, kit.chain + part.macros), velocity = 1f, take = TAKE,
        )
    }
}
