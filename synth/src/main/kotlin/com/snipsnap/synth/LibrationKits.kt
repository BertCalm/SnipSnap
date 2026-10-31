package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/** The approved finite bank as sixteen one-shot pads with native soft/hard touch. */
object LibrationKits {
    const val NAME = "SnipSnap Libration Starter"
    const val SOFT_VELOCITY = .35f
    const val HARD_VELOCITY = 1f
    const val PAD_LEVEL = .40f

    fun patches(): List<LibrationPatch> {
        val bank = LibrationPresets.all()
        fun variant(original: String, name: String, midi: Int) =
            bank.single { it.name == original }.copy(name = name, midi = midi)
        return (bank + listOf(
            variant("ROOT WOOD", "F WOOD", 41),
            variant("ROUND KEYS", "EB KEYS", 63),
            variant("GLASS SEED", "HIGH SEED", 75),
            variant("OPEN EXIT", "G EXIT", 67),
        )).map { it.copy(velocity = HARD_VELOCITY) }
    }

    /** Preserve native touch energy; Velocity.variantsAt would peak-match these layers. */
    fun arranged(render: (LibrationPatch) -> Snip = LibrationRenderSession()::render): List<ArrangedPad> = patches().map { patch ->
        ArrangedPad(
            snip = render(patch),
            drumClass = DrumClass.TONAL,
            recipe = PadRecipe(patch = patch).toJsonValue(),
            level = PAD_LEVEL,
            oneShot = true,
            softVariants = listOf(render(patch.copy(velocity = SOFT_VELOCITY))),
            displayName = patch.name,
            colorHex = when (patch.voice) {
                LibrationVoice.GATHER -> "#e5ae79"
                LibrationVoice.TWINS -> "#91cbd1"
                LibrationVoice.DEPARTURE -> "#c7d9a0"
            },
            source = mapOf("engine" to LibrationPatch.ENGINE, "model" to patch.model.toString(),
                "rootMidi" to patch.midi.toString(), "sourceSeconds" to patch.sourceSeconds.toString(),
                // Inline the soft recipe so phone-created kits and backups carry
                // both strikes without referring to the audition's extra sidecar.
                "softRecipe" to PadRecipe(patch = patch.copy(velocity = SOFT_VELOCITY)).toJsonText()),
        )
    }
}
