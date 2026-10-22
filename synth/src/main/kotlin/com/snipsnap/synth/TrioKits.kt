package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad

/** Four rows of dry, finite model-5 performances. Every pad retains its complete recipe. */
object TrioKits {
    const val NAME = "TRIO Conversations"

    /** A01–A04 roots/solos, A05–A08 delayed replies, A09–A12 exchange, A13–A16 full arrivals. */
    fun patches(): List<TrioPatch> = TrioPresets.all()

    fun classic(): List<ArrangedPad?> = patches().map { patch ->
        val recipe = PadRecipe(patch)
        ArrangedPad(recipe.render(), DrumClass.TONAL, recipe.toJsonValue(),
            source = mapOf("engine" to patch.engine, "voice" to patch.voiceName,
                "preset" to patch.name, "model" to patch.model.toString(), "midi" to patch.midi.toString(),
                "group" to TrioPresets.groupFor(patch).name),
            displayName = patch.name)
    }
}
