package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * Backroom: a six-pad boom bap study, awaiting listening before factory promotion.
 * SKIN supplies the drums; a shared small room and recording chain tie them together.
 * Constants are audition shapes, not measured gear settings. No new engine or patch format.
 */
object BoomBapKits {
    const val NAME = "SnipSnap Backroom Study"

    val coloration = FxChain(
        crunch = mapOf("BITS" to .08f, "RATE" to .18f, "TONE" to .52f, "GRIT" to .18f),
        tape = mapOf("WOBBLE" to .05f, "DRIVE" to .35f, "AGE" to .55f),
        room = mapOf("SIZE" to .12f, "WALLS" to .30f, "DISTANCE" to .22f),
    )

    data class Part(val id: String, val patch: SkinPatch, val drumClass: DrumClass, val mixGain: Float)

    private fun part(id: String, name: String, preset: String, voice: SkinVoice,
                     drumClass: DrumClass, gain: Float, vararg changes: Pair<String, Float>): Part {
        val source = SkinPresets.forVoice(voice).single { it.name == preset }
        // Save every control so a later default edit cannot silently recolor the study.
        return Part(id, SkinPatch(name, voice, Skin.defaults(voice) + source.macros + changes.toMap()), drumClass, gain)
    }

    fun parts(): List<Part> = listOf(
        part("kick", "Backroom Knock", "TAPE SKIN", SkinVoice.KICK, DrumClass.KICK, .80f,
            "DECAY" to .38f, "CLICK" to .32f, "DROP" to .12f),
        part("snare", "Backroom Paper", "DUSTY RIM", SkinVoice.SNARE, DrumClass.SNARE, .65f,
            "SNAP" to .50f, "DECAY" to .38f, "RATTLE" to .42f, "SIZZLE" to .18f),
        part("closed", "Backroom Tip", "SHUT BRASS", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, .22f,
            "DECAY" to .22f, "RING" to .40f),
        part("open", "Backroom Edge", "LOOSE BRASS", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN, .24f,
            "DECAY" to .28f, "RING" to .40f),
        part("stick", "Backroom Stick", "DRY CLICK", SkinVoice.STICK, DrumClass.PERC, .35f,
            "BODY" to .45f, "CLICK" to .12f),
        part("shaker", "Backroom Seeds", "SOFT SEED", SkinVoice.SHAKER, DrumClass.PERC, .14f,
            "GRAIN" to .45f, "SWELL" to .12f),
    )

    /** Six filled pads plus ten empty slots; the snare's ghost is a velocity layer. */
    fun study(processed: Boolean = true): List<ArrangedPad?> {
        val fx = coloration.takeIf { processed }
        val pads = parts().map { part ->
            val recipe = PadRecipe(part.patch, fx)
            val snip = recipe.render()
            ArrangedPad(snip, part.drumClass, recipe.toJsonValue(),
                softVariants = Velocity.variantsAt(snip, part.patch, fx, count = 2))
        }
        return pads + List(16 - pads.size) { null }
    }
}
