package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * Backroom: a six-pad boom bap study, accepted sonic baseline, awaiting MPC checks before factory promotion.
 * SKIN supplies the drums; a shared small room and recording chain tie them together.
 * Constants are audition shapes, not measured gear settings. No new engine or patch format.
 */
object BoomBapKits {
    const val NAME = "SnipSnap Backroom Snap"

    val coloration = FxChain(
        crunch = mapOf("BITS" to .16f, "RATE" to .29f, "TONE" to .43f, "GRIT" to .34f),
        tape = mapOf("WOBBLE" to .09f, "DRIVE" to .60f, "AGE" to .70f),
        room = mapOf("SIZE" to .14f, "WALLS" to .40f, "DISTANCE" to .28f),
    )

    // Only the snare gets attack shaping and less high-frequency rounding.
    fun colorationFor(id: String): FxChain = if (id == "snare") coloration.copy(
        spike = mapOf("ATTACK" to .42f, "SUSTAIN" to .50f),
        crunch = coloration.crunch!! + ("TONE" to .55f),
        tape = coloration.tape!! + mapOf("DRIVE" to .50f, "AGE" to .60f),
    ) else coloration

    data class Part(val id: String, val patch: SkinPatch, val drumClass: DrumClass, val mixGain: Float)

    private fun part(id: String, name: String, preset: String, voice: SkinVoice,
                     drumClass: DrumClass, gain: Float, vararg changes: Pair<String, Float>): Part {
        val source = SkinPresets.forVoice(voice).single { it.name == preset }
        // Save every control so a later default edit cannot silently recolor the study.
        return Part(id, SkinPatch(name, voice, Skin.defaults(voice) + source.macros + changes.toMap()), drumClass, gain)
    }

    fun parts(): List<Part> = listOf(
        part("kick", "Pushed Knock", "TAPE SKIN", SkinVoice.KICK, DrumClass.KICK, .80f,
            "DECAY" to .32f, "CLICK" to .40f, "DROP" to .20f, "PUNCH" to .70f),
        part("snare", "Snap Paper", "DUSTY RIM", SkinVoice.SNARE, DrumClass.SNARE, .65f,
            "SNAP" to .70f, "DECAY" to .40f, "RATTLE" to .62f, "SIZZLE" to .44f, "PUNCH" to .76f),
        part("closed", "Pushed Tip", "SHUT BRASS", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, .22f,
            "DECAY" to .18f, "RING" to .27f, "TONE" to .40f),
        part("open", "Pushed Edge", "LOOSE BRASS", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN, .24f,
            "DECAY" to .32f, "RING" to .28f, "TONE" to .42f),
        part("stick", "Pushed Stick", "DRY CLICK", SkinVoice.STICK, DrumClass.PERC, .35f,
            "BODY" to .60f, "CLICK" to .22f),
        part("shaker", "Pushed Seeds", "SOFT SEED", SkinVoice.SHAKER, DrumClass.PERC, .14f,
            "GRAIN" to .62f, "SWELL" to .18f),
    )

    /** Six filled pads plus ten empty slots; the snare's ghost is a velocity layer. */
    fun study(processed: Boolean = true): List<ArrangedPad?> {
        val pads = parts().map { part ->
            val fx = colorationFor(part.id).takeIf { processed }
            val recipe = PadRecipe(part.patch, fx)
            val snip = recipe.render()
            ArrangedPad(snip, part.drumClass, recipe.toJsonValue(),
                softVariants = Velocity.variantsAt(snip, part.patch, fx, count = 2))
        }
        return pads + List(16 - pads.size) { null }
    }
}
