package com.snipsnap.synth

/**
 * CLUTTER's factory bank: the sixty-four pads of the four accepted genre kits as patches (round 3).
 *
 * The engine stayed rosterless until the owner heard it. On 2026-11-14 they accepted the four kits as authored
 * and approved the audition of them as the app renders them (the listening page's version 8), so the roster is
 * those pads and nothing reasoned from the knobs: each object at the pad's own macros, struck full, as the
 * pad's first take (engine take 0 is the kit grid's take 0). A preset renders through the engine's own peak
 * match; the kit's pad is the same strike through the kit's level law.
 *
 * Named "<kit tag> <pad>", the tag being the kit's starter id without "clutter-" (LOFI, STREET, BOOMBAP, MICRO):
 * the kits' full titles would push most names past what a preset list shows, and the tag still says which kit
 * a sound came from.
 */
object ClutterPresets {
    /** Paste complete UserPresets roster lines here; the accepted kits stay fixed. */
    private val promoted: List<ClutterPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    fun forVoice(voice: ClutterVoice): List<ClutterPatch> = all().filter { it.voice == voice }

    fun all(): List<ClutterPatch> = ClutterKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** The tag a kit's presets are named with: its starter id without the engine's prefix. */
    fun tag(kit: ClutterKits.Kit): String = kit.id.removePrefix("clutter-").uppercase()

    fun kitPatches(kit: ClutterKits.Kit): List<ClutterPatch> = ClutterKits.pads(kit).map { pad ->
        ClutterPatch(
            "${tag(kit)} ${pad.name}".uppercase(), pad.voice,
            Clutter.settled(pad.voice, pad.macros), velocity = 1f, take = TAKE,
        )
    }
}
