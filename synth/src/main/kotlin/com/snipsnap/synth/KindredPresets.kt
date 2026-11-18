package com.snipsnap.synth

/**
 * KINDRED's factory bank: the fifty-six one-shots of the four approved genre kits as patches.
 *
 * The engine stayed a prototype until the owner heard its kits from the app. On 2026-11-18 they answered the K2
 * listening page and the hardware check with "Approved, approved", so the roster is those pads and nothing reasoned
 * from the knobs: each pad's voice, kit recipe and macros, struck full, as the kit grid's first take (engine take 0).
 * A preset renders through the engine's own output stage; the kit's pad is the same strike through the kit's level
 * law.
 *
 * Each kit is fourteen one-shots, then a phrase and a riser. The two phrases are many strikes of one voice, not a
 * pad's recipe, so they are not presets.
 *
 * Named "<KIT> <PAD>" in capitals, as SERVO's are (TECHNO KICK, INDUSTRIAL HAMMER), so a sound says which kit it came
 * from. Every one of the fifteen voices plays at least one pad.
 */
object KindredPresets {
    /** Paste complete UserPresets roster lines here; the approved kits stay fixed. */
    private val promoted: List<KindredPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    fun forVoice(voice: KindredVoice): List<KindredPatch> = all().filter { it.voice == voice }

    fun all(): List<KindredPatch> = KindredKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** The kit's tag in a preset name: its recipe prefix (TECHNO, INDUSTRIAL, ELECTRO, AMBIENT). */
    fun tag(kit: KindredKits.Kit): String = kit.spec.name

    fun kitPatches(kit: KindredKits.Kit): List<KindredPatch> = KindredKits.recipes(kit).map { r ->
        KindredPatch.of(r, velocity = 1f, take = TAKE).copy(name = "${tag(kit)} ${r.name}".uppercase())
    }
}
