package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/**
 * A listening proof for the thumb strip: dry hit -> short room -> distinct repeats.
 * Rendered once when creating the starter; note-on only selects cached WAVs.
 * These are deliberately strong audition settings, awaiting a phone listen.
 */
object StrikeDemoKit {
    val LAYER_NAMES = listOf("DRY", "REVERB", "ECHO")

    // Shape choices: about half a second of room, and repeats about 293 ms apart.
    // Each effect receives the same dry source so the middle layer's room never
    // smears the echo's gaps. Their existing feedback and tail bounds apply.
    private val room = FxChain(spring = mapOf("SIZE" to 0.42f, "TONE" to 0.7f, "MIX" to 1f))
    private val echo = FxChain(echo = mapOf("TIME" to 0.9f, "REPEAT" to 0.55f, "TONE" to 0.8f, "MIX" to 0.9f))

    fun build(): List<ArrangedPad> = listOf(
        Triple("RIM", ThumpPatch("Strike Rim", ThumpVoice.RIM, mapOf("DECAY" to 0f)), DrumClass.PERC),
        Triple("CLAP", ThumpPatch("Strike Clap", ThumpVoice.CLAP, mapOf("DECAY" to 0f)), DrumClass.CLAP),
        Triple("SNARE", ThumpPatch("Strike Snare", ThumpVoice.SNARE, mapOf("DECAY" to 0f)), DrumClass.SNARE),
        Triple("BLOCK", TinesPatch("Strike Block", TinesVoice.BLOCK, mapOf("DECAY" to 0f)), DrumClass.PERC),
    ).map { (name, patch, drumClass) ->
        val dry = patch.render()
        val recipe = PadRecipe(patch, echo, terminalFadeMs = 4f)
        ArrangedPad(
            snip = recipe.process(dry),
            drumClass = drumClass,
            recipe = recipe.toJsonValue(),
            softVariants = listOf(dry, PadRecipe(patch, room, terminalFadeMs = 4f).process(dry)),
            displayName = name,
            layerNames = LAYER_NAMES,
        )
    }
}
