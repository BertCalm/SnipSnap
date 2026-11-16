package com.snipsnap.synth

/**
 * CALABASH's factory bank: the sixty-four pads of the four approved genre kits as patches (round 3).
 *
 * The engine stayed rosterless until the owner heard it. On 2026-11-15 they approved round 0 ("I listened: approve
 * all") and then the four kits as the app renders them (round 2's page, "I listened: approve all"), so the roster is
 * those pads and nothing reasoned from the knobs: each voice at the pad's own macros, struck full, as the pad's first
 * take (engine take 0 is the kit grid's take 0). A preset renders through the engine's own peak match; the kit's pad
 * is the same strike scaled by the kit's level law.
 *
 * Named "<kit tag> <pad>", the tag being the kit's starter id without "calabash-" (AFROBEAT, AFROBEATS, MBALAX,
 * AFROCUBAN), so a sound says which kit it came from. One pad name is shortened here to keep every name within a
 * preset list's 24 letters ([SHORT]); the kit itself keeps its pad names.
 *
 * Thirty-five of the thirty-six voices are in a kit. The low agogo bell is in none, so it has no preset and the
 * picker offers it at its defaults.
 */
object CalabashPresets {
    /** Paste complete UserPresets roster lines here; the approved kits stay fixed. */
    private val promoted: List<CalabashPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    /** A preset list shows 24 letters; the one pad name that would pass it with its tag, shortened. */
    private val SHORT = mapOf("Talking Drum Fall" to "Talking Fall")

    fun forVoice(voice: CalabashVoice): List<CalabashPatch> = all().filter { it.voice == voice }

    fun all(): List<CalabashPatch> = CalabashKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** The tag a kit's presets are named with: its starter id without the engine's prefix. */
    fun tag(kit: CalabashKits.Kit): String = kit.id.removePrefix("calabash-").uppercase()

    fun kitPatches(kit: CalabashKits.Kit): List<CalabashPatch> = CalabashKits.pads(kit).map { pad ->
        CalabashPatch(
            "${tag(kit)} ${SHORT[pad.name] ?: pad.name}".uppercase(), pad.voice,
            Calabash.settled(pad.voice, pad.macros), velocity = 1f, take = TAKE,
        )
    }
}
