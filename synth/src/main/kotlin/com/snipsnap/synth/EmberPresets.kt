package com.snipsnap.synth

/**
 * EMBER's factory bank: the eighty pads of the five approved kits as patches (round 4).
 *
 * The engine stayed rosterless until the owner heard it. They approved the voices on 2026-11-15 ("I've heard the
 * sound and approve") and all five kits from the app's MPC 3 export on 2026-11-16 ("All sound great"), so the roster
 * is those pads and nothing reasoned from the knobs: each voice at the pad's own macros, struck full, as the pad's
 * first take (engine take 0 is the kit grid's take 0). A preset renders through the engine's own peak match; the
 * kit's pad is the same strike through the kit's finish and level law.
 *
 * Named "<kit tag> <pad>", the tag being the kit's title without "EMBER" (CLUB for the club kit, whose title is
 * the engine's name alone; AFTER HOURS, SOUL JAZZ, FREE, LATIN JAZZ), so a sound says which kit it came from. Four pad
 * names would pass a preset list's 24 letters; they, and the timbale that pairs with one of them, are shortened here
 * ([SHORT]). The kits keep their names.
 *
 * All 35 voices are on a pad, so every voice has at least one preset. The rolls, the swell and the bow are
 * voices here, not phrases, so they are presets too.
 */
object EmberPresets {
    /** Paste complete UserPresets roster lines here; the approved kits stay fixed. */
    private val promoted: List<EmberPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    /**
     * The four pad names that would pass 24 letters with their tag, shortened, and the Latin kit's other timbale: both
     * take the Spanish singular, timbal, so the pair still reads as a pair.
     */
    private val SHORT = mapOf(
        "Soft Cross Stick" to "Soft Cross",
        "Brushed Floor" to "Brush Floor",
        "Floor Tom, Snares On" to "Floor, Snares On",
        "Timbale Macho" to "Timbal Macho",
        "Timbale Hembra" to "Timbal Hembra",
    )

    fun forVoice(voice: EmberVoice): List<EmberPatch> = all().filter { it.voice == voice }

    fun all(): List<EmberPatch> = EmberKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** The tag a kit's presets are named with: its title without the engine's name, or CLUB for the club kit. */
    fun tag(kit: EmberKits.Kit): String = kit.title.removePrefix("EMBER").trim().uppercase().ifEmpty { "CLUB" }

    /** The name a pad is filed under: its tag and its pad name, shortened where [SHORT] says. */
    fun nameFor(kit: EmberKits.Kit, padName: String): String = "${tag(kit)} ${SHORT[padName] ?: padName}".uppercase()

    fun kitPatches(kit: EmberKits.Kit): List<EmberPatch> = EmberKits.pads(kit).map { pad ->
        EmberPatch(nameFor(kit, pad.name), pad.voice, Ember.settled(pad.voice, pad.macros), velocity = 1f, take = TAKE)
    }
}
