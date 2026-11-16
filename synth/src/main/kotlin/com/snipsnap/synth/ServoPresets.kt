package com.snipsnap.synth

/**
 * SERVO's factory bank: the forty-two hits of the three approved genre kits as patches.
 *
 * The engine stayed rosterless until the owner heard it. On 2026-11-15 they approved round 1b's kits ("Sound
 * approved, phone and MPC good, merge"), so the roster is those pads and nothing reasoned from the knobs: each voice
 * at the pad's own macros, struck full, as the pad's first take (engine take 0 is the kit grid's take 0). A preset
 * renders through the engine's own peak match; the kit's pad is the same strike through the kit's level law.
 *
 * Each kit is fourteen hits, then a roll and a riser. The two phrases are many strikes on one shared rail, not a
 * voice, so they are not presets. The parts that only the phrases play stay out too.
 *
 * Named "<kit tag> <pad>", the tag being the kit's starter id without "servo-" (ELECTRO, JIT, NEURO), so a sound
 * says which kit it came from. Every name fits a preset list's 24 letters as it stands.
 *
 * Nine of the ten voices are on a pad. CRASH plays only inside the phrases, so it has no preset and the picker
 * offers it at its defaults.
 */
object ServoPresets {
    /** Paste complete UserPresets roster lines here; the approved kits stay fixed. */
    private val promoted: List<ServoPatch> = listOf()

    /** The take a preset is filed at: the kit grid's first take, which is the engine's take 0. */
    const val TAKE = 0

    fun forVoice(voice: ServoVoice): List<ServoPatch> = all().filter { it.voice == voice }

    fun all(): List<ServoPatch> = ServoKits.Kit.entries.flatMap(::kitPatches) + promoted

    /** The tag a kit's presets are named with: its starter id without the engine's prefix. */
    fun tag(kit: ServoKits.Kit): String = kit.id.removePrefix("servo-").uppercase()

    fun kitPatches(kit: ServoKits.Kit): List<ServoPatch> = ServoKits.parts(kit).take(ServoKits.HITS).map { part ->
        ServoPatch(
            "${tag(kit)} ${part.name}".uppercase(), part.voice,
            Servo.settled(part.voice, part.macros), velocity = 1f, take = TAKE,
        )
    }
}
